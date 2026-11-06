package com.rabi.envoymcp.voice

import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.rabi.envoymcp.HomeActivity
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.a11y.A11yService
import com.rabi.envoymcp.core.RabiLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 話し相手の AI が Claude のとき、リゲルのタップで Claude のアプリの音声モードを開く・閉じる。
 * - いつも、先に文字で頼みの文 (prompt) を送ってから音声モードにする。Claude をリゲルとして話させ、道具の使い方を伝えるため
 *   (Claude の音声モードには文字の入力欄が無く、Claude は本人が話すまで話さない)。
 *   アプリを開いて新しいチャットにし、入力欄に文を入れて送り、「音声モード」が押せるようになったら押す。
 *   お知らせがあれば文に入れ、本人が話しかけると、Claude が最初にお知らせを声で伝える。
 *   送れたら onSent (お知らせを消す)。音声モードを押したら「話しかけてね」と小さく出す
 * - ユーザー補助が無ければ文を送れないので、ショートカット「音声会話を開始」(quick_action_voice) で音声モードだけ開く
 *   (ENVOY MCP が既定のホームアプリのときだけ開ける。開けなければ、アプリを開くだけ)
 * - どの段も待つ長さを決めてあり、うまくいかなければ記録に残してやめる (Claude は開いたまま)。文の中身は記録に残さない
 * - 閉じる: 「停止」があれば押して音声の会話を終え、ENVOY MCP のホームに戻る (Claude は強制停止しない)
 * - Claude の音声モードは端末の通話モードを使わない (通常のまま) ので、音声の会話中かは画面で見る
 *   (「停止」があり、「マイクをミュート」があるか入力欄が無い)
 * - Claude の画面は resource-id が無いので、部品の説明 (content-desc) で探す。上に ENVOY MCP の覆いがあっても、部品そのものを押せる
 */
class ClaudeVoice(private val g: McpGraph) {
    @Volatile private var job: Job? = null

    /** Claude のアプリが入っているか */
    fun installed() = g.context.packageManager.getLaunchIntentForPackage(PKG) != null

    /**
     * Claude の音声モードを開く。先に新しいチャットに prompt を送ってから音声モードにし、hint を小さく出す。
     * Claude のアプリを開けなければ false
     */
    fun open(prompt: String, hint: String, onSent: () -> Unit = {}): Boolean {
        job?.cancel()
        val svc = A11yService.instance
        if (svc == null) {
            // 文を送れない: 音声モードだけ開く (お知らせは残し、次のタップで伝える)
            if (startVoiceShortcut()) { RabiLog.i(TAG, "no a11y: opened voice by shortcut"); return true }
            if (!launchApp()) { RabiLog.w(TAG, "cannot launch Claude"); return false }
            RabiLog.w(TAG, "no a11y: opened Claude only"); return true
        }
        if (!launchApp()) { RabiLog.w(TAG, "cannot launch Claude"); return false }
        job = g.scope.launch(Dispatchers.IO) { sendThenVoice(svc, prompt, hint, onSent) }
        return true
    }

    /** 開いている途中ならやめる (入れた文字は消してから戻る) */
    suspend fun stop() { job?.cancelAndJoin(); job = null }

