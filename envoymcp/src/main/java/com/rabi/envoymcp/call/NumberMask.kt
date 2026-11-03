package com.rabi.envoymcp.call

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.withClip
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.a11y.A11yService
import com.rabi.envoymcp.core.RabiLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * リゲルが代わりに電話している間、電話のアプリの画面に出る電話番号をモザイクで隠す。
 * ユーザー補助で電話のアプリの画面を読み、電話番号らしい文字 (数字が 6 桁以上) の部品の上に、モザイクの柄の四角を重ねる。
 * 重ねるのはユーザー補助の窓 (TYPE_ACCESSIBILITY_OVERLAY。電話の画面の上でも消されない)。触れない (下の電話の画面を押せる)。
 * 画面の写しから本当にぼかすことはできない (ほかのアプリの画面は読めない) ので、柄で覆って隠す
 */
class NumberMask(private val g: McpGraph) {
    private var job: Job? = null
    private var stopJob: Job? = null
    private val shown = ArrayList<Pair<View, WindowManager.LayoutParams>>()
    private var wm: WindowManager? = null

    /** 始める (もう動いていれば、そのまま。終わる予定は取り消す) */
    fun start() {
        stopJob?.cancel(); stopJob = null
        if (job?.isActive == true) return
        RabiLog.i("mask", "start")
        job = g.scope.launch(Dispatchers.Default) {
            try {
                while (isActive) {
                    val rects = find()
                    withContext(Dispatchers.Main) { place(rects) }
                    delay(POLL_MS)
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { place(emptyList()) }
            }
        }
    }

    /** after ミリ秒たったら終える (通話が終わった画面にも番号が出るので、少し残す) */
    fun stopAfter(after: Long = STOP_AFTER_MS) {
        stopJob?.cancel()
        stopJob = g.scope.launch {
            delay(after)
            job?.cancel(); job = null
            RabiLog.i("mask", "stop")
        }
    }

    /** 電話のアプリの画面で、電話番号らしい文字の部品の場所 (画面の座標) */
    private fun find(): List<Rect> {
        val s = A11yService.instance ?: return emptyList()
        return runCatching {
            DIALERS.flatMap { pkg -> s.findAllIn(pkg) { n -> looksLikeNumber(n.text) || looksLikeNumber(n.contentDescription) } }
                .map { n -> Rect().also { r -> n.getBoundsInScreen(r) } }
                .filter { it.width() > 0 && it.height() > 0 }
                .distinctBy { it.flattenToString() }
        }.getOrDefault(emptyList())
    }

    /** 隠す四角を、今の場所に合わせて出し直す (同じ数なら動かすだけ) */
    private fun place(rects: List<Rect>) {
        val s = A11yService.instance
        if (s == null || rects.isEmpty()) { clear(); return }
        val m = wm ?: (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }
        while (shown.size > rects.size) shown.removeAt(shown.size - 1).let { runCatching { m.removeView(it.first) } }
        rects.forEachIndexed { i, r ->
            val x = r.left - PAD; val y = r.top - PAD; val w = r.width() + PAD * 2; val h = r.height() + PAD * 2
            if (i < shown.size) {
                val (v, lp) = shown[i]
                if (lp.x != x || lp.y != y || lp.width != w || lp.height != h) {
                    lp.x = x; lp.y = y; lp.width = w; lp.height = h
                    runCatching { m.updateViewLayout(v, lp) }
                }
            } else {
                val lp = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.x = x; this.y = y
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    title = "rigel-number-mask"
                }
                val v = MosaicView(s)
                if (runCatching { m.addView(v, lp) }.onFailure { RabiLog.w("mask", "add failed ${it.javaClass.simpleName}") }.isSuccess) {
                    shown.add(v to lp)
                    if (shown.size == 1) RabiLog.i("mask", "covering the number")
                }
            }
        }
    }

    private fun clear() {
        val m = wm
        if (shown.isNotEmpty()) RabiLog.i("mask", "uncovered")
        shown.forEach { (v, _) -> runCatching { m?.removeView(v) } }
        shown.clear()
    }

    /** モザイクの柄 (決まった並びの灰色と青のます目。角は丸める) */
    private class MosaicView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tile = context.resources.displayMetrics.density * 7f
        private val clip = Path()
        private val bounds = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = tile * 0.9f
            val w = width; val h = height
            bounds.set(0f, 0f, w.toFloat(), h.toFloat())
            clip.reset(); clip.addRoundRect(bounds, r, r, Path.Direction.CW)
            canvas.withClip(clip) {
                var j = 0; var y = 0f
                while (y < h) {
                    var i = 0; var x = 0f
                    while (x < w) {
                        val k = ((i * 7349 + j * 1231 + i * j * 17) % 6 + 6) % 6
                        paint.color = SHADES[k]
                        canvas.drawRect(x, y, x + tile, y + tile, paint)
                        x += tile; i++
                    }
                    y += tile; j++
                }
            }
        }
    }

    companion object {
        /** 電話のアプリ (Pixel は Google の電話。ほかの機種の名前も) */
        val DIALERS = listOf("com.google.android.dialer", "com.android.dialer", "com.android.incallui", "com.android.server.telecom")
        private const val POLL_MS = 250L
        private const val STOP_AFTER_MS = 3_000L
        private const val PAD = 8
        private val SHADES = intArrayOf(0xFF2B3442.toInt(), 0xFF3A4557.toInt(), 0xFF4A5870.toInt(), 0xFF56647A.toInt(), 0xFF323C4D.toInt(), 0xFF425066.toInt())
        /** 電話番号らしい並び (数字のあいだに空白・ハイフン・かっこ・点があってよい) */
        private val NUMBER_RE = Regex("""[+＋]?[0-9０-９][0-9０-９\s\-‐－–()（）.・]{4,}[0-9０-９]""")

        /** 電話番号らしい文字を含むか (数字が 6 桁以上つながっている。通話の時間 00:05 や時刻は当たらない) */
        fun looksLikeNumber(s: CharSequence?): Boolean {
            if (s.isNullOrEmpty()) return false
            return NUMBER_RE.findAll(s).any { m -> m.value.count { it.isDigit() } >= 6 }
        }
    }
}
