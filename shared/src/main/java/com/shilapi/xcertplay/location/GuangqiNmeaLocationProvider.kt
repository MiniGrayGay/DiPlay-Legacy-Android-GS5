package com.shilapi.xcertplay.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GpsStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The Guangqi GS5's GNSS, read the way the stock AMap reads it: the system `gps` provider.
 *
 * The receiver is the u-blox on `/dev/ttyACM17`, behind the system GPS HAL
 * (`/system/lib/hw/gps.SABRESD.so`, `/system/etc/u-blox.conf`). The stock AMap requests `gps`
 * updates from LocationManager, and its Guangqi adapter adds the HAL's u-blox sensor data through
 * `LocationManager.requestUbxInfoUpdates`. The Atheros path (`ingsvcd`, `/dev/athrnmea`) is not
 * that receiver: its HAL is parked as `/system/lib/gps.SABRESD.so-old` and nothing in the image
 * reads its output.
 *
 * The HAL's own GGA and RMC are passed on when their checksum matches, with the talker set to
 * `GP` as iAP2 names them. When the HAL has not reported NMEA for a while, the latest `gps` fix is
 * encoded instead.
 */
class GuangqiNmeaLocationProvider(context: Context) : Iap2LocationProvider {
    private val locationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sentences = NmeaSentences()
    private val latestFix = AtomicReference<Location?>(null)
    private var thread: HandlerThread? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestFix.set(location)
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
    }

    @Suppress("DEPRECATION")
    private val nmeaListener = GpsStatus.NmeaListener { _, nmea -> sentences.accept(nmea) }

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun start(): Boolean {
        if (thread != null) return true
        if (LocationManager.GPS_PROVIDER !in locationManager.allProviders) {
            Log.w(TAG, "no gps provider")
            return false
        }
        sentences.clear()
        latestFix.set(null)
        val looperThread = HandlerThread("diplay-gq-gnss").apply { start() }
        return try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MILLIS, 0f, locationListener, looperThread.looper,
            )
            // The NMEA transport takes the looper of the thread that registers it.
            Handler(looperThread.looper).post {
                @Suppress("DEPRECATION")
                val added = runCatching { locationManager.addNmeaListener(nmeaListener) }
                    .onFailure { Log.w(TAG, "addNmeaListener failed", it) }
                    .getOrDefault(false)
                Log.i(TAG, "gps started nmea=$added")
            }
            thread = looperThread
            true
        } catch (error: Exception) {
            Log.w(TAG, "gps updates failed", error)
            looperThread.quit()
            false
        }
    }

    @Synchronized
    override fun stop() {
        val running = thread ?: return
        thread = null
        runCatching { locationManager.removeUpdates(locationListener) }
        @Suppress("DEPRECATION")
        runCatching { locationManager.removeNmeaListener(nmeaListener) }
        running.quitSafely()
        Log.i(TAG, "gps stopped")
    }

    override fun latestNmea(): String? {
        sentences.take()?.let { return it }
        if (sentences.recentlyReceived()) return null
        val fix = latestFix.get()?.takeIf { System.currentTimeMillis() - it.time <= FIX_MAX_AGE_MILLIS }
            ?: return null
        return runCatching {
            NmeaLocationEncoder.encode(
                CarPlayLocationFix(
                    latitudeDegrees = fix.latitude,
                    longitudeDegrees = fix.longitude,
                    altitudeMeters = fix.altitude.takeIf { fix.hasAltitude() },
                    bearingDegrees = fix.bearing.takeIf { fix.hasBearing() }?.toDouble(),
                    speedMetersPerSecond = fix.speed.takeIf { fix.hasSpeed() }?.toDouble(),
                    accuracyMeters = fix.accuracy.takeIf { fix.hasAccuracy() }?.toDouble(),
                    timestampMillis = fix.time,
                ),
            )
        }.onFailure { Log.w(TAG, "could not encode gps fix", it) }.getOrNull()
    }

    /** The latest GGA and RMC from the HAL, each sent once. */
    internal class NmeaSentences(private val clock: () -> Long = SystemClock::elapsedRealtime) {
        private val gga = AtomicReference<String?>(null)
        private val rmc = AtomicReference<String?>(null)
        private val lastReceived = AtomicLong(Long.MIN_VALUE)

        /** [nmea] may carry several sentences; only GGA and RMC with a right checksum are kept. */
        fun accept(nmea: String?) {
            for (line in nmea.orEmpty().split('\n')) {
                val sentence = line.trim()
                val target = when {
                    sentence.startsWith("\$GPGGA,") || sentence.startsWith("\$GNGGA,") -> gga
                    sentence.startsWith("\$GPRMC,") || sentence.startsWith("\$GNRMC,") -> rmc
                    else -> continue
                }
                if (!checksumValid(sentence)) continue
                target.set(asGps(sentence))
                lastReceived.set(clock())
            }
        }

        fun take(): String? {
            val g = gga.getAndSet(null)
            val r = rmc.getAndSet(null)
            if (g == null && r == null) return null
            return buildString {
                if (g != null) append(g).append("\r\n")
                if (r != null) append(r).append("\r\n")
            }
        }

        /** True while the HAL is reporting NMEA, so an encoded fix would only duplicate it. */
        fun recentlyReceived(): Boolean {
            val last = lastReceived.get()
            return last != Long.MIN_VALUE && clock() - last <= NMEA_QUIET_MILLIS
        }

        fun clear() {
            gga.set(null)
            rmc.set(null)
            lastReceived.set(Long.MIN_VALUE)
        }
    }

    companion object {
        private const val TAG = "DiPlay-GQ-GNSS"
        private const val UPDATE_INTERVAL_MILLIS = 1_000L
        private const val FIX_MAX_AGE_MILLIS = 5_000L
        private const val NMEA_QUIET_MILLIS = 3_000L

        /** `$<body>*HH` where HH is the XOR of every body character, in hex. */
        internal fun checksumValid(sentence: String): Boolean {
            val star = sentence.lastIndexOf('*')
            if (!sentence.startsWith("$") || star < 1 || star + 3 != sentence.length) return false
            val expected = sentence.substring(star + 1).toIntOrNull(16) ?: return false
            return checksum(sentence.substring(1, star)) == expected
        }

        /** A multi-constellation `$GN` sentence as `$GP`, with its checksum redone. */
        internal fun asGps(sentence: String): String {
            if (!sentence.startsWith("\$GN")) return sentence
            val body = "GP" + sentence.substring(3, sentence.lastIndexOf('*'))
            return "$" + body + "*" + String.format(java.util.Locale.US, "%02X", checksum(body))
        }

        private fun checksum(body: String): Int {
            var sum = 0
            for (c in body) sum = sum xor c.code
            return sum
        }
    }
}
