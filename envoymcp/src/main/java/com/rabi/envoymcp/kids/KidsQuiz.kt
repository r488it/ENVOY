package com.rabi.envoymcp.kids

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * まなびの題。id は道具と設定で使う名前、label は画面と記録に出す名前、maxLevel はいちばん上の難しさ。
 * - かず: レベル 1 は 1〜5、レベル 2 は 1〜10
 * - ひらがな: レベル 1 は あ行、2 は か行、3 は さ行 … 10 は わ行
 * - とけい: レベル 1 は「〜じ」だけ、レベル 2 は「〜じはん」も
 */
enum class Topic(val id: String, val label: String, val maxLevel: Int) {
    KAZU("kazu", "かず", 2),
    HIRAGANA("hiragana", "ひらがな", 10),
    TOKEI("tokei", "とけい", 2);

    companion object {
        fun byId(id: String?): Topic? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/** 問いの絵 (画面に Compose で描く。写真や借りた絵は使わない) */
sealed interface Visual {
    /** 数えるものを n こ (kind で、りんご・ほし・みかん・ボールを描き分ける。QuestionMaker.ITEMS) */
    data class Dots(val n: Int, val kind: Int = 0) : Visual
    /** 大きな数字 */
    data class Numeral(val n: Int) : Visual
    /** 針の時計 (h 時 m 分) */
    data class Clock(val h: Int, val m: Int) : Visual
    /** 絵なし (問いの文だけ) */
    data object None : Visual
}

/** 選ぶもの。text は大きく出す字 (数字・ひらがな・「3じ」)。dots が 1 以上なら、字の代わりに丸を dots こ描く */
data class Choice(val text: String, val dots: Int = 0) {
    /** AI と記録に渡す言い方 */
    val label: String get() = if (dots > 0) "$dots こ" else text
}

/** 1 つの問い。answer は正しい選択肢の番号 (0 から) */
data class Question(
    val topic: Topic,
    val level: Int,
    /** 画面に出す問い */
    val text: String,
    /** 読み上げる問い */
    val speech: String,
    val visual: Visual,
    val choices: List<Choice>,
    val answer: Int,
) {
    val answerLabel: String get() = choices[answer].label

    /** 画面に出した絵の言い方 (AI が会話に使う。例: りんごが 3 こ) */
    val shown: String get() = when (val v = visual) {
        is Visual.Dots -> "${QuestionMaker.item(v.kind)}が ${v.n} こ"
        is Visual.Numeral -> "すうじの ${v.n}"
        is Visual.Clock -> "とけいが ${QuestionMaker.clockLabel(v.h, v.m)}"
        Visual.None -> ""
    }

    /** AI の先生に渡す形 (画面の問い・読む文・画面の絵・選ぶもの・正しい答え) */
    fun toJson(): JSONObject = JSONObject()
        .put("topic", topic.id).put("level", level).put("question", text).put("read", speech)
        .put("choices", JSONArray(choices.map { it.label })).put("answer", answerLabel)
        .apply { if (shown.isNotEmpty()) put("shown", shown) }
}

/** 問いを作る (題と難しさごと。試験では種を決めた Random を渡す) */
object QuestionMaker {
    /** ひらがなの行 (レベル 1 が あ行、2 が か行 …) */
    val ROWS = listOf("あいうえお", "かきくけこ", "さしすせそ", "たちつてと", "なにぬねの", "はひふへほ", "まみむめも", "やゆよ", "らりるれろ", "わをん")
    /** 数えるもの (Visual.Dots の kind ごと。画面に描くものと同じ) */
    val ITEMS = listOf("りんご", "ほし", "みかん", "ボール")
    /** 数えるものの種類の数 */
    const val DOT_KINDS = 4

    /** 数えるものの名前 */
    fun item(kind: Int): String = ITEMS[kind.mod(ITEMS.size)]

    fun make(topic: Topic, level: Int, random: Random): Question {
        val lv = level.coerceIn(1, topic.maxLevel)
        return when (topic) {
            Topic.KAZU -> kazu(lv, random)
            Topic.HIRAGANA -> hiragana(lv, random)
            Topic.TOKEI -> tokei(lv, random)
        }
    }

    /** かずの範囲: レベル 1 は 1〜5、レベル 2 は 1〜10 */
    fun numberRange(level: Int): IntRange = if (level <= 1) 1..5 else 1..10

    /**
     * かず: 丸を見せて数を選ぶ (いくつ あるかな？) か、数を見せて丸のまとまりを選ぶ (3 は どれ？)。
     * 選ぶものは、レベル 1 は 3 つ、レベル 2 は 4 つ (丸のまとまりは見分けやすいよう 3 つまで)
     */
    private fun kazu(level: Int, random: Random): Question {
        val range = numberRange(level)
        val n = range.random(random)
        return if (random.nextBoolean()) {
            val values = withOthers(n, range.toList(), if (level <= 1) 3 else 4, random)
            Question(Topic.KAZU, level, "いくつ あるかな？", "いくつ あるかな？", Visual.Dots(n, random.nextInt(DOT_KINDS)),
                values.map { Choice("$it") }, values.indexOf(n))
        } else {
            val values = withOthers(n, range.toList(), 3, random)
            Question(Topic.KAZU, level, "$n は どれ？", "$n は どれかな？", Visual.Numeral(n), values.map { Choice("$it", dots = it) }, values.indexOf(n))
        }
    }

    /** ひらがな: 「あ」は どれ？ (同じ行の字から選ぶ。レベル 1 は 2 つ、2〜3 は 3 つ、それより上は 4 つ) */
    private fun hiragana(level: Int, random: Random): Question {
        val row = ROWS[level - 1].map { it.toString() }
        val target = row.random(random)
        val count = when { level == 1 -> 2; level <= 3 -> 3; else -> 4 }.coerceAtMost(row.size)
        val letters = withOthers(target, row, count, random)
        return Question(Topic.HIRAGANA, level, "「$target」は どれ？", "「$target」は どれかな？", Visual.None,
            letters.map { Choice(it) }, letters.indexOf(target))
    }

    /** とけい: 針の時計を見せて「なんじ？」。レベル 2 は「〜じはん」も出す。選ぶものは 3 つ */
    private fun tokei(level: Int, random: Random): Question {
        val h = (1..12).random(random)
        val m = if (level >= 2 && random.nextBoolean()) 30 else 0
        val all = (1..12).flatMap { hh -> if (level >= 2) listOf(hh to 0, hh to 30) else listOf(hh to 0) }
        val answer = h to m
        // はんのあるレベルでは、同じ時の「はん」ちがいを 1 つ混ぜる (見分ける練習)
        val near = if (level >= 2) listOf(h to (30 - m)) else emptyList()
        val others = (near + (all - answer - near.toSet()).shuffled(random)).take(2)
        val times = (others + answer).shuffled(random)
        return Question(Topic.TOKEI, level, "なんじ？", "なんじ かな？", Visual.Clock(h, m),
            times.map { Choice(clockLabel(it.first, it.second)) }, times.indexOf(answer))
    }

    /** 時刻の言い方 (3じ・3じはん) */
    fun clockLabel(h: Int, m: Int): String = if (m == 30) "${h}じはん" else "${h}じ"

    /** 正しいものと、ほかのもの (重ならない) を合わせて count こ、並びを混ぜて返す */
    private fun <T> withOthers(answer: T, pool: List<T>, count: Int, random: Random): List<T> =
        (pool.filter { it != answer }.shuffled(random).take(count - 1) + answer).shuffled(random)
}
