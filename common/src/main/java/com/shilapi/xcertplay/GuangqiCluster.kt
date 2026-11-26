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
    /** AMap's guidance broadcast: the cluster draws the turn, its distance and the route left itself. */
    const val KEY_TYPE_GUIDANCE = 10001
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
     * The area shown in full, in pixels of the 1280x720 cluster layer: outside it the cluster lays a
     * gradient over the layer. Measured on the car with a ClusterTest grid: 964x550.
     * The phone receives it as a rectangle (origin and size); insets keep it valid when the stream
     * is scaled.
     */
    val SAFE_AREA = AirPlayInsets(top = 82, bottom = 88, left = 158, right = 158)

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

    /**
     * CarPlay's next turn in AMap's guidance broadcast. `gaei.cluster.service` turns ICON (with
     * ROUND_ABOUT_NUM for roundabouts) into its own arrow through `/system/gaeiform/AutoNaviTbt.map`
     * and sends it, the distance and the route left to the cluster, which draws them where it draws
     * AMap's. [totalMeters] and [totalSeconds] are the route's size for the progress bar. Null
     * [guidance] clears what the cluster shows. No speed limit: the iPhone sends none.
     */
    fun guidanceIntent(guidance: com.shilapi.xcertplay.hud.ClusterTurnGuidance?, totalMeters: Long, totalSeconds: Long): Intent =
        Intent(ACTION).setPackage(SERVICE_PACKAGE).putExtra("KEY_TYPE", KEY_TYPE_GUIDANCE).apply {
            putExtra("ICON", guidance?.icon ?: 0)
            if (guidance != null && guidance.roundaboutExit > 0) {
                putExtra("ROUND_ABOUT_NUM", guidance.roundaboutExit)
                putExtra("ROUNG_ABOUT_NUM", guidance.roundaboutExit) // AMap's own misspelling, read as well
            }
            putExtra("SEG_REMAIN_DIS", guidance?.distanceMeters ?: 0)
            putExtra("NEXT_ROAD_NAME", guidance?.road.orEmpty())
            putExtra("CUR_ROAD_NAME", "")
            putExtra("ROUTE_REMAIN_DIS", guidance?.remainingMeters?.toInt() ?: 0)
            putExtra("ROUTE_REMAIN_TIME", guidance?.remainingSeconds?.toInt() ?: 0)
            putExtra("ROUTE_ALL_DIS", if (guidance == null) 0 else totalMeters.toInt())
            putExtra("ROUTE_ALL_TIME", if (guidance == null) 0 else totalSeconds.toInt())
            putExtra("CAMERA_TYPE", -1)
        }

    /** Sends each change of CarPlay's next turn to the cluster. Main thread. */
    class GuidanceSender(private val context: Context, private val log: (String) -> Unit) {
        private var active = false
        private var totalMeters = 0L
        private var totalSeconds = 0L

        fun update(guidance: com.shilapi.xcertplay.hud.ClusterTurnGuidance?, enabled: Boolean) {
            val shown = guidance?.takeIf { enabled && it.icon != 0 }
            if (shown == null && !active) return
            if (shown == null) {
                totalMeters = 0
                totalSeconds = 0
            } else {
                // A new route starts from its largest remainder: the progress bar is measured on it.
                totalMeters = maxOf(totalMeters, shown.remainingMeters ?: 0)
                totalSeconds = maxOf(totalSeconds, shown.remainingSeconds ?: 0)
            }
            try {
                context.sendBroadcast(guidanceIntent(shown, totalMeters, totalSeconds))
            } catch (error: RuntimeException) {
                Log.w(TAG, "cluster guidance broadcast failed", error)
                return
            }
            if (active != (shown != null)) {
                active = shown != null
                log(if (active) "Cluster map: turn-by-turn sent to the cluster" else "Cluster map: turn-by-turn cleared on the cluster")
            }
        }
    }

    /** Reports ready while DiPlay's cluster layer shows, and exit when it goes away. Main thread. */
    class ReadyReporter(private val context: Context, private val log: (String) -> Unit) {
        var reported = false
            private set

        fun update(ready: Boolean) {
            if (ready == reported) return
            send(ready)
        }

        /**
         * Says ready again while the layer shows. The cluster can lose it (a mode change on the car,
         * for one), and the layer, kept across DiPlay's own reconnects, never changed to say it again:
         * the placeholder stayed until a full disconnect. Called when a session or its cluster stream
         * starts.
         */
        fun refresh(ready: Boolean, why: String) {
            if (!ready) return update(false)
            if (!present(context)) return
            log("Cluster map: navigation ready again ($why)")
            send(true)
        }

        private fun send(ready: Boolean) {
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
