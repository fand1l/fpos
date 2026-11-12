package com.fand1l.radioguard.guard

import com.fand1l.radioguard.data.Prefs
import com.fand1l.radioguard.radio.Check
import com.fand1l.radioguard.radio.CheckStatus
import com.fand1l.radioguard.radio.allowedAndOn
import com.fand1l.radioguard.shizuku.PrivilegedShell
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException

enum class GuardSummary { OFF, STARTING, OK, VIOLATION, NO_SHELL }

/** Зміна режиму, яку людина має підтвердити в застосунку. */
enum class GuardRequest { TURN_ON, TURN_OFF }

/** Що робить дотик до плитки чи віджета. */
enum class QuickAction(val request: GuardRequest?) {
    CONFIRM_ON(GuardRequest.TURN_ON),
    CONFIRM_OFF(GuardRequest.TURN_OFF),
    OPEN_APP(null),
}

/** Короткий стан для плитки й віджета. */
data class QuickState(
    val guardOn: Boolean,
    val shizukuReady: Boolean,
    val summary: GuardSummary,
    /** Працює дозволений виняток, який випромінює (наприклад, Bluetooth). */
    val exceptionsOn: Boolean = false,
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
 * Будь-яку зміну режиму людина підтверджує в застосунку: випадковий дотик
 * не повинен ні повернути радіо, ні обірвати зв'язок.
 */
fun quickAction(guardOn: Boolean, shizukuReady: Boolean): QuickAction = when {
    guardOn -> QuickAction.CONFIRM_OFF
    shizukuReady -> QuickAction.CONFIRM_ON
    else -> QuickAction.OPEN_APP
}

/** Яке підтвердження показати. null — питати нема про що: режим уже такий, як просили. */
fun confirmationFor(request: GuardRequest?, guardOn: Boolean): GuardRequest? = when (request) {
    GuardRequest.TURN_ON -> request.takeIf { !guardOn }
    GuardRequest.TURN_OFF -> request.takeIf { guardOn }
    null -> null
}

fun exceptionsOn(checks: List<Check>?): Boolean =
    checks?.allowedAndOn()?.any { it.emits } == true

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
        exceptionsOn = settings.guardOn && exceptionsOn(checks),
    )
}.distinctUntilChanged()
