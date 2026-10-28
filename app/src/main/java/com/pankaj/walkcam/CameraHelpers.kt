package com.pankaj.walkcam

import android.os.SystemClock

enum class TorchMode { AUTO, ON, OFF }

/**
 * Decides when to switch the flashlight on, from the ISO the camera reports
 * for every frame. High ISO for a moment = dark scene -> torch on.
 * It only switches off again when the scene is clearly bright (e.g. you walk
 * back into daylight), so it doesn't flicker on/off while walking.
 */
class LowLightTorch(private val onDecision: (Boolean) -> Unit) {

    @Volatile var autoEnabled = true
    @Volatile var isOn = false
        private set

    private var darkSinceNs = 0L
    private var brightSinceNs = 0L

    fun reset() {
        isOn = false
        darkSinceNs = 0L
        brightSinceNs = 0L
    }

    fun onFrame(iso: Int?) {
        if (!autoEnabled || iso == null) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (!isOn) {
            brightSinceNs = 0L
            if (iso >= DARK_ISO) {
                if (darkSinceNs == 0L) darkSinceNs = now
                else if (now - darkSinceNs >= DARK_HOLD_NS) {
                    isOn = true
                    darkSinceNs = 0L
                    onDecision(true)
                }
            } else darkSinceNs = 0L
        } else {
            darkSinceNs = 0L
            if (iso <= BRIGHT_ISO) {
                if (brightSinceNs == 0L) brightSinceNs = now
                else if (now - brightSinceNs >= BRIGHT_HOLD_NS) {
                    isOn = false
                    brightSinceNs = 0L
                    onDecision(false)
                }
            } else brightSinceNs = 0L
        }
    }

    companion object {
        /** Raise to make the torch come on later (darker), lower to come on sooner. */
        const val DARK_ISO = 2000
        const val BRIGHT_ISO = 200
        const val DARK_HOLD_NS = 1_500_000_000L
        const val BRIGHT_HOLD_NS = 4_000_000_000L
    }
}

/** Measures the real frame rate coming off the sensor. */
class FpsMeter {
    private var windowStart = 0L
    private var count = 0
    @Volatile var fps = 0.0
        private set

    fun reset() { windowStart = 0L; count = 0; fps = 0.0 }

    fun onFrame(sensorTimestampNs: Long?) {
        val t = sensorTimestampNs ?: return
        if (windowStart == 0L) { windowStart = t; count = 0; return }
        count++
        val dt = t - windowStart
        if (dt >= 1_000_000_000L) {
            fps = count * 1e9 / dt
            windowStart = t
            count = 0
        }
    }
}