    /** 音声の会話を終える: 「停止」があれば押し、ENVOY MCP のホームに戻る (Claude は強制停止しない) */
    fun close() {
        RabiLog.i(TAG, "close")
        g.scope.launch(Dispatchers.IO) {
            stop()
            val svc = A11yService.instance
            if (svc != null && click(svc, STOP)) { RabiLog.i(TAG, "pressed stop"); delay(CLOSE_SETTLE_MS) }
            else RabiLog.i(TAG, "no stop button")
            withContext(Dispatchers.Main) {
                runCatching {
                    g.context.startActivity(Intent(g.context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }.onFailure { RabiLog.w(TAG, "home failed ${it.javaClass.simpleName}") }
            }
        }
    }

    /** Claude の音声モードの画面が出ているか (ユーザー補助で読む。読めなければ false) */
    suspend fun voiceOn(): Boolean = withContext(Dispatchers.IO) {
        val svc = A11yService.instance ?: return@withContext false
        runCatching { voiceOn(svc) }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- 開く

    /** 音声モードを直接開くショートカット。ホームアプリでなければ開けない */
    private fun startVoiceShortcut(): Boolean = runCatching {
        g.context.getSystemService(LauncherApps::class.java).startShortcut(PKG, VOICE_SHORTCUT, null, onPhone(), Process.myUserHandle())
    }.onFailure { RabiLog.i(TAG, "voice shortcut failed ${it.javaClass.simpleName}") }.isSuccess

    /** Claude のアプリを開く (いつもスマホの画面に) */
    private fun launchApp(): Boolean {
        val launch = g.context.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        return runCatching { g.context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), onPhone()) }.isSuccess
    }

    private fun onPhone() = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()

    /** 新しいチャット → 文を入れて送る → 音声モードが押せるようになったら押す */
    private suspend fun sendThenVoice(svc: A11yService, text: String, hint: String, onSent: () -> Unit) {
        // 1. Claude の画面が出るのを待つ
        if (!waitFor(APP_WAIT_MS) { svc.inputOf(PKG) != null || has(svc, NEW_CHAT) || voiceButton(svc) != null || voiceOn(svc) }) {
            RabiLog.w(TAG, "Claude screen did not show"); return
        }
        // もう音声の会話をしている: そのまま (お知らせは残す)
        if (voiceOn(svc)) { RabiLog.i(TAG, "voice already on (notices kept)"); return }
        // 2. 新しいチャットにする (ボタンが無ければ、もう新しいチャットの画面)
        if (click(svc, NEW_CHAT)) { RabiLog.i(TAG, "new chat"); delay(NEW_CHAT_SETTLE_MS) }
        if (!waitFor(INPUT_WAIT_MS) { svc.inputOf(PKG) != null }) { RabiLog.w(TAG, "no input field"); return }
        // 本人が何か入れている途中なら、上書きしない
        if (svc.inputOf(PKG)?.let { empty(it) } == false) { RabiLog.i(TAG, "skip (field not empty)"); return }
        // 3. 文を入れて送る
        if (!send(svc, text)) return
        onSent()
        // 4. 返事が終わり、「音声モード」が押せるようになるのを待って押す
        delay(AFTER_SEND_MS)
        if (!waitSteady(REPLY_WAIT_MS, READY_STEADY_MS) { voiceButton(svc) != null && !has(svc, STOP) }) {
            RabiLog.w(TAG, "voice button did not come back"); return
        }
        if (voiceButton(svc)?.let { clickNode(it) } != true) { RabiLog.w(TAG, "cannot press voice mode"); return }
        RabiLog.i(TAG, "pressed voice mode")
        withContext(Dispatchers.Main) { Toast.makeText(g.context, hint, Toast.LENGTH_LONG).show() }
        // 確かめるための記録だけ (出なくても、Claude は開いたまま)
        RabiLog.i(TAG, if (waitFor(VOICE_ON_WAIT_MS) { voiceOn(svc) }) "voice on" else "voice not confirmed")
    }

    /** 入力欄に文を入れて「送信」を押す。入力欄が空に戻れば送れた (true)。送れなければ入れた文字を消す */
    private suspend fun send(svc: A11yService, text: String): Boolean {
        var typed = false
        var sent = false
        try {
            typed = svc.setInputText(PKG, text)
            if (!typed) { RabiLog.w(TAG, "cannot set text"); return false }
            if (!waitFor(SEND_WAIT_MS) { click(svc, SEND) }) { RabiLog.w(TAG, "no send button"); return false }
            sent = waitFor(SEND_WAIT_MS) { svc.inputOf(PKG)?.let { empty(it) } ?: true }
            RabiLog.i(TAG, if (sent) "sent" else "send not confirmed")
        } finally {
            if (typed && !sent) withContext(NonCancellable) { RabiLog.i(TAG, "clear field: " + svc.setInputText(PKG, "")) }
        }
        return sent
    }

    // ---------------------------------------------------------------- 画面を読む

    /** Claude の窓の中の、短い説明 (content-desc) をすべて */
    private fun descs(svc: A11yService): List<String> =
        svc.findAllIn(PKG) { (it.contentDescription?.length ?: 0) in 1..MAX_DESC }.mapNotNull { it.contentDescription?.toString() }

    private fun voiceOn(svc: A11yService) = isVoiceScreen(descs(svc), svc.inputOf(PKG) != null)

    private fun has(svc: A11yService, labels: List<String>) = svc.findAllIn(PKG) { matches(it.contentDescription, labels) }.isNotEmpty()

    /** 説明が labels のどれかの部品を押す (押せる親があればそれを押す) */
    private fun click(svc: A11yService, labels: List<String>): Boolean =
        svc.findAllIn(PKG) { matches(it.contentDescription, labels) }.any { clickNode(it) }

    /** 押せる「音声モード」(押せる親が、使える状態で見えている)。無ければ null */
    private fun voiceButton(svc: A11yService): AccessibilityNodeInfo? =
        svc.findAllIn(PKG) { matches(it.contentDescription, VOICE_MODE) }.map { clickable(it) }.firstOrNull { it.isEnabled && it.isVisibleToUser }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n ?: node
    }

