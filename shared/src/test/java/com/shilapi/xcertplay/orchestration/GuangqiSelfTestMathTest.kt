package com.shilapi.xcertplay.orchestration

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuangqiSelfTestMathTest {
    private val rate = 48_000

    /** Cabin noise at about -40 dBFS, with [tone] at [gain] mixed in after 400 ms. */
    private fun cabin(millis: Int, tone: ByteArray? = null, gain: Double = 0.0): ByteArray {
        val random = Random(7)
        val out = ByteArray(rate * 2 / 1000 * millis)
        for (i in 0 until out.size / 2) {
            var value = random.nextGaussian() * 330
            val at = (i - rate * 2 / 5) * 2
            if (tone != null && at in 0 until tone.size - 1) {
                value += ((tone[at].toInt() and 0xff) or (tone[at + 1].toInt() shl 8)) * gain
            }
            val sample = value.toInt().coerceIn(-32768, 32767)
            out[2 * i] = sample.toByte()
            out[2 * i + 1] = (sample shr 8).toByte()
        }
        return out
    }

    @Test fun aToneInTheCabinIsHeardAndNoiseAloneIsNot() {
        val tone = SelfTestTone.pcm(rate, 1_200)
        assertTrue(SelfTestTone.riseDb(cabin(1_000), cabin(2_000, tone, 0.1), rate) > 20)
        assertTrue(SelfTestTone.riseDb(cabin(1_000), cabin(2_000), rate) < SelfTestTone.HEARD_DB)
        assertTrue("a tone that plays anyway is not a rise",
            SelfTestTone.riseDb(tone, tone, rate) < SelfTestTone.HEARD_DB)
        assertTrue(SelfTestTone.riseDb(ByteArray(0), cabin(2_000), rate).isNaN())
    }

    @Test fun levels() {
        assertEquals(-12.1, SelfTestTone.levelDb(SelfTestTone.pcm(rate, 1_200)), 0.3)
        assertEquals(-120.0, SelfTestTone.levelDb(ByteArray(960)), 0.0)
    }

    /** As DiPlay writes it; the first line is from the car on 2026-11-25. */
    private fun stats(sent: Int, errors: Int, first: Int, ended: Boolean, silent: Int = 0, gap: Int = 0, filled: Int = 0) =
        "Microphone: stats type=speechrecognition source=VOICE_RECOGNITION codec=OPUS rate=48000 channels=1 frameMs=20 " +
            "routedDeviceType=unknown captureBytes=0 reads=1 zeroReads=0 readErrors=1 readMaxMs=0 encodedFrames=0 " +
            "emptyEncodedFrames=0 udpSent=$sent sendErrors=$errors sendGapMaxMs=$gap filledFrames=$filled silentFrames=$silent firstAudioMs=$first ended=$ended"

    @Test fun siriWithoutAMicrophone() {
        val siri = SelfTestSiri(listOf(stats(0, 0, -1, true)))
        assertTrue(siri.ended)
        assertFalse(siri.microphoneWorks)
    }

    @Test fun siriWithAMicrophone() {
        val siri = SelfTestSiri(listOf(stats(250, 0, 120, false), stats(40, 0, 120, true),
            "Audio: first PCM write audioType=speechrecognition bytes=1920 api=19",
            "Audio: Siri source 25 requested over DSP policy 2: result=0x6 held=true"))
        assertEquals(290L, siri.sent)
        assertTrue(siri.microphoneWorks && siri.downlink && siri.sourceHeld && !siri.sourceRefused)
        assertFalse(SelfTestSiri(listOf(stats(250, 3, 120, true))).microphoneWorks)
        assertFalse("silence", SelfTestSiri(listOf(stats(250, 0, -1, true))).microphoneWorks)
    }

    /** The three Siri runs on the car on 2026-11-26: nobody spoke, spoken with a 1 s gap, spoken. */
    @Test fun siriSpokenAndBroken() {
        val quiet = SelfTestSiri(listOf(stats(253, 0, 518, false, 251), stats(72, 0, 518, true, 72)))
        assertEquals(2L, quiet.audible)
        assertFalse(quiet.spoken || quiet.broken)
        val gap = SelfTestSiri(listOf(stats(198, 0, 15, false, 64, 1063), stats(199, 0, 15, true, 93, 48)))
        assertEquals(240L, gap.audible)
        assertTrue(gap.spoken && gap.broken)
        val good = SelfTestSiri(listOf(stats(251, 0, 12, false, 163, 65), stats(120, 0, 12, true, 5, 46)))
        assertTrue(good.spoken && !good.broken)
        assertTrue("filled with silence", SelfTestSiri(listOf(stats(250, 0, 12, true, 10, 200, 50))).broken)
    }

    @Test fun callsAreNotSiri() {
        assertEquals(0L, SelfTestSiri(listOf(stats(9, 0, 1, true).replace("speechrecognition", "telephony"))).sent)
    }
}
