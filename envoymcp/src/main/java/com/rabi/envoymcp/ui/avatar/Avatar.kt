package com.rabi.envoymcp.ui.avatar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rabi.envoymcp.core.Expression
import com.rabi.envoymcp.core.Mood
import com.rabi.envoymcp.ui.avatar.orb.Orb3D
import com.rabi.envoymcp.ui.avatar.orb.Orb3DSurface
import com.rabi.envoymcp.ui.avatar.orb.OrbControls
import com.rabi.envoymcp.ui.avatar.orb.orbTouch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** アバターの見た目。光る球だけ */
data class AvatarStyle(val id: String, val name: String, val color: Color, val glow: Color) {
    companion object {
        /** ほぼ黒の空間に浮かぶ、透明感のあるガラスの球と、青白く光る目 */
        val ORB = AvatarStyle("orb", "光る球", Color(0xFF101C30), Color(0xFFA8DFFF))
        fun byId(@Suppress("UNUSED_PARAMETER") id: String? = null) = ORB
    }
}

/**
 * アバターへの一度だけの合図。実際の出来事 (呼びかけの検出・操作の 1 手・読み上げの文の始まり・完了) で番号が増え、
 * 増えたときだけ反応する。何も起きていないのに進んでいるような演出はしない。
 */
data class AvatarSignals(
    val wake: Int = 0,
    val step: Int = 0,
    /** look = 画面を確かめる / act = 操作する */
    val stepKind: String = "",
    val done: Int = 0,
    val sentence: Int = 0,
    /** 読み上げの区切り (単語・文字の範囲) ごとに増える */
    val pulse: Int = 0,
    /** 一度だけ見せる感情の表情と、その番号 (見せるたびに増える) */
    val expression: Expression? = null,
    val expressionSeq: Int = 0,
    /**
     * 表情を、話し始めるまで続ける (音声モードの画面: ChatGPT は表情の道具を呼んでから話し始めるまで 3 秒ほどかかるので、
     * 決まった長さで戻すと声の出だしに表情が残らない)。話し始めてから EXPRESS_SPEECH_TAIL_MS で戻す
     */
    val holdUntilSpeaking: Boolean = false,
    /** 0 でなければ、表情を出してからこの長さで戻す (holdUntilSpeaking・表情ごとの長さより優先。呼び名で起きたとき: 2 秒) */
    val expressionHoldMs: Long = 0L,
)

/** 配置: 画面いっぱい (ホーム) は球の中心を横 50%・縦 45%、直径は幅の 58% (縦の長さと絶対値でも上限)。枠の中 (オーバーレイなど) は中央 */
object AvatarGeometry {
    /** big: モニター (PC モード) の全画面のリゲル。上限なしで、画面の高さの 74% (幅の 80% まで) の直径にする */
    fun center(w: Float, h: Float, fullScreen: Boolean, big: Boolean = false) =
        if (big) Offset(w * 0.5f, h * 0.47f) else if (fullScreen) Offset(w * 0.5f, h * 0.45f) else Offset(w / 2f, h / 2f)
    /** compact: 他のアプリの上の小さなリゲル。窓の 74% の直径にする */
    fun radius(w: Float, h: Float, fullScreen: Boolean, maxDiameterPx: Float, big: Boolean = false, compact: Boolean = false) =
        if (big) minOf(w * 0.80f, h * 0.74f) / 2f else if (fullScreen) minOf(w * 0.58f, h * 0.34f, maxDiameterPx) / 2f
        else minOf(w, h) * (if (compact) 0.37f else 0.30f)
}

/** 状態ごとの目標値。すべて球の半径 r に対する割合 (角度は度) */
private data class Pose(
    val floatAmp: Float,     // 上下の浮遊 (半分の振れ幅)
    val breathAmp: Float,    // 大きさの呼吸 (0.015 なら 1.00〜1.015 倍)
    val glow: Float,         // 光の強さ 0..1
    val open: Float,         // 目の開き
    val gx: Float, val gy: Float,   // 視線
    val tilt: Float,         // 傾き
    val asym: Float,         // 左右の目の非対称 0..1
    val warm: Float,         // 輪郭に混ぜる暖色 0..1
    val inner: Float,        // 内部の反射光の動きの速さ
    val sway: Float = 0.5f,  // 3D: 球がゆっくり向きを変える揺らぎの大きさ (1 で左右 ±4.5°・上下 ±2.5°)
)

private fun poseOf(m: Mood): Pose = when (m) {
    Mood.IDLE -> Pose(0.015f, 0.015f, 0.45f, 1.00f, 0f, 0f, 0f, 0f, 0f, 0.05f, sway = 1f)
    Mood.LISTENING -> Pose(0.006f, 0.006f, 0.60f, 1.08f, 0f, 0f, 0f, 0f, 0f, 0.05f, sway = 0.2f)
    Mood.THINKING -> Pose(0.008f, 0.007f, 0.52f, 0.97f, 0.028f, -0.034f, 0f, 0f, 0f, 0.28f, sway = 0.5f)
    Mood.ACTING -> Pose(0.008f, 0.006f, 0.55f, 0.98f, 0f, 0.012f, 0f, 0f, 0f, 0.45f, sway = 0.35f)
    Mood.SPEAKING -> Pose(0.010f, 0.004f, 0.56f, 1.00f, 0f, 0f, 0f, 0f, 0f, 0.10f, sway = 0.5f)
    Mood.APPROVAL -> Pose(0.004f, 0.003f, 0.55f, 1.00f, 0f, 0f, 0f, 0f, 0f, 0.03f, sway = 0.15f)
    Mood.DONE -> Pose(0.010f, 0.008f, 0.58f, 1.00f, 0f, 0f, 0f, 0f, 0f, 0.05f, sway = 0.5f)
    Mood.CONFUSED -> Pose(0.010f, 0.008f, 0.44f, 0.96f, 0.012f, 0.004f, 4f, 1f, 0.6f, 0.08f, sway = 0.5f)
}

/**
 * アバター。
 * - 動きは小さく滑らかに。点滅・高速回転・大きな跳ね・粒子は使わない。背景は動かさない
 * - 3D では球そのものがゆっくり少し向きを変える (視線の方へ回る・待機中は小さく揺らぐ)。2D では球は回さない
 * - 状態が変わったら 300ms ほどでつなぐ (動きを弱める設定では短く、振れ幅も小さく)
 * - 待機・承認待ちは描画の回数を抑える (毎秒 25 回)
 * - 話している間 (声の大きさが取れるとき。ChatGPT の声に合わせて表情豊かに):
 *   声の波で体がふくらみ、目が開き、光が強まる (口の代わり)。音節の立ち上がりで弾む。
 *   話しぶりが乗るほど首を揺らす。強く言ったところで目を見開いて跳ねる。間 (息つぎ・文の切れ目) でまばたき・うなずき・視線を外す。
 *   話している間の動きは、ほかの状態より大きくしている
 * @param level 声の大きさ 0..1。聞き取り中は聞いている声、話している間は話している声 (得られないときは 0 のまま。そのときは見せかけの反応はしない)
 * @param fullScreen ホーム画面のように画面いっぱいに置くか
 * @param allow3d 画面いっぱいのとき、3D (OpenGL ES 3 の柔らかい体) で描くか。使えない端末・失敗したときは 2D に戻す
 */