    private fun clickNode(node: AccessibilityNodeInfo) = clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK)

    /** 入力欄が空か (空のときに案内の文字を返す入力欄もあるので、案内を出しているかも見る) */
    private fun empty(n: AccessibilityNodeInfo) = n.text.isNullOrEmpty() || n.isShowingHintText

    private suspend fun waitFor(ms: Long, ok: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (true) {
            if (runCatching(ok).getOrDefault(false)) return true
            if (System.currentTimeMillis() >= until) return false
            delay(POLL_MS)
        }
    }

    /** ok が steadyMs 続けて成り立つまで待つ (返事の途中で一瞬だけ押せるように見えるのを避ける) */
    private suspend fun waitSteady(ms: Long, steadyMs: Long, ok: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        var since = 0L
        while (System.currentTimeMillis() < until) {
            val now = System.currentTimeMillis()
            if (runCatching(ok).getOrDefault(false)) {
                if (since == 0L) since = now
                if (now - since >= steadyMs) return true
            } else since = 0L
            delay(POLL_MS)
        }
        return false
    }

    companion object {
        const val PKG = "com.anthropic.claude"
        private const val TAG = "claude"
        /** 音声モードを直接開くショートカット (「音声会話を開始」) */
        const val VOICE_SHORTCUT = "quick_action_voice"

        // 部品の説明 (content-desc)。日本語は Claude 1.261104.19 の日本語の表示で見た名前。
        // 英語は英語の表示のときの名前の見込み (端末で要確認)
        /** 入力欄の横の丸いボタン (音声モードを始める) */
        val VOICE_MODE = listOf("音声モード", "Voice mode")
        /** 音声モードを終える (返事の途中の文字のチャットでは、返事を止める) */
        val STOP = listOf("停止", "Stop")
        /** 文字を入れると出る送信のボタン */
        val SEND = listOf("送信", "Send", "Send message")
        /** 会話の画面の上の、新しいチャットを始めるボタン */
        val NEW_CHAT = listOf("新規セッション", "New chat")
        /** 音声モードの間だけ出るマイクのミュート */
        val MUTE_MIC = listOf("マイクをミュート", "Mute microphone")

        /** 音声モードを押したあとに出す一言 (Claude は本人が話すまで話さないので、話しかけるよう伝える) */
        const val HINT = "話しかけてね"
        const val HINT_NOTICES = "話しかけてね（お知らせがあります）"

        /**
         * 道具を使う間は黙る頼み。Claude の音声モードは、返事の文をそのまま読み上げるので、道具を呼ぶ前後の
         * 途中の言葉 (「確認します」など) も声になる。先に送る文 (PERSONA) と、ENVOY MCP の最初の案内の両方に入れる
         */
        const val QUIET_TOOLS = "道具を使う前と使っている間は、何も話さずに、黙って道具を呼んでください。" +
            "「確認します」「〜を見てみます」「少々お待ちください」のような途中の言葉や、考えていることは声に出さないでください。" +
            "終わったら、結果だけを 1〜2 文で短く伝えてください。"

        /** 先に送る文の頭: Claude にリゲルとして話してもらう */
        const val PERSONA = "これから音声モードで話します。あなたは、このスマホの AI アシスタント「リゲル」です。" +
            "リゲルとして名乗り、やさしく短い言葉で話してください。" +
            "「ねえ、リゲル」は、あなたへの呼びかけです。音声の聞き取りで「ねえ、逃げる」「にげる」「りげる」などに聞こえても、" +
            "逃げる話ではなく、あなたを呼んだものとして受け取ってください。" +
            "呼ばれただけのときは、「リゲルだよ、こんにちは。」のように、ひとことで短く答えてください。" + QUIET_TOOLS

        /** 先に送る文の終わり: 文字の返事を短くして、早く音声モードに入る */
        const val REPLY_YES = "この文には、文字で「はい」とだけ返事してください。"

        /**
         * 音声モードの前に送る文: リゲルの役 (PERSONA)・いつもの最初の文 (primer。返事の仕方の一文は外す)・
         * お知らせ (notices。Notices.handoffForClaude。無ければ null)・返事の仕方 (REPLY_YES) の順。改行は空白にする
         */
        fun prompt(primer: String, notices: String?): String =
            listOf(PERSONA, primer.trim().removeSuffix(VoicePrimer.REPLY_HINT).trim(), notices.orEmpty(), REPLY_YES)
                .filter { it.isNotBlank() }.joinToString(" ").replace('\n', ' ')

        /** Claude の画面が出るのを待つ長さ */
        private const val APP_WAIT_MS = 8_000L
        /** 新しいチャットを押してから、画面が落ち着くまで */
        private const val NEW_CHAT_SETTLE_MS = 800L
        private const val INPUT_WAIT_MS = 3_000L
        private const val SEND_WAIT_MS = 2_000L
        /** 送ってから、返事が始まるのを待つ */
        private const val AFTER_SEND_MS = 1_000L
        /** 返事が終わり、音声モードが押せるようになるのを待つ最長と、押せる状態が続く長さ */
        private const val REPLY_WAIT_MS = 30_000L
        private const val READY_STEADY_MS = 1_000L
        /** 音声モードを押してから、音声モードの画面が出たかを確かめる長さ (記録のため) */
        private const val VOICE_ON_WAIT_MS = 5_000L
        /** 「停止」を押してから、ホームを出すまで */
        private const val CLOSE_SETTLE_MS = 400L
        private const val POLL_MS = 250L
        /** 読む説明の長さの上限 (会話の文は読まない) */
        private const val MAX_DESC = 40

        /** 部品の説明が labels のどれかと同じか (前後の空白と、英字の大文字・小文字は区別しない。一部だけ合うものは違う) */
        fun matches(desc: CharSequence?, labels: List<String>): Boolean {
            val d = desc?.toString()?.trim().orEmpty()
            return d.isNotEmpty() && labels.any { it.equals(d, ignoreCase = true) }
        }

        /**
         * 音声モードの画面か: 「停止」があり、「マイクをミュート」があるか入力欄が無い
         * (文字のチャットで返事を止める「停止」は、入力欄と一緒に出るので取り違えない)
         */
        fun isVoiceScreen(descs: Collection<String>, hasInput: Boolean): Boolean =
            descs.any { matches(it, STOP) } && (descs.any { matches(it, MUTE_MIC) } || !hasInput)
    }
}
