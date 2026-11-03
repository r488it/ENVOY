package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.a11y.A11yService
import com.rabi.envoymcp.core.Text
import com.rabi.envoymcp.device.ActionResult
import com.rabi.envoymcp.observe.Bounds
import com.rabi.envoymcp.observe.Observation
import com.rabi.envoymcp.observe.UiNode
import com.rabi.envoymcp.parts.ApprovalLevel
import com.rabi.envoymcp.parts.Matcher
import com.rabi.envoymcp.parts.Selector
import com.rabi.envoymcp.parts.TraceCompiler
import com.rabi.envoymcp.parts.TraceStep
import com.rabi.envoymcp.safety.CommitDetector
import com.rabi.envoymcp.safety.CommitResult
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** 画面を操作する道具: クイック設定・通知、タップ (確定は承認のあと)、文字の入力、スクロール、戻る・ホーム、キー、待つ */
internal class OperateTools(st: ToolState) : ToolGroup(st) {
    suspend fun openPanel(a: JSONObject): ToolResult {
        val which = a.optString("which")
        val before = g.device.observe()
        val r = g.device.openPanel(which)
        if (!r.ok) return fromAction(r, "開けませんでした")
        delay(700)
        val after = g.device.observe()
        record(TraceStep("panel", text = which, before = before, after = after))
        return ok("開きました。", summary(after))
    }

    suspend fun tap(a: JSONObject): ToolResult {
        val cur = g.device.observe()
        if (!cur.ok) return failed("画面を読み取れません (${cur.error})")
        ownScreen(cur)?.let { return it }
        val node: UiNode = when {
            a.has("element_id") -> {
                val prev = lastScreen ?: return failed("先に read_screen で画面を読んでください (番号はそのときのものです)。")
                val want = prev.nodeById(a.optInt("element_id")) ?: return failed("その番号の部品はありません。read_screen で読み直してください。")
                // 番号は画面ごとに変わる: 同じ位置・同じ文字の部品が今もあるときだけ押す
                cur.nodes.firstOrNull { it.bounds == want.bounds && it.label == want.label && it.cls == want.cls }
                    ?: return failed("画面が変わったので押していません。返した今の画面の一覧から選び直してください。", summary(cur))
            }
            a.optString("visual_id").isNotBlank() -> {
                val memo = lastVision ?: return failed("先に look_at_screen で画面を見てください。")
                val t = memo.targets[a.optString("visual_id")] ?: return failed("その visual_id はありません。look_at_screen で見直してください。")
                if (cur.signature() != memo.obs.signature()) return failed("画面が変わったので押していません。look_at_screen で見直してください。", summary(cur))
                // 部品の一覧の部品か、その場所に同じくらいの大きさの名前のある部品があれば、部品として押す (確定なら承認の流れ)
                val listed = t.elementId?.let { id -> memo.obs.nodeById(id) }?.let { w -> cur.nodes.firstOrNull { it.bounds == w.bounds && it.label == w.label && it.cls == w.cls } }
                    ?: cur.nodeAt(t.bounds.cx, t.bounds.cy)?.takeIf { it.label.isNotBlank() && overlap(it.bounds, t.bounds) >= SAME_THING }
                listed ?: return visualTap(cur, t)
            }
            a.optString("text").isNotBlank() -> {
                val t = a.optString("text")
                when (val f = Matcher.find(cur, Selector(text = listOf(t), desc = listOf(t)), emptyMap())) {
                    is Matcher.Found.One -> f.node
                    is Matcher.Found.Many -> {
                        lastScreen = cur
                        val c = JSONArray(f.nodes.take(6).map { JSONObject().put("element_id", it.index).put("label", it.label).put("b", it.bounds.toString()) })
                        return failed("「$t」が ${f.nodes.size} 個あるので押していません。element_id で選んでください。", JSONObject().put("candidates", c))
                    }
                    else -> return failed("「$t」が画面に見つかりません。", summary(cur))
                }
            }
            else -> return failed("element_id・text・visual_id のどれかを指定してください。")
        }
        if (node.bounds.area <= 0) return failed("その部品は画面に出ていません。")
        // 確定らしいボタン・設定のスイッチは、承認画面で本人が承認してから押す (部品と同じ流れ)
        if (CommitDetector.looksLikeCommit(node) || CommitDetector.togglesSetting(cur, node)) return commitTap(cur, node)
        val r = g.device.tap(node.bounds.cx, node.bounds.cy)
        if (!r.ok) return fromAction(r, "押せませんでした")
        delay(600)
        val after = g.device.observe()
        record(TraceStep("tap", pkg = cur.pkg, node = node, before = cur, after = after))
        val changed = after.ok && after.signature() != cur.signature()
        return ok("「${node.label.ifBlank { node.shortId.ifBlank { "部品" } }}」を押しました。" + if (changed) "画面が変わりました。" else "画面の文字は変わっていません (読み込み中かもしれません)。",
            summary(after).put("screen_changed", changed))
    }

