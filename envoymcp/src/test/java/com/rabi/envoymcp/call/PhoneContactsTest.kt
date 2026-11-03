package com.rabi.envoymcp.call

import com.rabi.envoymcp.call.PhoneContacts.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 電話をかける相手を連絡先から探す: 名前・ニックネーム・読み、敬称、表記のゆれ、何人か当たったとき、番号の選び方 */
class PhoneContactsTest {
    private val book = listOf(
        Entry(1, "田中 太郎", "090-1111-2222", "携帯", mobile = true, primary = false, aliases = listOf("タナカタロウ")),
        Entry(1, "田中 太郎", "03-3333-4444", "自宅", mobile = false, primary = false, aliases = listOf("タナカタロウ")),
        Entry(2, "田中 花子", "080-5555-6666", "携帯", mobile = true, primary = false, aliases = listOf("タナカハナコ")),
        Entry(3, "母", "090-7777-8888", "携帯", mobile = true, primary = false, aliases = listOf("お母さん", "ハハ")),
        Entry(4, "Kenji Sato", "+81 90 1234 5678", "勤務先", mobile = false, primary = true),
        Entry(4, "Kenji Sato", "090-0000-1111", "携帯", mobile = true, primary = false),
    )

    @Test fun findsByNameNicknameAndReading() {
        assertEquals(listOf("母"), PhoneContacts.find(book, "お母さん").map { it.name })        // ニックネーム
        assertEquals(listOf("田中 太郎"), PhoneContacts.find(book, "たなかたろう").map { it.name })  // 読み (カタカナ ↔ ひらがな)
        assertEquals(listOf("田中 太郎"), PhoneContacts.find(book, "田中太郎さん").map { it.name })   // 空白・敬称
        assertEquals(listOf("Kenji Sato"), PhoneContacts.find(book, "kenji").map { it.name })     // 大文字・小文字
    }

    @Test fun severalMatchesAreReturnedForAsking() {
        assertEquals(setOf("田中 太郎", "田中 花子"), PhoneContacts.find(book, "田中").map { it.name }.toSet())
        assertTrue(PhoneContacts.find(book, "鈴木").isEmpty())
        assertTrue(PhoneContacts.find(book, "  ").isEmpty())
    }

    @Test fun exactNameWinsOverPartial() {
        val b = book + Entry(5, "母方の叔母", "090-9999-0000", "携帯", mobile = true, primary = false)
        assertEquals(listOf("母"), PhoneContacts.find(b, "母").map { it.name })
    }

    @Test fun picksNumberByTypeThenPrimaryThenMobile() {
        val taro = PhoneContacts.find(book, "田中太郎").single()
        assertEquals("090-1111-2222", PhoneContacts.pick(taro, null)?.number)      // 携帯を優先
        assertEquals("03-3333-4444", PhoneContacts.pick(taro, "home")?.number)
        assertNull(PhoneContacts.pick(taro, "work"))                                // その種類が無い
        val kenji = PhoneContacts.find(book, "Kenji Sato").single()
        assertEquals("+81 90 1234 5678", PhoneContacts.pick(kenji, null)?.number)  // よく使う番号 (既定) を優先
    }

    @Test fun numbersAndTails() {
        assertTrue(PhoneContacts.isNumber("090-1234-5678"))
        assertTrue(PhoneContacts.isNumber("+81 (90) 1234 5678"))
        assertFalse(PhoneContacts.isNumber("田中"))
        assertFalse(PhoneContacts.isNumber("12"))
        assertEquals("5678", PhoneContacts.tail("090-1234-5678"))
        assertEquals("09012345678", PhoneContacts.digits("０９０−１２３４−５６７８"))
    }
}
