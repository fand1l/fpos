package com.fand1l.radioguard.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.fand1l.radioguard.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Застосунок не запустити на телефоні з CI, тому тут він стартує в емульованому Android:
 * так ловимо падіння на старті (ресурси, тема, ViewModel, DataStore) ще до встановлення APK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivitySmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `first launch explains itself, then shows the dashboard`() {
        waitForText("Як це працює")
        compose.onNodeWithText("Зрозуміло").performScrollTo().performClick()

        waitForText("Режим тиші")
        compose.onNodeWithText("Режим тиші").assertIsDisplayed()
        compose.onNodeWithText("Стан зараз").assertExists()
        compose.onNodeWithText("Стільникова мережа").assertExists()

        // Shizuku тут немає, тож увімкнути режим не можна, і про це сказано прямо.
        compose.onNodeWithText("Спочатку запустіть Shizuku і дайте доступ.").assertExists()
        compose.onAllNodes(isToggleable()).onFirst().assertIsNotEnabled()
    }
}
