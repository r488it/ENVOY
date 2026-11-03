package com.rabi.envoymcp.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.rabi.envoymcp.CheckedTap
import com.rabi.envoymcp.FullCapture
import com.rabi.envoymcp.ScreenCapture
import com.rabi.launcher.device.ActionResult
import com.rabi.launcher.device.AppShortcut
import com.rabi.launcher.device.AppShortcuts
import com.rabi.launcher.device.DeviceController
import com.rabi.launcher.observe.Bounds
import com.rabi.launcher.observe.Observation
import com.rabi.launcher.observe.UiNode
import com.rabi.launcher.parts.IntentSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * ユーザー補助 (A11yService) による端末操作。ADB 版 (AdbDeviceController) と同じ窓口で、差し替えて使う。
 * - 画面: 前面の窓の部品の木 (uiautomator dump と同じ並び・同じ項目にそろえる)。画像は takeScreenshot (取る瞬間だけ覆いを隠し、長辺 1280px 以下の JPEG)
 * - タップ: その場所の部品 (押せる親) をクリックする。押せる部品が無ければ指の動き (dispatchGesture)
 * - スワイプ: 指の動き。送る間は ENVOY MCP のリゲルを素通りにする
 * - 戻る・ホーム・通知・クイック設定: 全体の操作 (performGlobalAction)
 * - 文字入力: 選ばれている入力欄に、文字を直接入れる (ACTION_SET_TEXT。日本語もそのまま)
 * - アプリを開く・アプリへの指示: このアプリから開く (シェルの権限は無いので、発信などの権限の要る指示はできない)
 * - 他のアプリの強制停止・設定の書き換えなど、ADB のシェルでしかできないことはできない
 * - モニター (PC モード) につながっている間は displayId がモニターの番号になり、モニターの画面を読む・押す・撮る・アプリを開く
 *   読むのはモニターのアプリの窓 (上から順) とタスクバーで、ENVOY MCP 自身の窓 (全画面のリゲル) は除く。
 *   戻るはモニターに送り (displayKey)、ホームは全画面のリゲルを前に出す (displayHome)
 */
