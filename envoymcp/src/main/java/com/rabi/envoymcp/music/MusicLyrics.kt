package com.rabi.envoymcp.music

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.McpGraph
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/*
 * 音楽の画面の歌詞 = キネティック・タイポグラフィ (下のカラオケの字幕は出さない)。
 * 設定「音楽の画面に歌詞を出す」がオンで、LRCLIB に時刻付きの歌詞があるときだけ。時刻は再生位置 (NowPlaying.positionMs) から
 * - 舞台: 歌詞がある間は、リゲルと輪を少し下げて、画面の上 (テンポの表示の下〜輪の上) を歌詞の舞台にする (MusicStage)
 * - 前 (LyricsFront): 今の行。歌われる時刻に、1 文字 (英語は 1 語) ずつ現れる。現れ方は行ごとに替える
 *     静か: 上から落ちてはずむ (FALL) / 下から波のように浮かぶ (WAVE)
 *     盛り上がり: 奥から叩きつけ、衝撃の輪が広がる (SLAM) / 散らばった光の粒が集まって文字になる (CONVERGE)
 *   歌っている間は、行全体がゆっくり近づき (カメラが寄る)、文字は 1 つずつ息をするように揺れ、拍で脈打つ。動く文字には残像。
 *   歌い終わると光の帯が行をなでる。盛り上がりの強い拍では、色が一瞬ずれる (赤と青のにじみ)
 * - 奥 (LyricsBack。リゲルの球より奥): 終わった行の文字がばらけて、奥へ吸い込まれるように小さく、ぼけながら降っていく。
 *   さらに奥に、今の行を巨大な薄い文字でゆっくり流す。細かな光の粒が降り続ける
 * - 光らせる設定がオフなら、拍の脈打ち・色のずれ・着くときの光を止める (光に敏感な人のため)
 */

/** 現れ方。enterMs: 動きの長さ、land: 動きのどこで読めるようになるか (その瞬間を、歌われる時刻に合わせる) */
internal enum class Reveal(val enterMs: Float, val land: Float) { FALL(420f, 0.6f), WAVE(650f, 0.6f), SLAM(280f, 0.5f), CONVERGE(620f, 0.75f) }

/** 1 文字 (英語は 1 語) */
internal class Glyph(
    val layout: TextLayoutResult, val sungMs: Long, val homeX: Float, val homeY: Float, val col: Float, val font: Float,
    /** 行の中の位置 (0〜1。上の段から順に。光の帯がなでる順) */
    val xn: Float, val idx: Int, val seed: Int,
) {
    var released = false
    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f; var rot = 0f; var vrot = 0f; var relT = 0f; var s0 = 1f
}

internal class LyricsRain {
    val glyphs = ArrayList<Glyph>()
    var line = -1
    var lineMs = 0L
    var spanMs = 1_000L
    /** 今の行を歌っている長さ (見積もり。文字はこの間に順に現れ、終わると光の帯がなでる) */
    var singMs = 1_000L
    var reveal = Reveal.FALL
    var shown = 0
    var cx = 0f; var cy = 0f
    var big: TextLayoutResult? = null
    var w = 0f; var h = 0f; var areaTop = 0f; var areaBottom = 0f
    var pos = 0L
    var t = 0f
    val dx = FloatArray(DUST); val dy = FloatArray(DUST); val dv = FloatArray(DUST); val ds = FloatArray(DUST); val dp = FloatArray(DUST)
    var dustReady = false
    val frame = mutableLongStateOf(0L)

    /** 1 文字の形 (語を割るときに使う。同じ文字・同じ大きさは使い回す) */
    private val letterCache = HashMap<String, TextLayoutResult>()
    fun letter(ch: String, font: Float, measurer: TextMeasurer, density: Density): TextLayoutResult {
        if (letterCache.size > 400) letterCache.clear()
        return letterCache.getOrPut("$ch|${font.toInt()}") { measurer.measure(ch, styleOf(font, density), density = density) }
    }

