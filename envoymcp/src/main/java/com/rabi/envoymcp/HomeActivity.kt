package com.rabi.envoymcp

import com.rabi.envoymcp.mcp.ActivityLog
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.rabi.envoymcp.ui.avatar.Avatar
import com.rabi.envoymcp.ui.avatar.AvatarGeometry
import com.rabi.envoymcp.ui.avatar.AvatarSignals
import com.rabi.envoymcp.ui.activity.ActivityActivity
import com.rabi.envoymcp.ui.avatar.AvatarStyle
import com.rabi.envoymcp.ui.calllog.CallLogActivity
import com.rabi.envoymcp.ui.settings.SettingsActivity

/**
 * ENVOY MCP のホーム画面。リゲルと「設定」だけを出す (MCP サーバーの状態は設定の画面と通知で分かる)。
 * - 何もしていないときにリゲルをタップすると、話し相手の AI (ChatGPT か Claude) のアプリ (できれば音声モード) を開く
 * - 子どもモードの間は、リゲルのタップでお勉強を開く (McpGraph.openKids)
 * - 道具が動いている間にタップすると、止める (ChatGPT を開くのとは分ける)
 * - 下の「設定」から、MCP サーバーのオン・オフと接続の情報。左下の「したこと」で、AI エージェントとリゲルがしたことを見る
 * - 右上の子どもモードのボタン: オフのときは小さく「まなび」(押すと子どもモードに入り、お勉強を開く)。
 *   オンのときは大きく「おべんきょう」(押すとお勉強を開く)。子どもモードを切るのは、お勉強の画面の「おとなの人」か設定から (ご本人か確かめる)
 */
class HomeActivity : ComponentActivity() {
    private val g by lazy { (application as McpApp).graph }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // モニター (PC モード) に開いた (タスクバーのアイコンなど): スマホ用のホームではなく、モニターの全画面のリゲルを前に出す
        if ((display?.displayId ?: android.view.Display.DEFAULT_DISPLAY) != android.view.Display.DEFAULT_DISPLAY) {
            g.monitorStage.toFront()
            finish()
            return
        }
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        // ホームなので戻るボタンでは閉じない
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {} })
        setContent {
            HomeStage(g, onTap = { g.tapRigel("home") }, onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onCalls = { startActivity(Intent(this, CallLogActivity::class.java)) },
                onActivity = { startActivity(Intent(this, ActivityActivity::class.java)) },
                onKids = {
                    // 子どもモードに入るのは安全を強める変更なので、確かめずにすぐ入る
                    if (!g.settings.kidsMode) g.kids.setKidMode(true, ActivityLog.USER)
                    g.openKids("home")
                })
        }
    }

    override fun onResume() {
        super.onResume()
        g.homeVisible = true
        g.overlay.hide()
        // 本人がオンにしているのに止まっている (アプリが落ちた など) ときは、見えているこの時に動かし直す
        if (g.settings.serverEnabled && !g.server.running) McpService.start(this)
    }

    override fun onPause() {
        g.homeVisible = false
        super.onPause()
    }
}

