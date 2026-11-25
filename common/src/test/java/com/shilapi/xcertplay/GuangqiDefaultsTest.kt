package com.shilapi.xcertplay

import com.shilapi.xcertplay.hud.WheelIntegrationSettings
import com.shilapi.xcertplay.orchestration.GuangqiStandingMicrophone
import com.shilapi.xcertplay.orchestration.GuangqiVolumeTakeover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The defaults follow the settings 0.4.6.14-alpha4 was driven with. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GuangqiDefaultsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun whatTheCarRanWith() {
        assertTrue(AirPlayPersistence.loadDirectUsbfsMode(app))
        assertTrue(AirPlayPersistence.loadWiredKernelTun(app))
        assertTrue(AirPlayPersistence.loadCarplayAutoDisconnectBt(app))
        assertTrue(WheelIntegrationSettings.carPlayCallControls(app))
        assertTrue(GuangqiVolumeTakeover.enabled(app))
        assertTrue(GuangqiStandingMicrophone.enabled(app))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(app))
        assertFalse(AirPlayPersistence.loadCallEchoCancellation(app))
        assertFalse(AirPlayPersistence.loadCallMicCompatibility(app))
        assertFalse(AirPlayPersistence.loadCarBluetoothAudio(app))
    }

    @Test fun anEarlierChoiceStays() {
        AirPlayPersistence.saveWiredKernelTun(app, false)
        WheelIntegrationSettings.setCarPlayCallControls(app, false)
        assertFalse(AirPlayPersistence.loadWiredKernelTun(app))
        assertFalse(WheelIntegrationSettings.carPlayCallControls(app))
    }
}