    /** 今の行が、歌い始めからどこまで進んだか (0〜1) */
    fun hold() = ((pos - lineMs).toFloat() / spanMs).coerceIn(0f, 1f)
}

@Composable
internal fun rememberLyricsRain(g: McpGraph, dj: DjState): LyricsRain {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val st = remember { LyricsRain() }
    LaunchedEffect(Unit) {
        val rnd = Random(11)
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                st.t += dt
                val w = st.w; val h = st.h
                if (w <= 0f || st.areaBottom <= st.areaTop) { st.frame.longValue = now; return@withFrameNanos }
                if (!st.dustReady) {
                    for (i in 0 until DUST) { st.dx[i] = rnd.nextFloat() * w; st.dy[i] = rnd.nextFloat() * h; st.dp[i] = 0.3f + 0.7f * rnd.nextFloat(); st.dv[i] = (14f + 30f * st.dp[i]) * density.density; st.ds[i] = (0.8f + 2.2f * st.dp[i]) * density.density }
                    st.dustReady = true
                }
                for (i in 0 until DUST) { st.dy[i] += st.dv[i] * dt * (1f + dj.hype); if (st.dy[i] > h + 10f) { st.dy[i] = -10f; st.dx[i] = rnd.nextFloat() * w } }
                val lyr = g.lyrics.state.value
                val p = g.nowPlaying.positionMs()
                if (lyr.status == LyricsRepo.Status.FOUND && p != null) {
                    val pos = p + LEAD_MS + g.lyrics.offset.value               // 曲ごとのずれ (左右のスワイプで直したもの)
                    val back = pos < st.pos - 1_500
                    st.pos = pos
                    val li = Lyrics.lineAt(lyr.lines, pos + SWITCH_AHEAD_MS)       // 新しい行は少し前から用意する (最初の文字が歌に間に合うように)
                    if (li != st.line || back) {
                        release(st, dj, rnd, measurer, density)
                        st.line = li
                        lyr.lines.getOrNull(li)?.takeIf { it.text.isNotBlank() }?.let { buildLine(st, lyr.lines, li, measurer, density, dj) }
                    }
                } else if (st.line != -1) { release(st, dj, rnd, measurer, density); st.line = -1; st.big = null }
                // ばらけた文字: 少し奥へ押されてから、回りながら落ちていく
                val grav = (GRAVITY_DP + 220f * dj.hype) * density.density
                val it = st.glyphs.iterator()
                while (it.hasNext()) {
                    val gl = it.next()
                    if (!gl.released) continue
                    gl.relT += dt
                    val fall = if (gl.relT < 0.35f) 0.15f else 1f
                    gl.vy += grav * dt * fall; gl.vx *= 1f - 0.5f * dt
                    gl.x += gl.vx * dt; gl.y += gl.vy * dt; gl.rot += gl.vrot * dt
                    if (gl.relT > RELEASE_LIFE_S || gl.y > h + gl.font * 2) it.remove()
                }
                st.frame.longValue = now
            }
        }
    }
    return st
}

