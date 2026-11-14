package com.shilapi.xcertplay.orchestration

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
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
        Thread({
            CarBluetoothRelease.shared.restoreNow(AndroidCarBluetoothAdapter(), store, log)
        }, "diplay-car-bluetooth-restore").apply { isDaemon = true }.start()
    }
}
