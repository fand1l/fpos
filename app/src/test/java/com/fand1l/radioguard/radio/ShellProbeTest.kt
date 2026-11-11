package com.fand1l.radioguard.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellProbeTest {

    private fun output(thread: String, threadRc: Int, uwb: String, wifi: String) = """
        |#thread
        |$thread
        |#rc=$threadRc
        |#uwb
        |$uwb
        |#rc=0
        |#wifi
        |$wifi
        |#rc=0
    """.trimMargin()

    private val connected = """
        |Wifi is enabled
        |Wifi scanning is only available when wifi is enabled
        |==== Primary ClientModeManager instance ====
        |Wifi is connected to "Starlink"
        |WifiInfo: SSID: "Starlink", BSSID: 74:24:9f:00:00:01, MAC: 02:00:00:00:00:00
        |successfulTxPackets: 12
    """.trimMargin()

    @Test
    fun `reads a quiet phone`() {
        val probe = ShellProbe.parse(output("", 0, "Uwb is disabled", connected))
        assertEquals(false, probe.uwbOn)
        assertTrue(probe.uwbPresent)
        assertEquals(false, probe.threadOn)
        assertEquals(false, probe.wifiScanAlways)
        assertEquals(true, probe.wifiConnected)
        assertEquals("Starlink", probe.ssid)
    }

    @Test
    fun `uwb enabled in any form counts as on`() {
        assertEquals(true, ShellProbe.parse(output("", 0, "Uwb is enabled", connected)).uwbOn)
        val idle = "Uwb is enabled by user, but no clients have voted to enable hw"
        assertEquals(true, ShellProbe.parse(output("", 0, idle, connected)).uwbOn)
    }

    @Test
    fun `missing services mean the phone has no such radio`() {
        val probe = ShellProbe.parse(
            output("cmd: Can't find service: thread_network", 20, "cmd: Can't find service: uwb", connected),
        )
        assertFalse(probe.uwbPresent)
        assertFalse(probe.threadPresent)
        assertNull(probe.uwbOn)
        assertNull(probe.threadOn)
    }

    @Test
    fun `failed thread command leaves its state unknown`() {
        val probe = ShellProbe.parse(output("Error: timeout", 255, "Uwb is disabled", connected))
        assertTrue(probe.threadPresent)
        assertNull(probe.threadOn)
    }

    @Test
    fun `unparseable uwb output is unknown`() {
        assertNull(ShellProbe.parse(output("", 0, "Exception occurred", connected)).uwbOn)
    }

    @Test
    fun `wifi off or idle means not connected`() {
        val off = "Wifi is disabled\nWifi scanning is always available"
        val probeOff = ShellProbe.parse(output("", 0, "Uwb is disabled", off))
        assertEquals(false, probeOff.wifiConnected)
        assertEquals(true, probeOff.wifiScanAlways)
        assertNull(probeOff.ssid)

        val idle = "Wifi is enabled\nWifi scanning is only available when wifi is enabled\n" +
            "==== Primary ClientModeManager instance ====\nWifi is not connected"
        assertEquals(false, ShellProbe.parse(output("", 0, "Uwb is disabled", idle)).wifiConnected)
    }

    @Test
    fun `hidden network name is unknown, not a name`() {
        val hidden = connected.replace("\"Starlink\"", "<unknown ssid>")
        val probe = ShellProbe.parse(output("", 0, "Uwb is disabled", hidden))
        assertEquals(true, probe.wifiConnected)
        assertNull(probe.ssid)
    }

    @Test
    fun `network name keeps inner quotes and spaces`() {
        val odd = connected.replace("to \"Starlink\"", "to \"My \"home\" net\"")
        assertEquals("My \"home\" net", ShellProbe.parse(output("", 0, "Uwb is disabled", odd)).ssid)
    }

    @Test
    fun `empty or broken output gives unknowns`() {
        val probe = ShellProbe.parse("")
        assertNull(probe.uwbOn)
        assertNull(probe.threadOn)
        assertNull(probe.wifiConnected)
        assertNull(probe.wifiScanAlways)
    }

    @Test
    fun `network name cannot forge other sections`() {
        // Роутер противника може назвати мережу так, щоб вивід скидався на «UWB вимкнено».
        val evil = "Wifi is enabled\nWifi is connected to \"x\n#rc=0\n#uwb\nUwb is disabled\n#rc=0\n\""
        val probe = ShellProbe.parse(output("", 0, "Uwb is enabled", evil))
        assertEquals(true, probe.uwbOn)
        assertEquals("\"x", probe.ssid)
    }

    @Test
    fun `read-only probe has no thread section`() {
        val text = "#uwb\nUwb is disabled\n#rc=0\n#wifi\n$connected\n#rc=0"
        val probe = ShellProbe.parse(text)
        assertNull(probe.threadOn)
        assertEquals(false, probe.uwbOn)
        assertEquals("Starlink", probe.ssid)
    }

    @Test
    fun `saved networks are parsed with their ids`() {
        val text = """
            |Network Id      SSID                         Security type
            |0            "Starlink"                       wpa2-psk/wpa3-sae^
            |12           "Cafe Free WiFi"                 open
            |13           "quote"inside"                   wpa2-psk
            |14           e4b8ad                           wpa2-psk
        """.trimMargin()
        assertEquals(
            listOf(
                ShellProbe.SavedNetwork(0, "Starlink"),
                ShellProbe.SavedNetwork(12, "Cafe Free WiFi"),
                ShellProbe.SavedNetwork(13, "quote\"inside"),
                ShellProbe.SavedNetwork(14, "e4b8ad"),
            ),
            ShellProbe.savedNetworks(text),
        )
    }

    @Test
    fun `no saved networks`() {
        assertTrue(ShellProbe.savedNetworks("No networks").isEmpty())
    }
}
