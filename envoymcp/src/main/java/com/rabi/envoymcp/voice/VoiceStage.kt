package com.rabi.envoymcp.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.HomeActivity
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.core.Mood
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.music.SpectrumRing
import com.rabi.envoymcp.ui.avatar.Avatar
import com.rabi.envoymcp.ui.avatar.AvatarGeometry
import com.rabi.envoymcp.ui.avatar.AvatarSignals
import com.rabi.envoymcp.ui.avatar.AvatarStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * ChatGPT の音声モードの間、画面全体をホームと同じリゲルで覆う (あたかもリゲルと話しているように見せる)。
 * 下の真ん中にリゲル風の × を置き、押したら ChatGPT を終える (McpGraph.closeChatGpt: 覆ったまま ChatGPT を終了 → ホーム → 覆いを消す)。
 * - 音声モードかどうか: 端末が通話モード (MODE_IN_COMMUNICATION) で、前面が ChatGPT のとき。
 *   音声モードの間は ChatGPT が通話モードの持ち主になり、閉じると数秒で通常に戻る。
 *   ChatGPT は音声モードでも同じ画面 (MainActivity) なので、画面の名前では見分けられない
 * - 前面のアプリは、操作の方式に合わせてユーザー補助か ADB で読む。他のアプリが前に出たと分かったら消す (ChatGPT が道具で X などを開いたとき。そのアプリが見える)。
 *   前面を確かめられない (ADB が切れている) ときは、リゲルのタップで開いた音声モードだけ覆い続け、それ以外では新しく出さない。
 *   切れていれば、見張りの間に自分でつなぎ直す (Wi-Fi が切れて戻ったあと、ADB は自然にはつながらない)
 * - 覆うのは、リゲルのタップで始めた会話だけ (ChatGPT のアプリから直接始めた会話は覆わない)
 * - リゲルのタップで音声モードを開くとき (expectVoice) は、ChatGPT が通話モードに入る (1.6 秒ほどかかる) のを待たずに出す。
 *   10 秒たっても音声モードにならなければ消す
 * - ChatGPT のマイクのオフ・文字の入力欄も覆うので使えない
 * - リゲルが代わりに電話している間 (holdForRigelCall〜afterRigelCall) は覆わず、電話の画面を見せる。電話が通話モードを持つので、
 *   リゲルのタップで始めた会話の印は消さずに残す。電話が終わり、ChatGPT の音声モードに戻れば覆い直し、戻らなければホームのリゲルを出す
 *   (電話のあとに ChatGPT の画面を見せない)。
 *   覆い直すのは、電話が終わったと分かった時点で、前面を確かめずにすぐ (前面が ChatGPT になるのを待つと、電話の画面が消えてから
 *   1.2 秒ほど ChatGPT の画面が見える)。ChatGPT の音声モードが戻るのは電話のあと 7.6 秒ほどかかり、その間も端末は
 *   通話中のままなので、最長 AFTER_CALL_MAX_MS 覆ったまま待つ。戻らなければ、ホームを出してから覆いを消す (ChatGPT の画面を見せない)
 * - 声に合わせて動かす: 本人が設定でオンにし、マイクの許可があるときだけ、出力の波形 (Visualizer) から ChatGPT の声の大きさを取る。マイクの音は使わない。
 *   端末全体の出力 (Visualizer 0) には ChatGPT の声 (通話の出力) が入らないので、ChatGPT が声を流している流れ (音声のセッション) を
 *   ADB の dumpsys audio で探して、その流れに付ける (話している間だけ音節ごとに揺れ、黙ると 0)。ADB が要る
 */
class VoiceStage(private val g: McpGraph) {
    private val am = g.context.getSystemService(AudioManager::class.java)
    private var watch: Job? = null
    @Volatile private var showing = false
    /** 音声モードの全画面のリゲルが出ているか */
    val isShowing get() = showing

    private val _level = MutableStateFlow(0f)
    /** ChatGPT の声の大きさ 0..1 (取れないときは 0 のまま) */
    val level: StateFlow<Float> = _level
    private val _speaking = MutableStateFlow(false)
    /** 話している (声が途切れても少しの間は話していることにする) */
    val speaking: StateFlow<Boolean> = _speaking
    private val _pulse = MutableStateFlow(0)
    /** 声の区切り (音節の立ち上がり) ごとに増える */
    val pulse: StateFlow<Int> = _pulse
    private val _peak = MutableStateFlow(0f)
    /** これまでに取れた声の大きさの最大 (設定の画面で、取れているかを見る) */
    val peak: StateFlow<Float> = _peak

