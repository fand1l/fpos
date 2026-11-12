package com.fand1l.radioguard.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.fand1l.radioguard.radio.Radio
import com.fand1l.radioguard.radio.Snapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PrefsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun prefs() = Prefs(
        PreferenceDataStoreFactory.create(produceFile = { File(folder.root, "test.preferences_pb") }),
    )

    private val before = Snapshot(airplaneOn = false, bluetoothOn = true, nfcOn = null, wifiOn = true, uwbOn = true)
    private val locked = Snapshot(airplaneOn = true, bluetoothOn = false, nfcOn = false, wifiOn = true, uwbOn = false)

    @Test
    fun `defaults are off, wifi allowed, any network`() = runBlocking {
        val settings = prefs().current()
        assertFalse(settings.guardOn)
        assertTrue(settings.keepWifi)
        assertEquals("", settings.allowedSsid)
        assertFalse(settings.restorePending)
        assertFalse(settings.onboardingDone)
    }

    @Test
    fun `enabling stores the state from before`() = runBlocking {
        val prefs = prefs()
        prefs.enableGuard(before)
        assertTrue(prefs.current().guardOn)
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun `disabling asks for a restore, and a finished restore clears it`() = runBlocking {
        val prefs = prefs()
        prefs.enableGuard(before)
        prefs.disableGuard()
        assertFalse(prefs.current().guardOn)
        assertTrue(prefs.current().restorePending)
        prefs.restoreDone()
        assertFalse(prefs.current().restorePending)
    }

    @Test
    fun `disabling a mode that was never on asks for nothing`() = runBlocking {
        val prefs = prefs()
        prefs.disableGuard()
        assertFalse(prefs.current().restorePending)
    }

    @Test
    fun `re-enabling before the restore ran keeps the original snapshot`() = runBlocking {
        val prefs = prefs()
        prefs.enableGuard(before)
        prefs.disableGuard()
        // Радіо ще вимкнені: новий знімок описує вже заблокований стан.
        prefs.enableGuard(locked)
        assertEquals(before, prefs.snapshot())
        assertFalse(prefs.current().restorePending)
    }

    @Test
    fun `a fresh enable after a finished restore takes a new snapshot`() = runBlocking {
        val prefs = prefs()
        prefs.enableGuard(before)
        prefs.disableGuard()
        prefs.restoreDone()
        prefs.enableGuard(locked)
        assertEquals(locked, prefs.snapshot())
    }

    @Test
    fun `unknown snapshot values stay unknown`() = runBlocking {
        val prefs = prefs()
        prefs.enableGuard(locked)
        prefs.disableGuard()
        prefs.restoreDone()
        prefs.enableGuard(before)
        assertNull(prefs.snapshot().nfcOn)
    }

    @Test
    fun `only location is allowed by default`() = runBlocking {
        assertEquals(setOf(Radio.LOCATION), prefs().current().allowed)
    }

    @Test
    fun `exceptions can be added and removed`() = runBlocking {
        val prefs = prefs()
        prefs.setAllowed(Radio.BLUETOOTH, true)
        assertEquals(setOf(Radio.BLUETOOTH, Radio.LOCATION), prefs.current().allowed)
        prefs.setAllowed(Radio.LOCATION, false)
        prefs.setAllowed(Radio.BLUETOOTH, false)
        assertTrue(prefs.current().allowed.isEmpty())
    }

    @Test
    fun `wifi cannot become an exception`() = runBlocking {
        val prefs = prefs()
        prefs.setAllowed(Radio.WIFI, true)
        assertEquals(Radio.DEFAULT_ALLOWED, prefs.current().allowed)
    }

    @Test
    fun `turning off without restore leaves nothing to restore`() = runBlocking {
        val prefs = prefs()
        prefs.enableGuard(before)
        prefs.disableGuard(restore = false)
        assertFalse(prefs.current().guardOn)
        assertFalse(prefs.current().restorePending)
    }

    @Test
    fun `location state before is remembered`() = runBlocking {
        val prefs = prefs()
        prefs.enableGuard(Snapshot(locationOn = true))
        assertEquals(true, prefs.snapshot().locationOn)
    }

    @Test
    fun `allowed network is trimmed`() = runBlocking {
        val prefs = prefs()
        prefs.setAllowedSsid("  Starlink ")
        assertEquals("Starlink", prefs.current().allowedSsid)
    }
}
