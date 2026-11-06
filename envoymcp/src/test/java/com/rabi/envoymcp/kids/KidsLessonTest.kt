package com.rabi.envoymcp.kids

import com.rabi.envoymcp.core.Expression
import com.rabi.envoymcp.kids.KidsLesson.AiResult
import com.rabi.envoymcp.kids.KidsLesson.Mode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * お勉強の進み方 (AI の先生の道具は、どれも子どもを待たずに返る。1 問に 1 回。声でも画面でも答えられる。最初に題を選ぶ)。
 * ひとりでのお勉強は、押して答えて、少ししてから次の問題
 */
class KidsLessonTest {
    /** まわりの偽物 (設定・したこと・表情・画面・時計) */
    private class Host : KidsHost {
        override var kidMode = true
        override var topics: Set<Topic> = Topic.entries.toSet()
        override var limitMin = 15
        val levels = HashMap<Topic, Int>()
        override fun level(t: Topic) = levels[t] ?: 1
        override fun saveLevel(t: Topic, level: Int) { levels[t] = level }
        override var usedMs = 0L
        override var usedDay = ""
        val logs = ArrayList<String>()
        override fun log(who: String, tool: String, sentence: String) { logs += "$who|$tool|$sentence" }
        val expressions = ArrayList<Expression>()
        override fun express(e: Expression) { expressions += e }
        /** 開いた画面 (true: AI の先生の画面 = 重ねる窓、false: ひとりの画面) */
        val opened = ArrayList<Boolean>()
        override fun open(ai: Boolean) { opened += ai }
        var clock = 0L
        override fun now() = clock
        override fun today() = "d1"
    }

    private val host = Host()
    private fun TestScope.lesson() = KidsLesson(host, backgroundScope, Random(5)).also { it.noteTeacher("ChatGPT") }
    private fun TestScope.later(ms: Long) { advanceTimeBy(ms); runCurrent() }
    private fun wrongOf(q: Question) = q.choices.indices.first { it != q.answer }

    /** topic なしの kids_start: 題のボタンを出して、すぐ返す。画面で押したら、その題で最初の問題を出し、次の kids_answer() で分かる */
    @Test fun startWithoutTopicShowsThePickerAtOnce() = runTest {
        val l = lesson()
        assertEquals(AiResult.Picking(Topic.entries), l.startAi(null, null))
        assertEquals(Mode.PICK, l.view.value.mode)
        assertTrue(l.view.value.ai)
        assertEquals(listOf(true), host.opened)
        // まだ選んでいない
        assertEquals(AiResult.Picking(Topic.entries), l.answerAi(null))
        l.pickTopic(Topic.TOKEI)
        val q = l.view.value.question!!
        assertEquals(Topic.TOKEI, q.topic)
        assertEquals(Mode.ASK, l.view.value.mode)
        val c = l.answerAi(null) as AiResult.Checked
        assertEquals(Topic.TOKEI, c.picked)
        assertSame(q, c.question)
        assertTrue(c.answers.isEmpty())
        // 次は「まだ」(題はもう伝えた)
        val w = l.answerAi(null) as AiResult.Checked
        assertNull(w.picked)
        assertSame(q, w.question)
    }

    /** 子どもが声で題を言った (kids_answer に題の名前を渡しても始まる)。おうちの人が選んだ題だけ */
    @Test fun topicByVoiceAndOnlyEnabledTopics() = runTest {
        host.topics = setOf(Topic.KAZU, Topic.TOKEI)
        val l = lesson()
        assertEquals(AiResult.Picking(listOf(Topic.KAZU, Topic.TOKEI)), l.startAi(null, null))
        assertEquals(AiResult.NotEnabled(listOf(Topic.KAZU, Topic.TOKEI)), l.answerAi("ひらがな"))
        assertEquals(AiResult.NotEnabled(listOf(Topic.KAZU, Topic.TOKEI)), l.startAi(Topic.HIRAGANA, null))
        val c = l.answerAi("とけいが いい") as AiResult.Checked
        assertEquals(Topic.TOKEI, c.picked)
        assertEquals(Topic.TOKEI, c.question!!.topic)
        // AI が題を決めて始める: 最初の問題をすぐ返す
        val s = l.startAi(Topic.KAZU, 2) as AiResult.Started
        assertEquals(Topic.KAZU, s.question.topic)
        assertEquals(2, s.question.level)
        assertSame(s.question, l.view.value.question)
    }

