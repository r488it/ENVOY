package com.rabi.envoymcp.safety

import com.rabi.envoymcp.device.ActionResult
import com.rabi.envoymcp.device.DeviceController
import com.rabi.envoymcp.observe.Bounds
import com.rabi.envoymcp.observe.Observation
import com.rabi.envoymcp.observe.UiNode
import com.rabi.envoymcp.parts.ApprovalLevel
import com.rabi.envoymcp.parts.CommitKind
import com.rabi.envoymcp.parts.CommitSpec
import com.rabi.envoymcp.parts.Conditions
import com.rabi.envoymcp.parts.IntentSpec
import com.rabi.envoymcp.parts.Part
import com.rabi.envoymcp.parts.PartKind
import com.rabi.envoymcp.parts.PartRunner
import com.rabi.envoymcp.parts.RunOutcome
import com.rabi.envoymcp.parts.Selector
import com.rabi.envoymcp.parts.Step
import com.rabi.envoymcp.parts.Verification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

/**
 * 確定 (承認して押した) のあとの手順 (after_commit): 読み上げ (TalkBack) を止める部品で、スイッチのあとに出る「停止」を押す。
 * 最初から入っている部品だけが進め、確定らしい部品は押さない
 */
class AfterCommitTest {
    private fun node(i: Int, b: Bounds, text: String = "", cls: String = "android.widget.TextView", clickable: Boolean = false, depth: Int = 1) =
        UiNode(i, text, "", "", cls, "com.android.settings", b, clickable, enabled = true, focused = false, scrollable = false,
            checked = false, password = false, hint = "", depth = depth)

    /** TalkBack の画面 (「TalkBack を使用」のスイッチの行) */
    private val page = Observation("com.android.settings", null, listOf(
        node(0, Bounds(0, 0, 1080, 2400), cls = "android.widget.FrameLayout", depth = 0),
        node(1, Bounds(0, 400, 1080, 560), cls = "android.widget.LinearLayout", clickable = true),
        node(2, Bounds(60, 450, 600, 510), text = "TalkBack を使用", depth = 2),
        node(3, Bounds(900, 440, 1040, 520), cls = "android.widget.Switch", depth = 2),
    ), 1080, 2400)

    /** 止めるかを聞く画面 */
    private fun dialog(button: String) = Observation("com.android.settings", null, listOf(
        node(0, Bounds(0, 0, 1080, 2400), cls = "android.widget.FrameLayout", depth = 0),
        node(1, Bounds(80, 900, 1000, 980), text = "TalkBack を停止しますか？"),
        node(2, Bounds(500, 1300, 700, 1400), text = "キャンセル", cls = "android.widget.Button", clickable = true),
        node(3, Bounds(760, 1300, 1000, 1400), text = button, cls = "android.widget.Button", clickable = true),
    ), 1080, 2400)

    private val off = Observation("com.android.settings", null, listOf(
        node(0, Bounds(0, 0, 1080, 2400), cls = "android.widget.FrameLayout", depth = 0),
        node(1, Bounds(60, 450, 600, 510), text = "TalkBack を使用 (オフ)"),
    ), 1080, 2400)

    /** 押すたびに、次の画面になる偽の端末 */
    private class SeqDevice(private val screens: List<Observation>) : DeviceController {
        var taps = 0
        override val isConnected = true
        override suspend fun observe(screenshot: Boolean) = screens[minOf(taps, screens.size - 1)]
        override suspend fun launchApp(pkg: String) = ActionResult.OK
        override suspend fun startIntent(spec: IntentSpec) = ActionResult.OK
        override suspend fun tap(x: Int, y: Int): ActionResult { taps++; return ActionResult.OK }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) = ActionResult.OK
        override suspend fun back() = ActionResult.OK
        override suspend fun home() = ActionResult.OK
        override suspend fun typeText(text: String) = ActionResult.OK
        override suspend fun clearFocusedField() = ActionResult.OK
        override suspend fun isInstalled(pkg: String) = true
    }

    /** 承認する */
    private class YesUi : ApprovalUi {
        var asked = 0
        override suspend fun ask(req: ApprovalRequest, manager: ApprovalManager): ApprovalDecision { asked++; return ApprovalDecision.Approved(manager.issue(req, "test")) }
    }

    private val switch = Step("switch", "tap", selector = Selector(text = listOf("TalkBack を使用"), exact = true), commit = true)
    private val stop = Step("stop", "tap", selector = Selector(text = listOf("停止"), exact = true), optional = true, afterCommit = true)

    private fun part(source: String, vararg steps: Step) = Part("p", "読み上げを止める", PartKind.APP, 1, "", emptyList(), emptyList(), null,
        Conditions(), emptyList(), steps.toList(), null, CommitSpec(CommitKind.OTHER, ApprovalLevel.BIOMETRIC, "読み上げを止めます", mapOf("止めるもの" to "TalkBack")),
        Verification(), source)

    private fun runner(d: SeqDevice, ui: YesUi) = PartRunner(d, CommitFlow(d, ui, ApprovalManager(), DuplicateGuard()), pollMs = 1)

    @Test fun builtinPressesStopAfterApproval() = runTest {
        val d = SeqDevice(listOf(page, dialog("停止"), off)); val ui = YesUi()
        val out = runner(d, ui).run(part("builtin", switch, stop), emptyMap())
        assertTrue(out.toString(), out is RunOutcome.Success)
        assertEquals(1, ui.asked)
        assertEquals(2, d.taps)   // スイッチと「停止」
    }

    @Test fun learnedPartEndsAtCommit() = runTest {
        // 覚えた部品 (AI エージェントが保存したもの) は、確定のあとに進まない
        val d = SeqDevice(listOf(page, dialog("停止"), off)); val ui = YesUi()
        val out = runner(d, ui).run(part("learned", switch, stop), emptyMap())
        assertTrue(out.toString(), out is RunOutcome.Success)
        assertEquals(1, d.taps)
    }

    @Test fun commitLikeButtonAfterCommitIsNotPressed() = runTest {
        // 確定のあとの手順でも、確定らしい部品 (送信など) は押さない
        val d = SeqDevice(listOf(page, dialog("送信"), off)); val ui = YesUi()
        val send = Step("send", "tap", selector = Selector(text = listOf("送信"), exact = true), afterCommit = true)
        val out = runner(d, ui).run(part("builtin", switch, send), emptyMap())
        assertTrue(out.toString(), out is RunOutcome.Unknown)
        assertEquals(1, d.taps)
    }

    @Test fun afterCommitStepIsSkippedWithoutCommit() = runTest {
        // 確定を押していなければ、確定のあとの手順は進めない
        val d = SeqDevice(listOf(dialog("停止"))); val ui = YesUi()
        val out = runner(d, ui).run(part("builtin", stop), emptyMap())
        assertTrue(out.toString(), out is RunOutcome.Success)
        assertEquals(0, d.taps)
        assertEquals(0, ui.asked)
    }

    @Test fun talkbackPartReadsAfterCommit() {
        // 最初から入っている「読み上げを止める」部品: スイッチは確定、そのあとの「停止」は確定のあとの手順
        val j = JSONObject(File("src/main/assets/parts/app_settings_talkback_off.json").readText())
        val steps = j.getJSONArray("steps").let { a -> (0 until a.length()).map { Step.from(a.getJSONObject(it)) } }
        assertTrue(steps.first { it.id == "switch" }.commit)
        assertTrue(steps.first { it.id == "stop" }.afterCommit)
        assertEquals(true, Step.from(steps.first { it.id == "stop" }.toJson()).afterCommit)
    }
}