/** 前: 今の行 (舞台の上で、くっきり大きく) */
@Composable
internal fun LyricsFront(st: LyricsRain, dj: DjState, flash: Boolean, lift: Float, modifier: Modifier) {
    val density = LocalDensity.current
    Canvas(modifier) {
        st.frame.longValue
        // 舞台: テンポの表示 (左上) の下から、輪の上まで
        val c = djCenter(size.width, size.height, lift)
        val orb = djOrb(size.width, size.height, lift, with(density) { 360.dp.toPx() })
        val maxLen = minOf(size.width, size.height) * 0.22f
        st.w = size.width; st.h = size.height
        st.areaTop = 64.dp.toPx(); st.areaBottom = c.y - orb * 1.22f - maxLen * 0.45f
        val hold = st.hold()
        val push = 1f + 0.06f * smooth(hold)                         // 歌っている間、行がゆっくり近づく
        val sungEnd = st.lineMs + st.singMs
        val sweepT = (st.pos - sungEnd) / 700f                       // 歌い終わったら光の帯がなでる
        val kick = if (flash) dj.kick else 0f
        val downbeat = dj.beats % 4 == 0
        for (gl in st.glyphs) {
            if (gl.released) continue
            // 歌われる時刻に、ちょうど読めるようになるように (動き始めを、現れ方ごとに前倒しする)
            val k = ((st.pos - gl.sungMs) / st.reveal.enterMs + st.reveal.land).coerceIn(0f, 1f)
            if (k <= 0f) continue
            val col = dj.color(gl.col)
            val bx = st.cx + (gl.homeX - st.cx) * push
            val by = st.cy + (gl.homeY - st.cy) * push + sin(st.t * 2.2f + gl.idx * 0.55f) * 2.dp.toPx() * k   // 息をするように揺れる
            val m = motion(st.reveal, k, gl.font)
            val band = if (sweepT in 0f..1.3f) exp(-((sweepT * 1.5f - 0.25f - gl.xn) / 0.12f).pow(2)) else 0f
            val pulse = 1f + 0.05f * kick
            // 残像 (動いている間だけ)
            if (k < 1f && st.reveal != Reveal.CONVERGE) for (e in 1..2) {
                val ke = (k - 0.14f * e).coerceAtLeast(0f); if (ke <= 0f) continue
                val me = motion(st.reveal, ke, gl.font)
                glyph(gl, bx + me.dx, by + me.dy, me.scale * push, me.rot, col, me.alpha * (0.24f / e) * (1f - k), 0f, 0f)
            }
            // 光の粒が集まって文字になる
            if (st.reveal == Reveal.CONVERGE && k < 1f) converge(gl, bx, by, k, col)
            // 着くときの光と、叩きつけたときの衝撃の輪
            if (flash && m.flare > 0.01f) {
                val r = gl.font * 1.4f
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.55f * m.flare), Color.Transparent), center = Offset(bx, by), radius = r), radius = r, center = Offset(bx, by), blendMode = BlendMode.Plus)
            }
            if (st.reveal == Reveal.SLAM && k > 0.55f && k < 1f) {
                val u = (k - 0.55f) / 0.45f
                drawCircle(col.copy(alpha = 0.6f * (1f - u)), radius = gl.font * (0.55f + 1.6f * u), center = Offset(bx, by), style = Stroke(width = 2.dp.toPx() * (1f - u) + 0.5f), blendMode = BlendMode.Plus)
            }
            // 色のずれ (盛り上がりの強い拍)
            if (flash && dj.hype > 0.4f && downbeat && kick > 0.45f) {
                val o = 3.dp.toPx() * kick
                glyph(gl, bx + m.dx - o, by + m.dy, m.scale * push * pulse, m.rot, Color(0xFFFF3050), 0.45f * kick * m.alpha, 0f, 0f)
                glyph(gl, bx + m.dx + o, by + m.dy, m.scale * push * pulse, m.rot, Color(0xFF30E0FF), 0.45f * kick * m.alpha, 0f, 0f)
            }
            glyph(gl, bx + m.dx, by + m.dy, m.scale * push * pulse, m.rot, col, m.alpha, m.alpha * (0.78f + 0.22f * band), m.glow + band * 0.8f)
        }
    }
}

