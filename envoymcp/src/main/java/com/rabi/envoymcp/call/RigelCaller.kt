package com.rabi.envoymcp.call

import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.core.RabiLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * リゲルが代わりに電話して用件を伝える (ChatGPT に「〇〇に電話して XX と伝えて」と頼む)。
 * ボードの常駐 (callservice.py、TCP 8093) に頼み、ボードがヘッドセット「リゲル」として
 * Pixel とつながったら (ready)、place で発信する (電話: 本人が承認した番号に ENVOY MCP がかける。番号はボードに送らない。
 * LINE: LINE を操作して発信し、音の出入り口を「リゲル」にする)。通話はボードの Gemini 3.8 Live が受け持つ。
 * 結果 (伝わったか・相手の返事の要点・やり取り) はボードから届き、rigel_call_status で返す。1 度に 1 本
 * - 電話の間は、電話のアプリの画面の電話番号をモザイクで隠す (NumberMask。設定でオンのときだけ。最初はオフ)。電話のあとは、リゲルの画面に戻す (VoiceStage)
 * - ChatGPT の音声モードから頼まれたら、電話をかける前に音声モードを終え (話すと声が通話の音に入る)、電話のあとに開き直して、
 *   最初の一言で結果を送り、ChatGPT から本人に報告してもらう
 * - 通話の間は、リゲルと相手のやり取りを文字で画面に出し (CallScreen。ボードが言葉を届くたびに送る)、終わったら記録に残す
 *   (CallRecords)。設定で録音がオンなら、ボードが録った通話の音も結果の直前に届くので、一緒に残す (相手には伝えない)
 */
class RigelCaller(private val g: McpGraph) {
    @Volatile private var job: Job? = null
    @Volatile private var out: OutputStream? = null
    val busy get() = job?.isActive == true

    /** 今の様子 (idle / preparing / ready / dialing / ringing / talking / done / failed) と、相手・方法・最後の結果 */
    @Volatile var state = "idle"; private set
    @Volatile var to = ""; private set
    @Volatile var app = ""; private set
    @Volatile var result: JSONObject? = null; private set
    @Volatile var startedAt = 0L; private set
    /** この電話のために ChatGPT の音声モードを終えた (電話のあとに開き直す) */
    @Volatile private var endedVoice = false
    /** かかってきた電話にリゲルが代わりに出ている (終わったら、必ず ChatGPT の音声モードを開いて用件を報告させる) */
    @Volatile private var incoming = false

    private val _live = MutableStateFlow<CallInfo?>(null)
    /** いまの (最後の) 代行の通話: 相手・様子・やり取り (CallScreen が出す) */
    val live: StateFlow<CallInfo?> = _live
    /** ボードから届いている録音 (届き終えたら recReady) */
    private var recFile: File? = null
    private var recOut: OutputStream? = null
    @Volatile private var recReady = false
    @Volatile private var recRate = 8_000

