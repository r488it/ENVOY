package com.rabi.envoymcp.ui.avatar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 音楽に合わせてノる元 (音楽を流しているときも表情を豊かにする)。音楽の画面が毎フレーム書き、アバターが読む。
 * アバターは今の体の上下のずれと傾きを書き返す (音楽の画面が上に描く DJ のヘッドホンを、体に合わせて動かすため)
 */
class Groove {
    /** 拍が取れている */
    @Volatile var on = false
    /** 拍の中の位置 0..1 (0 が拍の頭) と、数えた拍・テンポ */
    @Volatile var phase = 0f
    @Volatile var beats = 0
    @Volatile var bpm = 0f
    /** 拍の頭の強さ (拍から消える)・低音の強さ・盛り上がり (いずれも 0..1) */
    @Volatile var kick = 0f
    @Volatile var bass = 0f
    @Volatile var hype = 0f
    /** ノり方を替える拍の数 (ふだん 16。試しでは 8) と、小節の頭の仕草を 2 小節ごとに順に出すか (試し) */
    @Volatile var styleBeats = 16
    @Volatile var demoFlourish = false
    /** 音の勢いの速い平均と遅い平均 (0..1)。盛り上がる前のため・静かな所を見分ける */
    @Volatile var energyFast = 0f
    @Volatile var energySlow = 0f
    /** アバターが書く: 体の上下のずれ (半径比、下が正) と傾き (度) */
    @Volatile var outOff = 0f
    @Volatile var outTilt = 0f
}

/**
 * 音楽に合わせた今の動き (Groove から毎フレーム計算する)。音だけで決める
 * - ノる: 16 拍ごとに、ノり方を替える (うなずき・弾み / 大きく左右に揺れる / 見回す / 跳ねる / 頭で円を描く)。
 *   拍の頭のキックでつぶれ、低音でふくらむ。目は気持ちよさそうに細め、ゆっくりした曲 (90 BPM 以下) ではほとんど閉じてうっとり
 * - 小節の頭の仕草: ときどき (2 小節以上あけて、4 割ほど) ウインク・ライトを見上げる・にっこり・2 回まばたき・くるっと一回転
 * - 盛り上がる前のため: 音がだんだん強くなる間、目を見開き、細かく震えながら沈み込む (盛り上がった瞬間に大きく跳ねる)
 * - 盛り上がり: 目を ＞＜ にし、金色の光とほほ、拍ごとに大きくヘドバン (始まりの跳ね・きらめきは呼ぶ側が出す)
 * - 盛り上がりの後: 満足そうに、にっこり (目を ∩) してほほを光らせ、少しずつノりに戻る
 * - 静かな所 (イントロ・間奏): 目を閉じてゆっくり揺れ、少し暗くなる。音が戻ったら、ぱっと目を開ける (呼ぶ側が出す)
 */
internal class GrooveNow {
    var off = 0f; var squash = 0f; var scale = 0f; var tilt = 0f; var yaw = 0f
    var gazeX = 0f; var gazeY = 0f
    /** まぶた (両目): 閉じる度合い・閉じた線の反り・にっこり。winkR は右目だけ閉じる (ー) */
    var close = 0f; var curve = 0f; var smile = 0f; var winkR = 0f
    var squeeze = 0f; var eyeScale = 0f
    var tint = 0f; var blush = 0f; var dim = 0f
    /** 盛り上がる前のためが、盛り上がったときにどれだけたまっていたか (跳ねる大きさに使う) */
    var antic = 0f; private set
    private var anticAtHype = 0f
    val anticPeak get() = anticAtHype

    private var amt = 0f
    private var fast = 0.5f
    private var hyped = false
    private var lastBeats = -1
    private val styleW = FloatArray(STYLES).also { it[0] = 1f }
    private var quiet = 0f
    private var quietDeep = false
    private var afterAt = -100f
    // 小節の頭の仕草
    private var fKind = -1
    private var fStart = 0f
    private var fDur = 0f
    private var fBlinkSecond = false
    private var lastFlourishBar = -100
    /** 試しで、次に出す仕草 (順に) */
    private var demoNext = -1

