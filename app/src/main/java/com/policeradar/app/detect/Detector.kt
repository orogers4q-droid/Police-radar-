package com.policeradar.app.detect

/** One sighting of a radio device during a scan. */
data class Observation(
    val source: SignalSource,
    val address: String,
    val name: String?,
    val rssi: Int,
    val timeMs: Long,
) {
    val key: String get() = "${source.name}:${Detector.normalizeMac(address)}"
}

/** Pure matching logic, independent of Android so it can be unit tested. */
object Detector {

    fun normalizeMac(value: String): String = value.trim().uppercase().replace('-', ':')

    /** First three bytes of a MAC address ("AA:BB:CC"), which identify the manufacturer. */
    fun manufacturerPrefix(address: String): String = normalizeMac(address).take(8)

    /**
     * True when the address is randomly generated (the "locally administered" bit is set).
     * Random addresses don't reveal the manufacturer, so prefix matching can't work on them.
     */
    fun isRandomAddress(address: String): Boolean {
        val firstByte = normalizeMac(address).take(2).toIntOrNull(16) ?: return false
        return firstByte and 0x02 != 0
    }

    fun matches(signature: Signature, obs: Observation): Boolean {
        if (!signature.enabled) return false
        return when (signature.matchType) {
            MatchType.MAC_PREFIX -> {
                val mac = normalizeMac(obs.address)
                signature.patterns.any { p ->
                    val prefix = normalizeMac(p)
                    prefix.isNotEmpty() && mac.startsWith(prefix)
                }
            }
            MatchType.WIFI_NAME ->
                obs.source == SignalSource.WIFI && nameMatches(obs.name, signature.patterns)
            MatchType.BLE_NAME ->
                obs.source == SignalSource.BLUETOOTH && nameMatches(obs.name, signature.patterns)
        }
    }

    /** The strongest-confidence signature matching [obs], or null if none match. */
    fun match(signatures: List<Signature>, obs: Observation): Signature? =
        signatures.filter { matches(it, obs) }.minByOrNull { it.confidence.ordinal }

    private fun nameMatches(name: String?, patterns: List<String>): Boolean {
        if (name.isNullOrBlank()) return false
        return patterns.any { it.isNotBlank() && name.contains(it.trim(), ignoreCase = true) }
    }
}

/** Rough distance from signal strength. Walls, cars and phones vary, so it's only a guide. */
enum class Proximity(val label: String) {
    VERY_CLOSE("Very close"),
    CLOSE("Close"),
    NEARBY("Nearby"),
    FAR("Far away");

    companion object {
        fun fromRssi(rssi: Int): Proximity = when {
            rssi >= -60 -> VERY_CLOSE
            rssi >= -72 -> CLOSE
            rssi >= -85 -> NEARBY
            else -> FAR
        }
    }
}

/**
 * Decides whether a detection should ping the user.
 *
 * - Each device alerts at most once per [cooldownMs].
 * - After any alert, other devices are silenced for [groupGapMs] and counted as alerted,
 *   so one patrol car carrying a router and two body cameras pings once, not three times.
 */
class AlertGate(
    private val cooldownMs: () -> Long,
    private val groupGapMs: Long = 20_000,
) {
    private val lastAlertByKey = HashMap<String, Long>()
    private var lastAlertAny: Long? = null

    fun shouldAlert(key: String, nowMs: Long): Boolean {
        val prev = lastAlertByKey[key]
        if (prev != null && nowMs - prev < cooldownMs()) return false
        lastAlertByKey[key] = nowMs
        val lastAny = lastAlertAny
        if (lastAny != null && nowMs - lastAny < groupGapMs) return false
        lastAlertAny = nowMs
        return true
    }

    fun reset() {
        lastAlertByKey.clear()
        lastAlertAny = null
    }
}
