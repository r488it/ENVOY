package com.rabi.envoymcp

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabi.envoymcp.mcp.McpServer
import com.rabi.envoymcp.mcp.TunnelClient
import com.rabi.launcher.adb.AdbState
import com.rabi.launcher.adb.PairingNotifier
import com.rabi.launcher.conversation.Expression
import com.rabi.launcher.core.RabiLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ENVOY MCP の設定。MCP サーバーのオン・オフ (初期値はオフ)、tunnel-client に渡す接続の情報、
 * 前面のアプリを読むための端末の操作の接続 (ワイヤレスデバッグ)、ChatGPT から呼ばれた道具の記録。
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
    fun copyUrl() {
        val clip = ClipData.newPlainText("ENVOY MCP", "http://127.0.0.1:${g.settings.port}/mcp/${g.settings.token}")
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
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

    // ---------------------------------------------------------------- 操作と表示

    fun openOverlayPermission() = runCatching {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
    fun setApprovalBiometric(on: Boolean) { g.settings.approvalBiometric = on; refresh.intValue++ }
    fun setApprovalScreen(on: Boolean) { g.settings.approvalScreen = on; refresh.intValue++ }

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
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

@Composable
private fun SettingsRoot(g: McpGraph, a: SettingsActivity, tick: Int) {
    val scope = rememberCoroutineScope()
    val server by g.server.state.collectAsState()
    val adb by g.adb.state.collectAsState()
    val entries by g.log.entries.collectAsState()
    var out by remember { mutableStateOf("") }
    var showTools by remember { mutableStateOf(false) }
    val enabled = remember(tick) { g.settings.serverEnabled }
    val search = remember { SettingsSearch() }
    var query by remember { mutableStateOf("") }
    search.setQuery(query)
    val opened = remember { OpenCards(g.settings.settingsOpen) { g.settings.settingsOpen = it } }
    // 開発・試験の画面 (試しの機能・記録・接続の情報。ふだんの設定から分ける)
    var dev by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = dev) { dev = false }
    CompositionLocalProvider(LocalSearch provides search, LocalQuery provides query, LocalOpen provides opened) {
    // 上の時刻・電池の帯と、下のナビゲーションの帯に重ならないように (下まで動かしたとき、最後の「開発・試験」が隠れない)
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0D12)).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.app_name), color = Color(0xFFA8DFFF), fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, modifier = Modifier.padding(top = 8.dp))
        if (dev) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { dev = false }) { Text("‹ 設定", color = Color(0xFFA8DFFF), fontSize = 15.sp) }
                Text("開発・試験", color = Color.White, fontSize = 16.sp)
            }
        } else Text("設定 (検証用)", color = Color.White, fontSize = 16.sp)
        // 設定を探す: 見出しが合えばカードを丸ごと、合わなければ合う項目だけを出す (ふだんの設定と開発・試験の両方から)
        OutlinedTextField(query, { query = it }, label = { Text("設定を探す (例: 録画・マイク・リゲル)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("消す") } })
        if (search.active && !search.anyVisible()) Note("「${query.trim()}」に合う項目はありません")
        if (out.isNotBlank()) Mono(out)
        val showMain = search.active || !dev
        val showDev = search.active || dev

        val mainCards: @Composable () -> Unit = {
        Card("MCP サーバー", server.label, server is McpServer.State.Running) {
            Note("オンにしている間だけ、このスマホの中 (127.0.0.1) で MCP サーバーが動き、通知に出し続けます。ChatGPT からは、tunnel-client (OpenAI の Secure MCP Tunnel) を通して届きます。今の道具は「スマホの状態」(get_state、読むだけ) だけです。")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MCP サーバーを動かす", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { a.setServer(it) })
            }
            Line("状態", server.label, server is McpServer.State.Running)
            if (g.stoppedByUser()) {
                Line("止めた", "リゲルのタップで止めました。しばらく道具を受け付けません", false)
                Btn("受け付けを再開する") { g.clearStop(); out = "受け付けを再開しました" }
            }
        }

        OperateCard(g, a, tick)

        VisionCard(g, a, tick)

        ControlCard(g, a, tick)

        ArmCard(g, a, tick) { out = it }

        UnoqWifiCard(g, tick)

        MonitorCard(g)

        Card("端末の操作の接続 (ワイヤレスデバッグ)", adb.label, adb is AdbState.Connected) {
            Note("「スマホの状態」で前面のアプリを読むのに使います (無くても、ほかの状態は読めます)。ペアリングは最初に一度だけ要ります。")
            Line("状態", adb.label, adb is AdbState.Connected)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("ペアリングを始める") { PairingNotifier.show(a); a.open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
                Btn("接続", true) { scope.launch { out = if (g.adb.ensureConnected()) "✓ 接続しました" else "✗ ${g.adb.state.value.label}" } }
            }
            Note("ペアリング: 開発者向けオプション › ワイヤレスデバッグ › 「ペア設定コードによるデバイスのペア設定」を開いたまま、通知の返信欄に 6 桁のコードを入れます。")
        }

        val phoneOk = remember(tick) { a.hasPhonePerms() }
        val phoneStatus = if (!phoneOk) "未許可" else "着信 " + when (g.settings.rigelIncoming) { "off" -> "出ない"; "voice" -> "声で尋ねる"; else -> "ボタン" } +
            "・録音 " + if (g.settings.rigelRecord) "オン" else "オフ"
        Card("電話", phoneStatus, if (phoneOk) null else false) {
            Line("許可", if (phoneOk) "連絡先を読む・電話をかける: 許可" else "未許可 (電話の道具が使えません)", phoneOk)
            if (!phoneOk) Btn("許可する", true) { a.requestPhonePerms() }
            Note("ChatGPT に「〇〇さんに電話して」と頼むと、電話のアプリを操作せず、連絡先から番号を探して、承認のあとすぐ発信します (承認のしかたは「操作と表示」の設定のとおり)。連絡先の中身は ChatGPT に渡しません (選んだ人の名前と、番号の種類・下 4 桁だけ)。緊急の番号にはかけません。")
            Sub("リゲルが代わりに電話する")
            var callerName by remember { mutableStateOf(g.settings.rigelCallerName) }
            OutlinedTextField(callerName, { callerName = it; g.settings.rigelCallerName = it }, label = { Text("あなたの呼び名 (リゲルが「〇〇さんの代わりに」と名乗る)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            val rc = g.rigelCaller
            var rcInfo by remember { mutableStateOf("") }
            LaunchedEffect(tick) { while (true) { rcInfo = if (rc.state == "idle") "" else "${rc.to}: ${rc.state}" + (rc.result?.optString("summary")?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""); delay(1_000) } }
            if (rcInfo.isNotBlank()) Line("リゲルの電話", rcInfo, if (rc.busy) true else null)
            if (rc.busy) Btn("リゲルの電話をやめる") { rc.hangup() }
            Note("ChatGPT に「〇〇さんに電話して、XX と伝えて」と頼むと、承認 (相手と用件) のあと、リゲル (Gemini 3.8 Live、UNO Q で動く) が電話をかけ、AI だと名乗って用件を伝え、返事を聞いて切ります。結果は ChatGPT が rigel_call_status で受け取ります。UNO Q (腕) につながっていて、UNO Q で電話の代役 (rigel-call) が動いている必要があります。相手の声は Google に送られます。")
            var mask by remember { mutableStateOf(g.settings.numberMask) }
            Toggle("電話の画面の番号をモザイクで隠す", mask) { g.settings.numberMask = it; mask = it }
            Note("リゲルが代わりに電話している間、電話のアプリの画面に出る番号の上にモザイクを重ねます。代行中はリゲルの通話の画面で覆うので、ふだんはオフで構いません (通話の画面が出せないとき・録画で電話の画面を映すとき用)。")
            Sub("かかってきた電話にリゲルが代わりに出る")
            var incoming by remember { mutableStateOf(g.settings.rigelIncoming) }
            listOf(
                "off" to "しない",
                "buttons" to "着信の画面に「リゲルが代わりに出る」「自分で出る」を出す",
                "voice" to "ChatGPT の音声モードを開いて、代わりに出るか尋ねる (ロック中は画面のボタン)",
            ).forEach { (v, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = incoming == v, onClick = { incoming = v; g.settings.rigelIncoming = v })
                    Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
            }
            var auto by remember { mutableStateOf(g.settings.rigelAutoAnswer) }
            var autoSec by remember { mutableFloatStateOf(g.settings.rigelAutoAnswerSec.toFloat()) }
            Toggle("出なかったら、リゲルが自動で出る", auto) { g.settings.rigelAutoAnswer = it; auto = it }
            if (auto && shown("自動で出るまで")) {
                Text("自動で出るまで: 鳴り始めから ${autoSec.roundToInt()} 秒", color = Color.White, fontSize = 15.sp)
                Slider(value = autoSec, onValueChange = { autoSec = it.roundToInt().toFloat() }, valueRange = 3f..30f, steps = 26,
                    onValueChangeFinished = { g.settings.rigelAutoAnswerSec = autoSec.roundToInt() })
            }
            Note("自分で出ず、「リゲルが代わりに出る」「自分で出る」も選ばないまま決めた秒数がたったら、リゲルが代わりに出ます (上で「しない」を選んでいるときは出ません)。留守番電話につながると出られないので、留守番電話より短くしてください。")
            var report by remember { mutableStateOf(g.settings.rigelIncomingReport) }
            Toggle("出たあと、ChatGPT の音声モードを開いて用件を伝える", report) { g.settings.rigelIncomingReport = it; report = it }
            Note("リゲルが出ると、AI だと名乗って相手の名前・用件・折り返しの要否を聞き、切ります。上のスイッチがオンなら、終わったら ChatGPT の音声モードが開いて、用件を整理して伝えます (オフなら通話の記録に残すだけ)。電話と LINE の着信に出ます (どのアプリの着信か・かけてきた人は、着信の通知から読みます。「通知へのアクセス」の許可が要ります)。ユーザー補助がオンで、UNO Q で電話の代役が動いている必要があります。")
            Sub("通話の記録")
            var record by remember { mutableStateOf(g.settings.rigelRecord) }
            Toggle("リゲルの通話を録音する", record) { g.settings.rigelRecord = it; record = it }
            Btn("通話の記録を見る") { a.startActivity(android.content.Intent(a, CallLogActivity::class.java)) }
            Note("リゲルが代わりにかけた・出た電話の間は、リゲルと相手のやり取りを文字で画面に出し、終わったら記録に残します (いつでも見られます。消すのは記録の画面から)。録音がオンなら、通話の音も残します (相手には伝えません)。記録と録音はこのスマホの中だけに置き、どこにも送りません。")
        }

        PhotoCard(g, a, tick)

        val chatGptInstalled = remember(tick) { runCatching { a.packageManager.getPackageInfo(McpGraph.CHATGPT, 0) }.isSuccess }
        Card("ChatGPT", if (chatGptInstalled) "入っています" else "入っていません", chatGptInstalled) {
            val installed = chatGptInstalled
            Line("アプリ", if (installed) "入っています" else "入っていません", installed)
            Btn("ChatGPT を開く (リゲルのタップと同じ)") { if (!g.openChatGptVoice()) out = "ChatGPT を開けませんでした" }
            Note("音声モードを直接開けるかは未確認です。開けないときは ChatGPT が開くだけなので、音声は自分で始めてください。")
            val assistant = remember(tick) { a.isAssistant() }
            Line("デジタル アシスタント", if (assistant) "ENVOY MCP" else "ほかのアプリ (ENVOY MCP ではない)", assistant)
            Btn("デジタル アシスタントの設定を開く") { a.openAssistantSettings() }
            Note("「デジタル アシスタント アプリ」で ENVOY MCP を選ぶと、アシスタントを呼ぶ操作 (電源ボタンの長押し・画面の下の角からのスワイプなど) で、リゲルのタップと同じように ChatGPT の音声モードが開きます。そのあいだ Gemini は呼べません。戻すときは、同じ画面で選び直してください。")
        }

        // 開発・試験の画面へ
        if (!search.active) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF14171E)).clickable { dev = true }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("開発・試験", color = Color(0xFFFFD86B), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("試しの機能・道具の記録・接続の情報・トンネル", color = Color(0xFF9AA3B5), fontSize = 13.sp)
            }
            Text("›", color = Color(0xFF7F8796), fontSize = 22.sp)
        }
        }

        val devCards: @Composable () -> Unit = {
        TunnelCard(g, a, tick) { out = it }

        Card("道具の記録", if (entries.isEmpty()) "まだありません" else "${entries.size} 件") {
            if (entries.isEmpty()) Note("まだありません")
            else {
                // 最後の 1 件だけ見せ、残りは折りたたむ (長くて見づらいため)
                val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.JAPAN) }
                fun line(e: com.rabi.envoymcp.mcp.ToolLog.Entry) = "${fmt.format(Date(e.at))} ${if (e.ok) "●" else "○"} ${e.name}  ${e.detail}"
                Mono("最後: " + line(entries.first()))
                Btn(if (showTools) "記録を閉じる" else "記録を見る (${entries.size} 件)") { showTools = !showTools }
                if (showTools) {
                    Note("新しい順です。")
                    entries.take(30).forEach { Mono(line(it)) }
                }
            }
        }

        DevCard(g, a) { out = it }
        }

        // 出していない側の画面のカードも見えない形で作り、項目の文字を覚えておく (どちらの画面からでも、全部の項目を探せるように)
        if (showMain) mainCards() else Hidden(mainCards)
        if (showDev) devCards() else Hidden(devCards)
    }
    }
}

