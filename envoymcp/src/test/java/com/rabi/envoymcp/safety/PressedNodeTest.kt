package com.rabi.envoymcp.safety

import com.rabi.envoymcp.device.ActionResult
import com.rabi.envoymcp.device.DeviceController
import com.rabi.envoymcp.observe.Bounds
import com.rabi.envoymcp.observe.Observation
import com.rabi.envoymcp.observe.UiNode
import com.rabi.envoymcp.parts.CommitKind
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 確定かどうかを、選んだ部品ではなく、実際に押される部品 (押せる親) とその中身で見る */
class PressedNodeTest {
    /** 部品の木を、上から順の並びと深さで作る (uiautomator dump と同じ並び) */
    private class Tree {
        val nodes = ArrayList<UiNode>()
        fun add(depth: Int, b: Bounds, text: String = "", desc: String = "", id: String = "", cls: String = "android.view.View", clickable: Boolean = false): UiNode =
            UiNode(nodes.size, text, desc, id, cls, "com.example", b, clickable, enabled = true, focused = false, scrollable = false,
                checked = false, password = false, hint = "", depth = depth).also { nodes += it }
        fun obs() = Observation("com.example", null, nodes.toList(), 1080, 2400)
    }

    private fun Tree.root() = add(0, Bounds(0, 0, 1080, 2400), cls = "android.widget.FrameLayout")

    /** 送信のボタンが「名前の無い押せる枠 > 説明が『送信』のアイコン」の形 (View で作ったアプリによくある) */
    private fun chatComposer(iconDesc: String = "送信"): Pair<Tree, UiNode> {
        val t = Tree()
        t.root()
        t.add(1, Bounds(0, 2200, 1080, 2400), cls = "android.widget.LinearLayout")
        t.add(2, Bounds(0, 2200, 900, 2400), text = "こんにちは", cls = "android.widget.EditText", clickable = true)
        val frame = t.add(2, Bounds(900, 2200, 1080, 2400), id = "com.example:id/composer_action", cls = "android.widget.FrameLayout", clickable = true)
        t.add(3, Bounds(930, 2230, 1050, 2370), desc = iconDesc, cls = "android.widget.ImageView")
        return t to frame
    }

    @Test fun unnamedClickableFrameAroundSendIconNeedsApproval() {
        val (t, frame) = chatComposer()
        val hit = CommitDetector.commitIn(t.obs(), frame)
        assertNotNull(hit)
        assertEquals("送信", hit!!.desc)
        assertEquals(CommitKind.SEND, CommitDetector.kindOf(hit))
        // 位置で押すとき (visual_id など) も同じ
        assertNotNull(CommitDetector.commitAt(t.obs(), frame.bounds.cx, frame.bounds.cy))
        // 中のアイコンが確定でなければ、承認は要らない
        val (t2, frame2) = chatComposer(iconDesc = "絵文字")
        assertNull(CommitDetector.commitIn(t2.obs(), frame2))
    }

    /** 「購入する」のボタンの中の、名前の無い子を選んでも、押されるのはボタン */
    @Test fun unnamedChildOfBuyButtonNeedsApproval() {
        val t = Tree()
        t.root()
        val button = t.add(1, Bounds(100, 1000, 980, 1150), text = "購入する", cls = "android.widget.Button", clickable = true)
        val icon = t.add(2, Bounds(120, 1020, 200, 1130), cls = "android.widget.ImageView")
        assertSame(button, CommitDetector.commitIn(t.obs(), icon))
    }

    /** 名前の無い押せる枠の中の長い文字も、最初の区切りまでが確定のボタンの文字なら確定 */
    @Test fun longLabelInsideUnnamedFrame() {
        val t = Tree()
        t.root()
        val frame = t.add(1, Bounds(40, 1500, 1040, 1700), cls = "android.widget.FrameLayout", clickable = true)
        val label = t.add(2, Bounds(60, 1520, 1020, 1680), text = "注文を確定する (お支払い金額 ¥12,800 税込・送料無料・明日お届け予定)", cls = "android.widget.TextView")
        assertSame(label, CommitDetector.commitIn(t.obs(), frame))
    }

