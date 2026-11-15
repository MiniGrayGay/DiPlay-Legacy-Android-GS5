package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The iPhone's route guidance, kept as the next turn for the dashboard overlay.
 *
 * Upstream this object also fed BYD's HUD, cluster, song and call services. The Guangqi
 * head unit has none of them, so only the overlay state, the call state for the wheel's
 * call keys and the cluster stream tick are left. The name is kept so the call sites stay close to upstream.
 */
object BydNavigationOutputs {
    @Volatile private var overlayListener: ((ClusterTurnGuidance?) -> Unit)? = null
    private val overlayLock = Any()
    private var publishedOverlay: ClusterTurnGuidance? = null
    private val overlayRoute = BydHudRouteState(
        staleRouteNs = 120_000_000_000L,
        emptyListHideNs = 8_000_000_000L,
        keepAcrossNoRoute = true,
    )

    /** Undo a Wi-Fi scan pause that an earlier process left behind, when the app opens. */
    fun onAppOpened(context: Context) {
        com.shilapi.xcertplay.network.WifiScanPause.restoreIfNeeded(context)
    }

    private val tickerStarted = AtomicBoolean(false)
    @Volatile private var streamControl: ((Boolean) -> Unit)? = null

    /**
     * Starts the one-second cluster stream tick. Upstream the tick paused the stream while a
     * BYD cluster hid the map; off BYD it always asked for the map, and the session relies on
     * that repeated request until the iPhone has reported the cluster URL.
     */
    fun start() {
        if (tickerStarted.compareAndSet(false, true)) {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "diplay-cluster-map").apply { isDaemon = true }
            }.scheduleAtFixedRate({ streamControl?.invoke(true) }, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    /** The running CarPlay session: told every second that the cluster shows the map. */
    fun setClusterStreamControl(control: (Boolean) -> Unit) { streamControl = control }

    fun clearClusterStreamControl(control: (Boolean) -> Unit) {
        if (streamControl == control) streamControl = null
    }

    private const val TICK_MILLIS = 1_000L

    private val callState = CarPlayCallState()

    /** The iPhone's current call, for the steering wheel's call keys. */
    fun carPlayCall(): CarPlayCallCard? = synchronized(callState) { callState.current() }

    fun onFrame(frame: Iap2Frame) {
        if (frame.messageId == CarPlayCallState.CALL_STATE_UPDATE) {
            synchronized(callState) { callState.accept(frame) }
            return
        }
        if (frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) return
        val change = synchronized(overlayLock) { overlayRoute.accept(frame.messageId, frame.payload) }
        if (change != BydHudRouteChange.NONE) refreshTurnOverlay()
    }

    /** Live next-turn state for the dashboard overlay. Called from the iAP2 thread. */
    fun setTurnOverlayListener(listener: ((ClusterTurnGuidance?) -> Unit)?) {
        overlayListener = listener
        val next = currentOverlay()
        synchronized(overlayLock) { publishedOverlay = next }
        listener?.invoke(next)
    }

    /** Called every second while the presentation owner lives, even without incoming frames. */
    fun refreshTurnOverlay() {
        val next = synchronized(overlayLock) {
            val current = currentOverlay()
            if (current == publishedOverlay) return
            publishedOverlay = current
            current
        }
        overlayListener?.invoke(next)
    }

    private fun currentOverlay(): ClusterTurnGuidance? = synchronized(overlayLock) {
        overlayRoute.currentApple()?.let { apple ->
            ClusterTurnGuidance.from(BydClusterFrame.from(apple)).copy(
                arrivalEpochSeconds = apple.arrivalEpochSeconds,
                remainingSeconds = apple.remainingSeconds,
                remainingMeters = apple.remainingMeters,
            )
        }
    }

    /** Best effort while alive; Android does not guarantee callbacks before force-stop. */
    fun endNow(preserveTurnOverlay: Boolean = false) {
        synchronized(callState) { callState.clear() }
        // Only a wireless session replacement retains the card. Explicit controller close
        // and wired disconnect still clear it immediately.
        if (!preserveTurnOverlay) {
            synchronized(overlayLock) { overlayRoute.clear() }
            refreshTurnOverlay()
        }
    }
}
