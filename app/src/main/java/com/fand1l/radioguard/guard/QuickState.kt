package com.fand1l.radioguard.guard

import com.fand1l.radioguard.data.Prefs
import com.fand1l.radioguard.radio.Check
import com.fand1l.radioguard.radio.CheckStatus
import com.fand1l.radioguard.shizuku.PrivilegedShell
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException

enum class GuardSummary { OFF, STARTING, OK, VIOLATION, NO_SHELL }

/** Що робить дотик до плитки чи віджета. */
enum class QuickAction { TURN_ON, OPEN_APP, CONFIRM_OFF }

/** Короткий стан для плитки й віджета. */
data class QuickState(
    val guardOn: Boolean,
    val shizukuReady: Boolean,
    val summary: GuardSummary,
) {
    val action: QuickAction get() = quickAction(guardOn, shizukuReady)
}

/** [checks] = null означає: вартовий ще нічого не перевірив, тож казати «усе вимкнено» зарано. */
fun guardSummary(
    guardOn: Boolean,
    shizukuReady: Boolean,
    checks: List<Check>?,
    enabling: Boolean = false,
): GuardSummary = when {
    enabling -> GuardSummary.STARTING
    !guardOn -> GuardSummary.OFF
    !shizukuReady -> GuardSummary.NO_SHELL
    checks == null -> GuardSummary.STARTING
    checks.any { it.status == CheckStatus.VIOLATION } -> GuardSummary.VIOLATION
    else -> GuardSummary.OK
}

/**
 * Увімкнути можна одним дотиком. Вимкнути — лише через підтвердження в застосунку:
 * випадковий дотик не повинен повернути радіо.
 */
fun quickAction(guardOn: Boolean, shizukuReady: Boolean): QuickAction = when {
    guardOn -> QuickAction.CONFIRM_OFF
    shizukuReady -> QuickAction.TURN_ON
    else -> QuickAction.OPEN_APP
}

private const val SETTINGS_RETRY_MS = 5_000L

fun quickStates(prefs: Prefs): Flow<QuickState> = combine(
    // Збій читання не повинен показати «вимкнено»: лишаємо попередній стан і пробуємо ще.
    prefs.settings.retryWhen { cause, _ ->
        if (cause !is IOException) return@retryWhen false
        delay(SETTINGS_RETRY_MS)
        true
    },
    PrivilegedShell.status,
    GuardState.checks,
    GuardState.enabling,
) { settings, shizuku, checks, enabling ->
    val ready = shizuku == PrivilegedShell.Status.READY
    QuickState(
        guardOn = settings.guardOn,
        shizukuReady = ready,
        summary = guardSummary(settings.guardOn, ready, checks, enabling),
    )
}.distinctUntilChanged()
