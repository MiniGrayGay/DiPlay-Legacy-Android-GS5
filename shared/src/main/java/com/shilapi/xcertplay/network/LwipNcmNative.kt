package com.shilapi.xcertplay.network

/**
 * JNI bindings for userspace lwIP NCM relay.
 */
class LwipNcmNative(
    private val onTxFrameCallback: (ByteArray) -> Unit,
) {
    private var nativeHandle: Long = 0

    init {
        try {
            val explicitLibrary = System.getProperty("diplay.lwip.path")
            if (explicitLibrary.isNullOrBlank()) {
                System.loadLibrary("lwip_ncm_relay")
            } else {
                System.load(java.io.File(explicitLibrary).absolutePath)
            }
        } catch (_: UnsatisfiedLinkError) {
            // Expected in host-side unit tests without NDK shared library loaded
        }
    }

    @Synchronized
    fun create(hostMac: ByteArray, linkLocal: String): Boolean {
        if (nativeHandle != 0L) return true
        return try {
            nativeHandle = nativeCreate(hostMac, linkLocal)
            nativeHandle != 0L
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    @Synchronized
    fun inputFrame(frame: ByteArray, length: Int = frame.size) {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeInputFrame(handle, frame, length)
        }
    }

    /** Injects [length] bytes at [offset] without copying them into a new Java array first. */
    @Synchronized
    fun inputFrame(buffer: ByteArray, offset: Int, length: Int) {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeInputFrameRange(handle, buffer, offset, length)
        }
    }

    /**
     * Injects [count] frames that all live in [buffer]; [ranges] holds an (offset, length) pair
     * per frame. The whole batch takes the native core lock once, reaches the app in one
     * loopback write per connection and is acknowledged once. [count] is at most
     * [MAX_BATCH_FRAMES].
     */
    @Synchronized
    fun inputFrames(buffer: ByteArray, ranges: IntArray, count: Int) {
        val handle = nativeHandle
        if (handle != 0L && count > 0) {
            nativeInputFrames(handle, buffer, ranges, count)
        }
    }

    /**
     * Reads the native performance counters into [out] (indexes are [PERF_RX_FRAMES] and
     * friends) and restarts the per-window totals. Returns false when the stack is gone.
     */
    @Synchronized
    fun readPerfCounters(out: LongArray): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        return try {
            nativeGetPerfCounters(handle, out) > 0
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    @Synchronized
    fun registerPort(port: Int, isTcp: Boolean) {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeRegisterPort(handle, port, isTcp)
        }
    }

    /** Sends one UDP payload through a relay that has already learned the phone's IPv6 address. */
    @Synchronized
    fun sendUdp(localPort: Int, remotePort: Int, payload: ByteArray): Boolean {
        val handle = nativeHandle
        if (handle == 0L || payload.isEmpty()) return false
        return nativeSendUdp(handle, localPort, remotePort, payload)
    }

    @Synchronized
    fun destroy() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            nativeDestroy(handle)
        }
    }

    @Synchronized
    fun getSnapshot(): String {
        val handle = nativeHandle
        if (handle == 0L) return "lwip uninitialized"
        return try {
            nativeGetSnapshot(handle)
        } catch (_: UnsatisfiedLinkError) {
            "lwip native unavailable"
        }
    }

    /** Called under the native core lock; must not acquire the native-handle monitor. */
    fun onTxFrame(frame: ByteArray) {
        onTxFrameCallback(frame)
    }

    private external fun nativeCreate(hostMac: ByteArray, linkLocal: String): Long
    private external fun nativeInputFrame(handle: Long, frame: ByteArray, length: Int)
    private external fun nativeInputFrameRange(handle: Long, frame: ByteArray, offset: Int, length: Int)
    private external fun nativeInputFrames(handle: Long, buffer: ByteArray, ranges: IntArray, count: Int)
    private external fun nativeGetPerfCounters(handle: Long, out: LongArray): Int
    private external fun nativeRegisterPort(handle: Long, port: Int, isTcp: Boolean)
    private external fun nativeSendUdp(handle: Long, localPort: Int, remotePort: Int, payload: ByteArray): Boolean
    private external fun nativeGetSnapshot(handle: Long): String
    private external fun nativeDestroy(handle: Long)

    companion object {
        /** Must match MAX_INPUT_BATCH_FRAMES in lwip_ncm_relay.c. */
        const val MAX_BATCH_FRAMES = 64

        // Must match the PERF_* enum in lwip_ncm_relay.c.
        const val PERF_RX_FRAMES = 0
        const val PERF_RX_DROPS = 1
        const val PERF_PBUF_ALLOC_FAILS = 2
        const val PERF_TX_FRAMES = 3
        const val PERF_RECV_ERR_MEM = 4
        const val PERF_RECV_MAX_CHAIN = 5
        const val PERF_RELAY_PEAK_BYTES = 6
        const val PERF_LOOPBACK_EAGAIN = 7
        const val PERF_TCP_REFUSED_PCBS = 8
        const val PERF_TCP_MIN_RCV_WND = 9
        const val PERF_TCP_OOSEQ_PCBS = 10
        const val PERF_COUNT = 11
    }
}
