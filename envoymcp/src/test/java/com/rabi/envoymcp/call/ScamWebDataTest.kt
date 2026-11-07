package com.rabi.envoymcp.call

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * ブラウザの試しのページ (docs/try) が使うデータ (docs/try/scam_data.js) を、見分けの決まり (ScamCheck) と評価の台本から作る。
 * ページは、同じ決まりを JavaScript で動かし、台本ごとにアプリの結果 (expected) と同じになるかも確かめる。
 * - 決まりや台本を直して、ページのデータが古くなっていたら落とす
 * - 作り直すとき: UPDATE_WEB_DATA=1 ./gradlew :envoymcp:testDebugUnitTest --tests '*ScamWebDataTest'
 */
class ScamWebDataTest {
    private val out = File("../docs/try/scam_data.js")

    private fun build(): String {
        val signs = JSONArray()
        ScamCheck.Sign.entries.forEach { s ->
            signs.put(JSONObject().put("key", s.name).put("label", s.label).put("patterns", JSONArray(ScamCheck.RULES.getValue(s))))
        }
        val calls = JSONObject(javaClass.classLoader!!.getResource("scam_eval/calls.json")!!.readText()).getJSONArray("calls")
        val scripts = JSONArray()
        for (i in 0 until calls.length()) {
            val c = calls.getJSONObject(i)
            val lines = c.getJSONArray("lines").let { a -> (0 until a.length()).map { a.getString(it) } }
            val r = ScamCheck.checkTexts(lines)
            scripts.put(JSONObject().put("id", c.getString("id")).put("label", c.getString("label")).put("kind", c.getString("kind"))
                .put("tricky", c.optBoolean("tricky")).put("lines", JSONArray(lines))
                .put("expected", JSONObject().put("level", r.level.name).put("reasons", JSONArray(r.reasons))))
        }
        val data = JSONObject()
            .put("signs", signs)
            .put("pressure", JSONArray(ScamCheck.PRESSURE.map { it.name }))
            .put("levels", JSONObject(ScamCheck.Level.entries.associate { it.name to it.label }))
            .put("mark", ScamCheck.MARK)
            .put("greeting", ScamTrial.GREETING)
            .put("advice", ScamTrial.ADVICE)
            .put("noRisk", ScamTrial.NO_RISK)
            .put("example", ScamTrial.EXAMPLE)
            .put("scripts", scripts)
        return "// ブラウザの試しのページが使う、詐欺の見分けの決まりと台本 (作り話)。\n" +
            "// ScamCheck.kt と評価の台本 (calls.json) から、ScamWebDataTest が作る。手で直さない。\n" +
            "const SCAM_DATA = " + data.toString(1) + ";\n"
    }

    @Test fun upToDate() {
        val want = build()
        if (System.getenv("UPDATE_WEB_DATA") == "1") out.writeText(want)
        assertEquals("docs/try/scam_data.js が古くなっています。UPDATE_WEB_DATA=1 を付けて、この試験で作り直してください",
            want, out.takeIf { it.exists() }?.readText())
    }
}
