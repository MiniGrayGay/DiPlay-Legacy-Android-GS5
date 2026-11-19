package com.shilapi.xcertplay.orchestration

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.hud.NavigationOutputs
import com.shilapi.xcertplay.hud.WheelIntegrationSettings
import com.shilapi.xcertplay.hud.CarPlayCallCard
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

/**
 * The Guangqi head unit's steering-wheel call keys during CarPlay, taken the way the stock CarLife
 * takes them.
 *
 * The wheel's call key (137), hang-up key (138) and the voice key's long press (injected as 57)
 * are handled in `PhoneWindowManager.interceptKeyBeforeQueueing` and never reach an app. While
 * the system believes CarLife is connected they go to the one key listener registered with the
 * `keyback` service under CarLife's name instead:
 *
 * - `KeyBackManager.registerKeyInfo(listener, looper, "com.thundersoft.connectivity.AudioRsManager")`
 * - broadcast `com.thundersoft.connectivity.ConnectivityManager` with `state` 2 (0 to end), which
 *   sets `mCarlifeConnState`;
 * - broadcast `com.baidu.carlifevehicle.bluetoothHfpClient` with `blockTelFlag` 1 (0 to end), so
 *   137 and 138 go to that listener even when CarLife is not in front. The stock phone app also
 *   hides its own call screen while the flag is set.
 *
 * The same broadcasts reach the USB music app, SystemUI and the cluster service. A short press on
 * the voice key still starts the stock voice assistant; the system does not pass it on.
 *
 * Once a CarPlay session is up the stock CarLife is force-stopped: it holds the same listener slot
 * while its process lives, and the service rejects a second registration under that name. Its
 * death clears the slot. DiPlay runs as `android.uid.system`, which may force-stop packages.
 *
 * Taking the keys is opt-in with the CarPlay call keys setting. The system keeps the connected
 * state if this process dies, so the hold is remembered and undone the next time a controller
 * starts. All work runs on one worker thread, in order.
 */
object GuangqiCarlifeKeys {
    enum class Action { NONE, ANSWER, END, SIRI }

    /** What a released wheel key does with the iPhone's [call]. */
    fun action(keyCode: Int, call: CarPlayCallCard?): Action = when (keyCode) {
        KEY_CALL -> if (call?.phase == CarPlayCallCard.Phase.RINGING) Action.ANSWER else Action.NONE
        KEY_HANG_UP -> if (call != null) Action.END else Action.NONE
        KEY_VOICE_LONG -> Action.SIRI
        else -> Action.NONE
    }

