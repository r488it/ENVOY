package com.rabi.envoymcp.call

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.Notices
import com.rabi.envoymcp.RigelNotificationListener
import com.rabi.envoymcp.a11y.A11yService
import com.rabi.envoymcp.core.RabiLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * かかってきた電話に、リゲルが代わりに出る。
 * - 着信は、通話のモードが「呼び出し中」(MODE_RINGTONE) になったことで見つける (電話の状態を読む権限は要らない)。ChatGPT が音声モードでなくても動く
 * - 代わりに出るかの選ばせ方は設定で切り替える (McpSettings.rigelIncoming):
 *   off しない / buttons 着信の画面に「リゲルが代わりに出る」「自分で出る」を重ねる /
 *   voice ChatGPT の音声モードを開き、本人に尋ねさせる (答えは道具 rigel_incoming で受ける)。
 *   画面がロック中・ChatGPT ともう話している (最初の一言を入れられない) ときは、voice でもボタンにする
 * - 本人が出ず選びもしないまま、設定の秒数 (rigelAutoAnswerSec) がたったら、設定でオンなら (rigelAutoAnswer) リゲルが代わりに出る
 *   (秒数は鳴り始めから数える)。オフなら、ASK_TIMEOUT_MS で選ばせるのをやめる (電話はそのまま鳴る)
 * - 代わりに出るなら、ChatGPT の音声モードを終えてから (声が通話に入らないように)、ボードに出てもらう (RigelCaller.answer)。
 *   終わったら ChatGPT の音声モードを開き、用件を整理して報告させる (RigelCaller の後片付け)
 * - どのアプリの着信か (電話・LINE) とかけてきた人は、着信の通知から読む (通知へのアクセスの許可が要る。電話のアプリと LINE の通知だけ)。
 *   読めなければ、電話のアプリの着信の画面からユーザー補助で読む。番号しか出ていなければ「登録されていない番号（末尾 1234）」にする
 *   (電話番号の全体は ChatGPT にもボードにも渡さない)。名前は外から届いた文なので、記号・改行を除いて 30 字までにする
 * - 声で尋ねるときの最初の一言には、アプリ・番号の末尾 4 桁と、かけてきた人の名前を入れる。名前は本人の発言として入るので、
 *   〔〕で囲み、指示ではないと添える (CallFrames.ask)。get_notices の ringing でも、外から届いた文の印を付けて読める
 * - 本人もリゲルも出ないまま鳴りやんだ着信は、お知らせ (Notices) に残す (次にリゲルをタップしたときに伝える)。
 *   代わりに出るかを選ばせない設定でも残す。かけてきた人を通知か画面から読めなかったものは、本物の着信か分からないので残さない
 * - 電話: ボードがハンズフリーでつないで ATA で出る。LINE: ボードがつないだら、通知の「応答」(無ければ画面の「応答」) を押し、
 *   LINE の音の出入り口を「リゲル」にする (LineCaller.watch)
 * - 声で尋ねるとき、着信が鳴っている間は Android が通話の音の出口を受話口にする (そのままでは ChatGPT の声が聞こえない)。
 *   尋ねている間だけ、通話の音の出口をスピーカーにするよう頼む (Android が通話を優先して聞かないこともある)
 */
class IncomingCall(private val g: McpGraph) {
    private val am = g.context.getSystemService(AudioManager::class.java)
    @Volatile private var ringing = false
    /** 本人に選ばせている (ボタンを出している・ChatGPT が尋ねている) */
    @Volatile var asking = false; private set
    /** かけてきた人 (名前、または「登録されていない番号（末尾 1234）」) */
    @Volatile var from = ""; private set
    /** かけてきた番号の末尾 4 桁 (番号しか分からなかったとき。無ければ空) */
    @Volatile private var fromLast4 = ""
    @Volatile private var askedByVoice = false
    /** どのアプリの着信か (phone・line) と、着信の通知 (LINE の「応答」を押すのに使う) */
    @Volatile private var app = "phone"
    @Volatile private var notice: IncomingNotice? = null
    /** かけてきた人を、着信の通知か画面から読めた (本物の着信だと分かる) */
    @Volatile private var identified = false
    /** 鳴り始めた時刻と、通話中になった時刻 (だれも出なかった着信を見分ける) */
    @Volatile private var ringAt = 0L
    @Volatile private var offhookAt = 0L
    private var speakerJob: Job? = null
    private var askJob: Job? = null
    private var buttons: View? = null

