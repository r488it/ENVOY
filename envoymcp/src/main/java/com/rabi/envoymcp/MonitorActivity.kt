package com.rabi.envoymcp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.OutcomeReceiver
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rabi.launcher.core.RabiLog

/**
 * モニター (PC モード) の全画面のリゲル (モニターにつないだら、全画面のリゲルがいつも居る)。
 * モニターがつながったら MonitorStage が開く。ホームと同じリゲルで、リゲルを押すとリゲルのタップと同じく ChatGPT の音声モードを開く。
 * 頼まれたアプリ (YouTube など) は、この上に窓として開く。モニターが外れてスマホの画面に移ったら閉じる
 */
class MonitorActivity : ComponentActivity() {
    private val g by lazy { (application as McpApp).graph }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (onPhone()) { finish(); return }
        g.monitorStage.activity = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { HomeStage(g, onTap = { g.tapRigel("monitor") }, onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }, big = true) }
    }

    override fun onStart() {
        super.onStart()
        // モニターが外れて、スマホの画面に移ってきた: 閉じる (スマホではふだんのホームを使う)
        if (onPhone()) finish()
    }

    override fun onResume() {
        super.onResume()
        fullScreen()
    }

    /**
     * 常に全画面にする: 上下の帯を隠し、自由な大きさの窓なら Android に全画面にしてほしいと頼む
     * (頼めない・断られたときは、モニターいっぱいの大きさの窓のまま)
     */
    private fun fullScreen() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && isInMultiWindowMode) {
            runCatching {
                requestFullscreenMode(FULLSCREEN_MODE_REQUEST_ENTER, object : OutcomeReceiver<Void, Throwable> {
                    override fun onResult(r: Void?) { RabiLog.i("monitor", "fullscreen ok") }
                    override fun onError(e: Throwable) { RabiLog.i("monitor", "fullscreen refused: ${e.javaClass.simpleName}") }
                })
            }.onFailure { RabiLog.i("monitor", "fullscreen request failed: ${it.javaClass.simpleName}") }
        }
    }

    private fun onPhone() = (display?.displayId ?: Display.DEFAULT_DISPLAY) == Display.DEFAULT_DISPLAY

    override fun onDestroy() {
        if (g.monitorStage.activity === this) g.monitorStage.activity = null
        super.onDestroy()
    }
}
