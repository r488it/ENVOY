package com.rabi.envoymcp.arm

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 音の立ち上がりの強さ (onset) の並びから、テンポと拍の位置を見つける (Android に頼らない。テストできるように分けた)。
 * - 直近 windowMs の立ち上がりを 10 ms の格子に並べ、自己相関で 1 拍の長さを探す (60〜180 BPM。120 BPM 付近を少し優先する)
 * - 1 拍の長さで、拍の位置 (最後の拍の時刻) を合わせる
 * 端末の出力の波形 (Visualizer) は 1 秒に 20 回ほどしか届かないので、細かい時刻は自己相関と内挿で補う。
 */
class BeatTracker(private val windowMs: Long = 8_000, private val gridMs: Int = 10) {
    /** テンポ (bpm)、最後の拍の時刻 (ms)、確かさ (0〜1)、音の強さ (0〜1) */
    data class Beat(val bpm: Double, val lastBeatMs: Long, val confidence: Double, val energy: Double) {
        val periodMs get() = 60_000.0 / bpm
        /** 時刻 t の拍の位置 (0〜1。0 が拍) */
        fun phaseAt(t: Long): Double { val x = (t - lastBeatMs) / periodMs; return x - floor(x) }
    }

    private class Sample(val t: Long, val onset: Double, val level: Double)
    private val samples = ArrayDeque<Sample>()
    /** 1 秒の平均の音の大きさの、これまでの最大 (ゆっくり下げる) */
    private var peakMean = 1e-6
    private var peakAt = 0L

    /** 立ち上がりの強さ (0 以上) と、音の大きさ (0 以上。単位は何でもよい) を足す。時刻は増えていく ms */
    fun push(t: Long, onset: Double, level: Double) {
        samples.addLast(Sample(t, max(0.0, onset), max(0.0, level)))
        while (samples.isNotEmpty() && samples.first().t < t - windowMs) samples.removeFirst()
    }

    fun clear() { samples.clear(); peakMean = 1e-6; peakAt = 0L }

    /**
     * 今の音の強さ (0〜1。直近 1 秒の平均を、1 秒の平均のこれまでの最大で割る)。
     * 1 回ごとの値の最大で割ると、音量が小さいとき (8 ビットでほとんどの成分が 0 になり、キックの瞬間だけ大きい) に
     * 平均が最大のごく一部になり、音が無いと見誤る
     */
    fun energy(now: Long): Double {
        val recent = samples.filter { it.t >= now - 1_000 }
        if (recent.isEmpty()) return 0.0
        val mean = recent.sumOf { it.level } / recent.size
        if (peakAt > 0) peakMean *= Math.pow(0.5, (now - peakAt).coerceAtLeast(0) / PEAK_HALF_MS)   // 半分になるまで 60 秒
        peakAt = now
        peakMean = max(peakMean, mean)
        return (mean / peakMean).coerceIn(0.0, 1.0)
    }

    /** 音が鳴っているか (直近 1.5 秒に、少しでも音があったか。音量が小さくても鳴っていれば true) */
    fun audible(now: Long): Boolean = samples.any { it.t >= now - 1_500 && it.level > 0.0 }

