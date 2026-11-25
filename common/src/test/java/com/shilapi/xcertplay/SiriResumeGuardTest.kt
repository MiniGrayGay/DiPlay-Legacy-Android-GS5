package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiriResumeGuardTest {
    private val guard = SiriResumeGuard()

    /** The times of the car's log on 2026-11-26, from 00:04:00. */
    private fun siriOverMusic() {
        guard.music(true, 9_700)
        guard.music(false, 40_857)
        guard.siri(true, 41_332, "A")
        guard.siri(false, 48_117, "A")
    }

    @Test fun musicResumedAfterSiri() {
        siriOverMusic()
        assertTrue(guard.isResume(48_814))
        assertFalse("later it is the driver's", guard.isResume(53_000))
    }

    @Test fun aQuestionFollowedByAnother() {
        siriOverMusic()
        guard.siri(true, 50_000, "A")
        guard.siri(false, 56_000, "A")
        assertTrue(guard.isResume(56_500))
    }

    @Test fun musicSiriWasAskedFor() {
        guard.siri(true, 41_332, "A")
        guard.siri(false, 48_117, "A")
        assertFalse(guard.isResume(48_814))
    }

    @Test fun otherMusicThanBeforeSiri() {
        siriOverMusic()
        assertFalse(guard.isRequested(48_900, "A"))
        assertTrue(guard.isRequested(48_900, "B"))
        assertFalse(guard.isRequested(70_000, "C"))
    }

    @Test fun musicStoppedLongBeforeSiri() {
        guard.music(true, 1_000)
        guard.music(false, 10_000)
        guard.siri(true, 20_000, null)
        guard.siri(false, 25_000, null)
        assertFalse(guard.isResume(25_500))
    }
}
