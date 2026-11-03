package com.rabi.envoymcp.music

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.ui.avatar.AvatarGeometry
import com.rabi.envoymcp.ui.avatar.Groove
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/*
 * 音楽の画面のクラブ DJ 風の演出。音声モードのリゲルのまわりは SpectrumRing のまま
 * - 拍で光る・低音でふくらむ: キックのたびに輪が明るくなり (4 拍に 1 回は強め)、輪の大きさが低い音に合わせて脈打つ
 * - ネオン: 光を足し算で重ね、白い芯を通す。色の組は 4 小節 (16 拍) ごとに替える (拍が無いときは 8 秒ごと)
 * - ピークの点 (少し止まってから落ちる) と、前の形を薄く残す光の尾
 * - 盛り上がり (ドロップ): 音の勢いが急に上がったら、背景にレーザーとステージの照明、拍ごとに輪から火花
 * - 波形の輪 (オシロスコープ)・輪の回転 (小節ごとに向きを変える)・上にテンポの LED 表示と 1〜4 拍の点
 * - リゲルに DJ のヘッドホン (音を聞いていなくても出す)
 * flash = false (設定「拍に合わせて光らせる」がオフ) のときは、拍で明るくする所を止め、レーザーも弱くする (光に敏感な人のため)。
 * 光るのは拍ごと (124 BPM で 1 秒に 2 回ほど。目安の 1 秒に 3 回より少ない) で、明るさの差も控えめにしている
 */

/** 演出の状態。1 つの毎フレームの計算で進め、後ろ (DjBackdrop) と前 (DjSpectrum) の 2 枚が読む */
@Stable
internal class DjState {
    val shown = FloatArray(SPECTRUM_BANDS)
    val peaks = FloatArray(SPECTRUM_BANDS)
    val hold = FloatArray(SPECTRUM_BANDS)
    val trails = Array(TRAILS) { FloatArray(SPECTRUM_BANDS) }
    val osc = FloatArray(OSC_N)
    var any = false
    var kick = 0f
    var bass = 0f
    var beats = 0
    var bpm: Double? = null
    var t = 0f
    /** 輪の向き (ラジアン) と、回る速さ */
    var rot = 0f
    var spin = 0f
    /** 盛り上がり (0〜1) */
    var hype = 0f
    /** 音の勢いの速い平均 (0.4 秒) と遅い平均 (8 秒)。0〜1 (この 1 秒の大きさ ÷ 最近の最大) */
    var energyFast = 0f
    var energySlow = 0f
    // 色の組 (前の組から今の組へ混ぜる)
    var target = 0
    var from = 0
    var to = 0
    var mix = 1f
    // 火花 (球の半径を 1 とした、球の中心からの位置と速さ)
    val sx = FloatArray(SPARKS); val sy = FloatArray(SPARKS); val svx = FloatArray(SPARKS); val svy = FloatArray(SPARKS); val life = FloatArray(SPARKS)
    var nextSpark = 0
    val frame = mutableLongStateOf(0L)

    /** 帯の位置 x (0 = 低い音 … 1 = 高い音) の色 */
    fun color(x: Float): Color = lerp(paletteColor(from, x), paletteColor(to, x), mix)

    private fun paletteColor(p: Int, x: Float): Color {
        val (a, b, c) = PALETTES[p]
        return if (x < 0.5f) lerp(a, b, x * 2f) else lerp(b, c, (x - 0.5f) * 2f)
    }
}

