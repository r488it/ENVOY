package com.rabi.envoymcp.arm

import android.content.Context
import android.media.AudioManager
import com.rabi.envoymcp.McpSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 呼び名を聞いてよいかを腕に知らせる (obs voice): 充電器に付いていて、ChatGPT の音声モード・
 * 通話の最中でない間だけ、腕のボードは呼び名の聞き取りを動かす。話しているかは通話のモードで見る
 * (音声モード・LINE の通話は MODE_IN_COMMUNICATION、電話は MODE_IN_CALL、かかってきた電話の呼び出しは MODE_RINGTONE)。
 * version は腕のプログラムの版 (hello の v)、docked はスマホが充電器 (腕) に付いているか
 */
internal class ArmVoiceGate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val link: ArmLink,
    private val settings: McpSettings,
    private val version: () -> Int,
    private val docked: () -> Boolean,
) {
    private var talking = false
    private var job: Job? = null
    private val modeListener = AudioManager.OnModeChangedListener { m -> setTalking(m != AudioManager.MODE_NORMAL) }

    /** 通話のモードを見始める */
    fun listen() {
        context.getSystemService(AudioManager::class.java)?.let { am ->
            am.addOnModeChangedListener(context.mainExecutor, modeListener)
            talking = am.mode != AudioManager.MODE_NORMAL
        }
    }

    /** 通話のモードを見るのをやめる */
    fun unlisten() {
        runCatching { context.getSystemService(AudioManager::class.java)?.removeOnModeChangedListener(modeListener) }
    }

    private fun setTalking(t: Boolean) {
        if (t == talking) return
        talking = t
        send()
    }

    /** 腕に今の状態を送る (変わったとき・hello のあと。BLE の観測は詰まると捨てられるので、ふだんも数秒ごとに送り直す) */
    fun send() {
        if (!link.ready || version() < 6) return
        link.sendObs(JSONObject().put("t", "obs").put("k", "voice").put("dock", docked()).put("talk", talking)
            .put("wth", r2(settings.wakeThreshold.toDouble())))
    }

    /** 数秒ごとに送り直し始める (腕とつながったとき) */
    fun startRepeating() {
        job?.cancel()
        job = scope.launch { while (isActive) { send(); delay(EVERY_MS) } }
    }

    fun stopRepeating() { job?.cancel(); job = null }

    private fun r2(x: Double) = (x * 100).roundToInt() / 100.0

    companion object {
        /** 呼び名を聞いてよいかを、腕に送り直す間 (ms) */
        private const val EVERY_MS = 5_000L
    }
}
