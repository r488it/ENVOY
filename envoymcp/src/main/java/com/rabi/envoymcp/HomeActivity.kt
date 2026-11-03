package com.rabi.envoymcp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.rabi.launcher.ui.Avatar
import com.rabi.launcher.ui.AvatarGeometry
import com.rabi.launcher.ui.AvatarSignals
import com.rabi.launcher.ui.AvatarStyle

/**
 * ENVOY MCP のホーム画面。リゲルと「設定」だけを出す (MCP サーバーの状態は設定の画面と通知で分かる)。
 * - 何もしていないときにリゲルをタップすると、ChatGPT のアプリ (できれば音声モード) を開く
 * - 道具が動いている間にタップすると、止める (ChatGPT を開くのとは分ける)
 * - 下の「設定」から、MCP サーバーのオン・オフと接続の情報
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
                onCalls = { startActivity(Intent(this, CallLogActivity::class.java)) })
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
internal fun HomeStage(g: McpGraph, onTap: () -> Unit, onSettings: () -> Unit, big: Boolean = false, onCalls: (() -> Unit)? = null) {
    val st by g.state.collectAsState()
    val density = LocalDensity.current
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val solid = remember(resumed) { g.settings.avatar3d }
    // まだ見ていない通話の記録の数 (「通話の記録」の横に出す)
    val unseen by g.callRecords.unseen.collectAsState()
    LaunchedEffect(resumed) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { g.callRecords.refreshUnseen() } }
    Box(Modifier.fillMaxSize().background(Color(0xFF080B10))) {
        Avatar(
            st.mood, AvatarStyle.byId(g.settings.avatar), 0f,
            Modifier.fillMaxSize()
                .semantics { contentDescription = "リゲル。タップすると ChatGPT を開きます。操作中にタップすると止めます" }
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
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (st.caption.isNotBlank()) Text(st.caption, color = Color(0xFFE8ECF2), fontSize = 22.sp, textAlign = TextAlign.Center, maxLines = 2)
            // 設定は真ん中、通話の記録 (リゲルが代わりにかけた・出た電話) は同じ高さの右端
            Box(Modifier.fillMaxWidth()) {
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
