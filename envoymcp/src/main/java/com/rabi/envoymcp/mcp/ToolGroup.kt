package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.device.ActionResult
import com.rabi.envoymcp.observe.Observation
import com.rabi.envoymcp.parts.TraceStep
import kotlinx.coroutines.delay
import org.json.JSONObject

/** 道具が分け合う状態 (McpTools が 1 つ持つ) */
internal class ToolState(val g: McpGraph) {
    /** 直前の read_screen の画面 (tap・type_text の番号はこの画面のもの) */
    @Volatile var lastScreen: Observation? = null
    /** 直前に look_at_screen で見た画面と、押せそうなもの (visual_id で押すため) */
    @Volatile var lastVision: VisionMemo? = null
    /** 前回の部品の登録より後の操作の記録 (save_part で部品にする) */
    val trace = ArrayList<TraceStep>()
}

/** look_at_screen で見た画面と、押せそうなもの (visual_id ごと) */
internal class VisionMemo(val obs: Observation, val targets: Map<String, ScreenVision.Target>)

/** 道具の実装のもと: 分け合う状態と、どの道具でも使う確かめ方・返し方 */
internal abstract class ToolGroup(private val st: ToolState) {
    protected val g: McpGraph get() = st.g
    protected var lastScreen: Observation?
        get() = st.lastScreen
        set(v) { st.lastScreen = v }
    protected var lastVision: VisionMemo?
        get() = st.lastVision
        set(v) { st.lastVision = v }
    protected val trace: ArrayList<TraceStep> get() = st.trace

    /** ENVOY MCP 自身の画面 (承認画面・設定など) は、ChatGPT の道具では押さない・入れない (承認を自分で押せないように) */
    protected fun ownScreen(o: Observation): ToolResult? =
        if (o.pkg == g.context.packageName) failed("ENVOY MCP 自身の画面 (承認画面・設定など) は操作しません。利用者が自分で操作します。", summary(o)) else null

    /** アプリの名前かパッケージ名から、パッケージ名を決める。決められなければ (null, 理由) */
    protected fun resolveApp(q: String): Pair<String?, String> {
        val s = q.trim()
        if (s.isEmpty()) return null to "アプリの指定がありません。"
        return when (val r = AppMatcher.resolve(s, g.launcherApps())) {
            is AppMatcher.Result.Found -> r.pkg to ""
            is AppMatcher.Result.NotFound -> null to "「$s」というアプリが見つかりません (list_apps で確かめてください)。"
            is AppMatcher.Result.Many -> null to "「$s」に当たるアプリが複数あります: ${r.apps.take(6).joinToString("、") { "${it.second} (${it.first})" }}。パッケージ名で指定してください。"
        }
    }

    protected suspend fun waitScreen(timeoutMs: Long, cond: (Observation) -> Boolean): Observation? {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            val o = g.device.observe()
            if (o.ok && cond(o)) return o
            if (System.currentTimeMillis() >= until) return null
            delay(350)
        }
    }

    /**
     * 操作のあとに返す画面: read_screen と同じ番号つきの部品の一覧。直前に読んだ画面として覚えるので、
     * ChatGPT は読み直さずに、次の tap・type_text でこの番号を使える (押す前に、同じ部品が今もあるかは確かめる)
     */
    protected fun summary(o: Observation): JSONObject {
        if (!o.ok) return JSONObject().put("app", o.pkg ?: JSONObject.NULL).put("error", o.error ?: "画面を読み取れません")
        lastScreen = o
        return o.toModelJson(AFTER_ITEMS).put("app_label", g.appLabel(o.pkg) ?: JSONObject.NULL)
    }

    protected fun record(t: TraceStep) = synchronized(trace) { trace += t; while (trace.size > MAX_TRACE) trace.removeAt(0) }

    protected fun fromAction(r: ActionResult, what: String): ToolResult =
        if (r.unknown) unknown("$what: 操作を送ったあと接続が切れました。できたかどうか分かりません。") else failed("$what (${r.detail})。")

    companion object {
        /** 操作のあとに返す部品の数の上限 */
        private const val AFTER_ITEMS = 80
        private const val MAX_TRACE = 40
    }
}

/** 道具の結果: ok・failed・unknown (structured の result に入れ、文の後ろにも JSON を付ける) */
internal fun ok(msg: String, o: JSONObject = JSONObject()) = o.put("result", "ok").let { ToolResult("$msg\n$it", it) }
internal fun failed(msg: String, o: JSONObject = JSONObject()) = o.put("result", "failed").let { ToolResult("$msg\n$it", it, isError = true) }
internal fun unknown(msg: String, o: JSONObject = JSONObject()) = o.put("result", "unknown").let { ToolResult("$msg\n$it", it, isError = true) }
