package com.rabi.envoymcp.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.mcp.ActivityLog
import com.rabi.envoymcp.mcp.Agent
import com.rabi.envoymcp.mcp.AgentPolicy
import com.rabi.envoymcp.mcp.Agents
import com.rabi.envoymcp.mcp.ToolGroups
import com.rabi.envoymcp.ui.activity.ActivityActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * エージェントの権限: ENVOY MCP を呼ぶ AI エージェント (合言葉ごと) の一覧と、使ってよい道具。
 * 行を押すと、その下で直せる (名前・使う / 止める・読むだけ・道具のまとまり・期限、足したものは URL と消す)。
 * 足す・権限を広げるときは、ご本人か確かめてから変える (狭める・止める・消すはすぐ)。
 * 確定の前の承認は、ここの権限とは別に、いつもどおりかかる
 */
@Composable
internal fun AgentsCard(g: McpGraph, a: SettingsActivity, tick: Int, say: (String) -> Unit) {
    val agents by g.agents.list.collectAsState()
    val dev = remember(tick) { g.settings.devNoAgentLimits }
    var editing by remember { mutableStateOf<String?>(null) }
    val now = remember(tick, agents) { System.currentTimeMillis() }
    Card(CardTitle.AGENTS, if (dev) "開発モード: 制限なし" else "${agents.size} つ", if (dev) false else null, icon = Icons.Rounded.AdminPanelSettings) {
        if (dev) Warning("開発モードがオンです。止めていないエージェントは、ここの権限に関係なく、すべての道具を使えます (確定の前の承認はそのまま)。「開発・試験」の「開発モード」で切れます。")
        Note("ENVOY MCP を呼ぶ AI エージェントごとに、使ってよい道具を決めます (読むだけにする・使える道具のまとまり・期限・止める)。許していない道具を頼まれたら、動かさずに断り、「したこと」に残します。送信・投稿・購入・削除・発信の前の承認は、ここの設定と関係なく、いつもどおり出ます。")
        Note("エージェントを足すときと、できることを広げるとき (道具を増やす・読むだけをやめる・期限をのばす・止めていたものを使えるようにする) は、指紋・顔・PIN でご本人か確かめてから変えます。狭める・止める・消すは、すぐに変わります。")
        agents.forEach { ag ->
            AgentRow(ag, now, dev, editing == ag.id) { editing = if (editing == ag.id) null else ag.id }
            if (editing == ag.id) AgentEditor(g, a, ag, say) { editing = null }
        }
        Btn("エージェントを足す", true) {
            a.addAgent(say) { n ->
                editing = n.id
                say("エージェントを足しました (${permissionLine(n)})。URL を写して、つなぐエージェントに渡してください")
            }
        }
        Note("足したエージェントは、自分の合言葉の URL (http://127.0.0.1:${g.settings.port}/mcp/<合言葉>) か、Authorization: Bearer <合言葉> でつなぎます。このスマホの中のアプリか、USB でつないだ PC (adb forward tcp:${g.settings.port} tcp:${g.settings.port}) から使えます。")
    }
}

/** エージェントの 1 行: 名前・今使えるか・権限の一行・最後に使った時刻 */
@Composable
private fun AgentRow(ag: Agent, now: Long, dev: Boolean, open: Boolean, onClick: () -> Unit) {
    val (state, ok) = agentState(ag, now, dev)
    val sum = permissionLine(ag)
    val used = if (ag.lastUsedAt > 0) "最後に使った: ${WHEN_FMT.format(Date(ag.lastUsedAt))}" else "まだ使っていません"
    if (!shown(ag.name, state, sum, used)) return
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Palette.Bg).border(1.dp, if (open) Palette.Accent else Palette.Divider, shape)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ag.name + if (ag.main) " (本体)" else "", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Chip(state, ok, Modifier.padding(start = 8.dp).widthIn(max = 160.dp))
            Text(if (open) "▾" else "▸", color = Palette.Chevron, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
        }
        Text(sum, color = Palette.Label, fontSize = 13.sp, lineHeight = 19.sp)
        Text(used, color = Palette.Muted, fontSize = 12.sp)
    }
}

