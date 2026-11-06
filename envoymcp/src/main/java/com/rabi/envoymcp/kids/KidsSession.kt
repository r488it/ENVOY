package com.rabi.envoymcp.kids

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * まなびの 1 回分 (ひと区切り。ふつうは 5 もん)。端末に触れない (試験する)。
 * - 正しく選んだら星を 1 つ。まちがえたら、その選択肢に印を付けるだけ (しからない。同じ問いをもう一度)
 * - 星が size こたまったら、ひと区切りおしまい
 * - 難しさはやさしく変える: 続けて STREAK_UP もん、1 回で正しく選べたら 1 つ上げる。
 *   まちがいのあった問いが MISS_DOWN もん続いたら 1 つ下げる (いちばん下より下げない)
 * - 題は問いごとに変えられる (AI の先生が題を変えるとき)。難しさは題ごとに覚える
 */
class KidsSession(
    topic: Topic,
    levels: Map<Topic, Int> = emptyMap(),
    val size: Int = DEFAULT_SIZE,
    private val random: Random = Random.Default,
) {
    /** 今の題 */
    var topic: Topic = topic
        private set
    private val level = HashMap<Topic, Int>().apply { levels.forEach { (t, v) -> put(t, v.coerceIn(1, t.maxLevel)) } }
    private val topics = LinkedHashSet<Topic>()
    private val wrongPicks = LinkedHashSet<Int>()

    /** 今の問い (まだ出していなければ null) */
    var current: Question? = null
        private set
    /** 今の問いを、もう正しく選んだか */
    var solved = false
        private set
    /** 今の問いで、まちがえたことがあるか */
    private var missed = false
    /** 出した問いの数・1 回で正しく選べた数・星の数 */
    var asked = 0
        private set
    var firstTry = 0
        private set
    var stars = 0
        private set
    /** 続けて 1 回で正しく選べた数と、続けてまちがいのあった問いの数 */
    private var streak = 0
    private var missRun = 0

    /** 今の問いで、まちがえて押した選択肢 */
    val wrong: Set<Int> get() = wrongPicks.toSet()

    /** ひと区切りおしまい (星が size こたまった) */
    val done: Boolean get() = stars >= size

    fun levelOf(t: Topic): Int = level[t] ?: 1

    /** 題ごとの難しさ (次の回のために残す) */
    fun levels(): Map<Topic, Int> = level.toMap()

    /** 次の問いを出す。t・lv を渡すと、その題・難しさにする (難しさは覚えて、次からも使う) */
    fun next(t: Topic = topic, lv: Int? = null): Question {
        topic = t
        if (lv != null) level[t] = lv.coerceIn(1, t.maxLevel)
        val prev = current
        var q = QuestionMaker.make(t, levelOf(t), random)
        // 同じ問いが続かないように (何回か作り直す)
        repeat(RETRY_SAME) { if (prev != null && q.text == prev.text && q.visual == prev.visual) q = QuestionMaker.make(t, levelOf(t), random) }
        current = q
        solved = false; missed = false
        wrongPicks.clear()
        asked++
        topics += t
        return q
    }

    /** 選んだ結果。levelUp・levelDown は、これで難しさが変わったか */
    data class Outcome(val correct: Boolean, val firstTry: Boolean, val levelUp: Boolean = false, val levelDown: Boolean = false)

    /** 選んだ (i は選択肢の番号)。今の問いが無い・もう正しく選んだ・同じまちがいをもう一度押した・番号が範囲の外なら null */
    fun answer(i: Int): Outcome? {
        val q = current ?: return null
        if (solved || i !in q.choices.indices || i in wrongPicks) return null
        if (i != q.answer) {
            wrongPicks += i
            return wrongOnce()
        }
        solved = true
        stars++
        if (missed) return Outcome(correct = true, firstTry = false)
        firstTry++
        missRun = 0
        streak++
        var up = false
        if (streak >= STREAK_UP) { streak = 0; up = shift(+1) }
        return Outcome(correct = true, firstTry = true, levelUp = up)
    }

    /**
     * 選ぶものに無い答えを言った (声で答えたとき。まちがいとして数え、選択肢には印を付けない)。
     * 今の問題が無い・もう正しく選んだなら null
     */
    fun miss(): Outcome? {
        if (current == null || solved) return null
        return wrongOnce()
    }

    /** まちがえた: その問題で初めてなら、続けて 1 回でできた数を 0 にし、まちがいのあった問題の数を数える (続いたら難しさを下げる) */
    private fun wrongOnce(): Outcome {
        var down = false
        if (!missed) {
            missed = true
            streak = 0
            missRun++
            if (missRun >= MISS_DOWN) { missRun = 0; down = shift(-1) }
        }
        return Outcome(correct = false, firstTry = false, levelDown = down)
    }

    /** 今の題の難しさを 1 つ上げる・下げる (変わったら true) */
    private fun shift(d: Int): Boolean {
        val cur = levelOf(topic)
        val next = (cur + d).coerceIn(1, topic.maxLevel)
        level[topic] = next
        return next != cur
    }

    /** ここまでのまとめ */
    fun summary(): Summary = Summary(topics.toList().ifEmpty { listOf(topic) }, asked, firstTry, stars)

    companion object {
        /** ひと区切りの問いの数 */
        const val DEFAULT_SIZE = 5
        /** 続けて 1 回で正しく選べたら、難しさを上げる数 */
        const val STREAK_UP = 3
        /** まちがいのあった問いが続いたら、難しさを下げる数 */
        const val MISS_DOWN = 2
        private const val RETRY_SAME = 3
    }
}