    /**
     * 部品の一覧に無いもの (look_at_screen で見つけたもの) を位置で押す。確定・設定の切り替えらしいものは押さない
     * (承認の流れは部品を前提にしていて、位置だけでは承認のあとに同じものを確かめられないため)。部品 (save_part) には残さない
     */
    private suspend fun visualTap(cur: Observation, t: ScreenVision.Target): ToolResult {
        val probe = UiNode(-1, t.label, "", "", if (t.kind == "switch") "android.widget.Switch" else "android.view.View", cur.pkg.orEmpty(), t.bounds,
            clickable = true, enabled = true, focused = false, scrollable = false, checked = false, password = false, hint = "", depth = 0)
        if (t.commit || t.kind == "switch" || CommitDetector.looksLikeCommit(probe) || CommitDetector.togglesSetting(cur, probe))
            return failed("「${t.label}」は確定・設定の切り替えらしく、画面の部品として読めないので押しません。利用者に押してもらってください。", summary(cur))
        val r = g.device.tap(t.bounds.cx, t.bounds.cy)
        if (!r.ok) return fromAction(r, "押せませんでした")
        delay(600)
        val after = g.device.observe()
        lastVision = null
        val changed = after.ok && after.signature() != cur.signature()
        return ok("「${t.label}」の位置を押しました。" + if (changed) "画面が変わりました。" else "画面の文字は変わっていません (読み込み中かもしれません)。",
            summary(after).put("screen_changed", changed))
    }

    /** 2 つの枠の重なり (小さい方の面積に対する割合) */
    private fun overlap(a: Bounds, b: Bounds): Float {
        val w = minOf(a.r, b.r) - maxOf(a.l, b.l); val h = minOf(a.b, b.b) - maxOf(a.t, b.t)
        if (w <= 0 || h <= 0) return 0f
        return w.toFloat() * h / minOf(a.area, b.area).coerceAtLeast(1)
    }

    /** 確定の流れ: 画面確認 → スマホで本人が承認 → 画面が変わっていないか確かめる → 押す → 結果を確かめる */
    private suspend fun commitTap(cur: Observation, node: UiNode): ToolResult {
        val label = node.label.ifBlank { "確定" }
        val app = g.appLabel(cur.pkg) ?: cur.pkg ?: ""
        val fields = linkedMapOf("操作" to label, "アプリ" to app)
        // 何が送られるか (入力欄の中身) を承認画面に出す。下書きが残っていた などにも、承認の前に気づけるように (パスワード欄は出さない)
        val content = cur.nodes.filter { it.editable && !it.password && it.text.isNotBlank() && it.text != it.hint }.joinToString("\n") { it.text }
        if (content.isNotBlank()) fields["内容"] = content.take(MAX_CONTENT)
        val sel = TraceCompiler.selectorFor(node, emptyMap(), cur)
        val summary = "${app}で「$label」を押します"
        val r = g.commitFlow.execute(node, sel, emptyMap(), cur, CommitDetector.kindOf(node), ApprovalLevel.BIOMETRIC, summary, fields, emptyList(), null)
        val after = runCatching { g.device.observe() }.getOrNull()
        val s = after?.let { summary(it) } ?: JSONObject()
        return when (r) {
            is CommitResult.Done -> {
                record(TraceStep("tap", pkg = cur.pkg, node = node, before = cur, after = after, commit = true))
                ok("利用者がスマホで承認したので「$label」を押し、画面が変わったことを確かめました。", s.put("approved", true))
            }
            is CommitResult.Rejected -> failed("利用者が承認しなかったので押していません (${r.reason})。", s.put("approved", false))
            is CommitResult.Changed -> failed("押していません: ${r.reason}。", s)
            is CommitResult.NotReady -> failed("押していません: ${r.reason}。", s)
            is CommitResult.Unknown -> unknown("${r.reason}。二重にならないよう、同じ確定を繰り返さないでください。利用者に画面で確かめてもらってください。", s)
        }
    }

