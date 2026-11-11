package com.fand1l.radioguard.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardStateTest {

    @Test
    fun `journal keeps the newest forty entries with unique ids`() {
        repeat(45) { GuardState.log("запис $it") }
        val journal = GuardState.journal.value
        assertEquals(40, journal.size)
        assertEquals("запис 44", journal.first().text)
        assertEquals(40, journal.map { it.id }.toSet().size)
        assertTrue(journal.zipWithNext().all { (newer, older) -> newer.id > older.id })
    }
}
