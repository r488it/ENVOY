package com.rabi.envoymcp.arm

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * スマホの重力 (TYPE_GRAVITY。無ければ加速度)。値は Android のまま (静かに置くと上を向く)。
 * 顔の向きの計算 (FaceGeometry.errors) と、AgentARM に送る grav (符号を反転して下向き) に使う。
 */
class PhonePose(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile var latest: FloatArray? = null
        private set
    /** 最後に測った時刻 (SystemClock.elapsedRealtime の ms) */
    @Volatile var atMs = 0L
        private set

    val available get() = sensor != null

    fun start() {
        sensor?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sm?.unregisterListener(this)
    }

    /** 今の重力が何 ms 前のものか (測っていなければ null) */
    fun ageMs(): Long? = latest?.let { SystemClock.elapsedRealtime() - atMs }

    override fun onSensorChanged(e: SensorEvent) {
        latest = floatArrayOf(e.values[0], e.values[1], e.values[2])
        atMs = SystemClock.elapsedRealtime()
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
}
