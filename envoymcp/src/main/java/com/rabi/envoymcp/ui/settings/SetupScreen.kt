package com.rabi.envoymcp.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.VoiceAgent
import com.rabi.envoymcp.mcp.AgentPolicy
import com.rabi.envoymcp.mcp.NgrokSetup
import com.rabi.envoymcp.mcp.OAuthServer

/** かんたんセットアップの画面の上の帯に出す名前 */
internal const val SETUP_BAR_TITLE = "かんたんセットアップ"

/**
 * かんたんセットアップの手順の様子を決めるための、今の様子を読む (準備の状態と同じ所から。tick が変わったら読み直す。
 * 設定の画面に戻ってきたとき (onResume) にも tick が変わるので、Android の設定で許可して戻ると、すぐ様子が変わる)
 */
@Composable
internal fun setupFacts(g: McpGraph, a: SettingsActivity, tick: Int, r: ReadyNow = readyNow(g, a, tick)): SetupFacts {
    val agents by g.agents.list.collectAsState()
    val home = remember(tick) { a.isHome() }
    val lock = remember(tick) { a.hasScreenLock() }
    val ai = remember(tick) { g.settings.voiceAgent }
    val installed = remember(tick, ai) { a.aiInstalled(ai) }
    val now = remember(tick, agents) { System.currentTimeMillis() }
    val claude = ai == VoiceAgent.CLAUDE
    // 話し相手の AI のエージェントが、今使えて、一度でもこのスマホにつながったか
    // (ChatGPT は本体のエージェント、Claude はコネクターをつないだときに足されるエージェント)
    val used = agents.any { ag ->
        (if (claude) !ag.main && ag.name == OAuthServer.AGENT_NAME else ag.main) &&
            ag.lastUsedAt > 0 && AgentPolicy.status(ag, now, r.dev) == AgentPolicy.Status.OK
    }
    // 入口: ChatGPT はスマホの中の tunnel-client (動かしていなければ分からない)、Claude は外からつなぐ入口 (オフなら届かない)
    val link = if (claude) (if (r.publicOn) r.publicSt.ok else false) else r.tunnelOk
    return SetupFacts(
        home = home, accessibility = r.a11yOn, overlay = r.canOverlay, notifications = r.notif, phone = r.phone, screenLock = lock,
        serverOn = r.serverOn, serverRunning = r.serverRunning, ai = ai, aiInstalled = installed, aiLink = link, aiUsed = used,
        armOn = r.armOn, armPermissions = r.armMissing.isEmpty(), armReady = r.arm.ready,
    )
}

/**
 * 設定の一覧のいちばん上に置く、かんたんセットアップの入口。
 * 渡す前に済ませる手順がそろっていない間は大きく出し、そろったら小さく「セットアップ済み」と出す (押すと、もう一度開ける)。
 * at は開く手順 (null なら、まだできていない最初の手順。そろっていれば終わりの画面)。押すと open に手順の番号を渡す
 */
@Composable
internal fun SetupEntry(g: McpGraph, a: SettingsActivity, tick: Int, skipped: Set<SetupStep>, at: SetupStep? = null, open: (Int) -> Unit) {
    val states = SetupGuide.states(setupFacts(g, a, tick))
    val ready = SetupGuide.ready(states)
    val (done, total) = SetupGuide.progress(states)
    val start = at ?: SetupGuide.start(states, skipped)
    val go = { open(start?.ordinal ?: SetupGuide.steps.size) }
    val shape = RoundedCornerShape(16.dp)
    if (ready) {
        Row(Modifier.fillMaxWidth().clip(shape).background(Palette.Surface).clickable(role = Role.Button, onClick = go)
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.CheckCircle, Palette.Ok, size = 34.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                Text("セットアップ済み", color = Color.White, fontSize = 15.sp)
                Text(SetupGuide.TITLE, color = Palette.Muted, fontSize = 12.sp)
            }
            Text("もう一度見る ›", color = Palette.Accent, fontSize = 14.sp)
        }
        return
    }
    Column(Modifier.fillMaxWidth().clip(shape).background(Palette.Primary.copy(alpha = 0.10f)).border(2.dp, Palette.Primary, shape)
        .clickable(role = Role.Button, onClick = go).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Checklist, Palette.Primary, size = 42.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(SetupGuide.TITLE, color = Palette.Title, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
                Text(SetupGuide.LEAD, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
        Chip("$total つのうち $done つ できています", false)
        Button(onClick = go, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (done == 0) "はじめる" else "つづきから", fontSize = 16.sp)
        }
    }
}

