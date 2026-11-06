package com.rabi.envoymcp.ui.settings

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.ComponentActivity
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.ui.approval.QuickApprovalActivity

/**
 * 安全の設定をゆるめる前に、ご本人か確かめる (Android の本人確認 BiometricPrompt。確定の前の承認と同じく、
 * 生体認証 (強) か、端末の PIN・パターン・パスワード)。子どもやほかの人がスマホを持っているときに、守りを外させないため。
 * - 確かめられたときだけ、変更 (change) を行う。やめた・できなかったときは、設定を変えずに一言で知らせる
 * - 画面ロック (指紋・PIN など) が無い端末では、確かめられないので変えない (確定の前の承認と同じ)
 * - 確かめてから 1 分の間は、この画面を開いている間だけ、続けて変えても確かめ直さない (エージェントの権限を続けて直すため)。
 *   画面から離れたら (forget)、次は確かめ直す
 * - 本人確認の画面は 1 つずつ出す (出している間の頼みは受け付けない)
 */
class OwnerCheck(private val activity: ComponentActivity) {
    private var signal: CancellationSignal? = null
    /** 最後に確かめられた時刻 (SystemClock.elapsedRealtime)。まだなら null */
    private var confirmedAt: Long? = null

    /** what は何を変えるか、note は変えるとどうなるか (どちらも本人確認の画面に出す)。say は結果の一言 */
    fun ask(what: String, note: String, say: (String) -> Unit, change: () -> Unit) {
        if (recentlyConfirmed()) { change(); return }
        if (signal != null) return
        val can = activity.getSystemService(BiometricManager::class.java)?.canAuthenticate(QuickApprovalActivity.AUTH)
            ?: BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            RabiLog.w("settings", "owner check unavailable: $can")
            say(when (can) {
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    "画面ロック (指紋・PIN など) が設定されていないので、ご本人か確かめられません。この設定は変えられません。Android の設定で画面ロックを設定してから、もう一度お試しください"
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                    "本人確認の機能が使えないので、この設定は変えられません"
                else -> "本人確認ができないので、この設定は変えられません ($can)"
            })
            return
        }
        val s = CancellationSignal().also { signal = it }
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(TITLE)
            .setSubtitle(what)
            .setDescription(note)
            .setAllowedAuthenticators(QuickApprovalActivity.AUTH)
            .setConfirmationRequired(true)
            .build()
        RabiLog.i("settings", "owner check")
        prompt.authenticate(s, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                RabiLog.i("settings", "owner confirmed")
                signal = null
                confirmedAt = SystemClock.elapsedRealtime()
                change()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                RabiLog.i("settings", "owner check error $errorCode")
                signal = null
                say(when (errorCode) {
                    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED -> "本人確認をやめました"
                    BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> "本人確認がほかの画面に取り消されました"
                    BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> "指紋の失敗が続いたので、しばらく使えません"
                    BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT -> "本人確認が時間切れになりました"
                    else -> "本人確認ができませんでした (Android の理由 $errorCode)"
                } + "。設定は変えていません")
            }
            // 1 回合わなかっただけ: 本人確認の画面がそのまま、もう一度を待つ
            override fun onAuthenticationFailed() {}
        })
    }

    /** 確かめたことを忘れる (画面から離れたとき。次は確かめ直す) */
    fun forget() { confirmedAt = null }

    /** 出している本人確認をやめる (画面を閉じるとき) */
    fun cancel() { signal?.cancel(); signal = null }

    private fun recentlyConfirmed(): Boolean =
        confirmedAt?.let { SystemClock.elapsedRealtime() - it < GRACE_MS } == true

    companion object {
        const val TITLE = "設定を変える前に、ご本人か確かめます"
        /** 確かめてから、確かめ直さずに続けて変えられる長さ */
        private const val GRACE_MS = 60_000L
    }
}
