package com.rabi.envoymcp.device

import com.rabi.envoymcp.observe.Observation
import com.rabi.envoymcp.parts.IntentSpec

/**
 * 端末の画面操作。実装は ADB (AdbDeviceController)。試験では FakeDevice。
 * AI モデルはこれを直接持たない。操作の提案は ActionGate / PartRunner を通って届く。
 * 操作は 1 本ずつ順に実行する (実装側で直列化する)。
 */
interface DeviceController {
    val isConnected: Boolean
    suspend fun observe(screenshot: Boolean = false): Observation
    suspend fun launchApp(pkg: String): ActionResult
    suspend fun startIntent(spec: IntentSpec): ActionResult
    suspend fun tap(x: Int, y: Int): ActionResult
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int = 350): ActionResult
    suspend fun back(): ActionResult
    suspend fun home(): ActionResult
    /** 文字入力 (日本語は端末で確かめた方式で)。入力後に画面で確かめるのは呼ぶ側 */
    suspend fun typeText(text: String): ActionResult
    /** 入力欄の文字を消す */
    suspend fun clearFocusedField(): ActionResult
    suspend fun isInstalled(pkg: String): Boolean
    /** 全アプリのショートカット (前もって読んで持っているもの)。読めなければ空 */
    fun shortcuts(): List<AppShortcut> = emptyList()
    /** 画面の上から引き出す画面を開く。which: quick_settings (クイック設定のボタン) / notifications (通知) / close */
    suspend fun openPanel(which: String): ActionResult = ActionResult.fail("使えません")
    /** アプリのショートカットを開く (X の「ポスト」なら、投稿の画面がすぐ開く) */
    suspend fun openShortcut(pkg: String, id: String): ActionResult = ActionResult.fail("ショートカットは使えません")
}

/**
 * 操作の結果。ok はコマンドが端末で実行されたこと (画面がどうなったかは別に確かめる)。
 * unknown は、送ったが実行されたか分からない (途中で接続が切れた など)。
 */
data class ActionResult(val ok: Boolean, val detail: String = "", val unknown: Boolean = false) {
    companion object {
        val OK = ActionResult(true)
        fun fail(d: String) = ActionResult(false, d)
        fun unknown(d: String) = ActionResult(false, d, unknown = true)
    }
}
