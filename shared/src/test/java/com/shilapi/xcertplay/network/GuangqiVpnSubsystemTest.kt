package com.shilapi.xcertplay.network

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class GuangqiVpnSubsystemTest {
    @Test
    fun anotherHeadUnitGetsNoUserStartingBroadcast() {
        val app = RuntimeEnvironment.getApplication() as Application
        val logged = mutableListOf<String>()

        assertFalse(GuangqiVpnSubsystem.applies())
        assertFalse(GuangqiVpnSubsystem.prepare(app) { logged += it })

        assertTrue("a working VPN table must not be restarted", shadowOf(app).broadcastIntents.isEmpty())
        assertTrue(logged.isEmpty())
    }
}