/** 見えず・押せず・読み上げもしない形で作る (検索のために、項目の文字を覚えるだけ) */
@Composable private fun Hidden(content: @Composable () -> Unit) {
    Box(Modifier.height(0.dp).clipToBounds().clearAndSetSemantics { }) { Column { content() } }
}

/**
 * UNO Q の Wi-Fi (外に持ち出すとき、設定の画面から登録できるように)。腕との Bluetooth を通して、今のつながり・
 * 登録してあるもの・近くのものを見て、登録・接続・削除する。パスワードは暗号化して送り (WifiCrypto)、スマホには残さない
 */
@Composable
private fun UnoqWifiCard(g: McpGraph, tick: Int) {
    val arm = g.arm
    val st by arm.status.collectAsState()
    val scope = rememberCoroutineScope()
    var cur by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var saved by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var near by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var busy by remember { mutableStateOf("") }
    var said by remember { mutableStateOf("") }
    var ssid by remember { mutableStateOf("") }
    var sec by remember { mutableStateOf("wpa") }
    var psk by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val why = remember(st.ready, st.version, tick) { arm.netCheck() }
    fun refresh() { scope.launch { busy = "読んでいます…"; cur = arm.wifiStatus(); saved = arm.wifiSaved() ?: emptyList(); busy = "" } }
    LaunchedEffect(st.ready, tick) { if (arm.netCheck() == null) refresh() }
    val now = cur?.optString("ssid").orEmpty()
    Card("UNO Q の Wi-Fi", when { why != null -> "腕につながっていない"; cur == null -> ""; now.isBlank() -> "つながっていない"; else -> now },
        when { why != null -> null; cur == null -> null; else -> now.isNotBlank() }) {
        Note("UNO Q (腕) の Wi-Fi を、腕との Bluetooth を通して見る・登録します。外に持ち出すときは、Pixel のテザリングを登録しておくと、家の Wi-Fi が無い所では自動でつながります (電話の代役など、Wi-Fi を使う機能が外でも動きます。腕の動きと呼び名は Bluetooth なので Wi-Fi が無くても動きます)。パスワードは暗号化して送り、UNO Q の中だけに保存します。")
        if (why != null) { Line("使えません", why, false); return@Card }
        Line("今", cur?.let { c -> if (c.optString("ssid").isBlank()) "つながっていません" else "${c.optString("ssid")}  ${c.optString("ip")}  (強さ ${c.optInt("sig")})" } ?: "—",
            cur?.let { it.optString("ssid").isNotBlank() })
        Btn("読み直す") { refresh() }
        Sub("登録してあるネットワーク")
        if (saved.isEmpty()) Note("ありません (読み直すと出ます)")
        saved.forEach { s ->
            val name = s.optString("name")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.optString("ssid").ifBlank { name } + if (name == now) "  (つながっている)" else "", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (name != now) TextButton(onClick = { scope.launch { busy = "つないでいます…"; said = arm.wifiConnect(name).second; busy = ""; refresh() } }) { Text("つなぐ") }
                TextButton(onClick = {
                    if (confirm != name) confirm = name
                    else scope.launch { confirm = ""; busy = "消しています…"; said = arm.wifiDelete(name).second; busy = ""; refresh() }
                }) { Text(if (confirm == name) "もう一度で消す" else "消す", color = Color(0xFFFFB4A8)) }
            }
        }
        Sub("登録する")
        Btn("近くのネットワークを探す") {
            scope.launch { busy = "探しています…"; near = arm.wifiScan() ?: emptyList(); busy = ""; said = if (near.isEmpty()) "見つかりませんでした" else "" }
        }
        near.forEach { n ->
            Text("${n.optString("ssid")}   強さ ${n.optInt("sig")}   ${if (n.optString("sec") == "open") "鍵なし" else "鍵あり"}", color = Color(0xFFCFE3FF), fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clickable { ssid = n.optString("ssid"); sec = n.optString("sec", "wpa") }.padding(vertical = 6.dp))
        }
        OutlinedTextField(ssid, { ssid = it }, label = { Text("ネットワーク名 (SSID。上の一覧を押すと入ります)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        listOf("wpa" to "パスワードあり (WPA2 など)", "sae" to "パスワードあり (WPA3 だけ)", "open" to "パスワードなし").forEach { (v, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = sec == v, onClick = { sec = v })
                Text(label, color = Color.White, fontSize = 14.sp)
            }
        }
        if (sec != "open") OutlinedTextField(psk, { psk = it }, label = { Text("パスワード") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
        Btn("登録する", true) {
            val name = ssid.trim()
            when {
                name.isEmpty() -> said = "ネットワーク名を入れてください"
                name.toByteArray().size > 32 -> said = "ネットワーク名が長すぎます"
                sec != "open" && psk.length !in 8..63 -> said = "パスワードは 8〜63 文字です"
                else -> scope.launch {
                    busy = "登録しています…"
                    val (ok, m) = arm.wifiAdd(name, sec, psk)
                    said = m; busy = ""
                    if (ok) { psk = ""; refresh() }
                }
            }
        }
        if (busy.isNotBlank()) Note(busy)
        if (said.isNotBlank()) Note(said)
        Note("「Wi-Fi を変える許可がありません」と出たときだけ、UNO Q で一度 sudo sh ~/agentarm/linux/tools/wifi_permission.sh を動かしてください (今の UNO Q は、ユーザーが netdev のグループに入っているので要りません)。")
    }
}

/** 写真を言葉で探す (EmbeddingGemma 2): 許可・モデル・索引・試しに探す・ライセンス */
@Composable
private fun PhotoCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val ps = g.photos
    val st by ps.status.collectAsState()
    val perm = remember(tick) { ps.hasPermission() }
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<com.rabi.envoymcp.media.PhotoSearch.Hit>>(emptyList()) }
    var said by remember { mutableStateOf("") }
    var showLicense by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val mb = ps.spec.size / 1_000_000
    // 設定を開くたびに見直す (モデルを ADB で置いたとき・写真を許可したとき)。モデルと許可があれば、新しい写真の索引を作り始める
    LaunchedEffect(tick) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ps.refreshStatus() }; ps.startIndexing() }
    val status = when {
        !perm -> "写真が未許可"
        !st.modelReady -> if (st.downloading) "モデルを落としています" else "モデルなし"
        st.indexing -> "索引 ${st.indexed}/${st.total}"
        else -> "索引 ${st.indexed} 枚"
    }
    Card("写真を探す (EmbeddingGemma 2)", status, if (!perm || !st.modelReady) false else true) {
        Note("ChatGPT に「〇〇の写真を探して、X にポストして」「LINE で〇〇さんに送って」と頼めます。写真は、スマホの中の AI (EmbeddingGemma 2。${ps.spec.label}) が言葉との近さで探し、スマホの外には出しません。ChatGPT に渡すのは、写真の番号・撮った日時・近さだけです。投稿・送信のボタンを押すときは、いつもどおりスマホでの承認が要ります。")
        Line("写真", if (perm) "許可" else "未許可", perm)
        if (!perm) Btn("写真へのアクセスを許可する (「すべて許可」を選ぶ)", true) { a.requestPhotos() }
        Line("モデル", when {
            st.modelReady -> "入っています (${ps.spec.label})"
            st.downloading -> "落としています ${(st.downloaded * 100 / ps.spec.size).coerceIn(0, 100)}%"
            else -> "入っていません ($mb MB)"
        }, if (st.modelReady) true else null)
        if (!st.modelReady) {
            if (st.downloading) Btn("止める") { ps.cancelDownload() }
            else Btn("モデルを落とす ($mb MB。Wi-Fi のときに)", true) { ps.download() }
        }
        Line("索引", "${st.indexed} / ${if (st.total >= 0) st.total else "?"} 枚" + if (st.msPerPhoto > 0) " (1 枚 ${st.msPerPhoto} ms)" else "",
            if (st.indexing) null else if (st.total in 1..st.indexed) true else null)
        if (st.modelReady && perm) {
            if (st.indexing) Btn("索引を作るのを止める") { ps.stopIndexing() } else Btn("索引を作る (新しい写真)") { ps.startIndexing() }
        }
        if (st.note.isNotBlank()) Note(st.note)
        Note("索引は、写真ごとの特徴 (数字の並び) をこのスマホの中に置いたものです。写真が増えたら、探すとき・アプリを開き直したときに足します。")
        if (st.modelReady && perm && shown("試しに探す")) {
            OutlinedTextField(q, { q = it }, label = { Text("試しに探す (例: 猫・夕焼け)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Btn("探す") {
                if (q.isNotBlank()) scope.launch {
                    said = "探しています…"; hits = emptyList()
                    hits = runCatching { ps.search(q.trim(), 6) }.getOrElse { said = "探せませんでした (${it.javaClass.simpleName})"; emptyList() }
                    if (said == "探しています…") said = if (hits.isEmpty()) "見つかりませんでした" else ""
                }
            }
            if (said.isNotBlank()) Note(said)
            if (hits.isNotEmpty()) PhotoHits(g, hits)
        }
        if (st.modelReady) Btn(if (confirmDelete) "もう一度押すとモデルを消します" else "モデルを消す") { if (confirmDelete) { ps.deleteModel(); confirmDelete = false } else confirmDelete = true }
        Btn(if (showLicense) "ライセンスを閉じる" else "ライセンス (Edge Gallery・EmbeddingGemma 2)") { showLicense = !showLicense }
        if (showLicense) {
            val text = remember { runCatching { listOf("NOTICE", "LICENSE").joinToString("\n\n") { f -> a.assets.open("licenses/edge-gallery/$f").bufferedReader().readText() } }.getOrDefault("ライセンスの表示がありません") }
            Mono(text)
        }
    }
}

/** 試しに探した写真 (近い順。小さな写真と近さ) */
@Composable
private fun PhotoHits(g: McpGraph, hits: List<com.rabi.envoymcp.media.PhotoSearch.Hit>) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        hits.forEach { h ->
            var img by remember(h.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            LaunchedEffect(h.id) {
                img = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { g.context.contentResolver.loadThumbnail(h.uri, android.util.Size(256, 256), null).asImageBitmap() }.getOrNull()
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E2430))) {
                    img?.let { androidx.compose.foundation.Image(it, contentDescription = "見つけた写真", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                }
                Text("%.2f".format(h.score), color = Color(0xFFAEB4C0), fontSize = 12.sp)
            }
        }
    }
}

