package com.rabi.envoymcp.arm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 電話番号らしい文字の見分け (NumberMask。リゲルが代わりに電話している間、電話の画面の番号を隠す) */
class NumberMaskTest {
    @Test fun phoneNumbersAreCovered() {
        for (s in listOf("090-1234-5678", "09012345678", "+81 90-1234-5678", "03 (1234) 5678", "携帯 080 1234 5678", "０９０－１２３４－５６７８"))
            assertTrue(s, NumberMask.looksLikeNumber(s))
    }

    @Test fun otherTextIsNotCovered() {
        for (s in listOf("00:05", "10:41", "通話中 00:12", "田中さん", "山本歯科", "2000/01/01", "呼び出し中…", "", null))
            assertFalse(s.toString(), NumberMask.looksLikeNumber(s))
    }
}