/** 奥 (リゲルの球より奥): 光の粒・巨大な薄い今の行・奥へ降っていく前の行の文字 */
@Composable
internal fun LyricsBack(st: LyricsRain, dj: DjState, lift: Float, modifier: Modifier) {
    val density = LocalDensity.current
    Canvas(modifier) {
        st.frame.longValue
        for (i in 0 until DUST) {
            val sway = sin(st.t * (0.6f + st.dp[i]) + i) * 6.dp.toPx()
            drawCircle(dj.color(st.dp[i]).copy(alpha = 0.10f + 0.30f * st.dp[i]), radius = st.ds[i], center = Offset(st.dx[i] + sway, st.dy[i]), blendMode = BlendMode.Plus)
        }
        st.big?.let { big ->
            val c = djCenter(size.width, size.height, lift)
            val drift = (0.5f - smooth(st.hold())) * size.width * 0.35f
            val a = (0.07f + 0.06f * dj.hype) * ((st.pos - st.lineMs) / 600f).coerceIn(0f, 1f)
            drawText(big, color = dj.color(0.5f), topLeft = Offset((size.width - big.size.width) / 2f + drift, c.y - big.size.height / 2f), alpha = a,
                shadow = Shadow(dj.color(0.5f), blurRadius = 40f), blendMode = BlendMode.Plus)
        }
        for (gl in st.glyphs) {
            if (!gl.released) continue
            val u = (gl.relT / RELEASE_LIFE_S).coerceIn(0f, 1f)
            val s = gl.s0 * (1f - 0.55f * easeOutCubic((u * 1.8f).coerceAtMost(1f)))     // 奥へ吸い込まれるように小さく
            val col = lerp(dj.color(gl.col), dj.color(0.5f), u)
            glyph(gl, gl.x, gl.y, s, gl.rot, col, (1f - u) * 0.8f, (1f - u * 2.2f).coerceAtLeast(0f) * 0.75f, 0.6f + 1.4f * u)   // 奥へ行くほど、芯が消えてぼける
        }
    }
}

/** 探している・見つからない、を小さく (6 秒で消す)。歌詞そのものは出さない */
@Composable
internal fun LyricsNote(g: McpGraph, modifier: Modifier) {
    val st by g.lyrics.state.collectAsState()
    var until by remember { mutableLongStateOf(0L) }
    LaunchedEffect(st.key, st.status) { until = SystemClock.elapsedRealtime() + 6_000 }
    val note = when (st.status) {
        LyricsRepo.Status.LOADING -> "歌詞を探しています…"
        LyricsRepo.Status.NONE -> "この曲の時刻付きの歌詞は見つかりませんでした"
        LyricsRepo.Status.ERROR -> "歌詞を取れませんでした (通信)"
        else -> ""
    }
    if (note.isNotBlank() && (st.status == LyricsRepo.Status.LOADING || SystemClock.elapsedRealtime() < until))
        Text(note, color = Color(0x99D0DCEC), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = modifier)
}

/** 現れ方ごとの、位置のずれ・大きさ・傾き・濃さ・光 */
private class Motion(val dx: Float, val dy: Float, val scale: Float, val rot: Float, val alpha: Float, val glow: Float, val flare: Float)

private fun motion(r: Reveal, k: Float, font: Float): Motion = when (r) {
    Reveal.FALL -> Motion(0f, -font * 1.6f * (1f - easeOutCubic(k)), 1f + 0.3f * (1f - easeOutBack(k)), 0f, (k * 1.8f).coerceAtMost(1f), 0f, (1f - k).pow(2))
    Reveal.WAVE -> Motion(0f, font * 0.9f * (1f - easeOutCubic(k)), 0.85f + 0.15f * easeOutCubic(k), -14f * (1f - easeOutCubic(k)), k, 0f, (1f - k).pow(2) * 0.6f)
    Reveal.SLAM -> Motion(0f, 0f, 1f + 1.2f * (1f - easeOutExpo(k)), 0f, (k * 2.5f).coerceAtMost(1f), 1f - k, (1f - k) * 0.9f)
    Reveal.CONVERGE -> Motion(0f, 0f, 1.08f - 0.08f * k, 0f, smoothstep(0.55f, 1f, k), 0.6f * (1f - k), smoothstep(0.5f, 0.8f, k) * (1f - k) * 3f)
}

/** 1 文字を描く: 光 (ぼかした影) と、白に近い芯 */
private fun DrawScope.glyph(gl: Glyph, x: Float, y: Float, s: Float, rot: Float, col: Color, glowA: Float, coreA: Float, glow: Float) {
    if (glowA <= 0.005f && coreA <= 0.005f) return
    val tl = Offset(-gl.layout.size.width / 2f, -gl.layout.size.height / 2f)
    withTransform({ translate(x, y); rotate(rot, Offset.Zero); scale(s, s, Offset.Zero) }) {
        if (glowA > 0.005f) drawText(gl.layout, color = col, topLeft = tl, alpha = (glowA * 0.9f).coerceAtMost(1f), shadow = Shadow(col, blurRadius = gl.font * (0.45f + 0.6f * glow)), blendMode = BlendMode.Plus)
        if (coreA > 0.005f) drawText(gl.layout, color = lerp(col, Color.White, 0.78f), topLeft = tl, alpha = coreA.coerceAtMost(1f))
    }
}

