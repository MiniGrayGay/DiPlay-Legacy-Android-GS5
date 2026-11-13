package com.shilapi.xcertplay.usbbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbBtPairingTest {

    /** Build User_Confirmation_Request params: BD_ADDR(6) + u32 LE value. */
    private fun confirmationParams(value: Long): ByteArray {
        val addr = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        return addr + byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )
    }

    @Test
    fun extractSixDigitsTypical() {
        // 1234567 % 1_000_000 = 234567
        assertEquals(234567, UsbBtPairingAgent.extractSixDigitNumber(confirmationParams(1_234_567)))
    }

    @Test
    fun extractSixDigitsSmallValue() {
        assertEquals(42, UsbBtPairingAgent.extractSixDigitNumber(confirmationParams(42)))
    }

    @Test
    fun extractSixDigitsMaxU32() {
        // 0xFFFFFFFF % 1_000_000 = 294967295 % ... = 967295
        assertEquals((0xFFFFFFFFL % 1_000_000).toInt(),
            UsbBtPairingAgent.extractSixDigitNumber(confirmationParams(0xFFFFFFFFL)))
    }

    @Test
    fun extractSixDigitsZero() {
        assertEquals(0, UsbBtPairingAgent.extractSixDigitNumber(confirmationParams(0)))
    }

    @Test
    fun extractRejectsShortParams() {
        assertNull(UsbBtPairingAgent.extractSixDigitNumber(byteArrayOf(1, 2, 3)))
        assertNull(UsbBtPairingAgent.extractSixDigitNumber(ByteArray(9)))
    }

    @Test
    fun extractIgnoresExtraTrailingBytes() {
        val params = confirmationParams(999) + byteArrayOf(0x7F, 0x7E)
        assertEquals(999, UsbBtPairingAgent.extractSixDigitNumber(params))
    }

    @Test
    fun sixDigitFormatting() {
        // The dialog formats with %06d so small numbers keep leading zeros.
        assertEquals("000042", "%06d".format(42))
        assertEquals("234567", "%06d".format(234567))
    }

    @Test
    fun ioCapabilityConstants() {
        // DisplayYesNo for Numeric Comparison, NoInputNoOutput for Just Works.
        assertEquals(0x01, HciProtocol.IO_CAP_DISPLAY_YES_NO)
        assertEquals(0x03, HciProtocol.IO_CAP_NO_INPUT_NO_OUTPUT)
    }

    @Test
    fun confirmationOpcodes() {
        assertEquals(0x0400 or 0x2C, HciProtocol.OP_USER_CONFIRMATION_REQUEST_REPLY and 0xFFFF)
        // Negative reply is the adjacent OCF.
        assertEquals(
            HciProtocol.OP_USER_CONFIRMATION_REQUEST_REPLY + 1,
            HciProtocol.OP_USER_CONFIRMATION_REQUEST_NEGATIVE_REPLY,
        )
    }

    @Test
    fun linkKeyAndPinCodeOpcodes() {
        assertEquals(0x040B, HciProtocol.OP_LINK_KEY_REQUEST_REPLY)
        assertEquals(0x040C, HciProtocol.OP_LINK_KEY_REQUEST_NEGATIVE_REPLY)
        assertEquals(0x040D, HciProtocol.OP_PIN_CODE_REQUEST_REPLY)
        assertEquals(0x040E, HciProtocol.OP_PIN_CODE_REQUEST_NEGATIVE_REPLY)
    }

    @Test
    fun encodeLinkKeyReplyPayload() {
        val bdAddr = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        val linkKey = ByteArray(16) { it.toByte() }
        val params = HciProtocol.encodeLinkKeyReply(bdAddr, linkKey)
        assertEquals(22, params.size)
        assertEquals(bdAddr.toList(), params.copyOfRange(0, 6).toList())
        assertEquals(linkKey.toList(), params.copyOfRange(6, 22).toList())
    }

    @Test
    fun encodeLinkKeyNegativeReplyPayload() {
        val bdAddr = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        val params = HciProtocol.encodeLinkKeyNegativeReply(bdAddr)
        assertEquals(6, params.size)
        assertEquals(bdAddr.toList(), params.toList())
    }

    @Test
    fun encodePinCodeNegativeReplyPayload() {
        val bdAddr = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val params = HciProtocol.encodePinCodeNegativeReply(bdAddr)
        assertEquals(6, params.size)
        assertEquals(bdAddr.toList(), params.toList())
    }

    @Test
    fun eventMasksEnablePairingEventsWithoutReservedBits() {
        // Bits 62 and 63 are reserved; a CSR8510 rejects a mask that sets them (0x12).
        val reserved = 0x3L shl 62
        for (mask in listOf(HciProtocol.EVENT_MASK_FULL, HciProtocol.EVENT_MASK_DEFAULT_WITH_SSP)) {
            assertEquals(0L, mask and reserved)
            assertEquals(HciProtocol.EVENT_MASK_SSP_REQUIRED, mask and HciProtocol.EVENT_MASK_SSP_REQUIRED)
            // Link Key Request (bit 22) and Link Key Notification (bit 23).
            assertEquals(0xC00000L, mask and 0xC00000L)
        }
        assertEquals(
            listOf(0xFF, 0xFF, 0xFB, 0xFF, 0x07, 0xF8, 0xBF, 0x3D),
            HciProtocol.encodeSetEventMask().map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun ioCapabilityAuthReqValues() {
        // Just Works (NoInputNoOutput = 3): General Bonding, MITM not required (0x04)
        val justWorksAuthReq = if (HciProtocol.IO_CAP_NO_INPUT_NO_OUTPUT == 3) 0x04 else 0x05
        assertEquals(0x04, justWorksAuthReq)

        // Numeric Comparison (DisplayYesNo = 1): General Bonding, MITM Protection Required (0x05)
        val numericComparisonAuthReq = if (HciProtocol.IO_CAP_DISPLAY_YES_NO == 3) 0x04 else 0x05
        assertEquals(0x05, numericComparisonAuthReq)
    }
}
