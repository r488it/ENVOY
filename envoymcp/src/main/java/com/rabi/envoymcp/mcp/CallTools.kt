package com.rabi.envoymcp.mcp

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.rabi.envoymcp.a11y.A11yService
import com.rabi.envoymcp.call.CallInfo
import com.rabi.envoymcp.call.PhoneContacts
import com.rabi.envoymcp.call.ScamCheck
import com.rabi.envoymcp.call.outcomeLabel
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.parts.ApprovalLevel
import com.rabi.envoymcp.parts.CommitKind
import com.rabi.envoymcp.safety.ApprovalDecision
import com.rabi.envoymcp.safety.ApprovalRequest
import com.rabi.envoymcp.safety.ConsumeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 電話の道具。
 * - 電話をかける (電話のアプリを操作すると手順が多いので、直接かける)。連絡先から番号を探し → 承認 (設定しだいで
 *   指紋など・承認画面・承認なし) → TelecomManager で発信。番号は ChatGPT に返さない (選んだ人の名前と、種類・下 4 桁だけ)
 * - リゲルが代わりに電話して用件を伝える (電話・LINE)、その様子と結果、途中でやめる
 * - かかってきた電話に、リゲルが代わりに出るかを受ける
 * - リゲルの電話の記録 (要点と録音) を、アプリの共有に添えて開く (送信は本人の承認のあと tap で)
 */
internal class CallTools(st: ToolState, private val apps: AppTools) : ToolGroup(st) {
    /** 電話の相手 (名前・番号の種類・番号)。名前が無いのは、連絡先にない番号 */
    private class PhoneTarget(val name: String, val kind: String, val number: String) {
        /** 本人確認や返事に出す相手 (番号は下 4 桁だけ) */
        val who get() = name.ifEmpty { "連絡先にない番号 (下 4 桁 ${PhoneContacts.tail(number)})" }
        val shown get() = if (name.isNotEmpty()) "$name ($kind・下 4 桁 ${PhoneContacts.tail(number)})" else "番号 下 4 桁 ${PhoneContacts.tail(number)}"
    }

    /** contact (連絡先の名前) か number から、電話の相手を決める。決められなければ、そのまま返す失敗 */
    private suspend fun resolvePhone(a: JSONObject): Pair<PhoneTarget?, ToolResult?> {
        val ctx = g.context
        val q = a.optString("contact").trim()
        val given = a.optString("number").trim().ifEmpty { q.takeIf { PhoneContacts.isNumber(it) }.orEmpty() }
        val t = if (given.isNotEmpty()) {
            if (!PhoneContacts.isNumber(given)) return null to failed("番号の形ではありません (数字・+・-・括弧だけにしてください)。")
            PhoneTarget("", "", given)
        } else {
            if (q.isEmpty()) return null to failed("相手の名前 (contact) か番号 (number) を入れてください。")
            if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                return null to failed("連絡先を読む許可がありません。利用者に、スマホの ENVOY MCP の設定の「電話」で許可してもらってください。番号が分かれば number でかけられます。")
            val people = withContext(Dispatchers.IO) { PhoneContacts.find(PhoneContacts.load(ctx), q) }
            when {
                people.isEmpty() -> return null to failed("連絡先に「$q」が見つかりません。利用者に、連絡先での名前を聞いて頼み直してください。")
                people.size > 1 -> return null to failed("「$q」に当たる人が ${people.size} 人います (${people.take(6).joinToString("・") { it.name }})。利用者にどなたか聞いて、名前を詳しくして頼み直してください。",
                    JSONObject().put("candidates", JSONArray(people.take(6).map { it.name })))
            }
            val p = people.single()
            val type = a.optString("phone_type").ifBlank { null }
            val e = PhoneContacts.pick(p, type) ?: return null to failed("${p.name} さんには、その種類の番号がありません (${p.numbers.joinToString("・") { it.type }})。")
            PhoneTarget(p.name, e.type, e.number)
        }
        if (PhoneContacts.isEmergency(ctx, t.number)) return null to failed("緊急の番号には、ENVOY MCP からはかけません。利用者に、電話のアプリから自分でかけてもらってください。")
        return t to null
    }

