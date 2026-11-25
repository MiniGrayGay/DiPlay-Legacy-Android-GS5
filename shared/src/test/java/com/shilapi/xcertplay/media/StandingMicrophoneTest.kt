package com.shilapi.xcertplay.media

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StandingMicrophoneTest {
    private val madeRoom = AtomicInteger()
    private val opened = AtomicInteger()
    private val released = CountDownLatch(1)
    private var microphone: StandingMicrophone? = null

    /** Records only when [granted]; delivers 20 ms chunks at about real time. */
    private class FakeRecord(private val granted: Boolean, private val failAfter: Int = Int.MAX_VALUE) : AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION, 48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 8_192,
    ) {
        @Volatile private var recording = false
        private var reads = 0
        override fun startRecording() { recording = granted }
        override fun getRecordingState() = if (recording) RECORDSTATE_RECORDING else RECORDSTATE_STOPPED
        override fun stop() { recording = false }
        override fun release() { recording = false }
        override fun getAudioSessionId() = 7
        override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
            if (++reads > failAfter) return ERROR_INVALID_OPERATION
            Thread.sleep(5)
            for (i in 0 until sizeInBytes) audioData[offsetInBytes + i] = if (i % 2 == 1) 4 else 0
            return sizeInBytes
        }
    }

    private fun standing(open: (Int) -> AudioRecord?) = StandingMicrophone(
        openRecorder = { open(opened.incrementAndGet()) },
        makeRoom = { madeRoom.incrementAndGet() },
        onReleased = released::countDown,
        retryMillis = 20,
        makeRoomMillis = 60,
    ).also { microphone = it }

    private fun StandingMicrophone.Reader.awaitAudio(): Int {
        val buffer = ByteArray(StandingMicrophone.CHUNK_BYTES)
        repeat(20) { read(buffer).let { if (it != 0) return it } }
        return 0
    }

    @After fun tearDown() { microphone?.close() }

    @Test fun itKeepsAskingUntilTheCarLetsGoAndAsksAgainWhileRecording() {
        val mic = standing { attempt -> FakeRecord(granted = attempt >= 4) }.start()
        val reader = mic.subscribe()!!
        assertEquals(StandingMicrophone.CHUNK_BYTES, reader.awaitAudio())
        assertEquals("three refusals, then held", 4, opened.get())
        val asked = madeRoom.get()
        assertTrue("asked before every attempt", asked >= 4)
        Thread.sleep(300)
        assertTrue("asked again while recording", madeRoom.get() > asked)
        assertEquals("one recorder throughout", 4, opened.get())
    }

    @Test fun aReaderKeepsReadingWhenTheRecorderHasToBeOpenedAgain() {
        val mic = standing { attempt -> FakeRecord(granted = true, failAfter = if (attempt == 1) 3 else Int.MAX_VALUE) }.start()
        val reader = mic.subscribe()!!
        repeat(8) { assertEquals(StandingMicrophone.CHUNK_BYTES, reader.awaitAudio()) }
        assertEquals(2, opened.get())
    }

    @Test fun eachReaderGetsEveryChunkAndASlowOneTheNewest() {
        val mic = standing { FakeRecord(granted = true) }.start()
        val first = mic.subscribe()!!
        val second = mic.subscribe()!!
        repeat(5) { assertTrue(first.awaitAudio() > 0); assertTrue(second.awaitAudio() > 0) }
        Thread.sleep(400)
        val buffer = ByteArray(StandingMicrophone.CHUNK_BYTES)
        second.read(buffer)
        val ageMs = (System.nanoTime() - second.lastReadEndNs) / 1_000_000
        assertTrue("oldest kept chunk is ${ageMs}ms old", ageMs < 1_000)
        second.close()
        assertEquals(-1, second.read(buffer))
        assertTrue("the other stream goes on", first.awaitAudio() > 0)
    }

    @Test fun withoutAnyRecorderEachStreamOpensItsOwn() {
        val mic = standing { null }
        assertNotNull("not known yet", mic.subscribe())
        mic.start()
        val deadline = System.currentTimeMillis() + 3_000
        while (opened.get() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        Thread.sleep(50)
        assertNull(mic.subscribe())
    }

    @Test fun turningItOffWaitsForACallAndThenLetsTheAssistantBack() {
        val mic = standing { FakeRecord(granted = true) }.start()
        val call = mic.subscribe()!!
        assertTrue(call.awaitAudio() > 0)
        mic.closeWhenIdle()
        assertNull("no new stream joins", mic.subscribe())
        assertTrue("the call keeps its microphone", call.awaitAudio() > 0)
        assertFalse(released.await(100, TimeUnit.MILLISECONDS))
        call.close()
        assertTrue(released.await(3, TimeUnit.SECONDS))
    }

    @Test fun closingWhileTheCarStillHoldsTheMicrophoneStopsTheRetries() {
        val mic = standing { FakeRecord(granted = false) }.start()
        Thread.sleep(80)
        mic.close()
        assertTrue(released.await(3, TimeUnit.SECONDS))
        val attempts = opened.get()
        Thread.sleep(100)
        assertEquals(attempts, opened.get())
    }

    @Test fun anUplinkSendsTheStandingAudioAtEveryRateWithoutOpeningARecorder() {
        val mic = standing { FakeRecord(granted = true) }.start()
        for (rate in listOf(48_000, 32_000, 24_000, 16_000)) {
            val diagnostics = java.util.concurrent.CopyOnWriteArrayList<String>()
            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { phone ->
                phone.soTimeout = 3_000
                val config = MicrophoneConfig(
                    audioType = "telephony", sampleRate = rate, channels = 1, payloadType = 100,
                    frameMillis = MicrophoneConfig.pcmFrameMillis(rate, 1), host = InetAddress.getByName("127.0.0.1"),
                    port = phone.localPort, key = ByteArray(32), codec = AudioCodecKind.LPCM,
                )
                val uplink = MicrophoneUplink(config, onDiagnostic = diagnostics::add, standing = mic)
                assertTrue(uplink.start())
                val packet = DatagramPacket(ByteArray(2_048), 2_048)
                repeat(10) { phone.receive(packet) }
                assertTrue("rate $rate packet ${packet.length}", packet.length > config.frameBytes)
                uplink.close()
            }
            assertTrue(diagnostics.toString(), diagnostics.any { it.startsWith("Microphone: start type=telephony source=STANDING") })
            assertTrue(diagnostics.none { it.contains("failure") })
        }
        assertEquals("the one standing recorder", 1, opened.get())
    }

    @Test fun threeSamplesAverageIntoOneAt16Kilohertz() {
        val input = ByteArray(12)
        listOf(300, 600, 900, -300, -600, -900).forEachIndexed { i, v ->
            input[i * 2] = v.toByte(); input[i * 2 + 1] = (v shr 8).toByte()
        }
        val output = ByteArray(4)
        assertEquals(4, MicrophoneUplink.downsample48To16(input, input.size, output))
        fun at(i: Int) = ((output[i * 2].toInt() and 0xFF) or (output[i * 2 + 1].toInt() shl 8)).toShort().toInt()
        assertEquals(600, at(0)); assertEquals(-600, at(1))
    }
}