@Composable
fun Avatar(
    mood: Mood,
    style: AvatarStyle,
    level: Float,
    modifier: Modifier = Modifier,
    signals: AvatarSignals = AvatarSignals(),
    fullScreen: Boolean = false,
    reduceMotion: Boolean = false,
    allow3d: Boolean = false,
    /** モニターの全画面のリゲルのように、上限なしで大きく描く */
    big: Boolean = false,
    /** 小さな窓 (他のアプリの上の小さなリゲル) に、窓いっぱい近くまで大きく描く。まわりの光は窓の端までに消す */
    compact: Boolean = false,
    /** 音楽に合わせてノる元 (音楽の画面のときだけ)。体のずれ・傾きを書き返す */
    groove: Groove? = null,
) {
    val ctx = LocalContext.current
    var glFailed by remember { mutableStateOf(false) }
    val gles3 = remember { Orb3D.supported(ctx) }
    val use3d = fullScreen && allow3d && gles3 && !glFailed
    val ctl = remember { OrbControls() }
    val moodNow by rememberUpdatedState(mood)
    val levelNow by rememberUpdatedState(level)
    val use3dNow by rememberUpdatedState(use3d)
    val grooveNow by rememberUpdatedState(groove)
    // 音楽に合わせた今の動き (毎フレーム計算し、3D と 2D の両方が読む)
    val gz = remember { GrooveNow() }
    // 今のまぶた (左右): 表情 (照れのにっこり・ウインク) と音楽 (ノり・静か・にっこり・ウインク) の、閉じる度合いの強いほう
    val lidL = remember { Lid() }
    val lidR = remember { Lid() }
    val motion = if (reduceMotion) 0.3f else 1f
    val dur = if (reduceMotion) 150 else 300
    val p = poseOf(mood)
    fun ease() = tween<Float>(dur, easing = FastOutSlowInEasing)
    val floatAmp by animateFloatAsState(p.floatAmp * motion, ease(), label = "float")
    val breathAmp by animateFloatAsState(p.breathAmp * motion, ease(), label = "breath")
    val glow by animateFloatAsState(p.glow, ease(), label = "glow")
    val open by animateFloatAsState(p.open, ease(), label = "open")
    val gx by animateFloatAsState(p.gx, tween(dur + 200, easing = FastOutSlowInEasing), label = "gx")
    val gy by animateFloatAsState(p.gy, tween(dur + 200, easing = FastOutSlowInEasing), label = "gy")
    val tilt by animateFloatAsState(p.tilt * motion, tween(if (reduceMotion) 200 else 900, easing = FastOutSlowInEasing), label = "tilt")
    val asym by animateFloatAsState(p.asym, ease(), label = "asym")
    val warm by animateFloatAsState(p.warm, tween(600), label = "warm")
    val innerSpeed by animateFloatAsState(p.inner, tween(600), label = "inner")
    val sway by animateFloatAsState(p.sway * motion, tween(900, easing = FastOutSlowInEasing), label = "sway")

    // 一度だけの反応
    val flash = remember { Animatable(0f) }      // 輪郭が柔らかく明るくなる
    val wakeOpen = remember { Animatable(0f) }   // 気づいて目が少し開く
    val nod = remember { Animatable(0f) }        // うなずき (下へ)
    val squint = remember { Animatable(0f) }     // 目を優しく細める
    val glanceX = remember { Animatable(0f) }
    val glanceY = remember { Animatable(0f) }
    val speech = remember { Animatable(0f) }     // 読み上げのリズム
    val wide = remember { Animatable(0f) }       // 強く言ったところで目を見開く

    // 感情の表情 (一度だけ。決まった長さで 0 に戻る)
    val exLid = remember { Animatable(0f) }      // にっこりの目 (照れ: 下まぶたが上がる)
    val exWink = remember { Animatable(0f) }     // 右目だけ閉じる (ー。ウインク)
    val exDroop = remember { Animatable(0f) }    // 悲しい目 (外側が下がった上まぶた)
    val exWide = remember { Animatable(0f) }     // 驚き (目が大きく)
    val exBlush = remember { Animatable(0f) }    // ほほの光
    val exShake = remember { Animatable(0f) }    // 笑いの揺れ
    val exTilt = remember { Animatable(0f) }     // 首かしげ (度)
    val exGx = remember { Animatable(0f) }       // 視線 (半径に対する割合)
    val exGy = remember { Animatable(0f) }
    val exSink = remember { Animatable(0f) }     // 沈む (半径に対する割合)
    val exDim = remember { Animatable(0f) }      // 光を弱める
    val exTint = remember { Animatable(0f) }     // 表情の色の光 (うれしい・笑うは金色、驚きは白、悲しいは青、照れは桃色)
    val exSway = remember { Animatable(0f) }     // うれしいときの、左右に揺れて弾む「ルンルン」
    val exSqueeze = remember { Animatable(0f) }  // 笑う目 (＞＜)
    val exDeflate = remember { Animatable(0f) }  // しぼむ・縮こまる (悲しい・照れ)
    val exWiggle = remember { Animatable(0f) }   // もじもじ (照れ)
    val exQuiver = remember { Animatable(0f) }   // びくっと細かく震える (驚き)
    val squashA = remember { Animatable(0f) }    // 跳ねるときのつぶれ・伸び
    // 表情の色 (縁・まわりの光) と、明るさの増減
    var tint by remember { mutableStateOf(TINT_JOY) }
    // 表情のまわりの演出 (きらめき・驚きの線・涙・照れの湯気)
    val fx = remember { mutableStateListOf<Fx>() }
    // 体の上下のずれ (半径に対する割合、下が正)。演出を体に合わせて置くのに使う
    var bodyOff by remember { mutableFloatStateOf(0f) }
    // 時間 (秒。描画とアニメの基準) と、まばたきを始めた時刻
    var t by remember { mutableFloatStateOf(0f) }
    var blinkStart by remember { mutableFloatStateOf(-10f) }
    var expressing by remember { mutableStateOf(false) }
    // 画面を開いたときに、前に見せた表情をもう一度見せない
    val firstExpressionSeq = remember { signals.expressionSeq }

    LaunchedEffect(signals.wake) {
        if (signals.wake == 0) return@LaunchedEffect
        launch { flash.animateTo(1f, tween(120)); flash.animateTo(0f, tween(260)) }
        launch { wakeOpen.animateTo(1f, tween(140)); wakeOpen.animateTo(0f, tween(400)) }
    }
    LaunchedEffect(signals.step) {
        if (signals.step == 0) return@LaunchedEffect
        launch { flash.animateTo(0.45f, tween(110)); flash.animateTo(0f, tween(240)) }
        // 画面を確かめるときは少し下 (スマホの中) を、操作するときは左右に小さく目を動かし、しばらくしたら戻す
        val (tx, ty) = if (signals.stepKind == "look") 0f to 0.03f else (if (signals.step % 2 == 0) 0.03f else -0.03f) to 0.012f
        launch { glanceX.animateTo(tx * motion, tween(220)); delay(900); glanceX.animateTo(0f, tween(420)) }
        launch { glanceY.animateTo(ty * motion, tween(220)); delay(900); glanceY.animateTo(0f, tween(420)) }
    }
    LaunchedEffect(signals.done) {
        if (signals.done == 0) return@LaunchedEffect
        launch { nod.animateTo(0.035f * motion, tween(200, easing = FastOutSlowInEasing)); nod.animateTo(0f, tween(320, easing = FastOutSlowInEasing)) }
        launch { flash.animateTo(0.8f, tween(160)); flash.animateTo(0f, tween(420)) }
        launch { squint.animateTo(1f, tween(200)); delay(150); squint.animateTo(0f, tween(300)) }
    }
    LaunchedEffect(signals.expressionSeq) {
        val e = signals.expression
        if (e == null || signals.expressionSeq == firstExpressionSeq) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        expressing = true
        tint = tintOf(e)
        val target = expressionTargets(e)
        val layers = listOf(exLid, exWink, exDroop, exWide, exBlush, exShake, exTilt, exGx, exGy, exSink, exDim, exTint, exSway, exSqueeze, exDeflate, exWiggle)
        // 動きの大きさ (揺れ・首かしげ・視線・沈み・もじもじ) は、動きを弱める設定で小さく
        val scaled = setOf(5, 6, 7, 8, 9, 12, 15)
        // 悲しいはゆっくり沈み、照れは少しゆっくり
        val inMs = when (e) { Expression.SAD -> 650; Expression.SHY -> 320; else -> EXPRESS_IN_MS }
        fun blinkNow() { blinkStart = t; ctl.blinkAt = System.nanoTime() }
        // 表情の間ずっと続く小さな動き (戻るときに止める)
        val extras = launch {
            when (e) {
                // ぱちぱち: 驚いたあと、2 回まばたき
                Expression.SURPRISED -> { delay(650); repeat(2) { blinkNow(); delay(230) } }
                // ため息: しばらくして、もう一段ゆっくり沈んで少し戻る
                Expression.SAD -> { delay(900); nod.animateTo(0.03f * motion, tween(800, easing = FastOutSlowInEasing)); nod.animateTo(0.015f * motion, tween(1000)) }
                // ちらっ: 目をそらしたあと、一度だけこちらを見て、またそらす
                Expression.SHY -> {
                    delay(1000)
                    launch { exGx.animateTo(0f, tween(220)); delay(420); exGx.animateTo(0.035f * motion, tween(280)) }
                    exGy.animateTo(0.004f, tween(220)); delay(420); exGy.animateTo(0.025f * motion, tween(280))
                }
                else -> {}
            }
        }
        coroutineScope {
            layers.forEachIndexed { i, a ->
                launch { a.animateTo(target[i] * (if (i in scaled) motion else 1f), tween(inMs, easing = FastOutSlowInEasing)) }
            }
            // 跳ねる・光る
            when (e) {
                Expression.HAPPY -> {
                    // ぴょんと跳ねる: 一度沈んで (ため)、伸びながら跳び、つぶれて着地し、小さく弾んで収まる
                    launch {
                        nod.animateTo(0.022f * motion, tween(100, easing = FastOutSlowInEasing))
                        nod.animateTo(-0.075f * motion, tween(190, easing = LinearOutSlowInEasing))
                        nod.animateTo(0f, spring(dampingRatio = 0.38f, stiffness = 260f))
                    }
                    launch {
                        squashA.animateTo(0.09f * motion, tween(100, easing = FastOutSlowInEasing))
                        squashA.animateTo(-0.08f * motion, tween(150, easing = FastOutSlowInEasing))
                        squashA.animateTo(0f, tween(150))
                        delay(90)
                        squashA.animateTo(0.06f * motion, tween(80, easing = FastOutSlowInEasing))
                        squashA.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 420f))
                    }
                    launch { flash.animateTo(0.45f, tween(150)); flash.animateTo(0f, tween(600)) }
                    addSparkles(fx, t, 6, 1.1f)
                }
                Expression.LAUGH -> {
                    launch { flash.animateTo(0.4f, tween(120)); flash.animateTo(0f, tween(700)) }
                    addSparkles(fx, t, 9, 1.6f)
                }
                Expression.SURPRISED -> {
                    // びくっ: 目がぱっと大きくなり (行き過ぎて戻る)、伸びながら跳び上がり、細かく震え、驚きの線が飛ぶ。白く光る
                    launch { exWide.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 520f)) }
                    launch { nod.animateTo(-0.07f * motion, tween(90, easing = LinearOutSlowInEasing)); nod.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 300f)) }
                    launch { squashA.animateTo(-0.12f * motion, tween(90)); squashA.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 380f)) }
                    launch { exQuiver.snapTo(motion); exQuiver.animateTo(0f, tween(700)) }
                    launch { flash.animateTo(0.75f, tween(80)); flash.animateTo(0f, tween(550)) }
                    addBursts(fx, t)
                }
                Expression.SAD -> {
                    // しょんぼり: ゆっくり沈んでしぼみ、下を向く。まぶたが下がり、光が青く弱まる。少しして涙がひとしずく
                    addTear(fx, t + 0.7f, -1f)
                }
                Expression.SHY -> {
                    // もじもじ: 縮こまって横下を見て、ほほが光り、体を小さく左右にひねる。頭から桃色の湯気がぽっ
                    addPuffs(fx, t + 0.2f)
                }
                Expression.WINK -> {
                    // ぱちっ: 右目を閉じ、首をかしげて小さく弾み、閉じた目のそばで星がキラッ
                    launch { flash.animateTo(0.25f, tween(120)); flash.animateTo(0f, tween(400)) }
                    launch { nod.animateTo(-0.03f * motion, tween(120, easing = LinearOutSlowInEasing)); nod.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 320f)) }
                    launch { squashA.animateTo(-0.05f * motion, tween(110)); squashA.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 420f)) }
                    addWinkStar(fx, t + 0.08f)
                }
            }
        }
        // 音声モードの画面で、まだ話していなければ、話し始めるまで続けてから少しして戻す (最長 EXPRESS_WAIT_SPEECH_MS 待つ)。
        // どちらでも、決まった長さ (holdMs) より短くはしない
        val heldFrom = System.currentTimeMillis()
        if (signals.expressionHoldMs > 0) {
            val left = signals.expressionHoldMs - (System.currentTimeMillis() - startedAt)
            if (left > 0) delay(left)
        } else {
            if (signals.holdUntilSpeaking && moodNow != Mood.SPEAKING) {
                withTimeoutOrNull(EXPRESS_WAIT_SPEECH_MS) { snapshotFlow { moodNow }.first { it == Mood.SPEAKING } }
                delay(EXPRESS_SPEECH_TAIL_MS)
            }
            val left = e.holdMs - (System.currentTimeMillis() - heldFrom)
            if (left > 0) delay(left)
        }
        extras.cancel()
        coroutineScope {
            layers.forEach { a -> launch { a.animateTo(0f, tween(EXPRESS_OUT_MS, easing = FastOutSlowInEasing)) } }
            if (e == Expression.SAD) launch { nod.animateTo(0f, tween(EXPRESS_OUT_MS, easing = FastOutSlowInEasing)) }
        }
        expressing = false
    }
    LaunchedEffect(signals.sentence) {
        if (signals.sentence == 0 || mood != Mood.SPEAKING) return@LaunchedEffect
        nod.animateTo(0.012f * motion, tween(140)); nod.animateTo(0f, tween(200))
    }
    LaunchedEffect(signals.pulse) {
        if (signals.pulse == 0 || mood != Mood.SPEAKING) return@LaunchedEffect
        speech.snapTo(maxOf(speech.value, 0.8f)); speech.animateTo(0f, tween(320, easing = LinearEasing))
    }

    // なめらかにした声の大きさ
    var lv by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableFloatStateOf(0f) }
    // 話している声の、2 秒ほどの平均 (話しぶりの乗り。首の揺れの大きさと、強く言ったところの見分けに使う)
    var talkAvg by remember { mutableFloatStateOf(0f) }
    val lowPower = (mood == Mood.IDLE || mood == Mood.APPROVAL) && !expressing
    LaunchedEffect(lowPower) {
        val start = System.nanoTime() - (t * 1e9).toLong()
        var nextBlink = t + 3f + Random.nextFloat() * 4f
        var last = t
        var lastLoud = 0f; var pauseSeen = true; var lastGesture = 0f; var lastStrong = 0f
        while (true) {
            if (lowPower) delay(40) else withFrameNanos { }
            val now = (System.nanoTime() - start) / 1e9f
            val dt = (now - last).coerceIn(0f, 0.1f); last = now
            t = now
            phase += dt * innerSpeed
            if (fx.isNotEmpty()) fx.removeAll { now - it.born > it.life }
            // 声の大きさ: 上がるときは速く、下がるときはゆっくり。上限を設けて激しく光らせない。
            // 話している間は音節が見えるよう、下がるのも速くする
            val speakingNow = moodNow == Mood.SPEAKING
            val target = if (moodNow == Mood.LISTENING || speakingNow) levelNow.coerceIn(0f, 0.8f) else 0f
            lv += (target - lv) * (if (target > lv) minOf(1f, dt * 12f) else minOf(1f, dt * (if (speakingNow) 7f else 2.5f)))
            if (now >= nextBlink && moodNow != Mood.DONE) {   // 3〜7 秒の不規則な間隔
                blinkStart = now; nextBlink = now + 3f + Random.nextFloat() * 4f
                ctl.blinkAt = System.nanoTime()
            }
            // 声の大きさが取れるときだけ (音楽のステージのように 0 のままなら、ふつうの話す顔)
            if (speakingNow && (levelNow > 0f || lastLoud > 0f)) {
                talkAvg += (lv - talkAvg) * minOf(1f, dt * 0.6f)
                if (lv > TALK_LOUD) { lastLoud = now; pauseSeen = false }
                // 間 (声が 0.3 秒ほど途切れた): まばたき・うなずき・視線を外す のどれか (続けて出しすぎない)
                if (!pauseSeen && lastLoud > 0f && now - lastLoud > 0.3f) {
                    pauseSeen = true
                    if (now - lastGesture > 0.9f) {
                        lastGesture = now
                        when (Random.nextInt(3)) {
                            0 -> { blinkStart = now; nextBlink = now + 2.5f + Random.nextFloat() * 3f; ctl.blinkAt = System.nanoTime() }
                            1 -> launch { nod.animateTo(0.028f * motion, tween(160, easing = FastOutSlowInEasing)); nod.animateTo(0f, tween(260, easing = FastOutSlowInEasing)) }
                            else -> {
                                val side = if (Random.nextBoolean()) 1f else -1f
                                launch { glanceX.animateTo(0.040f * side * motion, tween(200)); delay(550); glanceX.animateTo(0f, tween(380)) }
                                launch { glanceY.animateTo(-0.020f * motion, tween(200)); delay(550); glanceY.animateTo(0f, tween(380)) }
                            }
                        }
                    }
                }
                // 強く言ったところ (いつもの声よりはっきり大きい): 目を見開いて小さく跳ね、少し明るく
                if (lv > TALK_STRONG && lv > talkAvg * 1.6f && now - lastStrong > 2f) {
                    lastStrong = now
                    launch { wide.animateTo(1f, tween(110)); delay(160); wide.animateTo(0f, tween(420)) }
                    launch { nod.animateTo(-0.035f * motion, tween(120, easing = FastOutSlowInEasing)); nod.animateTo(0f, tween(300, easing = FastOutSlowInEasing)) }
                    launch { flash.animateTo(0.45f, tween(110)); flash.animateTo(0f, tween(380)) }
                }
            } else { talkAvg = 0f; lastLoud = 0f }
            // 話しているときの体の動き (声が取れないときは 0)
            val talk = if (speakingNow) lv else 0f
            val lively = (talkAvg * 3f).coerceIn(0f, 1f) * motion
            val voiced = talkAvg > 0f
            // 音楽に合わせてノる (音楽の画面のときだけ): 拍・低音・テンポ・盛り上がりから
            val gr = grooveNow
            if (gr != null) {
                val ev = gz.update(gr, now, dt, motion)
                if (ev and GrooveNow.EV_HYPE != 0) {
                    // 盛り上がりの始まり: わっと跳ねて光り、驚きの線ときらめき (ためていたほど高く跳ねる)
                    val up = 0.06f + 0.04f * gz.anticPeak
                    launch { nod.animateTo(-up * motion, tween(110, easing = LinearOutSlowInEasing)); nod.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 300f)) }
                    launch { squashA.animateTo(-0.1f * motion, tween(100)); squashA.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 380f)) }
                    launch { flash.animateTo(0.6f, tween(100)); flash.animateTo(0f, tween(600)) }
                    addBursts(fx, now); addSparkles(fx, now, 8, 1.2f)
                }
                // 盛り上がっている間は、4 拍ごとにきらめき
                if (ev and GrooveNow.EV_BAR != 0) addSparkles(fx, now, 3, 0.35f)
                // 小節の頭の仕草: まばたき・ウインクの星
                if (ev and GrooveNow.EV_BLINK != 0) { blinkStart = now; ctl.blinkAt = System.nanoTime() }
                if (ev and GrooveNow.EV_WINK != 0) addWinkStar(fx, now + 0.05f)
                // 静かな所から音が戻った: ぱっと目を開ける
                if (ev and GrooveNow.EV_WAKE != 0) {
                    launch { wide.animateTo(1f, tween(90)); wide.animateTo(0f, tween(520)) }
                    launch { flash.animateTo(0.35f, tween(90)); flash.animateTo(0f, tween(450)) }
                }
            } else gz.clear()
            // 体の上下 (半径に対する割合、下が正): 浮遊・うなずき・沈み・声の弾み・笑いの弾み・ルンルン・音楽のノり
            val breathPhase = sin(now * 2f * PI.toFloat() / 5f)
            val (lb, ls) = laughBeat(now, exShake.value, voiced, speech.value)
            bodyOff = breathPhase * floatAmp + nod.value + exSink.value - (if (voiced) speech.value * SPEECH_HOP * motion else 0f) - lb - happyBob(now, exSway.value) + gz.off
            val tiltNow = tilt + talkTilt(now, lively) + exTilt.value + laughTilt(now, exShake.value) + happyTilt(now, exSway.value) +
                wiggleTilt(now, exWiggle.value) + quiverTilt(now, exQuiver.value) + gz.tilt
            // 音楽の画面のヘッドホンを、体に合わせて動かす
            gr?.let { it.outOff = bodyOff; it.outTilt = tiltNow }
            lidL.set(exLid.value, SHY_CURVE, 1f).max(gz.close, gz.curve, gz.smile)
            lidR.set(lidL.close, lidL.curve, lidL.smile).max(maxOf(exWink.value, gz.winkR), 0f, 0f)
            if (use3dNow) {
                // 3D: 置き場所・大きさ・光・目の値を描画スレッドへ渡す (体はばねでついてくるので、揺れは物理で出る)
                ctl.offsetY = -bodyOff
                ctl.squash = squashA.value + ls + gz.squash
                // 表情の色と、盛り上がりの金色の、強いほう
                val tn = if (exTint.value >= gz.tint) tint else TINT_JOY
                ctl.tint = maxOf(exTint.value, gz.tint); ctl.tintLift = tn.lift
                ctl.tintR = tn.rim.red; ctl.tintG = tn.rim.green; ctl.tintB = tn.rim.blue
                ctl.haloR = tn.halo.red; ctl.haloG = tn.halo.green; ctl.haloB = tn.halo.blue
                ctl.scale = 1f + breathAmp * (0.5f + 0.5f * breathPhase) + speech.value * (if (voiced) SPEECH_SWELL else BEAT_SWELL) * motion + talk * TALK_SWELL * motion -
                    exDeflate.value * DEFLATE + gz.scale
                ctl.glow = (glow + breathPhase * 0.03f + lv * 0.35f + speech.value * 0.10f - exDim.value * 0.2f - gz.dim).coerceIn(0f, 1f)
                ctl.flash = flash.value; ctl.level = lv; ctl.warm = warm; ctl.phase = phase
                ctl.tiltDeg = tiltNow
                // 視線: 球ごとその方へ回し (半径の 0.01 で約 2.6°)、目もその中で少しだけ動かす。待機中はゆっくり向きを変える。
                // 話しぶりが乗るほど、左右・上下に小さく首を振る
                val lookX = gx + glanceX.value + exGx.value + gz.gazeX; val lookY = gy + glanceY.value + exGy.value + gz.gazeY
                ctl.yawDeg = lookX * 260f + sin(now * 2f * PI.toFloat() / 11f) * 4.5f * sway + sin(now * 2f * PI.toFloat() / 3.1f) * 7f * lively +
                    sin(now * 2f * PI.toFloat() * HAPPY_HZ * 0.5f) * 6f * exSway.value + sin(now * 2f * PI.toFloat() * WIGGLE_HZ * 0.5f) * 5f * exWiggle.value + gz.yaw
                ctl.pitchDeg = -lookY * 260f + sin(now * 2f * PI.toFloat() / 17f + 1f) * 2.5f * sway + sin(now * 2f * PI.toFloat() / 2.3f) * 3f * lively
                ctl.gazeX = lookX * 0.5f; ctl.gazeY = lookY * 0.5f
                ctl.eyeOpen = open + wakeOpen.value * 0.08f + wide.value * WIDE_OPEN + talk * TALK_OPEN + exWide.value * 0.1f; ctl.squint = squint.value; ctl.asym = asym
                ctl.eyeCloseL = lidL.close; ctl.eyeCurveL = lidL.curve; ctl.eyeSmileL = lidL.smile
                ctl.eyeCloseR = lidR.close; ctl.eyeCurveR = lidR.curve; ctl.eyeSmileR = lidR.smile
                ctl.eyeDroop = exDroop.value; ctl.eyeSqueeze = maxOf(exSqueeze.value, gz.squeeze); ctl.eyeScale = 1f + exWide.value * SURPRISE_SCALE + gz.eyeScale
                ctl.blush = maxOf(exBlush.value, gz.blush)
                ctl.active = !lowPower; ctl.reduceMotion = reduceMotion
            }
        }
    }

    val density = LocalDensity.current
    val maxDiameter = with(density) { 360.dp.toPx() }
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val r = AvatarGeometry.radius(w, h, fullScreen, maxDiameter, big, compact)
        val c0 = AvatarGeometry.center(w, h, fullScreen, big)
        if (use3d) {
            // 球のまわり (上下に半径の 2.1 倍) だけを描画面にする。外側の光もこの中に収まる
            val top = (c0.y - r * 2.1f).coerceAtLeast(0f)
            val bottom = (c0.y + r * 2.1f).coerceAtMost(h)
            ctl.cx = c0.x; ctl.cy = c0.y - top; ctl.radiusPx = r
            Orb3DSurface(
                ctl,
                Modifier.offset { IntOffset(0, top.roundToInt()) }.size(with(density) { w.toDp() }, with(density) { (bottom - top).toDp() }),
                onFail = { glFailed = true },
            )
            Box(Modifier.fillMaxSize().pointerInput(c0, r, top) { orbTouch(ctl, c0, r, top) })
        } else {
            // 1 フレーム分の値 (描画の中で読むので、値が変わると描き直しだけが走る)
            fun frame(center: Offset): OrbFrame {
                val breathPhase = sin(t * 2f * PI.toFloat() / 5f)               // 5 秒で一往復
                val talk = if (mood == Mood.SPEAKING) lv else 0f
                val lively = (talkAvg * 3f).coerceIn(0f, 1f) * motion
                val voiced = talkAvg > 0f
                val (_, ls) = laughBeat(t, exShake.value, voiced, speech.value)
                val floatY = bodyOff * r
                val scl = 1f + breathAmp * (0.5f + 0.5f * breathPhase) + speech.value * (if (voiced) SPEECH_SWELL else BEAT_SWELL) * motion + talk * TALK_SWELL * motion -
                    exDeflate.value * DEFLATE + gz.scale
                val glowNow = (glow + breathPhase * 0.03f + lv * 0.35f + speech.value * 0.10f - exDim.value * 0.2f - gz.dim).coerceIn(0f, 1f)
                val eye = EyeState(gx + glanceX.value + exGx.value + gz.gazeX, gy + glanceY.value + exGy.value + gz.gazeY,
                    open + wakeOpen.value * 0.08f + wide.value * WIDE_OPEN + talk * TALK_OPEN + exWide.value * 0.1f,
                    blinkFactor(t - blinkStart), squint.value, asym,
                    droop = exDroop.value, scale = 1f + exWide.value * SURPRISE_SCALE + gz.eyeScale, squeeze = maxOf(exSqueeze.value, gz.squeeze),
                    closeL = lidL.close, curveL = lidL.curve, smileL = lidL.smile, closeR = lidR.close, curveR = lidR.curve, smileR = lidR.smile)
                return OrbFrame(center + Offset(0f, floatY), r, scl,
                    tilt + talkTilt(t, lively) + exTilt.value + laughTilt(t, exShake.value) + happyTilt(t, exSway.value) + wiggleTilt(t, exWiggle.value) + quiverTilt(t, exQuiver.value) + gz.tilt,
                    eye, glowNow, flash.value, warm, phase, lv, blush = maxOf(exBlush.value, gz.blush), squash = squashA.value + ls + gz.squash,
                    tintAmt = maxOf(exTint.value, gz.tint), tint = if (exTint.value >= gz.tint) tint else TINT_JOY)
            }
            // 周りの光 (ぼけた広がり)
            Canvas(Modifier.fillMaxSize()) { drawOrbHalo(frame(c0), fit = compact) }
            // くっきりした層: ガラスの本体・厚み・映り込み・目
            Canvas(Modifier.fillMaxSize()) { val f = frame(c0); orbTransform(f) { drawOrbBody(f, style) } }
            // にじむ光: 縁・目・反射を明るく描いて GPU でぼかし、本体の上に光として足す (球のまわりだけの大きさにして負荷を抑える)
            val side = r * 3.2f
            Canvas(
                Modifier
                    .offset { IntOffset((c0.x - side / 2f).roundToInt(), (c0.y - side / 2f).roundToInt()) }
                    .size(with(density) { side.toDp() })
                    .graphicsLayer {
                        renderEffect = BlurEffect(r * 0.085f, r * 0.085f, TileMode.Decal)
                    },
            ) { val f = frame(Offset(side / 2f, side / 2f)); orbTransform(f) { drawOrbBloom(f) } }
        }
        if (fx.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
            val now = t; val c = c0 + Offset(0f, bodyOff * r)
            val eyeGx = gx + glanceX.value + exGx.value; val eyeGy = gy + glanceY.value + exGy.value
            for (f in fx) drawFx(f, now, c, r, eyeGx, eyeGy)
        }
    }
}

