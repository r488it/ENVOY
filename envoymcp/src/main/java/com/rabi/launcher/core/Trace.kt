package com.rabi.launcher.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * リゲルの処理の流れの記録。あとから分析するため、1 回のやりとり (Turn) ごとに
 * 呼びかけ → 聞き取り → 判断 → 返事・操作 → 終わり を、時刻・使ったモデル (Jev・ChatGPT・端末内) ・結果とともに残す。
 * - 聞き取った文・返事の文・操作の内容も残す (分析のため)。端末の中 (files/trace/) だけに置き、外へは送らない
 * - 試験版 (debug ビルド) だけ記録する (init の enabled)
 * - Jev (TypeSafe) の判断も入るので、端末内のモデルの学習・較正・比較には使わない (TypeSafe 契約 §2.3(b))
 * - 1 日 1 ファイル (YYYY-MM-DD.jsonl)、1 行 1 件 (追記)。KEEP_DAYS より古いファイルは消す
 * - 区切り: begin (呼びかけ・タップ・返事のあとに続けて聞く) で始め、end で終える
 * 1 行の形: {"turn":<始まりの時刻 ms>,"at":"<yyyy-MM-ddTHH:mm:ss.SSS+0900>","ms":<始まりからの ms>,"stage":"asr","info":{...}}
 */
object Trace {
    class Event(val turn: Long, val at: Long, val stage: String, val info: JSONObject)
    class Turn(val id: Long, val source: String) {
        val events = ArrayList<Event>()
        @Volatile var ended: Long? = null
    }

    private const val KEEP_DAYS = 14
    private const val KEEP_TURNS = 120
    @Volatile private var dir: File? = null
    private val turns = ArrayDeque<Turn>()
    private var current: Turn? = null
    private val io = Executors.newSingleThreadExecutor { Thread(it, "rabi-trace").apply { isDaemon = true } }
    private val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val atFmt get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    val enabled get() = dir != null

    fun init(base: File, enabled: Boolean) {
        if (!enabled) return
        val d = File(base, "trace").apply { mkdirs() }
        dir = d
        io.execute {
            // 古い記録を消し、今日の分を画面用に読み込む
            val limit = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
            d.listFiles()?.filter { it.name.endsWith(".jsonl") && it.lastModified() < limit }?.forEach { it.delete() }
            runCatching { loadDay(File(d, dayFmt.format(Date()) + ".jsonl")) }
        }
    }

    /** 新しいやりとりを始める (前のが終わっていなければ、途中で終わったものとして閉じる) */
    @Synchronized
    fun begin(source: String, vararg info: Pair<String, Any?>) {
        if (dir == null) return
        current?.let { if (it.ended == null) endLocked("次のやりとりが始まった") }
        val t = Turn(System.currentTimeMillis(), source)
        turns.addLast(t)
        while (turns.size > KEEP_TURNS) turns.removeFirst()
        current = t
        addLocked("begin", arrayOf("source" to source, *info))
    }

    /** いまのやりとりに 1 件足す (始まっていなければ「続き」として始める) */
    @Synchronized
    fun ev(stage: String, vararg info: Pair<String, Any?>) {
        if (dir == null) return
        if (current == null || current?.ended != null) begin("続き")
        addLocked(stage, info)
    }

    /** いまのやりとりを終える */
    @Synchronized
    fun end(outcome: String? = null) {
        if (dir == null || current == null || current?.ended != null) return
        endLocked(outcome)
    }

    private fun endLocked(outcome: String?) {
        addLocked("end", if (outcome != null) arrayOf("outcome" to outcome) else emptyArray())
        current?.ended = System.currentTimeMillis()
        current = null
    }

    private fun addLocked(stage: String, info: Array<out Pair<String, Any?>>) {
        val t = current ?: return
        val now = System.currentTimeMillis()
        val o = JSONObject()
        for ((k, v) in info) if (v != null) o.put(k, when (v) { is Collection<*> -> JSONArray(v); is Float -> v.toDouble(); else -> v })
        val e = Event(t.id, now, stage, o)
        t.events += e
        val line = JSONObject().put("turn", t.id).put("at", atFmt.format(Date(now))).put("ms", now - t.id).put("stage", stage).put("info", o).toString()
        val f = File(dir ?: return, dayFmt.format(Date(now)) + ".jsonl")
        io.execute { runCatching { f.appendText(line + "\n") } }
    }

    private fun loadDay(f: File) {
        if (!f.exists()) return
        val byId = LinkedHashMap<Long, Turn>()
        f.forEachLine { line ->
            runCatching {
                val o = JSONObject(line)
                val id = o.getLong("turn")
                val t = byId.getOrPut(id) { Turn(id, o.optJSONObject("info")?.optString("source").orEmpty().ifBlank { "?" }) }
                val e = Event(id, id + o.optLong("ms"), o.getString("stage"), o.optJSONObject("info") ?: JSONObject())
                t.events += e
                if (e.stage == "end") t.ended = e.at
            }
        }
        synchronized(this) {
            val mine = turns.toList()
            turns.clear()
            (byId.values.toList().takeLast(KEEP_TURNS) + mine).forEach { turns.addLast(it) }
            while (turns.size > KEEP_TURNS) turns.removeFirst()
        }
    }

    /** 画面用: 新しい順 */
    @Synchronized
    fun recent(n: Int = 40): List<Turn> = turns.toList().takeLast(n).reversed()

    fun files(): List<File> = dir?.listFiles()?.filter { it.name.endsWith(".jsonl") }?.sortedByDescending { it.name }.orEmpty()

    @Synchronized
    fun clear() {
        turns.clear(); current = null
        files().forEach { it.delete() }
    }
}
