package dev.arnv.bluke.ui

import android.bluetooth.BluetoothClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostListPolicyTest {
    private val buds = HostRef("AA", "Galaxy Buds", bonded = true, type = DeviceType.AUDIO)
    private val mac = HostRef("BB", "Rick's MacBook", bonded = true, type = DeviceType.COMPUTER)

    @Test
    fun aPairedHostOfAnUnlikelyClassIsListedWithHideUnlikelyOn() {
        // C9: the list and the row used to disagree on "supported", hiding a paired host by default.
        val speaker = HostRef("CC", "Kitchen speaker", bonded = false, type = DeviceType.AUDIO)
        val (paired, nearby) = hostLists(listOf(buds, mac), listOf(speaker), hideUnnamed = true, hideUnlikely = true)
        assertEquals(listOf(buds, mac), paired)
        assertEquals(emptyList<HostRef>(), nearby)
        assertTrue(isLikelyHost(buds))
    }

    @Test
    fun hideSettingsFilterNearbyOnly() {
        val speaker = HostRef("CC", "Kitchen speaker", bonded = false, type = DeviceType.AUDIO)
        val unnamed = HostRef("DD", null, bonded = false)
        val hidSpeaker = speaker.copy(address = "EE", hidCapable = true)
        val tv = HostRef("FF", "Living Room TV", bonded = false, type = DeviceType.TV)
        val scanned = listOf(speaker, unnamed, hidSpeaker, tv, mac)
        assertEquals(listOf(hidSpeaker, tv), nearbyHosts(scanned, setOf(mac.address), hideUnnamed = true, hideUnlikely = true))
        assertEquals(listOf(speaker, unnamed, hidSpeaker, tv), nearbyHosts(scanned, setOf(mac.address), hideUnnamed = false, hideUnlikely = false))
    }

    @Test
    fun likelyMeansPairedHidOrAKeyboardTakingClass() {
        assertFalse(isLikelyHost(HostRef("1", "Buds", bonded = false, type = DeviceType.AUDIO)))
        assertTrue(isLikelyHost(HostRef("1", "Buds", bonded = false, hidCapable = true, type = DeviceType.AUDIO)))
        assertTrue(isLikelyHost(HostRef("1", "PC", bonded = false, type = DeviceType.COMPUTER)))
    }

    @Test
    fun classifiesByNameThenClass() {
        assertEquals(DeviceType.COMPUTER, classifyHost("Rick's MacBook Pro", null, null))
        assertEquals(DeviceType.TV, classifyHost("Living Room Chromecast", null, null))
        assertEquals(DeviceType.GAMEPAD, classifyHost("Xbox Wireless Controller", BluetoothClass.Device.Major.PERIPHERAL, null))
        assertEquals(DeviceType.MOUSE, classifyHost("MX Master 3", null, null))
        assertEquals(DeviceType.COMPUTER, classifyHost("DESKTOP-K16", null, null))
        assertEquals(DeviceType.COMPUTER, classifyHost(null, BluetoothClass.Device.Major.COMPUTER, null))
        assertEquals(DeviceType.DEFAULT, classifyHost("Zorp", null, null))
    }
}
