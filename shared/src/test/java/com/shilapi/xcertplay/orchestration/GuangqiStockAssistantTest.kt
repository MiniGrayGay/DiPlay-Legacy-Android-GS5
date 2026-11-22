package com.shilapi.xcertplay.orchestration

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GuangqiStockAssistantTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test fun pausesWhileAnyReasonHoldsAndRecoversAnAbandonedPause() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = ADAPTER
            applicationInfo = ApplicationInfo().apply { packageName = ADAPTER }
        })

        GuangqiStockAssistant.acquire(app, "microphone 1")
        assertEquals(102, nextCommand())
        GuangqiStockAssistant.acquire(app, "voice-key")
        assertNull("a second reason sends nothing", nextCommand())

        GuangqiStockAssistant.release(app, "microphone 1")
        assertNull("still held by the voice key", nextCommand())
        GuangqiStockAssistant.reassert(app)
        assertEquals(102, nextCommand())
        GuangqiStockAssistant.release(app, "voice-key")
        assertEquals(101, nextCommand())
        GuangqiStockAssistant.release(app, "voice-key")
        assertNull("releasing twice does nothing", nextCommand())

        // A process that died holding the pause left the flag; the next one resumes the assistant.
        app.getSharedPreferences("guangqi_stock_assistant", 0).edit().putBoolean("paused", true).commit()
        GuangqiStockAssistant.recover(app)
        assertEquals(101, nextCommand())
        GuangqiStockAssistant.recover(app)
        assertNull(nextCommand())
    }

    private fun nextCommand(): Int? = shadowOf(app).nextStartedService?.let { intent ->
        assertEquals("com.gaei.speechadapter.SpeechAdapterService", intent.component?.className)
        intent.getIntExtra("vr_control", -1)
    }

    private companion object {
        const val ADAPTER = "com.gaei.speechadapter"
    }
}