    /** リゲルのタップで音声モードを開いた: この時刻までは、通話モードになるのを待つ */
    @Volatile private var expectUntil = 0L
    /** リゲルの × で閉じた: 音声モードが終わるまで出し直さない */
    @Volatile private var dismissed = false
    /** リゲルのタップで開いた音声モード (前面は ChatGPT と分かっているので、前面を確かめられなくても覆い続ける) */
    @Volatile private var trusted = false
    /** リゲルのタップで開いてから、ChatGPT が前に出たのを見たか (見るまでは、前のアプリが前にあっても覆いを消さない) */
    @Volatile private var seenChatGpt = false
    /** リゲルのタップで開いた時刻 */
    @Volatile private var openedAt = 0L
    /** リゲルの × で ChatGPT を終えている途中 (ホームが出るまで覆いを消さない) */
    @Volatile private var closing = false
    @Volatile private var adbTriedAt = 0L
    /** リゲルが代わりに電話している (その前の音声モードが、リゲルのタップで始めたもの) */
    @Volatile private var callHold = false
    /** 前の通話モードが、電話 (MODE_IN_CALL) だったか */
    @Volatile private var wasInCall = false

    private var vis: Visualizer? = null
    private var sessionJob: Job? = null
    private var loud = false
    private var lastLoudAt = 0L

    /** 最後に見た通話モード (知らせと見回りの両方から来るので、変わったときだけ動く) */
    @Volatile private var lastMode = -100

    fun start() {
        am.addOnModeChangedListener(g.context.mainExecutor) { changed(it) }
        changed(am.mode)
    }

    private fun changed(mode: Int) { if (mode != lastMode) { lastMode = mode; onMode(mode) } }

    /** 設定を変えたとき */
    fun refresh() { stopLevel(); lastMode = am.mode; onMode(lastMode) }

    private fun onMode(mode: Int) {
        val callEnded = wasInCall && mode != AudioManager.MODE_IN_CALL
        wasInCall = mode == AudioManager.MODE_IN_CALL
        // 調べるための記録 (記録の決まりで数字は伏せられるので、言葉で残す)
        RabiLog.i("stage", "mode " + when (mode) {
            AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
            AudioManager.MODE_NORMAL -> "normal"
            AudioManager.MODE_IN_CALL -> "in_call"
            AudioManager.MODE_RINGTONE -> "ringtone"
            else -> "other"
        } + if (g.settings.voiceStage) "" else " (stage off)")
        // モニターにつながっていれば、音声モードの声をモニターのスピーカーから出す (出せる機器なら)
        g.monitorStage.routeCallAudio(mode == AudioManager.MODE_IN_COMMUNICATION)
        if (mode == AudioManager.MODE_IN_COMMUNICATION) {
            // 音声モードが始まった: もう待たない (この後、通常に戻ればすぐ消す)
            expectUntil = 0L
            // 覆うのは、リゲルのタップで始めた会話だけ。ChatGPT のアプリから直接始めた会話は覆わない
            // (他のアプリを見ながら話せるように)
            if (g.settings.voiceStage && !dismissed && trusted) startWatch()
        } else {
            dismissed = false
            when {
                callHold && mode == AudioManager.MODE_IN_CALL -> stopWatch()     // リゲルの電話の間は覆わない (電話の画面を見せる)。会話の印は残す
                callHold -> {}                                                    // 電話が終わった直後 (下で覆い直す)
                !expecting() -> { trusted = false; stopWatch() }
            }
        }
        if (callHold && callEnded) coverAfterCall()
    }

    /**
     * リゲルの電話が終わった: 前面を確かめずに、すぐリゲルで覆う (電話の画面が消えて ChatGPT の画面が見えないうちに)。
     * リゲルのタップで開くときと同じく、しばらくは ChatGPT が前に出ていなくても覆い続ける
     */
    private fun coverAfterCall() {
        if (!trusted || !g.settings.voiceStage || dismissed) return
        RabiLog.i("stage", "cover after rigel call")
        seenChatGpt = false
        openedAt = System.currentTimeMillis()
        expectUntil = openedAt + AFTER_CALL_MAX_MS      // 音声モードが戻るまで、前面が ChatGPT でなくても覆い続ける
        show()
        startWatch()
    }