/** 話している声の大きさの目安: これより大きければ声が出ている、強く言った */
private const val TALK_LOUD = 0.10f
private const val TALK_STRONG = 0.42f

/** 話しぶりが乗っているときの首の傾きの揺れ (度)。乗りが 0 なら揺らさない */
private fun talkTilt(t: Float, lively: Float) = sin(t * 2f * PI.toFloat() / 2.7f) * 5f * lively

/**
 * 話している間の動きの大きさ (半径に対する割合): 声の波でふくらむ・音節で弾む・音節でふくらむ、目の開き (声の波・強く言ったところ)。
 * 音節で弾む・ふくらむのは、声の大きさが取れるときだけ大きくする (音楽のステージの拍の動きは、小さい BEAT_SWELL を使う)
 */
private const val TALK_SWELL = 0.06f
private const val SPEECH_HOP = 0.02f
private const val SPEECH_SWELL = 0.02f
private const val BEAT_SWELL = 0.012f
private const val TALK_OPEN = 0.20f
private const val WIDE_OPEN = 0.22f
/** 感情の表情: 出る・戻るのにかける時間、驚きの目の大きさの倍率の増え */
private const val EXPRESS_IN_MS = 180
private const val EXPRESS_OUT_MS = 380
/** 音声モードの画面で、話し始めるのを待つ長さの上限と、話し始めてから表情を続ける長さ */
private const val EXPRESS_WAIT_SPEECH_MS = 6_000L
private const val EXPRESS_SPEECH_TAIL_MS = 2_000L
private const val SURPRISE_SCALE = 0.28f

