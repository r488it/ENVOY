package com.rabi.launcher.parts

import android.content.Context
import java.io.File

/** 同梱の部品 (assets/parts の JSON) と、覚えた部品・実績 (filesDir/parts)。 */
class AndroidPartStore(private val context: Context) : PartStore {
    private val dir = File(context.filesDir, "parts").apply { mkdirs() }
    private val archiveDir = File(dir, "archive").apply { mkdirs() }

    override fun builtin(): List<String> = context.assets.list("parts").orEmpty().filter { it.endsWith(".json") }.sorted()
        .map { context.assets.open("parts/$it").bufferedReader().use { r -> r.readText() } }

    override fun learned(): List<String> = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != STATS }.orEmpty().sortedBy { it.name }.map { it.readText() }

    override fun saveLearned(id: String, version: Int, json: String) = atomicWrite(File(dir, safe(id) + ".json"), json)

    override fun archive(id: String, version: Int, json: String) = atomicWrite(File(archiveDir, "${safe(id)}.v$version.json"), json)

    override fun deleteLearned(id: String) { File(dir, safe(id) + ".json").delete() }

    override fun readStats(): String? = File(dir, STATS).takeIf { it.exists() }?.readText()

    override fun writeStats(json: String) = atomicWrite(File(dir, STATS), json)

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun atomicWrite(f: File, s: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(s)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object { private const val STATS = "_stats.json" }
}
