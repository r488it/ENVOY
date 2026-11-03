package com.rabi.envoymcp.parts

import com.rabi.envoymcp.core.Text
import org.json.JSONObject

/** 部品の保存先。Android ではアセット (同梱) + filesDir (覚えたもの・実績)。試験ではメモリ。 */
interface PartStore {
    fun builtin(): List<String>
    fun learned(): List<String>
    fun saveLearned(id: String, version: Int, json: String)
    fun archive(id: String, version: Int, json: String)
    fun deleteLearned(id: String)
    fun readStats(): String?
    fun writeStats(json: String)
}

/**
 * 部品の一覧と、この端末での実績 (成功・失敗・System 2 への引き渡し)。
 * 状態の移り変わり:
 *  覚えた直前 (DRAFT) → 成功を確かめて保存 (PROVISIONAL、成功 1) → 再利用で 2 回成功 (VERIFIED)
 *  VERIFIED でも 2 回続けて画面が想定と違えば PROVISIONAL に戻す。
 *  確定操作の承認は状態に関係なく毎回求める。
 */
class PartRepository(private val store: PartStore, private val device: String = "this-device") {
    private val parts = LinkedHashMap<String, Part>()
    private val stats = HashMap<String, Verification>()
    private val streak = HashMap<String, Int>()

    init { reload() }

    @Synchronized
    fun reload() {
        parts.clear()
        for (s in store.builtin()) runCatching { Part.from(JSONObject(s)) }.getOrNull()?.let { parts[it.id] = it }
        for (s in store.learned()) runCatching { Part.from(JSONObject(s)) }.getOrNull()?.let { p ->
            val old = parts[p.id]
            if (old == null || p.version >= old.version) parts[p.id] = p
        }
        stats.clear()
        store.readStats()?.let { s ->
            runCatching {
                val j = JSONObject(s)
                for (k in j.keys()) stats[k] = Verification.from(j.getJSONObject(k))
            }
        }
    }

    @Synchronized fun all(): List<Part> = parts.values.toList()
    @Synchronized fun get(id: String): Part? = parts[id]

    /** この端末での検証状態 (同梱部品も、この端末で 2 回成功するまでは暫定)。 */
    @Synchronized
    fun verification(p: Part): Verification = stats[key(p)] ?: when (p.source) {
        "learned" -> p.verification
        else -> Verification(VerifyStatus.PROVISIONAL)
    }

    fun usable(): List<Part> = all().filter { verification(it).status != VerifyStatus.DISABLED && it.verification.status != VerifyStatus.DISABLED && !blindTap(it) }

    /** 押す場所の手がかりが空の手を含む、覚えた部品。違う所を押すので使わない (ファイルは消さない) */
    private fun blindTap(p: Part) = p.source == "learned" && p.steps.any { it.op == "tap" && it.selector?.isEmpty != false }

    /** 発話に近い順の候補 (System 1 に渡す選択肢)。 */
    fun candidates(utterance: String, k: Int = 12): List<Pair<Part, Double>> =
        usable().map { p -> p to score(utterance, p) }.sortedByDescending { it.second }.take(k)

    fun score(utterance: String, p: Part): Double {
        val ex = (p.examples + p.name).maxOfOrNull { Text.matchScore(utterance, it) } ?: 0.0
        return maxOf(ex, 0.8 * Text.similarity(utterance, p.description))
    }

    @Synchronized
    fun recordSuccess(p: Part) = update(p) { v ->
        streak[key(p)] = 0
        val n = v.successes + 1
        val st = if (v.status == VerifyStatus.PROVISIONAL && n >= PROMOTE_AT) VerifyStatus.VERIFIED else v.status
        v.copy(status = st, successes = n, lastResult = "success", updatedAt = System.currentTimeMillis(),
            verifiedOn = if (st == VerifyStatus.VERIFIED) (v.verifiedOn + device).distinct() else v.verifiedOn)
    }

    @Synchronized
    fun recordEscalation(p: Part, reason: String) = update(p) { v ->
        val s = (streak[key(p)] ?: 0) + 1
        streak[key(p)] = s
        val st = if (v.status == VerifyStatus.VERIFIED && s >= 2) VerifyStatus.PROVISIONAL else v.status
        v.copy(status = st, escalations = v.escalations + 1, lastResult = "escalated: ${reason.take(80)}", updatedAt = System.currentTimeMillis())
    }

    @Synchronized
    fun recordFailure(p: Part, reason: String) = update(p) { v ->
        v.copy(failures = v.failures + 1, lastResult = "failed: ${reason.take(80)}", updatedAt = System.currentTimeMillis())
    }

    /**
     * 覚えた部品を保存する。成功を確かめた後にだけ呼ぶ。暫定 (PROVISIONAL・成功 1) で保存。
     * 同じ ID があれば版を上げ、古い版は archive に残す。
     */
    @Synchronized
    fun saveLearned(draft: Part): Part {
        val old = parts[draft.id]
        val version = (old?.version ?: 0) + 1
        old?.let { store.archive(it.id, it.version, it.toJson().toString(2)) }
        val v = Verification(VerifyStatus.PROVISIONAL, successes = 1, lastResult = "learned", updatedAt = System.currentTimeMillis())
        val p = draft.copy(version = version, source = "learned", verification = v)
        store.saveLearned(p.id, p.version, p.toJson().toString(2))
        parts[p.id] = p
        stats[key(p)] = v
        persistStats()
        return p
    }

    @Synchronized
    fun deleteLearned(id: String) {
        val p = parts[id] ?: return
        if (p.source != "learned") return
        store.deleteLearned(id)
        parts.remove(id)
        reload()
    }

    private fun update(p: Part, f: (Verification) -> Verification) {
        val k = key(p)
        stats[k] = f(verification(p))
        if (p.source == "learned") {
            val np = p.copy(verification = stats[k]!!)
            parts[p.id] = np
            store.saveLearned(np.id, np.version, np.toJson().toString(2))
        }
        persistStats()
    }

    private fun persistStats() {
        val j = JSONObject()
        stats.forEach { (k, v) -> j.put(k, v.toJson()) }
        store.writeStats(j.toString())
    }

    private fun key(p: Part) = "${p.id}@${p.version}"

    companion object {
        /** 覚えた時の 1 回 + 再利用の 2 回 */
        const val PROMOTE_AT = 3

    }
}

/** 試験用のメモリ上の保存先。 */
class MemoryPartStore(private val builtins: List<String> = emptyList()) : PartStore {
    val learnedMap = LinkedHashMap<String, String>()
    val archived = ArrayList<String>()
    var statsJson: String? = null
    override fun builtin() = builtins
    override fun learned() = learnedMap.values.toList()
    override fun saveLearned(id: String, version: Int, json: String) { learnedMap[id] = json }
    override fun archive(id: String, version: Int, json: String) { archived += "$id@$version" }
    override fun deleteLearned(id: String) { learnedMap.remove(id) }
    override fun readStats() = statsJson
    override fun writeStats(json: String) { statsJson = json }
}
