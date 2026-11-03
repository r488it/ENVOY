package com.rabi.envoymcp.arm

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * UNO Q の Wi-Fi を、腕との BLE を通して見る・登録する・つなぐ・消す (設定の画面から)。
 * パスワードは鍵を交換してから暗号化して送る (WifiCrypto)。version は腕のプログラムの版 (hello の v)
 */
class ArmWifi(private val link: ArmLink, private val version: () -> Int) {
    /** 集めている net の返事 (頼みの id ごと)。list (saved・scan) は n 件そろうまで、ほかは結果の 1 件 */
    private class NetWait(val op: String, val done: CompletableDeferred<List<JSONObject>>) { val got = ArrayList<JSONObject>() }
    private val netWaits = ConcurrentHashMap<String, NetWait>()
    private var netN = 0

    /** UNO Q の Wi-Fi を扱えるか (つながっていて、腕のプログラムが 7 以降)。だめなら理由 */
    fun check(): String? = when {
        !link.ready -> "腕 (UNO Q) につながっていません"
        version() < 7 -> "UNO Q のプログラムが古いので使えません (agentarm-d を更新してください)"
        else -> null
    }

    /** 腕からの net の返事 */
    fun onNet(m: JSONObject) {
        val w = netWaits[m.optString("id")] ?: return
        if (w.op == "add" && m.optString("op") != "add") return
        synchronized(w) {
            w.got += m
            val n = m.optInt("n", -1)
            val listOp = w.op == "saved" || w.op == "scan"
            if (!listOp || n <= 0 || w.got.size >= n) w.done.complete(w.got.toList())
        }
    }

    /** 頼みを送り、返事を待つ (時間切れ・送れなければ null)。sendOnly の頼みは id をそろえて別に送る */
    private suspend fun ask(op: String, extra: JSONObject = JSONObject(), id: String = "w${++netN}", waitOp: String = op,
                            then: (() -> Boolean)? = null, timeoutMs: Long = NET_TIMEOUT_MS): List<JSONObject>? {
        val w = NetWait(waitOp, CompletableDeferred())
        netWaits[id] = w
        try {
            if (!link.sendCmd(extra.put("t", "net").put("id", id).put("op", op))) return null
            if (then != null && !then()) return null
            return withTimeoutOrNull(timeoutMs) { w.done.await() }
        } finally { netWaits.remove(id) }
    }

    /** 今つながっている Wi-Fi (ssid・ip・sig) */
    suspend fun status(): JSONObject? = ask("status")?.firstOrNull()

    /** 登録してある Wi-Fi (name・ssid) */
    suspend fun saved(): List<JSONObject>? = ask("saved")?.filter { it.has("name") }

    /** 近くの Wi-Fi (ssid・sig・sec。強い順) */
    suspend fun scan(): List<JSONObject>? = ask("scan", timeoutMs = NET_SCAN_TIMEOUT_MS)?.filter { it.has("ssid") }

    suspend fun delete(name: String): Pair<Boolean, String> = result(ask("delete", JSONObject().put("name", name)))

    suspend fun connect(name: String): Pair<Boolean, String> = result(ask("connect", JSONObject().put("name", name), timeoutMs = NET_SCAN_TIMEOUT_MS))

    /**
     * Wi-Fi を登録する。パスワードは、鍵を交換してから暗号化して送る (BLE を盗み聞きされても読めない)。
     * sec: wpa (WPA2 など)・sae (WPA3 だけ)・open (パスワードなし)
     */
    suspend fun add(ssid: String, sec: String, psk: String): Pair<Boolean, String> {
        if (sec == "open") return result(ask("add", JSONObject().put("ssid", ssid).put("sec", sec)))
        val mine = WifiCrypto.newKey()
        val key = ask("key", JSONObject().put("pub", b64(mine.public)))?.firstOrNull()
            ?: return false to "UNO Q から返事がありません"
        val theirs = runCatching { Base64.decode(key.optString("pub"), Base64.NO_WRAP) }.getOrNull()
        val shared = theirs?.let { WifiCrypto.agree(mine.secret, it) } ?: return false to "鍵を交換できませんでした"
        val (kEnc, kMac) = WifiCrypto.sessionKeys(shared, mine.public, theirs)
        val id = "w${++netN}"
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val (ct, tag) = WifiCrypto.encrypt(kEnc, kMac, id, ssid, psk, nonce)
        // ネットワーク名 (add) を送ってから、同じ id で暗号化したパスワード (psk) を送り、登録の結果 (add) を待つ
        return result(ask("add", JSONObject().put("ssid", ssid).put("sec", sec), id = id, waitOp = "add", then = {
            link.sendCmd(JSONObject().put("t", "net").put("id", id).put("op", "psk").put("n", b64(nonce)).put("c", b64(ct)).put("g", b64(tag)))
        }, timeoutMs = NET_SCAN_TIMEOUT_MS))
    }

    private fun result(r: List<JSONObject>?): Pair<Boolean, String> {
        val m = r?.firstOrNull() ?: return false to "UNO Q から返事がありません"
        return m.optBoolean("ok") to m.optString("msg")
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    companion object {
        /** UNO Q の Wi-Fi の返事を待つ長さ (探す・つなぐ・登録は nmcli が遅いので長め) */
        private const val NET_TIMEOUT_MS = 10_000L
        private const val NET_SCAN_TIMEOUT_MS = 30_000L
    }
}
