package com.shilapi.xcertplay.usbbt

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.shilapi.xcertplay.usbbt.HciProtocol.MAX_EVENT_PACKET_SIZE
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_READ_BD_ADDR
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_READ_BUFFER_SIZE
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_READ_INQUIRY_RESPONSE_TX_POWER
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_READ_LOCAL_FEATURES
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_READ_LOCAL_VERSION
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_RESET
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_SET_EVENT_MASK
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_WRITE_CLASS_OF_DEVICE
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_WRITE_DEFAULT_LINK_POLICY
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_WRITE_EXTENDED_INQUIRY_RESPONSE
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_WRITE_INQUIRY_MODE
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_WRITE_INQUIRY_SCAN_TYPE
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_WRITE_LOCAL_NAME
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_WRITE_AUTHENTICATION_ENABLE
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_WRITE_PAGE_SCAN_TYPE
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_WRITE_INQUIRY_SCAN_ACTIVITY
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_WRITE_PAGE_SCAN_ACTIVITY
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_WRITE_SCAN_ENABLE
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_WRITE_SIMPLE_PAIRING_MODE
import com.shilapi.xcertplay.usbbt.HciProtocol.SCAN_INQUIRY_AND_PAGE
import com.shilapi.xcertplay.usbbt.HciProtocol.USB_BT_CONTROL_REQUEST
import com.shilapi.xcertplay.usbbt.HciProtocol.USB_BT_REQ_TYPE_CLASS_INTERFACE_OUT
import com.shilapi.xcertplay.usbbt.HciProtocol.USB_CLASS_WIRELESS_CONTROLLER
import com.shilapi.xcertplay.usbbt.HciProtocol.USB_PROTOCOL_BLUETOOTH
import com.shilapi.xcertplay.usbbt.HciProtocol.USB_SUBCLASS_RF
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeLocalName
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeSetEventMask
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeWriteClassOfDevice
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeWriteDefaultLinkPolicy
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeWriteExtendedInquiryResponse
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeWriteInquiryMode
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeWriteScanActivity
import com.shilapi.xcertplay.usbbt.HciProtocol.encodeWriteScanType
import com.shilapi.xcertplay.usbbt.HciProtocol.parseBdAddr
import com.shilapi.xcertplay.usbbt.HciProtocol.parseBufferSize
import com.shilapi.xcertplay.usbbt.HciProtocol.parseEvent

/**
 * HCI transport for a USB Bluetooth dongle (Phase 2).
 *
 * Mapping (Bluetooth Core Spec Vol 4, Part B):
 * - HCI commands  -> USB control transfer on endpoint 0
 *                     (bmRequestType=0x20, bRequest=0, wValue=0, wIndex=interface)
 * - HCI events    -> interrupt IN endpoint
 * - ACL data      -> bulk IN / bulk OUT endpoints
 *
 * Every public method blocks the calling thread; call from a background thread.
 * Create via [open]; always [close] when done.
 *
 * Clean-room: transport mapping and init sequence derived from the public
 * Bluetooth specification only.
 */
