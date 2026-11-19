package com.shilapi.xcertplay.bluetooth

import android.content.Context
import com.shilapi.xcertplay.usbbt.UsbBtBluetoothAdapter

object CarPlayBluetoothManager {
    @Volatile
    private var instance: HybridBluetoothAdapter? = null

    fun get(context: Context): HybridBluetoothAdapter {
        return instance ?: synchronized(this) {
            instance ?: HybridBluetoothAdapter(
                AndroidBluetoothAdapter(context.applicationContext),
                UsbBtBluetoothAdapter(context.applicationContext),
            ).also { instance = it }
        }
    }
}