/** 権限の一行:「読むだけ・11/12 9:00 まで」。withNoExpiry なら、期限が無いときも「期限なし」と書く */
internal fun permissionLine(ag: Agent, withNoExpiry: Boolean = false): String = AgentPolicy.summary(ag) + when {
    ag.expiresAt > 0 -> "・${WHEN_FMT.format(Date(ag.expiresAt))} まで"
    withNoExpiry -> "・期限なし"
    else -> ""
}

/** 今使えるか: 使える (緑)・期限切れ (黄)・止めている (灰)。開発モードでは期限を見ない */
private fun agentState(ag: Agent, now: Long, dev: Boolean): Pair<String, Boolean?> = when (AgentPolicy.status(ag, now)) {
    AgentPolicy.Status.OFF -> "止めている" to null
    AgentPolicy.Status.EXPIRED -> if (dev) "使える (開発モード)" to false else "期限切れ" to false
    AgentPolicy.Status.OK -> "使える" to true
}

/** 1 つのエージェントを直す (変えたらすぐ残す。権限を広げるときは、ご本人か確かめてから残す) */
@Composable
private fun AgentEditor(g: McpGraph, a: SettingsActivity, ag: Agent, say: (String) -> Unit, onClose: () -> Unit) {
    var name by remember(ag.id) { mutableStateOf(ag.name) }
    var confirm by remember(ag.id) { mutableStateOf(false) }
    fun save(x: Agent, done: String? = null) = a.updateAgent(x, say, done)
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Palette.Bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("名前") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (name.trim() != ag.name && name.isNotBlank()) Btn("名前を保存") { save(ag.copy(name = name.trim().take(MAX_NAME)), "名前を保存しました") }
        Toggle("使えるようにする (オフにすると、このエージェントからの接続を断ります)", ag.enabled) { save(ag.copy(enabled = it)) }
        Sub("かんたん設定")
        Btns {
            Btn("すべて許可") { save(ag.copy(groups = null, denied = emptySet(), readOnly = false, expiresAt = 0L), "${ag.name}: すべての道具を、期限なしで使えるようにしました") }
            Btn("読むだけ") { save(ag.copy(groups = null, denied = emptySet(), readOnly = true), "${ag.name}: 読むだけにしました") }
        }
        Toggle("読むだけにする (画面・状態を見るだけ。押す・開く・電話などはしない)", ag.readOnly) { save(ag.copy(readOnly = it)) }
        Sub("使える道具")
        // 道具の日本語の名前と、読むだけの道具か (MCP サーバーが返す道具の一覧から)
        val info = remember { toolInfo(g) }
        var openGroups by remember(ag.id) { mutableStateOf(setOf<String>()) }
        ToolGroups.groups.forEach { (group, tools) ->
            val st = AgentPolicy.groupState(ag, group)
            val count = if (st == AgentPolicy.GroupState.PART) "${tools.count { AgentPolicy.toolOn(ag, it) }} / ${tools.size}" else "${tools.size}"
            Toggle("$group ($count)", st != AgentPolicy.GroupState.OFF) { on -> save(AgentPolicy.withGroup(ag, group, on)) }
            val open = group in openGroups
            Text((if (open) "▾ " else "▸ ") + "道具を 1 つずつ選ぶ", color = Palette.Accent, fontSize = 13.sp,
                modifier = Modifier.clickable { openGroups = if (open) openGroups - group else openGroups + group }.padding(start = 4.dp, bottom = 2.dp))
            if (open) Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tools.forEach { t ->
                    val (title, ro) = info[t] ?: (t to false)
                    Toggle("$title ($t)" + if (ag.readOnly && !ro) " ・読むだけの間は使えない" else "", AgentPolicy.toolOn(ag, t)) { on -> save(AgentPolicy.withTool(ag, t, on)) }
                }
            }
        }
        Note("まとまりの中を開くと、道具を 1 つずつ止められます。オフのまとまりの道具を 1 つオンにすると、その道具だけが使えます。どのまとまりにも入らない道具 (その他) は、すべてのまとまりを許したときだけ使えます。道具の中身は「つながり」の「MCP の道具」で見られます。")
        Sub("期限")
        Line("期限", if (ag.expiresAt > 0) "${WHEN_FMT.format(Date(ag.expiresAt))} まで" else "なし")
        Btns {
            Btn("期限なし") { save(ag.copy(expiresAt = 0L)) }
            Btn("1 時間") { save(ag.copy(expiresAt = System.currentTimeMillis() + Agents.HOUR_MS)) }
            Btn("今日だけ") { save(ag.copy(expiresAt = endOfToday())) }
            Btn("1 週間") { save(ag.copy(expiresAt = System.currentTimeMillis() + Agents.WEEK_MS)) }
        }
        if (ag.main) {
            Note("ChatGPT は、設定の合言葉でつながる本体のエージェントです (トンネルから届きます)。消せません。合言葉の URL を写す・作り直すのは「開発・試験」の「開発者向け (接続の情報)」です。")
        } else {
            Sub("つなぎ方")
            Line("URL", "http://127.0.0.1:${g.settings.port}/mcp/••••")
            Note("•••• はこのエージェントの合言葉です (画面には出しません)。写した URL を、つなぐエージェントに渡してください。")
            Btns {
                Btn("URL を写す") { a.copyAgentUrl(ag.id); say("${ag.name} の合言葉の入った URL を写しました") }
                Btn(if (confirm) "もう一度押すと消します" else "このエージェントを消す") {
                    if (confirm) { g.agents.remove(ag.id); onClose(); say("${ag.name} を消しました") } else confirm = true
                }
            }
        }
    }
}