/**
 * 表情ごとの目標の値。順番は exLid, exWink, exDroop, exWide, exBlush, exShake, exTilt (度), exGx, exGy, exSink, exDim, exTint, exSway,
 * exSqueeze, exDeflate, exWiggle
 * - うれしい: 目は普段のまま、ほほを照れるときと同じに光らせ、金色の光をまとう。ぴょんと跳ねて (ため・伸び・つぶれて着地)、
 *   左右に揺れながら弾む (ルンルン)。少し上を見て、きらめきが散る
 * - 笑う: 目を ＞＜ に、ほほを強く光らせ、金色の光。頭を少し後ろへ反らし、「は・は・は」に合わせて弾み (声が取れれば声の音節に、
 *   取れなければ 1 秒に 4 回ほど)、左右に揺れる。きらめきが多めに散る
 * - 驚き: 目がぱっと大きくなり (行き過ぎて戻る)、伸びながら跳び上がり、細かく震え、驚きの線が飛ぶ。白く光り、ぱちぱちとまばたき
 * - 悲しい: 外側が下がった上まぶた、下を見て、ゆっくり沈んでしぼみ、光が青く弱まる。ため息のようにもう一段沈み、涙がひとしずく
 * - 照れ: ほほを強く光らせ、目を少し細め、縮こまって横下を見て首をかしげ、体を小さく左右にひねる。一度ちらっとこちらを見る。
 *   桃色の光と、頭から桃色の湯気
 * - ウインク: 右目を閉じ (ー。∩ ではなく ー)、首をかしげて小さく弾み、ほほを少し光らせる。閉じた目のそばで星がキラッ
 */
