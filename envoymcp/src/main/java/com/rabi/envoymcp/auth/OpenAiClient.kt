package com.rabi.envoymcp.auth

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** ChatGPT プラン利用での失敗の種類。復旧方法が異なるので区別する。 */
enum class AiErrorKind {
    /** 通信できない（機内モード・タイムアウト・ストリームの途切れ）。認証情報は保持する。 */
    NETWORK,
    /** 認証が通らない・失効した。再ログインが必要。 */
    AUTH,
    /** このユーザー・ワークスペースではプラン利用ができない。繰り返さない。 */
    NOT_ELIGIBLE,
    /** 利用枠の上限。新しい要求を止める。 */
    USAGE_LIMIT,
    /** 一時的に利用できない。時間をおいて再試行。 */
    UNAVAILABLE,
    /** 送ったパラメーターやモデルが未対応。同じ内容で再試行しない。 */
    UNSUPPORTED,
    /** 応答が使えない（途中で終わったなど）。 */
    BAD_OUTPUT,
    OTHER,
}

class AiException(
    val kind: AiErrorKind,
    val httpStatus: Int = 0,
    val code: String? = null,
    val requestId: String? = null,
    message: String,
    val param: String? = null,
) : Exception(message) {
    /** 画面に出す短い日本語。 */
    val userMessage: String
        get() = when (kind) {
            AiErrorKind.NETWORK -> "通信できませんでした"
            AiErrorKind.AUTH -> "ChatGPT の認証が切れました。設定から再ログインしてください"
            AiErrorKind.NOT_ELIGIBLE -> "このアカウントでは ChatGPT プランを利用できません"
            AiErrorKind.USAGE_LIMIT -> "ChatGPT の利用枠の上限に達しました（ChatGPT の設定 › 使用状況）"
            AiErrorKind.UNAVAILABLE -> "ChatGPT が一時的に利用できません。少し待ってから試してください"
            AiErrorKind.UNSUPPORTED -> "選択中のモデルまたは設定が未対応です（${code ?: httpStatus}）"
            AiErrorKind.BAD_OUTPUT -> "応答が途中で終わりました"
            AiErrorKind.OTHER -> "エラーが発生しました（${code ?: httpStatus}）"
        }
}

data class ModelInfo(val slug: String, val displayName: String)

/**
 * ChatGPT プラン利用での OpenAI API 呼び出し。
 * - モデル一覧: GET /v1/models（visibility == "list" のものだけ表示、サーバーの順序のまま）
 * - 推論: POST /v1/responses（store=false, stream=true 必須。temperature などの未対応項目は送らない）
 */
class OpenAiClient(private val base: String = "https://api.openai.com/v1") {

