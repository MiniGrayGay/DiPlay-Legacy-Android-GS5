package com.shilapi.xcertplay.hud

import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Reads vehicle speed and gear from the Guangqi GS5 head unit via IVehicleManager Binder.
 *
 * SpeedInfo (type 5): field layout `[unknown, speed_kmh, ...]` — `m_nSpeed` at int offset 1.
 * PowerInfo (type 10): field layout `[gear, engineSpeed/100, throttle%, waterTemp, ...]` —
 * `m_byteGear` at int offset 0 (P=13, R=14, N=15, D=12, S=11).
 *
 * Uses polling (CODE_GET_PACKET = 7) which is simpler and sufficient at 4 Hz.
 */
object GuangqiVehicleSpeedSource : VehicleSpeedSource {
    private const val TAG = "DiPlay-GQ-Speed"
    private const val IFACE_VEHICLE = "android.vehicle.IVehicleManager"
    private const val CODE_GET_PACKET = 7
    private const val TYPE_SPEED = 5
    private const val TYPE_POWER = 10
    private const val POLL_MS = 250L
    private const val GEAR_EVERY = 4
    private const val MAX_SAMPLES = 40

    private var executor: ScheduledExecutorService? = null
    private val samples = ArrayList<VehicleSpeedSample>()
    private var gear: VehicleGear? = null
    private var polls = 0
    private var firstSampleLogged = false
    private var binder: IBinder? = null

    @Synchronized
    override fun start() {
        if (executor != null) return
        samples.clear()
        gear = null
        polls = 0
        firstSampleLogged = false
        binder = getVehicleBinder()
        if (binder == null) {
            Log.w(TAG, "vehicle service not available")
            return
        }
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "diplay-gq-speed").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::poll, 0, POLL_MS, TimeUnit.MILLISECONDS) }
        Log.i(TAG, "vehicle speed started")
    }

    override fun stop() {
        val running = synchronized(this) { executor.also { executor = null; binder = null } } ?: return
        running.shutdown()
        Log.i(TAG, "vehicle speed stopped")
    }

    @Synchronized
    override fun drain(): VehicleSpeedReading? {
        val g = gear ?: return null
        if (samples.isEmpty()) return null
        return VehicleSpeedReading(g, samples.toList()).also { samples.clear() }
    }

    private fun poll() {
        val b = binder ?: return
        if (polls++ % GEAR_EVERY == 0) {
            readGear(b)
        }
        readSpeed(b)
    }

    private fun readSpeed(b: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE_VEHICLE)
            data.writeInt(TYPE_SPEED)
            val before = SystemClock.elapsedRealtime()
            b.transact(CODE_GET_PACKET, data, reply, 0)
            reply.readInt() // exception marker
            if (reply.readInt() == 0) return // no data
            reply.readInt() // skip first field (unknown)
            val kmh = reply.readInt()
            if (kmh < 0 || kmh > 300) return
            val mps = kmh / 3.6
            val sample = VehicleSpeedSample((before + SystemClock.elapsedRealtime()) / 2, mps)
            synchronized(this) {
                if (samples.size == MAX_SAMPLES) samples.removeAt(0)
                samples += sample
            }
            if (!firstSampleLogged) {
                firstSampleLogged = true
                Log.i(TAG, "first speed $kmh km/h")
            }
        } catch (e: Exception) {
            Log.w(TAG, "speed read failed", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun readGear(b: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE_VEHICLE)
            data.writeInt(TYPE_POWER)
            b.transact(CODE_GET_PACKET, data, reply, 0)
            reply.readInt() // exception marker
            if (reply.readInt() == 0) return // no data
            val gearRaw = reply.readInt()
            val next = when (gearRaw) {
                13 -> VehicleGear.PARK
                14 -> VehicleGear.REVERSE
                15 -> VehicleGear.NEUTRAL
                12, 11 -> VehicleGear.DRIVE // D=12, S=11
                else -> null
            } ?: return
            val changed = synchronized(this) { (gear != next).also { gear = next } }
            if (changed) Log.i(TAG, "gear $next")
        } catch (e: Exception) {
            Log.w(TAG, "gear read failed", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun getVehicleBinder(): IBinder? = try {
        val sm = Class.forName("android.os.ServiceManager")
        sm.getMethod("getService", String::class.java).invoke(null, "vehicle") as? IBinder
    } catch (e: Exception) {
        Log.w(TAG, "ServiceManager reflection failed", e)
        null
    }
}
