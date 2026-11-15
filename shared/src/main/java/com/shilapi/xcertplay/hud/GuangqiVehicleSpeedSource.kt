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
 * `getDefinedPacket(type)` (CODE_GET_PACKET = 7) replies `[noException] [hasPacket] [typeId]`
 * and then the packet's fields as ints, in DEX alphabetical order of the field names, already
 * decoded by the framework (Trumpchi-GS5 docs: can_packet_definitions.txt and the 2026-10
 * CAN captures).
 *
 * - SpeedInfo (type 5): `m_nSpeed` (km/h).
 * - PowerInfo (type 10): `m_bEngineTemAlarmShowReq`, then `m_byteGear`
 *   (11 S, 12 D, 13 P, 14 R, 15 N; S, D and N seen on the car, P and R from the framework).
 *
 * Uses polling, which is simpler and sufficient at 4 Hz.
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
            val kmh = speedKmh(reply) ?: return
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
            val next = gear(reply) ?: return
            val changed = synchronized(this) { (gear != next).also { gear = next } }
            if (changed) Log.i(TAG, "gear $next")
        } catch (e: Exception) {
            Log.w(TAG, "gear read failed", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** km/h from a `getDefinedPacket(5)` reply, or null when there is none or it is out of range. */
    internal fun speedKmh(reply: Parcel): Int? {
        if (!packetHeader(reply, TYPE_SPEED)) return null
        return reply.readInt().takeIf { it in 0..300 }
    }

    /** The gear from a `getDefinedPacket(10)` reply, or null when there is none or it is not known. */
    internal fun gear(reply: Parcel): VehicleGear? {
        if (!packetHeader(reply, TYPE_POWER)) return null
        reply.readInt() // m_bEngineTemAlarmShowReq sorts before m_byteGear
        return when (reply.readInt()) {
            13 -> VehicleGear.PARK
            14 -> VehicleGear.REVERSE
            15 -> VehicleGear.NEUTRAL
            10, 11, 12 -> VehicleGear.DRIVE // L, S, D
            else -> null
        }
    }

    /** Reads `[noException] [hasPacket] [typeId]`; true when a packet of [type] follows. */
    private fun packetHeader(reply: Parcel, type: Int): Boolean {
        if (reply.readInt() != 0) return false
        if (reply.readInt() == 0) return false
        return reply.readInt() == type
    }

    private fun getVehicleBinder(): IBinder? = try {
        val sm = Class.forName("android.os.ServiceManager")
        sm.getMethod("getService", String::class.java).invoke(null, "vehicle") as? IBinder
    } catch (e: Exception) {
        Log.w(TAG, "ServiceManager reflection failed", e)
        null
    }
}
