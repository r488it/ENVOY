package com.rabi.envoymcp

import com.rabi.launcher.device.ActionResult
import com.rabi.launcher.device.AppShortcut
import com.rabi.launcher.device.DeviceController
import com.rabi.launcher.observe.Bounds
import com.rabi.launcher.observe.Observation
import com.rabi.launcher.parts.IntentSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** 画面の画像だけを撮れる (部品の一覧は別の方法で読むとき) */
interface ScreenCapture {
    suspend fun capture(): ByteArray?
}

/** 全画面の大きさの PNG を撮れる (共有・保存用) */
interface FullCapture {
    suspend fun captureFullPng(): ByteArray?
}

/** 押す場所の部品の位置 (画面を別の方法で読んだときのもの) を確かめてから押せる操作 */
interface CheckedTap {
    suspend fun tap(x: Int, y: Int, expect: Bounds?): ActionResult
}

/**
 * 端末の操作の方式 (設定) に応じて、ADB 版とユーザー補助版を使い分ける。操作のたびに選ぶので、途中でオン・オフが変わっても追いつく。
 * - ADB / A11Y: すべてその方法
 * - HYBRID: 画面を読むのは ADB (漏れなし。ユーザー補助ツール以外に見せない部品も読める)。ADB が切れていればユーザー補助。
 *   画像はユーザー補助で撮る (速い)。押す・入力などの操作はユーザー補助 (オフなら ADB)。ADB で読んだ画面の部品を押すときは、その部品の位置を渡し、
 *   ユーザー補助で同じ部品が見えるときだけ部品をクリックする (見えなければ指の動きで押す)
 *   (自動字幕起こしのスイッチなど、ユーザー補助では読めず ADB では読める部品がある)
 */
class SwitchingDeviceController(
    private val mode: () -> ControlMode,
    private val adb: DeviceController,
    private val a11y: DeviceController,
    /** モニター (PC モード) を操作している (ADB の画面の読み取りはモニターに届かないので、ユーザー補助を使う) */
    private val onMonitor: () -> Boolean = { false },
) : DeviceController {
    /** 操作に使う方 */
    fun current(): DeviceController = if (onMonitor() && a11y.isConnected) a11y else when (mode()) {
        ControlMode.ADB -> adb
        ControlMode.A11Y -> a11y
        ControlMode.HYBRID -> if (a11y.isConnected) a11y else adb
    }

    /** 画面を読むのに使う方 */
    fun reader(): DeviceController = if (onMonitor() && a11y.isConnected) a11y else when (mode()) {
        ControlMode.ADB -> adb
        ControlMode.A11Y -> a11y
        ControlMode.HYBRID -> if (adb.isConnected || !a11y.isConnected) adb else a11y
    }

    /** 今ユーザー補助で操作しているか (false なら ADB) */
    fun usingA11y() = current() === a11y

    /** 直前に読んだ画面と、それを読んだ方 (押す場所の部品の位置を確かめるため) */
    @Volatile private var lastObs: Observation? = null
    @Volatile private var lastReader: DeviceController? = null

    override val isConnected get() = current().isConnected

    override suspend fun observe(screenshot: Boolean): Observation {
        val r = reader()
        // HYBRID で画像も要るとき: 部品の一覧は ADB、画像はユーザー補助で、同時に取る
        // (ADB の screencap は全画面の PNG を作って送るので 3〜6 秒かかる)
        val o = if (screenshot && r !== a11y && a11y is ScreenCapture && a11y.isConnected) coroutineScope {
            val shot = async { runCatching { a11y.capture() }.getOrNull() }
            val nodes = r.observe(false)
            val img = shot.await()
            if (img != null) nodes.copy(screenshotPng = img, screenshotMime = "image/jpeg") else r.observe(true)
        } else r.observe(screenshot)
        return o.also { if (it.ok) { lastObs = it; lastReader = r } }
    }

    override suspend fun tap(x: Int, y: Int): ActionResult {
        val c = current()
        // 読んだ方と押す方が違うとき (HYBRID で ADB で読み、ユーザー補助で押す): 読んだ画面のその場所の部品の位置を渡す
        // その場所の部品が読んだ画面に無ければ、どの部品とも一致しない位置を渡す (部品クリックをせず、必ず指の動きで押す)
        if (c is CheckedTap && lastReader != null && lastReader !== c) return c.tap(x, y, lastObs?.nodeAt(x, y)?.bounds ?: NO_MATCH)
        return c.tap(x, y)
    }

    /** 全画面の大きさの PNG。HYBRID はユーザー補助で撮る (速い)。使えなければ ADB */
    suspend fun captureFullPng(): ByteArray? {
        val first = if (mode() == ControlMode.ADB) adb else current()
        (first as? FullCapture)?.captureFullPng()?.let { return it }
        val other = if (first === adb) a11y else adb
        return if (mode() == ControlMode.HYBRID && other.isConnected) (other as? FullCapture)?.captureFullPng() else null
    }

    override suspend fun launchApp(pkg: String): ActionResult = current().launchApp(pkg)
    override suspend fun startIntent(spec: IntentSpec): ActionResult = current().startIntent(spec)
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): ActionResult = current().swipe(x1, y1, x2, y2, ms)
    override suspend fun back(): ActionResult = current().back()
    override suspend fun home(): ActionResult = current().home()
    override suspend fun typeText(text: String): ActionResult = current().typeText(text)
    override suspend fun clearFocusedField(): ActionResult = current().clearFocusedField()
    override suspend fun isInstalled(pkg: String): Boolean = current().isInstalled(pkg)
    override fun shortcuts(): List<AppShortcut> = current().shortcuts()
    override suspend fun openPanel(which: String): ActionResult = current().openPanel(which)
    override suspend fun openShortcut(pkg: String, id: String): ActionResult = current().openShortcut(pkg, id)

    companion object {
        private val NO_MATCH = Bounds(-1, -1, -1, -1)
    }
}
