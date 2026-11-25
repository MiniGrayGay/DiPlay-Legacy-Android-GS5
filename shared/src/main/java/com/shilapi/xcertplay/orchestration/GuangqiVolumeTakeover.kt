// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.orchestration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.util.Log

/**
 * DiPlay's side of [GuangqiVolumeHook], in DiPlay's main process: the driver's setting, the system
 * properties the hook reads at every key, the request to install the hook, and the bar for the keys
 * it took.
 *
 * With the setting on, the hook takes a volume key only while this process is alive
 * ([GuangqiVolumeHook.PROP_PID]); when it dies the keys go back to the car by themselves.
 */
object GuangqiVolumeTakeover {
    private const val TAG = "DiPlay-VolumeTakeover"
    private const val PREFS = "guangqi_volume_takeover"
    private const val KEY_ENABLED = "enabled"

    @Volatile private var receiver: BroadcastReceiver? = null
    /** The running CarPlay session's log, so the hook's work lands in diplay.log. */
    @Volatile private var sessionLog: ((String) -> Unit)? = null
    @Volatile private var lastState: String? = null
    @Volatile private var appContext: Context? = null

    /** True on head units with the car's DSP manager, the only ones the hook works on. */
    fun supported(): Boolean = runCatching { Class.forName("android.os.DspManager") }.isSuccess

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        apply(context.applicationContext)
    }

    /** DiPlay's main process started. */
    fun start(context: Context) {
        if (!supported()) return
        appContext = context.applicationContext
        apply(context.applicationContext)
    }

    fun sessionStarted(log: (String) -> Unit) {
        sessionLog = log
        val app = appContext ?: return
        report(when {
            !enabled(app) -> "volume hook: setting off, volume keys stay with the car"
            else -> lastState ?: "volume hook: no answer from the system process yet"
        })
    }

    fun sessionEnded() {
        sessionLog = null
    }

    private fun apply(app: Context) {
        val on = enabled(app)
        setProperty(GuangqiVolumeHook.PROP_SWITCH, if (on) "1" else "0")
        if (!on) {
            setProperty(GuangqiVolumeHook.PROP_PID, "0")
            report("volume hook: setting off, volume keys stay with the car")
            return
        }
        setProperty(GuangqiVolumeHook.PROP_PID, Process.myPid().toString())
        register(app)
        runCatching {
            app.sendBroadcast(Intent(GuangqiVolumeHook.ACTION_INSTALL)
                .setClassName(app.packageName, GuangqiVolumeHookReceiver::class.java.name))
        }.onFailure { report("volume hook: install request failed: $it") }
        report("volume hook: asked the system process to install v${GuangqiVolumeHook.VERSION}")
    }

    private fun register(app: Context) {
        if (receiver != null) return
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching { onHook(app, intent) }.onFailure { Log.w(TAG, "hook broadcast failed", it) }
            }
        }
        val filter = IntentFilter().apply {
            addAction(GuangqiVolumeHook.ACTION_CAR_VOLUME)
            addAction(GuangqiVolumeHook.ACTION_HOOK_STATE)
        }
        runCatching { app.registerReceiver(created, filter) }
            .onSuccess { receiver = created }
            .onFailure { report("volume hook: could not listen for the hook: $it") }
    }

    private fun onHook(app: Context, intent: Intent) {
        val version = intent.getIntExtra(GuangqiVolumeHook.EXTRA_VERSION, 0)
        when (intent.action) {
            GuangqiVolumeHook.ACTION_HOOK_STATE -> {
                val installed = intent.getBooleanExtra(GuangqiVolumeHook.EXTRA_INSTALLED, false)
                var line = "volume hook: ${intent.getStringExtra(GuangqiVolumeHook.EXTRA_DETAIL)}"
                if (installed && version != GuangqiVolumeHook.VERSION) {
                    line += "; this DiPlay has v${GuangqiVolumeHook.VERSION}, restart the car to use it"
                }
                lastState = line
                report(line)
            }
            GuangqiVolumeHook.ACTION_CAR_VOLUME -> {
                val kind = intent.getIntExtra(GuangqiVolumeHook.EXTRA_KIND, GuangqiVolumeHook.KIND_PASS)
                val value = intent.getIntExtra(GuangqiVolumeHook.EXTRA_VALUE, -1)
                val step = intent.getIntExtra(GuangqiVolumeHook.EXTRA_STEP, 0)
                val reason = intent.getStringExtra(GuangqiVolumeHook.EXTRA_REASON)
                val signed = if (step > 0) "+$step" else "$step"
                when (kind) {
                    GuangqiVolumeHook.KIND_MEDIA, GuangqiVolumeHook.KIND_NAVI -> {
                        val navigation = kind == GuangqiVolumeHook.KIND_NAVI
                        CarVolumeBar.show(app, navigation, value, GuangqiVolumeHook.MAX_VOLUME)
                        report("volume hook: step $signed → ${if (navigation) "navi" else "media"} $value ($reason)")
                    }
                    else -> report("volume hook: step $signed left to the car ($reason)")
                }
            }
        }
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        runCatching { sessionLog?.invoke(message) }
    }

    private fun setProperty(key: String, value: String) {
        runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("set", String::class.java, String::class.java)
                .invoke(null, key, value)
        }.onFailure { report("volume hook: could not set $key=$value: $it") }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
