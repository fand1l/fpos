package com.fand1l.radioguard.ui

import android.content.Context
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.fand1l.radioguard.App
import com.fand1l.radioguard.MainActivity
import com.fand1l.radioguard.radio.Snapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Плитка й віджет не вимикають режим самі: вони відкривають застосунок із цим запитанням. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfirmOffTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = (context as App).prefs

    private fun isShown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // Умова щоразу опитує екран: це ще й дає емульованому головному потоку виконати відкладену роботу.
    private fun waitUntilShown(text: String) = compose.waitUntil(timeoutMillis = 10_000) { isShown(text) }

    private fun waitUntilGone(text: String) = compose.waitUntil(timeoutMillis = 10_000) { !isShown(text) }

    private fun launchAskingToTurnOff(guardOn: Boolean): ActivityScenario<MainActivity> {
        runBlocking {
            prefs.setOnboardingDone()
            if (guardOn) prefs.enableGuard(Snapshot())
        }
        return ActivityScenario.launch(MainActivity.intent(context, confirmOff = true))
    }

    @Test
    fun `cancel leaves the mode on`() {
        launchAskingToTurnOff(guardOn = true).use {
            waitUntilShown(QUESTION)
            compose.onNodeWithText("Скасувати").performClick()
            waitUntilGone(QUESTION)

            assertTrue(runBlocking { prefs.current().guardOn })
        }
    }

    @Test
    fun `confirm turns the mode off and asks for the restore`() {
        launchAskingToTurnOff(guardOn = true).use {
            waitUntilShown(QUESTION)
            compose.onNodeWithText("Вимкнути").performClick()
            // Shizuku тут немає, тож повернути стан нічим — про це має сказати картка.
            waitUntilShown("Попередній стан не повернуто")

            val settings = runBlocking { prefs.current() }
            assertFalse(settings.guardOn)
            assertTrue(settings.restorePending)
            assertFalse(isShown(QUESTION))
        }
    }

    @Test
    fun `nothing is asked when the mode is already off`() {
        launchAskingToTurnOff(guardOn = false).use {
            waitUntilShown("Режим тиші")
            compose.waitForIdle()
            assertFalse(isShown(QUESTION))

            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Швидкий доступ"))
            compose.onNodeWithText("Додати плитку").assertExists()
        }
    }

    private companion object {
        const val QUESTION = "Вимкнути режим тиші?"
    }
}