/** まなびのまとめ: 題・出した問いの数・1 回で正しく選べた数・星の数 (子どもの声や名前は入れない) */
data class Summary(val topics: List<Topic>, val questions: Int, val correct: Int, val stars: Int) {
    /** したことの記録の文 (例: まなび（かず）を 5 もん した（4 もん せいかい）) */
    val sentence: String get() = "まなび（${topics.joinToString("・") { it.label }}）を $questions もん した（$correct もん せいかい）"

    fun toJson(): JSONObject = JSONObject().put("topics", JSONArray(topics.map { it.id }))
        .put("questions", questions).put("correct", correct).put("stars", stars)
}

/**
 * 1 日にまなべる時間を数える。まなびの画面が出ている間 (start〜stop) だけ数え、日が変わったら 0 から数える。
 * today は日を表す文字 (年月日。呼ぶ側が今の日を渡す)。端末に触れない (試験する)
 */
class KidsTimer(usedMs: Long = 0L, day: String = "") {
    private var usedMs = usedMs
    /** 数えている日 */
    var day: String = day
        private set
    /** 数え始めた時刻 (数えていなければ -1) */
    private var since = -1L

    val running: Boolean get() = since >= 0

    /** 数え始める (画面が出た) */
    fun start(now: Long, today: String) {
        roll(now, today)
        if (since < 0) since = now
    }

    /** 数えるのをやめる (画面が隠れた)。それまでの長さを足す */
    fun stop(now: Long, today: String) {
        roll(now, today)
        if (since >= 0) { usedMs += (now - since).coerceAtLeast(0L); since = -1L }
    }

    /** きょう、まなんだ長さ (数えている途中の分も入れる) */
    fun used(now: Long, today: String): Long {
        roll(now, today)
        return usedMs + if (since >= 0) (now - since).coerceAtLeast(0L) else 0L
    }

    /** のこりの長さ (0 より小さくしない) */
    fun left(limitMs: Long, now: Long, today: String): Long = (limitMs - used(now, today)).coerceAtLeast(0L)

    /** 決めた長さに届いたか */
    fun over(limitMs: Long, now: Long, today: String): Boolean = used(now, today) >= limitMs

    /** 0 から数え直す (子どもモードを入れたとき) */
    fun reset(now: Long, today: String) {
        usedMs = 0L
        day = today
        if (since >= 0) since = now
    }

    /** 残す長さ (数えている途中の分は入れない。stop のあとに残す) */
    fun stored(): Long = usedMs

    /** 日が変わったら 0 から (数えている途中なら、変わったところから数える) */
    private fun roll(now: Long, today: String) {
        if (today == day) return
        day = today
        usedMs = 0L
        if (since >= 0) since = now
    }
}

/** おうちの人が決める、まなびの決まり: 1 日の時間 (分)・まなぶ題・AI の先生・画面の固定 */
data class KidsRules(val limitMin: Int, val topics: Set<Topic>, val aiTeacher: Boolean, val pin: Boolean) {
    companion object {
        /** 選べる時間 (分) */
        val LIMITS = listOf(5, 10, 15, 20, 30)
        const val DEFAULT_LIMIT_MIN = 15
    }
}
