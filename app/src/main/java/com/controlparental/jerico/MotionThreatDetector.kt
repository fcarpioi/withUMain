package com.controlparental.jerico

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

class MotionThreatDetector(
    context: Context,
    private val listener: Listener
) : SensorEventListener {

    interface Listener {
        fun onMotionThreatDetected(event: MotionThreatEvent)
    }

    data class MotionThreatEvent(
        val type: String,
        val severity: String,
        val accelerationG: Float,
        val jerkG: Float,
        val timestampMs: Long
    )

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var fallDetectionEnabled = true
    private var snatchDetectionEnabled = true
    private var isRunning = false
    private var lastMagnitudeG = 1f
    private var lastSampleAtMs = 0L
    private var possibleFreeFallAtMs = 0L
    private var lastAlertAtMs = 0L
    private var cooldownMs = DEFAULT_COOLDOWN_MS

    fun configure(
        fallEnabled: Boolean,
        snatchEnabled: Boolean,
        alertCooldownSeconds: Long
    ) {
        fallDetectionEnabled = fallEnabled
        snatchDetectionEnabled = snatchEnabled
        cooldownMs = alertCooldownSeconds.coerceAtLeast(MIN_COOLDOWN_SECONDS) * 1000L

        if (!fallDetectionEnabled && !snatchDetectionEnabled) {
            stop()
        } else if (!isRunning) {
            start()
        }
    }

    fun start() {
        if (isRunning) return
        if (accelerometer == null) {
            Log.w(TAG, "Accelerometer unavailable; motion threat detector disabled")
            return
        }
        sensorManager.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        isRunning = true
        Log.d(TAG, "Motion threat detector started")
    }

    fun stop() {
        if (!isRunning) return
        sensorManager.unregisterListener(this)
        isRunning = false
        possibleFreeFallAtMs = 0L
        Log.d(TAG, "Motion threat detector stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val magnitudeG = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
        ) / SensorManager.GRAVITY_EARTH

        val elapsedSeconds = ((nowElapsedMs - lastSampleAtMs).coerceAtLeast(1L)) / 1000f
        val jerkG = abs(magnitudeG - lastMagnitudeG) / elapsedSeconds
        lastSampleAtMs = nowElapsedMs
        lastMagnitudeG = magnitudeG

        if (isInCooldown(nowElapsedMs)) return

        if (fallDetectionEnabled && magnitudeG < FREE_FALL_G_THRESHOLD) {
            possibleFreeFallAtMs = nowElapsedMs
            return
        }

        if (fallDetectionEnabled && possibleFreeFallAtMs > 0L) {
            val impactWindowMs = nowElapsedMs - possibleFreeFallAtMs
            if (impactWindowMs <= FALL_IMPACT_WINDOW_MS && magnitudeG >= FALL_IMPACT_G_THRESHOLD) {
                dispatchThreat("fall_detected", "high", magnitudeG, jerkG, nowElapsedMs)
                possibleFreeFallAtMs = 0L
                return
            }
            if (impactWindowMs > FALL_IMPACT_WINDOW_MS) {
                possibleFreeFallAtMs = 0L
            }
        }

        if (snatchDetectionEnabled &&
            magnitudeG >= SNATCH_ACCELERATION_G_THRESHOLD &&
            jerkG >= SNATCH_JERK_G_THRESHOLD
        ) {
            dispatchThreat("possible_snatch", "high", magnitudeG, jerkG, nowElapsedMs)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun isInCooldown(nowElapsedMs: Long): Boolean {
        return nowElapsedMs - lastAlertAtMs < cooldownMs
    }

    private fun dispatchThreat(
        type: String,
        severity: String,
        accelerationG: Float,
        jerkG: Float,
        nowElapsedMs: Long
    ) {
        lastAlertAtMs = nowElapsedMs
        listener.onMotionThreatDetected(
            MotionThreatEvent(
                type = type,
                severity = severity,
                accelerationG = accelerationG,
                jerkG = jerkG,
                timestampMs = System.currentTimeMillis()
            )
        )
    }

    companion object {
        private const val TAG = "MotionThreatDetector"
        private const val FREE_FALL_G_THRESHOLD = 0.55f
        private const val FALL_IMPACT_G_THRESHOLD = 2.8f
        private const val FALL_IMPACT_WINDOW_MS = 1_500L
        private const val SNATCH_ACCELERATION_G_THRESHOLD = 3.4f
        private const val SNATCH_JERK_G_THRESHOLD = 22f
        private const val DEFAULT_COOLDOWN_MS = 60_000L
        private const val MIN_COOLDOWN_SECONDS = 15L
    }
}
