package com.rabi.envoymcp.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** press_key: 選ばれているボタンを押してしまうキーは断り、動画のキーは送る */
class PressKeyTest {
    private fun code(raw: String) = (PressKey.resolve(raw) as? PressKey.Code)?.code

    @Test fun confirmKeysAreRefused() {
        for (k in listOf("space", "spacebar", "enter", "return", "numpad_enter", "dpad_center", "center", "select",
            "button_a", "button_start", "keycode_space", "keycode_enter", "keycode_dpad_center", "keycode_button_a")) {
            val r = PressKey.resolve(k)
            assertTrue(k, r is PressKey.Refused)
            // 動画の一時停止のやり方を返す
            assertTrue(k, (r as PressKey.Refused).why.contains("play_pause"))
        }
    }

    @Test fun videoAndMoveKeysAreSent() {
        assertEquals("KEYCODE_K", code("k"))
        assertEquals("KEYCODE_F", code("f"))
        assertEquals("KEYCODE_M", code("m"))
        assertEquals("KEYCODE_MEDIA_PLAY_PAUSE", code("play_pause"))
        assertEquals("KEYCODE_VOLUME_UP", code("volume_up"))
        assertEquals("KEYCODE_TAB", code("tab"))
        assertEquals("KEYCODE_DPAD_RIGHT", code("right"))
        assertEquals("KEYCODE_ESCAPE", code("esc"))
        assertEquals("KEYCODE_F5", code("f5"))
        assertEquals("KEYCODE_7", code("7"))
        assertEquals("KEYCODE_K", code("keycode_k"))
    }

    @Test fun unknownKeysAreRefused() {
        for (k in listOf("", "ctrl", "f13", "ab", "あ")) assertTrue(k, PressKey.resolve(k) is PressKey.Refused)
    }
}