class UsbBtHci private constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val btInterface: UsbInterface,
    private val eventEndpoint: UsbEndpoint,
    private val aclInEndpoint: UsbEndpoint,
    private val aclOutEndpoint: UsbEndpoint,
) : AutoCloseable {

    /** Outcome of an HCI operation. */
    sealed class HciResult<out T> {
        data class Ok<T>(val value: T) : HciResult<T>()
        data class Failed(val reason: String) : HciResult<Nothing>()
    }

    /** Result of [initialize]. */
    data class InitResult(
        val bdAddr: String?,
        val bufferSize: HciProtocol.BufferSize?,
        /** Human-readable step log for diagnostics. */
        val steps: List<String>,
        val advertisingName: String = "",
    )

    companion object {
        private const val TAG = "UsbBtHci"
        private const val DEFAULT_COMMAND_TIMEOUT_MS = 2_000

        /**
         * Open [device] for HCI: finds the Bluetooth interface, claims it and
         * locates the interrupt/bulk endpoints. Returns null with a log when
         * the device has no usable Bluetooth interface or endpoints.
         *
         * The caller must hold USB permission for [device].
         */
        fun open(usbManager: UsbManager, device: UsbDevice): UsbBtHci? {
            val btInterface = findBluetoothInterface(device)
                ?: run {
                    Log.w(TAG, "no Bluetooth interface on vid=0x${device.vendorId.toString(16)}")
                    return null
                }
            val connection = usbManager.openDevice(device)
                ?: run {
                    Log.w(TAG, "openDevice failed")
                    return null
                }
            var opened: UsbBtHci? = null
            try {
                if (!connection.claimInterface(btInterface, true)) {
                    Log.w(TAG, "claimInterface failed for interface ${btInterface.id}")
                    return null
                }
                val endpoints = findEndpoints(btInterface)
                if (endpoints == null) {
                    Log.w(TAG, "missing interrupt/bulk endpoints on interface ${btInterface.id}")
                    return null
                }
                Log.i(
                    TAG,
                    "HCI opened: interface=${btInterface.id} " +
                        "evtEp=${endpoints.event.address} aclIn=${endpoints.aclIn.address} " +
                        "aclOut=${endpoints.aclOut.address}",
                )
                opened = UsbBtHci(
                    device = device,
                    connection = connection,
                    btInterface = btInterface,
                    eventEndpoint = endpoints.event,
                    aclInEndpoint = endpoints.aclIn,
                    aclOutEndpoint = endpoints.aclOut,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "open failed", t)
            } finally {
                if (opened == null) {
                    runCatching { connection.releaseInterface(btInterface) }
                    runCatching { connection.close() }
                }
            }
            return opened
        }

        private data class Endpoints(
            val event: UsbEndpoint,
            val aclIn: UsbEndpoint,
            val aclOut: UsbEndpoint,
        )

        private fun findBluetoothInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == USB_CLASS_WIRELESS_CONTROLLER &&
                    intf.interfaceSubclass == USB_SUBCLASS_RF &&
                    intf.interfaceProtocol == USB_PROTOCOL_BLUETOOTH
                ) {
                    return intf
                }
            }
            // Fallback: some dongles only tag the device descriptor; interface 0
            // is the HCI control interface on virtually all of them.
            return if (device.interfaceCount > 0) device.getInterface(0) else null
        }

        private fun findEndpoints(intf: UsbInterface): Endpoints? {
            var event: UsbEndpoint? = null
            var aclIn: UsbEndpoint? = null
            var aclOut: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                val isIn = ep.direction == UsbConstants.USB_DIR_IN
                when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_INT -> if (isIn && event == null) event = ep
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> {
                        if (isIn && aclIn == null) aclIn = ep
                        if (!isIn && aclOut == null) aclOut = ep
                    }
                    else -> { /* isochronous (SCO audio) not needed for CarPlay data path */ }
                }
            }
            val e = event ?: return null
            val inEp = aclIn ?: return null
            val outEp = aclOut ?: return null
            return Endpoints(e, inEp, outEp)
        }
    }

    private val dispatcher = HciEventDispatcher(
        readEvent = { timeoutMs ->
            val bytes = ByteArray(MAX_EVENT_PACKET_SIZE)
            val read = connection.bulkTransfer(eventEndpoint, bytes, bytes.size, timeoutMs)
            if (read > 0) parseEvent(bytes, read) else null
        },
        writeCommand = { payload, timeoutMs ->
            connection.controlTransfer(
                USB_BT_REQ_TYPE_CLASS_INTERFACE_OUT, USB_BT_CONTROL_REQUEST,
                0, btInterface.id, payload, payload.size, timeoutMs,
            )
        },
    )

    /** Wait for Command Complete, or Command Status for asynchronous link commands. */
    fun sendCommand(
        opcode: Int,
        params: ByteArray = byteArrayOf(),
        timeoutMs: Int = DEFAULT_COMMAND_TIMEOUT_MS,
    ): HciResult<ByteArray> = dispatcher.sendCommand(opcode, params, timeoutMs).fold(
        onSuccess = { HciResult.Ok(it) },
        onFailure = { HciResult.Failed(it.message ?: it.javaClass.simpleName) },
    )

    /**
     * Minimal bring-up so the dongle becomes usable and discoverable:
     * Reset -> Read_Local_Version -> Read_BD_ADDR -> Read_Buffer_Size ->
     * Write_Scan_Enable(inquiry+page) -> Write_Local_Name.
     *
     * Each step runs through [UsbBtTiming] with per-step timeout + retry and
     * exponential backoff (see that file for the public references behind the
     * policy values). Per-step durations are recorded into [InitResult.steps]
     * for diagnostics. HCI_Reset is awaited via its Command Complete — never
     * a hard-coded sleep.
     */
    fun initialize(
        localName: String = "DiPlay",
        firmwareLoader: UsbBtFirmwareLoader? = null,
    ): HciResult<InitResult> {
        val steps = mutableListOf<String>()
        val timings = mutableListOf<UsbBtTiming.StepTiming>()
        fun step(text: String) {
            steps.add(text)
            Log.i(TAG, text)
        }

        fun timed(
            name: String,
            policy: UsbBtTiming.RetryPolicy,
            opcode: Int,
            params: ByteArray = byteArrayOf(),
        ): HciResult<ByteArray> {
            val (result, timing) = UsbBtTiming.runTimed<ByteArray>(name, policy) { timeout ->
                when (val r = sendCommand(opcode, params, timeout)) {
                    is HciResult.Ok -> Result.success(r.value)
                    is HciResult.Failed -> Result.failure(RuntimeException(r.reason))
                }
            }
            timings.add(timing)
            step(
                "$name: ${if (timing.success) "OK" else "FAIL"} " +
                    "${timing.durationMs}ms attempts=${timing.attempts}" +
                    (timing.lastError?.let { " (${it.take(80)})" } ?: ""),
            )
            return result.fold(
                onSuccess = { HciResult.Ok(it) },
                onFailure = { HciResult.Failed(timing.lastError ?: "failed") },
            )
        }

        val reset = timed("HCI_Reset", UsbBtTiming.RESET_POLICY, OP_RESET)
        if (reset is HciResult.Failed) {
            val fwResult = firmwareLoader?.loadForDevice(device.vendorId, device.productId)
            if (fwResult is UsbBtFirmwareLoader.LoadResult.Uploaded) {
                step("firmware uploaded (${fwResult.detail}); re-running init")
                return initialize(localName, null)
            }
            val hint = when (fwResult) {
                is UsbBtFirmwareLoader.LoadResult.Failed -> fwResult.reason
                is UsbBtFirmwareLoader.LoadResult.NoFirmware -> "missing firmware asset: ${fwResult.assetName}"
                is UsbBtFirmwareLoader.LoadResult.NoFirmwareNeeded ->
                    "device is ROM-based (no upload required); check USB bus power or connection"
                is UsbBtFirmwareLoader.LoadResult.Skipped -> fwResult.detail
                else -> "no response to HCI_Reset"
            }
            return HciResult.Failed("HCI_Reset failed: ${reset.reason}; $hint")
        }

        val version = timed("Read_Local_Version", UsbBtTiming.READ_POLICY, OP_READ_LOCAL_VERSION)
        when (version) {
            is HciResult.Ok -> step("Local_Version ok (${version.value.size} return bytes)")
            is HciResult.Failed -> step("Local_Version failed (non-fatal): ${version.reason}")
        }
        when (val firmware = firmwareLoader?.loadForDevice(
            device.vendorId, device.productId, (version as? HciResult.Ok)?.value,
        )) {
            is UsbBtFirmwareLoader.LoadResult.Uploaded -> {
                step("firmware uploaded: ${firmware.detail}")
                val patchedReset = timed("Post_Firmware_Reset", UsbBtTiming.RESET_POLICY, OP_RESET)
                if (patchedReset is HciResult.Failed) return HciResult.Failed("post-firmware reset: ${patchedReset.reason}")
            }
            is UsbBtFirmwareLoader.LoadResult.Failed -> return HciResult.Failed("firmware setup failed: ${firmware.reason}")
            is UsbBtFirmwareLoader.LoadResult.NoFirmware -> step("no optional firmware asset: ${firmware.assetName}")
            is UsbBtFirmwareLoader.LoadResult.NoFirmwareNeeded -> step(firmware.detail)
            is UsbBtFirmwareLoader.LoadResult.Skipped -> step(firmware.detail)
            null -> Unit
        }

        when (val features = timed("Read_Local_Features", UsbBtTiming.READ_POLICY, OP_READ_LOCAL_FEATURES)) {
            is HciResult.Ok -> step("Local_Features ok (${features.value.size} return bytes)")
            is HciResult.Failed -> step("Local_Features failed (non-fatal): ${features.reason}")
        }

        val bdAddr = when (val addr = timed("Read_BD_ADDR", UsbBtTiming.READ_POLICY, OP_READ_BD_ADDR)) {
            is HciResult.Ok -> parseBdAddr(addr.value)?.also { step("BD_ADDR=$it") }
                ?: run { step("Read_BD_ADDR returned malformed address"); null }
            is HciResult.Failed -> {
                return HciResult.Failed("Read_BD_ADDR failed: ${addr.reason}")
            }
        }

        val bufferSize = when (val bs = timed("Read_Buffer_Size", UsbBtTiming.READ_POLICY, OP_READ_BUFFER_SIZE)) {
            is HciResult.Ok -> parseBufferSize(bs.value)?.also {
                step("Buffer_Size aclLen=${it.aclDataPacketLength} aclPkts=${it.totalNumAclPackets}")
            }
            is HciResult.Failed -> {
                step("Read_Buffer_Size failed (non-fatal): ${bs.reason}")
                null
            }
        }

        // 6. Set_Event_Mask
        // Without the Secure Simple Pairing events the host never sees IO Capability
        // Request and every pairing ends in Authentication Failure (0x05).
        val eventMask = timed(
            "Set_Event_Mask",
            UsbBtTiming.WRITE_POLICY,
            OP_SET_EVENT_MASK,
            encodeSetEventMask(HciProtocol.EVENT_MASK_FULL),
        )
        if (eventMask is HciResult.Failed) {
            val fallback = timed(
                "Set_Event_Mask_Default_With_SSP",
                UsbBtTiming.WRITE_POLICY,
                OP_SET_EVENT_MASK,
                encodeSetEventMask(HciProtocol.EVENT_MASK_DEFAULT_WITH_SSP),
            )
            if (fallback is HciResult.Failed) {
                step("Set_Event_Mask FAIL: pairing events are masked, iPhone pairing cannot work (${fallback.reason})")
            }
        }

        // 7. Write_Local_Name (set name as early as possible so inquiry shows DiPlay-XXXX immediately)
        val suffix = bdAddr?.takeLast(5)?.replace(":", "") ?: "BT"
        val effectiveName = if (localName == "DiPlay") "DiPlay-$suffix" else localName
        val name = timed(
            "Write_Local_Name",
            UsbBtTiming.WRITE_POLICY,
            OP_WRITE_LOCAL_NAME,
            encodeLocalName(effectiveName),
        )
        if (name is HciResult.Failed) {
            step("Write_Local_Name failed (non-fatal): ${name.reason}")
        } else {
            step("Local_Name=$effectiveName ok")
        }

        // 8. Write_Simple_Pairing_Mode (enable SSP for seamless pairing with iPhone)
        val ssp = timed(
            "Write_Simple_Pairing_Mode",
            UsbBtTiming.WRITE_POLICY,
            OP_WRITE_SIMPLE_PAIRING_MODE,
            byteArrayOf(0x01),
        )
        if (ssp is HciResult.Failed) {
            step("Write_Simple_Pairing_Mode failed (non-fatal): ${ssp.reason}")
        }

        // 8b. Write_Authentication_Enable (0x00 = do not force legacy PIN code authentication under SSP)
        val authEnable = timed(
            "Write_Authentication_Enable",
            UsbBtTiming.WRITE_POLICY,
            OP_WRITE_AUTHENTICATION_ENABLE,
            byteArrayOf(0x00),
        )
        if (authEnable is HciResult.Failed) {
            step("Write_Authentication_Enable failed (non-fatal): ${authEnable.reason}")
        }

        // 9. Write_Inquiry_Mode (2 = Extended Inquiry Result or RSSI)
        timed(
            "Write_Inquiry_Mode",
            UsbBtTiming.WRITE_POLICY,
            OP_WRITE_INQUIRY_MODE,
            encodeWriteInquiryMode(2),
        )

        // 10. Write_Default_Link_Policy_Settings (0x0005 = role switch + sniff)
        timed(
            "Write_Default_Link_Policy",
            UsbBtTiming.WRITE_POLICY,
            OP_WRITE_DEFAULT_LINK_POLICY,
            encodeWriteDefaultLinkPolicy(5),
        )

        // 11. Write_Class_Of_Device (Handsfree car audio: 0x200408)
        val btIdentity = UsbBtIdentity(name = effectiveName)
        val cod = timed(
            "Write_Class_Of_Device",
            UsbBtTiming.WRITE_POLICY,
            OP_WRITE_CLASS_OF_DEVICE,
            encodeWriteClassOfDevice(btIdentity.classOfDevice),
        )
        if (cod is HciResult.Failed) {
            step("Write_Class_Of_Device failed (non-fatal): ${cod.reason}")
        }

        // 12. Read_Inquiry_Response_TX_Power_Level
        val txPower = when (val pwr = timed("Read_Inquiry_Response_TX_Power", UsbBtTiming.READ_POLICY, OP_READ_INQUIRY_RESPONSE_TX_POWER)) {
            is HciResult.Ok -> if (pwr.value.size >= 2 && pwr.value[0] == 0.toByte()) {
                pwr.value[1].toInt()
            } else null
            is HciResult.Failed -> null
        }

        // 13. Write_Extended_Inquiry_Response (crucial for iPhone to recognize CarPlay device)
        val eirBytes = btIdentity.extendedInquiryResponse(txPower)
        val eirResult = timed(
            "Write_Extended_Inquiry_Response",
            UsbBtTiming.WRITE_POLICY,
            OP_WRITE_EXTENDED_INQUIRY_RESPONSE,
            encodeWriteExtendedInquiryResponse(eirBytes),
        )
        if (eirResult is HciResult.Failed) {
            step("Write_Extended_Inquiry_Response failed (non-fatal): ${eirResult.reason}")
        } else {
            step("Extended_Inquiry_Response written (${eirBytes.size} bytes)")
        }

        // 14. Write_Inquiry_Scan_Type (interlaced, fallback to standard)
        val inqType = timed(
            "Write_Inquiry_Scan_Type",
            UsbBtTiming.WRITE_POLICY,
            OP_WRITE_INQUIRY_SCAN_TYPE,
            encodeWriteScanType(true),
        )
        if (inqType is HciResult.Failed) {
            timed(
                "Write_Inquiry_Scan_Type_Standard",
                UsbBtTiming.WRITE_POLICY,
                OP_WRITE_INQUIRY_SCAN_TYPE,
                encodeWriteScanType(false),
            )
        }

        // 14b. Write_Inquiry_Scan_Activity (640ms interval, 160ms window = 25% duty cycle for fast discovery)
        timed(
            "Write_Inquiry_Scan_Activity",
            UsbBtTiming.WRITE_POLICY,
            OP_WRITE_INQUIRY_SCAN_ACTIVITY,
            encodeWriteScanActivity(0x0400, 0x0100),
        )

        // 15. Write_Page_Scan_Type (interlaced, fallback to standard)
        val pageType = timed(
            "Write_Page_Scan_Type",
            UsbBtTiming.WRITE_POLICY,
            OP_WRITE_PAGE_SCAN_TYPE,
            encodeWriteScanType(true),
        )
        if (pageType is HciResult.Failed) {
            timed(
                "Write_Page_Scan_Type_Standard",
                UsbBtTiming.WRITE_POLICY,
                OP_WRITE_PAGE_SCAN_TYPE,
                encodeWriteScanType(false),
            )
        }

        // 15b. Write_Page_Scan_Activity (640ms interval, 80ms window)
        timed(
            "Write_Page_Scan_Activity",
            UsbBtTiming.WRITE_POLICY,
            OP_WRITE_PAGE_SCAN_ACTIVITY,
            encodeWriteScanActivity(0x0400, 0x0080),
        )

        // 16. Write_Scan_Enable (Inquiry + Page)
        val scan = timed(
            "Write_Scan_Enable",
            UsbBtTiming.WRITE_POLICY,
            OP_WRITE_SCAN_ENABLE,
            byteArrayOf(SCAN_INQUIRY_AND_PAGE.toByte()),
        )
        if (scan is HciResult.Failed) {
            return HciResult.Failed("Write_Scan_Enable failed: ${scan.reason}")
        }

        return HciResult.Ok(InitResult(bdAddr = bdAddr, bufferSize = bufferSize, steps = steps, advertisingName = effectiveName))
    }

    override fun close() {
        dispatcher.close()
        runCatching { connection.releaseInterface(btInterface) }
        runCatching { connection.close() }
        Log.i(TAG, "HCI closed")
    }

    /**
     * Send an L2CAP packet (already framed by [HciProtocol.encodeL2cap]) over
     * the ACL bulk-OUT endpoint. Returns false when the transfer failed.
     *
     * Phase 3: used by the L2CAP layer. Blocking; call from a background thread.
     */
    fun sendAcl(handle: Int, l2capPacket: ByteArray, timeoutMs: Int = 2_000): Boolean {
        val packet = runCatching { HciProtocol.encodeAcl(handle, l2capPacket) }.getOrElse {
            Log.w(TAG, "ACL encode failed: ${it.message}")
            return false
        }
        val sent = connection.bulkTransfer(aclOutEndpoint, packet, packet.size, timeoutMs)
        if (sent != packet.size) {
            Log.w(TAG, "ACL bulkTransfer sent $sent/${packet.size}")
            return false
        }
        return true
    }

    /**
     * Read one HCI ACL packet from the bulk-IN endpoint. Returns null on
     * timeout or when the packet is malformed. Blocking up to [timeoutMs].
     *
     * Phase 3: the L2CAP layer polls this in its receive loop.
     */
    fun pollAcl(timeoutMs: Int = 500): HciProtocol.AclPacket? {
        val buf = ByteArray(2048)
        val read = connection.bulkTransfer(aclInEndpoint, buf, buf.size, timeoutMs)
        if (read <= 0) return null
        return HciProtocol.parseAcl(buf, read)
    }

    /**
     * Read all HCI ACL packets available in one bulk-IN transfer.
     */
    fun pollAclPackets(timeoutMs: Int = 100): List<HciProtocol.AclPacket> {
        val buf = ByteArray(2048)
        val read = connection.bulkTransfer(aclInEndpoint, buf, buf.size, timeoutMs)
        if (read <= 0) return emptyList()
        return HciProtocol.parseAclPackets(buf, read)
    }

    /**
     * Poll one HCI event without consuming it for a pending command.
     * Used by the connection/pairing machinery for async events
     * (Connection Complete, IO Capability Request, ...).
     */
    fun pollEvent(timeoutMs: Int = 500): HciProtocol.HciEvent? = dispatcher.pollEvent(timeoutMs)
}
