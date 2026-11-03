package com.rabi.envoymcp.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 画面のスペクトラム (リゲルのまわり)。模擬の曲を端末の出力の波形と同じ形 (1 秒に 20 回ほど、8 ビット) にして確かめる。
 * 上 (低い音) の帯だけでなく、下 (高い音) の帯も音楽に合わせて動くこと
 */
class SpectrumShaperTest {
    /** 帯ごとの揺れ (表示の値の 90% 点 − 10% 点)。最初の 4 秒 (ならし始め) は数えない */
    private fun swings(pattern: String, volume: Double, seconds: Double = 24.0): FloatArray {
        val audio = SynthMusic.song(124.0, seconds, pattern)
        val wave = WaveSpectrum(); val shaper = SpectrumShaper(wave.bands)
        val rnd = Random(3)
        val seen = Array(wave.bands) { ArrayList<Float>() }
        var t = 0L
        while (t < seconds * 1000) {
            t += 47 + rnd.nextInt(7)
            val v = shaper.push(wave.push(SynthMusic.waveAt(audio, t, volume)))
            if (t > 4_000) for (i in v.indices) seen[i].add(v[i])
        }
        return FloatArray(wave.bands) { i -> val s = seen[i].sorted(); s[(s.size * 0.9).toInt()] - s[(s.size * 0.1).toInt()] }
    }

    @Test fun highBandsMoveToo() {
        for ((pattern, volume) in listOf("pop" to 0.6, "four" to 0.6, "pop" to 0.3, "pop" to 0.15)) {
            val s = swings(pattern, volume)
            val q = s.size / 4
            val low = s.take(q).average(); val high = s.takeLast(q).average()
            println("swing $pattern vol=$volume: 低い帯 ${"%.2f".format(low)} 高い帯 ${"%.2f".format(high)}")
            assertTrue("低い帯 (画面の上) も揺れる: $pattern $volume low=$low", low >= 0.35)
            assertTrue("高い帯 (画面の下) も揺れる: $pattern $volume high=$high", high >= 0.3)
        }
    }

    @Test fun silenceIsFlat() {
        val wave = WaveSpectrum(); val shaper = SpectrumShaper(wave.bands)
        val audio = SynthMusic.song(124.0, 6.0, "pop")
        var t = 0L
        while (t < 6_000) { t += 50; shaper.push(wave.push(SynthMusic.waveAt(audio, t, 0.6))) }
        // 曲が終わって無音が続いたら、どの帯も 0 に戻る (小さな音を大きく見せない)
        var v = FloatArray(0)
        repeat(40) { t += 50; v = shaper.push(wave.push(SynthMusic.waveAt(audio, t, 0.6))) }
        assertEquals(0f, v.maxOrNull() ?: 0f, 0.05f)
    }

    @Test fun bandsDoNotShareBins() {
        // 帯の境目: 同じ所 (周波数) を 2 つの帯で見ない
        val det = OnsetDetector()
        val e = det.edgesFor(256)
        for (i in 1 until e.size) assertTrue("帯 $i の境目 ${e[i]} は前 ${e[i - 1]} より大きい", e[i] > e[i - 1])
        assertEquals(1, e.first()); assertEquals(256, e.last())
    }
}
