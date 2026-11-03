package com.rabi.envoymcp.monitor

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.core.Mood
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.device.ActionResult
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * モニター (PC モード) のリゲル。モニターにつないだら、モニターに全画面のリゲルがいつも居て、
 * 頼んだら YouTube などをモニターに開く (そのあとの操作はモニターの画面で)。
 * - つながったら、モニターに全画面のリゲルの窓 (MonitorActivity) を開く。アプリはその上に窓として開き、閉じればリゲルが見える。
 *   ほかのアプリの上に重ねる窓 (オーバーレイ) を全画面にすると、開いたアプリまで隠すので、全画面のリゲルはアプリの窓にする
 * - つながっている間は、道具の操作先 (target) をモニターにする (読む・押す・撮る・アプリを開く。A11yDeviceController)
 * - 操作している間は、モニターの右下に小さなリゲルを重ねる (していることを出し、クリックで止める。アプリの窓に隠れないように)
 * - 外れたら、操作先を本体に戻し、全画面のリゲルの窓を閉じる
 */
class MonitorStage(private val g: McpGraph) {
    private val dm = g.context.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** 道具の操作先の画面 (ふだんは本体 0。モニターにつながっている間はモニター) */
    @Volatile var target = Display.DEFAULT_DISPLAY
        private set

    /** 開いている全画面のリゲルの窓 */
    @Volatile var activity: MonitorActivity? = null