    /**
     * 頼む。ボードが ready になったら place() で発信する (失敗なら理由を返す)。end() は、Gemini が電話を切ると言ったとき (LINE: 通話を終える)、
     * watch(send) は、発信のあと通話を見張り、相手が出たら send("answered")、終わったら send("ended") を呼ぶ。
     * 返すのは、始められなければ理由 (始められたら null。結果はあとで state・result)
     */
    suspend fun start(app: String, to: String, message: String, caller: String,
                      place: suspend () -> String?, end: suspend () -> Unit = {}, watch: (suspend ((String) -> Unit) -> Unit)? = null): String? {
        if (busy) return "ほかの電話をしています (rigel_call_status で様子が分かります)"
        val ip = g.arm.armIp ?: return "腕のボードにつながっていないので、電話できません。腕の電源とつながりを確かめてください"
        this.app = app; this.to = to; result = null; state = "preparing"; startedAt = System.currentTimeMillis()
        incoming = false
        begin(CallInfo(CallInfo.newId(startedAt), "out", app, to, message = message, startedAt = startedAt))
        g.voiceStage.holdForRigelCall()
        if (app == "phone" && g.settings.numberMask) g.numberMask.start()
        endedVoice = false
        // ChatGPT の音声モードを終える (ボードが Pixel につなぐ間に)。電話をかける前に終わるのを待つ
        val voiceEnd = if (app == "phone") g.scope.async(Dispatchers.IO) { runCatching { g.endChatGptVoiceForCall() }.getOrDefault(false) } else null
        val ready = CompletableDeferred<String?>()
        job = g.scope.launch(Dispatchers.IO) {
            try {
                session(ip, JSONObject().put("cmd", "call").put("app", app).put("to", to).put("message", message).put("caller", caller)
                    .put("record", g.settings.rigelRecord),
                    ready, { if (voiceEnd?.await() == true) endedVoice = true; place() }, end, watch)
            } catch (e: Exception) {
                RabiLog.w("rigelcall", "session failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
                if (!ready.isCompleted) ready.complete("ボードの電話の代役につながりません (${e.javaClass.simpleName})")
                if (result == null) finish(JSONObject().put("outcome", "failed").put("reason", e.javaClass.simpleName))
            } finally {
                out = null
                dropRecording()
                g.callScreen.hideAfter()
                if (!ready.isCompleted) ready.complete("ボードの電話の代役が途中で終わりました")
            }
        }
        // ready の中身は、うまくいけば null (理由なし)。時間切れと取り違えないよう、箱に入れて待つ
        // (取り違えると、発信できたのにボードに「やめて」と送ってしまい、音が「リゲル」に来ない)
        val got = withTimeoutOrNull(READY_TIMEOUT_MS) { listOf(ready.await()) }
        val err = if (got == null) "ボードの用意 (ヘッドセットとしてつなぐ) が間に合いませんでした" else got[0]
        if (err != null) {
            hangup(); job?.cancel(); state = "failed"
            if (voiceEnd?.await() == true) endedVoice = true
            cleanUp(JSONObject().put("outcome", "failed").put("reason", err))
        }
        return err
    }

    /**
     * かかってきた電話に、リゲルが代わりに出る (IncomingCall から)。ボードが Pixel にハンズフリーでつなぎ、
     * 「着信中」を見て ATA で出る。Gemini がすぐ名乗って用件を聞き、end_call で切る。終わったら、設定でオンなら ChatGPT の音声モードを開いて報告させる
     * (rigelIncomingReport。最初はオフ: 通話の記録に残すだけ)。
     * from はかけてきた人 (名前か「登録されていない番号（末尾 1234）」。番号の全体は送らない)。返すのは、出られなければ理由。
     * LINE (app = line): ボードがつないだら (ready) place で「応答」を押し、watch が音の出入り口を「リゲル」にする。切るのは end
     */
    suspend fun answer(from: String, caller: String, app: String = "phone", place: suspend () -> String? = { null },
                       end: suspend () -> Unit = {}, watch: (suspend ((String) -> Unit) -> Unit)? = null): String? {
        if (busy) return "ほかの電話をしています"
        val ip = g.arm.armIp ?: return "腕のボードにつながっていないので、代わりに出られません"
        this.app = app; to = from; result = null; state = "preparing"; startedAt = System.currentTimeMillis()
        incoming = true
        begin(CallInfo(CallInfo.newId(startedAt), "in", app, from, startedAt = startedAt))
        endedVoice = false
        g.voiceStage.holdForRigelCall()
        if (app == "phone" && g.settings.numberMask) g.numberMask.start()
        val ready = CompletableDeferred<String?>()
        job = g.scope.launch(Dispatchers.IO) {
            try {
                session(ip, JSONObject().put("cmd", "answer").put("app", app).put("from", from).put("caller", caller)
                    .put("record", g.settings.rigelRecord),
                    ready, place, end, watch ?: { send -> watchPhone(send) })
            } catch (e: Exception) {
                RabiLog.w("rigelcall", "answer session failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
                if (!ready.isCompleted) ready.complete("ボードの電話の代役につながりません (${e.javaClass.simpleName})")
                if (result == null) finish(JSONObject().put("outcome", "failed").put("reason", e.javaClass.simpleName))
            } finally {
                out = null
                dropRecording()
                g.callScreen.hideAfter()
                if (!ready.isCompleted) ready.complete("ボードの電話の代役が途中で終わりました")
            }
        }
        val got = withTimeoutOrNull(ANSWER_TIMEOUT_MS) { listOf(ready.await()) }
        val err = if (got == null) "ボードが電話に出るのが間に合いませんでした" else got[0]
        if (err != null) {
            hangup(); job?.cancel(); state = "failed"
            if (result == null) cleanUp(JSONObject().put("outcome", "failed").put("reason", err))
        }
        return err
    }

    /** 途中でやめる (ボードが切る) */
    fun hangup() {
        g.scope.launch(Dispatchers.IO) { send(JSONObject().put("cmd", "hangup")) }
    }

    private suspend fun session(ip: String, req: JSONObject, ready: CompletableDeferred<String?>,
                                place: suspend () -> String?, end: suspend () -> Unit, watch: (suspend ((String) -> Unit) -> Unit)?) {
        Socket().use { so ->
            so.connect(InetSocketAddress(ip, PORT), 3_000)
            so.tcpNoDelay = true
            out = so.getOutputStream()
            val rd = so.getInputStream().bufferedReader()
            send(req)
            var watcher: Job? = null
            while (true) {
                val line = rd.readLine() ?: break
                val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                when (ev.optString("event")) {
                    "hello" -> if (ev.optBoolean("busy")) { ready.complete("ボードはほかの電話をしています"); return }
                    "error" -> {
                        val reason = ev.optString("reason")
                        RabiLog.w("rigelcall", "uno error: $reason")
                        if (!ready.isCompleted) { ready.complete("ボード: $reason"); return }
                    }
                    "status" -> {
                        val st = ev.optString("state")
                        if (st.isNotEmpty() && st != "idle") { state = st; onState(st) }
                        RabiLog.i("rigelcall", "state $st")
                        if ((st == "ready" || st == "answering") && !ready.isCompleted) {
                            val err = place()
                            if (err != null) { RabiLog.w("rigelcall", "place failed: $err"); send(JSONObject().put("cmd", "hangup")); ready.complete(err) }
                            else {
                                g.voiceStage.yieldToCall()      // 電話の画面を見せる (リゲルの覆いを消す)
                                g.callScreen.show()             // その上に、やり取りを文字で出す画面
                                ready.complete(null)
                                watch?.let { w -> watcher = g.scope.launch(Dispatchers.IO) { w { cmd -> send(JSONObject().put("cmd", cmd)) } } }
                            }
                        }
                    }
                    "text" -> onText(ev.optString("who"), ev.optString("text"), ev.optBoolean("final"))
                    "audio_begin" -> runCatching {
                        dropRecording()
                        recRate = ev.optInt("rate", 8_000)
                        recFile = File(g.context.cacheDir, "rigelcall.pcm").also { it.delete() }
                        recOut = recFile!!.outputStream().buffered()
                    }.onFailure { RabiLog.w("rigelcall", "recording file failed: ${it.javaClass.simpleName}") }
                    "audio" -> recOut?.let { o -> runCatching { o.write(java.util.Base64.getDecoder().decode(ev.optString("data"))) }
                        .onFailure { RabiLog.w("rigelcall", "recording chunk failed: ${it.javaClass.simpleName}"); dropRecording() } }
                    "audio_end" -> recOut?.let { o ->
                        runCatching { o.close() }
                        recOut = null; recReady = true
                        RabiLog.i("rigelcall", "recording received (${(recFile?.length() ?: 0) / 2 / recRate} s)")
                    }
                    "end_request" -> {
                        RabiLog.i("rigelcall", "end requested")
                        runCatching { end() }
                        send(JSONObject().put("cmd", "ended"))
                    }
                    "result" -> { watcher?.cancel(); finish(ev); return }
                }
            }
            watcher?.cancel()
            if (result == null) finish(JSONObject().put("outcome", "failed").put("reason", "ボードとのつながりが切れました"))
        }
    }

    /**
     * 電話の状態を ADB (dumpsys telephony.registry の PreciseCallState) で見て、相手が出たら answered、切れたら ended を送る。
     * ボードは、Pixel が送る通話の一覧 (+CLCC) の番号が空だと通話の状態を読めない (相手が出たのが分からず、最初の「もしもし」を聞き逃す)
     */
    suspend fun watchPhone(send: (String) -> Unit) {
        if (!g.adb.isConnected && g.adb.paired) runCatching { g.adb.ensureConnected() }
        if (!g.adb.isConnected) { RabiLog.i("rigelcall", "no adb: uno decides by audio"); return }
        var dialing = false
        var active = false
        val t0 = System.currentTimeMillis()
        while (kotlin.coroutines.coroutineContext[Job]?.isActive != false && System.currentTimeMillis() - t0 < WATCH_MS) {
            val text = runCatching { g.adb.exec("dumpsys telephony.registry | grep mPreciseCallState", 3_000).text }.getOrNull().orEmpty()
            // SIM ごとに 1 行。0 待ち受け・1 通話中・3 発信中・4 呼び出し中・7 切れた (-1 は使っていない SIM)
            val fg = FG_RE.findAll(text).map { it.groupValues[1].toInt() }.filter { it > 0 }.maxOrNull() ?: 0
            when {
                fg == 1 && !active -> { active = true; RabiLog.i("rigelcall", "answered"); send("answered") }
                fg == 3 || fg == 4 -> dialing = true
                (dialing || active) && (fg == 0 || fg == 7) -> { RabiLog.i("rigelcall", "call ended"); send("ended"); return }
            }
            kotlinx.coroutines.delay(WATCH_POLL_MS)
        }
    }

    /**
     * 電話が終わった: 番号を隠すのをやめ (少し残す)、リゲルの画面に戻す (音声モードを終えていたら、結果を添えて開き直す)。
     * 本人に報告しないとき・開き直せなかったときは、結果をお知らせ (Notices) に残す (次にリゲルをタップしたときに伝える)
     */
    private fun cleanUp(ev: JSONObject?) {
        g.numberMask.stopAfter()
        val c = _live.value
        if (incoming) {
            incoming = false
            endedVoice = false
            // 出られなかった: 本人が出られるよう、何もしない (だれも出なければ、IncomingCall がお知らせに残す)
            if (ev?.optString("outcome") == "failed") { g.voiceStage.afterRigelCall(); return }
            val keep = keeper(c, noteIncoming(ev))
            if (!g.settings.rigelIncomingReport) { g.voiceStage.afterRigelCall(); keep(); return }          // ChatGPT は開かない (通話の記録とお知らせに残す)
            g.voiceStage.releaseHold()
            g.reopenVoiceAfterCall(reportIncoming(ev), onSkip = keep)
            return
        }
        val keep = keeper(c, note(ev))
        if (endedVoice) {
            endedVoice = false
            g.voiceStage.releaseHold()
            g.reopenVoiceAfterCall(report(ev), onSkip = keep)
        } else {
            g.voiceStage.afterRigelCall()
            // かけられなかったときは、頼んだ AI にその場で理由を返しているので残さない
            if (ev?.optString("outcome") != "failed") keep()
        }
    }

    /** お知らせに残す (呼んだとき) */
    private fun keeper(c: CallInfo?, text: String): () -> Unit = { if (c != null) g.notices.add(c.id, c.startedAt, text) }

    /** 結果を AI が受け取った (rigel_call_status): 本人にはその AI が伝えるので、お知らせからは消す */
    fun resultDelivered() {
        if (result == null) return
        _live.value?.let { g.notices.remove(listOf(it.id)) }
    }

    /** ChatGPT に送る電話の結果 (開き直した音声モードの最初の一言)。電話番号は入れない */
    private fun report(ev: JSONObject?): String =
        "（リゲルの電話が終わりました）${to}への電話: ${outcome(ev)}。" + summary(ev, "要点") + "この結果を、私に短く声で伝えてください。"

    /** かかってきた電話の結果 (開いた音声モードの最初の一言)。電話番号の全体は入れない */
    private fun reportIncoming(ev: JSONObject?): String =
        "（リゲルが代わりに電話に出ました）${to}からの電話: ${outcomeIncoming(ev)}。" + summary(ev, "内容") +
            "この内容を、私に分かりやすく整理して、短く声で伝えてください。"

    /** お知らせに残す文 (かけた電話・かかってきた電話) */
    private fun note(ev: JSONObject?): String = "リゲルが${to}に電話しました: ${outcome(ev)}。" + summary(ev, "要点")
    private fun noteIncoming(ev: JSONObject?): String = "リゲルが代わりに${to}からの電話に出ました: ${outcomeIncoming(ev)}。" + summary(ev, "内容")

    private fun outcome(ev: JSONObject?): String = when (ev?.optString("outcome")) {
        "conveyed", "done" -> "用件を伝えました"
        "voicemail" -> "留守番電話に用件を残しました"
        "refused" -> "用件を伝えましたが、断られました"
        "unclear" -> "用件が伝わったか、はっきりしませんでした"
        "no_answer" -> "相手が出ませんでした"
        "hung_up" -> "途中で電話が切れました"
        "stopped" -> "電話を取りやめました"
        "timeout" -> "長くなったので電話を切りました"
        else -> "電話をかけられませんでした" + (ev?.optString("reason")?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
    }

    private fun outcomeIncoming(ev: JSONObject?): String = when (ev?.optString("outcome")) {
        "message", "done", "conveyed" -> "用件を伺いました"
        "no_message" -> "用件を聞けないまま、電話が終わりました"
        "hung_up" -> "途中で電話が切れました"
        "timeout" -> "長くなったので電話を切りました"
        "stopped" -> "電話を途中でやめました"
        else -> "用件がはっきりしませんでした"
    }

    /** 「要点: ……。」(無ければ空) */
    private fun summary(ev: JSONObject?, label: String): String =
        ev?.optString("summary")?.trim().orEmpty().let { if (it.isNotEmpty()) "$label: $it。" else "" }

    private fun finish(ev: JSONObject) {
        result = ev
        state = if (ev.optString("outcome") == "failed") "failed" else "done"
        val now = System.currentTimeMillis()
        val done = _live.value?.let { c ->
            // やり取りは、届いた言葉 (その場の表示) を使う。届いていなければ、結果のやり取りから
            val lines = c.lines.ifEmpty {
                val t = ev.optJSONArray("transcript") ?: JSONArray()
                (0 until t.length()).mapNotNull { i -> t.optJSONArray(i) }
                    .map { r -> CallLine(if (r.optString(0) == "リゲル") "rigel" else "peer", r.optString(1), true, now) }
            }
            c.copy(state = state, endedAt = now, outcome = ev.optString("outcome"), summary = ev.optString("summary").trim(),
                reason = ev.optString("reason"), lines = lines.map { it.copy(final = true) })
        }
        _live.value = done
        cleanUp(ev)
        g.callScreen.hideAfter()
        RabiLog.i("rigelcall", "result ${ev.optString("outcome")} (${ev.optJSONArray("transcript")?.length() ?: 0} lines)")
        // 記録に残す (録音の変換に少しかかるので、報告の後ろで)。かけられなかったものは、やり取りが無ければ残さない
        val pcm = if (recReady) recFile else null
        recFile = null; recReady = false
        if (done != null && !(done.outcome == "failed" && done.lines.isEmpty() && done.answeredAt == 0L)) {
            g.scope.launch(Dispatchers.IO) { runCatching { g.callRecords.save(done, pcm, recRate) }.onFailure { RabiLog.w("rigelcall", "save failed: ${it.javaClass.simpleName}") } }
        } else pcm?.delete()
    }

    /** 新しい代行の通話を始めた (画面の中身を入れ替える) */
    private fun begin(c: CallInfo) {
        dropRecording()
        _live.value = c
    }

    /** ボードの様子が変わった: 相手が出たら、そこから時間を数える */
    private fun onState(st: String) {
        val c = _live.value ?: return
        _live.value = if (st == "talking" && c.answeredAt == 0L) c.copy(state = st, answeredAt = System.currentTimeMillis()) else c.copy(state = st)
    }

    /** やり取りの言葉が届いた (画面に出す) */
    private fun onText(who: String, text: String, final: Boolean) {
        val c = _live.value ?: return
        _live.value = c.withText(who, text, final, System.currentTimeMillis())
    }

    /** 途中の録音を捨てる */
    private fun dropRecording() {
        recOut?.let { runCatching { it.close() } }
        recOut = null
        if (!recReady) { recFile?.delete(); recFile = null }
    }

    private fun send(o: JSONObject) {
        val s = out ?: return
        runCatching { synchronized(s) { s.write((o.toString() + "\n").toByteArray()); s.flush() } }
    }

    /** rigel_call_status で返す中身 */
    fun statusJson(): JSONObject = JSONObject().put("state", state).put("to", to).put("app", app)
        .put("elapsed_sec", if (startedAt > 0) (System.currentTimeMillis() - startedAt) / 1000 else 0)
        .apply {
            result?.let { r ->
                put("outcome", r.optString("outcome"))
                put("summary", r.optString("summary"))
                put("transcript", r.optJSONArray("transcript") ?: JSONArray())
                r.optString("reason").takeIf { it.isNotEmpty() }?.let { put("reason", it) }
            }
        }

    private companion object {
        const val PORT = 8093
        /** ボードがドングルを入れて Pixel とつなぐまで (と、本人の承認のあと発信するまで) 待つ長さ */
        const val READY_TIMEOUT_MS = 60_000L
        /** かかってきた電話にボードが出るまで待つ長さ (つないで「着信中」を見て ATA。鳴っている間に) */
        const val ANSWER_TIMEOUT_MS = 20_000L
        /** 電話の状態を見る間隔と、見続ける長さ */
        const val WATCH_POLL_MS = 400L
        const val WATCH_MS = 7 * 60_000L
        val FG_RE = Regex("""Foreground call state: (-?\d+)""")
    }
}
