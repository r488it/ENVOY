package com.rabi.envoymcp.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.PrecisionManufacturing
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.arm.ArmController
import com.rabi.envoymcp.arm.ArmResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 腕に頼んで、結果を一言で出す */
private fun CoroutineScope.armGo(say: (String) -> Unit, block: suspend () -> ArmResult) { launch { say("AgentARM: " + block().message) } }

/** AgentARM の区分: 腕・呼び名・踊り・試す・ボードの Wi-Fi (呼び名・踊り・試すは、腕を使うときだけ出す) */
@Composable
internal fun ArmCards(g: McpGraph, a: SettingsActivity, tick: Int, say: (String) -> Unit) {
    val on = remember(tick) { g.settings.armEnabled }
    ArmCard(g, a, tick, say)
    if (on) {
        WakeCard(g)
        DanceCard(g, a, tick)
        ArmTryCard(g, say)
    }
    BoardWifiCard(g, tick)
}

/** AgentARM (スマホを載せる腕): オン・オフ、許可、つながり、腕の様子、録画、記録 */
@Composable
private fun ArmCard(g: McpGraph, a: SettingsActivity, tick: Int, say: (String) -> Unit) {
    val st by g.arm.status.collectAsState()
    val armLog by g.arm.log.lines.collectAsState()
    var showArmLog by remember { mutableStateOf(false) }
    val on = remember(tick) { g.settings.armEnabled }
    val missing = remember(tick) { a.armMissingPerms() }
    val serverOn = remember(tick) { g.settings.serverEnabled }
    val scope = rememberCoroutineScope()
    Card(CardTitle.ARM, if (!on) "使わない" else st.link, if (on) st.ready else null, icon = Icons.Rounded.PrecisionManufacturing) {
        Note("ChatGPT から、スマホを縦・横にする、こっちを向く、顔を追い続ける (動画を見る間など)、追うのをやめる、を頼めます。マグネット充電器に付けるとまっすぐ (縦) に直しながら顔に向け、外すと腕を元の姿勢に戻します。")
        Toggle("AgentARM を使う", on) { a.setArm(it) }
        if (on) {
            if (missing.isNotEmpty()) {
                Line("許可", "足りません (Bluetooth・カメラ)", false)
                Btn("許可する", true) { a.requestArmPerms() }
            }
            if (!serverOn) Line("動かす条件", "MCP サーバーが動いている間だけ動きます", false)
            Line("つながり", st.link, st.ready)
            if (st.ready) {
                Line("腕", st.mode.ifBlank { "—" } + when (st.calibrated) { false -> " (較正がまだ)"; else -> "" }, if (st.mode == "fault") false else null)
                Line("顔の追跡", if (st.following) "追っています" + (st.face?.let { (h, v) -> " (ずれ 横 ${h}° 縦 ${v}°)" } ?: " (顔が見えません)") else "していません", if (st.following) true else null)
            }
            if (st.ready && st.version in 1..1) Line("腕のプログラム", "古い版です (動き・踊りにはボードのプログラムの更新が要ります)", false)
            // 撮りながら顔を追う (前面カメラ。腕につながっていなければ撮るだけ)。撮り始めるボタンは「試す」にある
            Line("録画", if (st.recording) "撮っています (${if (st.recordingBack) "背面" else "前面"}。Movies/ENVOY に保存)" else "していません", if (st.recording) true else null)
            if (st.recording) Btn("録画を止める", true) { scope.armGo(say) { g.arm.recordStop() } }
            if (st.last.isNotBlank()) Line("最後", st.last)
            if (armLog.isNotEmpty()) {
                Btn(if (showArmLog) "記録を閉じる" else "腕の記録を見る (${armLog.size} 行)") { showArmLog = !showArmLog }
                if (showArmLog) {
                    Note("新しい順。拍の取れ方 (テンポ・確かさ・音の強さ) と、腕に頼んだことの結果です。")
                    armLog.take(40).forEach { Mono(it) }
                }
            }
            Note("顔を探す・追う間は前面カメラを使います (緑の点が出ます。映像は端末の外に出しません)。")
        }
    }
}