/** 散らばった光の粒が、文字の場所へ集まってくる */
private fun DrawScope.converge(gl: Glyph, x: Float, y: Float, k: Float, col: Color) {
    val r = Random(gl.seed)
    val u = easeInOutCubic((k / 0.75f).coerceAtMost(1f))
    val a = 1f - smoothstep(0.6f, 1f, k)
    for (j in 0 until 8) {
        val ang = r.nextFloat() * 2 * PI.toFloat(); val dist = (90f + 140f * r.nextFloat()) * density
        val p = Offset(x + cos(ang) * dist * (1f - u), y + sin(ang) * dist * (1f - u))
        drawCircle(lerp(col, Color.White, 0.5f).copy(alpha = 0.9f * a), radius = (1.6f + 1.4f * r.nextFloat()) * density, center = p, blendMode = BlendMode.Plus)
    }
}

/**
 * 今の行の文字を、ばらけさせる (少し奥へ押されてから、横へ散って落ちる。盛り上がっているほど強く)。
 * 英語などの語は、ばらけるときに 1 文字ずつに割る (現れるのは読みやすいよう語ごと)。
 * 割った文字は、語の中のその文字の位置から、同じ大きさのまま散る
 */
private fun release(st: LyricsRain, dj: DjState, rnd: Random, measurer: TextMeasurer, density: Density) {
    val dens = density.density
    val boost = 1f + dj.hype
    val push = 1f + 0.06f * smooth(st.hold())
    fun fling(g: Glyph, x: Float, y: Float) {
        g.released = true; g.x = x; g.y = y; g.s0 = push
        g.vx = (rnd.nextFloat() * 2 - 1) * 120f * dens * boost
        g.vy = (-30f + 60f * rnd.nextFloat()) * dens * boost
        g.vrot = (rnd.nextFloat() * 2 - 1) * 150f * boost
    }
    val words = ArrayList<Glyph>()
    val letters = ArrayList<Glyph>()
    for (gl in st.glyphs) if (!gl.released) {
        val x = st.cx + (gl.homeX - st.cx) * push; val y = st.cy + (gl.homeY - st.cy) * push
        val text = gl.layout.layoutInput.text.text
        if (text.codePointCount(0, text.length) <= 1) { fling(gl, x, y); continue }
        words += gl
        var i = 0
        while (i < text.length) {
            val n = Character.charCount(text.codePointAt(i))
            val ch = text.substring(i, i + n)
            if (ch.isNotBlank()) {
                val box = gl.layout.getBoundingBox(i)
                val lg = Glyph(st.letter(ch, gl.font, measurer, density), gl.sungMs, 0f, 0f, gl.col, gl.font, gl.xn, gl.idx, gl.seed * 31 + i)
                fling(lg, x + (box.center.x - gl.layout.size.width / 2f) * push, y + (box.center.y - gl.layout.size.height / 2f) * push)
                letters += lg
            }
            i += n
        }
    }
    st.glyphs.removeAll(words.toSet())
    st.glyphs += letters
}

/**
 * 行 i の文字を、舞台に並べる (はみ出さない大きさにして、長い行は折り返す。上下の真ん中に)。
 * 歌われる時刻は、文字数の割合で行の中に配る。現れ方は、静かなら FALL / WAVE、盛り上がりなら SLAM / CONVERGE を交互に
 */
