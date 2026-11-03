package com.rabi.envoymcp.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class McpServerTest {
    private class FakeTools : ToolHost {
        val called = CompletableDeferred<String>()
        override fun list(): JSONArray = JSONArray().put(JSONObject().put("name", "get_state"))
        override suspend fun call(name: String, args: JSONObject): ToolResult? {
            if (name != "get_state") return null
            called.complete(name)
            return ToolResult("ok", JSONObject().put("battery", 80))
        }
    }

    private val tools = FakeTools()
    private val log = ToolLog()
    private val server = McpServer(tools, port = { 0 }, token = { TOKEN }, log = log)

    private fun req(body: String, path: String = "/mcp/$TOKEN", headers: Map<String, String> = mapOf("Host" to "127.0.0.1:8765"), method: String = "POST"): HttpRequest {
        val head = buildString {
            append("$method $path HTTP/1.1\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("Content-Length: ${body.toByteArray().size}\r\n\r\n")
        }
        return Http.read(ByteArrayInputStream((head + body).toByteArray()), 1 shl 20)!!
    }

    private fun rpc(method: String, params: JSONObject = JSONObject(), id: Int = 1) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString()

    @Test fun initializeAnswersRequestedVersion() = runTest {
        val r = server.respond(req(rpc("initialize", JSONObject().put("protocolVersion", "2025-06-18"))))
        assertEquals(200, r.status)
        val res = JSONObject(String(r.body)).getJSONObject("result")
        assertEquals("2025-06-18", res.getString("protocolVersion"))
        assertEquals("envoy-mcp", res.getJSONObject("serverInfo").getString("name"))
        assertTrue(res.getJSONObject("capabilities").has("tools"))
    }

    @Test fun unknownVersionGetsLatest() = runTest {
        val r = server.respond(req(rpc("initialize", JSONObject().put("protocolVersion", "1999-01-01"))))
        assertEquals(McpProtocol.SUPPORTED.first(), JSONObject(String(r.body)).getJSONObject("result").getString("protocolVersion"))
    }

    @Test fun notificationGets202() = runTest {
        val r = server.respond(req("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertEquals(202, r.status)
        assertEquals(0, r.body.size)
    }

    @Test fun toolsListAndCall() = runTest {
        val list = JSONObject(String(server.respond(req(rpc("tools/list"))).body)).getJSONObject("result").getJSONArray("tools")
        assertEquals("get_state", list.getJSONObject(0).getString("name"))
        val call = JSONObject(String(server.respond(req(rpc("tools/call", JSONObject().put("name", "get_state").put("arguments", JSONObject())))).body))
        val res = call.getJSONObject("result")
        assertFalse(res.getBoolean("isError"))
        assertEquals("ok", res.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(80, res.getJSONObject("structuredContent").getInt("battery"))
        assertEquals("get_state", tools.called.await())
    }

    @Test fun unknownToolAndMethodAreErrors() = runTest {
        val t = JSONObject(String(server.respond(req(rpc("tools/call", JSONObject().put("name", "tap")))).body))
        assertEquals(McpProtocol.INVALID_PARAMS, t.getJSONObject("error").getInt("code"))
        val m = JSONObject(String(server.respond(req(rpc("resources/list"))).body))
        assertEquals(McpProtocol.METHOD_NOT_FOUND, m.getJSONObject("error").getInt("code"))
    }

    @Test fun brokenJsonIsParseError() = runTest {
        val r = JSONObject(String(server.respond(req("{not json")).body))
        assertEquals(McpProtocol.PARSE_ERROR, r.getJSONObject("error").getInt("code"))
    }

    @Test fun bearerHeaderIsAccepted() = runTest {
        val r = server.respond(req(rpc("ping"), path = "/mcp", headers = mapOf("Host" to "localhost:9000", "Authorization" to "Bearer $TOKEN")))
        assertEquals(200, r.status)
    }

    @Test fun missingOrWrongTokenIsRefused() = runTest {
        assertEquals(403, server.respond(req(rpc("ping"), path = "/mcp")).status)
        assertEquals(403, server.respond(req(rpc("ping"), path = "/mcp/wrong")).status)
        assertEquals(403, server.respond(req(rpc("ping"), path = "/mcp", headers = mapOf("Host" to "127.0.0.1", "Authorization" to "Bearer wrong"))).status)
        assertFalse(tools.called.isCompleted)
        // 記録に合言葉そのものは残さない
        assertTrue(log.entries.value.isNotEmpty())
        assertTrue(log.entries.value.none { it.detail.contains(TOKEN) || it.detail.contains("wrong") })
    }

    @Test fun browserAndForeignHostAreRefused() = runTest {
        assertEquals(403, server.respond(req(rpc("ping"), headers = mapOf("Host" to "127.0.0.1:8765", "Origin" to "https://evil.example"))).status)
        assertEquals(403, server.respond(req(rpc("ping"), headers = mapOf("Host" to "evil.example:8765"))).status)
        assertEquals(403, server.respond(req(rpc("ping"), headers = emptyMap())).status)
    }

    @Test fun otherPathsAndMethods() = runTest {
        assertEquals(404, server.respond(req(rpc("ping"), path = "/")).status)
        val get = server.respond(req("", method = "GET"))
        assertEquals(405, get.status)
        assertTrue(get.extra.contains("Allow" to "POST"))
    }

    @Test fun chunkedBodyIsRead() {
        val body = rpc("ping")
        val raw = "POST /mcp/$TOKEN HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "${Integer.toHexString(5)}\r\n${body.take(5)}\r\n${Integer.toHexString(body.length - 5)}\r\n${body.drop(5)}\r\n0\r\n\r\n"
        val r = Http.read(ByteArrayInputStream(raw.toByteArray()), 1 shl 20)!!
        assertEquals(body, String(r.body))
    }

    @Test fun emptyConnectionAndTooLarge() {
        assertNull(Http.read(ByteArrayInputStream(ByteArray(0)), 100))
        val raw = "POST /mcp HTTP/1.1\r\nContent-Length: 1000\r\n\r\n"
        val e = runCatching { Http.read(ByteArrayInputStream(raw.toByteArray()), 100) }.exceptionOrNull()
        assertTrue(e is Http.TooLarge)
        val neg = runCatching { Http.read(ByteArrayInputStream("POST /mcp HTTP/1.1\r\nContent-Length: -1\r\n\r\n".toByteArray()), 100) }.exceptionOrNull()
        assertTrue(neg is java.io.IOException && neg !is Http.TooLarge)
    }

    @Test fun responseIsWritten() {
        val out = ByteArrayOutputStream()
        Http.write(out, HttpResponse(200, "OK", "application/json", "{}".toByteArray()))
        val s = out.toString(Charsets.UTF_8.name())
        assertTrue(s.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(s.contains("Content-Length: 2\r\n"))
        assertTrue(s.endsWith("\r\n\r\n{}"))
    }

    @Test fun imageContentIsSerialized() {
        val j = ToolResult("画面です", images = listOf(ToolResult.Image("QUJD", "image/jpeg"))).toJson()
        val c = j.getJSONArray("content")
        assertEquals(2, c.length())
        assertEquals("text", c.getJSONObject(0).getString("type"))
        assertEquals("image", c.getJSONObject(1).getString("type"))
        assertEquals("QUJD", c.getJSONObject(1).getString("data"))
        assertEquals("image/jpeg", c.getJSONObject(1).getString("mimeType"))
    }

    /** 本物のソケットで: 127.0.0.1 で待ち受け、HTTP で受け答えし、止めたら閉じる */
    @Test fun realSocketRoundTrip() {
        server.start()
        try {
            val port = (server.state.value as McpServer.State.Running).port
            val c = java.net.URL("http://127.0.0.1:$port/mcp/$TOKEN").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(rpc("tools/call", JSONObject().put("name", "get_state")).toByteArray()) }
            assertEquals(200, c.responseCode)
            assertEquals("application/json", c.contentType)
            val res = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("result")
            assertEquals("ok", res.getJSONArray("content").getJSONObject(0).getString("text"))
        } finally {
            server.stop()
        }
        assertEquals(McpServer.State.Stopped, server.state.value)
    }

    companion object {
        private const val TOKEN = "abcDEF234"
    }
}