@Composable
internal fun HomeStage(g: McpGraph, onTap: () -> Unit, onSettings: () -> Unit, big: Boolean = false, onCalls: (() -> Unit)? = null,
                       onActivity: (() -> Unit)? = null, onKids: (() -> Unit)? = null) {
    val st by g.state.collectAsState()
    val density = LocalDensity.current
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val solid = remember(resumed) { g.settings.avatar3d }
    // 子どもモードか (見えるたびに読み直す)
    val kidMode = remember(resumed) { g.settings.kidsMode }
    // まだ見ていない通話の記録の数 (「通話の記録」の横に出す)
    val unseen by g.callRecords.unseen.collectAsState()
    val notes by g.notices.notes.collectAsState()
    LaunchedEffect(resumed) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { g.callRecords.refreshUnseen() } }
    Box(Modifier.fillMaxSize().background(Color(0xFF080B10))) {
        Avatar(
            st.mood, AvatarStyle.byId(g.settings.avatar), 0f,
            Modifier.fillMaxSize()
                .semantics {
                    contentDescription = if (kidMode) "リゲル。タッチすると おべんきょうが はじまります"
                    else "リゲル。タップすると音声の会話を始めます。操作中にタップすると止めます"
                }
                .pointerInput(Unit) {
                    // 球の上 (少し外側まで) を短くタップしたときだけ。指を動かした (球をつまんだ) ものはタップにしない
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 自分で送ったタップ・スワイプ (操作の途中) は、人のタッチとして扱わない
                        if (g.injectingInput()) return@awaitEachGesture
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val c = AvatarGeometry.center(w, h, true, big)
                        val r = AvatarGeometry.radius(w, h, true, with(density) { 360.dp.toPx() }, big)
                        if ((down.position - c).getDistance() > r * 1.25f) return@awaitEachGesture
                        var far = 0f
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            far = maxOf(far, (ch.position - down.position).getDistance())
                            if (!ch.pressed) break
                        }
                        if (far <= viewConfiguration.touchSlop && !g.injectingInput()) onTap()
                    }
                },
            signals = AvatarSignals(st.wakeSeq, st.stepSeq, st.stepKind, st.doneSeq, expression = st.expression, expressionSeq = st.expressionSeq,
                expressionHoldMs = st.expressionHoldMs),
            fullScreen = true, allow3d = solid, big = big,
        )
        // 右上: 子どもモードのボタン (オフのときは小さく、オンのときは子どもにも分かるよう大きく黄色に)
        if (onKids != null) KidsButton(kidMode, onKids, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 12.dp, end = 16.dp))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (st.caption.isNotBlank()) Text(st.caption, color = Color(0xFFE8ECF2), fontSize = 22.sp, textAlign = TextAlign.Center, maxLines = 2)
            // 子どもモード: リゲルのタップでお勉強が始まる (お知らせは、子どもモードを切ってから伝える)
            else if (kidMode) Text("リゲルを タッチして おべんきょう しよう", color = Color(0xFFFFD86B),
                fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(bottom = 4.dp))
            // まだ伝えていないお知らせ (電話のあとのこと・定期の依頼の結果) がある (リゲルをタップすると、音声モードの最初に伝える)
            else if (notes.isNotEmpty()) Text("お知らせが ${notes.size} 件あります。リゲルをタップすると伝えます", color = Color(0xFF8FB4FF),
                fontSize = 16.sp, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(bottom = 4.dp))
            // 設定は真ん中、したこと (AI エージェントとリゲルがしたこと) は左端、通話の記録 (リゲルが代わりにかけた・出た電話) は右端
            Box(Modifier.fillMaxWidth()) {
                if (onActivity != null) TextButton(onClick = onActivity, modifier = Modifier.align(Alignment.CenterStart)) {
                    Text("したこと", color = Color(0xFF5E6676), fontSize = 15.sp)
                }
                TextButton(onClick = onSettings, modifier = Modifier.align(Alignment.Center)) { Text("設定", color = Color(0xFF5E6676), fontSize = 15.sp) }
                if (onCalls != null) TextButton(onClick = onCalls, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text("通話の記録", color = Color(0xFF5E6676), fontSize = 15.sp)
                    if (unseen > 0) Text(if (unseen > 9) "9+" else "$unseen", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp).background(Color(0xFF2F6FED), RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
        }
    }
}

/** ホームの右上の子どもモードのボタン。kidMode なら「おべんきょう」(大きく黄色)、そうでなければ「まなび」(小さく控えめ) */
@Composable
private fun KidsButton(kidMode: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val ink = if (kidMode) Color(0xFF3A2A00) else Color(0xFF8A93A4)
    Row(
        modifier.clip(RoundedCornerShape(28.dp))
            .background(if (kidMode) Color(0xFFFFD86B) else Color(0x1AFFFFFF))
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (kidMode) "おべんきょうを はじめる" else "子どもモードに入って、お勉強をはじめる" }
            .padding(horizontal = if (kidMode) 22.dp else 14.dp, vertical = if (kidMode) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Star, contentDescription = null, tint = ink, modifier = Modifier.size(if (kidMode) 26.dp else 18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (kidMode) "おべんきょう" else "まなび", color = ink, fontSize = if (kidMode) 22.sp else 15.sp, fontWeight = FontWeight.Bold)
    }
}
