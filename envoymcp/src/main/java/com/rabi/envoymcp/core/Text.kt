package com.rabi.envoymcp.core

import java.text.Normalizer

/** 日本語の発話を比べるための正規化と、簡単な類似度。 */
object Text {
    private val punct = Regex("[\\s、。，．,.!！?？・「」『』（）()\\[\\]【】…〜~\"'`]+")

    /** NFKC・小文字・カタカナ→ひらがな・句読点と空白を除く。 */
    fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder(n.length)
        for (ch in n) sb.append(if (ch in 'ァ'..'ヶ') (ch - 0x60) else ch)
        return sb.toString().replace(punct, "")
    }

    /** 文字 bigram の Dice 係数 (0..1)。 */
    fun similarity(a: String, b: String): Double {
        val x = grams(norm(a)); val y = grams(norm(b))
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var inter = 0
        val counts = HashMap<String, Int>()
        for (g in y) counts[g] = (counts[g] ?: 0) + 1
        for (g in x) { val c = counts[g] ?: 0; if (c > 0) { inter++; counts[g] = c - 1 } }
        return 2.0 * inter / (x.size + y.size)
    }

    /** 片方がもう片方に含まれる度合いも見る類似度。短い例文と長い発話を比べるとき用。 */
    fun matchScore(utterance: String, example: String): Double {
        val u = norm(utterance); val e = norm(example)
        if (u.isEmpty() || e.isEmpty()) return 0.0
        if (u == e) return 1.0
        val contain = if (u.contains(e) || e.contains(u)) 0.85 * minOf(u.length, e.length) / maxOf(u.length, e.length) + 0.15 else 0.0
        return maxOf(similarity(u, e), contain)
    }

    private fun grams(s: String): List<String> = if (s.length < 2) listOf(s).filter { it.isNotEmpty() } else (0 until s.length - 1).map { s.substring(it, it + 2) }

    /** ログ用: 4 桁以上の数字・メールアドレスを伏せる。 */
    fun redact(s: String): String = s
        .replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "<email>")
        .replace(Regex("\\d[\\d-]{3,}"), "<num>")

    /** {a.b} 形式のテンプレートを埋める ({a.b:url} は URL エンコード)。値が無いものがあれば null。 */
    fun fill(template: String, vars: Map<String, String>): String? {
        var missing = false
        val out = Regex("\\{([a-zA-Z0-9_.]+)(:url)?\\}").replace(template) { m ->
            val v = vars[m.groupValues[1]] ?: run { missing = true; "" }
            if (m.groupValues[2] == ":url") java.net.URLEncoder.encode(v, "UTF-8") else v
        }
        return if (missing) null else out
    }
}
