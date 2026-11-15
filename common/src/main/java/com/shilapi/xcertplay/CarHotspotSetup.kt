package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.compat.realSdkInt
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/** Each grant is requested explicitly from settings; startup never calls this authorization path. */
internal object CarHotspotSetup {
    // Older BYD units report QUALCOMM/qti and have no supported navigation-output service.
    fun isBydHeadUnit(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationInfo("com.byd.carsettings", 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(false)

    // Only expose the direct path on the firmware family verified in #322. Do not
    // classify ECARX as BYD: that would also expose unrelated vehicle controls.
    fun isLegacyEcarxHeadUnit(context: Context): Boolean =
        realSdkInt() == 22 && Build.DEVICE.equals("xea406m", ignoreCase = true) && runCatching {
            context.packageManager.getApplicationInfo("ecarx.settings", 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
        }.getOrDefault(false)

    // Expose direct hotspot startup without ADB on legacy Android (API <= 22) or verified ECARX units.
    fun isDirectHotspotSupported(context: Context): Boolean =
        (realSdkInt() <= 22 && !isBydHeadUnit(context)) || isLegacyEcarxHeadUnit(context)

    fun bootLaunchNeedsAdb(context: Context): Boolean =
        CarHotspotSettings.shouldEnable(context, true, AirPlayPersistence.loadWirelessHotspotMode(context)) &&
            !isDirectHotspotSupported(context)

    enum class Permission(val appOp: String) {
        HOTSPOT("WRITE_SETTINGS"), BOOT_LAUNCH("SYSTEM_ALERT_WINDOW");

        fun granted(context: Context): Boolean = when (this) {
            HOTSPOT -> realSdkInt() < 23 || Settings.System.canWrite(context)
            BOOT_LAUNCH -> realSdkInt() < 23 || Settings.canDrawOverlays(context)
        }
    }

    fun check(context: Context, adb: LocalAdb = LocalAdb(AdbKeys.load(context))): LocalAdb.Access = adb.use {
        it.connect(mayAsk = false)
    }

    fun grant(context: Context, permissions: List<Permission>, adb: LocalAdb = LocalAdb(AdbKeys.load(context))): LocalAdb.Access =
        adb.use {
            val access = it.connect(mayAsk = true)
            Log.i("DiPlay-ADB", "switch connection: $access")
            if (access == LocalAdb.Access.READY) {
                for (permission in permissions) {
                    if (!permission.granted(context)) {
                        Log.i("DiPlay-ADB", "request permission: ${permission.appOp}")
                        it.shell("appops set ${context.packageName} ${permission.appOp} allow")
                    }
                    val granted = permission.granted(context)
                    Log.i("DiPlay-ADB", "permission ${permission.appOp}: granted=$granted")
                    if (!granted) break
                }
            }
            access
        }

    fun shouldStartOnLaunch(context: Context, hasSession: Boolean): Boolean =
        !hasSession && CarHotspotSettings.shouldEnable(context,
            AirPlayPersistence.loadWirelessEnabled(context), AirPlayPersistence.loadWirelessHotspotMode(context)) &&
            ManualHotspotValidation.error(AirPlayPersistence.loadManualHotspotSsid(context),
                AirPlayPersistence.loadManualHotspotPassphrase(context)) == null
}
