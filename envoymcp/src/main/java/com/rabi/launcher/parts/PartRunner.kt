package com.rabi.launcher.parts

import com.rabi.launcher.core.Text
import com.rabi.launcher.device.ActionResult
import com.rabi.launcher.device.DeviceController
import com.rabi.launcher.observe.Observation
import com.rabi.launcher.observe.UiNode
import com.rabi.launcher.safety.CommitDetector
import com.rabi.launcher.safety.CommitFlow
import com.rabi.launcher.safety.CommitResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** 部品の実行結果。成功と誤認しないよう、確かめられなかったものは Unknown / Escalate にする。 */
sealed class RunOutcome {
    data class Success(val summary: String, val vars: Map<String, String>) : RunOutcome()
    /** 画面が想定と違う → 止めて System 2 に渡す */
    data class Escalate(val stepIndex: Int, val reason: String, val obs: Observation?) : RunOutcome()
    /** 本人が承認しなかった */
    data class Rejected(val reason: String) : RunOutcome()
    /** 承認後に内容が変わった → 止めた (再承認が要る) */
    data class ContentChanged(val stepIndex: Int, val reason: String) : RunOutcome()
    /** 確定は送ったが、結果を確かめられない。自動では再送しない */
    data class Unknown(val reason: String) : RunOutcome()
    data class Failed(val reason: String) : RunOutcome()
}

interface RunListener {
    fun onStep(index: Int, step: Step) {}
    /** 最初の端末操作を始めたとき (応答時間の計測) */
    fun onFirstAction() {}
    suspend fun say(text: String) {}
}

/**
 * 部品を手順どおりに実行する。毎回 LLM に考えさせない。
 * 各操作のあとに画面を確かめ、想定と違えば止める。確定操作は承認とその直前の再確認なしには実行しない。
 */