    /** テンポと拍の位置。音が足りない・拍がはっきりしないときは null */
    fun estimate(now: Long): Beat? {
        if (samples.size < MIN_SAMPLES || now - samples.first().t < windowMs / 2) return null
        val n = (windowMs / gridMs).toInt()
        val start = now - windowMs
        val env = DoubleArray(n)
        // 立ち上がりを格子に並べる。届いた間は直線でつなぐ (点のまま置くと、届く間隔 (50 ms) そのものが周期に見える)
        var prev: Sample? = null
        for (s in samples) {
            val p = prev
            if (p != null && s.t > p.t) {
                val i0 = ((p.t - start).toDouble() / gridMs).roundToInt().coerceAtLeast(0)
                val i1 = ((s.t - start).toDouble() / gridMs).roundToInt().coerceAtMost(n - 1)
                for (k in i0..i1) {
                    val f = ((start + k * gridMs - p.t).toDouble() / (s.t - p.t)).coerceIn(0.0, 1.0)
                    env[k] = p.onset * (1 - f) + s.onset * f
                }
            }
            prev = s
        }
        val mean = env.average()
        for (i in env.indices) env[i] -= mean
        val r0 = env.sumOf { it * it } / n
        if (r0 <= 1e-12) return null

        val lagMin = (60_000.0 / (BPM_MAX * gridMs)).toInt()
        val lagMax = (60_000.0 / (BPM_MIN * gridMs)).toInt() + 1
        val r = DoubleArray(lagMax * 3 + 2)
        for (lag in 1 until r.size) {
            if (lag >= n) break
            var acc = 0.0
            for (i in 0 until n - lag) acc += env[i] * env[i + lag]
            r[lag] = acc / (n - lag)
        }
        // 1 拍の長さの候補: 自己相関 (2 拍ぶんの長さの重なりも足す) × テンポの好み (120 BPM の付近)
        var best = -1; var bestScore = Double.NEGATIVE_INFINITY
        val score = DoubleArray(lagMax + 2)
        for (lag in lagMin..lagMax) {
            val bpm = 60_000.0 / (lag * gridMs)
            val prior = exp(-0.5 * (ln(bpm / 120.0) / ln(2.0) / 0.9).let { it * it })
            score[lag] = (r[lag] + 0.5 * r[(2 * lag).coerceAtMost(r.size - 1)]) * prior
            if (score[lag] > bestScore) { bestScore = score[lag]; best = lag }
        }
        if (best < 0 || bestScore <= 0) return null
        // 確かさ: 1 拍・2 拍・3 拍ぶんずらしたときの重なりの平均 (雑音は 1 つの長さだけ偶然重なることがあるが、続けては重ならない)。
        // 倍のテンポにする前の長さで測る (2 拍おきの周期がはっきりしている曲は、それだけ拍もはっきりしている)
        val multiples = (1..3).map { best * it }.filter { it < r.size && it < n / 2 }
        val confidence = (multiples.sumOf { r[it] } / multiples.size / r0).coerceIn(0.0, 1.0)
        // 遅すぎるテンポ (1・3 拍目にキックがある曲は、2 拍ぶんの長さが強く出て半分のテンポに見える): 倍のテンポにも十分な重なりがあれば倍にする
        if (60_000.0 / (best * gridMs) < SLOW_BPM) {
            val half = (lagMin..lagMax).filter { abs(it - best / 2.0) <= 1.0 }.maxByOrNull { score[it] }
            if (half != null && score[half] >= HALF_OK * bestScore) best = half
        }
        // 細かい長さ: 山の両隣で放物線を当てる
        val lagF = if (best in (lagMin + 1) until lagMax) {
            val a = score[best - 1]; val b = score[best]; val c = score[best + 1]
            val den = a - 2 * b + c
            best + if (abs(den) > 1e-12) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
        } else best.toDouble()

        // 拍の位置: 1 拍の長さで並べた櫛を、新しいほど重く当てて、いちばん重なるずらし方を探す
        val steps = lagF.toInt().coerceAtLeast(1)
        var bestOff = 0.0; var bestSum = Double.NEGATIVE_INFINITY
        val sums = DoubleArray(steps)
        for (off in 0 until steps) {
            var acc = 0.0; var k = 0
            while (true) {
                val pos = n - 1 - off - k * lagF
                if (pos < 0) break
                acc += interp(env, pos) * exp(-k / 6.0)
                k++
            }
            sums[off] = acc
            if (acc > bestSum) { bestSum = acc; bestOff = off.toDouble() }
        }
        val o = bestOff.toInt()
        val a = sums[(o - 1 + steps) % steps]; val b = sums[o]; val c = sums[(o + 1) % steps]
        val den = a - 2 * b + c
        if (abs(den) > 1e-12) bestOff += (0.5 * (a - c) / den).coerceIn(-0.5, 0.5)
        val lastBeat = now - ((bestOff) * gridMs).roundToInt()
        return Beat(60_000.0 / (lagF * gridMs), lastBeat, confidence, energy(now))
    }

