package com.policeradar.app.data

import android.content.Context
import android.content.SharedPreferences
import com.policeradar.app.detect.BuiltInSignatures
import com.policeradar.app.detect.Category
import com.policeradar.app.detect.Confidence
import com.policeradar.app.detect.MatchType
import com.policeradar.app.detect.Signature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AppSettings(
    /** Only alert for signals at least this strong (dBm). -90 is far, -60 is very close. */
    val minRssi: Int = -90,
    /** Minutes before the same device can alert again. */
    val cooldownMinutes: Int = 10,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val voice: Boolean = true,
    /** Which kinds of emergency service trigger alerts. */
    val alertCategories: Set<Category> = Category.entries.toSet(),
    /** Show every nearby device so the user can mark unknown ones (off = responders only). */
    val learningMode: Boolean = false,
)

/** Persists settings and signatures, and exposes them as flows shared by the UI and the scanner. */
class SettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("police_radar", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _signatures = MutableStateFlow(loadSignatures())
    val signatures: StateFlow<List<Signature>> = _signatures.asStateFlow()

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putInt("minRssi", s.minRssi)
            .putInt("cooldownMinutes", s.cooldownMinutes)
            .putBoolean("sound", s.sound)
            .putBoolean("vibrate", s.vibrate)
            .putBoolean("voice", s.voice)
            .putStringSet("alertCategories", s.alertCategories.map { it.name }.toSet())
            .putStringSet("knownCategories", Category.entries.map { it.name }.toSet())
            .putBoolean("learningMode", s.learningMode)
            .apply()
        _settings.value = s
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val disabled = prefs.getStringSet("disabledIds", emptySet())!!.toMutableSet()
        if (enabled) disabled.remove(id) else disabled.add(id)
        prefs.edit().putStringSet("disabledIds", disabled).apply()
        _signatures.value = loadSignatures()
    }

    fun addCustom(label: String, category: Category, matchType: MatchType, patterns: List<String>) {
        val sig = Signature(
            id = "custom_" + UUID.randomUUID(),
            label = label.ifBlank { "My signature" },
            description = "Added by you.",
            category = category,
            matchType = matchType,
            patterns = patterns.map { it.trim() }.filter { it.isNotEmpty() },
            confidence = Confidence.HIGH,
        )
        saveCustom(loadCustom() + sig)
    }

    fun removeCustom(id: String) = saveCustom(loadCustom().filterNot { it.id == id })

    private fun saveCustom(list: List<Signature>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("label", s.label)
                    .put("category", s.category.name)
                    .put("matchType", s.matchType.name)
                    .put("patterns", JSONArray(s.patterns)),
            )
        }
        prefs.edit().putString("custom", arr.toString()).apply()
        _signatures.value = loadSignatures()
    }

    private fun loadCustom(): List<Signature> {
        val raw = prefs.getString("custom", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val p = o.getJSONArray("patterns")
                Signature(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    description = "Added by you.",
                    category = runCatching { Category.valueOf(o.optString("category")) }.getOrDefault(Category.POLICE),
                    matchType = MatchType.valueOf(o.getString("matchType")),
                    patterns = (0 until p.length()).map { p.getString(it) },
                    confidence = Confidence.HIGH,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadSignatures(): List<Signature> {
        val disabled = prefs.getStringSet("disabledIds", emptySet())!!
        return (BuiltInSignatures.all + loadCustom()).map { it.copy(enabled = it.id !in disabled) }
    }

    private fun loadSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            minRssi = prefs.getInt("minRssi", d.minRssi),
            cooldownMinutes = prefs.getInt("cooldownMinutes", d.cooldownMinutes),
            sound = prefs.getBoolean("sound", d.sound),
            vibrate = prefs.getBoolean("vibrate", d.vibrate),
            voice = prefs.getBoolean("voice", d.voice),
            alertCategories = loadAlertCategories() ?: d.alertCategories,
            learningMode = prefs.getBoolean("learningMode", d.learningMode),
        )
    }

    /** Saved choice, plus any category added in an app update since it was saved (on by default). */
    private fun loadAlertCategories(): Set<Category>? {
        val saved = prefs.getStringSet("alertCategories", null) ?: return null
        val known = prefs.getStringSet("knownCategories", null)
            ?: setOf(Category.POLICE, Category.FIRE, Category.EMS, Category.RESPONDER).map { it.name }.toSet()
        return Category.entries.filter { it.name in saved || it.name !in known }.toSet()
    }

    companion object {
        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}
