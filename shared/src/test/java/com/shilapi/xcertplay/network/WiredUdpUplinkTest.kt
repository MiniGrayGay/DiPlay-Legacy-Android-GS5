package com.shilapi.xcertplay.network

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredUdpUplinkTest {
    private class RecordingBridge(private val accept: Boolean = true) : WiredUdpUplink.Sender {
        val sent = mutableListOf<Triple<Int, Int, ByteArray>>()
        override fun sendUdp(localPort: Int, remotePort: Int, payload: ByteArray): Boolean {
            sent += Triple(localPort, remotePort, payload)
            return accept
        }
    }

    private val packet = byteArrayOf(1, 2, 3)

    @After
    fun tearDown() {
        WiredUdpUplink.current()?.let(WiredUdpUplink::clear)
    }

    @Test
    fun aWiredSessionSendsTheMicrophoneThroughTheBridgeNotTheSocket() {
        val bridge = RecordingBridge()
        var socketSends = 0

        val delivered = WiredUdpUplink.deliver(relayPort = 41000, remotePort = 63847, packet = packet, bridge = bridge) {
            socketSends++
        }

        assertTrue(delivered)
        assertEquals(0, socketSends)
        assertEquals(1, bridge.sent.size)
        assertEquals(41000, bridge.sent[0].first)
        assertEquals(63847, bridge.sent[0].second)
        assertArrayEquals(packet, bridge.sent[0].third)
    }

    @Test
    fun aRefusedBridgeSendIsReportedAndNotRetriedOnTheSocket() {
        val bridge = RecordingBridge(accept = false)
        var socketSends = 0

        val delivered = WiredUdpUplink.deliver(relayPort = 41000, remotePort = 63847, packet = packet, bridge = bridge) {
            socketSends++
        }

        assertFalse(delivered)
        assertEquals("a socket send would vanish on the wired link", 0, socketSends)
    }

    @Test
    fun wirelessUsesTheSocketBecauseNoBridgeRuns() {
        var socketPacket: ByteArray? = null

        val delivered = WiredUdpUplink.deliver(relayPort = 41000, remotePort = 63847, packet = packet, bridge = null) {
            socketPacket = it
        }

        assertTrue(delivered)
        assertArrayEquals(packet, socketPacket)
    }

    @Test
    fun aStreamWithoutARelayPortUsesTheSocket() {
        val bridge = RecordingBridge()
        var socketSends = 0

        WiredUdpUplink.deliver(relayPort = null, remotePort = 63847, packet = packet, bridge = bridge) { socketSends++ }
        WiredUdpUplink.deliver(relayPort = 0, remotePort = 63847, packet = packet, bridge = bridge) { socketSends++ }

        assertEquals(2, socketSends)
        assertTrue(bridge.sent.isEmpty())
    }

    @Test
    fun anEndedBridgeDoesNotClearItsReplacement() {
        val first = RecordingBridge()
        val second = RecordingBridge()
        WiredUdpUplink.install(first)
        WiredUdpUplink.install(second)

        WiredUdpUplink.clear(first)
        assertSame(second, WiredUdpUplink.current())

        WiredUdpUplink.clear(second)
        assertNull(WiredUdpUplink.current())
    }
}