private fun expressionTargets(e: Expression): FloatArray = when (e) {
    Expression.HAPPY -> floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, -0.012f, 0f, 0f, 1f, 1f, 0f, 0f, 0f)
    Expression.LAUGH -> floatArrayOf(0f, 0f, 0f, 0f, 0.85f, 1f, 0f, 0f, -0.03f, 0f, 0f, 1f, 0f, 1f, 0f, 0f)
    Expression.SURPRISED -> floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, -0.02f, 0f, 0f, 0.7f, 0f, 0f, 0f, 0f)
    Expression.SAD -> floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f, -4f, 0f, 0.035f, 0.04f, 1f, 0.65f, 0f, 0f, 1f, 0f)
    Expression.SHY -> floatArrayOf(0.45f, 0f, 0f, 0f, 1f, 0f, 6f, 0.035f, 0.025f, 0f, 0f, 0.6f, 0f, 0f, 0.6f, 1f)
    Expression.WINK -> floatArrayOf(0f, 1f, 0f, 0f, 0.3f, 0f, 6f, 0f, 0f, 0f, 0f, 0.45f, 0f, 0f, 0f, 0f)
}

/** 表情の色: 縁の色・まわりの光の色・明るさの増減 (うれしい・笑うは金色、驚きは白、悲しいは青、照れは桃色、ウインクは淡い橙) */
private class Tint(val rim: Color, val halo: Color, val lift: Float)
private val TINT_JOY = Tint(Color(1f, 0.86f, 0.60f), Color(0.95f, 0.70f, 0.38f), 0.35f)
private fun tintOf(e: Expression) = when (e) {
    Expression.HAPPY, Expression.LAUGH -> TINT_JOY
    Expression.SURPRISED -> Tint(Color(0.88f, 0.98f, 1f), Color(0.70f, 0.90f, 1f), 0.5f)
    Expression.SAD -> Tint(Color(0.50f, 0.58f, 1f), Color(0.22f, 0.30f, 0.80f), -0.3f)
    Expression.SHY -> Tint(Color(1f, 0.66f, 0.80f), Color(0.95f, 0.45f, 0.65f), 0.15f)
    Expression.WINK -> Tint(Color(1f, 0.82f, 0.72f), Color(0.95f, 0.62f, 0.50f), 0.2f)
}

/** しぼむ・縮こまる大きさ (倍率の減り) */
private const val DEFLATE = 0.04f
/** もじもじ: 首を左右にかしげながら体をひねる (度) */
private const val WIGGLE_HZ = 1.6f
private fun wiggleTilt(t: Float, w: Float) = sin(t * 2f * PI.toFloat() * WIGGLE_HZ * 0.5f) * 4f * w
/** びくっと細かく震える (度) */
private fun quiverTilt(t: Float, q: Float) = sin(t * 2f * PI.toFloat() * 11f) * 2.2f * q

/**
 * 笑いの弾み: (上への弾み (半径に対する割合), つぶれ・伸び)。声が取れていれば「は・は・は」の音節の立ち上がり (speech) ごとに
 * 伸びて跳ね、取れなければ 1 秒に 4.4 回のリズムで (下でつぶれ、上で伸びる)
 */
private fun laughBeat(t: Float, shake: Float, voiced: Boolean, speech: Float): Pair<Float, Float> {
    if (shake <= 0f) return 0f to 0f
    if (voiced) return speech * 0.045f * shake to (0.02f - speech * 0.08f) * shake
    val s = abs(sin(t * 2f * PI.toFloat() * 2.2f))
    return s * 0.035f * shake to (0.35f - s) * 0.09f * shake
}
/** 笑いの揺れ: 首を左右に揺らす (度) */
private fun laughTilt(t: Float, shake: Float) = sin(t * 2f * PI.toFloat() * 1.4f) * 6f * shake

/** うれしいときのルンルン: 左右に首を揺らし (度)、揺れの端ごとに小さく弾む (半径に対する割合) */
private const val HAPPY_HZ = 1.2f
private fun happyTilt(t: Float, sway: Float) = sin(t * 2f * PI.toFloat() * HAPPY_HZ * 0.5f) * 7f * sway
private fun happyBob(t: Float, sway: Float) = abs(sin(t * 2f * PI.toFloat() * HAPPY_HZ * 0.5f)) * 0.016f * sway

/** 表情のまわりの演出の種類: きらめき・驚きの線・涙・照れの湯気 */
private enum class FxKind { SPARKLE, BURST, TEAR, PUFF }

/**
 * 演出 1 つ: 生まれた時刻 (秒)・角度 (度、上が -90)・中心からの距離 (半径比)・大きさ (半径比)・向き (回る向き、涙はどちらの目か)・寿命 (秒)
 */
private class Fx(val kind: FxKind, val born: Float, val angle: Float, val dist: Float, val size: Float, val spin: Float, val life: Float)

/** 球の上半分のまわりに、少しずつずらしてきらめきを足す */
private fun addSparkles(list: MutableList<Fx>, now: Float, n: Int, over: Float) {
    for (i in 0 until n) {
        val born = now + over * i / n + Random.nextFloat() * 0.06f
        list.add(Fx(FxKind.SPARKLE, born, -170f + Random.nextFloat() * 160f, 1.12f + Random.nextFloat() * 0.38f,
            0.055f + Random.nextFloat() * 0.06f, if (Random.nextBoolean()) 1f else -1f, 0.85f))
    }
}

/** 驚きの線: 球の上のまわりから、外へ向かって 7 本飛ぶ */
private fun addBursts(list: MutableList<Fx>, now: Float) {
    for (i in 0 until 7) {
        val a = -155f + 130f * i / 6f + (Random.nextFloat() - 0.5f) * 8f
        list.add(Fx(FxKind.BURST, now + Random.nextFloat() * 0.05f, a, 1.1f, 0.24f + Random.nextFloat() * 0.06f, 1f, 0.55f))
    }
}

/** 涙: side の目 (-1 = 見て左) の下の外側にふくらみ、頬をつたって落ちる */
private fun addTear(list: MutableList<Fx>, born: Float, side: Float) {
    list.add(Fx(FxKind.TEAR, born, 0f, 0f, 0.055f, side, 2.0f))
}

/** 照れの湯気: 頭の上から、桃色の丸い湯気が 3 つ、ずらして立ちのぼる */
private fun addPuffs(list: MutableList<Fx>, now: Float) {
    for (i in 0 until 3) {
        list.add(Fx(FxKind.PUFF, now + i * 0.32f, -112f + i * 22f + (Random.nextFloat() - 0.5f) * 10f, 1.0f, 0.07f + Random.nextFloat() * 0.03f,
            if (i % 2 == 0) 1f else -1f, 1.6f))
    }
}

/** ウインクの星: 閉じた右目のそばに大きな星が 1 つ、その近くに小さな星が 2 つ */
private fun addWinkStar(list: MutableList<Fx>, now: Float) {
    list.add(Fx(FxKind.SPARKLE, now, -28f, 0.86f, 0.15f, 1f, 0.9f))
    list.add(Fx(FxKind.SPARKLE, now + 0.12f, -48f, 1.08f, 0.07f, -1f, 0.75f))
    list.add(Fx(FxKind.SPARKLE, now + 0.2f, -10f, 1.12f, 0.06f, 1f, 0.7f))
}

