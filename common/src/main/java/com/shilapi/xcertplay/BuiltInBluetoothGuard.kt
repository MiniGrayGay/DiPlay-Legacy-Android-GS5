package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.os.Handler
import android.os.Looper

/**
 * Disables the Android built-in Bluetooth adapter while a CarPlay session is active and
 * re-enables it after the session ends, with a configurable debounce so brief disconnects
 * (reconnect cycles) do not toggle the radio needlessly.
 *
 * On the Guangqi GS5 the default adapter is the Parrot FC6050 UART module; the external USB
 * dongle used by DiPlay for wireless CarPlay is controlled via USB HCI and is unaffected.
 */
class BuiltInBluetoothGuard(
    private val debounceMs: Long = 30_000L,
    private val log: (String) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var suppressed = false
    private val restoreRunnable = Runnable { doRestore("debounce elapsed") }

    fun suppress() {
        handler.removeCallbacks(restoreRunnable)
        if (suppressed) {
            log("BuiltInBtGuard: already suppressed, skipping")
            return
        }
        val adapter = defaultAdapter()
        if (adapter == null) {
            log("BuiltInBtGuard: no adapter, skipping suppress")
            return
        }
        if (!adapter.isEnabled) {
            log("BuiltInBtGuard: adapter already off, marking suppressed")
            suppressed = true
            return
        }
        val ok = runCatching { adapter.disable() }.getOrDefault(false)
        suppressed = ok
        log("BuiltInBtGuard: suppress=${if (ok) "ok" else "failed"}")
    }

    fun release() {
        if (!suppressed) return
        handler.removeCallbacks(restoreRunnable)
        handler.postDelayed(restoreRunnable, debounceMs)
        log("BuiltInBtGuard: restore scheduled in ${debounceMs}ms")
    }

    fun restoreNow() {
        handler.removeCallbacks(restoreRunnable)
        if (suppressed) doRestore("immediate")
    }

    private fun doRestore(reason: String) {
        if (!suppressed) return
        suppressed = false
        val adapter = defaultAdapter() ?: return
        if (adapter.isEnabled) {
            log("BuiltInBtGuard: restore($reason) skipped, adapter already on")
            return
        }
        val ok = runCatching { adapter.enable() }.getOrDefault(false)
        log("BuiltInBtGuard: restore($reason)=${if (ok) "ok" else "failed"}")
    }

    private fun defaultAdapter(): BluetoothAdapter? =
        runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
}
