package com.rabi.envoymcp.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.rabi.envoymcp.core.EngineState
import com.rabi.envoymcp.core.Mood
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.ui.avatar.Avatar
import com.rabi.envoymcp.ui.avatar.AvatarSignals
import com.rabi.envoymcp.ui.avatar.AvatarStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 他のアプリを使っている間、そのアプリの上に出すリゲル (オーバーレイ)。窓は 3 つ:
 * - 球: 小さな触れる窓。リゲルだけを窓いっぱい近くまで大きく描き、ホームのリゲルと同じように動かす (表情も出す)。
 *   最初は画面の下の中央。押したまま動かすと好きな位置へ動かせる (離した位置を覚える)。
 *   タップ (onTap) と長押し (onHoldStart / onHoldEnd。押している間は話を聞く)。
 *   本人にまだ伝えていないお知らせ (電話のあとのこと・定期の依頼の結果) があれば、右上にベルとその数 (badge) を出す
 * - 字幕の吹き出し: 触れない窓。話している・作業している間だけ、球の上 (球が上半分にあれば下) に出す
 * - 縁の光: 画面全体の触れない窓。作業中 (show / hide) だけ、画面の縁をうっすら光らせる
 * 出すとき: ENVOY MCP の画面が前に出ていない (setOtherAppInFront) 間。設定で切っていても、作業中・返事の途中・まだ伝えていないお知らせがあるときは出す。
 * 承認画面の間と、AI の先生のお勉強の窓を出している間は出さない (suppress・setKids)
 * - Android 12 以降、他アプリの窓を通り抜けるタッチは不透明度が 0.8 を超えるとブロックされるため、alpha は 0.8 以下
 * - アクセシビリティの木には出さない (UI 階層の取得に混ざらないように)。画面の画像を取る瞬間だけ隠す (hideWhile)
 * - ENVOY MCP がタップ・スワイプを送る間は、球を素通りにする (passThroughWhile)
 * - ロック画面より上には出せない (TYPE_APPLICATION_OVERLAY の仕様)
 * 4 つ目の窓「ステージ」: ChatGPT の音声モードの間、画面全体をホームと同じリゲルで覆う (setStage)。
 * - 中身 (リゲル・言葉・下の真ん中の ×) とタッチは stageContent (Compose) が受け持つ。下に通さないので、不透明度の制限はかからない
 * - ステージを出している間は、小さな球と字幕は出さない (リゲルが 2 つにならないように)
 * 5 つ目「指さし」(point): やり方を教えるとき、押す部品のまわりにリゲルの光の枠を脈打たせ、小さな印で指す。押すのは本人。
 * - 触れない窓を、部品のまわりの 4 つの帯に分けて出す (部品の上には窓を重ねない)。本人のタッチがどの窓も通らずに部品に届くように
 *   (触れない窓でも、上に重なっていると「覆われている」とみなして押させないアプリがある)
 * - 指している間は、縁の光と字幕を出さず、部品に重なる球も消す (操作ではなく教えている。重なった窓は下へのタッチを止めることがある)
 */