    /** リゲルの電話をかけた (電話の画面が出る): すぐ覆いを消して、電話の画面 (番号は隠してある) を見せる */
    fun yieldToCall() {
        if (!callHold) return
        g.scope.launch(Dispatchers.Main) {
            delay(CALL_SCREEN_MS)      // 電話の画面が出るのを待つ (先に消すと ChatGPT の画面が見える)
            if (callHold) { RabiLog.i("stage", "yield to the call screen"); stopWatch() }
        }
    }

    /** リゲルが代わりに電話を始める (RigelCaller.start)。リゲルのタップで始めた音声モードの途中なら、電話のあとに戻せるように印を残す */
    fun holdForRigelCall() {
        if (trusted && g.settings.voiceStage) {
            callHold = true; RabiLog.i("stage", "hold for rigel call")
            // 本人確認 (指紋) の画面が閉じたところ: 見張りの次の見回りを待たずに覆う (待つと 0.1 秒 ChatGPT の画面が見える)
            if (!dismissed) show()
        }
    }

    /**
     * リゲルの電話が終わり、ChatGPT の音声モードを開き直す (McpGraph.reopenVoiceAfterCall): すぐリゲルで覆い、開き直すまで覆い続ける
     * (電話のあと 7 秒ほど端末が通話中のままで、その間に音声モードを終えた ChatGPT の画面を見せない)。ホームには戻さない
     */
    fun releaseHold() {
        callHold = false
        RabiLog.i("stage", "rigel call ended (reopen voice)")
        if (!g.settings.voiceStage) return
        trusted = true; dismissed = false
        coverAfterCall()
    }

    /**
     * リゲルの電話が終わった (RigelCaller の結果)。ChatGPT の音声モードに戻れば覆い直す。AFTER_CALL_MAX_MS 待っても戻らない・通話なしが続けば
     * (電話で音声の会話が終わった)、ホームのリゲルを出す
     */
    fun afterRigelCall() {
        if (!callHold) return
        callHold = false
        RabiLog.i("stage", "rigel call ended")
        coverAfterCall()      // LINE の通話 (電話の通話モードにならない) は、ここで覆う。電話ならもう覆っている
        g.scope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            var normalSince = 0L
            while (System.currentTimeMillis() - t0 < AFTER_CALL_MAX_MS) {
                val mode = am.mode
                // 通話モードの持ち主が ChatGPT か (LINE の通話が終わった直後は、まだ LINE が持っていることがある)
                if (mode == AudioManager.MODE_IN_COMMUNICATION && g.phoneForeground() == McpGraph.CHATGPT) {
                    expectUntil = 0L      // ここからは、ふだんの見張り (音声モードで前面が ChatGPT なら覆う)
                    if (trusted && !dismissed && g.settings.voiceStage) startWatch()
                    RabiLog.i("stage", "voice resumed after rigel call")
                    return@launch
                }
                // 通話なしが続いた: 音声の会話は終わっている
                if (mode == AudioManager.MODE_NORMAL) {
                    if (normalSince == 0L) normalSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - normalSince > NORMAL_HOLD_MS) break
                } else normalSince = 0L
                delay(FAST_POLL_MS)
            }
            // 音声モードに戻らなかった: ホームのリゲルを出してから覆いを消す (ChatGPT の画面を見せない)
            RabiLog.i("stage", "voice not resumed after rigel call: home")
            trusted = false
            withContext(Dispatchers.Main) {
                runCatching {
                    g.context.startActivity(android.content.Intent(g.context, HomeActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }.onFailure { RabiLog.w("stage", "home failed ${it.javaClass.simpleName}") }
            }
            delay(HOME_SETTLE_MS)
            expectUntil = 0L
            withContext(Dispatchers.Main) { stopWatch() }
        }
    }

    private fun expecting() = System.currentTimeMillis() < expectUntil

    /** リゲルのタップで ChatGPT の音声モードを開く直前に呼ぶ: すぐリゲルで覆う */
    fun expectVoice() {
        if (!g.settings.voiceStage) return
        dismissed = false; trusted = true; seenChatGpt = false
        openedAt = System.currentTimeMillis()
        expectUntil = openedAt + EXPECT_MS
        show()
        startWatch()
    }

    /** リゲルのタップで開こうとしたが、ChatGPT を開けなかった */
    fun cancelExpect() { expectUntil = 0L; trusted = false; stopWatch() }

