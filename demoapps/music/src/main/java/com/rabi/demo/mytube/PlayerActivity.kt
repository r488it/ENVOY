package com.rabi.demo.mytube

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 撮影用の架空の音楽アプリ「MyTube Music」。自作の曲「リゲルのダンス」を流すだけ (どこにも通信しない)。
 * - 再生中の曲を MediaSession で知らせる (曲名・アーティスト・長さ・再生か一時停止か)。
 *   ENVOY MCP のリゲルの音楽の画面と腕の踊りは、これと端末の出力の音を見て動く
 * - 曲は端末のスピーカーから出す (腕は端末の出力の音から拍を取る)
 */
class PlayerActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private lateinit var session: MediaSession
    private lateinit var playButton: TextView
    private lateinit var progress: ProgressBar
    private lateinit var timeText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = MediaSession(this, "MyTubeMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = pause()
            })
            isActive = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#120F1C"))
        }
        val header = text("MyTube Music", 22f, "#FFFFFF", bold = true)
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // ジャケットの絵 (紫から水色の色の札に音符)
        val art = text("♪", 96f, "#FFFFFF", bold = true, center = true).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#7B5CFF"), Color.parseColor("#3FC8FF")))
                .apply { cornerRadius = dp(24).toFloat() }
        }
        root.addView(art, LinearLayout.LayoutParams(dp(280), dp(280)).apply { topMargin = dp(40) })
        root.addView(text(TITLE, 26f, "#FFFFFF", bold = true, center = true), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        root.addView(text(ARTIST, 16f, "#A79FC2", center = true), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        root.addView(progress, LinearLayout.LayoutParams(dp(300), WRAP_CONTENT).apply { topMargin = dp(28) })
        timeText = text("0:00", 13f, "#A79FC2", center = true)
        root.addView(timeText)
        playButton = text("▶", 34f, "#120F1C", bold = true, center = true).apply {
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(40).toFloat() }
            contentDescription = "再生"
            isClickable = true
            setOnClickListener { if (player?.isPlaying == true) pause() else play() }
        }
        root.addView(playButton, LinearLayout.LayoutParams(dp(80), dp(80)).apply { topMargin = dp(24) })
        root.addView(text("撮影用の見本のアプリです (自作の曲)", 12f, "#6E6688", center = true), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        // 状態バーとナビゲーションの帯に重ならないように
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            header.setPadding(dp(20), bars.top + dp(14), dp(20), dp(14))
            root.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        setContentView(root)
        publish(PlaybackState.STATE_PAUSED, 0)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        player?.release(); player = null
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 再生

    private fun play() {
        val p = player ?: MediaPlayer.create(this, R.raw.rigel_dance)?.also {
            it.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            it.setOnCompletionListener { done() }
            player = it
        } ?: return
        p.start()
        playButton.text = "❚❚"; playButton.contentDescription = "一時停止"
        publish(PlaybackState.STATE_PLAYING, p.currentPosition.toLong())
        tick()
    }

    private fun pause() {
        val p = player ?: return
        if (p.isPlaying) p.pause()
        playButton.text = "▶"; playButton.contentDescription = "再生"
        publish(PlaybackState.STATE_PAUSED, p.currentPosition.toLong())
    }

    /** 曲が終わった: 最初に戻して止める (もう一度押すと、頭から) */
    private fun done() {
        player?.seekTo(0)
        playButton.text = "▶"; playButton.contentDescription = "再生"
        progress.progress = 0; timeText.text = "0:00"
        publish(PlaybackState.STATE_STOPPED, 0)
    }

    /** 進み具合の表示 */
    private fun tick() {
        main.removeCallbacksAndMessages(null)
        val p = player ?: return
        if (!p.isPlaying) return
        progress.progress = (p.currentPosition * 1000L / p.duration.coerceAtLeast(1)).toInt()
        val s = p.currentPosition / 1000
        timeText.text = "%d:%02d".format(s / 60, s % 60)
        main.postDelayed({ tick() }, 250)
    }

    /** 再生中の曲と様子を MediaSession で知らせる */
    private fun publish(state: Int, position: Long) {
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, TITLE)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, ARTIST)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "AgentPhone")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, (player?.duration ?: DURATION_MS).toLong())
            .build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
            .setState(state, position, if (state == PlaybackState.STATE_PLAYING) 1f else 0f, SystemClock.elapsedRealtime())
            .build())
    }

    // ---------------------------------------------------------------- 部品

    private fun text(s: String, sp: Float, color: String, bold: Boolean = false, center: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (center) gravity = Gravity.CENTER
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TITLE = "リゲルのダンス"
        const val ARTIST = "リゲル"
        const val DURATION_MS = 21_419
    }
}