    /** 押せる行の中に、アイコンと「送信」の文字が並ぶ形: アイコンの位置を押しても確定 */
    @Test fun sendLabelBesideIconInClickableRow() {
        val t = Tree()
        t.root()
        val row = t.add(1, Bounds(0, 500, 1080, 700), cls = "android.widget.LinearLayout", clickable = true)
        t.add(2, Bounds(20, 520, 180, 680), cls = "android.widget.ImageView")
        val label = t.add(2, Bounds(200, 560, 400, 640), text = "送信", cls = "android.widget.TextView")
        assertSame(label, CommitDetector.commitAt(t.obs(), 100, 600))
        assertSame(label, CommitDetector.commitIn(t.obs(), row))
    }

    /** トークの一覧の行: 最後の言葉 (「通話時間」「送信しました」「送るね」) では止めない (行を開くのは確定ではない) */
    @Test fun chatListRowsDoNotNeedApproval() {
        val t = Tree()
        t.root()
        val rows = listOf("通話時間 1:23", "写真を送信しました", "明日送るね", "予約するね",
            "了解！明日の 10 時に予約するね。また連絡します。よろしくお願いします").mapIndexed { i, preview ->
            val top = 300 + i * 200
            val row = t.add(1, Bounds(0, top, 1080, top + 200), cls = "android.widget.LinearLayout", clickable = true)
            t.add(2, Bounds(20, top + 20, 180, top + 180), desc = "プロフィール", cls = "android.widget.ImageView", clickable = true)
            val name = t.add(2, Bounds(200, top + 20, 800, top + 80), text = "山田太郎", cls = "android.widget.TextView")
            t.add(2, Bounds(200, top + 100, 800, top + 160), text = preview, cls = "android.widget.TextView")
            t.add(2, Bounds(850, top + 20, 1060, top + 80), text = "12:30", cls = "android.widget.TextView")
            row to name
        }
        val o = t.obs()
        for ((row, name) in rows) {
            assertNull(CommitDetector.commitIn(o, row))
            assertNull(CommitDetector.commitIn(o, name))
        }
    }

    /** 行の中の別の押せる部品 (削除のボタン) は、その位置を押さなければ押されない */
    @Test fun nestedButtonElsewhereIsNotPressed() {
        val t = Tree()
        t.root()
        val row = t.add(1, Bounds(0, 800, 1080, 1000), cls = "android.widget.LinearLayout", clickable = true)
        t.add(2, Bounds(20, 820, 700, 980), text = "メモ 1", cls = "android.widget.TextView")
        val delete = t.add(2, Bounds(900, 820, 1060, 980), desc = "削除", cls = "android.widget.ImageButton", clickable = true)
        assertNull(CommitDetector.commitIn(t.obs(), row))
        assertSame(delete, CommitDetector.commitIn(t.obs(), delete))
        assertSame(delete, CommitDetector.commitAt(t.obs(), 980, 900))
    }

    /** 画面全体の押せる入れ物は、押した場所に重なる中身だけを見る (画面のどこかの「送信」では止めない) */
    @Test fun hugeReceiverOnlyLooksAtThePoint() {
        val t = Tree()
        t.add(0, Bounds(0, 0, 1080, 2400), cls = "android.widget.FrameLayout", clickable = true)
        t.add(1, Bounds(0, 100, 1080, 200), text = "タイトル", cls = "android.widget.TextView")
        t.add(1, Bounds(0, 2000, 1080, 2100), text = "送信", cls = "android.widget.TextView")
        assertNull(CommitDetector.commitAt(t.obs(), 540, 150))
        assertNotNull(CommitDetector.commitAt(t.obs(), 540, 2050))
    }

