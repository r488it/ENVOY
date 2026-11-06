package com.rabi.envoymcp

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.net.toUri
import com.rabi.envoymcp.a11y.A11yDeviceController
import com.rabi.envoymcp.a11y.A11yService
import com.rabi.envoymcp.arm.ArmController
import com.rabi.envoymcp.arm.RecordActivity
import com.rabi.envoymcp.auth.ChatGptAuth
import com.rabi.envoymcp.call.CallRecords
import com.rabi.envoymcp.call.CallScreen
import com.rabi.envoymcp.call.IncomingCall
import com.rabi.envoymcp.call.LineCaller
import com.rabi.envoymcp.call.NumberMask
import com.rabi.envoymcp.call.RigelCaller
import com.rabi.envoymcp.core.EngineState
import com.rabi.envoymcp.core.Expression
import com.rabi.envoymcp.core.Mood
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.core.SecureStore
import com.rabi.envoymcp.device.ActionResult
import com.rabi.envoymcp.device.AdbDeviceController
import com.rabi.envoymcp.device.AppShortcuts
import com.rabi.envoymcp.device.SwitchingDeviceController
import com.rabi.envoymcp.device.adb.AdbConnector
import com.rabi.envoymcp.mcp.ActivityLog
import com.rabi.envoymcp.mcp.Agents
import com.rabi.envoymcp.mcp.McpServer
import com.rabi.envoymcp.mcp.McpTools
import com.rabi.envoymcp.mcp.ScreenVision
import com.rabi.envoymcp.mcp.ToolLog
import com.rabi.envoymcp.mcp.TunnelClient
import com.rabi.envoymcp.media.PhotoSearch
import com.rabi.envoymcp.media.PhotoStrip
import com.rabi.envoymcp.monitor.MonitorStage
import com.rabi.envoymcp.music.LyricsRepo
import com.rabi.envoymcp.music.MusicListener
import com.rabi.envoymcp.music.MusicStage
import com.rabi.envoymcp.music.MusicWatch
import com.rabi.envoymcp.music.NowPlaying
import com.rabi.envoymcp.parts.AndroidPartStore
import com.rabi.envoymcp.parts.PartRepository
import com.rabi.envoymcp.parts.PartRunner
import com.rabi.envoymcp.safety.ApprovalManager
import com.rabi.envoymcp.safety.CommitFlow
import com.rabi.envoymcp.safety.DuplicateGuard
import com.rabi.envoymcp.ui.approval.ApprovalCoordinator
import com.rabi.envoymcp.ui.approval.QuickApprovalActivity
import com.rabi.envoymcp.ui.avatar.AvatarStyle
import com.rabi.envoymcp.ui.overlay.OperationOverlay
import com.rabi.envoymcp.voice.StageContent
import com.rabi.envoymcp.voice.VoicePrimer
import com.rabi.envoymcp.voice.VoiceStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * ENVOY MCP の部品のつなぎ役。聞き取り・読み上げ・会話・LLM は持たない。
 * - 端末の操作 (ADB・ユーザー補助)・ショートカット・部品・確定の承認をまとめる
 * - ChatGPT のアプリから MCP の道具 (McpTools) として呼ばれる。考えるのは ChatGPT 側
 * - 本人がいつでも止められる: 操作中に出るリゲルをタップすると、今の操作を止め、しばらく道具を受け付けない
 */
class McpGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = McpSettings(context)

    private val _state = MutableStateFlow(EngineState())
    /** アバターとオーバーレイに出す状態 (道具が動いている間だけ ACTING) */
    val state: StateFlow<EngineState> = _state

    // ---------------------------------------------------------------- 端末の操作
    val adb = AdbConnector(context)
    lateinit var overlay: OperationOverlay
    private val injecting = AtomicInteger(0)
    @Volatile private var injectEndAt = 0L
    /** 自分で送ったタップ・スワイプの間は、リゲルへのタッチを人のタッチとして扱わない */
    fun injectingInput() = injecting.get() > 0 || System.currentTimeMillis() - injectEndAt < INJECT_GRACE_MS
    private suspend fun <T> markInjecting(block: suspend () -> T): T {
        injecting.incrementAndGet()
        try { return block() } finally { injectEndAt = System.currentTimeMillis(); injecting.decrementAndGet() }
    }

    /** 全アプリのショートカット (起動時に読み、インストールや変更があれば自動で読み直す。ホームアプリのときだけ読める) */
    val shortcuts = AppShortcuts(context).also { it.start() }
    private val hideOverlay: suspend (suspend () -> ByteArray?) -> ByteArray? = { block -> if (::overlay.isInitialized) overlay.hideWhile { block() } else block() }
    private val passThrough: suspend (suspend () -> ActionResult) -> ActionResult =
        { block -> markInjecting { if (::overlay.isInitialized) overlay.passThroughWhile { block() } else block() } }
    /** ADB 版の端末操作 (ワイヤレスデバッグ) */
    val adbDevice = AdbDeviceController(context, adb, hideOverlay, passThroughWhile = passThrough, appShortcuts = shortcuts)
    /** ユーザー補助版の端末操作 (本人が Android の設定でオンにしたときだけ使える) */
    val a11yDevice = A11yDeviceController(context, hideOverlay, passThroughWhile = passThrough, appShortcuts = shortcuts,
        displayId = { monitorStage.target },
        displayKey = { d, key -> adb.isConnected && runCatching { adb.exec("input -d $d keyevent $key", 5_000) }.isSuccess },
        displayHome = { monitorStage.toFront() })
    /** 端末の操作 (設定の方式で、ADB 版とユーザー補助版を使い分ける。モニターにつながっている間はユーザー補助でモニターを操作する) */
    val device = SwitchingDeviceController({ settings.controlMode }, adbDevice, a11yDevice, onMonitor = { monitorStage.target != android.view.Display.DEFAULT_DISPLAY })
    /** モニター (PC モード): つながったら全画面のリゲルを出し、道具の操作先をモニターにする */
    val monitorStage by lazy { MonitorStage(this) }

    /** 今の操作が ADB か (ユーザー補助なら false) */
    fun usingAdb() = !device.usingA11y()

    /** 端末の操作の様子 (状態の取得・設定の画面に出す) */
    fun deviceStatus(): String {
        val a11yOn = A11yService.instance != null
        return "方式: ${settings.controlMode.label} / 操作: ${if (device.usingA11y()) "ユーザー補助" else "ADB"} / " +
            "読み取り: ${if (device.reader() === adbDevice) "ADB" else "ユーザー補助"} / " +
            "ユーザー補助: ${if (a11yOn) "オン" else "オフ"} / ADB: ${adb.state.value.label}"
    }

    // ---------------------------------------------------------------- 確定の承認・部品
    val approvalManager = ApprovalManager()
    val duplicates = DuplicateGuard()
    /** 確定 (送信・投稿など) は、内容を見せてから指紋などで本人が承認する (声での承認は無い) */
    // ENVOY MCP は声を聞かないので、声での承認 (VOICE) も指紋などの承認画面にする
    val approvals = ApprovalCoordinator(context, { }, { false }, { show -> if (::overlay.isInitialized) overlay.suppress(!show) }, voiceCallsNeedBiometric = { true },
        quick = { settings.approvalBiometric && !settings.approvalScreen && canQuickApprove() },
        skip = { !settings.approvalBiometric && !settings.approvalScreen })
    val commitFlow = CommitFlow(device, approvals, approvalManager, duplicates)
    val parts = PartRepository(AndroidPartStore(context))
    val runner = PartRunner(device, commitFlow)

    // ---------------------------------------------------------------- MCP
    /** 道具の記録 (アプリ専用の場所のファイルにも残す。入れ直し・再起動のあとでも調べられるように) */
    val log = ToolLog(java.io.File(context.noBackupFilesDir, "tool_log.json"))
    /** したこと: AI エージェントとリゲルがしたことを、本人が読める短い文で残す (ホームの「したこと」で見る。この端末の中だけ) */
    val activity = ActivityLog(java.io.File(context.filesDir, "activity_log.json"))
    /** エージェント (合言葉ごとの呼び手) と、その権限。本体の ChatGPT は設定の合言葉 */
    val agents = Agents(Agents.Prefs(context))
    val tools = McpTools(this)
    val server = McpServer(tools, port = { settings.port }, token = { settings.token }, log = log, agents = agents,
        unlimited = { settings.devNoAgentLimits })
    /** Sign in with ChatGPT (画面の画像を AI に読ませるのに使う。API キーは要らない) */
    val auth = ChatGptAuth(context).also { it.restore() }
    /** 画面の理解で Claude を使うときの、本人の Anthropic の API キー (Android Keystore で暗号化して保存。画面・記録には出さない) */
    val claudeKey = SecureStore(context, "anthropic_api_key")
    /** 画面の画像を AI (設定で ChatGPT か Claude) に読ませ、説明と押せそうなもの (位置つき) を文章にする */
    val vision = ScreenVision(auth, settings, claudeKey)
    /** スマホの中の tunnel-client と、その実行用キー (Android Keystore で暗号化して保存。画面・記録には出さない) */
    val tunnel = TunnelClient(context)
    val tunnelKey = SecureStore(context, "tunnel_runtime_key")

    /** ChatGPT の音声モードの真ん中に重ねるリゲル */
    val voiceStage = VoiceStage(this)
    /** 指紋など (または端末の PIN) で本人確認ができる (できなければ、承認画面で「確認して実行」に戻す) */
    private fun canQuickApprove(): Boolean = runCatching {
        context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
            .canAuthenticate(QuickApprovalActivity.AUTH) == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    /** 録画の画面 (RecordActivity) が出ている (その間は、音声モードの全画面のリゲルを出さない) */
    @Volatile var recordScreenVisible = false
    /** リゲルのタップで開いた音声モードに、最初の一言を送る */
    val voicePrimer = VoicePrimer(this)

    /** 音楽が流れているか (音楽の画面と、AgentARM の「音楽が流れたら踊る」で共有) */
    val musicWatch = MusicWatch(context)
    /** 端末で鳴っている音から拍とスペクトラムを取る (踊り・音楽の画面・試しで共有。だれかが聞いている間だけ動く) */
    val music = MusicListener(context, scope)
    /** AgentARM (スマホを載せる腕。本人が設定でオンにしたときだけ。MCP サーバーが動いている間 McpService が動かす) */
    /** 再生中の曲 (通知へのアクセスが許可されていれば) */
    val nowPlaying = NowPlaying(context)
    /** 音楽の画面の歌詞 (設定でオンのときだけ、LRCLIB から取る) */
    val lyrics = LyricsRepo(context, scope)
    val arm = ArmController(context, scope, settings, music, musicWatch, nowPlaying)
    /** リゲルが代わりに電話して用件を伝える (ボードの電話の代役 callservice.py に頼む) */
    val rigelCaller = RigelCaller(this)
    /** リゲルが代わりに電話している間の画面 (やり取りを文字で) と、その記録 (あとから見る・録音) */
    val callScreen = CallScreen(this)
    val callRecords = CallRecords(context)
    /** まだ本人に伝えていないお知らせ: 電話のあとのこと・定期の依頼の結果 (リゲルのタップで開いた音声モードに引き継ぐ) */
    val notices = Notices(context)
    /** 写真を言葉で探す (EmbeddingGemma 2。端末の中) */
    val photos = PhotoSearch(this)
    /** 見つけた写真を、画面の下に番号付きで並べる (本人が目で確かめる) */
    val photoStrip = PhotoStrip(this)
    /** リゲルが代わりに電話している間、電話のアプリの画面の電話番号を隠す */
    val numberMask = NumberMask(this)
    /** かかってきた電話にリゲルが代わりに出る (代わりに出るかを、画面のボタンか ChatGPT の音声モードで本人に選ばせる) */
    val incomingCall = IncomingCall(this)
    /** LINE の音声通話を画面の操作でかける・音を「リゲル」に回す・切る (リゲルが代わりに電話するとき) */
    val lineCaller = LineCaller(this)
    /** 音楽が流れたら出すリゲルの画面 */
    val musicStage = MusicStage(this)
    /** MCP サーバーのサービスが動いているか (McpService が入れる。音楽の見張りはこの間だけ) */
    @Volatile var serviceRunning = false

    /** 音楽の見張りを、要るときだけ動かす (設定・サービスが変わったら呼ぶ) */
    fun refreshMusic() {
        musicWatch.refresh(serviceRunning && (settings.musicStage || (settings.armEnabled && settings.armAutoDance) || arm.hasReservation()))
        nowPlaying.refresh()
    }

    // ---------------------------------------------------------------- 操作中の表示と、止める
    @Volatile var homeVisible = false
    @Volatile private var stoppedAt = 0L
    @Volatile var currentJob: Job? = null
    private var idleJob: Job? = null

    /** 本人が止めた直後か (しばらく道具を受け付けない。リゲルをタップして ChatGPT を開き直すと解ける) */
    fun stoppedByUser() = System.currentTimeMillis() - stoppedAt < STOP_HOLD_MS
    fun clearStop() { stoppedAt = 0L }

    /** リゲルをタップして止めた */
    fun stopByUser(from: String) {
        RabiLog.i("graph", "stopped by user ($from)")
        stoppedAt = System.currentTimeMillis()
        currentJob?.cancel()
        arm.stopAll("リゲルをタップして止めました")
        log.add("止めた", false, "リゲルをタップして止めました ($from)")
        _state.update { it.copy(mood = Mood.IDLE, caption = "") }
        overlay.hide()
    }

    /** 道具が動き始めた: リゲルを操作中にし、縁を光らせる */
    fun beginWork(caption: String, act: Boolean) {
        idleJob?.cancel()
        _state.update { it.copy(mood = Mood.ACTING, caption = caption, stepSeq = it.stepSeq + 1, stepKind = if (act) "act" else "look") }
        if (act) overlay.show()
    }

    /** リゲルに感情の表情を一度だけ見せる (うれしい・驚きなど。決まった長さで元に戻る)。AgentARM につないでいれば、腕も同じような動きをする */
    fun express(e: Expression) {
        RabiLog.i("graph", "express ${e.id}")
        _state.update { it.copy(expression = e, expressionSeq = it.expressionSeq + 1, expressionHoldMs = 0L) }
        arm.express(e)
    }

    /** 道具が終わった: しばらく次の道具が来なければ、待機に戻す */
    fun endWork() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_AFTER_MS)
            _state.update { it.copy(mood = Mood.IDLE, caption = "") }
            overlay.hide()
        }
    }

    // ---------------------------------------------------------------- アプリ

    /** アプリの名前 (読めなければ null) */
    fun appLabel(pkg: String?): String? = pkg?.let { p ->
        runCatching { context.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } }.getOrNull()
    }

    /** 開けるアプリの (パッケージ名, 名前) */
    fun launcherApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
    }

    /** 前面の画面の (パッケージ名, Activity)。ADB で読む (接続が無ければ null。接続はここでは始めない: 待たせないため) */
    suspend fun foregroundActivity(): Pair<String, String>? {
        // ユーザー補助で操作しているときは、操作の対象の窓から読む (すぐ分かる。ADB は要らない)
        if (device.usingA11y()) return A11yService.instance?.foreground()
        if (!adb.isConnected) return null
        val r = withTimeoutOrNull(FOREGROUND_TIMEOUT_MS) {
            runCatching { adb.exec("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n 2", FOREGROUND_TIMEOUT_MS) }.getOrNull()
        } ?: return null
        val m = Regex("u\\d+ ([\\w.]+)/([\\w.$]+)").find(r.text) ?: return null
        val pkg = m.groupValues[1]
        return pkg to m.groupValues[2].let { if (it.startsWith(".")) pkg + it else it }
    }

    // ---------------------------------------------------------------- ChatGPT のアプリ

    /** ChatGPT のアプリの音声モードを開く (アプリのショートカット「音声」。無ければ音声モードのリンク) */
    fun openChatGptVoice(): Boolean {
        val la = context.getSystemService(LauncherApps::class.java)
        val voice = shortcuts.of(CHATGPT).firstOrNull { it.id == "Voice" || it.label.contains("音声") }
        // ChatGPT はいつもスマホの画面に開く (モニターのリゲルから開いても。ChatGPT の画面はスマホだけでいい)
        val onPhone = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle()
        if (voice != null && runCatching { la.startShortcut(CHATGPT, voice.id, null, onPhone, Process.myUserHandle()) }.isSuccess) return true
        if (runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, "https://chatgpt.com/voice".toUri()).setPackage(CHATGPT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), onPhone)
            }.isSuccess) return true
        // 音声モードを直接開けなければ、ChatGPT のアプリを開くだけ (音声は本人が始める)
        val launch = context.packageManager.getLaunchIntentForPackage(CHATGPT) ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), onPhone) }.isSuccess
    }

    /**
     * 音声モードの画面全体のリゲルの × (下の真ん中) を押した: ChatGPT を先に終わらせてから、ホームに戻る。
     * 1. 覆いは出したまま (ChatGPT の画面を見せない)、ChatGPT を終了する。前面にいるアプリは am kill では終わらないので強制停止
     *    (音声の会話もここで終わる。次に ChatGPT を開くまで、ChatGPT の通知なども止まる)
     * 2. ENVOY MCP のホームを出す
     * 3. 覆いを消す
     * ユーザー補助のみ (または ADB が無い) ときは強制停止できないので、ChatGPT の「終了」をユーザー補助で押して会話を終え、
     * ホームに戻ってから、裏に回った ChatGPT を終えるよう Android に頼む。どちらも無ければホームに戻るだけ (音声の会話は裏で続く)
     */
    fun closeChatGpt(goHome: Boolean = true) {
        RabiLog.i("graph", "close ChatGPT (home $goHome)")
        voiceStage.beginClose()
        scope.launch(Dispatchers.IO) {
            try {
                voicePrimer.stop()   // 最初の一言を入れている途中なら、消してから (文字が残ると「終了」が出ない)
                // ADB があれば強制停止 (方式が「ユーザー補助のみ」なら使わない)
                val adbOk = settings.controlMode != ControlMode.A11Y && (adb.isConnected || (adb.paired && adb.probeIfIdle()))
                when {
                    adbOk -> {
                        runCatching { adb.exec("am force-stop $CHATGPT", 5_000) }.onFailure { RabiLog.w("graph", "force-stop failed: ${it.javaClass.simpleName}") }
                        delay(CLOSE_SETTLE_MS)
                    }
                    // ユーザー補助: ChatGPT の「終了」を直接押して、音声の会話を終える (覆いの下でも押せる)
                    A11yService.instance?.clickByDesc(CHATGPT, CHATGPT_END_DESC) == true -> { RabiLog.i("graph", "ended voice by a11y"); delay(CLOSE_SETTLE_MS) }
                    else -> RabiLog.w("graph", "cannot stop ChatGPT (no adb, no a11y)")
                }
                // 小さなリゲルから閉じたとき (ChatGPT は裏) は、今の画面のまま (ホームには戻らない)
                if (goHome) {
                    withContext(Dispatchers.Main) {
                        runCatching { context.startActivity(Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
                    }
                    delay(CLOSE_SETTLE_MS)   // ホームが出るのを待ってから覆いを消す
                }
                // 強制停止できなかったときは、裏に回った ChatGPT を終えるよう Android に頼む (裏で動くものがあると終わらないこともある)
                if (!adbOk) runCatching { context.getSystemService(android.app.ActivityManager::class.java).killBackgroundProcesses(CHATGPT) }
            } finally {
                withContext(Dispatchers.Main) { voiceStage.endClose() }
            }
        }
    }

    /**
     * 他のアプリの上の小さなリゲルをタップした:
     * - ChatGPT が裏で音声の会話をしているとき: 会話を終えて ChatGPT を閉じる (今の画面はそのまま。操作の途中なら止める)。
     *   裏の ChatGPT を閉じるには ADB の強制停止が要る。ADB が使えなければ ChatGPT を前に出す (全画面のリゲルの × で終えてもらう)
     * - 会話をしていないとき: リゲルのタップと同じ (操作の途中なら止める。何もしていなければ新しい会話を始める)
     */
    fun orbTap() {
        scope.launch {
            if (!chatGptVoiceActive()) { tapRigel("overlay"); return@launch }
            if (state.value.mood == Mood.ACTING || currentJob?.isActive == true) stopByUser("overlay")
            val adbOk = settings.controlMode != ControlMode.A11Y && (adb.isConnected || (adb.paired && withContext(Dispatchers.IO) { adb.probeIfIdle() }))
            if (adbOk) closeChatGpt(goHome = false)
            else { RabiLog.w("graph", "no adb: bring ChatGPT to front to end voice"); openChatGptVoice() }
        }
    }

    /**
     * ChatGPT が音声の会話をしているか: 端末が通話モードで、ADB が使えるときは、通話モードの持ち主が ChatGPT かも確かめる
     * (LINE の通話などと取り違えないように)。ADB が使えなければ通話モードだけで判断する
     */
    private var goodbyeJob: Job? = null

    /**
     * 「ばいばい」で音声モードを終える (道具 end_voice_chat から)。ChatGPT が別れの挨拶を言い終えるのを待ってから、
     * リゲルの × と同じく音声モードを終えて ChatGPT を閉じ、ホームのリゲルに戻す。言い終えたかは、ChatGPT の声の大きさ (VoiceStage) で見る。
     * 取れない (声に合わせて動かす設定がオフ・全画面のリゲルを出していない) ときは、決まった長さ待つ
     */
    fun endVoiceAfterGoodbye() {
        goodbyeJob?.cancel()
        goodbyeJob = scope.launch {
            delay(GOODBYE_START_MS)                       // 道具の答えを受けて、ChatGPT が話し始めるまで
            val levelOk = voiceStage.isShowing && settings.voiceStageLevel
            if (levelOk) {
                val t0 = System.currentTimeMillis()
                var spoke = false
                while (System.currentTimeMillis() - t0 < GOODBYE_MAX_MS) {
                    if (voiceStage.speaking.value) spoke = true else if (spoke) break      // 話し終えた (1.2 秒黙った)
                    delay(100)
                }
                if (!spoke) delay(GOODBYE_FIXED_MS)       // 声が取れなかった: 決まった長さ
            } else delay(GOODBYE_FIXED_MS)
            if (!chatGptVoiceActive()) { RabiLog.i("graph", "goodbye: voice already ended"); return@launch }
            RabiLog.i("graph", "goodbye: close ChatGPT voice")
            withContext(Dispatchers.Main) { closeChatGpt(goHome = true) }
        }
    }

    @Volatile private var realCallAt = 0L
    @Volatile private var realCallLast = false

    /**
     * 本物の通話 (携帯の通話・リゲルの代わりの電話・LINE などの通話) の最中か。ChatGPT との音声の会話は通話に入れない。
     * 通信中のモードの持ち主を ADB で確かめるのは重いので、結果を少しの間使い回す
     */
    suspend fun inRealCall(): Boolean {
        val mode = context.getSystemService(android.media.AudioManager::class.java).mode
        if (mode == android.media.AudioManager.MODE_IN_CALL || rigelCaller.busy) return true
        if (mode != android.media.AudioManager.MODE_IN_COMMUNICATION) return false
        val now = System.currentTimeMillis()
        if (now - realCallAt < REAL_CALL_CACHE_MS) return realCallLast
        // LINE の通話の画面が出ていれば通話。そうでなければ、持ち主が ChatGPT でないときだけ通話とみなす
        val call = lineCaller.inCallScreen() || !chatGptVoiceActive()
        realCallLast = call; realCallAt = now
        return call
    }

    private suspend fun chatGptVoiceActive(): Boolean {
        if (context.getSystemService(android.media.AudioManager::class.java).mode != android.media.AudioManager.MODE_IN_COMMUNICATION) return false
        if (!adb.isConnected) return true
        val uid = runCatching { context.packageManager.getPackageUid(CHATGPT, 0) }.getOrNull() ?: return true
        val owner = withContext(Dispatchers.IO) {
            runCatching { adb.exec("dumpsys audio | grep -m1 mAudioModeOwner", FOREGROUND_TIMEOUT_MS).text }.getOrNull()
        } ?: return true
        return owner.contains("mUid=$uid")
    }

    /**
     * 腕のボードが呼び名「リゲル」を本人の声で聞いた (試験): リゲルのタップと同じく、ChatGPT の音声モードを開く。
     * もう ChatGPT と話している・操作の途中のときは何もしない (タップと違って、操作は止めない)
     */
    private suspend fun wakeFromArm(score: Double) {
        if (chatGptVoiceActive()) { RabiLog.i("graph", "wake ignored (voice on)"); return }
        if (state.value.mood == Mood.ACTING || currentJob?.isActive == true) { RabiLog.i("graph", "wake ignored (acting)"); return }
        RabiLog.i("graph", "wake from arm")
        if (settings.wakeGreet) greetOnWake()
        tapRigel("arm-wake")
    }

    private var greetJob: Job? = null

    /**
     * 呼び名で起きた: ChatGPT の音声モードが開くまでの間をつなぐ。ランダムな表情 (画面のリゲルと腕の動き) を
     * 見せ、腕の動きが終わったら、前面カメラで顔を探して利用者の方を向く。悲しいは起きたときに合わないので選ばない。
     * 腕につながっていなければ、画面のリゲルの表情だけ
     */
    private fun greetOnWake() {
        greetJob?.cancel()
        greetJob = scope.launch {
            val e = WAKE_EXPRESSIONS.random()
            RabiLog.i("graph", "wake greet ${e.id}")
            launch { showWakeExpression(e) }
            if (!arm.expressNow(e)) return@launch
            val r = arm.lookAtMe(null)
            RabiLog.i("graph", "wake look at user -> ${if (r.ok) "ok" else r.code}")
        }
    }

    /**
     * 画面のリゲルに表情を出す。音声モードの全画面のリゲル (VoiceStage) は開くたびに作り直され、開いた時点の表情は見せない
     * (前の表情をもう一度見せないため) ので、全画面のリゲルが出てから出す
     */
    private suspend fun showWakeExpression(e: Expression) {
        val t0 = System.currentTimeMillis()
        while (!voiceStage.isShowing && System.currentTimeMillis() - t0 < WAKE_STAGE_WAIT_MS) delay(50)
        if (voiceStage.isShowing) delay(WAKE_STAGE_SETTLE_MS)
        _state.update { it.copy(expression = e, expressionSeq = it.expressionSeq + 1, expressionHoldMs = WAKE_EXPRESSION_MS) }
    }

    /** 音声モードの画面全体のリゲルをタップした: 道具が動いている間は止める (何もしていなければ何もしない) */
    fun stageTap() { if (state.value.mood == Mood.ACTING || currentJob?.isActive == true) stopByUser("stage") }

    /**
     * リゲルをタップした: 道具が動いている間は止める。何もしていなければ、止めた状態を解いて ChatGPT を開く。
     * 本人にまだ伝えていないお知らせ (notices。電話のあとのこと・定期の依頼の結果) があれば、最初の一言で「N 件ある。get_notices で読んで」と頼む
     * (最初の一言は決まった文と種類・時刻だけ。中身は get_notices で外から届いた文として渡す)。
     * 送れたものに印を付け、get_notices で読まれたら消す
     */
    fun tapRigel(from: String) {
        if (state.value.mood == Mood.ACTING || currentJob?.isActive == true) { stopByUser(from); return }
        clearStop()
        // 音声モードの真ん中のリゲルを、ChatGPT が開くのを待たずに出す (開けなければ消す)
        voiceStage.expectVoice()
        val notes = notices.notes.value
        val handoff = Notices.handoff(notes)
        if (handoff != null) {
            RabiLog.i("graph", "hand off ${notes.size} notices")
            voicePrimer.expectReport(handoff) { notices.remove(notes.map { it.id }) }
        } else voicePrimer.expect()
        if (!openChatGptVoice()) { RabiLog.w("graph", "cannot open ChatGPT"); voiceStage.cancelExpect(); scope.launch { voicePrimer.stop() } }
    }

    /**
     * デジタル アシスタントの受け口 (AssistActivity。電源ボタンの長押しなど) から開いた: 道具が動いている間は止める。
     * 何もしていなければ ChatGPT の音声モードを開く (いつもの最初の一言だけ送る)。
     * 受け口はほかのアプリからも開けるので、リゲルのタップと違い、お知らせの引き継ぎと、止めた状態を解くこと (clearStop) はしない
     */
    fun openVoiceFromAssist() {
        if (state.value.mood == Mood.ACTING || currentJob?.isActive == true) { stopByUser("assist"); return }
        voiceStage.expectVoice()
        voicePrimer.expect()
        if (!openChatGptVoice()) { RabiLog.w("graph", "cannot open ChatGPT"); voiceStage.cancelExpect(); scope.launch { voicePrimer.stop() } }
    }

    /**
     * リゲルが代わりに電話する直前: ChatGPT の音声モードを終える。電話の間に ChatGPT が話すと、
     * その声がつながっている「リゲル」に流れて通話の音に入り、リゲル (Gemini) が相手の声と取り違えるため。
     * ユーザー補助で「終了」を押す (リゲルの覆いの下でも押せる)。終えたら true (電話のあとに reopenVoiceAfterCall で開き直す)
     */
    suspend fun endChatGptVoiceForCall(force: Boolean = false): Boolean {
        // force: 着信の呼び出し中 (通話のモードが「呼び出し中」) は、音声モードでも「通信中」に見えないので、ChatGPT の画面に「終了」があれば押す
        if (force) {
            if (A11yService.instance?.hasDesc(CHATGPT, CHATGPT_END_DESC) != true) return false
        } else if (!chatGptVoiceActive()) return false
        val s = A11yService.instance ?: run { RabiLog.w("graph", "cannot end ChatGPT voice (no a11y)"); return false }
        voicePrimer.stop()
        // 指紋の画面が閉じた直後は、まだ押せないことがある。見つかるまで少しくり返す
        val until = System.currentTimeMillis() + VOICE_END_FIND_MS
        var clicked = false
        while (!clicked && System.currentTimeMillis() < until) {
            clicked = s.clickByDesc(CHATGPT, CHATGPT_END_DESC)
            if (!clicked) delay(150)
        }
        if (!clicked) {
            // 原因を確かめるため、ChatGPT の画面にあるボタンの説明 (短いものだけ。会話の中身は残さない) を記録に残す
            val buttons = runCatching {
                s.findAllIn(CHATGPT) { n -> n.isClickable && (n.contentDescription?.length ?: 0) in 1..12 }
                    .mapNotNull { it.contentDescription?.toString() }.distinct().take(12)
            }.getOrDefault(emptyList())
            RabiLog.w("graph", "cannot end ChatGPT voice for call (buttons: ${buttons.joinToString("・")})")
            return false
        }
        val am = context.getSystemService(android.media.AudioManager::class.java)
        val t0 = System.currentTimeMillis()
        while (am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION && System.currentTimeMillis() - t0 < VOICE_END_WAIT_MS) delay(100)
        RabiLog.i("graph", "ended ChatGPT voice for rigel call")
        return true
    }

    /**
     * 通話のモードが「呼び出し中」のまま固まって、持ち主が ChatGPT なら、ChatGPT を止めて外す (鳴っている間に ChatGPT の音声を
     * 始めると、ChatGPT は終わるときに始めたときのモードへ戻すため。ADB が使えるときだけ)
     */
    suspend fun releaseStuckRingMode() {
        if (!adb.isConnected && !(adb.paired && runCatching { adb.probeIfIdle() }.getOrDefault(false))) { RabiLog.w("graph", "stuck ringtone: no adb"); return }
        val uid = runCatching { context.packageManager.getPackageUid(CHATGPT, 0) }.getOrNull() ?: return
        val owner = withContext(Dispatchers.IO) { runCatching { adb.exec("dumpsys audio | grep -m1 mAudioModeOwner", FOREGROUND_TIMEOUT_MS).text }.getOrNull() }.orEmpty()
        if (!owner.contains("mUid=$uid")) { RabiLog.w("graph", "stuck ringtone: owner is not ChatGPT"); return }
        withContext(Dispatchers.IO) { runCatching { adb.exec("am force-stop $CHATGPT", 5_000) } }
        RabiLog.i("graph", "released stuck ringtone (stopped ChatGPT)")
    }

    /**
     * リゲルの電話が終わった: ChatGPT の音声モードを開き直し、最初の一言 (report。決まった文と結果の種類だけ。CallFrames) を送って、
     * rigel_call_status で結果を読んで本人に報告してもらう。
     * 電話が終わっても、しばらく端末は通話中のまま (音声モードが始まらない) なので、通話中が解けるのを待ってから開く (リゲルの覆いは出したまま)。
     * 開き直せなかったら (次の着信が鳴っている・ChatGPT を開けない) onSkip を呼ぶ (結果はお知らせに残してもらう)
     */
    fun reopenVoiceAfterCall(report: String, onSkip: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val am = context.getSystemService(android.media.AudioManager::class.java)
            val t0 = System.currentTimeMillis()
            // 電話は「通話中」、LINE などの通話アプリは「通信中」が、切ったあとしばらく残る。残っている間に開くと、
            // 最初の一言が「もう音声の会話中」とみなされて入らない
            while (am.mode.let { it == android.media.AudioManager.MODE_IN_CALL || it == android.media.AudioManager.MODE_IN_COMMUNICATION } &&
                System.currentTimeMillis() - t0 < CALL_END_WAIT_MS) delay(200)
            // 次の着信が鳴っている間は開かない (鳴っている間に ChatGPT の音声モードを始めると、声が受話口から出て聞こえず、
            // ChatGPT が終わるときに「呼び出し中」のモードへ戻して固まる)
            val t1 = System.currentTimeMillis()
            while (am.mode == android.media.AudioManager.MODE_RINGTONE && System.currentTimeMillis() - t1 < RING_WAIT_MS) delay(300)
            if (am.mode == android.media.AudioManager.MODE_RINGTONE) { RabiLog.w("graph", "still ringing: skip reopening ChatGPT voice"); onSkip(); return@launch }
            RabiLog.i("graph", "reopen ChatGPT voice with the call result")
            callScreen.hide()      // 代行の通話の画面が残っていれば消す (ChatGPT を開く操作の邪魔をしない)
            withContext(Dispatchers.Main) {
                voiceStage.expectVoice()
                voicePrimer.expectReport(report)
                if (!openChatGptVoice()) {
                    RabiLog.w("graph", "cannot reopen ChatGPT"); voiceStage.cancelExpect(); scope.launch { voicePrimer.stop() }; onSkip()
                }
            }
        }
    }

    // ---------------------------------------------------------------- 前に出ている画面を見張る

    private var resumedActivities = 0
    private fun onPhone(a: Activity) = (a.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY) == android.view.Display.DEFAULT_DISPLAY

    /** スマホの画面に ENVOY MCP の画面 (ホーム・設定など) が出ている */
    val ownScreenOnPhone get() = resumedActivities > 0

    /**
     * スマホの画面の前面のアプリ (音声モードの全画面のリゲルを出すかの判断に使う)。ENVOY MCP の画面が出ていれば ENVOY MCP。
     * そうでなければ、スマホの画面のアプリの窓のうち ENVOY MCP 自身の窓 (覆い) を除いていちばん上のもの (読めなければ前面の Activity を読む)
     */
    suspend fun phoneForeground(): String? {
        if (ownScreenOnPhone) return context.packageName
        // モニターにつながっている間は、前面の読み取りがモニターの窓を返すことがあるので、スマホの画面だけから読む (読めなければ null)
        if (monitorStage.target != android.view.Display.DEFAULT_DISPLAY) return A11yService.instance?.phoneTopApp(context.packageName)
        if (device.usingA11y()) A11yService.instance?.phoneTopApp(context.packageName)?.let { return it }
        return foregroundActivity()?.first
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun watchForeground() {
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // スマホの画面の画面だけを数える (モニターの全画面のリゲルは、スマホで ENVOY MCP が前に出ていることにはしない)
            override fun onActivityResumed(a: Activity) { if (!onPhone(a)) return; resumedActivities++; overlay.setOtherAppInFront(false) }
            override fun onActivityPaused(a: Activity) {
                if (!onPhone(a)) return
                resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
                mainHandler.postDelayed({ if (resumedActivities == 0) overlay.setOtherAppInFront(true) }, FOREGROUND_SETTLE_MS)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        mainHandler.postDelayed({ if (resumedActivities == 0) overlay.setOtherAppInFront(true) }, 1500)
    }

    init {
        overlay = OperationOverlay(context, state, { AvatarStyle.byId(settings.avatar) }, enabled = { settings.bubbleEnabled },
            badge = notices.notes.map { it.size }.stateIn(scope, SharingStarted.Eagerly, notices.notes.value.size),
            loadPos = { settings.bubbleX to settings.bubbleY }, savePos = { x, y -> settings.bubbleX = x; settings.bubbleY = y })
        // 操作中のリゲルをタップ: 止める。何もしていなければ ChatGPT の音声モードを開く
        overlay.onTap = { orbTap() }
        // 音声モードの間に画面全体に出すリゲル (タップ・× は StageContent が受ける)
        overlay.stageContent = { StageContent(this) }
        watchForeground()
        voiceStage.start()
        incomingCall.start()
        photos.startIndexing()      // 新しい写真があれば、裏で索引を作る (モデルと許可があるときだけ)
        musicStage.start()
        // ChatGPT などから「踊って」と頼まれたら、リゲルの音楽の画面を前に出す
        // (音声モードの全画面のリゲルが出ているときは、そちらにスペクトラムを出すので、画面は替えない)
        arm.onDance = { if (!voiceStage.isShowing) musicStage.show() }
        arm.onReserveChanged = { refreshMusic() }
        arm.onWake = { s -> scope.launch { wakeFromArm(s) } }
        arm.inCall = { inRealCall() }
        arm.onRecordStarted = { RecordActivity.open(context) }
        // リゲルの気分を、AgentARM の光にも出す
        scope.launch { state.map { it.mood }.distinctUntilChanged().collect { arm.setMood(it) } }
    }

    companion object {
        const val CHATGPT = "com.openai.chatgpt"
        /** 呼び名で起きたとき、全画面のリゲルが出るのを待つ最長と、出てから表情を出すまで (最初の描画を待つ) */
        private const val WAKE_STAGE_WAIT_MS = 1_500L
        private const val WAKE_STAGE_SETTLE_MS = 300L
        /** 呼び名で起きたときの画面のリゲルの表情の長さ */
        private const val WAKE_EXPRESSION_MS = 2_000L
        /** 「ばいばい」のあと: ChatGPT が話し始めるまで・言い終えるのを待つ最長・声が取れないときに待つ長さ */
        private const val GOODBYE_START_MS = 1_500L
        private const val GOODBYE_MAX_MS = 10_000L
        private const val GOODBYE_FIXED_MS = 3_500L
        /** 呼び名で起きたときに見せる表情 (この中からランダム) */
        private val WAKE_EXPRESSIONS = listOf(
            Expression.HAPPY, Expression.LAUGH,
            Expression.SURPRISED, Expression.SHY,
            Expression.WINK)
        /** ChatGPT の音声モードの × (会話を終える) の説明 (日本語の表示のとき。音声モードの画面にしか無いので、VoicePrimer は音声モードの見分けにも使う) */
        const val CHATGPT_END_DESC = "終了"
        /** リゲルの電話の前に ChatGPT の音声モードを終えて、通話モードが戻るのを待つ最長 */
        private const val VOICE_END_WAIT_MS = 3_000L
        /** 報告で ChatGPT の音声モードを開く前に、次の着信が鳴りやむのを待つ長さ */
        private const val RING_WAIT_MS = 60_000L
        /** ChatGPT の音声モードの「終了」を探し続ける長さ */
        private const val VOICE_END_FIND_MS = 3_000L
        /** リゲルの電話のあと、端末の通話中が解けるのを待つ最長 (解けるまで 7 秒ほどかかる) */
        private const val CALL_END_WAIT_MS = 12_000L
        private const val FOREGROUND_TIMEOUT_MS = 5_000L
        /** 本物の通話かを確かめた結果を使い回す長さ */
        private const val REAL_CALL_CACHE_MS = 2_000L
        /** ChatGPT を終えてから・ホームを出してから、次へ進むまでの待ち */
        private const val CLOSE_SETTLE_MS = 400L
        private const val INJECT_GRACE_MS = 600L
        private const val FOREGROUND_SETTLE_MS = 400L
        /** 道具が来なくなってから、待機に戻すまで */
        private const val IDLE_AFTER_MS = 6_000L
        /** 止めたあと、道具を受け付けない間 */
        const val STOP_HOLD_MS = 60_000L
    }
}