/** 演出を 1 つ描く。c は今の体の中心 (上下のずれ込み)、eyeGx・eyeGy は今の視線 (涙の置き場所に使う) */
private fun DrawScope.drawFx(f: Fx, now: Float, c: Offset, r: Float, eyeGx: Float, eyeGy: Float) {
    val u = (now - f.born) / f.life
    if (u !in 0f..1f) return
    when (f.kind) {
        FxKind.SPARKLE -> drawSparkle(f, u, c, r)
        FxKind.BURST -> {
            // 外へ飛びながら伸びて縮み、消える。まわりに淡い水色の光
            val a = Math.toRadians(f.angle.toDouble())
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
            val start = 1.08f + 0.32f * (1f - (1f - u) * (1f - u))
            val len = f.size * sin(u * PI.toFloat()).pow(0.7f)
            val p0 = c + dir * (start * r); val p1 = c + dir * ((start + len) * r)
            val al = (1f - u * u).coerceIn(0f, 1f)
            drawLine(Color(0xFFA8E4FF).copy(alpha = 0.35f * al), p0, p1, strokeWidth = r * 0.07f, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = al), p0, p1, strokeWidth = r * 0.025f * (1f - 0.4f * u), cap = StrokeCap.Round)
        }
        FxKind.TEAR -> {
            // 目の下の外側でふくらみ (はじめの 25%)、重さで速くなりながら頬をつたって落ち、最後に薄れて消える
            val ex = c.x + f.spin * r * 0.42f + eyeGx * r
            val ey = c.y - r * 0.04f + eyeGy * r
            val start = Offset(ex + f.spin * r * 0.07f, ey + r * 0.17f)
            val grow = (u / 0.25f).coerceIn(0f, 1f)
            val fall = ((u - 0.25f) / 0.75f).coerceIn(0f, 1f)
            val p = start + Offset(f.spin * r * 0.02f * fall, r * 0.40f * fall * fall)
            val al = if (u < 0.75f) 1f else ((1f - u) / 0.25f).coerceIn(0f, 1f)
            val rr = f.size * r * (0.4f + 0.6f * grow)
            if (fall > 0f) drawLine(Color(0xFFBDEBFF).copy(alpha = 0.22f * al), start, p, strokeWidth = rr * 0.5f, cap = StrokeCap.Round)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF9FDCFF).copy(alpha = 0.45f * al), Color.Transparent), center = p, radius = rr * 2.4f), rr * 2.4f, p)
            drawPath(tearPath(p, rr), Brush.verticalGradient(listOf(Color(0xFFEAF8FF), Color(0xFFA9DCFF), Color(0xFF6FC0F5)), startY = p.y - rr * 2.2f, endY = p.y + rr),
                alpha = al)
            drawCircle(Color.White.copy(alpha = 0.9f * al), rr * 0.28f, p + Offset(-rr * 0.35f, -rr * 0.25f))
        }
        FxKind.PUFF -> {
            // 頭の上から立ちのぼり、ゆらゆら横に揺れながらふくらんで薄れる
            val a = Math.toRadians(f.angle.toDouble())
            val base = c + Offset(cos(a).toFloat() * f.dist * r, sin(a).toFloat() * f.dist * r)
            val p = base + Offset(sin(u * PI.toFloat() * 2f) * r * 0.05f * f.spin, -r * 0.55f * u)
            val rr = f.size * r * (1f + 0.7f * u)
            val al = sin(u * PI.toFloat()).pow(0.8f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFC2D8).copy(alpha = 0.55f * al), Color(0xFFFF8FB8).copy(alpha = 0.25f * al), Color.Transparent),
                center = p, radius = rr * 1.6f), rr * 1.6f, p)
            drawCircle(Color.White.copy(alpha = 0.35f * al), rr * 0.35f, p + Offset(-rr * 0.3f, -rr * 0.3f))
        }
    }
}

/** 涙の形: 下が丸く、上がとがったしずく (p は丸い所の中心、rr はその半径) */
private fun tearPath(p: Offset, rr: Float) = Path().apply {
    moveTo(p.x, p.y - rr * 2.2f)
    cubicTo(p.x + rr * 0.35f, p.y - rr * 1.3f, p.x + rr, p.y - rr * 0.6f, p.x + rr, p.y)
    cubicTo(p.x + rr, p.y + rr * 0.6f, p.x + rr * 0.55f, p.y + rr, p.x, p.y + rr)
    cubicTo(p.x - rr * 0.55f, p.y + rr, p.x - rr, p.y + rr * 0.6f, p.x - rr, p.y)
    cubicTo(p.x - rr, p.y - rr * 0.6f, p.x - rr * 0.35f, p.y - rr * 1.3f, p.x, p.y - rr * 2.2f)
    close()
}

/** きらめき: 4 本の光の筋の星 (細い斜めの筋つき) と、まわりのあたたかい光。ふくらんで、回りながら消える */
private fun DrawScope.drawSparkle(sp: Fx, u: Float, c: Offset, r: Float) {
    val k = sin(u * PI.toFloat()).pow(0.8f)
    val a = Math.toRadians(sp.angle.toDouble())
    val p = c + Offset((cos(a) * sp.dist * r).toFloat(), (sin(a) * sp.dist * r).toFloat())
    val s = sp.size * r * k
    if (s < 0.5f) return
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE2A8).copy(alpha = 0.5f * k), Color.Transparent), center = p, radius = s * 1.8f), s * 1.8f, p)
    rotate(sp.spin * 90f * u, pivot = p) {
        drawPath(starPath(p, s), Brush.radialGradient(listOf(Color.White, Color(0xFFFFEBC0), Color(0xFFFFC970).copy(alpha = 0f)), center = p, radius = s), alpha = k)
        rotate(45f, pivot = p) { drawPath(starPath(p, s * 0.45f), Color(0xFFFFF4DC), alpha = 0.8f * k) }
    }
    drawCircle(Color.White.copy(alpha = k), s * 0.12f, p)
}

/** 4 本の光の筋の星 (筋は中心に向かってくびれる) */
private fun starPath(p: Offset, s: Float) = Path().apply {
    moveTo(p.x, p.y - s)
    quadraticTo(p.x, p.y, p.x + s, p.y)
    quadraticTo(p.x, p.y, p.x, p.y + s)
    quadraticTo(p.x, p.y, p.x - s, p.y)
    quadraticTo(p.x, p.y, p.x, p.y - s)
    close()
}

/** 目の開きの上限 (話している間に大きく見開けるよう、広めにしてある) */
private const val EYE_OPEN_MAX = 1.35f

/** 1 フレーム分の球の状態 */
private class OrbFrame(val c: Offset, val r: Float, val scl: Float, val tilt: Float, val eye: EyeState, val glow: Float, val flash: Float, val warm: Float, val phase: Float, val level: Float,
                       /** ほほの光 0..1 */
                       val blush: Float = 0f,
                       /** つぶれ・伸び (正で横に広く縦に低く) */
                       val squash: Float = 0f,
                       /** 表情の色の光の強さ 0..1 と、その色 */
                       val tintAmt: Float = 0f,
                       val tint: Tint = TINT_JOY) {
    /** にじむ光の強さ (状態・声・一度だけの反応で変わる。白飛びしないよう上限) */
    val bloom get() = (0.55f + glow * 0.45f + flash * 0.35f + level * 0.25f).coerceIn(0f, 1.25f)
}

private inline fun DrawScope.orbTransform(f: OrbFrame, block: DrawScope.() -> Unit) {
    val sq = f.squash.coerceIn(-0.15f, 0.15f)
    rotate(f.tilt, pivot = f.c) { scale(f.scl * (1f + sq * 0.5f), f.scl * (1f - sq), pivot = f.c) { block() } }
}

/** 目の状態。droop は悲しい目、scale は大きさの倍率、squeeze は笑う目 (＞＜)、close・curve・smile は左右のまぶた */
private data class EyeState(val gx: Float, val gy: Float, val open: Float, val blink: Float, val squint: Float, val asym: Float,
                            val droop: Float = 0f, val scale: Float = 1f, val squeeze: Float = 0f,
                            val closeL: Float = 0f, val curveL: Float = 0f, val smileL: Float = 0f,
                            val closeR: Float = 0f, val curveR: Float = 0f, val smileR: Float = 0f)

/**
 * まぶた 1 つ分: 閉じる度合い 0..1・閉じた線の反り (正で上に反る笑顔 ︵、0 で ー、負で下に反る穏やかな ︶)・にっこり 0..1
 * (自然に見えるよう、三日月の形にはせず、まぶたが閉じる線へ寄っていく作りにしてある)
 */
private class Lid(var close: Float = 0f, var curve: Float = 0f, var smile: Float = 0f) {
    fun set(k: Float, c: Float, s: Float) = apply { close = k; curve = c; smile = s }
    /** 閉じる度合いが強いほうにする */
    fun max(k: Float, c: Float, s: Float) = apply { if (k > close) { close = k; curve = c; smile = s } }
}

/** 照れのにっこりの目の、閉じた線の反り */
private const val SHY_CURVE = 0.8f

/** まばたき: 約 180ms で縦に細くして戻す (経過秒 → 目の高さの倍率) */
private fun blinkFactor(since: Float): Float {
    val d = 0.18f
    if (since !in 0f..d) return 1f
    return 1f - 0.92f * sin(since / d * PI.toFloat())
}

// ==================================================================== 光る球

private val RIM = Color(0xFFA8DFFF)      // 輪郭の光
private val CYAN = Color(0xFF71D9FF)     // 下側の光
private val EYE_HI = Color(0xFFDBF7FF)   // 目の光

/** 縁の色: 上と横は青白く、右上が最も明るく、下はシアン寄り (sweep は 3 時の位置から時計回り) */
private fun rimSweep(c: Offset, k: Float, warm: Float = 0f) = Brush.sweepGradient(
    0.00f to Color(0xFFCBEBFF).copy(alpha = (0.85f * k).coerceIn(0f, 1f)),
    0.25f to Color(0xFFA6EEFF).copy(alpha = (1.00f * k).coerceIn(0f, 1f)),
    0.42f to lerp(Color(0xFFA7D2F2), Color(0xFFFFC98A), 0.35f * warm).copy(alpha = (0.70f * k).coerceIn(0f, 1f)),
    0.55f to Color(0xFFA7D2F2).copy(alpha = (0.68f * k).coerceIn(0f, 1f)),
    0.75f to Color(0xFFD3EEFF).copy(alpha = (0.85f * k).coerceIn(0f, 1f)),
    0.875f to Color.White.copy(alpha = (1.00f * k).coerceIn(0f, 1f)),
    1.00f to Color(0xFFCBEBFF).copy(alpha = (0.85f * k).coerceIn(0f, 1f)),
    center = c,
)

