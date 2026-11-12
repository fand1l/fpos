package com.fand1l.radioguard.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {

    private val present = listOf(
        Radio.CELLULAR, Radio.MOBILE_DATA, Radio.BLUETOOTH, Radio.BLE_SCAN, Radio.NFC,
        Radio.HOTSPOT, Radio.UWB, Radio.THREAD, Radio.LOCATION, Radio.WIFI, Radio.WIFI_NETWORK,
    )

    @Test
    fun `everything except exceptions and wifi is silenced`() {
        assertEquals(
            listOf(
                Radio.CELLULAR, Radio.MOBILE_DATA, Radio.BLUETOOTH, Radio.BLE_SCAN, Radio.NFC,
                Radio.HOTSPOT, Radio.UWB, Radio.THREAD,
            ),
            radiosToSilence(present, Radio.DEFAULT_ALLOWED, keepWifi = true),
        )
    }

    @Test
    fun `wifi is silenced too when it is not allowed`() {
        assertEquals(Radio.WIFI, radiosToSilence(present, Radio.DEFAULT_ALLOWED, keepWifi = false).last())
    }

    @Test
    fun `missing radios are not promised`() {
        val noNfc = present - Radio.NFC
        assertTrue(Radio.NFC !in radiosToSilence(noNfc, emptySet(), keepWifi = true))
    }

    @Test
    fun `restore lists only what will come back on`() {
        val snapshot = Snapshot(airplaneOn = false, bluetoothOn = true, nfcOn = false, uwbOn = null, locationOn = true)
        assertEquals(
            listOf(Radio.CELLULAR, Radio.BLUETOOTH, Radio.LOCATION),
            radiosToRestore(snapshot, currentlyOn = emptySet()),
        )
    }

    @Test
    fun `what is already on is not listed again`() {
        val snapshot = Snapshot(bluetoothOn = true, locationOn = true, wifiOn = true)
        assertEquals(
            listOf(Radio.BLUETOOTH),
            radiosToRestore(snapshot, currentlyOn = setOf(Radio.LOCATION, Radio.WIFI)),
        )
    }

    @Test
    fun `restore list matches the restore script`() {
        val everything = Snapshot(
            airplaneOn = false, mobileDataOn = true, bluetoothOn = true, bleScanOn = true,
            nfcOn = true, wifiOn = true, uwbOn = true, locationOn = true,
        )
        val script = Lockdown.restoreScript(everything)
        val listed = radiosToRestore(everything, currentlyOn = emptySet())
        val commandFor = mapOf(
            Radio.CELLULAR to "airplane-mode disable",
            Radio.MOBILE_DATA to "svc data enable",
            Radio.BLUETOOTH to "svc bluetooth enable",
            Radio.BLE_SCAN to "ble_scan_always_enabled 1",
            Radio.NFC to "svc nfc enable",
            Radio.UWB to "enable-uwb",
            Radio.LOCATION to "set-location-enabled true",
            Radio.WIFI to "set-wifi-enabled enabled",
        )
        assertEquals(commandFor.keys.toList(), listed)
        commandFor.values.forEach { assertTrue(it, script.contains(it)) }
    }

    @Test
    fun `nothing known, nothing restored`() {
        assertTrue(radiosToRestore(Snapshot(), currentlyOn = emptySet()).isEmpty())
    }
}
