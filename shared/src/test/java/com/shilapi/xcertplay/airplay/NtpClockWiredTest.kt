package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** On wired lwIP the peer address is loopback, where no iPhone listens: the bridge carries the clock. */
class NtpClockWiredTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun theClockFollowsTheIphoneThroughTheBridge() {
        val sent = LinkedBlockingQueue<Triple<Int, Int, ByteArray>>()
        val bridge = WiredUdpUplink.Sender { local, remote, payload -> sent.add(Triple(local, remote, payload)) }
        val ntp = NtpClock(loopback)
        WiredUdpUplink.install(bridge)
        try {
            val port = ntp.listen()
            ntp.start(loopback, 50_123)
            repeat(2) {
                val request = sent.poll(3, TimeUnit.SECONDS)!!
                assertEquals(port, request.first)
                assertEquals(50_123, request.second)
                assertEquals(210, request.third[1].toInt() and 0xff)
                val reply = reply(request.third)
                DatagramSocket().use { it.send(DatagramPacket(reply, reply.size, loopback, port)) }
            }
            Thread.sleep(300)
            val ahead = ntp.syncedNtp().shiftRight(32).toLong() - (System.currentTimeMillis() / 1000 + 2_208_988_800L)
            assertTrue("the clock is ${ahead}s ahead", ahead in 3_590L..3_610L)
        } finally {
            WiredUdpUplink.clear(bridge)
            ntp.close()
        }
    }

    /** An iPhone whose clock is an hour ahead. */
    private fun reply(request: ByteArray): ByteArray {
        val t1 = ByteBuffer.wrap(request).getLong(24)
        val response = ByteArray(32)
        response[0] = 0x80.toByte()
        response[1] = 211.toByte()
        ByteBuffer.wrap(response).apply {
            putLong(8, t1)
            putLong(16, t1 + (3600L shl 32))
            putLong(24, t1 + (3600L shl 32))
        }
        return response
    }
}
