package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneGapTest {
    private val gap = MicrophoneGap(20)
    private fun ms(value: Int) = value * 1_000_000L

    @Test fun nothingIsMissingBeforeTheFirstAudio() = assertEquals(0, gap.idle(ms(5_000)))

    @Test fun jitterIsLeftAlone() {
        gap.heard(ms(1_000))
        assertEquals(0, gap.idle(ms(1_170)))
    }

    /** As on the car: five waits of 200 ms, then audio again after 1038 ms. */
    @Test fun aStoppedRecorderIsCoveredInRealTime() {
        gap.heard(ms(0))
        assertEquals(50, (1..5).sumOf { gap.idle(ms(it * 200)) })
        gap.heard(ms(1_038))
        assertEquals(0, gap.idle(ms(1_100)))
    }
}
