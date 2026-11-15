package com.shilapi.xcertplay.hud

import android.content.Context

/**
 * The two opt-in settings that are left of upstream's BYD outputs. The preference file and
 * keys are upstream's, so a value the driver set before this build still applies.
 */
object BydOutputSettings {
    private const val PREFS = "diplay_byd_outputs"
    private const val KEY_WHEEL_SPEED_TO_IPHONE = "wheel_speed_to_iphone"
    private const val KEY_CARPLAY_CALL_CONTROLS = "carplay_call_controls_experimental"

    /** Declare the car's speed to the iPhone at identification, for its dead reckoning. */
    fun wheelSpeedToIphone(context: Context): Boolean = prefs(context).getBoolean(KEY_WHEEL_SPEED_TO_IPHONE, false)

    fun setWheelSpeedToIphone(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WHEEL_SPEED_TO_IPHONE, enabled).commit()
    }

    /** The steering wheel's call keys answer and end CarPlay calls. */
    fun carPlayCallControls(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CARPLAY_CALL_CONTROLS, false)

    fun setCarPlayCallControls(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_CARPLAY_CALL_CONTROLS, enabled).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
