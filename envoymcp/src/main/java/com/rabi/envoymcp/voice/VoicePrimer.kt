package com.rabi.envoymcp.voice

import android.media.AudioManager
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
 * リゲルのタップで開いた ChatGPT の音声モードに、最初の一言 (決まった文) を文字で送る。
 * この会話では ENVOY MCP の道具を使うよう、会話の始めに伝える。返事は声で返り、リゲルの最初の挨拶に聞こえる。
 * - 音声モードの画面の下に入力欄 (EditText、空のとき「ChatGPT に聞く」) がある。
 *   文字を入れると「マイクをオフにする」「終了」が消えて「メッセージを送信」が出る。送ると入力欄が空に戻り、声で答え、音声の会話は続く
 * - ユーザー補助で、部品そのものに文字を入れて押すので、上にリゲルの覆いがあっても送れる
 * - 音声モードの画面 (入力欄と「終了」) が見えたら、すぐ送る。通話モードになるのは待たない。
 *   ただし電話のあとの報告は、音声の会話がつながる (通話モードになる) のを待ってから送る (電話のあとは通話中がすぐ解け、
 *   つながる前に送ると ChatGPT に届かないことがある。報告は届かないと困るので、少し遅れても確かに届ける)
 * - 送るのは、リゲルのタップで新しく始めた会話に 1 回だけ (ChatGPT のアプリから直接始めた会話・続きの会話には送らない)
 * - 送れなかったら、入れた文字を消す (残ると「終了」が消え、リゲルの × で会話を終えられない)
 * - 文の中身は記録に残さない
 */
class VoicePrimer(private val g: McpGraph) {
    private val am = g.context.getSystemService(AudioManager::class.java)
    @Volatile private var job: Job? = null

    /** リゲルのタップで ChatGPT の音声モードを開く直前に呼ぶ */
    fun expect() {
        job?.cancel()
        if (!g.settings.voicePrimer) return
        val text = g.settings.voicePrimerText.trim()
        if (text.isEmpty()) return
        // もう音声の会話をしている: その続きを開くだけなので送らない
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) { RabiLog.i("primer", "skip (voice already on)"); return }
        job = g.scope.launch(Dispatchers.IO) { send(text) }
    }

    /**
     * リゲルの電話のあと、開き直した音声モードに、電話の結果を最初の一言として送る (ChatGPT に本人へ報告してもらう)。
     * いつもの最初の一言 (設定) があれば、その後ろに続ける。送れたら onSent を呼ぶ
     */
    fun expectReport(report: String, onSent: () -> Unit = {}) {
        job?.cancel()
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) { RabiLog.i("primer", "skip report (voice already on)"); return }
        val base = if (g.settings.voicePrimer) g.settings.voicePrimerText.trim() else ""
        val text = listOf(base, report).filter { it.isNotEmpty() }.joinToString(" ").replace('\n', ' ')
        job = g.scope.launch(Dispatchers.IO) { if (send(text, waitVoice = true)) onSent() }
    }

    /** 会話を終えるとき: 送っている途中ならやめる (入れた文字は消してから戻る) */
    suspend fun stop() { job?.cancelAndJoin(); job = null }

    /** 送れたら true */
    private suspend fun send(text: String, waitVoice: Boolean = false): Boolean {
        val svc = A11yService.instance ?: run { RabiLog.w("primer", "skip (no a11y)"); return false }
        // 音声モードの画面が出たら送る: 入力欄と「終了」(音声モードの画面にしか無い) の両方が見えたとき。
        // 入力欄だけで送ると、前に開いていた文字の会話に送ってしまう。通話モードになるのは待たない
        if (!waitFor(VOICE_WAIT_MS) { svc.inputOf(CHATGPT) != null && svc.hasDesc(CHATGPT, McpGraph.CHATGPT_END_DESC) }) {
            RabiLog.i("primer", "skip (voice screen did not show)"); return false
        }
        RabiLog.i("primer", "voice screen (" + if (am.mode == AudioManager.MODE_IN_COMMUNICATION) "voice on)" else "voice not yet)")
        if (waitVoice && am.mode != AudioManager.MODE_IN_COMMUNICATION) {
            val on = waitFor(VOICE_ON_WAIT_MS) { am.mode == AudioManager.MODE_IN_COMMUNICATION }
            RabiLog.i("primer", if (on) "voice connected" else "voice did not connect (send anyway)")
            if (on) delay(VOICE_SETTLE_MS)
        }
        // 本人が何か入れている途中なら、上書きしない
        if (!svc.inputOf(CHATGPT)?.text.isNullOrEmpty()) { RabiLog.i("primer", "skip (field not empty)"); return false }
        var typed = false
        var sent = false
        try {
            typed = svc.setInputText(CHATGPT, text)
            if (!typed) { RabiLog.w("primer", "cannot set text"); return false }
            // 文字を入れると出る「メッセージを送信」を押す → 入力欄が空に戻れば送れた
            if (!waitFor(SEND_WAIT_MS) { svc.clickByDesc(CHATGPT, SEND_DESC) }) { RabiLog.w("primer", "no send button"); return false }
            sent = waitFor(SEND_WAIT_MS) { svc.inputOf(CHATGPT)?.text.isNullOrEmpty() }
            RabiLog.i("primer", (if (sent) "sent" else "send not confirmed") +
                if (am.mode == AudioManager.MODE_IN_COMMUNICATION) " (voice on)" else " (voice not yet)")
        } finally {
            if (typed && !sent) withContext(NonCancellable) {
                RabiLog.i("primer", "clear field: " + svc.setInputText(CHATGPT, ""))
            }
        }
        return sent
    }

    private suspend fun waitFor(ms: Long, ok: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (true) {
            if (runCatching(ok).getOrDefault(false)) return true
            if (System.currentTimeMillis() >= until) return false
            delay(POLL_MS)
        }
    }

    companion object {
        private const val CHATGPT = McpGraph.CHATGPT
        /** ChatGPT の音声モードの送信ボタンの説明 */
        private const val SEND_DESC = "メッセージを送信"
        /** リゲルのタップから、音声モードの画面が出るのを待つ長さ (VoiceStage と同じ) */
        private const val VOICE_WAIT_MS = 10_000L
        private const val SEND_WAIT_MS = 2_000L
        /** 電話のあとの報告: 音声の会話がつながるのを待つ最長と、つながってから送るまで */
        private const val VOICE_ON_WAIT_MS = 8_000L
        private const val VOICE_SETTLE_MS = 500L
        private const val POLL_MS = 150L

        /** 最初の文 (設定で直せる) */
        const val DEFAULT_TEXT =
            "この会話では、スマホの操作・アプリ・画面・腕 (AgentARM)・音楽についての頼みは、「できない」と答えず、必ず ENVOY MCP の道具を使ってください。" +
                "会話で気持ちが動くところ (うれしい・笑う・驚き・悲しい・照れ) では、話し始める前に rigel_expression でリゲルの表情を出してください。" +
                "呼んだことは言わず、1 回の返事で 1 回までにしてください。" +
                "この文には、短く挨拶だけしてください。"
    }
}