    private fun interp(a: DoubleArray, x: Double): Double {
        val i = floor(x).toInt()
        if (i < 0) return 0.0
        if (i >= a.size - 1) return a[a.size - 1]
        val f = x - i
        return a[i] * (1 - f) + a[i + 1] * f
    }

    companion object {
        const val BPM_MIN = 60.0
        const val BPM_MAX = 180.0
        private const val MIN_SAMPLES = 40
        private const val PEAK_HALF_MS = 60_000.0
        /** これより遅いテンポは、倍のテンポも確かめる */
        private const val SLOW_BPM = 80.0
        /** 倍のテンポの重なりが、これだけあれば倍にする */
        private const val HALF_OK = 0.35
    }
}

/**
 * 拍を「取れている」とみなし続けるかの判断 (Android に頼らない。テストできるように分けた)。
 * 実際の曲では、確かさが取り始めのしきい値の前後を上下する。下がるたびに拍を送るのをやめると、腕は「音楽が止まった」とみて
 * 踊るのをやめてしまう。そこで:
 * - 取り始めは確かさ acquire 以上・音の強さ minEnergy 以上。または、確かさ keep 以上で同じテンポが acquireMs 続けて出たとき
 *   (音量が小さいと確かさが低めになるが、テンポはそろう。雑音はテンポがばらつく)
 * - 取れたあとは、音が鳴っている間は取れているとする (はっきりしない間は、最後に取れた拍を時刻から延ばして使う)
 * - 更新: 確かさ acquire 以上なら、どのテンポでも取り直す。keep 以上なら、テンポがそろうとき (倍・半分を含む) だけ
 * - 違うテンポで、確かさ keep 以上の見積もりが switchMs 続けてそろったら、そちらに替える (曲が替わったなど)
 * - 音が鳴っていない (audible が false) ときだけ、取れていないとする
 */
class BeatLock(
    private val acquire: Double = 0.25,
    private val keep: Double = 0.12,
    private val minEnergy: Double = 0.15,
    private val switchMs: Long = 3_000,
    private val acquireMs: Long = 2_000,
) {
    var current: BeatTracker.Beat? = null
        private set
    /** 最後に取れなくなった理由 (記録に出す) */
    var lostWhy = ""
        private set
    /** 最後に見積もりで取り直した時刻 (はっきりしない状態がどれだけ続いているかを記録に出す) */
    var refreshedAt = 0L
        private set
    private var cand: BeatTracker.Beat? = null
    private var candSince = 0L

    fun reset() { current = null; refreshedAt = 0L; cand = null }

    /** raw: 今の見積もり (無ければ null)、energy: 音の強さ (0〜1)、audible: 音が鳴っているか。取れている拍 (無ければ null) を返す */
    fun update(raw: BeatTracker.Beat?, energy: Double, audible: Boolean, now: Long): BeatTracker.Beat? {
        if (!audible) {
            if (current != null) lostWhy = "音が鳴っていません"
            current = null; cand = null
            return null
        }
        val held = current
        val b = raw?.let { fold(held?.bpm, it) }
        when {
            b == null -> {}
            held == null -> when {
                energy < minEnergy -> {}
                b.confidence >= acquire -> take(b, now)
                b.confidence >= keep -> consider(b, now, acquireMs)
            }
            b.confidence >= acquire -> take(b, now)
            b.confidence >= keep && same(b.bpm, held.bpm) -> take(b, now)
            b.confidence >= keep -> consider(b, now, switchMs)   // 違うテンポ: しばらく同じテンポで続けば、そちらに替える
        }
        return current?.copy(energy = energy)
    }

    /** 同じテンポの見積もりが ms 続いたら取る */
    private fun consider(b: BeatTracker.Beat, now: Long, ms: Long) {
        val c = cand
        if (c == null || !same(b.bpm, c.bpm)) { cand = b; candSince = now }
        else if (now - candSince >= ms) take(b, now)
        else cand = b
    }

    private fun take(b: BeatTracker.Beat, now: Long) { current = b; refreshedAt = now; cand = null }

    private fun same(a: Double, b: Double) = abs(a - b) / b < SAME_TEMPO

    /**
     * 倍・半分に跳んだ見積もりを、今のテンポにそろえる。ただし今のテンポが遅い (SLOW_BPM 未満) ときの倍の見積もりはそろえない
     * (始めに半分のテンポで取っても、あとで正しいテンポが出たら倍の方へ乗り換えられるように)
     */
    private fun fold(prev: Double?, b: BeatTracker.Beat): BeatTracker.Beat {
        if (prev == null) return b
        for (k in listOf(2.0, 0.5)) {
            val want = b.bpm * k
            if (abs(want - prev) / prev < SAME_TEMPO && want in BeatTracker.BPM_MIN..BeatTracker.BPM_MAX) {
                return if (k == 0.5 && prev < SLOW_BPM) b else b.copy(bpm = want)
            }
        }
        return b
    }

    companion object {
        /** 同じテンポとみなす差 */
        private const val SAME_TEMPO = 0.06
        private const val SLOW_BPM = 80.0
    }
}