/** モニター (PC モード): つないだら全画面のリゲルを出して、モニターを操作する */
@Composable
private fun MonitorCard(g: McpGraph) {
    val stageOn = remember { mutableStateOf(g.settings.monitorStage) }
    Card("モニター (PC モード)", if (stageOn.value) "オン" else "オフ") {
        Toggle("モニターにつないだら、全画面のリゲルを出してモニターを操作する", stageOn.value) {
            g.settings.monitorStage = it; stageOn.value = it; g.monitorStage.refresh()
        }
        Note("オンのときは、スマホをモニターにつなぐと、モニターに全画面のリゲルが出ます。ChatGPT に頼んだアプリ (YouTube など) はモニターに開き、画面を読む・押す・撮るもモニターでします。操作している間は、モニターの右下に小さなリゲルが出て、クリックで止められます。リゲルを押すと ChatGPT の音声が始まります (話すのはスマホのマイク)。")
        Line("操作先", if (g.monitorStage.target != android.view.Display.DEFAULT_DISPLAY) "モニター (画面 ${g.monitorStage.target})" else "スマホ")
    }
}

/** AgentARM (スマホを載せる腕): オン・オフ、許可、つながり、試しに動かす */
@Composable
private fun ArmCard(g: McpGraph, a: SettingsActivity, tick: Int, say: (String) -> Unit) {
    val st by g.arm.status.collectAsState()
    val armLog by g.arm.log.lines.collectAsState()
    var showArmLog by remember { mutableStateOf(false) }
    val on = remember(tick) { g.settings.armEnabled }
    val missing = remember(tick) { a.armMissingPerms() }
    val serverOn = remember(tick) { g.settings.serverEnabled }
    val scope = rememberCoroutineScope()
    fun go(block: suspend () -> com.rabi.envoymcp.arm.ArmResult) { scope.launch { say("AgentARM: " + block().message) } }
    Card("AgentARM (スマホを載せる腕)", if (!on) "使わない" else st.link, if (on) st.ready else null) {
        Note("ChatGPT から、スマホを縦・横にする、こっちを向く、顔を追い続ける (動画を見る間など)、追うのをやめる、を頼めます。マグネット充電器に付けるとまっすぐ (縦) に直しながら顔に向け、外すと腕を元の姿勢に戻します。")
        Toggle("AgentARM を使う", on) { a.setArm(it) }
        if (on) {
            if (missing.isNotEmpty()) {
                Line("許可", "足りません (Bluetooth・カメラ)", false)
                Btn("許可する", true) { a.requestArmPerms() }
            }
            if (!serverOn) Line("動かす条件", "MCP サーバーが動いている間だけ動きます", false)
            Line("つながり", st.link, st.ready)
            if (st.ready) {
                Line("腕", st.mode.ifBlank { "—" } + when (st.calibrated) { false -> " (較正がまだ)"; else -> "" }, if (st.mode == "fault") false else null)
                Line("顔の追跡", if (st.following) "追っています" + (st.face?.let { (h, v) -> " (ずれ 横 ${h}° 縦 ${v}°)" } ?: " (顔が見えません)") else "していません", if (st.following) true else null)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Btn("縦") { go { g.arm.orient(com.rabi.envoymcp.arm.ArmController.PORTRAIT) } }
                    Btn("横") { go { g.arm.orient(com.rabi.envoymcp.arm.ArmController.LANDSCAPE) } }
                    Btn("こっち") { go { g.arm.lookAtMe(null) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Btn("横で追う") { go { g.arm.follow(com.rabi.envoymcp.arm.ArmController.LANDSCAPE) } }
                    Btn("追うのをやめる") { go { g.arm.stopFollowingByTool() } }
                }
            }
            // 撮りながら顔を追う (前面カメラ。腕につながっていなければ撮るだけ)
            Line("録画", if (st.recording) "撮っています (${if (st.recordingBack) "背面" else "前面"}。Movies/ENVOY に保存)" else "していません", if (st.recording) true else null)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (st.recording) Btn("録画を止める", true) { go { g.arm.recordStop() } }
                else {
                    Btn("撮りながら追う") { go { g.arm.recordStart(follow = true, maxMin = com.rabi.envoymcp.arm.ArmController.RECORD_DEFAULT_MIN) } }
                    Btn("カメラマン (背面)") { go { g.arm.recordStart(follow = true, maxMin = com.rabi.envoymcp.arm.ArmController.RECORD_DEFAULT_MIN, back = true) } }
                }
            }
            Note("カメラマン (背面) は、背面カメラで撮り、腕が背面カメラを人に向けて追います。画面は反対を向きます。背面カメラは水平より約 9° 上までしか向けられないので、スマホと同じ高さか低い所に立つ・座ると合います。")
            DanceSection(g, a, st, tick, ::go)
            WakeSection(g)
            if (st.last.isNotBlank()) Line("最後", st.last)
            if (armLog.isNotEmpty()) {
                Btn(if (showArmLog) "記録を閉じる" else "腕の記録を見る (${armLog.size} 行)") { showArmLog = !showArmLog }
                if (showArmLog) {
                    Note("新しい順。拍の取れ方 (テンポ・確かさ・音の強さ) と、腕に頼んだことの結果です。")
                    armLog.take(40).forEach { Mono(it) }
                }
            }
            Note("顔を探す・追う間は前面カメラを使います (緑の点が出ます。映像は端末の外に出しません)。")
        }
    }
}

