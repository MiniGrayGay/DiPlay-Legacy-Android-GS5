package com.shilapi.xcertplay

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.RadioButton
import android.widget.TextView
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.util.concurrent.PausedExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostSettingsTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var controllers: MockedConstruction<CarPlayController>

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        controllers = mockConstruction(CarPlayController::class.java)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        setField("teardownExecutor", PausedExecutorService())
        setField("airPlayIdentity", AirPlayIdentity.generate())
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 990)
        setField("activeDisplaySize", size)
        CarPlayBackgroundSession::class.java.getDeclaredField("owner").apply { isAccessible = true }
            .set(CarPlayBackgroundSession, activity)
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.USB_CH341)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
        invoke("loadPersistedSettings")
        invoke("buildContentView")
    }

    @After fun tearDown() {
        (field("shuttingDown") as AtomicBoolean).set(true)
        (field("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        (field("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
        controllers.close()
    }

    @Test fun configuredFingerCountsOpenTheMountedMenuWithoutLeavingCarPlay() {
        assertEquals(3, AirPlayPersistence.loadSettingsGestureFingers(activity))
        for (fingers in 2..4) {
            AirPlayPersistence.saveSettingsGestureFingers(activity, fingers)
            invoke("loadPersistedSettings")
            gesture(fingers)
            assertTrue(field("menuOpen") as Boolean)
            assertEquals(View.VISIBLE, menu().visibility)
            assertNotNull(menu().parent)
            assertEquals(View.GONE, (field("gestureOverlay") as View).visibility)
            assertNull(shadowOf(activity).nextStartedActivity)
            invoke("cancelSettingsEdits")
        }
    }

    @Test fun wrongFingerCountsAndNonDownwardSwipesDoNotOpenTheMenu() {
        for (configured in 2..4) {
            AirPlayPersistence.saveSettingsGestureFingers(activity, configured)
            invoke("loadPersistedSettings")
            for (actual in 2..4) {
                if (actual == configured) continue
                gesture(actual)
                assertFalse(field("menuOpen") as Boolean)
            }
            gesture(configured, x = 1000f, y = 130f)
            assertFalse(field("menuOpen") as Boolean)
            gesture(configured, y = -500f)
            assertFalse(field("menuOpen") as Boolean)
        }
    }

    @Test fun liftingAFingerCancelsTrackingUntilTheNextGesture() {
        touch(MotionEvent.ACTION_DOWN, 1, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 3, 100f)
        touch(MotionEvent.ACTION_POINTER_UP, 3, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 3, 100f)
        touch(MotionEvent.ACTION_MOVE, 3, 700f)
        assertFalse(field("menuOpen") as Boolean)
        touch(MotionEvent.ACTION_UP, 1, 700f)
        gesture(3)
        assertTrue(field("menuOpen") as Boolean)
    }







    @Test fun diPlaySettingsButtonLeavesTheSessionRunningAndOpensSettings() {
        val controller = attachController()
        invoke("openSettingsMenu")
        quickButton(activity.getString(R.string.app_name) + " " + activity.getString(R.string.settings)).performClick()
        assertFalse(field("menuOpen") as Boolean)
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(DiPlayActivity::class.java.name, intent.component!!.className)
        assertEquals("settings", intent.getStringExtra("page"))
    }

    @Test fun homeButtonLeavesTheSessionRunningAndOpensDiPlayHome() {
        val controller = attachController()
        invoke("openSettingsMenu")
        quickButton(activity.getString(R.string.quick_back_to_home)).performClick()
        assertFalse(field("menuOpen") as Boolean)
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(DiPlayActivity::class.java.name, intent.component!!.className)
        assertEquals("home", intent.getStringExtra("page"))
    }

    @Test fun quickMenuChangeSurvivesLeavingForDiPlaySettings() {
        attachController()
        invoke("openSettingsMenu")
        val before = AirPlayPersistence.loadClusterMapEnabled(activity)
        quickRow(R.string.carplay_map_on_instrument_cluster_experimental).performClick()
        quickButton(activity.getString(R.string.app_name) + " " + activity.getString(R.string.settings)).performClick()
        assertEquals(!before, AirPlayPersistence.loadClusterMapEnabled(activity))
    }

    @Test fun threeFingersOpenTheGuangqiQuickMenu() {
        attachController()
        invoke("openSettingsMenu")
        val texts = views(menu()).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(activity.getString(R.string.settings_quick_settings) in texts)
        assertTrue(activity.getString(R.string.carplay_map_on_instrument_cluster_experimental) in texts)
        assertTrue(activity.getString(R.string.settings_car_bluetooth_audio) in texts)
        assertTrue(activity.getString(R.string.settings_carplay_auto_disconnect_bt) in texts)
        assertTrue(activity.getString(R.string.music_buffer) in texts)
        assertTrue(activity.getString(R.string.smooth_video) in texts)
        assertFalse(activity.getString(R.string.carplay_call_controls_experimental) in texts)
        assertTrue(activity.getString(R.string.app_name) + " " + activity.getString(R.string.settings) in texts)
        assertTrue(activity.getString(R.string.quick_back_to_home) in texts)
        assertTrue(activity.getString(R.string.done) in texts)
        val buttons = views(menu()).filterIsInstance<Button>().filterNot { it is android.widget.CompoundButton }.count()
        assertEquals(3, buttons)
    }

    @Test fun carTopBarShowsOverCarPlayWhileTheMenuIsOpen() {
        attachController()
        setField("hideTopBar", true)
        setField("hideBottomBar", false)
        invoke("applyFullscreenMode")
        val fullscreen = android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        assertTrue(activity.window.attributes.flags and fullscreen != 0)
        invoke("openSettingsMenu")
        assertEquals(0, activity.window.attributes.flags and fullscreen)
        @Suppress("DEPRECATION")
        val flags = activity.window.decorView.systemUiVisibility
        // The window still lays out behind the bar, so the CarPlay picture keeps its size.
        assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN != 0)
        assertEquals(0, flags and View.SYSTEM_UI_FLAG_FULLSCREEN)
        invoke("closeQuickMenu")
        assertTrue(activity.window.attributes.flags and fullscreen != 0)
    }

    @Test fun quickMenuChangeForTheNextSessionReconnectsOnDone() {
        attachController()
        invoke("openSettingsMenu")
        val before = AirPlayPersistence.loadClusterMapEnabled(activity)
        quickRow(R.string.carplay_map_on_instrument_cluster_experimental).performClick()
        assertEquals(!before, AirPlayPersistence.loadClusterMapEnabled(activity))
        assertTrue(views(menu()).filterIsInstance<Button>().any { it.text == activity.getString(R.string.save_and_reconnect) })
        invoke("closeQuickMenu")
        assertFalse(field("menuOpen") as Boolean)
        assertEquals(1, field("restartGeneration"))
    }

    @Test fun quickMenuClosedWithoutChangesDoesNotReconnect() {
        val controller = attachController()
        invoke("openSettingsMenu")
        invoke("closeQuickMenu")
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
    }

    @Test fun carBluetoothAudioAndNoAutoConnectExcludeEachOther() {
        AirPlayPersistence.saveCarBluetoothAudio(activity, false)
        AirPlayPersistence.saveCarplayAutoDisconnectBt(activity, true)
        attachController()
        invoke("openSettingsMenu")
        quickRow(R.string.settings_car_bluetooth_audio).performClick()
        assertTrue(AirPlayPersistence.loadCarBluetoothAudio(activity))
        assertFalse(AirPlayPersistence.loadCarplayAutoDisconnectBt(activity))
        assertFalse(quickSwitch(R.string.settings_carplay_auto_disconnect_bt).isChecked)

        quickRow(R.string.settings_carplay_auto_disconnect_bt).performClick()
        assertTrue(AirPlayPersistence.loadCarplayAutoDisconnectBt(activity))
        assertFalse(AirPlayPersistence.loadCarBluetoothAudio(activity))
        assertFalse(quickSwitch(R.string.settings_car_bluetooth_audio).isChecked)
    }

    @Test fun aSavedPairWithBothOnKeepsCarBluetoothAudio() {
        AirPlayPersistence.saveCarBluetoothAudio(activity, true)
        AirPlayPersistence.saveCarplayAutoDisconnectBt(activity, true)
        attachController()
        invoke("openSettingsMenu")
        assertTrue(quickSwitch(R.string.settings_car_bluetooth_audio).isChecked)
        assertFalse(quickSwitch(R.string.settings_carplay_auto_disconnect_bt).isChecked)
        assertFalse(AirPlayPersistence.loadCarplayAutoDisconnectBt(activity))
    }

    @Test fun musicBufferChoiceIsSavedAndReconnectsOnDone() {
        attachController()
        invoke("openSettingsMenu")
        views(menu()).filterIsInstance<TextView>()
            .first { it.isClickable && it.text == activity.getString(R.string.s_1000_ms_most_stable) }
            .performClick()
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(activity))
        invoke("closeQuickMenu")
        assertEquals(1, field("restartGeneration"))
    }


    @Test fun failedStartupWhileMenuIsOpenRecoversOnCancel() {
        attachController()
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("Authentication failed"))
        assertEquals(0, field("restartGeneration"))
        invoke("cancelSettingsEdits")
        assertEquals(1, field("restartGeneration"))
    }

    @Test fun transportLossWhileMenuIsOpenRecoversOnCancel() {
        attachController()
        invoke("openSettingsMenu")
        val listener = activity.javaClass.getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 0) as AirPlaySessionListener
        listener.onTransportError("transport lost")
        invoke("cancelSettingsEdits")
        assertEquals(1, field("restartGeneration"))
    }

    @Test fun wifiResetFailureRequiresManualRecoveryAfterCancel() {
        attachController()
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("Wi-Fi needs a reset", wifiResetRequired = true))
        invoke("cancelSettingsEdits")
        assertEquals(View.VISIBLE, (field("wifiRecoveryButton") as View).visibility)
        assertEquals(0, field("restartGeneration"))
        assertFalse(field("reconnectScheduled") as Boolean)
    }



    @Test fun staleFailureDoesNotRestartWhenTheMenuCloses() {
        val controller = attachController()
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("old failure"), generation = -1)
        invoke("cancelSettingsEdits")
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
    }




    @Test fun localRuntimeDoesNotRequestCh341Devices() {
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.LOCAL)
        invoke("loadPersistedSettings")
        val config = invoke("createRuntimeConfig") as CarPlayRuntimeConfig
        assertEquals(MfiTarget.LOCAL, config.mfiTarget)
        assertTrue(config.ch341Devices.isEmpty())
    }

    @Test fun ch341AttachmentKeepsTheWirelessSession() {
        val controller = attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("loadPersistedSettings")
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x1a86)
        `when`(device.productId).thenReturn(0x5512)
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, intent)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(activity))
        assertTrue(field("wirelessEnabled") as Boolean)
        assertSame(controller, field("controller"))
        assertFalse((field("shuttingDown") as AtomicBoolean).get())
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun iphoneAttachmentSwitchesToWiredTransportWithoutFinishing() {
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(null)
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("loadPersistedSettings")
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, intent)
        assertFalse(AirPlayPersistence.loadWirelessEnabled(activity))
        assertFalse(field("wirelessEnabled") as Boolean)
        assertTrue(field("vpnReady") as Boolean)
        assertFalse((field("shuttingDown") as AtomicBoolean).get())
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun switchingToUsbUserspaceDoesNotRequestUnneededSystemVpnConsent() {
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(Intent("test.VPN_CONSENT"))
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("loadPersistedSettings")
        setField("vpnReady", true) // Previously granted permission may have been revoked.
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        val method = activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }
        method.invoke(activity, intent)
        assertFalse(field("awaitingVpnConsent") as Boolean)
        assertTrue(field("vpnReady") as Boolean)
        assertNull(field("controller"))
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        method.invoke(activity, intent)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertFalse(activity.isFinishing)
    }

    @Test fun usbAttachmentClosesSettingsAndRestartsInPlace() {
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(null)
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("loadPersistedSettings")
        invoke("openSettingsMenu")
        assertTrue(field("menuOpen") as Boolean)
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }
            .invoke(activity, intent)
        assertFalse(field("menuOpen") as Boolean)
        assertFalse(field("wirelessEnabled") as Boolean)
        assertTrue(field("vpnReady") as Boolean)
        assertNull(field("controller"))
        assertEquals(1, field("restartGeneration"))
        assertFalse(activity.isFinishing)
    }

    @Test fun onlyIphoneAttachmentSelectsWiredTransport() {
        val method = activity.javaClass.getDeclaredMethod("isIphoneUsbAttachment", Intent::class.java)
            .apply { isAccessible = true }
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        val attached = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        assertEquals(true, method.invoke(activity, attached))
        assertEquals(false, method.invoke(activity, Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)))
        assertEquals(false, method.invoke(activity, Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, device)))
    }






    private fun resizeWindow(width: Int, height: Int) {
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(width, height)
        setField("activeDisplaySize", size)
    }

    private fun gesture(fingers: Int, x: Float = 400f, y: Float = 700f) {
        touch(MotionEvent.ACTION_DOWN, 1, 100f)
        for (count in 2..fingers) touch(MotionEvent.ACTION_POINTER_DOWN, count, 100f)
        touch(MotionEvent.ACTION_MOVE, fingers, y, x)
        touch(MotionEvent.ACTION_UP, 1, y, x)
    }

    private fun touch(action: Int, count: Int, y: Float, x: Float = 400f) {
        val pointers = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val positions = Array(count) { i -> MotionEvent.PointerCoords().apply { this.x = x + i * 50f; this.y = y; pressure = 1f; size = 1f } }
        val indexedAction = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            action or ((count - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val event = MotionEvent.obtain(0, 10, indexedAction, count, pointers, positions, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try {
            activity.javaClass.getDeclaredMethod("onHostTouch", View::class.java, MotionEvent::class.java)
                .apply { isAccessible = true }.invoke(activity, field("gestureOverlay"), event)
        } finally { event.recycle() }
    }

    private fun attachController(): CarPlayController = CarPlayController(
        activity, CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
        AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(1920, 990)),
        AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
    ).also { setField("controller", it) }

    @Suppress("UNCHECKED_CAST")
    private fun report(status: CarPlayStatus, generation: Int = 0) {
        val reporter = activity.javaClass.getDeclaredMethod("createStatusReporter", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, generation) as (CarPlayStatus) -> Unit
        reporter(status)
    }

    private fun menu() = field("settingsMenu") as View
    /** The clickable quick-menu row that holds [title]. */
    private fun quickRow(title: Int): View {
        val label = views(menu()).filterIsInstance<TextView>().first { it.text == activity.getString(title) }
        var view: View = label
        while (!view.isClickable) view = view.parent as View
        return view
    }
    private fun quickSwitch(title: Int): android.widget.Switch =
        views(quickRow(title)).filterIsInstance<android.widget.Switch>().single()
    private fun quickButton(text: String) = views(menu()).filterIsInstance<Button>().first { it.text == text }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    private fun invoke(name: String): Any? = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
    private fun field(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun setField(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
