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
                        when {
                            line.startsWith("\$GPGGA") || line.startsWith("\$GNGGA") ->
                                latestGga.set(line.trimEnd())
                            line.startsWith("\$GPRMC") || line.startsWith("\$GNRMC") ->
                                latestRmc.set(line.trimEnd())
                        }
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

    companion object {
        private const val TAG = "DiPlay-GQ-NMEA"
        private const val DEVICE_PATH = "/dev/athrnmea"
        private const val PIPE_PATH = "/dev/athrpipeout"
        private const val RETRY_MS = 2000L
    }
}
