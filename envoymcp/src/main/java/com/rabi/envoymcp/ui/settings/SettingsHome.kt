package com.rabi.envoymcp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PrecisionManufacturing
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.ControlMode
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.a11y.A11yService
import com.rabi.envoymcp.device.adb.AdbState
import com.rabi.envoymcp.mcp.McpServer
import com.rabi.envoymcp.mcp.TunnelClient

/** 設定の区分 (一覧の 1 行。押すと、その区分のカードを並べた画面を開く) */
internal enum class Section(val title: String, val icon: ImageVector) {
    CONNECT("つながり", Icons.Rounded.Link),
    OPERATE("スマホの操作", Icons.Rounded.TouchApp),
    SAFETY("安心 (承認)", Icons.Rounded.Fingerprint),
    RIGEL("リゲル", Icons.Rounded.AutoAwesome),
    PHONE("電話", Icons.Rounded.Call),
    ARM("AgentARM", Icons.Rounded.PrecisionManufacturing),
    PHOTO("写真", Icons.Rounded.PhotoLibrary),
    MONITOR("モニター", Icons.Rounded.DesktopWindows),
    DEV("開発・試験", Icons.Rounded.Code),
}

/** 一覧から飛ぶ先のカードの見出し (カードの見出しと同じ文字にする) */
internal object CardTitle {
    const val SERVER = "MCP サーバー"
    const val TUNNEL = "トンネル (スマホの中の tunnel-client)"
    const val PUBLIC = "Claude から使う（外からつなぐ）"
    const val CONTROL = "端末の操作の方式"
    const val OVERLAY = "他のアプリの上に表示"
    const val VISION = "画面の理解（AI で画面を読む）"
    const val PHONE = "電話の許可"
    const val ARM = "AgentARM (スマホを載せる腕)"
    const val AGENTS = "エージェントの権限"
    const val DEV_MODE = "開発モード"
}

/** 準備の状態の 1 行: アイコン・名前・今の様子・色 (stateColor)・押したときの行き先・直す所を開くボタン (無いこともある) */
private class Check(val icon: ImageVector, val name: String, val status: String, val ok: Boolean?, val go: () -> Unit, val fix: String? = null, val onFix: () -> Unit = {})

/**
 * 準備の状態: ChatGPT からスマホを動かすのに要るものが、そろっているか。
 * 行を押すと、その設定のある区分 (または Android の設定) を開く。そろっていないものは、右のボタンで直す所を開く
 */
