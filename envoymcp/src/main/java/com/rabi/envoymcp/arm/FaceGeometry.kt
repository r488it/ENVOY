package com.rabi.envoymcp.arm

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 顔の向きを、AgentARM の定義の (h, v) にする (ボード側の agentarm_d/geometry.py の face_errors と同じ)。
 * - h: 顔の方位 − 画面の正面の方位 (度)。上から見て反時計回りが + (使う人から見て画面の右の側にいる顔は +)
 * - v: 顔の仰角 − 画面の正面の仰角 (度)。上が +
 * 腕はこれを受けて、旋回 = q1 + h、肩 = q2 − v を目標にする。
 *
 * スマホの座標: x 右・y 上・z 手前 (画面から使う人の方へ)。前面カメラは +z を見る。
 * 前面カメラの画像 (縦に起こしたもの。鏡には映していない) では、スマホの +x の側は画像の左に写る。
 * 背面カメラ (カメラマン役) は −z を見て、スマホの +x の側は画像の右に写る。
 * 背面カメラを顔へ向けるときも、腕は同じ (h, v) の約束で動く: 旋回は画面の正面と背面カメラを同じ向きに回すので h はそのまま、
 * 肩を + に曲げると画面の正面は下・背面カメラは上を向くので、v は背面カメラの仰角のずれの符号を反対にして送る (errorsBack)。
 */
object FaceGeometry {
    /**
     * 画像の中の点 (左上が 0、右・下が +) を、スマホの座標の向き (長さ 1) にする。
     * fPx: 焦点距離 (ピクセル)。画像の幅 w・高さ h は、縦に起こしたあとのもの
     */
    fun pixelToPhone(px: Double, py: Double, w: Int, h: Int, fPx: Double): DoubleArray =
        unit(doubleArrayOf(-(px - w / 2.0) / fPx, -(py - h / 2.0) / fPx, 1.0))

    /** 背面カメラの画像の中の点を、スマホの座標の向きにする (縦に起こした画像。−z を見る) */
    fun pixelToPhoneBack(px: Double, py: Double, w: Int, h: Int, fPx: Double): DoubleArray =
        unit(doubleArrayOf((px - w / 2.0) / fPx, -(py - h / 2.0) / fPx, -1.0))

    /**
     * up: スマホの座標の「上」。Android の重力センサー (TYPE_GRAVITY) の値そのままでよい (静かに置くと上を向く)。
     * dir: 顔の向き (スマホの座標)。戻り: (h, v) 度。画面がほぼ真上か真下を向いていて方位が決まらないときは null
     */
    fun errors(up: DoubleArray, dir: DoubleArray): Pair<Double, Double>? = errorsFrom(doubleArrayOf(0.0, 0.0, 1.0), up, dir)

    /**
     * 背面カメラを顔へ向けるときに、腕に送る (h, v)。h は背面カメラの向きからの方位のずれ (そのまま)、
     * v は背面カメラの仰角のずれの符号を反対にしたもの (腕は 肩 = q2 − v にするので、背面カメラが顔の方へ上下する)
     */
    fun errorsBack(up: DoubleArray, dir: DoubleArray): Pair<Double, Double>? =
        errorsFrom(doubleArrayOf(0.0, 0.0, -1.0), up, dir)?.let { (h, v) -> h to -v }

    /** 背面カメラの今の仰角 (度。上が +)。up は重力センサーの値 (上向き) */
    fun rearElevation(up: DoubleArray): Double = Math.toDegrees(asin((-unit(up)[2]).coerceIn(-1.0, 1.0)))

    /**
     * 背面カメラが上に届かないとき (腕の動く範囲の中で、届く所まで追う):
     * 腕に送る v (errorsBack の形) を、背面カメラの仰角が elMax − 0.5° を超えないところまでに抑える。
     * 上へ動かすときだけ抑え (もう端にいれば上下は止める)、下へはそのまま。こうすると腕は「届かない」で止まらず、左右だけ追い続ける
     */
    fun capBackV(v: Double, elNow: Double, elMax: Double): Double {
        val up = -v                              // 背面カメラを上へ向けたい角度
        if (up <= 0) return v
        return -minOf(up, maxOf(elMax - 0.5 - elNow, 0.0))
    }

    /** 向き n (スマホの座標) から見た、顔の方位と仰角のずれ */
    private fun errorsFrom(n: DoubleArray, up: DoubleArray, dir: DoubleArray): Pair<Double, Double>? {
        val u = unit(up)
        val d = unit(dir)
        val nU = dot(n, u)
        val dU = dot(d, u)
        val nh = sub(n, mul(u, nU))
        val dh = sub(d, mul(u, dU))
        if (len(nh) < 0.05 || len(dh) < 1e-6) return null
        val h = Math.toDegrees(atan2(dot(u, cross(nh, dh)), dot(nh, dh)))
        val v = Math.toDegrees(asin(dU.coerceIn(-1.0, 1.0))) - Math.toDegrees(asin(nU.coerceIn(-1.0, 1.0)))
        return h to v
    }

    /** AgentARM に送る重力は「本当の重力の向き」(下向き)。Android のセンサーの値 (上向き) の符号を反転する */
    fun gravityForArm(sensor: FloatArray): DoubleArray = doubleArrayOf(-sensor[0].toDouble(), -sensor[1].toDouble(), -sensor[2].toDouble())

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun mul(a: DoubleArray, k: Double) = doubleArrayOf(a[0] * k, a[1] * k, a[2] * k)
    private fun len(a: DoubleArray) = sqrt(dot(a, a))
    private fun unit(a: DoubleArray): DoubleArray { val l = len(a).takeIf { it > 1e-12 } ?: 1.0; return mul(a, 1.0 / l) }
}
