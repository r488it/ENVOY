package com.rabi.envoymcp.arm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallInfoTest {
    private val start = CallInfo("id", "in", "line", "佐藤さん", startedAt = 1_000L)

    @Test fun wordsAreMergedUntilFinished() {
        // 相手の言葉が少しずつ届き、リゲルが答え、相手がまた話す
        val c = start
            .withText("peer", "もし", false, 2_000L)
            .withText("peer", "もしもし", false, 2_100L)
            .withText("rigel", "AI アシスタントの", false, 2_200L)
            .withText("peer", "もしもし、佐藤です", true, 2_300L)
            .withText("rigel", "AI アシスタントのリゲルです。", true, 2_400L)
            .withText("peer", "明日の件で", false, 3_000L)
        assertEquals(listOf("peer", "rigel", "peer"), c.lines.map { it.who })
        assertEquals(listOf("もしもし、佐藤です", "AI アシスタントのリゲルです。", "明日の件で"), c.lines.map { it.text })
        assertEquals(listOf(true, true, false), c.lines.map { it.final })
        assertEquals(2_000L, c.lines[0].at)               // 行の時刻は、言い始めた時刻
        assertEquals(2_000L, c.answeredAt)                // 「話している」の知らせが無くても、言葉が来たら通話中
        assertEquals("peer", c.lastWho)
    }

    @Test fun blankOrUnknownWordsAreIgnored() {
        assertEquals(start, start.withText("peer", " ", false, 2_000L))
        assertEquals(start, start.withText("someone", "やあ", true, 2_000L))
    }

    @Test fun jsonRoundTrip() {
        val c = start.copy(message = "", answeredAt = 1_500L, endedAt = 65_500L, outcome = "message", summary = "明日 11 時にしたい", audio = "id.m4a")
            .withText("rigel", "リゲルです。", true, 1_600L).withText("peer", "佐藤です", true, 1_700L)
        val back = CallInfo.of(JSONObject(c.toJson().toString()))
        assertEquals(c.copy(state = "done", lastTextAt = 0L, lastWho = ""), back)
        assertEquals(64L, back.talkSec)
        assertTrue(back.ended)
    }
}