    @Volatile private var controller: CarPlayController? = null
    @Volatile private var holding = false
    private var listener: Any? = null
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "diplay-gq-keys").apply { isDaemon = true } }

    /** A CarPlay session is up: stops the stock CarLife, then takes the keys when the setting is on. */
    fun sessionStarted(context: Context, owner: CarPlayController) {
        val app = context.applicationContext
        controller = owner
        worker.execute {
            if (app.getSystemService(KEYBACK_SERVICE) == null) return@execute
            stopStockCarlife(app)
            if (controller === owner && WheelIntegrationSettings.carPlayCallControls(app)) hold(app)
        }
    }

    /** The session [owner] ran has ended; gives the keys back. */
    fun sessionEnded(context: Context, owner: CarPlayController) {
        if (controller !== owner) return
        controller = null
        val app = context.applicationContext
        worker.execute { release(app, "session ended") }
    }

    /** The setting changed; applies to a running session at once. */
    fun settingChanged(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        worker.execute {
            if (!enabled) release(app, "setting off")
            else if (controller != null) {
                stopStockCarlife(app)
                hold(app)
            }
        }
    }

    /** Undoes a hold this app left behind when its process died. */
    fun recoverAbandoned(context: Context) {
        val app = context.applicationContext
        worker.execute { recover(app) }
    }

    private fun recover(app: Context) {
        if (holding || !prefs(app).getBoolean(KEY_HOLDING, false)) return
        Log.i(TAG, "recover abandoned hold")
        if (stockCarlifeRunning(app)) {
            // The stock CarLife owns the state now; do not tell the system it disconnected.
            prefs(app).edit().putBoolean(KEY_HOLDING, false).commit()
            return
        }
        broadcast(app, connected = false)
        prefs(app).edit().putBoolean(KEY_HOLDING, false).commit()
    }

    private fun hold(app: Context) {
        if (holding) return
        val manager = app.getSystemService(KEYBACK_SERVICE) ?: run {
            Log.i(TAG, "no keyback service; not a Guangqi head unit")
            return
        }
        if (stockCarlifeRunning(app)) {
            Log.w(TAG, "stock CarLife is running and owns the keys; not taking them")
            return
        }
        val registered = runCatching {
            val type = Class.forName(LISTENER_CLASS)
            manager.javaClass.getMethod("registerKeyInfo", type, Looper::class.java, String::class.java)
                .invoke(manager, listener(type), Looper.getMainLooper(), CARLIFE_KEY_NAME) as? Boolean ?: true
        }.onFailure { Log.w(TAG, "registerKeyInfo failed", it) }.getOrDefault(false)
        if (!registered) return
        // Remembered before the system state changes, so a crash right after is still undone.
        prefs(app).edit().putBoolean(KEY_HOLDING, true).commit()
        holding = true
        broadcast(app, connected = true)
        Log.i(TAG, "wheel call keys taken")
    }

    private fun release(app: Context, reason: String) {
        if (!holding) return
        holding = false
        broadcast(app, connected = false)
        runCatching {
            val manager = app.getSystemService(KEYBACK_SERVICE) ?: return@runCatching
            manager.javaClass.getMethod("removeKeyInfo").invoke(manager)
        }.onFailure { Log.w(TAG, "removeKeyInfo failed", it) }
        prefs(app).edit().putBoolean(KEY_HOLDING, false).commit()
        Log.i(TAG, "wheel call keys released ($reason)")
    }

    /** Force-stops the stock CarLife and waits for its process, and so its key listener, to go. */
    private fun stopStockCarlife(app: Context) {
        if (!stockCarlifeRunning(app)) return
        val stopped = runCatching {
            val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.javaClass.getMethod("forceStopPackage", String::class.java).invoke(manager, STOCK_CARLIFE_PACKAGE)
        }.onFailure { Log.w(TAG, "force-stop of the stock CarLife failed", it) }.isSuccess
        if (!stopped) return
        var waited = 0L
        while (stockCarlifeRunning(app) && waited < STOP_WAIT_MILLIS) {
            Thread.sleep(STOP_POLL_MILLIS)
            waited += STOP_POLL_MILLIS
        }
        // The key service drops the listener from a death notification that follows the process.
        Thread.sleep(STOP_POLL_MILLIS)
        Log.i(TAG, "stock CarLife stopped after ${waited}ms running=${stockCarlifeRunning(app)}")
    }

    private fun broadcast(app: Context, connected: Boolean) {
        runCatching {
            app.sendBroadcast(Intent(ACTION_CARLIFE_STATE).putExtra(EXTRA_STATE, if (connected) 2 else 0))
            app.sendBroadcast(Intent(ACTION_CARLIFE_CALL).putExtra(EXTRA_BLOCK_TEL, if (connected) 1 else 0))
        }.onFailure { Log.w(TAG, "CarLife state broadcast failed", it) }
    }

    private fun onKeyUp(keyCode: Int) {
        val owner = controller ?: return
        val action = action(keyCode, NavigationOutputs.carPlayCall())
        val sent = when (action) {
            Action.NONE -> return
            Action.ANSWER -> owner.answerCall()
            Action.END -> owner.endCall()
            Action.SIRI -> owner.requestSiri()
        }
        Log.i(TAG, "wheel key $keyCode: $action sent=$sent")
    }

    /** One listener for the life of the process: KeyBackManager keeps the first one it was given. */
    private fun listener(type: Class<*>): Any = listener ?: Proxy.newProxyInstance(
        type.classLoader, arrayOf(type),
    ) { proxy, method, args ->
        when (method.name) {
            "onKeyUp" -> { (args?.firstOrNull() as? Int)?.let(::onKeyUp); null }
            "onKeyDown" -> null
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "DiPlayWheelCallKeys"
            else -> null
        }
    }.also { listener = it }

    private fun stockCarlifeRunning(app: Context): Boolean = runCatching {
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses.orEmpty()
            .any { it.processName == STOCK_CARLIFE_PROCESS }
    }.getOrDefault(false)

    private fun prefs(app: Context) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val KEY_CALL = 137
    const val KEY_HANG_UP = 138
    const val KEY_VOICE_LONG = 57

    private const val TAG = "DiPlay-GQ-Keys"
    private const val KEYBACK_SERVICE = "keyback"
    private const val LISTENER_CLASS = "android.vehicle.BackKeyListener"
    private const val CARLIFE_KEY_NAME = "com.thundersoft.connectivity.AudioRsManager"
    private const val STOCK_CARLIFE_PROCESS = "com.thundersoft.connectivity"
    private const val STOCK_CARLIFE_PACKAGE = "com.thundersoft.connectivity"
    private const val STOP_WAIT_MILLIS = 2_000L
    private const val STOP_POLL_MILLIS = 200L
    private const val ACTION_CARLIFE_STATE = "com.thundersoft.connectivity.ConnectivityManager"
    private const val EXTRA_STATE = "state"
    private const val ACTION_CARLIFE_CALL = "com.baidu.carlifevehicle.bluetoothHfpClient"
    private const val EXTRA_BLOCK_TEL = "blockTelFlag"
    private const val PREFS = "guangqi_carlife_keys"
    private const val KEY_HOLDING = "holding"
}
