package com.rabi.envoymcp.mcp

import android.app.KeyguardManager
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Base64
import com.rabi.envoymcp.BuildConfig
import com.rabi.envoymcp.VisionProvider
import com.rabi.envoymcp.core.RabiLog
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** 読む道具: スマホの状態・画面の部品・画面の画像・画面を見て説明する */
internal class ScreenTools(st: ToolState) : ToolGroup(st) {
    suspend fun getState(toolNames: List<String>): ToolResult {
        val ctx = g.context
        val pm = ctx.getSystemService(PowerManager::class.java)
        val km = ctx.getSystemService(KeyguardManager::class.java)
        val fg = foreground()
        val o = JSONObject()
            .put("time", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString())
            .put("battery", battery())
            .put("screen", JSONObject().put("on", pm.isInteractive).put("locked", km.isKeyguardLocked))
            .put("foreground_app", fg ?: JSONObject.NULL)
            .put("device_control", JSONObject().put("connected", g.device.isConnected).put("mode", g.settings.controlMode.id)
                .put("using", if (g.usingAdb()) "adb" else "accessibility").put("detail", g.deviceStatus()))
            .put("arm", g.arm.statusJson())
            .put("monitor", g.monitorStage.external()?.let { d ->
                JSONObject().put("connected", true).put("name", d.name).put("operating", g.monitorStage.target == d.displayId)
            } ?: JSONObject().put("connected", false))
            .put("now_playing", g.nowPlaying.track.value?.toJson()
                ?: if (g.nowPlaying.hasAccess()) JSONObject.NULL else "unknown (通知へのアクセスが未許可)")
            .put("envoy_mcp", JSONObject().put("version", BuildConfig.VERSION_NAME).put("home_visible", g.homeVisible)
                .put("can_operate", g.overlay.canShow() && g.device.isConnected).put("screenshots_allowed", g.settings.allowScreenshots)
                .put("tools", JSONArray(toolNames)))
        if (fg == null) o.put("note", "端末の操作 (ADB・ユーザー補助) が使えないので、前面のアプリは分かりません。")
        return ToolResult(o.toString(), structured = o)
    }

    /**
     * 電池 (Android が配る最新の電池の状態から)。BatteryManager.isCharging は、つないでいても false のことがある
     * (Pixel 10 で、充電中・電源ありなのに false になる)
     */
    private fun battery(): JSONObject {
        val b = g.context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return JSONObject()
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = when (b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
            else -> null
        }
        return JSONObject()
            .put("percent", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING)
            .put("full", status == BatteryManager.BATTERY_STATUS_FULL)
            .put("plugged", plugged ?: JSONObject.NULL)
    }

    /** 前面のアプリ (ユーザー補助か ADB で読む。使えなければ null。ADB の接続はここでは始めない: 待たせないため) */
    private suspend fun foreground(): JSONObject? {
        // モニターを操作している間は、モニターのいちばん上のアプリ (無ければデスクトップ = 全画面のリゲル)
        if (g.monitorStage.target != android.view.Display.DEFAULT_DISPLAY) {
            val pkg = g.monitorStage.topApp()
            return JSONObject().put("display", "monitor").put("package", pkg ?: JSONObject.NULL)
                .put("label", pkg?.let { g.appLabel(it) } ?: "デスクトップ (全画面のリゲル)")
        }
        val pkgAct = g.foregroundActivity() ?: return null
        val (pkg, act) = pkgAct
        return JSONObject().put("package", pkg).put("label", g.appLabel(pkg) ?: JSONObject.NULL).put("activity", act)
    }

    suspend fun readScreen(a: JSONObject): ToolResult {
        val o = g.device.observe()
        if (!o.ok) return failed("画面を読み取れません (${o.error})")
        lastScreen = o
        val j = o.toModelJson(a.optInt("max_items", 80).coerceIn(10, 150))
        j.put("app_label", g.appLabel(o.pkg) ?: JSONObject.NULL)
        return ToolResult(j.toString(), structured = j)
    }

    suspend fun screenshot(): ToolResult {
        if (!g.settings.allowScreenshots) return failed("利用者が設定で、画面の画像を渡さないようにしています。read_screen を使ってください。")
        val o = g.device.observe(screenshot = true)
        val img = o.screenshotPng ?: return failed("画面の画像を取れませんでした (${o.error ?: "不明"})")
        val s = summary(o)
        return ToolResult("画面の画像です。\n$s", s.put("result", "ok"), images = listOf(ToolResult.Image(Base64.encodeToString(img, Base64.NO_WRAP), o.screenshotMime)))
    }

    suspend fun lookAtScreen(): ToolResult {
        if (!g.settings.allowScreenshots) return failed("利用者が設定で、画面の画像を使わないようにしています。read_screen を使ってください。")
        if (g.settings.visionProvider == VisionProvider.CLAUDE) {
            if (g.claudeKey.read() == null) return failed("スマホの ENVOY MCP の設定で Claude の API キーが保存されていないので、画面を見られません " +
                "(利用者が ENVOY MCP の設定の「スマホの操作」›「画面の理解」でキーを保存すると使えます)。read_screen を使ってください。")
        } else {
            val st = g.auth.status.value
            if (!st.signedIn) return failed("スマホの ENVOY MCP で「ChatGPT でサインイン」が済んでいないので、画面を見られません。read_screen を使ってください。")
        }
        val t0 = System.currentTimeMillis()
        val o = g.device.observe(screenshot = true)
        RabiLog.i("vision", "capture ${System.currentTimeMillis() - t0}ms")
        if (o.screenshotPng == null) return failed("画面の画像を取れませんでした (${o.error ?: "不明"})。read_screen を使ってください。")
        ownScreen(o)?.let { return it }
        val r = try { g.vision.look(o) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return failed("画面を見られませんでした (${e.message ?: e.javaClass.simpleName})。read_screen を使ってください。")
        }
        lastVision = VisionMemo(o, r.targets.associateBy { it.visualId })
        val targets = JSONArray(r.targets.map { t ->
            JSONObject().put("visual_id", t.visualId).put("label", t.label).put("kind", t.kind).put("element_id", t.elementId ?: JSONObject.NULL)
                .put("x", t.bounds.cx).put("y", t.bounds.cy).put("b", t.bounds.toString()).put("commit_like", t.commit)
        })
        val s = summary(o).put("description", r.summary).put("visual_targets", targets)
        return ok("画面を見ました: ${r.summary.take(80)}", s)
    }
}
