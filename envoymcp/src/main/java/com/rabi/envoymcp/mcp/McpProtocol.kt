package com.rabi.envoymcp.mcp

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 道具の出し手 (本物は McpTools。試験では偽物) */
interface ToolHost {
    /** tools/list に出す道具の説明 (すべて。エージェントごとの絞り込みは McpProtocol がする) */
    fun list(): JSONArray
    /** 道具を動かす。知らない名前なら null。agent は呼んだエージェント (したことの記録に名前を残す。null は本体の ChatGPT) */
    suspend fun call(name: String, args: JSONObject, agent: Agent? = null): ToolResult?
    /** このエージェントに許されていない道具を頼まれた: 動かさずに断る (本物は記録にも残す) */
    fun refuse(name: String, args: JSONObject, agent: Agent): ToolResult = AgentPolicy.refusal(agent)
}

/**
 * 道具の結果。isError は「道具は動いたが、うまくいかなかった」(ChatGPT が読んで言い直せる)。
 * 操作を送っただけでアプリ側の処理が終わったとはみなさない (どこまで確かめたかは text に書く)
 */
data class ToolResult(
    val text: String,
    val structured: JSONObject? = null,
    val isError: Boolean = false,
    /** 画像 (画面の画像など)。base64 と形式 */
    val images: List<Image> = emptyList(),
) {
    data class Image(val base64: String, val mime: String)

    fun toJson(): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)).apply {
            images.forEach { put(JSONObject().put("type", "image").put("data", it.base64).put("mimeType", it.mime)) }
        })
        .apply { if (structured != null) put("structuredContent", structured) }
        .put("isError", isError)
}

/**
 * MCP (JSON-RPC 2.0) の受け答え。HTTP とは切り離してある (HTTP は McpServer が受け持つ)。
 * 対応するのは initialize / ping / tools/list / tools/call と通知 (返事なし)。それ以外は「対応していない」と返す。
 * エージェントの権限 (AgentPolicy): tools/list はそのエージェントが使える道具だけを出し、許されていない道具の tools/call は動かさずに断る。
 * unlimited (開発モード) のときは、まとまり・読むだけ・期限の制限をかけない (確定の前の承認は道具の中なので、そのまま)
 */
class McpProtocol(
    private val tools: ToolHost,
    private val version: String,
    private val instructions: String,
    private val unlimited: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    /** instructions に足す説明 (設定で変わるもの。空なら足さない) */
    private val extraInstructions: () -> String = { "" },
) {

    /** 届いた本文 (JSON) を処理する。返事の要らないもの (通知・ChatGPT 側からの返事) は null。agent は呼んだエージェント (null なら絞らない) */
    suspend fun handle(body: String, agent: Agent? = null): String? = when (val msg = runCatching { JSONTokener(body).nextValue() }.getOrNull()) {
        is JSONObject -> handleOne(msg, agent)?.toString()
        // JSON-RPC の一括 (配列。MCP の古い版にだけある)。来たら 1 件ずつ
        is JSONArray -> if (msg.length() == 0) error(null, INVALID_REQUEST, "空の一括です").toString() else {
            val out = JSONArray()
            for (i in 0 until msg.length()) {
                val o = msg.optJSONObject(i)
                val r = if (o == null) error(null, INVALID_REQUEST, "形が違います") else handleOne(o, agent)
                if (r != null) out.put(r)
            }
            if (out.length() == 0) null else out.toString()
        }
        else -> error(null, PARSE_ERROR, "JSON を読めません").toString()
    }

    private suspend fun handleOne(m: JSONObject, agent: Agent?): JSONObject? {
        val method = m.optString("method", "")
        val hasId = m.has("id")
        // 通知 (id なし) と、こちらへの返事 (method なし) には答えない
        if (method.isEmpty()) return if (hasId && !m.has("result") && !m.has("error")) error(m.opt("id"), INVALID_REQUEST, "method がありません") else null
        if (!hasId) return null
        val id = m.get("id")
        val params = m.optJSONObject("params") ?: JSONObject()
        return try {
            when (method) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JSONObject())
                "tools/list" -> result(id, JSONObject().put("tools", listFor(agent)))
                "tools/call" -> {
                    val name = params.optString("name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    val r = if (agent != null && permitted(agent, name) == false) tools.refuse(name, args, agent) else tools.call(name, args, agent)
                    if (r == null) error(id, INVALID_PARAMS, "道具がありません: $name") else result(id, r.toJson())
                }
                else -> error(id, METHOD_NOT_FOUND, "対応していません: $method")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error(id, INTERNAL_ERROR, "うまくいきませんでした (${e.javaClass.simpleName})")
        }
    }

    /** このエージェントに出す道具 (並びはそのまま。agent が無ければすべて) */
    private fun listFor(agent: Agent?): JSONArray {
        val all = tools.list()
        if (agent == null || unrestricted(agent)) return all
        val t = now(); val u = unlimited()
        return JSONArray().apply {
            for (i in 0 until all.length()) {
                val o = all.optJSONObject(i) ?: continue
                if (AgentPolicy.allows(agent, o.optString("name"), readOnly(o), t, u)) put(o)
            }
        }
    }

    /** この道具を使ってよいか (一覧に無い道具は null。道具の出し手が「ありません」と返す) */
    private fun permitted(agent: Agent, name: String): Boolean? {
        if (unrestricted(agent)) return true
        val all = tools.list()
        for (i in 0 until all.length()) {
            val o = all.optJSONObject(i) ?: continue
            if (o.optString("name") == name) return AgentPolicy.allows(agent, name, readOnly(o), now(), unlimited())
        }
        return null
    }

    /** すべての道具を使える (本体の ChatGPT のふだんの形・開発モード)。止めている・期限切れは McpServer が先に断っている */
    private fun unrestricted(agent: Agent) = AgentPolicy.status(agent, now(), unlimited()) == AgentPolicy.Status.OK &&
        (unlimited() || (!agent.readOnly && AgentPolicy.allGroups(agent)))

    /** 道具の説明の annotations の readOnlyHint (読むだけの道具か) */
    private fun readOnly(tool: JSONObject) = tool.optJSONObject("annotations")?.optBoolean("readOnlyHint", false) == true

    private fun initialize(p: JSONObject): JSONObject {
        val asked = p.optString("protocolVersion")
        return JSONObject()
            .put("protocolVersion", if (asked in SUPPORTED) asked else SUPPORTED.first())
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", "envoy-mcp").put("title", "ENVOY MCP").put("version", version))
            .put("instructions", extraInstructions().let { if (it.isBlank()) instructions else "$instructions $it" })
    }

    private fun result(id: Any?, r: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", r)
    private fun error(id: Any?, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    companion object {
        /** 答えられる MCP の版 (新しい順)。知らない版を頼まれたら、いちばん新しいものを返す (続けるかは相手が決める) */
        val SUPPORTED = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}
