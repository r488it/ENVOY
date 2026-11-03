package com.rabi.launcher.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.rabi.launcher.conversation.EngineState
import com.rabi.launcher.conversation.Mood
import com.rabi.launcher.core.RabiLog
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 他のアプリを使っている間、そのアプリの上に出すリゲル (オーバーレイ)。窓は 3 つ:
 * - 球: 小さな触れる窓。最初は画面の下の中央。押したまま動かすと好きな位置へ動かせる (離した位置を覚える)。
 *   タップ (onTap) と長押し (onHoldStart / onHoldEnd。押している間は話を聞く)
 * - 字幕の吹き出し: 触れない窓。話している・作業している間だけ、球の上 (球が上半分にあれば下) に出す
 * - 縁の光: 画面全体の触れない窓。作業中 (show / hide) だけ、画面の縁をうっすら光らせる
 * 出すとき: ENVOY MCP の画面が前に出ていない (setOtherAppInFront) 間。設定で切っていても、作業中・返事の途中は出す。承認画面の間は出さない (suppress)
 * - Android 12 以降、他アプリの窓を通り抜けるタッチは不透明度が 0.8 を超えるとブロックされるため、alpha は 0.8 以下
 * - アクセシビリティの木には出さない (UI 階層の取得に混ざらないように)。画面の画像を取る瞬間だけ隠す (hideWhile)
 * - ENVOY MCP がタップ・スワイプを送る間は、球を素通りにする (passThroughWhile)
 * - ロック画面より上には出せない (TYPE_APPLICATION_OVERLAY の仕様)
 * 4 つ目の窓「ステージ」: ChatGPT の音声モードの間、画面全体をホームと同じリゲルで覆う (setStage)。
 * - 中身 (リゲル・言葉・下の真ん中の ×) とタッチは stageContent (Compose) が受け持つ。下に通さないので、不透明度の制限はかからない
 * - ステージを出している間は、小さな球と字幕は出さない (リゲルが 2 つにならないように)
 */
