package com.rabi.envoymcp.kids

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.core.Mood
import com.rabi.envoymcp.ui.avatar.Avatar
import com.rabi.envoymcp.ui.avatar.AvatarSignals
import com.rabi.envoymcp.ui.avatar.AvatarStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * お勉強の画面の中身。お勉強の画面 (KidsActivity。ひとりで) と、重ねる窓 (KidsOverlay。AI の先生) の両方で使う。
 * speak はリゲルの読み上げ (ひとりのときだけ。AI の先生のときは null: AI が話す)。
 * insets は画面の端の余白 (重ねる窓は自分で測って渡す。null なら Activity の余白 safeDrawing)
 */
@Composable
internal fun KidsScreen(g: McpGraph, speak: ((String) -> Unit)?, onParent: () -> Unit, insets: PaddingValues? = null) {
    val v by g.kids.view.collectAsState()
    val st by g.state.collectAsState()
    // おうちの人が選んだ題 (題を選ぶ画面になるたびに読み直す)
    val topics = remember(v.mode) { Topic.entries.filter { it in g.settings.kidsTopics } }
    // ひとりのときは、リゲルが読み上げる (AI の先生のときは AI が話す)。見えているときだけ読む (隠れている間に変わったら、見えたときに読む)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(v.speechSeq) {
        if (v.ai || v.speech.isBlank() || speak == null) return@LaunchedEffect
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        speak(v.speech)
    }
    // 時間になったら、やさしく止める
    LaunchedEffect(Unit) { while (true) { delay(1_000); g.kids.tick() } }
    val say: (() -> Unit)? = if (v.ai || speak == null) null else ({ v.question?.let { speak(it.speech) } })
    val edge = if (insets != null) Modifier.padding(insets) else Modifier.safeDrawingPadding()
    Box(Modifier.fillMaxSize().background(KidsColors.Bg)) {
        Column(Modifier.fillMaxSize().then(edge).padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(170.dp)) {
                Avatar(Mood.IDLE, AvatarStyle.ORB, 0f, Modifier.fillMaxSize(),
                    signals = AvatarSignals(expression = st.expression, expressionSeq = st.expressionSeq, expressionHoldMs = st.expressionHoldMs))
            }
            when (v.mode) {
                KidsLesson.Mode.PICK -> PickBody(topics) { g.kids.pickTopic(it) }
                KidsLesson.Mode.ASK -> AskBody(v, onTap = { g.kids.tap(it) }, onSay = say)
                KidsLesson.Mode.END -> EndBody(v, onAgain = if (v.ai) null else ({ g.kids.pick() }))
                KidsLesson.Mode.TIME_UP -> TimeUpBody()
            }
        }
        StarBurst(v.cheerSeq)
        Text("おとなの人", color = KidsColors.Soft.copy(alpha = 0.55f), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.TopStart).then(edge).padding(6.dp).clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClick = onParent).padding(10.dp))
    }
}

/** 題を選ぶ: かず・ひらがな・とけい の大きなボタン (おうちの人が選んだものだけ) */
@Composable
private fun ColumnScope.PickBody(topics: List<Topic>, onPick: (Topic) -> Unit) {
    Text(KidsTeacher.PICK, color = KidsColors.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)) {
        topics.forEach { t ->
            Row(Modifier.fillMaxWidth().height(124.dp).clip(RoundedCornerShape(32.dp)).background(KidsColors.topic(t))
                .clickable(role = Role.Button, onClick = { onPick(t) }).semantics { contentDescription = t.label }.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    when (t) {
                        Topic.KAZU -> Dots(3, 1, Modifier.fillMaxSize())
                        Topic.HIRAGANA -> Text("あ", color = KidsColors.Text, fontSize = 64.sp, fontWeight = FontWeight.Bold)
                        Topic.TOKEI -> ClockFace(3, 0, Modifier.fillMaxSize(), numbers = false)
                    }
                }
                Text(t.label, color = KidsColors.Text, fontSize = 46.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp))
            }
        }
    }
}

