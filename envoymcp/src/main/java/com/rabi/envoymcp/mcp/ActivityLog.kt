package com.rabi.envoymcp.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * したこと: AI エージェント (ChatGPT など) とリゲルが、このスマホでしたことを、本人が読める短い文で残す (ホームの「したこと」で見る)。
 * - 残すのは、時刻・だれが・どの道具・何をしたかの文 (ActivitySentence)・見ただけか・どうなったか・本人が承認したか だけ
 * - 画面の中身・入れた文字・メッセージの本文・要点・合言葉・電話番号の全体は入れない
 * - このスマホのアプリ専用の場所のファイルだけ (新しいものが先頭)。KEEP_MS より古いもの・MAX 件を超えた分は消す
 */
class ActivityLog(
    private val file: File? = null,
    private val max: Int = MAX,
    private val keepMs: Long = KEEP_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Entry(
        val at: Long,
        /** だれが (エージェントの名前・リゲル・本人) */
        val agent: String,
        /** 道具の名前 (道具でないものは、着信なら incoming_call などの印) */
        val tool: String,
        val sentence: String,
        /** read: 見ただけ (読むだけの道具) / act: それ以外 */
        val kind: String,
        /** ok / failed / stopped (本人が止めた) / refused (許していない・承認されなかった) */
        val outcome: String,
        /** 本人が承認した (true)・承認しなかった (false)・承認の無いもの (null) */
        val approved: Boolean? = null,
    )

    private val _entries = MutableStateFlow(prune(load()))
    val entries: StateFlow<List<Entry>> = _entries

    @Synchronized fun add(agent: String, tool: String, sentence: String, kind: String, outcome: String, approved: Boolean? = null) {
        val e = Entry(now(), agent.take(40), tool, sentence.take(MAX_SENTENCE), kind, outcome, approved)
        _entries.value = prune(listOf(e) + _entries.value)
        save(_entries.value)
    }

    private fun prune(list: List<Entry>): List<Entry> {
        val since = now() - keepMs
        return list.filter { it.at >= since }.take(max)
    }

    private fun load(): List<Entry> = runCatching {
        val f = file?.takeIf { it.exists() } ?: return emptyList()
        val a = JSONArray(f.readText())
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let {
                Entry(it.getLong("at"), it.optString("agent"), it.optString("tool"), it.optString("sentence"), it.optString("kind", KIND_ACT),
                    it.optString("outcome", OK), if (it.has("approved")) it.getBoolean("approved") else null)
            }
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Entry>) {
        val f = file ?: return
        runCatching {
            val a = JSONArray(list.map {
                JSONObject().put("at", it.at).put("agent", it.agent).put("tool", it.tool).put("sentence", it.sentence)
                    .put("kind", it.kind).put("outcome", it.outcome).apply { if (it.approved != null) put("approved", it.approved) }
            })
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    companion object {
        const val KIND_READ = "read"
        const val KIND_ACT = "act"
        const val OK = "ok"
        const val FAILED = "failed"
        const val STOPPED = "stopped"
        const val REFUSED = "refused"
        /** リゲル自身がしたこと (道具でないもの) の名前 */
        const val RIGEL = "リゲル"
        /** 本人が設定で変えたこと */
        const val USER = "本人"
        const val MAX = 500
        const val KEEP_MS = 30 * 24 * 60 * 60_000L
        private const val MAX_SENTENCE = 120
    }
}
