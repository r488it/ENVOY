package com.rabi.envoymcp.arm

import kotlin.math.abs
import kotlin.math.max

/**
 * 顔を追い続けるとき、いつ向け直すかの判断 (Android に頼らない。テストできるように分けてある)。
 * 腕は「頼まれたら 1 回合わせる」(face_user) だけなので、ずっと追うときは ENVOY MCP がこれで向け直しを頼む。
 * - 向け終わった直後の顔の向きを基準にする (腕は好みの角度を足して合わせるので、0 に近いとは限らない)
 * - 基準から tolDeg 以上ずれた状態が dwellMs 続いたら、向け直す (うなずき・少し動いたくらいでは動かさない。
 *   ずっと追い続けると、動きが気になって落ち着かないため)
 * - 向け終わったあと quietMs (できなかったときは failQuietMs) は向け直さない
 * - 顔が lostMs 見えなければ、追うのをやめる (電池と熱のため)
 */
class FollowPolicy(
    private val tolDeg: Double = 10.0,
    private val dwellMs: Long = 1_500,
    private val quietMs: Long = 2_000,
    private val failQuietMs: Long = 8_000,
    private val lostMs: Long = 10 * 60_000L,
) {
    private var base = 0.0 to 0.0
    private var needBase = false
    private var driftSince: Long? = null
    private var quietUntil = 0L
    private var lastSeen = 0L

    /** 追い始めた */
    fun reset(now: Long) {
        base = 0.0 to 0.0; needBase = false; driftSince = null; quietUntil = now; lastSeen = now
    }

    /** 向け終わった (ok: 向けられた。次に見えた顔の向きを基準にする) */
    fun aimed(now: Long, ok: Boolean) {
        driftSince = null
        needBase = ok
        quietUntil = now + if (ok) quietMs else failQuietMs
    }

    /** ほかの動き (縦・横) が終わった: しばらく向け直さない */
    fun hold(now: Long) {
        driftSince = null
        quietUntil = max(quietUntil, now + quietMs)
    }

    /** 顔の向き (h, v) 度を受け取る。見えなければ null。向け直すときは true */
    fun sample(hv: Pair<Double, Double>?, now: Long): Boolean {
        if (hv == null) { driftSince = null; return false }
        lastSeen = now
        if (needBase) { base = hv; needBase = false; driftSince = null; return false }
        val err = max(abs(hv.first - base.first), abs(hv.second - base.second))
        if (err < tolDeg) { driftSince = null; return false }
        val since = driftSince ?: now.also { driftSince = it }
        return now >= quietUntil && now - since >= dwellMs
    }

    /** 顔が長く見えていない */
    fun lost(now: Long) = now - lastSeen > lostMs
}