private fun buildLine(st: LyricsRain, lines: List<LyricLine>, i: Int, measurer: TextMeasurer, density: Density, dj: DjState) {
    val text = lines[i].text
    val tokens = tokensOf(text)
    if (tokens.isEmpty()) return
    val latin = tokens.size > 1 && tokens.all { t -> t.any { it.isLetter() && it.code < 0x2E80 } }
    val margin = with(density) { 28.dp.toPx() }
    val avail = st.w - margin * 2
    val areaH = st.areaBottom - st.areaTop
    // 大きさ: 48dp で測って、比例で見積もり、舞台に収まるいちばん大きい大きさにする (fitFont)
    val ref = with(density) { 48.dp.toPx() }
    val refW = tokens.map { measurer.measure(it, styleOf(ref, density), density = density).size.width.toFloat() }
    val fontPx = with(density) { fitFont(refW, ref, avail, areaH, latin, 22.dp.toPx(), 72.dp.toPx(), 4.dp.toPx()) }
    val style = styleOf(fontPx, density)
    val layouts = tokens.map { measurer.measure(it, style, density = density) }
    val gap = lyricGap(fontPx, latin)
    val rows = pack(layouts.map { it.size.width.toFloat() }, avail * FIT_W, gap, perRow(latin))
    val lineH = fontPx * LINE_H
    val total = rows.size * lineH
    val top = st.areaTop + ((areaH - total) / 2f).coerceAtLeast(0f)
    val weights = tokens.map { if (latin) 0.5f + it.length * 0.35f else 1f }
    val wsum = weights.sum()
    val span = Lyrics.spanMs(lines, i)
    st.glyphs.removeAll { !it.released }
    // 歌っている長さ: 次の行までの時間 (の 85%) と、自然な歌の速さ (日本語は 1 秒に 7 文字・英語は 1 秒に 3 語) の短いほう
    // (次の行まで均等に配ると、歌い終わって間があく行では、後ろの文字が 1〜2 秒遅れる)
    val natural = (if (latin) tokens.size / WORDS_PER_S else tokens.size / CHARS_PER_S) * 1000f + 250f
    st.lineMs = lines[i].ms; st.spanMs = span; st.singMs = natural.toLong().coerceIn(400L, (span * SING_PART).toLong().coerceAtLeast(400L))
    st.reveal = if (dj.hype > 0.5f) (if (st.shown % 2 == 0) Reveal.SLAM else Reveal.CONVERGE) else (if (st.shown % 2 == 0) Reveal.FALL else Reveal.WAVE)
    st.shown++
    st.cx = st.w / 2f; st.cy = top + total / 2f
    var before = 0f
    for ((r, row) in rows.withIndex()) {
        val rw = row.sumOf { layouts[it].size.width.toDouble() }.toFloat() + gap * (row.size - 1)
        var x = (st.w - rw) / 2f
        for (k in row) {
            val l = layouts[k]
            val xn = (r + (x + l.size.width / 2f - (st.w - rw) / 2f) / rw.coerceAtLeast(1f)) / rows.size
            // 念のため、画面の内側に押し込む (見積もりの誤差で端の文字が出ないように)
            val half = l.size.width / 2f
            val hx = if (half * 2 >= st.w - margin * 2) st.w / 2f else (x + half).coerceIn(margin + half, st.w - margin - half)
            st.glyphs += Glyph(l, lines[i].ms + (st.singMs * before / wsum).toLong(), hx, top + r * lineH + lineH / 2f,
                k / (tokens.size - 1f).coerceAtLeast(1f), fontPx, xn, k, (lines[i].ms * 31 + k).toInt())
            before += weights[k]
            x += l.size.width + gap
        }
    }
    // 奥の巨大な薄い文字 (今の行を 1 行で)
    st.big = measurer.measure(text, styleOf(with(density) { 150.dp.toPx() }, density), density = density, softWrap = false, maxLines = 1)
}

internal fun styleOf(px: Float, density: Density) = TextStyle(fontSize = with(density) { px.toSp() }, fontWeight = FontWeight.Black, color = Color.White)

/**
 * 舞台に収まる、いちばん大きい文字の大きさ (px)。refW は ref の大きさで測った幅 (大きさに比例するとして見積もる)。
 * いちばん長い 1 語も幅に入るように。幅は FIT_W だけ余裕をとる (行が近づく動きと、拍の脈打ちのぶん)。
 * minPx でも高さに入らなければ、高さに合わせてさらに縮める (長い語・長い行が画面からはみ出さないように)
 */
