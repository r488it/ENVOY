package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.McpGraph
import org.junit.Assert.assertEquals
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
            "share_call_record", "share_screenshot", "end_voice_chat", "search_photos", "show_photo", "share_photo", "open_panel", "tap", "point_at", "type_text",
            "scroll", "back", "home", "press_key", "wait",
            "arm_portrait", "arm_landscape", "arm_look_at_me", "arm_follow_me", "arm_stop_following", "arm_record_video",
            "arm_switch_camera", "arm_stop_recording", "arm_move", "arm_motion", "arm_dance", "rigel_expression", "rigel_notify", "arm_stop",
            "run_part", "save_part", "delete_part",
        ), names)
    }
}
