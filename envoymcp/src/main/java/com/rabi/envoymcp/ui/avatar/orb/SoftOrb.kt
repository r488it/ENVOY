package com.rabi.envoymcp.ui.avatar.orb

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.sqrt

/** 正二十面体を細かく分けた半径 1 の球。三角形は外から見て反時計回り */
class IcoSphere(val v: FloatArray, val tris: IntArray) {
    val count get() = v.size / 3

    companion object {
        fun create(level: Int): IcoSphere {
            val t = ((1.0 + sqrt(5.0)) / 2.0).toFloat()
            val vs = ArrayList<FloatArray>()
            fun add(x: Float, y: Float, z: Float): Int {
                val l = sqrt(x * x + y * y + z * z); vs.add(floatArrayOf(x / l, y / l, z / l)); return vs.size - 1
            }
            add(-1f, t, 0f); add(1f, t, 0f); add(-1f, -t, 0f); add(1f, -t, 0f)
            add(0f, -1f, t); add(0f, 1f, t); add(0f, -1f, -t); add(0f, 1f, -t)
            add(t, 0f, -1f); add(t, 0f, 1f); add(-t, 0f, -1f); add(-t, 0f, 1f)
            var faces: List<IntArray> = listOf(
                intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
                intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
                intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
                intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1),
            )
            repeat(level) {
                val cache = HashMap<Long, Int>()
                fun mid(a: Int, b: Int): Int {
                    val key = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
                    return cache.getOrPut(key) { val p = vs[a]; val q = vs[b]; add((p[0] + q[0]) / 2, (p[1] + q[1]) / 2, (p[2] + q[2]) / 2) }
                }
                faces = faces.flatMap { f ->
                    val a = mid(f[0], f[1]); val b = mid(f[1], f[2]); val c = mid(f[2], f[0])
                    listOf(intArrayOf(f[0], a, c), intArrayOf(f[1], b, a), intArrayOf(f[2], c, b), intArrayOf(a, b, c))
                }
            }
            val v = FloatArray(vs.size * 3).also { for (i in vs.indices) { it[i * 3] = vs[i][0]; it[i * 3 + 1] = vs[i][1]; it[i * 3 + 2] = vs[i][2] } }
            val tris = IntArray(faces.size * 3)
            for ((k, f) in faces.withIndex()) {
                // 外向きにそろえる (法線と位置の向きが同じ)
                val (a, b, c) = Triple(f[0], f[1], f[2])
                val ux = v[b * 3] - v[a * 3]; val uy = v[b * 3 + 1] - v[a * 3 + 1]; val uz = v[b * 3 + 2] - v[a * 3 + 2]
                val wx = v[c * 3] - v[a * 3]; val wy = v[c * 3 + 1] - v[a * 3 + 1]; val wz = v[c * 3 + 2] - v[a * 3 + 2]
                val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
                val out = nx * v[a * 3] + ny * v[a * 3 + 1] + nz * v[a * 3 + 2] > 0
                tris[k * 3] = a; tris[k * 3 + 1] = if (out) b else c; tris[k * 3 + 2] = if (out) c else b
            }
            return IcoSphere(v, tris)
        }
    }
}

/**
 * 柔らかい球 (ゼリーのような体)。中身まで四面体で埋めた体積のあるメッシュを XPBD で動かす。
 * - 粒子: 表面 (半径 1) / 内側の殻 (半径 0.5) / 中心。表面と内側の殻の間は三角柱を 3 つの四面体に、内側は中心との四面体に分ける
 * - 制約: 辺の長さ (表面の辺は少し硬い = 皮)、四面体の体積 (伸ばしても体積を保ち、裏返りを防ぐ)
 * - 芯 (内側の殻と中心) だけを、ゆるいばねで「置き場所」につなぐ。表面は自由なので、触ると局所的にへこみ・伸び、離すと揺れてから収まる
 * - 状態の動き (浮遊・うなずき・呼吸) は置き場所と大きさを動かすだけ。体は遅れてついてくるので、自然な揺れになる
 * - 時間は固定の刻み (step の dt) を細かく分けて進める。速さ・引っぱる距離に上限。値がおかしくなったら元の形に戻す
 * 座標は球の半径を 1 とした単位 (y が上、z が手前)。
 */
