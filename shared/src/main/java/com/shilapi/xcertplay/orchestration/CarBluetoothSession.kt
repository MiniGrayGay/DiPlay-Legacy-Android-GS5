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
 * Decides whether [BluetoothDisableGuard] turns the adapter off once more or gives up.
 *
 * Two limits: [maxRapid] re-enables in a row less than [rapidThresholdMillis] apart (something is
 * fighting back at once), and [maxTotal] re-disables since the guard was armed. The second one
 * matters on the Guangqi head unit, whose service re-enables every 4-5 seconds: far too slow for
 * the first limit, and without a second one the radio would be switched for the whole session.
 */
internal class BluetoothReDisablePolicy(
    private val rapidThresholdMillis: Long = 2_000L,
    private val maxRapid: Int = 5,
    private val maxTotal: Int = 20,
) {
    enum class Decision { DISABLE_AGAIN, GIVE_UP_RAPID, GIVE_UP_TOTAL }

    private var lastDisableMillis = 0L
    var rapid = 0
        private set
    var total = 0
        private set

    fun reset() {
        lastDisableMillis = 0L
        rapid = 0
        total = 0
    }

    /** The adapter came on at [nowMillis] (a monotonic clock, greater than zero). */
    fun onReEnabled(nowMillis: Long): Decision {
        if (lastDisableMillis > 0L && nowMillis - lastDisableMillis < rapidThresholdMillis) {
            if (++rapid >= maxRapid) return Decision.GIVE_UP_RAPID
        } else {
            rapid = 0
        }
        if (total >= maxTotal) return Decision.GIVE_UP_TOTAL
        total++
        lastDisableMillis = nowMillis
        return Decision.DISABLE_AGAIN
    }
}

/**
 * Re-disables the car's Bluetooth adapter whenever the car's own service re-enables it.
 *
 * The Guangqi head unit's Bluetooth service detects an adapter-off and turns it back on within
 * 4-5 seconds.  This receiver catches every [BluetoothAdapter.STATE_ON] transition and calls
 * [BluetoothAdapter.disable] again while [CarBluetoothReleaseStore.turnedOff] is set.
 * It disarms itself when the store flag clears (restore happened) or when
 * [BluetoothReDisablePolicy] gives up. DiPlay disarms it before it turns the adapter on itself
 * (a wireless start's Bluetooth bootstrap); the next established session arms it again.
 */
@SuppressLint("MissingPermission")
internal object BluetoothDisableGuard {
    private val policy = BluetoothReDisablePolicy()
    private var receiver: BroadcastReceiver? = null

    @Synchronized
    fun arm(context: Context, log: (String) -> Unit) {
        internalDisarm(context)
        policy.reset()
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
        when (policy.onReEnabled(SystemClock.elapsedRealtime())) {
            BluetoothReDisablePolicy.Decision.GIVE_UP_RAPID -> {
                log("carBluetooth=guard: rapid re-enable loop (${policy.rapid}x), giving up")
                internalDisarm(context)
            }
            BluetoothReDisablePolicy.Decision.GIVE_UP_TOTAL -> {
                log("carBluetooth=guard: car re-enabled adapter ${policy.total}x since armed, giving up")
                internalDisarm(context)
            }
            BluetoothReDisablePolicy.Decision.DISABLE_AGAIN -> {
                log("carBluetooth=guard: car re-enabled adapter, re-disabling (total=${policy.total} rapid=${policy.rapid})")
                runCatching { BluetoothAdapter.getDefaultAdapter()?.disable() }
            }
        }
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
        BluetoothDisableGuard.disarm(context)
        val store = CarBluetoothReleaseStore(context)
        if (!store.turnedOff) return
        Thread({
            CarBluetoothRelease.shared.restoreNow(AndroidCarBluetoothAdapter(), store, log)
        }, "diplay-car-bluetooth-restore").apply { isDaemon = true }.start()
    }
}
