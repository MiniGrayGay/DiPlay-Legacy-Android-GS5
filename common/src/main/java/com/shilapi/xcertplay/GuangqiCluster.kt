package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.Display
import com.shilapi.xcertplay.airplay.AirPlayInsets

/**
 * The Guangqi (GAEI AVNTG2A10) instrument cluster.
 *
 * The cluster is the head unit's only non-default PRESENTATION display (1280x720), and
 * `gaei.cluster.service` decides whether the cluster composites it. Until a navigation source
 * reports ready, the cluster MCU draws its own "导航功能未启动" placeholder instead.
 * Reporting ready the AMap way (KEY_TYPE 10019, EXTRA_STATE 1 then 3) was tested on the car: it
 * does not launch AMap, and the cluster then shows our layer. The Tencent way (KEY_TYPE 2000)
 * makes the service launch com.tencent.wecarnavi, which then owns the cluster, so it is not used.
 *
 * The head unit's VPU decodes about 37 fps of 720p in total, so the cluster stream is requested
 * small and slow: the SurfaceFlinger alternates main and cluster composition anyway.
 */
internal object GuangqiCluster {
    const val SERVICE_PACKAGE = "gaei.cluster.service"
    const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
    const val KEY_TYPE_NAVI_STATE = 10019
    const val STATE_INIT = 1
    const val STATE_EXIT = 2
    const val STATE_FOREGROUND = 3

    /** Cluster stream rate; the Presentation display refreshes slowly, no need for more. */
    const val CLUSTER_FPS = 4

    /** Cluster stream size in percent of the panel (856x480 on 1280x720). */
    const val CLUSTER_SCALE_PERCENT = 67

    /** Main-screen resolution default (1024x576 on 1280x720), leaving VPU room for the cluster. */
    const val MAIN_RESOLUTION_PERCENT = 80

    /** The cluster layer the safe area was measured on. */
    const val LAYER_WIDTH = 1280
    const val LAYER_HEIGHT = 720

    /**
     * The area the gauges, clock and trip row leave clear, in pixels of the 1280x720 cluster layer.
     * Measured on the car with a ClusterTest grid: 976x549, exactly 16:9.
     */
    val SAFE_AREA = AirPlayInsets(top = 82, bottom = 89, left = 152, right = 152)

    /** [SAFE_AREA] scaled to a cluster stream of [width]x[height]. */
    fun safeArea(width: Int, height: Int): AirPlayInsets {
        fun x(value: Int) = Math.round(value * width.toDouble() / LAYER_WIDTH).toInt()
        fun y(value: Int) = Math.round(value * height.toDouble() / LAYER_HEIGHT).toInt()
        return AirPlayInsets(top = y(SAFE_AREA.top), bottom = y(SAFE_AREA.bottom),
            left = x(SAFE_AREA.left), right = x(SAFE_AREA.right))
    }

    private const val TAG = "DiPlay-GuangqiCluster"

    fun present(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SERVICE_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    /** The cluster: the first valid presentation display that is not the main screen. */
    fun findDisplay(presentationDisplays: Array<Display>): Display? =
        presentationDisplays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }

    fun stateIntent(state: Int): Intent = Intent(ACTION)
        .setPackage(SERVICE_PACKAGE)
        .putExtra("KEY_TYPE", KEY_TYPE_NAVI_STATE)
        .putExtra("EXTRA_STATE", state)

    /** Reports ready while the cluster shows the CarPlay stream, and exit when it stops. Main thread. */
    class ReadyReporter(private val context: Context, private val log: (String) -> Unit) {
        var reported = false
            private set

        fun update(ready: Boolean) {
            if (ready == reported) return
            if (ready && !present(context)) return
            reported = ready
            val states = if (ready) listOf(STATE_INIT, STATE_FOREGROUND) else listOf(STATE_EXIT)
            for (state in states) {
                try {
                    context.sendBroadcast(stateIntent(state))
                } catch (error: RuntimeException) {
                    Log.w(TAG, "cluster state $state broadcast failed", error)
                }
            }
            val line = "Cluster map: reported navigation ${if (ready) "ready (EXTRA_STATE 1, 3)" else "exit (EXTRA_STATE 2)"} to $SERVICE_PACKAGE"
            Log.i(TAG, line)
            log(line)
        }
    }
}