@Composable
internal fun ReadyChecklist(g: McpGraph, a: SettingsActivity, tick: Int, open: (Section, String) -> Unit) {
    val server by g.server.state.collectAsState()
    val tunnel by g.tunnel.state.collectAsState()
    val publicSt by g.publicTunnel.state.collectAsState()
    val arm by g.arm.status.collectAsState()
    val serverOn = remember(tick) { g.settings.serverEnabled }
    val tunnelOn = remember(tick) { g.settings.tunnelEnabled }
    val publicOn = remember(tick) { g.settings.publicTunnelEnabled }
    val mode = remember(tick) { g.settings.controlMode }
    val a11yOn = remember(tick) { A11yService.instance != null }
    val canOverlay = remember(tick) { g.overlay.canShow() }
    val notif = remember(tick) { g.nowPlaying.hasAccess() }
    val phone = remember(tick) { a.hasPhonePerms() }
    val armOn = remember(tick) { g.settings.armEnabled }
    val armMissing = remember(tick) { a.armMissingPerms() }
    val dev = remember(tick) { g.settings.devNoAgentLimits }
    val checks = listOfNotNull(
        Check(Icons.Rounded.Dns, "MCP サーバー",
            when { !serverOn -> "オフ"; server is McpServer.State.Running -> "動いています"; else -> server.label },
            if (!serverOn) null else server is McpServer.State.Running,
            { open(Section.CONNECT, CardTitle.SERVER) }),
        Check(Icons.Rounded.Cloud, "トンネル",
            if (!tunnelOn) "スマホの中では動かしていません" else tunnel.label,
            when { !tunnelOn -> null; tunnel is TunnelClient.State.Ready -> true; tunnel is TunnelClient.State.Off || tunnel is TunnelClient.State.Starting -> null; else -> false },
            { open(Section.CONNECT, CardTitle.TUNNEL) }),
        // 外からつなぐ入口 (Claude から使う) は、オンの間だけ出す
        if (publicOn) Check(Icons.Rounded.Public, "Claude から (外からつなぐ)", publicSt.label, publicSt.ok,
            { open(Section.CONNECT, CardTitle.PUBLIC) }) else null,
        Check(Icons.Rounded.Accessibility, "ユーザー補助",
            if (a11yOn) "オン" else if (mode == ControlMode.ADB) "オフ (ADB のみでは使いません)" else "オフ",
            if (a11yOn) true else if (mode == ControlMode.ADB) null else false,
            { open(Section.OPERATE, CardTitle.CONTROL) }, "設定を開く") { a.openAccessibilitySettings() },
        Check(Icons.Rounded.Layers, "他のアプリの上に表示",
            if (canOverlay) "許可" else "未許可 (操作の道具は動きません)", canOverlay,
            { open(Section.OPERATE, CardTitle.OVERLAY) }, "許可する") { a.openOverlayPermission() },
        Check(Icons.Rounded.Notifications, "通知へのアクセス",
            if (notif) "許可" else "未許可 (曲名・着信の通知を読めません)", notif,
            { a.openNotificationAccess() }, "許可する") { a.openNotificationAccess() },
        Check(Icons.Rounded.Call, "電話の許可",
            if (phone) "許可" else "未許可 (電話の道具が使えません)", phone,
            { open(Section.PHONE, CardTitle.PHONE) }, "許可する") { a.requestPhonePerms() },
        Check(Icons.Rounded.PrecisionManufacturing, "AgentARM (腕)",
            when { !armOn -> "使わない"; armMissing.isNotEmpty() -> "許可が足りません (Bluetooth・カメラ)"; else -> arm.link },
            when { !armOn -> null; armMissing.isNotEmpty() -> false; else -> arm.ready },
            { open(Section.ARM, CardTitle.ARM) },
            if (armOn && armMissing.isNotEmpty()) "許可する" else null) { a.requestArmPerms() },
        // 開発モードは、オンの間だけ見てほしいものとして出す
        if (dev) Check(Icons.Rounded.AdminPanelSettings, "開発モード", "オン: エージェントの権限の制限なし", false,
            { open(Section.DEV, CardTitle.DEV_MODE) }, "オフにする") { a.setDevNoAgentLimits(false, say = {}) } else null,
    )
    val ready = checks.count { it.ok == true }
    val needs = checks.count { it.ok == false }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("準備の状態", color = Palette.Title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Chip("${checks.size} つのうち $ready つ使えます", if (needs > 0) false else if (ready == checks.size) true else null)
        }
        checks.forEachIndexed { i, c ->
            if (i > 0) Divider()
            CheckRow(c)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun CheckRow(c: Check) {
    Row(Modifier.fillMaxWidth().clickable(onClick = c.go).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        // アイコンを今の様子の色で塗る (緑: 使える・黄: 見てほしい・灰: オフ)
        IconTile(c.icon, stateColor(c.ok), size = 34.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(c.name, color = Color.White, fontSize = 15.sp)
            Text(c.status, color = if (c.ok == null) Palette.Muted else stateColor(c.ok), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (c.fix != null && c.ok != true) SmallButton(c.fix, c.onFix)
        Text("›", color = Palette.Chevron, fontSize = 22.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

/** 行の右に置く小さなボタン */
@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Box(Modifier.widthIn(max = 120.dp).clip(RoundedCornerShape(50)).border(1.dp, Palette.Primary, RoundedCornerShape(50)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = Palette.Primary, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Palette.Divider))
}

/** 角の丸い四角に入れたアイコン (color で塗り、下地は同じ色をうすく) */
@Composable
internal fun IconTile(icon: ImageVector, color: Color, size: Dp = 38.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.58f))
    }
}

/** 区分の一覧。行ごとに、今の様子を一言 (色の点つき) で出す。押すと、その区分を開く */
@Composable
internal fun SectionList(g: McpGraph, a: SettingsActivity, tick: Int, open: (Section) -> Unit) {
    val sum = sectionSummaries(g, a, tick)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(Section.entries.filter { it != Section.DEV }, listOf(Section.DEV)).forEach { group ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface)) {
                group.forEachIndexed { i, s ->
                    if (i > 0) Divider()
                    val (text, ok) = sum[s] ?: ("" to null)
                    SectionRow(s.icon, s.title, text, ok) { open(s) }
                }
            }
        }
    }
}

