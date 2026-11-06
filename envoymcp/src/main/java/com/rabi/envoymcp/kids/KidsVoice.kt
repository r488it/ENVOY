package com.rabi.envoymcp.kids

import android.content.Context
import android.speech.tts.TextToSpeech
import com.rabi.envoymcp.core.RabiLog
import java.util.Locale

/**
 * リゲルの読み上げ (Android の日本語の読み上げ TextToSpeech。端末の中だけで、外には何も送らない)。
 * 日本語の読み上げが使えない端末では黙る (画面の字と絵だけで進む)。読み上げる言葉は記録に残さない
 */
class KidsVoice(context: Context) {
    @Volatile private var ready = false
    /** 用意ができる前に頼まれた言葉 (いちばん新しいもの 1 つ) */
    private var waiting: String? = null
    private var seq = 0
    private val tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { RabiLog.w(TAG, "tts init failed ($status)"); return }
        val r = tts.setLanguage(Locale.JAPAN)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) { RabiLog.w(TAG, "no japanese voice ($r)"); return }
        // 小さな子どもに聞き取りやすいよう、少しゆっくり・少し高く
        tts.setSpeechRate(SPEED)
        tts.setPitch(PITCH)
        ready = true
        waiting?.let { waiting = null; say(it) }
    }

    /** 読み上げる (前の言葉は止めて、こちらを読む) */
    fun say(text: String) {
        if (text.isBlank()) return
        if (!ready) { waiting = text; return }
        runCatching { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kids-${++seq}") }
    }

    /** 読み上げを止める (画面が隠れたとき) */
    fun stop() {
        waiting = null
        if (ready) runCatching { tts.stop() }
    }

    /** しまう (画面を閉じるとき) */
    fun shutdown() {
        ready = false
        waiting = null
        runCatching { tts.stop(); tts.shutdown() }
    }

    companion object {
        private const val TAG = "kids"
        private const val SPEED = 0.9f
        private const val PITCH = 1.1f
    }
}
