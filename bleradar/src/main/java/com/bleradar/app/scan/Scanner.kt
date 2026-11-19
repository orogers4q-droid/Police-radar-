package com.bleradar.app.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.bleradar.app.Prefs
import com.bleradar.app.detect.Classification
import com.bleradar.app.detect.Classifier
import com.bleradar.app.detect.Group
import com.bleradar.app.detect.Sighting
import com.bleradar.app.detect.Source
import com.bleradar.app.detect.Threat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A device in range: its latest sighting and the most suspicious thing it has looked like. */
data class Tracked(
    val latest: Sighting,
    val classification: Classification,
    val firstSeenMs: Long,
    val sightings: Int,
) {
    val key: String get() = latest.key
}

/**
 * Scans Bluetooth Low Energy continuously, and Wi-Fi every 30 seconds for the hotspots that
 * hidden cameras create. Runs while the app is open (the screen is kept on).
 */
object Scanner {
    private const val TAG = "Scanner"
    private const val PUBLISH_MS = 500L
    private const val WIFI_INTERVAL_MS = 30_000L
    /** While tracking a camera hotspot. Only works fully with Wi-Fi scan throttling turned off. */
    private const val WIFI_FAST_INTERVAL_MS = 5_000L
    private const val BLE_RESTART_MS = 10 * 60_000L
    private const val BLE_TIMEOUT_MS = 2 * 60_000L
    private const val WIFI_TIMEOUT_MS = 150_000L
    private const val ALERT_GAP_MS = 8_000L

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var bleScanner: BluetoothLeScanner? = null
    private var wifi: WifiManager? = null
    private var receiverRegistered = false

    /** Working copy, touched only on the main thread and published every [PUBLISH_MS]. */
    private val working = HashMap<String, Tracked>()
    private var dirty = false
    private val alerted = HashSet<String>()
    private var lastAlertMs = 0L

    private val _devices = MutableStateFlow<Map<String, Tracked>>(emptyMap())
    val devices: StateFlow<Map<String, Tracked>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** True when Android refused a Wi-Fi scan because of its 4-scans-per-2-minutes limit. */
    private val _wifiThrottled = MutableStateFlow(false)
    val wifiThrottled: StateFlow<Boolean> = _wifiThrottled.asStateFlow()

    private var fastWifi = false
    private val lastWifiStamp = HashMap<String, Long>()

    /** Scan Wi-Fi as often as Android allows, for tracking down a camera hotspot. */
    fun setFastWifi(on: Boolean) {
        if (fastWifi == on) return
        fastWifi = on
        if (_scanning.value) {
            handler.removeCallbacks(wifiTick)
            handler.post(wifiTick)
        }
    }

    /** Every raw sighting, unthrottled, for tracking one device down. */
    private val _sightings = MutableSharedFlow<Sighting>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val sightings: SharedFlow<Sighting> = _sightings.asSharedFlow()

    fun start(context: Context) {
        if (_scanning.value) return
        app = context.applicationContext
        _problem.value = null
        _scanning.value = true
        startBle()
        startWifi()
        handler.postDelayed(publishTick, PUBLISH_MS)
        handler.postDelayed(bleRestart, BLE_RESTART_MS)
    }

    fun stop() {
        if (!_scanning.value) return
        _scanning.value = false
        fastWifi = false
        _wifiThrottled.value = false
        handler.removeCallbacks(publishTick)
        handler.removeCallbacks(bleRestart)
        handler.removeCallbacks(wifiTick)
        stopBle()
        if (receiverRegistered) app.unregisterReceiver(wifiReceiver)
        receiverRegistered = false
        working.clear()
        lastWifiStamp.clear()
        alerted.clear()
        _devices.value = emptyMap()
    }

    // ---------- Bluetooth ----------

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { onBle(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            handler.post { results.forEach(::onBle) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            _problem.value = "Bluetooth scan failed (code $errorCode). Stop and start again, or toggle Bluetooth."
        }
    }

    private fun onBle(r: ScanResult) {
        if (!_scanning.value) return
        val record = r.scanRecord
        val maker = record?.manufacturerSpecificData
        val makerMap = if (maker == null) emptyMap() else (0 until maker.size()).associate { maker.keyAt(it) to maker.valueAt(it) }
        val uuids = record?.serviceUuids.orEmpty().mapNotNull(::shortUuid).toSet()
        val serviceData = record?.serviceData.orEmpty().entries
            .mapNotNull { (k, v) -> shortUuid(k)?.let { it to v } }.toMap()
        val tx = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }
        handle(
            Sighting(
                source = Source.BLE,
                address = r.device.address,
                name = record?.deviceName?.takeIf { it.isNotBlank() },
                rssi = r.rssi,
                timeMs = System.currentTimeMillis(),
                manufacturerData = makerMap,
                serviceUuids = uuids,
                serviceData = serviceData,
                txPower = tx,
            ),
        )
    }

