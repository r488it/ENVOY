package com.rabi.envoymcp.kids

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rabi.envoymcp.HomeActivity
import com.rabi.envoymcp.McpApp
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.core.Mood
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.mcp.ActivityLog
import com.rabi.envoymcp.ui.avatar.Avatar
import com.rabi.envoymcp.ui.avatar.AvatarSignals
import com.rabi.envoymcp.ui.avatar.AvatarStyle
import com.rabi.envoymcp.ui.settings.OwnerCheck
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * お勉強の画面 (子どもモード)。上にリゲル、まん中に大きな問題、下に大きな選ぶボタン (2〜4 こ)。
 * - ひとりで: 題を選ぶ (かず・ひらがな・とけい) → 5 もん → 星と「きょうも がんばったね」。リゲルが読み上げる
 * - AI の先生: 道具 (kids_question) が出した問題を見せ、子どもが押したものを道具に返す。読み上げない
 * - 時間になったら「きょうは ここまで」
 * - ひとりでお勉強するときは、Android のアプリの固定 (startLockTask) で画面を固定する (おうちの人が設定でオンにしているとき)。
 *   端末の管理者のアプリではないので、Android が「アプリを固定しますか？」と聞く。AI の先生のときは、AI のアプリの声を聞くので固定しない
 * - 左上の「おとなの人」: ご本人か確かめて (指紋など)、子どもモードを切り、固定をやめてホームに戻る
 */
class KidsActivity : ComponentActivity() {
    private val g by lazy { (application as McpApp).graph }
    private val owner = OwnerCheck(this)
    private lateinit var voice: KidsVoice
    /** この画面で、アプリの固定をもう頼んだか (Android の問いかけは 1 回だけ) */
    private var pinAsked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        voice = KidsVoice(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = back() })
        setContent { KidsScreen(g, voice, onParent = { parentExit() }) }
    }

    override fun onResume() {
        super.onResume()
        g.kids.shown()
        // 音声モードの全画面のリゲルが出ていたら消す (この画面の上に重なるため)
        g.voiceStage.yieldToApp()
        pin()
    }

    override fun onPause() {
        g.kids.hidden()
        super.onPause()
    }

    override fun onStop() {
        voice.stop()
        super.onStop()
    }

    override fun onDestroy() {
        owner.cancel()
        voice.shutdown()
        super.onDestroy()
    }

    /** 戻る: 問題・おしまいの画面なら題を選ぶところへ。題を選ぶ・時間になった画面ならホームへ。AI の先生のときは抜けない */
    private fun back() {
        val v = g.kids.view.value
        when {
            v.ai -> {}
            v.mode == KidsLesson.Mode.ASK || v.mode == KidsLesson.Mode.END -> g.kids.pick()
            // 固定している間は、Android がこの画面を閉じさせない
            else -> finish()
        }
    }

    /**
     * ひとりでお勉強するときは、この画面を固定する (おうちの人が設定でオンにしているとき・まだ固定していないとき。1 回だけ頼む)。
     * Android が「アプリを固定しますか？」と聞く。固定を外すのは「おとなの人」か、Android の固定を外す操作
     */
    private fun pin() {
        if (pinAsked || !g.settings.kidsMode || !g.settings.kidsPin || g.kids.view.value.ai) return
        val am = getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) return
        pinAsked = true
        runCatching { startLockTask() }.onFailure { RabiLog.w(TAG, "pin failed: ${it.javaClass.simpleName}") }
    }

    /** 「おとなの人」: ご本人か確かめてから、子どもモードを切り、固定をやめてホームに戻る */
    private fun parentExit() {
        owner.ask("子どもモードを切ります", "お勉強の画面を閉じて、ふだんの ENVOY MCP に戻ります", say = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }) {
            g.kids.setKidMode(false, ActivityLog.USER)
            runCatching { stopLockTask() }
            runCatching {
                startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
            finish()
        }
    }

    companion object {
        private const val TAG = "kids"

        /** お勉強の画面を開く (ホーム・設定・道具から。もう出ていれば、そのまま前に出す) */
        fun open(context: Context) {
            runCatching {
                context.startActivity(Intent(context, KidsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }.onFailure { RabiLog.w(TAG, "cannot open: ${it.javaClass.simpleName}") }
        }
    }
}

@Composable
private fun KidsScreen(g: McpGraph, voice: KidsVoice, onParent: () -> Unit) {
    val v by g.kids.view.collectAsState()
    val st by g.state.collectAsState()
    // おうちの人が選んだ題 (題を選ぶ画面になるたびに読み直す)
    val topics = remember(v.mode) { Topic.entries.filter { it in g.settings.kidsTopics } }
    // ひとりのときは、リゲルが読み上げる (AI の先生のときは AI が話す)。見えているときだけ読む (隠れている間に変わったら、見えたときに読む)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(v.speechSeq) {
        if (v.ai || v.speech.isBlank()) return@LaunchedEffect
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        voice.say(v.speech)
    }
    // 時間になったら、やさしく止める
    LaunchedEffect(Unit) { while (true) { delay(1_000); g.kids.tick() } }
    val say: (() -> Unit)? = if (v.ai) null else ({ v.question?.let { voice.say(it.speech) } })
    Box(Modifier.fillMaxSize().background(KidsColors.Bg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(170.dp)) {
                Avatar(Mood.IDLE, AvatarStyle.ORB, 0f, Modifier.fillMaxSize(),
                    signals = AvatarSignals(expression = st.expression, expressionSeq = st.expressionSeq, expressionHoldMs = st.expressionHoldMs))
            }
            when (v.mode) {
                KidsLesson.Mode.PICK -> PickBody(topics) { g.kids.startAlone(it) }
                KidsLesson.Mode.WAIT -> WaitBody(v.message)
                KidsLesson.Mode.ASK -> AskBody(v, onTap = { g.kids.tap(it) }, onSay = say)
                KidsLesson.Mode.END -> EndBody(v, onAgain = if (v.ai) null else ({ g.kids.pick() }))
                KidsLesson.Mode.TIME_UP -> TimeUpBody()
            }
        }
        StarBurst(v.cheerSeq)
        Text("おとなの人", color = KidsColors.Soft.copy(alpha = 0.55f), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(6.dp).clip(RoundedCornerShape(10.dp))
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

/** AI の先生が話している間 */
@Composable
private fun ColumnScope.WaitBody(message: String) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(message, color = KidsColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 46.sp)
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
