package com.rabi.envoymcp.music

import org.junit.Assert.assertTrue
import org.junit.Test

/** 歌詞の文字の大きさ: 長い語・長い行も、舞台 (幅・高さ) からはみ出さない */
class LyricsFitTest {
    private val ref = 48f
    private val avail = 1000f
    private val min = 22f; private val max = 72f; private val step = 4f

    private fun fits(refW: List<Float>, areaH: Float, latin: Boolean): Float {
        val f = fitFont(refW, ref, avail, areaH, latin, min, max, step)
        val widths = refW.map { it * f / ref }
        val rows = pack(widths, avail * 0.88f, lyricGap(f, latin), perRow(latin))
        for (row in rows) {
            val w = row.sumOf { widths[it].toDouble() } + lyricGap(f, latin) * (row.size - 1)
            assertTrue("幅に入る: $w", w <= avail * 0.88f + 0.5f)
        }
        assertTrue("高さに入る: ${rows.size * f * 1.22f} <= $areaH", rows.size * f * 1.22f <= areaH + 0.5f)
        return f
    }

    @Test fun shortLineIsBig() {
        val f = fits(List(4) { 48f }, 500f, latin = false)
        assertTrue("短い行は大きく: $f", f >= max - 0.01f)
    }

    @Test fun longWordFitsWidth() {
        // 1 語で 48 の大きさのとき 1400 の幅 (画面より長い英語の語)
        fits(listOf(300f, 1400f, 200f), 500f, latin = true)
    }

    @Test fun longLineFitsHeight() {
        // 日本語 60 文字を、低い舞台 (200) に
        fits(List(60) { 48f }, 200f, latin = false)
        fits(List(30) { 120f + it * 3f }, 160f, latin = true)
    }
}
