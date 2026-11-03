package com.rabi.envoymcp.call

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * 電話のあと、まだ本人に伝えていないこと (リゲルが出た・かけた電話の結果と、だれも出なかった着信)。
 * リゲル (ホーム・他のアプリの上の小さなリゲル) をタップして音声モードを開いたとき、最初の一言でまとめて送り、送れたものだけ消す。
 * - 使うまで残す (端末の中に保存する。アプリが終わっても残る)。多すぎるときは古いものから捨てる
 * - 電話番号は入れない (かけてきた人は、名前か「登録されていない番号（末尾 1234）」)
 */
class CallNotes(context: Context) {
    data class Note(val id: String, val at: Long, val text: String)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _notes = MutableStateFlow(load())
    /** まだ伝えていないもの (古い順) */
    val notes: StateFlow<List<Note>> = _notes

    /** 残す (同じ id なら置き換える) */
    @Synchronized fun add(id: String, at: Long, text: String) {
        save((_notes.value.filter { it.id != id } + Note(id, at, text)).sortedBy { it.at }.takeLast(MAX))
    }

    /** 伝えたものを消す */
    @Synchronized fun remove(ids: Collection<String>) {
        if (_notes.value.any { it.id in ids }) save(_notes.value.filter { it.id !in ids })
    }

    private fun save(list: List<Note>) {
        _notes.value = list
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("at", it.at).put("text", it.text)) }
        prefs.edit().putString(KEY, a.toString()).apply()
    }

    private fun load(): List<Note> = runCatching {
        val a = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Note(it.getString("id"), it.getLong("at"), it.getString("text")) } }
    }.getOrDefault(emptyList())

    companion object {
        private const val PREFS = "call_notes"
        private const val KEY = "notes"
        /** 残す数 */
        const val MAX = 10

        /** 音声モードの最初の一言にする文 (新しい順)。無ければ null */
        fun handoff(list: List<Note>, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? {
            if (list.isEmpty()) return null
            val newest = list.sortedByDescending { it.at }
            if (newest.size == 1) {
                val n = newest[0]
                return "（電話の引き継ぎ）${whenOf(n.at, now, zone)}、${n.text}この内容を、私に短く声で伝えてください。"
            }
            val items = newest.mapIndexed { i, n -> "${i + 1}. ${whenOf(n.at, now, zone)}、${n.text}" }.joinToString(" ")
            return "（電話の引き継ぎ）まだお伝えしていない電話が ${newest.size} 件あります（新しい順）。$items" +
                "この内容を、私に分かりやすく整理して、短く声で伝えてください。"
        }

        /** 今日なら「15:42」、昨日なら「昨日 15:42」、それより前なら「11/1 15:42」 */
        internal fun whenOf(at: Long, now: Long, zone: ZoneId): String {
            val t = Instant.ofEpochMilli(at).atZone(zone)
            val days = java.time.temporal.ChronoUnit.DAYS.between(t.toLocalDate(), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
            val hm = "%d:%02d".format(t.hour, t.minute)
            return when (days) {
                0L -> hm
                1L -> "昨日 $hm"
                else -> "${t.monthValue}/${t.dayOfMonth} $hm"
            }
        }
    }
}