    private var badge: Pair<WindowManager, View>? = null
    private var badgeOwner: Owner? = null
    private var started = false

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = refresh()
        override fun onDisplayRemoved(id: Int) = refresh()
        override fun onDisplayChanged(id: Int) = refresh()
    }

    /** 見張りを始める (サービスが動き始めたとき) */
    fun start() {
        if (started) return
        started = true
        dm.registerDisplayListener(listener, main)
        // 操作している間は、モニターの右下に小さなリゲルを重ねる
        g.scope.launch { g.state.map { it.mood == Mood.ACTING }.distinctUntilChanged().collect { acting -> setBadge(acting) } }
        refresh()
    }

    /** 外部の画面 (モニター)。アプリが作った見えない画面 (FLAG_PRIVATE) は除く */
    fun external(): Display? = dm.displays.firstOrNull {
        it.displayId != Display.DEFAULT_DISPLAY && it.state == Display.STATE_ON && it.flags and Display.FLAG_PRIVATE == 0
    }

    /** つながった・外れた・設定が変わった */
    fun refresh() { main.post { refreshNow() } }

    private fun refreshNow() {
        val ext = if (g.settings.monitorStage) external() else null
        val t = ext?.displayId ?: Display.DEFAULT_DISPLAY
        if (t != target) {
            target = t
            RabiLog.i("monitor", if (ext != null) "target monitor ${ext.name}" else "target phone")
            // 音声モードの最中につながった・外れた (ENVOY MCP が起動し直したときも): 声の出口を合わせる
            val inCall = g.context.getSystemService(android.media.AudioManager::class.java).mode == android.media.AudioManager.MODE_IN_COMMUNICATION
            routeCallAudio(inCall && ext != null)
        }
        if (ext != null) ensureStage(ext) else closeStage()
        if (ext == null) setBadge(false)
    }

    /** 全画面のリゲルの窓が、モニターに無ければ開く */
    private fun ensureStage(d: Display) {
        val a = activity
        if (a != null && !a.isFinishing && a.display?.displayId == d.displayId) return
        open(d)
    }

    private fun open(d: Display) {
        val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") d.getRealMetrics(it) }
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId).setLaunchBounds(Rect(0, 0, m.widthPixels, m.heightPixels))
        // 全画面の窓として開くよう頼む (公開されていない設定なので、使えなければ自由な大きさの窓のまま。開いたあと MonitorActivity でも頼む)
        runCatching { ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(opts, WINDOWING_MODE_FULLSCREEN) }
            .onFailure { RabiLog.i("monitor", "launch fullscreen not allowed: ${it.javaClass.simpleName}") }
        // 裏から画面を開くには、見えている窓が要る: 小さなリゲルを少しの間だけ重ねてから開く
        val tmp = badge == null
        if (tmp) showBadge(d)
        runCatching {
            g.context.startActivity(Intent(g.context, MonitorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), opts.toBundle())
            RabiLog.i("monitor", "stage opened on ${d.displayId}")
        }.onFailure { RabiLog.w("monitor", "stage open failed: ${it.javaClass.simpleName}") }
        if (tmp) main.postDelayed({ if (g.state.value.mood != Mood.ACTING) hideBadge() }, 2_000)
    }

    private fun closeStage() {
        activity?.let { runCatching { it.finish() } }
        activity = null
    }

    /** モニターのいちばん上のアプリ (ENVOY MCP 自身の窓は除く。無ければ null) */
    fun topApp(): String? {
        val svc = com.rabi.envoymcp.a11y.A11yService.instance ?: return null
        return svc.windowsOn(target).sortedByDescending { it.layer }
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root?.packageName?.toString() }.firstOrNull { it != g.context.packageName }
    }

    /** モニターの「ホーム」: 全画面のリゲルを前に出す */
    fun toFront(): ActionResult {
        val d = external() ?: return ActionResult.fail("モニターがつながっていません")
        open(d)
        return ActionResult.OK
    }

    // ---------------------------------------------------------------- 操作中の小さなリゲル

    private fun setBadge(on: Boolean) {
        main.post {
            val d = if (target != Display.DEFAULT_DISPLAY) external() else null
            if (on && d != null) showBadge(d) else hideBadge()
        }
    }

    private fun showBadge(d: Display) {
        if (badge != null) return
        runCatching {
            val wctx = g.context.createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            val wm = wctx.getSystemService(WindowManager::class.java)
            val o = Owner().also { it.start(); badgeOwner = it }
            val v = ComposeView(wctx).apply {
                setViewTreeLifecycleOwner(o)
                setViewTreeSavedStateRegistryOwner(o)
                setContent { Badge(g) { g.stopByUser("monitor") } }
            }
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END; x = 36; y = 72
            }
            wm.addView(v, lp)
            badge = wm to v
        }.onFailure { RabiLog.w("monitor", "badge failed: ${it.javaClass.simpleName}"); hideBadge() }
    }

    private fun hideBadge() {
        badge?.let { (wm, v) -> runCatching { wm.removeView(v) } }
        badge = null
        badgeOwner?.stop(); badgeOwner = null
    }

    // ---------------------------------------------------------------- 音声モードの声をモニターのスピーカーへ

    /**
     * ChatGPT の音声モード (通話) の音の出口を、モニターにできるならする (画面はスマホ、声はモニターのスピーカーで。
     * ChatGPT をどこから開いても)。Android が通話の出口として選ばせる機器 (getAvailableCommunicationDevices) にモニターがあるときだけ
     * (Pixel 10 では、受話口・スピーカー・HDMI (モニター) が選べる)。ENVOY MCP が止まると Android が元に戻す
     */
    fun routeCallAudio(on: Boolean) {
        val am = g.context.getSystemService(android.media.AudioManager::class.java)
        if (!on) { runCatching { am.clearCommunicationDevice() }; return }
        if (target == Display.DEFAULT_DISPLAY) return
        val devs = runCatching { am.availableCommunicationDevices }.getOrDefault(emptyList())
        RabiLog.i("monitor", "call outputs: " + devs.joinToString(", ") { outName(it.type) })
        val mon = devs.firstOrNull { it.type in MONITOR_OUTPUTS }
        if (mon == null) { RabiLog.i("monitor", "call audio cannot go to the monitor (not a call output)"); return }
        val ok = runCatching { am.setCommunicationDevice(mon) }.getOrDefault(false)
        RabiLog.i("monitor", "call audio to monitor (${outName(mon.type)}): $ok")
    }

    private fun outName(t: Int) = when (t) {
        android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "受話口"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "スピーカー"
        android.media.AudioDeviceInfo.TYPE_HDMI -> "HDMI (モニター)"
        android.media.AudioDeviceInfo.TYPE_HDMI_ARC, android.media.AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI ARC"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        android.media.AudioDeviceInfo.TYPE_BLE_HEADSET, android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有線"
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        else -> "その他"
    }

    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        private val reg = LifecycleRegistry(this)
        private val ssr = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = reg
        override val savedStateRegistry: SavedStateRegistry get() = ssr.savedStateRegistry
        fun start() { ssr.performRestore(null); reg.currentState = Lifecycle.State.RESUMED }
        fun stop() { if (reg.currentState != Lifecycle.State.DESTROYED) reg.currentState = Lifecycle.State.DESTROYED }
    }

    private companion object {
        /** 全画面の窓 (WindowConfiguration.WINDOWING_MODE_FULLSCREEN) */
        const val WINDOWING_MODE_FULLSCREEN = 1
        /** モニターの音の出口の種類 (DisplayPort の音は HDMI として見える) */
        val MONITOR_OUTPUTS = setOf(android.media.AudioDeviceInfo.TYPE_HDMI, android.media.AudioDeviceInfo.TYPE_HDMI_ARC, android.media.AudioDeviceInfo.TYPE_HDMI_EARC)
    }
}

/** 操作している間、モニターの右下に重ねる小さなリゲル (していること。クリックで止める) */
@Composable
private fun Badge(g: McpGraph, onStop: () -> Unit) {
    val st by g.state.collectAsState()
    Row(Modifier.background(Color(0xE60B0D12), RoundedCornerShape(22.dp)).clickable { onStop() }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(40.dp).background(Brush.radialGradient(listOf(Color(0xFFBFEFFF), Color(0xFF3FA9F5), Color(0xFF0B2A4A))), CircleShape))
        Column(Modifier.widthIn(max = 320.dp)) {
            Text(st.caption.ifBlank { "リゲル" }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text("クリックで止める", color = Color(0xFFA8DFFF), fontSize = 12.sp)
        }
    }
}
