package com.rabi.envoymcp.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.ui.calllog.CallLogActivity
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 電話: 許可・リゲルが代わりに電話する・かかってきた電話に出る・通話の記録。
 * 見出しの状態は設定を直接読むので、SettingsRoot を作り直すたびに作り直す (飛ばさない)
 */
@Composable
@NonSkippableComposable
internal fun PhoneCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val phoneOk = remember(tick) { a.hasPhonePerms() }
    val phoneStatus = if (!phoneOk) "未許可" else "着信 " + when (g.settings.rigelIncoming) { "off" -> "出ない"; "voice" -> "声で尋ねる"; else -> "ボタン" } +
        "・録音 " + if (g.settings.rigelRecord) "オン" else "オフ"
    Card("電話", phoneStatus, if (phoneOk) null else false) {
        Line("許可", if (phoneOk) "連絡先を読む・電話をかける: 許可" else "未許可 (電話の道具が使えません)", phoneOk)
        if (!phoneOk) Btn("許可する", true) { a.requestPhonePerms() }
        Note("ChatGPT に「〇〇さんに電話して」と頼むと、電話のアプリを操作せず、連絡先から番号を探して、承認のあとすぐ発信します (承認のしかたは「操作と表示」の設定のとおり)。連絡先の中身は ChatGPT に渡しません (選んだ人の名前と、番号の種類・下 4 桁だけ)。緊急の番号にはかけません。")
        Sub("リゲルが代わりに電話する")
        var callerName by remember { mutableStateOf(g.settings.rigelCallerName) }
        OutlinedTextField(callerName, { callerName = it; g.settings.rigelCallerName = it }, label = { Text("あなたの呼び名 (リゲルが「〇〇さんの代わりに」と名乗る)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        val rc = g.rigelCaller
        var rcInfo by remember { mutableStateOf("") }
        LaunchedEffect(tick) { while (true) { rcInfo = if (rc.state == "idle") "" else "${rc.to}: ${rc.state}" + (rc.result?.optString("summary")?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""); delay(1_000) } }
        if (rcInfo.isNotBlank()) Line("リゲルの電話", rcInfo, if (rc.busy) true else null)
        if (rc.busy) Btn("リゲルの電話をやめる") { rc.hangup() }
        Note("ChatGPT に「〇〇さんに電話して、XX と伝えて」と頼むと、承認 (相手と用件) のあと、リゲル (Gemini 3.8 Live、UNO Q で動く) が電話をかけ、AI だと名乗って用件を伝え、返事を聞いて切ります。結果は ChatGPT が rigel_call_status で受け取ります。UNO Q (腕) につながっていて、UNO Q で電話の代役 (rigel-call) が動いている必要があります。相手の声は Google に送られます。")
        var mask by remember { mutableStateOf(g.settings.numberMask) }
        Toggle("電話の画面の番号をモザイクで隠す", mask) { g.settings.numberMask = it; mask = it }
        Note("リゲルが代わりに電話している間、電話のアプリの画面に出る番号の上にモザイクを重ねます。代行中はリゲルの通話の画面で覆うので、ふだんはオフで構いません (通話の画面が出せないとき・録画で電話の画面を映すとき用)。")
        Sub("かかってきた電話にリゲルが代わりに出る")
        var incoming by remember { mutableStateOf(g.settings.rigelIncoming) }
        listOf(
            "off" to "しない",
            "buttons" to "着信の画面に「リゲルが代わりに出る」「自分で出る」を出す",
            "voice" to "ChatGPT の音声モードを開いて、代わりに出るか尋ねる (ロック中は画面のボタン)",
        ).forEach { (v, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = incoming == v, onClick = { incoming = v; g.settings.rigelIncoming = v })
                Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            }
        }
        var auto by remember { mutableStateOf(g.settings.rigelAutoAnswer) }
        var autoSec by remember { mutableFloatStateOf(g.settings.rigelAutoAnswerSec.toFloat()) }
        Toggle("出なかったら、リゲルが自動で出る", auto) { g.settings.rigelAutoAnswer = it; auto = it }
        if (auto && shown("自動で出るまで")) {
            Text("自動で出るまで: 鳴り始めから ${autoSec.roundToInt()} 秒", color = Color.White, fontSize = 15.sp)
            Slider(value = autoSec, onValueChange = { autoSec = it.roundToInt().toFloat() }, valueRange = 3f..30f, steps = 26,
                onValueChangeFinished = { g.settings.rigelAutoAnswerSec = autoSec.roundToInt() })
        }
        Note("自分で出ず、「リゲルが代わりに出る」「自分で出る」も選ばないまま決めた秒数がたったら、リゲルが代わりに出ます (上で「しない」を選んでいるときは出ません)。留守番電話につながると出られないので、留守番電話より短くしてください。")
        var report by remember { mutableStateOf(g.settings.rigelIncomingReport) }
        Toggle("出たあと、ChatGPT の音声モードを開いて用件を伝える", report) { g.settings.rigelIncomingReport = it; report = it }
        Note("リゲルが出ると、AI だと名乗って相手の名前・用件・折り返しの要否を聞き、切ります。上のスイッチがオンなら、終わったら ChatGPT の音声モードが開いて、用件を整理して伝えます (オフなら通話の記録に残すだけ)。電話と LINE の着信に出ます (どのアプリの着信か・かけてきた人は、着信の通知から読みます。「通知へのアクセス」の許可が要ります)。ユーザー補助がオンで、UNO Q で電話の代役が動いている必要があります。")
        Sub("通話の記録")
        var record by remember { mutableStateOf(g.settings.rigelRecord) }
        Toggle("リゲルの通話を録音する", record) { g.settings.rigelRecord = it; record = it }
        Btn("通話の記録を見る") { a.startActivity(Intent(a, CallLogActivity::class.java)) }
        Note("リゲルが代わりにかけた・出た電話の間は、リゲルと相手のやり取りを文字で画面に出し、終わったら記録に残します (いつでも見られます。消すのは記録の画面から)。録音がオンなら、通話の音も残します (相手には伝えません)。記録と録音はこのスマホの中だけに置き、どこにも送りません。")
    }
}
