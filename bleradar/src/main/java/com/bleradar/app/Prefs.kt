package com.bleradar.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    /** Beep when tracking, and chime when a new tracker or camera shows up. */
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    /** Chime + buzz when a tracker or possible camera first appears. */
    val alerts: Boolean = true,
    /** List every Bluetooth device, not just trackers and possible cameras. */
    val showAll: Boolean = true,
    /** When tracking, connect to the device to read its signal ~6 times a second. */
    val fastMode: Boolean = false,
)

/** Saved settings, shared by the screens and the scanner. */
object Prefs {
    @Volatile
    private var state: MutableStateFlow<Settings>? = null

    fun get(context: Context): StateFlow<Settings> = flow(context).asStateFlow()

    fun update(context: Context, transform: (Settings) -> Settings) {
        val f = flow(context)
        val s = transform(f.value)
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("sound", s.sound)
            .putBoolean("vibrate", s.vibrate)
            .putBoolean("alerts", s.alerts)
            .putBoolean("showAll", s.showAll)
            .putBoolean("fastMode", s.fastMode)
            .apply()
        f.value = s
    }

    private fun flow(context: Context): MutableStateFlow<Settings> =
        state ?: synchronized(this) {
            state ?: run {
                val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                val d = Settings()
                MutableStateFlow(
                    Settings(
                        sound = p.getBoolean("sound", d.sound),
                        vibrate = p.getBoolean("vibrate", d.vibrate),
                        alerts = p.getBoolean("alerts", d.alerts),
                        showAll = p.getBoolean("showAll", d.showAll),
                        fastMode = p.getBoolean("fastMode", d.fastMode),
                    ),
                ).also { state = it }
            }
        }

    private const val FILE = "ble_radar"
}