    suspend fun typeText(a: JSONObject): ToolResult {
        val text = a.optString("text")
        if (text.isEmpty()) return failed("入れる文字がありません。")
        if (text.contains('\n') || text.contains('\r')) return failed("改行は送信になることがあるので入れません。改行なしで入れてください。")
        val before = g.device.observe()
        ownScreen(before)?.let { return it }
        var target: UiNode? = null
        if (a.has("element_id")) {
            val prev = lastScreen ?: return failed("先に read_screen で画面を読んでください。")
            val want = prev.nodeById(a.optInt("element_id")) ?: return failed("その番号の部品はありません。read_screen で読み直してください。")
            target = before.nodes.firstOrNull { it.bounds == want.bounds && it.cls == want.cls }
                ?: return failed("画面が変わったので入れていません。返した今の画面の一覧から選び直してください。", summary(before))
        } else {
            target = before.nodes.firstOrNull { it.focused && it.editable }
        }
        if (target?.password == true) return failed("パスワード欄には入れません。")
        if (target != null && a.has("element_id")) {
            val tr = g.device.tap(target.bounds.cx, target.bounds.cy)
            if (!tr.ok) return fromAction(tr, "入力欄を押せませんでした")
            delay(300)
            if (target.text.isNotEmpty() && target.text != target.hint) g.device.clearFocusedField()
        }
        val r = g.device.typeText(text)
        if (!r.ok) return fromAction(r, "入れられませんでした")
        // 日本語の入力が本当に入ったかを画面で確かめる
        val after = waitScreen(2_000) { o -> o.nodes.any { it.editable && Text.norm(it.text).contains(Text.norm(text)) } }
        record(TraceStep("type", pkg = before.pkg, node = target, text = text, before = before, after = after, ok = after != null))
        if (after == null) return unknown("入れる操作は送りましたが、画面で確かめられません。read_screen で確かめてください。")
        if (a.optBoolean("submit")) return submitSearch(text, after)
        return ok("「${text.take(40)}」を入れ、画面で確かめました。まだ送信はしていません。", summary(after))
    }

    /** 検索欄なら、入れた文字で検索を実行する (キーボードの実行。だめなら検索欄のときだけ Enter)。検索欄でなければ実行しない */
    private suspend fun submitSearch(text: String, cur: Observation): ToolResult {
        val field = cur.nodes.firstOrNull { it.editable && it.focused && Text.norm(it.text).contains(Text.norm(text)) }
            ?: cur.nodes.firstOrNull { it.editable && Text.norm(it.text).contains(Text.norm(text)) }
        if (field == null || !SearchField.isSearch(field.resId, field.hint, field.desc, field.cls))
            return ok("「${text.take(40)}」を入れました。検索欄ではないので、実行 (Enter) はしていません。送信のボタンなどは tap で押してください。", summary(cur))
        val done = A11yService.instance?.imeEnterOnFocused() == true ||
            (g.adb.isConnected && runCatching { g.adb.exec("input -d ${g.monitorStage.target} keyevent KEYCODE_ENTER", 5_000) }.isSuccess)
        if (!done) return failed("「${text.take(40)}」は入れましたが、検索を実行できませんでした。検索のボタンを tap で押してください。", summary(cur))
        delay(800)
        val after = g.device.observe()
        return ok("「${text.take(40)}」で検索しました。", summary(after))
    }

    suspend fun scroll(a: JSONObject): ToolResult {
        val dir = a.optString("direction", "down")
        val o = g.device.observe()
        val w = o.screenW.takeIf { it > 0 } ?: 1080; val h = o.screenH.takeIf { it > 0 } ?: 2400
        val r = when (dir) {
            "up" -> g.device.swipe(w / 2, h * 3 / 10, w / 2, h * 7 / 10)      // 指を下へ (上の内容を見る)
            "left" -> g.device.swipe(w * 2 / 10, h / 2, w * 8 / 10, h / 2)
            "right" -> g.device.swipe(w * 8 / 10, h / 2, w * 2 / 10, h / 2)
            else -> g.device.swipe(w / 2, h * 7 / 10, w / 2, h * 3 / 10)      // 指を上へ (下の内容を見る)
        }
        if (!r.ok) return fromAction(r, "スクロールできませんでした")
        delay(500)
        val after = g.device.observe()
        // 部品の「swipe」は指の向き (up = 指を上へ = 下の内容)
        record(TraceStep("swipe", direction = when (dir) { "up" -> "down"; "down" -> "up"; "left" -> "right"; else -> "left" }, before = o, after = after))
        return ok("スクロールしました。", summary(after).put("screen_changed", after.signature() != o.signature()))
    }

