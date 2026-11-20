package com.shilapi.xcertplay.compat

import org.junit.Assert.assertEquals
import org.junit.Test

class BootSettleTest {
    @Test
    fun waitsOutTheRestOfTheFirstMinute() {
        assertEquals(60_000L, BootSettle.remainingMillis(0L))
        assertEquals(35_000L, BootSettle.remainingMillis(25_000L))
    }

    @Test
    fun doesNotWaitOnceTheWindowHasPassed() {
        assertEquals(0L, BootSettle.remainingMillis(60_000L))
        assertEquals(0L, BootSettle.remainingMillis(3_600_000L))
    }
}