@Composable
internal fun rememberDjState(g: McpGraph): DjState {
    val st = remember { DjState() }
    LaunchedEffect(Unit) {
        var last = 0L
        var trailAt = 0L
        var beatStart = Long.MIN_VALUE
        var ef = 0f; var es = 0f
        var dropStart = 0L; var dropUntil = 0L
        var oscGain = 0f
        val rnd = Random(7)
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                st.t += dt
                val src = g.music.spectrum.value
                var lit = 0
                for (i in 0 until SPECTRUM_BANDS) {
                    val target = if (src.size == SPECTRUM_BANDS) src[i] else 0f
                    st.shown[i] = if (target > st.shown[i]) st.shown[i] + (target - st.shown[i]) * minOf(1f, dt * 25f) else maxOf(target, st.shown[i] - dt * 1.4f)
                    if (st.shown[i] >= st.peaks[i]) { st.peaks[i] = st.shown[i]; st.hold[i] = PEAK_HOLD_S }
                    else if (st.hold[i] > 0f) st.hold[i] -= dt
                    else st.peaks[i] = maxOf(st.shown[i], st.peaks[i] - dt * PEAK_FALL)
                    if (st.shown[i] > 0.001f) lit++
                }
                st.any = src.isNotEmpty() || lit > 0
                // 光の尾: 少し前の形を、いくつか残す
                if (now - trailAt > TRAIL_EVERY_NS) {
                    trailAt = now
                    for (j in TRAILS - 1 downTo 1) st.trails[j - 1].copyInto(st.trails[j])
                    st.shown.copyInto(st.trails[0])
                }
                // 波形の輪: 大きさをならして (小さな音でも見えるように)、少しなめらかに
                val wv = g.music.waveform.value
                var mx = 0f
                for (x in wv) mx = maxOf(mx, abs(x))
                oscGain = maxOf(mx, oscGain * 0.995f, 0.02f)
                for (i in 0 until OSC_N) {
                    val target = if (wv.size >= OSC_N) wv[i * wv.size / OSC_N] / oscGain else 0f
                    st.osc[i] += (target - st.osc[i]) * minOf(1f, dt * 30f)
                }
                var bass = 0f
                for (i in 0 until 8) bass += st.shown[i]
                st.bass = bass / 8f
                // 拍: キックの光 (拍からの時間で消える)。拍を数えて、4 拍に 1 回は強く、16 拍で色を、4 拍で回る向きを替える
                val ms = SystemClock.elapsedRealtime()
                // 音楽の表情の試しの間は、筋書きの拍と強さを使う
                val demo = g.musicStage.demoActive
                val b = if (demo) g.musicStage.demoBeat(ms) else g.music.beat.value
                st.bpm = b?.bpm
                var onBeat = false
                if (b != null) {
                    val ph = b.phaseAt(ms)
                    val start = ms - (ph * b.periodMs).toLong()
                    if (beatStart == Long.MIN_VALUE || start - beatStart > b.periodMs * 0.5) { st.beats++; beatStart = start; onBeat = true }
                    st.kick = exp(-ph * 5.0).toFloat() * if (st.beats % 4 == 0) 1f else 0.7f
                    st.target = (st.beats / 16) % PALETTES.size
                } else {
                    st.kick = maxOf(0f, st.kick - dt * 3f)
                    beatStart = Long.MIN_VALUE
                    st.target = ((ms / 8_000) % PALETTES.size).toInt()
                }
                if (st.target != st.to) { st.from = st.to; st.to = st.target; st.mix = 0f }
                st.mix = minOf(1f, st.mix + dt / 0.6f)
                // 回転: 小節 (4 拍) ごとに向きを替えて、ゆっくり揺れる (拍が無いときは 8 秒ごと)
                val dir = if (b != null) (if ((st.beats / 4) % 2 == 0) 1f else -1f) else (if ((ms / 8_000) % 2 == 0L) 1f else -1f)
                st.spin += (dir * SPIN - st.spin) * minOf(1f, dt / 0.35f)
                st.rot += st.spin * dt
                // 盛り上がり: 音の勢い (この 1 秒の大きさ ÷ 最近の最大) の、速い平均が遅い平均より急に上がったら、10 秒ほど
                val e = (if (demo) g.musicStage.demoEnergy(ms) else g.music.status.value.energy).toFloat()
                ef += (e - ef) * (1 - exp(-dt / 0.4f)); es += (e - es) * (1 - exp(-dt / 8f))
                st.energyFast = ef; st.energySlow = es
                if (b != null && ef > 0.85f && ef - es > 0.05f && ms > dropUntil) { dropStart = ms; dropUntil = ms + DROP_MS }
                if (ms < dropUntil && ef > 0.92f && ms - dropStart < DROP_MAX_MS) dropUntil = maxOf(dropUntil, ms + 2_000)
                val want = if (ms < dropUntil) 1f else 0f
                st.hype += (want - st.hype) * minOf(1f, dt / if (want > st.hype) 0.5f else 1.5f)
                // 火花: 盛り上がっている間、拍ごとに輪から外へ
                if (onBeat && st.hype > 0.4f) repeat(if (st.beats % 4 == 0) 22 else 12) {
                    val a = rnd.nextFloat() * 2 * PI.toFloat(); val sp = 1.4f + rnd.nextFloat() * 1.6f
                    val k = st.nextSpark; st.nextSpark = (k + 1) % SPARKS
                    st.sx[k] = cos(a) * 1.7f; st.sy[k] = sin(a) * 1.7f; st.svx[k] = cos(a + 0.25f) * sp; st.svy[k] = sin(a + 0.25f) * sp; st.life[k] = 1f
                }
                for (k in 0 until SPARKS) if (st.life[k] > 0f) {
                    st.sx[k] += st.svx[k] * dt; st.sy[k] += st.svy[k] * dt
                    st.svx[k] *= 1 - dt * 1.6f; st.svy[k] = st.svy[k] * (1 - dt * 1.6f) + dt * 0.6f      // 少し落ちる
                    st.life[k] -= dt / SPARK_LIFE_S
                }
                st.frame.longValue = now
            }
        }
    }
    return st
}

