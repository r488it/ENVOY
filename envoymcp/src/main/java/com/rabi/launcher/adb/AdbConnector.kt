package com.rabi.launcher.adb

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Base64
import com.rabi.launcher.core.RabiLog
import com.rabi.launcher.llm.SecureStore
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

sealed class AdbState(val label: String) {
    data object NotPaired : AdbState("未ペアリング")
    data object Disconnected : AdbState("切断")
    data object Connecting : AdbState("接続中…")
    data class Connected(val host: String, val port: Int, val mode: String) : AdbState("接続済み ($mode $host:$port)")
    data class Failed(val reason: String) : AdbState("接続できません: $reason")
}

class ExecResult(val stdout: ByteArray, val stderr: ByteArray, val exitCode: Int) {
    val text: String get() = String(stdout, Charsets.UTF_8)
    val err: String get() = String(stderr, Charsets.UTF_8)
    val ok get() = exitCode == 0
}

/** コマンドを送ったあとで接続が切れた (実行されたか分からない) */
class AdbLostException(message: String, val io: Boolean = false) : Exception(message)

/**
 * 同じ端末の adbd への接続 (ワイヤレスデバッグ。root 不要)。
 * 通常のアプリが Runtime.exec を呼んでもシェル (shell uid) の権限にはならないので、ADB プロトコルで自分の adbd に接続する。
 * - ペアリング: 開発者向けオプション › ワイヤレスデバッグ › 「ペア設定コードによるデバイスのペア設定」の 6 桁 (ダイアログを開いている間だけ有効)
 * - 接続: TLS。ポートはワイヤレスデバッグを入れ直すたびに変わるので毎回 mDNS で探す。Wi-Fi が要る
 * - 予備: 一度 USB から `adb tcpip 5555` にした端末は 127.0.0.1:5555 (再起動で消える)
 * - 鍵 (RSA-2048) は Android Keystore の鍵で暗号化して保存 (この鍵はシェルの権限そのものなので、ログや外に出さない)
 * - コマンドは 1 本ずつ順に (Mutex)。shell v2 で終了コードまで受け取る
 */
