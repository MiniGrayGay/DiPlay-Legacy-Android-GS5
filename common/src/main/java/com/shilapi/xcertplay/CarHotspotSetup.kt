package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.compat.realSdkInt
import android.content.pm.ApplicationInfo
import android.os.Build
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/** Whether DiPlay may start the car's own hotspot for the driver. */
internal object CarHotspotSetup {
    // Only expose the direct path on the firmware family verified in #322.
    fun isLegacyEcarxHeadUnit(context: Context): Boolean =
        realSdkInt() == 22 && Build.DEVICE.equals("xea406m", ignoreCase = true) && runCatching {
            context.packageManager.getApplicationInfo("ecarx.settings", 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
        }.getOrDefault(false)

    // Legacy Android (API <= 22) and the verified ECARX units let an app start the hotspot itself.
    fun isDirectHotspotSupported(context: Context): Boolean =
        realSdkInt() <= 22 || isLegacyEcarxHeadUnit(context)

    fun shouldStartOnLaunch(context: Context, hasSession: Boolean): Boolean =
        !hasSession && CarHotspotSettings.shouldEnable(context,
            AirPlayPersistence.loadWirelessEnabled(context), AirPlayPersistence.loadWirelessHotspotMode(context)) &&
            ManualHotspotValidation.error(AirPlayPersistence.loadManualHotspotSsid(context),
                AirPlayPersistence.loadManualHotspotPassphrase(context)) == null
}
