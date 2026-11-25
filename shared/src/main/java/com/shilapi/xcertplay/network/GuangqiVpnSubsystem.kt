package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log

/**
 * Makes the Guangqi head unit's system VPN usable for the wired kernel TUN path.
 *
 * Two ROM defects stop `VpnService` there (see `docs/DiPlay-VPN-TUN修复记录.md` in the carlife tree):
 * - the ROM never broadcasts `USER_STARTING`, so `ConnectivityService.mVpns` stays empty and
 *   `prepare`/`establish` fail with a NullPointerException inside the service;
 * - `com.android.vpndialogs` is removed, so the consent screen from `VpnService.prepare` cannot open.
 *
 * As the system uid DiPlay may send that protected broadcast itself, which makes the service create
 * the user's VPN entry, and then grant itself the VPN with `IConnectivityManager.prepareVpn`. Neither
 * step needs root. Other head units are left alone: there the broadcast would restart a working
 * VPN table.
 */
object GuangqiVpnSubsystem {
    private const val TAG = "DiPlay-GQ-Vpn"
    private const val ACTION_USER_STARTING = "android.intent.action.USER_STARTING"
    private const val EXTRA_USER_HANDLE = "android.intent.extra.user_handle"
    private const val PREPARE_ATTEMPTS = 5
    private const val PREPARE_RETRY_MILLIS = 100L

    /** The Guangqi framework class, and DiPlay running as the system uid. */
    fun applies(): Boolean =
        Process.myUid() == Process.SYSTEM_UID &&
            runCatching { Class.forName("android.media.AudioSourceManager") }.isSuccess

    /** Grants DiPlay the VPN, first making the ROM create its VPN table if it has none. */
    fun prepare(context: Context, log: (String) -> Unit = {}): Boolean {
        if (!applies()) return false
        if (runCatching { prepareVpn(context.packageName) }.isSuccess) {
            log("Wired TUN: prepareVpn granted")
            return true
        }
        runCatching {
            context.sendBroadcast(Intent(ACTION_USER_STARTING).putExtra(EXTRA_USER_HANDLE, 0))
            log("Wired TUN: USER_STARTING sent so the ROM creates its VPN table")
        }.onFailure { error ->
            Log.w(TAG, "USER_STARTING broadcast failed", error)
            log("Wired TUN: USER_STARTING broadcast failed ${error.javaClass.simpleName}: ${error.message}")
        }
        // The service handles the broadcast on its own thread; prepareVpn fails until it has.
        var lastError: Throwable? = null
        repeat(PREPARE_ATTEMPTS) { attempt ->
            runCatching { Thread.sleep(PREPARE_RETRY_MILLIS) }
            val result = runCatching { prepareVpn(context.packageName) }
            if (result.isSuccess) {
                log("Wired TUN: prepareVpn granted ${(attempt + 1) * PREPARE_RETRY_MILLIS}ms after the broadcast")
                return true
            }
            lastError = result.exceptionOrNull()
        }
        val cause = lastError?.let { it.cause ?: it }
        Log.w(TAG, "prepareVpn failed", cause)
        log("Wired TUN: prepareVpn failed ${cause?.javaClass?.simpleName}: ${cause?.message}")
        return false
    }

    private fun prepareVpn(packageName: String) {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, Context.CONNECTIVITY_SERVICE) as IBinder
        val service = Class.forName("android.net.IConnectivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: error("no IConnectivityManager")
        val granted = service.javaClass.getMethod("prepareVpn", String::class.java, String::class.java)
            .invoke(service, null, packageName)
        if (granted == false) error("prepareVpn returned false")
    }
}
