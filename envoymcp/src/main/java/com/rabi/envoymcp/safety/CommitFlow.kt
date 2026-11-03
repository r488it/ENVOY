package com.rabi.envoymcp.safety

import com.rabi.envoymcp.device.DeviceController
import com.rabi.envoymcp.observe.Observation
import com.rabi.envoymcp.observe.UiNode
import com.rabi.envoymcp.parts.ApprovalLevel
import com.rabi.envoymcp.parts.CommitKind
import com.rabi.envoymcp.parts.Expect
import com.rabi.envoymcp.parts.Matcher
import com.rabi.envoymcp.parts.Selector
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

sealed class CommitResult {
    data class Done(val summary: String) : CommitResult()
    data class Rejected(val reason: String) : CommitResult()
    /** 承認後に内容や画面が変わった。押していない */
    data class Changed(val reason: String) : CommitResult()
    /** 押したが結果を確かめられない。自動で再送しない */
    data class Unknown(val reason: String) : CommitResult()
    /** 確定の前提がそろわない (画面に宛先が無い など)。押していない */
    data class NotReady(val reason: String) : CommitResult()
}

/**
 * 確定操作の流れ (部品の実行と System 2 の探索で共通):
 * 1. 画面に宛先・本文などが出ているか確かめる
 * 2. 同じ内容の確定が最近あったか (結果不明なら止める)
 * 3. 内容を見せて承認 (BiometricPrompt など)。承認はこの内容に対して 1 回・短期限
 * 4. 対象アプリの画面に戻るのを待つ
 * 5. 画面から内容を取り直し、承認時と同じか確かめる (違えば押さない)
 * 6. 押す → 結果の画面を確かめる (確かめられなければ Unknown、再送しない)
 */
class CommitFlow(
    private val device: DeviceController,
    private val approvalUi: ApprovalUi,
    private val approvals: ApprovalManager,
    private val duplicates: DuplicateGuard,
    private val pollMs: Long = 350,
    private val returnTimeoutMs: Long = 6000,
    /** 元の画面に戻ったと分かってから、承認画面の閉じる動きが終わるまで待つ長さ */
    private val returnSettleMs: Long = 800,
) {
    suspend fun execute(
        target: UiNode,
        selector: Selector,
        vars: Map<String, String>,
        obs: Observation,
        kind: CommitKind,
        level: ApprovalLevel,
        summary: String,
        fields: Map<String, String>,
        mustShow: List<String>,
        success: Expect?,
    ): CommitResult {
        val missing = mustShow.filter { !obs.hasText(it) }
        if (missing.isNotEmpty()) return CommitResult.NotReady("確定の前に画面で確認できません: ${missing.joinToString("、")}")
        val dupKey = duplicates.keyOf(kind, obs.pkg, fields)
        val prev = duplicates.recent(dupKey)
        if (prev != null && (prev.state == DuplicateGuard.State.UNKNOWN || prev.state == DuplicateGuard.State.ATTEMPTED))
            return CommitResult.NotReady("さきほど同じ内容の確定が、できたかどうか確認できていません。二重にならないよう、ここで止めます")
        val shown = if (prev?.state == DuplicateGuard.State.CONFIRMED) "さきほども同じ内容で確定しています。もう一度、$summary" else summary
        val shot = runCatching { device.observe(screenshot = true).screenshotPng }.getOrNull()
        val req = ApprovalRequest(
            kind = kind, level = level, summary = shown, fields = fields, targetPkg = obs.pkg,
            screenFacts = Fingerprint.screenFacts(obs, mustShow), screenshotPng = shot,
        )
        val token = when (val d = approvalUi.ask(req, approvals)) {
            is ApprovalDecision.Denied -> return CommitResult.Rejected(d.reason)
            is ApprovalDecision.Approved -> d.token
        }
        // 承認画面から対象アプリに戻るのを待つ
        var cur: Observation? = null
        val until = System.currentTimeMillis() + returnTimeoutMs
        while (true) {
            currentCoroutineContext().ensureActive()
            val o = device.observe()
            // 同じアプリの別の画面 (このアプリ自身の承認画面など) と取り違えないよう、画面 (Activity) まで比べる
            if (o.ok && o.pkg == obs.pkg && (obs.activity == null || o.activity == obs.activity)) {
                // 承認画面が閉じる動きの間は押しても届かない (ユーザー補助は読み取りが速く、閉じきる前に戻ったと判断してしまう)。
                // 動きが終わるのを待ってから読み直す
                delay(returnSettleMs)
                val again = device.observe()
                cur = if (again.ok && again.pkg == obs.pkg) again else o
                break
            }
            if (!device.isConnected) return CommitResult.Changed("端末の操作の接続が切れたので、押していません")
            if (System.currentTimeMillis() > until) return CommitResult.Changed("承認のあと、元の画面に戻れませんでした (いま: ${o.pkg})")
            delay(pollMs)
        }
        val now = cur!!
        val fp = Fingerprint.of(kind, now.pkg, fields, Fingerprint.screenFacts(now, mustShow))
        when (approvals.consume(token, fp)) {
            ConsumeResult.OK -> {}
            ConsumeResult.MISMATCH -> return CommitResult.Changed("承認したあとに画面の内容が変わったので、押していません")
            ConsumeResult.EXPIRED -> return CommitResult.Changed("承認の有効時間が過ぎたので、押していません")
            else -> return CommitResult.Changed("承認が使えないので、押していません")
        }
        val node = when (val f = Matcher.find(now, selector, vars)) {
            is Matcher.Found.One -> f.node
            else -> now.nodeAt(target.bounds.cx, target.bounds.cy)?.takeIf { it.label == target.label && it.label.isNotBlank() }
                ?: return CommitResult.Changed("確定ボタンを一つに決められないので、押していません")
        }
        duplicates.mark(dupKey, DuplicateGuard.State.ATTEMPTED, summary)
        val tr = device.tap(node.bounds.cx, node.bounds.cy)
        if (!tr.ok) {
            duplicates.mark(dupKey, DuplicateGuard.State.UNKNOWN, summary)
            return CommitResult.Unknown(if (tr.unknown) "押す操作を送ったあと接続が切れました。できたかどうか確認できません" else "押す操作ができませんでした (${tr.detail})")
        }
        if (success != null) {
            val end = System.currentTimeMillis() + maxOf(success.timeoutMs, 5000)
            while (true) {
                val o = device.observe()
                if (Matcher.check(o, success, vars).ok) break
                if (!device.isConnected || System.currentTimeMillis() > end) {
                    duplicates.mark(dupKey, DuplicateGuard.State.UNKNOWN, summary)
                    return CommitResult.Unknown("押したあとの画面で、できたかどうか確認できません")
                }
                delay(pollMs)
            }
        } else {
            // 結果の確かめ方が無い確定は、画面が変わったことしか分からない
            delay(1200)
            val o = device.observe()
            if (!o.ok || o.signature() == now.signature()) {
                duplicates.mark(dupKey, DuplicateGuard.State.UNKNOWN, summary)
                return CommitResult.Unknown("押したあと画面が変わらず、できたかどうか確認できません")
            }
        }
        duplicates.mark(dupKey, DuplicateGuard.State.CONFIRMED, summary)
        return CommitResult.Done(summary)
    }
}
