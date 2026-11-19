package com.bleradar.app.detect

enum class Source(val label: String) {
    BLE("Bluetooth"),
    WIFI("Wi-Fi"),
}

/** One received advertisement (Bluetooth) or scan result (Wi-Fi). */
class Sighting(
    val source: Source,
    val address: String,
    val name: String?,
    val rssi: Int,
    val timeMs: Long,
    /** Bluetooth manufacturer data, keyed by company ID (the ID itself is not in the bytes). */
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    /** 16-bit Bluetooth service UUIDs, e.g. 0xFEED. */
    val serviceUuids: Set<Int> = emptySet(),
    /** Bluetooth service data, keyed by 16-bit service UUID. */
    val serviceData: Map<Int, ByteArray> = emptyMap(),
    /** Advertised transmit power in dBm, if the device sends it. */
    val txPower: Int? = null,
) {
    val key: String get() = "${source.name}:${address.uppercase()}"
}

enum class Group(val label: String) {
    CAMERA("Possible hidden camera"),
    TRACKER("Tracker"),
    OTHER("Other device"),
}

/** How suspicious a device is. Lower ordinal = more suspicious. */
enum class Threat(val label: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
    NONE("None"),
}

enum class Kind(val label: String, val emoji: String, val group: Group, val threat: Threat) {
    CAMERA_WIFI("Camera Wi-Fi hotspot", "📷", Group.CAMERA, Threat.HIGH),
    CAMERA_BLE("Possible camera", "📷", Group.CAMERA, Threat.MEDIUM),
    FINDMY_SEPARATED("AirTag / Find My tracker", "🏷️", Group.TRACKER, Threat.HIGH),
    TILE("Tile tracker", "🏷️", Group.TRACKER, Threat.MEDIUM),
    SMARTTAG("Samsung SmartTag", "🏷️", Group.TRACKER, Threat.MEDIUM),
    GOOGLE_FIND("Google Find My Device tracker", "🏷️", Group.TRACKER, Threat.MEDIUM),
    CHIPOLO("Chipolo tracker", "🏷️", Group.TRACKER, Threat.MEDIUM),
    FINDMY_NEAR_OWNER("Find My device with its owner", "🍏", Group.TRACKER, Threat.LOW),
    APPLE("Apple device", "🍏", Group.OTHER, Threat.NONE),
    OTHER("Bluetooth device", "🔵", Group.OTHER, Threat.NONE),
}

data class Classification(val kind: Kind, val reason: String)

/** Works out what a sighting is. Pure Kotlin so it can be unit tested. */
object Classifier {
    const val APPLE_ID = 0x004C
    const val UUID_TILE = 0xFEED
    const val UUID_TILE_2 = 0xFEEC
    const val UUID_SMARTTAG = 0xFD5A
    const val UUID_EDDYSTONE = 0xFEAA
    const val UUID_CHIPOLO = 0xFE33
    private const val APPLE_OFFLINE_FINDING = 0x12

    /**
     * Words in Bluetooth names used by Wi-Fi spy cameras (which often set up over Bluetooth),
     * mini cameras, dash cams, baby monitors and security-camera brands.
     */
    val cameraNameWords = listOf(
        "cam", "camera", "ipcam", "ipc", "webcam", "spycam", "spy cam", "hidden cam", "hiddencam", "minicam",
        "mini cam", "wificam", "wifi cam", "hdwificam", "hd cam", "hdcam", "v380", "lookcam", "yoosee", "icsee",
        "cloudcam", "dvr", "nvr", "ezviz", "imou", "reolink", "hikvision", "dahua", "wyze", "eufycam", "tapo c",
        "vstarcam", "dashcam", "dash cam", "babycam", "baby monitor", "nanny cam",
    )

    /** Hotspot names hidden Wi-Fi cameras broadcast while waiting to be set up, or in direct mode. */
    val cameraWifiWords = listOf(
        "v380", "hdwificam", "lookcam", "ipc", "ipcam", "wificam", "wifi cam", "gw ap", "cam", "camera",
        "spycam", "spy cam", "hidden cam", "minicam", "mini cam", "yoosee", "icsee", "cloudcam",
        "hdcam", "vstarcam", "dvr", "nvr", "littlelf", "jxlcam", "ezviz", "imou", "reolink", "dashcam",
    )

