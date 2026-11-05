package com.rabi.envoymcp.auth

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Base64

/**
 * Claude (Anthropic の Messages API) を、本人の API キーで呼ぶ。画面の理解 (look_at_screen) で画面の画像を読ませるのに使う。
 * - POST https://api.anthropic.com/v1/messages (ストリーミングなし。答えの content の text をつなげて返す)
 * - キーは x-api-key の見出しでだけ送る。送り先は api.anthropic.com だけ (変えられないよう定数にする)。キーは記録に出さない
 * - 失敗は AiException (種類は AiErrorKind) にし、画面に出す一言は Claude 向けの言い方にする
 * - 送る中身の組み立て・答えの読み取り・失敗の分け方は、試せるよう通信と切り離す (companion の関数)
 */
class ClaudeClient {
    /**
     * 1 回だけ聞いて、答えの文章を返す。jpeg があれば、文章の前に画像を付ける。
     * effort は考える深さ (対応していないモデルには null を渡す)
     */
    fun ask(apiKey: String, model: String, system: String, prompt: String, jpeg: ByteArray?, maxTokens: Int, effort: String?,
            timeoutMs: Int = READ_TIMEOUT_MS): String {
        val body = requestBody(model, maxTokens, system, prompt, jpeg, effort).toString().toByteArray(Charsets.UTF_8)
        val c = try { URL(MESSAGES_URL).openConnection() as HttpURLConnection } catch (e: IOException) { throw networkError(e) }
        try {
            c.requestMethod = "POST"
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            headers(apiKey).forEach { (k, v) -> c.setRequestProperty(k, v) }
            val status = try {
                c.outputStream.use { it.write(body) }
                c.responseCode
            } catch (e: IOException) {
                throw networkError(e)
            }
            val rid = c.getHeaderField("request-id")
            if (status !in 200..299) throw errorFrom(status, readError(c), rid)
            val text = try { c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } } catch (e: IOException) { throw networkError(e) }
            return parseText(text, rid)
        } finally {
            c.disconnect()
        }
    }

    private fun readError(c: HttpURLConnection): String = try { c.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }

    private fun networkError(e: IOException): AiException =
        if (e is SocketTimeoutException) AiException(AiErrorKind.NETWORK, message = "timeout", userText = "Claude の返事が時間内に来ませんでした。少し待ってから試してください")
        else AiException(AiErrorKind.NETWORK, message = e.message ?: "network", userText = "Claude に通信できませんでした (通信の状態を確かめてください)")

    companion object {
        /** 送り先 (キーを送るのはここだけ) */
        const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2023-06-01"
        private const val CONNECT_TIMEOUT_MS = 15_000
        /** 答えを待つ長さ (look_at_screen は 60 秒で打ち切られるので、画面を撮る時間の分だけ短く) */
        const val READ_TIMEOUT_MS = 50_000
        /** キーの長さの目安 (これより短い・長いものは、キーとして受け付けない) */
        private const val MIN_KEY_LENGTH = 40
        private const val MAX_KEY_LENGTH = 300

        /** 送る見出し (キー・API の版・中身の形) */
        fun headers(apiKey: String): Map<String, String> =
            linkedMapOf("x-api-key" to apiKey, "anthropic-version" to API_VERSION, "content-type" to "application/json")

        /** 送る中身。画像 (JPEG) があれば文章の前に置く (Claude は、画像を先に置くとよく読む) */
        fun requestBody(model: String, maxTokens: Int, system: String, prompt: String, jpeg: ByteArray?, effort: String?): JSONObject {
            val content = JSONArray()
            if (jpeg != null) content.put(JSONObject().put("type", "image").put("source", JSONObject()
                .put("type", "base64").put("media_type", "image/jpeg").put("data", Base64.getEncoder().encodeToString(jpeg))))
            content.put(JSONObject().put("type", "text").put("text", prompt))
            val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            if (effort != null) body.put("output_config", JSONObject().put("effort", effort))
            return body
        }

        /** 答えの text の部分をつなげる (考えた跡などのほかの部分は使わない)。断られた・空・読めないときは BAD_OUTPUT */
        fun parseText(body: String, requestId: String? = null): String {
            val j = runCatching { JSONObject(body) }.getOrNull()
                ?: throw AiException(AiErrorKind.BAD_OUTPUT, 200, null, requestId, "invalid json", userText = "Claude の返事を読めませんでした")
            val stop = str(j, "stop_reason")
            if (stop == "refusal") throw AiException(AiErrorKind.BAD_OUTPUT, 200, stop, requestId, "refusal", userText = "Claude がこの依頼を断りました")
            val blocks = j.optJSONArray("content") ?: JSONArray()
            val text = (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
            if (text.isBlank()) throw AiException(AiErrorKind.BAD_OUTPUT, 200, stop, requestId, "no text",
                userText = if (stop == "max_tokens") "Claude の返事が長すぎて、途中で終わりました" else "Claude の返事が空でした")
            return text
        }

        /** 失敗の答え (HTTP の番号と {"error": {"type", "message"}}) を、種類と画面に出す一言に分ける */
        fun errorFrom(status: Int, body: String, requestId: String?): AiException {
            val err = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            val type = err?.let { str(it, "type") }
            val msg = err?.let { str(it, "message") } ?: body.take(200)
            val detail = msg.take(120)
            val (kind, text) = when {
                status == 401 || status == 403 -> AiErrorKind.AUTH to "API キーが違うか、使えません (ENVOY MCP の設定で、Claude の API キーを保存し直してください)"
                // 残高が足りないときは 400 で来ることもある
                status == 402 || msg.contains("credit balance", ignoreCase = true) ->
                    AiErrorKind.USAGE_LIMIT to "Anthropic の API の残高 (クレジット) が足りません (Claude Console の Billing で確かめてください)"
                status == 429 -> AiErrorKind.USAGE_LIMIT to "Claude の API の利用の上限に達しました。少し待ってから試してください"
                status == 404 -> AiErrorKind.UNSUPPORTED to "選んだ Claude のモデルが見つからないか、この API キーでは使えません。ほかのモデルを選んでください ($detail)"
                status == 413 -> AiErrorKind.UNSUPPORTED to "送るデータが大きすぎて、Claude が受け付けませんでした"
                status == 400 -> AiErrorKind.UNSUPPORTED to "Claude が依頼を受け付けませんでした ($detail)"
                status == 529 || status in 500..599 -> AiErrorKind.UNAVAILABLE to "Claude が混み合っているか、一時的に使えません。少し待ってから試してください"
                else -> AiErrorKind.OTHER to "Claude でエラーが起きました (HTTP $status)"
            }
            return AiException(kind, status, type, requestId, msg, userText = text)
        }

        /**
         * クリップボードから入れたキー (前後の空白は除いたもの) の形を確かめる。よければ null、だめなら理由 (画面に出す一言)。
         * 中身が本物かは「試す」で確かめる
         */
        fun keyProblem(key: String): String? = when {
            key.isEmpty() -> "クリップボードが空です"
            !key.startsWith("sk-ant-") -> "クリップボードの中身が Anthropic の API キーの形ではありません (sk-ant- で始まるもの)"
            key.startsWith("sk-ant-admin") -> "管理用のキー (Admin API キー) は使えません。ふつうの API キーをコピーしてください"
            key.length !in MIN_KEY_LENGTH..MAX_KEY_LENGTH || key.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_') } ->
                "クリップボードの中身が API キーの形ではありません"
            else -> null
        }

        /** 画面に出すキーの印 (末尾 4 文字だけ) */
        fun tail(key: String): String = "…" + key.takeLast(4)

        /** 文字の値 (無い・null なら null) */
        private fun str(j: JSONObject, k: String): String? = j.optString(k).takeIf { it.isNotEmpty() && it != "null" }
    }
}