/**
 * かんたんセットアップの画面: 手順を 1 つずつ出す (at は手順の番号。手順の数と同じなら終わりの画面)。
 * 下の帯に「もどる」と「次へ」(任意の手順で、まだなら「あとで」)。go は手順の番号を変える、skip は「あとで」にした手順を覚える、
 * jump は設定の区分のカードを開く (戻ると、この画面に戻る)、close はかんたんセットアップを閉じる
 */
@Composable
internal fun SetupWizard(g: McpGraph, a: SettingsActivity, tick: Int, at: Int, skipped: Set<SetupStep>, modifier: Modifier,
                         go: (Int) -> Unit, skip: (SetupStep) -> Unit, jump: (Section, String) -> Unit, close: () -> Unit, say: (String) -> Unit) {
    val r = readyNow(g, a, tick)
    val facts = setupFacts(g, a, tick, r)
    val states = SetupGuide.states(facts)
    val step = SetupGuide.steps.getOrNull(at)
    val scroll = remember(at) { ScrollState(0) }
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StepBar(states, at)
            if (step != null) StepPage(g, a, tick, r, facts, step, states.getValue(step), at, jump, say)
            else DonePage(states, skipped) { go(it.ordinal) }
        }
        NavBar(step, step?.let { states.getValue(it) }, at, go, skip, close)
    }
}

/** 手順の並びを細い帯で見せる (できた手順は緑、今の手順は水色) */
@Composable
private fun StepBar(states: Map<SetupStep, StepState>, at: Int) {
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SetupGuide.steps.forEachIndexed { i, s ->
            val c = when {
                i == at -> Palette.Accent
                states[s] == StepState.DONE -> Palette.Ok
                else -> Palette.Divider
            }
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(50)).background(c))
        }
    }
}