/**
 * 端末の出力の波形の周波数ごとの強さ (Visualizer の getFft と同じ形) から、音の立ち上がりの強さと、音の大きさ・音色を出す
 * (Android に頼らない。テストで模擬の音を通せるように分けた)。
 * fft[0] は 0 Hz、fft[1] は最高の周波数の実部。そのあと 1 番目から (実部, 虚部) が並ぶ (8 ビット)。
 */
class OnsetDetector(private val maxBin: Int = 256, private val lowBins: Int = 5, private val lowWeight: Double = 2.0, val bands: Int = 48) {
    /** bands: 周波数を低い方から bands 個に分けた強さ (音の大きさの対数。画面のスペクトラムに使う) */
    class Frame(val onset: Double, val level: Double, val centroid: Double, val bands: FloatArray)

    private var prev: FloatArray? = null

    fun reset() { prev = null }

    fun push(fft: ByteArray): Frame {
        val bins = fft.size / 2
        val top = min(bins, maxBin)
        val mag = FloatArray(top)
        for (k in 1 until top) mag[k] = ln(1f + hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()))
        val p = prev
        var flux = 0.0
        var level = 0.0
        var w = 0.0
        for (k in 1 until top) {
            level += mag[k]
            w += k * mag[k]
            if (p != null) {
                val d = mag[k] - p[k]
                if (d > 0) flux += d * if (k <= lowBins) lowWeight else 1.0
            }
        }
        prev = mag
        // 帯: 1 番目から top まで、低い方を細かく (対数の間隔) 分ける。広い帯 (高い音) は平均と最大を半分ずつ (ハイハットのような一瞬の音も出るように)
        val e = edgesFor(top)
        val out = FloatArray(bands)
        for (i in 0 until bands) {
            val a = min(e[i], top - 1); val b = max(min(e[i + 1], top), a + 1)
            var sum = 0f; var mx = 0f
            for (k in a until b) { sum += mag[k]; if (mag[k] > mx) mx = mag[k] }
            out[i] = 0.5f * sum / (b - a) + 0.5f * mx
        }
        return Frame(flux, level, if (level > 0) w / level / top else 0.0, out)
    }

    private var edgeTop = -1
    private var edges = IntArray(0)

    /**
     * 帯の境目 (bands + 1 個。1〜top の周波数の番号)。対数の間隔だが、低い方で同じ所を 2 つの帯が見ないよう、1 つずつは必ず進める
     * (そうしないと、いくつもの帯が同じ周波数の 1 か所を見て、そろって大きく跳ねる)
     */
    fun edgesFor(top: Int): IntArray {
        if (top != edgeTop) { edgeTop = top; edges = logBandEdges(top, bands) }
        return edges
    }
}

/** 帯の境目 (bands + 1 個。1〜top の周波数の番号)。対数の間隔で、1 つずつは必ず進める (OnsetDetector と WaveSpectrum で同じ分け方) */
fun logBandEdges(top: Int, bands: Int): IntArray {
    val edges = IntArray(bands + 1)
    var prev = 0
    for (i in 0..bands) {
        val e = max(Math.pow(top.toDouble(), i.toDouble() / bands).roundToInt(), prev + 1)
        edges[i] = min(e, top); prev = edges[i]
    }
    return edges
}