    /** 1 フレーム進める。now は秒。起きた出来事を EV_* の組で返す */
    fun update(g: Groove, now: Float, dt: Float, motion: Float): Int {
        var ev = 0
        amt += ((if (g.on) 1f else 0f) - amt) * minOf(1f, dt / 0.5f)
        val bpm = if (g.bpm > 0f) g.bpm else 120f
        fast += (((bpm - 90f) / 40f).coerceIn(0f, 1f) - fast) * minOf(1f, dt / 1.5f)
        val beatS = 60f / bpm
        val hy = g.hype.coerceIn(0f, 1f)
        val h2 = hy * hy * (3f - 2f * hy)
        val a = amt * motion
        val ph = g.phase
        val newBeat = g.beats != lastBeats
        lastBeats = g.beats

        // 静かな所: 音の勢いが落ちたら (拍が取れていなくても)
        val qT = ((0.32f - g.energyFast) / 0.2f).coerceIn(0f, 1f)
        quiet += (qT - quiet) * minOf(1f, dt / (if (qT > quiet) 1.2f else 0.25f))
        if (quiet > 0.6f) quietDeep = true
        if (quietDeep && quiet < 0.25f) { quietDeep = false; ev = ev or EV_WAKE }
        val loud = 1f - 0.7f * quiet

        // 盛り上がる前のため: 速い平均が遅い平均よりはっきり上で、音も大きくなってきた
        val anticT = (((g.energyFast - g.energySlow) / 0.05f).coerceIn(0f, 1f) * ((g.energyFast - 0.5f) / 0.3f).coerceIn(0f, 1f) * (1f - h2))
            .let { if (g.on) it else 0f }
        antic += (anticT - antic) * minOf(1f, dt / (if (anticT > antic) 0.6f else 0.3f))

        // ノり方 (16 拍ごとに替える。1 拍ほどかけて混ぜ替える)
        val style = (g.beats / g.styleBeats.coerceAtLeast(1)) % STYLES
        for (k in 0 until STYLES) styleW[k] += ((if (k == style) 1f else 0f) - styleW[k]) * minOf(1f, dt / (beatS * 1.2f))
        val nodV = (0.5f + 0.5f * cos(2f * PI.toFloat() * ph)).let { it * it }       // 拍の頭で下
        val bounceV = sin(PI.toFloat() * ph)                                             // 拍と拍の間で上
        val swing = sin(PI.toFloat() * ((g.beats % 2) + ph))                             // 2 拍で左右へ 1 往復
        val pos4 = ((g.beats % 4) + ph) / 4f                                             // 4 拍で 1 周
        val pos2 = ((g.beats % 2) + ph) / 2f                                             // 2 拍で 1 周
        val hop2 = if (bpm < 100f) sin(PI.toFloat() * pos2) else bounceV                 // ゆっくりした曲は 2 拍で 1 回跳ねる
        val sOff = floatArrayOf(
            (1f - fast) * 0.014f * nodV - fast * 0.022f * bounceV,                       // うなずき・弾み
            0.008f * nodV,                                                               // 大きく揺れる
            0.010f * nodV,                                                               // 見回す
            -0.03f * hop2,                                                               // 跳ねる
            0.015f * cos(2f * PI.toFloat() * pos2),                                      // 頭で円を描く
        )
        val sTilt = floatArrayOf((4f + 4f * (1f - fast)) * swing, 10f * sin(2f * PI.toFloat() * pos4), 2f * swing, 3f * swing, 6f * sin(2f * PI.toFloat() * pos2))
        val sYaw = floatArrayOf(4f * swing, 5f * sin(2f * PI.toFloat() * pos4), 14f * sin(2f * PI.toFloat() * pos4), 0f, 4f * cos(2f * PI.toFloat() * pos2))
        val sGaze = floatArrayOf(0f, 0f, 0.02f * sin(2f * PI.toFloat() * pos4), 0f, 0f)
        var o = 0f; var tl = 0f; var yw = 0f; var gzx = 0f
        for (k in 0 until STYLES) { o += styleW[k] * sOff[k]; tl += styleW[k] * sTilt[k]; yw += styleW[k] * sYaw[k]; gzx += styleW[k] * sGaze[k] }
        val styleK = a * loud * (1f - 0.6f * hy) * (1f - 0.6f * antic)
        off = styleK * o + a * hy * (0.03f * nodV + 0.022f * nodV * 1.6f) + antic * 0.025f * motion
        squash = a * g.kick * (0.03f + 0.03f * fast + 0.04f * hy + 0.04f * styleW[3]) + antic * 0.05f * motion
        scale = a * g.bass * 0.03f * loud
        tilt = styleK * tl + a * hy * swing * 6f + antic * sin(now * 2f * PI.toFloat() * 10f) * 1.5f * motion +
            quiet * sin(now * 2f * PI.toFloat() * 0.25f) * 6f * motion
        yaw = styleK * yw
        gazeX = styleK * gzx
        gazeY = 0f
        // 目: 気持ちよさそうに細める (速い曲は少しにっこり、ゆっくりした曲はほとんど閉じて、穏やかに下へ反った線)
        close = amt * (0.25f + 0.6f * (1f - fast)) * (1f - h2) * (1f - antic)
        curve = 0.6f - 0.8f * (1f - fast)
        smile = 0.3f + 0.7f * fast
        squeeze = amt * h2
        winkR = 0f
        eyeScale = antic * 0.15f
        tint = amt * hy * 0.9f
        blush = amt * hy * 0.7f
        dim = quiet * 0.15f
        lid(quiet, -0.2f, 0f)

        // 盛り上がりの始まり・終わり
        if (hy > 0.5f && !hyped && g.on) { hyped = true; anticAtHype = antic; ev = ev or EV_HYPE }
        if (hy < 0.25f && hyped) { hyped = false; afterAt = now }
        if (hyped && newBeat && g.beats % 4 == 0 && ev and EV_HYPE == 0) ev = ev or EV_BAR
        // 盛り上がりの後: にっこり (2.5 秒)
        val au = (now - afterAt) / 2.5f
        if (au in 0f..1f) {
            val env = sin(PI.toFloat() * au).let { if (it > 0f) it.pow(0.5f) else 0f }
            lid(0.8f * env, 0.9f, 1f); blush = maxOf(blush, 0.6f * env); tint = maxOf(tint, 0.4f * env)
        }

        // 小節の頭の仕草 (ためていない・盛り上がっていない・静かでないとき)
        val bar = g.beats / 4
        if (g.on && newBeat && g.beats % 4 == 0 && fKind < 0 && !hyped && antic < 0.3f && quiet < 0.3f && bar - lastFlourishBar >= 2 &&
            (g.demoFlourish || Random.nextFloat() < 0.4f)) {
            val x = Random.nextFloat()
            fKind = if (g.demoFlourish) { demoNext = (demoNext + 1) % 5; demoNext }
                else when { x < 0.25f -> F_SMILE; x < 0.5f -> F_LOOK_UP; x < 0.7f -> F_WINK; x < 0.9f -> F_BLINK2; else -> F_SPIN }
            fStart = now; lastFlourishBar = bar; fBlinkSecond = false
            fDur = beatS * (if (fKind == F_WINK) 1.2f else 2f)
            if (fKind == F_WINK) ev = ev or EV_WINK
            if (fKind == F_BLINK2) ev = ev or EV_BLINK
        }
        if (fKind >= 0) {
            val u = (now - fStart) / fDur
            if (u >= 1f) fKind = -1 else {
                val env = sin(PI.toFloat() * u).pow(0.6f)
                when (fKind) {
                    F_SMILE -> { lid(0.8f * env, 0.9f, 1f); blush = maxOf(blush, 0.5f * env) }
                    F_LOOK_UP -> { gazeY -= 0.04f * env * motion; eyeScale += 0.08f * env }
                    F_WINK -> { winkR = env; tilt += 5f * env * motion }
                    F_BLINK2 -> if (!fBlinkSecond && u >= 0.5f) { fBlinkSecond = true; ev = ev or EV_BLINK }
                    F_SPIN -> { val e = u * u * (3f - 2f * u); tilt += 360f * e * (if (motion < 1f) 0f else 1f) }
                }
            }
        }
        return ev
    }

    /** まぶたを、閉じる度合いの強いほうにする */
    private fun lid(k: Float, c: Float, sm: Float) { if (k > close) { close = k; curve = c; smile = sm } }

    fun clear() {
        off = 0f; squash = 0f; scale = 0f; tilt = 0f; yaw = 0f; gazeX = 0f; gazeY = 0f
        close = 0f; curve = 0f; smile = 0f; winkR = 0f; squeeze = 0f; eyeScale = 0f; tint = 0f; blush = 0f; dim = 0f
        amt = 0f; hyped = false; antic = 0f; quiet = 0f; quietDeep = false; fKind = -1
    }

    companion object {
        const val STYLES = 5
        const val EV_HYPE = 1; const val EV_BAR = 2; const val EV_BLINK = 4; const val EV_WINK = 8; const val EV_WAKE = 16
        private const val F_SMILE = 0; private const val F_LOOK_UP = 1; private const val F_WINK = 2; private const val F_BLINK2 = 3; private const val F_SPIN = 4
    }
}