/** 後ろ (リゲルの球より奥): 盛り上がりのレーザーとステージの照明 */
@Composable
internal fun DjBackdrop(st: DjState, flash: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        st.frame.longValue
        val h = st.hype
        if (h < 0.01f) return@Canvas
        val k = if (flash) st.kick else 0f
        val power = if (flash) 1f else 0.5f
        // ステージの照明 (上から 3 本。ゆっくり振る)
        for (j in 0 until 3) {
            val apex = Offset(size.width * (0.2f + 0.3f * j), -size.height * 0.02f)
            val a = (90f + 22f * sin(st.t * 0.55f + j * 2.1f)) * PI.toFloat() / 180f
            val half = 0.16f
            val len = size.height * 1.05f
            val p1 = apex + Offset(cos(a - half), sin(a - half)) * len
            val p2 = apex + Offset(cos(a + half), sin(a + half)) * len
            val col = st.color(j / 2f)
            val path = Path().apply { moveTo(apex.x, apex.y); lineTo(p1.x, p1.y); lineTo(p2.x, p2.y); close() }
            drawPath(path, Brush.linearGradient(listOf(col.copy(alpha = 0.16f * h * power), Color.Transparent), start = apex, end = apex + Offset(cos(a), sin(a)) * len), blendMode = BlendMode.Plus)
        }
        // レーザー (下の左右の角から 4 本ずつ。交差しながら振る)
        for (j in 0 until 8) {
            val left = j < 4
            val origin = Offset(if (left) size.width * 0.06f else size.width * 0.94f, size.height * 1.01f)
            val base = if (left) -62f else -118f
            val a = (base + (if (left) 1 else -1) * (-14f + 9f * (j % 4)) + 26f * sin(st.t * 1.1f + j * 0.9f)) * PI.toFloat() / 180f
            val end = origin + Offset(cos(a), sin(a)) * (size.height * 1.4f)
            val col = st.color((j % 4) / 3f)
            drawLine(col.copy(alpha = (0.10f + 0.08f * k) * h * power), origin, end, strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(col.copy(alpha = (0.55f + 0.25f * k) * h * power), origin, end, strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round, blendMode = BlendMode.Plus)
        }
    }
}

/**
 * 前 (リゲルの球の上): 波形の輪・スペクトラムの輪・火花・ヘッドホン・テンポの表示。
 * lift: リゲルを下げている量 (px。歌詞の舞台を上に作るとき。Avatar にも同じだけ上の余白を付ける)。compactTempo: テンポを左上に小さく
 */