    fun listModels(accessToken: String): List<ModelInfo> {
        val c = open("$base/models", accessToken, "GET")
        val body = readOrThrow(c)
        val json = JSONObject(body)
        val out = ArrayList<ModelInfo>()
        val models = json.optJSONArray("models")
        if (models != null) {
            for (i in 0 until models.length()) {
                val m = models.getJSONObject(i)
                android.util.Log.i("OpenAiClient", "model slug=${m.optString("slug")} visibility=${m.optString("visibility")}")
                if (m.optString("visibility", "list") != "list") continue
                val slug = m.optString("slug").ifEmpty { m.optString("id") }
                if (slug.isNotEmpty()) out += ModelInfo(slug, m.optString("display_name").ifEmpty { slug })
            }
        } else {
            val data = json.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) data.getJSONObject(i).optString("id").takeIf { it.isNotEmpty() }?.let { out += ModelInfo(it, it) }
        }
        return out
    }

    /** 短い文章だけの推論（モデルの確認用）。 */
    fun complete(accessToken: String, model: String, instructions: String, userText: String): String {
        val body = JSONObject().put("model", model).put("instructions", instructions)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", userText)))
            .put("store", false).put("stream", true)
        return respond(accessToken, body, null).second
    }

    /**
     * Responses API をストリーミングで呼ぶ。response.completed を受け取って初めて成功。
     * @return (出力項目, 出力テキスト)
     */
    fun respond(accessToken: String, body: JSONObject, onDelta: ((String) -> Unit)?): Pair<List<JSONObject>, String> {
        val c = open("$base/responses", accessToken, "POST")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "text/event-stream")
        c.doOutput = true
        c.readTimeout = 150_000
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        } catch (e: IOException) {
            throw AiException(AiErrorKind.NETWORK, message = e.message ?: "network")
        }
        val status = try { c.responseCode } catch (e: IOException) { throw AiException(AiErrorKind.NETWORK, message = e.message ?: "network") }
        val rid = c.getHeaderField("x-request-id")
        if (status !in 200..299) throw errorFrom(status, readError(c), rid)

        val text = StringBuilder()
        val items = sortedMapOf<Int, JSONObject>()
        var finalOutput: JSONArray? = null
        var completed = false
        try {
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val ev = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    when (ev.optString("type")) {
                        "response.output_text.delta" -> { val d = ev.optString("delta"); text.append(d); onDelta?.invoke(d) }
                        "response.output_item.done" -> ev.optJSONObject("item")?.let { items[ev.optInt("output_index", items.size)] = it }
                        "response.completed" -> { completed = true; finalOutput = ev.optJSONObject("response")?.optJSONArray("output") }
                        "response.failed" -> {
                            val err = ev.optJSONObject("response")?.optJSONObject("error")
                            throw errorFromCode(err?.optString("code"), 200, err?.optString("message") ?: "response.failed", rid, null)
                        }
                        "response.incomplete" -> throw AiException(AiErrorKind.BAD_OUTPUT, 200, "incomplete", rid, "response.incomplete")
                        "error" -> {
                            val code = ev.optString("code").ifEmpty { ev.optJSONObject("error")?.optString("code") }
                            throw errorFromCode(code, 200, ev.optString("message", "stream error"), rid, ev.optString("param").ifEmpty { null })
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw AiException(AiErrorKind.NETWORK, message = "stream interrupted: ${e.message}")
        }
        // response.completed を受け取って初めて成功とみなす（途中までの出力は使わない）
        if (!completed) throw AiException(AiErrorKind.NETWORK, message = "stream ended without response.completed")
        val out = finalOutput?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }?.takeIf { it.isNotEmpty() } ?: items.values.toList()
        return out to text.toString()
    }

    private fun open(url: String, token: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun readOrThrow(c: HttpURLConnection): String {
        val status = try { c.responseCode } catch (e: IOException) { throw AiException(AiErrorKind.NETWORK, message = e.message ?: "network") }
        if (status !in 200..299) throw errorFrom(status, readError(c), c.getHeaderField("x-request-id"))
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun readError(c: HttpURLConnection): String = try { c.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }

    private fun errorFrom(status: Int, body: String, rid: String?): AiException {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val err = json?.optJSONObject("error")
        val code = err?.optString("code")?.takeIf { it.isNotEmpty() && it != "null" }
        val param = err?.optString("param")?.takeIf { it.isNotEmpty() && it != "null" }
        // 推論開始前の受付エラーは {"detail": "..."} のこともある
        val msg = err?.optString("message") ?: json?.optString("detail") ?: body.take(200)
        if (code != null) return errorFromCode(code, status, msg, rid, param)
        val kind = when (status) {
            401 -> AiErrorKind.AUTH
            403 -> AiErrorKind.NOT_ELIGIBLE
            429 -> AiErrorKind.USAGE_LIMIT
            400, 404 -> AiErrorKind.UNSUPPORTED
            500, 502, 503, 504 -> AiErrorKind.UNAVAILABLE
            else -> AiErrorKind.OTHER
        }
        return AiException(kind, status, null, rid, msg, param)
    }

    private fun errorFromCode(code: String?, status: Int, msg: String, rid: String?, param: String?): AiException {
        val kind = when (code) {
            "subscription_sharing_usage_limit_exceeded", "rate_limit_exceeded" -> AiErrorKind.USAGE_LIMIT
            "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> AiErrorKind.UNAVAILABLE
            "subscription_sharing_user_not_eligible", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> AiErrorKind.NOT_ELIGIBLE
            "subscription_sharing_invalid_user", "invalid_api_key", "token_expired" -> AiErrorKind.AUTH
            "subscription_sharing_unsupported_capability", "subscription_sharing_route_not_supported", "model_not_found", "unsupported_parameter", "unsupported_value" -> AiErrorKind.UNSUPPORTED
            else -> when (status) { 401 -> AiErrorKind.AUTH; 429 -> AiErrorKind.USAGE_LIMIT; 503 -> AiErrorKind.UNAVAILABLE; 400 -> AiErrorKind.UNSUPPORTED; else -> AiErrorKind.OTHER }
        }
        return AiException(kind, status, code, rid, msg, param)
    }
}
