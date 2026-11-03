package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.arm.ArmController
import com.rabi.envoymcp.arm.ArmResult
import org.json.JSONObject

/**
 * AgentARM (スマホを載せる腕) の道具。画面は操作しないので、端末の操作の接続も、止められるリゲルも要らない。
 * 腕が動き終わるまで待って結果を返す
 */
internal class ArmTools(st: ToolState) : ToolGroup(st) {
    suspend fun orient(to: String): ToolResult = arm(g.arm.orient(to))

    suspend fun lookAtMe(a: JSONObject): ToolResult = arm(g.arm.lookAtMe(orientation(a)))

    suspend fun follow(a: JSONObject): ToolResult = arm(g.arm.follow(orientation(a)))

    suspend fun stopFollowing(): ToolResult = arm(g.arm.stopFollowingByTool())

    suspend fun recordStart(a: JSONObject): ToolResult =
        arm(g.arm.recordStart(a.optBoolean("follow", true), a.optNum("max_minutes")?.toInt() ?: ArmController.RECORD_DEFAULT_MIN, a.optString("camera") == "back"))

    suspend fun recordSwitch(a: JSONObject): ToolResult =
        arm(g.arm.recordSwitch(when (a.optString("camera")) { "back" -> true; "front" -> false; else -> null }))

    suspend fun recordStop(): ToolResult = arm(g.arm.recordStop())

    suspend fun move(a: JSONObject): ToolResult =
        arm(g.arm.move(a.optNum("turn"), a.optNum("tilt"), a.optNum("roll"), a.optBoolean("relative", true), a.optString("speed") == "slow"))

    suspend fun motion(a: JSONObject): ToolResult {
        val arr = a.optJSONArray("keyframes") ?: return failed("keyframes がありません。")
        val keys = (0 until arr.length()).map { i ->
            val k = arr.optJSONObject(i) ?: return failed("keyframes の ${i + 1} 番目が形になっていません。")
            ArmController.Key(k.optNum("turn") ?: 0.0, k.optNum("tilt") ?: 0.0, k.optNum("roll") ?: 0.0, k.optInt("ms", 0))
        }
        return arm(g.arm.motion(keys, a.optInt("repeat", 1)))
    }

    suspend fun dance(a: JSONObject): ToolResult {
        val style = a.optString("style").takeIf { it in listOf(ArmController.STYLE_CHILL, ArmController.STYLE_GROOVE, ArmController.STYLE_PARTY) }
        val size = when (a.optString("size")) { "small" -> 0.35; "medium" -> 0.65; "large" -> 1.0; else -> null }
        return arm(g.arm.dance(style, size))
    }

    suspend fun stop(): ToolResult = arm(g.arm.stopByTool())

    /** 数 (無い・数でなければ null) */
    private fun JSONObject.optNum(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    private fun orientation(a: JSONObject): String? = a.optString("orientation").takeIf { it == ArmController.PORTRAIT || it == ArmController.LANDSCAPE }

    private fun arm(r: ArmResult): ToolResult {
        val s = JSONObject().put("arm", g.arm.statusJson())
        return when (r.result) {
            "ok" -> ok(r.message, s)
            "unknown" -> unknown("${r.message}。腕がどこまで動いたかは確かめていません。", s)
            else -> failed(r.message, s.put("why", r.code))
        }
    }
}
