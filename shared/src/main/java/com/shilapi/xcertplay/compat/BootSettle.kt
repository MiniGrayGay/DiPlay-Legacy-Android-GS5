package com.shilapi.xcertplay.compat

import android.os.SystemClock

/**
 * The first minute after the head unit boots. The Guangqi unit shows its radio page as boot
 * completes, and it was reset by its hardware watchdog a moment later on some boots: a black
 * screen, then the boot logo. At that moment the system starts DiPlay for the USB Bluetooth
 * dongle it enumerated during boot, and DiPlay opened the dongle through usbfs at once, while
 * the USB bus, the Parrot module and the Wi-Fi driver were still coming up. Work that drives
 * hardware on its own (not because the driver asked for it) waits until this window has passed.
 */
object BootSettle {
    /** Uptime before which DiPlay does not start the dongle by itself. */
    const val SETTLE_MILLIS = 60_000L

    /** How long to wait at [uptimeMillis] since boot; 0 once the window has passed. */
    fun remainingMillis(uptimeMillis: Long = SystemClock.elapsedRealtime()): Long =
        (SETTLE_MILLIS - uptimeMillis).coerceAtLeast(0L)

    /** Blocks the calling worker thread until the window has passed. */
    fun await(log: (String) -> Unit) {
        val remaining = remainingMillis()
        if (remaining <= 0L) return
        log("waiting ${remaining}ms for the head unit to settle after boot")
        Thread.sleep(remaining)
    }
}
