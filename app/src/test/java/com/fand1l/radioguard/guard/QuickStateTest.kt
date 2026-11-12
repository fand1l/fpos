package com.fand1l.radioguard.guard

import com.fand1l.radioguard.radio.Check
import com.fand1l.radioguard.radio.CheckStatus
import com.fand1l.radioguard.radio.Radio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickStateTest {

    private val quiet = listOf(Check(Radio.CELLULAR, CheckStatus.OK), Check(Radio.UWB, CheckStatus.UNKNOWN))
    private val loud = quiet + Check(Radio.BLUETOOTH, CheckStatus.VIOLATION)

    @Test
    fun `turning on goes through the confirmation`() {
        assertEquals(QuickAction.CONFIRM_ON, quickAction(guardOn = false, shizukuReady = true))
        assertEquals(GuardRequest.TURN_ON, QuickAction.CONFIRM_ON.request)
    }

    @Test
    fun `turning off always goes through the confirmation, with or without shizuku`() {
        assertEquals(QuickAction.CONFIRM_OFF, quickAction(guardOn = true, shizukuReady = true))
        assertEquals(QuickAction.CONFIRM_OFF, quickAction(guardOn = true, shizukuReady = false))
        assertEquals(GuardRequest.TURN_OFF, QuickAction.CONFIRM_OFF.request)
    }

    @Test
    fun `a request is confirmed only when it would change something`() {
        assertEquals(GuardRequest.TURN_ON, confirmationFor(GuardRequest.TURN_ON, guardOn = false))
        assertEquals(null, confirmationFor(GuardRequest.TURN_ON, guardOn = true))
        assertEquals(GuardRequest.TURN_OFF, confirmationFor(GuardRequest.TURN_OFF, guardOn = true))
        assertEquals(null, confirmationFor(GuardRequest.TURN_OFF, guardOn = false))
        assertEquals(null, confirmationFor(null, guardOn = true))
    }

    @Test
    fun `only emitting exceptions that are on count for the widget`() {
        val gps = Check(Radio.LOCATION, CheckStatus.ALLOWED, on = true)
        val btOff = Check(Radio.BLUETOOTH, CheckStatus.ALLOWED, on = false)
        val btOn = Check(Radio.BLUETOOTH, CheckStatus.ALLOWED, on = true)
        assertFalse(exceptionsOn(listOf(gps, btOff)))
        assertTrue(exceptionsOn(listOf(gps, btOn)))
        assertFalse(exceptionsOn(null))
    }

    @Test
    fun `without shizuku a tap opens the app instead of pretending to turn on`() {
        assertEquals(QuickAction.OPEN_APP, quickAction(guardOn = false, shizukuReady = false))
        assertEquals(null, QuickAction.OPEN_APP.request)
    }

    @Test
    fun `off is off whatever else is known`() {
        assertEquals(GuardSummary.OFF, guardSummary(guardOn = false, shizukuReady = false, checks = loud))
        assertEquals(GuardSummary.OFF, guardSummary(guardOn = false, shizukuReady = true, checks = null))
    }

    @Test
    fun `no shizuku outranks everything while the mode is on`() {
        assertEquals(GuardSummary.NO_SHELL, guardSummary(guardOn = true, shizukuReady = false, checks = quiet))
        assertEquals(GuardSummary.NO_SHELL, guardSummary(guardOn = true, shizukuReady = false, checks = null))
    }

    @Test
    fun `all quiet is claimed only after the guard has actually checked`() {
        assertEquals(GuardSummary.STARTING, guardSummary(guardOn = true, shizukuReady = true, checks = null))
        assertEquals(GuardSummary.OK, guardSummary(guardOn = true, shizukuReady = true, checks = quiet))
    }

    @Test
    fun `any violation is reported`() {
        assertEquals(GuardSummary.VIOLATION, guardSummary(guardOn = true, shizukuReady = true, checks = loud))
    }

    @Test
    fun `enabling shows as starting even before the setting is written`() {
        assertEquals(
            GuardSummary.STARTING,
            guardSummary(guardOn = false, shizukuReady = true, checks = null, enabling = true),
        )
    }

    @Test
    fun `quick state derives its action`() {
        val state = QuickState(guardOn = true, shizukuReady = true, summary = GuardSummary.OK)
        assertEquals(QuickAction.CONFIRM_OFF, state.action)
    }
}
