package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarBluetoothReleaseTest {
    private class FakeAdapter(var enabled: Boolean? = true, var callLinkReleasable: Boolean = false) : CarBluetoothRelease.Adapter {
        val calls = ArrayList<String>()
        override fun isEnabled(): Boolean? = enabled
        override fun releaseCallLink(): Boolean { calls += "releaseCallLink"; return callLinkReleasable }
        override fun disable(): Boolean { calls += "disable"; enabled = false; return true }
        override fun enable(): Boolean { calls += "enable"; enabled = true; return true }
    }

    private class FakeStore(override var turnedOff: Boolean = false) : CarBluetoothRelease.Store

    /** Runs nothing by itself; the test fires or cancels the one pending task. */
    private class Timer {
        var pending: (() -> Unit)? = null
        var scheduled = 0
        val schedule: (Long, () -> Unit) -> CarBluetoothRelease.Cancelable = { _, task ->
            scheduled++
            pending = task
            CarBluetoothRelease.Cancelable { pending = null }
        }
        fun fire() { pending?.also { pending = null }?.invoke() }
    }

    private val timer = Timer()
    private val release = CarBluetoothRelease(timer.schedule)
    private val log: (String) -> Unit = {}

    @Test fun turnsBluetoothOffForTheSessionAndBackOnAfterTheDelay() {
        val adapter = FakeAdapter()
        val store = FakeStore()
        release.sessionEstablished(adapter, store, log)
        assertEquals(listOf("releaseCallLink", "disable"), adapter.calls)
        assertTrue(store.turnedOff)

        release.sessionEnded(adapter, store, log)
        assertEquals(false, adapter.enabled) // not yet
        timer.fire()
        assertEquals(true, adapter.enabled)
        assertFalse(store.turnedOff)
    }

    @Test fun aReleasedCallLinkLeavesBluetoothOn() {
        val adapter = FakeAdapter(callLinkReleasable = true)
        val store = FakeStore()
        release.sessionEstablished(adapter, store, log)
        assertEquals(listOf("releaseCallLink"), adapter.calls)
        assertFalse(store.turnedOff)
        release.sessionEnded(adapter, store, log)
        assertEquals(0, timer.scheduled)
    }

    /** The 0.4.4.4 guard skipped this: a wireless start turns Bluetooth on again before the session. */
    @Test fun turnsBluetoothOffAgainWhenAReconnectTurnedItBackOn() {
        val adapter = FakeAdapter()
        val store = FakeStore()
        release.sessionEstablished(adapter, store, log)
        release.sessionEnded(adapter, store, log)
        adapter.enabled = true // the wireless bootstrap of the reconnect
        adapter.calls.clear()

        release.sessionEstablished(adapter, store, log)
        assertEquals(listOf("releaseCallLink", "disable"), adapter.calls)
        assertEquals(false, adapter.enabled)
        // The restore of the first session was cancelled; it must not fire into the new one.
        timer.fire()
        assertEquals(false, adapter.enabled)
        assertTrue(store.turnedOff)
    }

    @Test fun aQuickReconnectDoesNotToggleTheRadio() {
        val adapter = FakeAdapter()
        val store = FakeStore()
        release.sessionEstablished(adapter, store, log)
        release.sessionEnded(adapter, store, log)
        adapter.calls.clear()
        release.sessionEstablished(adapter, store, log) // still off
        assertEquals(emptyList<String>(), adapter.calls)
        assertTrue(store.turnedOff)
    }

    @Test fun bluetoothTheDriverTurnedOffStaysOff() {
        val adapter = FakeAdapter(enabled = false)
        val store = FakeStore()
        release.sessionEstablished(adapter, store, log)
        release.sessionEnded(adapter, store, log)
        release.restoreNow(adapter, store, log)
        assertEquals(emptyList<String>(), adapter.calls)
        assertEquals(false, adapter.enabled)
    }

    @Test fun restoreNowGivesBluetoothBackWithoutWaiting() {
        val adapter = FakeAdapter()
        val store = FakeStore()
        release.sessionEstablished(adapter, store, log)
        release.sessionEnded(adapter, store, log)
        release.restoreNow(adapter, store, log)
        assertEquals(true, adapter.enabled)
        timer.fire() // cancelled
        assertEquals(listOf("releaseCallLink", "disable", "enable"), adapter.calls)
    }

    /** A process that died with Bluetooth off: only the persisted flag is left. */
    @Test fun aFreshProcessRestoresWhatThePreviousOneTurnedOff() {
        val adapter = FakeAdapter(enabled = false)
        val store = FakeStore(turnedOff = true)
        CarBluetoothRelease(timer.schedule).restoreNow(adapter, store, log)
        assertEquals(listOf("enable"), adapter.calls)
        assertFalse(store.turnedOff)
    }

    @Test fun aHeadUnitWithoutAnAdapterIsLeftAlone() {
        val adapter = FakeAdapter(enabled = null)
        val store = FakeStore()
        release.sessionEstablished(adapter, store, log)
        release.sessionEnded(adapter, store, log)
        assertEquals(emptyList<String>(), adapter.calls)
        assertFalse(store.turnedOff)
    }
}
