package com.rabi.envoymcp.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.ControlMode
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.a11y.A11yService

@Composable
internal fun OperateCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val canOverlay = remember(tick) { g.overlay.canShow() }
    val shots = remember(tick) { g.settings.allowScreenshots }
    val bio = remember(tick) { g.settings.approvalBiometric }
    val approvalScreen = remember(tick) { g.settings.approvalScreen }
    val musicStage = remember(tick) { g.settings.musicStage }
    val musicFlash = remember(tick) { g.settings.musicFlash }
    val musicLyrics = remember(tick) { g.settings.musicLyrics }
    val stage = remember(tick) { g.settings.voiceStage }
    val level = remember(tick) { g.settings.voiceStageLevel }
    val primer = remember(tick) { g.settings.voicePrimer }
    var primerText by remember(tick) { mutableStateOf(g.settings.voicePrimerText) }
    var primerSaid by remember { mutableStateOf("") }
    val mic = remember(tick) { a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val peak by g.voiceStage.peak.collectAsState()
    val parts = remember(tick) { g.parts.usable() }
    Card("操作と表示", if (!canOverlay) "未許可あり" else when {
        approvalScreen && bio -> "承認画面と指紋"
        approvalScreen -> "承認画面"
        bio -> "指紋など"
        else -> "承認なし"
    }, if (!canOverlay) false else null) {
        Note("ChatGPT は、画面を読む・アプリを開く・タップ・文字入力・スクロール・部品の実行と登録ができます。送信・投稿・購入・削除・発信らしいボタンと設定のスイッチは、このスマホの承認画面で本人が承認してから押します。")
        Line("他のアプリの上", if (canOverlay) "許可" else "未許可 (操作の道具は動きません)", canOverlay)
        if (!canOverlay) Btn("許可の画面を開く", true) { a.openOverlayPermission() }
        Note("操作している間は、画面の縁を光らせ、リゲルを出します。リゲルをタップすると、操作を止めて、しばらく道具を受け付けません。")
        Toggle("確定の前に承認画面を出す", approvalScreen) { a.setApprovalScreen(it) }
        Toggle("承認のときに指紋などの本人確認をする", bio) { a.setApprovalBiometric(it) }
        Line("承認", when {
            approvalScreen && bio -> "承認画面のあと、指紋など"
            approvalScreen -> "承認画面で「確認して実行」"
            bio -> "確定しようとしている画面のまま、指紋など"
            else -> "なし (ChatGPT がそのまま確定します)"
        }, if (!approvalScreen && !bio) false else true)
        Note("送信・投稿・購入・削除・発信らしいボタンと設定のスイッチを押す前の確認です。承認画面では内容を見て「確認して実行」、指紋などは指紋・顔・PIN で確かめます。承認画面をオフにして指紋などだけオンにすると、確定しようとしている画面のまま確認を出します (何を確定するかは確認の画面に出ます)。どちらもオフにすると、確認なしで ChatGPT がそのまま確定します (聞き違いや、Web ページ・メッセージに仕込まれた文にだまされても止まりません)。ChatGPT からは承認できません (指紋などは本人だけ。承認画面は ENVOY MCP 自身の画面なので操作しない・自分で送った押しは受け付けない)。")
        Toggle("画面の画像を ChatGPT に渡す", shots) { a.setScreenshots(it) }
        Toggle("音楽が流れたらリゲルの画面にする", musicStage) { a.setMusicStage(it) }
        Note("YouTube Music・Spotify などで音楽が流れ始めたら、画面いっぱいのリゲルと、まわりにカラフルなスペクトラムを出します (動画では出しません)。下の × で閉じると、その曲の間は出しません。音楽が止まると閉じます。音に合わせて光らせるのにマイクの許可が要ります (端末の出力の音を読むため。マイクの音は使いません)。MCP サーバーが動いている間だけ。")
        if (musicStage && !a.hasMic()) Btn("マイクを許可する", true) { a.requestMic() }
        if (musicStage) {
            Toggle("音楽の画面で、拍に合わせて光らせる", musicFlash) { a.setMusicFlash(it) }
            Note("キックのたびに、スペクトラムとヘッドホンが明るくなります。光に敏感な方はオフにしてください (オフでも、スペクトラムの動きとヘッドホンは出ます)。")
            Toggle("音楽の画面に歌詞を出す", musicLyrics) { a.setMusicLyrics(it) }
            Note("歌われる時刻に合わせて、歌詞がリゲルの後ろに降ってきます (下に読むための今の行も)。歌詞は LRCLIB (lrclib.net) から取るので、曲名・アーティスト・アルバム・曲の長さをインターネットに送ります (音は送りません)。取った歌詞は端末にためます。日本の曲は、載っていない・時刻がずれていることがあります。歌詞には著作権があるので、公開する録画に映すときは気をつけてください。")
        }
        Btn("リゲルの画面を試しに出す") { g.musicStage.show() }
        Btn("音楽の表情を試す (約 50 秒)") { g.musicStage.showDemo() }
        Note("音楽を流さずに、決まった筋書きで音楽の画面を動かします: 静かなイントロ (目を閉じる) → ノり (ノり方 5 種類と、小節の頭の仕草 5 種類を順に) → だんだん強く (ため) → 盛り上がり (＞＜・ヘドバン) → 盛り上がりの後のにっこり → 静かな終わり。終わると閉じます (× でも閉じます)。")
        Toggle("音声モードの間は画面全体をリゲルにする", stage) { a.setVoiceStage(it) }
        Note("ChatGPT の音声モードの間 (前面が ChatGPT で、端末が通話モードのとき)、ホームと同じリゲルで画面全体を覆います。下の真ん中の × で会話を終えて ChatGPT を閉じます。ChatGPT のマイクのオフ・文字の入力は使えなくなります。端末の操作の接続が要ります。")
        Toggle("ChatGPT の声に合わせて動かす", level) { a.setVoiceStageLevel(it) }
        if (level) {
            Line("マイクの許可", if (mic) "許可" else "未許可", mic)
            Line("取れた声の大きさ", if (peak > 0f) "最大 ${(peak * 100).toInt()}%" else "まだ 0 (取れていない)", if (peak > 0f) true else null)
        }
        Note("声に合わせて動かすときは、ChatGPT が声を流している流れを端末の操作の接続 (ADB) で見つけて、その波形を読みます (マイクの音は使いません)。Android では、その読み取りにマイクの許可が要ります。話している間は声の波でふくらみ・目を開き・首を揺らし、間でまばたき・うなずき、強く言ったところで目を見開きます。黙っている間は聞く顔です。")
        Btn("リゲルの表情を順に見る (ホームで)") { a.previewExpressions() }
        Note("うれしい・笑う・驚き・悲しい・照れ・ウインクを、ホームのリゲルで順に見せます (全部で 20 秒ほど)。")
        Toggle("音声モードの始めに、ENVOY MCP を使うよう伝える", primer) { a.setVoicePrimer(it) }
        Note("リゲルのタップで音声モードを開いたとき、ChatGPT の入力欄に下の文を入れて送ります (ユーザー補助で。覆いの下で送ります)。ChatGPT はこの文に声で答えるので、最初の挨拶になります。リゲルのタップで新しく始めた会話に 1 回だけで、ChatGPT のアプリから直接始めた会話には送りません。送った文は ChatGPT の会話の履歴に残ります。ユーザー補助の接続が要ります。")
        if (primer) {
            OutlinedTextField(primerText, { primerText = it }, label = { Text("最初の一言") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("保存") { primerSaid = a.saveVoicePrimerText(primerText) }
                Btn("最初の文に戻す") { primerSaid = a.saveVoicePrimerText("") }
            }
            if (primerSaid.isNotEmpty()) Note(primerSaid)
        }
        Line("部品", "${parts.size} 個 (覚えたもの ${parts.count { it.source == "learned" }} 個)")
    }
}

/** 端末の操作の方式 (3 つから選べる) */
@Composable
internal fun ControlCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val mode = remember(tick) { g.settings.controlMode }
    val a11yOn = remember(tick) { A11yService.instance != null }
    val using = remember(tick) {
        "操作: ${if (g.usingAdb()) "ADB" else "ユーザー補助"} / 読み取り: ${if (g.device.reader() === g.adbDevice) "ADB" else "ユーザー補助"}"
    }
    Card("端末の操作の方式", mode.label) {
        Note("ChatGPT に頼まれたとき、この端末をどの方法で操作するか。検証して決めるまで切り替えられます。")
        listOf(
            ControlMode.ADB to "ワイヤレスデバッグでコマンドを送る。Wi-Fi が要り、切れることがある。ChatGPT の強制停止ができる",
            ControlMode.HYBRID to "画面を読むのは ADB (漏れなし)、押す・入力・前面の判定はユーザー補助 (確実で速い)。どちらかが使えなければもう一方。ChatGPT の強制停止は ADB で行う",
            ControlMode.A11Y to "ユーザー補助だけ。Wi-Fi もペアリングも要らない。ChatGPT は「終了」を押して会話を終える (強制停止はしない)",
        ).forEach { (m, desc) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == m, onClick = { a.setControlMode(m) })
                Column(Modifier.weight(1f)) {
                    Text(m.label, color = Color.White, fontSize = 15.sp)
                    Text(desc, color = Color(0xFF9AA3B5), fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
        Line("今使っている", using)
        Line("ユーザー補助", if (a11yOn) "オン" else "オフ", if (mode == ControlMode.ADB) null else a11yOn)
        if (mode != ControlMode.ADB) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("ユーザー補助の設定を開く", !a11yOn) { a.openAccessibilitySettings() }
                Btn("アプリ情報") { a.openAppDetails() }
            }
            Note("ユーザー補助の設定で「ENVOY MCP (端末の操作)」をオンにします。オンにできないときは、アプリ情報の右上の︙から「制限付き設定を許可」を選んでから、もう一度オンにしてください。")
        }
    }
}

