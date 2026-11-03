package com.rabi.envoymcp.mcp

import com.rabi.launcher.observe.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenVisionTest {
    /** 画像 (570x1280) の位置を、画面 (1080x2424) の位置に直す。前後に文章が付いていても JSON だけを読む */
    @Test fun boxesAreScaledToScreen() {
        val out = """はい。
            {"summary": "自動字幕起こしの設定画面", "targets": [
              {"label": "自動字幕起こしを使用する (オフ)", "kind": "switch", "element_id": 24, "box": [22, 677, 548, 821], "commit": false},
              {"label": "マイク", "kind": "icon", "element_id": null, "box": [500, 100, 560, 160], "commit": false},
              {"label": "壊れた枠", "kind": "icon", "box": [10, 10, 5, 5]}
            ]}
            以上です"""
        val r = parseVision(out, 570, 1280, 1080, 2424)
        assertEquals("自動字幕起こしの設定画面", r.summary)
        assertEquals(2, r.targets.size)   // 壊れた枠は捨てる
        val sw = r.targets[0]
        assertEquals("v1", sw.visualId)
        assertEquals(24, sw.elementId)
        // 22 * 1080/570 = 41.7 → 41、677 * 2424/1280 = 1282.0 → 1282
        assertEquals(Bounds(41, 1282, 1038, 1554), sw.bounds)
        assertNull(r.targets[1].elementId)
        assertTrue(r.targets[1].bounds.r <= 1080)
    }

    @Test(expected = ScreenVision.VisionException::class) fun noJsonIsAnError() {
        parseVision("画面を読めませんでした", 570, 1280, 1080, 2424)
    }
}