    /** 声で答える: 1 問に 1 回の呼び出しで、正しければ次の問題も返す。画面は星を見せてから次の問題。5 もんで、まとめとおわりの画面 */
    @Test fun oneCallPerQuestionByVoice() = runTest {
        val l = lesson()
        var q = (l.startAi(Topic.KAZU, null) as AiResult.Started).question
        repeat(KidsSession.DEFAULT_SIZE) { i ->
            val r = l.answerAi(q.answerLabel) as AiResult.Checked
            val a = r.answers.single()
            assertTrue(a.correct && a.byVoice && a.firstTry)
            // 画面: すぐ星と、うれしい顔 (腕も)
            assertEquals(q.answer, l.view.value.right)
            assertEquals(Expression.HAPPY, host.expressions.last())
            if (i < KidsSession.DEFAULT_SIZE - 1) {
                val next = r.question!!
                assertNull(r.summary)
                assertEquals(i + 2, r.progress.asked)
                assertSame(q, l.view.value.question)          // まだ星を見せている
                later(KidsLesson.AI_NEXT_MS)
                assertSame(next, l.view.value.question)       // 少しして次の問題
                q = next
            } else {
                assertNull(r.question)
                assertEquals(Summary(listOf(Topic.KAZU), 5, 5, 5), r.summary)
                later(KidsLesson.AI_NEXT_MS)
                assertEquals(Mode.END, l.view.value.mode)
            }
        }
        assertEquals(listOf("ChatGPT|kids_lesson|まなび（かず）を 5 もん した（5 もん せいかい）"), host.logs.filter { "kids_lesson" in it })
    }

    /** 声のまちがい: 同じ問題のまま、ヒントを返す。選ぶものに無い答えもまちがい。分からない答えは数えない */
    @Test fun wrongVoiceAnswerKeepsTheQuestionWithAHint() = runTest {
        val l = lesson()
        val q = (l.startAi(Topic.HIRAGANA, null) as AiResult.Started).question
        val w = wrongOf(q)
        val r = l.answerAi(q.choices[w].label) as AiResult.Checked
        assertFalse(r.answers.single().correct)
        assertSame(q, r.question)
        assertEquals(KidsTeacher.hint(q), r.hint)
        assertEquals(KidsTeacher.RETRY, l.view.value.message)
        assertEquals(setOf(w), l.view.value.wrong)
        val other = l.answerAi("ん") as AiResult.Checked
        assertEquals("ん", other.answers.single().chosen)
        assertFalse(other.answers.single().correct)
        val unclear = l.answerAi("うさぎ") as AiResult.Checked
        assertEquals("うさぎ", unclear.unclear)
        assertTrue(unclear.answers.isEmpty())
        assertNull(unclear.hint)
        assertSame(q, unclear.question)
        // 言い直して正しい: 次の問題 (1 回でできた数には入れない)
        val ok = l.answerAi(q.answerLabel) as AiResult.Checked
        assertTrue(ok.answers.single().correct)
        assertFalse(ok.answers.single().firstTry)
        assertNotSame(q, ok.question)
        assertEquals(1, ok.progress.stars)
    }

    /** 画面で押した: すぐ星と顔。少ししてから次の問題。AI には次の kids_answer() で伝わる (押したもの・次の問題)。そのあとは「まだ」 */
    @Test fun screenTapsAreToldOnTheNextCall() = runTest {
        val l = lesson()
        val q = (l.startAi(Topic.KAZU, null) as AiResult.Started).question
        l.tap(q.answer)
        assertEquals(q.answer, l.view.value.right)
        assertEquals(1, l.view.value.cheerSeq)
        assertEquals(Expression.HAPPY, host.expressions.last())
        later(KidsLesson.AI_NEXT_MS - 1)
        assertSame(q, l.view.value.question)
        later(1)
        val next = l.view.value.question!!
        assertNotSame(q, next)
        val r = l.answerAi(null) as AiResult.Checked
        val a = r.answers.single()
        assertTrue(a.correct)
        assertFalse(a.byVoice)
        assertEquals(q.answerLabel, a.chosen)
        assertSame(next, r.question)
        val w = l.answerAi(null) as AiResult.Checked
        assertTrue(w.answers.isEmpty())
        assertSame(next, w.question)
    }

    /** 押してすぐ AI が確かめたら、星を待たずに次の問題を出す。続けて押したものは、まとめて伝える */
    @Test fun askingRightAfterATapShowsTheNextQuestionAtOnce() = runTest {
        val l = lesson()
        val q = (l.startAi(Topic.TOKEI, null) as AiResult.Started).question
        l.tap(wrongOf(q))
        assertEquals(KidsTeacher.RETRY, l.view.value.message)
        l.tap(q.answer)
        val r = l.answerAi(null) as AiResult.Checked
        assertEquals(listOf(false, true), r.answers.map { it.correct })
        assertFalse(r.answers.last().firstTry)
        assertSame(r.question, l.view.value.question)
        assertEquals(Mode.ASK, l.view.value.mode)
    }

    /** 画面で先に正しく押していたら、あとから来た声の答えは、次の問題とは比べない */
    @Test fun voiceAfterAScreenAnswerIsNotCounted() = runTest {
        val l = lesson()
        val q = (l.startAi(Topic.KAZU, null) as AiResult.Started).question
        l.tap(q.answer)
        val r = l.answerAi(q.answerLabel) as AiResult.Checked
        assertEquals(q.answerLabel, r.skipped)
        assertEquals(1, r.answers.size)
        assertEquals(2, r.progress.asked)
        assertEquals(1, r.progress.stars)
    }

