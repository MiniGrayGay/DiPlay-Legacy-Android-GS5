package com.shilapi.xcertplay

import com.shilapi.xcertplay.bluetooth.*
import com.shilapi.xcertplay.usbbt.*
import com.shilapi.xcertplay.network.LwipNcmBridge
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Wire fixtures use different local/remote CIDs, as on the supplied T3/iPhone log. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class T3ConnectionRegressionTest {
    private fun le(n: Int) = byteArrayOf(n.toByte(), (n ushr 8).toByte())
    private class Peer {
        val hci = mock(UsbBtHci::class.java)
        val layer = L2capLayer(1, hci)
        val writes = CopyOnWriteArrayList<ByteArray>()
        val configSent = CountDownLatch(1)
        var configId = 0
        val remoteCid = 0x1b12
        var localCid = 0
        private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 255) or ((b[i+1].toInt() and 255) shl 8)
        private fun le(n: Int) = byteArrayOf(n.toByte(), (n ushr 8).toByte())
        fun signal(code: Int, id: Int, data: ByteArray) = layer.onAclData(
            HciProtocol.encodeL2cap(1, byteArrayOf(code.toByte(), id.toByte()) + le(data.size) + data))
        init {
            `when`(hci.sendAcl(anyInt(), (any(ByteArray::class.java) ?: byteArrayOf()), anyInt())).thenAnswer { call ->
                val packet = call.getArgument<ByteArray>(1)
                writes.add(packet)
                if (u16(packet, 2) == 1) when(packet[4].toInt() and 255) {
                    2 -> {
                        localCid = u16(packet, 10)
                        signal(3, packet[5].toInt() and 255, le(remoteCid) + le(localCid) + le(0) + le(0))
                    }
                    4 -> { configId = packet[5].toInt() and 255; configSent.countDown() }
                }
                true
            }
        }
        fun peerConfig() = signal(4, 77, le(localCid) + le(0))
        fun acceptConfig(result: Int = 0) = signal(5, configId, le(localCid) + le(0) + le(result))
    }

    @Test fun l2capWaitsForBothConfigurationsAndRepliesUsingPeerCid() {
        val peer = Peer()
        val connect = FutureTask { peer.layer.connect(1) }
        Thread(connect).start()
        try {
            assertTrue(peer.configSent.await(2, TimeUnit.SECONDS))
            assertFalse(connect.isDone)
            peer.peerConfig()
            val response = peer.writes.last { it[4] == 5.toByte() }
            assertArrayEquals(le(peer.remoteCid), response.copyOfRange(8, 10))
            assertFalse(connect.isDone)
            peer.acceptConfig()
            assertTrue(connect.get(2, TimeUnit.SECONDS) is L2capLayer.L2capResult.Ok)
        } finally { peer.layer.close() }
    }

    @Test fun rejectedL2capConfigurationDoesNotExposeADataChannel() {
        val peer = Peer()
        val connect = FutureTask { peer.layer.connect(3) }
        Thread(connect).start()
        try {
            assertTrue(peer.configSent.await(2, TimeUnit.SECONDS))
            peer.peerConfig()
            peer.acceptConfig(2)
            assertTrue(connect.get(2, TimeUnit.SECONDS) is L2capLayer.L2capResult.Failed)
        } finally { peer.layer.close() }
    }

    @Test fun sdpRequestContainsExactlyOneUuidAndCanBeReadByTheServer() {
        val peer = Peer()
        val client = SdpClient(peer.layer, 1, uuidBigEndian(UsbBtIdentity.IAP2_ACCESSORY_UUID))
        val method = client.javaClass.declaredMethods.single { it.name.startsWith("buildSearchAttrRequest") }
        method.isAccessible = true
        val request = method.invoke(client, 0x1234) as ByteArray
        assertEquals(0x11, request[6].toInt())
        val (pattern, consumed) = SdpCodec.decode(request.copyOfRange(5, request.size))
        assertEquals(19, consumed)
        assertEquals(1, (pattern as DataElement.Sequence).items.size)
        val response = SdpServer(SdpCodec.defaultRecords(UsbBtIdentity(name = "test", iap2RfcommChannel = 5)))
            .handle(request, 1000)
        assertEquals(5, SdpClient.extractRfcommChannel(SdpClient.parseResponse(response, 0x1234)!!))
    }

    @Test fun inboundRfcommSessionAnswersSabmWithResponderCrBit() {
        val hci = mock(UsbBtHci::class.java)
        val layer = L2capLayer(1, hci)
        val writes = mutableListOf<ByteArray>()
        `when`(hci.sendAcl(anyInt(), (any(ByteArray::class.java) ?: byteArrayOf()), anyInt())).thenAnswer {
            writes += it.getArgument<ByteArray>(1); true
        }
        val rfcomm = RfcommLayer(layer, L2capLayer.Channel(64, 0x1b12, 3), initiator = false)
        // Standard RFCOMM session SABM from the iPhone, including its FCS.
        rfcomm.onRfcommFrame(byteArrayOf(0x03, 0x3f, 0x01, 0x1c))
        assertArrayEquals(byteArrayOf(0x03, 0x73, 0x01, 0xd7.toByte()), writes.single().copyOfRange(4, 8))
        rfcomm.close(); layer.close()
    }

    @Test fun pluggedInDongleRequiresItsOwnServiceAndNeverFallsBackToTheBuiltInAdapter() {
        val dongle = mock(UsbBtBluetoothAdapter::class.java)
        val native = mock(AndroidBluetoothAdapter::class.java)
        `when`(dongle.isSupported()).thenReturn(true)
        `when`(native.isEnabled()).thenReturn(true)
        val hybrid = HybridBluetoothAdapter(native, dongle)
        assertFalse(hybrid.isEnabled())
        `when`(dongle.isEnabled()).thenReturn(true)
        `when`(dongle.openRfcommStream("peer", "uuid")).thenAnswer { throw IOException("SDP rejected") }
        try { hybrid.openRfcommStream("peer", "uuid"); fail("must preserve the dongle failure") }
        catch (expected: IOException) { assertEquals("SDP rejected", expected.message) }
        verify(native, never()).openRfcommStream(anyString(), anyString())
    }

    @Test fun abortedUserspaceBridgeStillReleasesNcmExactlyOnce() {
        val ncm = mock(NcmUsbBridge::class.java)
        val bridge = LwipNcmBridge(ncm, byteArrayOf(2, 0, 0, 0, 0, 2), "fe80::2", onError = {})
        bridge.markAborted()
        bridge.close()
        bridge.close()
        verify(ncm).markAborted()
        verify(ncm, times(1)).close()
    }

    @Test fun concurrentAuthenticationWaitsForDriverConfirmationAndStartsOnlyOneExchange() {
        val hci = mock(UsbBtHci::class.java)
        val events = LinkedBlockingQueue<HciProtocol.HciEvent>()
        val requested = CountDownLatch(1)
        val adapter = UsbBtBluetoothAdapter(RuntimeEnvironment.getApplication())
        `when`(hci.pollEvent(anyInt())).thenAnswer { events.poll(100, TimeUnit.MILLISECONDS) }
        `when`(hci.sendCommand(anyInt(), (any(ByteArray::class.java) ?: byteArrayOf()), anyInt())).thenAnswer { call ->
            when (call.getArgument<Int>(0)) {
                HciProtocol.OP_AUTHENTICATION_REQUESTED -> requested.countDown()
                HciProtocol.OP_SET_CONNECTION_ENCRYPTION -> events.offer(
                    HciProtocol.HciEvent(HciProtocol.EVT_ENCRYPTION_CHANGE, byteArrayOf(0, 1, 0, 1)))
            }
            UsbBtHci.HciResult.Ok(byteArrayOf(0))
        }
        ReflectionHelpers.setField(adapter, "hci", hci)
        ReflectionHelpers.callInstanceMethod<Unit>(adapter, "startPump", ClassParameter.from(UsbBtHci::class.java, hci))
        val authenticate = adapter.javaClass.declaredMethods.single { it.name == "authenticateAndEncrypt\$default" }
            .apply { isAccessible = true }
        fun request() = FutureTask { authenticate.invoke(null, adapter, hci, 1, 0L, 4, null) }
        val first = request()
        val second = request()
        try {
            Thread(first).start()
            assertTrue(requested.await(2, TimeUnit.SECONDS))
            Thread(second).start()
            // A driver comparing codes and granting phone permissions can take
            // longer than the old three-second security deadline.
            assertFalse(CountDownLatch(1).await(3_200, TimeUnit.MILLISECONDS))
            assertFalse(first.isDone)
            verify(hci, times(1)).sendCommand(eq(HciProtocol.OP_AUTHENTICATION_REQUESTED),
                (any(ByteArray::class.java) ?: byteArrayOf()), anyInt())
            events.offer(HciProtocol.HciEvent(HciProtocol.EVT_AUTHENTICATION_COMPLETE, byteArrayOf(0, 1, 0)))
            first.get(3, TimeUnit.SECONDS)
            second.get(3, TimeUnit.SECONDS)
        } finally {
            first.cancel(true)
            second.cancel(true)
            adapter.shutdown()
        }
    }
}