/** 1 つの手順: 番号・名前・なぜ要るか・今の様子・直す所を開くボタン */
@Composable
private fun StepPage(g: McpGraph, a: SettingsActivity, tick: Int, r: ReadyNow, f: SetupFacts, step: SetupStep, st: StepState, at: Int,
                     jump: (Section, String) -> Unit, say: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${at + 1} / ${SetupGuide.steps.size}", color = Palette.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (step.optional) Chip("任意", null)
        if (step.family) Chip("家族が確かめる", null)
    }
    Text(step.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
    Text(step.why, color = Palette.Label, fontSize = 15.sp, lineHeight = 22.sp)
    StatusBox(st, detail(step, r, f))
    val done = st == StepState.DONE
    when (step) {
        SetupStep.HOME -> {
            BigBtn("ホームアプリの設定を開く", done) { a.openHomeSettings() }
            Note("一覧から「ENVOY MCP」を選んでください。選ぶと、ホームの画面に移ることがあります。そのときは、ホームの下の「設定」を押して、「かんたんセットアップ」の「つづきから」を押してください。")
        }
        SetupStep.ACCESSIBILITY -> {
            BigBtn("ユーザー補助の設定を開く", done) { a.openAccessibilitySettings() }
            Btn("アプリ情報を開く") { a.openAppDetails() }
            Note("ユーザー補助の設定で「ENVOY MCP (端末の操作)」をオンにします。オンにできないときは、アプリ情報の右上の︙から「制限付き設定を許可」を選んでから、もう一度オンにしてください。")
        }
        SetupStep.OVERLAY -> {
            BigBtn("許可の画面を開く", done) { a.openOverlayPermission() }
            Note("「ENVOY MCP」の「他のアプリの上に表示できるようにする」をオンにして、戻ってください。")
        }
        SetupStep.NOTIFICATIONS -> {
            BigBtn("通知へのアクセスの設定を開く", done) { a.openNotificationAccess() }
            Note("一覧の「ENVOY MCP」を押して、オンにしてください。確かめの画面が出たら「許可」を押します。")
        }
        SetupStep.PERMISSIONS -> {
            BigBtn("許可する", done) { a.requestPhonePerms() }
            Btn("アプリ情報を開く") { a.openAppDetails() }
            Note("出てきた画面で、連絡先と電話の両方を「許可」してください。画面が出ないときは、アプリ情報の「権限」から許可できます。連絡先の中身は AI に渡しません。")
        }
        SetupStep.SCREEN_LOCK -> {
            BigBtn("セキュリティの設定を開く", done) { a.openSecuritySettings() }
            Note("「画面ロック」で PIN を決めてください。指紋や顔も登録しておくと、承認がかんたんになります。")
        }
        SetupStep.SERVER -> {
            if (!f.serverOn) BigBtn("MCP サーバーをオンにする", done) { a.setServer(true) }
            else BigBtn("MCP サーバーの設定を開く", done) { jump(Section.CONNECT, CardTitle.SERVER) }
            Note("オンにすると、通知を出してよいか聞かれることがあります。「許可」を押してください。")
        }
        SetupStep.CHOOSE_AI -> ChooseAi(a, f, say)
        SetupStep.CONNECT_AI -> ConnectAi(g, a, f, done, tick, jump, say)
        SetupStep.ARM -> {
            when {
                !f.armOn -> BigBtn("AgentARM を使う", done) { a.setArm(true) }
                !f.armPermissions -> BigBtn("許可する", done) { a.requestArmPerms() }
                else -> BigBtn("AgentARM の設定を開く", done) { jump(Section.ARM, CardTitle.ARM) }
            }
            if (!f.armOn || !f.armPermissions) Btn("AgentARM の設定を開く") { jump(Section.ARM, CardTitle.ARM) }
            Note("腕の電源を入れて、スマホの近くに置いてください。Bluetooth とカメラの許可を聞かれたら「許可」を押します。つながると「できています」になります。")
        }
    }
}

/** 話し相手の AI を選ぶ: ChatGPT か Claude。選んだアプリが入っていなければ、Play ストアのページを開くボタンを出す */
@Composable
private fun ChooseAi(a: SettingsActivity, f: SetupFacts, say: (String) -> Unit) {
    listOf(
        VoiceAgent.CHATGPT to "ChatGPT の音声モードでお話しします",
        VoiceAgent.CLAUDE to "Claude の音声モードでお話しします。Claude は、話しかけるまで待っています",
    ).forEach { (v, desc) -> Choice(v.label, desc, f.ai == v) { a.setVoiceAgent(v) } }
    Line("アプリ", if (f.aiInstalled) "入っています" else "入っていません", f.aiInstalled)
    if (!f.aiInstalled) BigBtn("Play ストアで入れる", false) { if (!a.openStore(f.ai)) say("Play ストアを開けませんでした") }
    Note("入れたら、そのアプリを一度開いて、使う方のアカウントでログインしておいてください。")
}

/**
 * AI とつなぐ: 選んだ AI に ENVOY MCP を登録する手順をまとめ、くわしい設定のあるカードを開く。
 * 登録は AI の会社のページでするので、AI から一度つながるまでは、家族の方が確かめる
 */
