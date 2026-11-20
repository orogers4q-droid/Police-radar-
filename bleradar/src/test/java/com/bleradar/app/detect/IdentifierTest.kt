package com.bleradar.app.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class IdentifierTest {

    private fun ble(
        name: String? = null,
        maker: Map<Int, ByteArray> = emptyMap(),
        uuids: Set<Int> = emptySet(),
    ) = Sighting(Source.BLE, "AA:BB:CC:DD:EE:FF", name, -70, 0L, maker, uuids)

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun id(s: Sighting) = Identifier.identify(s, Classifier.classify(s).kind)

    @Test
    fun airPodsModelFromAppleBroadcast() {
        // Proximity pairing record: type 0x07, then prefix 0x01 and model 0x0E20 (AirPods Pro).
        val data = bytes(0x07, 0x19, 0x01, 0x0E, 0x20, *IntArray(22))
        val i = id(ble(maker = mapOf(Classifier.APPLE_ID to data)))
        assertEquals("AirPods Pro", i.what)
        assertEquals("Apple", i.maker)
    }

    @Test
    fun unknownAppleAudioModelFallsBack() {
        val data = bytes(0x07, 0x19, 0x01, 0x7F, 0x7F, *IntArray(22))
        assertEquals("AirPods or Beats headphones", id(ble(maker = mapOf(Classifier.APPLE_ID to data))).what)
    }

    @Test
    fun iphoneNearbyInfo() {
        val data = bytes(0x10, 0x05, 1, 2, 3, 4, 5)
        assertEquals("iPhone, iPad, Mac or Apple Watch", id(ble(maker = mapOf(Classifier.APPLE_ID to data))).what)
    }

    @Test
    fun appleNameBeatsVagueType() {
        val data = bytes(0x10, 0x05, 1, 2, 3, 4, 5)
        assertEquals("Smartwatch or fitness band", id(ble(name = "Sam's Apple Watch", maker = mapOf(Classifier.APPLE_ID to data))).what)
    }

    @Test
    fun serviceIds() {
        assertEquals("Keyboard, mouse or remote", id(ble(uuids = setOf(0x1812))).what)
        assertEquals("Heart-rate monitor or fitness band", id(ble(uuids = setOf(0x180D))).what)
        assertEquals("Headphones or earbuds (Google Fast Pair)", id(ble(uuids = setOf(0xFE2C))).what)
    }

    @Test
    fun nameHints() {
        assertEquals("Headphones or earbuds", id(ble(name = "Galaxy Buds2 Pro")).what)
        assertEquals("Car", id(ble(name = "Tesla Model 3")).what)
        assertEquals("TV or streaming device", id(ble(name = "[TV] Samsung 7 Series")).what)
        assertEquals("Electric toothbrush", id(ble(name = "Oral-B Toothbrush")).what)
    }

    @Test
    fun companyFallback() {
        val samsung = id(ble(maker = mapOf(0x0075 to bytes(1, 2))))
        assertEquals("Samsung phone, watch, earbuds or TV", samsung.what)
        assertEquals("Samsung", samsung.maker)
        val chip = id(ble(maker = mapOf(0x0059 to bytes(1))))
        assertEquals("Gadget with a Nordic Semiconductor Bluetooth chip", chip.what)
    }

    @Test
    fun namePlusMaker() {
        val i = id(ble(name = "Buds Live", maker = mapOf(0x0075 to bytes(1))))
        assertEquals("Headphones or earbuds", i.what)
        assertEquals("Headphones or earbuds · Samsung", i.line)
    }

    @Test
    fun unknown() {
        assertEquals("Unknown Bluetooth device", id(ble()).what)
        assertEquals("Bluetooth device", id(ble(name = "XK-2291")).what)
    }

    @Test
    fun trackersKeepTheirLabel() {
        assertEquals("Tile tracker", id(ble(uuids = setOf(0xFEED))).what)
    }
}
