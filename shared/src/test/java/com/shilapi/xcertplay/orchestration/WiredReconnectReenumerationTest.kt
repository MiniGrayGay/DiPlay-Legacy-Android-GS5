package com.shilapi.xcertplay.orchestration

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * An in-place wired reconnect (no unplug) after a completed session must force one vendor
 * re-enumeration. The latch that stops a second re-enumeration on the same attach (issue #368) used
 * to outlive the session, so the reconnect reused the configuration the session left behind and
 * the iPhone never answered over NCM until the cable was unplugged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WiredReconnectReenumerationTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: CarPlayController

    @Before fun setUp() {
        setLatch("reenumeratedThisAttach", false)
        setLatch("forcedReenumerationForAttach", false)
        controller = CarPlayController(
            app,
            CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3),
            ),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(1920, 990)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
        )
    }

    @After fun tearDown() {
        runCatching { controller.close() }
        setLatch("reenumeratedThisAttach", false)
        setLatch("forcedReenumerationForAttach", false)
    }

    @Test fun aCompletedSessionMakesTheNextReuseForceAReenumeration() {
        // The attach that started the session re-enumerated the iPhone (issue #368 latch set).
        setLatch("reenumeratedThisAttach", true)
        assertFalse("before any session the #368 latch keeps a second transition off", shouldForce())

        invoke("markWiredSessionCompleted")

        assertTrue("the in-place reconnect must flush the state the session used", shouldForce())
    }

    @Test fun aForcedPassThatLedToASessionDoesNotBlockTheNextOne() {
        setLatch("forcedReenumerationForAttach", true)
        invoke("markWiredSessionCompleted")
        assertTrue(shouldForce())
    }

    @Test fun withoutACompletedSessionThereIsNothingToFlush() {
        app.getSharedPreferences("wired_session_state", Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(shouldForce())
    }

    private fun shouldForce(): Boolean = CarPlayController::class.java
        .getDeclaredMethod("shouldForceReenumerationBeforeReuse")
        .apply { isAccessible = true }.invoke(controller) as Boolean

    private fun invoke(name: String) {
        CarPlayController::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(controller)
    }

    private fun setLatch(name: String, value: Boolean) {
        CarPlayController::class.java.getDeclaredField(name).apply { isAccessible = true }.setBoolean(null, value)
    }
}
