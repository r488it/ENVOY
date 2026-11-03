package com.rabi.envoymcp.core

/**
 * ログ。発話や宛先などの個人情報・API キーは出さない (数字列・メールは伏せる)。
 * 診断画面用に最近の行をメモリにだけ残す (ファイルには書かない)。
 */
object RabiLog {
    private const val TAG = "Rabi"
    private val ring = ArrayDeque<String>()
    @Volatile var sink: ((String, String) -> Unit)? = null

    fun i(area: String, msg: String) = write("I", area, msg)
    fun w(area: String, msg: String) = write("W", area, msg)

    private fun write(level: String, area: String, msg: String) {
        val line = Text.redact(msg).take(400)
        synchronized(ring) {
            ring.addLast("${System.currentTimeMillis() % 100_000_000}|$level|$area|$line")
            while (ring.size > 300) ring.removeFirst()
        }
        runCatching {
            if (level == "W") android.util.Log.w(TAG, "[$area] $line") else android.util.Log.i(TAG, "[$area] $line")
        }
        sink?.invoke(area, line)
    }

    fun recent(n: Int = 120): List<String> = synchronized(ring) { ring.toList().takeLast(n).reversed() }
}
