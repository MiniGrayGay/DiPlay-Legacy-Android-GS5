package com.shilapi.xcertplay.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuangqiNmeaLocationProviderTest {
    private val gga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
    private val rmc = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"
    private var now = 10_000L
    private val sentences = GuangqiNmeaLocationProvider.NmeaSentences { now }

    @Test fun checksumOfKnownSentences() {
        assertTrue(GuangqiNmeaLocationProvider.checksumValid(gga))
        assertTrue(GuangqiNmeaLocationProvider.checksumValid(rmc))
    }

    @Test fun cutOrDamagedSentencesAreRejected() {
        assertFalse(GuangqiNmeaLocationProvider.checksumValid(gga.substring(0, 40)))
        assertFalse(GuangqiNmeaLocationProvider.checksumValid(gga.replace("4807.038", "4807.039")))
        assertFalse(GuangqiNmeaLocationProvider.checksumValid(gga.removeSuffix("*47")))
    }

    @Test fun multiConstellationTalkerBecomesGps() {
        val gn = GuangqiNmeaLocationProvider.asGps(gga).let { gp ->
            // Build the $GN form of the same sentence, then convert it back.
            val body = "GN" + gp.substring(3, gp.lastIndexOf('*'))
            var sum = 0
            for (c in body) sum = sum xor c.code
            "$" + body + "*" + String.format("%02X", sum)
        }
        assertTrue(GuangqiNmeaLocationProvider.checksumValid(gn))
        assertEquals(gga, GuangqiNmeaLocationProvider.asGps(gn))
        assertEquals(rmc, GuangqiNmeaLocationProvider.asGps(rmc))
    }

    @Test fun onlyValidGgaAndRmcAreSentOnce() {
        sentences.accept("\$GPGSV,3,1,11,03,03,111,00,04,15,270,00,06,01,010,00,13,06,292,00*74")
        sentences.accept(gga.substring(10))
        assertNull(sentences.take())
        assertFalse(sentences.recentlyReceived())
        sentences.accept("$gga\r\n$rmc\r\n")
        assertEquals("$gga\r\n$rmc\r\n", sentences.take())
        assertNull(sentences.take())
    }

    @Test fun quietNmeaLetsTheEncodedFixThrough() {
        sentences.accept(gga)
        assertTrue(sentences.recentlyReceived())
        now += 3_001
        assertFalse(sentences.recentlyReceived())
    }
}
