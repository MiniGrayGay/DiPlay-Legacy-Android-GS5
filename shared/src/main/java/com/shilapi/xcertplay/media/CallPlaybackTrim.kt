package com.shilapi.xcertplay.media

/**
 * Keeps a call's playback close to real time. The iPhone opens a call with a burst, and a network
 * gap ends in another; what is queued then is never played out, and the caller hears that much delay
 * to the end of the call. Quiet packets are dropped while more than [TARGET_MILLIS] is waiting, and
 * any packet above [MAX_MILLIS].
 */
internal class CallPlaybackTrim {
    var droppedMillis = 0L
        private set

    /** Whether to drop a packet of [packetMillis] whose loudest sample is [peak], with [waitingMillis] ahead of it. */
    fun drop(waitingMillis: Long, packetMillis: Long, peak: Int): Boolean {
        val drop = waitingMillis > MAX_MILLIS || (waitingMillis > TARGET_MILLIS && peak < QUIET_PEAK)
        if (drop) droppedMillis += packetMillis
        return drop
    }

    companion object {
        /** Calls were seen with arrival gaps of 100 ms, at worst 217. */
        const val TARGET_MILLIS = 160L
        const val MAX_MILLIS = 300L
        /** -36 dBFS. */
        const val QUIET_PEAK = 520
    }
}
