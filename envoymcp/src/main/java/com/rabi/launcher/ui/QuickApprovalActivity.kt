package com.rabi.launcher.ui

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rabi.envoymcp.McpApp
import com.rabi.launcher.core.RabiLog
import kotlinx.coroutines.launch

/**
 * 指紋などだけで承認する (承認画面を挟まず、確定しようとしている画面のまま指紋で承認する)。
 * 本人確認がオンのときだけ使う。自分の画面は出さず (透明)、Android の本人確認 (BiometricPrompt) だけを出すので、
 * 後ろには電話をかける・送るなどをしようとしているアプリの画面がそのまま見える。本人確認の見出し・説明に、何を確定するかを出す。
 * 本人確認はオフのとき、または指紋・PIN が使えない端末では、ApprovalActivity (内容を見て「確認して実行」) を使う。
 * - 本人確認は 生体認証 (強) または 端末の PIN・パターン・パスワード。ChatGPT・ENVOY MCP の押しでは通らない
 * - 本人確認をやめた・できなかったら、承認しなかったことにする (後ろの画面はそのまま。確定のボタンは押さない)
 * - この画面が前に出て操作できるようになってから出す (onWindowFocusChanged)。ほかの画面が前に出て Android に取り消されたとき
 *   (本人がやめたのではないとき。通話の画面が前に出たときなど) は、この画面が前に戻ったら出し直す (2 回まで)
 */
class QuickApprovalActivity : ComponentActivity() {
    private val g by lazy { (application as McpApp).graph }
    private var signal: CancellationSignal? = null
    private var pending: ApprovalCoordinator.Pending? = null
    private var asked = false
    /** Android に取り消された回数と、前に戻ったら出し直すか */
    private var retries = 0
    private var retryOnFocus = false
    private val giveUp = Runnable { pending?.let { p -> if (retryOnFocus) { deny(p, "本人確認の画面を出せませんでした (ほかの画面が前にあります)"); } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = g.approvals.current.value
        if (p == null) { finish(); return }
        pending = p
        // 「やめて」・時間切れで、承認の頼みが無くなったら閉じる
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                g.approvals.current.collect { cur -> if (cur == null || cur !== p) close() }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val p = pending ?: return
        if (!hasFocus || isFinishing) return
        if (!asked) { asked = true; authenticate(p); return }
        if (retryOnFocus) {
            retryOnFocus = false
            window.decorView.removeCallbacks(giveUp)
            RabiLog.i("approval", "quick prompt again (retry $retries)")
            authenticate(p)
        }
    }

    private fun authenticate(p: ApprovalCoordinator.Pending) {
        val r = p.req
        val s = CancellationSignal().also { signal = it }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(ApprovalActivity.kindTitle(r.kind))
            .setSubtitle(r.summary.take(80))
            .setDescription(r.fields.entries.joinToString("\n") { "${it.key}: ${it.value.take(60)}" })
            .setAllowedAuthenticators(AUTH)
            .setConfirmationRequired(true)
            .build()
        RabiLog.i("approval", "quick prompt")
        prompt.authenticate(s, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val method = if (result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC) "biometric" else "device_credential"
                RabiLog.i("approval", "approved by $method (quick)")
                g.approvals.complete(p, true, method)
                close()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                RabiLog.i("approval", "quick auth error $errorCode")
                // Android に取り消された (ほかの画面が前に出たなど。本人がやめたのではない): 前に戻ったら出し直す
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED && retries < MAX_RETRIES && !isFinishing) {
                    retries++
                    retryOnFocus = true
                    if (hasWindowFocus()) onWindowFocusChanged(true)
                    else window.decorView.postDelayed(giveUp, REFOCUS_WAIT_MS)
                    return
                }
                // 本人がやめた (キャンセル・戻る・外を押した)・回数を超えた など: 承認しない
                deny(p, when (errorCode) {
                    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED -> "本人確認をやめました"
                    BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> "本人確認がほかの画面に取り消されました"
                    BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> "指紋の失敗が続いたので、しばらく使えません"
                    BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT -> "本人確認が時間切れになりました"
                    else -> "本人確認ができませんでした"
                } + " (Android の理由 $errorCode)")
            }
            // 1 回合わなかっただけ: 本人確認の画面がそのまま、もう一度を待つ
            override fun onAuthenticationFailed() {}
        })
    }

    private fun deny(p: ApprovalCoordinator.Pending, reason: String) {
        retryOnFocus = false
        g.approvals.complete(p, false, "", reason)
        close()
    }

    private fun close() {
        if (isFinishing) return
        signal?.cancel()
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(giveUp)
        signal?.cancel()
        pending?.let { if (!it.result.isCompleted && isFinishing) g.approvals.complete(it, false, "", "閉じました") }
        super.onDestroy()
    }

    companion object {
        /** 生体認証 (強) か、端末の PIN・パターン・パスワード */
        const val AUTH = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        /** Android に取り消されたときに出し直す回数と、この画面が前に戻るのを待つ長さ */
        private const val MAX_RETRIES = 2
        private const val REFOCUS_WAIT_MS = 10_000L
    }
}
