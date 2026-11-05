package com.rabi.demo.puzzle

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * 撮影用の架空のゲーム「ぷちぷちパズル」。
 * - 開いて少しすると、パズルの画面の上に全画面の広告が出る (「パズルで脳トレ！」)
 * - 広告は右上の小さな × でだけ閉じられる (戻るでは消えない。緑のボタンを押しても、どこにもつながらない)
 * - 「つぎのステージへ」を押すと、また広告が出る (撮り直しのため)
 * どこにも通信しない。実在の会社・アプリの名前やロゴは使わない
 */
class PuzzleActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var ad: View
    private lateinit var close: TextView
    private lateinit var scoreText: TextView
    private var score = 120
    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.addView(game(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        ad = adView()
        ad.visibility = View.GONE
        root.addView(ad, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // 状態バーとナビゲーションの帯に重ならないように (× は状態バーのすぐ下に置く)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            root.getChildAt(0).setPadding(dp(20), bars.top + dp(16), dp(20), bars.bottom + dp(16))
            (close.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + dp(10)
            close.requestLayout()
            insets
        }
        setContentView(root)
        main.postDelayed({ showAd() }, AD_DELAY_MS)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- ゲームの画面

    private fun game(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor("#FFF7EC"))
        addView(text("ぷちぷちパズル", 28f, "#3A2E5C", bold = true))
        scoreText = text("ステージ 3 ・ スコア $score", 16f, "#6B5E8C")
        addView(scoreText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        val grid = GridLayout(context).apply { columnCount = GRID; rowCount = GRID }
        val size = (resources.displayMetrics.widthPixels - dp(40) - dp(6) * GRID * 2) / GRID
        repeat(GRID * GRID) { i ->
            var c = i % COLORS.size
            val tile = View(context)
            tile.background = rounded(COLORS[c], dp(10).toFloat())
            tile.setOnClickListener {
                c = (c + 1) % COLORS.size
                tile.background = rounded(COLORS[c], dp(10).toFloat())
                score += 10; scoreText.text = "ステージ 3 ・ スコア $score"
            }
            grid.addView(tile, GridLayout.LayoutParams().apply { width = size; height = size; setMargins(dp(6), dp(6), dp(6), dp(6)) })
        }
        addView(grid, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(24); gravity = Gravity.CENTER_HORIZONTAL })
        addView(button("つぎのステージへ", "#7B5CFF") { showAd() },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(28) })
    }

    // ---------------------------------------------------------------- 全画面の広告

    private fun adView(): View {
        val frame = FrameLayout(this)
        frame.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#7B5CFF"), Color.parseColor("#FF7AA2")))
        frame.isClickable = true   // 広告の下のゲームを押させない
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(96), dp(28), dp(40))
        }
        col.addView(text("パズルで\n脳トレ！", 44f, "#FFFFFF", bold = true, center = true))
        col.addView(text("1 日 5 分、いつでも無料", 18f, "#FFFFFF", center = true),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        val pic = GridLayout(this).apply { columnCount = 4; rowCount = 4 }
        repeat(16) { i ->
            val v = View(this)
            v.background = rounded(if (i % 5 == 0) Color.WHITE else COLORS[(i * 3) % COLORS.size], dp(8).toFloat())
            pic.addView(v, GridLayout.LayoutParams().apply { width = dp(52); height = dp(52); setMargins(dp(5), dp(5), dp(5), dp(5)) })
        }
        col.addView(pic, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(Color.WHITE, dp(20).toFloat())
            setPadding(dp(20), dp(18), dp(20), dp(20))
            addView(text("ぷちぷちパズル", 20f, "#3A2E5C", bold = true, center = true))
            addView(text("★★★★☆ ・ ゲーム", 14f, "#8A7FA8", center = true))
            addView(button("今すぐ無料で遊ぶ", "#22B45A") {
                Toast.makeText(this@PuzzleActivity, "見本の広告です (どこにもつながりません)", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })
        }
        col.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(32) })
        frame.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        // 左上の「広告」の札と、右上の小さな × (読み上げの名前は「広告を閉じる」)
        frame.addView(text("広告", 12f, "#FFFFFF").apply {
            background = rounded(Color.parseColor("#55000000"), dp(6).toFloat()); setPadding(dp(8), dp(2), dp(8), dp(2))
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { topMargin = dp(48); marginStart = dp(16) })
        close = text("×", 16f, "#FFFFFF", bold = true, center = true).apply {
            background = rounded(Color.parseColor("#66000000"), dp(14).toFloat())
            contentDescription = "広告を閉じる"
            isClickable = true
            setOnClickListener { hideAd() }
        }
        frame.addView(close, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply { topMargin = dp(48); marginEnd = dp(14) })
        return frame
    }

    private fun showAd() {
        ad.visibility = View.VISIBLE
        holdBack(true)
    }

    private fun hideAd() {
        ad.visibility = View.GONE
        holdBack(false)
    }

    /** 広告が出ている間は、戻るを受け止めて何もしない (広告は × でだけ閉じる) */
    private fun holdBack(on: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        if (on && backCallback == null) {
            val cb = OnBackInvokedCallback { }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (!on) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
    }

    @Deprecated("Android 12 だけで使う (13 以降は holdBack)")
    override fun onBackPressed() {
        if (ad.visibility == View.VISIBLE) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------------------------------------------------------------- 部品

    private fun text(s: String, sp: Float, color: String, bold: Boolean = false, center: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (center) gravity = Gravity.CENTER
    }

    private fun button(s: String, color: String, onClick: () -> Unit) = text(s, 18f, "#FFFFFF", bold = true, center = true).apply {
        background = rounded(Color.parseColor(color), dp(28).toFloat())
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val GRID = 5
        /** 開いてから広告が出るまで */
        const val AD_DELAY_MS = 1_500L
        val COLORS = listOf("#FF7AA2", "#FFC93C", "#5CC8FF", "#7BE07B", "#B48CFF").map { Color.parseColor(it) }
    }
}