    /**
     * リゲルの × を押した: 見張りを止める。覆いはまだ消さない (ChatGPT を終えてホームが出るまで、ChatGPT の画面を見せない)。
     * 音声モードが終わっても出し直さない。終わったら endClose
     */
    fun beginClose() { closing = true; dismissed = true; expectUntil = 0L; trusted = false; watch?.cancel(); watch = null }

    /** ChatGPT を終えてホームを出した: 覆いを消す */
    fun endClose() { closing = false; hide() }

    /** 録画の画面が前に出た: すぐ覆いを消す (閉じたあと、ChatGPT と話していれば見張りが出し直す) */
    fun yieldToRecord() { hide() }

    private fun startWatch() {
        if (watch?.isActive == true) return
        RabiLog.i("stage", "watch start")
        watch = g.scope.launch(Dispatchers.IO) {
            try {
                while (isActive && g.settings.voiceStage && !dismissed && (am.mode == AudioManager.MODE_IN_COMMUNICATION || expecting())) {
                    if (g.usingAdb() && !g.adb.isConnected) reconnectAdb()
                    val fg = g.phoneForeground()
                    when {
                        fg == McpGraph.CHATGPT -> { seenChatGpt = true; show() }
                        // 前面を確かめられない: リゲルのタップで ChatGPT を開いている途中だけ覆う。それ以外は今のまま (新しくは出さない)
                        // (開いたあとも「分からない」で覆うと、ChatGPT が開いたほかのアプリの操作の上に出てしまう)
                        fg == null -> if (trusted && expecting()) show()
                        // 開いている途中 (まだ ENVOY MCP の画面が前) は待つ。それ以外のアプリが前なら消す
                        fg == g.context.packageName && expecting() -> {}
                        // リゲルのタップで開いた直後で、ChatGPT がまだ前に出ていない: 前のアプリが残っているだけなので消さない
                        // (他のアプリの上から開いたとき、ここで消すと覆いが 0.5 秒消えて ChatGPT の画面が見える。デジタル アシスタントから開くと毎回こうなる)
                        trusted && !seenChatGpt && System.currentTimeMillis() < openedAt + EXPECT_MS -> {}
                        else -> { if (showing) RabiLog.i("stage", "hide (front: ${fg ?: "?"})"); hide() }
                    }
                    delay(if (expecting() || !showing) FAST_POLL_MS else POLL_MS)
                }
            } finally {
                expectUntil = 0L
                hide()
            }
        }
    }

    private fun stopWatch() { watch?.cancel(); watch = null; hide() }

    /** ADB が切れていれば、裏でつなぎ直す (10 秒に 1 回まで。見張りを止めないよう待たない) */
    private fun reconnectAdb() {
        val now = System.currentTimeMillis()
        if (!g.adb.paired || now - adbTriedAt < ADB_RETRY_MS) return
        adbTriedAt = now
        g.scope.launch(Dispatchers.IO) { runCatching { g.adb.ensureConnected() } }
    }

    private fun show() {
        // 録画の画面が出ている間は覆わない (覆いはほかのアプリの上に出るので、カメラの映像と止めるボタンを隠してしまう)
        if (g.recordScreenVisible) { hide(); return }
        if (!showing) {
            showing = true; RabiLog.i("stage", "show"); startLevel()
            // 端末の音を聞いたままになっていないかを記録に残す
            if (g.music.listening) RabiLog.i("stage", "music listener on (${g.music.holdersText()})")
        }
        g.overlay.setStage(true)
    }

    private fun hide() {
        if (closing) return
        if (showing) { showing = false; RabiLog.i("stage", "hide"); stopLevel() }
        g.overlay.setStage(false)
    }

    // ---------------------------------------------------------------- 声の大きさ (試験)

