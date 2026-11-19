package com.bleradar.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierTest {

    private fun ble(
        name: String? = null,
        maker: Map<Int, ByteArray> = emptyMap(),
        uuids: Set<Int> = emptySet(),
        serviceData: Map<Int, ByteArray> = emptyMap(),
    ) = Sighting(Source.BLE, "AA:BB:CC:DD:EE:FF", name, -70, 0L, maker, uuids, serviceData)

    private fun wifi(ssid: String) = Sighting(Source.WIFI, "11:22:33:44:55:66", ssid, -70, 0L)

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun airTagAwayFromOwnerIsHighThreat() {
        // Offline-finding record: type 0x12, length 0x19, then 25 bytes.
        val payload = bytes(0x12, 0x19, *IntArray(25) { it })
        val c = Classifier.classify(ble(maker = mapOf(Classifier.APPLE_ID to payload)))
        assertEquals(Kind.FINDMY_SEPARATED, c.kind)
        assertEquals(Threat.HIGH, c.kind.threat)
    }

    @Test
    fun findMyNearOwnerIsLowThreat() {
        val payload = bytes(0x12, 0x02, 0x00, 0x01)
        assertEquals(Kind.FINDMY_NEAR_OWNER, Classifier.classify(ble(maker = mapOf(Classifier.APPLE_ID to payload))).kind)
    }

    @Test
    fun appleRecordSkipsOtherRecords() {
        // Nearby-info record first, then offline finding.
        val payload = bytes(0x10, 0x03, 1, 2, 3, 0x12, 0x19, *IntArray(25))
        assertEquals(Kind.FINDMY_SEPARATED, Classifier.classify(ble(maker = mapOf(Classifier.APPLE_ID to payload))).kind)
    }

    @Test
    fun ordinaryAppleDevice() {
        val payload = bytes(0x10, 0x05, 1, 2, 3, 4, 5) // iPhone "nearby" broadcast
        assertEquals(Kind.APPLE, Classifier.classify(ble(maker = mapOf(Classifier.APPLE_ID to payload))).kind)
    }

    @Test
    fun truncatedAppleDataDoesNotCrash() {
        assertEquals(Kind.APPLE, Classifier.classify(ble(maker = mapOf(Classifier.APPLE_ID to bytes(0x10)))).kind)
        assertEquals(Kind.FINDMY_NEAR_OWNER, Classifier.classify(ble(maker = mapOf(Classifier.APPLE_ID to bytes(0x12, 0x19, 1)))).kind)
    }

    @Test
    fun otherTrackers() {
        assertEquals(Kind.TILE, Classifier.classify(ble(uuids = setOf(0xFEED))).kind)
        assertEquals(Kind.TILE, Classifier.classify(ble(serviceData = mapOf(0xFEEC to bytes(1)))).kind)
        assertEquals(Kind.SMARTTAG, Classifier.classify(ble(serviceData = mapOf(0xFD5A to bytes(1)))).kind)
        assertEquals(Kind.CHIPOLO, Classifier.classify(ble(uuids = setOf(0xFE33))).kind)
        assertEquals(Kind.GOOGLE_FIND, Classifier.classify(ble(serviceData = mapOf(0xFEAA to bytes(0x41, 0)))).kind)
    }

    @Test
    fun eddystoneBeaconIsNotATracker() {
        assertEquals(Kind.OTHER, Classifier.classify(ble(serviceData = mapOf(0xFEAA to bytes(0x10, 0)))).kind)
    }

    @Test
    fun cameraNames() {
        for (n in listOf("IPCAM_01", "V380-A1B2", "LookCam 5", "HD Camera", "CAM123", "spy cam", "Mini_Cam")) {
            assertEquals(n, Kind.CAMERA_BLE, Classifier.classify(ble(name = n)).kind)
        }
    }

    @Test
    fun notCameras() {
        for (n in listOf("Cambridge Audio", "Scampi", "JBL Flip 5", "Camaro", "Cam's iPhone", "Cam's AirPods", "Galaxy Watch")) {
            assertEquals(n, Kind.OTHER, Classifier.classify(ble(name = n)).kind)
        }
    }

    @Test
    fun cameraHotspots() {
        assertEquals(Kind.CAMERA_WIFI, Classifier.classify(wifi("V380-12345678")).kind)
        assertEquals(Kind.CAMERA_WIFI, Classifier.classify(wifi("GW_AP_1234")).kind)
        assertEquals(Kind.CAMERA_WIFI, Classifier.classify(wifi("HDWiFiCam-ABCD")).kind)
        assertEquals(Kind.OTHER, Classifier.classify(wifi("Galaxy A9 Hotspot")).kind)
        assertEquals(Kind.OTHER, Classifier.classify(wifi("NETGEAR42")).kind)
    }

    @Test
    fun wholeWords() {
        assertTrue(Classifier.nameHasWord("FIRE-CAM_2", listOf("cam")))
        assertFalse(Classifier.nameHasWord("camping", listOf("cam")))
        assertFalse(Classifier.nameHasWord(null, listOf("cam")))
    }
}

class SignalTest {

    @Test
    fun closenessIsClampedAndMonotonic() {
        assertEquals(0f, Signal.closeness(-120.0), 0f)
        assertEquals(1f, Signal.closeness(-20.0), 0f)
        assertTrue(Signal.closeness(-60.0) > Signal.closeness(-80.0))
    }

    @Test
    fun beepsGetFasterAndHigherWhenCloser() {
        assertTrue(Signal.beepIntervalMs(1f) < Signal.beepIntervalMs(0.5f))
        assertTrue(Signal.beepIntervalMs(0.5f) < Signal.beepIntervalMs(0f))
        assertEquals(80L, Signal.beepIntervalMs(1f))
        assertEquals(1400L, Signal.beepIntervalMs(0f))
        assertTrue(Signal.beepFrequencyHz(1f) > Signal.beepFrequencyHz(0f))
    }

    @Test
    fun distanceEstimate() {
        assertEquals(1.0, Signal.estimateMeters(-59.0, null), 0.01)
        assertTrue(Signal.estimateMeters(-80.0, null) > 5)
        assertEquals("under 1 m", Signal.formatMeters(0.4))
        assertEquals("about 2.5 m", Signal.formatMeters(2.4))
        assertEquals("about 3 m", Signal.formatMeters(3.1))
        assertEquals("about 15 m", Signal.formatMeters(15.2))
    }

    @Test
    fun trackerSmoothsSpikes() {
        val t = SignalTracker()
        listOf(-70, -70, -70, -40, -70).forEachIndexed { i, r -> t.add(r, i * 100L) }
        // A single spike is ignored by the median.
        assertEquals(-70.0, t.smoothed!!, 0.01)
    }

    @Test
    fun trendDetectsApproach() {
        val t = SignalTracker()
        var time = 0L
        repeat(20) { t.add(-85, time); time += 200 }
        repeat(8) { t.add(-60, time); time += 200 }
        assertEquals(Trend.WARMER, t.trend())
        repeat(8) { t.add(-90, time); time += 200 }
        assertEquals(Trend.COLDER, t.trend())
        repeat(40) { t.add(-90, time); time += 200 }
        assertEquals(Trend.STEADY, t.trend())
    }

    @Test
    fun lostAfterTimeout() {
        val t = SignalTracker()
        assertTrue(t.isLost(0))
        t.add(-60, 1000)
        assertFalse(t.isLost(5000))
        assertTrue(t.isLost(8000))
    }
}
