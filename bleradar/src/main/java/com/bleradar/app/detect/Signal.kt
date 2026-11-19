package com.bleradar.app.detect

import kotlin.math.pow
import kotlin.math.roundToInt

/** Rough distance from signal strength. Walls, bodies and phones vary, so it's only a guide. */
enum class Proximity(val label: String) {
    RIGHT_HERE("Right here"),
    VERY_CLOSE("Very close"),
    CLOSE("Close"),
    NEARBY("Nearby"),
    FAR("Far away");

    companion object {
        fun fromRssi(rssi: Double): Proximity = when {
            rssi >= -50 -> RIGHT_HERE
            rssi >= -62 -> VERY_CLOSE
            rssi >= -74 -> CLOSE
            rssi >= -86 -> NEARBY
            else -> FAR
        }

        fun fromRssi(rssi: Int): Proximity = fromRssi(rssi.toDouble())
    }
}

object Signal {
    /** Typical Bluetooth signal strength one metre away, when the device doesn't say. */
    private const val DEFAULT_RSSI_AT_1M = -59.0
    private const val PATH_LOSS_EXPONENT = 2.3

    /**
     * Distance in metres from the log-distance model. Advertised transmit power is the power at
     * the antenna; it's about 41 dB stronger than at one metre.
     */
    fun estimateMeters(rssi: Double, txPower: Int?): Double {
        val atOneMeter = if (txPower != null && txPower in -30..20) txPower - 41.0 else DEFAULT_RSSI_AT_1M
        return 10.0.pow((atOneMeter - rssi) / (10 * PATH_LOSS_EXPONENT)).coerceIn(0.1, 60.0)
    }

    fun formatMeters(m: Double): String = when {
        m < 1 -> "under 1 m"
        m < 10 -> "about ${(m * 2).roundToInt() / 2.0} m".replace(".0 m", " m")
        else -> "about ${m.roundToInt()} m"
    }

    /** 0 = barely heard (-95 dBm), 1 = touching it (-40 dBm). */
    fun closeness(rssi: Double): Float = ((rssi + 95.0) / 55.0).toFloat().coerceIn(0f, 1f)

    /** Gap between beeps: slow ticks far away, a rapid buzz when right on top of it. */
    fun beepIntervalMs(closeness: Float): Long = (1400 - 1320 * closeness.pow(0.8f)).toLong().coerceIn(80, 1400)

    /** Pitch rises as you get closer. */
    fun beepFrequencyHz(closeness: Float): Int = (440 + 1560 * closeness).roundToInt()
}

enum class Trend(val label: String, val arrow: String) {
    WARMER("Getting closer", "▲"),
    COLDER("Moving away", "▼"),
    STEADY("Holding steady", "●"),
}

/**
 * Smooths the jumpy signal strength of one device. Bluetooth readings jump by ±10 dB from
 * moment to moment, so a median of the last [windowMs] of readings is fed into a moving average.
 * Both are time-based: a device heard 10 times a second is smoothed hard, while one heard every
 * 2 seconds (like an AirTag) follows each new reading almost at once instead of lagging behind.
 */
class SignalTracker(
    private val tauMs: Double = 700.0,
    private val windowMs: Long = 1500,
    private val trendWindowMs: Long = 3000,
    private val trendThresholdDb: Double = 2.5,
) {
    private val recent = ArrayDeque<Pair<Long, Int>>()
    private val history = ArrayDeque<Pair<Long, Double>>()

    var smoothed: Double? = null
        private set
    var peak: Double? = null
        private set
    var lastSeenMs: Long = 0
        private set
    var samples: Int = 0
        private set

    fun add(rssi: Int, timeMs: Long) {
        recent.addLast(timeMs to rssi)
        while (recent.size > 1 && (recent.first().first < timeMs - windowMs || recent.size > 9)) recent.removeFirst()
        val sorted = recent.map { it.second }.sorted()
        val median = sorted[sorted.size / 2].toDouble()
        val prev = smoothed
        val s = if (prev == null || samples == 0) {
            median
        } else {
            val dt = (timeMs - lastSeenMs).coerceAtLeast(1).toDouble()
            val alpha = 1 - kotlin.math.exp(-dt / tauMs)
            prev + alpha * (median - prev)
        }
        smoothed = s
        peak = maxOf(peak ?: s, s)
        lastSeenMs = timeMs
        samples++
        history.addLast(timeMs to s)
        while (history.size > 2 && history.first().first < timeMs - trendWindowMs * 3) history.removeFirst()
    }

    /** Readings per second over the last few seconds. */
    fun ratePerSecond(nowMs: Long, overMs: Long = 4000): Double =
        history.count { it.first > nowMs - overMs } * 1000.0 / overMs

    /** Compares now with about [trendWindowMs] ago. */
    fun trend(): Trend {
        val now = history.lastOrNull() ?: return Trend.STEADY
        val then = history.lastOrNull { it.first <= now.first - trendWindowMs } ?: history.first()
        if (then === now) return Trend.STEADY
        val diff = now.second - then.second
        return when {
            diff > trendThresholdDb -> Trend.WARMER
            diff < -trendThresholdDb -> Trend.COLDER
            else -> Trend.STEADY
        }
    }

    fun isLost(nowMs: Long, timeoutMs: Long = 6000): Boolean = samples == 0 || nowMs - lastSeenMs > timeoutMs

    fun resetPeak() {
        peak = smoothed
    }
}