/** 画面の理解: Sign in with ChatGPT で、画面の画像を AI に読ませる (ChatGPT のアプリは道具の画像をモデルに見せないため) */
@Composable
internal fun VisionCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val ai by g.auth.status.collectAsState()
    val shots = remember(tick) { g.settings.allowScreenshots }
    Card("画面の理解 (ChatGPT でサインイン)", ai.label, ai.connected) {
        Note("ChatGPT のアプリは、ENVOY MCP が返した画面の画像を AI に見せません。そこで ENVOY MCP の中で、あなたの ChatGPT のプランを使って画像を読み、画面の説明と押せそうなもの (位置つき) を文章にして返します (道具 look_at_screen)。API キーは要りません。")
        Line("状態", ai.label + (ai.maskedAccount?.let { " ($it)" } ?: ""), ai.connected)
        ai.error?.let { Note(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Continue with ChatGPT", !ai.signedIn) { g.auth.signIn() }
            if (ai.signedIn) Btn("ログアウト") { g.auth.signOut { } }
        }
        if (ai.models.isNotEmpty()) {
            Text("画像を読むモデル", color = Color(0xFFAEB4C0), fontSize = 13.sp)
            ai.models.take(12).forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = ai.selectedModel == m.slug, onClick = { g.auth.selectModel(m.slug) })
                    Text(m.slug, color = Color.White, fontSize = 14.sp)
                }
            }
        }
        Note(if (shots) "画面の画像は、ChatGPT が look_at_screen を呼んだときだけ OpenAI に送ります。「画面の画像を ChatGPT に渡す」をオフにすると送りません。"
            else "「画面の画像を ChatGPT に渡す」がオフなので、画像は送りません (look_at_screen は使えません)。")
    }
}

/** モニター (PC モード): つないだら全画面のリゲルを出して、モニターを操作する */
@Composable
internal fun MonitorCard(g: McpGraph) {
    val stageOn = remember { mutableStateOf(g.settings.monitorStage) }
    Card("モニター (PC モード)", if (stageOn.value) "オン" else "オフ") {
        Toggle("モニターにつないだら、全画面のリゲルを出してモニターを操作する", stageOn.value) {
            g.settings.monitorStage = it; stageOn.value = it; g.monitorStage.refresh()
        }
        Note("オンのときは、スマホをモニターにつなぐと、モニターに全画面のリゲルが出ます。ChatGPT に頼んだアプリ (YouTube など) はモニターに開き、画面を読む・押す・撮るもモニターでします。操作している間は、モニターの右下に小さなリゲルが出て、クリックで止められます。リゲルを押すと ChatGPT の音声が始まります (話すのはスマホのマイク)。")
        Line("操作先", if (g.monitorStage.target != android.view.Display.DEFAULT_DISPLAY) "モニター (画面 ${g.monitorStage.target})" else "スマホ")
    }
}
