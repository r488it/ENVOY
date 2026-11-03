package com.rabi.envoymcp.arm

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.rabi.envoymcp.McpSettings
import com.rabi.envoymcp.core.Expression
import com.rabi.envoymcp.core.Mood
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.music.BeatTracker
import com.rabi.envoymcp.music.MusicListener
import com.rabi.envoymcp.music.MusicWatch
import com.rabi.envoymcp.music.NowPlaying
import com.rabi.envoymcp.music.SongDances
import com.rabi.envoymcp.music.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * AgentARM (スマホを載せる腕) を ENVOY MCP から動かす。
 * - ChatGPT の道具から (McpTools の arm_*): 縦にする・横にする・こっちを向く (1 回)・顔を追い続ける・追うのをやめる
 * - ENVOY MCP 本体で: マグネット充電器に付けたら、まっすぐ (縦) に直しながら顔に向ける (付けるごとに 1 回)。外したら、元の姿勢に戻す
 * - 顔は ENVOY MCP の中で前面カメラ (CameraX + ML Kit。通信なし) で探し、顔の向き (h, v) と重力だけを BLE で腕に送る (映像は外に出さない)
 * - 腕は「頼まれたら 1 回合わせる」(face_user) だけなので、追い続けるときは、顔がずれたら ENVOY MCP が向け直しを頼む (FollowPolicy)
 * - 任意の動き (move・motion) と、音楽に合わせて踊る (dance)。踊りの振り付けは腕 (UNO Q) が作り、スマホは端末の出力の音から
 *   見つけた拍 (MusicListener) を送るだけ。設定でオンにすると、音楽が流れたら自動で踊る (スマホが腕に付いているときだけ)
 * - 本人が設定でオンにしていて、MCP サーバーが動いている間だけ動く (McpService が start / stop を呼ぶ)
 * 中の状態はメインスレッドだけで触る (道具からは withContext(Main) で呼ぶ)。
 */
