package com.rabi.envoymcp.mcp

import android.content.ClipData
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.rabi.envoymcp.parts.TraceStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** アプリの道具: アプリ・ショートカットの一覧と開く、画像などを添えてアプリの共有を開く */
internal class AppTools(st: ToolState) : ToolGroup(st) {
    fun listApps(): ToolResult {
        val apps = g.launcherApps().sortedBy { it.second }
        val arr = JSONArray(apps.map { (pkg, label) -> JSONObject().put("label", label).put("package", pkg) })
        val o = JSONObject().put("apps", arr)
        return ToolResult(o.toString(), o)
    }

    fun listShortcuts(a: JSONObject): ToolResult {
        val pkg = a.optString("package").takeIf { it.isNotBlank() }
        val list = if (pkg != null) g.shortcuts.of(pkg) else g.shortcuts.all()
        val arr = JSONArray(list.map { JSONObject().put("package", it.pkg).put("app", g.appLabel(it.pkg) ?: it.pkg).put("shortcut_id", it.id).put("label", it.label) })
        val o = JSONObject().put("shortcuts", arr)
        if (list.isEmpty()) o.put("note", "ショートカットを読めませんでした (ENVOY MCP が既定のホームアプリのときだけ読めます)")
        return ToolResult(o.toString(), o)
    }

    suspend fun openApp(a: JSONObject): ToolResult {
        val (pkg, why) = resolveApp(a.optString("app"))
        if (pkg == null) return failed(why)
        val before = g.device.observe()
        val r = g.device.launchApp(pkg)
        if (!r.ok) return fromAction(r, "アプリを開けませんでした")
        val after = waitScreen(4_000) { it.pkg == pkg }
        record(TraceStep("launch", pkg = pkg, before = before, after = after, ok = after?.pkg == pkg))
        return if (after?.pkg == pkg) ok("${g.appLabel(pkg) ?: pkg} を開きました。", summary(after))
        else unknown("開く操作は送りましたが、${g.appLabel(pkg) ?: pkg} の画面になったか確かめられません。", after?.let { summary(it) } ?: JSONObject())
    }

    suspend fun openShortcut(a: JSONObject): ToolResult {
        val pkg = a.optString("package"); val id = a.optString("shortcut_id")
        val before = g.device.observe()
        val r = g.device.openShortcut(pkg, id)
        if (!r.ok) return fromAction(r, "ショートカットを開けませんでした")
        val after = waitScreen(4_000) { it.pkg == pkg }
        record(TraceStep("shortcut", pkg = pkg, text = id, before = before, after = after, ok = after?.pkg == pkg))
        return if (after?.pkg == pkg) ok("ショートカットを開きました (まだ送信・投稿はしていません)。", summary(after))
        else unknown("開く操作は送りましたが、画面を確かめられません。", after?.let { summary(it) } ?: JSONObject())
    }

    /** 画面を撮って写真に保存し、アプリの共有 (ACTION_SEND) に添えて開く。投稿・送信はしない */
    suspend fun shareScreenshot(a: JSONObject): ToolResult {
        val (pkg, why) = resolveApp(a.optString("app"))
        if (pkg == null) return failed(why)
        val label = g.appLabel(pkg) ?: pkg
        val png = g.device.captureFullPng() ?: return failed("画面の画像を撮れませんでした。")
        val uri = withContext(Dispatchers.IO) { saveToPictures(png) } ?: return failed("写真に保存できませんでした。")
        return share(uri, "image/png", pkg, a.optString("text").trim(), a.optString("target", "post"), "画面の画像を写真に保存し、", "画像は写真に保存してあります。")
    }

