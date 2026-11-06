package com.rabi.envoymcp.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** お勉強の問題の作り方 (かず・ひらがな・とけい。難しさごとの範囲と、正しい答え) */
class QuestionMakerTest {
    /** 種を変えて、たくさん作る */
    private fun many(topic: Topic, level: Int, n: Int = 300): List<Question> = (0 until n).map { QuestionMaker.make(topic, level, Random(it)) }

    /** 見せた数 (丸の数か、大きな数字) */
    private fun shown(q: Question): Int = when (val v = q.visual) {
        is Visual.Dots -> v.n
        is Visual.Numeral -> v.n
        else -> { fail("かずの問題に絵がありません"); 0 }
    }

    /** 選ぶものが表す数 (丸の数か、数字) */
    private fun value(c: Choice): Int = if (c.dots > 0) c.dots else c.text.toInt()

    /** どの問題も、正しい答えが選ぶものの中にあり、選ぶものは重ならず 2〜4 こ */
    @Test fun answerIsAmongTheChoices() {
        for (t in Topic.entries) for (lv in 1..t.maxLevel) for (q in many(t, lv, 100)) {
            assertTrue(q.answer in q.choices.indices)
            assertTrue(q.choices.size in 2..4)
            assertEquals(q.choices.size, q.choices.map { it.label }.toSet().size)
            assertEquals(t, q.topic)
            assertEquals(lv, q.level)
            assertTrue(q.text.isNotBlank() && q.speech.isNotBlank())
        }
    }

    /** かず: 正しい答えは見せた数。レベル 1 は 1〜5 (選ぶものは 3 つ)、レベル 2 は 1〜10 (6 より大きい数も出る) */
    @Test fun numbersStayInTheLevelRange() {
        for (q in many(Topic.KAZU, 1)) {
            assertTrue(shown(q) in 1..5)
            assertTrue(q.choices.all { value(it) in 1..5 })
            assertEquals(shown(q), value(q.choices[q.answer]))
            assertEquals(3, q.choices.size)
        }
        val two = many(Topic.KAZU, 2)
        for (q in two) {
            assertTrue(shown(q) in 1..10)
            assertTrue(q.choices.all { value(it) in 1..10 })
            assertEquals(shown(q), value(q.choices[q.answer]))
        }
        assertTrue(two.any { shown(it) > 5 })
        // 2 つの出し方 (丸を数える・数の丸を選ぶ) が、どちらも出る
        assertTrue(two.any { it.visual is Visual.Dots } && two.any { it.visual is Visual.Numeral })
        // 数を見せるときは、丸のまとまりから選ぶ
        two.filter { it.visual is Visual.Numeral }.forEach { q -> assertTrue(q.choices.all { it.dots > 0 }) }
    }

    /** ひらがな: レベル 1 は あ行、2 は か行、3 は さ行 の字だけ。問いの字が正しい答え */
    @Test fun hiraganaUsesTheRowOfTheLevel() {
        mapOf(1 to "あいうえお", 2 to "かきくけこ", 3 to "さしすせそ").forEach { (lv, row) ->
            for (q in many(Topic.HIRAGANA, lv)) {
                assertTrue(q.choices.all { it.text.length == 1 && it.text in row })
                assertTrue(q.text.contains("「${q.answerLabel}」"))
            }
        }
        assertTrue(many(Topic.HIRAGANA, 1).all { it.choices.size == 2 })
        // いちばん上は わ行 (3 文字しかないので、選ぶものも 3 つ)
        many(Topic.HIRAGANA, 10).forEach { q -> assertTrue(q.choices.size == 3 && q.choices.all { it.text in "わをん" }) }
        // 範囲の外の難しさは、いちばん近い難しさにする
        assertEquals(1, QuestionMaker.make(Topic.HIRAGANA, 0, Random(1)).level)
        assertEquals(10, QuestionMaker.make(Topic.HIRAGANA, 99, Random(1)).level)
    }

    /** とけい: レベル 1 は「〜じ」だけ (長い針は 12)。レベル 2 は「〜じはん」も。正しい答えは時計の時刻 */
    @Test fun clockTimes() {
        for (q in many(Topic.TOKEI, 1)) {
            val c = q.visual as Visual.Clock
            assertEquals(0, c.m)
            assertTrue(c.h in 1..12)
            assertEquals("${c.h}じ", q.answerLabel)
            assertTrue(q.choices.none { it.text.endsWith("はん") })
            assertEquals(3, q.choices.size)
        }
        val two = many(Topic.TOKEI, 2)
        assertTrue(two.any { (it.visual as Visual.Clock).m == 30 })
        assertTrue(two.any { (it.visual as Visual.Clock).m == 0 })
        for (q in two) {
            val c = q.visual as Visual.Clock
            assertEquals(QuestionMaker.clockLabel(c.h, c.m), q.answerLabel)
            // 同じ時の「はん」ちがいを混ぜる (見分ける練習)
            assertTrue(q.choices.any { it.text == QuestionMaker.clockLabel(c.h, 30 - c.m) })
        }
        assertEquals("3じはん", QuestionMaker.clockLabel(3, 30))
        assertEquals("12じ", QuestionMaker.clockLabel(12, 0))
    }

    /** 同じ種なら同じ問題 (試験で決まった問題を作れる)。題は名前で引ける */
    @Test fun seededRandomRepeatsAndTopicsById() {
        for (t in Topic.entries) assertEquals(QuestionMaker.make(t, 2, Random(42)), QuestionMaker.make(t, 2, Random(42)))
        assertEquals(Topic.TOKEI, Topic.byId(" TOKEI "))
        assertEquals(Topic.HIRAGANA, Topic.byId("hiragana"))
        assertNull(Topic.byId("eigo"))
    }
}
