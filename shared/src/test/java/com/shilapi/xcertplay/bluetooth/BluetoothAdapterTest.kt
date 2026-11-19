package com.shilapi.xcertplay.bluetooth

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BluetoothAdapterTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun testCarPlayBluetoothDeviceProperties() {
        val device = CarPlayBluetoothDevice("iPhone 15 Pro", "AA:BB:CC:DD:EE:FF", true)
        assertEquals("iPhone 15 Pro", device.name)
        assertEquals("AA:BB:CC:DD:EE:FF", device.address)
        assertTrue(device.isConnected)

        val disconnected = CarPlayBluetoothDevice("iPhone 12", "11:22:33:44:55:66")
        assertFalse(disconnected.isConnected)
    }

    @Test
    fun testHybridAdapterNamesTheAndroidStack() {
        val android = AndroidBluetoothAdapter(context)
        val hybrid = HybridBluetoothAdapter(android)
        assertEquals("Hybrid(Android)", hybrid.getAdapterName())
    }
}
