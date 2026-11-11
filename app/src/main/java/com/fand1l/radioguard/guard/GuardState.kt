package com.fand1l.radioguard.guard

import com.fand1l.radioguard.radio.Check
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** [id] потрібен екрану: два однакові записи можуть з'явитись у ту саму мілісекунду. */
data class JournalEntry(val id: Long, val timeMillis: Long, val text: String)

/** Живе лише в пам'яті: журнал не можна писати на диск. */
object GuardState {

    private const val MAX_ENTRIES = 40
    private val nextId = AtomicLong()

    private val _journal = MutableStateFlow<List<JournalEntry>>(emptyList())
    val journal: StateFlow<List<JournalEntry>> = _journal.asStateFlow()

    /** Остання перевірка вартового; null — служба не працює. */
    private val _checks = MutableStateFlow<List<Check>?>(null)
    val checks: StateFlow<List<Check>?> = _checks.asStateFlow()

    fun log(text: String) {
        val entry = JournalEntry(nextId.incrementAndGet(), System.currentTimeMillis(), text)
        _journal.update { (listOf(entry) + it).take(MAX_ENTRIES) }
    }

    fun publish(checks: List<Check>?) {
        _checks.value = checks
    }
}
