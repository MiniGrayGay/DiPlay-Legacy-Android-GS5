package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.compat.realSdkInt
import java.io.Closeable

/** Session-scoped Wi-Fi power policy; failure on a vendor ROM must not prevent CarPlay. */
internal class WirelessPerformanceLock(context: Context, private val log: (String) -> Unit) : Closeable {
    private val appContext = context.applicationContext
    private val locks = mutableListOf<WifiManager.WifiLock>()

    @Synchronized
    fun acquire() {
        if (locks.isNotEmpty()) return
        try {
            val manager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            acquire(manager, WifiManager.WIFI_MODE_FULL_HIGH_PERF, "diplay:high-performance")
            // Use the real OS version: some legacy ROMs spoof a newer SDK.
            if (realSdkInt() >= 29) {
                acquire(manager, WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "diplay:low-latency")
            }
        } catch (error: Exception) {
            log("Wireless Wi-Fi performance policy unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun acquire(manager: WifiManager, mode: Int, tag: String) {
        try {
            val lock = manager.createWifiLock(mode, tag)
            lock.setReferenceCounted(false)
            lock.acquire()
            locks.add(lock)
            log("Wireless Wi-Fi lock acquired: $tag")
        } catch (error: Exception) {
            log("Wireless Wi-Fi lock unavailable: $tag: ${error.javaClass.simpleName}")
        }
    }

    @Synchronized
    override fun close() {
        locks.forEach { lock ->
            try {
                if (lock.isHeld) lock.release()
            } catch (error: Exception) {
                log("Wireless Wi-Fi lock release failed: ${error.javaClass.simpleName}")
            }
        }
        locks.clear()
    }
}