class SoftOrb(level: Int = 2, private val innerRadius: Float = 0.5f) {
    val shell = IcoSphere.create(level)
    val nOuter = shell.count
    val n = nOuter * 2 + 1
    private val center = nOuter * 2

    /** 元の形 (大きさ 1・置き場所 0) */
    val rest = FloatArray(n * 3)
    val x = FloatArray(n * 3)
    private val prev = FloatArray(n * 3)
    private val vel = FloatArray(n * 3)
    private val w = FloatArray(n) { 1f }

    val tets: IntArray
    private val restVol: FloatArray
    private val edges: IntArray
    private val restLen: FloatArray
    private val edgeSkin: BooleanArray

    // ---- 調整できる値 (単位: 半径 1・質量 1・秒)
    // 離してから約 1.2 秒で収まり、その間に 2〜3 回揺れる値
    var fleshCompliance = 2.5e-3f
    var skinCompliance = 6e-4f
    var volumeCompliance = 1.2e-2f
    var coreCompliance = 2e-6f
    var damping = 2.4f
    var maxSpeed = 6f
    var maxPull = 0.55f
    /** つかんだ所のばねの柔らかさ (つかんだ中心で。まわりは弱く) */
    var grabCompliance = 4e-7f

    // ---- 外から動かす値
    var anchorX = 0f; var anchorY = 0f; var anchorZ = 0f
    var scale = 1f

    // ---- つかんでいる所
    private var grabIdx = IntArray(0)
    private var grabWt = FloatArray(0)
    private var grabStart = FloatArray(0)
    private var grabAnchor = FloatArray(3)
    private var pullX = 0f; private var pullY = 0f; private var pullZ = 0f
    val grabbing get() = grabIdx.isNotEmpty()

    init {
        for (i in 0 until nOuter) {
            for (k in 0..2) {
                rest[i * 3 + k] = shell.v[i * 3 + k]
                rest[(i + nOuter) * 3 + k] = shell.v[i * 3 + k] * innerRadius
            }
        }
        val tl = ArrayList<IntArray>()
        val tr = shell.tris
        for (f in 0 until tr.size / 3) {
            val s = intArrayOf(tr[f * 3], tr[f * 3 + 1], tr[f * 3 + 2]).also { it.sort() }
            val b0 = s[0] + nOuter; val b1 = s[1] + nOuter; val b2 = s[2] + nOuter
            val t0 = s[0]; val t1 = s[1]; val t2 = s[2]
            // 三角柱の分け方を全体でそろえる (番号の小さい内側から大きい表面へ対角線) と、隣どうしの面が一致する
            tl.add(intArrayOf(b0, b1, b2, t2)); tl.add(intArrayOf(b0, b1, t1, t2)); tl.add(intArrayOf(b0, t0, t1, t2))
            tl.add(intArrayOf(b0, b1, b2, center))
        }
        tets = IntArray(tl.size * 4)
        restVol = FloatArray(tl.size)
        for ((k, t) in tl.withIndex()) {
            var v = signedVolume(rest, t[0], t[1], t[2], t[3])
            if (v < 0) { val tmp = t[1]; t[1] = t[2]; t[2] = tmp; v = -v }
            for (j in 0..3) tets[k * 4 + j] = t[j]
            restVol[k] = v
        }
        val es = LinkedHashSet<Long>()
        for (k in 0 until tl.size) for (a in 0..3) for (b in a + 1..3) {
            val i = tets[k * 4 + a]; val j = tets[k * 4 + b]
            es.add(if (i < j) (i.toLong() shl 32) or j.toLong() else (j.toLong() shl 32) or i.toLong())
        }
        edges = IntArray(es.size * 2); restLen = FloatArray(es.size); edgeSkin = BooleanArray(es.size)
        for ((k, key) in es.withIndex()) {
            val i = (key shr 32).toInt(); val j = (key and 0xffffffffL).toInt()
            edges[k * 2] = i; edges[k * 2 + 1] = j
            restLen[k] = dist(rest, i, j)
            edgeSkin[k] = i < nOuter && j < nOuter
        }
        reset()
    }