/** 呼び名 (「ねえ、リゲル」) で呼ぶ: 起きたときの表情・しきい値 */
@Composable
private fun WakeSection(g: McpGraph) {
    Sub("呼び名で呼ぶ")
    var greet by remember { mutableStateOf(g.settings.wakeGreet) }
    Toggle("呼び名で起きたら、表情を見せてこちらを向く", greet) { g.settings.wakeGreet = it; greet = it }
    Note("「ねえ、リゲル」と呼ばれたら、ChatGPT の音声モードが開くまでの間に、リゲルがランダムな表情 (うれしい・笑う・驚き・照れ・ウインク) を画面と腕の動きで見せてから、前面カメラで顔を探してこちらを向きます。")
    var th by remember { mutableFloatStateOf(g.settings.wakeThreshold) }
    if (shown("呼び名のしきい値")) {
        Text("呼び名のしきい値: ${"%.2f".format(th)}", color = Color.White, fontSize = 15.sp)
        Slider(value = th, onValueChange = { th = (it * 100).roundToInt() / 100f }, valueRange = 0.10f..0.90f, steps = 79,
            onValueChangeFinished = { g.settings.wakeThreshold = th; g.arm.wakeSettingsChanged() })
    }
    Note("UNO Q が「本人の『ねえ、リゲル』」とみなす声の近さです (最初は 0.58)。下げると反応しやすくなり、上げるとほかの人の声や似た言葉に反応しにくくなります。変えるとすぐ UNO Q に送ります (腕につながっているとき)。")
}