    /** スイッチのある設定の行は、行を押しても設定の切り替え */
    @Test fun switchRowTogglesSetting() {
        val t = Tree()
        t.root()
        val row = t.add(1, Bounds(0, 1200, 1080, 1350), cls = "android.widget.LinearLayout", clickable = true)
        t.add(2, Bounds(40, 1240, 600, 1310), text = "Wi-Fi", cls = "android.widget.TextView")
        t.add(2, Bounds(900, 1230, 1040, 1320), cls = "android.widget.Switch")
        assertNotNull(CommitDetector.commitIn(t.obs(), row))
    }

    /** 見本の買い物アプリ: 品物の「選ぶ」と品物の名前は確定ではなく、「注文を確定する」は確定 */
    @Test fun martDemo() {
        val t = Tree()
        t.root()
        t.add(1, Bounds(40, 300, 1040, 460), cls = "android.widget.LinearLayout")
        val name = t.add(2, Bounds(200, 320, 700, 370), text = "いつものお米 5kg", cls = "android.widget.TextView")
        t.add(2, Bounds(200, 380, 700, 420), text = "前回: 先月 ・ 1 袋", cls = "android.widget.TextView")
        val choose = t.add(2, Bounds(800, 340, 1000, 420), text = "選ぶ", cls = "android.widget.TextView", clickable = true)
        val confirm = t.add(1, Bounds(40, 1500, 1040, 1650), text = "注文を確定する", cls = "android.widget.TextView", clickable = true)
        val o = t.obs()
        assertNull(CommitDetector.commitIn(o, choose))
        assertNull(CommitDetector.commitIn(o, name))
        assertSame(confirm, CommitDetector.commitIn(o, confirm))
    }

    // ---------------------------------------------------------------- 部品の実行 (run_part) でも同じ

    /** 画面を返し、押した数を数える偽の端末 */
    private class FakeDevice(private val o: Observation) : DeviceController {
        var taps = 0
        override val isConnected = true
        override suspend fun observe(screenshot: Boolean) = o
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

    /** 承認を求められたら覚えて、断る */
    private class DenyUi : ApprovalUi {
        var asked: ApprovalRequest? = null
        override suspend fun ask(req: ApprovalRequest, manager: ApprovalManager): ApprovalDecision { asked = req; return ApprovalDecision.Denied("テスト") }
    }

    private fun part(step: Step) = Part("p", "試し", PartKind.APP, 1, "", emptyList(), emptyList(), null, Conditions(), emptyList(),
        listOf(step), null, null, Verification(), "test")

    private fun runner(o: Observation): Triple<PartRunner, FakeDevice, DenyUi> {
        val d = FakeDevice(o); val ui = DenyUi()
        return Triple(PartRunner(d, CommitFlow(d, ui, ApprovalManager(), DuplicateGuard()), pollMs = 1), d, ui)
    }

    @Test fun partTapOnUnnamedFrameAsksApproval() = runTest {
        val (t, _) = chatComposer()
        val (r, d, ui) = runner(t.obs())
        val out = r.run(part(Step("s1", "tap", selector = Selector(id = listOf("composer_action")))), emptyMap())
        assertTrue(out.toString(), out is RunOutcome.Rejected)
        assertEquals(0, d.taps)
        assertEquals(CommitKind.SEND, ui.asked?.kind)
    }

    @Test fun partTapOnHarmlessFrameTaps() = runTest {
        val (t, _) = chatComposer(iconDesc = "絵文字")
        val (r, d, ui) = runner(t.obs())
        val out = r.run(part(Step("s1", "tap", selector = Selector(id = listOf("composer_action")))), emptyMap())
        assertTrue(out.toString(), out is RunOutcome.Success)
        assertEquals(1, d.taps)
        assertNull(ui.asked)
    }

    /** 文字を入れる手順で、入力欄でない確定らしい部品を指していたら、押さずに止める */
    @Test fun partTypeOnSendButtonDoesNotTap() = runTest {
        val (t, _) = chatComposer()
        val (r, d, _) = runner(t.obs())
        val out = r.run(part(Step("s1", "type", selector = Selector(id = listOf("composer_action")), text = "hi")), emptyMap())
        assertTrue(out.toString(), out is RunOutcome.Escalate)
        assertEquals(0, d.taps)
    }
}
