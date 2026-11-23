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

        override fun keepMusicLevel(index: Int): String {
            calls += "keep music $index"
            return "set"
        }
    }

    private fun source(api: GuangqiNavigationSource.CarAudioSourceApi? = car) =
        GuangqiNavigationSource(api, Handler(Looper.getMainLooper()), prefs) { nowMillis * 1_000_000L }

    private fun advance(millis: Long) {
        nowMillis += millis
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS)
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
        assertEquals(listOf("request 27", "keep music 11"), calls)
        assertTrue(prefs.getBoolean("held", false))

        advance(GuangqiNavigationSource.RELEASE_MILLIS + 100)
        assertEquals(listOf("request 27", "keep music 11", "abandon 27"), calls)
        assertFalse(prefs.getBoolean("held", false))

        // The next prompt asks again.
        source.onNavigationPcm({ log += it })
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("request 27", "keep music 11", "abandon 27", "request 27"), calls)
    }

    @Test fun aRefusedRequestIsNotAbandoned() {
        car.result = 0x200000 // ASR_LOW_PRORITY_DENY
        val source = source()
        source.onNavigationPcm({ log += it })
        ShadowLooper.idleMainLooper()
        advance(GuangqiNavigationSource.RELEASE_MILLIS + 100)
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
        advance(120 + GuangqiNavigationSource.SWITCH_SLACK_MILLIS - 1)
        assertEquals(listOf("duck"), calls)
        advance(1)
        assertEquals(listOf("duck", "request 27"), calls)
        // Back up only once the car's own duck has reached the speakers too.
        source.onNavigationPcm({ log += it }, guard)
        advance(120 + GuangqiNavigationSource.DSP_SWITCH_MILLIS - 1)
        assertEquals(listOf("duck", "request 27"), calls)
        advance(1)
        assertEquals(listOf("duck", "request 27", "keep music 11", "unduck"), calls)
    }

    @Test fun withoutMusicTheSourceIsRequestedAtOnce() {
        val source = source()
        source.onNavigationPcm({ log += it }, Guard(playing = false, calls = calls))
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("request 27"), calls)
        advance(GuangqiNavigationSource.DSP_SWITCH_MILLIS)
        assertEquals(listOf("request 27", "keep music 11"), calls)
    }

    @Test fun theChosenMusicIndexIsUsed() {
        GuangqiGuidanceMusic.setMusicIndex(app, 13)
        val source = source()
        source.onNavigationPcm({ log += it }, Guard(playing = true, calls = calls))
        ShadowLooper.idleMainLooper()
        advance(1_000)
        assertEquals(listOf("duck", "request 27", "keep music 13", "unduck"), calls)
    }

    @Test fun theMusicIndexStaysInItsRange() {
        GuangqiGuidanceMusic.setMusicIndex(app, 30)
        assertEquals(GuangqiGuidanceMusic.MAX_INDEX, GuangqiGuidanceMusic.musicIndex(app))
        GuangqiGuidanceMusic.setMusicIndex(app, 1)
        assertEquals(GuangqiGuidanceMusic.MIN_INDEX, GuangqiGuidanceMusic.musicIndex(app))
        assertEquals((8..14).toList(), GuangqiGuidanceMusic.choices)
    }

    @Test fun lowMediaLevelsAreLeftToTheCar() {
        // At media level 6 or below the car keeps music at its level itself.
        assertEquals(null, GuangqiNavigationSource.musicIndexDuringGuidance(6, 11, 30))
        assertEquals(11, GuangqiNavigationSource.musicIndexDuringGuidance(7, 11, 30))
        assertEquals(14, GuangqiNavigationSource.musicIndexDuringGuidance(25, 14, 30))
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

    @Test fun withoutTheCarSourceManagerNothingHappens() {
        val source = source(api = null)
        source.onNavigationPcm({ log += it })
        source.releaseNow()
        ShadowLooper.idleMainLooper()
        assertTrue(calls.isEmpty())
        assertTrue(log.isEmpty())
    }
}
