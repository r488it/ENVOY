package com.rabi.envoymcp.arm

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import com.rabi.envoymcp.core.RabiLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * AgentARM (UNO Q) との BLE (UNO Q 側は agentarm_d/transports/ble.py)。
 * - 探す → つなぐ → MTU 247 → 知らせ (state: Notify、event: Indicate) を受け取る → hello → 0.5 秒ごとに hb
 * - 命令 (cmd) は応答ありで、観測 (obs) と hb は応答なしで書く。GATT の操作は 1 つずつ (列に並べる)
 * - 切れたら、前につないだ AgentARM に自動接続で戻る (画面が消えていても、裏にいても戻れる)。30 秒で戻れなければ探し直す
 * - 腕からの知らせ (state、10 回/秒) が 5 秒来なければ、つながりが古くなったとみて、つなぎ直す
 *   (UNO Q のプログラムを起動し直すと、Bluetooth はつながったまま中身だけ替わり、送ったものが届かなくなるため)
 * 中身は 1 行の JSON (200 B 以内)。
 * つないだ AgentARM を覚えておき、次に動かすときは探さずに自動接続で待つ (画面が消えていても戻れるように。広告を絞らずに探すのは、画面が消えていると止められる)。
 */
@SuppressLint("MissingPermission")
class ArmLink(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onMessage: (JSONObject) -> Unit,
    /** hello で名乗る名前 */
    private val app: String,
    /** 前につないだ AgentARM のアドレス (無ければ null) と、つないだときに覚える先 */
    private val remembered: () -> String?,
    private val remember: (String) -> Unit,
) {
    sealed class State(val label: String) {
        object Off : State("止めています")
        object NoPermission : State("Bluetooth の許可がありません")
        object BluetoothOff : State("Bluetooth が切れています")
        object Scanning : State("AgentARM を探しています")
        class Connecting(val name: String) : State("つないでいます: $name")
        class Ready(val name: String) : State("つながりました: $name")
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state
    val ready get() = _state.value is State.Ready

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var cmdCh: BluetoothGattCharacteristic? = null
    private var obsCh: BluetoothGattCharacteristic? = null
    private var wanted = false
    private var scanning = false
    private var ops = Channel<Op>(Channel.UNLIMITED)
    @Volatile private var pending: CompletableDeferred<Boolean>? = null
    private val obsBacklog = AtomicInteger(0)
    private var writerJob: Job? = null
    private var hbJob: Job? = null
    private var retryJob: Job? = null
    private var watchJob: Job? = null
    private var hbSeq = 0
    /** 前につないだ AgentARM (切れたら、ここへ自動接続で戻る) */
    private var lastDevice: BluetoothDevice? = null
    private var lastName = "AgentARM"
    @Volatile private var lastRxMs = 0L

    private sealed class Op {
        class Write(val ch: BluetoothGattCharacteristic, val data: ByteArray, val withResponse: Boolean, val isObs: Boolean) : Op()
        class Subscribe(val ch: BluetoothGattCharacteristic, val indicate: Boolean) : Op()
    }

    fun start() {
        wanted = true
        if (gatt == null && !scanning) begin()
    }

    /** 覚えている AgentARM があれば、自動接続で待つ (30 秒で戻れなければ探す)。無ければ探す */
    private fun begin() {
        if (!hasPerms()) { _state.value = State.NoPermission; return }
        val a = adapter
        if (a == null || !a.isEnabled) { _state.value = State.BluetoothOff; retryLater(3000); return }
        if (lastDevice == null) remembered()?.let { addr -> lastDevice = runCatching { a.getRemoteDevice(addr) }.getOrNull() }
        if (lastDevice != null) reconnect() else scan()
    }

    fun stop() {
        wanted = false
        retryJob?.cancel()
        watchJob?.cancel()
        stopScan()
        closeGatt()
        _state.value = State.Off
    }

    /** 命令 (応答あり)。つながっていなければ false */
    fun sendCmd(msg: JSONObject): Boolean = enqueue(cmdCh, msg, withResponse = true, isObs = false)

    /** 観測 (応答なし)。詰まっているときは捨てる (新しいものがすぐ来るので) */
    fun sendObs(msg: JSONObject): Boolean {
        if (obsBacklog.get() > 3) return false
        return enqueue(obsCh, msg, withResponse = false, isObs = true)
    }

    private fun enqueue(ch: BluetoothGattCharacteristic?, msg: JSONObject, withResponse: Boolean, isObs: Boolean): Boolean {
        if (ch == null || !ready) return false
        val data = msg.toString().toByteArray(Charsets.UTF_8)
        if (data.size > MAX_BYTES) { RabiLog.w("ble", "長すぎます (${data.size} B): ${msg.optString("t")}"); return false }
        if (isObs) obsBacklog.incrementAndGet()
        return ops.trySend(Op.Write(ch, data, withResponse, isObs)).isSuccess
    }

    // ---------------------------------------------------------------- 探す
    private fun hasPerms() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun scan() {
        if (!wanted) return
        if (!hasPerms()) { _state.value = State.NoPermission; return }
        val a = adapter
        if (a == null || !a.isEnabled) { _state.value = State.BluetoothOff; retryLater(3000); return }
        val scanner = a.bluetoothLeScanner ?: run { retryLater(3000); return }
        _state.value = State.Scanning
        scanning = true
        // 広告にサービスの UUID が入っていない場合もあるので、絞らずに探して、UUID か名前 (AgentARM-xxxx) で見分ける
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        RabiLog.i("ble", "探し始めました")
        // 見つからないまま長く探し続けない (Android は短い間に何度も探し直すと止める)
        retryJob?.cancel()
        retryJob = scope.launch { delay(20_000); if (scanning) { stopScan(); scan() } }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rec = result.scanRecord
            val name = rec?.deviceName ?: result.device.name ?: ""
            val hasUuid = rec?.serviceUuids?.contains(ParcelUuid(SERVICE)) == true
            if (!hasUuid && !name.startsWith("AgentARM")) return
            if (!scanning) return
            stopScan()
            retryJob?.cancel()
            connect(result.device, name.ifBlank { result.device.address })
        }

        override fun onScanFailed(errorCode: Int) {
            RabiLog.w("ble", "探せません ($errorCode)")
            scanning = false
            retryLater(3000)
        }
    }

    private fun retryLater(ms: Long) {
        retryJob?.cancel()
        retryJob = scope.launch { delay(ms); if (wanted && gatt == null) begin() }
    }

    /** 切れたとき: 前の AgentARM に自動接続で戻る。30 秒で戻れなければ、探し直す */
    private fun reconnect() {
        val d = lastDevice
        if (d == null || !hasPerms()) { retryLater(2000); return }
        connect(d, lastName, auto = true)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(30_000)
            if (wanted && !ready) { RabiLog.w("ble", "戻れないので、探し直します"); closeGatt(); scan() }
        }
    }

    /** つながっているはずなのに、腕から知らせが来ない: 古いつながりを捨てて、つなぎ直す */
    private fun forceReconnect(why: String) {
        RabiLog.w("ble", "$why。つなぎ直します")
        closeGatt()
        _state.value = State.Scanning
        if (wanted) reconnect()
    }

    // ---------------------------------------------------------------- つなぐ
    private fun connect(device: BluetoothDevice, name: String, auto: Boolean = false) {
        _state.value = State.Connecting(name)
        RabiLog.i("ble", "つなぎます: $name${if (auto) " (自動接続で待つ)" else ""}")
        lastDevice = device
        lastName = name
        gatt = device.connectGatt(context, auto, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    /** Android が覚えている GATT の中身を捨てる (UNO Q 側で作り直されたとき、古い中身に書かないように) */
    private fun refreshCache(g: BluetoothGatt) {
        runCatching { g.javaClass.getMethod("refresh").invoke(g) }
    }

    /** 腕からの知らせが 5 秒来なければ、つなぎ直す (つながってから見張る) */
    private fun startWatch() {
        watchJob?.cancel()
        lastRxMs = android.os.SystemClock.elapsedRealtime()
        watchJob = scope.launch {
            while (isActive && ready) {
                delay(1000)
                if (ready && android.os.SystemClock.elapsedRealtime() - lastRxMs > 5000) {
                    forceReconnect("腕から知らせが 5 秒来ません")
                    break
                }
            }
        }
    }

    private fun closeGatt() {
        hbJob?.cancel(); writerJob?.cancel(); watchJob?.cancel()
        pending?.complete(false)
        ops.close()
        ops = Channel(Channel.UNLIMITED)
        obsBacklog.set(0)
        cmdCh = null; obsCh = null
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                RabiLog.i("ble", "つながりました。MTU を広げます")
                refreshCache(g)
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                RabiLog.w("ble", "切れました (status $status)")
                scope.launch(Dispatchers.Main) {
                    closeGatt()
                    if (wanted) { _state.value = State.Scanning; delay(1000); if (wanted && gatt == null) reconnect() } else _state.value = State.Off
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            RabiLog.i("ble", "MTU $mtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE)
            if (svc == null) { RabiLog.w("ble", "AgentARM のサービスがありません"); g.disconnect(); return }
            cmdCh = svc.getCharacteristic(CMD)
            obsCh = svc.getCharacteristic(OBS)
            val stateCh = svc.getCharacteristic(STATE)
            val eventCh = svc.getCharacteristic(EVENT)
            scope.launch(Dispatchers.Main) {
                startWriter(g)
                stateCh?.let { ops.trySend(Op.Subscribe(it, indicate = false)) }
                eventCh?.let { ops.trySend(Op.Subscribe(it, indicate = true)) }
                val name = (_state.value as? State.Connecting)?.name ?: "AgentARM"
                _state.value = State.Ready(name)
                retryJob?.cancel()
                runCatching { remember(g.device.address) }
                sendCmd(JSONObject().put("t", "hello").put("v", 1).put("app", app))
                startHeartbeat()
                startWatch()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            lastRxMs = android.os.SystemClock.elapsedRealtime()
            val text = value.toString(Charsets.UTF_8)
            text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                runCatching { JSONObject(line) }.onSuccess { m -> scope.launch(Dispatchers.Main) { onMessage(m) } }
                    .onFailure { RabiLog.w("ble", "読めない知らせ: ${line.take(60)}") }
            }
        }
    }

    /** GATT の操作を 1 つずつ行う (Android は同時に 1 つしかできない) */
    private fun startWriter(g: BluetoothGatt) {
        writerJob?.cancel()
        val queue = ops
        writerJob = scope.launch(Dispatchers.IO) {
            for (op in queue) {
                if (!isActive) break
                val ok = perform(g, op)
                if (op is Op.Write && op.isObs) obsBacklog.decrementAndGet()
                if (!ok && op is Op.Write && !op.isObs) RabiLog.w("ble", "書けませんでした: ${op.data.toString(Charsets.UTF_8).take(60)}")
            }
        }
    }

    private suspend fun perform(g: BluetoothGatt, op: Op): Boolean {
        val done = CompletableDeferred<Boolean>()
        pending = done
        var tries = 0
        while (true) {
            val code = when (op) {
                is Op.Write -> g.writeCharacteristic(
                    op.ch, op.data,
                    if (op.withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                )
                is Op.Subscribe -> {
                    g.setCharacteristicNotification(op.ch, true)
                    val d = op.ch.getDescriptor(CCCD) ?: return false
                    g.writeDescriptor(d, if (op.indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            }
            if (code == BluetoothStatusCodes.SUCCESS) break
            if (++tries > 20) return false            // 前の操作がまだ終わっていない (BUSY) ときは少し待って試し直す
            delay(10)
        }
        return withTimeoutOrNull(1500) { done.await() } ?: false
    }

    private fun startHeartbeat() {
        hbJob?.cancel()
        hbJob = scope.launch {
            while (isActive && ready) {
                // hb は捨てない (0.5 秒ごと。1.5 秒途切れると腕が止まる)
                obsCh?.let { ch ->
                    val data = JSONObject().put("t", "hb").put("seq", ++hbSeq).toString().toByteArray(Charsets.UTF_8)
                    ops.trySend(Op.Write(ch, data, withResponse = false, isObs = false))
                }
                delay(500)
            }
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("8a7f0001-3c2d-4b5e-9f10-2a4d6e8b0c1f")
        val CMD: UUID = UUID.fromString("8a7f0002-3c2d-4b5e-9f10-2a4d6e8b0c1f")
        val OBS: UUID = UUID.fromString("8a7f0003-3c2d-4b5e-9f10-2a4d6e8b0c1f")
        val STATE: UUID = UUID.fromString("8a7f0004-3c2d-4b5e-9f10-2a4d6e8b0c1f")
        val EVENT: UUID = UUID.fromString("8a7f0005-3c2d-4b5e-9f10-2a4d6e8b0c1f")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val MAX_BYTES = 200
    }
}
