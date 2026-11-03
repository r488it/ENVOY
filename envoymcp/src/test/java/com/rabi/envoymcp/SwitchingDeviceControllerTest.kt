package com.rabi.envoymcp

import com.rabi.launcher.device.ActionResult
import com.rabi.launcher.device.DeviceController
import com.rabi.launcher.observe.Bounds
import com.rabi.launcher.observe.Observation
import com.rabi.launcher.observe.UiNode
import com.rabi.launcher.parts.IntentSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchingDeviceControllerTest {
    /** どちらが呼ばれたかを名前で返す偽物。tap に渡された位置を覚える */
    private open class Fake(val name: String, var connected: Boolean) : DeviceController, CheckedTap {
        var expected: Bounds? = null
        override suspend fun tap(x: Int, y: Int, expect: Bounds?): ActionResult { expected = expect; return ActionResult(true, "$name+check") }
        override val isConnected get() = connected
        override suspend fun observe(screenshot: Boolean) =
            Observation(name, null, listOf(UiNode(0, "ボタン", "", "", "Button", name, Bounds(0, 0, 100, 100), true, true, false, false, false, false, "", 0)), 1080, 2424)
        override suspend fun launchApp(pkg: String) = ActionResult(true, name)
        override suspend fun startIntent(spec: IntentSpec) = ActionResult(true, name)
        override suspend fun tap(x: Int, y: Int) = ActionResult(true, name)
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) = ActionResult(true, name)
        override suspend fun back() = ActionResult(true, name)
        override suspend fun home() = ActionResult(true, name)
        override suspend fun typeText(text: String) = ActionResult(true, name)
        override suspend fun clearFocusedField() = ActionResult(true, name)
        override suspend fun isInstalled(pkg: String) = true
    }

    private val adb = Fake("adb", connected = true)
    private val a11y = Fake("a11y", connected = false)
    private var mode = ControlMode.ADB
    private val sw = SwitchingDeviceController({ mode }, adb, a11y)

    @Test fun adbOnlyAlwaysUsesAdb() = runTest {
        a11y.connected = true
        assertEquals("adb", sw.tap(1, 1).detail)
        assertFalse(sw.usingA11y())
    }

    @Test fun a11yOnlyAlwaysUsesA11y() = runTest {
        mode = ControlMode.A11Y
        assertEquals("a11y", sw.observe(false).pkg)
        // ユーザー補助がオフなら、つながっていない (ADB に逃げない)
        assertFalse(sw.isConnected)
        a11y.connected = true
        assertTrue(sw.isConnected)
    }

    @Test fun hybridPrefersA11yAndFallsBackToAdb() = runTest {
        mode = ControlMode.HYBRID
        assertEquals("adb", sw.back().detail)   // ユーザー補助がオフ
        a11y.connected = true
        assertEquals("a11y", sw.back().detail)  // オンになったら、すぐユーザー補助
        assertTrue(sw.usingA11y())
    }

    /** HYBRID: 画面は ADB で読み、ユーザー補助で押すときは、ADB で読んだその場所の部品の位置を渡す */
    @Test fun hybridReadsWithAdbAndTapsWithCheckedPosition() = runTest {
        mode = ControlMode.HYBRID
        a11y.connected = true
        assertEquals("adb", sw.observe(false).pkg)   // 読み取りは ADB
        assertEquals("a11y+check", sw.tap(50, 50).detail)
        assertEquals(Bounds(0, 0, 100, 100), a11y.expected)
        // 読んだ画面にその場所の部品が無い: どれとも一致しない位置を渡す (部品クリックをさせない)
        sw.tap(500, 500)
        assertEquals(Bounds(-1, -1, -1, -1), a11y.expected)
        // ADB が切れたら、読み取りもユーザー補助
        adb.connected = false
        assertEquals("a11y", sw.observe(false).pkg)
    }
}
