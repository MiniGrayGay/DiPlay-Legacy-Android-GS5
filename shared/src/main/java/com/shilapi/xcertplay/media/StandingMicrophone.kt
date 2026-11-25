package com.shilapi.xcertplay.media

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * One recorder kept open for a whole CarPlay session. Each [MicrophoneUplink] reads its own copy of
 * what it captures through a [Reader]; with no reader the audio is thrown away.
 *
 * Android 4.4 runs one recorder at a time and refuses the second, so whoever starts first keeps the
 * microphone. Opening it per call loses that race whenever the car's wake word is listening again,
 * and the driver's AudioRecord start costs up to a second of each call. The worker asks for the
 * microphone ([makeRoom]) and retries until it records, and opens again when reading fails; readers
 * stay attached across that. [makeRoom] is repeated while recording, so a wake word the car turned
 * back on does not keep failing to start behind this recorder.
 */
internal class StandingMicrophone(
    private val openRecorder: (source: Int) -> AudioRecord? = ::openDefault,
    private val source: () -> Int = { SOURCE },
    private val makeRoom: () -> Unit = {},
    /** The microphone is still refused after [EVICT_AFTER] attempts, and every [EVICT_EVERY] after that. */
    private val evict: () -> Unit = {},
    private val log: (String) -> Unit = {},
    /** The recorder is gone for good; on the worker. */
    private val onReleased: () -> Unit = {},
    private val retryMillis: Long = RETRY_MILLIS,
    private val makeRoomMillis: Long = MAKE_ROOM_MILLIS,
) : Closeable {
    /** A chunk of 48 kHz mono PCM and when its last sample was heard, on System.nanoTime's clock. */
    private class Chunk(val pcm: ByteArray, val endNs: Long)

    inner class Reader internal constructor() : Closeable {
        private val queue = ArrayBlockingQueue<Chunk>(READER_CHUNKS)
        @Volatile private var open = true

        val sampleRate: Int get() = SAMPLE_RATE
        val audioSource: Int get() = this@StandingMicrophone.currentSource
        /** The session effects attach to; 0 while the recorder is being opened. */
        val audioSessionId: Int get() = runCatching { recorder?.audioSessionId }.getOrNull() ?: 0
        /** When the last sample of the latest [read] was heard. */
        @Volatile var lastReadEndNs: Long = 0L
            private set

        /** One chunk into [buffer]; 0 when none came in time, -1 when closed. */
        fun read(buffer: ByteArray): Int {
            if (!open) return -1
            val chunk = queue.poll(READ_WAIT_MILLIS, TimeUnit.MILLISECONDS)
                ?: return if (open && running) 0 else -1
            lastReadEndNs = chunk.endNs
            val count = minOf(buffer.size, chunk.pcm.size)
            chunk.pcm.copyInto(buffer, 0, 0, count)
            return count
        }

        internal fun offer(pcm: ByteArray, endNs: Long) {
            val chunk = Chunk(pcm, endNs)
            // A reader that fell behind hears the newest audio, not a growing delay.
            while (open && !queue.offer(chunk)) queue.poll()
        }

        override fun close() {
            open = false
            if (readers.remove(this) && closing && readers.isEmpty()) this@StandingMicrophone.close()
        }
    }

    private val readers = CopyOnWriteArrayList<Reader>()
    @Volatile private var running = true
    /** Set by [closeWhenIdle]: no new readers, and the worker ends with the last one. */
    @Volatile private var closing = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var currentSource = source()
    @Volatile private var openFailures = 0
    private val waitLock = Object()
    private val worker = Thread(::run, "diplay-standing-mic").apply { isDaemon = true }

    fun start() = apply { worker.start() }

    /** Null when no recorder can be opened here: the caller then opens its own. */
    fun subscribe(): Reader? {
        if (!running || closing || openFailures >= MAX_OPEN_FAILURES) return null
        return Reader().also(readers::add)
    }

    /** Opens the recorder again, for a source that turned out to be silent. */
    fun reopen() {
        runCatching { recorder?.stop() }
    }

    override fun close() {
        synchronized(waitLock) {
            if (!running) return
            running = false
        }
        runCatching { recorder?.stop() }
        synchronized(waitLock) { waitLock.notifyAll() }
        readers.forEach(Reader::close)
    }

    /** Closes after the streams now reading have ended, so a call in progress keeps its microphone. */
    fun closeWhenIdle() {
        closing = true
        if (readers.isEmpty()) close()
    }

    private fun run() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        val since = System.nanoTime()
        var attempts = 0
        while (running) {
            attempts++
            makeRoom()
            val opened = open()
            if (opened != null) {
                log("Standing microphone: recording source=$currentSource rate=$SAMPLE_RATE attempts=$attempts " +
                    "afterMs=${(System.nanoTime() - since) / 1_000_000L}")
                attempts = 0
                pump(opened)
            } else if (attempts == 1) {
                log("Standing microphone: not available yet, retrying every ${retryMillis}ms")
            }
            recorder = null
            opened?.let { runCatching { it.release() } }
            // Refused, not unable to open: asking did not help, so someone else really holds it.
            val evicting = opened == null && running && openFailures == 0 && attempts >= EVICT_AFTER &&
                (attempts - EVICT_AFTER) % EVICT_EVERY == 0
            if (evicting) runCatching(evict)
            // An ended holder may be started again: be there first.
            if (running) pause(if (evicting) minOf(retryMillis, AFTER_EVICT_MILLIS) else retryMillis)
        }
        log("Standing microphone: released")
        runCatching(onReleased)
    }

    /** A recorder that is recording, or null. */
    private fun open(): AudioRecord? {
        currentSource = source()
        val created = runCatching { openRecorder(currentSource) }
            .onFailure { Log.w(TAG, "standing recorder creation failed", it) }.getOrNull()
        if (created == null) {
            if (++openFailures == MAX_OPEN_FAILURES) log("Standing microphone: no recorder can be opened; each stream opens its own")
            return null
        }
        openFailures = 0
        recorder = created
        // The audio policy refuses a second recorder without an exception; it just stays stopped.
        runCatching { created.startRecording() }.onFailure { Log.w(TAG, "standing recorder start failed", it) }
        if (running && created.recordingState == AudioRecord.RECORDSTATE_RECORDING) return created
        recorder = null
        runCatching { created.release() }
        return null
    }

    private fun pump(active: AudioRecord) {
        var pcm = ByteArray(CHUNK_BYTES)
        var filled = 0
        var madeRoomNs = System.nanoTime()
        while (running) {
            val count = runCatching { active.read(pcm, filled, pcm.size - filled) }.getOrDefault(AudioRecord.ERROR)
            if (count < 0 || active.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                if (running) log("Standing microphone: lost (read $count); opening it again")
                return
            }
            filled += count
            val now = System.nanoTime()
            if (filled == pcm.size) {
                // A full chunk is handed over; the next read gets its own array.
                val endNs = now - CaptureClock.FALLBACK_LATENCY_NS
                readers.forEach { it.offer(pcm, endNs) }
                if (readers.isNotEmpty()) pcm = ByteArray(CHUNK_BYTES)
                filled = 0
            } else if (count == 0) {
                runCatching { Thread.sleep(EMPTY_READ_MILLIS) }
            }
            if ((now - madeRoomNs) / 1_000_000L >= makeRoomMillis) {
                madeRoomNs = now
                makeRoom()
            }
        }
    }

    private fun pause(millis: Long) = synchronized(waitLock) {
        if (running) runCatching { waitLock.wait(millis) }
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        /**
         * Not VOICE_COMMUNICATION: each time the car's output starts after standby, its audio HAL
         * (`audio.primary.imx6.so`, out_write) puts a recorder with that source into standby too, and a
         * call or Siri then loses its first second. The microphones sound the same on this car.
         */
        const val SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        const val SAMPLE_RATE = 48_000
        /** 20 ms; a multiple of 2 and 3 samples, so every converter takes whole chunks. */
        const val CHUNK_BYTES = SAMPLE_RATE / 50 * 2
        private const val READER_CHUNKS = 25
        private const val READ_WAIT_MILLIS = 200L
        private const val EMPTY_READ_MILLIS = 5L
        private const val RETRY_MILLIS = 500L
        private const val MAKE_ROOM_MILLIS = 15_000L
        private const val MAX_OPEN_FAILURES = 3
        /** The first attempt and three retries, 1.5 s; the car was seen to let go within 1 s. */
        const val EVICT_AFTER = 4
        /** 10 s. */
        private const val EVICT_EVERY = 20
        private const val AFTER_EVICT_MILLIS = 150L

        private fun openDefault(source: Int): AudioRecord? {
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return null
            val created = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min * 2, CHUNK_BYTES * 8))
            if (created.state == AudioRecord.STATE_INITIALIZED) return created
            created.release()
            return null
        }
    }
}
