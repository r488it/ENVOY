package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.McpGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** tools/list の道具の名前と並び (ChatGPT に出す順。変えると接続先の見え方が変わる) */
class McpToolsListTest {
    /** McpGraph は Android の部品を作るので、コンストラクタを通さずに作る (道具の一覧は McpGraph を使わずに組み立てる) */
    private fun graph(): McpGraph {
        val cls = Class.forName("sun.misc.Unsafe")
        val u = cls.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return cls.getMethod("allocateInstance", Class::class.java).invoke(u, McpGraph::class.java) as McpGraph
    }

    /** どの道具も、設定の画面の「MCP の道具」のまとまりに入っている (まとまりに、無い道具を書いていない) */
    @Test fun everyToolHasAGroup() {
        val list = McpTools(graph()).list()
        val names = (0 until list.length()).map { list.getJSONObject(it).getString("name") }.toSet()
        val grouped = ToolGroups.groups.flatMap { it.second }
        assertEquals(emptySet<String>(), names - grouped.toSet())
        assertEquals(emptySet<String>(), grouped.toSet() - names)
        assertEquals(grouped.size, grouped.toSet().size)
    }

    @Test fun namesAndOrder() {
        val list = McpTools(graph()).list()
        val names = (0 until list.length()).map { list.getJSONObject(it).getString("name") }
        assertEquals(listOf(
            "get_state", "read_screen", "get_screenshot", "look_at_screen", "list_apps", "list_shortcuts", "list_parts",
            "open_app", "open_shortcut", "call_phone", "rigel_call", "rigel_call_status", "rigel_call_hangup", "rigel_incoming",
            "share_call_record", "share_screenshot", "end_voice_chat", "search_photos", "show_photo", "share_photo", "open_panel", "tap", "point_at", "wait_for_user", "type_text",
            "scroll", "back", "home", "press_key", "wait",
            "arm_portrait", "arm_landscape", "arm_look_at_me", "arm_follow_me", "arm_stop_following", "arm_record_video",
            "arm_switch_camera", "arm_stop_recording", "arm_move", "arm_motion", "arm_dance", "rigel_expression", "rigel_notify", "get_notices", "arm_stop",
            "run_part", "save_part", "delete_part",
            "kids_start", "kids_answer", "kids_cheer", "kids_end",
        ), names)
    }

    /**
     * まなびの道具は「まなび」のまとまりで、お勉強の画面を変えるので読むだけではない (読むだけのエージェントには出さない)。
     * どれも引数なしで呼べる (kids_start は題なしで子どもが選ぶ、kids_answer は答えなしで画面で押したかを確かめる)。
     * 子どもモードで使える道具は、どれも本物の道具の一覧にある
     */
    @Test fun kidsToolsAreGroupedAndNotReadOnly() {
        val list = McpTools(graph()).list()
        val byName = (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.getString("name") }
        for (t in listOf("kids_start", "kids_answer", "kids_cheer", "kids_end")) {
            assertEquals(ToolGroups.KIDS, ToolGroups.of(t))
            val annotations = byName.getValue(t).getJSONObject("annotations")
            assertEquals(false, annotations.getBoolean("readOnlyHint"))
            assertEquals(false, annotations.getBoolean("destructiveHint"))
            assertEquals(0, byName.getValue(t).getJSONObject("inputSchema").getJSONArray("required").length())
        }
        assertTrue(byName.getValue("kids_start").getJSONObject("inputSchema").getJSONObject("properties").has("topic"))
        assertTrue(byName.getValue("kids_answer").getJSONObject("inputSchema").getJSONObject("properties").has("answer"))
        assertEquals(emptySet<String>(), AgentPolicy.KIDS_TOOLS - byName.keys)
    }

    /** get_notices は読むだけ (読むだけのエージェントにも出せる)。お知らせを預ける rigel_notify は読むだけではない */
    @Test fun getNoticesIsReadOnly() {
        val list = McpTools(graph()).list()
        val byName = (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.getString("name") }
        assertEquals(true, byName.getValue("get_notices").getJSONObject("annotations").getBoolean("readOnlyHint"))
        assertEquals(false, byName.getValue("rigel_notify").getJSONObject("annotations").getBoolean("readOnlyHint"))
        assertEquals(ToolGroups.of("rigel_notify"), ToolGroups.of("get_notices"))
        val reader = Agent("a1", "テスト", token = "t", readOnly = true, groups = setOf(ToolGroups.of("get_notices")))
        assertEquals(true, AgentPolicy.allows(reader, "get_notices", readOnly = true, now = 0L))
        assertEquals(false, AgentPolicy.allows(reader, "rigel_notify", readOnly = false, now = 0L))
    }
}
