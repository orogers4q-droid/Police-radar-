package com.policeradar.app.data

import com.policeradar.app.detect.Observation
import com.policeradar.app.detect.Signature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A device currently in range, with the signature it matched (if any). */
data class SeenDevice(
    val latest: Observation,
    val signature: Signature?,
    val firstSeenMs: Long,
)

/** In-memory state shared between the scanner service and the screen. */
object DetectionRepository {
    private val _devices = MutableStateFlow<Map<String, SeenDevice>>(emptyMap())
    val devices: StateFlow<Map<String, SeenDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Problem the scanner ran into (e.g. Bluetooth off), shown on screen. */
    private val _scannerProblem = MutableStateFlow<String?>(null)
    val scannerProblem: StateFlow<String?> = _scannerProblem.asStateFlow()

    fun record(obs: Observation, signature: Signature?) {
        _devices.update { map ->
            val prev = map[obs.key]
            map + (obs.key to SeenDevice(obs, signature, prev?.firstSeenMs ?: obs.timeMs))
        }
    }

    /** Re-evaluate every device in range, e.g. after signatures change. */
    fun rematch(matcher: (Observation) -> Signature?) {
        _devices.update { map -> map.mapValues { (_, d) -> d.copy(signature = matcher(d.latest)) } }
    }

    /** Forget ordinary devices (used when learning mode is switched off). */
    fun dropUnmatched() {
        _devices.update { map -> map.filterValues { it.signature != null } }
    }

    fun pruneOlderThan(cutoffMs: Long) {
        _devices.update { map -> map.filterValues { it.latest.timeMs >= cutoffMs } }
    }

    fun setScanning(value: Boolean) {
        _scanning.value = value
        if (!value) _devices.value = emptyMap()
    }

    fun setProblem(message: String?) {
        _scannerProblem.value = message
    }
}
