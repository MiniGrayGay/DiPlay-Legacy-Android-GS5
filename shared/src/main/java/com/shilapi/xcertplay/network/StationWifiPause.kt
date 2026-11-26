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
 * Keeps the head unit's own Wi-Fi client connection (wlan0 to a router or a phone hotspot) off
 * while wireless CarPlay runs over Wi-Fi Direct, after a countdown the driver can stop.
 *
 * The Guangqi unit has one 2.4 GHz radio (AR6003). A client connection on another channel makes
 * the radio share its time with the Wi-Fi Direct group CarPlay uses. Wi-Fi itself must stay on:
 * on Android 4.4 turning it off makes `WifiStateMachine` send `CMD_DISABLE_P2P_REQ` first, which
 * removes the Wi-Fi Direct group and ends CarPlay. So the network is disabled
 * (`WifiManager.disableNetwork`), which disconnects it once and keeps the supplicant from joining
 * it again; disconnecting alone let the car join again and again, each time another change on the
 * radio. Another saved network that the car joins instead is disabled too. This is not saved to
 * the Wi-Fi configuration, so a restart of the head unit enables them all.
 *
 * The driver's choice, keep or pause, lasts across the automatic reconnects of one connection: a
 * controller that ends does not enable the networks, the next one takes them over and does not
 * ask again. They are enabled when the driver disconnects or leaves DiPlay ([finish]), when no
 * wireless session takes them over within [RESTORE_GRACE_MILLIS], and by [recover] in a process
 * that follows one that died.
 *
 * All state lives on the main thread. The countdown runs here, not in a screen, so it completes
 * when no screen shows it; [StationWifiPrompt] only shows it and lets the driver answer.
 */
class StationWifiPause(context: Context, private val log: (String) -> Unit) {
    private val app = context.applicationContext
    private val countdownEnd = Runnable { pause("countdown ended") }

    /** The Wi-Fi Direct group is waiting for the iPhone: count down if the client is connected. */
    fun offer() {
        handler.post {
            handler.removeCallbacks(restore)
            if (StationWifiPrompt.current?.owner === this) return@post
            owner = this
            ownerLog = log
            when {
                disabled.isNotEmpty() -> log("Station Wi-Fi: still paused (earlier choice)")
                choice == Choice.KEEP -> log("Station Wi-Fi: kept (earlier choice)")
                connectedSsid(app) == null -> log("Station Wi-Fi: not connected to a network; nothing to pause")
                choice == Choice.PAUSE -> pause("earlier choice")
                else -> {
                    log("Station Wi-Fi: connected to a network; pausing in ${COUNTDOWN_MILLIS / 1000}s unless kept")
                    StationWifiPrompt.show(
                        StationWifiPrompt.Countdown(
                            ssid = connectedSsid(app) ?: "",
                            deadlineUptimeMillis = SystemClock.uptimeMillis() + COUNTDOWN_MILLIS,
                            owner = this,
                        ),
                    )
                    handler.postDelayed(countdownEnd, COUNTDOWN_MILLIS)
                }
            }
        }
    }

