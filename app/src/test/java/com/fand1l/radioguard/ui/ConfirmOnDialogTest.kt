package com.fand1l.radioguard.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fand1l.radioguard.MainActivity
import com.fand1l.radioguard.guard.GuardRequest
import com.fand1l.radioguard.radio.Check
import com.fand1l.radioguard.radio.CheckStatus
import com.fand1l.radioguard.radio.Radio
import com.fand1l.radioguard.shizuku.PrivilegedShell
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Справжнього Shizuku тут немає, тож екран отримує готовий стан «Shizuku працює». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfirmOnDialogTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private class Recorder : MainActions {
        val calls = mutableListOf<String>()
        override fun onRequest(request: GuardRequest) { calls += "request $request" }
        override fun onDismissRequest() { calls += "dismiss" }
        override fun onConfirmOn() { calls += "on" }
        override fun onConfirmOff(restore: Boolean) { calls += "off restore=$restore" }
        override fun onAllowChange(radio: Radio, allowed: Boolean) { calls += "allow $radio $allowed" }
        override fun onKeepWifiChange(keep: Boolean) = Unit
        override fun onSsidSave(ssid: String) = Unit
        override fun onShizukuGrant() = Unit
        override fun onRestoreRetry() = Unit
        override fun onOnboardingDone() = Unit
    }

    private val checks = listOf(
        Check(Radio.CELLULAR, CheckStatus.VIOLATION, on = true),
        Check(Radio.BLUETOOTH, CheckStatus.ALLOWED, on = true),
        Check(Radio.NFC, CheckStatus.VIOLATION, on = true),
        Check(Radio.LOCATION, CheckStatus.ALLOWED, on = true),
        Check(Radio.WIFI, CheckStatus.OK, on = true, wantedOn = true),
    )

    private fun show(state: MainUiState, actions: MainActions) {
        compose.activity.setContent {
            RadioGuardTheme {
                MainScreen(state, true, canAddTile = false, canAddWidget = false, actions = actions, {}, {}, {}, {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the dialog says what goes off and what stays, and turns on only on confirm`() {
        val actions = Recorder()
        show(
            MainUiState(
                loaded = true,
                shizuku = PrivilegedShell.Status.READY,
                checks = checks,
                allowed = setOf(Radio.BLUETOOTH, Radio.LOCATION),
                request = GuardRequest.TURN_ON,
            ),
            actions,
        )
        compose.onNodeWithText("Буде вимкнено: Стільникова мережа, NFC.").assertExists()
        compose.onNodeWithText("Винятки, їх не вимикатиму: Bluetooth, Геолокація (GPS).").assertExists()
        compose.onNodeWithText("Wi-Fi лишається ввімкненим.").assertExists()
        assertEquals(emptyList<String>(), actions.calls)

        compose.onNodeWithText("Увімкнути").assertIsEnabled().performClick()
        assertEquals(listOf("on"), actions.calls)
    }

    @Test
    fun `the switch only asks, it does not turn on by itself`() {
        val actions = Recorder()
        show(MainUiState(loaded = true, shizuku = PrivilegedShell.Status.READY, checks = checks), actions)
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        assertEquals(listOf("request TURN_ON"), actions.calls)
    }
}