    /**
     * ファイル (uri。null なら文だけ) を添えて、アプリの共有の画面を開く (share_screenshot・share_photo・share_call_record)。
     * done は成功の言葉の頭、keep は失敗のときに添える言葉、what は添えるものの呼び名、note は成功のときに足す言葉、extra は結果に足すもの
     */
    suspend fun share(uri: Uri?, mime: String, pkg: String, text: String, target: String, done: String, keep: String = "",
                      what: String = "画像", note: String = "", extra: JSONObject = JSONObject()): ToolResult {
        val label = g.appLabel(pkg) ?: pkg
        val i = Intent(Intent.ACTION_SEND).setType(if (uri != null) mime else "text/plain").setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (uri != null) {
            i.putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData = ClipData.newRawUri("", uri)
        }
        if (text.isNotEmpty()) i.putExtra(Intent.EXTRA_TEXT, text)
        // アプリの中に共有先が複数あると、Android がどれで開くかを尋ねる画面を出す (X は投稿とチャット)。
        // 投稿かメッセージかを決めて、その共有先を直接開く
        val targets = runCatching { g.context.packageManager.queryIntentActivities(i, 0).map { it.activityInfo } }.getOrDefault(emptyList())
        if (targets.isEmpty()) return failed("$label は${what}の共有を受け付けません。$keep", extra)
        val chosen = pickShareTarget(targets, target)
        i.component = ComponentName(chosen.packageName, chosen.name)
        val started = withContext(Dispatchers.Main) { runCatching { g.context.startActivity(i) } }
        if (started.isFailure) return failed("$label は${what}の共有を受け付けませんでした (${started.exceptionOrNull()?.javaClass?.simpleName})。$keep", extra)
        val after = waitScreen(SHARE_WAIT_MS) { it.pkg == pkg }
        val added = if (uri != null) "${what}を添えてあります" else "文を入れてあります"
        return if (after != null) ok("$done$label の共有の画面を開きました ($added)。まだ投稿・送信はしていません。$note",
            summary(after).also { s -> extra.keys().forEach { k -> s.put(k, extra.get(k)) } })
        else unknown("共有の画面を開く操作は送りましたが、$label の画面になったか確かめられません。$keep", extra)
    }

    /** アプリが、その種類 (mime) のものの共有 (ACTION_SEND) を受け付けるか */
    fun accepts(pkg: String, mime: String): Boolean = runCatching {
        g.context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType(mime).setPackage(pkg), 0).isNotEmpty()
    }.getOrDefault(false)

    /** 共有先を選ぶ: post は投稿の画面、message はメッセージ・チャット。名前 (クラス名と表示名) で決め、決まらなければ最初のもの */
    private fun pickShareTarget(list: List<ActivityInfo>, target: String): ActivityInfo {
        if (list.size == 1) return list[0]
        val pm = g.context.packageManager
        fun words(a: ActivityInfo) = (a.name + " " + runCatching { a.loadLabel(pm).toString() }.getOrDefault("")).lowercase()
        val msg = listOf("chat", "message", "msg", "direct", "dm", "チャット", "メッセージ")
        val post = listOf("compos", "post", "tweet", "publish", "upload", "feed", "story", "投稿", "ポスト")
        val want = if (target == "message") msg else post
        val avoid = if (target == "message") post else msg
        return list.firstOrNull { a -> want.any { words(a).contains(it) } && avoid.none { words(a).contains(it) } }
            ?: list.firstOrNull { a -> avoid.none { words(a).contains(it) } }
            ?: list[0]
    }

    /** PNG を端末の写真 (Pictures/ENVOY MCP) に保存する。保存した画像の URI */
    private fun saveToPictures(png: ByteArray): Uri? {
        val cr = g.context.contentResolver
        val name = "envoy_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date()) + ".png"
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ENVOY MCP")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) }.getOrNull() ?: return null
        return runCatching {
            cr.openOutputStream(uri)!!.use { it.write(png) }
            cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        }.getOrElse { runCatching { cr.delete(uri, null, null) }; null }
    }

    companion object {
        /** 共有の画面が開くのを待つ長さ */
        private const val SHARE_WAIT_MS = 6_000L
    }
}
