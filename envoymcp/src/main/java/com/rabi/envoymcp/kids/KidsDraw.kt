package com.rabi.envoymcp.kids

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** お勉強の画面の絵。どれもアプリが自分で描く (写真や借りた絵は使わない) */
internal object KidsColors {
    val Bg = Color(0xFF0E1630)
    val Card = Color(0xFF223259)
    val Text = Color(0xFFFFFFFF)
    val Soft = Color(0xFFB9C6E4)
    val Star = Color(0xFFFFD54F)
    val StarOff = Color(0xFF3A4A72)
    val Right = Color(0xFF43A047)
    val Retry = Color(0xFFFFB74D)
    /** 丸の色 (Visual.Dots の kind ごと) */
    val Dots = listOf(Color(0xFFFF6B6B), Color(0xFFFFD54F), Color(0xFF66BB6A), Color(0xFF4FC3F7))
    /** 題ごとのボタンの色 (かず・ひらがな・とけい) */
    fun topic(t: Topic): Color = when (t) {
        Topic.KAZU -> Color(0xFF2E7D6B)
        Topic.HIRAGANA -> Color(0xFF8E3B76)
        Topic.TOKEI -> Color(0xFF2F5FA8)
    }
}

/** 丸を n こ。5 こずつ 1 行に並べる (6 こ以上は 2 行。数えやすいように) */
@Composable
internal fun Dots(n: Int, kind: Int, modifier: Modifier = Modifier) {
    val color = KidsColors.Dots[kind.mod(KidsColors.Dots.size)]
    Canvas(modifier) {
        if (n <= 0) return@Canvas
        val cols = min(n, 5)
        val rows = (n + 4) / 5
        val cell = min(size.width / cols, size.height / rows)
        val r = cell * 0.38f
        val top = (size.height - cell * rows) / 2f
        for (i in 0 until n) {
            val row = i / 5
            val inRow = if (row == rows - 1) n - row * 5 else 5
            val left = (size.width - cell * min(inRow, cols)) / 2f
            val c = Offset(left + cell * (i % 5) + cell / 2f, top + cell * row + cell / 2f)
            drawCircle(color, r, c)
            // 少し光って見えるように (丸いつや)
            drawCircle(Color.White.copy(alpha = 0.35f), r * 0.28f, c + Offset(-r * 0.35f, -r * 0.35f))
        }
    }
}

/** 針の時計 (h 時 m 分)。短い針は赤、長い針は青 */
@Composable
internal fun ClockFace(h: Int, m: Int, modifier: Modifier = Modifier, numbers: Boolean = true) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val r = size.minDimension / 2f * 0.96f
        val c = center
        drawCircle(Color.White, r, c)
        drawCircle(Color(0xFF2B3A55), r, c, style = Stroke(r * 0.06f))
        // 目盛り (分は小さな点、時は少し大きな点)
        for (i in 0 until 60) {
            val a = (i * 6.0 - 90.0) * PI / 180.0
            val p = Offset(c.x + (cos(a) * r * 0.9).toFloat(), c.y + (sin(a) * r * 0.9).toFloat())
            drawCircle(Color(0xFF6B7894), if (i % 5 == 0) r * 0.025f else r * 0.012f, p)
        }
        if (numbers) for (i in 1..12) {
            val a = (i * 30.0 - 90.0) * PI / 180.0
            val p = Offset(c.x + (cos(a) * r * 0.72).toFloat(), c.y + (sin(a) * r * 0.72).toFloat())
            val text = measurer.measure("$i", TextStyle(fontSize = (r * 0.17f).toSp(), fontWeight = FontWeight.Bold, color = Color(0xFF1B2333)))
            drawText(text, topLeft = p - Offset(text.size.width / 2f, text.size.height / 2f))
        }
        hand(c, ((h % 12) + m / 60.0) * 30.0, r * 0.48f, r * 0.085f, Color(0xFFE53935))
        hand(c, m * 6.0, r * 0.74f, r * 0.05f, Color(0xFF1E88E5))
        drawCircle(Color(0xFF1B2333), r * 0.07f, c)
    }
}

/** 針 (deg は 12 時から時計回りの角度) */
private fun DrawScope.hand(c: Offset, deg: Double, len: Float, width: Float, color: Color) {
    val a = (deg - 90.0) * PI / 180.0
    drawLine(color, c, Offset(c.x + (cos(a) * len).toFloat(), c.y + (sin(a) * len).toFloat()), strokeWidth = width, cap = StrokeCap.Round)
}

/** 星の形 (5 つのとがり) */
internal fun starPath(c: Offset, outer: Float, inner: Float = outer * 0.45f): Path = Path().apply {
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val a = (i * 36.0 - 90.0) * PI / 180.0
        val x = c.x + (cos(a) * r).toFloat()
        val y = c.y + (sin(a) * r).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** 星 1 つ (on なら黄色、まだなら暗い色) */
@Composable
internal fun Star(on: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawPath(starPath(center, size.minDimension / 2f), if (on) KidsColors.Star else KidsColors.StarOff) }
}

/** 三日月 (時間になった画面) */
@Composable
internal fun Moon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f * 0.9f
        drawCircle(Color(0xFFFFE082), r, center)
        drawCircle(KidsColors.Bg, r * 0.86f, center + Offset(r * 0.42f, -r * 0.22f))
    }
}

/** 正しく選んだとき: 大きな星がぽんと出て、消える (seq が増えたときだけ。画面を開いたときは出さない) */
@Composable
internal fun StarBurst(seq: Int, modifier: Modifier = Modifier) {
    val first = remember { seq }
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(seq) {
        if (seq == first) return@LaunchedEffect
        alpha.snapTo(1f)
        scale.snapTo(0.2f)
        launch { scale.animateTo(1.15f, tween(320, easing = FastOutSlowInEasing)); scale.animateTo(1f, tween(180)) }
        delay(850)
        alpha.animateTo(0f, tween(450))
    }
    if (alpha.value <= 0f) return
    Canvas(modifier.fillMaxSize()) {
        val r = size.minDimension * 0.3f * scale.value
        drawPath(starPath(center, r), KidsColors.Star.copy(alpha = alpha.value))
        drawPath(starPath(center, r * 0.55f), Color.White.copy(alpha = alpha.value * 0.35f))
    }
}