@Composable
private fun SectionRow(icon: ImageVector, title: String, summary: String, ok: Boolean?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, Palette.Accent)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, color = Palette.Title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (summary.isNotBlank()) Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(stateColor(ok), size = 7.dp)
                Spacer(Modifier.width(7.dp))
                Text(summary, color = if (ok == null) Palette.Muted else stateColor(ok), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("›", color = Palette.Chevron, fontSize = 22.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

/** 区分ごとの今の様子 (一言と色)。中のカードの見出しの状態と同じ所から読む */
@Composable
private fun sectionSummaries(g: McpGraph, a: SettingsActivity, tick: Int): Map<Section, Pair<String, Boolean?>> {
    val server by g.server.state.collectAsState()
    val tunnel by g.tunnel.state.collectAsState()
    val publicSt by g.publicTunnel.state.collectAsState()
    val adb by g.adb.state.collectAsState()
    val arm by g.arm.status.collectAsState()
    val photo by g.photos.status.collectAsState()
    val entries by g.log.entries.collectAsState()
    val s = g.settings
    val serverOn = remember(tick) { s.serverEnabled }
    val tunnelOn = remember(tick) { s.tunnelEnabled }
    val publicOn = remember(tick) { s.publicTunnelEnabled }
    val mode = remember(tick) { s.controlMode }
    val a11yOn = remember(tick) { A11yService.instance != null }
    val canOverlay = remember(tick) { g.overlay.canShow() }
    val phoneOk = remember(tick) { a.hasPhonePerms() }
    val armMissing = remember(tick) { a.armMissingPerms() }
    val photoPerm = remember(tick) { g.photos.hasPermission() }
    val mic = remember(tick) { a.hasMic() }
    fun onOff(on: Boolean) = if (on) "オン" else "オフ"
    return mapOf(
        Section.CONNECT to when {
            !serverOn -> "MCP サーバーはオフ" to null
            server !is McpServer.State.Running -> server.label to false
            // 外からつなぐ入口 (Claude から使う) がうまくいっていない・それだけを使っているときは、その様子を出す
            publicOn && (publicSt.ok == false || !tunnelOn) -> "オン・Claude から: ${publicSt.label}" to publicSt.ok
            !tunnelOn -> "オン・スマホの中のトンネルはオフ" to true
            tunnel is TunnelClient.State.Ready -> "オン・つながっています" to true
            tunnel is TunnelClient.State.Off || tunnel is TunnelClient.State.Starting -> "オン・トンネル: ${tunnel.label}" to null
            else -> "オン・トンネル: ${tunnel.label}" to false
        },
        Section.OPERATE to when {
            !canOverlay -> "他のアプリの上に表示が未許可" to false
            mode != ControlMode.ADB && !a11yOn -> "${mode.label}・ユーザー補助がオフ" to false
            mode == ControlMode.ADB && adb !is AdbState.Connected -> "${mode.label}・${adb.label}" to false
            else -> mode.label to true
        },
        Section.SAFETY to when {
            s.devNoAgentLimits -> "開発モード: 権限の制限なし" to false
            s.approvalScreen && s.approvalBiometric -> "承認画面のあと、指紋など" to true
            s.approvalScreen -> "承認画面" to true
            s.approvalBiometric -> "指紋など" to true
            else -> "承認なし (そのまま確定します)" to false
        },
        Section.RIGEL to when {
            (s.musicStage || s.voiceStageLevel) && !mic -> "マイクが未許可" to false
            else -> "音声モード ${onOff(s.voiceStage)}・音楽 ${onOff(s.musicStage)}・最初の一言 ${onOff(s.voicePrimer)}" to null
        },
        Section.PHONE to when {
            !phoneOk -> "未許可" to false
            else -> ("着信 " + when (s.rigelIncoming) { "off" -> "出ない"; "voice" -> "声で尋ねる"; else -> "ボタン" } +
                "・録音 " + onOff(s.rigelRecord)) to true
        },
        Section.ARM to when {
            !s.armEnabled -> "使わない" to null
            armMissing.isNotEmpty() -> "許可が足りません (Bluetooth・カメラ)" to false
            else -> arm.link to arm.ready
        },
        Section.PHOTO to when {
            !photoPerm -> "写真が未許可" to false
            !photo.modelReady -> (if (photo.downloading) "モデルを落としています" else "モデルなし") to false
            photo.indexing -> "索引 ${photo.indexed}/${photo.total}" to true
            else -> "索引 ${photo.indexed} 枚" to true
        },
        Section.MONITOR to when {
            !s.monitorStage -> "オフ" to null
            else -> ("オン・操作先は " + if (g.monitorStage.target != android.view.Display.DEFAULT_DISPLAY) "モニター" else "スマホ") to true
        },
        Section.DEV to (if (s.devNoAgentLimits) "開発モード オン・道具の記録 ${entries.size} 件" to false
            else "接続の情報・道具の記録 ${entries.size} 件" to null),
    )
}
