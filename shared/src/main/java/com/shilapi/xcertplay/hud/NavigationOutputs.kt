package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a CarPlay session reports for the rest of the app: the next turn for the dashboard
 * overlay, the current call for the wheel's call keys, and the one-second cluster stream tick.
 * (Upstream's `BydNavigationOutputs`, without the BYD HUD, cluster, song and call services.)
 */
object NavigationOutputs {
    @Volatile private var overlayListener: ((ClusterTurnGuidance?) -> Unit)? = null
    private val overlayLock = Any()
    private var publishedOverlay: ClusterTurnGuidance? = null
    private val overlayRoute = RouteGuidanceState(
        staleRouteNs = 120_000_000_000L,
        emptyListHideNs = 8_000_000_000L,
        keepAcrossNoRoute = true,
    )

    private val tickerStarted = AtomicBoolean(false)
    @Volatile private var streamControl: ((Boolean) -> Unit)? = null

    /**
     * Starts the one-second cluster stream tick. It always asks for the map, and the session relies
     * on that repeated request until the iPhone has reported the cluster URL. Do not remove it.
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
        if (frame.messageId != RouteGuidanceState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != RouteGuidanceState.ROUTE_GUIDANCE_MANEUVER_UPDATE) return
        val change = synchronized(overlayLock) { overlayRoute.accept(frame.messageId, frame.payload) }
        if (change != RouteGuidanceChange.NONE) refreshTurnOverlay()
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
            ClusterTurnGuidance.from(AmapTurnFrame.from(apple)).copy(
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