/** 試す (ふだんは閉じておく): 腕を縦・横・こっちに向ける、顔を追う、撮りながら追う、拍を聞く、踊る */
@Composable
private fun ArmTryCard(g: McpGraph, say: (String) -> Unit) {
    val st by g.arm.status.collectAsState()
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf("") }
    fun go(block: suspend () -> ArmResult) = scope.armGo(say, block)
    Card("試す", folded = true, icon = Icons.Rounded.Science) {
        if (st.ready) {
            Sub("向き・顔の追跡")
            Btns {
                Btn("縦") { go { g.arm.orient(ArmController.PORTRAIT) } }
                Btn("横") { go { g.arm.orient(ArmController.LANDSCAPE) } }
                Btn("こっち") { go { g.arm.lookAtMe(null) } }
            }
            Btns {
                Btn("横で追う") { go { g.arm.follow(ArmController.LANDSCAPE) } }
                Btn("追うのをやめる") { go { g.arm.stopFollowingByTool() } }
            }
        }
        Sub("録画")
        if (!st.recording) Btns {
            Btn("撮りながら追う") { go { g.arm.recordStart(follow = true, maxMin = ArmController.RECORD_DEFAULT_MIN) } }
            Btn("カメラマン (背面)") { go { g.arm.recordStart(follow = true, maxMin = ArmController.RECORD_DEFAULT_MIN, back = true) } }
        }
        Note("カメラマン (背面) は、背面カメラで撮り、腕が背面カメラを人に向けて追います。画面は反対を向きます。背面カメラは水平より約 9° 上までしか向けられないので、スマホと同じ高さか低い所に立つ・座ると合います。")
        Sub("拍・踊り")
        Btns {
            Btn("拍を聞いてみる") { said = g.arm.listenTest() }
            if (st.ready) {
                if (st.dancing) Btn("やめる") { go { g.arm.stopByTool() } }
                else Btn("踊る") { go { g.arm.dance(null, null) } }
            }
        }
        if (said.isNotBlank()) Note(said)
    }
}

/** 呼び名 (「ねえ、リゲル」) で呼ぶ: 起きたときの表情・しきい値 */
@Composable
private fun WakeCard(g: McpGraph) {
    var greet by remember { mutableStateOf(g.settings.wakeGreet) }
    var th by remember { mutableFloatStateOf(g.settings.wakeThreshold) }
    Card("呼び名で呼ぶ", "しきい値 ${"%.2f".format(th)}", icon = Icons.Rounded.Hearing) {
        Toggle("呼び名で起きたら、表情を見せてこちらを向く", greet) { g.settings.wakeGreet = it; greet = it }
        Note("「ねえ、リゲル」と呼ばれたら、ChatGPT の音声モードが開くまでの間に、リゲルがランダムな表情 (うれしい・笑う・驚き・照れ・ウインク) を画面と腕の動きで見せてから、前面カメラで顔を探してこちらを向きます。")
        if (shown("呼び名のしきい値")) {
            Text("呼び名のしきい値: ${"%.2f".format(th)}", color = Color.White, fontSize = 15.sp)
            Slider(value = th, onValueChange = { th = (it * 100).roundToInt() / 100f }, valueRange = 0.10f..0.90f, steps = 79,
                onValueChangeFinished = { g.settings.wakeThreshold = th; g.arm.wakeSettingsChanged() })
        }
        Note("ボードが「本人の『ねえ、リゲル』」とみなす声の近さです (最初は 0.58)。下げると反応しやすくなり、上げるとほかの人の声や似た言葉に反応しにくくなります。変えるとすぐボードに送ります (腕につながっているとき)。")
    }
}