/** AgentARM の踊り: 音楽が流れたら踊る、マイクの許可、拍が取れているか、試す */
@Composable
private fun DanceSection(g: McpGraph, a: SettingsActivity, st: com.rabi.envoymcp.arm.ArmController.Status, tick: Int,
                         go: (suspend () -> com.rabi.envoymcp.arm.ArmResult) -> Unit) {
    val auto = remember(tick) { g.settings.armAutoDance }
    val mic = remember(tick) { a.hasMic() }
    val ms by g.arm.music.status.collectAsState()
    var said by remember { mutableStateOf("") }
    Sub("音楽に合わせて踊る")
    Toggle("音楽が流れたら踊る", auto) { a.setAutoDance(it) }
    Note("スマホで音楽 (YouTube Music・Spotify など) が流れ始めたら、腕が拍に合わせて踊ります。スマホが腕に付いていて (ワイヤレス充電中)、顔を追っていない・音声の会話をしていないときだけ。動画では踊りません。ChatGPT に「踊って」と頼むこともできます。")
    Line("マイクの許可", if (mic) "許可" else "未許可 (拍を取るのに要ります)", mic)
    if (!mic) Btn("許可する", true) { a.requestMic() }
    if (auto) Line("音楽", if (st.musicPlaying) "流れています" else "流れていません", if (st.musicPlaying) true else null)
    if (ms.listening || ms.note.isNotBlank()) {
        Line("拍", (ms.bpm?.let { "テンポ ${it.toInt()}" } ?: "—") + "  確かさ ${(ms.confidence * 100).toInt()}%  音 ${(ms.energy * 100).toInt()}%", if (ms.bpm != null) true else null)
        if (ms.note.isNotBlank()) Note(ms.note)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Btn("拍を聞いてみる") { said = g.arm.listenTest() }
        if (st.ready) {
            if (st.dancing) Btn("やめる") { go { g.arm.stopByTool() } }
            else Btn("踊る") { go { g.arm.dance(null, null) } }
        }
    }
    if (said.isNotBlank()) Note(said)
    val track by g.nowPlaying.track.collectAsState()
    val access = remember(tick) { g.nowPlaying.hasAccess() }
    Line("曲名", if (!access) "未許可 (通知へのアクセス)" else track?.let { (if (it.playing) "▶ " else "■ ") + it.label + " (" + it.app + ")" } ?: "再生中の曲なし", if (access) null else false)
    if (!access) Btn("通知へのアクセスを開く", true) { a.openNotificationAccess() }
    Note("曲名が分かると、曲ごとの専用の振り付けで踊り、「この曲は party で」と頼むとその曲の踊り方を覚えます (覚えた曲 ${g.arm.songDances.count()} 曲)。通知へのアクセスは、再生中の曲を読むためだけに使います (通知の中身は読みません)。オンにできないときは、アプリ情報の右上の︙から「制限付き設定を許可」を選んでから、もう一度オンにしてください。")
    if (st.ready && st.version in 1..1) Line("腕のプログラム", "古い版です (動き・踊りには UNO Q の更新が要ります)", false)
    Note("拍は端末の出力の音から取ります (マイクの音は使いません。Android では出力の音を読むのにもマイクの許可が要ります)。速さの上限 (1 秒に約 28°) のため、速い曲ほど小さく揺れます。")
}

