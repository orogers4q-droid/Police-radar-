package com.policeradar.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorTest {

    private fun wifi(mac: String, name: String? = null, rssi: Int = -70) =
        Observation(SignalSource.WIFI, mac, name, rssi, 0)

    private fun ble(mac: String, name: String? = null, rssi: Int = -70) =
        Observation(SignalSource.BLUETOOTH, mac, name, rssi, 0)

    private val sigs = BuiltInSignatures.all
    private val plainMac = "10:20:30:40:50:60"

    private fun categoryOf(obs: Observation) = Detector.match(sigs, obs)?.category

    @Test
    fun axonPrefixIsPolice_anyCaseOrSeparator() {
        assertEquals("axon_oui", Detector.match(sigs, ble("00:25:df:12:34:56"))?.id)
        assertEquals(Category.POLICE, categoryOf(wifi("00-25-DF-AA-BB-CC")))
    }

    @Test
    fun everydayDevicesDoNotMatch() {
        listOf("JBL Flip 5", "Starbucks WiFi", "NETGEAR42", "Fire TV Stick", "Firestick-4K", "Kindle Fire HD",
            "Enginetics", "Ladderball", "Gems Cafe", "Medicine Shoppe", "xfinitywifi", "Patrolman's Pub",
        ).forEach { name ->
            assertNull("\"$name\" should not match", Detector.match(sigs, wifi(plainMac, name)))
        }
    }

    @Test
    fun policeWifiNames() {
        listOf("PATROL-14", "Sheriff Unit 7", "unit12_police", "K9-UNIT", "Deputy 33").forEach {
            assertEquals(it, Category.POLICE, categoryOf(wifi(plainMac, it)))
        }
    }

    @Test
    fun fireWifiNames() {
        listOf("ENGINE 12", "ENGINE12", "Ladder-3", "VFD Rescue", "FireDept_Station2", "Battalion 1").forEach {
            assertEquals(it, Category.FIRE, categoryOf(wifi(plainMac, it)))
        }
    }

    @Test
    fun ambulanceWifiNames() {
        listOf("MEDIC 5", "MEDIC12", "Ambulance-22", "EMS_UNIT_4", "Paramedic3").forEach {
            assertEquals(it, Category.EMS, categoryOf(wifi(plainMac, it)))
        }
    }

    @Test
    fun flockCameras() {
        listOf("Flock-A1B2C3", "FLOCK_7F2E", "FS Ext Battery", "Pigvision").forEach {
            assertEquals(it, Category.CAMERA, categoryOf(wifi(plainMac, it)))
        }
        assertEquals(Category.CAMERA, categoryOf(ble("C0:11:22:33:44:55", "Penguin-0042")))
        // Flock's own registered hardware block.
        assertEquals("flock_mac", Detector.match(sigs, wifi("b4:1e:52:aa:bb:cc"))?.id)
        assertEquals(
            "flock_ble_maker",
            Detector.match(sigs, ble(plainMac).copy(manufacturerIds = setOf(0x004C, 0x09C8)))?.id,
        )
        // Apple's ID alone, "Flockhart" and a Wi-Fi "Penguin" don't count.
        assertNull(Detector.match(sigs, ble(plainMac).copy(manufacturerIds = setOf(0x004C))))
        assertNull(Detector.match(sigs, wifi(plainMac, "Flockhart Family")))
        assertNull(Detector.match(sigs, wifi(plainMac, "Penguin House")))
    }

    @Test
    fun parseIds() {
        assertEquals(0x09C8, Detector.parseId("0x09C8"))
        assertEquals(0x09C8, Detector.parseId("09c8"))
        assertEquals(2504, Detector.parseId("2504"))
        assertNull(Detector.parseId("zz"))
    }

    @Test
    fun cradlepointIsGenericButSpecificNameWins() {
        assertEquals(Category.RESPONDER, categoryOf(wifi("00:30:44:11:22:33", "IBR900-7d2")))
        assertEquals(Category.RESPONDER, categoryOf(wifi(plainMac, "IBR1700-abc")))
        // A Cradlepoint named "MEDIC 5" is an ambulance.
        assertEquals(Category.EMS, categoryOf(wifi("00:30:44:11:22:33", "MEDIC 5")))
    }

    @Test
    fun wifiNameRulesOnlyApplyToWifi() {
        assertNull(Detector.match(sigs, ble(plainMac, "PATROL-14")))
    }

    @Test
    fun strongestConfidenceWins() {
        // Axon MAC (HIGH, police) beats a fire-named network (MEDIUM).
        assertEquals("axon_oui", Detector.match(sigs, wifi("00:25:DF:00:00:01", "ENGINE 4"))?.id)
    }

    @Test
    fun disabledSignaturesAreIgnored() {
        val disabled = sigs.map { if (it.id == "axon_oui") it.copy(enabled = false) else it }
        assertNull(Detector.match(disabled, ble("00:25:DF:12:34:56")))
    }

    @Test
    fun customBleNameSignature() {
        val custom = Signature("c", "Mine", "", Category.FIRE, MatchType.BLE_NAME, listOf("Signal Sidearm"), Confidence.HIGH)
        assertTrue(Detector.matches(custom, ble("11:22:33:44:55:66", "Axon Signal Sidearm 2")))
        assertFalse(Detector.matches(custom, wifi("11:22:33:44:55:66", "Axon Signal Sidearm 2")))
    }

    @Test
    fun randomAddressDetection() {
        assertTrue(Detector.isRandomAddress("DA:11:22:33:44:55")) // 0xDA has bit 0x02 set
        assertFalse(Detector.isRandomAddress("00:25:DF:33:44:55"))
        assertEquals("00:25:DF", Detector.manufacturerPrefix("00-25-df-33-44-55"))
    }

    @Test
    fun proximityBuckets() {
        assertEquals(Proximity.VERY_CLOSE, Proximity.fromRssi(-55))
        assertEquals(Proximity.CLOSE, Proximity.fromRssi(-70))
        assertEquals(Proximity.NEARBY, Proximity.fromRssi(-80))
        assertEquals(Proximity.FAR, Proximity.fromRssi(-95))
    }

    @Test
    fun alertGateCooldownPerDevice() {
        val gate = AlertGate(cooldownMs = { 60_000 }, groupGapMs = 0)
        assertTrue(gate.shouldAlert("a", 0))
        assertFalse(gate.shouldAlert("a", 30_000))
        assertTrue(gate.shouldAlert("a", 61_000))
    }

    @Test
    fun alertGateGroupsDevicesSeenTogether() {
        val gate = AlertGate(cooldownMs = { 600_000 }, groupGapMs = 20_000)
        assertTrue(gate.shouldAlert("router", 0))
        // Body camera in the same car, seconds later: silent, and won't alert later either.
        assertFalse(gate.shouldAlert("bodycam", 5_000))
        assertFalse(gate.shouldAlert("bodycam", 40_000))
        // A different vehicle well after the gap does alert.
        assertTrue(gate.shouldAlert("other-car", 60_000))
    }
}
