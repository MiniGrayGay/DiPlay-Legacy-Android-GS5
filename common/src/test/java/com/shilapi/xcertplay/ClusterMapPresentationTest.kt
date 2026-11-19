package com.shilapi.xcertplay

import android.hardware.display.DisplayManager
import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterMapPresentationTest {
    @Test fun repeatedActiveNotificationsDoNotRestartTheFade() = withPresentation { presentation ->
        presentation.setStreamActive(true)
        val label: android.widget.TextView = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
        label.alpha = 0.4f
        presentation.setStreamActive(true)
        assertEquals(0.4f, label.alpha, 0.001f)
    }

    @Test fun reversingTheFadeKeepsTheCurrentOpacityAndWaitingLabelVisible() = withPresentation { presentation ->
        presentation.setStreamActive(true)
        val label: android.widget.TextView = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
        label.alpha = 0.4f
        presentation.setStreamActive(false)
        assertEquals(android.view.View.VISIBLE, label.visibility)
        assertEquals(0.4f, label.alpha, 0.001f)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(android.view.View.VISIBLE, label.visibility)
    }

    @Test fun aNewPresentationStartsWithVisibleWaitingText() = withPresentation { presentation ->
        val label: android.widget.TextView = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
        assertEquals(1f, label.alpha, 0.001f)
        assertEquals(android.view.View.VISIBLE, label.visibility)
    }

    @Test @Config(sdk = [28, 29])
    fun surfaceUsesAnOpaquePlaceholderWithoutChangingSurfaceAlpha() = withPresentation { presentation ->
        val video = surfaceView(presentation.window!!.decorView) ?: error("The cluster must use a SurfaceView")
        val label: android.widget.TextView = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
        val background = label.background as android.graphics.drawable.ColorDrawable
        assertEquals(255, android.graphics.Color.alpha(background.color))
        assertEquals(android.graphics.Color.rgb(233, 238, 246), background.color)
        assertEquals(1f, video.alpha, 0.001f)
        presentation.setStreamActive(true)
        assertEquals(1f, video.alpha, 0.001f)
        label.alpha = 0.4f
        presentation.setStreamActive(false)
        assertEquals(1f, video.alpha, 0.001f)
        assertEquals(0.4f, label.alpha, 0.001f)
        assertEquals(android.view.View.VISIBLE, label.visibility)
    }

    private fun surfaceView(view: android.view.View): android.view.SurfaceView? {
        if (view is android.view.SurfaceView) return view
        if (view is android.view.ViewGroup) for (child in 0 until view.childCount) {
            surfaceView(view.getChildAt(child))?.let { return it }
        }
        return null
    }

    private fun withPresentation(block: (ClusterMapPresentation) -> Unit) {
        val id = display(CLUSTER, "w1280dp-h720dp-mdpi")
        val presentation = ClusterMapPresentation(context, manager.getDisplay(id)) {}
        try { presentation.create(); block(presentation) }
        finally { presentation.dismiss(); ShadowDisplayManager.removeDisplay(id) }
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private val CLUSTER = "HDMI 屏幕"
    private val manager get() = context.getSystemService(DisplayManager::class.java)

    private fun display(name: String, spec: String = "w960dp-h360dp"): Int {
        // Display.TYPE_VIRTUAL (5) is hidden from the public Android SDK.
        val id = ShadowDisplayManager.addDisplay(spec, 5)
        shadowOf(manager.getDisplay(id)).apply {
            setName(name)
            setFlags(Display.FLAG_PRESENTATION)
        }
        return id
    }

    @Test fun theFirstOtherPresentationDisplayIsTheCluster() {
        val id = display(CLUSTER, "w1280dp-h720dp-mdpi")
        try {
            assertEquals(id, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun theClusterRequiresThePresentationFlag() {
        val id = display(CLUSTER, "w1280dp-h720dp-mdpi")
        shadowOf(manager.getDisplay(id)).setFlags(0)
        try {
            assertNull(ClusterMapPresentation.findDisplay(context))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun disabledClusterStillReportsDisplay() {
        val enabled = AirPlayPersistence.loadClusterMapEnabled(context)
        AirPlayPersistence.saveClusterMapEnabled(context, false)
        val id = display(CLUSTER, "w1920dp-h720dp-mdpi")
        try {
            val report = ClusterMapPresentation.diagnosticReport(context)
            assertTrue(report.contains("clusterEnabled=false"))
            assertTrue(report.contains("1920x720"))
            assertTrue(report.contains("selectedCluster=$id:$CLUSTER"))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
            AirPlayPersistence.saveClusterMapEnabled(context, enabled)
        }
    }

    @Test fun diagnosticsDistinguishVisibleDisplayFromEligiblePresentation() {
        val id = display(CLUSTER, "w1920dp-h720dp-mdpi")
        shadowOf(manager.getDisplay(id)).setFlags(0)
        try {
            val report = ClusterMapPresentation.diagnosticReport(context)
            assertTrue(report.contains("$id:$CLUSTER"))
            assertTrue(report.contains("selectedCluster=none"))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun holderSurfaceIsReportedAsPhysicalOutputWithoutTakingItsOwnership() {
        val id = display(CLUSTER)
        val reported = mutableListOf<android.view.Surface?>()
        val presentation = ClusterMapPresentation(context, manager.getDisplay(id)) { reported += it }
        val surface = org.mockito.Mockito.mock(android.view.Surface::class.java)
        val holder = org.mockito.Mockito.mock(android.view.SurfaceHolder::class.java)
        org.mockito.Mockito.`when`(holder.surface).thenReturn(surface)
        try {
            presentation.create()
            val view = surfaceView(presentation.window!!.decorView) ?: error("The cluster must use a SurfaceView")
            val callbacks = (view.holder as org.robolectric.shadows.ShadowSurfaceView.FakeSurfaceHolder).callbacks
            callbacks.forEach { it.surfaceCreated(holder) }
            assertSame(surface, presentation.outputSurface)
            assertSame(surface, reported.last())
            callbacks.forEach { it.surfaceDestroyed(holder) }
            assertNull(presentation.outputSurface)
            assertNull(reported.last())
            org.mockito.Mockito.verify(surface, org.mockito.Mockito.never()).release()
        } finally {
            presentation.dismiss()
            ShadowDisplayManager.removeDisplay(id)
        }
    }

}