internal fun fitFont(refW: List<Float>, ref: Float, avail: Float, areaH: Float, latin: Boolean, minPx: Float, maxPx: Float, step: Float): Float {
    val w = avail * FIT_W
    val widest = refW.maxOrNull() ?: return maxPx
    val byWord = ref * w / widest
    fun need(f: Float) = pack(refW.map { it * f / ref }, w, lyricGap(f, latin), perRow(latin)).size * f * LINE_H
    var f = minOf(maxPx, byWord)
    while (f > minPx) { if (need(f) <= areaH) return f; f -= step }
    f = minOf(minPx, byWord)
    var guard = 0
    while (need(f) > areaH && guard++ < 30) f *= 0.92f
    return f
}

internal fun lyricGap(f: Float, latin: Boolean) = if (latin) f * 0.32f else f * 0.05f
internal fun perRow(latin: Boolean) = if (latin) 99 else CJK_PER_ROW

/** 幅の並びを、行に詰める (各行の番号の並び) */
internal fun pack(widths: List<Float>, avail: Float, gap: Float, maxPerRow: Int): List<List<Int>> {
    val rows = ArrayList<List<Int>>()
    var cur = ArrayList<Int>(); var wsum = 0f
    for ((k, w) in widths.withIndex()) {
        val add = w + if (cur.isEmpty()) 0f else gap
        if (cur.isNotEmpty() && (wsum + add > avail || cur.size >= maxPerRow)) { rows += cur; cur = ArrayList(); wsum = 0f }
        wsum += w + if (cur.isEmpty()) 0f else gap
        cur += k
    }
    if (cur.isNotEmpty()) rows += cur
    return rows
}

/** 文字の区切り: 英語などは語ごと、日本語などは 1 文字ずつ (空白は落とす) */
private fun tokensOf(text: String): List<String> {
    val letters = text.count { it.isLetter() }
    val latinLetters = text.count { it.isLetter() && it.code < 0x2E80 }
    if (letters > 0 && latinLetters * 2 > letters) return text.split(Regex("""\s+""")).filter { it.isNotBlank() }
    val out = ArrayList<String>()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i); val n = Character.charCount(cp)
        val ch = text.substring(i, i + n)
        if (ch.isNotBlank()) out += ch
        i += n
    }
    return out
}

private fun smooth(x: Float) = x * x * (3f - 2f * x)
private fun smoothstep(a: Float, b: Float, x: Float) = smooth(((x - a) / (b - a)).coerceIn(0f, 1f))
private fun easeOutCubic(x: Float) = 1f - (1f - x).pow(3)
private fun easeOutExpo(x: Float) = if (x >= 1f) 1f else 1f - 2f.pow(-10f * x)
private fun easeInOutCubic(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
private fun easeOutBack(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1f; val u = x - 1f; return 1f + c3 * u * u * u + c1 * u * u }

/** 再生位置に足す量 (スピーカーの遅れはごく小さいので 0。曲ごとのずれは LyricsRepo.offset で直す) */
private const val LEAD_MS = 0L
/** 次の行を、このくらい前から用意する (現れ方の前倒しのぶん) */
private const val SWITCH_AHEAD_MS = 450L
/** 自然な歌の速さ (歌っている長さの見積もり) */
private const val CHARS_PER_S = 7f
private const val WORDS_PER_S = 3f
/** 行の長さのうち、歌っている所 (残りは余韻。歌い終わると光の帯がなでる) */
private const val SING_PART = 0.85f
private const val CJK_PER_ROW = 9
/** 幅に詰めるときの余裕 (行が 6% 近づく・拍で 5% 脈打つぶん) */
private const val FIT_W = 0.88f
private const val LINE_H = 1.22f
private const val GRAVITY_DP = 300f
private const val RELEASE_LIFE_S = 2.6f
private const val DUST = 48
