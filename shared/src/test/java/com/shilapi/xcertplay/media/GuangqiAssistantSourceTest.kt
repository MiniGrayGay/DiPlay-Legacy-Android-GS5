package com.shilapi.xcertplay.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GuangqiAssistantSourceTest {
    private val prefs get() = (RuntimeEnvironment.getApplication() as Context)
        .getSharedPreferences("guangqi_assistant_source", Context.MODE_PRIVATE)
    private val calls = mutableListOf<String>()

    private val car = object : GuangqiNavigationSource.CarAudioSourceApi {
        var policy: Int? = 2
        var result = 6 // granted, the other source pauses
        override fun request(sourceId: Int, onChange: (Int) -> Unit): Int {
            calls += "request $sourceId"
            return result
        }

        override fun abandon(sourceId: Int): Int {
            calls += "abandon $sourceId"
            return 0
        }

        override fun dspPolicy(): Int? = policy
    }

    private val source by lazy { GuangqiAssistantSource(car, Handler(Looper.getMainLooper()), prefs) }

    private fun idle(millis: Long = 0) = ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS)

    private fun start(stream: Any) = source.started(stream) {}.also { idle() }

    private fun end(stream: Any) = source.ended(stream).also { idle() }

    @Test fun siriOverTheRadioOrBluetoothMusicHoldsTheVoiceSourceUntilItEnds() {
        for (policy in listOf(2, 4)) {
            calls.clear()
            car.policy = policy
            start("siri")
            assertEquals(listOf("request 25"), calls)
            assertTrue(prefs.getBoolean("held", false))
            car.policy = 3
            idle(10_000)
            end("siri")
            assertEquals(listOf("request 25", "abandon 25"), calls)
            assertFalse(prefs.getBoolean("held", false))
        }
    }

    @Test fun siriOverCarPlayMusicAsksForNothing() {
        for (policy in listOf(0, 1, 3, 5, null)) {
            car.policy = policy
            start("siri")
            idle(5_000)
            end("siri")
        }
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun theRadioComingBackAfterAPromptIsNoticed() {
        car.policy = 1
        start("siri")
        car.policy = 2
        idle(GuangqiAssistantSource.CHECK_MILLIS)
        assertEquals(listOf("request 25"), calls)
        end("siri")
    }

    @Test fun theSourceIsHeldUntilTheLastOfTwoStreamsEnds() {
        start("first")
        start("second")
        end("first")
        assertEquals(listOf("request 25"), calls)
        source.endedAll(listOf("first", "second"))
        idle()
        assertEquals(listOf("request 25", "abandon 25"), calls)
    }

    @Test fun aRefusedSourceIsAskedForThreeTimesAndNeverAbandoned() {
        car.result = 0x200000 // ASR_LOW_PRORITY_DENY
        start("siri")
        idle(30_000)
        end("siri")
        assertEquals(List(3) { "request 25" }, calls)
    }
}