    private fun remember() { lastAx = anchorX; lastAy = anchorY; lastAz = anchorZ; lastScale = scale }

    fun reset() {
        for (i in 0 until n) for (k in 0..2) {
            val a = when (k) { 0 -> anchorX; 1 -> anchorY; else -> anchorZ }
            x[i * 3 + k] = a + rest[i * 3 + k] * scale; prev[i * 3 + k] = x[i * 3 + k]; vel[i * 3 + k] = 0f
        }
        grabIdx = IntArray(0)
    }

    // ---- 休み (揺れが収まり、触られていない間は計算しない: 元の形のまま置き場所と大きさに合わせて動かすだけ)
    /** 休んでいるか (粒子は元の形のまま) */
    var asleep = false; private set
    private var lastAx = 0f; private var lastAy = 0f; private var lastAz = 0f; private var lastScale = 1f

    /** 揺れが十分に収まっていれば休む。休めたら true */
    fun trySleep(): Boolean {
        if (asleep) return true
        if (grabbing || maxSpeedNow() > 0.03f || maxDeviation() > 0.003f) return false
        asleep = true
        rigid()
        return true
    }

    private fun rigid() {
        for (i in 0 until n) {
            val o = i * 3
            x[o] = anchorX + rest[o] * scale; x[o + 1] = anchorY + rest[o + 1] * scale; x[o + 2] = anchorZ + rest[o + 2] * scale
            vel[o] = 0f; vel[o + 1] = 0f; vel[o + 2] = 0f
        }
    }

    /** dt 秒だけ進める (substeps に分ける)。呼ぶ側は dt を固定にすること */
    fun step(dt: Float, substeps: Int = 8) {
        if (asleep) {
            // 置き場所・大きさが速く変わったら (うなずき・声の拍) 目を覚まして揺れを出す。ゆっくりなら (浮遊・呼吸) 元の形のまま動かす
            val dv = sqrt((anchorX - lastAx) * (anchorX - lastAx) + (anchorY - lastAy) * (anchorY - lastAy) + (anchorZ - lastAz) * (anchorZ - lastAz)) / dt
            val ds = abs(scale - lastScale) / dt
            remember()
            if (dv < 0.08f && ds < 0.05f) { rigid(); return }
            asleep = false
        }
        remember()
        val h = dt / substeps
        val damp = exp(-damping * h)
        for (s in 0 until substeps) {
            for (i in 0 until n) {
                val o = i * 3
                for (k in 0..2) {
                    prev[o + k] = x[o + k]
                    vel[o + k] *= damp
                    x[o + k] += vel[o + k] * h
                }
            }
            solveEdges(h)
            solveVolumes(h)
            solveTotalVolume()
            solveCore(h)
            solveGrab(h)
            val inv = 1f / h
            for (i in 0 until n) {
                val o = i * 3
                var vx = (x[o] - prev[o]) * inv; var vy = (x[o + 1] - prev[o + 1]) * inv; var vz = (x[o + 2] - prev[o + 2]) * inv
                val sp = sqrt(vx * vx + vy * vy + vz * vz)
                if (sp > maxSpeed) { val f = maxSpeed / sp; vx *= f; vy *= f; vz *= f }
                vel[o] = vx; vel[o + 1] = vy; vel[o + 2] = vz
            }
        }
        if (!x.all { it.isFinite() }) reset()
    }

