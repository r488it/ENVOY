package com.rabi.envoymcp.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URL
import java.util.Base64

/** Claude の API: 送る中身・見出し、答えの読み取り、失敗の分け方、キーの形 (通信はしない) */
class ClaudeClientTest {
    /** 試し用の、キーの形をしただけの文字 (本物のキーではない) */
    private val fakeKey = "sk-ant-api03-" + "x".repeat(80) + "AbCd"

    private fun failure(block: () -> Unit): AiException {
        try { block() } catch (e: AiException) { return e }
        fail("AiException になるはず"); throw AssertionError()
    }

    /** 画像 (base64 の JPEG) を文章の前に置き、モデル・長さの上限・system・考える深さを入れる */
    @Test fun requestBodyHasImageThenText() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
        val b = ClaudeClient.requestBody("claude-sonnet-5-5", 16_000, "画面を読む係", "画像の大きさ: 570x1280", jpeg, "low")
        assertEquals("claude-sonnet-5-5", b.getString("model"))
        assertEquals(16_000, b.getInt("max_tokens"))
        assertEquals("画面を読む係", b.getString("system"))
        assertEquals("low", b.getJSONObject("output_config").getString("effort"))
        val msgs = b.getJSONArray("messages")
        assertEquals(1, msgs.length())
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
        val content = msgs.getJSONObject(0).getJSONArray("content")
        assertEquals(2, content.length())
        val img = content.getJSONObject(0)
        assertEquals("image", img.getString("type"))
        val src = img.getJSONObject("source")
        assertEquals("base64", src.getString("type"))
        assertEquals("image/jpeg", src.getString("media_type"))
        assertEquals(Base64.getEncoder().encodeToString(jpeg), src.getString("data"))
        assertFalse(src.getString("data").contains("\n"))
        val text = content.getJSONObject(1)
        assertEquals("text", text.getString("type"))
        assertEquals("画像の大きさ: 570x1280", text.getString("text"))
    }

    /** 画像なし (「試す」) は文章だけ。考える深さを指定できないモデル (null) には output_config を付けない */
    @Test fun textOnlyBodyWithoutEffort() {
        val b = ClaudeClient.requestBody("claude-haiku-4-5-20251001", 1_024, "短く", "OK とだけ", null, null)
        val content = b.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertFalse(b.has("output_config"))
    }

    /** 見出しは x-api-key・anthropic-version・content-type。送り先は api.anthropic.com だけ */
    @Test fun headersAndEndpoint() {
        val h = ClaudeClient.headers(fakeKey)
        assertEquals(fakeKey, h["x-api-key"])
        assertEquals("2023-06-01", h["anthropic-version"])
        assertEquals("application/json", h["content-type"])
        assertNull(h["Authorization"])
        val u = URL(ClaudeClient.MESSAGES_URL)
        assertEquals("https", u.protocol)
        assertEquals("api.anthropic.com", u.host)
        assertEquals("/v1/messages", u.path)
    }

    /** text の部分だけをつなげる (考えた跡の thinking などは使わない) */
    @Test fun parseJoinsTextBlocks() {
        val body = """{"id":"msg_1","type":"message","role":"assistant","stop_reason":"end_turn","content":[
            {"type":"thinking","thinking":"","signature":"abc"},
            {"type":"text","text":"{\"summary\": \"設定"},
            {"type":"text","text":"の画面\", \"targets\": []}"}]}"""
        assertEquals("{\"summary\": \"設定の画面\", \"targets\": []}", ClaudeClient.parseText(body))
    }

    @Test fun parseEmptyOrInvalidIsBadOutput() {
        val empty = failure { ClaudeClient.parseText("""{"content":[],"stop_reason":"end_turn"}""") }
        assertEquals(AiErrorKind.BAD_OUTPUT, empty.kind)
        assertEquals("Claude の返事が空でした", empty.userMessage)
        val noContent = failure { ClaudeClient.parseText("""{"stop_reason":"end_turn"}""") }
        assertEquals(AiErrorKind.BAD_OUTPUT, noContent.kind)
        val invalid = failure { ClaudeClient.parseText("<html>gateway</html>") }
        assertEquals(AiErrorKind.BAD_OUTPUT, invalid.kind)
        assertEquals("Claude の返事を読めませんでした", invalid.userMessage)
        val cut = failure { ClaudeClient.parseText("""{"content":[{"type":"thinking","thinking":""}],"stop_reason":"max_tokens"}""") }
        assertTrue(cut.userMessage.contains("途中で終わりました"))
        val refused = failure { ClaudeClient.parseText("""{"content":[{"type":"text","text":"..."}],"stop_reason":"refusal"}""") }
        assertEquals(AiErrorKind.BAD_OUTPUT, refused.kind)
        assertEquals("refusal", refused.code)
    }

    /** HTTP の番号ごとの種類と、画面に出す一言 (ChatGPT の言い方にならない) */
    @Test fun errorsByStatus() {
        fun err(status: Int, type: String, msg: String) =
            ClaudeClient.errorFrom(status, """{"type":"error","error":{"type":"$type","message":"$msg"},"request_id":"req_1"}""", "req_1")
        val auth = err(401, "authentication_error", "invalid x-api-key")
        assertEquals(AiErrorKind.AUTH, auth.kind)
        assertEquals(401, auth.httpStatus)
        assertEquals("authentication_error", auth.code)
        assertEquals("req_1", auth.requestId)
        assertTrue(auth.userMessage.startsWith("API キーが違うか、使えません"))
        assertEquals(AiErrorKind.AUTH, err(403, "permission_error", "not allowed").kind)
        assertEquals(AiErrorKind.USAGE_LIMIT, err(429, "rate_limit_error", "slow down").kind)
        for (s in listOf(500, 503, 529)) assertEquals(AiErrorKind.UNAVAILABLE, err(s, "overloaded_error", "busy").kind)
        val bad = err(400, "invalid_request_error", "max_tokens: too large")
        assertEquals(AiErrorKind.UNSUPPORTED, bad.kind)
        assertTrue(bad.userMessage.contains("max_tokens: too large"))
        assertEquals(AiErrorKind.UNSUPPORTED, err(404, "not_found_error", "model: claude-x").kind)
        assertEquals(AiErrorKind.UNSUPPORTED, err(413, "request_too_large", "too big").kind)
        // 残高が足りないときは 400 でも利用の上限として扱う
        val credit = err(400, "invalid_request_error", "Your credit balance is too low to access the Anthropic API.")
        assertEquals(AiErrorKind.USAGE_LIMIT, credit.kind)
        assertEquals(AiErrorKind.USAGE_LIMIT, err(402, "billing_error", "billing").kind)
        assertEquals(AiErrorKind.OTHER, err(418, "teapot", "x").kind)
        // JSON でない答えでも、番号で分ける
        assertEquals(AiErrorKind.UNAVAILABLE, ClaudeClient.errorFrom(502, "<html>Bad gateway</html>", null).kind)
        for (e in listOf(auth, bad, credit)) assertFalse(e.userMessage.contains("ChatGPT"))
    }

    /** ChatGPT の失敗の一言は、ChatGPT 向けの言い方 (Claude の言い方が混ざらない) */
    @Test fun chatGptMessagesUnchanged() {
        assertEquals("ChatGPT の認証が切れました。設定から再ログインしてください", AiException(AiErrorKind.AUTH, message = "x").userMessage)
        assertEquals("通信できませんでした", AiException(AiErrorKind.NETWORK, message = "x").userMessage)
    }

    /** キーの形: sk-ant- で始まり、英数字と - _ だけ。管理用のキー・ほかの会社のキー・空白入りは受け付けない */
    @Test fun keySanityCheck() {
        assertNull(ClaudeClient.keyProblem(fakeKey))
        assertNotNull(ClaudeClient.keyProblem(""))
        assertNotNull(ClaudeClient.keyProblem("sk-proj-" + "x".repeat(80)))
        assertNotNull(ClaudeClient.keyProblem("sk-ant-admin01-" + "x".repeat(80)))
        assertNotNull(ClaudeClient.keyProblem("sk-ant-api03-short"))
        assertNotNull(ClaudeClient.keyProblem("sk-ant-api03-" + "x".repeat(40) + " " + "y".repeat(40)))
        assertNotNull(ClaudeClient.keyProblem("sk-ant-api03-" + "x".repeat(40) + "あ"))
        assertNotNull(ClaudeClient.keyProblem("sk-ant-api03-" + "x".repeat(400)))
    }

    /** 画面に出すのは末尾 4 文字だけ */
    @Test fun tailShowsLastFourOnly() {
        assertEquals("…AbCd", ClaudeClient.tail(fakeKey))
        assertFalse(ClaudeClient.tail(fakeKey).contains("sk-ant"))
    }
}
