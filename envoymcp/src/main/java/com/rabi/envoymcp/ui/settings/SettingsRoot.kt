package com.rabi.envoymcp.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.R

/**
 * 設定の画面: 一覧 (探す・準備の状態・区分) → 区分の画面 (その区分のカード)。戻るで一覧へ。
 * 区分のカードは、見えていない区分の分も高さ 0 で作っておく (どこからでも全部の項目を探せるように。動きも見えている時と同じ)
 */
@Composable
internal fun SettingsRoot(g: McpGraph, a: SettingsActivity, tick: Int) {
    var out by remember { mutableStateOf("") }
    val search = remember { SettingsSearch() }
    var query by rememberSaveable { mutableStateOf("") }
    val opened = remember { OpenCards(g.settings.settingsOpen) { g.settings.settingsOpen = it } }
    // 開いている区分 (null なら一覧)
    var sectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val section = sectionName?.let { n -> Section.entries.firstOrNull { it.name == n } }
    var focus by remember { mutableStateOf(Focus("", 0)) }
    fun open(s: Section, card: String = "") { sectionName = s.name; focus = Focus(card, focus.n + 1); out = "" }
    fun back() { sectionName = null; focus = Focus("", focus.n + 1) }
    val jump = remember { { s: Section, card: String -> open(s, card) } }
    BackHandler(enabled = section != null) { back() }
    // 一覧で探している間は、戻るで探すのをやめる
    BackHandler(enabled = section == null && query.isNotEmpty()) { query = "" }
    // 区分の画面では絞り込まない (その区分を全部出す)。探した言葉は一覧に戻ったときのために残す
    val q = if (section == null) query else ""
    search.setQuery(q)
    val listScroll = rememberScrollState()
    val detailScroll = remember(section) { ScrollState(0) }
    CompositionLocalProvider(LocalSearch provides search, LocalQuery provides q, LocalOpen provides opened,
        LocalFocus provides focus, LocalJump provides jump) {
    // 上の時刻・電池の帯と、下のナビゲーションの帯に重ならないように
    Column(Modifier.fillMaxSize().background(Palette.Bg).systemBarsPadding()) {
        if (section != null) TopBar(section) { back() }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(if (section == null) listScroll else detailScroll).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            if (section == null) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.app_name), color = Palette.Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, modifier = Modifier.padding(top = 16.dp))
                Text("設定 (検証用)", color = Color.White, fontSize = 16.sp)
                // 設定を探す: 合う項目を区分ごとに出す (見出しが合えばカードを丸ごと)。区分の名前やカードの見出しを押すと、その区分を開く
                OutlinedTextField(query, { query = it }, label = { Text("設定を探す (例: 録画・マイク・リゲル)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("消す") } })
                if (search.active) {
                    if (!search.anyVisible()) Text("「${query.trim()}」に合う項目はありません", color = Palette.Muted, fontSize = 14.sp)
                } else {
                    ReadyChecklist(g, a, tick, jump)
                    SectionList(g, a, tick) { open(it) }
                }
            }
            Section.entries.forEach { s ->
                key(s) {
                    val visible = section == s || (section == null && search.active && search.sectionVisible(s.title))
                    CompositionLocalProvider(LocalSection provides s) {
                        SectionBody(visible) {
                            if (section == null && search.active) SearchHead(s) { open(s) }
                            SectionCards(s, g, a, tick) { out = it }
                        }
                    }
                }
            }
        }
        if (out.isNotBlank()) Said(out) { out = "" }
    }
    }
}

/** 区分の中のカード */
@Composable
private fun SectionCards(s: Section, g: McpGraph, a: SettingsActivity, tick: Int, say: (String) -> Unit) {
    when (s) {
        Section.CONNECT -> { ServerCard(g, a, tick, say); ToolsCard(g); TunnelCard(g, a, tick, say); ChatGptCard(g, a, tick, say); PublicTunnelCard(g, a, tick, say) }
        Section.OPERATE -> { ControlCard(g, a, tick); AdbCard(g, a, say); OverlayCard(g, a, tick); VisionCard(g, a, tick, say); PartsCard(g, tick) }
        Section.SAFETY -> { ApprovalCard(g, a, tick, say); AgentsCard(g, a, tick, say); ActivityLogCard(g, a) }
        Section.RIGEL -> { VoiceAgentCard(g, tick); VoiceStageCard(g, a, tick); PrimerCard(g, a, tick); ExpressionCard(a); MusicStageCard(g, a, tick) }
        Section.PHONE -> PhoneCards(g, a, tick)
        Section.ARM -> ArmCards(g, a, tick, say)
        Section.PHOTO -> PhotoCard(g, a, tick)
        Section.MONITOR -> MonitorCard(g)
        Section.DEV -> { DevCard(g, a, say); DevModeCard(g, a, tick, say); ToolLogCard(g) }
        Section.ABOUT -> LicenseCard()
    }
}

/**
 * 区分のカードを置く所。見えていないときは高さ 0 で作る (見えず、押せず、読み上げもしない)。
 * 見える・見えないで作り直さない (中の状態を残すため、同じ形のまま大きさだけ変える)
 */
@Composable
private fun SectionBody(visible: Boolean, content: @Composable () -> Unit) {
    val m = if (visible) Modifier.fillMaxWidth().padding(top = 16.dp) else Modifier.height(0.dp).clipToBounds().clearAndSetSemantics { }
    Box(m) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
}

/** 探した結果の区分の名前 (押すと、その区分を開く) */
@Composable
private fun SearchHead(s: Section, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(s.icon, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(18.dp))
        Text(s.title, color = Palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
        Text("開く ›", color = Palette.Accent, fontSize = 14.sp)
    }
}

/** 区分の画面の上の帯: 戻る矢印と、区分のアイコン・名前 */
@Composable
private fun TopBar(section: Section, onBack: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = "戻る" },
                contentAlignment = Alignment.Center) {
                Text("‹", color = Palette.Accent, fontSize = 32.sp, modifier = Modifier.clearAndSetSemantics { })
            }
            IconTile(section.icon, Palette.Accent, size = 32.dp)
            Text(section.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Divider))
    }
}

/** ボタンを押した結果の一言 (下に出し続ける。× で消す) */
@Composable
private fun Said(text: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Palette.Surface).padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color(0xFFCFD5E0), fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onClose) { Text("×", color = Palette.Muted, fontSize = 18.sp) }
    }
}
