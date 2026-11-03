package com.rabi.envoymcp.arm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

class BeatTrackerTest {
    /**
     * 端末の出力の波形と同じく、約 50 ms ごと (ゆらぎあり) に立ち上がりの強さが届く。
     * 拍 (beatMs から bpm で並ぶ) のあとの最初の 1 回が強く、裏拍が少し、ほかは雑音
     */
    private fun feed(t: BeatTracker, bpm: Double, beatMs: Long, fromMs: Long, toMs: Long, seed: Int = 1, offbeat: Double = 0.4) {
        val rnd = Random(seed)
        val period = 60_000.0 / bpm
        var now = fromMs
        var prev = fromMs
        while (now <= toMs) {
            fun crossed(shift: Double): Boolean {
                val a = floor((prev - beatMs - shift) / period); val b = floor((now - beatMs - shift) / period)
                return b > a
            }
            val onset = when {
                crossed(0.0) -> 1.0 + rnd.nextDouble() * 0.3
                crossed(period / 2) -> offbeat
                else -> rnd.nextDouble() * 0.15
            }
            // MusicListener と同じく、1 回の間の平均の遅れ (FRAME_DELAY_MS) を引いた時刻で渡す
            t.push(now - MusicListener.FRAME_DELAY_MS, onset, 0.5 + rnd.nextDouble() * 0.1)
            prev = now
            now += 45 + rnd.nextInt(11)
        }
    }

    @Test fun findsTempoAndPhase() {
        for ((i, bpm) in listOf(90.0, 100.0, 120.0, 128.0, 150.0, 172.0).withIndex()) {
            val t = BeatTracker()
            val beatMs = 1_234L
            feed(t, bpm, beatMs, 0, 12_000, seed = i)
            val b = t.estimate(12_000)
            assertTrue("$bpm: 見つからない", b != null)
            b!!
            // 速い曲は半分のテンポに取ることがある (踊りは半分の速さでも拍に乗るので、それでもよい)
            val want = if (bpm > 160 && abs(b.bpm - bpm / 2) < 2.5) bpm / 2 else bpm
            assertEquals("$bpm のテンポ", want, b.bpm, 2.5)
            // 拍の位置は、元の曲の拍の上にあるか (半分のテンポのときは、表と裏のどちらの拍に乗ってもよい)
            val onBeat = ((b.lastBeatMs - beatMs) / (60_000.0 / bpm)).let { abs(it - Math.round(it)) }
            assertTrue("$bpm の拍の位置: $onBeat", onBeat < 0.1)
            assertTrue("$bpm の確かさ ${b.confidence}", b.confidence >= MusicListener.MIN_CONFIDENCE)
        }
    }

    @Test fun silenceAndNoiseGiveNothingOrLowConfidence() {
        val t = BeatTracker()
        var now = 0L
        while (now < 10_000) { t.push(now, 0.0, 0.0); now += 50 }
        assertNull(t.estimate(10_000))
        val n = BeatTracker()
        val rnd = Random(7)
        now = 0L
        while (now < 10_000) { n.push(now, rnd.nextDouble(), 0.5); now += 45 + rnd.nextInt(11) }
        val b = n.estimate(10_000)
        assertTrue("雑音の確かさ ${b?.confidence}", b == null || b.confidence < MusicListener.MIN_CONFIDENCE)
    }

    @Test fun followsTempoChange() {
        val t = BeatTracker()
        feed(t, 100.0, 0, 0, 10_000)
        feed(t, 140.0, 10_100, 10_050, 20_000, seed = 3)
        assertEquals(140.0, t.estimate(20_000)!!.bpm, 2.5)
    }
}
