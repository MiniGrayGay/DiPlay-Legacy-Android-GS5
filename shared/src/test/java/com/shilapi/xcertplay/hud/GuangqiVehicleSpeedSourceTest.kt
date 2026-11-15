package com.shilapi.xcertplay.hud

import android.os.Parcel
import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Replies as captured from the car's `getDefinedPacket` (Trumpchi-GS5 stock/can_dumps, 2026-10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GuangqiVehicleSpeedSourceTest {
    private fun reply(vararg ints: Int): Parcel = Parcel.obtain().apply {
        ints.forEach(::writeInt)
        setDataPosition(0)
    }

    @Test fun gearIsTheSecondPowerInfoField() {
        // 2026-10-17 snapshot, engine idling in N: 0 1 10 | 0 15 0 20 ...
        assertEquals(VehicleGear.NEUTRAL, GuangqiVehicleSpeedSource.gear(reply(0, 1, 10, 0, 15, 0, 20, 0)))
        // 2026-10-21 drive: D and S
        assertEquals(VehicleGear.DRIVE, GuangqiVehicleSpeedSource.gear(reply(0, 1, 10, 0, 12, 0, 20)))
        assertEquals(VehicleGear.DRIVE, GuangqiVehicleSpeedSource.gear(reply(0, 1, 10, 0, 11, 0, 20)))
        assertEquals(VehicleGear.PARK, GuangqiVehicleSpeedSource.gear(reply(0, 1, 10, 0, 13, 0, 20)))
        assertEquals(VehicleGear.REVERSE, GuangqiVehicleSpeedSource.gear(reply(0, 1, 10, 0, 14, 0, 20)))
    }

    @Test fun theTypeIdIsNotTakenForTheGear() {
        // Reading the gear one int early returns the type id 10 (L); the alarm flag must be skipped.
        assertNull(GuangqiVehicleSpeedSource.gear(reply(0, 1, 10, 0, 0, 0, 20)))
    }

    @Test fun speedFollowsTheTypeId() {
        assertEquals(42, GuangqiVehicleSpeedSource.speedKmh(reply(0, 1, 5, 42)))
        assertEquals(0, GuangqiVehicleSpeedSource.speedKmh(reply(0, 1, 5, 0)))
    }

    @Test fun missingOrForeignPacketsAreIgnored() {
        assertNull(GuangqiVehicleSpeedSource.speedKmh(reply(0, 0)))
        assertNull(GuangqiVehicleSpeedSource.speedKmh(reply(0, 1, 10, 42)))
        assertNull(GuangqiVehicleSpeedSource.speedKmh(reply(0, 1, 5, 400)))
        assertNull(GuangqiVehicleSpeedSource.gear(reply(-1, 1, 10, 0, 12)))
    }
}
