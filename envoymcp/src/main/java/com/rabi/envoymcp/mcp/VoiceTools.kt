package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.core.Expression
import org.json.JSONObject

/** リゲルと音声の会話の道具: 表情を一度だけ見せる・音声の会話を終える */
internal class VoiceTools(st: ToolState) : ToolGroup(st) {
    /** 表情を一度だけ見せる (スマホも腕も動かさない) */
    fun express(a: JSONObject): ToolResult {
        val e = Expression.byId(a.optString("expression"))
            ?: return failed("expression は ${Expression.entries.joinToString("・") { it.id }} のどれかです。")
        g.express(e)
        return ok("リゲルが「${e.label}」の表情をしました (このことは利用者に言わなくて構いません)。", JSONObject().put("expression", e.id))
    }

    /** 「ばいばい」: 挨拶を言い終えたら、音声モードを終えて閉じる */
    fun endVoiceChat(): ToolResult {
        g.endVoiceAfterGoodbye()
        return ok("わかりました。ひとこと短く別れの挨拶をしてください。言い終えたら、スマホが音声モードを終えて閉じます。")
    }
}