@Composable
internal fun DjSpectrum(st: DjState, flash: Boolean, lift: Float, compactTempo: Boolean, modifier: Modifier, groove: Groove? = null) {
    val density = LocalDensity.current
    val text = rememberTextMeasurer()
    Canvas(modifier) {
        st.frame.longValue                                           // 毎フレーム描き直す
        val c = djCenter(size.width, size.height, lift)
        val orb = djOrb(size.width, size.height, lift, with(density) { 360.dp.toPx() })
        val k = if (flash) st.kick else 0f
        val accent = st.color(0.5f)
        // 低い音でふくらむ (光らせない設定でも、動きは残す)
        val inner = orb * 1.22f * (1f + 0.06f * st.bass * st.bass + 0.03f * st.kick)
        val maxLen = minOf(size.width, size.height) * 0.22f
        if (st.any) {
            val n = SPECTRUM_BANDS * 2                               // 左右に鏡写し (低い音が上、高い音が下)
            val w = (2 * PI.toFloat() * inner / n) * 0.55f
            // 拍で、輪のまわりがふわっと光る (輪の外側だけ。リゲルの球は覆わない)
            if (k > 0.02f) {
                val outer = inner + maxLen * 1.25f
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.Transparent, (inner * 0.92f / outer) to Color.Transparent,
                        (inner * 1.05f / outer) to accent.copy(alpha = 0.22f * k), 1f to Color.Transparent,
                        center = c, radius = outer,
                    ),
                    radius = outer, center = c, blendMode = BlendMode.Plus,
                )
            }
            drawOscilloscope(st, c, orb, accent)
            val len = { v: Float -> maxLen * (0.03f + 0.97f * v) }
            for (i in 0 until n) {
                val band = if (i < SPECTRUM_BANDS) i else n - 1 - i
                val ang = (-PI / 2 + (i + 0.5) * 2 * PI / n).toFloat() + st.rot
                val dir = Offset(cos(ang), sin(ang))
                val col = st.color(band / (SPECTRUM_BANDS - 1f))
                val l = len(st.shown[band])
                val from = c + dir * inner
                val to = c + dir * (inner + l)
                // 光の尾 (今より長かった形の先だけ、古いほど薄く)
                for (j in TRAILS - 1 downTo 0) {
                    val lt = len(st.trails[j][band])
                    if (lt > l + 1f) drawLine(col.copy(alpha = 0.16f * (TRAILS - j) / TRAILS), to, c + dir * (inner + lt), strokeWidth = w * 0.8f, cap = StrokeCap.Round, blendMode = BlendMode.Plus)
                }
                drawLine(col.copy(alpha = 0.16f + 0.22f * k), from, to, strokeWidth = w * 3.0f, cap = StrokeCap.Round, blendMode = BlendMode.Plus)   // ネオンの光
                drawLine(col.copy(alpha = 0.95f), from, to, strokeWidth = w, cap = StrokeCap.Round)
                drawLine(Color.White.copy(alpha = 0.30f + 0.35f * k), from, to, strokeWidth = w * 0.35f, cap = StrokeCap.Round, blendMode = BlendMode.Plus)   // 白い芯
                // ピークの点
                val pl = len(st.peaks[band])
                drawCircle(lerp(col, Color.White, 0.45f), radius = w * 0.42f, center = c + dir * (inner + pl + w * 1.3f))
            }
            // 火花
            for (s in 0 until SPARKS) {
                val lf = st.life[s]; if (lf <= 0f) continue
                val p = c + Offset(st.sx[s], st.sy[s]) * orb
                val tail = p - Offset(st.svx[s], st.svy[s]) * (orb * 0.06f)
                val col = lerp(st.color(s % 7 / 6f), Color.White, 0.35f)
                drawLine(col.copy(alpha = 0.9f * lf), tail, p, strokeWidth = 3.dp.toPx() * (0.5f + 0.5f * lf), cap = StrokeCap.Round, blendMode = BlendMode.Plus)
            }
        }
        // ヘッドホンは、音楽に合わせて揺れるリゲルの体 (上下のずれ・傾き) に合わせる
        val hc = c + Offset(0f, (groove?.outOff ?: 0f) * orb)
        rotate(groove?.outTilt ?: 0f, pivot = hc) { drawHeadphones(hc, orb, k, st.kick, accent) }
        if (compactTempo) drawTempoCompact(text, st, accent) else drawTempo(text, st, c.y - inner - maxLen * 1.05f, accent)
    }
}

