package com.rabi.envoymcp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.mcp.ToolGroups

/** 道具の一覧の 1 行: 名前 (日本語)・道具の名前・説明の最初の一文 */
private class ToolRow(val title: String, val name: String, val summary: String)

/**
 * MCP の道具: AI エージェントから呼べる道具を、まとまりごとに並べる (最初は閉じておく)。
 * 中身は MCP サーバーが返す道具の一覧 (tools/list) から作るので、道具を足しても合う
 */
@Composable
internal fun ToolsCard(g: McpGraph) {
    val rows = remember {
        val list = g.tools.list()
        (0 until list.length()).map { i ->
            val t = list.getJSONObject(i)
            ToolRow(t.optString("title"), t.getString("name"), firstSentence(t.optString("description")))
        }
    }
    val grouped = (ToolGroups.groups.map { it.first } + ToolGroups.OTHER).map { group -> group to rows.filter { ToolGroups.of(it.name) == group } }
    Card("MCP の道具", "${rows.size} 個", folded = true, icon = Icons.Rounded.Handyman) {
        Note("AI エージェント (ChatGPT など) が、MCP で呼べる道具です。送信・購入・発信など取り消せない操作は、道具が呼ばれても、スマホで本人が承認するまで実行しません。")
        grouped.filter { it.second.isNotEmpty() }.forEach { (group, tools) ->
            Sub(group)
            tools.forEach { ToolItem(it) }
        }
    }
}

@Composable
private fun ToolItem(t: ToolRow) {
    if (!shown(t.title, t.name, t.summary)) return
    Column(Modifier.padding(top = 2.dp)) {
        Text(t.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
        Text(t.name, color = Palette.Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
        if (t.summary.isNotBlank()) Text(t.summary, color = Palette.Muted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

/** 説明の最初の一文 (長ければ切る) */
private fun firstSentence(s: String): String {
    val end = s.indexOf('。')
    val one = if (end >= 0) s.substring(0, end + 1) else s
    return if (one.length > 90) one.take(90) + "…" else one
}