    /** Phones, laptops and earbuds named after a person called "Cam" ("Cam's iPhone"). */
    val personalDeviceWords = listOf(
        "iphone", "ipad", "macbook", "galaxy", "pixel", "android", "phone", "airpods", "buds", "watch", "laptop",
    )

    private fun looksLikeCamera(name: String?, words: List<String>): Boolean =
        nameHasWord(name, words) && !nameHasWord(name, personalDeviceWords)

    fun classify(s: Sighting): Classification {
        if (s.source == Source.WIFI) {
            return if (looksLikeCamera(s.name, cameraWifiWords)) {
                Classification(Kind.CAMERA_WIFI, "Wi-Fi network \"${s.name}\" is named like a camera hotspot")
            } else {
                Classification(Kind.OTHER, "Wi-Fi network")
            }
        }

        // ---- Trackers ----
        s.manufacturerData[APPLE_ID]?.let { apple ->
            val of = appleRecord(apple, APPLE_OFFLINE_FINDING)
            if (of != null) {
                // A full 25-byte key means the tracker is away from its owner's iPhone:
                // the state Apple's own anti-stalking alerts look for.
                return if (of.size >= 22) {
                    Classification(Kind.FINDMY_SEPARATED, "Apple Find My broadcast, away from its owner")
                } else {
                    Classification(Kind.FINDMY_NEAR_OWNER, "Apple Find My broadcast, near its owner")
                }
            }
        }
        if (UUID_TILE in s.serviceUuids || UUID_TILE_2 in s.serviceUuids ||
            UUID_TILE in s.serviceData || UUID_TILE_2 in s.serviceData
        ) {
            return Classification(Kind.TILE, "Tile service ID")
        }
        if (UUID_SMARTTAG in s.serviceUuids || UUID_SMARTTAG in s.serviceData) {
            return Classification(Kind.SMARTTAG, "Samsung SmartTag service ID")
        }
        s.serviceData[UUID_EDDYSTONE]?.firstOrNull()?.toInt()?.and(0xFF)?.let { frame ->
            if (frame == 0x40 || frame == 0x41) {
                return Classification(Kind.GOOGLE_FIND, "Google Find My Device network broadcast")
            }
        }
        if (UUID_CHIPOLO in s.serviceUuids || UUID_CHIPOLO in s.serviceData) {
            return Classification(Kind.CHIPOLO, "Chipolo service ID")
        }

        // ---- Cameras ----
        if (looksLikeCamera(s.name, cameraNameWords)) {
            return Classification(Kind.CAMERA_BLE, "Bluetooth name \"${s.name}\" sounds like a camera")
        }

        if (APPLE_ID in s.manufacturerData) return Classification(Kind.APPLE, "Apple maker ID")
        return Classification(Kind.OTHER, "No tracker or camera signs")
    }

    /**
     * Apple manufacturer data is a list of [type, length, value...] records.
     * Returns the value of the first record of [type], or null.
     */
    fun appleRecord(data: ByteArray, type: Int): ByteArray? {
        var i = 0
        while (i + 1 < data.size) {
            val t = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            val start = i + 2
            val end = minOf(start + len, data.size)
            if (t == type) return data.copyOfRange(start, end)
            i = start + len
        }
        return null
    }

    /** Lowercase, with "-", "_" and "." turned into spaces so "IPCAM_01" reads as "ipcam 01". */
    fun normalizeName(s: String): String =
        s.lowercase().replace(Regex("[-_.]+"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * True if [name] contains one of [words] standing on its own: "CAM-01" and "V380_A1B2"
     * match, "Cambridge" and "Scampi" don't. Digits may touch the word.
     */
    fun nameHasWord(name: String?, words: List<String>): Boolean {
        if (name.isNullOrBlank()) return false
        val n = normalizeName(name)
        return words.any { w ->
            val p = normalizeName(w)
            p.isNotEmpty() && Regex("(?<![a-z])${Regex.escape(p)}(?![a-z])").containsMatchIn(n)
        }
    }
}