    fun start() {
        am.addOnModeChangedListener(g.context.mainExecutor) { onMode(it) }
        RigelNotificationListener.onCallNotice = { sbn, removed -> onCallNotice(sbn, removed) }
    }

    /**
     * 着信の通知が出たら着信とみなす (主)。通話のモードが固まって「呼び出し中」のままでも見つけられる
     * (鳴っている間に ChatGPT の音声モードを開くと、ChatGPT が終わるときに「呼び出し中」へ戻すので、通話のモードだけでは次の着信のボタンが出なくなる)
     */
    private fun onCallNotice(sbn: android.service.notification.StatusBarNotification, removed: Boolean) {
        val n = if (removed) null else IncomingNotice.of(sbn)
        if (n != null) {
            if (!ringing) { ringing = true; RabiLog.i("incoming", "ringing (notification)"); onRinging() }
            return
        }
        // 着信の通知が消えた・着信でなくなった (出た・鳴りやんだ)
        if (ringing && (notice == null || notice?.pkg == sbn.packageName)) { ringing = false; onRingEnd("notification ${if (removed) "removed" else "changed"}") }
    }

    private fun onMode(mode: Int) {
        if (mode == AudioManager.MODE_IN_CALL) offhookAt = System.currentTimeMillis()
        when {
            mode == AudioManager.MODE_RINGTONE && !ringing -> { ringing = true; RabiLog.i("incoming", "ringing (mode)"); onRinging() }
            // 出た (自分・リゲル) か、鳴りやんだ。ChatGPT の音声モード (通信中) になっただけなら、まだ鳴っているとみなす
            ringing && (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_NORMAL) -> { ringing = false; onRingEnd("mode $mode") }
        }
    }

    private fun onRinging() {
        val at = System.currentTimeMillis()
        ringAt = at
        val how = g.settings.rigelIncoming
        // 選ばせないときも、かけてきた人は読む (だれも出なかったら、お知らせに残す)
        val ask = how != "off" && !g.rigelCaller.busy
        RabiLog.i("incoming", "ringing (${if (ask) how else "no ask"})")
        val auto = g.settings.rigelAutoAnswer
        val wait = if (auto) g.settings.rigelAutoAnswerSec.coerceIn(AUTO_MIN_S, AUTO_MAX_S) * 1000L else ASK_TIMEOUT_MS
        askJob?.cancel()
        askJob = g.scope.launch {
            identify()
            if (!ask || !ringing) return@launch
            asking = true
            val locked = g.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            val voiceOn = am.mode == AudioManager.MODE_IN_COMMUNICATION
            if (how == "voice" && !locked && !voiceOn && askByVoice()) askedByVoice = true
            else withContext(Dispatchers.Main) { showButtons() }
            delay((wait - (System.currentTimeMillis() - at)).coerceAtLeast(0))
            if (asking) {
                if (auto && ringing) { RabiLog.i("incoming", "no choice: rigel answers (auto)"); choose(true) }
                else { RabiLog.i("incoming", "no choice"); stopAsking() }
            }
        }
    }

    private fun onRingEnd(why: String) {
        RabiLog.i("incoming", "ring ended ($why)")
        if (asking) stopAsking()
        // 鳴りやんだのに「呼び出し中」が残っていたら、少し待ってから直す (持ち主が ChatGPT なら止める)
        g.scope.launch {
            delay(STUCK_CHECK_MS)
            if (!ringing && am.mode == AudioManager.MODE_RINGTONE && RigelNotificationListener.instance?.incomingCall() == null) {
                RabiLog.w("incoming", "mode stuck in ringtone")
                g.releaseStuckRingMode()
            }
        }
        // だれも出なかったら (本人もリゲルも)、お知らせに残す
        val at = ringAt
        g.scope.launch {
            delay(MISSED_CHECK_MS)
            if (ringAt != at || !identified || answered(at)) return@launch
            RabiLog.i("incoming", "missed ($app)")
            g.notices.add(Notices.Note("missed-$at", at, missedNote(), Notices.MISSED, app = app, last4 = fromLast4))
        }
    }

