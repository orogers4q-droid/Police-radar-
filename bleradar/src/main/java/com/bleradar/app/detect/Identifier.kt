package com.bleradar.app.detect

/**
 * What a device most likely is, in plain words.
 * [specificity] lets the most informative guess win when a device sends several kinds of
 * broadcast (iPhones, for example, alternate between several).
 */
data class Identity(
    val what: String,
    val emoji: String,
    /** Company behind the device or its Bluetooth chip, if known. */
    val maker: String? = null,
    val specificity: Int = 0,
) {
    /** "Headphones or earbuds · Samsung" */
    val line: String get() = if (maker != null && !what.contains(maker, ignoreCase = true)) "$what · $maker" else what
}

/**
 * Works out what a Bluetooth device is from the clues in its broadcast: Apple's broadcast
 * types, standard service IDs, the maker's company ID and words in its name.
 * Pure Kotlin so it can be unit tested.
 */
object Identifier {

    private class Company(val name: String, val what: String?, val emoji: String, val chipOnly: Boolean = false)

    /** Bluetooth SIG company IDs for common brands and chip makers. */
    private val companies = mapOf(
        0x004C to Company("Apple", "Apple device", "🍏"),
        0x0006 to Company("Microsoft", "Windows PC or Microsoft accessory", "💻"),
        0x0075 to Company("Samsung", "Samsung phone, watch, earbuds or TV", "📱"),
        0x00E0 to Company("Google", "Google or Android device", "📱"),
        0x0087 to Company("Garmin", "Garmin watch or fitness device", "⌚"),
        0x009E to Company("Bose", "Bose headphones or speaker", "🎧"),
        0x0157 to Company("Huami (Amazfit)", "Amazfit watch or fitness band", "⌚"),
        0x038F to Company("Xiaomi", "Xiaomi device", "📱"),
        0x0171 to Company("Amazon", "Amazon device (Echo, Kindle, Fire TV)", "🔊"),
        0x05A7 to Company("Sonos", "Sonos speaker", "🔊"),
        0x00C4 to Company("LG", "LG TV, phone or appliance", "📺"),
        0x006B to Company("Polar", "Polar fitness watch or sensor", "⌚"),
        0x0067 to Company("Jabra (GN)", "Jabra headset", "🎧"),
        0x0057 to Company("Harman (JBL)", "JBL / Harman speaker or headphones", "🔊"),
        0x027D to Company("Huawei", "Huawei phone, watch or earbuds", "📱"),
        0x012D to Company("Sony", "Sony headphones, TV or camera", "🎧"),
        0x01DA to Company("Logitech", "Logitech keyboard, mouse or webcam", "⌨️"),
        0x0059 to Company("Nordic Semiconductor", null, "🔵", chipOnly = true),
        0x000F to Company("Broadcom", null, "🔵", chipOnly = true),
        0x000A to Company("Qualcomm", null, "🔵", chipOnly = true),
        0x000D to Company("Texas Instruments", null, "🔵", chipOnly = true),
        0x0002 to Company("Intel", null, "🔵", chipOnly = true),
        0x02E5 to Company("Espressif", null, "🔵", chipOnly = true),
    )

    /** Standard and company-registered 16-bit service IDs that say what a device does. */
    private val services = mapOf(
        0x180D to Identity("Heart-rate monitor or fitness band", "⌚"),
        0x1812 to Identity("Keyboard, mouse or remote", "⌨️"),
        0x1816 to Identity("Bike speed / cadence sensor", "🚲"),
        0x1818 to Identity("Bike power meter", "🚲"),
        0x1826 to Identity("Fitness machine (treadmill, bike, rower)", "🏋️"),
        0x181A to Identity("Temperature / humidity sensor", "🌡️"),
        0x1809 to Identity("Thermometer", "🌡️"),
        0x1810 to Identity("Blood-pressure monitor", "🩺"),
        0x1808 to Identity("Glucose monitor", "🩺"),
        0x181D to Identity("Bathroom scale", "⚖️"),
        0xFE2C to Identity("Headphones or earbuds (Google Fast Pair)", "🎧"),
        0xFE9F to Identity("Google device", "📱", "Google"),
        0xFE95 to Identity("Xiaomi smart-home device", "🏠", "Xiaomi"),
        0xFEAA to Identity("Beacon (shop or location beacon)", "📍"),
        0xFE03 to Identity("Amazon Echo / Alexa device", "🔊", "Amazon"),
        0xFE07 to Identity("Sonos speaker", "🔊", "Sonos"),
        0xFE0F to Identity("Philips Hue light", "💡", "Philips"),
        0xFEBE to Identity("Bose headphones or speaker", "🎧", "Bose"),
        0xFD50 to Identity("Smart-home device (Tuya / Smart Life)", "🏠", "Tuya"),
        0xFD6F to Identity("Phone (exposure notifications)", "📱"),
    )

