package com.shilapi.xcertplay.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuangqiNmeaLocationProviderTest {
    private val gga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
    private val rmc = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"

    @Test fun checksumOfKnownSentences() {
        assertTrue(GuangqiNmeaLocationProvider.checksumValid(gga))
        assertTrue(GuangqiNmeaLocationProvider.checksumValid(rmc))
    }

    @Test fun cutOrDamagedSentencesAreRejected() {
        assertFalse(GuangqiNmeaLocationProvider.checksumValid(gga.substring(0, 40)))
        assertFalse(GuangqiNmeaLocationProvider.checksumValid(gga.replace("4807.038", "4807.039")))
        assertFalse(GuangqiNmeaLocationProvider.checksumValid(gga.removeSuffix("*47")))
    }

    @Test fun onlyValidGgaAndRmcAreSentOnce() {
        val provider = GuangqiNmeaLocationProvider()
        provider.accept("\$GPGSV,3,1,11,03,03,111,00,04,15,270,00,06,01,010,00,13,06,292,00*74")
        provider.accept(gga.substring(10))
        assertNull(provider.latestNmea())
        provider.accept("$gga\r")
        provider.accept(rmc)
        assertEquals("$gga\r\n$rmc\r\n", provider.latestNmea())
        assertNull(provider.latestNmea())
    }
}
