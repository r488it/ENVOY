package com.rabi.demo.mart

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 撮影用の架空の通販「まいにちマート」。
 * いつもの品物の一覧 → 注文の確認 (品物・金額・お届け) →「注文を確定する」→ 受付の画面、の 3 つだけ。
 * 「注文を確定する」は確定のボタンなので、ENVOY MCP が押すときは、スマホでの本人の承認が出る。
 * どこにも通信しない (注文はどこにも届かない)。実在の会社・アプリの名前やロゴは使わない
 */
class MartActivity : Activity() {
    private lateinit var body: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#FFF8F0")) }
        val header = text("まいにちマート", 24f, "#FFFFFF", bold = true).apply {
            setBackgroundColor(Color.parseColor("#E8772E")); setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        // 状態バーとナビゲーションの帯に重ならないように
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            header.setPadding(dp(20), bars.top + dp(14), dp(20), dp(14))
            body.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        setContentView(root)
        showList()
    }

    @Deprecated("確認・受付の画面からは、一覧に戻る")
    override fun onBackPressed() {
        if (body.tag != LIST) showList() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------------------------------------------------------------- 画面

    /** いつもの品物の一覧 */
    private fun showList() = page(LIST) {
        addView(text("いつもの買い物", 20f, "#3A2E1E", bold = true))
        ITEMS.forEach { item ->
            addView(itemCard(item, withButton = true), gap(14))
        }
    }

    /** 注文の確認 */
    private fun showConfirm(item: Item) = page(CONFIRM) {
        addView(text("ご注文の確認", 22f, "#3A2E1E", bold = true))
        addView(itemCard(item, withButton = false), gap(16))
        addView(row("数量", "1"), gap(16))
        addView(row("お届け", "明日 ・ 自宅"), gap(8))
        addView(row("お支払い", "代金引換"), gap(8))
        addView(row("合計", item.price, strong = true), gap(8))
        addView(button("注文を確定する", "#E8772E") { showDone(item) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(28) })
        addView(text("※ 撮影用の見本です。注文はどこにも届きません。", 12f, "#9A8B78", center = true), gap(10))
    }

    /** 注文の受付 */
    private fun showDone(item: Item) = page(DONE) {
        addView(text("✓", 64f, "#2EAA5C", bold = true, center = true))
        addView(text("ご注文を受け付けました", 22f, "#3A2E1E", bold = true, center = true), gap(8))
        addView(text("${item.name}\n明日、ご自宅にお届けします", 16f, "#6B5B45", center = true), gap(12))
        addView(button("買い物を続ける", "#8A7A66") { showList() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(32) })
    }

    private fun page(tag: String, build: LinearLayout.() -> Unit) {
        body.removeAllViews()
        body.tag = tag
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24)); build() }
        body.addView(ScrollView(this).apply { addView(col) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    // ---------------------------------------------------------------- 部品

    private class Item(val name: String, val note: String, val price: String, val color: String, val mark: String)

    private fun itemCard(item: Item, withButton: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Color.WHITE, dp(16).toFloat())
        setPadding(dp(14), dp(14), dp(14), dp(14))
        // 品物の絵 (色の札に 1 文字)
        addView(text(item.mark, 26f, "#FFFFFF", bold = true, center = true).apply { background = rounded(Color.parseColor(item.color), dp(12).toFloat()) },
            LinearLayout.LayoutParams(dp(64), dp(64)))
        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(item.name, 18f, "#3A2E1E", bold = true))
            addView(text(item.note, 13f, "#9A8B78"))
            addView(text(item.price, 18f, "#D2452E", bold = true))
        }
        addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        if (withButton) addView(button("注文へ", "#E8772E") { showConfirm(item) }, LinearLayout.LayoutParams(dp(88), dp(44)))
    }

    private fun row(k: String, v: String, strong: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(text(k, 16f, "#6B5B45"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(text(v, if (strong) 22f else 16f, if (strong) "#D2452E" else "#3A2E1E", bold = strong))
    }

    private fun text(s: String, sp: Float, color: String, bold: Boolean = false, center: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (center) gravity = Gravity.CENTER
    }

    private fun button(s: String, color: String, onClick: () -> Unit) = text(s, 17f, "#FFFFFF", bold = true, center = true).apply {
        background = rounded(Color.parseColor(color), dp(26).toFloat())
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun gap(top: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val LIST = "list"
        const val CONFIRM = "confirm"
        const val DONE = "done"
        /** 架空の品物 (動画の仮の絵と同じ「いつものお米 5kg・¥2,980」) */
        val ITEMS = listOf(
            Item("いつものお米 5kg", "前回: 先月 ・ 1 袋", "¥2,980", "#C9A15B", "米"),
            Item("たまご 10 個", "前回: 先週 ・ 1 パック", "¥298", "#F2B84B", "卵"),
            Item("おいしい牛乳 1L", "前回: 先週 ・ 2 本", "¥238", "#6FA8DC", "乳"),
            Item("緑茶のティーバッグ", "前回: 2 か月前 ・ 1 箱", "¥498", "#5DA36A", "茶"),
        )
    }
}
