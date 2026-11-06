package com.rabi.envoymcp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.kids.KidsRules
import com.rabi.envoymcp.kids.Topic

/**
 * まなび (子どもモード): 3 さいくらいの子どもが、リゲルと「かず」「ひらがな」「とけい」をお勉強する。
 * - 「お勉強をはじめる」: 子どもモードを入れて、お勉強を開く (リゲルのタップと同じ)
 * - 子どもモード: 入れるのはすぐ。切るときは、ご本人か確かめる
 * - 1 日の時間・お勉強するもの・すすめ方 (ひとりで / AI の先生)・画面の固定。子どもモードの間にゆるめるときは、ご本人か確かめる
 */
@Composable
internal fun KidsCard(g: McpGraph, a: SettingsActivity, tick: Int, say: (String) -> Unit) {
    val on = remember(tick) { g.settings.kidsMode }
    val rules = remember(tick) { g.settings.kidsRules }
    val left = remember(tick) { g.kids.minutesLeft() }
    val used = remember(tick) { g.kids.minutesUsed() }
    val agent = remember(tick) { g.settings.voiceAgent }
    Card(CardTitle.KIDS, if (on) "オン・のこり $left 分" else "オフ", if (on) true else null, icon = Icons.Rounded.ChildCare) {
        Note("3 さいくらいの子どもが、リゲルと「かず」「ひらがな」「とけい」をお勉強できます。絵と字は、アプリが自分で描きます。")
        if (shown(START)) Button(onClick = { a.startKids() }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text(START, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Toggle("子どもモード", on) { a.setKidsMode(it, say) }
        Note("子どもモードの間は、リゲルをタップすると (腕の呼び名「ねえ、リゲル」でも)、お勉強が始まります。")
        Note("AI エージェントが使えるのは、まなびの道具と、表情・腕の動き・踊り・会話を終える道具だけです。買い物・電話・共有・ほかのアプリを開く・ほかのアプリを押す、はできません。頼まれても動かさずに断り、したことに残します。確定の前の承認は、そのままです。")
        Note("子どもモードを入れるのはすぐです。切るときは、指紋・顔・PIN で、おとなの人か確かめます (お勉強の画面の左上の「おとなの人」からも切れます)。")

        Sub("1 日に お勉強できる時間")
        Btns { KidsRules.LIMITS.forEach { m -> Btn("$m 分", primary = m == rules.limitMin) { a.setKidsRules(rules.copy(limitMin = m), say) } } }
        Line("きょう", "$used 分 (のこり $left 分)")
        Note("お勉強の画面が出ている長さを数えます。決めた時間になると「きょうは ここまで」と出して、やさしく止めます。日が変わるか、子どもモードを入れ直すと、0 から数えます。")

        Sub("お勉強する もの")
        Topic.entries.forEach { t ->
            Toggle(TOPIC_LINES.getValue(t), t in rules.topics) { v ->
                val next = if (v) rules.topics + t else rules.topics - t
                if (next.isEmpty()) say("1 つは選んでください") else a.setKidsRules(rules.copy(topics = next), say)
            }
        }
        Note("むずかしさは、答え方に合わせて少しずつ変わります (続けてできたら上げ、まちがいが続いたら下げます)。まちがえても、しかりません。")

        Sub("すすめ方")
        Choice("ひとりで (スマホの中だけ)", "リゲルが問題を読み上げ、子どもが画面を押して答えます。声も答えも、スマホの外には何も送りません。",
            !rules.aiTeacher) { a.setKidsRules(rules.copy(aiTeacher = false), say) }
        Choice("AI の先生 (${agent.label})", "リゲルのタップで、話し相手の AI の音声モードを、子どもの先生として開きます。AI が話しかけ、問題はスマホの画面に出ます。",
            rules.aiTeacher) { a.setKidsRules(rules.copy(aiTeacher = true), say) }
        if (rules.aiTeacher) Warn("AI の先生では、子どもの声が、選んだ AI (${agent.label}) に届きます。ENVOY から AI に渡すのは、問題と、子どもが選んだ答えだけです (名前などは渡しません)。")
        Note("Claude を先生にするときは、「安心 (承認)」の「エージェントの権限」で、Claude (コネクター) の「読むだけ」を外し、「まなび」を許してください (まなびの道具は画面を変えるので、読むだけの道具ではありません)。")

        Sub("画面の固定")
        Toggle("ひとりでお勉強する画面を固定する (アプリの固定)", rules.pin) { a.setKidsRules(rules.copy(pin = it), say) }
        Note("お勉強の画面を開くと、Android の「アプリを固定しますか？」が出ます。おとなの人が「OK」を押すと、ホームやほかのアプリに移れなくなります。外すときは、お勉強の画面の左上の「おとなの人」を押して、指紋などで確かめます。")
        Note("Android の設定 (セキュリティ › アプリの固定) で「固定を解除する前に PIN を要求」をオンにすると、Android の操作で固定を外すときも PIN が要ります。AI の先生のときは、AI のアプリの声を聞くので固定しません。")
        Btns { Btn("アプリの固定の設定を開く") { a.openSecuritySettings() } }
    }
}

/** 黄色の注意 (AI に子どもの声が届くこと) */
@Composable
private fun Warn(s: String) {
    if (!shown(s)) return
    Text(s, color = Palette.Warn, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Warn.copy(alpha = 0.12f)).padding(10.dp))
}

private const val START = "お勉強をはじめる"

/** お勉強するものの説明 (おうちの人むけ) */
private val TOPIC_LINES = mapOf(
    Topic.KAZU to "かず (1〜5、なれたら 1〜10)",
    Topic.HIRAGANA to "ひらがな (あ行から、か行・さ行 …)",
    Topic.TOKEI to "とけい (〜じ、なれたら〜じはん)",
)
