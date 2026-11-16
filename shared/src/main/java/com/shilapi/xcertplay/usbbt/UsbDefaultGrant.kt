package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Helper to grant and persist default USB permissions using elevated privileges (su or Local ADB)
 * so the system dialog is not prompted again across reboots.
 */
object UsbDefaultGrant {
    private const val TAG = "UsbDefaultGrant"

    /**
     * Attempt to grant and persist USB permission for [device] via root shell if available.
     * Returns true if granted successfully.
     */
    fun tryGrantWithRoot(context: Context, device: UsbDevice): Boolean {
        val apkPath = context.packageCodePath
        val pkg = context.packageName
        val uid = context.applicationInfo.uid
        val vid = device.vendorId
        val pid = device.productId

        val cmd = "/system/bin/app_process -Djava.class.path=$apkPath /system/bin " +
            "com.shilapi.xcertplay.usbbt.UsbDefaultGrantMain $pkg $uid $vid $pid 0\n"

        return runCatching {
            val process = Runtime.getRuntime().exec("su")
            process.outputStream.use { out ->
                out.write(cmd.toByteArray())
                out.write("exit\n".toByteArray())
                out.flush()
            }
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            val success = output.contains("FA_USB_LIVE=ok")
            if (success) {
                Log.i(TAG, "Successfully granted persistent USB permission for vid=0x${vid.toString(16)} pid=0x${pid.toString(16)}")
            } else {
                Log.w(TAG, "Root grant returned: $output")
                // Issue #285: the diagnostic report only ever showed
                // "root grant attempted: success=false" — record WHY it
                // failed (no su vs. grant rejected) so the next report
                // can tell a missing-root unit from a swallowed dialog.
                UsbBtLog.log(context, TAG, "Root grant failed: output=${output.trim().take(200)}")
            }
            success
        }.getOrElse {
            Log.d(TAG, "Root execution not available: ${it.message}")
            UsbBtLog.log(context, TAG, "Root execution not available: ${it.message?.take(200)}")
            false
        }
    }

}