    private fun solveEdges(h: Float) {
        val ih2 = 1f / (h * h)
        for (e in restLen.indices) {
            val i = edges[e * 2]; val j = edges[e * 2 + 1]
            val dx = x[i * 3] - x[j * 3]; val dy = x[i * 3 + 1] - x[j * 3 + 1]; val dz = x[i * 3 + 2] - x[j * 3 + 2]
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            if (len < 1e-7f) continue
            val c = len - restLen[e] * scale
            val alpha = (if (edgeSkin[e]) skinCompliance else fleshCompliance) * ih2
            val dl = -c / (w[i] + w[j] + alpha) / len
            x[i * 3] += w[i] * dl * dx; x[i * 3 + 1] += w[i] * dl * dy; x[i * 3 + 2] += w[i] * dl * dz
            x[j * 3] -= w[j] * dl * dx; x[j * 3 + 1] -= w[j] * dl * dy; x[j * 3 + 2] -= w[j] * dl * dz
        }
    }

    private val gx = FloatArray(4); private val gy = FloatArray(4); private val gz = FloatArray(4)
    private fun solveVolumes(h: Float) {
        val alpha = volumeCompliance / (h * h)
        val s3 = scale * scale * scale
        for (t in restVol.indices) {
            val id = t * 4
            var wsum = 0f
            for (j in 0..3) {
                // 体積の勾配: 向かい合う面の法線 / 6
                val a = tets[id + FACE[j * 3]]; val b = tets[id + FACE[j * 3 + 1]]; val c = tets[id + FACE[j * 3 + 2]]
                val ux = x[b * 3] - x[a * 3]; val uy = x[b * 3 + 1] - x[a * 3 + 1]; val uz = x[b * 3 + 2] - x[a * 3 + 2]
                val vx = x[c * 3] - x[a * 3]; val vy = x[c * 3 + 1] - x[a * 3 + 1]; val vz = x[c * 3 + 2] - x[a * 3 + 2]
                gx[j] = (uy * vz - uz * vy) / 6f; gy[j] = (uz * vx - ux * vz) / 6f; gz[j] = (ux * vy - uy * vx) / 6f
                wsum += w[tets[id + j]] * (gx[j] * gx[j] + gy[j] * gy[j] + gz[j] * gz[j])
            }
            if (wsum < 1e-12f) continue
            val vol = signedVolume(x, tets[id], tets[id + 1], tets[id + 2], tets[id + 3])
            val c = vol - restVol[t] * s3
            val dl = -c / (wsum + alpha)
            for (j in 0..3) {
                val p = tets[id + j]
                x[p * 3] += dl * w[p] * gx[j]; x[p * 3 + 1] += dl * w[p] * gy[j]; x[p * 3 + 2] += dl * w[p] * gz[j]
            }
        }
    }

    private val tg by lazy { FloatArray(n * 3) }
    private val totalRest by lazy { restVol.sum() }