    /** 電話をかけられる状態か (許可・今の通話)。かけられなければ失敗 */
    private fun phoneBlocker(): ToolResult? {
        val ctx = g.context
        if (ctx.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return failed("電話をかける許可がありません。利用者に、スマホの ENVOY MCP の設定の「電話」で許可してもらってください。")
        // 通話中・呼び出し中はかけない (同じ電話を二度かけない。通話の画面が前にあると本人確認も取り消される)
        val mode = ctx.getSystemService(android.media.AudioManager::class.java).mode
        if (mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_RINGTONE)
            return failed("今、電話で話しているか、呼び出しの最中です。もう一度かけるなら、利用者に今の電話を切ってもらってから頼み直してください。")
        return null
    }

    /** 発信する (かけられなければ、その理由) */
    private suspend fun placeCall(number: String): Throwable? = withContext(Dispatchers.Main) {
        runCatching {
            g.context.getSystemService(android.telecom.TelecomManager::class.java).placeCall(Uri.fromParts("tel", PhoneContacts.digits(number), null), android.os.Bundle())
        }.recoverCatching {
            g.context.startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", PhoneContacts.digits(number), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.exceptionOrNull()
    }

    suspend fun callPhone(a: JSONObject): ToolResult {
        phoneBlocker()?.let { return it }
        val (t, err) = resolvePhone(a)
        if (t == null) return err!!
        // 本人確認の画面には番号を出さない。相手の名前と番号の種類だけ。連絡先にない番号は下 4 桁だけ。
        // 番号は承認と照らし合わせる内側の事実 (screenFacts) にだけ入れる (承認した番号と違う番号にはかけられない)
        val fields = linkedMapOf("相手" to t.who)
        if (t.kind.isNotEmpty()) fields["種類"] = t.kind
        val req = ApprovalRequest(kind = CommitKind.CALL, level = ApprovalLevel.BIOMETRIC, summary = "${t.who} に電話をかけます",
            fields = fields, targetPkg = null, screenFacts = mapOf("number" to PhoneContacts.digits(t.number)))
        when (val d = g.approvals.ask(req, g.approvalManager)) {
            is ApprovalDecision.Denied -> return failed("利用者が承認しなかったので、電話はかけていません (${d.reason})。")
            is ApprovalDecision.Approved -> if (g.approvalManager.consume(d.token, req.fingerprint) != ConsumeResult.OK)
                return failed("承認の期限が切れたので、電話はかけていません。頼み直してください。")
        }
        placeCall(t.number)?.let {
            RabiLog.w("mcp", "call failed: ${it.javaClass.simpleName}")
            return failed("電話をかけられませんでした (${it.javaClass.simpleName})。")
        }
        return ok("${t.shown} に電話をかけました。", JSONObject().put("called", true))
    }

    /**
     * リゲルが代わりに電話して用件を伝える。本人の承認 (相手・用件) のあと、ボードの電話の代役に頼み、
     * ボードがヘッドセットとしてつながったら発信する。すぐ戻り、結果は rigel_call_status で返す
     */
    suspend fun rigelCall(a: JSONObject): ToolResult {
        val app = a.optString("app").ifBlank { "phone" }
        val message = a.optString("message").trim()
        if (message.isEmpty()) return failed("伝える用件 (message) を入れてください。")
        val caller = a.optString("caller").trim().ifEmpty { g.settings.rigelCallerName }
        if (g.rigelCaller.busy) return failed("リゲルはほかの電話をしています。rigel_call_status で様子を確かめてください。")
        if (app == "line") return rigelLineCall(a, message, caller)
        phoneBlocker()?.let { return it }
        val (t, err) = resolvePhone(a)
        if (t == null) return err!!
        // 本人確認: 相手 (番号は出さない)・用件・名乗る名前。番号と用件は承認と照らし合わせる内側の事実にする
        val fields = linkedMapOf("相手" to t.who)
        if (t.kind.isNotEmpty()) fields["種類"] = t.kind
        fields["用件"] = message
        fields["名乗り"] = "AI アシスタントのリゲル (${caller.ifEmpty { "あなた" }} の代わり)"
        val req = ApprovalRequest(kind = CommitKind.CALL, level = ApprovalLevel.BIOMETRIC, summary = "リゲルが ${t.who} に電話して、用件を伝えます",
            fields = fields, targetPkg = null, screenFacts = mapOf("number" to PhoneContacts.digits(t.number), "message" to message))
        when (val d = g.approvals.ask(req, g.approvalManager)) {
            is ApprovalDecision.Denied -> return failed("利用者が承認しなかったので、電話はかけていません (${d.reason})。")
            is ApprovalDecision.Approved -> if (g.approvalManager.consume(d.token, req.fingerprint) != ConsumeResult.OK)
                return failed("承認の期限が切れたので、電話はかけていません。頼み直してください。")
        }
        val name = t.name.ifEmpty { "お相手" }
        g.rigelCaller.start("phone", name, message, caller, place = {
            placeCall(t.number)?.let { "電話をかけられませんでした (${it.javaClass.simpleName})" }
        }, watch = { send -> g.rigelCaller.watchPhone(send) })?.let { return failed("リゲルの電話を始められませんでした: $it") }
        return ok("リゲルが ${t.shown} に電話をかけ始めました。相手が出たら、リゲル (AI) が名乗って用件を伝え、返事を聞いて切ります。" +
            CALL_QUIET + "1〜3 分ほどしたら rigel_call_status で結果を確かめ、利用者に伝えてください。", JSONObject().put("calling", true))
    }

    /** リゲルが LINE の音声通話で代わりに話す: LINE の友だち (表示名) を本人に承認してもらい、LINE を操作してかける */
    private suspend fun rigelLineCall(a: JSONObject, message: String, caller: String): ToolResult {
        val friend = a.optString("contact").trim()
        if (friend.isEmpty()) return failed("LINE の友だちの名前 (contact。LINE の表示名) を入れてください。")
        if (A11yService.instance == null) return failed("ユーザー補助がオフなので、LINE を操作できません。利用者に、ENVOY MCP のユーザー補助をオンにしてもらってください。")
        if (g.context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            return failed("スマホのロックが掛かっているので、LINE を操作できません。利用者にロックを解除してもらってから頼み直してください。")
        val mode = g.context.getSystemService(android.media.AudioManager::class.java).mode
        if (mode != android.media.AudioManager.MODE_NORMAL)
            return failed("今、通話か音声の会話をしています (ChatGPT の音声モードを含む)。終えてから頼み直してください (LINE の通話とマイクを取り合うため)。")
        val fields = linkedMapOf("相手" to "LINE の友だち: $friend", "用件" to message, "名乗り" to "AI アシスタントのリゲル (${caller.ifEmpty { "あなた" }} の代わり)")
        val req = ApprovalRequest(kind = CommitKind.CALL, level = ApprovalLevel.BIOMETRIC, summary = "リゲルが LINE で $friend に電話して、用件を伝えます",
            fields = fields, targetPkg = "jp.naver.line.android", screenFacts = mapOf("line_friend" to friend, "message" to message))
        when (val d = g.approvals.ask(req, g.approvalManager)) {
            is ApprovalDecision.Denied -> return failed("利用者が承認しなかったので、電話はかけていません (${d.reason})。")
            is ApprovalDecision.Approved -> if (g.approvalManager.consume(d.token, req.fingerprint) != ConsumeResult.OK)
                return failed("承認の期限が切れたので、電話はかけていません。頼み直してください。")
        }
        g.rigelCaller.start("line", friend, message, caller,
            place = { g.lineCaller.call(friend) },
            end = { g.lineCaller.end() },
            watch = { send -> g.lineCaller.watch(send) })?.let { return failed("リゲルの LINE 通話を始められませんでした: $it") }
        return ok("リゲルが LINE で $friend さんに電話をかけ始めました。相手が出たら、音をリゲルに回し、リゲル (AI) が名乗って用件を伝え、返事を聞いて切ります。" +
            CALL_QUIET + "1〜3 分ほどしたら rigel_call_status で結果を確かめ、利用者に伝えてください。", JSONObject().put("calling", true))
    }

    fun rigelCallStatus(): ToolResult {
        val status = g.rigelCaller.statusJson()
        g.rigelCaller.resultDelivered()
        return ok("リゲルの電話の様子です。", status)
    }

    fun rigelCallHangup(): ToolResult =
        if (!g.rigelCaller.busy) ok("リゲルは電話をしていません。", g.rigelCaller.statusJson())
        else { g.rigelCaller.hangup(); ok("リゲルの電話を切るよう頼みました。", JSONObject().put("hangup", true)) }

    /** かかってきた電話に、リゲルが代わりに出るか (rigel)、本人が出るか (self) */
    fun rigelIncoming(a: JSONObject): ToolResult {
        val rigel = a.optString("choice") == "rigel"
        val msg = g.incomingCall.choose(rigel)
        return if (!g.incomingCall.asking && msg.startsWith("今は")) failed(msg)
        else ok(msg + if (rigel) "今は返事をせず黙っていてください (この音声の会話はすぐ終わります。電話のあとに開き直して、結果を伝えてもらいます)。" else "",
            JSONObject().put("choice", if (rigel) "rigel" else "self"))
    }

    /**
     * リゲルが代わりにかけた・出た電話の記録を、アプリの共有に添えて開く (家族に送るなど)。要点の文と、録音 (あれば) を添える。送信はしない。
     * 録音は FileProvider で、開くアプリにだけ読む許可を付けて渡す。アプリが音声のファイルを受け付けなければ文だけ。
     * ファイルを添えると文を入れないアプリ (LINE など) があるので、文 (改行なし) も結果に入れて、あとで入力欄に入れられるようにする
     */
    suspend fun shareCallRecord(a: JSONObject): ToolResult {
        val (pkg, why) = resolveApp(a.optString("app"))
        if (pkg == null) return failed(why)
        val want = a.optString("call").trim().ifEmpty { "last" }
        if (want == "last" && g.rigelCaller.busy) return failed("リゲルは今、電話をしています。電話が終わってから頼み直してください。")
        val list = withContext(Dispatchers.IO) { recordsAfterSave(want == "last") }
            ?: return failed("さっきの電話の記録が、まだ保存されていません (つながらなかった電話は残しません)。少し待ってから頼み直してください。")
        if (list.isEmpty()) return failed("リゲルが代わりにかけた・出た電話の記録がありません。")
        val c = (if (want == "last") list.first() else list.firstOrNull { it.id == want })
            ?: return failed("その記録 ($want) は見つかりません。最近の記録 (recent) から id を選んでください。", JSONObject().put("recent", JSONArray(list.take(RECENT_MAX).map {
                JSONObject().put("id", it.id).put("when", WHEN_FMT.format(Date(it.startedAt))).put("name", it.name).put("dir", it.dir).put("outcome", it.outcomeLabel())
            })))
        val text = recordText(c, a.optString("text").trim())
        val label = g.appLabel(pkg) ?: pkg
        val target = a.optString("target").ifBlank { "message" }
        val about = "電話の記録 (${WHEN_FMT.format(Date(c.startedAt))}・${c.name}) を共有するため、"
        val extra = JSONObject().put("call_id", c.id).put("text_to_type", text.replace("\n", " ／ "))
        val file = withContext(Dispatchers.IO) { g.callRecords.audioFile(c) }
        if (file != null) {
            val mime = if (file.name.endsWith(".wav")) "audio/wav" else "audio/mp4"
            val uri = runCatching { FileProvider.getUriForFile(g.context, "${g.context.packageName}$PROVIDER", file) }
                .onFailure { RabiLog.w("mcp", "call record uri failed: ${it.javaClass.simpleName}") }.getOrNull()
            if (uri != null && apps.accepts(pkg, mime))
                return apps.share(uri, mime, pkg, text, target, about, what = "要点の文と録音", note = TYPE_LATER,
                    extra = extra.put("recording", true))
        }
        val note = if (file == null) "この電話には録音がありません (文だけです)。" else "$label は録音 (音声のファイル) の共有を受け付けないので、文だけにしました。"
        return apps.share(null, "text/plain", pkg, text, target, about, what = "要点の文", note = note, extra = extra.put("recording", false))
    }

    /**
     * 記録の一覧 (新しい順)。waitLast なら、終わったばかりの電話の記録を保存し終えるまで少し待つ (録音の変換に少しかかる)。
     * 待っても保存されなければ null (古い記録を「いちばん新しい」と取り違えないように)
     */
    private suspend fun recordsAfterSave(waitLast: Boolean): List<CallInfo>? {
        val just = g.rigelCaller.live.value?.takeIf { waitLast && it.ended && System.currentTimeMillis() - it.endedAt < JUST_ENDED_MS }?.id
        val until = System.currentTimeMillis() + SAVE_WAIT_MS
        while (true) {
            val list = g.callRecords.list()
            if (just == null || list.any { it.id == just }) return list
            if (System.currentTimeMillis() > until) return null
            delay(300)
        }
    }

    /** 共有する文: 何の電話か・相手 (記録の名前。番号の全体は入れない)・日時・結果・伝えたこと・要点・詐欺の疑い (あれば)・足す言葉 */
    private fun recordText(c: CallInfo, extra: String): String = buildList {
        add(if (c.dir == "in") "リゲル (AI アシスタント) が代わりに出た電話の記録です。" else "リゲル (AI アシスタント) が代わりにかけた電話の記録です。")
        add("相手: ${c.name}" + if (c.app == "line") " (LINE)" else "")
        add("日時: ${WHEN_FMT.format(Date(c.startedAt))}")
        add("結果: ${c.outcomeLabel()}")
        if (c.dir != "in" && c.message.isNotBlank()) add("伝えたこと: ${c.message}")
        if (c.summary.isNotBlank()) add((if (c.dir == "in") "用件: " else "相手の返事: ") + c.summary)
        ScamCheck.label(c.risk, c.riskReasons).takeIf { it.isNotEmpty() }?.let { add(it) }
        if (extra.isNotEmpty()) add(extra)
    }.joinToString("\n")

    companion object {
        /**
         * リゲルの電話の間、ChatGPT に黙っていてもらう (ChatGPT の返事は、つながっている「リゲル」に流れて通話の音に入り、
         * リゲル (Gemini) がそれに答えて、相手に向けない言葉を話してしまう。利用者にも聞こえない)
         */
        private const val CALL_QUIET = "今は返事をせず、電話が終わるまで黙って待ってください (話すと、その声はリゲルの通話の線に入り、" +
            "相手との会話のじゃまになります。利用者にも聞こえません)。"
        /** 録音を添えたときに足す言葉 (ファイルを添えると文を入れないアプリがある) */
        private const val TYPE_LATER = "LINE などは、録音を添えると文を入れないことがあります。送ったあと、トークに文が届いていなければ、" +
            "text_to_type (改行なし) を入力欄に type_text で入れて、送信を tap してください (承認が要ります)。"
        /** FileProvider の名前 (パッケージ名の後ろ。AndroidManifest と同じ) */
        private const val PROVIDER = ".files"
        /** 終わってからこの間の電話は、保存し終えるのを待つ (最長 SAVE_WAIT_MS) */
        private const val JUST_ENDED_MS = 60_000L
        private const val SAVE_WAIT_MS = 10_000L
        /** 記録が見つからないときに返す、最近の記録の数 */
        private const val RECENT_MAX = 5
        private val WHEN_FMT get() = SimpleDateFormat("M月d日 H:mm", Locale.JAPAN)
    }
}
