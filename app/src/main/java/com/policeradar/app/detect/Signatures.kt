package com.policeradar.app.detect

enum class SignalSource(val label: String) {
    WIFI("Wi-Fi"),
    BLUETOOTH("Bluetooth"),
}

/**
 * Who the equipment belongs to. [RESPONDER] = used by several services, can't tell which.
 * [inSentence] is the label as it reads mid-sentence ("Possible ambulance nearby").
 */
enum class Category(val label: String, val emoji: String, val inSentence: String) {
    POLICE("Police", "🚓", "police"),
    FIRE("Fire", "🚒", "fire"),
    EMS("Ambulance", "🚑", "ambulance"),
    CAMERA("Flock / plate camera", "📷", "Flock camera"),
    RESPONDER("Emergency vehicle", "🚨", "emergency vehicle"),
}

enum class MatchType(val label: String, val hint: String) {
    MAC_PREFIX("Hardware address starts with", "e.g. 00:25:DF"),
    WIFI_NAME("Wi-Fi name contains", "e.g. PATROL"),
    BLE_NAME("Bluetooth name contains", "e.g. Axon"),
    BLE_MANUFACTURER("Bluetooth maker ID is", "e.g. 0x09C8"),
}

/** How strongly a match suggests an emergency service. Lower ordinal = stronger. */
enum class Confidence(val label: String) {
    HIGH("Likely"),
    MEDIUM("Possible"),
    LOW("Weak match"),
}

/**
 * A pattern that identifies emergency-service equipment by the radio signals it broadcasts.
 * [patterns] are alternatives: any one matching is enough.
 * With [wholeWord], name patterns must not be part of a longer word: "ENGINE 12" and
 * "ENGINE12" match "engine", "Enginetics" doesn't. Names containing any of [excludes] never match.
 */
data class Signature(
    val id: String,
    val label: String,
    val description: String,
    val category: Category,
    val matchType: MatchType,
    val patterns: List<String>,
    val confidence: Confidence,
    val wholeWord: Boolean = false,
    val excludes: List<String> = emptyList(),
    val builtIn: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * Starter list of signatures. These are heuristics, not guarantees: departments differ in
 * what gear they carry and how it's named. Users can turn any of them off and teach the app
 * new devices with learning mode.
 */
object BuiltInSignatures {
    /** Consumer products whose names contain responder words. */
    private val consumerFire = listOf(
        "fire tv", "firetv", "fire stick", "firestick", "fire tablet", "kindle fire", "amazon fire", "fire hd",
    )

    val all: List<Signature> = listOf(
        // ---- Police ----
        Signature(
            id = "axon_oui",
            label = "Axon body camera / Taser",
            description = "Body cameras, Tasers and Axon Signal devices made by Axon " +
                "(formerly TASER International), which sells mainly to law enforcement.",
            category = Category.POLICE,
            matchType = MatchType.MAC_PREFIX,
            patterns = listOf("00:25:DF"),
            confidence = Confidence.HIGH,
            builtIn = true,
        ),
        Signature(
            id = "police_wifi_name",
            label = "Police vehicle Wi-Fi",
            description = "Wi-Fi networks named with police words, e.g. \"PATROL-14\" or \"SHERIFF UNIT 7\".",
            category = Category.POLICE,
            matchType = MatchType.WIFI_NAME,
            patterns = listOf("police", "sheriff", "patrol", "trooper", "deputy", "k9", "k-9", "state police", "highway patrol"),
            confidence = Confidence.MEDIUM,
            wholeWord = true,
            builtIn = true,
        ),

        // ---- Fire ----
        Signature(
            id = "fire_wifi_name",
            label = "Fire apparatus Wi-Fi",
            description = "Wi-Fi networks named after fire apparatus or departments, e.g. " +
                "\"ENGINE 12\", \"LADDER-3\", \"VFD Rescue\".",
            category = Category.FIRE,
            matchType = MatchType.WIFI_NAME,
            patterns = listOf(
                "fire dept", "firedept", "fire department", "fire rescue", "fire engine", "fire truck", "firetruck",
                "fire district", "vfd", "engine", "ladder", "battalion", "tanker", "brush truck",
            ),
            confidence = Confidence.MEDIUM,
            wholeWord = true,
            excludes = consumerFire,
            builtIn = true,
        ),

        // ---- Ambulance / EMS ----
        Signature(
            id = "ems_wifi_name",
            label = "Ambulance Wi-Fi",
            description = "Wi-Fi networks named after ambulances or EMS units, e.g. \"MEDIC 5\", \"AMBULANCE-22\".",
            category = Category.EMS,
            matchType = MatchType.WIFI_NAME,
            patterns = listOf("ambulance", "medic", "paramedic", "ems", "rescue squad", "emt"),
            confidence = Confidence.MEDIUM,
            wholeWord = true,
            builtIn = true,
        ),

        // ---- Flock Safety license-plate cameras ----
        // Patterns reported by community detection projects (e.g. "flock-you"). Flock changes
        // its hardware over time, so these may miss newer units.
        Signature(
            id = "flock_wifi_name",
            label = "Flock camera Wi-Fi",
            description = "Flock Safety license-plate cameras and their battery packs broadcast Wi-Fi " +
                "names like \"Flock-A1B2C3\" or \"FS Ext Battery\".",
            category = Category.CAMERA,
            matchType = MatchType.WIFI_NAME,
            patterns = listOf("flock", "fs ext battery", "pigvision"),
            confidence = Confidence.HIGH,
            wholeWord = true,
            builtIn = true,
        ),
        Signature(
            id = "flock_ble_name",
            label = "Flock camera Bluetooth",
            description = "Bluetooth names used by Flock cameras and their \"Penguin\" battery packs.",
            category = Category.CAMERA,
            matchType = MatchType.BLE_NAME,
            patterns = listOf("flock", "fs ext battery", "pigvision", "penguin"),
            confidence = Confidence.MEDIUM,
            wholeWord = true,
            builtIn = true,
        ),
        Signature(
            id = "flock_ble_maker",
            label = "Flock battery pack (Bluetooth maker ID)",
            description = "Bluetooth maker ID 0x09C8, reported on Flock camera battery packs. " +
                "The same ID may appear on some unrelated gadgets.",
            category = Category.CAMERA,
            matchType = MatchType.BLE_MANUFACTURER,
            patterns = listOf("0x09C8"),
            confidence = Confidence.MEDIUM,
            builtIn = true,
        ),

        // ---- Shared by all services ----
        Signature(
            id = "cradlepoint_oui",
            label = "Cradlepoint vehicle router",
            description = "In-vehicle routers fitted to many patrol cars, fire trucks and ambulances. " +
                "Also used by some buses and businesses.",
            category = Category.RESPONDER,
            matchType = MatchType.MAC_PREFIX,
            patterns = listOf("00:30:44"),
            confidence = Confidence.MEDIUM,
            builtIn = true,
        ),
        Signature(
            id = "cradlepoint_ssid",
            label = "Cradlepoint vehicle router (default name)",
            description = "Wi-Fi names Cradlepoint vehicle routers use out of the box, based on their model numbers.",
            category = Category.RESPONDER,
            matchType = MatchType.WIFI_NAME,
            patterns = listOf("IBR900", "IBR1100", "IBR1700", "IBR600", "R1900", "R2100"),
            confidence = Confidence.MEDIUM,
            builtIn = true,
        ),
    )
}
