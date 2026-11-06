package com.rabi.envoymcp.device

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import com.rabi.envoymcp.call.NumberMask
import com.rabi.envoymcp.core.RabiLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** アプリのショートカット (アイコンの長押しで出る項目。X の「ポスト」・メッセージの「新しいメッセージ」など) */
data class AppShortcut(val pkg: String, val id: String, val label: String, val longLabel: String = "", val dynamic: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("pkg", pkg).put("id", id).put("label", label).put("long", longLabel).put("dynamic", dynamic)
    companion object {
        fun from(j: JSONObject) = AppShortcut(j.getString("pkg"), j.getString("id"), j.optString("label"), j.optString("long"), j.optBoolean("dynamic"))
    }
}

/**
 * 全アプリのショートカットを前もって読み、持っておく。画面を順に押すより速く確かなので、操作ではまずこれを使う。
 * - ショートカットを読めて開けるのは、既定のホームアプリ (ENVOY MCP) だけ (LauncherApps のホームアプリ向けの機能)
 * - 起動時: 前回の一覧 (files/shortcuts.json) をすぐ使えるように読み、裏で全アプリを読み直す
 * - アプリのインストール・更新・削除と、アプリがショートカットを変えたとき (動的なショートカットは、アプリを使ううちに増える) は、そのアプリだけ読み直す
 */
class AppShortcuts(private val context: Context) {
    private val la = context.getSystemService(LauncherApps::class.java)
    private val file = File(context.filesDir, "shortcuts.json")
    private val io = Executors.newSingleThreadExecutor { Thread(it, "rabi-shortcuts").apply { isDaemon = true } }
    @Volatile private var byPkg: Map<String, List<AppShortcut>> = emptyMap()
    /** 一覧が変わるたびに増える (画面の表示用) */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    @Volatile var lastScan = 0L; private set
    @Volatile var lastError: String? = null; private set

    /** ショートカットを読めるか (ENVOY MCP が既定のホームアプリか) */
    val canRead get() = runCatching { la.hasShortcutHostPermission() }.getOrDefault(false)

    fun start() {
        io.execute {
            load()
            refreshAll()
        }
        runCatching { la.registerCallback(callback, Handler(Looper.getMainLooper())) }
            .onFailure { RabiLog.w("shortcuts", "callback failed ${it.javaClass.simpleName}") }
    }

    fun all(): List<AppShortcut> = byPkg.values.flatten()
    fun of(pkg: String): List<AppShortcut> = byPkg[pkg].orEmpty()

    /** 全アプリ (ホーム画面に出るアプリ) を読み直す */
    fun refreshAll() {
        if (!canRead) { lastError = "既定のホームアプリではないので、ショートカットを読めません"; RabiLog.w("shortcuts", lastError!!); return }
        val apps = runCatching { la.getActivityList(null, user()).map { it.componentName.packageName }.distinct() }.getOrDefault(emptyList())
        val m = LinkedHashMap<String, List<AppShortcut>>()
        for (p in apps.sorted()) query(p).takeIf { it.isNotEmpty() }?.let { m[p] = it }
        lastError = null
        lastScan = System.currentTimeMillis()
        set(m)
        RabiLog.i("shortcuts", "scanned ${apps.size} apps: ${m.size} apps have ${m.values.sumOf { it.size }} shortcuts")
    }

    /** そのアプリのショートカットを開く。開くと電話がかかるかもしれないもの (mayCall) は開かない */
    fun open(pkg: String, id: String): ActionResult {
        if (!canRead) return ActionResult.fail("既定のホームアプリではないので、ショートカットを開けません")
        val s = of(pkg).firstOrNull { it.id == id } ?: query(pkg).firstOrNull { it.id == id }
            ?: return ActionResult.fail("そのショートカットはありません ($pkg / $id)")
        if (mayCall(s)) return ActionResult.fail("電話がかかるかもしれないショートカットは開きません")
        return runCatching { la.startShortcut(pkg, id, null, null, user()); ActionResult.OK }
            .getOrElse { ActionResult.fail("開けませんでした (${it.javaClass.simpleName})") }
    }

    private fun user(): UserHandle = Process.myUserHandle()

    private fun query(pkg: String): List<AppShortcut> = runCatching {
        val q = LauncherApps.ShortcutQuery().setPackage(pkg)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        la.getShortcuts(q, user()).orEmpty().filter { it.isEnabled }
            .map { AppShortcut(pkg, it.id, it.shortLabel?.toString().orEmpty(), it.longLabel?.toString().orEmpty(), it.isDynamic) }
            .filter { it.label.isNotBlank() || it.longLabel.isNotBlank() }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    private fun update(pkg: String) {
        if (!canRead) return
        val l = query(pkg)
        if (l == byPkg[pkg].orEmpty()) return
        set(byPkg.toMutableMap().apply { if (l.isEmpty()) remove(pkg) else put(pkg, l) })
        RabiLog.i("shortcuts", "updated $pkg: ${l.size}")
    }

    private fun remove(pkg: String) { if (byPkg.containsKey(pkg)) set(byPkg - pkg) }

    private fun set(m: Map<String, List<AppShortcut>>) {
        byPkg = m
        _version.value++
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONArray(m.values.flatten().map { it.toJson() }).toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val a = JSONArray(file.readText())
            byPkg = (0 until a.length()).map { AppShortcut.from(a.getJSONObject(it)) }.groupBy { it.pkg }
            _version.value++
        }
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) { io.execute { update(packageName) } }
        override fun onPackageChanged(packageName: String, user: UserHandle) { io.execute { update(packageName) } }
        override fun onPackageRemoved(packageName: String, user: UserHandle) { io.execute { remove(packageName) } }
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { io.execute { packageNames.forEach { update(it) } } }
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { if (!replacing) io.execute { packageNames.forEach { remove(it) } } }
        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<android.content.pm.ShortcutInfo>, user: UserHandle) { io.execute { update(packageName) } }
    }

    companion object {
        /** 電話・連絡先のアプリ (ショートカットに、開くとすぐ発信するもの (「〇〇に発信」・連絡先の直接発信) がある) */
        private val CALLING_APPS = NumberMask.DIALERS + listOf(
            "com.samsung.android.dialer", "com.samsung.android.incallui", "com.android.phone",
            "com.google.android.contacts", "com.android.contacts", "com.samsung.android.app.contacts",
        )
        /** ショートカットの名前で発信と分かる語 (ほかのアプリの「〇〇に発信」「ビデオ通話」など) */
        private val CALL_WORDS = listOf("発信", "通話", "電話をかけ", "に電話")
        private val CALL_EN = Regex("(?<![a-z])(call|dial)(?![a-z])")

        /** 開くと電話がかかるかもしれないショートカットか (電話は、承認と緊急の番号の確かめがある call_phone でかける) */
        fun mayCall(s: AppShortcut): Boolean = s.pkg in CALLING_APPS || listOf(s.label, s.longLabel).any { l ->
            CALL_WORDS.any { l.contains(it) } || CALL_EN.containsMatchIn(l.lowercase())
        }
    }
}
