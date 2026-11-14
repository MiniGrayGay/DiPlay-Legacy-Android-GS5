package com.shilapi.xcertplay.orchestration

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/** The head unit's Bluetooth adapter as [CarBluetoothRelease] sees it. */
@SuppressLint("MissingPermission")
internal open class AndroidCarBluetoothAdapter : CarBluetoothRelease.Adapter {
    protected fun adapter(): BluetoothAdapter? = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()

    override fun isEnabled(): Boolean? = adapter()?.let { runCatching { it.isEnabled }.getOrDefault(false) }

    override fun releaseCallLink(): Boolean = false

    override fun disable(): Boolean = runCatching { adapter()?.disable() == true }.getOrDefault(false)

    override fun enable(): Boolean = runCatching { adapter()?.enable() == true }.getOrDefault(false)
}

internal class CarBluetoothReleaseStore(context: Context) : CarBluetoothRelease.Store {
    private val preferences = context.applicationContext.getSharedPreferences("car_bluetooth_release", Context.MODE_PRIVATE)

    override var turnedOff: Boolean
        get() = preferences.getBoolean(KEY_TURNED_OFF, false)
        // commit(): the process may be killed right after Bluetooth goes off.
        @SuppressLint("ApplySharedPref")
        set(value) { preferences.edit().putBoolean(KEY_TURNED_OFF, value).commit() }

    private companion object {
        const val KEY_TURNED_OFF = "turned_off_by_diplay"
    }
}

/**
 * Re-disables the car's Bluetooth adapter whenever the car's own service re-enables it.
 *
 * The Guangqi head unit's Bluetooth service detects an adapter-off and turns it back on within
 * 4-5 seconds.  This receiver catches every [BluetoothAdapter.STATE_ON] transition and calls
 * [BluetoothAdapter.disable] again while [CarBluetoothReleaseStore.turnedOff] is set.
 * It auto-disarms when the store flag clears (restore happened) or on a tight re-enable loop.
 */
@SuppressLint("MissingPermission")
internal object BluetoothDisableGuard {
    private const val RAPID_THRESHOLD_MS = 2_000L
    private const val MAX_RAPID_RETRIES = 5

    private var receiver: BroadcastReceiver? = null
    private var lastDisableMs = 0L
    private var rapidRetries = 0

    @Synchronized
    fun arm(context: Context, log: (String) -> Unit) {
        internalDisarm(context)
        lastDisableMs = 0L
        rapidRetries = 0
        val appContext = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                if (state != BluetoothAdapter.STATE_ON) return
                onAdapterReEnabled(appContext, log)
            }
        }
        appContext.registerReceiver(r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        receiver = r
        log("carBluetooth=guard armed")
    }

    @Synchronized
    fun disarm(context: Context) {
        internalDisarm(context)
    }

    @Synchronized
    private fun internalDisarm(context: Context) {
        val r = receiver ?: return
        receiver = null
        runCatching { context.applicationContext.unregisterReceiver(r) }
    }

    @Synchronized
    private fun onAdapterReEnabled(context: Context, log: (String) -> Unit) {
        if (receiver == null) return
        val store = CarBluetoothReleaseStore(context)
        if (!store.turnedOff) {
            log("carBluetooth=guard: suppression cleared, disarming")
            internalDisarm(context)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastDisableMs > 0L && now - lastDisableMs < RAPID_THRESHOLD_MS) {
            rapidRetries++
            if (rapidRetries >= MAX_RAPID_RETRIES) {
                log("carBluetooth=guard: rapid re-enable loop ($MAX_RAPID_RETRIES×), giving up")
                internalDisarm(context)
                return
            }
        } else {
            rapidRetries = 0
        }
        lastDisableMs = now
        log("carBluetooth=guard: car re-enabled adapter, re-disabling (rapid=$rapidRetries)")
        runCatching { BluetoothAdapter.getDefaultAdapter()?.disable() }
    }
}

/** What the app shell needs from [CarBluetoothRelease]; the controller drives the rest. */
object CarBluetoothSession {
    private val recovered = AtomicBoolean(false)

    /**
     * Once per process, before any session: turns the car's Bluetooth back on if DiPlay turned it
     * off and then died before it could restore it.
     */
    fun recoverAfterRestart(context: Context, log: (String) -> Unit) {
        if (!recovered.compareAndSet(false, true)) return
        restoreNow(context, log)
    }

    /** DiPlay is closing with no session left: give the car's Bluetooth back at once. */
    fun restoreNow(context: Context, log: (String) -> Unit) {
        val store = CarBluetoothReleaseStore(context)
        if (!store.turnedOff) return
        BluetoothDisableGuard.disarm(context)
        Thread({
            CarBluetoothRelease.shared.restoreNow(AndroidCarBluetoothAdapter(), store, log)
        }, "diplay-car-bluetooth-restore").apply { isDaemon = true }.start()
    }
}
