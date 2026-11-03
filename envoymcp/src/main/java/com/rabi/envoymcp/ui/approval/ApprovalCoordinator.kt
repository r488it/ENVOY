package com.rabi.envoymcp.ui.approval

import android.content.Context
import android.content.Intent
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.parts.ApprovalLevel
import com.rabi.envoymcp.safety.ApprovalDecision
import com.rabi.envoymcp.safety.ApprovalManager
import com.rabi.envoymcp.safety.ApprovalRequest
import com.rabi.envoymcp.safety.ApprovalUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 承認の窓口 (Android)。
 * - BIOMETRIC: ApprovalActivity で内容を大きく見せ、そのまま BiometricPrompt (生体認証 / 端末の PIN 等)。認証できたときだけトークンを出す。
 *   quick なら承認画面を挟まず、QuickApprovalActivity で BiometricPrompt だけを出す (確定しようとしている画面のまま)
 * - VOICE: 内容を読み上げて「はい」で承認 (発信など。家族設定で BIOMETRIC に上げられる)
 * 承認の間は操作中のオーバーレイを隠す。「やめて」(キャンセル) で画面を閉じる。
 */
class ApprovalCoordinator(
    private val context: Context,
    private val speak: suspend (String) -> Unit,
    private val askYesNo: suspend (String) -> Boolean,
    private val overlay: (Boolean) -> Unit,
    private val voiceCallsNeedBiometric: () -> Boolean = { false },
    /** 承認画面を挟まず、指紋などだけで承認するか (QuickApprovalActivity。承認画面がオフ・本人確認がオンで、指紋・PIN が使えるとき) */
    private val quick: () -> Boolean = { false },
    /** 承認なしでそのまま確定するか (承認画面も本人確認もオフ) */
    private val skip: () -> Boolean = { false },
) : ApprovalUi {
    class Pending(val req: ApprovalRequest, val manager: ApprovalManager, val result: CompletableDeferred<ApprovalDecision>)

    private val _current = MutableStateFlow<Pending?>(null)
    val current: StateFlow<Pending?> = _current

    override suspend fun ask(req: ApprovalRequest, manager: ApprovalManager): ApprovalDecision {
        if (skip()) {
            RabiLog.i("approval", "approved without asking (approval off)")
            return ApprovalDecision.Approved(manager.issue(req, "none"))
        }
        val level = if (req.level == ApprovalLevel.VOICE && voiceCallsNeedBiometric()) ApprovalLevel.BIOMETRIC else req.level
        if (level == ApprovalLevel.VOICE) {
            val ok = askYesNo(req.summary + "。よろしいですか？")
            return if (ok) ApprovalDecision.Approved(manager.issue(req, "voice")) else ApprovalDecision.Denied("いいえ")
        }
        val d = CompletableDeferred<ApprovalDecision>()
        val p = Pending(req, manager, d)
        _current.value = p
        overlay(false)
        try {
            val screen = if (quick()) QuickApprovalActivity::class.java else ApprovalActivity::class.java
            context.startActivity(Intent(context, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            speak("確定する前に確認します。画面の内容を確かめてください")
            return withTimeoutOrNull(120_000) { d.await() } ?: ApprovalDecision.Denied("時間切れ")
        } catch (e: CancellationException) {
            RabiLog.i("approval", "cancelled")
            throw e
        } finally {
            if (_current.value === p) _current.value = null
            overlay(true)
        }
    }

    /** ApprovalActivity から。approved は BiometricPrompt が成功したときだけ true */
    fun complete(p: Pending, approved: Boolean, method: String, reason: String = "") {
        if (p.result.isCompleted) return
        p.result.complete(if (approved) ApprovalDecision.Approved(p.manager.issue(p.req, method)) else ApprovalDecision.Denied(reason.ifBlank { "やめました" }))
        if (_current.value === p) _current.value = null
    }
}