    /**
     * 体全体の体積を保つ (固い制約)。四面体ごとの体積は少し緩めてある (粗い四面体で一つずつ固くすると、体全体が固まってしまう = 体積ロック)。
     * その代わり、全体ではほぼ変わらないようにする
     */
    private fun solveTotalVolume() {
        // 体全体の体積は、表面の三角形だけで求まる (中の粒子は全体の体積を変えない): V = Σ a·(b×c)/6
        java.util.Arrays.fill(tg, 0, nOuter * 3, 0f)
        var vol = 0f
        val t = shell.tris
        for (f in 0 until t.size / 3) {
            val a = t[f * 3] * 3; val b = t[f * 3 + 1] * 3; val c = t[f * 3 + 2] * 3
            val ax = x[a]; val ay = x[a + 1]; val az = x[a + 2]
            val bx = x[b]; val by = x[b + 1]; val bz = x[b + 2]
            val cx = x[c]; val cy = x[c + 1]; val cz = x[c + 2]
            val bcx = by * cz - bz * cy; val bcy = bz * cx - bx * cz; val bcz = bx * cy - by * cx
            vol += ax * bcx + ay * bcy + az * bcz
            tg[a] += bcx; tg[a + 1] += bcy; tg[a + 2] += bcz
            tg[b] += cy * az - cz * ay; tg[b + 1] += cz * ax - cx * az; tg[b + 2] += cx * ay - cy * ax
            tg[c] += ay * bz - az * by; tg[c + 1] += az * bx - ax * bz; tg[c + 2] += ax * by - ay * bx
        }
        vol /= 6f
        var wsum = 0f
        for (i in 0 until nOuter * 3) { tg[i] /= 6f; wsum += w[i / 3] * tg[i] * tg[i] }
        if (wsum < 1e-12f) return
        val dl = -(vol - totalRest * scale * scale * scale) / wsum
        for (i in 0 until nOuter * 3) x[i] += dl * w[i / 3] * tg[i]
    }

    /** 芯 (内側の殻と中心) を置き場所へゆるく引き戻す */
    private fun solveCore(h: Float) {
        val alpha = coreCompliance / (h * h)
        for (i in nOuter until n) {
            val o = i * 3
            val f = w[i] / (w[i] + alpha)
            x[o] += (anchorX + rest[o] * scale - x[o]) * f
            x[o + 1] += (anchorY + rest[o + 1] * scale - x[o + 1]) * f
            x[o + 2] += (anchorZ + rest[o + 2] * scale - x[o + 2]) * f
        }
    }

    private fun solveGrab(h: Float) {
        if (grabIdx.isEmpty()) return
        val ih2 = 1f / (h * h)
        val ax = anchorX - grabAnchor[0]; val ay = anchorY - grabAnchor[1]; val az = anchorZ - grabAnchor[2]
        for (k in grabIdx.indices) {
            val i = grabIdx[k]; val wt = grabWt[k]
            val alpha = grabCompliance / wt * ih2
            val f = w[i] / (w[i] + alpha)
            x[i * 3] += (grabStart[k * 3] + ax + pullX * wt - x[i * 3]) * f
            x[i * 3 + 1] += (grabStart[k * 3 + 1] + ay + pullY * wt - x[i * 3 + 1]) * f
            x[i * 3 + 2] += (grabStart[k * 3 + 2] + az + pullZ * wt - x[i * 3 + 2]) * f
        }
    }

