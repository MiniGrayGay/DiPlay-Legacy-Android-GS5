package com.shilapi.xcertplay.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Takes the head unit's own Wi-Fi client connection (wlan0 to a router or a phone hotspot) down
 * while wireless CarPlay runs over Wi-Fi Direct, after a countdown the driver can stop.
 *
 * The Guangqi unit has one 2.4 GHz radio (AR6003). A client connection on another channel makes
 * the radio share its time with the Wi-Fi Direct group CarPlay uses. Wi-Fi itself must stay on:
 * on Android 4.4 turning it off makes `WifiStateMachine` send `CMD_DISABLE_P2P_REQ` first, which
 * removes the Wi-Fi Direct group and ends CarPlay. So the client is only disconnected
 * (`WifiManager.disconnect()`, which also stops the supplicant from reconnecting by itself),
 * disconnected again if anything reconnects it, and reconnected when the session ends. A process
 * that dies holding the pause reconnects at the next [recover].
 *
 * All state lives on the main thread. The countdown runs here, not in a screen, so it completes
 * when no screen shows it; [StationWifiPrompt] only shows it and lets the driver answer.
 */
class StationWifiPause(context: Context, private val log: (String) -> Unit) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val wifi: WifiManager? get() = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var paused = false
    private var declined = false
    private var guard: BroadcastReceiver? = null
    private val countdownEnd = Runnable { pause("countdown ended") }

    /** The Wi-Fi Direct group is waiting for the iPhone: count down if the client is connected. */
    fun offer() {
        handler.post {
            if (paused || declined || StationWifiPrompt.current?.owner === this) return@post
            val ssid = connectedSsid() ?: run {
                log("Station Wi-Fi: not connected to a network; nothing to pause")
                return@post
            }
            log("Station Wi-Fi: connected to a network; pausing in ${COUNTDOWN_MILLIS / 1000}s unless kept")
            StationWifiPrompt.show(
                StationWifiPrompt.Countdown(
                    ssid = ssid,
                    deadlineUptimeMillis = SystemClock.uptimeMillis() + COUNTDOWN_MILLIS,
                    owner = this,
                ),
            )
            handler.postDelayed(countdownEnd, COUNTDOWN_MILLIS)
        }
    }

    /** The driver chose to keep the connection; not asked again for this controller. */
    fun keep() {
        handler.post {
            if (StationWifiPrompt.current?.owner !== this) return@post
            handler.removeCallbacks(countdownEnd)
            declined = true
            StationWifiPrompt.clear(this)
            log("Station Wi-Fi: kept by the driver")
        }
    }

    /** The driver chose not to wait for the countdown. */
    fun pauseNow() {
        handler.post {
            if (StationWifiPrompt.current?.owner !== this) return@post
            handler.removeCallbacks(countdownEnd)
            pause("paused by the driver")
        }
    }

    /** The wireless session ended: stops a countdown and reconnects a paused client. */
    fun end() {
        handler.post {
            handler.removeCallbacks(countdownEnd)
            StationWifiPrompt.clear(this)
            if (!paused) return@post
            paused = false
            guard?.let { runCatching { app.unregisterReceiver(it) } }
            guard = null
            reconnect(app, log)
        }
    }

    private fun pause(reason: String) {
        StationWifiPrompt.clear(this)
        if (paused) return
        val manager = wifi ?: return
        if (connectedSsid() == null) {
            log("Station Wi-Fi: already disconnected ($reason)")
            return
        }
        prefs(app).edit().putBoolean(KEY_PAUSED, true).commit()
        paused = true
        runCatching { manager.disconnect() }
            .onFailure { log("Station Wi-Fi: disconnect failed: ${it.javaClass.simpleName}") }
        guard = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                if (paused && info?.isConnected == true) {
                    log("Station Wi-Fi: reconnected during the session; disconnecting again")
                    runCatching { wifi?.disconnect() }
                }
            }
        }.also { app.registerReceiver(it, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)) }
        log("Station Wi-Fi: paused for the Wi-Fi Direct session ($reason)")
    }

    private fun connectedSsid(): String? = runCatching {
        val manager = wifi ?: return@runCatching null
        if (!manager.isWifiEnabled) return@runCatching null
        val info = manager.connectionInfo ?: return@runCatching null
        if (info.networkId == -1 || info.supplicantState != SupplicantState.COMPLETED) return@runCatching null
        info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: ""
    }.getOrNull()

    companion object {
        const val COUNTDOWN_MILLIS = 5_000L
        private const val PREFS = "station_wifi_pause"
        private const val KEY_PAUSED = "paused"

        /** Reconnects a client that a previous process paused and never reconnected. */
        fun recover(context: Context, log: (String) -> Unit) {
            val app = context.applicationContext
            if (!prefs(app).getBoolean(KEY_PAUSED, false)) return
            log("Station Wi-Fi: reconnecting after an abandoned pause")
            reconnect(app, log)
        }

        private fun reconnect(app: Context, log: (String) -> Unit) {
            val manager = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ok = runCatching { manager?.reconnect() == true }.getOrDefault(false)
            prefs(app).edit().putBoolean(KEY_PAUSED, false).commit()
            log("Station Wi-Fi: reconnect requested ok=$ok")
        }

        private fun prefs(app: Context) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}

/** What a screen shows of a running [StationWifiPause] countdown. Main thread only. */
object StationWifiPrompt {
    class Countdown(val ssid: String, val deadlineUptimeMillis: Long, internal val owner: StationWifiPause) {
        fun keep() = owner.keep()
        fun pauseNow() = owner.pauseNow()
    }

    var current: Countdown? = null
        private set
    private val listeners = mutableSetOf<(Countdown?) -> Unit>()

    fun addListener(listener: (Countdown?) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeListener(listener: (Countdown?) -> Unit) {
        listeners.remove(listener)
    }

    internal fun show(countdown: Countdown) {
        current = countdown
        listeners.toList().forEach { it(countdown) }
    }

    internal fun clear(owner: StationWifiPause) {
        if (current?.owner !== owner) return
        current = null
        listeners.toList().forEach { it(null) }
    }
}