    /** AirPods / Beats model codes in Apple's "proximity pairing" broadcast. */
    private val appleAudioModels = mapOf(
        0x0220 to "AirPods (1st gen)",
        0x0F20 to "AirPods (2nd gen)",
        0x1320 to "AirPods (3rd gen)",
        0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro (2nd gen)",
        0x0A20 to "AirPods Max",
        0x0320 to "Powerbeats3",
        0x0520 to "BeatsX",
        0x0620 to "Beats Solo3",
        0x0920 to "Beats Studio3",
        0x0B20 to "Powerbeats Pro",
        0x0C20 to "Beats Solo Pro",
        0x1020 to "Beats Flex",
        0x1120 to "Beats Studio Buds",
    )

    /** Apple broadcast types (first byte of each record in Apple's manufacturer data). */
    private val appleTypes = mapOf(
        0x02 to Identity("Beacon (iBeacon)", "📍", "Apple", 3),
        0x05 to Identity("iPhone or Mac sharing (AirDrop)", "📱", "Apple", 3),
        0x07 to Identity("AirPods or Beats headphones", "🎧", "Apple", 4),
        0x09 to Identity("Apple TV or AirPlay speaker", "📺", "Apple", 3),
        0x0A to Identity("iPhone, iPad or Mac (AirPlay)", "📱", "Apple", 2),
        0x0C to Identity("iPhone, iPad, Mac or Apple Watch (Handoff)", "📱", "Apple", 2),
        0x0D to Identity("iPhone sharing its hotspot", "📱", "Apple", 3),
        0x0E to Identity("iPad or Mac using a hotspot", "💻", "Apple", 3),
        0x0F to Identity("iPhone or iPad", "📱", "Apple", 2),
        0x10 to Identity("iPhone, iPad, Mac or Apple Watch", "📱", "Apple", 2),
        0x12 to Identity("Find My device (AirTag, AirPods or iPhone)", "🏷️", "Apple", 3),
    )

    private class Hint(val identity: Identity, words: List<String>) {
        val regex = Regex(words.joinToString("|", "(?<![a-z])(", ")(?![a-z])") { Regex.escape(Classifier.normalizeName(it)) })
    }

