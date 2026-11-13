package com.shilapi.xcertplay.usbbt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.shilapi.xcertplay.compat.realSdkInt
import android.util.Log
import com.shilapi.xcertplay.bluetooth.CarPlayBluetooth
import com.shilapi.xcertplay.bluetooth.CarPlayBluetoothDevice
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_AUTHENTICATION_COMPLETE
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_CONNECTION_COMPLETE
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_CONNECTION_REQUEST
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_DISCONNECTION_COMPLETE
import com.shilapi.xcertplay.usbbt.HciProtocol.EVT_ENCRYPTION_CHANGE
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_ACCEPT_CONNECTION_REQUEST
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_AUTHENTICATION_REQUESTED
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_CREATE_CONNECTION
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_DISCONNECT
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_RESET
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_SET_CONNECTION_ENCRYPTION
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_WRITE_SCAN_ENABLE
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeAcceptConnection
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeAuthenticationRequested
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeBdAddr
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeCreateConnection
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeDisconnect
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeSetConnectionEncryption
import com.shilapi.xcertplay.usbbt.HciProtocol.parseConnectionComplete
import com.shilapi.xcertplay.usbbt.HciProtocol.parseConnectionRequest
import com.shilapi.xcertplay.usbbt.HciProtocol.parseDisconnectionComplete
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [CarPlayBluetooth] implementation backed by a USB Bluetooth dongle driven
 * through the Android USB Host API (Phase 5).
 *
 * Lifecycle (all blocking calls belong on a background thread):
 * - [isSupported]: a dongle is plugged in (USB device scan)
 * - [enable]: request USB permission if needed, open HCI, initialize,
 *   start the pairing agent
 * - [openRfcommStream]: HCI Create_Connection -> L2CAP (SDP query for the
 *   iAP2 channel, fallback to channel 1) -> RFCOMM DLC -> byte stream
 *
 * Threading: a single event-pump thread routes HCI events (connection
 * completions) and ACL data to the L2CAP layer. Public methods are
 * thread-safe.
 *
 * Clean-room: integration logic only; protocols from the public spec.
 */
