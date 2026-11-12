package com.fand1l.radioguard.ui

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fand1l.radioguard.App
import com.fand1l.radioguard.R
import com.fand1l.radioguard.data.GuardSettings
import com.fand1l.radioguard.guard.GuardService
import com.fand1l.radioguard.guard.GuardState
import com.fand1l.radioguard.guard.GuardSummary
import com.fand1l.radioguard.guard.JournalEntry
import com.fand1l.radioguard.guard.guardSummary
import com.fand1l.radioguard.radio.Check
import com.fand1l.radioguard.radio.RadioReader
import com.fand1l.radioguard.shizuku.PrivilegedShell
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

data class MainUiState(
    val loaded: Boolean = false,
    val guardOn: Boolean = false,
    val keepWifi: Boolean = true,
    val allowedSsid: String = "",
    val restorePending: Boolean = false,
    val onboardingDone: Boolean = true,
    val shizuku: PrivilegedShell.Status = PrivilegedShell.Status.NOT_RUNNING,
    val busy: Boolean = false,
    val enabling: Boolean = false,
    val checks: List<Check> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
) {
    val shizukuReady: Boolean get() = shizuku == PrivilegedShell.Status.READY

    val summary: GuardSummary get() = guardSummary(guardOn, shizukuReady, checks, enabling)

    /** Без Shizuku режим не ввімкнути, але вимкнути можна завжди. */
    val canToggle: Boolean get() = !busy && !enabling && (guardOn || shizukuReady)
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

class MainViewModel(private val app: App) : ViewModel(), MainActions {

    private val prefs = app.prefs
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
        GuardState.enabling,
    ) { (settings, shizuku, busy), local, guardChecks, journal, enabling ->
        MainUiState(
            loaded = true,
            guardOn = settings.guardOn,
            keepWifi = settings.keepWifi,
            allowedSsid = settings.allowedSsid,
            restorePending = settings.restorePending,
            onboardingDone = settings.onboardingDone,
            shizuku = shizuku,
            busy = busy,
            enabling = enabling,
            checks = guardChecks?.takeIf { settings.guardOn }
                ?: local.checks(probe = null, settings.keepWifi, settings.allowedSsid),
            journal = journal,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MainUiState())

    override fun onGuardToggle(on: Boolean) {
        if (on) {
            // Знімок стану і запис налаштувань робить сама служба — так само, як для плитки й віджета.
            GuardService.enable(app)
        } else {
            write {
                prefs.disableGuard()
                GuardService.start(app)
            }
        }
    }

    override fun onKeepWifiChange(keep: Boolean) = write { prefs.setKeepWifi(keep) }

    override fun onSsidSave(ssid: String) = write { prefs.setAllowedSsid(ssid) }

    override fun onShizukuGrant() = PrivilegedShell.requestPermission()

    override fun onRestoreRetry() {
        GuardService.start(app)
    }

    override fun onOnboardingDone() = write { prefs.setOnboardingDone() }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (e: IOException) {
                GuardState.log(app.getString(R.string.log_error, e.message.orEmpty()))
            } finally {
                busy.value = false
            }
        }
    }

    companion object {
        private const val REFRESH_MS = 1_500L
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * Застосунок беремо з того екрана, що створює ViewModel. Типова фабрика AndroidViewModel
         * запам'ятовує перший екземпляр на весь процес, і тести з кількома екземплярами
         * читали б чужі налаштування.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as App) }
        }
    }
}
