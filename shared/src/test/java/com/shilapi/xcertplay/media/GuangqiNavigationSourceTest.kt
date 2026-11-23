package com.shilapi.xcertplay.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GuangqiNavigationSourceTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("guangqi_navigation_source", Context.MODE_PRIVATE)
    private var nowMillis = 0L
    private val calls = mutableListOf<String>()
    private val log = mutableListOf<String>()

    private val car = object : GuangqiNavigationSource.CarAudioSourceApi {
        var result = 0x402 // granted, foreground flag
        override fun request(sourceId: Int, onChange: (Int) -> Unit): Int {
            calls += "request $sourceId"
            return result
        }

        override fun abandon(sourceId: Int): Int {
            calls += "abandon $sourceId"
            return 0
        }

        override fun keepMusicLevel(duckDb: Int): String {
            calls += "keep music -$duckDb dB"
            return "set"
        }
    }

    private fun source(api: GuangqiNavigationSource.CarAudioSourceApi? = car) =
        GuangqiNavigationSource(api, Handler(Looper.getMainLooper()), prefs) { nowMillis * 1_000_000L }

    private fun advance(millis: Long) {
        nowMillis += millis
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS)
    }

    /** Time passing a millisecond at a time, so timers read the clock they fire at. */
    private fun elapse(millis: Long) = repeat(millis.toInt()) { advance(1) }

    /** A prompt playing for [millis]: audible PCM every millisecond, as far as the hold can tell. */
    private fun play(source: GuangqiNavigationSource, millis: Long, guard: GuangqiNavigationSource.SwitchGuard? = null) {
        repeat(millis.toInt()) {
            source.onNavigationPcm({ log += it }, guard)
            advance(1)
        }
    }

    @Test fun holdsTheCarPlayNavigationSourceWhileGuidancePlays() {
        val source = source()
        source.onNavigationPcm({ log += it })
        ShadowLooper.idleMainLooper()
        // A prompt: PCM every 20 ms for two seconds, longer than the release delay.
        repeat(100) {
            advance(20)
            source.onNavigationPcm({ log += it })
        }
        // Once the car has switched, music is set to the default index instead of the car's 9.
        assertEquals(listOf("request 27", "keep music -6 dB"), calls)
        assertTrue(prefs.getBoolean("held", false))

        advance(GuangqiNavigationSource.DEFAULT_RELEASE_MILLIS + 100)
        assertEquals(listOf("request 27", "keep music -6 dB", "abandon 27"), calls)
        assertFalse(prefs.getBoolean("held", false))

        // The next prompt asks again.
        source.onNavigationPcm({ log += it })
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("request 27", "keep music -6 dB", "abandon 27", "request 27"), calls)
    }

    @Test fun aRefusedRequestIsNotAbandoned() {
        car.result = 0x200000 // ASR_LOW_PRORITY_DENY
        val source = source()
        source.onNavigationPcm({ log += it })
        ShadowLooper.idleMainLooper()
        advance(GuangqiNavigationSource.DEFAULT_RELEASE_MILLIS + 100)
        assertEquals(listOf("request 27"), calls)
        assertFalse(prefs.getBoolean("held", false))
    }

    @Test fun closingTheAudioReleasesAtOnce() {
        val source = source()
        source.onNavigationPcm({ log += it })
        ShadowLooper.idleMainLooper()
        source.releaseNow()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("request 27", "abandon 27"), calls)
    }

    private class Guard(var playing: Boolean, val calls: MutableList<String>) : GuangqiNavigationSource.SwitchGuard {
        override fun mediaPlaying() = playing
        override fun duckForSwitch(on: Boolean) { calls += if (on) "duck" else "unduck" }
        override fun outputLatencyMillis() = 120L
    }

    @Test fun musicIsLoweredBeforeTheSwitchAndRestoredAfterTheCarDucksIt() {
        val source = source()
        val guard = Guard(playing = true, calls = calls)
        source.onNavigationPcm({ log += it }, guard)
        ShadowLooper.idleMainLooper()
        // Lowered at once; the request waits until the lowered music has reached the speakers.
        assertEquals(listOf("duck"), calls)
        play(source, 120 + GuangqiNavigationSource.SWITCH_SLACK_MILLIS - 1, guard)
        assertEquals(listOf("duck"), calls)
        play(source, 1, guard)
        assertEquals(listOf("duck", "request 27"), calls)
        // Back up only once the car's own duck has reached the speakers too.
        play(source, 120 + GuangqiNavigationSource.DSP_SWITCH_MILLIS - 1, guard)
        assertEquals(listOf("duck", "request 27"), calls)
        play(source, 1, guard)
        assertEquals(listOf("duck", "request 27", "keep music -6 dB", "unduck"), calls)
    }

    @Test fun withoutMusicTheSourceIsRequestedAtOnce() {
        val source = source()
        source.onNavigationPcm({ log += it }, Guard(playing = false, calls = calls))
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("request 27"), calls)
        advance(GuangqiNavigationSource.DSP_SWITCH_MILLIS)
        assertEquals(listOf("request 27", "keep music -6 dB"), calls)
    }

    @Test fun theChosenDuckIsUsed() {
        GuangqiGuidanceMusic.setDuckDb(app, 9)
        val source = source()
        play(source, 1_000, Guard(playing = true, calls = calls))
        assertEquals(listOf("duck", "request 27", "keep music -9 dB", "unduck"), calls)
    }

    @Test fun theDuckStaysInItsRange() {
        GuangqiGuidanceMusic.setDuckDb(app, 40)
        assertEquals(GuangqiGuidanceMusic.MAX_DUCK_DB, GuangqiGuidanceMusic.duckDb(app))
        GuangqiGuidanceMusic.setDuckDb(app, -3)
        assertEquals(GuangqiGuidanceMusic.MIN_DUCK_DB, GuangqiGuidanceMusic.duckDb(app))
        assertEquals((0..15).toList(), GuangqiGuidanceMusic.choices)
    }

    @Test fun silenceNeitherStartsNorExtendsTheHold() {
        val source = source()
        source.onNavigationPcm({ log += it }, audible = false)
        ShadowLooper.idleMainLooper()
        assertTrue("a silent stream does not switch the car", calls.isEmpty())

        source.onNavigationPcm({ log += it })
        ShadowLooper.idleMainLooper()
        // A silent tail keeps arriving, but the hold counts from the last audible write.
        repeat(40) {
            advance(20)
            source.onNavigationPcm({ log += it }, audible = false)
        }
        assertEquals(listOf("request 27", "keep music -6 dB", "abandon 27"), calls)
    }

    @Test fun theHoldFollowsTheGuidanceTrackLatency() {
        val source = source()
        val guard = object : GuangqiNavigationSource.SwitchGuard {
            override fun mediaPlaying() = false
            override fun duckForSwitch(on: Boolean) = Unit
            override fun outputLatencyMillis() = 100L
            override fun navigationLatencyMillis() = 400L
        }
        source.onNavigationPcm({ log += it }, guard)
        ShadowLooper.idleMainLooper()
        // Guidance plays once the car has switched; it is given back as the last audible guidance
        // finishes playing, 50 ms early for the car.
        elapse(GuangqiNavigationSource.GATE_SETTLE_MILLIS + 400 - GuangqiNavigationSource.EARLY_MILLIS - 1)
        assertFalse(calls.contains("abandon 27"))
        elapse(1 + 60)
        assertTrue(calls.contains("abandon 27"))
        assertTrue(log.any { "hold 350ms" in it })
    }

    @Test fun theSpeakerMusicCurveMatchesAosp() {
        // sSpeakerMediaVolumeCurve: 12/30 is position 40, between (20, -34) and (60, -11).
        assertEquals(-22.5f, GuangqiNavigationSource.speakerMusicDb(12, 30), 0.01f)
        assertEquals(-28.25f, GuangqiNavigationSource.speakerMusicDb(9, 30), 0.01f)
        assertEquals(0f, GuangqiNavigationSource.speakerMusicDb(30, 30), 0.01f)
    }

    @Test fun sixDecibelsAtMediaTenIsTheTwelveTheDriverChose() {
        assertEquals(12, GuangqiNavigationSource.musicIndexForDuck(10, 6, 30))
    }

    @Test fun theDuckFeelsTheSameAtEveryMediaLevel() {
        for (level in 3..20) {
            val index = GuangqiNavigationSource.musicIndexForDuck(level, 6, 30)
            val duck = GuangqiNavigationSource.musicDuckDb(level, index, 30)
            // Never louder than before, and within a step of the 6 dB asked for.
            assertTrue("media $level index $index duck $duck", duck >= 5.5f && duck < 9f)
        }
    }

    @Test fun aPromptThatEndsDuringTheSwitchPutsTheMusicBack() {
        val source = source()
        source.onNavigationPcm({ log += it }, Guard(playing = true, calls = calls))
        ShadowLooper.idleMainLooper()
        source.releaseNow()
        ShadowLooper.idleMainLooper()
        advance(1_000)
        assertEquals(listOf("duck", "unduck"), calls)
    }

    @Test fun aPromptWaitsUntilTheCarHasSwitched() {
        val source = source()
        source.onNavigationPcm({ log += it })
        assertFalse("the first words must not play in the media mode", source.guidanceGateOpen)
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("request 27"), calls)
        advance(GuangqiNavigationSource.GATE_SETTLE_MILLIS - 1)
        assertFalse(source.guidanceGateOpen)
        advance(1)
        assertTrue(source.guidanceGateOpen)
        assertTrue(log.any { "guidance plays (car switched)" in it })
    }

    @Test fun withMusicThePromptWaitsForTheLoweredMusicAndTheSwitch() {
        val source = source()
        val guard = Guard(playing = true, calls = calls)
        source.onNavigationPcm({ log += it }, guard)
        ShadowLooper.idleMainLooper()
        // No guidance is written while it waits, so the hold must not run out meanwhile.
        advance(120 + GuangqiNavigationSource.SWITCH_SLACK_MILLIS + GuangqiNavigationSource.GATE_SETTLE_MILLIS - 1)
        assertFalse(source.guidanceGateOpen)
        assertFalse(calls.contains("abandon 27"))
        advance(1)
        assertTrue(source.guidanceGateOpen)
    }

    @Test fun aRefusedRequestLetsThePromptPlayAtOnce() {
        car.result = 0x200000 // ASR_LOW_PRORITY_DENY
        val source = source()
        source.onNavigationPcm({ log += it })
        ShadowLooper.idleMainLooper()
        assertTrue(source.guidanceGateOpen)
    }

    @Test fun aClosedGateWaitsNoLongerThanItsTimeout() {
        val source = source()
        source.onNavigationPcm({ log += it })
        val started = System.nanoTime()
        assertFalse(source.awaitGate(30))
        assertTrue((System.nanoTime() - started) / 1_000_000L >= 25)
    }

    @Test fun withoutTheCarSourceManagerNothingHappens() {
        val source = source(api = null)
        source.onNavigationPcm({ log += it })
        source.releaseNow()
        ShadowLooper.idleMainLooper()
        assertTrue(calls.isEmpty())
        assertTrue(log.isEmpty())
    }
}
