package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GuangqiClusterTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun readyIsReportedTheAmapWayToTheClusterServiceOnly() {
        installClusterService()
        val logs = mutableListOf<String>()
        val reporter = GuangqiCluster.ReadyReporter(context, logs::add)

        reporter.update(true)
        reporter.update(true)

        val sent = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
        assertEquals(listOf(GuangqiCluster.STATE_INIT, GuangqiCluster.STATE_FOREGROUND), sent.map { it.getIntExtra("EXTRA_STATE", -1) })
        for (intent in sent) {
            assertEquals("AUTONAVI_STANDARD_BROADCAST_SEND", intent.action)
            assertEquals("gaei.cluster.service", intent.`package`)
            assertEquals(10019, intent.getIntExtra("KEY_TYPE", -1))
        }
        assertTrue(reporter.reported)
        assertEquals(1, logs.size)

        reporter.update(false)
        assertEquals(GuangqiCluster.STATE_EXIT, sent.last().getIntExtra("EXTRA_STATE", -1))
        assertEquals(3, sent.size)
        assertFalse(reporter.reported)
    }

    @Test fun otherHeadUnitsNeverReportReady() {
        val reporter = GuangqiCluster.ReadyReporter(context) {}
        reporter.update(true)
        assertFalse(reporter.reported)
        assertTrue(shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.isEmpty())
    }

    @Test fun guangqiDefaultsLeaveVpuRoomForTheClusterStream() {
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(context))
        assertEquals(CarPlayClusterDisplay.STREAM_SCALE_PERCENT, AirPlayPersistence.loadClusterMapScalePercent(context))
        val upstreamResolution = AirPlayPersistence.loadDisplayScalePercent(context)

        installClusterService()

        assertTrue(AirPlayPersistence.loadClusterMapEnabled(context))
        assertEquals(GuangqiCluster.CLUSTER_SCALE_PERCENT, AirPlayPersistence.loadClusterMapScalePercent(context))
        assertEquals(GuangqiCluster.MAIN_RESOLUTION_PERCENT, AirPlayPersistence.loadDisplayScalePercent(context))
        assertEquals(30, AirPlayPersistence.loadFps(context))
        assertEquals(100, upstreamResolution)

        // The driver's own choices still win.
        AirPlayPersistence.saveDisplayScalePercent(context, 100)
        assertEquals(100, AirPlayPersistence.loadDisplayScalePercent(context))
    }

    @Test fun clusterStreamIsRequestedAt4FpsAndNativeResolution() {
        val config = CarPlayClusterDisplay.config(
            1280, 720, GuangqiCluster.CLUSTER_SCALE_PERCENT, fps = GuangqiCluster.CLUSTER_FPS,
        )
        assertEquals(4, config.fps)
        assertEquals(1280, config.widthPixels)
        assertEquals(720, config.heightPixels)
        assertEquals(CarPlayClusterDisplay.FPS, CarPlayClusterDisplay.config(1280, 720).fps)
    }

    @Test fun clusterSafeAreaIsTheMeasuredAreaScaledToTheStream() {
        val full = GuangqiCluster.safeArea(1280, 720)
        assertEquals(AirPlayInsets(top = 82, bottom = 88, left = 158, right = 158), full)
        assertEquals(964, 1280 - full.left - full.right)
        assertEquals(550, 720 - full.top - full.bottom)
        assertEquals(AirPlayInsets(top = 55, bottom = 59, left = 106, right = 106), GuangqiCluster.safeArea(856, 480))
    }

    @Test fun testOverridesChangeOnlyWhatIsSet() {
        val base = CarPlayClusterDisplay.config(1280, 720, 67, fps = 15)
        val none = GuangqiCluster.Tuning.apply(base) { "" }
        assertEquals(base, none.first)
        assertEquals("", none.second)

        val props = mapOf(
            GuangqiCluster.Tuning.MM to "155",
            GuangqiCluster.Tuning.URL to "instruments",
            GuangqiCluster.Tuning.SAFE to "158,82,158,88",
        )
        val (tuned, used) = GuangqiCluster.Tuning.apply(base) { props[it] ?: "" }
        assertEquals(155, tuned.widthPhysicalMm)
        assertEquals(87, tuned.heightPhysicalMm)
        assertEquals("maps:/car/instrumentcluster", tuned.initialUrl)
        assertEquals(base.fps, tuned.fps)
        assertEquals(GuangqiCluster.safeArea(856, 480), tuned.safeArea)
        assertTrue(used.contains("mm=155") && used.contains("url=maps:/car/instrumentcluster"))
    }

    private fun installClusterService() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = GuangqiCluster.SERVICE_PACKAGE
            applicationInfo = ApplicationInfo().apply {
                packageName = GuangqiCluster.SERVICE_PACKAGE
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
    }
}
