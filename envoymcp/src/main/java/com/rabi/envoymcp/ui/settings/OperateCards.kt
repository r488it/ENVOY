package com.rabi.envoymcp.ui.settings

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.ControlMode
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.a11y.A11yService
import com.rabi.envoymcp.device.adb.AdbState
import com.rabi.envoymcp.device.adb.PairingNotifier
import kotlinx.coroutines.launch

/** 端末の操作の方式 (3 つから選べる) とユーザー補助 */
@Composable
internal fun ControlCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val mode = remember(tick) { g.settings.controlMode }
    val a11yOn = remember(tick) { A11yService.instance != null }
    val using = remember(tick) {
        "操作: ${if (g.usingAdb()) "ADB" else "ユーザー補助"} / 読み取り: ${if (g.device.reader() === g.adbDevice) "ADB" else "ユーザー補助"}"
    }
    Card(CardTitle.CONTROL, mode.label, icon = Icons.Rounded.TouchApp) {
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
                    Text(desc, color = Palette.Muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
        Line("今使っている", using)
        Line("ユーザー補助", if (a11yOn) "オン" else "オフ", if (mode == ControlMode.ADB) null else a11yOn)
        if (mode != ControlMode.ADB) {
            Btns {
                Btn("ユーザー補助の設定を開く", !a11yOn) { a.openAccessibilitySettings() }
                Btn("アプリ情報") { a.openAppDetails() }
            }
            Note("ユーザー補助の設定で「ENVOY MCP (端末の操作)」をオンにします。オンにできないときは、アプリ情報の右上の︙から「制限付き設定を許可」を選んでから、もう一度オンにしてください。")
        }
    }
}

/** 端末の操作の接続 (ワイヤレスデバッグ): ペアリングと接続 */
@Composable
internal fun AdbCard(g: McpGraph, a: SettingsActivity, say: (String) -> Unit) {
    val adb by g.adb.state.collectAsState()
    val scope = rememberCoroutineScope()
    Card("端末の操作の接続 (ワイヤレスデバッグ)", adb.label, adb is AdbState.Connected, icon = Icons.Rounded.Adb) {
        Note("「スマホの状態」で前面のアプリを読むのに使います (無くても、ほかの状態は読めます)。ペアリングは最初に一度だけ要ります。")
        Line("状態", adb.label, adb is AdbState.Connected)
        Btns {
            Btn("ペアリングを始める") { PairingNotifier.show(a); a.open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
            Btn("接続", true) { scope.launch { say(if (g.adb.ensureConnected()) "✓ 接続しました" else "✗ ${g.adb.state.value.label}") } }
        }
        Note("ペアリング: 開発者向けオプション › ワイヤレスデバッグ › 「ペア設定コードによるデバイスのペア設定」を開いたまま、通知の返信欄に 6 桁のコードを入れます。")
    }
}

/** 他のアプリの上に表示: 操作の道具に要る許可と、操作している間の表示 */
@Composable
internal fun OverlayCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val canOverlay = remember(tick) { g.overlay.canShow() }
    Card(CardTitle.OVERLAY, if (canOverlay) "許可" else "未許可", canOverlay, icon = Icons.Rounded.Layers) {
        Note("ChatGPT は、画面を読む・アプリを開く・タップ・文字入力・スクロール・部品の実行と登録ができます。送信・投稿・購入・削除・発信らしいボタンと設定のスイッチは、このスマホの承認画面で本人が承認してから押します。")
        Line("他のアプリの上", if (canOverlay) "許可" else "未許可 (操作の道具は動きません)", canOverlay)
        if (!canOverlay) Btn("許可の画面を開く", true) { a.openOverlayPermission() }
        Note("操作している間は、画面の縁を光らせ、リゲルを出します。リゲルをタップすると、操作を止めて、しばらく道具を受け付けません。")
    }
}

