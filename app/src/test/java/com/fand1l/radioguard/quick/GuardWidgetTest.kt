package com.fand1l.radioguard.quick

import android.app.PendingIntent
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.fand1l.radioguard.MainActivity
import com.fand1l.radioguard.R
import com.fand1l.radioguard.guard.GuardRequest
import com.fand1l.radioguard.guard.GuardSummary
import com.fand1l.radioguard.guard.QuickState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Віджет малює не наш процес, а лаунчер, і бере він лише дозволені види екранних елементів.
 * Помилку в розмітці видно тільки на телефоні («не вдалося завантажити віджет»), тому розгортаємо її тут.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuardWidgetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun inflate(state: QuickState): View =
        GuardWidget.views(context, state).apply(context, FrameLayout(context))

    private fun statusOf(state: QuickState): String =
        inflate(state).findViewById<TextView>(R.id.widget_status).text.toString()

    private fun clickOf(state: QuickState): PendingIntent {
        val root = inflate(state).findViewById<View>(R.id.widget_root)
        return GuardWidgetClicks.pendingIntentOf(root)
    }

    private fun state(guardOn: Boolean, ready: Boolean, summary: GuardSummary) = QuickState(guardOn, ready, summary)

    @Test
    fun `every state inflates and names itself`() {
        assertEquals("Вимкнено. Торкніться.", statusOf(state(false, true, GuardSummary.OFF)))
        assertEquals("Немає Shizuku", statusOf(state(false, false, GuardSummary.OFF)))
        assertEquals("Вмикається…", statusOf(state(false, true, GuardSummary.STARTING)))
        assertEquals("Усе вимкнено", statusOf(state(true, true, GuardSummary.OK)))
        assertEquals("Є винятки", statusOf(QuickState(true, true, GuardSummary.OK, exceptionsOn = true)))
        assertEquals("Є порушення!", statusOf(state(true, true, GuardSummary.VIOLATION)))
        assertEquals("НЕ контролюється!", statusOf(state(true, false, GuardSummary.NO_SHELL)))
    }

    @Test
    fun `tap on a switched-off widget opens the confirmation to turn on`() {
        val click = shadowOf(clickOf(state(false, true, GuardSummary.OFF)))
        assertTrue(click.isActivityIntent)
        assertEquals(MainActivity::class.java.name, click.savedIntent.component?.className)
        assertEquals(MainActivity.intent(context, GuardRequest.TURN_ON).action, click.savedIntent.action)
    }

    @Test
    fun `tap on a switched-on widget opens the confirmation to turn off`() {
        for (summary in listOf(GuardSummary.OK, GuardSummary.VIOLATION, GuardSummary.NO_SHELL)) {
            val click = shadowOf(clickOf(state(true, summary != GuardSummary.NO_SHELL, summary)))
            assertTrue(click.isActivityIntent)
            assertEquals(MainActivity.intent(context, GuardRequest.TURN_OFF).action, click.savedIntent.action)
        }
    }

    @Test
    fun `without shizuku a tap just opens the app`() {
        val click = shadowOf(clickOf(state(false, false, GuardSummary.OFF)))
        assertTrue(click.isActivityIntent)
        assertEquals(MainActivity.intent(context, null).action, click.savedIntent.action)
    }
}

/** RemoteViews ховає PendingIntent у тег кореневого елемента; дістаємо його так само, як лаунчер. */
private object GuardWidgetClicks {
    fun pendingIntentOf(view: View): PendingIntent {
        val tagId = Class.forName("com.android.internal.R\$id").getField("pending_intent_tag").getInt(null)
        return view.getTag(tagId) as PendingIntent
    }
}
