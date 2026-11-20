package com.bleradar.app.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Fast mode: instead of waiting for a device's broadcasts (every 0.1–2 s, set by the device),
 * connect to it and ask for its signal strength several times a second.
 * Only works for devices that accept connections; others just stay on broadcasts.
 */
class RssiPoller(
    context: Context,
    private val address: String,
    private val onRssi: (Int) -> Unit,
    private val onState: (State) -> Unit,
    /** What the device says about itself (name, maker, model), once it's been asked. */
    private val onInfo: (DeviceInfo) -> Unit = {},
) {
    enum class State(val label: String) {
        CONNECTING("Connecting…"),
        CONNECTED("Connected: reading the signal directly"),
        RETRYING("Connection dropped, retrying…"),
        UNAVAILABLE("This device doesn't accept connections, so broadcasts are used"),
        NO_PERMISSION("Fast mode needs the Nearby devices permission"),
    }

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var connected = false
    private var failures = 0
    private var infoAsked = false
    private val pendingReads = ArrayDeque<BluetoothGattCharacteristic>()
    private val infoValues = HashMap<UUID, String>()

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        failures = 0
        connect()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {
            // Permission revoked; nothing to close.
        }
        gatt = null
        connected = false
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        if (!running) return
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            set(State.UNAVAILABLE)
            return
        }
        set(if (failures == 0) State.CONNECTING else State.RETRYING)
        try {
            val device = adapter.getRemoteDevice(address)
            gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
            // Android can wait ~30 s before giving up on a device that never answers.
            handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        } catch (e: SecurityException) {
            set(State.NO_PERMISSION)
        } catch (e: IllegalArgumentException) {
            set(State.UNAVAILABLE)
        }
    }

    private val connectTimeout = Runnable { if (!connected) failed() }

    @SuppressLint("MissingPermission")
    private fun failed() {
        try {
            gatt?.close()
        } catch (e: SecurityException) {
            // Ignore.
        }
        gatt = null
        connected = false
        handler.removeCallbacks(poll)
        handler.removeCallbacks(connectTimeout)
        failures++
        if (!running) return
        if (failures >= MAX_FAILURES) {
            set(State.UNAVAILABLE)
            // Try again later: the device may only accept connections now and then.
            handler.postDelayed({ failures = 0; connect() }, 20_000)
        } else {
            handler.postDelayed(::connect, 1_000)
        }
    }

    private val poll = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (!running || !connected) return
            try {
                gatt?.readRemoteRssi()
            } catch (e: SecurityException) {
                set(State.NO_PERMISSION)
                return
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (!running || g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connected = true
                    failures = 0
                    handler.removeCallbacks(connectTimeout)
                    set(State.CONNECTED)
                    if (infoAsked) {
                        handler.post(poll)
                    } else {
                        // First ask what it is, then start reading the signal.
                        infoAsked = true
                        val started = try {
                            g.discoverServices()
                        } catch (e: SecurityException) {
                            false
                        }
                        if (started) handler.postDelayed(infoTimeout, INFO_TIMEOUT_MS) else handler.post(poll)
                    }
                } else {
                    failed()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (!running || g != gatt) return@post
                pendingReads.clear()
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    for ((service, ch) in INFO_CHARACTERISTICS) {
                        g.getService(service)?.getCharacteristic(ch)?.let(pendingReads::addLast)
                    }
                }
                readNextInfo()
            }
        }

        @Deprecated("Still called on all Android versions, with the value set on the characteristic.")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val value = c.value
            handler.post {
                if (!running || g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
                    val text = String(value, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                    if (text.isNotEmpty()) infoValues[c.uuid] = text
                }
                readNextInfo()
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handler.post { if (running) onRssi(rssi) }
        }
    }

    /** Read the name/maker/model one at a time (Bluetooth allows one request at once), then start polling. */
    @SuppressLint("MissingPermission")
    private fun readNextInfo() {
        while (pendingReads.isNotEmpty()) {
            val c = pendingReads.removeFirst()
            val ok = try {
                gatt?.readCharacteristic(c) == true
            } catch (e: SecurityException) {
                false
            }
            if (ok) return
        }
        finishInfo()
    }

    private val infoTimeout = Runnable { pendingReads.clear(); finishInfo() }

    private fun finishInfo() {
        handler.removeCallbacks(infoTimeout)
        val info = DeviceInfo(
            name = infoValues[DEVICE_NAME],
            manufacturer = infoValues[MANUFACTURER],
            model = infoValues[MODEL],
        )
        if (!info.isEmpty) onInfo(info)
        handler.removeCallbacks(poll)
        if (running && connected) handler.post(poll)
    }

    private fun set(state: State) = handler.post { if (running) onState(state) }

    companion object {
        /** About 6 readings a second. */
        private const val POLL_MS = 160L
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val MAX_FAILURES = 3
        private const val INFO_TIMEOUT_MS = 4_000L

        private fun uuid16(v: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))
        private val GENERIC_ACCESS = uuid16(0x1800)
        private val DEVICE_INFO = uuid16(0x180A)
        private val DEVICE_NAME = uuid16(0x2A00)
        private val MANUFACTURER = uuid16(0x2A29)
        private val MODEL = uuid16(0x2A24)
        private val INFO_CHARACTERISTICS = listOf(
            GENERIC_ACCESS to DEVICE_NAME,
            DEVICE_INFO to MANUFACTURER,
            DEVICE_INFO to MODEL,
        )
    }
}
