package com.rabi.envoymcp.call

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.a11y.A11yService
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.ui.avatar.AvatarStyle
import com.rabi.envoymcp.ui.overlay.OverlayOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * リゲルが代わりに電話している間の画面 (リゲルと相手のやり取りを文字で出す)。相手・通話の時間・「リゲルが話しています」などの帯・やり取り (文字)・切るボタン。
 * - かける・出る、どちらも、ボードが通話に入れたところ (RigelCaller の place のあと) で出し、通話が終わって少ししたら消す
 * - ユーザー補助の窓で出す (電話・LINE の通話の画面やロック画面の上にも出る。下の LINE の画面もユーザー補助で読めるまま)。
 *   ユーザー補助がオフなら、ほかのアプリの上に表示の許可で出す。どちらも無ければ出さない (電話の画面が見えるだけ)
 * - 上の時刻・電池の帯は隠さない (その下から出す)
 */
class CallScreen(private val g: McpGraph) {
    private var view: View? = null
    private var wm: WindowManager? = null
    private var owner: OverlayOwner? = null
    private var hideJob: Job? = null

    fun show() {
        g.scope.launch(Dispatchers.Main) {
            hideJob?.cancel(); hideJob = null
            if (view != null) return@launch
            val a11y = A11yService.instance
            val type = when {
                a11y != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                Settings.canDrawOverlays(g.context) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else -> { RabiLog.w("callscreen", "no a11y or overlay permission"); return@launch }
            }
            val ctx: Context = a11y ?: g.context
            val w = ctx.getSystemService(WindowManager::class.java)
            val m = w.currentWindowMetrics
            val top = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
            val nav = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
            val navDp = (nav / ctx.resources.displayMetrics.density).dp
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, m.bounds.height() - top, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                y = top
                title = "rigel-call"
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            val o = OverlayOwner().also { it.start() }
            val v = ComposeView(ctx).apply {
                setViewTreeLifecycleOwner(o)
                setViewTreeSavedStateRegistryOwner(o)
                setContent { CallScreenContent(g, navDp) }
            }
            runCatching { w.addView(v, lp) }
                .onSuccess { view = v; wm = w; owner = o; RabiLog.i("callscreen", "shown (${if (a11y != null) "a11y" else "overlay"})") }
                .onFailure { o.stop(); RabiLog.w("callscreen", "show failed: ${it.javaClass.simpleName}") }
        }
    }

    /** 通話が終わった: 「通話を終えました」を少し見せてから消す */
    fun hideAfter(ms: Long = END_SHOW_MS) {
        g.scope.launch(Dispatchers.Main) {
            if (view == null) return@launch
            hideJob?.cancel()
            hideJob = g.scope.launch(Dispatchers.Main) { delay(ms); hide() }
        }
    }

    fun hide() {
        g.scope.launch(Dispatchers.Main) {
            val v = view ?: return@launch
            view = null
            runCatching { wm?.removeView(v) }
            owner?.stop(); owner = null; wm = null
            RabiLog.i("callscreen", "hidden")
        }
    }

    companion object {
        /** 終わってから消すまで */
        const val END_SHOW_MS = 1_500L
    }
}

// ---------------------------------------------------------------- 色

internal val CALL_BG = Brush.verticalGradient(listOf(Color(0xFF13202F), Color(0xFF070B12)))
internal val CALL_GREEN = Color(0xFF7FE3B0)
internal val CALL_SUB = Color(0xFFB9CBE3)
internal val CALL_DIM = Color(0xFF7F90A8)
internal val CALL_RIGEL = Color(0xFF9FD8FF)
internal val CALL_TEXT = Color(0xFFE8EEF7)
internal val CALL_PEER_TEXT = Color(0xFFD7F5E6)
/** 言葉が届いてから、まだ話していることにする長さ */
private const val SPEAK_HOLD_MS = 1_500L