/** AgentARM の踊り: 音楽が流れたら踊る、マイクの許可、拍が取れているか、曲名 */
@Composable
private fun DanceCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val st by g.arm.status.collectAsState()
    val auto = remember(tick) { g.settings.armAutoDance }
    val mic = remember(tick) { a.hasMic() }
    val ms by g.arm.music.status.collectAsState()
    val track by g.nowPlaying.track.collectAsState()
    val access = remember(tick) { g.nowPlaying.hasAccess() }
    Card("音楽に合わせて踊る", when { !auto -> "オフ"; !mic -> "マイクが未許可"; else -> "オン" }, when { !auto -> null; else -> mic }, icon = Icons.Rounded.Celebration) {
        Toggle("音楽が流れたら踊る", auto) { a.setAutoDance(it) }
        Note("スマホで音楽 (YouTube Music・Spotify など) が流れ始めたら、腕が拍に合わせて踊ります。スマホが腕に付いていて (ワイヤレス充電中)、顔を追っていない・音声の会話をしていないときだけ。動画では踊りません。ChatGPT に「踊って」と頼むこともできます。")
        Line("マイクの許可", if (mic) "許可" else "未許可 (拍を取るのに要ります)", mic)
        if (!mic) Btn("許可する", true) { a.requestMic() }
        if (auto) Line("音楽", if (st.musicPlaying) "流れています" else "流れていません", if (st.musicPlaying) true else null)
        if (ms.listening || ms.note.isNotBlank()) {
            Line("拍", (ms.bpm?.let { "テンポ ${it.toInt()}" } ?: "—") + "  確かさ ${(ms.confidence * 100).toInt()}%  音 ${(ms.energy * 100).toInt()}%", if (ms.bpm != null) true else null)
            if (ms.note.isNotBlank()) Note(ms.note)
        }
        Line("曲名", if (!access) "未許可 (通知へのアクセス)" else track?.let { (if (it.playing) "▶ " else "■ ") + it.label + " (" + it.app + ")" } ?: "再生中の曲なし", if (access) null else false)
        if (!access) Btn("通知へのアクセスを開く", true) { a.openNotificationAccess() }
        Note("曲名が分かると、曲ごとの専用の振り付けで踊り、「この曲は party で」と頼むとその曲の踊り方を覚えます (覚えた曲 ${g.arm.songDances.count()} 曲)。通知へのアクセスは、再生中の曲を読むためだけに使います (通知の中身は読みません)。オンにできないときは、アプリ情報の右上の︙から「制限付き設定を許可」を選んでから、もう一度オンにしてください。")
        Note("拍は端末の出力の音から取ります (マイクの音は使いません。Android では出力の音を読むのにもマイクの許可が要ります)。速さの上限 (1 秒に約 28°) のため、速い曲ほど小さく揺れます。")
    }
}

/**
 * ボードの Wi-Fi (外に持ち出すとき、設定の画面から登録できるように)。腕との Bluetooth を通して、今のつながり・
 * 登録してあるもの・近くのものを見て、登録・接続・削除する。パスワードは暗号化して送り (WifiCrypto)、スマホには残さない
 */
