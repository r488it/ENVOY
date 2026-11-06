package com.rabi.envoymcp.kids

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.rabi.envoymcp.McpApp
import com.rabi.envoymcp.core.RabiLog
import com.rabi.envoymcp.mcp.ActivityLog
import com.rabi.envoymcp.ui.settings.OwnerCheck

/**
 * 重ねる窓 (KidsOverlay) の「おとなの人」: 本人確認 (指紋・PIN。Activity が要る) だけを出す透明な画面。
 * 確かめられたら、子どもモードを切り、お勉強の窓を消して、ChatGPT の音声の会話を終える (ホームに戻る)。
 * やめた・確かめられなかったときは、お勉強の窓を出し直して閉じる (ChatGPT の音声の会話はそのまま)
 */
class KidsOwnerActivity : ComponentActivity() {
    private val g by lazy { (application as McpApp).graph }
    private val owner = OwnerCheck(this)
    /** 本人確認を、もう出したか (1 回だけ) */
    private var asked = false
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 本人確認の間は、お勉強の窓を隠す (確かめる画面の上に重ならないように)
        g.kidsOverlay.setHidden(true)
    }

    override fun onResume() {
        super.onResume()
        if (asked) return
        asked = true
        owner.ask("子どもモードを切ります", "お勉強の画面を閉じて、ChatGPT の音声の会話も終えます", say = { why ->
            Toast.makeText(this, why, Toast.LENGTH_LONG).show()
            finishWith(false)
        }) { finishWith(true) }
    }

    override fun onDestroy() {
        owner.cancel()
        // 確かめずに閉じた (戻るなど): お勉強の窓を出し直す
        if (!done) g.kidsOverlay.setHidden(false)
        super.onDestroy()
    }

    private fun finishWith(ok: Boolean) {
        if (done) return
        done = true
        if (ok) {
            RabiLog.i(TAG, "parent exit (overlay)")
            g.kids.setKidMode(false, ActivityLog.USER)
            g.kidsOverlay.hide("parent")
            g.closeChatGpt(goHome = true)
        } else g.kidsOverlay.setHidden(false)
        finish()
    }

    companion object {
        private const val TAG = "kids"

        fun open(context: Context) {
            runCatching {
                context.startActivity(Intent(context, KidsOwnerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }.onFailure { RabiLog.w(TAG, "cannot open owner check: ${it.javaClass.simpleName}") }
        }
    }
}
