package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuangqiAutoConnectTest {
    private val backup = FakeBackup()
    private val memory = GuangqiAutoConnectMemory()
    private val guard = GuangqiAutoConnect(backup, memory, log = {})

    @Test
    fun suspendRemembersThePreviousValueAndDoesNotOverwriteIt() {
        backup.value = 1
        guard.suspendForSession("session")
        guard.suspendForSession("again")
        assertEquals(listOf(0), backup.writes)
        assertEquals(listOf(false, false), backup.module)
        assertTrue(memory.holding)
        assertEquals(1, memory.previous)
    }

    @Test
    fun restoreWritesTheSavedValueBack() {
        backup.value = 1
        guard.suspendForSession("session")
        guard.restore("ended")
        assertEquals(listOf(0, 1), backup.writes)
        assertEquals(listOf(false, true), backup.module)
        assertFalse(memory.holding)
    }

    @Test
    fun alreadyOffStaysOffOnRestore() {
        backup.value = 0
        guard.suspendForSession("session")
        guard.restore("ended")
        assertEquals(listOf(0), backup.writes)
        assertEquals(listOf(false, false), backup.module)
    }

    @Test
    fun unreadableBackupDoesNotInventARestoreValue() {
        backup.readable = false
        guard.suspendForSession("session")
        guard.restore("ended")
        assertEquals(emptyList<Int>(), backup.writes)
        assertEquals(listOf(false), backup.module)
        assertFalse(memory.holding)
    }

    @Test
    fun abandonedHoldIsRestoredOnTheNextStart() {
        backup.value = 1
        guard.suspendForSession("session")
        val restarted = GuangqiAutoConnect(backup, memory, log = {})
        restarted.recoverAbandoned()
        assertEquals(listOf(0, 1), backup.writes)
        assertEquals(listOf(false, true), backup.module)
        assertFalse(memory.holding)
    }

    private class FakeBackup : GuangqiAutoConnectBackup {
        var value: Int? = 0
        var readable = true
        val writes = mutableListOf<Int>()
        val module = mutableListOf<Boolean>()

        override fun read(): Int? = if (readable) value else null

        override fun write(value: Int): Boolean {
            writes += value
            this.value = value
            return true
        }

        override fun setModule(enabled: Boolean): Boolean {
            module += enabled
            return true
        }
    }
}