class UsbBtBluetoothAdapter(
    private val appContext: Context,
) : CarPlayBluetooth {

    companion object {
        private const val TAG = "UsbBtBluetoothAdapter"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val FALLBACK_IAP2_CHANNEL = 1
        /** Issue #281: result=0x4 rejections on one ACL before it is force-dropped. */
        private const val STALE_ACL_REJECTION_LIMIT = 2
    }

    private val usbManager: UsbManager? =
        runCatching {
            appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
        }.getOrNull()
    private val detector = UsbBtDongleDetector(appContext)
    private val linkKeys = LinkKeyStore(appContext)

    /** Guard for lifecycle transitions. */
    private val lock = Any()
    /** Serializes enable against the orderly wireless-handoff shutdown sequence. */
    private val shutdownLock = Any()

    @Volatile private var hci: UsbBtHci? = null
    private var pairingAgent: UsbBtPairingAgent? = null
    @Volatile private var activeDevice: UsbDevice? = null
    @Volatile private var localBdAddr: String? = null
    private var dongleLabel: String? = null

    /** BD_ADDR -> pending ACL connection latch. */
    private val pendingAcl = ConcurrentHashMap<String, PendingAcl>()

    /** ACL handle -> L2CAP layer (one per peer connection). */
    private val l2capLayers = ConcurrentHashMap<Int, L2capLayer>()
    /** Issue #281: consecutive L2CAP result=0x4 rejections per ACL handle. */
    private val l2capRejectionCounts = ConcurrentHashMap<Int, AtomicInteger>()

    /** ACL handle -> active RFCOMM layer (inbound or outbound). */
    private val rfcommLayers = ConcurrentHashMap<Int, RfcommLayer>()

    /** ACL handle -> active inbound or outbound RFCOMM stream. */
    private val activeStreams = ConcurrentHashMap<Int, UsbBtRfcommStream>()

    /** Handles of inbound ACL connections created by the peer. */
    private val inboundHandles = ConcurrentHashMap.newKeySet<Int>()

    private data class PendingAcl(
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var handle: Int = -1,
        @Volatile var status: Int = -1,
    )

    private val pumpRunning = AtomicBoolean(false)
    private var eventPumpThread: Thread? = null
    private var aclPumpThread: Thread? = null

    init {
        ensureUsbReceivers()
    }

    /** USB permission / detach receivers; registered on first enable() attempt. */
    private var usbReceivers: Closeable? = null

    /** Set when enable() is waiting for the user to grant USB permission. */
    private val awaitingPermission = AtomicBoolean(false)
    @Volatile private var lastPermissionRequestMillis = 0L

    /** BD_ADDR (uppercase) -> ACL handle for established connections. */
    private val aclHandleByAddr = ConcurrentHashMap<String, Int>()

    /** ACL handle -> active user count (RFCOMM streams sharing one ACL). */
    private val aclRefCount = ConcurrentHashMap<Int, AtomicInteger>()

    /** BD_ADDRs in most-recently-connected order (for getConnectedDevice). */
    private val connectedOrder = java.util.concurrent.ConcurrentLinkedDeque<String>()

    /** ACL handle -> whether link encryption has been verified on this ACL. */
    private val encryptedHandles = ConcurrentHashMap<Int, Boolean>()

    /** ACL handle -> latch awaiting EVT_AUTHENTICATION_COMPLETE. */
    private val authLatches = ConcurrentHashMap<Int, CountDownLatch>()

    /** ACL handle -> latch awaiting EVT_ENCRYPTION_CHANGE. */
    private val encryptLatches = ConcurrentHashMap<Int, CountDownLatch>()
    private val authenticationLocks = ConcurrentHashMap<Int, Any>()
    private val authenticationStatus = ConcurrentHashMap<Int, Int>()

    init {
        runCatching {
            ensureUsbReceivers()
        }
    }

    // ------------------------------------------------------------------
    // CarPlayBluetooth
    // ------------------------------------------------------------------

    override fun isSupported(): Boolean {
        val mgr = usbManager ?: return false
        return runCatching { detector.findDongles(mgr).isNotEmpty() }.getOrDefault(false)
    }

    override fun isEnabled(): Boolean = hci != null && pumpRunning.get()

    override fun getAdapterName(): String = "UsbBt(dongle${dongleLabel?.let { ":$it" } ?: ""})"

    /**
     * Bring up the dongle: permission -> open -> initialize -> pairing agent.
     * Returns true when the HCI layer is ready.
     *
     * If USB permission is not yet granted, requests it and returns false; the
     * permission broadcast receiver (registered here) automatically retries
     * enable() on a background thread once the user grants access.
     */
    override fun enable(): Boolean = synchronized(shutdownLock) { synchronized(lock) {
        if (isEnabled()) return true
        ensureUsbReceivers()
        val mgr = usbManager ?: return false
        val candidates = runCatching { detector.findDongles(mgr) }.getOrElse { emptyList() }
        if (candidates.isEmpty()) {
            Log.w(TAG, "enable: no dongle found")
            return false
        }

        // Try candidate dongles that already have permission first.
        val (granted, ungranted) = candidates.partition { mgr.hasPermission(it.device) }

        for (dongle in granted) {
            val opened = UsbBtHci.open(mgr, dongle.device)
            if (opened == null) {
                Log.w(TAG, "enable: UsbBtHci.open failed for ${dongle.vendorLabel}")
                continue
            }
            val firmwareLoader = UsbBtFirmwareLoader(opened, appContext.assets)
            when (val init = opened.initialize(firmwareLoader = firmwareLoader)) {
                is UsbBtHci.HciResult.Failed -> {
                    Log.w(TAG, "enable: HCI init failed for ${dongle.vendorLabel}: ${init.reason}")
                    runCatching { opened.close() }
                    continue
                }
                is UsbBtHci.HciResult.Ok -> {
                    activeDevice = dongle.device
                    localBdAddr = init.value.bdAddr
                    dongleLabel = dongle.vendorLabel
                    initSteps = init.value.steps
                    hci = opened
                    pairingAgent = UsbBtPairingAgent(opened, linkKeys, pairingUiCallback, appContext).also { it.start() }
                    startPump(opened)
                    awaitingPermission.set(false)
                    val advName = init.value.advertisingName.ifEmpty { "DiPlay-${localBdAddr?.takeLast(5)?.replace(":", "") ?: "BT"}" }
                    val enableMsg = "USB Bluetooth enabled: bdAddr=$localBdAddr vendor=${dongle.vendorLabel} advertising=$advName"
                    UsbBtLog.log(appContext, TAG, enableMsg)
                    // Init steps only reach logcat; keep the failed ones in the diagnostic report.
                    init.value.steps.filter { "FAIL" in it }.forEach {
                        UsbBtLog.warn(appContext, TAG, "HCI init step $it")
                    }
                    return true
                }
            }
        }

        // If none of the permitted devices could be initialized, request permission for the best ungranted candidate.
        if (ungranted.isNotEmpty()) {
            val target = ungranted.first()
            val now = System.currentTimeMillis()
            if (awaitingPermission.compareAndSet(false, true) || (now - lastPermissionRequestMillis > 10_000L)) {
                lastPermissionRequestMillis = now
                awaitingPermission.set(true)
                val reqMsg = "enable: requesting USB permission for candidate: ${target.vendorLabel}"
                UsbBtLog.log(appContext, TAG, reqMsg)
                detector.ensurePermission(mgr, target)
            }
            return false
        }

        UsbBtLog.warn(appContext, TAG, "enable: all candidate dongles failed to open/initialize")
        return false
    } }

    override fun disable() {
        if (!isEnabled() && synchronized(lock) { hci == null && activeDevice == null } && !pumpRunning.get()) {
            return
        }
        shutdown(unregisterReceivers = false)
    }

    /** HCI init step log, exposed for diagnostics. */
    @Volatile private var initSteps: List<String> = emptyList()

    /** Optional UI hook for Numeric Comparison pairing (item 2). Null = Just Works. */
    @Volatile var pairingUiCallback: UsbBtPairingAgent.PairingUiCallback? = null
        set(value) {
            field = value
            pairingAgent?.uiCallback = value
        }

    /**
     * Register USB permission + device-detach receivers once. The permission
     * receiver retries enable() in the background after the user grants access;
     * the detach receiver shuts the stack down when the dongle is unplugged.
     */
    private fun ensureUsbReceivers() {
        if (usbReceivers != null) return
        val permissionAction = UsbBtDongleDetector.permissionAction(appContext.packageName)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    permissionAction -> {
                        val device = if (realSdkInt() >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                        }
                        val granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED,
                            false,
                        )
                        Log.i(TAG, "USB permission result: granted=$granted device=$device")
                        if (granted && device != null &&
                            detector.identify(device) != null
                        ) {
                            // Retry on a background thread; enable() blocks.
                            Thread({
                                runCatching { enable() }
                                    .onFailure { Log.w(TAG, "permission retry enable failed", it) }
                            }, "UsbBtPermissionRetry").apply {
                                isDaemon = true
                                start()
                            }
                        } else {
                            awaitingPermission.set(false)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = if (realSdkInt() >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                        }
                        if (device != null && detector.identify(device) != null) {
                            Log.i(TAG, "dongle attached (device=${device.deviceName}); bringing up USB Bluetooth")
                            Thread({
                                runCatching { enable() }
                                    .onFailure { Log.w(TAG, "attach enable failed", it) }
                            }, "UsbBtAttachEnable").apply {
                                isDaemon = true
                                start()
                            }
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = if (realSdkInt() >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                        }
                        if (device != null) {
                            val currentActive = synchronized(lock) { activeDevice }
                            val isTargetDongle = if (currentActive != null) {
                                (device.vendorId == currentActive.vendorId && device.productId == currentActive.productId) &&
                                    (device.deviceId == currentActive.deviceId || device.deviceName == currentActive.deviceName)
                            } else {
                                detector.identify(device) != null
                            }
                            if (isTargetDongle) {
                                Log.i(TAG, "dongle detached (device=${device.deviceName}); shutting down USB Bluetooth")
                                shutdown(unregisterReceivers = false)
                            } else {
                                Log.i(TAG, "non-active USB peripheral detached (device=${device.deviceName}); ignoring")
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val registered = if (realSdkInt() >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        usbReceivers = Closeable {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
        // Keep a reference so lint does not flag the unused value; the Closeable
        // is released in shutdown().
        if (registered == null) Log.w(TAG, "receiver registration returned null")
    }

    override fun localAddress(context: Context): String? = synchronized(lock) { localBdAddr }

    override fun getPairedDevices(): List<CarPlayBluetoothDevice> {
        // Paired = we hold a link key. Names are unknown without inquiry;
        // the address is the stable identifier.
        return linkKeys.peers().map { addr ->
            CarPlayBluetoothDevice(name = "iPhone ($addr)", address = addr,
                isConnected = aclHandleByAddr.containsKey(addr.uppercase()))
        }
    }

    override fun getConnectedDevice(): CarPlayBluetoothDevice? {
        val addr = connectedOrder.peekLast() ?: return null
        return CarPlayBluetoothDevice(name = "iPhone ($addr)", address = addr, isConnected = true)
    }

    /**
     * The cached iAP2 stream for [handle] when it is still usable, or null. A stream
     * closed by a previous session's teardown stays in the cache while the inbound ACL
     * keeps a reference alive, and handing the corpse out again makes every retry fail
     * instantly with zero bytes transferred (issue #274) — evict it, together with the
     * RFCOMM layer its close() already shut down, and let bring-up build a fresh DLC.
     */
    private fun liveCachedStream(handle: Int, deviceAddress: String): UsbBtRfcommStream? {
        val existing = activeStreams[handle] ?: return null
        if (!existing.isClosed) return existing
        if (activeStreams.remove(handle, existing)) {
            rfcommLayers.remove(handle)
            UsbBtLog.log(appContext, TAG, "openRfcommStream: evicted closed cached stream for $deviceAddress; opening a fresh DLC")
        }
        return null
    }

    /**
     * Open an RFCOMM stream to [deviceAddress] for the iAP2 service ([uuid]
     * is accepted for interface compatibility; the channel comes from SDP).
     *
     * ACL connections are shared per peer: opening a second stream to an
     * already-connected BD_ADDR reuses the ACL (refcounted).
     */
    override fun openRfcommStream(deviceAddress: String, uuid: String): BlockingDuplexByteStream {
        val h = synchronized(lock) { hci } ?: throw IOException("USB Bluetooth not enabled")
        if (!isEnabled()) throw IOException("USB Bluetooth not enabled")

        // 1) ACL connection (reused when already up for this peer).
        val handle = createAclConnection(h, deviceAddress)
        val freshAcl = aclRefCount[handle]?.get() == 1
        val isInbound = inboundHandles.contains(handle)
        try {
            // 2) L2CAP layer on this handle + ACL receive pump hookup.
            // One L2CAP instance per ACL handle, shared by all streams on it.
            val l2cap = getOrCreateL2cap(handle, h)

            // 2.5) Authenticate and enable encryption on ACL link if needed.
            // iOS requires link encryption (SSP Mode 4) for wireless iAP2 RFCOMM access.
            authenticateAndEncrypt(h, handle)

            // Check if peer already opened an inbound iAP2 stream on this ACL handle
            liveCachedStream(handle, deviceAddress)?.let { existing ->
                UsbBtLog.log(appContext, TAG, "openRfcommStream: reusing existing inbound stream for $deviceAddress")
                activeStreams.remove(handle, existing)
                return existing
            }

            if (isInbound) {
                // If peer just connected inbound, give iOS a brief window (up to 2.5s) to open its inbound iAP2 DLC
                val waitDeadline = System.currentTimeMillis() + 2500L
                while (System.currentTimeMillis() < waitDeadline && isEnabled()) {
                    liveCachedStream(handle, deviceAddress)?.let { existing ->
                        UsbBtLog.log(appContext, TAG, "openRfcommStream: peer opened inbound iAP2 stream for $deviceAddress; reusing")
                        activeStreams.remove(handle, existing)
                        return existing
                    }
                    try { Thread.sleep(100L) } catch (_: InterruptedException) { break }
                }
            }

            // 3) SDP: find the iAP2 RFCOMM channel.
            // Logs from the field (2026-10-20) repeatedly show "SDP query timeout" and
            // "L2CAP connect rejected: result=0x3" on iPhone-initiated reconnects.
            // iOS often rejects the SDP L2CAP channel while the iAP2 service is still
            // reachable on channel 1 (its historic default). Fall back to channel 1 on
            // any SDP failure rather than aborting the whole RFCOMM bring-up.
            val targetUuid = runCatching { UUID.fromString(uuid) }.getOrNull()
            val uuidBytes = if (targetUuid != null) uuidBigEndian(targetUuid) else HciProtocol.IAP2_UUID_BYTES
            val sdp = SdpClient(l2cap, handle, uuidBytes)
            val channel = try { when (val r = sdp.findIap2RfcommChannel()) {
                is SdpClient.SdpResult.Ok -> {
                    UsbBtLog.log(appContext, TAG, "SDP: iAP2 RFCOMM channel=${r.value}")
                    r.value
                }
                is SdpClient.SdpResult.Failed -> {
                    // Fall back to the well-known iAP2 channel instead of failing the whole
                    // RFCOMM attempt. iOS frequently rejects the SDP L2CAP channel
                    // (result=0x3, PSM=0x1) or lets it time out while iAP2 itself is
                    // still responsive on the default channel.
                    UsbBtLog.warn(appContext, TAG, "SDP failed (${r.reason}); falling back to channel $FALLBACK_IAP2_CHANNEL")
                    FALLBACK_IAP2_CHANNEL
                }
            } } finally { sdp.close() }

            // 4) RFCOMM DLC to the channel. Reuse existing RFCOMM layer if established inbound
            val rfcomm = rfcommLayers[handle] ?: run {
                val chanResult = l2cap.connect(HciProtocol.L2CAP_PSM_RFCOMM)
                val chan = when (chanResult) {
                    is L2capLayer.L2capResult.Ok -> chanResult.value
                    is L2capLayer.L2capResult.Failed -> {
                        rfcommLayers[handle]?.let { return@run it }
                        throw IOException("RFCOMM L2CAP connect failed: ${chanResult.reason}")
                    }
                }
                RfcommLayer(l2cap, chan).also { rfcommLayers[handle] = it }
            }

            val dlc = when (val r = rfcomm.openDlc(channel)) {
                is RfcommLayer.RfcommResult.Ok -> r.value
                is RfcommLayer.RfcommResult.Failed -> {
                    // Issue #165: a PN send failure leaves this RFCOMM layer
                    // wedged, and the layer is cached per ACL handle — every
                    // reconnect attempt then reuses it and fails the same way
                    // while the ACL itself is reused. Evict it so the next
                    // attempt builds a fresh DLC instead of replaying the
                    // same failure.
                    rfcommLayers.remove(handle)?.let { runCatching { it.close() } }
                    throw IOException("RFCOMM DLC open failed: ${r.reason}")
                }
            }

            // 5) Byte-stream facade.
            val stream = UsbBtRfcommStream(
                rfcomm = rfcomm,
                dlc = dlc,
                onClose = { teardown(handle) },
            )
            activeStreams[handle] = stream
            l2capRejectionCounts.remove(handle)
            UsbBtLog.log(appContext, TAG, "openRfcommStream: connected successfully to $deviceAddress (handle=0x${handle.toString(16)}, channel=$channel)")
            return stream
        } catch (t: Throwable) {
            UsbBtLog.warn(appContext, TAG, "openRfcommStream failed for $deviceAddress", t)
            when {
                // Issue #281: the ACL survived a link loss (e.g. Wi-Fi dropped while
                // Bluetooth stayed up, or our process died mid-session), so the phone
                // still holds the previous session's RFCOMM DLC and refuses every new
                // one (L2CAP result=0x4). Retrying forever never recovers; after a
                // couple of rejections, drop the ACL itself so the phone releases
                // the stale DLC and the next attempt starts from a clean link.
                !freshAcl && noteL2capRejection(handle, t) -> forceDropAcl(handle)
                // Only tear down the ACL when we created it fresh for this call AND it is not an inbound connection from peer.
                freshAcl && !isInbound -> teardown(handle)
                else -> releaseAclRef(handle)
            }
            throw t
        }
    }

    /** Counts L2CAP result=0x4 rejections for [handle]; true once the stale-ACL limit is hit. */
    private fun noteL2capRejection(handle: Int, error: Throwable): Boolean {
        val rejected = generateSequence(error) { it.cause }
            .any { it.message?.contains("L2CAP connect rejected: result=0x4") == true }
        if (!rejected) return false
        val count = l2capRejectionCounts.getOrPut(handle) { AtomicInteger(0) }.incrementAndGet()
        UsbBtLog.warn(
            appContext, TAG,
            "L2CAP result=0x4 rejection $count/$STALE_ACL_REJECTION_LIMIT for handle=0x${handle.toString(16)}",
        )
        return count >= STALE_ACL_REJECTION_LIMIT
    }

    /**
     * Issue #281: unconditionally drops an ACL and all its layers, including the HCI
     * disconnect [releaseAclRef] skips for inbound links — the phone must release its
     * side too, stale DLC included. Used only by the stale-ACL escalation above.
     */
    private fun forceDropAcl(handle: Int) {
        UsbBtLog.warn(
            appContext, TAG,
            "dropping stale ACL handle=0x${handle.toString(16)} after repeated L2CAP rejections; " +
                "the peer must release its leftover RFCOMM channel before reconnecting",
        )
        l2capRejectionCounts.remove(handle)
        aclRefCount.remove(handle)
        aclHandleByAddr.entries.find { it.value == handle }?.let { entry ->
            aclHandleByAddr.remove(entry.key)
            connectedOrder.remove(entry.key)
        }
        inboundHandles.remove(handle)
        encryptedHandles.remove(handle)
        rfcommLayers.remove(handle)?.let { runCatching { it.close() } }
        activeStreams.remove(handle)?.let { runCatching { it.close() } }
        l2capLayers.remove(handle)?.let { runCatching { it.close() } }
        val h = synchronized(lock) { hci } ?: return
        runCatching { h.sendCommand(OP_DISCONNECT, encodeDisconnect(handle)) }
    }

    // ------------------------------------------------------------------
    // ACL connection management (multi-peer, refcounted).
    // ------------------------------------------------------------------

    /**
     * Get or establish the ACL connection to [bdAddr]. Returns the handle and
     * bumps its refcount; callers must pair every success with [releaseAclRef]
     * (via [teardown]).
     */
    private fun createAclConnection(h: UsbBtHci, bdAddr: String): Int {
        val key = bdAddr.uppercase()
        aclHandleByAddr[key]?.let { existing ->
            aclRefCount.getOrPut(existing) { AtomicInteger(0) }.incrementAndGet()
            Log.i(TAG, "ACL reused: $bdAddr handle=0x${existing.toString(16)}")
            UsbBtLog.log(appContext, TAG, "ACL reused: $bdAddr handle=0x${existing.toString(16)}")
            return existing
        }
        val addrBytes = encodeBdAddr(bdAddr)
            ?: throw IOException("malformed BD_ADDR: $bdAddr")
        val pending = PendingAcl()
        pendingAcl[key] = pending
        try {
            when (val r = h.sendCommand(OP_CREATE_CONNECTION, encodeCreateConnection(addrBytes))) {
                is UsbBtHci.HciResult.Failed ->
                    throw IOException("Create_Connection failed: ${r.reason}")
                is UsbBtHci.HciResult.Ok -> { /* Command Status ok; completion follows */ }
            }
            if (!pending.latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IOException("ACL connection timeout to $bdAddr")
            }
            if (pending.status != 0) {
                throw IOException(
                    "ACL connection failed to $bdAddr status=0x${pending.status.toString(16)}",
                )
            }
            val handle = pending.handle
            aclHandleByAddr[key] = handle
            aclRefCount.getOrPut(handle) { AtomicInteger(0) }.incrementAndGet()
            connectedOrder.remove(key)
            connectedOrder.addLast(key)
            Log.i(TAG, "ACL up: $bdAddr handle=0x${handle.toString(16)}")
            UsbBtLog.log(appContext, TAG, "ACL created: $bdAddr handle=0x${handle.toString(16)}")
            return handle
        } finally {
            pendingAcl.remove(key)
        }
    }

    /** Get or create L2capLayer on [handle], registering the SDP server and inbound RFCOMM server. */
    private fun getOrCreateL2cap(handle: Int, h: UsbBtHci): L2capLayer {
        l2capLayers[handle]?.let { return it }
        return synchronized(l2capLayers) {
            l2capLayers[handle] ?: run {
                val l2cap = L2capLayer(aclHandle = handle, hci = h)
                val identity = UsbBtIdentity(
                    name = "DiPlay",
                )
                l2cap.registerSdpServer(SdpCodec.defaultRecords(identity))

                // Register inbound RFCOMM on PSM 3 so iPhone incoming connections succeed
                l2cap.registerService(HciProtocol.L2CAP_PSM_RFCOMM) { channel ->
                    val rfcomm = RfcommLayer(l2cap, channel, initiator = false)
                    rfcommLayers[handle] = rfcomm
                    rfcomm.onDlcOpened = { dlc ->
                        Log.i(TAG, "Inbound RFCOMM DLC opened: ch=${dlc.serverChannel} dlci=${dlc.dlci} on handle=0x${handle.toString(16)}")
                        if (dlc.serverChannel == UsbBtIdentity.IAP2_RFCOMM_CHANNEL) {
                            val stream = UsbBtRfcommStream(
                                rfcomm = rfcomm,
                                dlc = dlc,
                                onClose = { teardown(handle) },
                            )
                            activeStreams[handle] = stream
                        }
                    }
                    rfcomm.onDlcClosed = { channel ->
                        if (channel == UsbBtIdentity.IAP2_RFCOMM_CHANNEL) {
                            activeStreams.remove(handle)?.let { runCatching { it.close() } }
                        }
                    }
                    { payload -> rfcomm.onRfcommFrame(payload) }
                }

                l2capLayers[handle] = l2cap
                l2cap
            }
        }
    }

    /** Release one user of [handle]; disconnects HCI only when the last user goes. */
    private fun releaseAclRef(handle: Int) {
        val remaining = aclRefCount[handle]?.decrementAndGet() ?: 0
        if (remaining <= 0) {
            aclRefCount.remove(handle)
            // Find and drop the BD_ADDR mapping for this handle.
            aclHandleByAddr.entries.find { it.value == handle }?.let { entry ->
                aclHandleByAddr.remove(entry.key)
                connectedOrder.remove(entry.key)
            }
            rfcommLayers.remove(handle)?.let { runCatching { it.close() } }
            activeStreams.remove(handle)?.let { runCatching { it.close() } }
            l2capLayers.remove(handle)?.let { runCatching { it.close() } }
            val wasInbound = inboundHandles.remove(handle)
            val h = synchronized(lock) { hci } ?: return
            runCatching { h.sendCommand(OP_DISCONNECT, encodeDisconnect(handle)) }
            Log.i(TAG, "ACL down: handle=0x${handle.toString(16)} wasInbound=$wasInbound")
        }
    }

    private fun onPeerDisconnected(handle: Int) {
        inboundHandles.remove(handle)
        aclRefCount.remove(handle)
        aclHandleByAddr.entries.find { it.value == handle }?.let { entry ->
            aclHandleByAddr.remove(entry.key)
            connectedOrder.remove(entry.key)
        }
        rfcommLayers.remove(handle)?.let { runCatching { it.close() } }
        activeStreams.remove(handle)?.let { runCatching { it.close() } }
        l2capLayers.remove(handle)?.let { runCatching { it.close() } }
        encryptedHandles.remove(handle)
        authenticationStatus[handle] = -1
        authLatches.remove(handle)?.countDown()
        encryptLatches.remove(handle)?.countDown()
        Log.i(TAG, "ACL down (peer disconnected): handle=0x${handle.toString(16)}")
    }

    private fun teardown(handle: Int) = releaseAclRef(handle)

    private fun authenticateAndEncrypt(h: UsbBtHci, handle: Int, timeoutMs: Long = 35_000L): Unit =
        synchronized(authenticationLocks.getOrPut(handle) { Any() }) {
        if (encryptedHandles[handle] == true) return
        authenticationStatus.remove(handle)
        val authLatch = CountDownLatch(1)
        authLatches[handle] = authLatch
        try {
            val authRes = h.sendCommand(OP_AUTHENTICATION_REQUESTED, encodeAuthenticationRequested(handle))
            UsbBtLog.log(appContext, TAG, "Authentication_Requested for handle=0x${handle.toString(16)}: $authRes")
            if (authRes is UsbBtHci.HciResult.Ok) {
                if (!authLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IOException("Bluetooth pairing confirmation timed out")
                if (authenticationStatus[handle] != 0) throw IOException("Bluetooth authentication failed: ${authenticationStatus[handle]}")
            } else {
                throw IOException("Bluetooth authentication request failed: $authRes")
            }
        } finally {
            authLatches.remove(handle)
        }

        if (encryptedHandles[handle] == true) return

        val encryptLatch = CountDownLatch(1)
        encryptLatches[handle] = encryptLatch
        try {
            val encRes = h.sendCommand(OP_SET_CONNECTION_ENCRYPTION, encodeSetConnectionEncryption(handle, true))
            UsbBtLog.log(appContext, TAG, "Set_Connection_Encryption for handle=0x${handle.toString(16)}: $encRes")
            if (encRes is UsbBtHci.HciResult.Ok) {
                encryptLatch.await(5_000L, TimeUnit.MILLISECONDS)
            }
            if (encryptedHandles[handle] != true) throw IOException("Bluetooth link encryption was not established")
        } finally {
            encryptLatches.remove(handle)
        }
    }

    // ------------------------------------------------------------------
    // Event / ACL pump.
    // ------------------------------------------------------------------

    private fun startPump(h: UsbBtHci) {
        if (!pumpRunning.compareAndSet(false, true)) return
        eventPumpThread = Thread({
            while (pumpRunning.get()) {
                // HCI events (connection completions, inbound connection requests, pairing, etc.).
                val event = runCatching { h.pollEvent(100) }.getOrNull() ?: continue
                pairingAgent?.onEvent(event)
                when (event.code) {
                    EVT_CONNECTION_REQUEST -> {
                        val req = parseConnectionRequest(event)
                        if (req != null) {
                            val msg = "incoming connection request from ${req.bdAddr} linkType=${req.linkType} CoD=0x${req.classOfDevice.toString(16)}"
                            Log.i(TAG, msg)
                            UsbBtLog.log(appContext, TAG, msg)
                            if (req.linkType == 1) { // ACL
                                val acceptCmd = encodeAcceptConnection(req.rawAddr)
                                val res = h.sendCommand(OP_ACCEPT_CONNECTION_REQUEST, acceptCmd)
                                val resMsg = "accept connection result for ${req.bdAddr}: $res"
                                Log.i(TAG, resMsg)
                                UsbBtLog.log(appContext, TAG, resMsg)
                            }
                        }
                    }
                    EVT_CONNECTION_COMPLETE -> {
                        val cc = parseConnectionComplete(event)
                        if (cc != null && cc.bdAddr != null) {
                            val key = cc.bdAddr.uppercase()
                            if (cc.status == 0) {
                                aclHandleByAddr[key] = cc.handle
                                connectedOrder.remove(key)
                                connectedOrder.addLast(key)
                                if (pendingAcl[key] == null) {
                                    inboundHandles.add(cc.handle)
                                    aclRefCount.getOrPut(cc.handle) { AtomicInteger(0) }.incrementAndGet()
                                }
                                if (cc.encryptionEnabled) {
                                    encryptedHandles[cc.handle] = true
                                }
                                getOrCreateL2cap(cc.handle, h)
                                val msg = "ACL up: $key handle=0x${cc.handle.toString(16)} encrypted=${cc.encryptionEnabled}"
                                Log.i(TAG, msg)
                                UsbBtLog.log(appContext, TAG, msg)

                                // Proactively authenticate and encrypt inbound connection if not already encrypted.
                                // Otherwise iOS expects security establishment and disconnects with reason 0x13 after 17s.
                                if (!cc.encryptionEnabled && pendingAcl[key] == null) {
                                    Thread({
                                        runCatching {
                                            authenticateAndEncrypt(h, cc.handle)
                                        }.onFailure {
                                            UsbBtLog.warn(appContext, TAG, "inbound authenticateAndEncrypt failed for $key", it)
                                        }
                                    }, "UsbBtInboundAuth").apply {
                                        isDaemon = true
                                        start()
                                    }
                                }
                            } else {
                                val msg = "ACL connection failed: $key status=0x${cc.status.toString(16)}"
                                Log.w(TAG, msg)
                                UsbBtLog.warn(appContext, TAG, msg)
                            }
                            pendingAcl[key]?.let { pending ->
                                pending.handle = cc.handle
                                pending.status = cc.status
                                pending.latch.countDown()
                            }
                        }
                    }
                    EVT_AUTHENTICATION_COMPLETE -> {
                        if (event.params.size >= 3) {
                            val status = event.params[0].toInt() and 0xFF
                            val handle = (event.params[1].toInt() and 0xFF) or (((event.params[2].toInt() and 0xFF) and 0x0F) shl 8)
                            val msg = "HCI authentication complete for handle=0x${handle.toString(16)} status=0x${status.toString(16)}"
                            Log.i(TAG, msg)
                            UsbBtLog.log(appContext, TAG, msg)
                            // A peer-initiated SSP exchange can already be in progress.
                            // Command Disallowed is not its final authentication result.
                            if (status != 0x0c) {
                                authenticationStatus[handle] = status
                                authLatches[handle]?.countDown()
                            }
                        }
                    }
                    EVT_ENCRYPTION_CHANGE -> {
                        if (event.params.size >= 4) {
                            val status = event.params[0].toInt() and 0xFF
                            val handle = (event.params[1].toInt() and 0xFF) or (((event.params[2].toInt() and 0xFF) and 0x0F) shl 8)
                            val enabled = event.params[3].toInt() and 0xFF
                            val msg = "HCI encryption change for handle=0x${handle.toString(16)} status=0x${status.toString(16)} enabled=$enabled"
                            Log.i(TAG, msg)
                            UsbBtLog.log(appContext, TAG, msg)
                            if (status == 0 && enabled != 0) {
                                encryptedHandles[handle] = true
                            }
                            encryptLatches[handle]?.countDown()
                        }
                    }
                    EVT_DISCONNECTION_COMPLETE -> {
                        val dc = parseDisconnectionComplete(event)
                        if (dc != null && dc.status == 0) {
                            val msg = "ACL disconnected: handle=0x${dc.handle.toString(16)} reason=0x${dc.reason.toString(16)}"
                            Log.i(TAG, msg)
                            UsbBtLog.log(appContext, TAG, msg)
                            onPeerDisconnected(dc.handle)
                        }
                    }
                }
            }
        }, "UsbBtEventPump").apply {
            isDaemon = true
            start()
        }

        aclPumpThread = Thread({
            while (pumpRunning.get()) {
                if (aclHandleByAddr.isEmpty()) {
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                val packets = runCatching { h.pollAclPackets(100) }.getOrNull()
                if (!packets.isNullOrEmpty()) {
                    for (acl in packets) {
                        l2capLayers[acl.handle]?.onAclData(acl.data)
                    }
                    // Drain immediately available ACL packets to avoid latency build-up
                    while (pumpRunning.get()) {
                        val drain = runCatching { h.pollAclPackets(0) }.getOrNull() ?: break
                        if (drain.isEmpty()) break
                        for (acl in drain) {
                            l2capLayers[acl.handle]?.onAclData(acl.data)
                        }
                    }
                }
            }
        }, "UsbBtAclPump").apply {
            isDaemon = true
            start()
        }
    }

    /** Shut the adapter down; idempotent. */
    fun shutdown(unregisterReceivers: Boolean = true) {
        shutdownInternal(unregisterReceivers, gracefulRadioOff = false)
    }

    /** Shut down the USB radio and return command results for wireless-handoff diagnostics. */
    fun shutdownForWirelessHandoff(): String =
        shutdownInternal(unregisterReceivers = false, gracefulRadioOff = true)

    private fun UsbBtHci.HciResult<*>?.describeShutdownResult(): String = when (this) {
        null -> "noReply"
        is UsbBtHci.HciResult.Ok<*> -> "ok"
        is UsbBtHci.HciResult.Failed -> "failed:${reason}"
    }

    private fun shutdownInternal(unregisterReceivers: Boolean, gracefulRadioOff: Boolean): String = synchronized(shutdownLock) {
        val wasActive = isEnabled() || synchronized(lock) { hci != null || activeDevice != null } || pumpRunning.get()
        val closingHci = synchronized(lock) { hci }
        val shutdownResults = mutableListOf<String>()

        // Stop the pairing worker before touching the controller. It can otherwise
        // race the shutdown sequence by answering a queued SSP event after reset.
        val closingPairingAgent = pairingAgent
        pairingAgent = null
        closingPairingAgent?.stop()

        // Keep the HCI dispatcher and ACL path alive while RFCOMM sends DISC and
        // outstanding links are disconnected. Closing HCI first silently drops
        // these frames and can leave the phone parked in Bluetooth bootstrap.
        if (closingHci != null && gracefulRadioOff) {
            UsbBtLog.log(appContext, TAG, "handoff shutdown stage=close RFCOMM streams")
            activeStreams.values.toList().forEach { stream ->
                val result = runCatching { stream.close() }
                shutdownResults += "rfcommClose=${if (result.isSuccess) "ok" else result.exceptionOrNull()?.javaClass?.simpleName}"
            }
            rfcommLayers.values.toList().forEach { layer -> runCatching { layer.close() } }

            val handles = aclHandleByAddr.values.toSet()
            UsbBtLog.log(appContext, TAG, "handoff shutdown stage=disconnect ACL count=${handles.size}")
            handles.forEach { handle ->
                val result = runCatching {
                    closingHci.sendCommand(OP_DISCONNECT, encodeDisconnect(handle), timeoutMs = 1_000)
                }.getOrNull()
                shutdownResults += "disconnect=0x${handle.toString(16)}:${result.describeShutdownResult()}"
            }
            UsbBtLog.log(appContext, TAG, "handoff shutdown stage=disable inquiry/page scan")
            val scanResult = runCatching {
                closingHci.sendCommand(OP_WRITE_SCAN_ENABLE, byteArrayOf(0), timeoutMs = 1_000)
            }.getOrNull()
            shutdownResults += "scanDisable=${scanResult.describeShutdownResult()}"
            UsbBtLog.log(appContext, TAG, "handoff shutdown stage=HCI reset")
            val resetResult = runCatching {
                closingHci.sendCommand(OP_RESET, byteArrayOf(), timeoutMs = 1_000)
            }.getOrNull()
            shutdownResults += "hciReset=${resetResult.describeShutdownResult()}"
        } else if (closingHci == null) {
            shutdownResults += "hci=alreadyClosed"
        } else {
            shutdownResults += "radioShutdown=skipped"
        }

        pumpRunning.set(false)
        eventPumpThread?.interrupt()
        runCatching { eventPumpThread?.join(300) }
        eventPumpThread = null
        aclPumpThread?.interrupt()
        runCatching { aclPumpThread?.join(300) }
        aclPumpThread = null
        if (unregisterReceivers) {
            runCatching { usbReceivers?.close() }
            usbReceivers = null
        }
        awaitingPermission.set(false)
        val detachedHci = synchronized(lock) {
            val toClose = hci
            hci = null
            activeDevice = null
            localBdAddr = null
            initSteps = emptyList()
            toClose
        }
        if (detachedHci != null) {
            runCatching { detachedHci.close() }
        }
        rfcommLayers.values.forEach { runCatching { it.close() } }
        rfcommLayers.clear()
        activeStreams.values.forEach { runCatching { it.close() } }
        activeStreams.clear()
        aclHandleByAddr.keys.toList().forEach { addr ->
            aclHandleByAddr.remove(addr)?.let { handle ->
                aclRefCount.remove(handle)
                l2capLayers.remove(handle)?.let { runCatching { it.close() } }
            }
        }
        aclRefCount.clear()
        connectedOrder.clear()
        pendingAcl.clear()
        encryptedHandles.clear()
        authLatches.values.forEach { it.countDown() }
        authLatches.clear()
        encryptLatches.values.forEach { it.countDown() }
        encryptLatches.clear()
        if (wasActive) {
            UsbBtLog.log(appContext, TAG, "USB Bluetooth shut down ${shutdownResults.joinToString(" ")}")
        }
        shutdownResults.joinToString(" ")
    }

    /** Snapshot for diagnostics (LegacyConnectionProbe). */
    fun diagnosticState(): String {
        val dongle = synchronized(lock) {
            activeDevice?.let { dev -> detector.identify(dev) }
        } ?: runCatching {
            usbManager?.let { detector.findDongles(it).firstOrNull() }
        }.getOrNull()
        return buildString {
            append("dongle=")
            append(dongle?.let { "vid=0x${it.device.vendorId.toString(16)} pid=0x${it.device.productId.toString(16)} ${it.vendorLabel}" } ?: "none")
            append(" hciUp=${hci != null}")
            append(" initSteps=${initSteps.size}")
            append(" aclCount=${aclHandleByAddr.size}")
            append(" peers=[${aclHandleByAddr.keys.joinToString(",")}]")
            append(" linkKeys=${linkKeys.peers().size}")
            append(" awaitingPermission=${awaitingPermission.get()}")
        }
    }

    data class StatusInfo(
        val isSupported: Boolean,
        val isEnabled: Boolean,
        val device: UsbDevice?,
        val vendorLabel: String?,
        val hasPermission: Boolean,
        val localBdAddr: String?,
        val aclCount: Int,
        val peers: List<String>,
        val awaitingPermission: Boolean,
    )

    fun getStatus(): StatusInfo {
        val mgr = usbManager
        // UI reads must never wait for enable()'s USB initialization/retry lock.
        val dongle = activeDevice?.let { detector.identify(it) } ?: runCatching {
            mgr?.let { detector.findDongles(it).firstOrNull() }
        }.getOrNull()
        val dev = dongle?.device
        val hasPerm = if (mgr != null && dev != null) mgr.hasPermission(dev) else false
        return StatusInfo(
            isSupported = isSupported(),
            isEnabled = isEnabled(),
            device = dev,
            vendorLabel = dongle?.vendorLabel,
            hasPermission = hasPerm,
            localBdAddr = localBdAddr,
            aclCount = aclHandleByAddr.size,
            peers = aclHandleByAddr.keys.toList(),
            awaitingPermission = awaitingPermission.get(),
        )
    }
}

/**
 * [BlockingDuplexByteStream] over an RFCOMM DLC.
 *
 * Inbound UIH frames are reassembled: [recv] returns up to [maxBytes] from an
 * internal buffer and keeps the remainder for the next call, so callers never
 * lose the tail of a frame larger than their read size.
 */
private class UsbBtRfcommStream(
    private val rfcomm: RfcommLayer,
    private val dlc: RfcommLayer.Dlc,
    private val onClose: () -> Unit,
) : BlockingDuplexByteStream {

    private val inbound = LinkedBlockingQueue<ByteArray>(64)
    private val closed = AtomicBoolean(false)

    /** True once [close] ran; a closed stream must never be handed to a new session. */
    val isClosed: Boolean get() = closed.get()

    /** Reassembly buffer for frames larger than one recv() call. Guarded by itself. */
    private val reassembly = java.io.ByteArrayOutputStream()

    init {
        rfcomm.onData(dlc) { data ->
            // Drop when the app is not draining; never block the BT thread.
            inbound.offer(data)
        }
    }

    override fun send(data: ByteArray) {
        if (closed.get()) throw IOException("stream closed")
        if (!rfcomm.send(dlc, data)) throw IOException("RFCOMM send failed")
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        if (closed.get()) return ByteArray(0)
        synchronized(reassembly) {
            if (reassembly.size() > 0) return takeFromReassembly(maxBytes)
        }
        val chunk = inbound.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return null
        synchronized(reassembly) {
            reassembly.write(chunk, 0, chunk.size)
            return takeFromReassembly(maxBytes)
        }
    }

    /** Take up to [maxBytes] from the reassembly buffer, keeping the rest. */
    private fun takeFromReassembly(maxBytes: Int): ByteArray {
        val buf = reassembly.toByteArray()
        return if (buf.size <= maxBytes) {
            reassembly.reset()
            buf
        } else {
            val out = buf.copyOf(maxBytes)
            reassembly.reset()
            reassembly.write(buf, maxBytes, buf.size - maxBytes)
            out
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { rfcomm.close() }
        runCatching { onClose() }
    }
}