/** 周りの淡い青い光 (輪郭のぼけた広がり) と、遠くの淡い輪。fit: 描く面の端までに消えるようにする (小さな窓で、光が四角く切れないように) */
private fun DrawScope.drawOrbHalo(f: OrbFrame, fit: Boolean = false) {
    val c = f.c; val r = f.r * f.scl
    val edge = if (fit) minOf(c.x, c.y, size.width - c.x, size.height - c.y).coerceAtLeast(1f) else Float.MAX_VALUE
    val far = minOf(r * 2.4f, edge)
    val ring = minOf(r * 1.32f, edge)
    val k = (0.6f + f.glow * 0.4f + f.flash * 0.3f + f.level * 0.2f).coerceIn(0f, 1.3f) * (1f + f.tint.lift * f.tintAmt)
    // 表情の色: まわりの光をその色に寄せる (うれしい・笑うは金色)
    val h1 = lerp(Color(0xFF2A6FB8), f.tint.halo, 0.55f * f.tintAmt)
    val h2 = lerp(Color(0xFF1F5594), lerp(f.tint.halo, Color.Black, 0.22f), 0.55f * f.tintAmt)
    drawCircle(Brush.radialGradient(
        0.0f to h1.copy(alpha = (0.22f * k).coerceIn(0f, 1f)), 0.42f to h1.copy(alpha = (0.16f * k).coerceIn(0f, 1f)),
        0.62f to h2.copy(alpha = (0.06f * k).coerceIn(0f, 1f)), 1.0f to Color.Transparent,
        center = c, radius = far,
    ), far, c)
    drawCircle(Brush.radialGradient(
        0.0f to Color.Transparent, 0.885f to Color.Transparent, 0.94f to Color(0xFF6FB4F0).copy(alpha = (0.075f * k).coerceIn(0f, 1f)), 1.0f to Color.Transparent,
        center = c, radius = ring,
    ), ring, c)
}

/** にじむ光の層 (このあと GPU でぼかす): 縁・右上の反射・下側の反射と光点・目 */
private fun DrawScope.drawOrbBloom(f: OrbFrame) {
    val c = f.c; val r = f.r; val k = f.bloom
    fun al(x: Float) = (x * k).coerceIn(0f, 1f)
    drawCircle(rimSweep(c, 0.42f * k, f.warm), r, c, style = Stroke(width = r * 0.06f))
    drawArc(Color.White.copy(alpha = al(0.42f)), -74f, 50f, false, c - Offset(r * 0.93f, r * 0.93f), Size(r * 1.86f, r * 1.86f), style = Stroke(width = r * 0.09f, cap = StrokeCap.Round))
    drawArc(CYAN.copy(alpha = al(0.35f)), 50f, 80f, false, c - Offset(r * 0.93f, r * 0.93f), Size(r * 1.86f, r * 1.86f), style = Stroke(width = r * 0.10f, cap = StrokeCap.Round))
    drawOval(Color.White.copy(alpha = al(0.55f)), topLeft = Offset(c.x - r * 0.26f, c.y + r * 0.86f), size = Size(r * 0.52f, r * 0.11f))
    if (f.blush > 0.01f) drawBlush(f, 0.35f)
    if (f.tintAmt > 0.01f) drawCircle(f.tint.rim.copy(alpha = (0.5f * f.tintAmt).coerceIn(0f, 1f)), r, c, style = Stroke(width = r * 0.08f))
    forEachEye(f) { ec, w, h, side ->
        val sq = f.eye.squeeze
        eyeLid(ec, w, h, side, f.eye.droop) {
            lidClip(ec, w, h * 1.1f, f.eye.closeOf(side), f.eye.curveOf(side), f.eye.smileOf(side)) {
                drawOval(Color(0xFF9FD8FF).copy(alpha = al(0.55f)), topLeft = ec - Offset(w * 1.15f, h * 1.1f), size = Size(w * 2.3f, h * 2.2f), alpha = 1f - sq)
            }
            if (sq > 0.01f) drawPath(chevron(ec, w, side), Color(0xFF9FD8FF).copy(alpha = al(0.7f)), alpha = sq,
                style = Stroke(width = w * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** 目の位置と大きさ: 幅 = 直径の 14%、高さ = 20%。中心は球の中の横 29% と 71%、縦は少し上 (48%)。うれしい目は少し横長、驚きは大きく */
private inline fun forEachEye(f: OrbFrame, block: (ec: Offset, w: Float, h: Float, side: Float) -> Unit) {
    val c = f.c; val r = f.r; val e = f.eye
    for (side in floatArrayOf(-1f, 1f)) {
        val right = side > 0
        val asymScale = if (right) 1f - 0.12f * e.asym else 1f
        val close = e.closeOf(side); val smile = e.smileOf(side)
        // まぶたを閉じた目・笑う目は、まばたき・細めをしない。閉じた目は少し横に長く、にっこりの目はほほに押されて少し上がる
        val keep = maxOf(e.squeeze, close)
        val ec = Offset(c.x + side * r * 0.42f + e.gx * r, c.y - r * 0.04f + e.gy * r - (if (right) 0.02f * e.asym * r else 0f) - 0.025f * r * close * smile)
        val w = r * 0.14f * asymScale * e.scale * (1f + 0.15f * close)
        val openF = e.open.coerceIn(0.85f, EYE_OPEN_MAX); val shutF = e.blink * (1f - 0.45f * e.squint)
        val h = r * 0.20f * asymScale * e.scale * (openF + (1f - openF) * keep) * (shutF + (1f - shutF) * keep)
        block(ec, w, h, side)
    }
}

private fun EyeState.closeOf(side: Float) = if (side > 0) closeR else closeL
private fun EyeState.curveOf(side: Float) = if (side > 0) curveR else curveL
private fun EyeState.smileOf(side: Float) = if (side > 0) smileR else smileL

/**
 * まぶた: 上まぶたと下まぶたの間だけを描く (閉じていなければ、そのまま描く)。3D の目と同じ線:
 * 目の中の座標 (横 x・上 y、-1..1) で、閉じた線 yc = -0.05 + 反り × 0.42 × (1 - x²)、その太さの半分 th = 0.16√(1 - x²) + 0.012。
 * 上まぶたは 1.25 から yc + th へ、下まぶたは -1.25 から yc - th へ寄る (にっこりでは下まぶたが先に上がる)。
 * 閉じる途中は、上まぶたを上に反った弧に (目尻から閉じる。平らな線だとにらんだ目に見える)、
 * 下まぶたは目の丸みに沿った弧 (にっこりのときは上に押し上がる弧) にする
 */
private fun DrawScope.lidClip(ec: Offset, w: Float, h: Float, close: Float, curve: Float, smile: Float, block: DrawScope.() -> Unit) {
    if (close < 0.001f) { block(); return }
    val ku = close * (1f - 0.45f * smile)
    val kl = minOf(1f, close * (1f + smile))
    fun ys(x: Float): Pair<Float, Float> {
        val x2 = minOf(x * x, 1f)
        val yc = -0.05f + curve * 0.42f * (1f - x2)
        val th = 0.16f * sqrt(1f - x2) + 0.012f
        return (1.25f + (yc + th - 1.25f) * ku + 0.32f * 4f * ku * (1f - ku) * (1f - x2)) to
            (-1.25f + (yc - th + 1.25f) * kl + (0.35f * smile - 0.25f * (1f - smile)) * 4f * kl * (1f - kl) * (1f - x2))
    }
    val n = 20
    val path = Path().apply {
        for (i in 0..n) {
            val x = -1.3f + 2.6f * i / n
            val y = ec.y - ys(x).first * h
            if (i == 0) moveTo(ec.x + x * w, y) else lineTo(ec.x + x * w, y)
        }
        for (i in n downTo 0) {
            val x = -1.3f + 2.6f * i / n
            lineTo(ec.x + x * w, ec.y - ys(x).second * h)
        }
        close()
    }
    clipPath(path) { block() }
}

/**
 * 悲しい目の上まぶた: 外側ほど下がる線より下だけを描く (droop が 0 なら、そのまま描く)。
 * 3D と同じ線: 目の中の座標 (横 x・上 y、-1..1) で y = (1.6 から 0.45 へ) - side × droop × 0.55 × x
 */
private fun DrawScope.eyeLid(ec: Offset, w: Float, h: Float, side: Float, droop: Float, block: DrawScope.() -> Unit) {
    if (droop < 0.001f) { block(); return }
    val base = 1.6f + (0.45f - 1.6f) * droop
    val x0 = -1.8f; val x1 = 1.8f
    val path = Path().apply {
        moveTo(ec.x + x0 * w, ec.y - (base - side * droop * 0.55f * x0) * h)
        lineTo(ec.x + x1 * w, ec.y - (base - side * droop * 0.55f * x1) * h)
        lineTo(ec.x + x1 * w, ec.y + h * 4f); lineTo(ec.x + x0 * w, ec.y + h * 4f); close()
    }
    clipPath(path) { block() }
}

/** 笑う目: 左目は ＞、右目は ＜ (とがった先が鼻の側)。目の幅 w を元にした「く」の字 (3D の目と同じ形) */
private fun chevron(ec: Offset, w: Float, side: Float) = Path().apply {
    moveTo(ec.x + side * 0.85f * w, ec.y - 0.95f * w)
    lineTo(ec.x - side * 0.80f * w, ec.y)
    lineTo(ec.x + side * 0.85f * w, ec.y + 0.95f * w)
}

/** ほほの光: 目の下の外側に、横長の柔らかい桃色 (視線の向きに少しついていく) */
private fun DrawScope.drawBlush(f: OrbFrame, k: Float) {
    val c = f.c; val r = f.r; val e = f.eye
    for (sd in floatArrayOf(-1f, 1f)) {
        val bc = Offset(c.x + sd * r * 0.49f + e.gx * r * 0.6f, c.y + r * 0.25f + e.gy * r * 0.6f)
        scale(1f, 0.62f, pivot = bc) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFB38F).copy(alpha = (0.6f * k * f.blush).coerceIn(0f, 1f)), Color.Transparent), center = bc, radius = r * 0.26f), r * 0.26f, bc)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFF7FA8).copy(alpha = (k * f.blush).coerceIn(0f, 1f)), Color.Transparent), center = bc, radius = r * 0.16f), r * 0.16f, bc)
        }
    }
}

