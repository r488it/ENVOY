package com.rabi.envoymcp.call

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.a11y.A11yService
import com.rabi.envoymcp.core.RabiLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * LINE の音声通話を、ユーザー補助で画面を操作してかける・音の出入り口を「リゲル」にする・切る (リゲルが代わりに電話するとき)。
 * LINE には友だちに通話をかける入口 (リンク・ショートカット) が無いので、画面をたどる。
 * 画面の作り (LINE 26.15.1):
 * - トーク タブ (説明「トークタブ」) → 見出しの「友だち」→「検索」に名前を入れる → 友だちの行 (説明「名前, ひとこと」)
 * - プロフィール (UserProfileActivity): user_profile_name に名前、user_profile_button_text「音声通話」
 * - 通話 (VoIPServiceActivity): 呼び出し中は button_decline「通話を終了」、つながると calling_time (00:02 など) と button_end。
 *   Bluetooth のヘッドセットがつながっていると bottom_action_speaker が「オーディオ設定」になり、押すと「端末・スピーカー・Bluetooth」の一覧
 * ロックが掛かっていると操作できない
 */
class LineCaller(private val g: McpGraph) {
    private val a11y get() = A11yService.instance

    /** 友だち (表示名) に音声通話をかける。呼び出し中の画面になれば null、うまくいかなければ理由 */
    suspend fun call(friend: String): String? {
        val s = a11y ?: return "ユーザー補助がオフなので、LINE を操作できません"
        if (g.context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return "スマホのロックが掛かっているので、LINE を操作できません。ロックを解除してから頼み直してください"
        val launch = g.context.packageManager.getLaunchIntentForPackage(LINE) ?: return "LINE が入っていません"
        withContext(Dispatchers.Main) { g.context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        // トーク タブ → 見出しの「友だち」
        if (!waitClick(8_000) { s.findAllIn(LINE) { n -> n.contentDescription?.toString()?.startsWith("トークタブ") == true }.firstOrNull() })
            return "LINE のトーク タブが見つかりません"
        delay(STEP_MS)
        if (!waitClick(5_000) { s.findAllIn(LINE) { n -> n.text?.toString() == "友だち" && top(n) < HEADER_BOTTOM }.firstOrNull() })
            return "LINE の「友だち」が見つかりません"
        delay(STEP_MS)
        // 検索に名前を入れる
        if (!waitClick(5_000) { s.findAllIn(LINE) { n -> n.text?.toString() == "検索" && top(n) < SEARCH_BOTTOM }.firstOrNull() })
            return "LINE の検索の欄が見つかりません"
        delay(STEP_MS)
        if (!waitFor(4_000) { s.inputOf(LINE) != null } || !s.setInputText(LINE, friend)) return "LINE の検索の欄に名前を入れられません"
        delay(SEARCH_SETTLE_MS)
        // 友だちの行: 説明が「名前」か「名前, ひとこと」。同じ名前が無ければ、名前で始まる行がひとつだけのとき
        val rows = s.findAllIn(LINE) { n -> n.isClickable || n.className?.toString()?.endsWith("Button") == true }
            .filter { n -> n.contentDescription?.toString()?.let { d -> d == friend || d.startsWith("$friend,") || d.startsWith("$friend ") } == true }
        val row = rows.firstOrNull() ?: run {
            val loose = s.findAllIn(LINE) { n -> n.contentDescription?.toString()?.startsWith(friend) == true && top(n) > SEARCH_BOTTOM }
            if (loose.size == 1) loose.single() else return if (loose.isEmpty()) "LINE の友だちに「$friend」が見つかりません" else "LINE の友だちに「$friend」で始まる人が ${loose.size} 人います。名前を詳しくしてください"
        }
        if (!s.clickNode(row)) return "LINE の友だちを開けません"
        // プロフィールで名前を確かめて「音声通話」
        if (!waitFor(5_000) { s.findAllIn(LINE) { n -> n.viewIdResourceName == "$LINE:id/user_profile_name" }.isNotEmpty() })
            return "LINE の友だちのプロフィールが開きません"
        val shown = s.findAllIn(LINE) { n -> n.viewIdResourceName == "$LINE:id/user_profile_name" }.firstOrNull()?.text?.toString().orEmpty()
        if (shown != friend && !shown.startsWith(friend)) return "開いたプロフィールの名前 (「$shown」) が、頼まれた相手と違うので、かけていません"
        if (!waitClick(3_000) { s.findAllIn(LINE) { n -> n.viewIdResourceName == "$LINE:id/user_profile_button_text" && n.text?.toString() == "音声通話" }.firstOrNull() })
            return "LINE の「音声通話」ボタンが見つかりません"
        if (!waitFor(8_000) { inCallScreen() }) return "LINE の通話の画面になりません"
        RabiLog.i("linecall", "ringing")
        return null
    }

    /** 通話の画面か (呼び出し中・通話中) */
    fun inCallScreen(): Boolean = a11y?.findAllIn(LINE) { n ->
        n.viewIdResourceName in setOf("$LINE:id/button_end", "$LINE:id/button_decline", "$LINE:id/peer_name")
    }?.isNotEmpty() == true

    /** 相手が出たか (通話時間が出ている) */
    fun answered(): Boolean = a11y?.findAllIn(LINE) { n -> n.viewIdResourceName == "$LINE:id/calling_time" }?.isNotEmpty() == true

    /** 音の出入り口を Bluetooth (「リゲル」) にする。「オーディオ設定」→「Bluetooth」 */
    suspend fun routeToBluetooth(): Boolean {
        val s = a11y ?: return false
        if (!waitClick(4_000) { s.findAllIn(LINE) { n -> n.viewIdResourceName == "$LINE:id/bottom_action_speaker" && n.contentDescription?.toString()?.contains("オーディオ") == true }.firstOrNull() })
            return false.also { RabiLog.w("linecall", "audio button not found") }
        delay(STEP_MS)
        val ok = waitClick(3_000) { s.findAllIn(LINE) { n -> n.text?.toString() == "Bluetooth" }.firstOrNull() }
        RabiLog.i("linecall", "route to bluetooth: $ok")
        return ok
    }

    /** 通話を切る (つながっていれば「通話を終了」、呼び出し中ならその「通話を終了」) */
    fun end(): Boolean {
        val s = a11y ?: return false
        val btn = s.findAllIn(LINE) { n -> n.viewIdResourceName == "$LINE:id/button_end" || n.viewIdResourceName == "$LINE:id/button_decline" }.firstOrNull() ?: return false
        return s.clickNode(btn).also { RabiLog.i("linecall", "end tapped: $it") }
    }

    /**
     * かけたあと見張る: 相手が出たら、音を「リゲル」に回して send("answered")、通話の画面が消えたら send("ended")
     * (相手が切った・出なかった)。相手が出たかは通話の画面 (通話時間) で見るので、つながるまでは通話の画面を前にしておく
     */
    suspend fun watch(send: (String) -> Unit) {
        var answered = false
        var gone = 0
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < WATCH_MS) {
            if (!answered && answered()) {
                answered = true
                RabiLog.i("linecall", "answered")
                routeToBluetooth()
                send("answered")
            }
            // 画面が一瞬読めないことがあるので、続けて消えていたら終わったとみなす。通話の画面が後ろに回っても、
            // LINE が通話の状態 (MODE_IN_COMMUNICATION) にしている間は、まだ話している
            val inCall = inCallScreen() || g.context.getSystemService(android.media.AudioManager::class.java).mode == android.media.AudioManager.MODE_IN_COMMUNICATION
            gone = if (inCall) 0 else gone + 1
            if (gone >= GONE_POLLS) { RabiLog.i("linecall", "call screen gone"); send("ended"); return }
            delay(POLL_MS)
        }
    }

    private suspend fun waitFor(ms: Long, ok: () -> Boolean): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < ms) { if (ok()) return true; delay(POLL_MS) }
        return false
    }

    private suspend fun waitClick(ms: Long, find: () -> AccessibilityNodeInfo?): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < ms) {
            val n = find()
            if (n != null && a11y?.clickNode(n) == true) return true
            delay(POLL_MS)
        }
        return false
    }

    private fun top(n: AccessibilityNodeInfo): Int = Rect().also { n.getBoundsInScreen(it) }.top
    private val screenH get() = g.context.resources.displayMetrics.heightPixels
    private val HEADER_BOTTOM get() = (screenH * HEADER_FRAC).toInt()
    private val SEARCH_BOTTOM get() = (screenH * SEARCH_FRAC).toInt()

    companion object {
        const val LINE = "jp.naver.line.android"
        const val POLL_MS = 300L
        const val STEP_MS = 700L
        const val SEARCH_SETTLE_MS = 1_500L
        /** 見出し (「トーク」「友だち」) と検索の欄の下の端 (画面の高さに対する割合。1080×2424 で、見出し 179〜302・検索 302〜415) */
        const val HEADER_FRAC = 0.136
        const val SEARCH_FRAC = 0.194
        const val WATCH_MS = 7 * 60_000L
        const val GONE_POLLS = 4
    }
}
