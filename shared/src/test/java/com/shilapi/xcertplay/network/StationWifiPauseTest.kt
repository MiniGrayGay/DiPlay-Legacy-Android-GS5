package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowWifiInfo

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

    @Test fun countsDownThenPausesAndReconnectsAtTheEnd() {
        val pause = StationWifiPause(app) { log += it }
        pause.offer()
        ShadowLooper.idleMainLooper()
        val countdown = StationWifiPrompt.current
        assertNotNull(countdown)
        assertEquals("Home", countdown!!.ssid)

        ShadowLooper.idleMainLooper(StationWifiPause.COUNTDOWN_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertNull("the prompt closes when the pause happens", StationWifiPrompt.current)
        assertTrue(prefs.getBoolean("paused", false))

        pause.end()
        ShadowLooper.idleMainLooper()
        assertFalse(prefs.getBoolean("paused", false))
        assertTrue(log.any { it.contains("reconnect requested") })
    }

    @Test fun keepingStopsTheCountdownAndIsNotAskedAgain() {
        val pause = StationWifiPause(app) { log += it }
        pause.offer()
        ShadowLooper.idleMainLooper()
        StationWifiPrompt.current!!.keep()
        ShadowLooper.idleMainLooper(StationWifiPause.COUNTDOWN_MILLIS * 2, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertNull(StationWifiPrompt.current)
        assertFalse(prefs.getBoolean("paused", false))

        pause.offer()
        ShadowLooper.idleMainLooper()
        assertNull("kept for the rest of this controller", StationWifiPrompt.current)
    }

    @Test fun nothingIsOfferedWithoutAClientConnection() {
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        shadowOf(wifi).setConnectionInfo(ShadowWifiInfo.newInstance())
        StationWifiPause(app) { log += it }.offer()
        ShadowLooper.idleMainLooper()
        assertNull(StationWifiPrompt.current)
    }

    @Test fun anAbandonedPauseIsReconnected() {
        prefs.edit().putBoolean("paused", true).commit()
        StationWifiPause.recover(app) { log += it }
        assertFalse(prefs.getBoolean("paused", false))
    }
}
