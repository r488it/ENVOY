package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.kids.KidsTeacher
import com.rabi.envoymcp.voice.ClaudeVoice
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** 子どもモード: どのエージェントにも、まなびの道具 (と表情・腕の動き・踊り・会話を終える道具) だけを出し、ほかは動かさずに断る */
class KidModeTest {
    /** まなびの道具と、ほかの道具 (買う・電話・共有・ほかのアプリを開く・押す など) */
    private class Tools : ToolHost {
        val called = ArrayList<String>()
        val refused = ArrayList<String>()
        val refusedInKidMode = ArrayList<String>()
        private val tools = listOf(
            "get_state" to true, "tap" to false, "type_text" to false, "call_phone" to false, "rigel_call" to false, "share_photo" to false,
            "open_app" to false, "run_part" to false, "kids_start" to false, "kids_question" to false, "kids_cheer" to false, "kids_end" to false,
            "rigel_expression" to false, "arm_motion" to false, "arm_dance" to false, "end_voice_chat" to false, "mystery" to true,
        )
        override fun list(): JSONArray = JSONArray().apply {
            tools.forEach { (n, ro) -> put(JSONObject().put("name", n).put("annotations", JSONObject().put("readOnlyHint", ro))) }
        }
        override suspend fun call(name: String, args: JSONObject, agent: Agent?): ToolResult? {
            if (tools.none { it.first == name }) return null
            called += name
            return ToolResult("ok")
        }
        override fun refuse(name: String, args: JSONObject, agent: Agent): ToolResult {
            refused += name
            return super.refuse(name, args, agent)
        }
        override fun refuseInKidMode(name: String, args: JSONObject, agent: Agent?): ToolResult {
            refusedInKidMode += name
            return super.refuseInKidMode(name, args, agent)
        }
    }

    private var kid = true
    private var dev = false
    private val tools = Tools()
    private val agents = Agents()
    private val server = McpServer(tools, port = { 0 }, token = { TOKEN }, log = ToolLog(), agents = agents, unlimited = { dev },
        extraInstructions = { McpServer.extraInstructions(claudeVoice = false, kidMode = kid) }, kidMode = { kid })

    private fun req(body: String, path: String): HttpRequest {
        val head = "POST $path HTTP/1.1\r\nHost: 127.0.0.1:8765\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n"
        return Http.read(ByteArrayInputStream((head + body).toByteArray()), 1 shl 20)!!
    }

    private fun rpc(method: String, params: JSONObject = JSONObject()) =
        JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString()

    private suspend fun names(token: String = TOKEN): List<String> {
        val list = JSONObject(String(server.respond(req(rpc("tools/list"), "/mcp/$token")).body)).getJSONObject("result").getJSONArray("tools")
        return (0 until list.length()).map { list.getJSONObject(it).getString("name") }
    }

    private suspend fun call(name: String, token: String = TOKEN): JSONObject =
        JSONObject(String(server.respond(req(rpc("tools/call", JSONObject().put("name", name).put("arguments", JSONObject())), "/mcp/$token")).body))

    private fun text(res: JSONObject) = res.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")

    /** 子どもモードの間は、まなびの道具と表情・腕の動き・踊り・会話を終える道具だけが一覧に出る (並びはそのまま)。切れば全部 */
    @Test fun kidModeListsOnlyLearningTools() = runTest {
        assertEquals(KIDS, names())
        kid = false
        assertEquals(17, names().size)
    }

    /** 子どもモードの間に、ほかの道具を呼ぶと、動かさずに断り (記録に残す)、まなびの道具は動く */
    @Test fun kidModeRefusesOtherTools() = runTest {
        val others = listOf("tap", "type_text", "call_phone", "rigel_call", "share_photo", "open_app", "run_part", "get_state", "mystery")
        for (t in others) {
            val res = call(t).getJSONObject("result")
            assertTrue(t, res.getBoolean("isError"))
            assertTrue(res.getJSONArray("content").getJSONObject(0).getString("text").contains("子どもモード"))
            assertEquals(false, res.getJSONObject("structuredContent").getBoolean("permitted"))
        }
        assertEquals(others, tools.refusedInKidMode)
        assertTrue(tools.called.isEmpty())
        for (t in KIDS) assertFalse(t, call(t).getJSONObject("result").getBoolean("isError"))
        assertEquals(KIDS, tools.called)
        // 一覧に無い道具は、子どもモードでも「ありません」
        assertEquals(McpProtocol.INVALID_PARAMS, call("nope").getJSONObject("error").getInt("code"))
        // 切れば、ほかの道具も動く
        kid = false
        assertEquals("ok", text(call("tap")))
    }

