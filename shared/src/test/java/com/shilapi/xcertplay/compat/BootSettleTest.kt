package com.shilapi.xcertplay.compat

import org.junit.Assert.assertEquals
import org.junit.Test

class BootSettleTest {
    @Test
    fun waitsOutTheRestOfTheFirstHalfMinute() {
        assertEquals(30_000L, BootSettle.remainingMillis(0L))
        assertEquals(5_000L, BootSettle.remainingMillis(25_000L))
    }

    @Test
    fun doesNotWaitOnceTheWindowHasPassed() {
        assertEquals(0L, BootSettle.remainingMillis(30_000L))
        assertEquals(0L, BootSettle.remainingMillis(3_600_000L))
    }
}