    private suspend fun simple(op: String, act: suspend () -> ActionResult): ToolResult {
        val before = g.device.observe()
        val r = act()
        if (!r.ok) return fromAction(r, "できませんでした")
        delay(500)
        val after = g.device.observe()
        record(TraceStep(op, before = before, after = after))
        return ok(if (op == "home") "ホームに戻りました。" else "戻りました。", summary(after))
    }

    /**
     * キーを押す (モニターの YouTube を f で全画面にするなど)。操作先の画面 (モニターにつながっていればモニター) に、
     * ADB の input keyevent で送る (ユーザー補助では文字以外のキーを送れない)。Enter は送信になることがあるので送らない
     */
    suspend fun pressKey(a: JSONObject): ToolResult {
        val raw = a.optString("key").trim().lowercase()
        if (raw in setOf("enter", "return", "numpad_enter", "\n")) return failed("Enter は送信になることがあるので押しません。確定のボタンは tap で押してください (承認のあとに押します)。")
        val code = KEYS[raw] ?: when {
            raw.length == 1 && raw[0] in 'a'..'z' -> "KEYCODE_${raw.uppercase()}"
            raw.length == 1 && raw[0] in '0'..'9' -> "KEYCODE_$raw"
            raw.matches(Regex("f([1-9]|1[0-2])")) -> "KEYCODE_${raw.uppercase()}"
            else -> null
        } ?: return failed("押せないキーです ($raw)。英字・数字 1 文字か、space・escape・矢印 (up/down/left/right) などを指定してください。")
        if (!g.adb.isConnected && !(g.adb.paired && g.adb.probeIfIdle())) return failed("キーを送るには、端末の操作の接続 (ADB) が要ります。")
        val times = a.optInt("times", 1).coerceIn(1, 10)
        val display = g.monitorStage.target
        repeat(times) {
            val r = runCatching { g.adb.exec("input -d $display keyevent $code", 5_000) }
            if (r.isFailure) return failed("キーを送れませんでした (${r.exceptionOrNull()?.javaClass?.simpleName})。")
        }
        delay(400)
        val after = g.device.observe()
        // キーの手順は部品 (save_part) にできないので、操作の記録には入れない
        val where = if (display != android.view.Display.DEFAULT_DISPLAY) "モニター" else "スマホ"
        return ok("${where}で $raw キーを${if (times > 1) " $times 回" else ""}押しました。", summary(after))
    }

    suspend fun waitFor(a: JSONObject): ToolResult {
        val ms = (a.optDouble("seconds", 2.0) * 1000).toLong().coerceIn(500, 10_000)
        delay(ms)
        val o = g.device.observe()
        record(TraceStep("wait"))
        return ok("${ms / 1000.0} 秒待ちました。", summary(o))
    }

    suspend fun back(): ToolResult = simple("back") { g.device.back() }

    suspend fun home(): ToolResult = simple("home") { g.device.home() }

    companion object {
        /** 位置で見つけたものと部品が「同じもの」とみなす重なり */
        private const val SAME_THING = 0.6f
        /** 承認画面に出す内容 (入力欄の中身) の長さの上限 */
        private const val MAX_CONTENT = 600
        /** press_key で押せる名前つきのキー (Enter は送信になるので入れない) */
        private val KEYS = mapOf(
            "space" to "KEYCODE_SPACE", "escape" to "KEYCODE_ESCAPE", "esc" to "KEYCODE_ESCAPE", "tab" to "KEYCODE_TAB",
            "backspace" to "KEYCODE_DEL", "delete" to "KEYCODE_FORWARD_DEL",
            "up" to "KEYCODE_DPAD_UP", "down" to "KEYCODE_DPAD_DOWN", "left" to "KEYCODE_DPAD_LEFT", "right" to "KEYCODE_DPAD_RIGHT",
            "page_up" to "KEYCODE_PAGE_UP", "page_down" to "KEYCODE_PAGE_DOWN", "move_home" to "KEYCODE_MOVE_HOME", "move_end" to "KEYCODE_MOVE_END",
            "play_pause" to "KEYCODE_MEDIA_PLAY_PAUSE", "next" to "KEYCODE_MEDIA_NEXT", "previous" to "KEYCODE_MEDIA_PREVIOUS",
            "volume_up" to "KEYCODE_VOLUME_UP", "volume_down" to "KEYCODE_VOLUME_DOWN", "mute" to "KEYCODE_VOLUME_MUTE",
        )
    }
}
