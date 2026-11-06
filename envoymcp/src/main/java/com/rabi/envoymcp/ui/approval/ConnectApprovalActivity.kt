package com.rabi.envoymcp.ui.approval

import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rabi.envoymcp.McpApp
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.mcp.OAuthServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Claude のコネクターをつなぐ前の、本人の許可の窓口 (OAuthServer の認可から呼ぶ)。
 * ConnectApprovalActivity を出して、指紋などで許可されたら true を返す。やめた・閉じた・時間切れ (呼ぶ側が打ち切る) は false。
 * 画面ロック (指紋・PIN など) が無い端末では、本人か確かめられないので、確認を出さずに false (設定でエージェントを足すときと同じ)
 */
class ConnectApproval(private val context: Context) {
    class Pending {
        internal val result = CompletableDeferred<Boolean>()
    }

    private val _current = MutableStateFlow<Pending?>(null)
    val current: StateFlow<Pending?> = _current

    suspend fun ask(): Boolean {
        val can = context.getSystemService(BiometricManager::class.java)?.canAuthenticate(QuickApprovalActivity.AUTH)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            RabiLog.w("approval", "connect: owner check unavailable: $can")
            return false
        }
        val p = Pending()
        _current.value = p
        try {
            RabiLog.i("approval", "connect: ask owner")
            context.startActivity(Intent(context, ConnectApprovalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            return p.result.await()
        } finally {
            if (_current.value === p) _current.value = null
        }
    }

    /** ConnectApprovalActivity から。approved は BiometricPrompt が成功したときだけ true */
    fun complete(p: Pending, approved: Boolean) {
        p.result.complete(approved)
        if (_current.value === p) _current.value = null
    }
}

/**
 * Claude のコネクターをつなぐかを、指紋などで本人に聞く。自分の画面は出さず (透明)、Android の本人確認 (BiometricPrompt) だけを出す。
 * 見出しは「Claude をつなぎますか」。説明に、足すエージェントの名前と、はじめにできること (見るだけ・1 週間) を出す。
 * - 本人確認は 生体認証 (強) または 端末の PIN・パターン・パスワード (QuickApprovalActivity.AUTH)。AI の道具の押しでは通らない
 * - やめた・できなかったら、つながない
 * - この画面が前に出て操作できるようになってから出す。ほかの画面が前に出て Android に取り消されたときは、
 *   この画面が前に戻ったら出し直す (2 回まで)
 */
class ConnectApprovalActivity : ComponentActivity() {
    private val g by lazy { (application as McpApp).graph }
    private var signal: CancellationSignal? = null
    private var pending: ConnectApproval.Pending? = null
    private var asked = false
    /** Android に取り消された回数と、前に戻ったら出し直すか */
    private var retries = 0
    private var retryOnFocus = false
    private val giveUp = Runnable { pending?.let { p -> if (retryOnFocus) deny(p) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = g.connectApproval.current.value
        if (p == null) { finish(); return }
        pending = p
        // 時間切れ・サーバーが止まったなどで、頼みが無くなったら閉じる
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                g.connectApproval.current.collect { cur -> if (cur == null || cur !== p) close() }
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
            authenticate(p)
        }
    }

    private fun authenticate(p: ConnectApproval.Pending) {
        val s = CancellationSignal().also { signal = it }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(TITLE)
            .setSubtitle(SUBTITLE)
            .setDescription(DESCRIPTION)
            .setAllowedAuthenticators(QuickApprovalActivity.AUTH)
            .setConfirmationRequired(true)
            .build()
        prompt.authenticate(s, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                RabiLog.i("approval", "connect: approved")
                g.connectApproval.complete(p, true)
                close()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                RabiLog.i("approval", "connect: auth error $errorCode")
                // Android に取り消された (ほかの画面が前に出たなど。本人がやめたのではない): 前に戻ったら出し直す
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED && retries < MAX_RETRIES && !isFinishing) {
                    retries++
                    retryOnFocus = true
                    if (hasWindowFocus()) onWindowFocusChanged(true)
                    else window.decorView.postDelayed(giveUp, REFOCUS_WAIT_MS)
                    return
                }
                // 本人がやめた・回数を超えた など: つながない
                deny(p)
            }
            // 1 回合わなかっただけ: 本人確認の画面がそのまま、もう一度を待つ
            override fun onAuthenticationFailed() {}
        })
    }

    private fun deny(p: ConnectApproval.Pending) {
        retryOnFocus = false
        g.connectApproval.complete(p, false)
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
        pending?.let { if (!it.result.isCompleted && isFinishing) g.connectApproval.complete(it, false) }
        super.onDestroy()
    }

    companion object {
        const val TITLE = "Claude をつなぎますか"
        const val SUBTITLE = "新しいエージェント「${OAuthServer.AGENT_NAME}」を足します"
        const val DESCRIPTION = "はじめは見るだけです（画面や写真を見る・探す。押す・文字を入れる・送る・電話はできません）。" +
            "1 週間たつと使えなくなります。できることは、設定のエージェントで変えられます。\n" +
            "ご自分で Claude からつなごうとしたときだけ、許可してください。"
        /** Android に取り消されたときに出し直す回数と、この画面が前に戻るのを待つ長さ */
        private const val MAX_RETRIES = 2
        private const val REFOCUS_WAIT_MS = 10_000L
    }
}
