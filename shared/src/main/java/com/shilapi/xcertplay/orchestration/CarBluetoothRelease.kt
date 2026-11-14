package com.shilapi.xcertplay.orchestration

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the car's own Bluetooth out of a CarPlay session (issue #343).
 *
 * While the iPhone stays connected to the car's Bluetooth, an incoming call rings on that link
 * too: the head unit shows its own call screen and takes the audio from CarPlay. Once a session
 * is established this drops the phone link, or turns the adapter off when the link cannot be
 * dropped on its own, and turns the adapter back on some time after the session ends.
 *
 * One instance serves wired and wireless sessions and outlives the controller, which is replaced
 * on every reconnect. It acts on what the adapter reports, not on what it did last: a wireless
 * start turns the adapter back on for its Bluetooth bootstrap, and the next session must turn it
 * off again. The adapter is only turned on again if this class turned it off, and that fact is
 * persisted so a process that died with Bluetooth off repairs it on the next start.
 */
internal class CarBluetoothRelease(
    private val schedule: (delayMillis: Long, task: () -> Unit) -> Cancelable,
    private val restoreDelayMillis: Long = RESTORE_DELAY_MILLIS,
) {
    /** The car's Bluetooth adapter. Every call may block for a short time. */
    interface Adapter {
        /** Null when the head unit has no adapter. */
        fun isEnabled(): Boolean?
        /** Drops only the phone-call link and leaves Bluetooth on; false when that is not possible. */
        fun releaseCallLink(): Boolean
        fun disable(): Boolean
        fun enable(): Boolean
    }

    /** Remembers across process restarts that this class turned the adapter off. */
    interface Store {
        var turnedOff: Boolean
    }

    fun interface Cancelable {
        fun cancel()
    }

    private var pendingRestore: Cancelable? = null

    /** A session is running: release the car's Bluetooth. Call off the main thread. */
    @Synchronized
    fun sessionEstablished(adapter: Adapter, store: Store, log: (String) -> Unit) {
        cancelPendingRestore()
        when (adapter.isEnabled()) {
            null -> log("carBluetooth=skipped (no Bluetooth adapter)")
            // Already off: by this class earlier (the flag is still set) or by the driver (it is
            // not, and then it is not this class's to turn on later).
            false -> log("carBluetooth=already off turnedOffByDiPlay=${store.turnedOff}")
            true -> if (adapter.releaseCallLink()) {
                log("carBluetooth=call link released (Bluetooth stays on)")
            } else {
                val accepted = adapter.disable()
                if (accepted) store.turnedOff = true
                log("carBluetooth=${if (accepted) "turned off" else "turn-off refused"} for the CarPlay session")
            }
        }
    }

    /**
     * The session is over. Bluetooth comes back after [restoreDelayMillis], so a reconnect within
     * that time does not switch the radio on and off again.
     */
    @Synchronized
    fun sessionEnded(adapter: Adapter, store: Store, log: (String) -> Unit) {
        if (!store.turnedOff || pendingRestore != null) return
        log("carBluetooth=restore scheduled in ${restoreDelayMillis}ms")
        pendingRestore = schedule(restoreDelayMillis) { restore(adapter, store, log, "delay elapsed") }
    }

    /** DiPlay is closing, or starts after it died with Bluetooth off: give it back at once. */
    @Synchronized
    fun restoreNow(adapter: Adapter, store: Store, log: (String) -> Unit) {
        cancelPendingRestore()
        restore(adapter, store, log, "immediate")
    }

    @Synchronized
    private fun restore(adapter: Adapter, store: Store, log: (String) -> Unit, reason: String) {
        pendingRestore = null
        if (!store.turnedOff) return
        store.turnedOff = false
        when (adapter.isEnabled()) {
            null -> Unit
            true -> log("carBluetooth=restore ($reason) not needed, already on")
            false -> log("carBluetooth=restore ($reason) ${if (adapter.enable()) "turned on" else "refused"}")
        }
    }

    private fun cancelPendingRestore() {
        pendingRestore?.cancel()
        pendingRestore = null
    }

    companion object {
        const val RESTORE_DELAY_MILLIS = 30_000L

        private val timer: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "diplay-car-bluetooth").apply { isDaemon = true }
            }
        }

        /** The process-wide instance. */
        val shared: CarBluetoothRelease by lazy {
            CarBluetoothRelease(schedule = { delayMillis, task ->
                val future: ScheduledFuture<*> = timer.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
                Cancelable { future.cancel(false) }
            })
        }
    }
}
