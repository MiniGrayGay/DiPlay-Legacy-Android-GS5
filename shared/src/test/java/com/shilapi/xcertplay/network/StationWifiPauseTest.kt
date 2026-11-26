package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowWifiInfo
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class StationWifiPauseTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val log = mutableListOf<String>()
    private val prefs get() = app.getSharedPreferences("station_wifi_pause", Context.MODE_PRIVATE)

    @Before fun connectToANetwork() {
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.setWifiEnabled(true)
        shadowOf(wifi).setConnectionInfo(ShadowWifiInfo.newInstance().also {
            shadowOf(it).setNetworkId(3)
            shadowOf(it).setSSID("\"Home\"")
            shadowOf(it).setSupplicantState(SupplicantState.COMPLETED)
        })
    }

    private val wifi get() = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @After fun forget() = StationWifiPause.reset()

    private fun idle(millis: Long = 0) = ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS)

    @Test fun countsDownThenDisablesTheNetworkAndEnablesItWhenTheDriverDisconnects() {
        val pause = StationWifiPause(app) { log += it }
        pause.offer()
        idle()
        val countdown = StationWifiPrompt.current
        assertNotNull(countdown)
        assertEquals("Home", countdown!!.ssid)

        idle(StationWifiPause.COUNTDOWN_MILLIS)
        assertNull("the prompt closes when the pause happens", StationWifiPrompt.current)
        assertTrue(prefs.getBoolean("paused", false))
        assertEquals("3", prefs.getString("networks", null))

        pause.end()
        idle()
        assertTrue("an automatic reconnect may follow", prefs.getBoolean("paused", false))
        StationWifiPause.finish(app) { log += it }
        idle()
        assertFalse(prefs.getBoolean("paused", false))
        assertTrue(log.toString(), log.any { it.contains("reconnect requested") && it.contains("disconnected") })
    }

    @Test fun anAutomaticReconnectKeepsThePauseWithoutAskingAgain() {
        val first = StationWifiPause(app) { log += it }
        first.offer()
        idle()
        StationWifiPrompt.current!!.pauseNow()
        idle()
        first.end()
        idle(5_000)
        StationWifiPause(app) { log += it }.offer()
        idle()
        assertNull("not asked again", StationWifiPrompt.current)
        assertTrue(log.toString(), log.any { it.contains("still paused") })
        idle(StationWifiPause.RESTORE_GRACE_MILLIS * 2)
        assertTrue("taken over, so not enabled", prefs.getBoolean("paused", false))
    }

    @Test fun withoutANextSessionTheNetworkComesBack() {
        val pause = StationWifiPause(app) { log += it }
        pause.offer()
        idle()
        StationWifiPrompt.current!!.pauseNow()
        idle()
        pause.end()
        idle(StationWifiPause.RESTORE_GRACE_MILLIS)
        assertFalse(prefs.getBoolean("paused", false))
    }

    @Test fun keepingLastsUntilTheDriverDisconnects() {
        val pause = StationWifiPause(app) { log += it }
        pause.offer()
        idle()
        StationWifiPrompt.current!!.keep()
        idle(StationWifiPause.COUNTDOWN_MILLIS * 2)
        assertNull(StationWifiPrompt.current)
        assertFalse(prefs.getBoolean("paused", false))

        pause.end()
        StationWifiPause(app) { log += it }.offer()
        idle()
        assertNull("kept across the automatic reconnect", StationWifiPrompt.current)

        StationWifiPause.finish(app) { log += it }
        idle()
        StationWifiPause(app) { log += it }.offer()
        idle()
        assertNotNull("asked again after a disconnect", StationWifiPrompt.current)
    }

    @Test fun nothingIsOfferedWithoutAClientConnection() {
        shadowOf(wifi).setConnectionInfo(ShadowWifiInfo.newInstance())
        StationWifiPause(app) { log += it }.offer()
        idle()
        assertNull(StationWifiPrompt.current)
    }

    @Test fun anAbandonedPauseIsEnabled() {
        prefs.edit().putBoolean("paused", true).putString("networks", "3").commit()
        StationWifiPause.recover(app) { log += it }
        idle()
        assertFalse(prefs.getBoolean("paused", false))
        assertTrue(log.toString(), log.any { it.contains("enabled") })
    }
}