/** 確定の前の承認: 承認画面と、指紋などの本人確認 */
@Composable
internal fun ApprovalCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val bio = remember(tick) { g.settings.approvalBiometric }
    val approvalScreen = remember(tick) { g.settings.approvalScreen }
    Card("確定の前の承認", when {
        approvalScreen && bio -> "承認画面と指紋"
        approvalScreen -> "承認画面"
        bio -> "指紋など"
        else -> "承認なし"
    }, approvalScreen || bio, icon = Icons.Rounded.Fingerprint) {
        Toggle("確定の前に承認画面を出す", approvalScreen) { a.setApprovalScreen(it) }
        Toggle("承認のときに指紋などの本人確認をする", bio) { a.setApprovalBiometric(it) }
        Line("承認", when {
            approvalScreen && bio -> "承認画面のあと、指紋など"
            approvalScreen -> "承認画面で「確認して実行」"
            bio -> "確定しようとしている画面のまま、指紋など"
            else -> "なし (ChatGPT がそのまま確定します)"
        }, if (!approvalScreen && !bio) false else true)
        Note("送信・投稿・購入・削除・発信らしいボタンと設定のスイッチを押す前の確認です。承認画面では内容を見て「確認して実行」、指紋などは指紋・顔・PIN で確かめます。承認画面をオフにして指紋などだけオンにすると、確定しようとしている画面のまま確認を出します (何を確定するかは確認の画面に出ます)。どちらもオフにすると、確認なしで ChatGPT がそのまま確定します (聞き違いや、Web ページ・メッセージに仕込まれた文にだまされても止まりません)。ChatGPT からは承認できません (指紋などは本人だけ。承認画面は ENVOY MCP 自身の画面なので操作しない・自分で送った押しは受け付けない)。")
    }
}

/** 画面の理解: Sign in with ChatGPT で、画面の画像を AI に読ませる (ChatGPT のアプリは道具の画像をモデルに見せないため) */
@Composable
internal fun VisionCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val ai by g.auth.status.collectAsState()
    val shots = remember(tick) { g.settings.allowScreenshots }
    Card("画面の理解 (ChatGPT でサインイン)", ai.label, ai.connected, icon = Icons.Rounded.Visibility) {
        Note("ChatGPT のアプリは、ENVOY MCP が返した画面の画像を AI に見せません。そこで ENVOY MCP の中で、あなたの ChatGPT のプランを使って画像を読み、画面の説明と押せそうなもの (位置つき) を文章にして返します (道具 look_at_screen)。API キーは要りません。")
        Line("状態", ai.label + (ai.maskedAccount?.let { " ($it)" } ?: ""), ai.connected)
        ai.error?.let { Note(it) }
        Btns {
            Btn("Continue with ChatGPT", !ai.signedIn) { g.auth.signIn() }
            if (ai.signedIn) Btn("ログアウト") { g.auth.signOut { } }
        }
        if (ai.models.isNotEmpty()) {
            Text("画像を読むモデル", color = Palette.Label, fontSize = 13.sp)
            ai.models.take(12).forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = ai.selectedModel == m.slug, onClick = { g.auth.selectModel(m.slug) })
                    Text(m.slug, color = Color.White, fontSize = 14.sp)
                }
            }
        }
        Toggle("画面の画像を ChatGPT に渡す", shots) { a.setScreenshots(it) }
        Note(if (shots) "画面の画像は、ChatGPT が look_at_screen を呼んだときだけ OpenAI に送ります。「画面の画像を ChatGPT に渡す」をオフにすると送りません。"
            else "「画面の画像を ChatGPT に渡す」がオフなので、画像は送りません (look_at_screen は使えません)。")
    }
}

/** 使える部品の数 (同梱のものと、覚えたもの) */
@Composable
internal fun PartsCard(g: McpGraph, tick: Int) {
    val parts = remember(tick) { g.parts.usable() }
    Card("部品", "${parts.size} 個", icon = Icons.Rounded.Extension) {
        Line("部品", "${parts.size} 個 (覚えたもの ${parts.count { it.source == "learned" }} 個)")
    }
}

/** モニター (PC モード): つないだら全画面のリゲルを出して、モニターを操作する */
@Composable
internal fun MonitorCard(g: McpGraph) {
    val stageOn = remember { mutableStateOf(g.settings.monitorStage) }
    Card("モニター (PC モード)", if (stageOn.value) "オン" else "オフ", if (stageOn.value) true else null, icon = Icons.Rounded.DesktopWindows) {
        Toggle("モニターにつないだら、全画面のリゲルを出してモニターを操作する", stageOn.value) {
            g.settings.monitorStage = it; stageOn.value = it; g.monitorStage.refresh()
        }
        Note("オンのときは、スマホをモニターにつなぐと、モニターに全画面のリゲルが出ます。ChatGPT に頼んだアプリ (YouTube など) はモニターに開き、画面を読む・押す・撮るもモニターでします。操作している間は、モニターの右下に小さなリゲルが出て、クリックで止められます。リゲルを押すと ChatGPT の音声が始まります (話すのはスマホのマイク)。")
        Line("操作先", if (g.monitorStage.target != android.view.Display.DEFAULT_DISPLAY) "モニター (画面 ${g.monitorStage.target})" else "スマホ")
    }
}