/** 問題: 問いの文・絵・ひとこと・選ぶボタン */
@Composable
private fun ColumnScope.AskBody(v: KidsLesson.View, onTap: (Int) -> Unit, onSay: (() -> Unit)?) {
    val q = v.question ?: return
    StarRow(v.stars, maxOf(v.size, v.stars), Modifier.height(30.dp))
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // 問いの文は、読み上げのボタンの残りの幅に収める (長ければ折り返す)
        Text(q.text, color = KidsColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            lineHeight = 42.sp, modifier = Modifier.weight(1f, fill = false))
        if (onSay != null) Box(Modifier.padding(start = 12.dp).size(52.dp).clip(CircleShape).background(KidsColors.Card)
            .clickable(role = Role.Button, onClick = onSay).semantics { contentDescription = "もういちど きく" }, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = KidsColors.Text, modifier = Modifier.size(30.dp))
        }
    }
    Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        when (val vis = q.visual) {
            is Visual.Dots -> Dots(vis.n, vis.kind, Modifier.fillMaxWidth().fillMaxHeight(0.9f))
            is Visual.Numeral -> Text("${vis.n}", color = KidsColors.Star, fontSize = 140.sp, fontWeight = FontWeight.Bold)
            is Visual.Clock -> ClockFace(vis.h, vis.m, Modifier.fillMaxHeight().aspectRatio(1f))
            Visual.None -> {}
        }
    }
    Text(v.message, color = if (v.right != null) KidsColors.Star else KidsColors.Retry, fontSize = 32.sp, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.height(48.dp))
    Choices(q, v, onTap)
    Spacer(Modifier.height(8.dp))
}

/**
 * 選ぶボタン。数字・ひらがなは横に並べる (4 こは 2 つずつ 2 段)。時計の答えと丸のまとまりは、字や丸が大きく見えるよう縦に並べる
 */
@Composable
private fun Choices(q: Question, v: KidsLesson.View, onTap: (Int) -> Unit) {
    val n = q.choices.size
    val tall = q.topic == Topic.TOKEI || q.choices.any { it.dots > 0 }
    val rows: List<List<Int>> = when {
        tall -> q.choices.indices.map { listOf(it) }
        n == 4 -> listOf(listOf(0, 1), listOf(2, 3))
        else -> listOf(q.choices.indices.toList())
    }
    val h = when { tall -> 84.dp; n == 4 -> 112.dp; else -> 150.dp }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().height(h), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { i ->
                    val c = q.choices[i]
                    val right = v.right == i
                    val wrong = i in v.wrong
                    Box(Modifier.weight(1f).fillMaxHeight().alpha(if (wrong) 0.3f else 1f).clip(RoundedCornerShape(28.dp))
                        .background(if (right) KidsColors.Right else KidsColors.Card)
                        .clickable(enabled = v.right == null && !wrong, role = Role.Button, onClick = { onTap(i) })
                        .semantics { contentDescription = c.label }, contentAlignment = Alignment.Center) {
                        if (c.dots > 0) Dots(c.dots, 3, Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp))
                        else Text(c.text, color = KidsColors.Text, fontWeight = FontWeight.Bold, maxLines = 1,
                            fontSize = when { tall -> 48.sp; c.text.length <= 2 -> 72.sp; else -> 48.sp })
                        if (right) Star(true, Modifier.align(Alignment.TopEnd).padding(8.dp).size(34.dp))
                    }
                }
            }
        }
    }
}

/** 星の並び (filled こが黄色) */
@Composable
private fun StarRow(filled: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total.coerceAtMost(MAX_STARS)) { Star(it < filled, Modifier.fillMaxHeight().aspectRatio(1f)) }
    }
}

/** おしまい: もらった星と「きょうも がんばったね」 */
@Composable
private fun ColumnScope.EndBody(v: KidsLesson.View, onAgain: (() -> Unit)?) {
    val stars = v.summary?.stars ?: v.stars
    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(KidsTeacher.END, color = KidsColors.Text, fontSize = 38.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 50.sp)
        Spacer(Modifier.height(24.dp))
        stars.coerceAtMost(MAX_STARS).let { n -> (0 until n).chunked(5).forEach { row ->
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { _ -> Star(true, Modifier.size(56.dp)) } }
        } }
        Text("ほし $stars こ", color = KidsColors.Star, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        if (onAgain != null) {
            Spacer(Modifier.height(36.dp))
            Box(Modifier.width(260.dp).height(96.dp).clip(RoundedCornerShape(32.dp)).background(KidsColors.Card)
                .clickable(role = Role.Button, onClick = onAgain), contentAlignment = Alignment.Center) {
                Text("もういちど", color = KidsColors.Text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 時間になった: 「きょうは ここまで」 */
@Composable
private fun ColumnScope.TimeUpBody() {
    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Moon(Modifier.size(150.dp))
        Spacer(Modifier.height(24.dp))
        Text(KidsTeacher.TIME_UP, color = KidsColors.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 52.sp)
        Text("また あそぼうね", color = KidsColors.Soft, fontSize = 30.sp, modifier = Modifier.padding(top = 12.dp))
    }
}

/** 一度に並べる星の数の上限 */
private const val MAX_STARS = 10
