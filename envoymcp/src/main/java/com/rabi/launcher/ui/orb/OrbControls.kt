package com.rabi.launcher.ui.orb

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 画面 (Compose) から 3D 描画のスレッドへ渡す値。毎フレーム上書きするだけなので、値ごとに volatile で足りる。
 * 長さは球の半径を 1 とした単位、y は上が正。
 */
class OrbControls {
    // ---- 置き場所と大きさ (体はばねでついてくるので、ここは滑らかに動かす)
    @Volatile var offsetY = 0f
    @Volatile var scale = 1f
    @Volatile var tiltDeg = 0f
    /** 球の向きの目標 (度)。左右 (右が正) と上下 (上が正)。描画側でばねを通して回す */
    @Volatile var yawDeg = 0f
    @Volatile var pitchDeg = 0f

    // ---- 光
    @Volatile var glow = 0.45f
    @Volatile var flash = 0f
    @Volatile var level = 0f
    @Volatile var warm = 0f
    @Volatile var phase = 0f

    // ---- 目 (視線は画面の向き: x は右、y は下が正)
    @Volatile var gazeX = 0f
    @Volatile var gazeY = 0f
    @Volatile var eyeOpen = 1f
    @Volatile var squint = 0f
    @Volatile var asym = 0f
    /** まばたきを始めた時刻 (System.nanoTime)。描画側で時間に合わせて目を細める */
    @Volatile var blinkAt = 0L
    // ---- 感情の表情 (一度だけ見せる。0 で普段の目)
    /**
     * まぶた (左右別): 閉じる度合い 0..1・閉じた線の反り (正で上に反る笑顔の ︵、0 で ー、負で下に反る穏やかな ︶)・
     * にっこり 0..1 (下まぶたが先に上がり、上の丸みは残る = 頬に押された笑い目)
     */
    @Volatile var eyeCloseL = 0f
    @Volatile var eyeCloseR = 0f
    @Volatile var eyeCurveL = 0f
    @Volatile var eyeCurveR = 0f
    @Volatile var eyeSmileL = 0f
    @Volatile var eyeSmileR = 0f
    /** 笑う目 (＞＜) の度合い 0..1 */
    @Volatile var eyeSqueeze = 0f
    /** 悲しい目 (外側が下がった上まぶた) の度合い 0..1 */
    @Volatile var eyeDroop = 0f
    /** 目の大きさの倍率 (驚きで大きく) */
    @Volatile var eyeScale = 1f
    /** ほほの光 (照れ・うれしい) 0..1 */
    @Volatile var blush = 0f
    /** つぶれ・伸び (正で横に広く縦に低く、負で縦に伸びる。跳ねる・笑うときの弾み) */
    @Volatile var squash = 0f
    /**
     * 表情の色の光 0..1 (縁とまわりの光を、その色に寄せる。うれしい・笑うは金色、驚きは白、悲しいは青、照れは桃色)。
     * tintR/G/B は縁の色、haloR/G/B はまわりの光の色、tintLift はまわりの光の明るさの増減 (負で暗く)
     */
    @Volatile var tint = 0f
    @Volatile var tintR = 1f; @Volatile var tintG = 0.86f; @Volatile var tintB = 0.60f
    @Volatile var haloR = 0.95f; @Volatile var haloG = 0.70f; @Volatile var haloB = 0.38f
    @Volatile var tintLift = 0.35f

    // ---- 動かし方
    /** 待機・承認待ち以外 (毎秒 60 回で描く)。false なら体が止まっていれば毎秒 30 回 */
    @Volatile var active = false
    @Volatile var reduceMotion = false
    /** 画面が見えているか (見えていない間は描かない) */
    @Volatile var visible = true

    // ---- 配置 (描画面の中の球の中心と半径、ピクセル)
    @Volatile var cx = 0f
    @Volatile var cy = 0f
    @Volatile var radiusPx = 1f

    /** 描画スレッドを起こす (見えるようになったとき) */
    @Volatile var wake: (() -> Unit)? = null

    enum class TouchKind { GRAB, MOVE, RELEASE, TAP }
    class Touch(val kind: TouchKind, val x: Float, val y: Float)

    /** 指の動き (描画面のピクセル座標) */
    val touches = ConcurrentLinkedQueue<Touch>()

    fun touch(kind: TouchKind, x: Float = 0f, y: Float = 0f) {
        if (touches.size < 64) touches.add(Touch(kind, x, y))
        wake?.invoke()
    }
}
