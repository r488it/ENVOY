package com.rabi.envoymcp.kids

import com.rabi.envoymcp.voice.ClaudeVoice
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

    /** AI の先生に渡す文: 改行が無く、選んだ題だけと、まなびの道具の名前を入れる */
    @Test fun teacherTexts() {
        val claude = KidsTeacher.claudePrompt(setOf(Topic.TOKEI, Topic.KAZU))
        assertFalse(claude.contains('\n'))
        assertTrue(claude.contains("かず・とけい"))
        assertFalse(claude.contains("ひらがな"))
        assertTrue(claude.contains("kids_start"))
        assertTrue(claude.contains(ClaudeVoice.QUIET_TOOLS))
        assertTrue(claude.endsWith(ClaudeVoice.REPLY_YES))
        val chatgpt = KidsTeacher.chatGptReport(Topic.entries)
        assertTrue(chatgpt.contains("かず・ひらがな・とけい"))
        assertTrue(chatgpt.contains("kids_start"))
        assertTrue(KidsTeacher.INSTRUCTIONS.contains("3 さい"))
        assertTrue(KidsTeacher.CHEERS.contains(KidsTeacher.cheer(Random(3))))
    }
}