class A11yDeviceController(
    private val context: Context,
    private val hideOverlayWhile: suspend (suspend () -> ByteArray?) -> ByteArray?,
    private val passThroughWhile: suspend (suspend () -> ActionResult) -> ActionResult = { it() },
    private val appShortcuts: AppShortcuts? = null,
    /** 操作する画面の番号 (ふだんは本体 0。モニターにつながっている間はモニター) */
    private val displayId: () -> Int = { Display.DEFAULT_DISPLAY },
    /** その画面にキー (戻るなど) を送る (できなければ false。ADB の input -d) */
    private val displayKey: suspend (Int, Int) -> Boolean = { _, _ -> false },
    /** その画面の「ホーム」(モニターでは全画面のリゲルを前に出す) */
    private val displayHome: suspend (Int) -> ActionResult? = { null },
) : DeviceController, CheckedTap, ScreenCapture, FullCapture {
    private val svc get() = A11yService.instance
    private val onMonitor get() = displayId() != Display.DEFAULT_DISPLAY

    override val isConnected get() = svc != null

    override suspend fun observe(screenshot: Boolean): Observation {
        val s = svc ?: return Observation(null, null, emptyList(), 0, 0, error = "ユーザー補助がオフです")
        if (onMonitor) return observeOn(s, displayId(), screenshot)
        val (w, h) = screenSize()
        val root = withContext(Dispatchers.Default) { s.fresh(); s.rootInActiveWindow }
            ?: return Observation(null, null, emptyList(), w, h, error = "画面を読み取れません")
        val nodes = withContext(Dispatchers.Default) { ArrayList<UiNode>().also { walk(root, 0, it) } }
        val pkg = root.packageName?.toString()
        val shot = if (screenshot) screenshot() else null
        return Observation(pkg, s.foreground()?.takeIf { it.first == pkg }?.second?.ifEmpty { null }, nodes, w, h, shot,
            error = if (nodes.isEmpty()) "画面を読み取れません" else null, screenshotMime = "image/jpeg")
    }

    /**
     * モニターの画面を読む: アプリの窓を上から順に (ENVOY MCP 自身の窓は除く)、最後にタスクバー。
     * 前面のアプリは、いちばん上のアプリの窓 (無ければタスクバーのランチャー)
     */
    private suspend fun observeOn(s: A11yService, d: Int, screenshot: Boolean): Observation {
        val (w, h) = screenSize()
        val nodes = ArrayList<UiNode>()
        var pkg: String? = null
        withContext(Dispatchers.Default) {
            s.fresh()
            val ws = s.windowsOn(d).sortedByDescending { it.layer }
            val apps = ws.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() != context.packageName }
            val taskbar = ws.filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.title?.toString() in TASKBAR_TITLES }
            for (win in apps + taskbar) win.root?.let { r ->
                if (pkg == null) pkg = r.packageName?.toString()
                walk(r, 0, nodes)
            }
        }
        val shot = if (screenshot) screenshot() else null
        return Observation(pkg, null, nodes, w, h, shot, error = if (nodes.isEmpty()) "モニターの画面を読み取れません" else null, screenshotMime = "image/jpeg")
    }

    /** uiautomator dump と同じく、木を上から順にたどって番号を振る */
    private fun walk(n: AccessibilityNodeInfo, depth: Int, out: MutableList<UiNode>) {
        if (out.size >= MAX_NODES || depth > MAX_DEPTH) return
        val r = Rect().also { n.getBoundsInScreen(it) }
        // 入力欄がヒント (「検索」など) を出しているときは、文字は空 (uiautomator と同じ扱い)
        val text = if (n.isShowingHintText) "" else n.text?.toString().orEmpty()
        out += UiNode(
            index = out.size, text = text, desc = n.contentDescription?.toString().orEmpty(), resId = n.viewIdResourceName.orEmpty(),
            cls = n.className?.toString().orEmpty(), pkg = n.packageName?.toString().orEmpty(), bounds = Bounds(r.left, r.top, r.right, r.bottom),
            clickable = n.isClickable, enabled = n.isEnabled, focused = n.isFocused, scrollable = n.isScrollable, checked = n.isChecked,
            password = n.isPassword, hint = n.hintText?.toString().orEmpty(), depth = depth,
        )
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, out) }
    }

    private fun screenSize(): Pair<Int, Int> {
        if (onMonitor) context.getSystemService(DisplayManager::class.java).getDisplay(displayId())?.let { d ->
            val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") d.getRealMetrics(it) }
            return m.widthPixels to m.heightPixels
        }
        return context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.let { it.width() to it.height() }
    }

    /** 画面の画像だけを撮る (ADB + ユーザー補助で、部品の一覧は ADB で読み、画像はこちらで撮るときに使う。ADB の screencap より速い) */
    override suspend fun capture(): ByteArray? = if (svc == null) null else screenshot()

    /** 画面の画像。リゲルが映り込まないよう、取る間だけ覆いを隠す。長辺 1280px 以下の JPEG */
    private suspend fun screenshot(): ByteArray? = hideOverlayWhile { rawShot()?.let { compress(it) } }

    /** 全画面の大きさの PNG (共有・保存用)。リゲルが映り込まないよう、取る間だけ覆いを隠す */
    override suspend fun captureFullPng(): ByteArray? = if (svc == null) null else hideOverlayWhile {
        rawShot()?.let { b -> withContext(Dispatchers.Default) { ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() } }
    }

    /** ユーザー補助で画面を撮る (縮めない) */
    private suspend fun rawShot(): Bitmap? {
        val s = svc ?: return null
        return suspendCancellableCoroutine<Bitmap?> { c ->
            runCatching {
                s.takeScreenshot(displayId(), s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                        val hb = r.hardwareBuffer
                        val b = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                        if (c.isActive) c.resume(b)
                    }
                    override fun onFailure(code: Int) { if (c.isActive) c.resume(null) }
                })
            }.onFailure { if (c.isActive) c.resume(null) }
        }
    }

    private suspend fun compress(bmp: Bitmap): ByteArray = withContext(Dispatchers.Default) {
        val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
        val s = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        ByteArrayOutputStream().also { s.compress(Bitmap.CompressFormat.JPEG, 72, it) }.toByteArray()
    }

    override suspend fun launchApp(pkg: String): ActionResult {
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return ActionResult.fail("起動する画面が見つかりません")
        return start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    /** モニターにつながっている間は、モニターのデスクトップに開く */
    private fun launchOptions(): Bundle? =
        if (onMonitor) ActivityOptions.makeBasic().setLaunchDisplayId(displayId()).toBundle() else null

    override suspend fun startIntent(spec: IntentSpec): ActionResult {
        val i = Intent()
        spec.action?.let { i.action = it }
        spec.data?.let { i.data = Uri.parse(it) }
        spec.category?.let { i.addCategory(it) }
        spec.component?.let { ComponentName.unflattenFromString(it)?.let { c -> i.component = c } } ?: spec.pkg?.let { i.setPackage(it) }
        spec.extras.forEach { (k, v) -> i.putExtra(k, v) }
        return start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private suspend fun start(i: Intent): ActionResult = withContext(Dispatchers.Main) {
        try { context.startActivity(i, launchOptions()); ActionResult.OK }
        catch (e: ActivityNotFoundException) { ActionResult.fail("開ける画面がありません") }
        catch (e: SecurityException) { ActionResult.fail("権限が無いので開けません") }
    }

    /**
     * タップ: その場所の部品 (押せなければ押せる親) をクリックする。部品そのものを押すので、画面の動きの途中や覆いの下でも届く。
     * 押せる部品が無いとき・クリックできなかったときは、指の動きで押す
     */
    override suspend fun tap(x: Int, y: Int): ActionResult = tap(x, y, null)

    /**
     * expect: 画面を ADB で読んだときの、その場所の部品の位置 (ADB + ユーザー補助のとき)。
     * ユーザー補助で見えるその場所の部品が同じ位置・大きさのときだけ部品をクリックし、違えば指の動きで押す。
     * ADB には見えてユーザー補助には見えない部品 (ユーザー補助ツール以外に見せない部品) で、近くの別の部品をクリックしないため
     */
    override suspend fun tap(x: Int, y: Int, expect: Bounds?): ActionResult = passThroughWhile {
        // 部品クリックも「押しを送っている間」に含める (承認画面などが、自分の押しを本人の押しと取り違えないように)
        if (clickAt(x, y, expect)) ActionResult.OK else gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, TAP_MS)
    }

    /** (x, y) にあるいちばん小さい部品から、押せる親までさかのぼってクリックする */
    private suspend fun clickAt(x: Int, y: Int, expect: Bounds?): Boolean = withContext(Dispatchers.Default) {
        val s = svc ?: return@withContext false
        s.fresh()
        // モニターでは、その場所にあるいちばん上の窓の部品 (ENVOY MCP 自身の窓は除く)
        val root = (if (onMonitor) s.windowsOn(displayId()).sortedByDescending { it.layer }.firstOrNull { w ->
            val r = Rect().also { w.getBoundsInScreen(it) }
            r.contains(x, y) && w.root?.packageName?.toString() != context.packageName
        }?.root else s.rootInActiveWindow) ?: return@withContext false
        val smallest = smallestAt(root, x, y, 0) ?: return@withContext false
        if (expect != null) {
            val r = Rect().also { smallest.getBoundsInScreen(it) }
            if (!near(r, expect)) return@withContext false
        }
        var target: AccessibilityNodeInfo? = smallest
        while (target != null && !target.isClickable) target = target.parent
        val t = target ?: return@withContext false
        // 押せる親が画面の大部分を覆う (画面全体の入れ物など) なら、部品をクリックせず指で押す (違う所を押さないため)
        val r = Rect().also { t.getBoundsInScreen(it) }
        val (w, h) = screenSize()
        if (!r.contains(x, y) || r.width().toLong() * r.height() > w.toLong() * h / 2) return@withContext false
        t.isEnabled && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** 位置と大きさが、ほぼ同じか (各辺 NEAR_PX 以内) */
    private fun near(r: Rect, b: Bounds) =
        kotlin.math.abs(r.left - b.l) <= NEAR_PX && kotlin.math.abs(r.top - b.t) <= NEAR_PX &&
            kotlin.math.abs(r.right - b.r) <= NEAR_PX && kotlin.math.abs(r.bottom - b.b) <= NEAR_PX

    private fun smallestAt(n: AccessibilityNodeInfo, x: Int, y: Int, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        val r = Rect().also { n.getBoundsInScreen(it) }
        if (!r.contains(x, y) || !n.isVisibleToUser) return null
        for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { c -> smallestAt(c, x, y, depth + 1)?.let { return it } }
        return n
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) =
        passThroughWhile { gesture(Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }, ms.toLong().coerceAtLeast(50)) }

    private suspend fun gesture(path: Path, ms: Long): ActionResult {
        val s = svc ?: return ActionResult.fail("ユーザー補助がオフです")
        val g = GestureDescription.Builder().setDisplayId(displayId()).addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        return suspendCancellableCoroutine { c ->
            val sent = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(ActionResult.OK) }
                // 途中で取り消された (画面に触れた など): 送ったが、届いたかは分からない
                override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(ActionResult.unknown("指の動きが途中で取り消されました")) }
            }, null)
            if (!sent && c.isActive) c.resume(ActionResult.fail("指の動きを送れません"))
        }
    }

    override suspend fun back(): ActionResult {
        // モニター: モニターに戻るのキーを送る (全体の操作は、どちらの画面に効くか決まらないため)
        if (onMonitor && displayKey(displayId(), android.view.KeyEvent.KEYCODE_BACK)) return ActionResult.OK
        return global(AccessibilityService.GLOBAL_ACTION_BACK, "back")
    }
    override suspend fun home(): ActionResult {
        if (onMonitor) displayHome(displayId())?.let { return it }
        return global(AccessibilityService.GLOBAL_ACTION_HOME, "home")
    }

    private fun global(action: Int, what: String): ActionResult {
        val s = svc ?: return ActionResult.fail("ユーザー補助がオフです")
        return if (s.performGlobalAction(action)) ActionResult.OK else ActionResult.fail(what)
    }

    override suspend fun typeText(text: String): ActionResult {
        if (text.isEmpty()) return ActionResult.OK
        val n = focusedInput() ?: return ActionResult.fail("入力欄が選ばれていません")
        // 今の文字の後ろに足す (ヒントを出しているときは空とみなす)
        val cur = if (n.isShowingHintText) "" else n.text?.toString().orEmpty()
        val r = setText(n, cur + text)
        // 直接入れられない欄 (X など) は、貼り付けで入れる
        return if (r.ok) r else paste(n, text)
    }

    override suspend fun clearFocusedField(): ActionResult {
        val n = focusedInput() ?: return ActionResult.fail("入力欄が選ばれていません")
        return setText(n, "")
    }

    private fun focusedInput(): AccessibilityNodeInfo? = svc?.let { it.fresh(); it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }?.takeIf { it.isEditable }

    private fun setText(n: AccessibilityNodeInfo, t: String): ActionResult {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
        return if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) ActionResult.OK else ActionResult.fail("文字を入れられません")
    }

    /** 直接入れられない入力欄の予備: クリップボードに入れて貼り付ける (今の文字の続きに入る) */
    private suspend fun paste(n: AccessibilityNodeInfo, text: String): ActionResult {
        val set = withContext(Dispatchers.Main) {
            runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("envoy", text)); true }.getOrDefault(false)
        }
        if (!set) return ActionResult.fail("文字を入れられません")
        return if (n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) ActionResult.OK else ActionResult.fail("文字を入れられません")
    }

    override suspend fun isInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess || context.packageManager.getLaunchIntentForPackage(pkg) != null

    override fun shortcuts(): List<AppShortcut> = appShortcuts?.all().orEmpty()

    override suspend fun openPanel(which: String): ActionResult = when (which) {
        "quick_settings" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, which)
        "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, which)
        "close" -> global(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE, which)
        else -> ActionResult.fail("開く画面の指定が不正 ($which)")
    }

    override suspend fun openShortcut(pkg: String, id: String): ActionResult =
        appShortcuts?.let { s -> withContext(Dispatchers.Main) { s.open(pkg, id) } } ?: ActionResult.fail("ショートカットは使えません")

    companion object {
        private const val TAP_MS = 60L
        /** デスクトップのタスクバーの窓の題 (日本語・英語) */
        private val TASKBAR_TITLES = setOf("タスクバー", "Taskbar")
        private const val NEAR_PX = 8
        private const val MAX_NODES = 2_000
        private const val MAX_DEPTH = 60
    }
}
