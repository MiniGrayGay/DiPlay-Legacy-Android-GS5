package com.shilapi.xcertplay.location

import android.util.Log
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads NMEA directly from the Atheros AR1520A GPS baseband on the Guangqi GS5 head unit.
 * The device outputs GGA, GSA, GSV, RMC, VTG, GNS to `/dev/athrnmea`. DiPlay runs as
 * `android.uid.system` so it can open the device file without extra permissions.
 *
 * Returns raw `$GPGGA` + `$GPRMC` lines, which is exactly what iAP2 0xFFFB sends.
 *
 * Only sentences whose checksum matches are passed on. The device can hand over a partial line,
 * for example the tail of a sentence when the reader opens mid-output, and the iPhone must not
 * get a position cut in half.
 */
class GuangqiNmeaLocationProvider : Iap2LocationProvider {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diplay-gq-nmea").apply { isDaemon = true }
    }
    @Volatile private var running = false
    private val latestGga = AtomicReference<String?>(null)
    private val latestRmc = AtomicReference<String?>(null)

    override fun start(): Boolean {
        if (running) return true
        val dev = File(DEVICE_PATH)
        if (!dev.exists()) {
            Log.w(TAG, "$DEVICE_PATH does not exist")
            val pipe = File(PIPE_PATH)
            if (!pipe.exists()) {
                Log.w(TAG, "$PIPE_PATH does not exist either; no GPS source available")
                return false
            }
        }
        // A fix read before the last stop is no longer current.
        latestGga.set(null)
        latestRmc.set(null)
        running = true
        executor.execute(::readLoop)
        Log.i(TAG, "NMEA reader started")
        return true
    }

    override fun stop() {
        running = false
        Log.i(TAG, "NMEA reader stopped")
    }

    override fun latestNmea(): String? {
        val gga = latestGga.getAndSet(null)
        val rmc = latestRmc.getAndSet(null)
        if (gga == null && rmc == null) return null
        return buildString {
            if (gga != null) append(gga).append("\r\n")
            if (rmc != null) append(rmc).append("\r\n")
        }
    }

    private fun readLoop() {
        val path = if (File(DEVICE_PATH).exists()) DEVICE_PATH else PIPE_PATH
        while (running) {
            try {
                BufferedReader(InputStreamReader(FileInputStream(path), Charsets.US_ASCII)).use { reader ->
                    Log.i(TAG, "opened $path")
                    var line: String?
                    while (running) {
                        line = reader.readLine() ?: break
                        accept(line)
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "NMEA read error, retrying in ${RETRY_MS}ms", e)
                    try { Thread.sleep(RETRY_MS) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    /** Keeps [line] as the latest GGA or RMC when it is one and its checksum is right. */
    internal fun accept(line: String) {
        val sentence = line.trim()
        val target = when {
            sentence.startsWith("\$GPGGA,") || sentence.startsWith("\$GNGGA,") -> latestGga
            sentence.startsWith("\$GPRMC,") || sentence.startsWith("\$GNRMC,") -> latestRmc
            else -> return
        }
        if (checksumValid(sentence)) target.set(sentence)
    }

    companion object {
        /** `$<body>*HH` where HH is the XOR of every body character, in hex. */
        internal fun checksumValid(sentence: String): Boolean {
            val star = sentence.lastIndexOf('*')
            if (!sentence.startsWith("$") || star < 1 || star + 3 != sentence.length) return false
            val expected = sentence.substring(star + 1).toIntOrNull(16) ?: return false
            var sum = 0
            for (i in 1 until star) sum = sum xor sentence[i].code
            return sum == expected
        }

        private const val TAG = "DiPlay-GQ-NMEA"
        private const val DEVICE_PATH = "/dev/athrnmea"
        private const val PIPE_PATH = "/dev/athrpipeout"
        private const val RETRY_MS = 2000L
    }
}