    private fun startLevel() {
        if (vis != null || !g.settings.voiceStageLevel) return
        if (g.context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        vis = runCatching { newVisualizer(0) }.onFailure { RabiLog.w("stage", "visualizer failed: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()
        sessionJob = g.scope.launch(Dispatchers.IO) { attachToChatGpt() }
    }

    private fun newVisualizer(session: Int) = Visualizer(session).apply {
        captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
        // 既定 (NORMALIZED) は小さな音も最大まで引き伸ばすので、声の大小が分からない。流れているままの大きさで取る
        scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
        setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(v: Visualizer?, wave: ByteArray?, rate: Int) { wave?.let { onWave(it) } }
            override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {}
        }, Visualizer.getMaxCaptureRate(), true, false)
        val rc = setEnabled(true)
        if (rc != Visualizer.SUCCESS) RabiLog.w("stage", "visualizer enable failed (code ${-rc})")
    }

    /**
     * ChatGPT が音声モードで声を流している流れ (音声のセッション) を ADB の dumpsys audio で探し、その流れに Visualizer を付ける。
     * 見つかるまでは短い間隔で、見つかったあとも流れが作り直されていないかをときどき確かめる
     */
    private suspend fun attachToChatGpt() {
        val uid = runCatching { g.context.packageManager.getPackageUid(McpGraph.CHATGPT, 0) }.getOrNull() ?: return
        var tries = 0
        while (showing) {
            delay(if (session == 0) SESSION_POLL_MS else SESSION_RECHECK_MS)
            if (!showing) return
            if (!g.adb.isConnected) continue
            val text = runCatching {
                g.adb.exec("dumpsys audio | grep 'u/pid:$uid/' | grep USAGE_VOICE_COMMUNICATION | grep 'state:started'", 5_000).text
            }.getOrNull()
            val found = text?.let { SESSION_RE.find(it) }?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
            if (found != null && found != session) withContext(Dispatchers.Main) { useSession(found) }
            if (session == 0 && ++tries >= SESSION_TRIES) { RabiLog.i("stage", "chatgpt voice stream not found"); return }
        }
    }

    /** いま Visualizer を付けている ChatGPT の声の流れ (0 = まだ見つけていない) */
    @Volatile private var session = 0

    private fun useSession(session: Int) {
        if (!showing) return
        val v = runCatching { newVisualizer(session) }
            .onFailure { RabiLog.w("stage", "visualizer on chatgpt failed: ${it.javaClass.simpleName} ${it.message}") }.getOrNull() ?: return
        vis?.let { runCatching { it.enabled = false; it.release() } }
        vis = v
        this.session = session
        RabiLog.i("stage", "visualizer on chatgpt voice stream")
    }

    private fun stopLevel() {
        sessionJob?.cancel(); sessionJob = null
        vis?.let { runCatching { it.enabled = false; it.release() } }
        vis = null
        session = 0
        _level.value = 0f; _speaking.value = false
    }

    private fun onWave(w: ByteArray) {
        var sum = 0.0
        for (b in w) { val x = ((b.toInt() and 0xff) - 128) / 128.0; sum += x * x }
        val lv = (sqrt(sum / w.size).toFloat() * LEVEL_GAIN).coerceIn(0f, 1f)
        _level.value = _level.value * 0.5f + lv * 0.5f
        if (lv > _peak.value) _peak.value = lv
        val now = System.currentTimeMillis()
        if (lv > LOUD) { lastLoudAt = now; if (!loud) { loud = true; _pulse.value++ } } else if (lv < QUIET) loud = false
        val sp = now - lastLoudAt < SPEAK_HOLD_MS
        if (sp != _speaking.value) RabiLog.i("stage", if (sp) "chatgpt speaking" else "chatgpt quiet")
        _speaking.value = sp
    }

    companion object {
        private const val POLL_MS = 1_500L
        /** 開いている途中・まだ出していない間は、前面のアプリを短い間隔で確かめる */
        private const val FAST_POLL_MS = 400L
        /** リゲルのタップで開いてから、音声モード (通話モード) になるのを待つ長さ */
        private const val EXPECT_MS = 10_000L
        private const val LEVEL_GAIN = 4f
        /** ChatGPT の声の流れを探す回数と間隔 (音声モードになってから流れが出るまで少しかかる) */
        private const val SESSION_TRIES = 15
        private const val SESSION_POLL_MS = 2_000L
        /** 見つけたあと、流れが作り直されていないかを確かめる間隔 */
        private const val SESSION_RECHECK_MS = 8_000L
        private val SESSION_RE = Regex("""session(?:Id)?:(\d+)""")
        private const val LOUD = 0.12f
        private const val QUIET = 0.06f
        /** 声が途切れても話していることにする長さ (文の間の息つぎで聞く顔に戻らないように。間の表情はアバターが出す) */
        private const val SPEAK_HOLD_MS = 1_200L
        private const val ADB_RETRY_MS = 10_000L
        /** リゲルの電話のあと、ChatGPT の音声モードに戻るのを待つ最長 (戻るのに 7.6 秒ほどかかる) */
        private const val AFTER_CALL_MAX_MS = 20_000L
        /** 通話なしがこれだけ続いたら、音声の会話は終わったとみなす */
        private const val NORMAL_HOLD_MS = 2_000L
        /** 電話をかけてから、電話の画面が出るまで待つ (覆いを消す前に) */
        private const val CALL_SCREEN_MS = 500L
        /** ホームを出してから覆いを消すまで (ホームが前に出るのを待つ) */
        private const val HOME_SETTLE_MS = 700L
    }
}

/** ホーム画面と同じ背景の色 */
internal val HOME_BG = Color(0xFF080B10)

/**
 * 音声モードの間に画面全体に出すもの: ホームと同じリゲル (位置・大きさ・3D)、操作中の言葉、下の真ん中のリゲル風の ×。
 * リゲルの顔: 操作中は操作の顔、ChatGPT が話している間は声に合わせて話す顔 (声が取れるとき)、それ以外は聞く顔
 */
@Composable
fun StageContent(g: McpGraph) {
    val st by g.state.collectAsState()
    val arm by g.arm.status.collectAsState()
    val lv by g.voiceStage.level.collectAsState()
    val speaking by g.voiceStage.speaking.collectAsState()
    val pulse by g.voiceStage.pulse.collectAsState()
    val density = LocalDensity.current
    val mood = when {
        st.mood == Mood.ACTING -> Mood.ACTING
        speaking -> Mood.SPEAKING
        else -> Mood.LISTENING
    }
    // 下のナビゲーションの高さ (オーバーレイの窓には画面の余白が届かないので、自分で測る)
    val navPx = remember {
        g.context.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
    }
    Box(Modifier.fillMaxSize().background(HOME_BG)) {
        Avatar(
            mood, AvatarStyle.byId(g.settings.avatar), lv,
            Modifier.fillMaxSize()
                .semantics { contentDescription = "リゲル。操作中にタップすると止めます" }
                .pointerInput(Unit) {
                    // 球の上 (少し外側まで) を短くタップしたときだけ。指を動かした (球をつまんだ) ものはタップにしない
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val c = AvatarGeometry.center(w, h, true)
                        val r = AvatarGeometry.radius(w, h, true, with(density) { 360.dp.toPx() })
                        if ((down.position - c).getDistance() > r * 1.25f) return@awaitEachGesture
                        var far = 0f
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            far = maxOf(far, (ch.position - down.position).getDistance())
                            if (!ch.pressed) break
                        }
                        if (far <= viewConfiguration.touchSlop) g.stageTap()
                    }
                },
            signals = AvatarSignals(st.wakeSeq, st.stepSeq, st.stepKind, st.doneSeq, 0, pulse, st.expression, st.expressionSeq, holdUntilSpeaking = true,
                expressionHoldMs = st.expressionHoldMs),
            fullScreen = true, allow3d = g.settings.avatar3d,
        )
        // AgentARM が踊っている間は、聞いている音楽をまわりにカラフルなスペクトラムで出す
        // (踊っていないときは、端末の音を聞いていても出さない)
        if (arm.dancing) SpectrumRing(g, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = with(density) { navPx.toDp() } + 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (st.mood == Mood.ACTING && st.caption.isNotBlank()) {
                Text(st.caption, color = Color(0xFFE8ECF2), fontSize = 20.sp, lineHeight = 28.sp, textAlign = TextAlign.Center, maxLines = 2,
                    modifier = Modifier.padding(horizontal = 28.dp))
                Spacer(Modifier.height(24.dp))
            }
            RigelCloseButton(g, Modifier.size(CLOSE_DP.dp).semantics { contentDescription = "会話を終えて閉じる" }.clickable { g.closeChatGpt() })
        }
    }
}

/** リゲル風の × (リゲルと同じ暗いガラスの丸に、リゲルの光の縁と白い ×) */
@Composable
internal fun RigelCloseButton(g: McpGraph, modifier: Modifier) {
    val glow = AvatarStyle.byId(g.settings.avatar).glow
    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(Brush.radialGradient(listOf(Color(0xFF1A2533), HOME_BG), center = center, radius = r), radius = r)
        drawCircle(glow.copy(alpha = 0.9f), radius = r * 0.93f, style = Stroke(width = r * 0.08f))
        val a = r * 0.30f
        val line = Color(0xFFEAF6FF)
        drawLine(line, center + Offset(-a, -a), center + Offset(a, a), strokeWidth = r * 0.11f, cap = StrokeCap.Round)
        drawLine(line, center + Offset(-a, a), center + Offset(a, -a), strokeWidth = r * 0.11f, cap = StrokeCap.Round)
    }
}

/** × の大きさ */
private const val CLOSE_DP = 72
