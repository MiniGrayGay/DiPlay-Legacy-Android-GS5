package com.shilapi.xcertplay.bluetooth

import android.content.Context
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.usbbt.UsbBtBluetoothAdapter

class HybridBluetoothAdapter(
    val android: AndroidBluetoothAdapter,
    /** USB dongle stack; preferred for data channels when plugged in. */
    val usbDongle: UsbBtBluetoothAdapter? = null,
) : CarPlayBluetooth {

    override fun isSupported(): Boolean =
        android.isSupported() || usbDongle?.isSupported() == true

    override fun isEnabled(): Boolean {
        if (usbDongle?.isSupported() == true) return usbDongle.isEnabled()
        return android.isEnabled()
    }

    override fun enable(): Boolean {
        val androidEnabled = android.enable()
        // Plugging in a dongle is the opt-in. Its address is the one the iPhone
        // pairs with, so it takes over whenever it is present.
        if (usbDongle?.isSupported() == true) return usbDongle.enable()
        return androidEnabled
    }

    override fun disable() {
        usbDongle?.disable()
    }

    override fun getAdapterName(): String {
        val parts = mutableListOf<String>()
        if (android.isSupported()) parts.add("Android")
        if (usbDongle?.isSupported() == true) parts.add("USB")
        return if (parts.isEmpty()) android.getAdapterName()
        else "Hybrid(${parts.joinToString("+")})"
    }

    override fun getPairedDevices(): List<CarPlayBluetoothDevice> {
        // Merge both stacks, dedupe by MAC. An iPhone paired on the dongle must
        // stay visible when the built-in adapter also has paired devices.
        val seen = HashSet<String>()
        val merged = ArrayList<CarPlayBluetoothDevice>()
        fun addAll(devices: List<CarPlayBluetoothDevice>) {
            for (device in devices) {
                val key = device.address.lowercase()
                if (seen.add(key)) merged.add(device)
            }
        }
        runCatching { addAll(android.getPairedDevices()) }
        usbDongle?.let { runCatching { addAll(it.getPairedDevices()) } }
        return merged
    }

    override fun getConnectedDevice(): CarPlayBluetoothDevice? {
        // Dongle first: it is the data-capable stack.
        usbDongle?.let { runCatching { it.getConnectedDevice() } }?.getOrNull()?.let { return it }
        return android.getConnectedDevice()
    }

    override fun localAddress(context: Context): String? {
        // Dongle first: its BD_ADDR is the one the iPhone must pair with for
        // the data channel.
        usbDongle?.let { runCatching { it.localAddress(context) } }?.getOrNull()
            ?.takeIf { !it.isNullOrBlank() }?.let { return it }
        return android.localAddress(context)
    }

    override fun openRfcommStream(deviceAddress: String, uuid: String): BlockingDuplexByteStream {
        val dongle = usbDongle
        if (dongle?.isSupported() == true) {
            if (!dongle.isEnabled()) throw java.io.IOException("USB Bluetooth not enabled")
            // An iAP2 advertisement and bond belong to one local adapter address.
            // The built-in adapter cannot resume the dongle's connection.
            return dongle.openRfcommStream(deviceAddress, uuid)
        }
        if (android.isSupported() && android.isEnabled()) {
            return android.openRfcommStream(deviceAddress, uuid)
        }
        throw java.io.IOException("Bluetooth RFCOMM unavailable: no Bluetooth stack available")
    }
}
