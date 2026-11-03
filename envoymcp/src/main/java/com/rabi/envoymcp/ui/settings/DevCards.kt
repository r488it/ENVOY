package com.rabi.envoymcp.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.mcp.ToolLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 開発者向け: 接続の情報と合言葉の作り直し。Mac で試すとき・調べるときだけ使う */
@Composable
internal fun DevCard(g: McpGraph, a: SettingsActivity, say: (String) -> Unit) {
    Card("開発者向け (接続の情報)", icon = Icons.Rounded.Key) {
        Line("URL", "http://127.0.0.1:${g.settings.port}/mcp/••••")
        Note("•••• は合言葉です (画面には出しません)。合言葉は URL の最後か、ヘッダー Authorization: Bearer <合言葉> のどちらで渡しても通ります。合言葉の無い接続・違う接続、ブラウザからの接続は断ります。スマホの中の tunnel-client には、アプリが自動で渡します。")
        Btns {
            Btn("URL を写す") { a.copyUrl(); say("合言葉の入った URL を写しました") }
            Btn("合言葉を作り直す") { say(a.regenerateToken()) }
        }
    }
}

/** ChatGPT から呼ばれた道具の記録 (最後の 1 件だけ見せ、残りは折りたたむ) */
@Composable
internal fun ToolLogCard(g: McpGraph) {
    val entries by g.log.entries.collectAsState()
    var showTools by remember { mutableStateOf(false) }
    Card("道具の記録", if (entries.isEmpty()) "まだありません" else "${entries.size} 件", icon = Icons.Rounded.Build) {
        if (entries.isEmpty()) Note("まだありません")
        else {
            val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.JAPAN) }
            fun line(e: ToolLog.Entry) = "${fmt.format(Date(e.at))} ${if (e.ok) "●" else "○"} ${e.name}  ${e.detail}"
            Mono("最後: " + line(entries.first()))
            Btn(if (showTools) "記録を閉じる" else "記録を見る (${entries.size} 件)") { showTools = !showTools }
            if (showTools) {
                Note("新しい順です。")
                entries.take(30).forEach { Mono(line(it)) }
            }
        }
    }
}
