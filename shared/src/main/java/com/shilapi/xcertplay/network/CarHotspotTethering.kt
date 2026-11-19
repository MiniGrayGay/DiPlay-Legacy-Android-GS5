package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import com.shilapi.xcertplay.compat.isReflectionFailure
import com.shilapi.xcertplay.compat.realSdkInt
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.Settings
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Uses the car's saved hotspot configuration. This never stops or reconfigures the hotspot. */
object CarHotspotTethering {
    private val startupLock = ReentrantLock()
    enum class Result(val diagnostic: String) {
        READY("Car hotspot is on"),
        PERMISSION_REQUIRED("Hotspot control permission is missing"),
        UNSUPPORTED("This firmware does not support automatic hotspot startup"),
        FAILED("The car could not start its hotspot"),
        TIMED_OUT("Timed out waiting for the car hotspot"),
        CANCELLED("Hotspot startup was cancelled"),
    }

    fun permitted(context: Context): Boolean = realSdkInt() < 23 || Settings.System.canWrite(context)

    /** Blocking; serialize startup and connection requests, checking cancellation after acquiring the lock. */
    @android.annotation.SuppressLint("SoonBlockedPrivateApi")
    fun enable(
        context: Context,
        isCancelled: () -> Boolean,
        timeoutMillis: Long = WirelessStartupPolicy.HOTSPOT_READY_MILLIS,
        log: (String) -> Unit,
    ): Result {
        disableWifiBeforeHotspot(context, log)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val startReflection: (ResultReceiver) -> Unit = { receiver ->
            if (realSdkInt() >= 37) throw NoSuchMethodException("Hidden tethering service is unavailable on API 37+")
            val conn = if (realSdkInt() >= Build.VERSION_CODES.M) {
                context.getSystemService(ConnectivityManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            }
            try {
                val service = ConnectivityManager::class.java.getDeclaredField("mService")
                    .apply { isAccessible = true }
                    .get(conn)
                    ?: throw NoSuchMethodException("Connectivity service unavailable")
                service.javaClass.getMethod(
                    "startTethering", Int::class.javaPrimitiveType, ResultReceiver::class.java,
                    Boolean::class.javaPrimitiveType, String::class.java,
                ).invoke(service, 0, receiver, false, context.packageName)
            } catch (error: Exception) {
                // ReflectiveOperationException is API 19 and cannot appear in a catch clause
                // on Android 4.2 (VerifyError for this whole class), so classify by name.
                if (!error.isReflectionFailure()) throw error
                // T3-class ROMs may lack the tethering service. Fall back to
                // the pre-O WifiManager.setWifiApEnabled hidden API (P0-3).
                // It exists on API 19; on newer ROMs it throws and startup is reported as unsupported.
                startWifiApFallback(context, receiver)
            }
        }
        return enableUntil(
            deadline,
            isCancelled,
            { permitted(context) },
            { CarHotspotStatus.isEnabled(context) },
            start = startReflection,
        ).also { log("car hotspot auto-enable: ${it.diagnostic}") }
    }

    /**
     * Fallback for ROMs without the tethering service: the pre-O hidden
     * `WifiManager.setWifiApEnabled` API. Passing null config reuses the
     * hotspot configuration the user already saved in system settings, so
     * no SSID/password handling is needed here.
     */
    private fun startWifiApFallback(context: Context, receiver: ResultReceiver) {
        val wifi = if (realSdkInt() >= Build.VERSION_CODES.M) {
            context.getSystemService(android.net.wifi.WifiManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        } ?: throw NoSuchMethodException("WifiManager unavailable")
        val ok = android.net.wifi.WifiManager::class.java.getMethod(
            "setWifiApEnabled",
            android.net.wifi.WifiConfiguration::class.java,
            Boolean::class.javaPrimitiveType,
        ).invoke(wifi, null, true) as? Boolean ?: false
        if (!ok) throw NoSuchMethodException("setWifiApEnabled returned false")
        // 0 = no error; the caller polls isEnabled() until the AP comes up.
        receiver.send(0, null)
    }

    /**
     * Issue #172: on some head units the Wi-Fi station and the hotspot AP conflict,
     * so starting the AP with Wi-Fi still on fails silently at boot. The user-verified
     * flow is to turn Wi-Fi off (twice, ~1s apart) before starting the hotspot; mirror
     * it here. This only runs in the head-unit-as-AP autostart path, and any failure
     * just falls through to the normal AP startup, so units without the conflict
     * behave exactly as before.
     */
    private fun disableWifiBeforeHotspot(context: Context, log: (String) -> Unit) {
        val wifi = if (realSdkInt() >= Build.VERSION_CODES.M) {
            context.getSystemService(android.net.wifi.WifiManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        } ?: return
        repeat(2) { attempt ->
            val enabled = runCatching { wifi.isWifiEnabled }.getOrNull() ?: return
            if (!enabled) {
                log("car hotspot: wifi already off before AP start")
                return
            }
            if (runCatching { wifi.setWifiEnabled(false) }.getOrNull() != true) {
                log("car hotspot: could not turn wifi off (attempt ${attempt + 1})")
                return
            }
            val waitUntil = SystemClock.elapsedRealtime() + 1_000L
            while (runCatching { wifi.isWifiEnabled }.getOrDefault(true) &&
                SystemClock.elapsedRealtime() < waitUntil) {
                try {
                    Thread.sleep(100L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
            if (runCatching { wifi.isWifiEnabled }.getOrDefault(true)) {
                log("car hotspot: wifi still on after turn-off (attempt ${attempt + 1})")
            }
        }
        log("car hotspot: wifi turned off before AP start")
    }

    internal fun enable(
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        start: (ResultReceiver) -> Unit,
    ): Result = enableUntil(System.nanoTime() + timeoutMillis * 1_000_000L,
        isCancelled, canWrite, isEnabled, start)

    private fun enableUntil(
        deadline: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        start: (ResultReceiver) -> Unit,
    ): Result {
        fun stopped(): Result? = when {
            isCancelled() || Thread.currentThread().isInterrupted -> Result.CANCELLED
            System.nanoTime() >= deadline -> Result.TIMED_OUT
            else -> null
        }
        while (true) {
            stopped()?.let { return it }
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) return Result.TIMED_OUT
            try {
                if (startupLock.tryLock(minOf(250L, remaining), TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result.CANCELLED
            }
        }
        try {
            stopped()?.let { return it }
            val initialState = isEnabled()
            stopped()?.let { return it }
            if (initialState == true) return Result.READY
            val permission = canWrite()
            stopped()?.let { return it }
            if (!permission) return Result.PERMISSION_REQUIRED
            // Never start blindly: without a readable AP state there is no way to confirm the result.
            if (initialState == null) return stopped() ?: Result.UNSUPPORTED
            val response = AtomicInteger(-1)
            try {
                stopped()?.let { return it }
                start(object : ResultReceiver(null) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        response.set(resultCode)
                    }
                })
            } catch (error: Exception) {
                stopped()?.let { return it }
                return when {
                    error is InvocationTargetException -> if (error.targetException is SecurityException) Result.PERMISSION_REQUIRED else Result.FAILED
                    error.isReflectionFailure() -> Result.UNSUPPORTED
                    error is SecurityException -> Result.PERMISSION_REQUIRED
                    else -> Result.FAILED
                }
            }
            while (true) {
                stopped()?.let { return it }
                val enabled = isEnabled()
                stopped()?.let { return it }
                if (enabled == true) return Result.READY
                if (response.get() > 0) return Result.FAILED
                val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMillis <= 0) return Result.TIMED_OUT
                try {
                    Thread.sleep(minOf(250L, remainingMillis))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return Result.CANCELLED
                }
            }
        } finally {
            startupLock.unlock()
        }
    }
}