class OperationOverlay(
    private val context: Context,
    private val state: StateFlow<EngineState>,
    private val style: () -> AvatarStyle,
    /** 他のアプリの上に、いつもリゲルを出すか (設定) */
    private val enabled: () -> Boolean = { true },
    /** 球の位置 (画面の中で動かせる幅に対する割合 0〜1)。負なら最初の位置 (下の中央) */
    private val loadPos: () -> Pair<Float, Float> = { -1f to -1f },
    private val savePos: (Float, Float) -> Unit = { _, _ -> },
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val density get() = context.resources.displayMetrics.density

    /** 作業中 (縁を光らせる)。env.showOverlay で切り替わる */
    @Volatile var wanted = false; private set
    @Volatile private var otherApp = false
    @Volatile private var suppressed = false
    @Volatile private var hiddenDepth = 0
    @Volatile private var passDepth = 0

    /** 球をタップした */
    var onTap: (() -> Unit)? = null
    /** 球を長押ししたとき (押している間は話を聞く。離したら終わり) */
    var onHoldStart: (() -> Unit)? = null
    var onHoldEnd: (() -> Unit)? = null

    private class Win(val view: View, val lp: WindowManager.LayoutParams, val owner: OverlayOwner?)
    private var orb: Win? = null
    private var caption: Win? = null
    private var glow: Win? = null
    private var stageWin: Win? = null
    @Volatile private var stageOn = false
    /** ステージに描くもの (ENVOY MCP が渡す。タッチもここで受ける) */
    var stageContent: (@Composable () -> Unit)? = null

    /** ステージ (画面全体のリゲル) を出す・消す */
    fun setStage(on: Boolean) { if (on != stageOn) { stageOn = on; update() } }

    init {
        // 話している・作業しているかで、球 (設定で切っているとき) と字幕を出し分ける
        scope.launch { state.map { it.mood != Mood.IDLE }.distinctUntilChanged().collect { update() } }
    }

    fun canShow() = Settings.canDrawOverlays(context)

    /** 作業を始めた: 縁を光らせる (球も出す) */
    fun show() { wanted = true; update() }

    /** 作業が終わった */
    fun hide() { wanted = false; update() }

    /** ENVOY MCP の画面が前に出ていない (他のアプリを使っている) か */
    fun setOtherAppInFront(on: Boolean) { otherApp = on; update() }

    /** 承認画面などの間だけ消す。戻すと出し直す */
    fun suppress(on: Boolean) { suppressed = on; update() }

    /** 設定を変えたとき */
    fun refresh() = update()

    private fun update() = main.post {
        val active = wanted || state.value.mood != Mood.IDLE
        val can = !suppressed && canShow()
        if (stageOn && can) addStage() else { remove(stageWin); stageWin = null }
        val showOrb = otherApp && can && stageWin == null && (enabled() || active)
        if (showOrb) addOrb() else { remove(caption); remove(orb); caption = null; orb = null }
        if (otherApp && can && wanted) addGlow() else remove(glow).also { glow = null }
        if (showOrb && active) addCaption() else remove(caption).also { caption = null }
    }

    // ---------------------------------------------------------------- ステージ (音声モードの真ん中)

    private fun addStage() {
        if (stageWin != null) return
        val o = OverlayOwner().also { it.start() }
        val v = composeView(o) { stageContent?.invoke() }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or (if (passDepth > 0) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "RabiVoiceStage"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (hiddenDepth > 0) v.visibility = View.INVISIBLE
        runCatching { wm.addView(v, lp) }.onFailure { RabiLog.w("overlay", "stage failed ${it.javaClass.simpleName}"); o.stop(); return }
        stageWin = Win(v, lp, o)
    }

    // ---------------------------------------------------------------- 球

    private fun addOrb() {
        if (orb != null) return
        val o = OverlayOwner().also { it.start() }
        val size = (ORB_DP * density).toInt()
        val v = composeView(o) { OrbContent(state, style()) }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or (if (passDepth > 0) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = MAX_ALPHA
            title = "RabiOverlayOrb"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        placeOrb(lp)
        attachTouch(v, lp)
        if (hiddenDepth > 0) v.visibility = View.INVISIBLE
        runCatching { wm.addView(v, lp) }.onFailure { RabiLog.w("overlay", "orb failed ${it.javaClass.simpleName}"); o.stop(); return }
        orb = Win(v, lp, o)
    }

    /** 画面の大きさと、下のナビゲーションの高さ */
    private fun screen(): Triple<Int, Int, Int> {
        val m = wm.currentWindowMetrics
        val nav = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()).bottom
        return Triple(m.bounds.width(), m.bounds.height(), nav)
    }

    /** 覚えた位置 (無ければ下の中央) に置く */
    private fun placeOrb(lp: WindowManager.LayoutParams) {
        val (w, h, nav) = screen()
        val (fx, fy) = loadPos()
        val maxX = w - lp.width; val maxY = h - lp.height
        lp.x = if (fx in 0f..1f) (fx * maxX).toInt() else maxX / 2
        lp.y = if (fy in 0f..1f) (fy * maxY).toInt() else maxY - nav - (BOTTOM_MARGIN_DP * density).toInt()
    }

    /** 短く離せばタップ、HOLD_MS 押し続ければ長押し (離したら終わり)、押したまま動かせば移動 */
    private fun attachTouch(v: View, lp: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false; var holding = false
        val startHold = Runnable { if (!dragging) { holding = true; onHoldStart?.invoke() } }
        @Suppress("ClickableViewAccessibility")
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    dragging = false; holding = false
                    if (onHoldStart != null) main.postDelayed(startHold, HOLD_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && !holding && hypot(dx, dy) > slop) { dragging = true; main.removeCallbacks(startHold) }
                    if (dragging) {
                        val (w, h, _) = screen()
                        lp.x = (startX + dx).toInt().coerceIn(0, w - lp.width)
                        lp.y = (startY + dy).toInt().coerceIn(0, h - lp.height)
                        runCatching { wm.updateViewLayout(v, lp) }
                        placeCaption()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(startHold)
                    when {
                        holding -> onHoldEnd?.invoke()
                        dragging -> { val (w, h, _) = screen(); savePos(lp.x.toFloat() / (w - lp.width).coerceAtLeast(1), lp.y.toFloat() / (h - lp.height).coerceAtLeast(1)) }
                        else -> onTap?.invoke()
                    }
                    holding = false; dragging = false
                }
                MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(startHold); if (holding) onHoldEnd?.invoke(); holding = false; dragging = false }
            }
            true
        }
    }

    // ---------------------------------------------------------------- 字幕の吹き出し

    private fun addCaption() {
        if (caption == null) {
            val o = OverlayOwner().also { it.start() }
            val v = composeView(o) { CaptionContent(state) }
            val lp = WindowManager.LayoutParams(
                1, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                alpha = MAX_ALPHA
                title = "RabiOverlayCaption"
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (hiddenDepth > 0) v.visibility = View.INVISIBLE
            runCatching { wm.addView(v, lp) }.onFailure { RabiLog.w("overlay", "caption failed ${it.javaClass.simpleName}"); o.stop(); return }
            caption = Win(v, lp, o)
        }
        placeCaption()
    }

    /** 球の上 (球が画面の上半分にあれば下) の、球を中心にした位置 */
    private fun placeCaption() {
        val c = caption ?: return
        val o = orb?.lp ?: return
        val (w, h, _) = screen()
        val gap = (8 * density).toInt()
        val lp = c.lp
        lp.width = (w * CAPTION_WIDTH).toInt()
        lp.x = (o.x + o.width / 2 - lp.width / 2).coerceIn(0, w - lp.width)
        if (o.y + o.height / 2 > h / 2) { lp.gravity = Gravity.BOTTOM or Gravity.START; lp.y = h - o.y + gap }
        else { lp.gravity = Gravity.TOP or Gravity.START; lp.y = o.y + o.height + gap }
        runCatching { wm.updateViewLayout(c.view, lp) }
    }

    // ---------------------------------------------------------------- 縁の光 (作業中)

    private fun addGlow() {
        if (glow != null) return
        val v = composeView(null) { GlowContent(style()) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = MAX_ALPHA
            title = "RabiOperationOverlay"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (hiddenDepth > 0) v.visibility = View.INVISIBLE
        runCatching { wm.addView(v, lp) }.onFailure { RabiLog.w("overlay", "glow failed ${it.javaClass.simpleName}"); return }
        glow = Win(v, lp, (v.tag as? OverlayOwner))
        // 球より下に重ねる (球を後から出し直す)
        orb?.let { w -> runCatching { wm.removeView(w.view); wm.addView(w.view, w.lp) } }
    }

    // ---------------------------------------------------------------- 共通

    private fun composeView(owner: OverlayOwner?, content: @Composable () -> Unit): ComposeView {
        val o = owner ?: OverlayOwner().also { it.start() }
        return ComposeView(context).apply {
            tag = o
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setContent(content)
        }
    }

    private fun remove(w: Win?) {
        w ?: return
        runCatching { wm.removeView(w.view) }
        (w.owner ?: w.view.tag as? OverlayOwner)?.stop()
    }

    private fun removeAll() {
        remove(glow); remove(caption); remove(orb); remove(stageWin)
        glow = null; caption = null; orb = null; stageWin = null
    }

    /** ENVOY MCP がタップ・スワイプを送る間、球を素通りにする (球の下にある部品も押せるように) */
    suspend fun <T> passThroughWhile(block: suspend () -> T): T {
        if (orb == null && stageWin == null) return block()
        setPass(true)
        try {
            kotlinx.coroutines.delay(40)   // 窓の設定が効くまで
            return block()
        } finally {
            setPass(false)
        }
    }

    private suspend fun setPass(on: Boolean) = suspendCancellableCoroutine<Unit> { c ->
        main.post {
            passDepth = (passDepth + if (on) 1 else -1).coerceAtLeast(0)
            listOfNotNull(orb, stageWin).forEach { w ->
                w.lp.flags = if (passDepth > 0) w.lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else w.lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(w.view, w.lp) }
            }
            c.resume(Unit)
        }
    }

    /** 画面の画像を取る間だけ隠す (映り込み防止)。描画が消えるのを 2 フレームほど待つ */
    suspend fun <T> hideWhile(block: suspend () -> T): T {
        if (orb == null && glow == null && stageWin == null) return block()
        setHidden(true)
        try {
            kotlinx.coroutines.delay(60)
            return block()
        } finally {
            setHidden(false)
        }
    }

    private suspend fun setHidden(h: Boolean) = suspendCancellableCoroutine<Unit> { c ->
        main.post {
            hiddenDepth = (hiddenDepth + if (h) 1 else -1).coerceAtLeast(0)
            val vis = if (hiddenDepth > 0) View.INVISIBLE else View.VISIBLE
            listOfNotNull(orb, caption, glow, stageWin).forEach { it.view.visibility = vis }
            c.resume(Unit)
        }
    }

    /** オーバーレイの窓に Compose を載せるための持ち主 (Activity の代わり) */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val reg = LifecycleRegistry(this)
        private val ssr = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = reg
        override val savedStateRegistry: SavedStateRegistry get() = ssr.savedStateRegistry
        fun start() { ssr.performRestore(null); reg.currentState = Lifecycle.State.RESUMED }
        fun stop() { if (reg.currentState != Lifecycle.State.DESTROYED) reg.currentState = Lifecycle.State.DESTROYED }
    }

    companion object {
        /** InputManager.getMaximumObscuringOpacityForTouch() の既定値 (0.8) 以下 */
        const val MAX_ALPHA = 0.78f
        /** 球の窓の大きさ */
        const val ORB_DP = 84
        /** 最初の位置: 画面の下 (ナビゲーションの上) からの余白 */
        const val BOTTOM_MARGIN_DP = 24
        /** 字幕の吹き出しの幅 (画面の幅に対する割合) */
        const val CAPTION_WIDTH = 0.8f
        /** これだけ押し続けたら長押し */
        const val HOLD_MS = 450L
    }
}

