package com.rabi.envoymcp.mcp

import com.rabi.envoymcp.observe.Bounds
import com.rabi.envoymcp.observe.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** つまみ (スライダー) の位置を押す: つまみかどうかと、押す x */
class SliderTapTest {
    private fun node(cls: String) = UiNode(0, "", "明るさ", "", cls, "com.android.systemui", Bounds(100, 500, 900, 600), clickable = true,
        enabled = true, focused = false, scrollable = false, checked = false, password = false, hint = "", depth = 0)

    @Test fun slidersOnly() {
        assertTrue(OperateTools.isSlider(node("android.widget.SeekBar")))
        assertTrue(OperateTools.isSlider(node("com.android.systemui.settings.brightness.ToggleSeekBar")))
        assertTrue(OperateTools.isSlider(node("androidx.compose.material3.Slider")))
        assertFalse(OperateTools.isSlider(node("android.widget.Button")))
        assertFalse(OperateTools.isSlider(node("android.widget.Switch")))
    }

    @Test fun positionMapsInsideTheFrame() {
        val b = Bounds(100, 500, 900, 600)
        // 端は枠の内側 (4px) を押す。1 で右端、0 で左端、0.5 で真ん中
        assertEquals(896, OperateTools.sliderX(b, 1.0))
        assertEquals(104, OperateTools.sliderX(b, 0.0))
        assertEquals(500, OperateTools.sliderX(b, 0.5))
        // 範囲の外は端にそろえる
        assertEquals(896, OperateTools.sliderX(b, 3.0))
        assertEquals(104, OperateTools.sliderX(b, -1.0))
        // とても細い枠でも、枠の中に収まる
        val thin = Bounds(10, 0, 18, 10)
        assertTrue(OperateTools.sliderX(thin, 1.0) in 10..18)
        assertTrue(OperateTools.sliderX(thin, 0.0) in 10..18)
    }
}