/** 波形の輪 (オシロスコープ): 球と輪の間に、音の波形を細い光の線で */
private fun DrawScope.drawOscilloscope(st: DjState, c: Offset, orb: Float, accent: Color) {
    val r0 = orb * 1.10f
    val amp = orb * 0.07f
    val path = Path()
    for (i in 0..OSC_N) {
        val v = st.osc[i % OSC_N]
        val a = (-PI / 2 + i * 2 * PI / OSC_N).toFloat() - st.rot * 0.5f
        val r = r0 + amp * v
        val x = c.x + r * cos(a); val y = c.y + r * sin(a)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val col = lerp(accent, Color.White, 0.5f)
    drawPath(path, col.copy(alpha = 0.22f), style = Stroke(width = 7.dp.toPx(), join = StrokeJoin.Round), blendMode = BlendMode.Plus)
    drawPath(path, col.copy(alpha = 0.85f), style = Stroke(width = 1.8.dp.toPx(), join = StrokeJoin.Round), blendMode = BlendMode.Plus)
}

/** リゲルの球の中心と半径 (上に lift だけ余白を付けた Avatar と同じ。歌詞の舞台を作るときに下げる) */
internal fun djCenter(w: Float, h: Float, lift: Float) = AvatarGeometry.center(w, h - lift, true) + Offset(0f, lift)
internal fun djOrb(w: Float, h: Float, lift: Float, maxDiameterPx: Float) = AvatarGeometry.radius(w, h - lift, true, maxDiameterPx)

/** 歌詞を出している間のテンポの表示 (左上に小さく。数字と 1〜4 拍の点を横に並べる) */
private fun DrawScope.drawTempoCompact(text: TextMeasurer, st: DjState, accent: Color) {
    val bpm = st.bpm
    val num = text.measure(bpm?.let { "%3d".format(it.toInt()) } ?: "---", TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp,
        color = if (bpm != null) lerp(accent, Color.White, 0.35f) else Color.White.copy(alpha = 0.2f), shadow = if (bpm != null) Shadow(accent, blurRadius = 14f) else null))
    val label = text.measure("BPM", TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Color(0xFF9FB0C8)))
    val x0 = 20.dp.toPx(); val y0 = 18.dp.toPx()
    drawText(num, topLeft = Offset(x0, y0))
    drawText(label, topLeft = Offset(x0 + num.size.width + 4.dp.toPx(), y0 + num.size.height - label.size.height - 4.dp.toPx()))
    val dot = 3.5f.dp.toPx(); val gap = 12.dp.toPx(); val cx0 = x0 + num.size.width + label.size.width + 18.dp.toPx(); val cy = y0 + num.size.height / 2f
    for (i in 0 until 4) {
        val on = bpm != null && st.beats.mod(4) == i
        drawCircle(if (on) (if (i == 0) Color(0xFFFFE2A8) else accent) else Color(0xFF253041), radius = dot, center = Offset(cx0 + gap * i, cy))
    }
}

/** 上のテンポの表示 (DJ ソフトの LED の数字): 薄い「888」の上に光る数字、その下に 1〜4 拍の点 */
private fun DrawScope.drawTempo(text: TextMeasurer, st: DjState, bottom: Float, accent: Color) {
    val digits = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 60.sp, letterSpacing = 2.sp)
    val ghost = text.measure("888", digits.copy(color = Color.White.copy(alpha = 0.07f)))
    val bpm = st.bpm
    val shown = text.measure(bpm?.let { "%3d".format(it.toInt()) } ?: "---",
        digits.copy(color = if (bpm != null) lerp(accent, Color.White, 0.35f) else Color.White.copy(alpha = 0.18f), shadow = if (bpm != null) Shadow(accent, blurRadius = 28f) else null))
    val label = text.measure("BPM", TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, color = Color(0xFF9FB0C8)))
    val dot = 7.dp.toPx(); val gap = 26.dp.toPx(); val dotsH = dot * 2 + 10.dp.toPx()
    val top = maxOf(28.dp.toPx(), bottom - ghost.size.height - dotsH - 24.dp.toPx())
    val total = ghost.size.width + 10.dp.toPx() + label.size.width
    val x0 = (size.width - total) / 2f
    drawText(ghost, topLeft = Offset(x0, top))
    drawText(shown, topLeft = Offset(x0, top))
    drawText(label, topLeft = Offset(x0 + ghost.size.width + 10.dp.toPx(), top + ghost.size.height - label.size.height - 10.dp.toPx()))
    val cy = top + ghost.size.height + dot + 6.dp.toPx()
    val cx0 = size.width / 2f - gap * 1.5f
    for (i in 0 until 4) {
        val on = bpm != null && st.beats.mod(4) == i          // 0 番目 (金色) が強い拍
        val col = if (i == 0) Color(0xFFFFE2A8) else accent
        drawCircle(if (on) col.copy(alpha = 0.35f) else Color.Transparent, radius = dot * 2.2f, center = Offset(cx0 + gap * i, cy), blendMode = BlendMode.Plus)
        drawCircle(if (on) col else Color(0xFF253041), radius = dot * (if (i == 0) 1.15f else 1f), center = Offset(cx0 + gap * i, cy))
    }
}

