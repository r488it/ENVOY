package com.rabi.envoymcp.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.rabi.envoymcp.device.adb.AdbConnector
import com.rabi.envoymcp.device.adb.AdbLostException
import com.rabi.envoymcp.device.adb.ExecResult
import com.rabi.envoymcp.observe.Observation
import com.rabi.envoymcp.observe.UiDumpParser
import com.rabi.envoymcp.parts.IntentSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * ADB による端末操作。
 * - 画面: uiautomator dump (UI 階層) + 前面のアクティビティ。画像は screencap (取る瞬間だけオーバーレイを隠し、縮小して JPEG)
 * - 文字入力: ASCII は input text、日本語などはクリップボードに入れて貼り付け (KEYCODE_PASTE。貼ったあとクリップボードを空にする: PasteClip)。
 *   入ったかは呼ぶ側が画面で確かめる
 * - 計測: 操作ごとの所要時間を残す (診断画面)
 */
class AdbDeviceController(
    private val context: Context,
    private val adb: AdbConnector,
    private val hideOverlayWhile: suspend (suspend () -> ByteArray?) -> ByteArray?,
    /** タップ・スワイプを送る間、操作中のリゲルの「触れる窓」(試験版の緊急停止) を素通りにする (自分のタップで止まらないように) */
    private val passThroughWhile: suspend (suspend () -> ActionResult) -> ActionResult = { it() },
    /** 全アプリのショートカット (ADB ではなく、ホームアプリの機能で読み・開く) */
    private val appShortcuts: AppShortcuts? = null,
) : DeviceController, FullCapture {
    override val isConnected get() = adb.isConnected
    @Volatile private var screen: Pair<Int, Int>? = null
    @Volatile var lastObserveMs = 0L; private set
    @Volatile var inputMethod = "未確認"; private set

    override suspend fun observe(screenshot: Boolean): Observation {
        val t0 = System.currentTimeMillis()
        if (!adb.isConnected && !adb.ensureConnected()) return Observation(null, null, emptyList(), 0, 0, error = "接続なし")
        return try {
            val size = screen ?: wmSize().also { screen = it }
            val r = adb.exec(
                // 読んだらすぐ消す (画面の文字を端末のファイルに残さない)
                "uiautomator dump $DUMP >/dev/null 2>&1; cat $DUMP 2>/dev/null; rm -f $DUMP; echo; echo $MARK; " +
                    "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n 2", 20_000,
            )
            val text = r.text
            val xml = text.substringBefore(MARK)
            val fg = text.substringAfter(MARK, "")
            val m = Regex("u\\d+ ([\\w.]+)/([\\w.$]+)").find(fg)
            val pkg = m?.groupValues?.get(1)
            val act = m?.groupValues?.get(2)?.let { if (it.startsWith(".")) pkg + it else it }
            val nodes = if (xml.contains("<hierarchy")) UiDumpParser.parse(xml) else emptyList()
            val shot = if (screenshot) screenshot() else null
            lastObserveMs = System.currentTimeMillis() - t0
            Observation(pkg, act, nodes, size.first, size.second, shot, error = if (nodes.isEmpty() && pkg == null) "画面を読み取れません" else null, screenshotMime = "image/jpeg")
        } catch (e: AdbLostException) {
            Observation(null, null, emptyList(), 0, 0, error = "接続が切れました")
        } catch (e: Exception) {
            Observation(null, null, emptyList(), 0, 0, error = e.javaClass.simpleName)
        }
    }

    private suspend fun wmSize(): Pair<Int, Int> {
        val t = adb.exec("wm size").text
        val m = Regex("(\\d+)x(\\d+)").findAll(t).lastOrNull() ?: return 1080 to 2400
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** 全画面の大きさの PNG (ENVOY MCP の共有・保存用)。アバターが映り込まないよう、取る間だけオーバーレイを隠す */
    override suspend fun captureFullPng(): ByteArray? = hideOverlayWhile {
        runCatching { adb.exec("screencap -p", 20_000).stdout }.getOrNull()?.takeIf { it.size > 100 }
    }

    /** 画面の画像。アバターが映り込まないよう、取る間だけオーバーレイを隠す。長辺 1280px 以下の JPEG */
    private suspend fun screenshot(): ByteArray? = hideOverlayWhile {
        val png = runCatching { adb.exec("screencap -p", 20_000).stdout }.getOrNull()
        png?.takeIf { it.size > 100 }?.let { compress(it) }
    }

    private suspend fun compress(png: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return@withContext null
        val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
        val s = if (scale < 1f) bmp.scale((bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        ByteArrayOutputStream().also { s.compress(Bitmap.CompressFormat.JPEG, 72, it) }.toByteArray()
    }

    override suspend fun launchApp(pkg: String): ActionResult {
        if (!pkg.matches(Regex("[\\w.]+"))) return ActionResult.fail("パッケージ名が不正")
        return run("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg | tail -n 1") { r ->
            val comp = r.text.trim().lines().lastOrNull()?.trim().orEmpty()
            if (!comp.contains('/')) return@run ActionResult.fail("起動する画面が見つかりません")
            // 画面の名前に $ が入ることがある (YouTube は Shell$HomeActivity)。シェルに変数として読まれないよう引用符で囲む
            val s = adb.exec("am start -n ${q(comp)}")
            if (s.ok && !s.text.contains("Error")) ActionResult.OK else ActionResult.fail(s.text.take(120))
        }
    }

    override suspend fun startIntent(spec: IntentSpec): ActionResult {
        val parts = ArrayList<String>()
        parts += "am start"
        spec.action?.let { parts += "-a ${q(it)}" }
        spec.data?.let { parts += "-d ${q(it)}" }
        spec.category?.let { parts += "-c ${q(it)}" }
        spec.component?.let { parts += "-n ${q(it)}" } ?: spec.pkg?.let { parts += "-p ${q(it)}" }
        spec.extras.forEach { (k, v) -> parts += "--es ${q(k)} ${q(v)}" }
        val cmd = parts.joinToString(" ")
        if (!cmd.all { it.code < 128 }) return ActionResult.fail("日本語を含む値は画面の操作で入力します")
        return run(cmd) { r -> if (r.ok && !r.text.contains("Error:")) ActionResult.OK else ActionResult.fail(r.text.take(160)) }
    }

    override suspend fun tap(x: Int, y: Int) = passThroughWhile { run("input tap $x $y") { r -> if (r.ok) ActionResult.OK else ActionResult.fail(r.err.take(80)) } }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) = passThroughWhile { run("input swipe $x1 $y1 $x2 $y2 $ms") { r -> if (r.ok) ActionResult.OK else ActionResult.fail(r.err.take(80)) } }

    /**
     * キー (KEYCODE_…) を display の画面に 1 つ送る (press_key)。送る間は、タップと同じく自分の押しとして扱う
     * (承認画面などが本人の押しと取り違えないように)
     */
    suspend fun key(display: Int, code: String): ActionResult {
        if (!code.matches(Regex("KEYCODE_[A-Z0-9_]+"))) return ActionResult.fail("キーの名前が不正")
        return passThroughWhile { run("input -d $display keyevent $code") { r -> if (r.ok) ActionResult.OK else ActionResult.fail(r.err.take(80)) } }
    }

    override suspend fun back() = run("input keyevent 4") { if (it.ok) ActionResult.OK else ActionResult.fail("back") }

    override suspend fun home() = run("input keyevent 3") { if (it.ok) ActionResult.OK else ActionResult.fail("home") }

    override suspend fun typeText(text: String): ActionResult {
        if (text.isEmpty()) return ActionResult.OK
        val simple = text.all { it.code in 0x20..0x7e } && text.none { it in "\"'`\\$&|;<>()*?~!#%" }
        if (simple) {
            inputMethod = "input text"
            return run("input text ${text.replace(" ", "%s")}") { if (it.ok) ActionResult.OK else ActionResult.fail("input text") }
        }
        // 日本語など: クリップボード経由で貼り付ける (input text は ASCII 以外を扱えない)。貼ったら、入れた文字をクリップボードに残さない
        inputMethod = "クリップボード + 貼り付け"
        if (!PasteClip.put(context, text)) return ActionResult.fail("クリップボードに入れられません")
        delay(150)
        return run("input keyevent 279") { if (it.ok) ActionResult.OK else ActionResult.fail("paste") }.also { PasteClip.clearSoon(context) }
    }

    override suspend fun clearFocusedField(): ActionResult {
        // 行末へ移動して、まとめて後ろから消す (Android 12 でも使える方法)
        val dels = (1..80).joinToString(" ") { "67" }
        return run("input keyevent 123 && input keyevent $dels") { if (it.ok) ActionResult.OK else ActionResult.fail("clear") }
    }

    override suspend fun openPanel(which: String): ActionResult {
        val cmd = when (which) {
            "quick_settings" -> "cmd statusbar expand-settings"
            "notifications" -> "cmd statusbar expand-notifications"
            "close" -> "cmd statusbar collapse"
            else -> return ActionResult.fail("開く画面の指定が不正 ($which)")
        }
        return run(cmd) { if (it.ok) ActionResult.OK else ActionResult.fail(it.err.take(80)) }
    }

    override fun shortcuts(): List<AppShortcut> = appShortcuts?.all().orEmpty()

    override suspend fun openShortcut(pkg: String, id: String): ActionResult =
        appShortcuts?.let { s -> withContext(Dispatchers.Main) { s.open(pkg, id) } } ?: ActionResult.fail("ショートカットは使えません")

    override suspend fun isInstalled(pkg: String): Boolean {
        val local = runCatching { context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)); true }.getOrDefault(false)
        if (local) return true
        // このアプリから見えないパッケージ (ランチャーに無いもの) は ADB で確かめる
        return runCatching { adb.exec("pm path $pkg").ok }.getOrDefault(false)
    }

    /** 送ったあとに切れたら unknown (実行されたか分からない) */
    private suspend fun run(cmd: String, f: suspend (ExecResult) -> ActionResult): ActionResult = try {
        if (!adb.isConnected && !adb.ensureConnected()) ActionResult.fail("接続なし") else f(adb.exec(cmd))
    } catch (e: AdbLostException) {
        ActionResult.unknown("接続が切れました")
    } catch (e: IllegalStateException) {
        ActionResult.fail("接続なし")
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        private const val DUMP = "/data/local/tmp/rabi_ui.xml"
        private const val MARK = "__RABI_FG__"

        /** ランチャーに出るアプリの一覧 (ラベル → パッケージ) */
        fun launcherApps(context: Context): Map<String, String> {
            val pm = context.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return pm.queryIntentActivities(i, PackageManager.ResolveInfoFlags.of(0))
                .filter { it.activityInfo.packageName != context.packageName }
                .associate { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        }
    }
}
