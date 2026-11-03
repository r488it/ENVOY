package com.rabi.envoymcp.arm

import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ボードに Wi-Fi のパスワードを送るときの暗号 (agentarm/linux/agentarm_d/wifi.py と同じ。腕との BLE は暗号化していないため)。
 * X25519 (毎回新しい鍵) で共有の秘密を作り、HKDF-SHA256 で暗号の鍵と確かめの鍵に分け、HMAC-SHA256 の CTR で暗号化し、
 * HMAC-SHA256 (16 バイト) で改ざんを確かめる。頼みの id とネットワーク名も確かめに含める
 */
object WifiCrypto {
    class KeyPair(val secret: ByteArray, val public: ByteArray)

    fun newKey(random: SecureRandom = SecureRandom()): KeyPair = keyOf(ByteArray(32).also { random.nextBytes(it) })

    fun keyOf(secret: ByteArray): KeyPair = KeyPair(secret, ByteArray(32).also { X25519.scalarMultBase(secret, 0, it, 0) })

    /** 共有の秘密 (相手の公開鍵がおかしければ null) */
    fun agree(secret: ByteArray, peer: ByteArray): ByteArray? {
        if (peer.size != 32) return null
        val out = ByteArray(32)
        return if (X25519.calculateAgreement(secret, 0, peer, 0, out, 0)) out else null
    }

    /** 暗号の鍵と確かめの鍵 (各 32 バイト) */
    fun sessionKeys(shared: ByteArray, phonePub: ByteArray, boardPub: ByteArray): Pair<ByteArray, ByteArray> {
        val k = hkdf(shared, phonePub + boardPub, "agentarm-wifi-v1".toByteArray(), 64)
        return k.copyOfRange(0, 32) to k.copyOfRange(32, 64)
    }

    /** パスワードを暗号化する。返すのは (暗号文, 確かめ 16 バイト) */
    fun encrypt(kEnc: ByteArray, kMac: ByteArray, id: String, ssid: String, psk: String, nonce: ByteArray): Pair<ByteArray, ByteArray> {
        val pt = psk.toByteArray()
        val ks = keystream(kEnc, nonce, pt.size)
        val ct = ByteArray(pt.size) { (pt[it].toInt() xor ks[it].toInt()).toByte() }
        val tag = hmac(kMac, "psk|".toByteArray() + id.toByteArray() + "|".toByteArray() + ssid.toByteArray() + "|".toByteArray() + nonce + ct).copyOf(16)
        return ct to tag
    }

    private fun keystream(k: ByteArray, nonce: ByteArray, n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (out.size() < n) {
            out.write(hmac(k, nonce + byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())))
            i++
        }
        return out.toByteArray().copyOf(n)
    }

    private fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray, n: Int): ByteArray {
        val prk = hmac(salt, secret)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < n) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(n)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
