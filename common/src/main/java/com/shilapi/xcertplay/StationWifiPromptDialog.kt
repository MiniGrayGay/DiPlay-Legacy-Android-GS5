package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.StationWifiPrompt

/**
 * Shows the Wi-Fi Direct countdown before the head unit's own Wi-Fi client is disconnected,
 * with a button to keep it and one to disconnect at once. The countdown itself runs in
 * `StationWifiPause`; closing this dialog or leaving the screen does not stop it.
 */
internal class StationWifiPromptDialog(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var shown: StationWifiPrompt.Countdown? = null
    private val tick = object : Runnable {
        override fun run() {
            val countdown = shown ?: return
            dialog?.setMessage(message(countdown))
            handler.postDelayed(this, TICK_MILLIS)
        }
    }
    private val listener: (StationWifiPrompt.Countdown?) -> Unit = { countdown ->
        if (countdown == null) dismiss() else show(countdown)
    }

    fun attach() = StationWifiPrompt.addListener(listener)

    fun detach() {
        StationWifiPrompt.removeListener(listener)
        dismiss()
    }

    private fun show(countdown: StationWifiPrompt.Countdown) {
        if (shown === countdown && dialog?.isShowing == true) return
        if (activity.isFinishing) return
        dismiss()
        shown = countdown
        dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.station_wifi_pause_title))
            .setMessage(message(countdown))
            .setNegativeButton(activity.getString(R.string.station_wifi_pause_keep)) { _, _ -> countdown.keep() }
            .setPositiveButton(activity.getString(R.string.station_wifi_pause_now)) { _, _ -> countdown.pauseNow() }
            .setCancelable(false)
            .show()
        handler.postDelayed(tick, TICK_MILLIS)
    }

    private fun dismiss() {
        handler.removeCallbacks(tick)
        shown = null
        runCatching { dialog?.dismiss() }
        dialog = null
    }

    private fun message(countdown: StationWifiPrompt.Countdown): String {
        val remaining = countdown.deadlineUptimeMillis - SystemClock.uptimeMillis()
        val seconds = ((remaining + 999) / 1000).coerceAtLeast(0)
        val network = countdown.ssid.ifBlank { activity.getString(R.string.station_wifi_pause_unknown_network) }
        return activity.getString(R.string.station_wifi_pause_message, network, seconds)
    }

    private companion object {
        const val TICK_MILLIS = 250L
    }
}
