package com.shilapi.xcertplay

import android.content.Context

/**
 * The wired network stack in force: the chosen one, or lwIP after the kernel TUN failed to bring
 * CarPlay up. The fallback lasts until DiPlay is restarted or the driver chooses again, and is not
 * saved: a TUN that failed once may have met a phone or a cable problem.
 */
internal object WiredNetwork {
    @Volatile private var tunFailed = false

    fun kernelTun(context: Context): Boolean = !tunFailed && AirPlayPersistence.loadWiredKernelTun(context)

    fun choose(context: Context, kernelTun: Boolean) {
        tunFailed = false
        AirPlayPersistence.saveWiredKernelTun(context, kernelTun)
    }

    /** True when [reason] ended a wired attempt on the TUN, and the next one is to use lwIP. */
    fun fallBack(context: Context, reason: String): Boolean {
        if (!kernelTun(context) || FAILURES.none { reason.contains(it) }) return false
        tunFailed = true
        return true
    }

    internal fun reset() {
        tunFailed = false
    }

    private val FAILURES = listOf(
        // The iPhone's SYN was never answered, or never came.
        "No AirPlay TCP after CarPlay StartSession",
        // The VPN could not be set up.
        "Could not attach the NCM/VPN AirPlay transport",
    )
}