    /** おしまい (kids_end): まとめを返して、おわりの画面。したことに残す */
    @Test fun endReturnsTheSummary() = runTest {
        val l = lesson()
        var q = (l.startAi(Topic.KAZU, null) as AiResult.Started).question
        repeat(2) { q = (l.answerAi(q.answerLabel) as AiResult.Checked).question!! }
        val e = l.endAi() as AiResult.Ended
        assertEquals(Summary(listOf(Topic.KAZU), 3, 2, 2), e.summary)
        assertEquals(Mode.END, l.view.value.mode)
        assertTrue(host.logs.contains("ChatGPT|kids_lesson|まなび（かず）を 3 もん した（2 もん せいかい）"))
        assertEquals(AiResult.NotStarted, KidsLesson(host, backgroundScope).answerAi(null))
    }

    /** 時間になったら「きょうは ここまで」(道具は time_up を返す)。子どもモードがオフなら、始めるときにオンにする */
    @Test fun timeUpAndKidMode() = runTest {
        host.kidMode = false
        host.limitMin = 1
        val l = lesson()
        l.startAi(Topic.KAZU, null)
        assertTrue(host.kidMode)
        assertTrue(host.logs.contains("ChatGPT|kids_mode|子どもモードを入れた"))
        l.shown()
        host.clock = 61_000L
        assertEquals(AiResult.TimeUp, l.answerAi("3"))
        assertEquals(Mode.TIME_UP, l.view.value.mode)
        assertEquals(AiResult.TimeUp, l.startAi(null, null))
        assertEquals(0, l.minutesLeft())
    }

    /** ひとりで: 押して答え、リゲルが読み上げる。正しければ、ほめる言葉のあと少ししてから次の問題。AI の道具は効かない */
    @Test fun aloneReadsAndWaitsALittle() = runTest {
        val l = lesson()
        l.pick()
        assertEquals(Mode.PICK, l.view.value.mode)
        assertFalse(l.view.value.ai)
        assertEquals(KidsTeacher.PICK, l.view.value.speech)
        l.pickTopic(Topic.KAZU)
        val q = l.view.value.question!!
        assertEquals(q.speech, l.view.value.speech)
        l.tap(q.answer)
        assertTrue(l.view.value.speech in KidsTeacher.CHEERS)
        later(KidsLesson.NEXT_MS - 1)
        assertSame(q, l.view.value.question)
        later(1)
        val next = l.view.value.question!!
        assertNotSame(q, next)
        assertEquals(next.speech, l.view.value.speech)
        assertEquals(AiResult.NotStarted, l.answerAi(null))
        assertNotNull(l.view.value.question)
    }

    /**
     * リゲルのタップで始めるとき: ChatGPT を開く前に、題を選ぶ画面を AI の先生の画面 (重ねる窓) で出しておく。
     * そのあと ChatGPT が kids_start を呼んでも、子どもがもう選んでいれば選び直させない (選んだ題と最初の問題を伝える)
     */
    @Test fun pickerIsReadyBeforeTheTeacherSpeaks() = runTest {
        val l = lesson()
        assertEquals(AiResult.Picking(Topic.entries), l.startAi(null, null))   // openKids が ChatGPT を開く前に呼ぶ
        assertEquals(listOf(true), host.opened)
        l.pickTopic(Topic.HIRAGANA)
        val q = l.view.value.question!!
        val c = l.startAi(null, null) as AiResult.Checked
        assertEquals(Topic.HIRAGANA, c.picked)
        assertSame(q, c.question)
        assertEquals(Mode.ASK, l.view.value.mode)
        // まだ選んでいないときに kids_start を呼んでも、題を選ぶ画面のまま
        l.startAi(null, null)
        assertEquals(AiResult.Picking(Topic.entries), l.startAi(null, null))
        assertEquals(Mode.PICK, l.view.value.mode)
    }

    /** 開く画面: AI の先生は重ねる窓 (時間切れのときも)、ひとりはお勉強の画面 */
    @Test fun teacherOpensTheOverlayAndAloneOpensTheScreen() = runTest {
        val l = lesson()
        l.openAlone()
        assertEquals(listOf(false), host.opened)
        assertFalse(l.view.value.ai)
        l.startAi(Topic.TOKEI, null)
        assertEquals(listOf(false, true), host.opened)
        assertTrue(l.view.value.ai)
        host.limitMin = 1
        l.shown()
        host.clock = 61_000L
        assertEquals(AiResult.TimeUp, l.startAi(null, null))
        assertEquals(listOf(false, true, true), host.opened)
        assertEquals(Mode.TIME_UP, l.view.value.mode)
    }
}