class OperationOverlay(
    private val context: Context,
    private val state: StateFlow<EngineState>,
    private val style: () -> AvatarStyle,
    /** 他のアプリの上に、いつもリゲルを出すか (設定) */
    private val enabled: () -> Boolean = { true },
    /** 球の右上にベルと一緒に出す数 (本人にまだ伝えていないお知らせ)。0 なら出さない */
    private val badge: StateFlow<Int> = MutableStateFlow(0),
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
    @Volatile private var kids = false
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
        // まだ伝えていないお知らせがあれば、設定で切っていても球を出す
        scope.launch { badge.map { it > 0 }.distinctUntilChanged().collect { update() } }
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

    /** AI の先生のお勉強の窓 (KidsOverlay) を出している間は、どの窓も出さない (お勉強の窓の上に重ならないように) */
    fun setKids(on: Boolean) { kids = on; update() }

    /** 設定を変えたとき */
    fun refresh() = update()

    private fun update() = main.post {
        val active = wanted || state.value.mood != Mood.IDLE
        val can = !suppressed && !kids && canShow()
        val p = point?.takeIf { can }
        if (stageOn && can) addStage() else { remove(stageWin); stageWin = null }
        val showOrb = otherApp && can && stageWin == null && (enabled() || active || badge.value > 0) && (p == null || !orbOver(p))
        if (showOrb) addOrb() else { remove(caption); remove(orb); caption = null; orb = null }
        if (otherApp && can && wanted && p == null) addGlow() else remove(glow).also { glow = null }
        if (showOrb && active && p == null) addCaption() else remove(caption).also { caption = null }
        if (p != null) addPoint(p) else removePoint()
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
        val v = composeView(o) { OrbContent(state, style(), badge) }
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

    /** 画面の大きさ (px。今の向き) */
    fun screenSize(): Pair<Int, Int> = screen().let { it.first to it.second }

    /** 覚えた位置 (無ければ下の中央) に置く */
    private fun placeOrb(lp: WindowManager.LayoutParams) {
        val (x, y) = orbPos(lp.width, lp.height)
        lp.x = x; lp.y = y
    }

    /** 大きさ ow × oh の球を置く位置: 覚えた位置 (無ければ下の中央) */
    private fun orbPos(ow: Int, oh: Int): Pair<Int, Int> {
        val (w, h, nav) = screen()
        val (fx, fy) = loadPos()
        val maxX = w - ow; val maxY = h - oh
        return (if (fx in 0f..1f) (fx * maxX).toInt() else maxX / 2) to
            (if (fy in 0f..1f) (fy * maxY).toInt() else maxY - nav - (BOTTOM_MARGIN_DP * density).toInt())
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

    // ---------------------------------------------------------------- 指さし (やり方を教える)

    /** 指している部品の画面の位置 (px)。startedAt から光の脈を数える (帯の窓どうしで脈をそろえる) */
    private class Point(val id: Int, val l: Int, val t: Int, val r: Int, val b: Int, val startedAt: Long)
    @Volatile private var point: Point? = null
    private val pointSeq = AtomicInteger(0)
    private val pointLock = Any()
    private var pointWins: List<Win> = emptyList()
    /** 窓を出している指さしの番号 (0 なら出していない) */
    private var pointShown = 0

    /**
     * 画面の部品 (l, t, r, b。画面の座標の px) のまわりに、リゲルの光の枠を脈打たせて出す (押すのは本人)。ms たったら消す。前の指さしは消える。
     * 返す番号は、消すときに渡す (あとから出した指さしを、前の指さしの後始末で消さないように)
     */
    fun point(l: Int, t: Int, r: Int, b: Int, ms: Long): Int {
        val id = pointSeq.incrementAndGet()
        synchronized(pointLock) { point = Point(id, l, t, r, b, System.currentTimeMillis()) }
        update()
        main.postDelayed({ clearPoint(id) }, ms)
        return id
    }

    /** 指さしを消す (id を渡すと、その指さしのときだけ) */
    fun clearPoint(id: Int = 0) {
        synchronized(pointLock) {
            val p = point ?: return
            if (id != 0 && p.id != id) return
            point = null
        }
        update()
    }

    /** その指さしを、まだ出しているか */
    fun pointing(id: Int) = point?.id == id

    /** 球 (今の位置か、出すときの位置) が、指している部品とまわりの枠に重なるか */
    private fun orbOver(p: Point): Boolean {
        val size = (ORB_DP * density).toInt()
        val (x, y) = orb?.lp?.let { it.x to it.y } ?: orbPos(size, size)
        val m = ((POINT_PAD_DP + POINT_HALO_DP) * density).toInt()
        return x < p.r + m && x + size > p.l - m && y < p.b + m && y + size > p.t - m
    }

    /** 部品のまわりの 4 つの帯 (上・下・左・右) に、触れない窓を出す。指す印は部品の上 (上に場所が無ければ下) */
    private fun addPoint(p: Point) {
        if (pointShown == p.id) return
        removePoint()
        val (sw, sh, _) = screen()
        val m = ((POINT_PAD_DP + POINT_HALO_DP) * density).toInt()
        val mark = (POINT_MARK_DP * density).toInt()
        val above = p.t - m - mark >= 0
        val strips = listOf(
            Rect(p.l - m, p.t - m - (if (above) mark else 0), p.r + m, p.t),
            Rect(p.l - m, p.b, p.r + m, p.b + m + (if (above) 0 else mark)),
            Rect(p.l - m, p.t, p.l, p.b),
            Rect(p.r, p.t, p.r + m, p.b),
        ).map { Rect(it.left.coerceAtLeast(0), it.top.coerceAtLeast(0), it.right.coerceAtMost(sw), it.bottom.coerceAtMost(sh)) }
            .filter { it.width() > 0 && it.height() > 0 }
        val glowColor = style().glow
        pointWins = strips.mapNotNull { s ->
            val v = composeView(null) { PointContent(glowColor, p.l, p.t, p.r, p.b, s.left, s.top, above, p.startedAt) }
            val lp = WindowManager.LayoutParams(
                s.width(), s.height(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = s.left; y = s.top
                alpha = MAX_ALPHA
                title = "RabiPoint"
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (hiddenDepth > 0) v.visibility = View.INVISIBLE
            runCatching { wm.addView(v, lp) }
                .onFailure { RabiLog.w("overlay", "point failed ${it.javaClass.simpleName}"); (v.tag as? OverlayOwner)?.stop() }
                .getOrNull()?.let { Win(v, lp, v.tag as? OverlayOwner) }
        }
        pointShown = p.id
    }

    private fun removePoint() {
        pointWins.forEach { remove(it) }
        pointWins = emptyList(); pointShown = 0
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
        remove(glow); remove(caption); remove(orb); remove(stageWin); removePoint()
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
        if (orb == null && glow == null && stageWin == null && pointWins.isEmpty()) return block()
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
            (listOfNotNull(orb, caption, glow, stageWin) + pointWins).forEach { it.view.visibility = vis }
            c.resume(Unit)
        }
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
        /** 指さし: 部品の縁から光の枠までの間・枠の外に広がる光の幅・指す印の場所の高さ (dp) */
        const val POINT_PAD_DP = 8
        const val POINT_HALO_DP = 18
        const val POINT_MARK_DP = 44
        /** 指さしの光の脈の長さ */
        const val POINT_PULSE_MS = 1_400L
    }
}

@Composable
private fun OrbContent(state: StateFlow<EngineState>, style: AvatarStyle, badge: StateFlow<Int>) {
    val s by state.collectAsState()
    val n by badge.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Avatar(mood = s.mood, style = style, level = 0f, modifier = Modifier.fillMaxSize(),
            signals = AvatarSignals(s.wakeSeq, s.stepSeq, s.stepKind, s.doneSeq, expression = s.expression, expressionSeq = s.expressionSeq,
                expressionHoldMs = s.expressionHoldMs),
            compact = true)
        // まだ伝えていないお知らせ: ベルと数 (球の右上の縁に重ねる)
        if (n > 0) Row(
            Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 4.dp).height(22.dp).background(Color(0xFF2F6FED), RoundedCornerShape(11.dp))
                .padding(start = 4.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Notifications, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            Text(if (n > 9) "9+" else "$n", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 2.dp))
        }
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

/**
 * 指さしの帯の窓の中身: 部品 (l, t, r, b。画面の座標) のまわりの光の枠と、指す印 (リゲルの色の小さな球と三角)。
 * 帯の窓は枠の一部だけを受け持つので、窓の画面の位置だけずらして枠の全体を描く (窓の外は切れる)。
 * 窓の位置は、頼んだ位置 (x0, y0) ではなく画面に出た位置で合わせる。脈は始めた時刻から数える (帯の窓どうしでそろう)
 */
@Composable
private fun PointContent(glow: Color, l: Int, t: Int, r: Int, b: Int, x0: Int, y0: Int, above: Boolean, startedAt: Long) {
    val view = LocalView.current
    var origin by remember { mutableStateOf(Offset(x0.toFloat(), y0.toFloat())) }
    val phase by produceState(0f) {
        while (true) withFrameMillis { value = ((System.currentTimeMillis() - startedAt) % OperationOverlay.POINT_PULSE_MS) / OperationOverlay.POINT_PULSE_MS.toFloat() }
    }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        origin = Offset(loc[0].toFloat(), loc[1].toFloat())
    }) {
        translate(-origin.x, -origin.y) {
            val pad = OperationOverlay.POINT_PAD_DP.dp.toPx()
            val tl = Offset(l - pad, t - pad)
            val sz = Size(r - l + pad * 2, b - t + pad * 2)
            val rad = minOf(16.dp.toPx(), sz.minDimension / 2)
            // 外へ広がって消えていく光
            val grow = phase * 14.dp.toPx()
            drawRoundRect(glow.copy(alpha = 0.6f * (1f - phase)), tl - Offset(grow, grow), Size(sz.width + grow * 2, sz.height + grow * 2),
                CornerRadius(rad + grow), style = Stroke(6.dp.toPx()))
            // 枠: 白い画面でも見えるよう、濃い青の縁の上にリゲルの光の色
            drawRoundRect(POINT_EDGE, tl, sz, CornerRadius(rad), style = Stroke(7.dp.toPx()))
            drawRoundRect(glow, tl, sz, CornerRadius(rad), style = Stroke(3.5f.dp.toPx()))
            // 指す印: 枠の上 (下) で小さく上下する。三角の先を枠に向ける
            val bob = (1f - cos(phase * 2f * PI.toFloat())) / 2f * 6.dp.toPx()
            val dir = if (above) -1f else 1f
            val cx = (l + r) / 2f
            val tipY = (if (above) tl.y else tl.y + sz.height) + dir * (4.dp.toPx() + bob)
            val baseY = tipY + dir * 12.dp.toPx()
            val half = 9.dp.toPx()
            val tri = Path().apply { moveTo(cx, tipY); lineTo(cx - half, baseY); lineTo(cx + half, baseY); close() }
            drawPath(tri, POINT_EDGE, style = Stroke(3.dp.toPx()))
            drawPath(tri, glow)
            val c = Offset(cx, baseY + dir * 9.dp.toPx())
            val cr = 10.dp.toPx()
            drawCircle(Brush.radialGradient(listOf(Color.White, glow, POINT_EDGE), center = c, radius = cr), radius = cr, center = c)
            drawCircle(POINT_EDGE, radius = cr, center = c, style = Stroke(1.5f.dp.toPx()))
        }
    }
}

/** 指さしの枠と印の縁の色 (白い画面でも見えるように) */
private val POINT_EDGE = Color(0xFF1E62D0)

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
