package com.fand1l.radioguard.ui

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.fand1l.radioguard.App
import com.fand1l.radioguard.MainActivity
import com.fand1l.radioguard.guard.GuardRequest
import com.fand1l.radioguard.radio.Radio
import com.fand1l.radioguard.radio.Snapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Будь-яка зміна режиму — лише через підтвердження. Тут застосунок стартує по-справжньому,
 * але без Shizuku: увімкнути режим не можна, вимкнути — можна.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfirmTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = (context as App).prefs

    private fun isShown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // Умова щоразу опитує екран: це ще й дає емульованому головному потоку виконати відкладену роботу.
    private fun waitUntilShown(text: String) = compose.waitUntil(timeoutMillis = 10_000) { isShown(text) }

    private fun waitUntilGone(text: String) = compose.waitUntil(timeoutMillis = 10_000) { !isShown(text) }

    private fun launch(guardOn: Boolean, request: GuardRequest?, snapshot: Snapshot = Snapshot()): ActivityScenario<MainActivity> {
        runBlocking {
            prefs.setOnboardingDone()
            if (guardOn) prefs.enableGuard(snapshot)
        }
        return ActivityScenario.launch(MainActivity.intent(context, request))
    }

    // UWB тут не читається (немає Shizuku), тож у списку «знову ввімкнеться» він буде напевно.
    private val uwbWasOn = Snapshot(uwbOn = true)

    @Test
    fun `cancel leaves the mode on`() {
        launch(guardOn = true, request = GuardRequest.TURN_OFF).use {
            waitUntilShown(OFF_QUESTION)
            compose.onNodeWithText("Скасувати").performClick()
            waitUntilGone(OFF_QUESTION)
            assertTrue(runBlocking { prefs.current().guardOn })
        }
    }

    @Test
    fun `turning off can keep the radios off`() {
        launch(guardOn = true, request = GuardRequest.TURN_OFF, snapshot = uwbWasOn).use {
            waitUntilShown(OFF_QUESTION)
            compose.onNodeWithText("Якщо повернути як було, знову ввімкнеться: UWB.").assertExists()
            compose.onNodeWithText("Вимкнути, радіо не вмикати").performClick()
            waitUntilShown(MODE_OFF_WITHOUT_SHIZUKU)

            assertFalse(runBlocking { prefs.current().guardOn })
            assertFalse(runBlocking { prefs.current().restorePending })
            assertFalse(isShown(OFF_QUESTION))
            assertFalse(isShown("Попередній стан не повернуто"))
        }
    }

    @Test
    fun `turning off can restore the state from before`() {
        launch(guardOn = true, request = GuardRequest.TURN_OFF, snapshot = uwbWasOn).use {
            waitUntilShown(OFF_QUESTION)
            compose.onNodeWithText("Вимкнути й повернути як було").performClick()
            // Shizuku тут немає, тож повернути стан нічим — про це має сказати картка.
            waitUntilShown("Попередній стан не повернуто")
            assertTrue(runBlocking { prefs.current().restorePending })
        }
    }

    @Test
    fun `with nothing to restore there is a single turn-off button`() {
        launch(guardOn = true, request = GuardRequest.TURN_OFF).use {
            waitUntilShown(OFF_QUESTION)
            assertFalse(isShown("Вимкнути й повернути як було"))
            compose.onNodeWithText("Вимкнути").performClick()
            waitUntilShown(MODE_OFF_WITHOUT_SHIZUKU)
            assertFalse(runBlocking { prefs.current().guardOn })
        }
    }

    @Test
    fun `the switch in the app asks before turning off`() {
        launch(guardOn = true, request = null).use {
            waitUntilShown("Режим тиші")
            compose.onAllNodes(isToggleable()).onFirst().performClick()
            waitUntilShown(OFF_QUESTION)
            assertTrue(runBlocking { prefs.current().guardOn })
        }
    }

    @Test
    fun `turning on from the tile asks first and waits for shizuku`() {
        launch(guardOn = false, request = GuardRequest.TURN_ON).use {
            waitUntilShown(ON_QUESTION)
            compose.onNodeWithText("Винятки, їх не вимикатиму: Геолокація (GPS).").assertExists()
            compose.onNodeWithText("Shizuku ще не готовий. Кнопка запрацює, щойно він підключиться.").assertExists()
            compose.onNodeWithText("Увімкнути").assertIsNotEnabled()

            compose.onNodeWithText("Скасувати").performClick()
            waitUntilGone(ON_QUESTION)
            assertFalse(runBlocking { prefs.current().guardOn })
        }
    }

    @Test
    fun `nothing is asked when the mode is already what was requested`() {
        launch(guardOn = false, request = GuardRequest.TURN_OFF).use {
            waitUntilShown("Режим тиші")
            compose.waitForIdle()
            assertFalse(isShown(OFF_QUESTION))
        }
    }

    @Test
    fun `an exception is saved and shown in the state list`() {
        launch(guardOn = false, request = null).use {
            waitUntilShown("Режим тиші")
            val bluetoothException = isToggleable() and hasText("Bluetooth")
            compose.onNode(hasScrollAction()).performScrollToNode(bluetoothException)
            compose.onNode(bluetoothException).assertIsOff().performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(bluetoothException and isOn()).fetchSemanticsNodes().isNotEmpty()
            }

            val allowed = runBlocking { prefs.current().allowed }
            assertTrue(Radio.BLUETOOTH in allowed)
            assertTrue(Radio.LOCATION in allowed)
        }
    }

    private companion object {
        const val ON_QUESTION = "Увімкнути режим тиші?"
        const val OFF_QUESTION = "Вимкнути режим тиші?"

        // Так картка режиму виглядає, коли режим вимкнено, а Shizuku немає.
        const val MODE_OFF_WITHOUT_SHIZUKU = "Спочатку запустіть Shizuku і дайте доступ."
    }
}
