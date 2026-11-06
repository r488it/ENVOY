package com.rabi.envoymcp.kids

import com.rabi.envoymcp.kids.KidsAnswer.Heard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 子どもが声で言った答えの比べ方 (言い方のちがいを受け付ける。選ぶものに無い答えはまちがい、答えでないものは分からない) */
class KidsAnswerTest {
    private fun kazu(vararg values: Int, answer: Int, dots: Boolean = false) =
        Question(Topic.KAZU, 2, "いくつ あるかな？", "いくつ あるかな？", Visual.Dots(values[answer]),
            values.map { if (dots) Choice("$it", dots = it) else Choice("$it") }, answer)

    private fun kana(vararg letters: String, answer: Int) =
        Question(Topic.HIRAGANA, 1, "「${letters[answer]}」は どれ？", "「${letters[answer]}」は どれかな？", Visual.None, letters.map { Choice(it) }, answer)

    private fun clock(vararg labels: String, answer: Int) =
        Question(Topic.TOKEI, 2, "なんじ？", "なんじ かな？", Visual.Clock(3, 30), labels.map { Choice(it) }, answer)

    private fun judge(q: Question, said: String) = KidsAnswer.judge(q, said)

    /** かず: 数字・全角・よみ・ものの数え方・漢数字・「こ」「です」「だよ」付き */
    @Test fun numbersInManyWays() {
        val q = kazu(2, 3, 5, 10, answer = 1)
        for (said in listOf("3", "３", "さん", "サン", "みっつ", "三", "3こ", "さんこ", "3個", "3つ", "3 だよ", "さんです", "みっつ！"))
            assertEquals(said, Heard.Choice(1), judge(q, said))
        for (said in listOf("じゅう", "とお", "10", "十", "じゅっこ")) assertEquals(said, Heard.Choice(3), judge(q, said))
        for (said in listOf("に", "ふたつ", "2こ", "二")) assertEquals(said, Heard.Choice(0), judge(q, said))
        for (said in listOf("ご", "いつつ", "5")) assertEquals(said, Heard.Choice(2), judge(q, said))
        // 選ぶものに無い数は、まちがい
        for (said in listOf("なな", "しち", "7", "ななこ")) assertEquals(said, Heard.Other("7"), judge(q, said))
        // 数でないものは、分からない
        for (said in listOf("", "  ", "りんご", "わかんない", "えーと")) assertEquals(said, Heard.Unclear, judge(q, said))
        // 丸のまとまりから選ぶ問題も、数で答えられる
        val groups = kazu(1, 4, 6, answer = 1, dots = true)
        assertEquals(Heard.Choice(1), judge(groups, "よっつ"))
        assertEquals(Heard.Choice(2), judge(groups, "6 こ"))
    }

    /** ひらがな: 1 文字 (カタカナ・のばす音・かっこ・「だよ」付きも) */
    @Test fun lettersInManyWays() {
        val q = kana("あ", "い", "う", answer = 2)
        for (said in listOf("う", "ウ", "うー", "「う」", "う！", "うだよ")) assertEquals(said, Heard.Choice(2), judge(q, said))
        assertEquals(Heard.Choice(0), judge(q, "あ"))
        assertEquals(Heard.Other("か"), judge(q, "か"))
        for (said in listOf("うさぎ", "a", "わからない")) assertEquals(said, Heard.Unclear, judge(q, said))
    }

    /** とけい: 「〜じ」「〜時」「〜じはん」「〜時半」「〜:30」「〜時30分」・よみ (よじ・くじ・しちじ)・数だけ */
    @Test fun clockTimesInManyWays() {
        val q = clock("3じ", "3じはん", "4じ", answer = 1)
        for (said in listOf("3じはん", "さんじはん", "3時半", "3:30", "3時30分", "さんじさんじゅっぷん", "3じ30ぷん", "サンジハン", "さんはん"))
            assertEquals(said, Heard.Choice(1), judge(q, said))
        for (said in listOf("3じ", "さんじ", "3時", "3:00", "さん", "三時", "さんじちょうど", "3じだよ")) assertEquals(said, Heard.Choice(0), judge(q, said))
        for (said in listOf("よじ", "4じ", "よん")) assertEquals(said, Heard.Choice(2), judge(q, said))
        assertEquals(Heard.Other("9じ"), judge(q, "くじ"))
        assertEquals(Heard.Other("12じ"), judge(q, "じゅうにじ"))
        assertEquals(Heard.Other("11じはん"), judge(q, "じゅういちじはん"))
        assertEquals(Heard.Other("7じ"), judge(q, "しちじ"))
        assertEquals(Heard.Other("7じ"), judge(q, "ななじ"))
        for (said in listOf("13じ", "おひる", "3じ15ふん")) assertEquals(said, Heard.Unclear, judge(q, said))
    }

    /** 題の言い方 (題を選ぶとき) */
    @Test fun topicsByName() {
        assertEquals(Topic.TOKEI, KidsAnswer.topic("とけい"))
        assertEquals(Topic.TOKEI, KidsAnswer.topic("トケイがいい"))
        assertEquals(Topic.HIRAGANA, KidsAnswer.topic("ひらがな！"))
        assertEquals(Topic.KAZU, KidsAnswer.topic("かず"))
        assertEquals(Topic.KAZU, KidsAnswer.topic("kazu"))
        assertEquals(Topic.KAZU, KidsAnswer.topic("すうじ"))
        assertNull(KidsAnswer.topic("おなかすいた"))
    }
}
