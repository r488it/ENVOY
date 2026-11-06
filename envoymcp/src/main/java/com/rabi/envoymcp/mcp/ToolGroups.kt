package com.rabi.envoymcp.mcp

/** 道具のまとまり (設定の画面の「MCP の道具」で、この順に並べる。どのまとまりにも入らない道具は「その他」) */
object ToolGroups {
    val groups: List<Pair<String, List<String>>> = listOf(
        "様子・画面" to listOf("get_state", "read_screen", "get_screenshot", "look_at_screen", "share_screenshot"),
        "アプリ" to listOf("list_apps", "list_shortcuts", "open_app", "open_shortcut", "open_panel"),
        "操作" to listOf("tap", "point_at", "wait_for_user", "type_text", "scroll", "back", "home", "press_key", "wait"),
        "部品 (覚えた手順)" to listOf("list_parts", "run_part", "save_part", "delete_part"),
        "写真" to listOf("search_photos", "show_photo", "share_photo"),
        "電話" to listOf("call_phone", "rigel_call", "rigel_call_status", "rigel_call_hangup", "rigel_incoming", "share_call_record"),
        "音声モード・表情・お知らせ" to listOf("end_voice_chat", "rigel_expression", "rigel_notify", "get_notices"),
        "AgentARM (スマホを載せる腕)" to listOf(
            "arm_portrait", "arm_landscape", "arm_look_at_me", "arm_follow_me", "arm_stop_following", "arm_record_video",
            "arm_switch_camera", "arm_stop_recording", "arm_move", "arm_motion", "arm_dance", "arm_stop",
        ),
    )
    const val OTHER = "その他"

    fun of(name: String): String = groups.firstOrNull { name in it.second }?.first ?: OTHER
}