/** 道具の名前 → (日本語の名前, 読むだけの道具か) */
private fun toolInfo(g: McpGraph): Map<String, Pair<String, Boolean>> {
    val list = g.tools.list()
    return (0 until list.length()).associate { i ->
        val t = list.getJSONObject(i)
        t.getString("name") to (t.optString("title") to (t.optJSONObject("annotations")?.optBoolean("readOnlyHint") == true))
    }
}

/** 今日の終わり (この時刻から使えない) */
private fun endOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_YEAR, 1)
}.timeInMillis

/** したことの記録: 件数と、記録の画面を開くボタン */
@Composable
internal fun ActivityLogCard(g: McpGraph, a: SettingsActivity) {
    val entries by g.activity.entries.collectAsState()
    Card("したことの記録", if (entries.isEmpty()) "まだありません" else "${entries.size} 件", icon = Icons.Rounded.Checklist) {
        Note("AI エージェントとリゲルが、このスマホでしたこと (アプリを開いた・押した・電話した・断った など) を、短い文で残しています。30 日・${ActivityLog.MAX} 件まで、このスマホの中だけです。入れた文字・画面の中身・メッセージ・電話番号は残しません。ホームの左下の「したこと」からも開けます。")
        Btn("したことを見る") { a.startActivity(Intent(a, ActivityActivity::class.java)) }
    }
}

/** 開発モード: エージェントの権限の制限をかけない (試すときだけ。オンにするときは、ご本人か確かめる) */
@Composable
internal fun DevModeCard(g: McpGraph, a: SettingsActivity, tick: Int, say: (String) -> Unit) {
    val dev = remember(tick) { g.settings.devNoAgentLimits }
    Card(CardTitle.DEV_MODE, if (dev) "オン: 権限の制限なし" else "オフ", if (dev) false else null, icon = Icons.Rounded.DeveloperMode) {
        Toggle("開発モード: エージェントの権限の制限をかけない", dev) { a.setDevNoAgentLimits(it, say) }
        if (dev) Warning("開発モードがオンです。止めていないエージェントは、すべての道具を使えます。")
        Note("オンの間は、止めていないエージェントなら、「安心 (承認)」の「エージェントの権限」(読むだけ・道具のまとまり・期限) に関係なく、すべての道具を使えます。合言葉の違う接続と、止めているエージェントは、オンでも断ります。送信・投稿・購入・削除・発信などの前の承認 (承認画面・指紋など) は、オンでもそのまま出ます。試すときだけオンにしてください。オンにするときは、指紋・顔・PIN でご本人か確かめます。")
    }
}

/** 黄色の注意の帯 */
@Composable
private fun Warning(s: String) {
    if (!shown(s)) return
    Text(s, color = Palette.Warn, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Warn.copy(alpha = 0.12f)).padding(10.dp))
}

private val WHEN_FMT get() = SimpleDateFormat("M/d H:mm", Locale.JAPAN)
private const val MAX_NAME = 30
