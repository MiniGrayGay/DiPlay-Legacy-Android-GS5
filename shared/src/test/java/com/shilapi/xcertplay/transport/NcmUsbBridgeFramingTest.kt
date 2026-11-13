package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Replays USB completions through recv(), including boundaries invisible to an NTB byte stream. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [NcmFramingConnectionShadow::class, NcmFramingRequestShadow::class])
class NcmUsbBridgeFramingTest {
    @Before fun reset() { NcmFramingReplay.reset() }

    @Test fun completeAlignedBlockDoesNotWaitForAnExtraByte() {
        NcmFramingReplay.chunks.add(unpadded(alignedFrame, 1))
        withBridge { ncm -> assertArrayEquals(alignedFrame, ncm.recv(50)) }
        assertEquals(1, NcmFramingReplay.completions)
    }

    @Test fun adjacentAlignedBlocksKeepBothHeadersAndAllFrames() {
        val second = ByteArray(996) { (it * 17).toByte() }
        NcmFramingReplay.chunks.add(
            unpadded(alignedFrame, 1) + unpadded(second, 2) + Ntb16Codec.build(smallFrame, 3),
        )
        withBridge { ncm ->
            assertArrayEquals(alignedFrame, ncm.recv(50))
            assertArrayEquals(second, ncm.recv(50))
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
        assertEquals(1, NcmFramingReplay.completions)
    }

    @Test fun optionalZeroPadAndFollowingBlockCanShareACompletion() {
        NcmFramingReplay.chunks.add(Ntb16Codec.build(alignedFrame, 1) + Ntb16Codec.build(smallFrame, 2))
        withBridge { ncm ->
            assertArrayEquals(alignedFrame, ncm.recv(50))
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
    }

    @Test fun optionalPadAndNextHeaderCanArriveInSeparatePartialReads() {
        checkSplitFollowingBlock(padded = true)
    }

    @Test fun unpaddedNextHeaderCanArriveInSeparatePartialReads() {
        checkSplitFollowingBlock(padded = false)
    }

    @Test fun nonzeroGarbageAfterAlignedBlockIsSkippedAndTheNextBlockRecovered() {
        NcmFramingReplay.chunks.add(unpadded(alignedFrame, 1) + byteArrayOf(0x55))
        NcmFramingReplay.chunks.add(Ntb16Codec.build(smallFrame, 2))
        val diagnostics = withBridge { ncm ->
            assertArrayEquals(alignedFrame, ncm.recv(50))
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
        assertEquals(listOf("NCM resynchronized after Invalid NTB16 short-packet pad; skipped=1 resyncs=1"), diagnostics)
    }

    @Test fun nextHeaderWithOnlyItsFirstSignatureByteCorrectIsDropped() {
        val malformed = Ntb16Codec.build(smallFrame, 2).also { it[1] = 0x55 }
        NcmFramingReplay.chunks.add(unpadded(alignedFrame, 1) + malformed)
        NcmFramingReplay.chunks.add(Ntb16Codec.build(alignedFrame, 3))
        withBridge { ncm ->
            assertArrayEquals(alignedFrame, ncm.recv(50))
            assertArrayEquals(alignedFrame, ncm.recv(50))
        }
    }

    @Test fun moreThanOnePadByteIsSkipped() {
        NcmFramingReplay.chunks.add(Ntb16Codec.build(alignedFrame, 1) + byteArrayOf(0) + Ntb16Codec.build(smallFrame, 2))
        withBridge { ncm ->
            assertArrayEquals(alignedFrame, ncm.recv(50))
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
    }

    @Test fun unexpectedPaddingAfterAnUnalignedBlockIsSkipped() {
        NcmFramingReplay.chunks.add(Ntb16Codec.build(smallFrame, 1) + byteArrayOf(0) + Ntb16Codec.build(smallFrame, 2))
        withBridge { ncm ->
            assertArrayEquals(smallFrame, ncm.recv(50))
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
    }

    @Test fun fullSpeedLinkAcceptsThePadAfterA64ByteAlignedBlock() {
        val frame64 = ByteArray(484 - 448) { (it * 7).toByte() } // 28 + 36 = 64 bytes
        val block = Ntb16Codec.build(frame64, 1)
        assertEquals(64, block.size)
        NcmFramingReplay.chunks.add(block + byteArrayOf(0) + Ntb16Codec.build(smallFrame, 2))
        val diagnostics = withBridge(packetSize = 64) { ncm ->
            assertArrayEquals(frame64, ncm.recv(50))
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
        assertTrue("A 64-byte link pad is expected, not a resync: $diagnostics", diagnostics.isEmpty())
    }

    @Test fun aBlockCutShortInTheMiddleOfAStreamLosesOnlyThatBlock() {
        val second = ByteArray(996) { (it * 17).toByte() }
        val damaged = Ntb16Codec.build(second, 2)
        NcmFramingReplay.chunks.add(Ntb16Codec.build(smallFrame, 1) + damaged.copyOf(300))
        val following = (3 until 43).map { Ntb16Codec.build(smallFrame, it) }.reduce(ByteArray::plus)
        NcmFramingReplay.chunks.add(damaged.copyOfRange(700, damaged.size) + following)
        withBridge { ncm ->
            assertArrayEquals(smallFrame, ncm.recv(50))
            // The cut block's header claims more bytes than arrived, so it swallows the start of
            // the traffic behind it; the IP stack drops what it yields, and the stream recovers.
            val received = generateSequence { ncm.recv(50) }.toList()
            assertTrue(received.size >= 20)
            assertTrue(received.takeLast(20).all { it.contentEquals(smallFrame) })
        }
    }

    @Test fun damageThatKeepsRecurringStillFailsTheTransport() {
        repeat(20) { NcmFramingReplay.chunks.add(Ntb16Codec.build(smallFrame, it) + byteArrayOf(0x55)) }
        withBridge { ncm ->
            val error = expectFailure { repeat(40) { ncm.recv(50) } }
            assertTrue(error.message!!.contains("(repeated)"))
            assertTrue(error === expectFailure { ncm.recv(50) })
        }
    }

    @Test fun severalReadsStayQueuedWhileFramesAreProcessed() {
        repeat(6) { NcmFramingReplay.chunks.add(Ntb16Codec.build(byteArrayOf(it.toByte(), 1, 2, 3), it)) }
        withBridge { ncm ->
            repeat(6) { assertArrayEquals(byteArrayOf(it.toByte(), 1, 2, 3), ncm.recv(50)) }
        }
        assertEquals(NcmUsbBridge.READ_REQUEST_COUNT, NcmFramingReplay.maxPending)
    }

    @Test fun theReadRingKeepsWireOrderAcrossSplitBlocks() {
        val frames = (0 until 30).map { index -> ByteArray(200 + index * 37) { (index * 13 + it).toByte() } }
        val stream = frames.mapIndexed { index, frame -> Ntb16Codec.build(frame, index) }.reduce(ByteArray::plus)
        var offset = 0
        var size = 1
        while (offset < stream.size) {
            val end = minOf(stream.size, offset + size)
            NcmFramingReplay.chunks.add(stream.copyOfRange(offset, end))
            offset = end
            size = size * 3 % 1700 + 1
        }
        withBridge { ncm ->
            val received = ArrayList<ByteArray>()
            while (received.size < frames.size) {
                ncm.recvInto(50) { buffer, start, length -> received += buffer.copyOfRange(start, start + length) }
            }
            frames.forEachIndexed { index, frame -> assertArrayEquals("frame $index", frame, received[index]) }
        }
    }

    @Test fun recvIntoDeliversEveryBufferedFrameInPlace() {
        val second = ByteArray(996) { (it * 17).toByte() }
        NcmFramingReplay.chunks.add(
            Ntb16Codec.build(smallFrame, 1) + Ntb16Codec.build(second, 2) + Ntb16Codec.build(smallFrame, 3),
        )
        withBridge { ncm ->
            val received = ArrayList<ByteArray>()
            val delivered = ncm.recvInto(50) { buffer, start, length ->
                received += buffer.copyOfRange(start, start + length)
            }
            assertEquals(3, delivered)
            assertArrayEquals(smallFrame, received[0])
            assertArrayEquals(second, received[1])
            assertArrayEquals(smallFrame, received[2])
            assertEquals(0, ncm.recvInto(20) { _, _, _ -> throw AssertionError("no frame expected") })
            val window = ncm.takePerfWindow()
            assertEquals(1, window.readCompletions)
            assertEquals(3, window.framesDelivered)
            assertEquals(NcmUsbBridge.READ_REQUEST_COUNT, window.requestsConfigured)
        }
    }

    @Test fun framesStayValidUntilTheSinkIsToldTheBatchEnded() {
        val second = ByteArray(996) { (it * 17).toByte() }
        NcmFramingReplay.chunks.add(Ntb16Codec.build(smallFrame, 1) + Ntb16Codec.build(second, 2))
        NcmFramingReplay.chunks.add(Ntb16Codec.build(smallFrame, 3))
        withBridge { ncm ->
            val received = ArrayList<ByteArray>()
            // Collects (buffer, offset, length) and only reads the bytes at the batch end, the
            // way the lwIP bridge injects them.
            val sink = object : NcmUsbBridge.FrameSink {
                val pending = ArrayList<Triple<ByteArray, Int, Int>>()
                override fun onFrame(buffer: ByteArray, offset: Int, length: Int) {
                    pending += Triple(buffer, offset, length)
                }
                override fun onBatchEnd() {
                    pending.forEach { (buffer, offset, length) -> received += buffer.copyOfRange(offset, offset + length) }
                    pending.clear()
                }
            }
            while (received.size < 3) ncm.recvInto(50, sink)
            assertEquals(0, sink.pending.size)
            assertArrayEquals(smallFrame, received[0])
            assertArrayEquals(second, received[1])
            assertArrayEquals(smallFrame, received[2])
        }
    }

    @Test fun aRefusedConcurrentReadFallsBackToOneRequest() {
        NcmFramingReplay.refuseWhenPending = 1
        repeat(3) { NcmFramingReplay.chunks.add(Ntb16Codec.build(byteArrayOf(it.toByte(), 9), it)) }
        val diagnostics = withBridge { ncm ->
            repeat(3) { assertArrayEquals(byteArrayOf(it.toByte(), 9), ncm.recv(50)) }
            assertTrue(ncm.takePerfWindow().concurrentReadsDisabled)
        }
        assertEquals(1, NcmFramingReplay.maxPending)
        assertEquals(1, diagnostics.count { it.startsWith("NCM concurrent read refused") })
    }

    private fun checkSplitFollowingBlock(padded: Boolean) {
        NcmFramingReplay.chunks.add(unpadded(alignedFrame, 1))
        if (padded) NcmFramingReplay.chunks.add(byteArrayOf(0))
        val following = Ntb16Codec.build(smallFrame, 2)
        NcmFramingReplay.chunks.addAll(listOf(
            following.copyOfRange(0, 1), following.copyOfRange(1, 3),
            following.copyOfRange(3, 12), following.copyOfRange(12, following.size),
        ))
        withBridge { ncm ->
            assertArrayEquals(alignedFrame, ncm.recv(50))
            assertEquals(1, NcmFramingReplay.completions)
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
        assertTrue(NcmFramingReplay.chunks.isEmpty())
    }

    private fun unpadded(frame: ByteArray, sequence: Int): ByteArray {
        val block = Ntb16Codec.build(frame, sequence)
        assertEquals(1, block.size % 512)
        return block.copyOf(block.size - 1)
    }

    private fun expectFailure(block: () -> Unit): IphoneUsbException.DeviceUnavailable {
        try { block(); throw AssertionError("Expected invalid NTB framing to fail") }
        catch (error: IphoneUsbException.DeviceUnavailable) { return error }
    }

    private fun withBridge(packetSize: Int = 512, block: (NcmUsbBridge) -> Unit): List<String> {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, packetSize),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        val diagnostics = mutableListOf<String>()
        val ncm = NcmUsbBridge(SharedUsbDeviceConnection.own(connection), endpoint(0x06), endpoint(0x85), null, emptyList(), true, null, diagnostics::add)
        try { block(ncm) } finally { ncm.close() }
        return diagnostics
    }

    companion object {
        private val alignedFrame = ByteArray(484) { (it * 31).toByte() }
        private val smallFrame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
    }
}

object NcmFramingReplay {
    /** Each completion fills the oldest queued read, as a bulk endpoint does. */
    val chunks = ArrayDeque<ByteArray>()
    val pending = ArrayDeque<Pair<UsbRequest, ByteBuffer>>()
    val cancelled = ArrayDeque<UsbRequest>()
    var completions = 0
    var maxPending = 0
    /** Refuse queue() while this many reads are already pending (null = never). */
    var refuseWhenPending: Int? = null
    fun reset() {
        chunks.clear(); pending.clear(); cancelled.clear()
        completions = 0; maxPending = 0; refuseWhenPending = null
    }
}

@Implements(UsbRequest::class)
class NcmFramingRequestShadow {
    @RealObject lateinit var request: UsbRequest
    @Implementation fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint) = true
    @Implementation fun queue(buffer: ByteBuffer): Boolean {
        val limit = NcmFramingReplay.refuseWhenPending
        if (limit != null && NcmFramingReplay.pending.size >= limit) return false
        NcmFramingReplay.pending.addLast(request to buffer)
        NcmFramingReplay.maxPending = maxOf(NcmFramingReplay.maxPending, NcmFramingReplay.pending.size)
        return true
    }
    @Implementation fun cancel(): Boolean {
        val entry = NcmFramingReplay.pending.firstOrNull { it.first === request } ?: return false
        NcmFramingReplay.pending.remove(entry)
        NcmFramingReplay.cancelled.addLast(request)
        return true
    }
    @Implementation fun close() = Unit
}

@Implements(UsbDeviceConnection::class)
class NcmFramingConnectionShadow {
    @Implementation fun requestWait(timeoutMillis: Long): UsbRequest {
        NcmFramingReplay.cancelled.pollFirst()?.let { return it }
        if (NcmFramingReplay.chunks.isEmpty()) throw TimeoutException()
        val (request, buffer) = NcmFramingReplay.pending.pollFirst() ?: throw TimeoutException()
        buffer.put(NcmFramingReplay.chunks.pollFirst())
        NcmFramingReplay.completions += 1
        return request
    }
    @Implementation fun close() = Unit
}