/** 開発者向け (ふだんは閉じておく): 接続の情報と合言葉の作り直し。Mac で試すとき・調べるときだけ使う */
@Composable
private fun DevCard(g: McpGraph, a: SettingsActivity, say: (String) -> Unit) {
    Card("開発者向け (接続の情報)") {
        run {
            Line("URL", "http://127.0.0.1:${g.settings.port}/mcp/••••")
            Note("•••• は合言葉です (画面には出しません)。合言葉は URL の最後か、ヘッダー Authorization: Bearer <合言葉> のどちらで渡しても通ります。合言葉の無い接続・違う接続、ブラウザからの接続は断ります。スマホの中の tunnel-client には、アプリが自動で渡します。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("URL を写す") { a.copyUrl(); say("合言葉の入った URL を写しました") }
                Btn("合言葉を作り直す") { say(a.regenerateToken()) }
            }
        }
    }
}

/** 画面の理解: Sign in with ChatGPT で、画面の画像を AI に読ませる (ChatGPT のアプリは道具の画像をモデルに見せないため) */
@Composable
private fun VisionCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val ai by g.auth.status.collectAsState()
    val shots = remember(tick) { g.settings.allowScreenshots }
    Card("画面の理解 (ChatGPT でサインイン)", ai.label, ai.connected) {
        Note("ChatGPT のアプリは、ENVOY MCP が返した画面の画像を AI に見せません。そこで ENVOY MCP の中で、あなたの ChatGPT のプランを使って画像を読み、画面の説明と押せそうなもの (位置つき) を文章にして返します (道具 look_at_screen)。API キーは要りません。")
        Line("状態", ai.label + (ai.maskedAccount?.let { " ($it)" } ?: ""), ai.connected)
        ai.error?.let { Note(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Continue with ChatGPT", !ai.signedIn) { g.auth.signIn() }
            if (ai.signedIn) Btn("ログアウト") { g.auth.signOut { } }
        }
        if (ai.models.isNotEmpty()) {
            Text("画像を読むモデル", color = Color(0xFFAEB4C0), fontSize = 13.sp)
            ai.models.take(12).forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = ai.selectedModel == m.slug, onClick = { g.auth.selectModel(m.slug) })
                    Text(m.slug, color = Color.White, fontSize = 14.sp)
                }
            }
        }
        Note(if (shots) "画面の画像は、ChatGPT が look_at_screen を呼んだときだけ OpenAI に送ります。「画面の画像を ChatGPT に渡す」をオフにすると送りません。"
            else "「画面の画像を ChatGPT に渡す」がオフなので、画像は送りません (look_at_screen は使えません)。")
    }
}

/** 端末の操作の方式 (3 つから選べる) */
@Composable
private fun ControlCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val mode = remember(tick) { g.settings.controlMode }
    val a11yOn = remember(tick) { com.rabi.envoymcp.a11y.A11yService.instance != null }
    val using = remember(tick) {
        "操作: ${if (g.usingAdb()) "ADB" else "ユーザー補助"} / 読み取り: ${if (g.device.reader() === g.adbDevice) "ADB" else "ユーザー補助"}"
    }
    Card("端末の操作の方式", mode.label) {
        Note("ChatGPT に頼まれたとき、この端末をどの方法で操作するか。検証して決めるまで切り替えられます。")
        listOf(
            ControlMode.ADB to "ワイヤレスデバッグでコマンドを送る。Wi-Fi が要り、切れることがある。ChatGPT の強制停止ができる",
            ControlMode.HYBRID to "画面を読むのは ADB (漏れなし)、押す・入力・前面の判定はユーザー補助 (確実で速い)。どちらかが使えなければもう一方。ChatGPT の強制停止は ADB で行う",
            ControlMode.A11Y to "ユーザー補助だけ。Wi-Fi もペアリングも要らない。ChatGPT は「終了」を押して会話を終える (強制停止はしない)",
        ).forEach { (m, desc) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == m, onClick = { a.setControlMode(m) })
                Column(Modifier.weight(1f)) {
                    Text(m.label, color = Color.White, fontSize = 15.sp)
                    Text(desc, color = Color(0xFF9AA3B5), fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
        Line("今使っている", using)
        Line("ユーザー補助", if (a11yOn) "オン" else "オフ", if (mode == ControlMode.ADB) null else a11yOn)
        if (mode != ControlMode.ADB) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("ユーザー補助の設定を開く", !a11yOn) { a.openAccessibilitySettings() }
                Btn("アプリ情報") { a.openAppDetails() }
            }
            Note("ユーザー補助の設定で「ENVOY MCP (端末の操作)」をオンにします。オンにできないときは、アプリ情報の右上の︙から「制限付き設定を許可」を選んでから、もう一度オンにしてください。")
        }
    }
}

