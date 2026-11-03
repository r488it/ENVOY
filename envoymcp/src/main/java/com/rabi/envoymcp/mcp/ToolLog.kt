package com.rabi.envoymcp.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ChatGPT から呼ばれた道具の記録 (設定の画面に出す。新しいものが先頭)。
 * file があれば、そこにも残す (入れ直し・再起動のあとでも調べられるように。この端末のアプリ専用の場所だけ)。
 * 合言葉 (トークン) や画面の中身は入れない
 */
class ToolLog(private val file: File? = null, private val max: Int = 50) {
    data class Entry(val at: Long, val name: String, val ok: Boolean, val detail: String)

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<Entry>> = _entries

    fun add(name: String, ok: Boolean, detail: String = "") {
        val e = Entry(System.currentTimeMillis(), name, ok, detail.take(200))
        _entries.update { (listOf(e) + it).take(max) }
        save(_entries.value)
    }

    private fun load(): List<Entry> = runCatching {
        val f = file?.takeIf { it.exists() } ?: return emptyList()
        val a = JSONArray(f.readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Entry(it.getLong("at"), it.getString("name"), it.getBoolean("ok"), it.optString("detail")) } }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(list: List<Entry>) {
        val f = file ?: return
        runCatching {
            val a = JSONArray(list.map { JSONObject().put("at", it.at).put("name", it.name).put("ok", it.ok).put("detail", it.detail) })
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}
