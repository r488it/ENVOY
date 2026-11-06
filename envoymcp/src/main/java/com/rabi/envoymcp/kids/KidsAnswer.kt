package com.rabi.envoymcp.kids

import java.text.Normalizer

/**
 * 子どもが声で言った答え (AI が文字にして渡す) を、今の問題の選ぶものと比べる。端末に触れない (試験する)。
 * 言い方のちがいを受け付ける:
 * - かず: 「3」「３」「さん」「みっつ」「三」「3こ」「さんこ」「3 だよ」など
 * - ひらがな: 「あ」「ア」「あー」「『あ』」など (1 文字)
 * - とけい: 「3じ」「さんじ」「3時」「3じはん」「3時半」「3:30」「3時30分」「さん」など
 * 答えの中身は記録に残さない
 */
object KidsAnswer {
    /** 比べた結果 */
    sealed interface Heard {
        /** 選ぶものの、この番号のもの */
        data class Choice(val index: Int) : Heard
        /** 答えの形だが、選ぶものには無い (まちがい) */
        data class Other(val value: String) : Heard
        /** 答えとして分からない */
        data object Unclear : Heard
    }

    /** said を、問題 q の選ぶものと比べる */
    fun judge(q: Question, said: String): Heard {
        val s = norm(said)
        if (s.isEmpty()) return Heard.Unclear
        return when (q.topic) {
            Topic.KAZU -> number(s)?.let { n -> pick(q, "$n") { c -> (if (c.dots > 0) c.dots else c.text.toIntOrNull()) == n } }
            Topic.HIRAGANA -> letter(s)?.let { l -> pick(q, l) { it.text == l } }
            Topic.TOKEI -> time(s)?.let { (h, m) -> QuestionMaker.clockLabel(h, m).let { label -> pick(q, label) { it.text == label } } }
        } ?: Heard.Unclear
    }

    /** 言った題 (かず・ひらがな・とけい。「すうじ」「もじ」「じかん」なども)。分からなければ null */
    fun topic(said: String): Topic? {
        val s = norm(said)
        Topic.byId(s)?.let { return it }
        return TOPIC_WORDS.entries.firstOrNull { (_, words) -> words.any { s.contains(it) } }?.key
    }

    private fun pick(q: Question, value: String, same: (Choice) -> Boolean): Heard {
        val i = q.choices.indexOfFirst(same)
        return if (i >= 0) Heard.Choice(i) else Heard.Other(value)
    }

    /** NFKC・小文字・カタカナをひらがなに・のばす音と記号と空白を除く・終わりのことば (「です」「だよ」など) を除く */
    fun norm(said: String): String {
        val n = Normalizer.normalize(said, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder(n.length)
        for (ch in n) {
            val c = if (ch in 'ァ'..'ヶ') ch - 0x60 else ch
            if (c.isWhitespace() || c in DROP) continue
            sb.append(c)
        }
        var s = sb.toString()
        var cut = true
        while (cut) {
            cut = false
            for (t in TAILS) if (s.length > t.length && s.endsWith(t)) { s = s.dropLast(t.length); cut = true; break }
        }
        return s
    }

    /** 数 (数字・漢数字・よみ・ものの数え方。「こ」「つ」「個」が付いていてもよい)。分からなければ null */
    fun number(s: String): Int? {
        for (c in listOf(s, s.removeSuffix("こ"), s.removeSuffix("個"), s.removeSuffix("つ"))) parse(c)?.let { return it }
        return null
    }

    private fun parse(c: String): Int? {
        if (c.isEmpty()) return null
        if (c.all { it.isDigit() }) return c.take(3).toIntOrNull()
        READINGS[c]?.let { return it }
        if (c.all { it in KANJI }) return when (c.length) {
            1 -> KANJI.getValue(c[0])
            2 -> if (c[0] == '十') 10 + KANJI.getValue(c[1]) else if (c[1] == '十') KANJI.getValue(c[0]) * 10 else null
            else -> null
        }
        return null
    }

    /** ひらがな 1 文字 (それ以外は null) */
    private fun letter(s: String): String? = s.takeIf { it.length == 1 && it[0] in 'ぁ'..'ん' }

    /** 時刻 (時・分)。1〜12 時の、ちょうどか半だけ。分からなければ null */
    fun time(said: String): Pair<Int, Int>? {
        val s = said.replace("時", "じ").replace("半", "はん").replace("分", "ふん").removeSuffix("ちょうど")
        val t = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(s)?.let { m -> m.groupValues[1].toInt() to m.groupValues[2].toInt() }
            ?: Regex("^(.+?)じ(.*)$").matchEntire(s)?.let { m -> hour(m.groupValues[1])?.let { h -> minute(m.groupValues[2])?.let { h to it } } }
            ?: Regex("^(.+?)はん$").matchEntire(s)?.let { m -> hour(m.groupValues[1])?.let { it to 30 } }
            ?: number(s)?.let { it to 0 }
        return t?.takeIf { (h, m) -> h in 1..12 && (m == 0 || m == 30) }
    }

    private fun hour(s: String): Int? = number(s) ?: HOUR_ONLY[s]

    private fun minute(s: String): Int? = when (s) {
        "", "ちょうど", "0", "00", "0ふん", "0ぷん", "00ふん", "00ぷん" -> 0
        "はん", "30", "30ふん", "30ぷん", "さんじゅっぷん", "さんじっぷん", "さんじゅうふん", "さんじゅっふん" -> 30
        else -> null
    }

    /** 除く記号 (NFKC のあと。時刻の「:」は残す) */
    private const val DROP = "ー〜~!?。、,.・「」『』()\"'…"
    /** 終わりのことば (答えのあとに付くもの) */
    private val TAILS = listOf("でしょう", "でしょ", "です", "だよ", "だね", "かな", "かも", "だ", "よ", "ね")
    private val KANJI = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10)
    /** 数のよみと、ものの数え方 (「ー」を除いたあとの形も) */
    private val READINGS = mapOf(
        "いち" to 1, "ひとつ" to 1, "ひと" to 1, "いっ" to 1,
        "に" to 2, "ふたつ" to 2, "ふた" to 2,
        "さん" to 3, "みっつ" to 3, "みつ" to 3, "みっ" to 3,
        "よん" to 4, "し" to 4, "よっつ" to 4, "よつ" to 4, "よっ" to 4,
        "ご" to 5, "いつつ" to 5, "いつ" to 5,
        "ろく" to 6, "むっつ" to 6, "むつ" to 6, "ろっ" to 6,
        "なな" to 7, "しち" to 7, "ななつ" to 7,
        "はち" to 8, "やっつ" to 8, "やつ" to 8, "はっ" to 8,
        "きゅう" to 9, "きゅ" to 9, "く" to 9, "ここのつ" to 9,
        "じゅう" to 10, "じゅ" to 10, "とお" to 10, "とう" to 10, "じゅっ" to 10, "じっ" to 10,
        "じゅういち" to 11, "じゅうに" to 12,
    )
    /** 時刻だけのよみ (よじ・くじ) */
    private val HOUR_ONLY = mapOf("よ" to 4, "く" to 9)
    /** 題の言い方 */
    private val TOPIC_WORDS = mapOf(
        Topic.KAZU to listOf("かず", "すうじ", "かぞえ"),
        Topic.HIRAGANA to listOf("ひらがな", "もじ"),
        Topic.TOKEI to listOf("とけい", "じかん", "なんじ"),
    )
}
