package com.shilapi.xcertplay.transport

/**
 * Minimal NTB16 codec for the iPhone NCM data link.
 *
 * Only the 16-bit transfer-block layout used by LIVI's `iap2-usbmux/src/ntb.rs` is implemented:
 * one NTH16 header, one NDP16 table and the USB short-packet pad byte. Datagrams start on
 * 4-byte boundaries, which is what the iPhone asks for (wNdpOutDivisor=4, remainder 0). There
 * is no NTB32, CRC, or NCM control-plane handling here.
 */
object Ntb16Codec {
    const val NTH16_SIG = 0x484d434e
    const val NDP16_SIG = 0x304d434e

    internal const val NTH_LENGTH = 12
    private const val NDP_LENGTH = 16
    private const val DATAGRAM_INDEX = 28
    private const val USB_PACKET_SIZE = 512

    /** Wraps one Ethernet frame in one NTB16 block, padding an exact 512-byte boundary. */
    fun build(frame: ByteArray, sequence: Int): ByteArray {
        require(frame.isNotEmpty()) { "frame must not be empty" }
        require(frame.size <= MAX_DATAGRAM_BYTES) { "frame exceeds the NTB16 length field" }
        require(sequence in 0..0xffff) { "sequence must fit in u16" }

        val blockLength = DATAGRAM_INDEX + frame.size
        val block = ByteArray(blockLength)
        putU32(block, 0, NTH16_SIG)
        putU16(block, 4, NTH_LENGTH)
        putU16(block, 6, sequence)
        putU16(block, 8, blockLength)
        putU16(block, 10, NTH_LENGTH)

        putU32(block, 12, NDP16_SIG)
        putU16(block, 16, NDP_LENGTH)
        putU16(block, 18, 0)
        putU16(block, 20, DATAGRAM_INDEX)
        putU16(block, 22, frame.size)
        putU16(block, 24, 0)
        putU16(block, 26, 0)

        frame.copyInto(block, DATAGRAM_INDEX)
        // A transfer that ends exactly on a USB packet boundary is read as a short packet.
        return if (block.size % USB_PACKET_SIZE == 0) block + byteArrayOf(0) else block
    }

    /**
     * Wraps [count] Ethernet frames in one NTB16 block so they cost one USB transfer. A single
     * frame produces the same bytes as [build] for one frame. The caller keeps the block within
     * the phone's dwNtbOutMaxSize with [aggregateLength].
     */
    fun build(frames: List<ByteArray>, count: Int, sequence: Int): ByteArray {
        require(count in 1..frames.size) { "count must select at least one frame" }
        if (count == 1) return build(frames[0], sequence)
        require(sequence in 0..0xffff) { "sequence must fit in u16" }

        // NDP16: 8-byte header, one 4-byte entry per datagram and the zero terminator entry.
        val ndpLength = 8 + 4 * (count + 1)
        var blockLength = align4(NTH_LENGTH + ndpLength)
        for (index in 0 until count) {
            require(frames[index].isNotEmpty()) { "frame must not be empty" }
            blockLength = align4(blockLength) + frames[index].size
        }
        require(blockLength <= 0xffff) { "frames exceed the NTB16 length field" }

        val pad = if (blockLength % USB_PACKET_SIZE == 0) 1 else 0
        val block = ByteArray(blockLength + pad)
        putU32(block, 0, NTH16_SIG)
        putU16(block, 4, NTH_LENGTH)
        putU16(block, 6, sequence)
        putU16(block, 8, blockLength)
        putU16(block, 10, NTH_LENGTH)

        putU32(block, 12, NDP16_SIG)
        putU16(block, 16, ndpLength)
        putU16(block, 18, 0)
        var entry = NTH_LENGTH + 8
        var offset = align4(NTH_LENGTH + ndpLength)
        for (index in 0 until count) {
            val frame = frames[index]
            offset = align4(offset)
            putU16(block, entry, offset)
            putU16(block, entry + 2, frame.size)
            frame.copyInto(block, offset)
            offset += frame.size
            entry += 4
        }
        return block
    }

    /**
     * wBlockLength of a block once a frame of [nextBytes] joins the [count] frames it holds.
     * [payloadBytes] is the length of those frames including the padding between them: 0 for
     * none, then `align4(payloadBytes) + size` per frame, which is [appendPayload]. Lets a
     * sender stop before the phone's dwNtbOutMaxSize.
     */
    fun aggregateLength(count: Int, payloadBytes: Int, nextBytes: Int): Int =
        align4(NTH_LENGTH + 8 + 4 * (count + 2)) + align4(payloadBytes) + nextBytes

    /** Running payload length after a frame of [frameBytes] is added; see [aggregateLength]. */
    fun appendPayload(payloadBytes: Int, frameBytes: Int): Int = align4(payloadBytes) + frameBytes

    private fun align4(value: Int): Int = (value + 3) and 3.inv()

    /**
     * Extracts the Ethernet frames carried by one NTB16 block.
     *
     * Wire garbage returns an empty list instead of throwing; malformed entries are skipped where
     * a bounds check fails. A step guard stops a malformed NDP pointer chain from cycling.
     */
    fun parse(block: ByteArray): List<ByteArray> = parse(block, 0, block.size)

    /** Parses one NTB16 block from [offset] without copying the containing buffer. */
    fun parse(block: ByteArray, offset: Int, length: Int): List<ByteArray> {
        val datagrams = ArrayList<ByteArray>(1)
        forEachDatagram(block, offset, length) { start, size ->
            datagrams.add(block.copyOfRange(start, start + size))
        }
        return datagrams
    }

    /**
     * Calls [onDatagram] with the absolute offset and length of each Ethernet frame in one NTB16
     * block, in wire order, without allocating. Validation is identical to [parse].
     */
    internal inline fun forEachDatagram(block: ByteArray, offset: Int, length: Int, onDatagram: (Int, Int) -> Unit) {
        require(offset >= 0 && length >= 0 && offset <= block.size - length) {
            "NTB16 range is outside the source buffer"
        }
        val end = offset + length
        if (length < NTH_LENGTH || u32(block, offset) != NTH16_SIG) return

        var ndpOffset = offset + u16(block, offset + 10)
        var hops = 0
        val maxHops = maxOf(1, length / 4)
        while (ndpOffset != 0 && hops < maxHops) {
            if (ndpOffset < offset || ndpOffset + 12 > end) break
            if (u32(block, ndpOffset) and 0x00ffffff != NDP16_SIG and 0x00ffffff) break
            val ndpLength = u16(block, ndpOffset + 4)
            val nextNdp = u16(block, ndpOffset + 6)
            var entry = ndpOffset + 8
            val ndpEnd = minOf(ndpOffset + ndpLength, end)
            while (entry + 4 <= ndpEnd) {
                val datagramIndex = u16(block, entry)
                val datagramLength = u16(block, entry + 2)
                if (datagramIndex == 0 || datagramLength == 0) break
                val datagramStart = offset + datagramIndex
                val datagramEnd = datagramStart + datagramLength
                if (datagramStart >= offset && datagramStart <= end && datagramEnd <= end) {
                    onDatagram(datagramStart, datagramLength)
                }
                entry += 4
            }
            ndpOffset = if (nextNdp == 0) 0 else offset + nextNdp
            hops++
        }
    }

    internal fun u16(source: ByteArray, offset: Int): Int = readU16(source, offset)

    internal fun u32(source: ByteArray, offset: Int): Int = readU32(source, offset)

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    private const val MAX_DATAGRAM_BYTES = 0xffff - DATAGRAM_INDEX
}
