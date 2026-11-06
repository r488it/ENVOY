package com.rabi.envoymcp.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.core.net.toUri
import com.rabi.envoymcp.ClaudeModel
import com.rabi.envoymcp.ControlMode
import com.rabi.envoymcp.HomeActivity
import com.rabi.envoymcp.McpApp
import com.rabi.envoymcp.McpService
import com.rabi.envoymcp.VisionProvider
import com.rabi.envoymcp.auth.ClaudeClient
import com.rabi.envoymcp.core.Expression
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.mcp.ActivityLog
import com.rabi.envoymcp.mcp.Agent
import com.rabi.envoymcp.voice.VoicePrimer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ENVOY MCP の設定。MCP サーバーのオン・オフ (初期値はオフ)、tunnel-client に渡す接続の情報、
 * 前面のアプリを読むための端末の操作の接続 (ワイヤレスデバッグ)、ChatGPT から呼ばれた道具の記録。
 * 安全の設定をゆるめる変更 (承認をオフ・開発モードをオン・エージェントを足す・権限を広げる) は、ご本人か確かめてから行う (OwnerCheck)。
 */
class SettingsActivity : ComponentActivity() {
    private val g by lazy { (application as McpApp).graph }
    private val refresh = mutableIntStateOf(0)
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh.intValue++ }
    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { g.voiceStage.refresh(); refresh.intValue++ }
    /** 電話の道具の許可 (連絡先を読む・電話をかける) */
    private val phonePerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh.intValue++ }
    /** 写真へのアクセス (写真を言葉で探す)。許可されたら、索引を作り始める */
    private val photoPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { g.photos.refreshStatus(); g.photos.startIndexing(); refresh.intValue++ }
    fun requestPhotos() = photoPerm.launch(Manifest.permission.READ_MEDIA_IMAGES)
    /** AgentARM の許可 (Bluetooth・カメラ)。答えが出たら、サービスに入れ直させる (カメラの種類を付け、腕とつなぎ始める) */
    private val armPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reapply() }
    /** 安全の設定をゆるめる前に、ご本人か確かめる */
    private val owner = OwnerCheck(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFFC93C), onPrimary = Color.Black, background = Color(0xFF0B0D12), surface = Color(0xFF14171E))) {
                SettingsRoot(g, this, refresh.intValue)
            }
        }
    }

    // ChatGPT でサインインの戻りを受け取るのは、この画面を開いている間だけ
    override fun onPause() { g.auth.setForeground(false); super.onPause() }

    // 画面から離れたら、次にゆるめるときは確かめ直す
    override fun onStop() { owner.forget(); super.onStop() }

    override fun onDestroy() { owner.cancel(); super.onDestroy() }

    override fun onResume() {
        super.onResume()
        // ChatGPT でサインインの戻り (ブラウザから) を受け取れるように
        g.auth.setForeground(true)
        // 本人がオンにしているのに止まっている (アプリが落ちた など) ときは、見えているこの時に動かし直す。
        // AgentARM を使うときは、見えているこの時に常駐へカメラの種類を付け直す (見えていないときに始まると、カメラを使えないため)
        if (g.settings.serverEnabled && (!g.server.running || g.settings.armEnabled)) McpService.start(this)
        g.nowPlaying.refresh()   // 通知へのアクセスを許可して戻ってきたとき
        refresh.intValue++
    }

    fun setServer(on: Boolean) {
        g.settings.serverEnabled = on
        if (on) {
            // 動いていることを通知に出すため (断られても動かす。そのときは通知の一覧に出ないことがある)
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            McpService.start(this)
        } else McpService.stop(this)
        refresh.intValue++
    }

    /** 合言葉の入った URL を写す (クリップボードの見本の表示に出さないよう、秘密の印を付ける) */
    fun copyUrl() = copySecret("http://127.0.0.1:${g.settings.port}/mcp/${g.settings.token}")

    /** 足したエージェントの合言葉の入った URL を写す */
    fun copyAgentUrl(id: String) {
        val a = g.agents.get(id)?.takeIf { !it.main && it.token.isNotEmpty() } ?: return
        copySecret("http://127.0.0.1:${g.settings.port}/mcp/${a.token}")
    }

    private fun copySecret(text: String) {
        val clip = ClipData.newPlainText("ENVOY MCP", text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    /**
     * 開発モード (エージェントの権限の制限をかけない) を切り替え、したことに残す。確定の前の承認は変えない。
     * オンにするときは、先にご本人か確かめる (オフはすぐ)
     */
    fun setDevNoAgentLimits(on: Boolean, say: (String) -> Unit) {
        if (g.settings.devNoAgentLimits == on) return
        val change: () -> Unit = {
            g.settings.devNoAgentLimits = on
            g.activity.add(ActivityLog.USER, "dev_mode", if (on) "開発モードをオンにした（エージェントの権限の制限をかけない）" else "開発モードをオフにした",
                ActivityLog.KIND_ACT, ActivityLog.OK)
            refresh.intValue++
        }
        if (!SafetyChange.loosensDevMode(g.settings.devNoAgentLimits, on)) { change(); return }
        owner.ask("開発モードをオンにします", "止めていないエージェントが、権限の制限なしで、すべての道具を使えるようになります", say, change)
    }

    // ---------------------------------------------------------------- エージェント

    /** エージェントを足す。先にご本人か確かめ、足したら added に渡す */
    fun addAgent(say: (String) -> Unit, added: (Agent) -> Unit) =
        owner.ask("AI エージェントを足します", "足したエージェントは、合言葉の URL から、このスマホの道具を使えるようになります", say) { added(g.agents.add()) }

    /**
     * エージェントの名前・権限・期限を変える。権限を広げるときは、先にご本人か確かめる (狭める・止めるはすぐ)。
     * 変えたら done (あれば) を知らせる
     */
    fun updateAgent(next: Agent, say: (String) -> Unit, done: String? = null) {
        val cur = g.agents.get(next.id) ?: return
        val change: () -> Unit = { g.agents.update(next); done?.let(say) }
        if (!SafetyChange.widensAgent(cur, next)) { change(); return }
        val what = if (!cur.enabled && next.enabled) "「${cur.name}」を使えるようにします" else "「${cur.name}」の権限を広げます"
        owner.ask(what, "変えたあと: ${permissionLine(next, withNoExpiry = true)}", say, change)
    }

    // ---------------------------------------------------------------- スマホの中の tunnel-client

    /** 設定を変えたら、動いているサービスに入れ直させる (サーバーがオフなら何もしない) */
    private fun reapply() { if (g.settings.serverEnabled) McpService.start(this); refresh.intValue++ }

    /** 合言葉を作り直す。スマホの中の tunnel-client も新しい合言葉で動かし直す (古いままだと、ChatGPT からの依頼をすべて断ってしまう) */
    fun regenerateToken(): String {
        g.settings.newToken()
        reapply()
        return if (g.settings.tunnelEnabled) "合言葉を作り直し、スマホの中の tunnel-client も動かし直しました"
        else "合言葉を作り直しました (Mac などで tunnel-client を動かしているときは、そちらの設定も直してください)"
    }

    fun setTunnel(on: Boolean) { g.settings.tunnelEnabled = on; if (!on) g.tunnel.stop(); reapply() }

    fun saveTunnelId(id: String): String {
        val t = id.trim()
        if (!Regex("tunnel_[A-Za-z0-9]+").matches(t)) return "tunnel_ で始まる値を入れてください"
        g.settings.tunnelId = t; reapply()
        return "tunnel_id を保存しました"
    }

    /** 実行用キーをクリップボードから保存する (画面には出さない。保存したらクリップボードを空にする) */
    fun saveKeyFromClipboard(): String {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            ?: return "クリップボードが空です。Platform でキーをコピーしてから押してください"
        if (!text.startsWith("sk-") || text.length < MIN_KEY_LENGTH || text.any { it.isWhitespace() }) return "クリップボードの中身がキーの形ではありません"
        g.tunnelKey.write(text)
        cm.clearPrimaryClip()
        reapply()
        return "実行用キーを保存しました (${text.length} 文字)。クリップボードは空にしました"
    }

    fun clearKey() { g.tunnelKey.clear(); reapply() }

    // ---------------------------------------------------------------- スマホの操作・承認・リゲル

    fun openOverlayPermission() = runCatching {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun setVoiceStage(on: Boolean) { g.settings.voiceStage = on; g.voiceStage.refresh(); refresh.intValue++ }

    /** リゲルの感情の表情を試す: ホームに戻り、うれしい・笑う・驚き・悲しい・照れ・ウインクを順に見せる */
    fun previewExpressions() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        g.scope.launch {
            delay(1_000)
            for (e in Expression.entries) { g.express(e); delay(e.holdMs + 1_400) }
        }
    }

    /** 声に合わせて動かす: オンにするときだけマイクの許可を求める (出力の波形を読むのに要る) */
    fun setVoiceStageLevel(on: Boolean) {
        g.settings.voiceStageLevel = on
        if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micPerm.launch(Manifest.permission.RECORD_AUDIO)
        g.voiceStage.refresh(); refresh.intValue++
    }

    fun setScreenshots(on: Boolean) { g.settings.allowScreenshots = on; refresh.intValue++ }

    // ---------------------------------------------------------------- 画面の理解 (ChatGPT か Claude)

    fun setVisionProvider(p: VisionProvider) { g.settings.visionProvider = p; refresh.intValue++ }
    fun setClaudeModel(m: ClaudeModel) { g.settings.claudeModel = m; refresh.intValue++ }

    /** Claude の API キーをクリップボードから保存する (画面には出さない。保存したらクリップボードを空にする) */
    fun saveClaudeKeyFromClipboard(): String {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) return "クリップボードが空です。Claude Console で API キーをコピーしてから押してください"
        ClaudeClient.keyProblem(text)?.let { return it }
        g.claudeKey.write(text)
        cm.clearPrimaryClip()
        refresh.intValue++
        return "Claude の API キーを保存しました (${ClaudeClient.tail(text)})。クリップボードは空にしました"
    }

    fun clearClaudeKey() { g.claudeKey.clear(); refresh.intValue++ }

    fun setVoicePrimer(on: Boolean) { g.settings.voicePrimer = on; refresh.intValue++ }

    /** 最初の一言の文を保存する (空なら最初の文に戻す) */
    fun saveVoicePrimerText(t: String): String {
        val v = t.trim()
        g.settings.voicePrimerText = v.ifEmpty { VoicePrimer.DEFAULT_TEXT }
        refresh.intValue++
        return if (v.isEmpty()) "最初の文に戻しました" else "保存しました"
    }

    /** 音楽が流れたらリゲルの画面を出す: オンにするときは、マイクの許可も求める (スペクトラムに使う) */
    fun setMusicStage(on: Boolean) {
        g.settings.musicStage = on
        g.refreshMusic()
        if (on && !hasMic()) requestMic() else refresh.intValue++
    }
    fun setMusicLyrics(on: Boolean) { g.settings.musicLyrics = on; refresh.intValue++ }
    fun setMusicFlash(on: Boolean) { g.settings.musicFlash = on; refresh.intValue++ }

    /** 確定の前に承認画面を出すか。オフにする (承認をゆるめる) ときは、先にご本人か確かめる */
    fun setApprovalScreen(on: Boolean, say: (String) -> Unit) {
        val s = g.settings
        val change: () -> Unit = { s.approvalScreen = on; refresh.intValue++ }
        if (!SafetyChange.loosensApproval(s.approvalScreen, s.approvalBiometric, on, s.approvalBiometric)) { change(); return }
        owner.ask("確定の前の承認画面を出さないようにします", approvalAfter(on, s.approvalBiometric), say, change)
    }

    /** 承認のときに指紋などの本人確認をするか。オフにする (承認をゆるめる) ときは、先にご本人か確かめる */
    fun setApprovalBiometric(on: Boolean, say: (String) -> Unit) {
        val s = g.settings
        val change: () -> Unit = { s.approvalBiometric = on; refresh.intValue++ }
        if (!SafetyChange.loosensApproval(s.approvalScreen, s.approvalBiometric, s.approvalScreen, on)) { change(); return }
        owner.ask("承認のときの指紋などの本人確認をやめます", approvalAfter(s.approvalScreen, on), say, change)
    }

    /** ゆるめたあとの承認のしかた (本人確認の画面に出す) */
    private fun approvalAfter(screen: Boolean, biometric: Boolean) = when {
        screen -> "承認画面で「確認して実行」を押すだけで確定します"
        biometric -> "確定しようとしている画面のまま、指紋などで承認します"
        else -> "承認なしで、ChatGPT がそのまま確定するようになります"
    }

    // ---------------------------------------------------------------- AgentARM

    fun armMissingPerms(): List<String> = ARM_PERMS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** AgentARM を使う: オンにするときは、足りない許可 (Bluetooth・カメラ) を求める */
    fun setArm(on: Boolean) {
        g.settings.armEnabled = on
        if (!on) g.arm.stop()
        val missing = armMissingPerms()
        if (on && missing.isNotEmpty()) armPerms.launch(missing.toTypedArray()) else reapply()
    }

    /** 通知へのアクセスの設定 (再生中の曲名を読むのに要る) */
    fun openNotificationAccess() = open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun requestArmPerms() { armMissingPerms().takeIf { it.isNotEmpty() }?.let { armPerms.launch(it.toTypedArray()) } }

    /** 踊るのに要るマイクの許可 (端末の出力の音を読むため。マイクの音は使わない) */
    fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun hasPhonePerms() = PHONE_PERMS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    fun requestPhonePerms() { phonePerms.launch(PHONE_PERMS.toTypedArray()) }
    fun requestMic() { if (!hasMic()) armPerms.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }

    /** 音楽が流れたら踊る: オンにするときは、マイクの許可も求める */
    fun setAutoDance(on: Boolean) {
        g.settings.armAutoDance = on
        g.refreshMusic()
        if (on && !hasMic()) requestMic() else refresh.intValue++
    }

    // ---------------------------------------------------------------- 端末の操作の方式

    fun setControlMode(m: ControlMode) { g.settings.controlMode = m; g.voiceStage.refresh(); refresh.intValue++ }
    fun openAccessibilitySettings() = open(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    /** アプリ情報 (ストア以外から入れたアプリは、ここの「制限付き設定を許可」をしないとユーザー補助をオンにできないことがある) */
    fun openAppDetails() = runCatching {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun open(action: String) = runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { RabiLog.w("settings", "cannot open $action") }

    /** ENVOY MCP が、デジタル アシスタント アプリに選ばれているか */
    fun isAssistant(): Boolean = runCatching { getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)

    /**
     * デジタル アシスタント アプリを選ぶ画面を開く (アプリからは選べない。本人が Android の設定で選ぶ)。
     * 開けなければ「デフォルトのアプリ」、それも無ければ設定のトップ
     */
    fun openAssistantSettings() {
        if (open(Settings.ACTION_VOICE_INPUT_SETTINGS).isSuccess) return
        if (open(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).isSuccess) return
        open(Settings.ACTION_SETTINGS)
    }

    companion object {
        /** これより短いものはキーとして受け付けない */
        private const val MIN_KEY_LENGTH = 40
        private val ARM_PERMS = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.CAMERA)
        private val PHONE_PERMS = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
    }
}