    /** 表面の (hx, hy, hz) の付近をつかむ。近いほど強く、指の動きについてくる */
    fun grab(hx: Float, hy: Float, hz: Float, radius: Float = 0.5f) {
        val idx = ArrayList<Int>(); val wt = ArrayList<Float>()
        for (i in 0 until nOuter) {
            val dx = x[i * 3] - hx; val dy = x[i * 3 + 1] - hy; val dz = x[i * 3 + 2] - hz
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 > radius * radius) continue
            // 中心の近くはひとまとまりで動かし (先が尖らない)、外へなめらかに弱める
            val u = ((sqrt(d2) - radius * 0.3f) / (radius * 0.7f)).coerceIn(0f, 1f)
            val k = 1f - u * u * (3f - 2f * u)
            if (k > 0.04f) { idx.add(i); wt.add(k) }
        }
        asleep = false
        grabIdx = idx.toIntArray(); grabWt = wt.toFloatArray()
        grabStart = FloatArray(grabIdx.size * 3).also { for ((k, i) in grabIdx.withIndex()) for (c in 0..2) it[k * 3 + c] = x[i * 3 + c] }
        grabAnchor[0] = anchorX; grabAnchor[1] = anchorY; grabAnchor[2] = anchorZ
        pullX = 0f; pullY = 0f; pullZ = 0f
    }

    /** つかんだ所を (dx, dy, dz) だけ引く (上限 maxPull) */
    fun pull(dx: Float, dy: Float, dz: Float) {
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        val f = if (l > maxPull) maxPull / l else 1f
        pullX = dx * f; pullY = dy * f; pullZ = dz * f
    }

    fun release() { grabIdx = IntArray(0) }

    /** 表面の (hx, hy, hz) の付近を、向き (nx, ny, nz) へ速さ speed で軽く押す (タップの手ごたえ) */
    fun poke(hx: Float, hy: Float, hz: Float, nx: Float, ny: Float, nz: Float, speed: Float, radius: Float = 0.45f) {
        asleep = false
        val sig = radius * 0.55f
        for (i in 0 until nOuter) {
            val dx = x[i * 3] - hx; val dy = x[i * 3 + 1] - hy; val dz = x[i * 3 + 2] - hz
            val k = exp(-(dx * dx + dy * dy + dz * dz) / (sig * sig))
            if (k < 0.02f) continue
            vel[i * 3] += nx * speed * k; vel[i * 3 + 1] += ny * speed * k; vel[i * 3 + 2] += nz * speed * k
        }
    }

    /** 体全体の中心 (粒子の平均) */
    fun centroid(out: FloatArray) {
        var sx = 0f; var sy = 0f; var sz = 0f
        for (i in 0 until n) { sx += x[i * 3]; sy += x[i * 3 + 1]; sz += x[i * 3 + 2] }
        out[0] = sx / n; out[1] = sy / n; out[2] = sz / n
    }

    /** 動きの大きさ (粒子の速さの最大) */
    fun maxSpeedNow(): Float {
        var m = 0f
        for (i in 0 until n) { val o = i * 3; val s = vel[o] * vel[o] + vel[o + 1] * vel[o + 1] + vel[o + 2] * vel[o + 2]; if (s > m) m = s }
        return sqrt(m)
    }

    /** 元の形からのずれの最大 (置き場所と大きさを除く) */
    fun maxDeviation(): Float {
        var m = 0f
        for (i in 0 until n) {
            val dx = x[i * 3] - (anchorX + rest[i * 3] * scale)
            val dy = x[i * 3 + 1] - (anchorY + rest[i * 3 + 1] * scale)
            val dz = x[i * 3 + 2] - (anchorZ + rest[i * 3 + 2] * scale)
            val d = dx * dx + dy * dy + dz * dz
            if (d > m) m = d
        }
        return sqrt(m)
    }

    // ---- 表面の点を体の変形に乗せる (描画用のなめらかな球・目)

    /** 表面の近くの点が、どの表面粒子にどれだけ乗るか (近い k 個・重みの合計 1) */
    class Embedding(val k: Int, val idx: IntArray, val wt: FloatArray)

    /** 単位ベクトルの並び dirs (xyz) を、表面粒子に埋め込む。sigma は角度 (ラジアン) */
    fun embed(dirs: FloatArray, k: Int = 8, sigma: Float = 0.30f): Embedding {
        val m = dirs.size / 3
        val idx = IntArray(m * k); val wt = FloatArray(m * k)
        val dots = FloatArray(nOuter)
        for (p in 0 until m) {
            embedOne(dirs[p * 3], dirs[p * 3 + 1], dirs[p * 3 + 2], k, sigma, dots, idx, wt, p * k)
        }
        return Embedding(k, idx, wt)
    }

    private fun embedOne(px: Float, py: Float, pz: Float, k: Int, sigma: Float, dots: FloatArray, idx: IntArray, wt: FloatArray, off: Int) {
        val l = sqrt(px * px + py * py + pz * pz).coerceAtLeast(1e-6f)
        for (i in 0 until nOuter) dots[i] = (rest[i * 3] * px + rest[i * 3 + 1] * py + rest[i * 3 + 2] * pz) / l
        var sum = 0f
        for (j in 0 until k) {
            var best = -1; var bv = -2f
            for (i in 0 until nOuter) if (dots[i] > bv) { bv = dots[i]; best = i }
            dots[best] = -3f
            val a = acos(bv.coerceIn(-1f, 1f))
            val wv = exp(-(a / sigma) * (a / sigma))
            idx[off + j] = best; wt[off + j] = wv; sum += wv
        }
        for (j in 0 until k) wt[off + j] /= sum
    }

    /** 埋め込んだ点 (元の位置 pts) の今の位置を out に書く: 置き場所 + 大きさ × 元の位置 + まわりの粒子のずれの重み付き平均 */
    fun deform(e: Embedding, pts: FloatArray, out: FloatArray) {
        val m = pts.size / 3
        for (p in 0 until m) {
            var dx = 0f; var dy = 0f; var dz = 0f
            for (j in 0 until e.k) {
                val i = e.idx[p * e.k + j]; val wv = e.wt[p * e.k + j]
                dx += wv * (x[i * 3] - rest[i * 3] * scale)
                dy += wv * (x[i * 3 + 1] - rest[i * 3 + 1] * scale)
                dz += wv * (x[i * 3 + 2] - rest[i * 3 + 2] * scale)
            }
            out[p * 3] = pts[p * 3] * scale + dx; out[p * 3 + 1] = pts[p * 3 + 1] * scale + dy; out[p * 3 + 2] = pts[p * 3 + 2] * scale + dz
        }
    }

    private val tmpDots by lazy { FloatArray(nOuter) }
    private val tmpIdx = IntArray(8); private val tmpWt = FloatArray(8)

    /** 1 点だけ (目の位置など、毎回場所が変わるもの) を変形に乗せる */
    fun deformPoint(px: Float, py: Float, pz: Float, out: FloatArray, o: Int = 0) {
        embedOne(px, py, pz, 8, 0.30f, tmpDots, tmpIdx, tmpWt, 0)
        var dx = 0f; var dy = 0f; var dz = 0f
        for (j in 0 until 8) {
            val i = tmpIdx[j]; val wv = tmpWt[j]
            dx += wv * (x[i * 3] - rest[i * 3] * scale)
            dy += wv * (x[i * 3 + 1] - rest[i * 3 + 1] * scale)
            dz += wv * (x[i * 3 + 2] - rest[i * 3 + 2] * scale)
        }
        out[o] = px * scale + dx; out[o + 1] = py * scale + dy; out[o + 2] = pz * scale + dz
    }

    companion object {
        /** 四面体の j 番目の頂点に向かい合う面 (外向きの法線になる順) */
        private val FACE = intArrayOf(1, 3, 2, 0, 2, 3, 0, 3, 1, 0, 1, 2)

        private fun dist(a: FloatArray, i: Int, j: Int): Float {
            val dx = a[i * 3] - a[j * 3]; val dy = a[i * 3 + 1] - a[j * 3 + 1]; val dz = a[i * 3 + 2] - a[j * 3 + 2]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        fun signedVolume(a: FloatArray, i0: Int, i1: Int, i2: Int, i3: Int): Float {
            val ux = a[i1 * 3] - a[i0 * 3]; val uy = a[i1 * 3 + 1] - a[i0 * 3 + 1]; val uz = a[i1 * 3 + 2] - a[i0 * 3 + 2]
            val vx = a[i2 * 3] - a[i0 * 3]; val vy = a[i2 * 3 + 1] - a[i0 * 3 + 1]; val vz = a[i2 * 3 + 2] - a[i0 * 3 + 2]
            val wx = a[i3 * 3] - a[i0 * 3]; val wy = a[i3 * 3 + 1] - a[i0 * 3 + 1]; val wz = a[i3 * 3 + 2] - a[i0 * 3 + 2]
            return ((uy * vz - uz * vy) * wx + (uz * vx - ux * vz) * wy + (ux * vy - uy * vx) * wz) / 6f
        }
    }
}