class ArmController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: McpSettings,
    /** 端末で鳴っている音から拍を見つける (踊り・音楽の画面・試しで共有。だれかが聞いている間だけ動く) */
    val music: MusicListener,
    /** 音楽が流れているか (音楽の画面と共有) */
    private val watch: MusicWatch,
    /** 再生中の曲 (通知へのアクセスが許可されていれば。曲ごとの踊りに使う) */
    private val nowPlaying: NowPlaying,
) {
    /** 設定の画面・道具の結果に出す様子 */
    data class Status(
        val link: String = "止めています",
        val ready: Boolean = false,
        /** 腕のモード (idle / moving / face / orient / manual / stopped / fault) */
        val mode: String = "",
        val calibrated: Boolean? = null,
        val following: Boolean = false,
        /** 追い続けるときに合わせる向き (portrait / landscape。null は今のまま) */
        val followTo: String? = null,
        /** 顔の向き (h, v) 度。カメラが動いていて顔が見えているときだけ */
        val face: Pair<Double, Double>? = null,
        /** 最後にしたこと・起きたこと */
        val last: String = "",
        val dancing: Boolean = false,
        /** 音楽が流れているか (音楽が流れたら踊る、がオンのときだけ見る) */
        val musicPlaying: Boolean = false,
        /** 関節の角度 [① 旋回, ② 肩, ③ 先端] (度。腕の state から) */
        val q: List<Double>? = null,
        /** 腕のプログラムの版 (hello の v。2 から move・motion・dance がある) */
        val version: Int = 0,
        /** 前面カメラで録画している (撮りながら顔を追う) */
        val recording: Boolean = false,
        /** 録画を始めた時刻 (System.currentTimeMillis。録画の画面の時間に使う) */
        val recordingSince: Long = 0L,
        /** 背面カメラで撮っている (カメラマン役: 背面カメラを人に向ける) */
        val recordingBack: Boolean = false,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val pose = PhonePose(context)
    val link = ArmLink(context, scope, ::onArmMessage, APP, { settings.armAddress.ifBlank { null } }, { settings.armAddress = it })
    private var face: FaceObserver? = null
    private var owner: AlwaysOn? = null
    /** ふだん (動画を見る間など) は落ち着いて追う */
    private val calmPolicy = FollowPolicy(lostMs = LOST_MIN * 60_000L)
    /** 背面で撮っている間 (カメラマン役) は、動く人を細かく追う */
    private val cameraPolicy = FollowPolicy(tolDeg = 5.0, dwellMs = 500, quietMs = 800, failQuietMs = 2_000, lostMs = LOST_MIN * 60_000L)
    private var policy = calmPolicy
    /** 曲ごとに覚えた踊り方 */
    val songDances = SongDances(context)
    /** 踊ってと頼まれた・予約した (音楽の画面を前に出す) */
    var onDance: (() -> Unit)? = null
    /** 踊りの予約が変わった (音楽の見張りを動かす・止める) */
    var onReserveChanged: (() -> Unit)? = null
    /** 録画を始めた (録画の画面を開く) */
    var onRecordStarted: (() -> Unit)? = null
    /** 腕 (UNO Q) が呼び名「リゲル」を本人の声で聞いた (試験。本人らしさ 0..1) */
    var onWake: ((Double) -> Unit)? = null
    /** 腕の家のネットワークでの IP (hello の ip。版 5 から。電話の代役 (RigelCaller) がつなぐ先) */
    @Volatile var armIp: String? = null; private set

    /** 腕と拍の記録 (設定の画面に出す。ファイルにも残す) */
    val log = ArmLog(java.io.File(context.noBackupFilesDir, "arm_log.txt"))
    /** UNO Q の Wi-Fi (設定の画面から) */
    val wifi = ArmWifi(link) { armVersion }

    private var running = false
    private var sensing = false
    private var gravJob: Job? = null
    private var followJob: Job? = null
    private var aimJob: Job? = null
    private var cameraStopJob: Job? = null
    /** 録画 (撮りながら顔を追う): 保存し終わるのを待つもの、時間切れ・熱の見張り、録画のために追い始めたか */
    private var recordDone: CompletableDeferred<FaceObserver.Saved>? = null
    private var recordJob: Job? = null
    private var recordFollow = false
    /** 撮りながらカメラを切り替えたあと、腕を回して顔に向け直す (裏で) */
    private var switchJob: Job? = null
    /** 背面カメラを使う (背面で録画している間だけ。カメラマン役) */
    private var cameraBack = false
    /** 背面カメラが上に届く端の仰角 (腕が「届かない」と返したときに覚える。腕の範囲は変えないので、当たって知る) */
    private var rearElMax: Double? = null
    /** 最後に見た顔の、背面カメラから上へのずれ (抑える前。上に届かなかったのかを見分ける) */
    private var lastRearUp = 0.0

    /** 腕に頼んで、done / fail を待っているもの (腕は新しい頼みで前の頼みを置き換えるので、いつも 1 つ) */
    private class Pending(val id: String, val what: String, val face: Boolean, val dance: Boolean = false, val result: CompletableDeferred<ArmResult> = CompletableDeferred())
    private var pending: Pending? = null
    private var cmdN = 0
    private var armMode = ""
    private var mood = Mood.IDLE
    private var following = false
    private var armVersion = 0

    // 踊る
    private var dancing = false
    private var danceJob: Job? = null
    /** 音楽が止まったら踊りを終える (少し待っても止まったままなら) */
    private var musicStopJob: Job? = null
    /** 音楽が止まったので、踊りの終わりを腕に頼んだ (その間は拍を送らない) */
    private var endingForMusic = false
    /** 腕が「聞いている」動きの最中 (送り直すと、揺れている途中の姿勢が元の姿勢になってしまうので、送り直さない) */
    private var listenActive = false
    private var listenJob: Job? = null
    /** 踊りの予約 (会話中・音楽が止まっているときに頼まれた。音楽が流れたら踊る) */
    private class Reservation(val style: String?, val amp: Double?, val until: Long)
    private var reservation: Reservation? = null
    private var reserveJob: Job? = null
    /** 今踊っている曲の種 (曲が替わったら腕に知らせる) */
    private var danceSeed: Int? = null
    /** 踊り始めるところ (拍を待っている間)。この間は、自動の踊り・予約の踊りを始めない (同時に 2 つ始まってぶつからないように) */
    private var danceStarting = false
    /** 今流れている音楽で、自動で踊るのを試したか (音楽が止まると戻す。止められたあと、同じ曲の間に踊り直さない) */
    private var autoTried = false
    private var autoJob: Job? = null

    // マグネット充電器 (付けるごとに 1 回だけ、まっすぐに直す)
    private var onCharger = false
    private var straightened = false
    private var pendingStraighten = false
    private var receiverOn = false

    /** 呼び名を聞いてよいかを腕に知らせる */
    private val voiceGate = ArmVoiceGate(context, scope, link, settings, { armVersion }, { onCharger })
    private val powerRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> checkAttached(0)
                Intent.ACTION_POWER_DISCONNECTED -> checkDetached()
            }
        }
    }

    init {
        music.onBeat = ::onBeat
        music.onNote = { log.add(it) }
        scope.launch { watch.playing.collect { onMusicPlaying(it) } }
        scope.launch { nowPlaying.track.collect { onTrack(it) } }
        scope.launch {
            link.state.collect { s ->
                if (_status.value.link != s.label) log.add("つながり: ${s.label}")
                _status.update { it.copy(link = s.label, ready = s is ArmLink.State.Ready) }
                if (s is ArmLink.State.Ready) {
                    startSensing()
                    sendMood()
                    maybeStraighten()
                    voiceGate.startRepeating()
                } else {
                    voiceGate.stopRepeating()
                    stopSensing()
                    pending?.result?.complete(ArmResult.failed("AgentARM との接続が切れました", "disconnected"))
                }
            }
        }
    }

    // ---------------------------------------------------------------- 始める・止める (McpService から)

    /** 本人が設定でオンにしていて、MCP サーバーが動いているとき (許可が変わったときにも呼ばれるので、2 回呼ばれても困らないようにする) */
    fun start() {
        link.start()
        if (running) return
        running = true
        RabiLog.i("arm", "start")
        if (!receiverOn) {
            context.registerReceiver(powerRx, IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            }, Context.RECEIVER_NOT_EXPORTED)
            receiverOn = true
        }
        // 付けたままで始めたときは直さない (付けたときだけ直す。急に動かないように)
        onCharger = wirelessNow()
        straightened = onCharger
        voiceGate.listen()
        voiceGate.send()
    }

    fun stop() {
        if (!running) return
        running = false
        RabiLog.i("arm", "stop")
        stopFollowing("腕の機能を止めました", sendStop = true)
        pending?.result?.complete(ArmResult.failed("腕の機能を止めました", "off"))
        endDanceLocal()
        listenJob?.cancel()
        music.release(LISTEN_DANCE)
        music.release(LISTEN_TEST)
        autoJob?.cancel()
        cancelReservation(null)
        if (receiverOn) { runCatching { context.unregisterReceiver(powerRx) }; receiverOn = false }
        voiceGate.unlisten()
        voiceGate.stopRepeating()
        link.stop()
        stopSensing()
        face?.stop()
    }

    // ---------------------------------------------------------------- 道具から (ChatGPT)

    /** 縦 (portrait) か横 (landscape) にする。追っている途中なら、追うのは続ける */
    suspend fun orient(to: String): ArmResult = withContext(Dispatchers.Main) {
        check()?.let { return@withContext it }
        val r = command(cmd("orient").put("to", to), if (to == LANDSCAPE) "横にする" else "縦にする", ORIENT_WAIT_MS)
        if (following) policy.hold(now())
        settle(r, if (to == LANDSCAPE) "スマホを横にしました" else "スマホを縦にしました")
    }

    /** 前面カメラで顔を探し、1 回だけ顔に向ける。to を付けると、向けながら縦・横にも合わせる */
    suspend fun lookAtMe(to: String?): ArmResult = withContext(Dispatchers.Main) {
        (check() ?: cameraCheck())?.let { return@withContext it }
        val r = aim(to, "顔に向ける")
        if (following) policy.aimed(now(), r.ok) else stopCameraSoon()
        settle(r, "画面を利用者の顔に向けました" + toText(to))
    }

    /**
     * 顔を追い続ける。まず顔に向け (to を付けると縦・横にも合わせる)、そのあとは顔がずれたら向け直す。
     * 最初に向け終わったら返り、あとは裏で続ける (追うのをやめる・スマホを外す・顔が長く見えない・熱いときにやめる)
     */
    suspend fun follow(to: String?): ArmResult = withContext(Dispatchers.Main) {
        (check() ?: cameraCheck())?.let { return@withContext it }
        following = true
        _status.update { it.copy(following = true, followTo = to) }
        policy.reset(now())
        startWatchdog()
        RabiLog.i("arm", "顔を追い始めます (${to ?: "向きはそのまま"})")
        val r = aim(to, "顔に向ける (追い続ける)")
        policy.aimed(now(), r.ok)
        when {
            !following -> ArmResult.failed("途中で追うのをやめました (${_status.value.last})", "stopped")
            r.ok -> settle(ArmResult.ok(""), "画面を利用者の顔に向けました${toText(to)}。このあとも顔を追い続けます (やめるときは arm_stop_following)")
            r.code in FATAL -> { stopFollowing("追えません: ${r.message}", sendStop = false); settle(r, "") }
            else -> settle(ArmResult.ok(""), "顔を追い始めました。ただし最初は向けられませんでした (${r.message})。顔が見えたら向け直します (やめるときは arm_stop_following)")
        }
    }

    /**
     * 前面カメラで録画を始める (撮りながら顔を追う)。カメラのアプリで撮っている間は前面カメラを使えないので、
     * ENVOY MCP が同じ前面カメラで録画と顔の検出をする。follow なら顔も追い続ける (腕につながっていなければ撮るだけ)。
     * maxMin 分で自動で止める。スマホが熱くなったら止める。止めるのは recordStop・リゲルのタップ・腕の機能を止めたとき
     */
    suspend fun recordStart(follow: Boolean, maxMin: Int, back: Boolean = false): ArmResult = withContext(Dispatchers.Main) {
        cameraCheck()?.let { return@withContext it }
        if (face?.recordingNow == true) return@withContext ArmResult.ok("もう録画しています (止めるときは arm_stop_recording)")
        if (hot()) return@withContext ArmResult.failed("スマホが熱いので、録画を始めません", "hot")
        val cam = if (back) "背面" else "前面"
        if (back != cameraBack) {
            // カメラを切り替える: 前のカメラで追っていたらやめる (追い直すのは、切り替えたカメラで)
            if (following) stopFollowing("${cam}カメラに切り替えるので、顔を追うのをやめました", sendStop = false)
            cameraBack = back
        }
        policy = if (back) cameraPolicy else calmPolicy
        if (!startCamera()) {
            cameraBack = false
            if (!following) releaseCamera()
            return@withContext ArmResult.failed("${cam}カメラを使えません (ほかのアプリが使っている・カメラが切られている、または ENVOY MCP の設定の画面を一度開くと使えるようになります)。", "camera")
        }
        val f = face ?: return@withContext ArmResult.failed("前面カメラを使えません", "camera")
        val done = CompletableDeferred<FaceObserver.Saved>()
        f.startRecording(rotationNow()) { s -> done.complete(s); onRecordDone(s) }?.let { err ->
            cameraBack = false
            if (!following) releaseCamera()
            return@withContext settle(ArmResult.failed(err, "camera"), "")
        }
        recordDone = done
        _status.update { it.copy(recording = true, recordingSince = System.currentTimeMillis(), recordingBack = back) }
        onRecordStarted?.invoke()
        val mins = maxMin.coerceIn(1, RECORD_MAX_MIN)
        val until = now() + mins * 60_000L
        recordJob?.cancel()
        recordJob = scope.launch {
            while (isActive && face?.recordingNow == true) {
                if (now() >= until) { log.add("録画が $mins 分になったので止めました"); face?.stopRecording(); break }
                if (hot()) { log.add("スマホが熱くなったので、録画を止めました"); face?.stopRecording(); break }
                delay(RECORD_WATCH_MS)
            }
        }
        val followText = when {
            !follow -> "顔は追いません"
            following -> "顔を追いながら撮っています"
            !link.ready -> "AgentARM につながっていないので、顔は追わずに撮っています"
            else -> {
                recordFollow = true
                val r = follow(null)
                if (r.ok) "顔を追いながら撮っています" else "顔は追えません (${r.message})。撮るのは続けています"
            }
        }
        val backText = if (back) "背面カメラを利用者に向けて撮ります (カメラマン役。画面は反対を向きます。背面カメラは水平より約 9° 上までしか向けられないので、顔がスマホと同じ高さか低いときに合います)。" else ""
        settle(ArmResult.ok(""), "${cam}カメラで録画を始めました ($mins 分で自動で止まります)。$backText$followText。止めるときは arm_stop_recording")
    }

    /**
     * 撮っている途中で前面・背面を切り替える (back が null なら反対へ)。録画は止めずに 1 本の動画のまま。
     * 腕が回り終わるのを待たずに、すぐ返す。
     * 顔を追っているときは、裏で土台を 180° 回し (範囲の外は端まで)、背面にするときは背面カメラをできるだけ上へ向けてから、顔に向け直す
     */
    suspend fun recordSwitch(back: Boolean?): ArmResult = withContext(Dispatchers.Main) {
        val f = face
        if (f?.recordingNow != true) return@withContext ArmResult.ok("録画はしていません (切り替えるのは撮っている間だけです。撮り始めるときのカメラは arm_record_video の camera で選びます)")
        val to = back ?: !cameraBack
        val cam = if (to) "背面" else "前面"
        if (to == cameraBack) return@withContext ArmResult.ok("もう${cam}カメラで撮っています")
        f.switchCamera(to)?.let { return@withContext settle(ArmResult.failed(it, "camera"), "") }
        cameraBack = to
        policy = if (to) cameraPolicy else calmPolicy
        _status.update { it.copy(recordingBack = to) }
        var text = "録画を止めずに${cam}カメラに切り替えました"
        switchJob?.cancel()
        if (following && link.ready) {
            policy.reset(now())
            text += "。腕を回して、${cam}カメラを顔に向けています (10 秒ほど)"
            if (to) text += "。背面カメラは水平より下しか向けられないので、スマホと同じ高さか低い所にいると映りやすくなります"
            switchJob = scope.launch {
                val q1 = _status.value.q?.get(0) ?: 0.0
                val turned = move(if (q1 <= 0) 180.0 else -180.0, if (to) -90.0 else null, null, relative = true, slow = false)
                if (!isActive || !following) return@launch
                val r = aim(null, "${cam}カメラを顔に向ける")
                policy.aimed(now(), r.ok)
                log.add(when {
                    r.ok -> "腕を回して、${cam}カメラを顔に向けました"
                    turned.ok -> "腕を回しました (顔はまだ向けられません: ${r.message})。顔が見えたら向け直します"
                    else -> "腕は回せませんでした (${turned.message})"
                })
            }
        }
        settle(ArmResult.ok(""), text)
    }

    /** 録画を止めて保存する (録画のために追い始めたなら、追うのもやめる) */
    suspend fun recordStop(): ArmResult = withContext(Dispatchers.Main) {
        val done = recordDone
        if (face?.recordingNow != true || done == null) return@withContext ArmResult.ok("録画はしていませんでした (何もしていません)")
        face?.stopRecording()
        val s = withTimeoutOrNull(RECORD_SAVE_WAIT_MS) { done.await() }
            ?: return@withContext settle(ArmResult.unknown("録画を止めましたが、保存し終わったか分かりません"), "")
        if (s.uri == null || s.error != null) settle(ArmResult.failed("録画を保存できませんでした (${s.error ?: "保存先が分かりません"})", "record"), "")
        else settle(ArmResult.ok(""), "録画を止めて、「フォト」の Movies/ENVOY に保存しました (${savedText(s)})")
    }

    /** 録画の画面に映す画像を受け取るもの (画面を閉じたら null) */
    fun setPreviewSink(sink: ((android.graphics.Bitmap) -> Unit)?) { face?.previewSink = sink }

    /** 録画が終わった (止めた・時間切れ・熱・失敗): 見張りをやめ、録画のために追い始めたなら追うのもやめ、カメラを止める */
    private fun onRecordDone(s: FaceObserver.Saved) {
        recordJob?.cancel()
        switchJob?.cancel()
        recordDone = null
        _status.update { it.copy(recording = false, recordingBack = false) }
        log.add(if (s.uri != null && s.error == null) "録画を保存しました (${savedText(s)})" else "録画を保存できませんでした (${s.error})")
        val wasBack = cameraBack
        cameraBack = false       // ふだん (こっちを向く・顔を追う) は前面カメラ
        policy = calmPolicy
        if (recordFollow || wasBack) { recordFollow = false; stopFollowing("録画を止めたので、顔を追うのをやめました", sendStop = false) }
        if (wasBack || (!following && pending?.face != true)) releaseCamera()
    }

    /** 保存したファイルと長さ */
    private fun savedText(s: FaceObserver.Saved) =
        if (s.names.size <= 1) "${s.name}、${s.ms / 1000} 秒" else "${s.names.size} 本: ${s.name}、合わせて ${s.ms / 1000} 秒"

    /** 動画の向き (始めたときのスマホの向き。重力で決める。平らなら縦) */
    private fun rotationNow(): Int {
        val g = pose.latest ?: return Surface.ROTATION_0
        val x = g[0]; val y = g[1]
        return when {
            abs(x) < 3f && abs(y) < 3f -> Surface.ROTATION_0
            abs(y) >= abs(x) -> if (y >= 0) Surface.ROTATION_0 else Surface.ROTATION_180
            x > 0 -> Surface.ROTATION_90
            else -> Surface.ROTATION_270
        }
    }

    /** 顔を追うのをやめる (カメラも止める。腕はその場で止まる) */
    suspend fun stopFollowingByTool(): ArmResult = withContext(Dispatchers.Main) {
        if (!following) return@withContext ArmResult.ok("顔は追っていませんでした (何もしていません)")
        stopFollowing("ChatGPT に頼まれて、顔を追うのをやめました", sendStop = true)
        ArmResult.ok("顔を追うのをやめました。前面カメラも止めました")
    }

    /**
     * 1 つの姿勢へ動かす。turn: 左右 (+ は画面の前にいる人から見て右へ向く)、tilt: 上下 (+ は上を向く)、roll: 先端の回転
     * (向きは取り付けで変わる)。relative なら今の姿勢からの差、そうでなければ腕の原点からの角度。null の関節は動かさない
     */
    suspend fun move(turn: Double?, tilt: Double?, roll: Double?, relative: Boolean, slow: Boolean): ArmResult = withContext(Dispatchers.Main) {
        (check() ?: newCheck())?.let { return@withContext it }
        if (turn == null && tilt == null && roll == null) return@withContext ArmResult.failed("動かす角度がありません (turn・tilt・roll のどれか)。", "bad_request")
        val goal = listOf(turn, tilt?.let { -it }, roll)
        val q = JSONArray().apply { goal.forEach { put(it?.let { v -> r1(v) } ?: JSONObject.NULL) } }
        val v = if (slow) SLOW_DEG_S else MOVE_DEG_S
        val c = cmd("move").put("q", q).put("v", v)
        if (relative) c.put("rel", 1)
        // 待つ長さ: いちばん大きく動く関節の角度から (5 次の曲線の最大の速さは平均の 1.875 倍)
        val cur = _status.value.q
        val dq = goal.mapIndexed { j, g -> if (g == null) 0.0 else if (relative) abs(g) else cur?.let { abs(g - it[j]) } ?: 340.0 }.max()
        val r = command(c, "動かす", (1.875 * dq / v * 1000).toLong() + WAIT_MARGIN_MS)
        if (following) policy.hold(now())
        settle(r, "動かしました" + extraText(r) + angleText())
    }

    /** 動きの並び (1 つの姿勢ごとに、今の姿勢からの差と、そこへ動く長さ)。repeat 回くり返す */
    data class Key(val turn: Double, val tilt: Double, val roll: Double, val ms: Int)

    suspend fun motion(keys: List<Key>, repeat: Int): ArmResult = withContext(Dispatchers.Main) {
        (check() ?: newCheck())?.let { return@withContext it }
        if (keys.isEmpty() || keys.size > MOTION_KEYS) return@withContext ArmResult.failed("姿勢は 1〜$MOTION_KEYS 個にしてください。", "bad_request")
        if (repeat !in 1..MOTION_REPEAT) return@withContext ArmResult.failed("くり返しは 1〜$MOTION_REPEAT 回にしてください。", "bad_request")
        if (keys.any { it.ms !in 100..10_000 }) return@withContext ArmResult.failed("1 つの姿勢へ動く長さ (ms) は 100〜10000 にしてください。", "bad_request")
        // 長さの見込み (速さの上限を超える動きは、腕が長さを延ばす)
        var prev = Key(0.0, 0.0, 0.0, 0)
        var once = 0.0
        for (k in keys) {
            val dq = maxOf(abs(k.turn - prev.turn), abs(k.tilt - prev.tilt), abs(k.roll - prev.roll))
            once += maxOf(k.ms / 1000.0, 1.875 * dq / VMAX_DEG_S)
            prev = k
        }
        val total = once * repeat
        if (total > MOTION_MAX_S) return@withContext ArmResult.failed("動きが長すぎます (約 ${total.roundToInt()} 秒。${MOTION_MAX_S.roundToInt()} 秒までにしてください)。", "bad_request")
        val k = JSONArray().apply {
            keys.forEach { put(JSONArray().put(it.turn.roundToInt()).put((-it.tilt).roundToInt()).put(it.roll.roundToInt()).put(it.ms)) }
        }
        val r = command(cmd("motion").put("k", k).put("n", repeat).put("rel", 1), "動きの並び", (total * 1000).toLong() + WAIT_MARGIN_MS)
        if (following) policy.hold(now())
        settle(r, "動き終えました" + extraText(r))
    }

    /**
     * リゲルの表情に合わせて、腕も同じような動きをする。
     * つながっていない・踊り・録画・顔の追跡・ほかの動きの最中は何もしない (それらを止めない)。結果は待たない。
     * 表情の動きの最中に次の表情が来たら、今の動きが終わってからする (いちばん新しい 1 つだけ。途中で置き換えると、
     * 腕は置き換えたときの向きを元にするので、元の向きに戻らなくなる)
     */
    fun express(e: Expression, chained: Boolean = false) {
        scope.launch(Dispatchers.Main) { expressNow(e, chained) }
    }

    /** 表情の動きをして、終わるまで待つ (呼び名で起きたとき、表情のあとに顔へ向けるため)。動いたら true */
    suspend fun expressNow(e: Expression, chained: Boolean = false): Boolean = withContext(Dispatchers.Main) {
        if (check() != null || newCheck() != null) return@withContext false
        if (gestureRunning) { nextGesture = e; RabiLog.i("arm", "express ${e.id} queued"); return@withContext false }
        val s = _status.value
        // 続けて出すとき (chained)・自分の表情の動きを終えたすぐあとは、腕の様子が「動いている」のまま残っていることがあるので、それは見ない
        // (ほかの動きの最中と取り違えて、次の表情を見送らないように)
        val leftover = chained || now() - gestureEndedAt < GESTURE_SETTLE_MS
        if (s.dancing || s.following || s.recording || danceStarting || (s.mode in MOVING && !leftover)) {
            RabiLog.i("arm", "express ${e.id} skipped (busy)")
            return@withContext false
        }
        gestureRunning = true
        try {
            val (keys, repeat) = ArmGestures.of(e)
            val r = motion(keys, repeat)
            RabiLog.i("arm", "express ${e.id} -> ${r.result}")
            r.ok
        } finally {
            gestureRunning = false
            gestureEndedAt = now()
            nextGesture?.let { nextGesture = null; express(it, chained = true) }
        }
    }

    /** 表情の動きをしている最中か、その間に来た次の表情 */
    private var gestureRunning = false
    private var nextGesture: Expression? = null
    /** 最後に表情の動きを終えた時刻 (elapsedRealtime) */
    private var gestureEndedAt = 0L

    /**
     * 音楽に合わせて踊る。端末で鳴っている音から拍を見つけ (数秒)、腕に「踊って」と頼んで、拍を送り続ける。
     * 拍が取れたら返り、あとは裏で続ける (音楽が止まる・arm_stop・リゲルのタップ・ほかの動き・スマホを外すと終わる)
     * style: chill / groove / party (null なら、その曲に覚えた踊り方か groove)、amp: 振りの大きさ (0.1〜1。null なら覚えたものか 0.65)
     * - 曲名が分かれば、曲ごとの専用の振り付けにする (腕に曲の種を送る)。style を指定したら、その曲の踊り方として覚える
     * - 会話中 (ChatGPT の音声モードなど) や、音楽が流れていないときは、音楽が流れたら踊る予約にする (RESERVE_MS まで)
     */
    // Main.immediate: メインスレッドから呼ばれたときは、待たずにそのまま始める (予約から踊り出すとき、
    // 自動の踊りが間に割り込まないように。踊り始めるところの印を付けるまで、ほかの仕事に順番を渡さない)
    suspend fun dance(style: String?, amp: Double?, auto: Boolean = false): ArmResult = withContext(Dispatchers.Main.immediate) {
        (check() ?: newCheck() ?: micCheck())?.let { return@withContext it }
        if (!auto) onDance?.invoke()
        if (danceStarting) return@withContext ArmResult.failed("いま踊り始めるところです。少し待ってから頼み直してください。", "busy")
        // 予約にするのは、音楽が流れていないときだけ (ChatGPT の音声の会話中でも、音楽が流れていればすぐ踊る)
        if (watch.active && !watch.playing.value && !dancing) {
            if (auto) return@withContext ArmResult.failed("音楽が流れていません", "nobeat")
            reserve(style, amp)
            return@withContext settle(ArmResult.ok(""), "今は音楽が流れていないので、音楽が流れたら踊ります (${RESERVE_MS / 60_000} 分まで待ちます)。YouTube Music などで音楽を流してください")
        }
        if (!auto) cancelReservation(null)
        val track = nowPlaying.track.value
        val saved = songDances.get(track)
        val st = style ?: saved?.style ?: AUTO_STYLE
        val am = (amp ?: saved?.amp ?: DEFAULT_AMP).coerceIn(0.1, 1.0)
        val remember = !auto && style != null && track != null
        if (following) stopFollowing("踊るので、顔を追うのをやめました", sendStop = false)
        if (!music.hold(LISTEN_DANCE)) return@withContext settle(ArmResult.failed(music.status.value.note.ifBlank { "端末の音を読めません" }, "music"), "")
        // 拍を待つ間に止められた (リゲルのタップ・設定の画面を閉じた) ときは、聞くのもやめる
        danceStarting = true
        // 拍をつかむ間 (4〜6 秒)、腕に「聞いている」動きをしてもらう (腕のプログラムが 4 以降。踊りを頼めば、そのまま踊りへ移る)
        // (拍が取れるまで腕が止まったままだと、踊り出しが遅く見えるため)
        val listening = listenActive || (music.locked == null && startListen())
        val beat = try { waitBeat() } catch (e: kotlinx.coroutines.CancellationException) {
            danceStarting = false
            if (listening) endListen()
            if (!dancing) music.release(LISTEN_DANCE); throw e
        }
        danceStarting = false
        if (beat == null) {
            if (listening) endListen()
            if (!dancing) music.release(LISTEN_DANCE)
            // 会話中で音楽が聞こえないときは、音楽が流れたら踊る予約にする
            if (!auto && inCommunication()) {
                reserve(style, amp)
                return@withContext settle(ArmResult.ok(""), "会話中で音楽の拍が取れないので、音楽が流れたら踊ります (${RESERVE_MS / 60_000} 分まで待ちます)")
            }
            return@withContext settle(ArmResult.failed("音楽の拍が取れません (${music.status.value.note})。音楽が流れているか、音量が 0 でないかを確かめてください。", "nobeat"), "")
        }
        ensureResumed()
        pending?.result?.complete(ArmResult.failed("次の頼みに置き換えました", "busy"))
        val p = Pending("e${++cmdN}", "踊る", face = false, dance = true)
        pending = p
        val c = cmd("dance").put("id", p.id).put("style", st).put("amp", r2(am))
        if (armVersion >= 3 && track != null) c.put("sd", track.seed)     // 曲ごとの専用の振り付け
        if (!link.sendCmd(c)) {
            pending = null
            music.release(LISTEN_DANCE)
            return@withContext ArmResult.failed("AgentARM に送れませんでした", "disconnected")
        }
        listenActive = false                          // 腕が「聞いている」動きを、この踊りに置き換える
        RabiLog.i("arm", "頼みました: 踊る ($st${if (auto) "、音楽が流れたので" else ""})")
        log.add("踊ります: $st 大きさ ${r2(am)} テンポ ${beat.bpm.roundToInt()} 確かさ ${(beat.confidence * 100).roundToInt()}%" +
            (track?.let { " 曲「${it.label}」${if (saved != null && style == null) " (覚えた踊り方)" else ""}" } ?: "") + if (auto) " (音楽が流れたので)" else "")
        if (remember) songDances.put(track!!, SongDances.Pref(st, am))
        danceSeed = track?.seed
        dancing = true
        _status.update { it.copy(dancing = true) }
        sendBeat(beat)
        // すぐに断られたとき (腕が止まっている・調整中・較正がまだ など)
        val early = withTimeoutOrNull(EARLY_FAIL_MS) { p.result.await() }
        if (early != null) {
            if (pending === p) pending = null
            // 次の踊りに置き換えられたときは、新しい踊りの状態 (踊っている印・音を聞くこと) に触らない
            if (pending?.dance != true) endDanceLocal()
            return@withContext settle(early, "踊り終えました")
        }
        danceJob?.cancel()
        danceJob = scope.launch {
            val r = p.result.await()
            val heard = music.status.value.note     // 音を聞くのをやめる前に、聞こえ方を控える
            val byMusic = endingForMusic
            if (pending === p) pending = null
            if (pending?.dance == true) return@launch    // 次の踊りに置き換えられた (新しい踊りの状態に触らない)
            endDanceLocal(cancelJob = false)
            val text = if (r.ok) when {
                byMusic -> "音楽が止まったので、踊り終えました"
                "end:nobeat" in r.extra -> "拍が腕に届かなくなったので、踊り終えました (${heard.ifBlank { "音楽が止まった・拍が取れなくなった" }})"
                "end:timeout" in r.extra -> "長く踊ったので、踊り終えました"
                else -> "踊り終えました"
            } else "踊るのをやめました: ${r.message}"
            settle(if (r.ok) ArmResult.ok(text) else r, text)
        }
        val about = track?.let { "「${it.label}」に合わせて" } ?: "音楽に合わせて"
        val how = when {
            remember -> "。この曲の踊り方 ($st) として覚えました"
            saved != null && style == null -> "。この曲に覚えた踊り方 (${saved.style}) で踊ります"
            track != null && armVersion >= 3 -> "。この曲専用の振り付けです"
            else -> ""
        }
        settle(ArmResult.ok(""), "${about}踊り始めました (テンポ ${beat.bpm.roundToInt()})$how。音楽が止まると、踊り始めた姿勢に戻って終わります (やめるときは arm_stop)")
    }

    /** 予約があるか (音楽の見張りを動かしておく) */
    fun hasReservation() = reservation?.let { now() < it.until } == true

    private fun reserve(style: String?, amp: Double?) {
        reservation = Reservation(style, amp, now() + RESERVE_MS)
        log.add("踊りを予約しました (音楽が流れたら踊ります)")
        onReserveChanged?.invoke()
        reserveJob?.cancel()
        reserveJob = scope.launch {
            while (isActive && hasReservation()) {
                delay(RESERVE_CHECK_MS)
                val r = reservation ?: break
                if (watch.playing.value && pending == null && !dancing && !danceStarting) {
                    reservation = null
                    onReserveChanged?.invoke()
                    log.add("音楽が流れたので、予約していた踊りを始めます")
                    val out = dance(r.style, r.amp)
                    RabiLog.i("arm", "予約の踊り: ${out.result} ${out.message}")
                    return@launch
                }
            }
            if (reservation != null) cancelReservation("音楽が流れなかったので、踊りの予約を取り消しました")
        }
    }

    /** 予約を取り消す (why があれば記録に残す) */
    private fun cancelReservation(why: String?): Boolean {
        if (reservation == null) return false
        reservation = null
        reserveJob?.cancel()
        why?.let { log.add(it); _status.update { s -> s.copy(last = it) } }
        onReserveChanged?.invoke()
        return true
    }

    private fun inCommunication(): Boolean {
        val mode = context.getSystemService(AudioManager::class.java)?.mode
        return mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL
    }

    /** 曲が替わった: 踊っている途中なら、腕に新しい曲の種を知らせる (振り付けをその曲のものに替える) */
    private fun onTrack(t: Track?) {
        if (dancing && t?.paused == true) scheduleMusicStop()
        if (t == null || !dancing || armVersion < 3 || t.seed == danceSeed) return
        danceSeed = t.seed
        link.sendObs(JSONObject().put("t", "obs").put("k", "song").put("sd", t.seed))
        log.add("曲が替わりました: 「${t.label}」の振り付けにします")
    }

    /** 止める (道具の arm_stop): 踊っていれば踊り始めた姿勢に戻して終え、顔を追っていればやめ、動いていればその場で止める */
    suspend fun stopByTool(): ArmResult = withContext(Dispatchers.Main) {
        val did = mutableListOf<String>()
        if (cancelReservation(null)) did += "踊りの予約を取り消しました"
        if (listenActive) { autoJob?.cancel(); endListen(); did += "音楽を聞いている動きをやめました" }
        if (dancing) {
            val p = pending
            if (link.ready) link.sendCmd(cmd("dance").put("id", "e${++cmdN}").put("end", 1))
            if (p?.dance == true) withTimeoutOrNull(DANCE_END_WAIT_MS) { p.result.await() }
            endDanceLocal()
            did += "踊るのをやめて、踊り始めた姿勢に戻しました"
        }
        if (following) { stopFollowing("ChatGPT に頼まれて止めました", sendStop = true); did += "顔を追うのをやめました" }
        val p = pending
        if (p != null && !p.dance) {
            if (link.ready) { link.sendCmd(cmd("stop")); armMode = "stopped" }
            p.result.complete(ArmResult.failed("止めました", "stopped"))
            did += "腕をその場で止めました"
        }
        settle(ArmResult.ok(""), if (did.isEmpty()) "腕は何もしていませんでした" else did.joinToString("。"))
    }

    /** 本人がリゲルをタップして止めた: 追う・踊るのをやめ、動いていれば腕をその場で止める */
    fun stopAll(why: String) {
        scope.launch {
            val moving = pending != null || armMode in MOVING
            if (face?.recordingNow == true) { RabiLog.i("arm", "止めたので、録画も止めます"); face?.stopRecording() }
            stopFollowing(why, sendStop = false)
            if (moving && link.ready) { link.sendCmd(cmd("stop")); armMode = "stopped" }
            pending?.result?.complete(ArmResult.failed("止めました", "stopped"))
            endDanceLocal()
            cancelReservation(null)
        }
    }

    /** 設定の画面から: 拍が取れるかを、しばらく聞いて確かめる (腕は動かさない) */
    fun listenTest(): String {
        if (!music.hold(LISTEN_TEST)) return music.status.value.note
        listenJob?.cancel()
        listenJob = scope.launch { delay(LISTEN_TEST_MS); music.release(LISTEN_TEST) }
        return "${LISTEN_TEST_MS / 1000} 秒、端末で鳴っている音の拍を聞きます (音楽を流してください)"
    }

    /** リゲルの気分を、腕の光にも出す */
    fun setMood(m: Mood) {
        if (m == mood) return
        mood = m
        sendMood()
    }

    /** 設定で呼び名のしきい値を変えた: すぐ腕に送る */
    fun wakeSettingsChanged() = scope.launch(Dispatchers.Main) { voiceGate.send() }

    /** 道具の結果・状態の取得に付ける腕の様子 */
    fun statusJson(): JSONObject {
        val s = _status.value
        val o = JSONObject().put("enabled", settings.armEnabled).put("connected", s.ready).put("mode", s.mode)
            .put("following", s.following).put("follow_orientation", s.followTo ?: JSONObject.NULL)
            .put("dancing", s.dancing).put("dance_reserved", hasReservation()).put("program_version", s.version)
        s.q?.let { q -> o.put("angles", JSONObject().put("turn", r1(q[0])).put("tilt", r1(-q[1])).put("roll", r1(q[2]))) }
        return o
    }

    // ---------------------------------------------------------------- 頼む

    private fun check(): ArmResult? = when {
        !settings.armEnabled -> ArmResult.failed("AgentARM の機能がオフです。利用者に、スマホの ENVOY MCP の設定で「AgentARM」をオンにしてもらってください。", "off")
        !running -> ArmResult.failed("ENVOY MCP が AgentARM を動かしていません (MCP サーバーが動いている間だけ使えます)。", "off")
        !link.ready -> ArmResult.failed("AgentARM につながっていません (${link.state.value.label})。腕の電源と Bluetooth を確かめてください。", "disconnected")
        armMode == "fault" -> ArmResult.failed("腕が異常で止まっています。腕を確かめてください。", "fault")
        else -> null
    }

    private fun hasCamera() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** move・motion・dance は、腕のプログラムの版 2 から */
    private fun newCheck(): ArmResult? = if (armVersion >= 2) null
        else ArmResult.failed("AgentARM のプログラムが古いため、この動きはできません。UNO Q のプログラム (agentarm-d) を更新してください。", "old")

    private fun micCheck(): ArmResult? = if (music.hasPermission()) null
        else ArmResult.failed("音楽の拍を取るのに、マイクの許可が要ります (端末の出力の音を読むため。マイクの音は使いません)。利用者に、スマホの ENVOY MCP の設定の「AgentARM」で許可してもらってください。", "mic")

    private fun cameraCheck(): ArmResult? =
        if (hasCamera()) null else ArmResult.failed("カメラの許可がありません。利用者に、スマホの ENVOY MCP の設定の「AgentARM」で許可してもらってください。", "camera")

    /** 前面カメラで顔を見ながら、腕に face_user を頼む (顔の向きは、頼んでいる間ずっと送る) */
    private suspend fun aim(to: String?, what: String): ArmResult {
        if (!startCamera()) {
            if (!following) releaseCamera()
            return ArmResult.failed("${if (cameraBack) "背面" else "前面"}カメラを使えません (ほかのアプリが使っている・カメラが切られている、または ENVOY MCP の設定の画面を一度開くと使えるようになります)。", "camera")
        }
        val c = cmd("face_user").put("timeout", if (to != null) FACE_TO_TIMEOUT_S else FACE_TIMEOUT_S)
        if (to != null) c.put("to", to)
        val r = command(c, what, if (to != null) FACE_TO_WAIT_MS else FACE_WAIT_MS, face = true)
        // 背面カメラが上に届かなかった: 届いた所を上の端として覚え、上下はそこで止めて、すぐ向け直す (左右を追う)
        if (cameraBack && r.code == "limit" && learnRearCap()) return command(c, what, if (to != null) FACE_TO_WAIT_MS else FACE_WAIT_MS, face = true)
        return r
    }

    /** 腕が端まで動いて「届かない」と返した: 顔が上にあるなら、今の背面カメラの仰角を上の端として覚える */
    private fun learnRearCap(): Boolean {
        val up = pose.latest ?: return false
        if (lastRearUp < REAR_CAP_MIN_UP) return false            // 上ではなく、左右の端に当たった
        val el = FaceGeometry.rearElevation(doubleArrayOf(up[0].toDouble(), up[1].toDouble(), up[2].toDouble()))
        rearElMax = el
        log.add("背面カメラは上に ${r1(el)}° までしか向かないので、上下はそこで止めて、左右を追います")
        RabiLog.i("arm", "背面カメラの上の端: ${r1(el)}°")
        return true
    }

    /** 命令を送り、腕から done / fail が来るまで待つ (来なければ unknown) */
    private suspend fun command(c: JSONObject, what: String, waitMs: Long, face: Boolean = false): ArmResult {
        if (!link.ready) return ArmResult.failed("AgentARM につながっていません", "disconnected")
        ensureResumed()
        // 前の頼みは腕が置き換える (腕から busy が来る前に、こちらでも終わらせる)
        pending?.result?.complete(ArmResult.failed("次の頼みに置き換えました", "busy"))
        val p = Pending("e${++cmdN}", what, face)
        pending = p
        listenActive = false                          // 「聞いている」動きは、腕がこの頼みに置き換える
        this.face?.minIntervalMs = if (face) 0 else IDLE_FRAME_MS
        if (!link.sendCmd(c.put("id", p.id))) { pending = null; return ArmResult.failed("AgentARM に送れませんでした", "disconnected") }
        RabiLog.i("arm", "頼みました: $what")
        try {
            return withTimeoutOrNull(waitMs) { p.result.await() } ?: ArmResult.unknown("腕から返事がありません")
        } finally {
            if (pending === p) { pending = null; this.face?.minIntervalMs = IDLE_FRAME_MS }
        }
    }

    /** 止める言葉・止めるタップで止めた腕は、「再開」まで次の動きを断る。動かす前に、止めたままなら再開する (異常で止まったときは再開しない) */
    private fun ensureResumed() {
        if (armMode == "stopped") {
            RabiLog.i("arm", "止まっているので、再開してから頼みます")
            link.sendCmd(cmd("resume"))
            armMode = "idle"
        }
    }

    /** 結果を記録し、できたときの言葉を付ける */
    private fun settle(r: ArmResult, okText: String): ArmResult {
        val out = if (r.ok) ArmResult.ok(okText) else r
        _status.update { it.copy(last = out.message) }
        log.add("${if (out.ok) "●" else "○"} ${out.message}")
        RabiLog.i("arm", "${out.result}: ${out.message}")
        return out
    }

    // ---------------------------------------------------------------- 顔を追い続ける

    private fun stopFollowing(why: String, sendStop: Boolean) {
        if (!following) return
        following = false
        followJob?.cancel()
        aimJob?.cancel()
        val p = pending
        if (p?.face == true) {
            if (sendStop && link.ready) { link.sendCmd(cmd("stop")); armMode = "stopped" }
            p.result.complete(ArmResult.failed("追うのをやめました", "stopped"))
        }
        releaseCamera()
        _status.update { it.copy(following = false, followTo = null, face = null, last = why) }
        RabiLog.i("arm", why)
    }

    /** 追っている間の見張り: 顔が長く見えない・腕とつながらない・スマホが熱いときは、追うのをやめる */
    private fun startWatchdog() {
        followJob?.cancel()
        followJob = scope.launch {
            var downSince: Long? = null
            while (isActive && following) {
                delay(WATCH_MS)
                val t = now()
                downSince = if (link.ready) null else downSince ?: t
                when {
                    policy.lost(t) -> stopFollowing("顔が ${LOST_MIN} 分見えないので、顔を追うのをやめました", sendStop = false)
                    downSince != null && t - downSince > LINK_LOST_MS -> stopFollowing("AgentARM とつながらないので、顔を追うのをやめました", sendStop = false)
                    hot() -> stopFollowing("スマホが熱くなったので、顔を追うのをやめました", sendStop = true)
                }
            }
        }
    }

    private fun hot() = context.getSystemService(PowerManager::class.java)?.currentThermalStatus?.let { it >= PowerManager.THERMAL_STATUS_SEVERE } == true

    private fun reaim() {
        aimJob = scope.launch {
            RabiLog.i("arm", "顔がずれたので、向け直します")
            val r = aim(null, "顔に向け直す")
            policy.aimed(now(), r.ok)
            if (!r.ok) RabiLog.i("arm", "向け直せませんでした: ${r.message}")
            if (r.code in FATAL && following) stopFollowing("顔を追うのをやめました: ${r.message}", sendStop = false)
        }
    }

    // ---------------------------------------------------------------- カメラ

    /** カメラを始め、画像が届くまで待つ (届かなければ false) */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private suspend fun startCamera(): Boolean {
        cameraStopJob?.cancel()
        val f = face ?: FaceObserver(context, ::onFace).also { face = it }
        if (pending == null) f.minIntervalMs = IDLE_FRAME_MS
        if (f.running && f.back != cameraBack && !f.recordingNow) f.stop()     // 前面・背面を切り替える
        if (!f.recordingNow) f.back = cameraBack      // 録画中の切り替えは FaceObserver.switchCamera が受け持つ
        if (!f.running) f.start(owner ?: AlwaysOn().also { owner = it })
        val until = now() + CAMERA_WAIT_MS
        while (now() < until) {
            if (f.running && now() - f.lastFrameMs < FRESH_FRAME_MS) return true
            delay(50)
        }
        RabiLog.w("arm", "カメラから画像が届きません")
        return false
    }

    /** カメラを止める。録画している間は止めない (録画が終わると onRecordDone で止める) */
    private fun releaseCamera() {
        if (face?.recordingNow == true) return
        face?.stop()
    }

    /** 1 回だけ向けたあと、少ししてカメラを止める (追っているときは止めない) */
    private fun stopCameraSoon() {
        cameraStopJob?.cancel()
        cameraStopJob = scope.launch {
            delay(CAMERA_LINGER_MS)
            if (!following && pending?.face != true) { releaseCamera(); _status.update { it.copy(face = null) } }
        }
    }

    private fun onFace(s: FaceObserver.Sample?) {
        val up = pose.latest
        val t = now()
        val hv = if (s != null && up != null) {
            val upD = doubleArrayOf(up[0].toDouble(), up[1].toDouble(), up[2].toDouble())
            // 背面カメラ (カメラマン役) なら、背面カメラを顔へ向ける (h, v) にする
            if (s.back) FaceGeometry.errorsBack(upD, FaceGeometry.pixelToPhoneBack(s.px, s.py, s.w, s.h, s.fPx))?.let { (h, v) ->
                // 上に届かない端を知っていれば、上下はそこまでに抑えて、左右だけ追う
                lastRearUp = -v
                val cap = rearElMax
                if (cap != null) h to FaceGeometry.capBackV(v, FaceGeometry.rearElevation(upD), cap) else h to v
            }
            else FaceGeometry.errors(upD, FaceGeometry.pixelToPhone(s.px, s.py, s.w, s.h, s.fPx))
        } else null
        _status.update { it.copy(face = hv?.let { (h, v) -> r1(h) to r1(v) }) }
        val p = pending
        if (p?.face == true) {
            val age = s?.let { (t - it.atMs).coerceIn(0, 2000) } ?: 0
            link.sendObs(if (hv != null) {
                JSONObject().put("t", "obs").put("k", "face").put("h", r2(hv.first)).put("v", r2(hv.second)).put("conf", 0.9).put("age", age)
            } else {
                JSONObject().put("t", "obs").put("k", "noface").put("age", age)
            })
            return
        }
        val want = policy.sample(hv, t)
        if (want && following && link.ready && p == null && aimJob?.isActive != true) reaim()
    }

    // ---------------------------------------------------------------- 重力

    /** 重力は、動かしている間は 20 回/秒、待機中は 5 回/秒で送る (腕は face_user・orient に重力も使う。頼む前から届くように) */
    private fun startSensing() {
        if (!sensing) { pose.start(); sensing = true }
        gravJob?.cancel()
        gravJob = scope.launch {
            while (isActive) {
                val g = pose.latest
                if (g != null && link.ready) {
                    link.sendObs(JSONObject().put("t", "obs").put("k", "grav")
                        .put("g", JSONArray(FaceGeometry.gravityForArm(g).map { r2(it) })).put("age", pose.ageMs() ?: 0))
                }
                delay(if (pending != null && pending?.dance != true) 50 else 200)
            }
        }
    }

    // ---------------------------------------------------------------- 踊る

    /** 「聞いている」動きを頼めない理由 (頼めるなら null) */
    private fun listenBlocker(): String? = when {
        armVersion < 4 -> "腕のプログラムが古い (版 $armVersion)"
        !link.ready -> "腕につながっていない"
        dancing -> "踊っている最中"
        pending != null -> "ほかの動きの最中 (${pending?.what})"
        else -> null
    }

    /** 腕に「聞いている」動きを頼む (腕のプログラムが 4 以降。もう聞いていれば送り直さない)。頼めたら true */
    private fun startListen(timeoutS: Long = BEAT_WAIT_MS / 1000 + 1): Boolean {
        if (listenActive) return true
        if (listenBlocker() != null) return false
        listenActive = link.sendCmd(cmd("listen").put("id", "$LISTEN_ID${++cmdN}").put("timeout", timeoutS))
        return listenActive
    }

    /** 「聞いている」動きをやめてもらう (拍が取れなかった・止められた・音楽が止まった)。腕は聞き始めた姿勢へ戻る */
    private fun endListen() {
        if (!listenActive) return
        listenActive = false
        if (link.ready) link.sendCmd(cmd("listen").put("id", "$LISTEN_ID${++cmdN}").put("end", 1))
    }

    /** 拍がはっきりするまで待つ (取れなければ null) */
    private suspend fun waitBeat(): BeatTracker.Beat? {
        val until = now() + BEAT_WAIT_MS
        while (now() < until) {
            music.locked?.let { return it }
            delay(100)
        }
        return null
    }

    private fun onBeat(b: BeatTracker.Beat) {
        if (dancing && link.ready && !endingForMusic) sendBeat(b)
    }

    /**
     * 音楽が止まった (再生の知らせ・端末の音の出方から): MUSIC_STOP_GRACE_MS 待っても止まったままなら、踊りを終える
     * (拍を送るのをやめるだけだと、拍を少し保つ間と、腕が拍の途切れに気づくまで、数秒踊り続けるため、終わりを腕に頼む)。
     * 曲の切り替えで読み込み中・次の曲へ移る途中のときは続ける
     */
    private fun scheduleMusicStop() {
        if (!dancing || musicStopJob?.isActive == true) return
        musicStopJob = scope.launch {
            delay(MUSIC_STOP_GRACE_MS)
            if (!dancing || endingForMusic || !musicStopped()) return@launch
            endingForMusic = true
            RabiLog.i("arm", "音楽が止まったので、踊り終えます"); log.add("音楽が止まったので、踊り終えます")
            if (link.ready) link.sendCmd(cmd("dance").put("id", "e${++cmdN}").put("end", 1)) else endDanceLocal()
        }
    }

    /**
     * 音楽が止まっているか: 再生の知らせがあれば、それが一時停止・停止か (読み込み中・次の曲へ移る途中は止まっていない)。
     * 知らせが無ければ (通知へのアクセスが無い)、端末の音の出方で
     */
    private fun musicStopped(): Boolean {
        val np = nowPlaying.track.value ?: return watch.active && !watch.playing.value
        return np.paused
    }

    private fun sendBeat(b: BeatTracker.Beat) {
        val t = now()
        link.sendObs(JSONObject().put("t", "obs").put("k", "beat").put("bpm", r1(b.bpm)).put("ph", r3(b.phaseAt(t)))
            .put("e", r2(b.energy)).put("c", music.color).put("age", 0))
    }

    /** こちらの踊りの印を消し、音を聞くのをやめる (腕への知らせは呼ぶ側)。踊りの終わりを待つ仕事の中からは cancelJob = false */
    private fun endDanceLocal(cancelJob: Boolean = true) {
        if (!dancing) return
        dancing = false
        endingForMusic = false
        musicStopJob?.cancel()
        danceSeed = null
        if (cancelJob) danceJob?.cancel()
        music.release(LISTEN_DANCE)
        _status.update { it.copy(dancing = false) }
    }

    /** 音楽が流れ始めた・止まった (MusicWatch から)。設定の「音楽が流れたら踊る」がオンなら、少し流れ続けてから踊る */
    private fun onMusicPlaying(playing: Boolean) {
        _status.update { it.copy(musicPlaying = playing) }
        if (playing) musicStopJob?.cancel() else scheduleMusicStop()
        autoJob?.cancel()
        if (!playing) { autoTried = false; return }
        if (!running || !settings.armAutoDance) return
        autoJob = scope.launch {
            // 待つ間も、すぐ「聞いている」動き (踊れるときだけ。少し流れ続けるのを待つ間と、拍を待つ間に、腕が止まったままにならないように)。
            // 前の踊りが終わる途中などで頼めなければ、頼めるようになったらすぐ頼む (0.2 秒ごとに確かめる)
            var handed = false
            var why: String? = null
            try {
                val until = now() + AUTO_DELAY_MS       // 少し流れ続けてから (曲の切り替えなどで、すぐ止まることがある)
                while (now() < until) {
                    if (!listenActive && !autoTried) {
                        val b = autoBlocker() ?: listenBlocker()
                        if (b == null) { if (startListen((AUTO_DELAY_MS + BEAT_WAIT_MS) / 1000 + 1)) RabiLog.i("arm", "音楽が流れたので、聞いている動きを頼みました") }
                        else if (b != why) { why = b; RabiLog.i("arm", "聞いている動きを、まだ頼めません ($b)") }
                    }
                    delay(200)
                }
                if (!watch.playing.value || autoTried) return@launch
                autoBlocker()?.let { RabiLog.i("arm", "音楽が流れていますが、踊りません ($it)"); log.add("音楽が流れていますが、踊りません ($it)"); return@launch }
                autoTried = true
                handed = true                           // ここからは dance() が「聞いている」動きを受け継ぐ
                val r = dance(null, null, auto = true)
                RabiLog.i("arm", "音楽が流れたので踊る: ${r.result} ${r.message}")
            } finally {
                if (!handed && !dancing) endListen()    // 音楽がすぐ止まった・踊らないとき: 元の姿勢へ
            }
        }
    }

    /** 自動で踊らない理由 (踊ってよければ null) */
    private fun autoBlocker(): String? = when {
        !settings.armAutoDance -> "設定がオフ"
        !link.ready -> "腕につながっていない"
        armVersion < 2 -> "腕のプログラムが古い"
        !onCharger -> "スマホが腕に付いていない"
        following -> "顔を追っている (動画を見ている)"
        dancing || pending != null || danceStarting -> "ほかの動きの最中"
        hasReservation() -> "予約した踊りがある"
        context.getSystemService(AudioManager::class.java)?.mode == AudioManager.MODE_IN_COMMUNICATION -> "通話・音声の会話の最中"
        !music.hasPermission() -> "マイクの許可がない"
        else -> null
    }

    private fun extraText(r: ArmResult) = buildString {
        if ("lim" in r.extra) append("。関節の範囲の端までにしました")
        if ("slow" in r.extra) append("。速さの上限 (1 秒に約 28°) のため、頼まれたより遅く動きました")
    }

    private fun angleText(): String {
        val q = _status.value.q ?: return ""
        return "。今の向き: 左右 ${r1(q[0])}°、上下 ${r1(-q[1])}°、回転 ${r1(q[2])}°"
    }

    private fun stopSensing() {
        gravJob?.cancel(); gravJob = null
        if (sensing) { pose.stop(); sensing = false }
    }

    // ---------------------------------------------------------------- マグネット充電器

    private fun wirelessNow(): Boolean =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == BatteryManager.BATTERY_PLUGGED_WIRELESS

    /** つないだ直後は充電の種類がまだ決まっていないことがあるので、少し待って何回か見る */
    private fun checkAttached(tries: Int) {
        scope.launch {
            delay(if (tries == 0) 300 else 500)
            when {
                wirelessNow() -> {
                    if (!onCharger) { onCharger = true; voiceGate.send() }
                    if (!straightened) { RabiLog.i("charge", "充電器に付きました"); pendingStraighten = true; maybeStraighten() }
                }
                tries < 6 -> checkAttached(tries + 1)
            }
        }
    }

    /**
     * 充電が止まった: マグネット充電器から外したなら、腕を元の姿勢 (home) に戻す。
     * Qi の充電器は一瞬止まることがあるので、1 秒待っても止まったままのときだけ「外した」とみなす。USB を抜いたときは何もしない
     */
    private fun checkDetached() {
        if (!onCharger) { straightened = false; return }
        scope.launch {
            delay(1000)
            if (wirelessNow()) return@launch            // 一瞬止まっただけ
            onCharger = false
            voiceGate.send()
            straightened = false
            pendingStraighten = false
            RabiLog.i("charge", "充電器から外れたので、腕を元の姿勢に戻します")
            stopFollowing("スマホを腕から外したので、顔を追うのをやめました", sendStop = false)
            if (link.ready) settle(command(cmd("home"), "元の姿勢に戻す", HOME_WAIT_MS), "スマホを外したので、腕を元の姿勢に戻しました")
        }
    }

    /** 充電器に付いたら、まっすぐ (縦) に直しながら顔に向ける。カメラが使えなければ、縦に直すだけ */
    private fun maybeStraighten() {
        if (!pendingStraighten || !link.ready) return
        pendingStraighten = false
        straightened = true
        scope.launch {
            delay(STRAIGHTEN_DELAY_MS)                  // 磁石に付いて落ち着くのを待つ
            if (pending != null || following) return@launch
            RabiLog.i("arm", "充電器に付いたので、まっすぐ (縦) に直しながら、顔に向けます")
            var r = if (hasCamera()) aim(PORTRAIT, "まっすぐに直して顔に向ける").also { stopCameraSoon() }
                else ArmResult.failed("カメラの許可がありません", "camera")
            if (r.code == "camera") r = command(cmd("orient").put("to", PORTRAIT), "まっすぐ (縦) に直す", ORIENT_WAIT_MS)
            settle(r, "スマホを付けたので、まっすぐに直しました")
        }
    }

    // ---------------------------------------------------------------- 腕からの知らせ

    private fun onArmMessage(m: JSONObject) {
        when (m.optString("t")) {
            "net" -> wifi.onNet(m)
            "done" -> finish(m.optString("id"), doneResult(m))
            "fail" -> {
                // 「聞いている」動きが踊りに置き換わったのは、ふつうのこと (記録に出さない)
                if (m.optString("id").startsWith(LISTEN_ID) && m.optString("why") == "busy") return
                log.add("腕: できません (${m.optString("why")}${m.optString("msg").takeIf { it.isNotBlank() }?.let { " $it" } ?: ""})")
                finish(m.optString("id"), ArmResult.failed(reason(m.optString("why"), m.optString("msg")), m.optString("why")))
            }
            "fault" -> {
                log.add("腕: 異常で止まりました (${m.optString("code")})")
                armMode = "fault"
                _status.update { it.copy(mode = "fault", last = "腕が異常で止まりました (${m.optString("code")})") }
                pending?.result?.complete(ArmResult.failed("腕が異常で止まりました (${m.optString("code")})", "fault"))
                stopFollowing("腕が異常で止まったので、顔を追うのをやめました", sendStop = false)
            }
            "hello" -> {
                armVersion = m.optInt("v", 1)
                armIp = m.optString("ip").takeIf { it.isNotBlank() }
                _status.update { it.copy(calibrated = m.optBoolean("calibrated"), version = armVersion) }
                voiceGate.send()
            }
            "state" -> {
                armMode = m.optString("mode")
                val q = m.optJSONArray("q")?.takeIf { it.length() == 3 }?.let { a -> List(3) { r1(a.optDouble(it)) } }
                _status.update { it.copy(mode = armMode, q = q ?: it.q) }
            }
            "event" -> when (m.optString("k")) {
                "phone" -> if (!m.optBoolean("attached", true)) {
                    stopFollowing("腕がスマホが外れたと知らせたので、顔を追うのをやめました", sendStop = false)
                }
                "wake" -> {
                    log.add("腕: 呼び名を聞きました (本人らしさ ${"%.2f".format(m.optDouble("s"))})")
                    onWake?.invoke(m.optDouble("s"))
                }
            }
        }
    }

    private fun doneResult(m: JSONObject): ArmResult {
        val extra = mutableSetOf<String>()
        if (m.optBoolean("lim")) extra += "lim"
        if (m.optBoolean("slow")) extra += "slow"
        m.optString("end").takeIf { it.isNotBlank() }?.let { extra += "end:$it" }
        return ArmResult.ok("", extra)
    }

    private fun finish(id: String, r: ArmResult) {
        val p = pending ?: return
        if (p.id == id) p.result.complete(r)
    }

    private fun reason(why: String, msg: String) = when (why) {
        "noface" -> "顔が見つかりません"
        "limit" -> "腕の届く範囲の外です"
        "nograv", "flat" -> "スマホの傾きが測れません (平らに置かれているかもしれません)"
        "manual" -> "腕のデバッグ画面で操作中です"
        "not_calibrated" -> "腕の較正がまだです"
        "stopped" -> "腕が止まっています"
        "busy" -> "腕がほかの動きの最中です"
        "timeout" -> "時間内に向けきれませんでした"
        "stall" -> "腕に負荷がかかりすぎて止まりました"
        "detached" -> "スマホが腕から外れています"
        "disconnected" -> "腕とのつながりが切れました"
        "nobeat" -> "腕に音楽の拍が届きませんでした"
        "bad_request" -> "腕が頼みの形を受け付けませんでした" + if (msg.isNotBlank()) " ($msg)" else ""
        else -> listOf(why, msg).filter { it.isNotBlank() }.joinToString(": ").ifBlank { "腕ができないと答えました" }
    }

    // ---------------------------------------------------------------- 小物

    private fun sendMood() {
        if (link.ready) link.sendCmd(cmd("mood").put("m", mood.name.lowercase()))
    }

    private fun cmd(doWhat: String) = JSONObject().put("t", "cmd").put("do", doWhat)
    private fun toText(to: String?) = when (to) { PORTRAIT -> " (縦にも合わせました)"; LANDSCAPE -> " (横にも合わせました)"; else -> "" }
    private fun now() = SystemClock.elapsedRealtime()
    private fun r2(x: Double) = (x * 100).roundToInt() / 100.0
    private fun r1(x: Double) = (x * 10).roundToInt() / 10.0
    private fun r3(x: Double) = (x * 1000).roundToInt() / 1000.0

    /** カメラを裏 (サービス) で使うための、いつも「動いている」持ち主 (CameraX は持ち主が動いている間だけカメラを開く) */
    private class AlwaysOn : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    companion object {
        private const val APP = "envoymcp"
        const val PORTRAIT = "portrait"
        const val LANDSCAPE = "landscape"
        /** 腕が動いているモード */
        private val MOVING = setOf("moving", "face", "orient")
        /** これで失敗したら、追い続けても無駄なのでやめる */
        private val FATAL = setOf("camera", "disconnected", "stopped", "manual", "not_calibrated", "fault", "off", "detached")
        /** 腕の時間の上限 (face_user の timeout。秒) と、こちらで待つ長さ (腕の上限より少し長く) */
        private const val FACE_TIMEOUT_S = 8
        private const val FACE_TO_TIMEOUT_S = 14
        private const val FACE_WAIT_MS = 12_000L
        private const val FACE_TO_WAIT_MS = 18_000L
        private const val ORIENT_WAIT_MS = 16_000L
        private const val HOME_WAIT_MS = 12_000L
        /** カメラの最初の画像を待つ長さ・画像が届いているとみなす新しさ */
        private const val CAMERA_WAIT_MS = 3_000L
        private const val FRESH_FRAME_MS = 600L
        /** 腕に顔の向きを送っていない間に、顔を調べる間隔 (追い続けるとき。電池と熱のため) */
        private const val IDLE_FRAME_MS = 200L
        /** 1 回だけ向けたあと、カメラを止めるまで */
        private const val CAMERA_LINGER_MS = 800L
        /** 録画の長さの上限 (分) と既定 */
        const val RECORD_MAX_MIN = 30
        const val RECORD_DEFAULT_MIN = 10
        private const val RECORD_WATCH_MS = 5_000L
        private const val RECORD_SAVE_WAIT_MS = 10_000L
        /** 顔がこれ以上 (度) 上にあるときの「届かない」を、上に届かなかったとみなす */
        private const val REAR_CAP_MIN_UP = 2.0
        private const val STRAIGHTEN_DELAY_MS = 1_500L
        private const val WATCH_MS = 5_000L
        /** 顔がこれだけ見えなければ、追うのをやめる (分) */
        private const val LOST_MIN = 10
        private const val LINK_LOST_MS = 60_000L
        // 任意の動き (腕の controller.py と同じ上限)
        private const val MOVE_DEG_S = 20.0
        private const val SLOW_DEG_S = 10.0
        private const val VMAX_DEG_S = 27.5
        const val MOTION_KEYS = 6
        const val MOTION_REPEAT = 10
        /** 動きの並びの長さの上限 (秒。道具の時間の上限より短く) */
        private const val MOTION_MAX_S = 25.0
        /** 表情の動きを終えてから、腕の様子が「動いている」のまま残っていても、自分の動きの名残とみなす長さ */
        private const val GESTURE_SETTLE_MS = 1_500L
        private const val WAIT_MARGIN_MS = 3_000L
        // 踊る
        const val STYLE_CHILL = "chill"
        const val STYLE_GROOVE = "groove"
        const val STYLE_PARTY = "party"
        private const val AUTO_STYLE = STYLE_GROOVE
        private const val DEFAULT_AMP = 0.65
        /** 踊りの予約を待つ長さ・確かめる間隔 */
        private const val RESERVE_MS = 3 * 60_000L
        private const val RESERVE_CHECK_MS = 2_000L
        /** 拍が取れるまで待つ長さ (BeatTracker は 4 秒ぶんの音が要る) */
        private const val BEAT_WAIT_MS = 9_000L
        private const val EARLY_FAIL_MS = 800L
        private const val DANCE_END_WAIT_MS = 6_000L
        private const val AUTO_DELAY_MS = 3_000L
        /** 音楽が止まってから、踊りを終えるまで (曲の切り替えで一瞬止まるのは続ける) */
        private const val MUSIC_STOP_GRACE_MS = 700L
        private const val LISTEN_TEST_MS = 20_000L
        private const val LISTEN_DANCE = "dance"
        /** 「聞いている」動きの命令の番号の頭 (結果を待たない) */
        private const val LISTEN_ID = "ls"
        private const val LISTEN_TEST = "test"
    }
}
