package com.fand1l.radioguard.quick

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.fand1l.radioguard.App
import com.fand1l.radioguard.MainActivity
import com.fand1l.radioguard.R
import com.fand1l.radioguard.guard.GuardRequest
import com.fand1l.radioguard.guard.GuardSummary
import com.fand1l.radioguard.guard.QuickAction
import com.fand1l.radioguard.guard.QuickState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GuardTileService : TileService() {

    private var listening: CoroutineScope? = null
    private var latest: QuickState? = null

    private val app: App get() = application as App

    override fun onStartListening() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        listening = scope
        scope.launch {
            app.quickStates.collect { state ->
                latest = state
                show(state)
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        val known = latest
        if (known != null) {
            act(known.action)
        } else {
            // Дотик прийшов раніше за перший стан.
            app.appScope.launch(Dispatchers.Main.immediate) { act(app.quickStates.first().action) }
        }
    }

    /** Підтвердження показує застосунок, тому спершу треба розблокувати телефон. */
    private fun act(action: QuickAction) {
        whenUnlocked { openApp(action.request) }
    }

    private fun whenUnlocked(block: () -> Unit) {
        if (isLocked) unlockAndRun(block) else block()
    }

    // Стару версію виклику Android 14+ забороняє, нової до Android 14 немає.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp(request: GuardRequest?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(MainActivity.pendingIntent(this, request))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(MainActivity.intent(this, request))
        }
    }

    private fun show(state: QuickState) {
        val tile = qsTile ?: return
        val active = state.guardOn || state.summary == GuardSummary.STARTING
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.guard_title)
        tile.subtitle = getString(
            when (state.summary) {
                GuardSummary.OFF -> if (state.shizukuReady) R.string.quick_off else R.string.quick_no_shizuku
                GuardSummary.STARTING -> R.string.quick_starting
                GuardSummary.OK -> if (state.exceptionsOn) R.string.quick_ok_exceptions else R.string.quick_ok
                GuardSummary.VIOLATION -> R.string.quick_violation
                GuardSummary.NO_SHELL -> R.string.quick_no_shell
            },
        )
        tile.updateTile()
    }
}
