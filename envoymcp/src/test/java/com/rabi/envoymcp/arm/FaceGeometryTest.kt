package com.rabi.envoymcp.arm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * 顔の向きの (h, v): 腕を「旋回 += h、画面の正面の仰角 += v」(肩 = q2 − v) で 1 回動かすと、
 * 前面なら画面の正面が、背面 (カメラマン役) なら背面カメラが、顔の方を向くこと。
 * 腕のまね: 世界の上は +Y。画面の正面 N は、方位 ψ (Y まわり、右ねじ) と仰角 e で決まる。背面カメラは −N
 */
class FaceGeometryTest {
    private fun rad(d: Double) = Math.toRadians(d)
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun dirOf(azDeg: Double, elDeg: Double) =
        doubleArrayOf(cos(rad(elDeg)) * sin(rad(azDeg)), sin(rad(elDeg)), cos(rad(elDeg)) * cos(rad(azDeg)))
    private fun angle(a: DoubleArray, b: DoubleArray) = Math.toDegrees(acos(dot(a, b).coerceIn(-1.0, 1.0)))

    /** 姿勢 (ψ, e) のスマホの x・y・z (世界の座標)。z は画面の正面、y は上の辺の向き */
    private fun axes(psi: Double, e: Double): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val z = dirOf(psi, e)
        val y = doubleArrayOf(-sin(rad(e)) * sin(rad(psi)), cos(rad(e)), -sin(rad(e)) * cos(rad(psi)))
        return Triple(cross(y, z), y, z)
    }

    private fun toPhone(v: DoubleArray, ax: Triple<DoubleArray, DoubleArray, DoubleArray>) =
        doubleArrayOf(dot(v, ax.first), dot(v, ax.second), dot(v, ax.third))

    @Test fun frontAimsScreenAtFace() {
        for ((psi, e, fAz, fEl) in listOf(listOf(0.0, 40.0, 15.0, 30.0), listOf(-30.0, 60.0, -50.0, 45.0), listOf(20.0, 35.0, 25.0, 50.0))) {
            val ax = axes(psi, e)
            val face = dirOf(fAz, fEl)
            val (h, v) = FaceGeometry.errors(toPhone(doubleArrayOf(0.0, 1.0, 0.0), ax), toPhone(face, ax))!!
            assertTrue("前面 ψ=$psi e=$e", angle(dirOf(psi + h, e + v), face) < 0.5)
        }
    }

    @Test fun backAimsRearCameraAtFace() {
        // 背面カメラ (−N) の方位は ψ+180、仰角は −e。顔はスマホと同じ高さか少し下 (背面カメラは水平より 9° 上までしか向けない)
        for ((psi, e, fAz, fEl) in listOf(listOf(0.0, 20.0, 170.0, -5.0), listOf(30.0, 10.0, 230.0, 5.0), listOf(-40.0, 30.0, 120.0, -20.0))) {
            val ax = axes(psi, e)
            val face = dirOf(fAz, fEl)
            val (h, v) = FaceGeometry.errorsBack(toPhone(doubleArrayOf(0.0, 1.0, 0.0), ax), toPhone(face, ax))!!
            val rear = dirOf(psi + h, e + v).map { -it }.toDoubleArray()
            assertTrue("背面 ψ=$psi e=$e → 残り ${angle(rear, face)}°", angle(rear, face) < 0.5)
        }
    }

    @Test fun backCapHoldsAtTopAndTracksDown() {
        // 背面カメラの上の端が −33°。顔が 10° 上 (v = −10)
        assertEquals(-6.5, FaceGeometry.capBackV(-10.0, -40.0, -33.0), 1e-9)   // 端の手前まで上げる
        assertEquals(0.0, FaceGeometry.capBackV(-10.0, -33.0, -33.0), 1e-9)    // もう端: 上下は止める
        assertEquals(0.0, FaceGeometry.capBackV(-10.0, -32.0, -33.0), 1e-9)    // 端より上にいても、上へは送らない
        assertEquals(5.0, FaceGeometry.capBackV(5.0, -33.0, -33.0), 1e-9)      // 顔が下: そのまま下げる
        assertEquals(-3.0, FaceGeometry.capBackV(-3.0, -40.0, -33.0), 1e-9)    // 端まで届く: そのまま
        // 重力が真下 (スマホが平らで画面が上) なら、背面カメラは真下 (−90°)
        assertEquals(-90.0, FaceGeometry.rearElevation(doubleArrayOf(0.0, 0.0, 9.8)), 1e-9)
    }

    @Test fun backPixelsMatchDirection() {
        // 背面カメラの画像: スマホの +x の側は右、+y の側は上に写る
        val f = 1000.0
        val d = FaceGeometry.pixelToPhoneBack(540.0 + 200.0, 640.0 - 100.0, 1080, 1280, f)
        assertTrue(d[0] > 0 && d[1] > 0 && d[2] < 0)
        assertEquals(0.2, d[0] / -d[2], 1e-9)
        assertEquals(0.1, d[1] / -d[2], 1e-9)
        val c = FaceGeometry.pixelToPhoneBack(540.0, 640.0, 1080, 1280, f)
        assertEquals(-1.0, c[2], 1e-9)
    }
}
