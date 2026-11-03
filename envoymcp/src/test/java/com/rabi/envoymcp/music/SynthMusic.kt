package com.rabi.envoymcp.music

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * テスト用の模擬の曲 (キック・スネア・ハイハット・ベース・和音) と、端末の出力の波形 (Visualizer) のまね。
 * Visualizer は 1 秒に 20 回ほど、その時の 1024 個の音 (8 ビット) の周波数成分を 8 ビットで渡す。
 */
object SynthMusic {
    const val RATE = 44_100

    /** pattern: "four" (毎拍キック)、"pop" (1・3 拍目キック、2・4 拍目スネア)、"sync" (裏にもキック) */
    fun song(bpm: Double, seconds: Double, pattern: String = "pop", seed: Int = 1, quietFrom: Double = -1.0, quietTo: Double = -1.0): FloatArray {
        val rnd = Random(seed)
        val n = (seconds * RATE).toInt()
        val out = FloatArray(n)
        val period = 60.0 / bpm
        var prevNoise = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val b = t / period
            val beat = floor(b).toInt()
            val tb = (b - beat) * period                      // 拍からの時間
            val inBar = beat % 4
            val e8 = ((b * 2) - floor(b * 2)) * period / 2    // 8 分からの時間
            val noise = rnd.nextDouble() * 2 - 1
            val hp = noise - prevNoise; prevNoise = noise
            var x = 0.0
            val kickHere = when (pattern) {
                "four" -> true
                "sync" -> inBar == 0 || inBar == 2 || (inBar == 3 && tb > period / 2)
                else -> inBar == 0 || inBar == 2
            }
            val tk = if (pattern == "sync" && inBar == 3) tb - period / 2 else tb
            if (kickHere && tk >= 0) {
                val f = 50 + 70 * exp(-tk / 0.03)
                x += 0.7 * exp(-tk / 0.12) * sin(2 * PI * f * tk)
            }
            if (inBar == 1 || inBar == 3) x += 0.35 * exp(-tb / 0.08) * noise
            x += 0.10 * exp(-e8 / 0.03) * hp
            val bar = beat / 4
            x += 0.15 * sin(2 * PI * 55.0 * (1 + (bar % 4) * 0.25) * t)
            for (f in doubleArrayOf(220.0, 277.2, 329.6)) x += 0.05 * sin(2 * PI * f * t)
            x += 0.02 * noise
            val quiet = t in quietFrom..quietTo
            out[i] = (x * if (quiet) 0.25 else 1.0).toFloat()
        }
        return out
    }

    /** 時刻 tMs の Visualizer の波形 (getWaveForm の形、1024 個。8 ビットで 128 が 0) */
    fun waveAt(audio: FloatArray, tMs: Long, volume: Double = 0.6): ByteArray {
        val n = 1024
        val end = ((tMs / 1000.0) * RATE).toInt()
        return ByteArray(n) { k ->
            val idx = end - n + k
            val v = if (idx >= 0 && idx < audio.size) audio[idx] * volume else 0.0
            ((v * 127).roundToInt().coerceIn(-128, 127) + 128).toByte()
        }
    }

    /** 時刻 tMs の Visualizer の周波数成分 (getFft の形、1024 個) */
    fun fftAt(audio: FloatArray, tMs: Long, volume: Double = 0.6): ByteArray {
        val n = 1024
        val end = ((tMs / 1000.0) * RATE).toInt()
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (k in 0 until n) {
            val idx = end - n + k
            val v = if (idx >= 0 && idx < audio.size) audio[idx] * volume else 0.0      // 曲が終わったあとは無音
            re[k] = (v * 127).roundToInt().coerceIn(-128, 127).toDouble()
        }
        fft(re, im)
        val out = ByteArray(n)
        fun b(x: Double) = (x * 2 / n).roundToInt().coerceIn(-128, 127).toByte()
        out[0] = b(re[0]); out[1] = b(re[n / 2])
        for (k in 1 until n / 2) { out[2 * k] = b(re[k]); out[2 * k + 1] = b(im[k]) }
        return out
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cos(ang * k); val wi = sin(ang * k)
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                }
            }
            len = len shl 1
        }
    }
}
