package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import com.shilapi.xcertplay.compat.realSdkInt
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * Shows CarPlay's instrument-cluster stream on the cluster's presentation display.
 *
 * On the Guangqi head unit the cluster is the only other presentation display; see [GuangqiCluster]
 * for when the cluster composites this layer.
 */
internal class ClusterMapPresentation(
    context: Context,
    display: Display,
    private var onSurface: (Surface?) -> Unit,
) : Presentation(context, display) {

    fun rebindSurfaceCallback(callback: (Surface?) -> Unit) {
        onSurface = callback
        outputSurface?.let(callback)
    }
    private var waitingLabel: TextView? = null
    private var turnCardView: ClusterTurnCardView? = null
    private var streamActive = false
    private var sessionActive = false
    var outputSurface: Surface? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Guangqi: the placeholder shows on the cluster from DiPlay's start, also at night, so it is dark.
        val guangqi = GuangqiCluster.present(context)
        val placeholder = if (guangqi) GUANGQI_PLACEHOLDER else Color.rgb(233, 238, 246)
        val root = FrameLayout(context).apply { setBackgroundColor(placeholder) }
        val videoParams = FrameLayout.LayoutParams(-1, -1)
        val surfaceView = SurfaceView(context)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "cluster surface created")
                outputSurface = holder.surface
                onSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "cluster surface ${width}x$height")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "cluster surface destroyed")
                onSurface(null)
                // SurfaceHolder owns this surface; do not release it ourselves.
                outputSurface = null
            }
        })
        root.addView(surfaceView, videoParams)
        waitingLabel = TextView(context).apply {
            text = waitingText()
            setTextColor(if (guangqi) GUANGQI_PLACEHOLDER_TEXT else Color.DKGRAY)
            setBackgroundColor(placeholder)
            textSize = 26f
            gravity = Gravity.CENTER
        }
        root.addView(waitingLabel, FrameLayout.LayoutParams(videoParams))
        turnCardView = ClusterTurnCardView(context).apply { visibility = View.GONE }
        root.addView(turnCardView, FrameLayout.LayoutParams(-1, -1))
        // Start with the placeholder visible; SurfaceView would otherwise cover the
        // backdrop with an empty black surface before the first stream notification.
        // SurfaceView ignores fractional alpha before API 34. Keep its decoder surface
        // visible and fade an opaque ordinary View over it instead.
        waitingLabel?.apply {
            alpha = if (streamActive) 0f else 1f
            visibility = if (streamActive) View.GONE else View.VISIBLE
        }
        setContentView(root)
    }

    /** What the placeholder says: no CarPlay yet, or CarPlay without its cluster stream yet. */
    fun setSessionActive(active: Boolean) {
        if (sessionActive == active) return
        sessionActive = active
        waitingLabel?.text = waitingText()
    }

    private fun waitingText(): String =
        context.getString(if (sessionActive) R.string.cluster_waiting_for_map else R.string.cluster_waiting_for_carplay)

    /** Cross-fades the waiting placeholder over the live map; the SurfaceView stays visible under it. */
    fun setStreamActive(active: Boolean) {
        if (streamActive == active) return
        // Update before cancelling: a cancelled fade must not hide the newly restored label.
        streamActive = active
        val label = waitingLabel ?: return
        label.animate().cancel()
        label.visibility = View.VISIBLE
        // Animate from the current opacity so a stream reversal does not jump or restart.
        label.animate().alpha(if (active) 0f else 1f).setDuration(300).withEndAction {
            if (streamActive == active) label.visibility = if (active) View.GONE else View.VISIBLE
        }.start()
    }

    fun setTurnCardOverlay(xPercent: Int, yPercent: Int, sizePercent: Int) {
        turnCardView?.setLayout(xPercent, yPercent, sizePercent)
    }

    fun setTurnCardNightMode(night: Boolean) {
        turnCardView?.setNightMode(night)
    }

    fun setTurnCardOpacity(percent: Int) {
        turnCardView?.setOpacity(percent)
    }

    fun setTurnCardGuidance(guidance: ClusterTurnGuidance?) {
        turnCardView?.setGuidance(guidance)
    }

    companion object {
        const val TAG = "DiPlay-Cluster"
        private val GUANGQI_PLACEHOLDER = Color.rgb(8, 10, 14)
        private val GUANGQI_PLACEHOLDER_TEXT = Color.rgb(96, 104, 116)

        /**
         * Guangqi: the cluster Presentation outlives the Activity so it keeps showing when the
         * user presses Home. The process stays alive (system uid + lwIP), so a static reference
         * is safe. [retainGuangqi] stores the Presentation here; [reclaimGuangqi] hands it back
         * to a new Activity; [releaseGuangqi] dismisses it for real (CarPlay session ended).
         */
        @Volatile private var retainedGuangqi: ClusterMapPresentation? = null

        fun retainGuangqi(presentation: ClusterMapPresentation) { retainedGuangqi = presentation }
        fun reclaimGuangqi(): ClusterMapPresentation? = retainedGuangqi?.takeIf { it.isShowing }
        fun releaseGuangqi() { retainedGuangqi?.let { runCatching { it.dismiss() } }; retainedGuangqi = null }

        /** The cluster is the first other presentation display; on Guangqi it is the only one ("HDMI 屏幕"). */
        fun findDisplay(context: Context): Display? {
            val displays = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION) ?: return null
            return GuangqiCluster.findDisplay(displays)
        }

        private fun displayManager(context: Context): DisplayManager? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                if (realSdkInt() >= Build.VERSION_CODES.M) {
                    context.getSystemService(DisplayManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                }
            } else null

        fun describeDisplays(context: Context): String =
            displayManager(context)?.displays
                ?.joinToString {
                    val size = sizeOf(it)
                    "${it.displayId}:${it.name} ${size.x}x${size.y} flags=${it.flags} valid=${it.isValid}"
                }.orEmpty()

        fun diagnosticReport(context: Context): String = buildString {
            appendLine("clusterEnabled=${AirPlayPersistence.loadClusterMapEnabled(context)}")
            appendLine("allDisplays=${describeDisplays(context)}")
            val presentations = displayManager(context)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).orEmpty()
            appendLine("presentationDisplayIds=${presentations.joinToString { it.displayId.toString() }}")
            val selected = findDisplay(context)
            append("selectedCluster=${selected?.let { "${it.displayId}:${it.name}" } ?: "none"}")
        }

        fun sizeOf(display: Display): Point = Point().also {
            @Suppress("DEPRECATION")
            display.getRealSize(it)
        }
    }
}
