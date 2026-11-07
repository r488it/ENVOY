package com.rabi.envoymcp.call

import com.rabi.envoymcp.call.ScamCheck.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 詐欺の電話の試し: 聞き取った言葉から、本物の電話と同じ見分けで結果を出す */
class ScamTrialTest {
    private fun lines(vararg peer: String) =
        listOf(CallLine("rigel", ScamTrial.GREETING, true, 0L)) + peer.map { CallLine("peer", it, true, 1L) }

    @Test fun exampleIsHigh() {
        // 画面に出す例を読めば、「高い」になる (リゲルの名乗りは数えない)
        val r = ScamCheck.check(lines(ScamTrial.EXAMPLE))
        assertEquals(Level.HIGH, r.level)
        assertEquals("詐欺の疑い: 高い（お金の話・大事な番号を聞く・警察などを名乗る・おどす）。", ScamTrial.summary(r, 1))
        assertEquals(ScamTrial.ADVICE, ScamTrial.closing(r, 1))
    }

    @Test fun ordinaryCallHasNoRisk() {
        val r = ScamCheck.check(lines("駅前クリニックの受付です。明日の午前十時のご予約の確認でお電話しました。"))
        assertEquals(Level.NONE, r.level)
        assertEquals(ScamTrial.NO_RISK, ScamTrial.summary(r, 1))
        assertEquals(ScamTrial.NO_RISK, ScamTrial.closing(r, 1))
    }

    @Test fun nothingHeard() {
        val r = ScamCheck.check(lines())
        assertTrue(ScamTrial.summary(r, 0).startsWith("相手の言葉を聞き取れませんでした"))
        assertEquals("", ScamTrial.closing(r, 0))
        // リゲルの名乗りの言葉だけでは、疑いは出ない
        assertEquals(Level.NONE, r.level)
    }
}
