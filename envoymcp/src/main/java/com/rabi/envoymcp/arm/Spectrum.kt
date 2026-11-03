package com.rabi.envoymcp.arm

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * 画面のスペクトラムの帯の強さを、端末の出力の波形 (Visualizer の 8 ビットの波形。128 が 0) から自分で FFT して出す。
 * Visualizer の FFT (getFft) は値の丸めが粗く、弱い高い音 (ハイハットなど) は普通の音量でも 0 になる
 * (音量どおりに読むので)。波形から計算すると、丸めて捨てられる前の音が残る。拍の取り方 (OnsetDetector) は getFft を使う
 * - 帯の分け方は OnsetDetector と同じ (低い方から 48 本、43 Hz〜11 kHz ほど)。広い帯は平均と最大を半分ずつ
 * - 強さは ln(1 + |X| / SCALE)。無音 (全部 128) なら 0
 */
class WaveSpectrum(val bands: Int = 48, private val maxBin: Int = 256) {
    private var n = 0
    private var window = FloatArray(0)
    private var re = FloatArray(0)
    private var im = FloatArray(0)
    private var cosT = FloatArray(0)
    private var sinT = FloatArray(0)
    private var edgeTop = -1
    private var edges = IntArray(0)

    fun push(wave: ByteArray): FloatArray {
        val size = Integer.highestOneBit(wave.size)
        if (size < 8) return FloatArray(bands)
        if (size != n) setup(size)
        for (k in 0 until n) { re[k] = ((wave[k].toInt() and 0xFF) - 128) * window[k]; im[k] = 0f }
        fft()
        val top = min(n / 2, maxBin)
        if (top != edgeTop) { edgeTop = top; edges = logBandEdges(top, bands) }
        val out = FloatArray(bands)
        for (i in 0 until bands) {
            val a = min(edges[i], top - 1); val b = maxOf(min(edges[i + 1], top), a + 1)
            var sum = 0f; var mx = 0f
            for (k in a until b) { val m = ln(1f + hypot(re[k], im[k]) / SCALE); sum += m; if (m > mx) mx = m }
            out[i] = 0.5f * sum / (b - a) + 0.5f * mx
        }
        return out
    }

    private fun setup(size: Int) {
        n = size
        window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }   // ハン窓 (帯の間の漏れを減らす)
        re = FloatArray(n); im = FloatArray(n)
        cosT = FloatArray(n / 2) { cos(-2 * PI * it / n).toFloat() }; sinT = FloatArray(n / 2) { sin(-2 * PI * it / n).toFloat() }
    }

    private fun fft() {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cosT[k * step]; val wi = sinT[k * step]
                    val p = i + k; val q = p + len / 2
                    val vr = re[q] * wr - im[q] * wi; val vi = re[q] * wi + im[q] * wr
                    re[q] = re[p] - vr; im[q] = im[p] - vi
                    re[p] += vr; im[p] += vi
                }
            }
            len = len shl 1
        }
    }

    companion object {
        /** 大きい音 (全振幅) でおよそ 7〜8、8 ビットの丸めの雑音でおよそ 0.3 になる大きさ */
        private const val SCALE = 16f
    }
}

/**
 * 画面のスペクトラム (音楽の画面と、音声モードの全画面のリゲルのまわり) の帯の強さを 0〜1 にならす。
 * 帯ごとに、その帯のこの数秒の「下」と「上」(どちらもゆっくり追う) の間のどこにいるかで出す (音楽プレイヤーの表示と同じ考え)。
 * 全部の帯を 1 つの最大でならすと、最大はほぼ低い音で決まるので、高い音 (画面の下) がほとんど動かない
 * - 音のほとんど無い帯を大きく見せないよう、幅には下限 (全体の最大の MIN_RANGE 倍) を置く
 * - 帯ごとの音の強さの違いも少し残す (全体の最大でならした値を ABS だけ混ぜる)
 * 値は 1 秒に 20 回ほど (Visualizer の波形が届くたび) に入る前提の速さ。帯の値は WaveSpectrum (または OnsetDetector) の対数の強さ
 */
class SpectrumShaper(private val bands: Int = 48) {
    private val lo = FloatArray(bands)
    private val hi = FloatArray(bands)
    private var peak = 0f
    private var started = false

    fun reset() { started = false; peak = 0f }

    fun push(x: FloatArray): FloatArray {
        val n = minOf(bands, x.size)
        var top = 0f
        for (i in 0 until n) if (x[i] > top) top = x[i]
        peak = maxOf(top, peak * PEAK_DECAY, MIN_PEAK)
        if (!started) { for (i in 0 until n) { lo[i] = x[i]; hi[i] = x[i] }; started = true }
        return FloatArray(n) { i ->
            val v = x[i]
            lo[i] = if (v < lo[i]) v else lo[i] + (v - lo[i]) * LO_RISE
            hi[i] = if (v > hi[i]) v else hi[i] - (hi[i] - v) * HI_FALL
            val range = maxOf(hi[i] - lo[i], MIN_RANGE * peak)
            val rel = ((v - lo[i]) / range).coerceIn(0f, 1f)
            ((1 - ABS) * rel + ABS * v / peak).coerceIn(0f, 1f)
        }
    }

    companion object {
        /** 下は 2.5 秒ほど、上は 5 秒ほどでついてくる (1 回ごとの割合) */
        private const val LO_RISE = 0.02f
        private const val HI_FALL = 0.01f
        private const val MIN_RANGE = 0.25f
        private const val ABS = 0.2f
        private const val PEAK_DECAY = 0.997f
        private const val MIN_PEAK = 0.3f
    }
}