class PartRunner(
    private val device: DeviceController,
    private val commitFlow: CommitFlow,
    private val pollMs: Long = 350,
) {
    suspend fun run(part: Part, args: Map<String, ArgValue>, listener: RunListener = object : RunListener {}): RunOutcome {
        val vars = HashMap<String, String>()
        for ((name, v) in args) {
            vars[name] = v.display
            for ((k, fv) in v.fields) vars["$name.$k"] = fv
        }
        part.app?.let { app ->
            val pkg = (listOfNotNull(app.pkg) + app.alternatives).firstOrNull { device.isInstalled(it) }
                ?: return RunOutcome.Failed("${app.label} のアプリが入っていません")
            vars["app.package"] = pkg
            vars["app.label"] = app.label
        }
        if (part.conditions.requiresAdb && !device.isConnected) return RunOutcome.Failed("端末の操作の接続が切れています")

        var acted = false
        var i = 0
        var lastObs: Observation? = null
        val steps = part.steps
        var guard = 0
        while (i < steps.size) {
            currentCoroutineContext().ensureActive()
            if (++guard > 200) return RunOutcome.Escalate(i, "手順が繰り返しになっています", lastObs)
            val step = steps[i]
            listener.onStep(i, step)
            if (!acted && step.op in ACTION_OPS) { acted = true; listener.onFirstAction() }
            val r: StepResult = when (step.op) {
                "launch" -> {
                    val pkg = Text.fill(step.pkg ?: "{app.package}", vars) ?: return RunOutcome.Failed("アプリの指定が足りません")
                    action(device.launchApp(pkg))
                }
                "panel" -> action(device.openPanel(step.text ?: "quick_settings"))
                "shortcut" -> {
                    val pkg = Text.fill(step.pkg ?: "{app.package}", vars) ?: return RunOutcome.Failed("アプリの指定が足りません")
                    val id = step.shortcut ?: return RunOutcome.Failed("ショートカットの指定がありません")
                    // アプリの更新でショートカットが無くなっていたら、画面から進める (System 2 に渡す)
                    val r = device.openShortcut(pkg, id)
                    if (!r.ok) return RunOutcome.Escalate(i, "ショートカットを開けません (${r.detail})", lastObs)
                    action(r)
                }
                "intent" -> {
                    val spec = step.intent ?: return RunOutcome.Failed("intent の指定がありません")
                    val filled = IntentSpec(
                        spec.action?.let { Text.fill(it, vars) ?: return RunOutcome.Failed("intent の値が足りません") },
                        spec.data?.let { Text.fill(it, vars) ?: return RunOutcome.Failed("intent の値が足りません") },
                        spec.pkg?.let { Text.fill(it, vars) }, spec.component?.let { Text.fill(it, vars) }, spec.category,
                        spec.extras.mapValues { (_, v) -> Text.fill(v, vars) ?: return RunOutcome.Failed("intent の値が足りません") },
                    )
                    action(device.startIntent(filled))
                }
                "tap" -> {
                    val obs = device.observe().also { lastObs = it }
                    if (!obs.ok) return disconnectedOrEscalate(i, obs)
                    when (val f = Matcher.find(obs, step.selector ?: Selector(), vars)) {
                        is Matcher.Found.One -> {
                            // 部品に確定と書かれていなくても、確定らしい要素は承認なしに押さない
                            if (step.commit || CommitDetector.looksLikeCommit(f.node) || CommitDetector.togglesSetting(obs, f.node)) {
                                return commit(part, step, i, f.node, vars, obs, listener)
                            }
                            action(device.tap(f.node.bounds.cx, f.node.bounds.cy))
                        }
                        is Matcher.Found.Many -> if (step.commit || f.nodes.any { CommitDetector.looksLikeCommit(it) }) return RunOutcome.Escalate(i, "押す場所が一つに決まりません (${f.nodes.size} 個)", obs)
                            else action(device.tap(f.nodes[0].bounds.cx, f.nodes[0].bounds.cy))
                        is Matcher.Found.None -> if (step.optional) StepResult.Skipped else return RunOutcome.Escalate(i,
                            if (step.selector?.isEmpty != false) "押す場所の手がかりが部品にありません" else "「${step.selector}」が画面に見つかりません", obs)
                        is Matcher.Found.BadTemplate -> return RunOutcome.Failed("必要な情報が足りません (${f.what})")
                    }
                }
                "type" -> {
                    val text = Text.fill(step.text ?: "", vars) ?: return RunOutcome.Failed("入力する内容が足りません")
                    if (text.contains('\n')) return RunOutcome.Failed("改行は送信になることがあるので入力しません")
                    var target: UiNode? = null
                    if (step.selector != null) {
                        val obs = device.observe().also { lastObs = it }
                        if (!obs.ok) return disconnectedOrEscalate(i, obs)
                        target = when (val f = Matcher.find(obs, step.selector, vars)) {
                            is Matcher.Found.One -> f.node
                            is Matcher.Found.Many -> f.nodes.firstOrNull { it.editable } ?: f.nodes[0]
                            else -> return RunOutcome.Escalate(i, "入力欄が見つかりません", obs)
                        }
                        if (target.password) return RunOutcome.Failed("パスワード欄には入力しません")
                        val tr = device.tap(target.bounds.cx, target.bounds.cy)
                        if (!tr.ok) return actionFailed(i, tr)
                        delay(250)
                        if (target.text.isNotEmpty() && target.text != target.hint) device.clearFocusedField()
                    }
                    val tr = device.typeText(text)
                    if (!tr.ok) return actionFailed(i, tr)
                    // 日本語入力が本当に入ったかを画面で確かめる
                    val ok = waitUntil(1800) { o -> lastObs = o; o.nodes.any { it.editable && Text.norm(it.text).contains(Text.norm(text)) } }
                    if (!ok) return RunOutcome.Escalate(i, "入力した文字が画面で確認できません", lastObs)
                    StepResult.Done
                }
                "swipe" -> {
                    val obs = lastObs ?: device.observe().also { lastObs = it }
                    action(swipe(obs, step.direction ?: "up"))
                }
                "scroll_to" -> {
                    val sel = step.selector ?: return RunOutcome.Failed("scroll_to の指定がありません")
                    var found = false
                    for (n in 0 until 8) {
                        val obs = device.observe().also { lastObs = it }
                        if (!obs.ok) return disconnectedOrEscalate(i, obs)
                        if (Matcher.find(obs, sel, vars) is Matcher.Found.One || Matcher.find(obs, sel, vars) is Matcher.Found.Many) { found = true; break }
                        val sr = swipe(obs, step.direction ?: "up")
                        if (!sr.ok) return actionFailed(i, sr)
                        delay(450)
                    }
                    if (!found) return RunOutcome.Escalate(i, "「$sel」までたどり着けません", lastObs)
                    StepResult.Done
                }
                "back" -> action(device.back())
                "home" -> action(device.home())
                "wait" -> { delay(step.ms.coerceIn(0, 10_000)); StepResult.Done }
                "expect" -> StepResult.Done   // 下の expect でまとめて確かめる
                "branch" -> {
                    val e = step.expect ?: return RunOutcome.Failed("branch の条件がありません")
                    val obs = device.observe().also { lastObs = it }
                    if (Matcher.check(obs, e.copy(timeoutMs = 0), vars).ok && step.goto != null) {
                        val j = steps.indexOfFirst { it.id == step.goto }
                        if (j < 0) return RunOutcome.Failed("branch の行き先 ${step.goto} がありません")
                        i = j; continue
                    }
                    i++; continue
                }
                "say" -> { Text.fill(step.text ?: "", vars)?.let { listener.say(it) }; StepResult.Done }
                "read" -> {
                    val obs = device.observe().also { lastObs = it }
                    val f = Matcher.find(obs, step.selector ?: Selector(), vars)
                    val node = (f as? Matcher.Found.One)?.node ?: (f as? Matcher.Found.Many)?.nodes?.firstOrNull()
                    if (node == null) { if (step.optional) StepResult.Skipped else return RunOutcome.Escalate(i, "読む場所が見つかりません", obs) }
                    else { vars[step.into ?: "read"] = node.label; StepResult.Done }
                }
                else -> return RunOutcome.Failed("未対応の手順 ${step.op}")
            }
            when (r) {
                is StepResult.Failed -> return actionFailed(i, r.result)
                StepResult.Skipped -> { i++; continue }
                StepResult.Done -> {}
            }
            step.expect?.let { e ->
                val res = waitExpect(e, vars) { lastObs = it }
                if (!res.ok) {
                    if (!device.isConnected) return RunOutcome.Failed("途中で端末の操作の接続が切れました")
                    return RunOutcome.Escalate(i, "画面が想定と違います (${res.why})", lastObs)
                }
            }
            i++
        }
        part.success?.let { e ->
            val res = waitExpect(e, vars) { lastObs = it }
            if (!res.ok) return RunOutcome.Escalate(steps.size, "終わりの画面を確認できません (${res.why})", lastObs)
        }
        return RunOutcome.Success(part.name, vars)
    }

    /** 確定操作: CommitFlow (画面確認 → 承認 → 戻りを待つ → 再確認 → 押す → 結果確認) に任せる */
    private suspend fun commit(part: Part, step: Step, i: Int, node: UiNode, vars: Map<String, String>, obs: Observation, listener: RunListener): RunOutcome {
        val spec = part.commit
        val fields = LinkedHashMap<String, String>()
        spec?.fields?.forEach { (k, t) -> fields[k] = Text.fill(t, vars) ?: return RunOutcome.Failed("確認に出す「$k」が足りません") }
        if (fields.isEmpty()) fields["操作"] = node.label.ifBlank { "確定" }
        val checks = spec?.screenChecks?.map { Text.fill(it, vars) ?: return RunOutcome.Failed("確認する文字が足りません") }.orEmpty()
        val kind = spec?.kind ?: CommitDetector.kindOf(node)
        val level = spec?.approval ?: ApprovalLevel.BIOMETRIC
        val summary = spec?.summary?.let { Text.fill(it, vars) } ?: "「${node.label}」を押して確定します"
        val r = commitFlow.execute(node, step.selector ?: Selector(), vars, obs, kind, level, summary, fields, checks, step.expect ?: part.success)
        return when (r) {
            is CommitResult.Done -> RunOutcome.Success(r.summary, vars)   // 確定のあとに手順が残っていても、ここで終える
            is CommitResult.Rejected -> RunOutcome.Rejected(r.reason)
            is CommitResult.Changed -> RunOutcome.ContentChanged(i, r.reason)
            is CommitResult.Unknown -> RunOutcome.Unknown(r.reason)
            is CommitResult.NotReady -> RunOutcome.Escalate(i, r.reason, obs)
        }
    }

    private sealed class StepResult {
        data object Done : StepResult()
        data object Skipped : StepResult()
        data class Failed(val result: ActionResult) : StepResult()
    }

    private fun action(r: ActionResult): StepResult = if (r.ok) StepResult.Done else StepResult.Failed(r)

    private fun actionFailed(i: Int, r: ActionResult): RunOutcome =
        if (r.unknown || !device.isConnected) RunOutcome.Failed("端末の操作の接続が切れました (${r.detail})")
        else RunOutcome.Escalate(i, "操作が失敗しました (${r.detail})", null)

    private fun disconnectedOrEscalate(i: Int, obs: Observation): RunOutcome =
        if (!device.isConnected) RunOutcome.Failed("端末の操作の接続が切れました") else RunOutcome.Escalate(i, "画面を読み取れません (${obs.error})", obs)

    private suspend fun swipe(obs: Observation, dir: String): ActionResult {
        val w = obs.screenW.takeIf { it > 0 } ?: 1080; val h = obs.screenH.takeIf { it > 0 } ?: 2400
        return when (dir) {
            "down" -> device.swipe(w / 2, h * 3 / 10, w / 2, h * 7 / 10)   // 指を下へ (上の内容を見る)
            "left" -> device.swipe(w * 8 / 10, h / 2, w * 2 / 10, h / 2)
            "right" -> device.swipe(w * 2 / 10, h / 2, w * 8 / 10, h / 2)
            else -> device.swipe(w / 2, h * 7 / 10, w / 2, h * 3 / 10)     // 指を上へ (下の内容を見る)
        }
    }

    private suspend fun waitExpect(e: Expect, vars: Map<String, String>, onObs: (Observation) -> Unit): Matcher.ExpectResult {
        val until = System.currentTimeMillis() + e.timeoutMs
        var last = Matcher.ExpectResult(false, "未確認")
        while (true) {
            currentCoroutineContext().ensureActive()
            val obs = device.observe()
            onObs(obs)
            last = Matcher.check(obs, e, vars)
            if (last.ok) return last
            if (!device.isConnected) return Matcher.ExpectResult(false, "接続が切れた")
            if (System.currentTimeMillis() >= until) return last
            delay(pollMs)
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, cond: (Observation) -> Boolean): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            currentCoroutineContext().ensureActive()
            val obs = device.observe()
            if (obs.ok && cond(obs)) return true
            if (!device.isConnected || System.currentTimeMillis() >= until) return false
            delay(pollMs)
        }
    }

    companion object {
        val ACTION_OPS = setOf("launch", "shortcut", "panel", "intent", "tap", "type", "swipe", "scroll_to", "back", "home")
    }
}
