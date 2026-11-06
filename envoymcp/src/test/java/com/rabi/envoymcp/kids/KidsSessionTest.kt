package com.rabi.envoymcp.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** お勉強のひと区切り (星・やさしい難しさの変え方)・1 日の時間・AI の先生に渡す文 */
class KidsSessionTest {
    private fun session(size: Int = 5, levels: Map<Topic, Int> = emptyMap(), topic: Topic = Topic.KAZU) = KidsSession(topic, levels, size, Random(7))

    /** まちがいの選択肢 (どれか 1 つ) */
    private fun wrongOf(q: Question) = q.choices.indices.first { it != q.answer }

    /** 正しく選ぶと星が 1 つ。size こたまったら、ひと区切りおしまい */
    @Test fun starsAndRound() {
        val s = session(size = 3)
        repeat(3) {
            assertFalse(s.done)
            val q = s.next()
            val o = s.answer(q.answer)!!
            assertTrue(o.correct && o.firstTry)
        }
        assertTrue(s.done)
        assertEquals(Summary(listOf(Topic.KAZU), 3, 3, 3), s.summary())
        assertEquals("まなび（かず）を 3 もん した（3 もん せいかい）", s.summary().sentence)
        assertEquals(5, KidsSession.DEFAULT_SIZE)
    }

    /** まちがえても星は減らず、しからない (その選択肢に印を付けるだけ)。同じ問題でもう一度選べる */
    @Test fun wrongAnswerOnlyMarksTheChoice() {
        val s = session()
        assertNull(s.answer(0))   // まだ問題を出していない
        val q = s.next()
        val w = wrongOf(q)
        val o = s.answer(w)!!
        assertFalse(o.correct)
        assertEquals(setOf(w), s.wrong)
        assertEquals(0, s.stars)
        assertNull(s.answer(w))              // 同じまちがいをもう一度押しても数えない
        assertNull(s.answer(q.choices.size)) // 範囲の外
        val ok = s.answer(q.answer)!!
        assertTrue(ok.correct)
        assertFalse(ok.firstTry)
        assertEquals(1, s.stars)
        assertEquals(0, s.firstTry)
        assertNull(s.answer(q.answer))       // 正しく選んだあとは数えない
        assertEquals("まなび（かず）を 1 もん した（0 もん せいかい）", s.summary().sentence)
        // 次の問題では、印は消える
        s.next()
        assertTrue(s.wrong.isEmpty())
    }

    /** 続けて 3 もん 1 回で正しく選べたら、難しさを 1 つ上げる (いちばん上より上げない) */
    @Test fun levelGoesUpAfterAStreak() {
        val s = session()
        assertEquals(1, s.levelOf(Topic.KAZU))
        var up = false
        repeat(KidsSession.STREAK_UP) { val q = s.next(); up = s.answer(q.answer)!!.levelUp }
        assertTrue(up)
        assertEquals(2, s.levelOf(Topic.KAZU))
        assertEquals(2, s.next().level)
        // かずはレベル 2 まで
        repeat(KidsSession.STREAK_UP * 2) { val q = s.next(); s.answer(q.answer) }
        assertEquals(2, s.levelOf(Topic.KAZU))
        assertEquals(mapOf(Topic.KAZU to 2), s.levels())
    }

    /** まちがいのあった問題が 2 もん続いたら、難しさを 1 つ下げる (1 より下げない)。間に 1 回でできた問題があれば数え直す */
    @Test fun levelGoesDownGently() {
        val s = session(levels = mapOf(Topic.HIRAGANA to 3), topic = Topic.HIRAGANA)
        fun missThenSolve(): KidsSession.Outcome {
            val q = s.next()
            val o = s.answer(wrongOf(q))!!
            s.answer(q.answer)
            return o
        }
        assertFalse(missThenSolve().levelDown)
        assertEquals(3, s.levelOf(Topic.HIRAGANA))
        assertTrue(missThenSolve().levelDown)
        assertEquals(2, s.levelOf(Topic.HIRAGANA))
        missThenSolve()
        s.next().let { s.answer(it.answer) }
        missThenSolve()
        assertEquals(2, s.levelOf(Topic.HIRAGANA))
        val low = session()
        repeat(4) { val q = low.next(); low.answer(wrongOf(q)); low.answer(q.answer) }
        assertEquals(1, low.levelOf(Topic.KAZU))
        // まちがえても、まちがえた回数で星は減らない
        assertEquals(4, low.stars)
    }

    /** 題と難しさは問題ごとに変えられる (AI の先生)。まとめには出した題を並べる */
    @Test fun topicAndLevelCanChange() {
        val s = session()
        s.next()
        val q = s.next(Topic.TOKEI, 2)
        assertEquals(Topic.TOKEI, q.topic)
        assertEquals(2, q.level)
        assertEquals(Topic.TOKEI, s.topic)
        assertEquals(2, s.levelOf(Topic.TOKEI))
        assertEquals(listOf(Topic.KAZU, Topic.TOKEI), s.summary().topics)
        assertEquals("まなび（かず・とけい）を 2 もん した（0 もん せいかい）", s.summary().sentence)
        // 範囲の外の難しさは、範囲に収める
        assertEquals(2, s.next(Topic.KAZU, 9).level)
        assertEquals(1, KidsSession(Topic.TOKEI, mapOf(Topic.TOKEI to -3)).levelOf(Topic.TOKEI))
    }

