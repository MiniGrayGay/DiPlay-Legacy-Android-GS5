package com.shilapi.xcertplay

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.StationWifiPrompt

/**
 * Shows the Wi-Fi Direct countdown before the head unit's own Wi-Fi client is paused, with a button
 * to keep it and one to pause it at once. Sized for a car screen, as the quick menu is: the stock
 * dialog's buttons were too small to hit while driving. The countdown itself runs in
 * `StationWifiPause`; closing this dialog or leaving the screen does not stop it.
 */
internal class StationWifiPromptDialog(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var messageView: TextView? = null
    private var shown: StationWifiPrompt.Countdown? = null
    private val tick = object : Runnable {
        override fun run() {
            val countdown = shown ?: return
            messageView?.text = message(countdown)
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
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(MENU_SURFACE)
                cornerRadius = activity.menuDp(24).toFloat()
                setStroke(activity.menuDp(1), MENU_BORDER)
            }
            setPadding(activity.menuDp(32), activity.menuDp(26), activity.menuDp(32), activity.menuDp(26))
        }
        panel.addView(activity.menuText(activity.getString(R.string.station_wifi_pause_title), 26f, Color.WHITE, bold = true))
        messageView = activity.menuText(message(countdown), 19f, MENU_SECONDARY).apply {
            setLineSpacing(0f, 1.25f)
            setPadding(0, activity.menuDp(14), 0, activity.menuDp(24))
        }
        panel.addView(messageView)
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button(activity.getString(R.string.station_wifi_pause_keep), primary = false) { countdown.keep() },
            LinearLayout.LayoutParams(0, activity.menuDp(76), 1f))
        buttons.addView(button(activity.getString(R.string.station_wifi_pause_now), primary = true) { countdown.pauseNow() },
            LinearLayout.LayoutParams(0, activity.menuDp(76), 1f).apply { marginStart = activity.menuDp(20) })
        panel.addView(buttons)
        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(panel)
            setCancelable(false)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(activity.menuDp(720).coerceAtMost(activity.resources.displayMetrics.widthPixels - activity.menuDp(32)),
                ViewGroup.LayoutParams.WRAP_CONTENT)
            window?.setGravity(Gravity.CENTER)
            show()
        }
        handler.postDelayed(tick, TICK_MILLIS)
    }

    private fun button(text: String, primary: Boolean, click: () -> Unit) = Button(activity).apply {
        this.text = text
        isAllCaps = false
        textSize = 20f
        setTextColor(if (primary) MENU_BUTTON_TEXT else Color.WHITE)
        @Suppress("DEPRECATION")
        setBackgroundDrawable(activity.menuButtonBackground(if (primary) MENU_ACCENT else MENU_BUTTON, radiusDp = 20))
        setOnClickListener { click() }
    }

    private fun dismiss() {
        handler.removeCallbacks(tick)
        shown = null
        messageView = null
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
