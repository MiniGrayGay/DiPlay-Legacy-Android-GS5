package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuangqiMicrophoneHolderTest {
    /** From the car, 2026-11-25, with its wake word listening. */
    private val car = """
        |Clients:
        |  pid: 2041
        |  pid: 2305
        |Global session refs:
        | session pid count
        |       8 2305   1
        |      10 2041   1
        |
        |Input thread 0x400b1898 internals
        |In index: 0
        |Buffer size: 768 bytes
        |Sample rate: 16000
        |
        |Input thread 0x400b1898 tracks
        |  Client Fmt Chn mask Session S   Server fCount
        |    2041   1 00000010      10 6 00412300   2304
        |    2305   1 00000010      12 1 00000000   2304
        |
        |Input thread 0x400b1898 active tracks
        |  Client Fmt Chn mask Session S   Server fCount
        |    2041   1 00000010      10 6 00412300   2304
        |
        |- 1 Effect Chains:
        |			Pid   Priority Ctrl Locked client server
        |			02041 -0001    1    0      00000  00000
    """.trimMargin()

    @Test fun onlyTheClientThatIsRecordingIsAHolder() {
        assertEquals(listOf(2041), GuangqiMicrophoneHolder.recordingPids(car))
    }

    @Test fun nobodyRecordingMeansNoHolder() {
        val idle = car.replace(Regex("""(active tracks\n.*\n).*\n"""), "$1")
        assertEquals(emptyList<Int>(), GuangqiMicrophoneHolder.recordingPids(idle))
        assertEquals(emptyList<Int>(), GuangqiMicrophoneHolder.recordingPids(""))
    }

    @Test fun onlyTheStockAssistantIsEverEnded() {
        assertTrue(GuangqiMicrophoneHolder.isAssistant("com.tencent.wecarspeech"))
        assertTrue(GuangqiMicrophoneHolder.isAssistant("com.tencent.wecarspeech:wecarbase"))
        for (other in listOf("", "com.android.bluetooth", "gaei.ecallbcall", "com.tencent.wecarnavi", "com.tencent.wecarspeechx")) {
            assertFalse(other, GuangqiMicrophoneHolder.isAssistant(other))
        }
    }
}