    /** 開発モードでも、子どもモードの間は絞る */
    @Test fun kidModeHoldsInDevMode() = runTest {
        dev = true
        assertEquals(KIDS, names())
        assertTrue(call("call_phone").getJSONObject("result").getBoolean("isError"))
        assertEquals(listOf("call_phone"), tools.refusedInKidMode)
    }

    /** 足したエージェントの権限は、その上でかける (読むだけなら、まなびの道具も使えない。まなびだけ許せば、表情の道具は使えない) */
    @Test fun agentLimitsStillApply() = runTest {
        val a = agents.add()
        assertEquals(emptyList<String>(), names(a.token))
        agents.update(a.copy(readOnly = false, groups = setOf(ToolGroups.KIDS)))
        assertEquals(listOf("kids_start", "kids_question", "kids_cheer", "kids_end"), names(a.token))
        assertFalse(call("kids_start", a.token).getJSONObject("result").getBoolean("isError"))
        assertTrue(call("rigel_expression", a.token).getJSONObject("result").getBoolean("isError"))
        assertEquals(listOf("rigel_expression"), tools.refused)
        assertTrue(call("tap", a.token).getJSONObject("result").getBoolean("isError"))
        assertEquals(listOf("tap"), tools.refusedInKidMode)
    }

    /** 道具を使ってよいかの決まり (AgentPolicy): 子どもモードの間は、決まった道具だけ (開発モードでも) */
    @Test fun policyAllowsOnlyKidTools() {
        assertEquals(KIDS.toSet(), AgentPolicy.KIDS_TOOLS)
        for (t in KIDS) assertTrue(t, AgentPolicy.allows(Agent.MAIN, t, readOnly = false, now = 1L, kidMode = true))
        for (t in listOf("call_phone", "rigel_call", "share_photo", "share_screenshot", "share_call_record", "open_app", "open_shortcut",
            "tap", "type_text", "press_key", "run_part", "save_part", "get_state", "some_new_tool")) {
            assertFalse(t, AgentPolicy.allows(Agent.MAIN, t, readOnly = false, now = 1L, kidMode = true))
            assertFalse(t, AgentPolicy.allows(Agent.MAIN, t, readOnly = false, now = 1L, unlimited = true, kidMode = true))
            assertTrue(t, AgentPolicy.allows(Agent.MAIN, t, readOnly = false, now = 1L, kidMode = false))
        }
        assertEquals(false, AgentPolicy.kidRefusal().structured?.getBoolean("permitted"))
    }

    /** 子どもモードの説明は、子どもモードの間だけ、最初の案内に足す */
    @Test fun kidInstructionsOnlyInKidMode() = runTest {
        assertEquals("", McpServer.extraInstructions(claudeVoice = false, kidMode = false))
        assertEquals(KidsTeacher.INSTRUCTIONS, McpServer.extraInstructions(claudeVoice = false, kidMode = true))
        assertTrue(McpServer.extraInstructions(claudeVoice = true, kidMode = false).contains(ClaudeVoice.QUIET_TOOLS))
        assertFalse(McpServer.extraInstructions(claudeVoice = true, kidMode = false).contains(KidsTeacher.INSTRUCTIONS))
        assertTrue(McpServer.extraInstructions(claudeVoice = true, kidMode = true).endsWith(KidsTeacher.INSTRUCTIONS))
        suspend fun instructions() = JSONObject(String(server.respond(req(rpc("initialize"), "/mcp/$TOKEN")).body))
            .getJSONObject("result").getString("instructions")
        assertEquals(McpServer.INSTRUCTIONS + " " + KidsTeacher.INSTRUCTIONS, instructions())
        kid = false
        assertEquals(McpServer.INSTRUCTIONS, instructions())
    }

    companion object {
        private const val TOKEN = "kidTOKEN234"
        /** 子どもモードで使える道具 (Tools の並び) */
        private val KIDS = listOf("kids_start", "kids_question", "kids_cheer", "kids_end", "rigel_expression", "arm_motion", "arm_dance", "end_voice_chat")
    }
}
