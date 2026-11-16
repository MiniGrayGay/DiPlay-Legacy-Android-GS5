package com.shilapi.xcertplay

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.hud.NavigationOutputs
import com.shilapi.xcertplay.hud.WheelIntegrationSettings
import com.shilapi.xcertplay.hud.CarPlayCallKeyPolicy
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * The steering wheel's call keys during a CarPlay call (see [CarPlayCallKeyPolicy]): the call key answers,
 * the hang-up keys end or decline the call. The keys come as key events, to the wheel key service or to
 * the CarPlay screen. Guangqi's call keys do not come this way; see GuangqiCarlifeKeys.
 */
internal object CarPlayCallKeys {
    private const val TAG = "DiPlay-CallKeys"
    /** Returns true when the key belongs to a CarPlay call and must not reach the car. */
    fun onKey(context: Context, keyCode: Int, down: Boolean, controller: CarPlayController? = currentController()): Boolean {
        if (!WheelIntegrationSettings.carPlayCallControls(context)) return false
        val action = CarPlayCallKeyPolicy.onKey(keyCode, down, NavigationOutputs.carPlayCall(), controller.hasSession())
        when (action) {
            CarPlayCallKeyPolicy.Action.PASS -> return false
            CarPlayCallKeyPolicy.Action.CONSUME -> Unit
            CarPlayCallKeyPolicy.Action.ANSWER -> {
                val sent = controller?.answerCall() == true
                Log.i(TAG, "call key $keyCode: answer CarPlay call sent=$sent")
            }
            CarPlayCallKeyPolicy.Action.END -> {
                val sent = controller?.endCall() == true
                Log.i(TAG, "key $keyCode: end CarPlay call sent=$sent")
            }
        }
        return true
    }

    private fun currentController(): CarPlayController? = CarPlayBackgroundSession.snapshot()?.controller

    private fun CarPlayController?.hasSession(): Boolean = this?.activeAirPlaySessionToken() != null
}