@Composable
private fun OrbContent(state: StateFlow<EngineState>, style: AvatarStyle) {
    val s by state.collectAsState()
    Box(Modifier.fillMaxSize().padding(4.dp).background(Color(0xCC0B0D12), CircleShape)) {
        // 何もしていない間は動きを控えめにする (電池のため)
        Avatar(mood = s.mood, style = style, level = 0f, modifier = Modifier.fillMaxSize().padding(4.dp),
            signals = AvatarSignals(s.wakeSeq, s.stepSeq, s.stepKind, s.doneSeq), reduceMotion = s.mood == Mood.IDLE)
    }
}

@Composable
private fun CaptionContent(state: StateFlow<EngineState>) {
    val s by state.collectAsState()
    val text = s.caption.ifBlank { if (s.mood == Mood.ACTING) "操作しています" else "" }
    if (text.isBlank()) return
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text, color = Color(0xFFF2F2F2), fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 3,
            modifier = Modifier.background(Color(0xE60B0D12), RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun GlowContent(style: AvatarStyle) {
    val glow = style.glow
    // 画面の縁をうっすら光らせて「AI が操作中」と分かるようにする
    Box(Modifier.fillMaxSize().drawBehind {
        val w = 6.dp.toPx()
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(glow.copy(alpha = 0.85f), glow.copy(alpha = 0.35f), glow.copy(alpha = 0.85f))),
            cornerRadius = CornerRadius(28.dp.toPx()), style = Stroke(width = w),
        )
    })
}
