package com.shilapi.xcertplay

import com.shilapi.xcertplay.hud.NavigationOutputs
import com.shilapi.xcertplay.hud.WheelIntegrationSettings
import com.shilapi.xcertplay.hud.CarPlayCallState
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayCallControlsOptInTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun cleanup() {
        WheelIntegrationSettings.setCarPlayCallControls(app, false)
        NavigationOutputs.endNow()
    }

    @Test fun newCallKeysCannotConsumeOrSendAnythingWhenOff() {
        WheelIntegrationSettings.setCarPlayCallControls(app, false)
        val controller = mock(CarPlayController::class.java)
        for (code in listOf(313, 314, 309)) {
            assertFalse(CarPlayCallKeys.onKey(app, code, true, controller))
            assertFalse(CarPlayCallKeys.onKey(app, code, false, controller))
        }
        verifyNoInteractions(controller)
    }

    @Test fun onEnablesAnswerAndDisableRestoresPassThrough() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        NavigationOutputs.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 2); string(4, "incoming-call")
        })
        WheelIntegrationSettings.setCarPlayCallControls(app, true)
        assertTrue(CarPlayCallKeys.onKey(app, 313, true, controller))
        assertTrue(CarPlayCallKeys.onKey(app, 313, false, controller))
        verify(controller).answerCall()
        WheelIntegrationSettings.setCarPlayCallControls(app, false)
        assertFalse(CarPlayCallKeys.onKey(app, 314, false, controller))
        verify(controller, never()).endCall()
    }
}
