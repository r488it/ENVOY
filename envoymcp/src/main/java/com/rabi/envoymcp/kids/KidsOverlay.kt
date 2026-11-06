package com.rabi.envoymcp.kids

import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.rabi.envoymcp.McpGraph
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.ui.overlay.OverlayOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * AI の先生のときのお勉強の画面: ほかのアプリの上に重ねる全画面の窓 (TYPE_APPLICATION_OVERLAY)。中身はお勉強の画面と同じ (KidsScreen)。
 * ChatGPT の音声モードを前に出したままにする (お勉強の画面を Activity で前に出すと ChatGPT が裏になり、
 * 「バックグラウンドでの会話」がオフだと、Android が ChatGPT のマイクを止めるため)。
 * - 出している間は、音声モードの全画面のリゲルと小さなリゲルを出さない (VoiceStage・OperationOverlay と取り合わない)
 * - ChatGPT の音声の会話が終わったら消す (通話のモードが終わった・closeChatGpt・end_voice_chat)。kids_end のおわりの画面は、少し見せてから消す
 * - リゲルのタップで開いたのに、VOICE_START_MS たっても音声の会話が始まらなければ、ひとりでのお勉強の画面にする
 * - 「おとなの人」: 本人確認のための透明な画面 (KidsOwnerActivity) を開く
 * - 起動の速さを調べるため、kids open・overlay shown・voice on・primer sent・first tool を、開いてからの秒で記録に残す (中身は書かない)
 * - 「他のアプリの上に表示」が許可されていなければ出さない (呼ぶ側がお勉強の画面 KidsActivity にする)
 * 窓はメインスレッドだけで触る
 */
class KidsOverlay(private val g: McpGraph) {
    private val wm = g.context.getSystemService(WindowManager::class.java)
    private val am = g.context.getSystemService(AudioManager::class.java)
    private var view: View? = null
    private var owner: OverlayOwner? = null
    private var hideJob: Job? = null
    private var voiceJob: Job? = null

    /** 窓を出しているか */
    @Volatile var showing = false
        private set
    /** 窓を出してから、ChatGPT の音声の会話 (通話のモード) が始まったか */
    @Volatile private var voiceSeen = false
    /** リゲルのタップで開いた時刻 (elapsedRealtime。0 なら測っていない) と、最初の道具・音声を記録に残したか */
    @Volatile private var openAt = 0L
    @Volatile private var toolMarked = false
    @Volatile private var voiceMarked = false

    fun canShow(): Boolean = Settings.canDrawOverlays(g.context)

    /** 窓を出す (もう出ていれば、そのまま。消す予定があれば、やめる)。出せなければ false */
    fun show(): Boolean {
        hideJob?.cancel()
        hideJob = null
        view?.let { it.visibility = View.VISIBLE; return true }
        if (!canShow()) { RabiLog.w(TAG, "overlay not allowed"); return false }
        val m = wm.currentWindowMetrics
        val bars = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val d = g.context.resources.displayMetrics.density
        val pad = PaddingValues(start = (bars.left / d).dp, top = (bars.top / d).dp, end = (bars.right / d).dp, bottom = (bars.bottom / d).dp)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "RabiKids"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(g.context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { KidsScreen(g, speak = null, onParent = { KidsOwnerActivity.open(g.context) }, insets = pad) }
        }
        val added = runCatching { wm.addView(v, lp) }.onFailure { RabiLog.w(TAG, "overlay failed ${it.javaClass.simpleName}") }.isSuccess
        if (!added) { o.stop(); return false }
        view = v
        owner = o
        showing = true
        voiceSeen = am.mode == AudioManager.MODE_IN_COMMUNICATION
        // 音声モードの全画面のリゲル・小さなリゲルは出さない (この窓の上に重なって、押せなくなるため)
        g.overlay.setKids(true)
        g.voiceStage.yieldToApp()
        g.kids.shown()
        mark("overlay shown")
        return true
    }

    /** 窓を消す (why は記録に残す理由) */
    fun hide(why: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { g.scope.launch(Dispatchers.Main) { hide(why) }; return }
        hideJob?.cancel(); hideJob = null
        voiceJob?.cancel(); voiceJob = null
        val v = view ?: return
        view = null
        showing = false
        runCatching { wm.removeView(v) }
        owner?.stop(); owner = null
        g.kids.hidden()
        g.overlay.setKids(false)
        RabiLog.i(TAG, "overlay hidden ($why)")
    }

    /** おわりの画面を少し見せてから消す (kids_end のあと) */
    fun hideAfter(ms: Long = END_SHOW_MS) {
        if (!showing) return
        hideJob?.cancel()
        hideJob = g.scope.launch(Dispatchers.Main) { delay(ms); hide("end") }
    }

    /** 本人確認の間だけ隠す (確かめられなければ出し直す) */
    fun setHidden(hidden: Boolean) {
        view?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    /** 通話のモードが変わった (VoiceStage から)。音声の会話が始まったら覚え、終わったら窓を消す */
    fun onVoiceMode(on: Boolean) {
        if (on) {
            if (showing && !voiceMarked) { voiceMarked = true; mark("voice on") }
            voiceSeen = true
            return
        }
        if (showing && voiceSeen) hide("voice ended")
        voiceSeen = false
    }

    /** リゲルのタップで ChatGPT の音声モードを開いた: VOICE_START_MS のうちに音声の会話が始まらなければ、ひとりでのお勉強にする */
    fun expectVoice() {
        voiceJob?.cancel()
        voiceJob = g.scope.launch(Dispatchers.Main) {
            delay(VOICE_START_MS)
            voiceJob = null
            if (!showing || voiceSeen) return@launch
            RabiLog.w(TAG, "voice did not start: alone")
            hide("no voice")
            g.kids.openAlone()
        }
    }

    // ---------------------------------------------------------------- 起動の速さ (記録だけ)

    /** リゲルのタップでお勉強を開いた (ここから秒を数える) */
    fun markOpen() {
        openAt = SystemClock.elapsedRealtime()
        toolMarked = false
        voiceMarked = false
        RabiLog.i(TAG, "kids open")
    }

    /** まなびの道具が呼ばれた (開いてから最初の 1 回だけ記録に残す) */
    fun markTool(name: String) {
        if (toolMarked) return
        toolMarked = true
        mark("first tool $name")
    }

    /** 開いてからの秒を付けて記録に残す (中身は書かない。4 桁以上の数は記録で伏せられるので、秒で書く) */
    fun mark(what: String) {
        val at = openAt
        RabiLog.i(TAG, if (at == 0L) what else "$what (+${String.format(Locale.ROOT, "%.1f", (SystemClock.elapsedRealtime() - at) / 1000.0)} s)")
    }

    companion object {
        private const val TAG = "kids"
        /** kids_end のおわりの画面を見せる長さ */
        const val END_SHOW_MS = 6_000L
        /** リゲルのタップで開いてから、音声の会話が始まるのを待つ長さ */
        const val VOICE_START_MS = 25_000L
    }
}
