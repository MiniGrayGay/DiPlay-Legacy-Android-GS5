package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Ntb16CodecTest {
    @Test
    fun parseCanReadBlockFromLargerBuffer() {
        val frame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
        val block = Ntb16Codec.build(frame, 7)
        val buffer = ByteArray(11) + block + ByteArray(3)

        val parsed = Ntb16Codec.parse(buffer, 11, block.size)

        assertArrayEquals(frame, parsed.single())
    }

    @Test
    fun largestDatagramKeepsBlockLengthWithinU16() {
        val block = Ntb16Codec.build(ByteArray(65_507), 0x1234)

        assertEquals(65_535, block.size)
        assertEquals(0xff, block[8].toInt() and 0xff)
        assertEquals(0xff, block[9].toInt() and 0xff)
    }

    @Test
    fun aggregatedBlockCarriesEveryFrameOnFourByteBoundaries() {
        val frames = listOf(ByteArray(74) { 1 }, ByteArray(75) { 2 }, ByteArray(1514) { 3 }, ByteArray(1) { 4 })
        val block = Ntb16Codec.build(frames, frames.size, 9)

        val seen = ArrayList<Pair<Int, Int>>()
        Ntb16Codec.forEachDatagram(block, 0, block.size) { offset, length -> seen += offset to length }
        assertEquals(frames.map { it.size }, seen.map { it.second })
        seen.forEach { assertEquals(0, it.first % 4) }
        assertEquals(frames.map { it.toList() }, Ntb16Codec.parse(block).map { it.toList() })

        // wBlockLength is what the sender predicted before it built the block.
        var payload = 0
        var predicted = 0
        frames.forEachIndexed { index, frame ->
            predicted = Ntb16Codec.aggregateLength(index, payload, frame.size)
            payload = Ntb16Codec.appendPayload(payload, frame.size)
        }
        assertEquals(predicted, Ntb16Codec.u16(block, 8))
        assertEquals(predicted, block.size)
    }

    @Test
    fun aggregatingOneFrameMatchesTheSingleFrameBlock() {
        val frame = ByteArray(74) { it.toByte() }
        assertArrayEquals(Ntb16Codec.build(frame, 5), Ntb16Codec.build(listOf(frame, frame), 1, 5))
        assertEquals(Ntb16Codec.build(frame, 5).size, Ntb16Codec.aggregateLength(0, 0, frame.size))
    }

    @Test
    fun aggregatedBlockEndingOnAUsbPacketBoundaryGetsThePadByte() {
        // Header for two frames is 32 bytes; 240 + 240 lands the block on 512.
        val block = Ntb16Codec.build(listOf(ByteArray(240), ByteArray(240)), 2, 0)
        assertEquals(512, Ntb16Codec.u16(block, 8))
        assertEquals(513, block.size)
        assertEquals(2, Ntb16Codec.parse(block, 0, 512).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun datagramThatWouldOverflowBlockLengthIsRejected() {
        Ntb16Codec.build(ByteArray(65_508), 0x1234)
    }

    @Test
    fun forEachDatagramPointsIntoTheSourceBuffer() {
        val frame = ByteArray(300) { (it * 7).toByte() }
        val stream = byteArrayOf(1, 2, 3) + Ntb16Codec.build(frame, 4)
        val seen = ArrayList<Pair<Int, Int>>()
        Ntb16Codec.forEachDatagram(stream, 3, stream.size - 3) { offset, length -> seen += offset to length }
        org.junit.Assert.assertEquals(listOf((3 + 28) to frame.size), seen)
        org.junit.Assert.assertArrayEquals(frame, stream.copyOfRange(seen[0].first, seen[0].first + seen[0].second))
    }
}
