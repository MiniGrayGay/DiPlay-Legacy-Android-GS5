package com.shilapi.xcertplay.airplay

import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(
    private val key: ByteArray,
    private val expectedPeer: InetAddress,
    private val onDiagnostic: (String) -> Unit = {},
    private val bindReference: InetAddress? = null,
) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(naluBytes: ByteArray) {}
        /**
         * A frame with the iPhone's frame time ([senderNanos], from the header) and when its body finished
         * arriving ([arrivalNanos], System.nanoTime). Listeners that do not pace frames ignore both.
         */
        fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) = onFrame(naluBytes)
        /** A received frame was dropped before delivery (decrypt queue overflow), breaking the reference chain. */
        fun onFrameDropped() {}
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)
    private val decryptFailures = AtomicInteger(0)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile private var listener: Listener = object : Listener {}

    /**
     * Issue #249: on API 17 the ChaCha fallback (BouncyCastle) decrypts at ~0.2 MB/s, and doing
     * it inline stalled the socket receive loop, so frames arrived late and A/V drifted. Video
     * frames now go through this queue to a dedicated decrypt thread; the receive loop only
     * enqueues. When the queue is full the frame is dropped (a late frame is worse than a
     * skipped one for a live stream) and counted in the stats.
     */
    private data class PendingFrame(
        val header: ByteArray,
        val body: ByteArray,
        val stats: StreamReceiveStats,
        val arrivalNanos: Long,
        val frameNonce: Long,
    )
    private val decryptQueue = LinkedBlockingQueue<PendingFrame>(DECRYPT_QUEUE_CAPACITY)
    @Volatile private var decryptThread: Thread? = null
    private val droppedFrames = AtomicLong(0)

    private fun startDecryptWorker() {
        if (decryptThread != null) return
        decryptThread = Thread({ decryptLoop() }, "airplay-screen-decrypt").apply {
            isDaemon = true
            start()
        }
    }

    private fun decryptLoop() {
        while (!closed.get()) {
            val pending = try {
                decryptQueue.take()
            } catch (_: InterruptedException) {
                break
            }
            try {
                decryptAndDeliver(pending)
            } catch (error: Exception) {
                // decryptAndDeliver already handles per-frame auth failures; anything escaping
                // here is a stream-level failure (e.g. the consecutive-failure cap).
                if (!closed.get()) {
                    Log.e(TAG, "video decrypt worker failed", error)
                    listener.onClosed(error)
                    return
                }
            }
        }
    }

    fun listen(listener: Listener): Int {
        this.listener = listener
        startDecryptWorker()
        val bound = AirPlaySocketBinder.server(bindReference, "AirPlay screen")
        server = bound
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        thread?.interrupt()
        decryptThread?.interrupt()
        decryptQueue.clear()
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.acceptFrom(expectedPeer) { onDiagnostic("Video: rejected sender outside the control session") }
            socket = accepted
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) listener.onClosed(error)
        }
    }

    private fun run(sock: Socket) {
        var failure: Throwable? = null
        val stats = StreamReceiveStats("video", onDiagnostic)
        try {
            val input = sock.getInputStream()
            while (!closed.get()) {
                stats.reading()
                val header = readFully(input, HEADER_LEN) ?: break
                val bodySize = readU32Le(header, 0)
                if (bodySize > MAX_BODY) break
                val body = readFully(input, bodySize) ?: break
                val arrivalNanos = System.nanoTime()
                stats.received(HEADER_LEN + bodySize)
                AirPlayMediaActivity.onPayload()
                onMessage(header, body, stats, arrivalNanos)
                stats.processed()
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            stats.flush(ended = true)
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    private fun onMessage(header: ByteArray, body: ByteArray, stats: StreamReceiveStats, arrivalNanos: Long) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                // A body shorter than the Poly1305 tag cannot be a sealed frame: drop it unread
                // and leave the nonce alone, never hand it to the decoder unauthenticated
                // (upstream #1123).
                if (body.size < ScreenCodec.TAG_SIZE) return
                // Every received video frame from iPhone advances the ChaCha nonce by 1.
                // We capture the nonce at reception time so each frame keeps its exact
                // nonce regardless of worker queue depth, delays, or dropped frames (#249).
                val frameNonce = frameCounter.getAndIncrement()
                // Issues #249/#288: a plain offer() drops the frame the moment the
                // queue is full. On units whose software decryption is barely as fast
                // as the stream, one slow frame (GC/JIT spikes reach 300 ms+) fills
                // the 10-frame queue and the overflow drops shatter the reference
                // chain — mosaic blocks and a frozen picture while rx stays healthy.
                // Wait briefly for a slot instead: the receive loop stalls, TCP
                // backpressure paces the phone, and spikes degrade into latency
                // rather than lost frames. Sustained overload still drops after the
                // wait, and the nonce was already captured above, so a drop never
                // corrupts later frames' decryption.
                val queued = try {
                    decryptQueue.offer(
                        PendingFrame(header, body, stats, arrivalNanos, frameNonce),
                        DECRYPT_QUEUE_OFFER_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
                if (!queued) {
                    val dropped = droppedFrames.incrementAndGet()
                    if (dropped == 1L || dropped % 60L == 0L) {
                        onDiagnostic("video decrypt queue full; dropped frames=$dropped")
                    }
                    // The decoder cannot see this loss: without a signal it keeps
                    // decoding dependent frames against the missing reference and
                    // the picture stays mosaic until a decoder error happens to
                    // trigger recovery (issue #288).
                    listener.onFrameDropped()
                }
            }
            OP_VIDEO_CONFIG -> {
                val (codec, codecData) = ScreenCodec.detectConfig(body)
                Log.i(TAG, "video codec config codec=$codec body=${body.size} data=${codecData.size}")
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    /** Decrypts one queued video frame and delivers it; runs on the decrypt worker thread. */
    private fun decryptAndDeliver(pending: PendingFrame) {
        val header = pending.header
        val body = pending.body
        val stats = pending.stats
        val arrivalNanos = pending.arrivalNanos
        val frameNonce = pending.frameNonce
        val payload = if (body.size >= ScreenCodec.TAG_SIZE) {
            val start = System.nanoTime()
            try {
                ScreenCodec.decryptFrame(key, frameNonce, header, body)
                    .also {
                        stats.decrypted(System.nanoTime() - start, body.size)
                        decryptFailures.set(0)
                    }
            } catch (error: Exception) {
                // A single corrupt frame (transient USB bit error, #180) must not
                // kill the whole screen stream: the per-frame nonce is already
                // assigned, so skipping this frame keeps us in sync with the iPhone
                // and the stream survives. A systematic desync still fails every frame,
                // so cap consecutive skips and end the stream as before once the cap is hit.
                val consecutive = decryptFailures.incrementAndGet()
                Log.w(TAG, "video frame auth failed, skipping frame (consecutive=$consecutive, nonce=$frameNonce)", error)
                onDiagnostic("video frame auth failed, skipped consecutive=$consecutive")
                if (consecutive >= MAX_CONSECUTIVE_DECRYPT_FAILURES) throw error
                // A skipped frame breaks the reference chain the same way a
                // queue drop does (issue #288).
                listener.onFrameDropped()
                return
            }
        } else {
            // onMessage already drops these; never deliver an unauthenticated body.
            return
        }
        if (firstFrameLogged.compareAndSet(false, true)) {
            Log.i(
                TAG,
                "video first decrypted frame sealed=${body.size} plain=${payload.size} " +
                "head=${payload.hexPrefix(16)} chacha=${AirPlayCrypto.chachaImplementation} " +
                "native=${NativeChaCha.available} nativeOpens=${NativeChaCha.opens.get()}",
            )
        }
        listener.onFrame(ScreenCodec.lengthPrefixedToAnnexB(payload), ScreenCodec.senderNanos(header), arrivalNanos)
    }

    private fun readFully(input: InputStream, length: Int): ByteArray? {
        if (length < 0) return null
        val output = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(output, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return output
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
        /**
         * Consecutive corrupt video frames tolerated before giving up: a transient
         * error skips one frame, a systematic nonce/key desync still ends the
         * stream exactly as before (#180).
         */
        const val MAX_CONSECUTIVE_DECRYPT_FAILURES = 30
        /** Bounded decrypt backlog (#249): at 30 fps this is ~0.3 s of video. */
        const val DECRYPT_QUEUE_CAPACITY = 10
        /** How long the receive loop waits for a decrypt slot before dropping a frame. */
        const val DECRYPT_QUEUE_OFFER_WAIT_MILLIS = 30L
    }
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    /**
     * The frame time in a screen header: bytes 8..15, a little-endian NTP 32.32 value, in nanoseconds.
     * On a Tang with iOS 27 consecutive main-screen frames are exactly 1/60 s apart (1/30 s on the
     * cluster stream), and skipped frames leave whole multiples.
     */
    fun senderNanos(header: ByteArray): Long {
        if (header.size < SENDER_TIME_OFFSET + 8) return 0L
        var raw = 0L
        for (index in 7 downTo 0) raw = (raw shl 8) or (header[SENDER_TIME_OFFSET + index].toLong() and 0xff)
        return (raw ushr 32) * 1_000_000_000L + ((raw and 0xffff_ffffL) * 1_000_000_000L ushr 32)
    }

    private const val SENDER_TIME_OFFSET = 8

    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        if (body.size < TAG_SIZE) body
        else AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)

    /**
     * Replaces each four-byte NAL length with an Annex B start code in place.
     *
     * The payload is left untouched unless every length-prefixed NAL is valid, so malformed
     * input keeps its original bytes for the normal decoder error path.
     */
    fun lengthPrefixedToAnnexB(payload: ByteArray): ByteArray {
        if (payload.size < 4 || payload.startsWithStartCode()) return payload

        var offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            offset += 4
            if (length <= 0 || offset + length > payload.size) return payload
            offset += length
        }
        if (offset != payload.size) return payload

        offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + length
        }
        return payload
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            when (fourcc) {
                "hvcC" -> return VideoCodec.H265 to payload.copyOfRange(index + 4, payload.size)
                "avcC" -> return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return if (looksLikeAvcC(payload)) VideoCodec.H264 to payload else VideoCodec.H265 to payload
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun ByteArray.startsWithStartCode(): Boolean =
    size >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        this[2] == 0.toByte() &&
        this[3] == 1.toByte()

private fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
