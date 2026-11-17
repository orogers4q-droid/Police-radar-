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

    @Test
    fun axonPrefixMatchesOverBluetoothAndWifi_anyCaseOrSeparator() {
        assertEquals("axon_oui", Detector.match(sigs, ble("00:25:df:12:34:56"))?.id)
        assertEquals("axon_oui", Detector.match(sigs, wifi("00-25-DF-AA-BB-CC"))?.id)
    }

    @Test
    fun unrelatedDevicesDoNotMatch() {
        assertNull(Detector.match(sigs, ble("A4:C1:38:12:34:56", "JBL Flip 5")))
        assertNull(Detector.match(sigs, wifi("10:20:30:40:50:60", "Starbucks WiFi")))
    }

    @Test
    fun wifiNameRulesOnlyApplyToWifi() {
        assertEquals("police_keywords", Detector.match(sigs, wifi("10:20:30:40:50:60", "Unit12-POLICE"))?.id)
        assertNull(Detector.match(sigs, ble("10:20:30:40:50:60", "Unit12-POLICE")))
        assertEquals("cradlepoint_ssid", Detector.match(sigs, wifi("10:20:30:40:50:60", "IBR900-7d2"))?.id)
    }

    @Test
    fun strongestConfidenceWins() {
        // Axon MAC broadcasting a police-named network: HIGH beats MEDIUM.
        assertEquals("axon_oui", Detector.match(sigs, wifi("00:25:DF:00:00:01", "patrol car 7"))?.id)
    }

    @Test
    fun disabledSignaturesAreIgnored() {
        val disabled = sigs.map { if (it.id == "axon_oui") it.copy(enabled = false) else it }
        assertNull(Detector.match(disabled, ble("00:25:DF:12:34:56")))
    }

    @Test
    fun customBleNameSignature() {
        val custom = Signature("c", "Mine", "", MatchType.BLE_NAME, listOf("Signal Sidearm"), Confidence.HIGH)
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
        // A different car well after the gap does alert.
        assertTrue(gate.shouldAlert("other-car", 60_000))
    }
}
