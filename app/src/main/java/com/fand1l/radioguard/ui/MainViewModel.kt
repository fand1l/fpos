package com.fand1l.radioguard.ui

import android.app.Application
import androidx.compose.runtime.Stable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fand1l.radioguard.App
import com.fand1l.radioguard.R
import com.fand1l.radioguard.data.GuardSettings
import com.fand1l.radioguard.guard.GuardService
import com.fand1l.radioguard.guard.GuardState
import com.fand1l.radioguard.guard.JournalEntry
import com.fand1l.radioguard.radio.Check
import com.fand1l.radioguard.radio.CheckStatus
import com.fand1l.radioguard.radio.Lockdown
import com.fand1l.radioguard.radio.RadioReader
import com.fand1l.radioguard.radio.ShellProbe
import com.fand1l.radioguard.shizuku.PrivilegedShell
import com.fand1l.radioguard.shizuku.ShellException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

enum class GuardSummary { OFF, OK, VIOLATION, NO_SHELL }

data class MainUiState(
    val loaded: Boolean = false,
    val guardOn: Boolean = false,
    val keepWifi: Boolean = true,
    val allowedSsid: String = "",
    val restorePending: Boolean = false,
    val onboardingDone: Boolean = true,
    val shizuku: PrivilegedShell.Status = PrivilegedShell.Status.NOT_RUNNING,
    val busy: Boolean = false,
    val checks: List<Check> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
) {
    val shizukuReady: Boolean get() = shizuku == PrivilegedShell.Status.READY

    val summary: GuardSummary
        get() = when {
            !guardOn -> GuardSummary.OFF
            !shizukuReady -> GuardSummary.NO_SHELL
            checks.any { it.status == CheckStatus.VIOLATION } -> GuardSummary.VIOLATION
            else -> GuardSummary.OK
        }

    /** Без Shizuku режим не ввімкнути, але вимкнути можна завжди. */
    val canToggle: Boolean get() = !busy && (guardOn || shizukuReady)
}

@Stable
interface MainActions {
    fun onGuardToggle(on: Boolean)
    fun onKeepWifiChange(keep: Boolean)
    fun onSsidSave(ssid: String)
    fun onShizukuGrant()
    fun onRestoreRetry()
    fun onOnboardingDone()
}

class MainViewModel(app: Application) : AndroidViewModel(app), MainActions {

    private val prefs = (app as App).prefs
    private val reader = RadioReader(app)
    private val busy = MutableStateFlow(false)

    /** Поки екран відкритий, стан перечитуємо самі: служба може й не працювати. */
    private val localState = flow {
        while (true) {
            PrivilegedShell.refresh()
            emit(reader.read())
            delay(REFRESH_MS)
        }
    }.flowOn(Dispatchers.Default)

    private val settings = prefs.settings.catch { e ->
        if (e !is IOException) throw e
        GuardState.log(app.getString(R.string.log_settings_unreadable))
        emit(GuardSettings())
    }

    val uiState: StateFlow<MainUiState> = combine(
        combine(settings, PrivilegedShell.status, busy, ::Triple),
        localState,
        GuardState.checks,
        GuardState.journal,
    ) { (settings, shizuku, busy), local, guardChecks, journal ->
        MainUiState(
            loaded = true,
            guardOn = settings.guardOn,
            keepWifi = settings.keepWifi,
            allowedSsid = settings.allowedSsid,
            restorePending = settings.restorePending,
            onboardingDone = settings.onboardingDone,
            shizuku = shizuku,
            busy = busy,
            checks = guardChecks?.takeIf { settings.guardOn }
                ?: local.checks(probe = null, settings.keepWifi, settings.allowedSsid),
            journal = journal,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MainUiState())

    init {
        // Процес могли вбити. Якщо режим увімкнений, вартовий має працювати.
        viewModelScope.launch {
            val guardOn = try {
                prefs.current().guardOn
            } catch (e: IOException) {
                false
            }
            if (guardOn) GuardService.start(getApplication())
        }
    }

    override fun onGuardToggle(on: Boolean) = write {
        if (on) {
            if (PrivilegedShell.refresh() != PrivilegedShell.Status.READY) return@write
            val probe = try {
                ShellProbe.parse(PrivilegedShell.exec(Lockdown.probeScript()).output)
            } catch (e: ShellException) {
                null
            }
            prefs.enableGuard(reader.read().snapshot(probe))
        } else {
            prefs.disableGuard()
        }
        GuardService.start(getApplication())
    }

    override fun onKeepWifiChange(keep: Boolean) = write { prefs.setKeepWifi(keep) }

    override fun onSsidSave(ssid: String) = write { prefs.setAllowedSsid(ssid) }

    override fun onShizukuGrant() = PrivilegedShell.requestPermission()

    override fun onRestoreRetry() {
        GuardService.start(getApplication())
    }

    override fun onOnboardingDone() = write { prefs.setOnboardingDone() }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (e: IOException) {
                GuardState.log(getApplication<App>().getString(R.string.log_error, e.message.orEmpty()))
            } finally {
                busy.value = false
            }
        }
    }

    private companion object {
        const val REFRESH_MS = 1_500L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
