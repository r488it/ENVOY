package com.rabi.envoymcp.kids

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rabi.envoymcp.HomeActivity
import com.rabi.envoymcp.McpApp
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.mcp.ActivityLog
import com.rabi.envoymcp.ui.settings.OwnerCheck

/**
 * お勉強の画面 (子どもモード)。上にリゲル、まん中に大きな問題、下に大きな選ぶボタン (2〜4 こ)。
 * - ひとりで: 題を選ぶ (かず・ひらがな・とけい) → 5 もん → 星と「きょうも がんばったね」。リゲルが読み上げる
 * - AI の先生のときは、ふつうは重ねる窓 (KidsOverlay) で出す (ChatGPT を前にしたまま、マイクを止めないため)。
 *   「他のアプリの上に表示」が許可されていないときだけ、この画面で出す (読み上げない。話すのは AI)
 * - 中身は KidsScreen (重ねる窓と同じ)
 * - 時間になったら「きょうは ここまで」
 * - ひとりでお勉強するときは、Android のアプリの固定 (startLockTask) で画面を固定する (おうちの人が設定でオンにしているとき)。
 *   端末の管理者のアプリではないので、Android が「アプリを固定しますか？」と聞く。AI の先生のときは、AI のアプリの声を聞くので固定しない
 * - 左上の「おとなの人」: ご本人か確かめて (指紋など)、子どもモードを切り、固定をやめてホームに戻る
 */
class KidsActivity : ComponentActivity() {
    private val g by lazy { (application as McpApp).graph }
    private val owner = OwnerCheck(this)
    /** この画面で、アプリの固定をもう頼んだか (Android の問いかけは 1 回だけ) */
    private var pinAsked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = back() })
        // 読み上げは、子どもモードに入ったときに用意してあるもの (McpGraph.kidsVoice) を使う
        setContent { KidsScreen(g, speak = { g.kidsVoice().say(it) }, onParent = { parentExit() }) }
    }

    override fun onResume() {
        super.onResume()
        g.kids.shown()
        // 音声モードの全画面のリゲルが出ていたら消す (この画面の上に重なるため)
        g.voiceStage.yieldToApp()
        pin()
    }

    override fun onPause() {
        g.kids.hidden()
        super.onPause()
    }

    override fun onStop() {
        g.stopKidsVoice()
        super.onStop()
    }

    override fun onDestroy() {
        owner.cancel()
        super.onDestroy()
    }

    /** 戻る: 問題・おしまいの画面なら題を選ぶところへ。題を選ぶ・時間になった画面ならホームへ。AI の先生のときは抜けない */
    private fun back() {
        val v = g.kids.view.value
        when {
            v.ai -> {}
            v.mode == KidsLesson.Mode.ASK || v.mode == KidsLesson.Mode.END -> g.kids.pick()
            // 固定している間は、Android がこの画面を閉じさせない
            else -> finish()
        }
    }

    /**
     * ひとりでお勉強するときは、この画面を固定する (おうちの人が設定でオンにしているとき・まだ固定していないとき。1 回だけ頼む)。
     * Android が「アプリを固定しますか？」と聞く。固定を外すのは「おとなの人」か、Android の固定を外す操作
     */
    private fun pin() {
        if (pinAsked || !g.settings.kidsMode || !g.settings.kidsPin || g.kids.view.value.ai) return
        val am = getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) return
        pinAsked = true
        runCatching { startLockTask() }.onFailure { RabiLog.w(TAG, "pin failed: ${it.javaClass.simpleName}") }
    }

    /** 「おとなの人」: ご本人か確かめてから、子どもモードを切り、固定をやめてホームに戻る */
    private fun parentExit() {
        owner.ask("子どもモードを切ります", "お勉強の画面を閉じて、ふだんの ENVOY MCP に戻ります", say = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }) {
            g.kids.setKidMode(false, ActivityLog.USER)
            runCatching { stopLockTask() }
            runCatching {
                startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
            finish()
        }
    }

    companion object {
        private const val TAG = "kids"

        /** お勉強の画面を開く (ホーム・設定・道具から。もう出ていれば、そのまま前に出す) */
        fun open(context: Context) {
            runCatching {
                context.startActivity(Intent(context, KidsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }.onFailure { RabiLog.w(TAG, "cannot open: ${it.javaClass.simpleName}") }
        }
    }
}
