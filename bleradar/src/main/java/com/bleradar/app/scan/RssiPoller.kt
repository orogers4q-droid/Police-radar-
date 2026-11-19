package com.bleradar.app.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper

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
                    handler.post(poll)
                } else {
                    failed()
                }
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handler.post { if (running) onRssi(rssi) }
        }
    }

    private fun set(state: State) = handler.post { if (running) onState(state) }

    companion object {
        /** About 6 readings a second. */
        private const val POLL_MS = 160L
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val MAX_FAILURES = 3
    }
}
