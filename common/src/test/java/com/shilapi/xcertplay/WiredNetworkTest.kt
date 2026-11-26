package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WiredNetworkTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun tearDown() = WiredNetwork.reset()

    @Test fun aTunThatDoesNotConnectGivesWayOnce() {
        assertTrue(WiredNetwork.kernelTun(app))
        assertFalse("not the network", WiredNetwork.fallBack(app, "USB transfer failed"))
        assertTrue(WiredNetwork.fallBack(app, "Error: No AirPlay TCP after CarPlay StartSession; resetting link"))
        assertFalse(WiredNetwork.kernelTun(app))
        assertFalse("lwIP failing is its own matter", WiredNetwork.fallBack(app, "No AirPlay TCP after CarPlay StartSession"))
        assertTrue("the choice is kept", AirPlayPersistence.loadWiredKernelTun(app))
        WiredNetwork.choose(app, kernelTun = true)
        assertTrue(WiredNetwork.kernelTun(app))
    }

    @Test fun lwipStaysLwip() {
        WiredNetwork.choose(app, kernelTun = false)
        assertFalse(WiredNetwork.fallBack(app, "Could not attach the NCM/VPN AirPlay transport: x"))
        assertFalse(WiredNetwork.kernelTun(app))
    }
}