@Composable
private fun OperateCard(g: McpGraph, a: SettingsActivity, tick: Int) {
    val canOverlay = remember(tick) { g.overlay.canShow() }
    val shots = remember(tick) { g.settings.allowScreenshots }
    val bio = remember(tick) { g.settings.approvalBiometric }
    val approvalScreen = remember(tick) { g.settings.approvalScreen }
    val musicStage = remember(tick) { g.settings.musicStage }
    val musicFlash = remember(tick) { g.settings.musicFlash }
    val musicLyrics = remember(tick) { g.settings.musicLyrics }
    val stage = remember(tick) { g.settings.voiceStage }
    val level = remember(tick) { g.settings.voiceStageLevel }
    val primer = remember(tick) { g.settings.voicePrimer }
    var primerText by remember(tick) { mutableStateOf(g.settings.voicePrimerText) }
    var primerSaid by remember { mutableStateOf("") }
    val mic = remember(tick) { a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val peak by g.voiceStage.peak.collectAsState()
    val parts = remember(tick) { g.parts.usable() }
    Card("操作と表示", if (!canOverlay) "未許可あり" else when {
        approvalScreen && bio -> "承認画面と指紋"
        approvalScreen -> "承認画面"
        bio -> "指紋など"
        else -> "承認なし"
    }, if (!canOverlay) false else null) {
        Note("ChatGPT は、画面を読む・アプリを開く・タップ・文字入力・スクロール・部品の実行と登録ができます。送信・投稿・購入・削除・発信らしいボタンと設定のスイッチは、このスマホの承認画面で本人が承認してから押します。")
        Line("他のアプリの上", if (canOverlay) "許可" else "未許可 (操作の道具は動きません)", canOverlay)
        if (!canOverlay) Btn("許可の画面を開く", true) { a.openOverlayPermission() }
        Note("操作している間は、画面の縁を光らせ、リゲルを出します。リゲルをタップすると、操作を止めて、しばらく道具を受け付けません。")
        Toggle("確定の前に承認画面を出す", approvalScreen) { a.setApprovalScreen(it) }
        Toggle("承認のときに指紋などの本人確認をする", bio) { a.setApprovalBiometric(it) }
        Line("承認", when {
            approvalScreen && bio -> "承認画面のあと、指紋など"
            approvalScreen -> "承認画面で「確認して実行」"
            bio -> "確定しようとしている画面のまま、指紋など"
            else -> "なし (ChatGPT がそのまま確定します)"
        }, if (!approvalScreen && !bio) false else true)
        Note("送信・投稿・購入・削除・発信らしいボタンと設定のスイッチを押す前の確認です。承認画面では内容を見て「確認して実行」、指紋などは指紋・顔・PIN で確かめます。承認画面をオフにして指紋などだけオンにすると、確定しようとしている画面のまま確認を出します (何を確定するかは確認の画面に出ます)。どちらもオフにすると、確認なしで ChatGPT がそのまま確定します (聞き違いや、Web ページ・メッセージに仕込まれた文にだまされても止まりません)。ChatGPT からは承認できません (指紋などは本人だけ。承認画面は ENVOY MCP 自身の画面なので操作しない・自分で送った押しは受け付けない)。")
        Toggle("画面の画像を ChatGPT に渡す", shots) { a.setScreenshots(it) }
        Toggle("音楽が流れたらリゲルの画面にする", musicStage) { a.setMusicStage(it) }
        Note("YouTube Music・Spotify などで音楽が流れ始めたら、画面いっぱいのリゲルと、まわりにカラフルなスペクトラムを出します (動画では出しません)。下の × で閉じると、その曲の間は出しません。音楽が止まると閉じます。音に合わせて光らせるのにマイクの許可が要ります (端末の出力の音を読むため。マイクの音は使いません)。MCP サーバーが動いている間だけ。")
        if (musicStage && !a.hasMic()) Btn("マイクを許可する", true) { a.requestMic() }
        if (musicStage) {
            Toggle("音楽の画面で、拍に合わせて光らせる", musicFlash) { a.setMusicFlash(it) }
            Note("キックのたびに、スペクトラムとヘッドホンが明るくなります。光に敏感な方はオフにしてください (オフでも、スペクトラムの動きとヘッドホンは出ます)。")
            Toggle("音楽の画面に歌詞を出す", musicLyrics) { a.setMusicLyrics(it) }
            Note("歌われる時刻に合わせて、歌詞がリゲルの後ろに降ってきます (下に読むための今の行も)。歌詞は LRCLIB (lrclib.net) から取るので、曲名・アーティスト・アルバム・曲の長さをインターネットに送ります (音は送りません)。取った歌詞は端末にためます。日本の曲は、載っていない・時刻がずれていることがあります。歌詞には著作権があるので、公開する録画に映すときは気をつけてください。")
        }
        Btn("リゲルの画面を試しに出す") { g.musicStage.show() }
        Btn("音楽の表情を試す (約 50 秒)") { g.musicStage.showDemo() }
        Note("音楽を流さずに、決まった筋書きで音楽の画面を動かします: 静かなイントロ (目を閉じる) → ノり (ノり方 5 種類と、小節の頭の仕草 5 種類を順に) → だんだん強く (ため) → 盛り上がり (＞＜・ヘドバン) → 盛り上がりの後のにっこり → 静かな終わり。終わると閉じます (× でも閉じます)。")
        Toggle("音声モードの間は画面全体をリゲルにする", stage) { a.setVoiceStage(it) }
        Note("ChatGPT の音声モードの間 (前面が ChatGPT で、端末が通話モードのとき)、ホームと同じリゲルで画面全体を覆います。下の真ん中の × で会話を終えて ChatGPT を閉じます。ChatGPT のマイクのオフ・文字の入力は使えなくなります。端末の操作の接続が要ります。")
        Toggle("ChatGPT の声に合わせて動かす", level) { a.setVoiceStageLevel(it) }
        if (level) {
            Line("マイクの許可", if (mic) "許可" else "未許可", mic)
            Line("取れた声の大きさ", if (peak > 0f) "最大 ${(peak * 100).toInt()}%" else "まだ 0 (取れていない)", if (peak > 0f) true else null)
        }
        Note("声に合わせて動かすときは、ChatGPT が声を流している流れを端末の操作の接続 (ADB) で見つけて、その波形を読みます (マイクの音は使いません)。Android では、その読み取りにマイクの許可が要ります。話している間は声の波でふくらみ・目を開き・首を揺らし、間でまばたき・うなずき、強く言ったところで目を見開きます。黙っている間は聞く顔です。")
        Btn("リゲルの表情を順に見る (ホームで)") { a.previewExpressions() }
        Note("うれしい・笑う・驚き・悲しい・照れ・ウインクを、ホームのリゲルで順に見せます (全部で 20 秒ほど)。")
        Toggle("音声モードの始めに、ENVOY MCP を使うよう伝える", primer) { a.setVoicePrimer(it) }
        Note("リゲルのタップで音声モードを開いたとき、ChatGPT の入力欄に下の文を入れて送ります (ユーザー補助で。覆いの下で送ります)。ChatGPT はこの文に声で答えるので、最初の挨拶になります。リゲルのタップで新しく始めた会話に 1 回だけで、ChatGPT のアプリから直接始めた会話には送りません。送った文は ChatGPT の会話の履歴に残ります。ユーザー補助の接続が要ります。")
        if (primer) {
            OutlinedTextField(primerText, { primerText = it }, label = { Text("最初の一言") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("保存") { primerSaid = a.saveVoicePrimerText(primerText) }
                Btn("最初の文に戻す") { primerSaid = a.saveVoicePrimerText("") }
            }
            if (primerSaid.isNotEmpty()) Note(primerSaid)
        }
        Line("部品", "${parts.size} 個 (覚えたもの ${parts.count { it.source == "learned" }} 個)")
    }
}

@Composable private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    if (!shown(label)) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun TunnelCard(g: McpGraph, a: SettingsActivity, tick: Int, say: (String) -> Unit) {
    val st by g.tunnel.state.collectAsState()
    val log by g.tunnel.log.collectAsState()
    val installed = remember(tick) { g.tunnel.installed() }
    val enabled = remember(tick) { g.settings.tunnelEnabled }
    val hasKey = remember(tick) { g.tunnelKey.read() != null }
    var id by remember(tick) { mutableStateOf(g.settings.tunnelId) }
    var showLicense by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    Card("トンネル (スマホの中の tunnel-client)", st.label, when (st) { is TunnelClient.State.Ready -> true; is TunnelClient.State.Off, is TunnelClient.State.Starting -> null; else -> false }) {
        Note("ChatGPT とこのスマホを OpenAI の Secure MCP Tunnel でつなぐ中継 (tunnel-client v0.0.16、Apache 2.0) を、このアプリの中で動かします。Mac は要りません。MCP サーバーが動いている間だけ動きます。Mac の tunnel-client と同じトンネルで同時に動かさないでください。")
        Line("本体", if (installed) "入っています" else "入っていません (scripts/build_tunnel_client.sh でビルドして入れ直す)", installed)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("スマホの中で動かす", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { a.setTunnel(it) })
        }
        Line("状態", st.label, when (st) { is TunnelClient.State.Ready -> true; is TunnelClient.State.Off, is TunnelClient.State.Starting -> null; else -> false })
        if (g.tunnel.lastForwardAt > 0) Line("最後の取り次ぎ", SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(g.tunnel.lastForwardAt)))
        OutlinedTextField(id, { id = it }, label = { Text("tunnel_id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Btn("tunnel_id を保存") { say(a.saveTunnelId(id)) }
        Line("実行用キー", if (hasKey) "保存済み" else "未設定", hasKey)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("クリップボードから保存", true) { say(a.saveKeyFromClipboard()) }
            if (hasKey) Btn("消す") { a.clearKey(); say("実行用キーを消しました") }
        }
        Note("実行用キーは Platform の API keys で作り (Tunnels の Read + Use)、コピーしてから「クリップボードから保存」を押します。キーは画面に出さず、Android の Keystore で暗号化してこの端末だけに保存し、保存後にクリップボードを空にします。")
        if (log.isNotEmpty()) {
            Btn(if (showLog) "記録を閉じる" else "tunnel-client の記録を見る (${log.size} 行)") { showLog = !showLog }
            if (showLog) {
                Note("新しい順。キーと合言葉は伏せてあります。")
                log.take(12).forEach { Mono(it) }
            }
        }
        Btn(if (showLicense) "ライセンスを閉じる" else "tunnel-client のライセンス") { showLicense = !showLicense }
        if (showLicense) {
            val text = remember { runCatching { listOf("NOTICE", "LICENSE").joinToString("\n\n") { f -> a.assets.open("licenses/tunnel-client/$f").bufferedReader().readText() } }.getOrDefault("ライセンスの表示がありません (scripts/build_tunnel_client.sh で入ります)") }
            Mono(text)
        }
    }
}

/** 設定の検索 (SettingsSearch) と、今の言葉 (変わったら、カードと項目を見直す) */
private val LocalSearch = staticCompositionLocalOf { SettingsSearch() }
private val LocalQuery = compositionLocalOf { "" }
/** 今のカードの見出しと、見出しが検索に合っているか (合っていれば中は全部出す) */
private val LocalCard = compositionLocalOf { "" }
private val LocalShowAll = compositionLocalOf { true }

/** この項目を出すか (文字はカードごとに覚えておき、カードを出すかの判断に使う) */
@Composable private fun shown(vararg text: String): Boolean {
    val s = LocalSearch.current
    LocalQuery.current
    val card = LocalCard.current
    if (card.isNotEmpty()) text.forEach { s.add(card, it) }
    return LocalShowAll.current || s.itemHit(*text)
}

/**
 * 設定のカード。見出しの行 (右に今の状態を一言) を押すと開く・閉じる (開いたものは覚えておく)。探している間は、合うカードを開いて出す。
 * 閉じているときも中身を高さ 0 で作っておく (検索のために、項目の文字を覚えておくため。見えず、押せず、読み上げもしない)
 */
@Composable private fun Card(title: String, status: String = "", ok: Boolean? = null, content: @Composable () -> Unit) {
    val s = LocalSearch.current
    LocalQuery.current
    if (!s.cardVisible(title)) return
    val open = LocalOpen.current
    val expanded = s.active || open.isOpen(title)
    CompositionLocalProvider(LocalCard provides title, LocalShowAll provides s.titleHit(title)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF14171E))) {
            Row(Modifier.fillMaxWidth().clickable(enabled = !s.active) { open.toggle(title) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color(0xFFFFD86B), fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (status.isNotBlank()) Text((ok?.let { if (it) "● " else "○ " } ?: "") + status, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = when (ok) { true -> Color(0xFF8BE39A); false -> Color(0xFFFFB4A8); null -> Color(0xFFAEB4C0) },
                    modifier = Modifier.padding(start = 8.dp).widthIn(max = 170.dp))
                if (!s.active) Text(if (expanded) "▾" else "▸", color = Color(0xFF7F8796), fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
            }
            val body = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
            if (expanded) Column(body, verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
            else Hidden { Column(body) { content() } }
        }
    }
}

/** 開いているカード (見出し)。変えたら覚えておく */
private class OpenCards(initial: Set<String>, private val save: (Set<String>) -> Unit) {
    private val titles = mutableStateListOf<String>().apply { addAll(initial) }
    fun isOpen(title: String) = title in titles
    fun toggle(title: String) {
        if (title in titles) titles.remove(title) else titles.add(title)
        save(titles.toSet())
    }
}
private val LocalOpen = staticCompositionLocalOf { OpenCards(emptySet()) { } }
/** カードの中の小見出し */
@Composable private fun Sub(s: String) {
    if (!shown(s)) return
    Text(s, color = Color(0xFFFFD86B), fontSize = 15.sp, fontWeight = FontWeight.Bold)
}
@Composable private fun Line(k: String, v: String, ok: Boolean? = null) {
    if (!shown(k, v)) return
    Row(Modifier.fillMaxWidth()) {
        Text(k, color = Color(0xFFAEB4C0), fontSize = 14.sp, modifier = Modifier.width(90.dp))
        Text((ok?.let { if (it) "● " else "○ " } ?: "") + v, color = when (ok) { true -> Color(0xFF8BE39A); false -> Color(0xFFFFB4A8); null -> Color.White }, fontSize = 14.sp)
    }
}
@Composable private fun Note(s: String) {
    if (!shown(s)) return
    Text(s, color = Color(0xFF9AA3B5), fontSize = 13.sp, lineHeight = 19.sp)
}
@Composable private fun Btn(s: String, primary: Boolean = false, onClick: () -> Unit) {
    if (!shown(s)) return
    if (primary) Button(onClick = onClick) { Text(s) } else OutlinedButton(onClick = onClick) { Text(s, color = Color.White) }
}
@Composable private fun Mono(s: String) {
    if (!shown(s)) return
    Text(s, color = Color(0xFFCFD5E0), fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
}
