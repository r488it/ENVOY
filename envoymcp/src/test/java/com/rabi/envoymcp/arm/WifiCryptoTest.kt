package com.rabi.envoymcp.arm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** 腕のボードのプログラム (agentarm/linux/tests/test_wifi.py の test_known_vector_for_the_phone) と同じ答えになるか */
class WifiCryptoTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun sameAsBoard() {
        val phone = WifiCrypto.keyOf(ByteArray(32) { (it + 1).toByte() })
        val board = WifiCrypto.keyOf(ByteArray(32) { (it + 33).toByte() })
        assertEquals("07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c", hex(phone.public))
        val shared = WifiCrypto.agree(phone.secret, board.public)!!
        assertArrayEquals(shared, WifiCrypto.agree(board.secret, phone.public))
        val (kEnc, kMac) = WifiCrypto.sessionKeys(shared, phone.public, board.public)
        val (ct, tag) = WifiCrypto.encrypt(kEnc, kMac, "w7", "Pixel 10", "secret-pass", ByteArray(12))
        assertEquals("930fcee0c89c77699c22aaeb51008a340286666bcc2e0ef7d41f81", hex(ct + tag))
    }

    @Test fun rfc7748() {
        val a = WifiCrypto.keyOf(hexBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hex(a.public))
    }

    private fun hexBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
