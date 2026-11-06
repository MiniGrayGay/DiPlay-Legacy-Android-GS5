package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.Display
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
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

    /** Cluster stream size default: native resolution of the 1280x720 panel. */
    const val CLUSTER_SCALE_PERCENT = 100

    /** Main-screen resolution default: native 1280x720. */
    const val MAIN_RESOLUTION_PERCENT = 100

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

    /**
     * Cluster declaration overrides for testing on the car without a new build. Set as root, e.g.
     * `setprop debug.diplay.cl.mm 155`, then reconnect CarPlay; `setprop debug.diplay.cl.mm ""` clears.
     * They apply only on the Guangqi head unit and are logged at connection.
     */
    object Tuning {
        const val MM = "debug.diplay.cl.mm"         // physical width in mm, 50..400
        const val URL = "debug.diplay.cl.url"       // map | card | instruments | a maps:/ URL
        const val SAFE = "debug.diplay.cl.safe"     // "left,top,right,bottom" on the 1280x720 layer
        val ALL = listOf(MM, URL, SAFE)

        fun systemProperty(name: String): String = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, name) as? String ?: ""
        } catch (_: Exception) {
            ""
        }

        /** [config] with the overrides that are set; the overrides listed in the second value. */
        fun apply(config: AirPlayDisplayConfig, props: (String) -> String): Pair<AirPlayDisplayConfig, String> {
            var out = config
            val used = mutableListOf<String>()
            props(MM).trim().toIntOrNull()?.coerceIn(50, 400)?.let { mm ->
                out = out.copy(widthPhysicalMm = mm,
                    heightPhysicalMm = Math.round(mm * out.heightPixels.toDouble() / out.widthPixels).toInt())
                used += "mm=$mm"
            }
            props(URL).trim().takeIf { it.isNotEmpty() }?.let { value ->
                val url = when (value) {
                    "map" -> "maps:/car/instrumentcluster/map"
                    "card" -> "maps:/car/instrumentcluster/instructioncard"
                    "instruments" -> "maps:/car/instrumentcluster"
                    else -> value.takeIf { it.startsWith("maps:/") }
                }
                if (url != null) { out = out.copy(initialUrl = url); used += "url=$url" }
            }
            props(SAFE).split(',').map { it.trim().toIntOrNull() }.takeIf { it.size == 4 && it.all { v -> v != null && v >= 0 } }
                ?.let { (l, t, r, b) ->
                    fun x(v: Int?) = Math.round(v!! * out.widthPixels.toDouble() / LAYER_WIDTH).toInt()
                    fun y(v: Int?) = Math.round(v!! * out.heightPixels.toDouble() / LAYER_HEIGHT).toInt()
                    out = out.copy(safeArea = AirPlayInsets(top = y(t), bottom = y(b), left = x(l), right = x(r)))
                    used += "safe=$l,$t,$r,$b"
                }
            return out to used.joinToString(" ")
        }
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
