package com.fand1l.radioguard.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockdownTest {

    private fun lines(script: String) = script.lines().filter { it.isNotEmpty() }

    @Test
    fun `no violations give an empty script`() {
        assertEquals("", Lockdown.lockScript(emptySet(), keepWifi = true))
    }

    @Test
    fun `script contains only commands for the violated radios`() {
        assertEquals(
            listOf("svc bluetooth disable"),
            lines(Lockdown.lockScript(setOf(Radio.BLUETOOTH), keepWifi = true)),
        )
    }

    @Test
    fun `wifi is not touched without need`() {
        val script = Lockdown.lockScript(
            setOf(Radio.BLUETOOTH, Radio.NFC, Radio.MOBILE_DATA, Radio.BLE_SCAN, Radio.UWB, Radio.HOTSPOT),
            keepWifi = true,
        )
        assertFalse(script.contains("set-wifi-enabled"))
    }

    @Test
    fun `airplane mode comes first, then a pause, then wifi`() {
        val script = lines(Lockdown.lockScript(setOf(Radio.CELLULAR, Radio.BLUETOOTH), keepWifi = true))
        assertEquals("cmd connectivity airplane-mode enable", script.first())
        assertEquals(listOf("sleep 1", "cmd wifi set-wifi-enabled enabled"), script.takeLast(2))
    }

    @Test
    fun `wifi is switched back on when it was turned off`() {
        assertEquals(
            listOf("cmd wifi set-wifi-enabled enabled"),
            lines(Lockdown.lockScript(setOf(Radio.WIFI), keepWifi = true)),
        )
    }

    @Test
    fun `wifi and its background scanning are switched off when wifi is not allowed`() {
        assertEquals(
            listOf("cmd wifi set-wifi-enabled disabled", "cmd wifi set-scan-always-available disabled"),
            lines(Lockdown.lockScript(setOf(Radio.WIFI), keepWifi = false)),
        )
        val withAirplane = Lockdown.lockScript(setOf(Radio.CELLULAR), keepWifi = false)
        assertFalse(withAirplane.contains("set-wifi-enabled"))
    }

    @Test
    fun `every radio has its command`() {
        val script = lines(Lockdown.lockScript(Radio.entries.toSet(), keepWifi = true))
        assertTrue("svc data disable" in script)
        assertTrue("svc bluetooth disable" in script)
        assertTrue("settings put global ble_scan_always_enabled 0" in script)
        assertTrue("svc nfc disable" in script)
        assertTrue("cmd wifi stop-softap" in script)
        assertTrue("cmd uwb disable-uwb" in script)
        assertTrue("cmd thread_network disable" in script)
    }

    @Test
    fun `restore turns back on only what was on`() {
        val snapshot = Snapshot(
            airplaneOn = false,
            mobileDataOn = true,
            bluetoothOn = true,
            bleScanOn = false,
            nfcOn = true,
            wifiOn = true,
            uwbOn = false,
        )
        val script = lines(Lockdown.restoreScript(snapshot))
        assertEquals(
            listOf(
                "svc nfc enable",
                "svc data enable",
                "cmd connectivity airplane-mode disable",
                "sleep 1",
                "svc bluetooth enable",
                "cmd wifi set-wifi-enabled enabled",
            ),
            script,
        )
    }

    @Test
    fun `restore leaves airplane mode on if it was on before`() {
        val script = Lockdown.restoreScript(Snapshot(airplaneOn = true, wifiOn = false))
        assertFalse(script.contains("airplane-mode"))
        assertTrue(script.contains("cmd wifi set-wifi-enabled disabled"))
    }

    @Test
    fun `restore with an unknown snapshot does nothing`() {
        assertEquals("", Lockdown.restoreScript(Snapshot()))
    }

    @Test
    fun `restore keeps the battery exemption that lets the tile and widget start the guard`() {
        val everythingOn = Snapshot(true, true, true, true, true, true, true)
        assertFalse(Lockdown.restoreScript(everythingOn).contains("deviceidle"))
        assertEquals("cmd deviceidle whitelist +com.example", Lockdown.setupScript("com.example"))
    }

    @Test
    fun `restore never turns the hotspot or thread back on`() {
        val everythingOn = Snapshot(true, true, true, true, true, true, true)
        val script = Lockdown.restoreScript(everythingOn)
        assertFalse(script.contains("softap"))
        assertFalse(script.contains("thread_network"))
    }

    @Test
    fun `forget script has one command per network`() {
        assertEquals(
            listOf("cmd wifi forget-network 3", "cmd wifi forget-network 7"),
            lines(Lockdown.forgetNetworksScript(listOf(3, 7))),
        )
    }

    @Test
    fun `probe script only reads`() {
        val script = Lockdown.probeScript()
        assertTrue(script.contains("cmd uwb status"))
        assertTrue(script.contains("cmd wifi status"))
        assertFalse(script.contains("disable"))
    }

    @Test
    fun `slow script asks about wifi last`() {
        val script = Lockdown.slowScript()
        assertTrue(script.indexOf("#thread") < script.indexOf("#uwb"))
        assertTrue(script.indexOf("#uwb") < script.indexOf("#wifi"))
    }
}