/**
 * リゲルの DJ のヘッドホン (頭の上のバンドと、左右の耳あて)。球の大きさに合わせる。
 * 球は少し浮き沈みする (半径の 1% ほど) ので、球よりひと回り大きく描いてずれを目立たせない。拍で少しはねる
 */
private fun DrawScope.drawHeadphones(c: Offset, r: Float, glow: Float, bounce: Float, accent: Color) {
    val y = c.y - r * 0.025f * bounce
    val br = r * 1.14f
    val body = Color(0xFF1B2130)
    val edge = Color(0xFF3A4458)
    // バンド (上の弧)
    drawArc(body, 196f, 148f, false, topLeft = Offset(c.x - br, y - br), size = Size(br * 2, br * 2), style = Stroke(width = r * 0.13f, cap = StrokeCap.Round))
    drawArc(edge, 196f, 148f, false, topLeft = Offset(c.x - br, y - br), size = Size(br * 2, br * 2), style = Stroke(width = r * 0.03f, cap = StrokeCap.Round))
    val nr = br + r * 0.075f
    drawArc(accent.copy(alpha = 0.55f + 0.35f * glow), 214f, 112f, false, topLeft = Offset(c.x - nr, y - nr), size = Size(nr * 2, nr * 2),
        style = Stroke(width = r * 0.022f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
    // 耳あて
    for (s in intArrayOf(-1, 1)) {
        val endA = (if (s < 0) 196f else 344f) * PI.toFloat() / 180f
        val end = Offset(c.x + br * cos(endA), y + br * sin(endA))
        val cx = c.x + s * r * 1.07f
        val cy = y + r * 0.10f
        val cw = r * 0.30f
        val ch = r * 0.58f
        drawLine(edge, end, Offset(cx, cy - ch * 0.42f), strokeWidth = r * 0.07f, cap = StrokeCap.Round)          // バンドから耳あてへ
        drawRoundRect(body, topLeft = Offset(cx - cw / 2, cy - ch / 2), size = Size(cw, ch), cornerRadius = CornerRadius(cw * 0.48f))
        drawRoundRect(Color(0xFF0E121A), topLeft = Offset(cx - cw * 0.32f - s * cw * 0.12f, cy - ch * 0.40f), size = Size(cw * 0.64f, ch * 0.80f), cornerRadius = CornerRadius(cw * 0.32f))
        drawRoundRect(accent.copy(alpha = 0.75f + 0.25f * glow), topLeft = Offset(cx - cw / 2, cy - ch / 2), size = Size(cw, ch), cornerRadius = CornerRadius(cw * 0.48f),
            style = Stroke(width = r * 0.022f), blendMode = BlendMode.Plus)
        drawCircle(accent.copy(alpha = 0.45f + 0.55f * glow), radius = r * 0.035f, center = Offset(cx + s * cw * 0.12f, cy - ch * 0.26f), blendMode = BlendMode.Plus)   // 小さな明かり
    }
}

/** ネオンの色の組 (低い音・真ん中・高い音)。4 小節ごとに次へ */
private val PALETTES = listOf(
    Triple(Color(0xFFFF2BD6), Color(0xFF8A5CFF), Color(0xFF20E3FF)),   // マゼンタ → 紫 → シアン
    Triple(Color(0xFFB6FF3B), Color(0xFF00FFC6), Color(0xFF2F7BFF)),   // ライム → アクア → 青
    Triple(Color(0xFFFF8A00), Color(0xFFFF3D7F), Color(0xFFB04DFF)),   // オレンジ → ピンク → 紫
    Triple(Color(0xFF00E5FF), Color(0xFFFFFFFF), Color(0xFFFF2BD6)),   // シアン → 白 → マゼンタ
)
private const val TRAILS = 5
private const val TRAIL_EVERY_NS = 45_000_000L
private const val PEAK_HOLD_S = 0.25f
private const val PEAK_FALL = 0.5f
private const val OSC_N = 128
private const val SPARKS = 120
private const val SPARK_LIFE_S = 0.9f
/** 輪が回る速さ (ラジアン/秒。小節ごとに向きが替わるので、行って戻る揺れになる) */
private const val SPIN = 0.10f
private const val DROP_MS = 10_000L
private const val DROP_MAX_MS = 24_000L
