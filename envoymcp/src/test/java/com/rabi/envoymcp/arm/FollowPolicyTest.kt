package com.rabi.envoymcp.arm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowPolicyTest {
    private fun policy() = FollowPolicy(tolDeg = 10.0, dwellMs = 1_500, quietMs = 2_000, failQuietMs = 8_000, lostMs = 60_000).also { it.reset(0) }

    /** 0.2 秒ごとに同じ向きの顔を見せ、向け直すと言った時刻を返す (言わなければ null) */
    private fun FollowPolicy.feed(hv: Pair<Double, Double>?, from: Long, to: Long): Long? {
        var t = from
        while (t <= to) { if (sample(hv, t)) return t; t += 200 }
        return null
    }

    @Test fun smallDriftNeverReaims() {
        val p = policy()
        assertTrue(p.feed(6.0 to -4.0, 0, 30_000) == null)
    }

    @Test fun largeDriftReaimsOnlyAfterDwell() {
        val p = policy()
        val at = p.feed(15.0 to 0.0, 3_000, 10_000)
        assertTrue("向け直す: $at", at != null && at >= 3_000 + 1_500 && at <= 3_000 + 1_700)
    }

    @Test fun briefMovementIsIgnored() {
        val p = policy()
        assertTrue(p.feed(20.0 to 0.0, 3_000, 4_000) == null)   // 1 秒だけずれた (うなずきなど)
        assertTrue(p.feed(1.0 to 0.0, 4_200, 6_000) == null)
        assertTrue(p.feed(null, 6_200, 6_400) == null)          // 見えなくなると数え直す
        val at = p.feed(20.0 to 0.0, 6_600, 12_000)
        assertTrue("数え直してから向け直す: $at", at != null && at >= 6_600 + 1_500)
    }

    @Test fun quietAfterAim() {
        val p = policy()
        p.aimed(10_000, ok = false)                             // できなかった: 8 秒は向け直さない
        val at = p.feed(30.0 to 0.0, 10_000, 30_000)
        assertTrue("静かな間は向け直さない: $at", at != null && at >= 18_000)
    }

    @Test fun baselineAfterSuccessfulAim() {
        val p = policy()
        p.aimed(5_000, ok = true)
        assertFalse(p.sample(12.0 to 3.0, 5_200))              // 向け終わった直後の向きを基準にする (腕の好みの角度のぶん)
        assertTrue(p.feed(14.0 to 5.0, 5_400, 20_000) == null)  // 基準からは小さいずれ
        val at = p.feed(25.0 to 3.0, 20_200, 30_000)
        assertTrue("基準から大きくずれたら向け直す: $at", at != null)
    }

    @Test fun lostAfterLongAbsence() {
        val p = policy()
        p.sample(0.0 to 0.0, 1_000)
        assertFalse(p.lost(50_000))
        p.feed(null, 1_200, 70_000)
        assertTrue(p.lost(70_000))
    }
}