@Composable
internal fun BoardWifiCard(g: McpGraph, tick: Int) {
    val arm = g.arm
    val st by arm.status.collectAsState()
    val scope = rememberCoroutineScope()
    var cur by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var saved by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var near by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var busy by remember { mutableStateOf("") }
    var said by remember { mutableStateOf("") }
    var ssid by remember { mutableStateOf("") }
    var sec by remember { mutableStateOf("wpa") }
    var psk by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val why = remember(st.ready, st.version, tick) { arm.wifi.check() }
    fun refresh() { scope.launch { busy = "読んでいます…"; cur = arm.wifi.status(); saved = arm.wifi.saved() ?: emptyList(); busy = "" } }
    LaunchedEffect(st.ready, tick) { if (arm.wifi.check() == null) refresh() }
    val now = cur?.optString("ssid").orEmpty()
    Card("ボードの Wi-Fi", when { why != null -> "腕につながっていない"; cur == null -> ""; now.isBlank() -> "つながっていない"; else -> now },
        when { why != null -> null; cur == null -> null; else -> now.isNotBlank() }, icon = Icons.Rounded.Wifi) {
        Note("腕のボードの Wi-Fi を、腕との Bluetooth を通して見る・登録します。外に持ち出すときは、Pixel のテザリングを登録しておくと、家の Wi-Fi が無い所では自動でつながります (電話の代役など、Wi-Fi を使う機能が外でも動きます。腕の動きと呼び名は Bluetooth なので Wi-Fi が無くても動きます)。パスワードは暗号化して送り、ボードの中だけに保存します。")
        if (why != null) { Line("使えません", why, false); return@Card }
        Line("今", cur?.let { c -> if (c.optString("ssid").isBlank()) "つながっていません" else "${c.optString("ssid")}  ${c.optString("ip")}  (強さ ${c.optInt("sig")})" } ?: "—",
            cur?.let { it.optString("ssid").isNotBlank() })
        Btn("読み直す") { refresh() }
        Sub("登録してあるネットワーク")
        if (saved.isEmpty()) Note("ありません (読み直すと出ます)")
        saved.forEach { s ->
            val name = s.optString("name")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.optString("ssid").ifBlank { name } + if (name == now) "  (つながっている)" else "", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (name != now) TextButton(onClick = { scope.launch { busy = "つないでいます…"; said = arm.wifi.connect(name).second; busy = ""; refresh() } }) { Text("つなぐ") }
                TextButton(onClick = {
                    if (confirm != name) confirm = name
                    else scope.launch { confirm = ""; busy = "消しています…"; said = arm.wifi.delete(name).second; busy = ""; refresh() }
                }) { Text(if (confirm == name) "もう一度で消す" else "消す", color = Color(0xFFFFB4A8)) }
            }
        }
        Sub("登録する")
        Btn("近くのネットワークを探す") {
            scope.launch { busy = "探しています…"; near = arm.wifi.scan() ?: emptyList(); busy = ""; said = if (near.isEmpty()) "見つかりませんでした" else "" }
        }
        near.forEach { n ->
            Text("${n.optString("ssid")}   強さ ${n.optInt("sig")}   ${if (n.optString("sec") == "open") "鍵なし" else "鍵あり"}", color = Color(0xFFCFE3FF), fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clickable { ssid = n.optString("ssid"); sec = n.optString("sec", "wpa") }.padding(vertical = 6.dp))
        }
        OutlinedTextField(ssid, { ssid = it }, label = { Text("ネットワーク名 (SSID。上の一覧を押すと入ります)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        listOf("wpa" to "パスワードあり (WPA2 など)", "sae" to "パスワードあり (WPA3 だけ)", "open" to "パスワードなし").forEach { (v, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = sec == v, onClick = { sec = v })
                Text(label, color = Color.White, fontSize = 14.sp)
            }
        }
        if (sec != "open") OutlinedTextField(psk, { psk = it }, label = { Text("パスワード") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
        Btn("登録する", true) {
            val name = ssid.trim()
            when {
                name.isEmpty() -> said = "ネットワーク名を入れてください"
                name.toByteArray().size > 32 -> said = "ネットワーク名が長すぎます"
                sec != "open" && psk.length !in 8..63 -> said = "パスワードは 8〜63 文字です"
                else -> scope.launch {
                    busy = "登録しています…"
                    val (ok, m) = arm.wifi.add(name, sec, psk)
                    said = m; busy = ""
                    if (ok) { psk = ""; refresh() }
                }
            }
        }
        if (busy.isNotBlank()) Note(busy)
        if (said.isNotBlank()) Note(said)
        Note("「Wi-Fi を変える許可がありません」と出たときだけ、ボードで一度 sudo sh ~/agentarm/linux/tools/wifi_permission.sh を動かしてください (今のボードは、ユーザーが netdev のグループに入っているので要りません)。")
    }
}
