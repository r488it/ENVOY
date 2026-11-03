package com.rabi.launcher.ui

import android.graphics.BitmapFactory
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.McpApp
import com.rabi.launcher.conversation.Mood
import com.rabi.launcher.core.RabiLog
import com.rabi.launcher.parts.CommitKind

/**
 * 確定前の承認画面。内容 (宛先・アカウント・公開範囲・内容・金額・画面の画像) を大きく見せ、
 * 本人が内容を読んで「確認して実行」を押したら BiometricPrompt を出す (「内容提示 + 本人確認」を一つの承認にする)。
 * 本人確認の画面を自動で出すと内容を隠してしまい、読むには一度キャンセルが要るので、自動では出さない。
 * 本人確認は 生体認証 (強) または 端末の PIN・パターン・パスワード。画面ロックが無い端末では承認できない。
 * ENVOY MCP では、本人確認をするかを設定で選べる (最初はオン)。この画面を出すかも設定で選べ (最初はオフ)、
 * 画面オフ・本人確認オンなら QuickApprovalActivity で本人確認だけ、どちらもオフなら承認なしで確定する (ApprovalCoordinator)。
 * 本人確認オフでこの画面を出すときに ChatGPT が自分で押さないよう、
 * - ENVOY MCP が押しを送っている間 (ADB・ユーザー補助) の押しは受け付けない
 * - 他のアプリの窓が上に重なっているときの押しは受け付けない (filterTouchesWhenObscured)
 * - ChatGPT の道具は ENVOY MCP 自身の画面を押さない (McpTools)
 */
class ApprovalActivity : ComponentActivity() {
    private val g by lazy { (application as McpApp).graph }
    private var signal: CancellationSignal? = null
    private var pending: ApprovalCoordinator.Pending? = null
    private val status = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = g.approvals.current.value
        if (p == null) { finish(); return }
        pending = p
        // 他のアプリの窓が上に重なっているときの押しは受け付けない (なりすましの押しを防ぐ)
        window.decorView.filterTouchesWhenObscured = true
        setContent {
            val cur by g.approvals.current.collectAsState()
            LaunchedEffect(cur) { if (cur == null || cur !== p) finishAndRemoveTask() }   // やめて・時間切れで閉じる
            ApprovalScreen(p, status.value, g.settings.approvalBiometric, onApprove = { approve(p) },
                onCancel = { g.approvals.complete(p, false, "", "やめました"); finishAndRemoveTask() })
        }
    }

    /** 「確認して実行」: 本人確認がオンなら指紋など、オフならそのまま承認。ENVOY MCP が押しを送っている間の押しは受け付けない */
    private fun approve(p: ApprovalCoordinator.Pending) {
        if (g.injectingInput()) {
            RabiLog.w("approval", "ignored injected tap")
            status.value = "この操作は、ご本人が画面を押したときだけ受け付けます"
            return
        }
        if (g.settings.approvalBiometric) { authenticate(p); return }
        RabiLog.i("approval", "approved by button")
        g.approvals.complete(p, true, "button")
        finishAndRemoveTask()
    }

    private fun authenticate(p: ApprovalCoordinator.Pending) {
        val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val can = getSystemService(BiometricManager::class.java).canAuthenticate(auth)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            status.value = when (can) {
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "本人確認のための画面ロック (指紋・PIN など) が設定されていないため、確定できません"
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "本人確認の機能が使えないため、確定できません"
                else -> "本人確認ができないため、確定できません ($can)"
            }
            RabiLog.w("approval", "cannot authenticate: $can")
            return
        }
        signal?.cancel()
        val s = CancellationSignal().also { signal = it }
        val r = p.req
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(kindTitle(r.kind))
            .setSubtitle(r.summary.take(80))
            .setDescription(r.fields.entries.joinToString("\n") { "${it.key}: ${it.value.take(60)}" })
            .setAllowedAuthenticators(auth)
            .setConfirmationRequired(true)
            .build()
        status.value = "本人確認をしています…"
        prompt.authenticate(s, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val method = if (result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC) "biometric" else "device_credential"
                RabiLog.i("approval", "approved by $method")
                g.approvals.complete(p, true, method)
                finishAndRemoveTask()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                status.value = "本人確認ができませんでした。もう一度押すか、「やめる」を押してください"
                RabiLog.i("approval", "auth error $errorCode")
            }
            override fun onAuthenticationFailed() { status.value = "確認できませんでした。もう一度お試しください" }
        })
    }

    override fun onDestroy() {
        signal?.cancel()
        // 画面が閉じられた (戻るボタンなど) のに結果が出ていなければ、承認しなかったことにする
        pending?.let { if (!it.result.isCompleted && isFinishing) g.approvals.complete(it, false, "", "閉じました") }
        super.onDestroy()
    }

    companion object {
        fun kindTitle(k: CommitKind) = when (k) {
            CommitKind.SEND -> "送信の確認"
            CommitKind.POST -> "投稿の確認"
            CommitKind.PURCHASE -> "購入の確認"
            CommitKind.DELETE -> "削除の確認"
            CommitKind.CALL -> "電話をかける確認"
            CommitKind.OTHER -> "確定の確認"
        }
    }
}

@Composable
private fun ApprovalScreen(p: ApprovalCoordinator.Pending, status: String, biometric: Boolean, onApprove: () -> Unit, onCancel: () -> Unit) {
    val r = p.req
    val shot = remember(r.id) { r.screenshotPng?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
    var big by remember { mutableStateOf(false) }
    val style = AvatarStyle.byId((androidx.compose.ui.platform.LocalContext.current.applicationContext as McpApp).graph.settings.avatar)
    Column(Modifier.fillMaxSize().background(Color(0xFF06070A)).verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(110.dp)) { Avatar(Mood.APPROVAL, style, 0f, Modifier.fillMaxSize()) }
        Text(ApprovalActivity.kindTitle(r.kind), color = Color(0xFFFFD86B), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(r.summary, color = Color.White, fontSize = 22.sp, lineHeight = 30.sp)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().background(Color(0xFF14161C), RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            r.fields.forEach { (k, v) ->
                Column {
                    Text(k, color = Color(0xFFAEB4C0), fontSize = 17.sp)
                    Text(v, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium, lineHeight = 32.sp)
                }
            }
        }
        if (shot != null) {
            Spacer(Modifier.height(14.dp))
            Text(if (big) "画面 (タップで小さく)" else "送る前の画面 (タップで大きく)", color = Color(0xFFAEB4C0), fontSize = 16.sp)
            OutlinedButton(onClick = { big = !big }, Modifier.fillMaxWidth()) {
                Image(shot, null, Modifier.fillMaxWidth().heightIn(max = if (big) 900.dp else 260.dp), contentScale = ContentScale.Fit)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (status.isNotBlank()) Text(status, color = Color(0xFFFFB4A8), fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, Modifier.weight(1f).height(64.dp)) { Text("やめる", fontSize = 20.sp, color = Color.White) }
            Button(onClick = onApprove, Modifier.weight(1.4f).height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC93C), contentColor = Color.Black)) {
                Text("確認して実行", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(if (biometric) "実行は、指紋などの本人確認のあとに一度だけ行います。内容が変わったら、もう一度確認します。"
            else "「確認して実行」を押すと、一度だけ実行します。内容が変わったら、もう一度確認します。", color = Color(0xFF8A90A0), fontSize = 15.sp)
    }
}