    /** 時間: 画面が出ている間だけ数え、日が変わったら 0 から。決めた長さで止める。入れ直したら 0 から */
    @Test fun timerCountsShownTimePerDay() {
        val min = 60_000L
        val t = KidsTimer(0L, "d1")
        t.start(0L, "d1")
        assertTrue(t.running)
        assertEquals(5 * min, t.used(5 * min, "d1"))
        t.stop(10 * min, "d1")
        assertEquals(10 * min, t.used(99 * min, "d1"))   // 隠れている間は数えない
        assertFalse(t.over(15 * min, 99 * min, "d1"))
        assertEquals(5 * min, t.left(15 * min, 99 * min, "d1"))
        t.start(100 * min, "d1")
        assertTrue(t.over(15 * min, 105 * min, "d1"))
        assertEquals(0L, t.left(15 * min, 106 * min, "d1"))
        t.stop(106 * min, "d1")
        assertEquals(16 * min, t.stored())
        // 残した長さから続けて数える (アプリを開き直したとき)
        assertTrue(KidsTimer(t.stored(), t.day).over(15 * min, 0L, "d1"))
        // 次の日は 0 から
        assertEquals(0L, t.used(200 * min, "d2"))
        assertFalse(t.over(15 * min, 200 * min, "d2"))
        // 入れ直したら 0 から (数えている途中でも)
        t.start(300 * min, "d2")
        t.reset(302 * min, "d2")
        assertEquals(1 * min, t.used(303 * min, "d2"))
    }

    /**
     * AI の先生に渡す文: 改行が無く、選んだ題だけを入れる。会話しながらテンポよく (1〜2 文・少し速め・問題を会話に入れる)。
     * 待つ道具は無いので、すぐ話してよい。最初は topic なしの kids_start で「どれにする？」。声の答えは kids_answer(answer=…)、押したかは kids_answer()
     */
    @Test fun teacherTexts() {
        val two = KidsTeacher.chatGptReport(setOf(Topic.TOKEI, Topic.KAZU))
        assertFalse(two.contains('\n'))
        assertTrue(two.contains("かず・とけい"))
        assertFalse(two.contains("ひらがな"))
        val chatgpt = KidsTeacher.chatGptReport(Topic.entries)
        assertTrue(chatgpt.contains("かず・ひらがな・とけい"))
        for (text in listOf(chatgpt, KidsTeacher.INSTRUCTIONS)) {
            assertTrue(text.contains("会話しながら"))
            assertTrue(text.contains("1〜2 文"))
            assertTrue(text.contains("少し速め"))
            assertTrue(text.contains("りんごが いくつ あるかな？ りんご すき？"))
            assertTrue(text.contains("すぐ返ります"))
            assertTrue(text.contains("topic なしで kids_start"))
            assertTrue(text.contains("どれにする？"))
            assertTrue(text.contains("kids_answer(answer="))
            assertTrue(text.contains("kids_answer()"))
            assertTrue(text.contains("end_voice_chat"))
            // 待つ道具の使い方は書かない
            assertFalse(text.contains("kids_question") || text.contains("same=true") || text.contains("wait=false"))
        }
        assertTrue(KidsTeacher.INSTRUCTIONS.contains("3 さい"))
        assertTrue(KidsTeacher.INSTRUCTIONS.contains("kids_start・kids_answer・kids_cheer・kids_end"))
        assertTrue(KidsTeacher.CHEERS.contains(KidsTeacher.cheer(Random(3))))
    }

    /** 声で、選ぶものに無い答えを言った: まちがいとして数え (星は増えない)、選択肢には印を付けない。続いたら難しさを下げる */
    @Test fun missWithoutAChoice() {
        val s = session(levels = mapOf(Topic.KAZU to 2))
        assertNull(s.miss())          // まだ問題を出していない
        val q = s.next()
        assertFalse(s.miss()!!.correct)
        assertTrue(s.wrong.isEmpty())
        assertEquals(0, s.stars)
        assertFalse(s.answer(q.answer)!!.firstTry)
        assertNull(s.miss())          // 正しく選んだあとは数えない
        val q2 = s.next()
        assertTrue(s.miss()!!.levelDown)   // まちがいのあった問題が 2 もん続いた
        assertEquals(1, s.levelOf(Topic.KAZU))
        assertEquals(q2, s.current)
    }

    /** まちがえたときのヒント: 答えそのものは言わない。ひらがなは、その字のことば */
    @Test fun hintsDoNotTellTheAnswer() {
        for (t in Topic.entries) for (lv in 1..t.maxLevel) repeat(30) { seed ->
            val q = QuestionMaker.make(t, lv, Random(seed))
            val h = KidsTeacher.hint(q)
            assertTrue(h.isNotBlank())
            if (t == Topic.TOKEI) assertFalse(h, h.contains(q.answerLabel))
            if (t == Topic.HIRAGANA) assertTrue(h, h.startsWith("「${q.answerLabel}」は「"))
        }
        val apples = Question(Topic.KAZU, 1, "いくつ あるかな？", "いくつ あるかな？", Visual.Dots(3, 0), listOf(Choice("2"), Choice("3")), 1)
        assertEquals("りんごを ゆびで ひとつずつ かぞえてみよう", KidsTeacher.hint(apples))
        assertEquals("りんごが 3 こ", apples.shown)
        assertEquals("いくつ あるかな？", apples.toJson().getString("read"))
    }
}
