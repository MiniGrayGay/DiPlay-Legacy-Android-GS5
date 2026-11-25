package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarSelfTestActivityTest {
    private fun texts(view: View): List<String> = when (view) {
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        is TextView -> listOf(view.text.toString())
        else -> emptyList()
    }

    private fun find(view: View, text: String): Button? = when (view) {
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it), text) }
        is Button -> view.takeIf { it.text.toString() == text }
        else -> null
    }

    /** Off the car every step fails or is skipped; it must still report and stop when told to. */
    @Test fun aRunReportsItsStepsAndStops() {
        val activity = Robolectric.buildActivity(CarSelfTestActivity::class.java).setup().get()
        val root = activity.window.decorView
        find(root, activity.getString(R.string.car_self_test_start))!!.performClick()
        val stop = activity.getString(R.string.car_self_test_stop)
        assertTrue(stop in texts(root))
        val report = File(activity.filesDir, "logs/${CarSelfTestActivity.REPORT_NAME}")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while ("车机状态" !in report.readText() && System.nanoTime() < deadline) Thread.sleep(50)
        ShadowLooper.idleMainLooper()
        find(root, stop)!!.performClick()
        while (stop in texts(root) && System.nanoTime() < deadline) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper()
        }
        assertEquals(1, texts(root).count { it == activity.getString(R.string.car_self_test_start) })
        assertTrue(report.readText(), "已停止" in report.readText())
        assertTrue(texts(root).joinToString(), texts(root).any { it.startsWith("✗  车机状态") })
    }
}