    /** Words in device names, checked in order. */
    private val nameHints = listOf(
        Hint(Identity("Headphones or earbuds", "🎧", specificity = 3), listOf(
            "airpods", "buds", "earbuds", "headphones", "headphone", "headset", "earphones", "beats", "jabra",
            "soundcore", "wh 1000", "wf 1000", "freebuds", "pixel buds", "galaxy buds", "quietcomfort", "qc35", "qc45",
        )),
        Hint(Identity("Smartwatch or fitness band", "⌚", specificity = 3), listOf(
            "watch", "band", "fitbit", "garmin", "amazfit", "mi band", "whoop", "forerunner", "fenix", "versa",
            "charge 5", "charge 6", "inspire", "polar", "oura",
        )),
        Hint(Identity("Phone or tablet", "📱", specificity = 3), listOf(
            "iphone", "ipad", "galaxy", "pixel", "phone", "oneplus", "moto", "redmi", "poco", "tablet",
        )),
        Hint(Identity("Computer", "💻", specificity = 3), listOf(
            "macbook", "imac", "laptop", "pc", "desktop", "thinkpad", "surface", "chromebook",
        )),
        Hint(Identity("TV or streaming device", "📺", specificity = 3), listOf(
            "tv", "bravia", "roku", "fire tv", "chromecast", "shield", "projector",
        )),
        Hint(Identity("Speaker", "🔊", specificity = 3), listOf(
            "speaker", "jbl", "sonos", "soundlink", "echo", "boombox", "ue boom", "megaboom", "soundbar", "homepod",
        )),
        Hint(Identity("Keyboard, mouse or controller", "⌨️", specificity = 3), listOf(
            "keyboard", "mouse", "trackpad", "remote", "controller", "xbox", "dualsense", "dualshock", "joy con", "gamepad",
        )),
        Hint(Identity("Car", "🚗", specificity = 3), listOf(
            "car", "tesla", "bmw", "audi", "ford", "toyota", "honda", "vw", "mercedes", "uconnect", "carplay", "my car",
            "hyundai", "kia", "nissan", "chevrolet", "chevy", "subaru", "mazda", "lexus", "volvo",
        )),
        Hint(Identity("Smart light", "💡", specificity = 3), listOf("light", "lamp", "bulb", "hue", "govee", "led strip")),
        Hint(Identity("Smart lock", "🔒", specificity = 3), listOf("lock", "august", "nuki", "yale")),
        Hint(Identity("Sensor", "🌡️", specificity = 3), listOf("sensor", "thermometer", "hygrometer", "ruuvi", "govee h", "switchbot")),
        Hint(Identity("Scale", "⚖️", specificity = 3), listOf("scale")),
        Hint(Identity("Electric toothbrush", "🪥", specificity = 3), listOf("toothbrush", "oral b", "sonicare")),
        Hint(Identity("Printer", "🖨️", specificity = 3), listOf("printer", "deskjet", "laserjet", "officejet")),
        Hint(Identity("E-bike or scooter", "🛴", specificity = 3), listOf("scooter", "ninebot", "segway", "e bike", "ebike")),
    )

    fun identify(s: Sighting, kind: Kind): Identity {
        val company = s.manufacturerData.keys.firstNotNullOfOrNull { companies[it] }
        val makerName = company?.let { if (it.chipOnly) "${it.name} chip" else it.name }

        // Trackers and possible cameras are already identified by the classifier.
        if (kind.group != Group.OTHER) return Identity(kind.label, kind.emoji, makerName, 10)

        // Apple: the broadcast type says a lot, and AirPods even say their model.
        s.manufacturerData[Classifier.APPLE_ID]?.let { data ->
            Classifier.appleRecord(data, 0x07)?.let { rec ->
                if (rec.size >= 3) {
                    val model = ((rec[1].toInt() and 0xFF) shl 8) or (rec[2].toInt() and 0xFF)
                    appleAudioModels[model]?.let { return Identity(it, "🎧", "Apple", 6) }
                }
            }
            val type = data.firstOrNull()?.toInt()?.and(0xFF)
            appleTypes[type]?.let { apple ->
                return nameIdentity(s.name, "Apple")?.takeIf { apple.specificity < 3 } ?: apple
            }
        }

        s.serviceUuids.plus(s.serviceData.keys).firstNotNullOfOrNull { services[it] }?.let { svc ->
            return svc.copy(maker = svc.maker ?: makerName, specificity = 4)
        }

        nameIdentity(s.name, makerName)?.let { return it }

        if (company != null && company.what != null) return Identity(company.what, company.emoji, company.name, 2)
        if (company != null) return Identity("Gadget with a ${company.name} Bluetooth chip", "🔵", makerName, 1)
        return Identity(if (s.name != null) "Bluetooth device" else "Unknown Bluetooth device", "🔵", null, 0)
    }

    private fun nameIdentity(name: String?, maker: String?): Identity? {
        if (name.isNullOrBlank()) return null
        val n = Classifier.normalizeName(name)
        return nameHints.firstOrNull { it.regex.containsMatchIn(n) }?.identity?.copy(maker = maker)
    }
}
