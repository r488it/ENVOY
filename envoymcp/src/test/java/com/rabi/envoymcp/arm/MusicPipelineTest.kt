package com.rabi.envoymcp.arm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 模擬の曲を、端末の出力の波形と同じ形 (1 秒に 20 回ほど、8 ビット) にして、拍の取り方を通しで確かめる。
 * 音量が小さくても「音が無い」と見誤らず、はっきりしない状態が続いても拍をやめないこと
 */
class MusicPipelineTest {
    private class Run(val lockedPct: Int, val losses: Int, val bpm: Double?)

    private fun run(bpm: Double, pattern: String, volume: Double, seconds: Double = 60.0, quiet: Pair<Double, Double>? = null): Run {
        val audio = SynthMusic.song(bpm, seconds, pattern, quietFrom = quiet?.first ?: -1.0, quietTo = quiet?.second ?: -1.0)
        val det = OnsetDetector(); val tr = BeatTracker(); val lock = BeatLock(acquire = MusicListener.MIN_CONFIDENCE)
        val rnd = Random(2)
        var t = 0L; var nextEval = 250L
        var locked = 0; var evals = 0; var losses = 0; var was = false
        while (t < seconds * 1000) {
            t += 47 + rnd.nextInt(7)
            val f = det.push(SynthMusic.fftAt(audio, t, volume))
            tr.push(t - MusicListener.FRAME_DELAY_MS, f.onset, f.level)
            if (t >= nextEval) {
                nextEval += 250
                val b = lock.update(tr.estimate(t), tr.energy(t), tr.audible(t), t)
                if (t > 8_000) { evals++; if (b != null) locked++ }
                if (was && b == null) losses++
                was = b != null
            }
        }
        return Run(locked * 100 / evals, losses, lock.current?.bpm)
    }

    @Test fun keepsBeatThroughSongsAndVolumes() {
        for ((bpm, pattern) in listOf(120.0 to "pop", 100.0 to "four", 128.0 to "sync", 90.0 to "pop")) {
            for (vol in listOf(0.6, 0.15, 0.05, 0.02)) {
                val r = run(bpm, pattern, vol)
                assertTrue("$bpm $pattern 音量 $vol: 取れていた割合 ${r.lockedPct}%", r.lockedPct >= 98)
                assertEquals("$bpm $pattern 音量 $vol: 途切れ", 0, r.losses)
                // 半分のテンポに取らない。ただし音量がごく小さいと、8 ビットで残るのがキックだけになり、
                // 1・3 拍目にキックがある曲は 2 拍おきの周期しか無い (半分の速さで踊る)
                val want = if (vol < 0.03 && pattern == "pop" && kotlin.math.abs(r.bpm!! - bpm / 2) < 2.5) bpm / 2 else bpm
                assertEquals("$bpm $pattern 音量 $vol: テンポ", want, r.bpm!!, 2.5)
            }
        }
    }

    @Test fun keepsBeatThroughQuietPart() {
        val r = run(120.0, "pop", 0.3, quiet = 20.0 to 32.0)            // 静かなところ (音が 1/4) が 12 秒
        assertTrue("取れていた割合 ${r.lockedPct}%", r.lockedPct >= 98)
        assertEquals(0, r.losses)
    }

    @Test fun stopsWhenMusicStops() {
        val audio = SynthMusic.song(120.0, 20.0, "pop")
        val det = OnsetDetector(); val tr = BeatTracker(); val lock = BeatLock()
        var t = 0L; var b: BeatTracker.Beat? = null
        while (t < 30_000) {                                            // 20 秒で曲が終わる (そのあとは無音)
            t += 50
            val f = det.push(SynthMusic.fftAt(audio, t, 0.5))
            tr.push(t - 25, f.onset, f.level)
            if (t % 250 == 0L) b = lock.update(tr.estimate(t), tr.energy(t), tr.audible(t), t)
            if (t == 19_000L) assertTrue(b != null)
        }
        assertTrue(b == null)
        assertEquals("音が鳴っていません", lock.lostWhy)
    }
}
