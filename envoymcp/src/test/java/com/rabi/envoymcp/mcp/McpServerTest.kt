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
        override suspend fun call(name: String, args: JSONObject, agent: Agent?): ToolResult? {
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

    /** 設定で変わる説明は、最初の案内の後ろに足す (空なら足さない) */
    @Test fun extraInstructionsAreAppended() = runTest {
        var extra = ""
        val s = McpServer(tools, port = { 0 }, token = { TOKEN }, log = log, extraInstructions = { extra })
        suspend fun instructions() = JSONObject(String(s.respond(req(rpc("initialize", JSONObject()))).body)).getJSONObject("result").getString("instructions")
        assertEquals(McpServer.INSTRUCTIONS, instructions())
        extra = "音声で話しているときは、黙って道具を呼んでください。"
        assertEquals(McpServer.INSTRUCTIONS + " " + extra, instructions())
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

    // ---------------------------------------------------------------- エージェントの権限

    /** いくつかの道具 (読むだけ・操作・電話・どのまとまりにも入らないもの) と、呼ばれた道具の記録 */
    private class ManyTools : ToolHost {
        val called = ArrayList<String>()
        val refused = ArrayList<String>()
        val agents = ArrayList<String?>()
        private fun tool(name: String, readOnly: Boolean) = JSONObject().put("name", name)
            .put("annotations", JSONObject().put("readOnlyHint", readOnly))
        override fun list(): JSONArray = JSONArray()
            .put(tool("get_state", true)).put(tool("tap", false)).put(tool("call_phone", false)).put(tool("mystery", true))
        override suspend fun call(name: String, args: JSONObject, agent: Agent?): ToolResult? {
            if (name !in setOf("get_state", "tap", "call_phone", "mystery")) return null
            called += name; agents += agent?.name
            return ToolResult("ok")
        }
        override fun refuse(name: String, args: JSONObject, agent: Agent): ToolResult {
            refused += name
            return super.refuse(name, args, agent)
        }
    }

    private var clock = 1_000_000L
    private val many = ManyTools()
    private val agents = Agents()
    private var dev = false
    private val guarded = McpServer(many, port = { 0 }, token = { TOKEN }, log = log, agents = agents, unlimited = { dev }, now = { clock })

    private suspend fun names(server: McpServer, path: String): List<String> {
        val list = JSONObject(String(server.respond(req(rpc("tools/list"), path = path)).body)).getJSONObject("result").getJSONArray("tools")
        return (0 until list.length()).map { list.getJSONObject(it).getString("name") }
    }

    private suspend fun callResult(server: McpServer, path: String, name: String): JSONObject =
        JSONObject(String(server.respond(req(rpc("tools/call", JSONObject().put("name", name).put("arguments", JSONObject())), path = path)).body)).getJSONObject("result")

    /** 本体の合言葉では、エージェントの一覧があっても、すべての道具が同じ並びで出て、呼べる */
    @Test fun mainTokenKeepsEverything() = runTest {
        assertEquals(listOf("get_state", "tap", "call_phone", "mystery"), names(guarded, "/mcp/$TOKEN"))
        assertEquals(many.list().toString(), JSONObject(String(guarded.respond(req(rpc("tools/list"))).body)).getJSONObject("result").getJSONArray("tools").toString())
        assertFalse(callResult(guarded, "/mcp/$TOKEN", "call_phone").getBoolean("isError"))
        assertEquals(listOf("call_phone"), many.called)
        assertEquals(listOf<String?>(Agent.MAIN_NAME), many.agents)
        val r = guarded.respond(req(rpc("ping"), path = "/mcp", headers = mapOf("Host" to "127.0.0.1", "Authorization" to "Bearer $TOKEN")))
        assertEquals(200, r.status)
    }

    /** 足したエージェント: 許したまとまり・読むだけの道具だけが一覧に出る (並びはそのまま) */
    @Test fun addedAgentSeesOnlyAllowedTools() = runTest {
        val a = agents.add(clock)
        // 最初は読むだけ・すべてのまとまり
        assertEquals(listOf("get_state", "mystery"), names(guarded, "/mcp/${a.token}"))
        agents.update(a.copy(readOnly = false, groups = setOf("操作")))
        assertEquals(listOf("tap"), names(guarded, "/mcp/${a.token}"))
        // Bearer でも同じ
        val r = guarded.respond(req(rpc("tools/list"), path = "/mcp", headers = mapOf("Host" to "127.0.0.1", "Authorization" to "Bearer ${a.token}")))
        assertEquals(1, JSONObject(String(r.body)).getJSONObject("result").getJSONArray("tools").length())
    }

    /** 許していない道具を呼ぶと、動かさずに道具の失敗 (isError) で断る */
    @Test fun disallowedToolIsRefusedAndNotRun() = runTest {
        val a = agents.add(clock)   // 読むだけ
        val res = callResult(guarded, "/mcp/${a.token}", "tap")
        assertTrue(res.getBoolean("isError"))
        assertTrue(res.getJSONArray("content").getJSONObject(0).getString("text").contains("許されていません"))
        assertTrue(res.getJSONArray("content").getJSONObject(0).getString("text").contains(Agents.NEW_NAME))
        assertEquals(listOf("tap"), many.refused)
        assertTrue(many.called.isEmpty())
        // 許している道具は動く (呼んだエージェントの名前つき)
        assertFalse(callResult(guarded, "/mcp/${a.token}", "get_state").getBoolean("isError"))
        assertEquals(listOf("get_state"), many.called)
        assertEquals(listOf<String?>(Agents.NEW_NAME), many.agents)
        // 一覧に無い道具は「ありません」
        val unknown = JSONObject(String(guarded.respond(req(rpc("tools/call", JSONObject().put("name", "nope")), path = "/mcp/${a.token}")).body))
        assertEquals(McpProtocol.INVALID_PARAMS, unknown.getJSONObject("error").getInt("code"))
    }

    /** 知らない合言葉・期限切れ・止めているエージェントは 403 (記録に合言葉は残さない) */
    @Test fun unknownExpiredOrOffAgentIsRefused() = runTest {
        val a = agents.add(clock)
        assertEquals(403, guarded.respond(req(rpc("ping"), path = "/mcp/${a.token}x")).status)
        clock = a.expiresAt
        assertEquals(403, guarded.respond(req(rpc("ping"), path = "/mcp/${a.token}")).status)
        assertTrue(log.entries.value.first().detail.contains("期限"))
        agents.update(agents.get(a.id)!!.copy(expiresAt = 0L, enabled = false))
        assertEquals(403, guarded.respond(req(rpc("ping"), path = "/mcp/${a.token}")).status)
        assertTrue(log.entries.value.first().detail.contains("止めて"))
        agents.update(agents.get(a.id)!!.copy(enabled = true))
        assertEquals(200, guarded.respond(req(rpc("ping"), path = "/mcp/${a.token}")).status)
        assertTrue(log.entries.value.none { it.detail.contains(a.token) || it.detail.contains(TOKEN) })
        assertTrue(many.called.isEmpty())
    }

    /** 開発モード: 止めていないエージェントは、期限・読むだけ・まとまりに関係なくすべて使える。止めているものと知らない合言葉は断る */
    @Test fun devModeLiftsLimitsButNotOffOrUnknown() = runTest {
        val a = agents.add(clock)
        agents.update(a.copy(groups = setOf("写真")))
        clock = a.expiresAt + 1
        dev = true
        assertEquals(listOf("get_state", "tap", "call_phone", "mystery"), names(guarded, "/mcp/${a.token}"))
        assertFalse(callResult(guarded, "/mcp/${a.token}", "tap").getBoolean("isError"))
        assertEquals(403, guarded.respond(req(rpc("ping"), path = "/mcp/wrong")).status)
        agents.update(agents.get(a.id)!!.copy(enabled = false))
        assertEquals(403, guarded.respond(req(rpc("ping"), path = "/mcp/${a.token}")).status)
    }

    /** 使った時刻を残す (間をあけて) */
    @Test fun lastUsedIsTouched() = runTest {
        val a = agents.add(clock)
        guarded.respond(req(rpc("ping"), path = "/mcp/${a.token}"))
        assertEquals(clock, agents.get(a.id)!!.lastUsedAt)
        val first = clock
        clock += 1_000
        guarded.respond(req(rpc("ping"), path = "/mcp/${a.token}"))
        assertEquals(first, agents.get(a.id)!!.lastUsedAt)
        clock += 120_000
        guarded.respond(req(rpc("ping"), path = "/mcp/${a.token}"))
        assertEquals(clock, agents.get(a.id)!!.lastUsedAt)
    }

    companion object {
        private const val TOKEN = "abcDEF234"
    }
}
