package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.Notices
import com.rabi.envoymcp.core.Expression
import org.json.JSONObject

/** リゲルと音声の会話の道具: 表情を一度だけ見せる・音声の会話を終える・お知らせを預ける */
internal class VoiceTools(st: ToolState) : ToolGroup(st) {
    /** 表情を一度だけ見せる (スマホも腕も動かさない) */
    fun express(a: JSONObject): ToolResult {
        val e = Expression.byId(a.optString("expression"))
            ?: return failed("expression は ${Expression.entries.joinToString("・") { it.id }} のどれかです。")
        g.express(e)
        return ok("リゲルが「${e.label}」の表情をしました (このことは利用者に言わなくて構いません)。", JSONObject().put("expression", e.id))
    }

    /** 定期の依頼の結果などを預ける (リゲルにベルを付け、利用者がリゲルを押したら音声モードで伝える) */
    fun notify(a: JSONObject): ToolResult {
        val message = a.optString("message").trim()
        if (message.isEmpty()) return failed("message に伝える中身を入れてください。")
        val title = a.optString("title").trim()
        val text = (if (title.isEmpty()) message else "$title: $message").replace('\n', ' ')
        val now = System.currentTimeMillis()
        g.notices.add("task-$now", now, if (text.length > Notices.TASK_MAX_CHARS) text.take(Notices.TASK_MAX_CHARS) + "…" else text, Notices.TASK)
        return ok("お知らせを預けました。スマホのリゲルにベルが付き、利用者がリゲルを押すと音声モードで伝えます。", JSONObject().put("queued", true))
    }

    /** 「ばいばい」: 挨拶を言い終えたら、音声モードを終えて閉じる */
    fun endVoiceChat(): ToolResult {
        g.endVoiceAfterGoodbye()
        return ok("わかりました。ひとこと短く別れの挨拶をしてください。言い終えたら、スマホが音声モードを終えて閉じます。")
    }
}
