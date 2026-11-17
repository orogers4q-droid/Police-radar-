package com.policeradar.app.detect

enum class SignalSource(val label: String) {
    WIFI("Wi-Fi"),
    BLUETOOTH("Bluetooth"),
}

enum class MatchType(val label: String, val hint: String) {
    MAC_PREFIX("Hardware address starts with", "e.g. 00:25:DF"),
    WIFI_NAME("Wi-Fi name contains", "e.g. PATROL"),
    BLE_NAME("Bluetooth name contains", "e.g. Axon"),
}

/** How strongly a match suggests police. Lower ordinal = stronger. */
enum class Confidence(val label: String) {
    HIGH("Likely police"),
    MEDIUM("Possible police"),
    LOW("Weak match"),
}

/**
 * A pattern that identifies a kind of police equipment by the radio signals it broadcasts.
 * [patterns] are alternatives: any one of them matching is enough.
 */
data class Signature(
    val id: String,
    val label: String,
    val description: String,
    val matchType: MatchType,
    val patterns: List<String>,
    val confidence: Confidence,
    val builtIn: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * Starter list of signatures. These are heuristics, not guarantees: the same hardware
 * is also used by ambulances, fire crews, transit and businesses. Users can turn any of
 * them off and add their own from the "nearby signals" list.
 */
object BuiltInSignatures {
    val all: List<Signature> = listOf(
        Signature(
            id = "axon_oui",
            label = "Axon equipment",
            description = "Body cameras, Tasers and Axon Signal devices made by Axon " +
                "(formerly TASER International), which sells mainly to law enforcement.",
            matchType = MatchType.MAC_PREFIX,
            patterns = listOf("00:25:DF"),
            confidence = Confidence.HIGH,
            builtIn = true,
        ),
        Signature(
            id = "cradlepoint_oui",
            label = "Cradlepoint vehicle router",
            description = "In-vehicle routers fitted to many patrol cars. Also used by " +
                "ambulances, fire trucks, buses and businesses.",
            matchType = MatchType.MAC_PREFIX,
            patterns = listOf("00:30:44"),
            confidence = Confidence.MEDIUM,
            builtIn = true,
        ),
        Signature(
            id = "cradlepoint_ssid",
            label = "Cradlepoint vehicle router (default name)",
            description = "Wi-Fi names Cradlepoint vehicle routers use out of the box, " +
                "based on their model numbers.",
            matchType = MatchType.WIFI_NAME,
            patterns = listOf("IBR900", "IBR1100", "IBR1700", "IBR600", "R1900", "R2100"),
            confidence = Confidence.MEDIUM,
            builtIn = true,
        ),
        Signature(
            id = "police_keywords",
            label = "Police-named Wi-Fi",
            description = "Wi-Fi networks whose names contain police words. Could also be " +
                "a police station, or someone's joke network name.",
            matchType = MatchType.WIFI_NAME,
            patterns = listOf("police", "sheriff", "patrol", "trooper"),
            confidence = Confidence.MEDIUM,
            builtIn = true,
        ),
    )
}