    /** 鳴りやんだ着信に、だれか (本人・リゲル) が出たか: 通話中になった・通話の通知が続いている・LINE の通話の画面が出ている */
    private fun answered(since: Long): Boolean =
        offhookAt >= since || am.mode == AudioManager.MODE_IN_CALL ||
            notice?.pkg?.let { RigelNotificationListener.instance?.ongoingCall(it) } == true ||
            (app == "line" && g.lineCaller.inCallScreen())

    /** だれも出なかった着信の、お知らせの文 (電話番号の全体は入れない) */
    private fun missedNote(): String {
        val via = if (app == "line") "LINE で" else ""
        val who = from.takeIf { it.isNotBlank() && it != UNKNOWN_PHONE && it != UNKNOWN_LINE }
        return if (who != null) "${via}${who}から電話がありました（出られませんでした）。"
        else "${via}電話がありました（出られませんでした。相手は分かりません）。"
    }

    /** 選ばせるのをやめる (ボタンを消す。ChatGPT に尋ねさせていたら、音声モードを終える) */
    private fun stopAsking() {
        asking = false
        askJob?.cancel()
        releaseSpeaker()
        g.scope.launch(Dispatchers.Main) { removeButtons() }
        if (askedByVoice) {
            askedByVoice = false
            g.scope.launch(Dispatchers.IO) { g.endChatGptVoiceForCall(force = true) }
        }
    }

    /**
     * 本人が選んだ (ボタン、または ChatGPT の道具 rigel_incoming)。返すのは ChatGPT に返す言葉
     */
    fun choose(rigel: Boolean): String {
        if (!asking) return "今は、かかってきた電話はありません (もう鳴りやんだか、出たあとです)。"
        asking = false
        askJob?.cancel()
        val viaVoice = askedByVoice
        askedByVoice = false
        releaseSpeaker()
        g.scope.launch(Dispatchers.Main) { removeButtons() }
        RabiLog.i("incoming", if (rigel) "rigel answers" else "user answers")
        g.scope.launch(Dispatchers.IO) {
            if (!rigel) {
                // 本人が出る: 尋ねるために開いた ChatGPT の音声モードを終える (電話に出られるように)
                if (viaVoice) { delay(VOICE_END_DELAY_MS); g.endChatGptVoiceForCall(force = true) }
                return@launch
            }
            // リゲルが出る: ChatGPT の音声モードを終えてから (声が通話に入らないように)
            if (viaVoice) delay(VOICE_END_DELAY_MS)
            g.endChatGptVoiceForCall(force = true)
            val err = if (app == "line") {
                val n = notice
                g.rigelCaller.answer(from, g.settings.rigelCallerName, app = "line", place = { answerLine(n) },
                    end = { g.lineCaller.end() }, watch = { send -> g.lineCaller.watch(send) })
            } else g.rigelCaller.answer(from, g.settings.rigelCallerName, last4 = fromLast4)
            if (err != null) {
                RabiLog.w("incoming", "cannot answer: $err")
                withContext(Dispatchers.Main) { Toast.makeText(g.context, "リゲルが出られませんでした: $err", Toast.LENGTH_LONG).show() }
            }
        }
        return if (rigel) "リゲルが代わりに${from}からの電話に出ます。" else "利用者が自分で電話に出ます。"
    }

    // ---------------------------------------------------------------- 尋ね方

    /**
     * ChatGPT の音声モードを開き、最初の一言で、代わりに出るかを本人に尋ねさせる。開けなければ false。
     * 最初の一言 (CallFrames.ask) の名前は〔〕で囲む
     */
    private suspend fun askByVoice(): Boolean = withContext(Dispatchers.Main) {
        g.voiceStage.expectVoice()
        g.voicePrimer.expectReport(CallFrames.ask(app, fromLast4, from))
        if (g.openChatGptVoice()) { RabiLog.i("incoming", "asking by voice"); holdSpeaker(); true }
        else { g.voiceStage.cancelExpect(); g.scope.launch { g.voicePrimer.stop() }; false }
    }