/**
 * くっきりした層: 透明感のあるガラスの球。口・鼻・耳・手足は描かない。
 * 中は上が黒に近く、下は内側から青く光る。縁はガラスの厚み (帯と内側の殻の線)、右上の反射、左の淡い筋、
 * 下半球の反射の帯 (口ではなく内側の反射) と下端の光点。目は青白く光る小さなガラスの形。
 */
private fun DrawScope.drawOrbBody(f: OrbFrame, style: AvatarStyle) {
    val c = f.c; val r = f.r
    val a = (0.7f + f.glow * 0.3f).coerceIn(0f, 1f)
    fun al(x: Float) = (x * a).coerceIn(0f, 1f)
    val circle = Path().apply { addOval(Rect(c, r)) }
    clipPath(circle) {
        // 本体: 上 (やや左) が最も暗く、縁へ向かって深い青に
        drawCircle(Brush.radialGradient(
            0.0f to Color(0xFF02050A), 0.45f to Color(0xFF040A14), 0.75f to Color(0xFF071426), 0.92f to Color(0xFF0D2541), 1.0f to Color(0xFF173B63),
            center = c + Offset(-r * 0.12f, -r * 0.30f), radius = r * 1.25f,
        ), r * 1.3f, c)
        // 球面の陰影: 右上 (光の側) をわずかに明るく、左下をわずかに暗く
        drawCircle(Brush.linearGradient(
            0.0f to Color(0xFF6FB6F2).copy(alpha = al(0.07f)), 0.45f to Color.Transparent, 1.0f to Color.Black.copy(alpha = 0.22f),
            start = c + Offset(r * 0.75f, -r * 0.75f), end = c + Offset(-r * 0.75f, r * 0.75f),
        ), r * 1.3f, c)
        // 下から内側に入る青い光
        drawCircle(Brush.radialGradient(
            0.0f to Color(0xFF2B86D9).copy(alpha = al(0.42f)), 0.5f to Color(0xFF1A5EA8).copy(alpha = al(0.18f)), 1.0f to Color.Transparent,
            center = Offset(c.x, c.y + r * 0.92f), radius = r * 1.05f,
        ), r * 1.3f, c)
        // 左上のごく淡い映り込み
        drawCircle(Brush.radialGradient(listOf(Color(0xFF8CC8FF).copy(alpha = al(0.05f)), Color.Transparent), center = c + Offset(-r * 0.55f, -r * 0.35f), radius = r * 0.6f), r * 1.3f, c)
        // ガラスの厚み (縁に近いほど明るい)
        drawCircle(Brush.radialGradient(
            0.0f to Color.Transparent, 0.80f to Color.Transparent, 0.93f to Color(0xFF5FB2F5).copy(alpha = al(0.20f)),
            0.985f to Color(0xFFBDE7FF).copy(alpha = al(0.55f)), 1.0f to Color(0xFFE2F5FF).copy(alpha = al(0.75f)),
            center = c, radius = r,
        ), r, c)
        // 明るい縁のすぐ内側の暗い帯 (ガラスの厚み・球面の丸み)
        drawCircle(Brush.radialGradient(
            0.0f to Color.Transparent, 0.84f to Color.Transparent, 0.905f to Color.Black.copy(alpha = 0.30f), 0.95f to Color.Transparent, 1.0f to Color.Transparent,
            center = c, radius = r,
        ), r, c)
        // 内側の殻の線 (ガラスが二重に見える)
        drawCircle(rimSweep(c, 0.32f * a), r * 0.925f, c + Offset(0f, r * 0.012f), style = Stroke(width = r * 0.007f))
        // 左側の淡い反射の筋
        drawArc(Color(0xFFBFE2FF).copy(alpha = al(0.05f)), 150f, 56f, false, c - Offset(r * 0.84f, r * 0.84f), Size(r * 1.68f, r * 1.68f), style = Stroke(width = r * 0.07f, cap = StrokeCap.Round))
        drawArc(Brush.sweepGradient(0.55f to Color.Transparent, 0.62f to Color.White.copy(alpha = al(0.16f)), 0.70f to Color.White.copy(alpha = al(0.10f)), 0.74f to Color.Transparent, center = c),
            205f, 50f, false, c - Offset(r * 0.8f, r * 0.8f), Size(r * 1.6f, r * 1.6f), style = Stroke(width = r * 0.10f, cap = StrokeCap.Round))
        // 右上の広い柔らかな反射 (考え中・操作中はゆっくり動く)
        val drift = sin(f.phase) * 8f
        val ha = Math.toRadians(-48.0 + drift).toFloat()
        val hp = c + Offset(cos(ha) * r * 0.74f, sin(ha) * r * 0.74f)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = al(0.13f)), Color(0xFFBFE6FF).copy(alpha = al(0.04f)), Color.Transparent), center = hp, radius = r * 0.36f), r * 1.3f, c)
        // 下の縁の内側に集まる光 (ガラスを通った光。線は描かない = 口に見せない)
        drawCircle(Brush.radialGradient(
            0.0f to Color.Transparent, 0.70f to Color.Transparent, 0.86f to CYAN.copy(alpha = al(0.22f)), 0.97f to Color(0xFF9BE7FF).copy(alpha = al(0.30f)), 1.0f to Color.Transparent,
            center = Offset(c.x, c.y - r * 0.18f), radius = r * 1.12f,
        ), r * 1.3f, c)
        // 下端の光点
        drawOval(Brush.radialGradient(listOf(Color.White.copy(alpha = al(0.95f)), Color(0xFF9BE7FF).copy(alpha = al(0.5f)), Color.Transparent), center = Offset(c.x, c.y + r * 0.905f), radius = r * 0.19f),
            topLeft = Offset(c.x - r * 0.19f, c.y + r * 0.867f), size = Size(r * 0.38f, r * 0.075f))
        // 右上の鋭い反射 (二本と小さな光)
        drawArc(Color.White.copy(alpha = al(0.9f)), -64f + drift * 0.3f, 28f, false, c - Offset(r * 0.905f, r * 0.905f), Size(r * 1.81f, r * 1.81f), style = Stroke(width = r * 0.022f, cap = StrokeCap.Round))
        drawArc(Color.White.copy(alpha = al(0.55f)), -46f + drift * 0.3f, 9f, false, c - Offset(r * 0.86f, r * 0.86f), Size(r * 1.72f, r * 1.72f), style = Stroke(width = r * 0.014f, cap = StrokeCap.Round))
        // ほほの光 (照れ・うれしい)
        if (f.blush > 0.01f) drawBlush(f, 0.55f)
    }
    // 外周の輪郭 (くっきり)。表情の色の縁を重ねる (うれしい・笑うは金色)
    drawCircle(rimSweep(c, a, f.warm), r, c, style = Stroke(width = r * 0.016f))
    if (f.tintAmt > 0.01f) drawCircle(lerp(f.tint.rim, Color.White, 0.25f).copy(alpha = (0.6f * f.tintAmt).coerceIn(0f, 1f)), r, c, style = Stroke(width = r * 0.018f))
    drawCircle(rimSweep(c, 0.5f * a, f.warm), r * 1.004f, c, style = Stroke(width = r * 0.006f))
    // 目: 青白く光る小さなガラス。中央から下が白に近く、外側・上側は淡い水色。右上に白いハイライト
    // まぶたを閉じる目は、上下のまぶたの間だけを描く。悲しい目は上まぶたで上を削る。笑う目 (＞＜) は普段の目を薄くして「く」の字を重ねる
    forEachEye(f) { ec, w, h, side ->
        val sq = f.eye.squeeze
        val close = f.eye.closeOf(side)
        val plain = 1f - sq
        drawCircle(Brush.radialGradient(listOf(RIM.copy(alpha = al(0.25f * (1f - 0.6f * close))), Color.Transparent), center = ec, radius = maxOf(w, h) * 2.2f), maxOf(w, h) * 2.2f, ec)
        eyeLid(ec, w, h, side, f.eye.droop) { lidClip(ec, w, h, close, f.eye.curveOf(side), f.eye.smileOf(side)) {
            drawOval(Brush.radialGradient(
                0.0f to Color.White, 0.45f to Color(0xFFE6F8FF), 0.85f to Color(0xFFA9DCFF), 1.0f to Color(0xFF7DC4F7),
                center = Offset(ec.x, ec.y + h * 0.3f), radius = maxOf(h, w) * 1.2f,
            ), topLeft = ec - Offset(w, h), size = Size(w * 2f, h * 2f), alpha = plain)
            drawOval(EYE_HI.copy(alpha = 0.8f), topLeft = ec - Offset(w, h), size = Size(w * 2f, h * 2f), alpha = plain, style = Stroke(width = r * 0.006f))
            if (f.eye.blink > 0.6f && f.eye.squint < 0.5f) {
                drawOval(Color.White.copy(alpha = 0.95f), topLeft = ec + Offset(w * 0.18f, -h * 0.68f), size = Size(w * 0.42f, h * 0.26f), alpha = plain)
                drawOval(Color.White.copy(alpha = 0.25f), topLeft = ec + Offset(-w * 0.55f, h * 0.35f), size = Size(w * 0.25f, h * 0.15f), alpha = plain)
            }
            }
            if (sq > 0.01f) drawPath(chevron(ec, w, side),
                Brush.linearGradient(listOf(Color.White, Color(0xFFE6F8FF), Color(0xFFA9DCFF)), start = Offset(ec.x, ec.y - w), end = Offset(ec.x, ec.y + w)),
                alpha = sq, style = Stroke(width = w * 0.42f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
