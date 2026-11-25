package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPlaybackTrimTest {
    private val trim = CallPlaybackTrim()

    @Test fun nothingIsDroppedNearRealTime() {
        assertFalse(trim.drop(CallPlaybackTrim.TARGET_MILLIS, 7, peak = 0))
        assertFalse(trim.drop(0, 7, peak = 9_000))
        assertEquals(0L, trim.droppedMillis)
    }

    @Test fun aPauseIsShortenedAndSpeechIsNot() {
        assertTrue(trim.drop(250, 7, peak = 100))
        assertFalse(trim.drop(250, 7, peak = 9_000))
        assertEquals(7L, trim.droppedMillis)
    }

    @Test fun speechGoesOnlyWhenFarBehind() = assertTrue(trim.drop(301, 7, peak = 9_000))
}