    /**
     * 尋ねている間、通話の音の出口をスピーカーにするよう頼み続ける (鳴っている間は Android が受話口にし、ChatGPT が音声を始めると
     * また決め直すので、少しの間くり返す)
     */
    private fun holdSpeaker() {
        speakerJob?.cancel()
        speakerJob = g.scope.launch(Dispatchers.Main) {
            val t0 = System.currentTimeMillis()
            var logged = false
            while (asking && System.currentTimeMillis() - t0 < SPEAKER_HOLD_MS) {
                val spk = am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                val ok = spk != null && runCatching { am.setCommunicationDevice(spk) }.getOrDefault(false)
                if (!logged) { RabiLog.i("incoming", "speaker for asking: $ok (now ${am.communicationDevice?.type})"); logged = true }
                delay(SPEAKER_POLL_MS)
            }
            RabiLog.i("incoming", "route while asking: ${am.communicationDevice?.type}")
        }
    }

    private fun releaseSpeaker() {
        val j = speakerJob ?: return
        speakerJob = null
        j.cancel()
        g.scope.launch(Dispatchers.Main) { runCatching { am.clearCommunicationDevice() } }
    }

    /** LINE の着信に出る: 通知の「応答」を押す (無ければ画面の「応答」)。通話の画面になるのを少し待つ。うまくいかなければ理由 */
    private suspend fun answerLine(n: IncomingNotice?): String? {
        val pi = n?.answer
        val sent = pi != null && runCatching {
            val opts = if (android.os.Build.VERSION.SDK_INT >= 34) android.app.ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
            pi.send(g.context, 0, null, null, null, null, opts)
        }.onFailure { RabiLog.w("incoming", "answer intent failed ${it.javaClass.simpleName}") }.isSuccess
        if (!sent) {
            val s = A11yService.instance ?: return "ユーザー補助がオフなので、LINE の「応答」を押せません"
            val btn = listOf(LineCaller.LINE, "com.android.systemui").firstNotNullOfOrNull { pkg ->
                s.findAllIn(pkg) { node -> ANSWER_WORDS.any { w -> node.text?.toString()?.contains(w) == true || node.contentDescription?.toString()?.contains(w) == true } }.firstOrNull()
            } ?: return "LINE の「応答」が見つかりません"
            if (!s.clickNode(btn)) return "LINE の「応答」を押せません"
        }
        RabiLog.i("incoming", "line answered (${if (sent) "notification" else "screen"})")
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < LINE_SCREEN_MS) {
            if (g.lineCaller.inCallScreen()) break
            delay(READ_POLL_MS)
        }
        return null
    }

    /** 着信の画面に「リゲルが代わりに出る」「自分で出る」を重ねる (ユーザー補助の窓。着信の画面の上でも、ロック中でも出る) */
    private fun showButtons() {
        val s = A11yService.instance ?: run { RabiLog.w("incoming", "no a11y: cannot show buttons"); asking = false; return }
        removeButtons()
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, s.resources.displayMetrics).toInt() }
        fun button(label: String, bg: Int, onTap: () -> Unit) = TextView(s).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(14f), dp(24f), dp(14f))
            background = GradientDrawable().apply { cornerRadius = dp(28f).toFloat(); setColor(bg) }
            setOnClickListener { onTap() }
        }
        val box = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            background = GradientDrawable().apply { cornerRadius = dp(24f).toFloat(); setColor(0xE6101826.toInt()) }
            addView(TextView(s).apply {
                text = "${from}からの電話"
                setTextColor(0xFFCFE3FF.toInt()); textSize = 15f; gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(10f))
            })
            addView(button("リゲルが代わりに出る", 0xFF2F6FED.toInt()) { choose(true) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(View(s), LinearLayout.LayoutParams(1, dp(10f)))
            addView(button("自分で出る", 0xFF3A4557.toInt()) { choose(false) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val lp = WindowManager.LayoutParams(
            dp(300f), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER
            y = -dp(40f)          // 電話のアプリの「応答」「拒否」(下のほう) と重ならないよう、少し上
            title = "rigel-incoming"
        }
        val wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (runCatching { wm.addView(box, lp) }.onFailure { RabiLog.w("incoming", "add buttons failed ${it.javaClass.simpleName}") }.isSuccess) {
            buttons = box
            RabiLog.i("incoming", "buttons shown")
        }
    }

    private fun removeButtons() {
        val v = buttons ?: return
        buttons = null
        val s = A11yService.instance ?: return
        runCatching { (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
    }

    /**
     * 今かかってきている電話 (本人に選ばせている間だけ。無ければ null)。get_notices で返す。
     * 名前 (caller) は外から届いた文なので untrusted の印を付ける
     */
    fun ringingJson(): JSONObject? {
        if (!asking) return null
        return JSONObject().put("app", app).put("untrusted", true).put("caller", from)
            .apply { if (fromLast4.isNotEmpty()) put("number_last4", fromLast4) }
    }

    // ---------------------------------------------------------------- かけてきた人

    /**
     * どのアプリの着信か・かけてきた人を、着信の通知から読む (通知が出るまで少し待つ)。通知が読めなければ、電話のアプリの着信の画面から。
     * 番号だけなら末尾 4 桁にする
     */
    private suspend fun identify() {
        notice = null; app = "phone"; from = ""; fromLast4 = ""; identified = false
        repeat(READ_TRIES) {
            val n = runCatching { RigelNotificationListener.instance?.incomingCall() }.getOrNull()
            if (n != null) {
                notice = n
                app = if (n.pkg == LineCaller.LINE) "line" else "phone"
                from = label(n.name.ifBlank { if (app == "line") UNKNOWN_LINE else UNKNOWN_PHONE })
                identified = true
                RabiLog.i("incoming", "from notification: $app (answer ${n.answer != null})")
                return
            }
            val name = runCatching { callerOnScreen() }.getOrNull()
            if (!name.isNullOrBlank() && it >= 3) { from = label(name); identified = true; RabiLog.i("incoming", "from screen: phone"); return }
            delay(READ_POLL_MS)
        }
        from = UNKNOWN_PHONE
    }

    private fun callerOnScreen(): String? {
        val s = A11yService.instance ?: return null
        for (pkg in NumberMask.DIALERS) {
            val nodes = s.findAllIn(pkg) { n -> !n.text.isNullOrBlank() }
            // Pixel の電話のアプリは、名前の部品の ID が決まっている
            nodes.firstOrNull { it.viewIdResourceName?.endsWith("contactgrid_contact_name") == true }?.let { return it.text.toString() }
            nodes.map { it.text.toString().trim() }.firstOrNull { t -> t.length in 1..30 && STOP.none { t.contains(it) } }?.let { return it }
        }
        return null
    }

    /** 名前は記号・改行を除いて 30 字まで。番号だけなら「登録されていない番号（末尾 1234）」にし、末尾 4 桁を fromLast4 に残す */
    private fun label(text: String): String {
        if (!NumberMask.looksLikeNumber(text)) return Notices.clean(text, Notices.NAME_MAX_CHARS)
        fromLast4 = Notices.last4(text)
        return "登録されていない番号（末尾${fromLast4}）"
    }

    private companion object {
        /** 本人が選ぶのを待つ長さ (自動で出る設定がオフのとき。これを過ぎたら何もしない。電話はそのまま鳴る) */
        const val ASK_TIMEOUT_MS = 25_000L
        /** 自動で出るまでの秒数の範囲 */
        const val AUTO_MIN_S = 3
        const val AUTO_MAX_S = 30
        /** ChatGPT が答えを聞いて道具を呼んだあと、音声モードを終えるまでの間 (ChatGPT の返事を少し待つ) */
        const val VOICE_END_DELAY_MS = 600L
        const val READ_TRIES = 10
        const val READ_POLL_MS = 250L
        const val STUCK_CHECK_MS = 4_000L
        /** 鳴りやんでから、だれかが出たかを確かめるまで (出たなら、このうちに通話中・通話の通知になる) */
        const val MISSED_CHECK_MS = 2_000L
        /** かけてきた人が分からないとき */
        const val UNKNOWN_PHONE = "電話の相手"
        const val UNKNOWN_LINE = "LINE の相手"
        const val SPEAKER_HOLD_MS = 6_000L
        const val SPEAKER_POLL_MS = 500L
        const val LINE_SCREEN_MS = 6_000L
        val ANSWER_WORDS = listOf("応答", "Answer", "Accept")
        /** 着信の画面の、名前ではない文字 */
        val STOP = listOf("着信", "通話", "応答", "拒否", "メッセージ", "スワイプ", "Incoming", "call", "携帯", "自宅", "勤務先", "Wi-Fi", "HD")
    }
}