    /** "0000FEED-0000-1000-8000-00805F9B34FB" to 0xFEED; null for full 128-bit UUIDs. */
    private fun shortUuid(p: ParcelUuid): Int? {
        val u = p.uuid
        if (u.leastSignificantBits != -0x7fffff7fa064cb05L) return null // 8000-00805F9B34FB
        val msb = u.mostSignificantBits
        if (msb and 0xFFFFFFFFL != 0x1000L) return null // -0000-1000
        return ((msb ushr 32) and 0xFFFF).toInt()
    }

    @SuppressLint("MissingPermission")
    private fun startBle() {
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _problem.value = "Bluetooth is off. Turn it on to find trackers and Bluetooth cameras."
            return
        }
        bleScanner = adapter.bluetoothLeScanner
        val builder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
        // Also hear newer "extended" and long-range broadcasts, where the phone supports them.
        if (adapter.isLeExtendedAdvertisingSupported) {
            builder.setLegacy(false)
            builder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        val settings = builder.build()
        try {
            bleScanner?.startScan(null, settings, bleCallback)
        } catch (e: SecurityException) {
            _problem.value = "The Nearby devices permission is needed to scan Bluetooth."
        } catch (e: IllegalStateException) {
            _problem.value = "Bluetooth is off. Turn it on to find trackers and Bluetooth cameras."
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBle() {
        try {
            bleScanner?.stopScan(bleCallback)
        } catch (e: Exception) {
            // Adapter turned off or permission revoked; nothing to stop.
        }
        bleScanner = null
    }

    /** Android quietly weakens scans running longer than 30 minutes, so restart regularly. */
    private val bleRestart = object : Runnable {
        override fun run() {
            stopBle()
            startBle()
            handler.postDelayed(this, BLE_RESTART_MS)
        }
    }

    // ---------- Wi-Fi (camera hotspots only) ----------

    private fun startWifi() {
        wifi = app.getSystemService(WifiManager::class.java)
        ContextCompat.registerReceiver(
            app, wifiReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        handler.post(wifiTick)
    }

    /** Android allows about 4 Wi-Fi scans per 2 minutes. */
    private val wifiTick = object : Runnable {
        override fun run() {
            try {
                @Suppress("DEPRECATION")
                val accepted = wifi?.startScan() ?: true
                _wifiThrottled.value = !accepted
            } catch (e: SecurityException) {
                Log.w(TAG, "Wi-Fi scan not allowed", e)
            }
            readWifi()
            handler.postDelayed(this, if (fastWifi) WIFI_FAST_INTERVAL_MS else WIFI_INTERVAL_MS)
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = readWifi()
    }

    @SuppressLint("MissingPermission")
    private fun readWifi() {
        if (!_scanning.value) return
        val results = try {
            wifi?.scanResults
        } catch (e: SecurityException) {
            null
        } ?: return
        val nowMs = System.currentTimeMillis()
        val nowMicros = SystemClock.elapsedRealtime() * 1000
        for (r in results) {
            if (nowMicros - r.timestamp > 60_000_000L) continue // cached from an old scan
            val bssid = r.BSSID ?: continue
            @Suppress("DEPRECATION")
            val ssid = r.SSID?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: continue
            // The same cached result comes back until a new scan finishes; count each reading once.
            if (lastWifiStamp.put(bssid, r.timestamp) == r.timestamp) continue
            val heardMs = nowMs - (nowMicros - r.timestamp) / 1000
            val s = Sighting(Source.WIFI, bssid, ssid, r.level, heardMs)
            // Only camera hotspots: this is a Bluetooth radar, not a Wi-Fi list.
            if (Classifier.classify(s).kind.group == Group.CAMERA) handle(s)
        }
    }

    // ---------- Bookkeeping ----------

    private fun handle(s: Sighting) {
        _sightings.tryEmit(s)
        val c = Classifier.classify(s)
        val prev = working[s.key]
        // A device can send several kinds of advertisement; remember the most suspicious.
        val keep = if (prev != null && prev.classification.kind.threat.ordinal <= c.kind.threat.ordinal) prev.classification else c
        val t = Tracked(s, keep, prev?.firstSeenMs ?: s.timeMs, (prev?.sightings ?: 0) + 1)
        working[s.key] = t
        dirty = true
        maybeAlert(t)
    }

    private fun maybeAlert(t: Tracked) {
        val threat = t.classification.kind.threat
        if (threat != Threat.HIGH && threat != Threat.MEDIUM) return
        if (!alerted.add(t.key)) return
        val prefs = Prefs.get(app).value
        if (!prefs.alerts) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAlertMs < ALERT_GAP_MS) return
        lastAlertMs = now
        HuntFeedback.chime(app, prefs.sound, prefs.vibrate)
    }

    private val publishTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val removed = working.entries.removeAll { (_, t) ->
                val timeout = if (t.latest.source == Source.WIFI) WIFI_TIMEOUT_MS else BLE_TIMEOUT_MS
                now - t.latest.timeMs > timeout
            }
            if (dirty || removed) _devices.value = HashMap(working)
            dirty = false
            handler.postDelayed(this, PUBLISH_MS)
        }
    }
}