@Composable
private fun ConnectAi(g: McpGraph, a: SettingsActivity, f: SetupFacts, done: Boolean, tick: Int, jump: (Section, String) -> Unit, say: (String) -> Unit) {
    val tryIt = "ホームのリゲルを押して「スマホの電池はどれくらい？」と聞いてみてください。答えが返ってきたら、つながっています。この画面に戻ると「できています」になります。"
    if (f.ai == VoiceAgent.CLAUDE) {
        BigBtn("「Claude から使う」の設定を開く", done) { jump(Section.CONNECT, CardTitle.PUBLIC) }
        Steps(
            "「Claude から使う（外からつなぐ）」で、ngrok の認証トークンとドメインを保存し、「外からつなぐ」をオンにします（指紋などで、ご本人か確かめます）",
            "claude.ai を開き、設定 › コネクター › カスタムコネクターを追加 で、下の URL を貼り付けます",
            "「接続」を押すと、このスマホに承認の画面が出ます。指紋などで承認します",
            tryIt,
        )
        val domain = remember(tick) { g.settings.publicDomain }
        val url = NgrokSetup.connectorUrl(domain)
        Line("URL", url ?: "ドメインを保存すると出ます")
        if (url != null) Btn("URL をコピー") { a.copyPlain(url); say("URL をコピーしました: $url") }
        Note("つないだ Claude は、はじめは見るだけで、1 週間で使えなくなります。操作もさせたいとき・ずっと使うときは、「エージェントの権限」で「${OAuthServer.AGENT_NAME}」の範囲と期限を広げてください（指紋などで、ご本人か確かめます）。")
        Btn("エージェントの権限を開く") { jump(Section.SAFETY, CardTitle.AGENTS) }
    } else {
        BigBtn("トンネルの設定を開く", done) { jump(Section.CONNECT, CardTitle.TUNNEL) }
        Steps(
            "「トンネル」で tunnel_id と実行用キーを保存し、「スマホの中で動かす」をオンにします（Mac などで tunnel-client を動かしているときは要りません）",
            "ChatGPT に、ENVOY MCP のコネクターを登録します",
            tryIt,
        )
        Btn("ChatGPT の設定を開く") { jump(Section.CONNECT, CardTitle.CHATGPT) }
    }
}

