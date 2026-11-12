package com.fand1l.radioguard.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStateTest {

    private val quiet = RadioState(
        airplaneOn = true,
        mobileDataOn = false,
        bluetoothOn = false,
        bleScanOn = false,
        hasNfc = true,
        nfcOn = false,
        hotspotOn = false,
        wifiOn = true,
    )
    private val quietProbe = ProbeState(
        uwbOn = false,
        threadOn = false,
        wifiScanAlways = false,
        wifiConnected = true,
        ssid = "Starlink",
    )

    private fun RadioState.violations(
        probe: ProbeState? = quietProbe,
        keepWifi: Boolean = true,
        allowedSsid: String = "",
        allowed: Set<Radio> = emptySet(),
    ) = checks(probe, keepWifi, allowedSsid, allowed).violations()

    private fun RadioState.status(radio: Radio, probe: ProbeState? = quietProbe, allowedSsid: String = "") =
        checks(probe, keepWifi = true, allowedSsid).first { it.radio == radio }.status

    @Test
    fun `quiet phone has no violations`() {
        assertTrue(quiet.violations().isEmpty())
    }

    @Test
    fun `airplane mode off is a cellular violation`() {
        assertEquals(setOf(Radio.CELLULAR), quiet.copy(airplaneOn = false).violations())
    }

    @Test
    fun `each radio that is on is reported`() {
        val loud = quiet.copy(mobileDataOn = true, bluetoothOn = true, bleScanOn = true, nfcOn = true, hotspotOn = true)
        assertEquals(
            setOf(Radio.MOBILE_DATA, Radio.BLUETOOTH, Radio.BLE_SCAN, Radio.NFC, Radio.HOTSPOT),
            loud.violations(),
        )
    }

    @Test
    fun `uwb comes from the shell probe`() {
        assertEquals(setOf(Radio.UWB), quiet.violations(probe = quietProbe.copy(uwbOn = true)))
        assertEquals(CheckStatus.UNKNOWN, quiet.status(Radio.UWB, probe = null))
    }

    @Test
    fun `radios the phone does not have get no row`() {
        val checks = quiet.copy(hasNfc = false, nfcOn = null)
            .checks(quietProbe.copy(uwbPresent = false, threadPresent = false), keepWifi = true, allowedSsid = "")
        val radios = checks.map { it.radio }
        assertFalse(Radio.NFC in radios)
        assertFalse(Radio.UWB in radios)
        assertFalse(Radio.THREAD in radios)
    }

    @Test
    fun `unreadable state is unknown, not a violation`() {
        val blind = quiet.copy(bluetoothOn = null, airplaneOn = null)
        assertTrue(blind.violations().isEmpty())
        assertEquals(CheckStatus.UNKNOWN, blind.status(Radio.BLUETOOTH))
        assertEquals(CheckStatus.UNKNOWN, blind.status(Radio.CELLULAR))
    }

    @Test
    fun `wifi must stay on when it is allowed`() {
        assertEquals(setOf(Radio.WIFI), quiet.copy(wifiOn = false).violations())
        assertTrue(quiet.checks(quietProbe, keepWifi = true, allowedSsid = "").first { it.radio == Radio.WIFI }.wantedOn)
    }

    @Test
    fun `wifi must be off when it is not allowed`() {
        assertEquals(setOf(Radio.WIFI), quiet.violations(keepWifi = false))
        assertTrue(quiet.copy(wifiOn = false).violations(keepWifi = false).isEmpty())
    }

    @Test
    fun `always-on wifi scanning counts as wifi when wifi is not allowed`() {
        val scanning = quietProbe.copy(wifiScanAlways = true)
        assertEquals(setOf(Radio.WIFI), quiet.copy(wifiOn = false).violations(probe = scanning, keepWifi = false))
        assertTrue(quiet.violations(probe = scanning, keepWifi = true).isEmpty())
    }

    @Test
    fun `empty allowed network means any network is fine`() {
        val checks = quiet.checks(quietProbe.copy(ssid = "Cafe"), keepWifi = true, allowedSsid = "  ")
        assertNull(checks.firstOrNull { it.radio == Radio.WIFI_NETWORK })
    }

    @Test
    fun `only the allowed network passes`() {
        assertTrue(quiet.violations(allowedSsid = "Starlink").isEmpty())
        assertEquals(setOf(Radio.WIFI_NETWORK), quiet.violations(allowedSsid = "Other"))
        assertEquals(setOf(Radio.WIFI_NETWORK), quiet.violations(allowedSsid = "starlink"))
    }

    @Test
    fun `allowed network name is trimmed`() {
        assertTrue(quiet.violations(allowedSsid = " Starlink ").isEmpty())
    }

    @Test
    fun `not connected is fine, unknown network is unknown`() {
        val offline = quietProbe.copy(wifiConnected = false, ssid = null)
        assertEquals(CheckStatus.OK, quiet.status(Radio.WIFI_NETWORK, offline, "Starlink"))
        val hidden = quietProbe.copy(wifiConnected = true, ssid = null)
        assertEquals(CheckStatus.UNKNOWN, quiet.status(Radio.WIFI_NETWORK, hidden, "Starlink"))
        assertEquals(CheckStatus.UNKNOWN, quiet.status(Radio.WIFI_NETWORK, null, "Starlink"))
    }

    @Test
    fun `network is not checked when wifi is not allowed`() {
        val checks = quiet.checks(quietProbe, keepWifi = false, allowedSsid = "Other")
        assertNull(checks.firstOrNull { it.radio == Radio.WIFI_NETWORK })
    }

    @Test
    fun `only mobile data and location are non-critical`() {
        assertEquals(listOf(Radio.MOBILE_DATA, Radio.LOCATION), Radio.entries.filterNot { it.critical })
    }

    @Test
    fun `allowed radios are never violations and keep their real state`() {
        val loud = quiet.copy(airplaneOn = false, bluetoothOn = true, locationOn = true)
        val allowed = setOf(Radio.CELLULAR, Radio.BLUETOOTH, Radio.LOCATION)
        val checks = loud.checks(quietProbe, keepWifi = true, allowedSsid = "", allowed = allowed)
        assertTrue(checks.violations().isEmpty())
        val bluetooth = checks.first { it.radio == Radio.BLUETOOTH }
        assertEquals(CheckStatus.ALLOWED, bluetooth.status)
        assertEquals(true, bluetooth.on)
        assertEquals(true, checks.first { it.radio == Radio.CELLULAR }.on)
        assertEquals(listOf(Radio.CELLULAR, Radio.BLUETOOTH, Radio.LOCATION), checks.allowedAndOn())
    }

    @Test
    fun `allowing one radio does not excuse the others`() {
        val loud = quiet.copy(bluetoothOn = true, nfcOn = true)
        assertEquals(setOf(Radio.NFC), loud.violations(allowed = setOf(Radio.BLUETOOTH)))
    }

    @Test
    fun `location is blocked only when it is not allowed`() {
        val located = quiet.copy(locationOn = true)
        assertEquals(setOf(Radio.LOCATION), located.violations(allowed = emptySet()))
        assertTrue(located.violations(allowed = Radio.DEFAULT_ALLOWED).isEmpty())
    }

    @Test
    fun `by default only location is allowed`() {
        assertEquals(setOf(Radio.LOCATION), Radio.DEFAULT_ALLOWED)
    }

    @Test
    fun `wifi is never an exception, it has its own setting`() {
        assertFalse(Radio.WIFI.allowable)
        assertFalse(Radio.WIFI_NETWORK.allowable)
        val checks = quiet.copy(wifiOn = false).checks(quietProbe, keepWifi = true, allowedSsid = "", allowed = setOf(Radio.WIFI))
        assertEquals(setOf(Radio.WIFI), checks.violations())
    }

    @Test
    fun `gps does not count as an emitting radio`() {
        assertFalse(Radio.LOCATION.emits)
        assertTrue(Radio.entries.filter { it != Radio.LOCATION }.all { it.emits })
    }

    @Test
    fun `snapshot keeps what was on before`() {
        val before = quiet.copy(airplaneOn = false, bluetoothOn = true, nfcOn = null)
        val snapshot = before.copy(locationOn = true).snapshot(quietProbe.copy(uwbOn = true))
        assertEquals(true, snapshot.locationOn)
        assertEquals(false, snapshot.airplaneOn)
        assertEquals(true, snapshot.bluetoothOn)
        assertNull(snapshot.nfcOn)
        assertEquals(true, snapshot.uwbOn)
        assertNull(before.snapshot(null).uwbOn)
    }
}
