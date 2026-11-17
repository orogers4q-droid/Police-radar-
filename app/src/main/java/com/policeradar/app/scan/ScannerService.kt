package com.policeradar.app.scan

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.policeradar.app.data.DetectionRepository
import com.policeradar.app.data.SettingsStore
import com.policeradar.app.detect.AlertGate
import com.policeradar.app.detect.Detector
import com.policeradar.app.detect.Observation
import com.policeradar.app.detect.SignalSource
import android.bluetooth.le.ScanResult as BleScanResult

/**
 * Foreground service that keeps scanning Wi-Fi and Bluetooth Low Energy while the app
 * is in the background, matches what it hears against the signatures, and alerts.
 */
class ScannerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: SettingsStore
    private lateinit var notifier: AlertNotifier
    private lateinit var gate: AlertGate
    private var wifi: WifiManager? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var receiverRegistered = false
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore.get(this)
        notifier = AlertNotifier(this)
        gate = AlertGate(cooldownMs = { store.settings.value.cooldownMinutes * 60_000L })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notifier.scanningNotification(), type)
        } catch (e: Exception) {
            // Usually means the location permission is missing.
            Log.w(TAG, "Could not start in foreground", e)
            DetectionRepository.setProblem("Location permission is needed to scan.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) startScanning()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopBle()
        if (receiverRegistered) unregisterReceiver(wifiReceiver)
        receiverRegistered = false
        notifier.shutdown()
        DetectionRepository.setScanning(false)
        super.onDestroy()
    }

    private fun startScanning() {
        running = true
        gate.reset()
        DetectionRepository.setProblem(null)
        DetectionRepository.setScanning(true)

        wifi = applicationContext.getSystemService(WifiManager::class.java)
        ContextCompat.registerReceiver(
            this, wifiReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true

        startBle()
        handler.post(wifiTick)
        handler.postDelayed(pruneTick, PRUNE_INTERVAL_MS)
        handler.postDelayed(bleRestart, BLE_RESTART_MS)
    }

    // ---------- Wi-Fi ----------

    /** Android allows about 4 Wi-Fi scans per 2 minutes, so ask every 30 seconds. */
    private val wifiTick = object : Runnable {
        override fun run() {
            try {
                @Suppress("DEPRECATION")
                wifi?.startScan()
            } catch (e: SecurityException) {
                Log.w(TAG, "Wi-Fi scan not allowed", e)
            }
            readWifiResults()
            handler.postDelayed(this, WIFI_INTERVAL_MS)
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = readWifiResults()
    }

    @SuppressLint("MissingPermission")
    private fun readWifiResults() {
        val results = try {
            wifi?.scanResults
        } catch (e: SecurityException) {
            null
        } ?: return
        val nowMs = System.currentTimeMillis()
        val nowMicros = SystemClock.elapsedRealtime() * 1000
        for (r in results) {
            // Skip cached results from older scans.
            if (nowMicros - r.timestamp > MAX_RESULT_AGE_MS * 1000) continue
            val bssid = r.BSSID ?: continue
            @Suppress("DEPRECATION")
            val ssid = r.SSID?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            handle(Observation(SignalSource.WIFI, bssid, ssid, r.level, nowMs))
        }
    }

    // ---------- Bluetooth ----------

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: BleScanResult) = onBle(result)
        override fun onBatchScanResults(results: MutableList<BleScanResult>) = results.forEach(::onBle)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            DetectionRepository.setProblem("Bluetooth scan failed (code $errorCode). Try stopping and starting again.")
        }
    }

    private fun onBle(r: BleScanResult) {
        handle(
            Observation(
                SignalSource.BLUETOOTH,
                r.device.address,
                r.scanRecord?.deviceName,
                r.rssi,
                System.currentTimeMillis(),
            ),
        )
    }

    @SuppressLint("MissingPermission")
    private fun startBle() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            DetectionRepository.setProblem("Bluetooth is off, so only Wi-Fi is being scanned.")
            return
        }
        bleScanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            bleScanner?.startScan(null, settings, bleCallback)
        } catch (e: SecurityException) {
            DetectionRepository.setProblem("Bluetooth permission is needed to scan Bluetooth.")
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

    // ---------- Matching ----------

    private val pruneTick = object : Runnable {
        override fun run() {
            DetectionRepository.pruneOlderThan(System.currentTimeMillis() - DEVICE_TIMEOUT_MS)
            handler.postDelayed(this, PRUNE_INTERVAL_MS)
        }
    }

    private fun handle(obs: Observation) {
        val settings = store.settings.value
        val signature = Detector.match(store.signatures.value, obs)
        DetectionRepository.record(obs, signature)
        if (signature != null && obs.rssi >= settings.minRssi && gate.shouldAlert(obs.key, obs.timeMs)) {
            notifier.alert(obs, signature, settings)
        }
    }

    companion object {
        const val ACTION_STOP = "com.policeradar.app.STOP"
        private const val TAG = "ScannerService"
        private const val NOTIFICATION_ID = 1
        private const val WIFI_INTERVAL_MS = 30_000L
        private const val MAX_RESULT_AGE_MS = 60_000L
        private const val BLE_RESTART_MS = 10 * 60_000L
        private const val PRUNE_INTERVAL_MS = 5_000L
        private const val DEVICE_TIMEOUT_MS = 90_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ScannerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScannerService::class.java))
        }
    }
}