/** 番号つきの手順の文 */
@Composable
private fun Steps(vararg lines: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEachIndexed { i, s ->
            Row {
                Text("${i + 1}.", color = Palette.Accent, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.width(22.dp))
                Text(s, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 今の様子の一言 (できている・まだの理由) */
private fun detail(step: SetupStep, r: ReadyNow, f: SetupFacts): String = when (step) {
    SetupStep.HOME -> if (f.home) "ENVOY MCP がホームアプリです" else "ほかのアプリがホームアプリです"
    SetupStep.ACCESSIBILITY -> if (f.accessibility) "オンです" else "オフです"
    SetupStep.OVERLAY -> if (f.overlay) "許可しています" else "まだ許可していません"
    SetupStep.NOTIFICATIONS -> if (f.notifications) "許可しています" else "まだ許可していません"
    SetupStep.PERMISSIONS -> if (f.phone) "連絡先を読む・電話をかける: 許可しています" else "まだ許可していません"
    SetupStep.SCREEN_LOCK -> if (f.screenLock) "画面ロックがあります" else "画面ロック（指紋・PIN など）がありません"
    SetupStep.SERVER -> when { !f.serverOn -> "オフです"; f.serverRunning -> "動いています"; else -> r.server.label }
    SetupStep.CHOOSE_AI -> "${f.ai.label}を選んでいます。" + if (f.aiInstalled) "このスマホに入っています" else "このスマホに入っていません"
    SetupStep.CONNECT_AI -> {
        val name = if (f.ai == VoiceAgent.CLAUDE) "Claude" else "ChatGPT"
        when {
            !(f.serverOn && f.serverRunning) -> "先に「MCP サーバーをオンにする」を済ませてください"
            f.ai == VoiceAgent.CLAUDE && !r.publicOn -> "「外からつなぐ」がオフです"
            f.aiLink == false -> if (f.ai == VoiceAgent.CLAUDE) r.publicSt.label else r.tunnel.label
            f.aiUsed -> "$name から、このスマホにつながりました"
            else -> "まだ $name からつながっていません。登録したら、使ってみて確かめてください"
        }
    }
    SetupStep.ARM -> when { !f.armOn -> "使っていません"; !f.armPermissions -> "許可が足りません（Bluetooth・カメラ）"; else -> r.arm.link }
}

/** 今の様子を大きく出す (できたら緑に変わり、「次へ」を案内する。読み上げは変わったときに知らせる) */
@Composable
private fun StatusBox(st: StepState, detail: String) {
    val color = when (st) { StepState.DONE -> Palette.Ok; StepState.NOT_YET -> Palette.Warn; StepState.FAMILY -> Palette.Accent }
    val bg by animateColorAsState(color.copy(alpha = 0.14f), label = "setup-state")
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, color.copy(alpha = 0.5f), shape).padding(14.dp)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        val icon = when (st) { StepState.DONE -> Icons.Rounded.CheckCircle; StepState.NOT_YET -> Icons.Rounded.ErrorOutline; StepState.FAMILY -> Icons.Rounded.Groups }
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(34.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(st.label, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
            if (st == StepState.DONE) Text("できました。「次へ」を押して進んでください", color = Palette.Ok, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 手順の大きなボタン (できている手順では、目立たない形にして「次へ」を目立たせる) */
@Composable
private fun BigBtn(text: String, done: Boolean, onClick: () -> Unit) {
    val m = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    if (done) OutlinedButton(onClick = onClick, modifier = m) { Text(text, color = Color.White, fontSize = 16.sp) }
    else Button(onClick = onClick, modifier = m) { Text(text, fontSize = 16.sp) }
}

/** 終わりの画面: そろったら「スマホを渡してください」。手順ごとの様子 (押すとその手順へ) と、渡すときに伝えること */
@Composable
private fun DonePage(states: Map<SetupStep, StepState>, skipped: Set<SetupStep>, goTo: (SetupStep) -> Unit) {
    val ready = SetupGuide.ready(states)
    IconTile(if (ready) Icons.Rounded.Celebration else Icons.Rounded.Checklist, if (ready) Palette.Ok else Palette.Warn, size = 56.dp)
    Text(if (ready) SetupGuide.DONE_TITLE else "まだの所があります", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
    Text(if (ready) "渡す前に済ませる設定は、そろいました。" else "スマホを渡す前に、まだの手順を済ませてください。下の行を押すと、その手順を開きます。",
        color = Palette.Label, fontSize = 15.sp, lineHeight = 22.sp)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface)) {
        SetupGuide.steps.forEachIndexed { i, s ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Palette.Divider))
            val st = states.getValue(s)
            val (label, ok) = when {
                st == StepState.DONE -> st.label to true
                s.optional && s in skipped -> "あとで" to null
                s.optional -> st.label to null
                st == StepState.FAMILY -> "家族が確かめる" to false
                else -> st.label to false
            }
            Row(Modifier.fillMaxWidth().clickable { goTo(s) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.width(24.dp))
                Text(s.title, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
                Chip(label, ok)
            }
        }
    }
    if (!ready) Button(onClick = { SetupGuide.nextNotDone(states, null, skipped)?.let(goTo) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text("まだの所へ", fontSize = 16.sp)
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Primary.copy(alpha = 0.10f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("使う方に伝えること", color = Palette.Title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(SetupGuide.HINT, color = Color.White, fontSize = 16.sp, lineHeight = 24.sp)
    }
}

/** 下の帯: 「もどる」(最初の手順では閉じる) と、「次へ」(任意の手順でまだなら「あとで」。終わりの画面では「おわる」) */
@Composable
private fun NavBar(step: SetupStep?, st: StepState?, at: Int, go: (Int) -> Unit, skip: (SetupStep) -> Unit, close: () -> Unit) {
    val m = Modifier.heightIn(min = 48.dp)
    Row(Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { if (at > 0) go(at - 1) else close() }, modifier = m) { Text("もどる", color = Color.White, fontSize = 16.sp) }
        Spacer(Modifier.weight(1f))
        when {
            step == null -> Button(onClick = close, modifier = m) { Text("おわる", fontSize = 16.sp) }
            step.optional && st != StepState.DONE -> OutlinedButton(onClick = { skip(step); go(at + 1) }, modifier = m) {
                Text("あとで", color = Color.White, fontSize = 16.sp)
            }
            st == StepState.DONE -> Button(onClick = { go(at + 1) }, modifier = m) { Text("次へ", fontSize = 16.sp) }
            else -> OutlinedButton(onClick = { go(at + 1) }, modifier = m) { Text("次へ", color = Color.White, fontSize = 16.sp) }
        }
    }
}
