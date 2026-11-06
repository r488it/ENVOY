package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.Notices
import com.rabi.envoymcp.core.Expression
import org.json.JSONObject

/** リゲルと音声の会話の道具: 表情を一度だけ見せる・音声の会話を終える・お知らせを預ける・お知らせを読む */
internal class VoiceTools(st: ToolState) : ToolGroup(st) {
    /** 表情を一度だけ見せる (スマホも腕も動かさない) */
    fun express(a: JSONObject): ToolResult {
        val e = Expression.byId(a.optString("expression"))
            ?: return failed("expression は ${Expression.entries.joinToString("・") { it.id }} のどれかです。")
        g.express(e)
        return ok("リゲルが「${e.label}」の表情をしました (このことは利用者に言わなくて構いません)。", JSONObject().put("expression", e.id))
    }

    /**
     * 定期の依頼の結果などを預ける (リゲルにベルを付け、利用者がリゲルを押したら音声モードで伝える)。
     * 中身はウェブなどから集めた文を含むので、改行・区切りの記号を除いて短くし、get_notices では外から届いた文として渡す
     */
    fun notify(a: JSONObject): ToolResult {
        val message = a.optString("message").trim()
        if (message.isEmpty()) return failed("message に伝える中身を入れてください。")
        val title = a.optString("title").trim()
        val text = Notices.clean(if (title.isEmpty()) message else "$title: $message", Notices.TASK_MAX_CHARS)
        if (text.isEmpty()) return failed("message に伝える中身を入れてください。")
        val now = System.currentTimeMillis()
        g.notices.add(Notices.Note("task-$now", now, text, Notices.TASK))
        return ok("お知らせを預けました。スマホのリゲルにベルが付き、利用者がリゲルを押すと、音声モードで get_notices から読んで伝えます。", JSONObject().put("queued", true))
    }

    /**
     * まだ伝えていないお知らせと、今かかってきている電話 (尋ねている間だけ) を返す。中の文は外から届いた文の印を付けて渡す。
     * 読んでも消さない (消すのは、リゲルのタップの最初の一言で送れたとき)
     */
    fun getNotices(): ToolResult {
        val list = g.notices.notes.value
        val ringing = g.incomingCall.ringingJson()
        val head = (if (ringing != null) "今、電話がかかってきています (ringing)。" else "") +
            if (list.isEmpty()) "まだ伝えていないお知らせはありません。" else "まだ伝えていないお知らせが ${list.size} 件あります (新しい順)。"
        return ok(head + Notices.UNTRUSTED_NOTE + "中身を報告として、利用者に分かりやすく短く伝えてください。", Notices.toJson(list, ringing))
    }

    /** 「ばいばい」: 挨拶を言い終えたら、音声モードを終えて閉じる */
    fun endVoiceChat(): ToolResult {
        g.endVoiceAfterGoodbye()
        return ok("わかりました。ひとこと短く別れの挨拶をしてください。言い終えたら、スマホが音声モードを終えて閉じます。")
    }
}
