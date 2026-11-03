package com.rabi.envoymcp.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 電話の引き継ぎの文 (CallNotes。リゲルのタップで開いた音声モードの最初の一言) */
class CallNotesTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2000, 1, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(10, 18, 0)

    @Test fun timeIsShortForToday() {
        assertEquals("9:05", CallNotes.whenOf(at(10, 9, 5), now, zone))
        assertEquals("昨日 23:40", CallNotes.whenOf(at(9, 23, 40), now, zone))
        assertEquals("1/3 7:00", CallNotes.whenOf(at(3, 7, 0), now, zone))
    }

    @Test fun nothingToHandOff() = assertNull(CallNotes.handoff(emptyList(), now, zone))

    @Test fun oneNote() {
        val s = CallNotes.handoff(listOf(CallNotes.Note("a", at(10, 15, 42), "田中さんから電話がありました（出られませんでした）。")), now, zone)!!
        assertEquals("（電話の引き継ぎ）15:42、田中さんから電話がありました（出られませんでした）。この内容を、私に短く声で伝えてください。", s)
    }

    @Test fun newestFirst() {
        val s = CallNotes.handoff(listOf(
            CallNotes.Note("old", at(9, 10, 0), "古い。"),
            CallNotes.Note("new", at(10, 17, 30), "新しい。"),
        ), now, zone)!!
        assertTrue(s, s.contains("2 件"))
        assertTrue(s, s.indexOf("1. 17:30、新しい。") in 0 until s.indexOf("2. 昨日 10:00、古い。"))
    }
}
