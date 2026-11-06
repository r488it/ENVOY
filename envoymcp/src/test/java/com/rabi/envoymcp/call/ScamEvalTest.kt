package com.rabi.envoymcp.call

import com.rabi.envoymcp.call.ScamCheck.Level
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 詐欺の見分けの評価。台本 (src/test/resources/scam_eval/calls.json) は、見分けの規則を見ていない書き手が、
 * 警察庁などの公開の手口の説明をもとに書いた作り話 (詐欺 30 本・ふつうの電話 30 本)。
 * - 台本ごとに疑いの強さを出し、表を build/reports/scam_eval.md に書き出す
 * - 測った数より悪くなったら落とす (規則を直したときに、見逃しや、ふつうの電話を疑う数が増えていないか)
 * - 文字の台本で測るので、声の聞き取りの間違いは入らない
 */
class ScamEvalTest {
    private data class Call(val id: String, val scam: Boolean, val kind: String, val tricky: Boolean, val transcript: Boolean, val lines: List<String>)

    private data class Scored(val call: Call, val level: Level, val reasons: List<String>)

    private fun load(): List<Call> {
        val text = javaClass.classLoader!!.getResource("scam_eval/calls.json")!!.readText()
        val a = JSONObject(text).getJSONArray("calls")
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val l = o.getJSONArray("lines")
            Call(o.getString("id"), o.getString("label") == "scam", o.getString("kind"), o.optBoolean("tricky"),
                o.optString("style") == "transcript", (0 until l.length()).map { l.getString(it) })
        }
    }

    @Test fun table() {
        val calls = load()
        assertEquals(30, calls.count { it.scam })
        assertEquals(30, calls.count { !it.scam })
        val scored = calls.map { c -> ScamCheck.checkTexts(c.lines).let { Scored(c, it.level, it.reasons) } }
        val scam = scored.filter { it.call.scam }
        val ordinary = scored.filter { !it.call.scam }

        File("build/reports").mkdirs()
        File("build/reports/scam_eval.md").writeText(report(scam, ordinary))

        // 測った数 (これより悪くなったら落とす)
        assertTrue(scam.count { it.level == Level.HIGH } >= MIN_SCAM_HIGH)
        assertTrue(scam.count { it.level == Level.NONE } <= MAX_SCAM_MISSED)
        assertTrue(ordinary.count { it.level == Level.HIGH } <= MAX_ORDINARY_HIGH)
        assertTrue(ordinary.count { it.level == Level.NONE } >= MIN_ORDINARY_NONE)
    }

    private fun report(scam: List<Scored>, ordinary: List<Scored>): String = buildString {
        fun row(name: String, l: List<Scored>) {
            val n = Level.entries.reversed().map { lv -> l.count { it.level == lv } }
            appendLine("| $name | ${l.size} | ${n[0]} | ${n[1]} | ${n[2]} |")
        }
        appendLine("## 疑いの強さ")
        appendLine()
        appendLine("| 台本 | 本数 | 高い | 気をつけて | なし |")
        appendLine("| --- | ---: | ---: | ---: | ---: |")
        row("詐欺", scam)
        row("ふつうの電話", ordinary)
        row("（うち、まぎらわしい本物）", ordinary.filter { it.call.tricky })
        appendLine()
        appendLine("## 詐欺の手口ごと")
        appendLine()
        appendLine("| 手口 | 本数 | 高い | 気をつけて | なし |")
        appendLine("| --- | ---: | ---: | ---: | ---: |")
        scam.groupBy { it.call.kind }.forEach { (k, l) -> row(k, l) }
        appendLine()
        appendLine("## 書き方ごと")
        appendLine()
        appendLine("| 台本 | 本数 | 高い | 気をつけて | なし |")
        appendLine("| --- | ---: | ---: | ---: | ---: |")
        row("詐欺（文字起こしの形）", scam.filter { it.call.transcript })
        row("詐欺（ふつうの書き方）", scam.filter { !it.call.transcript })
        row("ふつうの電話（文字起こしの形）", ordinary.filter { it.call.transcript })
        row("ふつうの電話（ふつうの書き方）", ordinary.filter { !it.call.transcript })
        appendLine()
        appendLine("## 1 本ずつ")
        appendLine()
        appendLine("| id | 正解 | 種類 | 強さ | 理由 |")
        appendLine("| --- | --- | --- | --- | --- |")
        (scam + ordinary).forEach {
            val truth = if (it.call.scam) "詐欺" else if (it.call.tricky) "ふつう（まぎらわしい）" else "ふつう"
            appendLine("| ${it.call.id} | $truth | ${it.call.kind} | ${it.level.label.ifEmpty { "なし" }} | ${it.reasons.joinToString("・")} |")
        }
    }

    private companion object {
        const val MIN_SCAM_HIGH = 22
        const val MAX_SCAM_MISSED = 0
        const val MAX_ORDINARY_HIGH = 2
        const val MIN_ORDINARY_NONE = 17
    }
}