class AdbConnector(private val context: Context) {
    private val store = SecureStore(context, "adb_identity")
    private val prefs = context.getSharedPreferences("adb", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val _state = MutableStateFlow<AdbState>(if (prefs.getBoolean("paired", false)) AdbState.Disconnected else AdbState.NotPaired)
    val state: StateFlow<AdbState> = _state
    @Volatile private var manager: Manager? = null

    val isConnected: Boolean get() = manager?.isConnected == true && _state.value is AdbState.Connected
    val paired get() = prefs.getBoolean("paired", false)

    private class Manager(private val key: PrivateKey, private val cert: Certificate) : AbsAdbConnectionManager() {
        init { setApi(Build.VERSION.SDK_INT); setTimeout(10, TimeUnit.SECONDS) }
        override fun getPrivateKey(): PrivateKey = key
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "Rabi Launcher"
    }

    private fun manager(): Manager {
        manager?.let { return it }
        val saved = store.read()?.let { runCatching { JSONObject(it) }.getOrNull() }
        val (key, cert) = if (saved != null) {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(saved.getString("key"), Base64.NO_WRAP))) to
                CertificateFactory.getInstance("X.509").generateCertificate(Base64.decode(saved.getString("cert"), Base64.NO_WRAP).inputStream())
        } else {
            // adbd は RSA-2048 だけを受け付ける。証明書の期限は見ない
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val dn = X500Name("CN=RabiLauncher")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(dn, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 3650L * 86_400_000L), dn, kp.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
            val c = JcaX509CertificateConverter().getCertificate(holder)
            store.write(JSONObject().put("key", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)).put("cert", Base64.encodeToString(c.encoded, Base64.NO_WRAP)).toString())
            kp.private to c
        }
        return Manager(key, cert).also { manager = it }
    }

    /** ペアリング。ポートを省けば、開いているペアリングダイアログを mDNS で探す。 */
    suspend fun pair(code: String, host: String = "127.0.0.1", port: Int? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = code.filter { it.isDigit() }
            require(c.length == 6) { "ペアリングコードは 6 桁の数字です" }
            val p = port ?: discover(AdbMdns.SERVICE_TYPE_TLS_PAIRING, 30_000)
            check(p > 0) { "ペアリングのダイアログが見つかりません (開いたままにしてください)" }
            val ok = manager().pair(host, p, c)
            check(ok) { "ペアリングできませんでした" }
            prefs.edit().putBoolean("paired", true).apply()
            _state.value = AdbState.Disconnected
            RabiLog.i("adb", "paired")
        }.onFailure { RabiLog.w("adb", "pair failed: ${it.javaClass.simpleName} ${it.message}") }
    }

    /** 接続していなければつなぐ。ワイヤレスデバッグの TLS → だめなら 127.0.0.1:5555 (tcpip) */
    suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext true
        lock.withLock {
            if (isConnected) return@withLock true
            _state.value = AdbState.Connecting
            val m = runCatching { manager() }.getOrElse { _state.value = AdbState.Failed("鍵を用意できません"); return@withLock false }
            runCatching { m.disconnect() }
            val wifi = wifiOn()
            val tlsPort = if (wifi) discover(AdbMdns.SERVICE_TYPE_TLS_CONNECT, 4_000).takeIf { it > 0 } ?: prefs.getInt("last_port", -1) else -1
            val attempts = listOfNotNull(tlsPort.takeIf { it > 0 }?.let { "tls" to it }, "tcpip" to 5555)
            for ((mode, port) in attempts) {
                val ok = try {
                    m.connect("127.0.0.1", port)
                } catch (e: AdbPairingRequiredException) {
                    prefs.edit().putBoolean("paired", false).apply()
                    _state.value = AdbState.NotPaired
                    RabiLog.w("adb", "pairing required"); false
                } catch (e: Exception) {
                    RabiLog.i("adb", "connect $mode:$port failed ${e.javaClass.simpleName}"); false
                }
                if (ok && m.isConnected) {
                    // tcpip (USB デバッグの許可) で通った場合も、この鍵は端末に認められている
                    prefs.edit().putBoolean("paired", true).apply()
                    if (mode == "tls") prefs.edit().putInt("last_port", port).apply()
                    _state.value = AdbState.Connected("127.0.0.1", port, mode)
                    RabiLog.i("adb", "connected $mode:$port")
                    com.rabi.launcher.core.Trace.ev("adb_connected", "mode" to mode, "port" to port)
                    return@withLock true
                }
            }
            if (_state.value !is AdbState.NotPaired) _state.value = AdbState.Failed(if (!wifi) "Wi-Fi が切れています" else "ワイヤレスデバッグがオフか、ポートが見つかりません")
            false
        }
    }

    fun disconnect() {
        runCatching { manager?.disconnect() }
        _state.value = if (paired) AdbState.Disconnected else AdbState.NotPaired
    }

    /**
     * 1 つのコマンドを実行して終了コードまで待つ。送ったあとに切れたら AdbLostException。
     * libadb 3.1.1 は ASCII 以外を含むコマンドを送れない (日本語の入力はクリップボード経由)。
     */
    suspend fun exec(cmd: String, timeoutMs: Long = 15_000): ExecResult {
        // しばらく使わなかったあと: 画面を変えるコマンド (やり直せない) の前に、接続が生きているかを確かめる (切れていればつなぎ直す)。
        // (しばらく使わないうちに接続が切れていると、そのコマンドが切れた接続のまま失敗するため)
        if (!readOnly(cmd) && System.currentTimeMillis() - lastOkAt > IDLE_PROBE_MS) probe()
        return execRetrying(cmd, timeoutMs)
    }

    /** 接続が生きているかを、何もしないコマンドで確かめる (切れていれば、つなぎ直してもう一度) */
    suspend fun probe(): Boolean = runCatching { execRetrying("echo ok", 5_000).ok }.getOrDefault(false)

    /** しばらく使っていなければ確かめる (呼びかけに反応したときの、前もっての接続) */
    suspend fun probeIfIdle(): Boolean = if (System.currentTimeMillis() - lastOkAt > IDLE_PROBE_MS) probe() else isConnected

    @Volatile private var lastOkAt = 0L

    private suspend fun execRetrying(cmd: String, timeoutMs: Long): ExecResult = try {
        execOnce(cmd, timeoutMs)
    } catch (e: AdbLostException) {
        // つなぎ直した直後の最初のコマンドが、切れた接続のまま失敗することがある (止めた操作のあと・しばらく使わなかったあと)。
        // 画面を読むだけのコマンドは、つなぎ直して 1 回だけやり直す。タップ・入力など画面を変えるコマンドは、二重にならないようやり直さない
        if (!e.io || !readOnly(cmd) || !currentCoroutineContext().isActive) throw e
        com.rabi.launcher.core.Trace.ev("adb_retry", "cmd" to cmd.take(60))
        RabiLog.i("adb", "retry after lost: ${cmd.take(40)}")
        execOnce(cmd, timeoutMs)
    }

    /** 画面や端末の様子を読むだけのコマンド (やり直してよい) */
    private fun readOnly(cmd: String): Boolean = READ_ONLY.any { cmd.trimStart().startsWith(it) }

    private suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = withContext(Dispatchers.IO) {
        require(cmd.all { it.code < 128 }) { "ADB のコマンドは ASCII のみ" }
        if (!isConnected && !ensureConnected()) throw IllegalStateException("not connected")
        lock.withLock {
            val m = manager ?: throw IllegalStateException("not connected")
            try {
                withTimeout(timeoutMs) { execRaw(m, cmd) }.also { lastOkAt = System.currentTimeMillis() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // ストリームが止まったまま: 切って次回つなぎ直す
                runCatching { m.disconnect() }
                _state.value = AdbState.Failed("応答がありません")
                com.rabi.launcher.core.Trace.ev("adb_lost", "why" to "応答がない (${timeoutMs}ms)", "cmd" to cmd.take(60))
                throw AdbLostException("timeout: ${cmd.take(40)}")
            } catch (e: java.io.IOException) {
                val mode = (_state.value as? AdbState.Connected)?.mode
                runCatching { m.disconnect() }
                _state.value = AdbState.Failed("接続が切れました")
                com.rabi.launcher.core.Trace.ev("adb_lost", "why" to "${e.javaClass.simpleName}: ${e.message?.take(120)}", "cmd" to cmd.take(60), "mode" to mode)
                throw AdbLostException("io: ${e.javaClass.simpleName}", io = true)
            }
        }
    }

    private suspend fun execRaw(m: Manager, cmd: String): ExecResult = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream(); var exit = -1
        // shell v2: [id:1][len:4 LE][payload]、1=stdout 2=stderr 3=exit。exit を受け取ったら終わる
        m.openStream("shell,v2,raw:$cmd").use { s ->
            val input = DataInputStream(s.openInputStream())
            val header = ByteArray(5)
            while (exit < 0) {
                input.readFully(header)
                val len = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val payload = ByteArray(len).also { input.readFully(it) }
                when (header[0].toInt()) {
                    1 -> out.write(payload)
                    2 -> err.write(payload)
                    3 -> exit = payload[0].toInt() and 0xFF
                }
            }
        }
        ExecResult(out.toByteArray(), err.toByteArray(), exit)
    }

    private fun discover(type: String, timeoutMs: Long): Int {
        val port = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val ml = runCatching { wifi.createMulticastLock("rabi-adb-mdns").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val mdns = AdbMdns(context, type) { _, p -> if (p > 0) { port.set(p); latch.countDown() } }
        try {
            mdns.start()
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { mdns.stop() }
            runCatching { ml?.release() }
        }
        return port.get()
    }

    private fun wifiOn(): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(false)
}

/** 画面や端末の様子を読むだけのコマンドの頭 (切れたときに 1 回だけやり直してよい) */
private val READ_ONLY = listOf("wm size", "uiautomator dump", "cat ", "dumpsys ", "cmd package resolve-activity", "pm list", "pm path", "getprop", "settings get", "screencap", "echo ")

/** これより長く使わなかったら、画面を変えるコマンドの前に接続を確かめる */
private const val IDLE_PROBE_MS = 30_000L