internal fun mmss(sec: Long): String = "%02d:%02d".format(sec / 60, sec % 60)

/** 通話中の画面 (相手・時間・帯・やり取り・切るボタン) */
@Composable
fun CallScreenContent(g: McpGraph, navBottom: Dp) {
    val live by g.rigelCaller.live.collectAsState()
    val c = live ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    val glow = remember { AvatarStyle.byId(g.settings.avatar).glow }
    Box(Modifier.fillMaxSize().background(CALL_BG)) {
        Column(Modifier.fillMaxSize().padding(bottom = navBottom + 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(36.dp))
            CallerCircle(ringing = !c.talking && !c.ended, diameter = 112.dp)
            Spacer(Modifier.height(18.dp))
            Text(c.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(6.dp))
            val st = when {
                c.ended -> "通話を終えました"
                c.talking -> mmss(((now - c.answeredAt) / 1000).coerceAtLeast(0))
                c.dir == "in" -> "応答しています…"
                else -> "呼び出し中…"
            }
            Text(st, color = if (c.talking && !c.ended) CALL_GREEN else CALL_SUB, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            val recent = c.lastTextAt > 0L && now - c.lastTextAt < SPEAK_HOLD_MS
            val rigelTalking = c.talking && !c.ended && recent && c.lastWho == "rigel"
            val label = when {
                rigelTalking -> "リゲルが話しています"
                c.talking && !c.ended && recent && c.lastWho == "peer" -> "相手の声を聞いています"
                !c.talking && !c.ended && c.dir == "in" -> "リゲルが代わりに出ます"
                else -> "音声: リゲル"
            }
            Row(
                Modifier.width(272.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0x382B86D9))
                    .border(1.dp, Color(0x809FD8FF), RoundedCornerShape(24.dp)).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniOrb(glow, rigelTalking, Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text(label, color = CALL_TEXT, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            Spacer(Modifier.height(16.dp))
            val hint = when {
                c.talking || c.ended -> ""
                c.dir == "in" -> "リゲルが代わりに出て、用件を伺います"
                else -> "相手が出たら、リゲルが名乗って伝えます"
            }
            CallTranscript(c.lines, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), hint = hint, scroll = rememberScrollState())
            Spacer(Modifier.height(20.dp))
            HangupButton(Modifier.size(72.dp).alpha(if (c.ended) 0.38f else 1f)
                .semantics { contentDescription = "リゲルの電話を切る" }
                .clickable(enabled = !c.ended) { g.rigelCaller.hangup() })
        }
    }
}

/**
 * やり取り (文字)。左にリゲル・相手、右に言葉。scroll を渡すと欄の中で動かし、新しい言葉が来たら下まで送る
 * (上を読んでいるときは送らない)。渡さなければ、全部をそのまま並べる (記録の画面: 画面ごと動かす)
 */
@Composable
fun CallTranscript(lines: List<CallLine>, modifier: Modifier = Modifier, hint: String = "", scroll: ScrollState? = null) {
    val shape = RoundedCornerShape(17.dp)
    Column(modifier.clip(shape).background(Color(0xD9080D16)).border(1.dp, Color(0x2978B4FF), shape).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text("通話のやり取り（文字）", color = CALL_DIM, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (lines.isEmpty()) {
            if (hint.isNotEmpty()) Text(hint, color = CALL_DIM, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp))
            return@Column
        }
        if (scroll != null) {
            LaunchedEffect(scroll) {
                var lastMax = 0
                snapshotFlow { scroll.maxValue }.collect { m -> if (scroll.value >= lastMax - 48) scroll.animateScrollTo(m); lastMax = m }
            }
        }
        Column(if (scroll != null) Modifier.fillMaxWidth().verticalScroll(scroll) else Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEach { l ->
                val me = l.who == "rigel"
                Row {
                    Text(if (me) "リゲル" else "相手", color = if (me) CALL_RIGEL else CALL_GREEN, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.width(50.dp).padding(top = 3.dp))
                    Text(l.text, color = if (me) CALL_TEXT else CALL_PEER_TEXT, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 相手の丸 (人の形)。呼び出し中は、まわりに緑の輪が広がる (rings: 輪の場所を空けておく) */
@Composable
fun CallerCircle(ringing: Boolean, diameter: Dp, rings: Boolean = true) {
    val t = rememberInfiniteTransition(label = "ring")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Canvas(Modifier.size(if (rings) diameter * 1.9f else diameter)) {
        val r = diameter.toPx() / 2f
        if (ringing) for (k in 0 until 2) {
            val q = (p + k * 0.5f) % 1f
            drawCircle(CALL_GREEN.copy(alpha = 0.5f * (1f - q)), radius = r * (0.96f + 0.8f * q), style = Stroke(width = 2.dp.toPx()))
        }
        drawCircle(Brush.radialGradient(listOf(Color(0xFF2C4260), Color(0xFF142033)), center = center, radius = r), radius = r)
        drawCircle(Color(0x599FD8FF), radius = r, style = Stroke(width = 2.dp.toPx()))
        val fig = Color(0xFFB7CCE6)
        drawCircle(fig, radius = r * 0.27f, center = center + Offset(0f, -r * 0.2f))
        drawArc(fig, 180f, 180f, true, topLeft = center + Offset(-r * 0.5f, r * 0.2f), size = Size(r, r * 0.9f))
    }
}

/** 帯の中の小さなリゲル (暗いガラスの丸に光の縁と目。話している間は明るく) */
@Composable
fun MiniOrb(glow: Color, talking: Boolean, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val k by t.animateFloat(0f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "k")
    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(Brush.radialGradient(listOf(Color(0xFF1A2533), Color(0xFF080B10)), center = center, radius = r), radius = r)
        drawCircle(glow.copy(alpha = if (talking) 0.6f + 0.4f * k else 0.75f), radius = r * 0.92f, style = Stroke(width = r * 0.12f))
        val eye = Color(0xFFEAF6FF)
        val h = if (talking) r * (0.22f + 0.08f * k) else r * 0.26f
        for (sx in listOf(-1f, 1f)) drawLine(eye, center + Offset(sx * r * 0.28f, -h / 2), center + Offset(sx * r * 0.28f, h / 2),
            strokeWidth = r * 0.16f, cap = StrokeCap.Round)
    }
}

private val CALL_END_PATH = PathParser().parsePathString(
    "M12 9c-1.6 0-3.15.25-4.6.72v3.1c0 .39-.23.74-.56.9-.98.49-1.87 1.12-2.66 1.85-.18.18-.43.28-.7.28-.28 0-.53-.11-.71-.29L.29 13.08" +
        "c-.18-.17-.29-.42-.29-.7 0-.28.11-.53.29-.71C3.34 8.78 7.46 7 12 7s8.66 1.78 11.71 4.67c.18.18.29.43.29.71 0 .28-.11.53-.29.71" +
        "l-2.48 2.48c-.18.18-.43.29-.71.29-.27 0-.52-.11-.7-.28-.79-.74-1.69-1.36-2.67-1.85-.33-.16-.56-.5-.56-.9v-3.1C15.15 9.25 13.6 9 12 9z",
).toPath()

/** 赤い丸に受話器 (切る) */
@Composable
fun HangupButton(modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(Brush.verticalGradient(listOf(Color(0xFFF0535A), Color(0xFFC81E2A))), radius = r)
        drawCircle(Color(0x33FFFFFF), radius = r, style = Stroke(width = 1.5.dp.toPx()))
        val s = r * 1.05f / 24f
        translate(center.x - 12f * s, center.y - 12f * s + r * 0.04f) {
            scale(s, s, pivot = Offset.Zero) { drawPath(CALL_END_PATH, Color.White) }
        }
    }
}