    /** The driver chose to keep the connection; not asked again until [finish]. */
    fun keep() {
        handler.post {
            if (StationWifiPrompt.current?.owner !== this) return@post
            handler.removeCallbacks(countdownEnd)
            choice = Choice.KEEP
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

    /**
     * This controller ended: stops a countdown. Paused networks stay so for the next controller of
     * an automatic reconnect, and are enabled if none takes them over in time.
     */
    fun end() {
        handler.post {
            handler.removeCallbacks(countdownEnd)
            StationWifiPrompt.clear(this)
            if (owner !== this) return@post
            owner = null
            if (disabled.isNotEmpty()) {
                handler.removeCallbacks(restore)
                handler.postDelayed(restore, RESTORE_GRACE_MILLIS)
            }
        }
    }

    private fun pause(reason: String) {
        StationWifiPrompt.clear(this)
        choice = Choice.PAUSE
        if (!disableConnected(app)) {
            log("Station Wi-Fi: already disconnected ($reason)")
            return
        }
        log("Station Wi-Fi: paused for the Wi-Fi Direct session ($reason)")
    }

    private enum class Choice { KEEP, PAUSE }

    companion object {
        const val COUNTDOWN_MILLIS = 5_000L
        /** Long enough for an automatic reconnect to start its next wireless session. */
        const val RESTORE_GRACE_MILLIS = 60_000L
        private const val PREFS = "station_wifi_pause"
        private const val KEY_PAUSED = "paused"
        private const val KEY_NETWORKS = "networks"

        private val handler = Handler(Looper.getMainLooper())
        // Main thread only below; one connection's state, shared by its controllers.
        private var choice: Choice? = null
        private val disabled = LinkedHashSet<Int>()
        private var owner: StationWifiPause? = null
        private var ownerLog: (String) -> Unit = {}
        private var appContext: Context? = null
        private var guard: BroadcastReceiver? = null
        private val restore = Runnable { appContext?.let { enableAll(it, "no wireless session took it over", ownerLog) } }

        /** The driver disconnected or left DiPlay: the choice is forgotten and the networks come back. */
        fun finish(context: Context, log: (String) -> Unit) {
            val app = context.applicationContext
            handler.post {
                handler.removeCallbacks(restore)
                choice = null
                owner?.let(StationWifiPrompt::clear)
                owner = null
                enableAll(app, "disconnected", log)
            }
        }

        /** Enables networks that a previous process paused and never enabled. */
        fun recover(context: Context, log: (String) -> Unit) {
            val app = context.applicationContext
            if (!prefs(app).getBoolean(KEY_PAUSED, false)) return
            log("Station Wi-Fi: enabling after an abandoned pause")
            handler.post {
                if (disabled.isEmpty()) {
                    prefs(app).getString(KEY_NETWORKS, "").orEmpty().split(',')
                        .mapNotNullTo(disabled) { it.toIntOrNull() }
                }
                enableAll(app, "abandoned pause", log)
            }
        }

        private fun disableConnected(app: Context): Boolean {
            val manager = wifi(app) ?: return false
            val network = connectedNetwork(app) ?: return false
            disabled += network
            appContext = app
            prefs(app).edit().putBoolean(KEY_PAUSED, true).putString(KEY_NETWORKS, disabled.joinToString(",")).commit()
            val ok = runCatching { manager.disableNetwork(network) }.getOrDefault(false)
            if (!ok) runCatching { manager.disconnect() }
            if (guard == null) {
                guard = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        @Suppress("DEPRECATION")
                        val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                        if (disabled.isNotEmpty() && info?.isConnected == true) {
                            ownerLog("Station Wi-Fi: joined another network during the session; pausing it too")
                            disableConnected(app)
                        }
                    }
                }.also { app.registerReceiver(it, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)) }
            }
            return true
        }

        private fun enableAll(app: Context, reason: String, log: (String) -> Unit) {
            guard?.let { runCatching { app.unregisterReceiver(it) } }
            guard = null
            if (disabled.isEmpty() && !prefs(app).getBoolean(KEY_PAUSED, false)) return
            val manager = wifi(app)
            val enabled = disabled.count { network -> runCatching { manager?.enableNetwork(network, false) == true }.getOrDefault(false) }
            val ok = runCatching { manager?.reconnect() == true }.getOrDefault(false)
            log("Station Wi-Fi: ${disabled.size} network(s) enabled=$enabled, reconnect requested ok=$ok ($reason)")
            disabled.clear()
            prefs(app).edit().putBoolean(KEY_PAUSED, false).remove(KEY_NETWORKS).commit()
        }

        private fun wifi(app: Context) = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        private fun connectedNetwork(app: Context): Int? = runCatching {
            val manager = wifi(app) ?: return@runCatching null
            if (!manager.isWifiEnabled) return@runCatching null
            val info = manager.connectionInfo ?: return@runCatching null
            info.networkId.takeIf { it != -1 && info.supplicantState == SupplicantState.COMPLETED }
        }.getOrNull()

        private fun connectedSsid(app: Context): String? {
            connectedNetwork(app) ?: return null
            val info = wifi(app)?.connectionInfo
            return info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: ""
        }

        /** Tests only. */
        internal fun reset() {
            handler.removeCallbacks(restore)
            choice = null
            disabled.clear()
            owner = null
            guard = null
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
