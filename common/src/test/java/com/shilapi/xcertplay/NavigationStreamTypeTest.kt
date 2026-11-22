package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NavigationStreamTypeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun stored(value: Int?): Int {
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
        if (value == null) prefs.clear() else prefs.putInt("navigation_stream_type", value)
        prefs.commit()
        return AirPlayPersistence.loadNavigationStreamType(context)
    }

    @Test fun guidanceDefaultsToTheGuangqiNavigationStream() {
        assertEquals(AudioManager.STREAM_NOTIFICATION, stored(null))
    }

    @Test fun oldMediaAndBydValuesMoveToTheNavigationStream() {
        assertEquals(AudioManager.STREAM_NOTIFICATION, stored(AudioManager.STREAM_MUSIC))
        assertEquals(AudioManager.STREAM_NOTIFICATION, stored(14))
    }

    @Test fun anotherStoredStreamIsKept() {
        assertEquals(AudioManager.STREAM_VOICE_CALL, stored(AudioManager.STREAM_VOICE_CALL))
    }
}
