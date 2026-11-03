package com.rabi.envoymcp.arm

/**
 * 腕への頼みの結果 (道具はこれを ok / failed / unknown で返す)。code は failed の理由 (腕の why など)。
 * extra は腕の done に付いた印 (lim: 範囲の端までにした、slow: 速さの上限で遅くした、end:<理由>: 踊りの終わり方)
 */
data class ArmResult(val result: String, val message: String, val code: String = "", val extra: Set<String> = emptySet()) {
    val ok get() = result == "ok"

    companion object {
        fun ok(message: String, extra: Set<String> = emptySet()) = ArmResult("ok", message, extra = extra)
        fun failed(message: String, code: String) = ArmResult("failed", message, code)
        fun unknown(message: String) = ArmResult("unknown", message, "noreply")
    }
}
