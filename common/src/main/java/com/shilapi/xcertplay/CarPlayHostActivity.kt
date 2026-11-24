package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewTreeObserver
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import androidx.core.content.ContextCompat
import androidx.core.widget.CompoundButtonCompat
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.compat.realSdkInt
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.isManualHotspotChannelCompatible
import com.shilapi.xcertplay.settings.ConnectionSettingsSection
import com.shilapi.xcertplay.settings.DisplaySettingsSection
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.util.safeStackTrace
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.UsbDeviceId
import com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Full-screen CarPlay host. It renders decoded video through a [TextureView], forwards touch to
 * the active AirPlay session, and drives the complete wired or wireless bring-up through
 * [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private data class SettingsBaseline(
        val safeAreaRects: MutableMap<DisplaySize, SafeAreaRect?>,
        val customIconBytes: ByteArray?,
    )

    private var startupAutomation: CarPlayStartup? = null
    private var startupAutomationReady = false
    private var startupPrerequisitesRequested = false
    private var startupResumed = false
    private val secondScreen by lazy { CarPlaySecondScreen(this, ::dismissClusterPresentation) }
    private val guangqiClusterReady by lazy { GuangqiCluster.ReadyReporter(this, ::appendLog) }
    private val startupOverlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            startupAutomation?.onOverlayPermissionResult()
        }

    private var connectionPanel: View? = null
    private var wifiRecoveryButton: View? = null
    private var reconnectAttempts = 0
    /** Issues #275/#243: consecutive wired no-TCP failures on pre-Lollipop units. */
    private var wiredNoTcpFailures = 0
    /** Issue #305: consecutive wired USB-busy (EBUSY) failures. */
    private var wiredUsbBusyFailures = 0
    private var wiredUsbStallFailures = 0
    private val legacySiriPresses = mutableSetOf<Triple<Int, Int, Int>>()
    private val startupRetryBudget = WirelessStartupRetryBudget()
    // Issue #290: persistent consecutive wireless link-failure streak, restored
    // from prefs so it survives process restarts (the budget above does not).
    /** Wi-Fi Direct: counts down before the unit's own Wi-Fi client is disconnected. */
    private val stationWifiPrompt by lazy { StationWifiPromptDialog(this) }
    private val wirelessLinkFailurePrompt: WirelessLinkFailurePrompt by lazy {
        WirelessLinkFailurePrompt(
            AirPlayPersistence.loadWirelessLinkFailures(this),
            AirPlayPersistence.loadWirelessRepairPromptShown(this),
        )
    }
    private var startupRetryStopped = false

    /**
     * Releases the automatic-retry stop (issues #359/#275/#305): the wired
     * failure counters restart and the retry button hides. Only deliberate
     * or physical events call this — the Retry button, a fresh USB attach
     * (the replug the stop messages ask for), a transport switch, or a
     * session actually activating. Status reports and waiting states must
     * never release it implicitly: issue #359 showed the stop being walked
     * around because restarts reached the stack without ever consulting
     * the latch.
     */
    private fun clearStartupRetryStop() {
        startupRetryStopped = false
        wiredNoTcpFailures = 0
        wiredUsbBusyFailures = 0
        wiredUsbStallFailures = 0
        startupRetryButton?.visibility = View.GONE
    }
    private var startupRetryButton: View? = null
    private var startupFailureGeneration = -1
    private lateinit var airPlayIdentity: AirPlayIdentity
    private var languagePreferenceAtCreate = AppLocale.DEFAULT

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = "DiPlay",
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "DIPLAY-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", ""),
            firmwareVersion = "0.1.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
            // Guangqi reads the speed from its own vehicle service, so no switch or ADB is needed.
            vehicleSpeedEnabled = locationReportingEnabled && (GuangqiCluster.present(this) ||
                com.shilapi.xcertplay.hud.WheelIntegrationSettings.wheelSpeedToIphone(this)),
        ),
        label = "DiPlay",
        hostName = "diplay-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", "").lowercase(),
        hostMac = DiPlayBootstrap.deviceId(airPlayIdentity).split(":").map { it.toInt(16).toByte() }.toByteArray(),
        wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(this),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        wifiP2pPreferredChannel = AirPlayPersistence.loadWifiP2pPreferredChannel(this),
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        existingWifiSsid = existingWifiSsid,
        existingWifiPassphrase = existingWifiPassphrase,
        hostapdSsid = hostapdSsid,
        hostapdPassphrase = hostapdPassphrase,
        hostapdChannel = hostapdChannel,
        hostapdAppleVendorIe = hostapdAppleVendorIe,
        carplayAutoDisconnectBt = carplayAutoDisconnectBt,
        locationReportingEnabled = locationReportingEnabled,
        directUsbfsMode = AirPlayPersistence.loadDirectUsbfsMode(this),
        userspaceNetworkEnabled = !AirPlayPersistence.loadWiredKernelTun(this),
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingVpnConsent = false
            if (result.resultCode == RESULT_OK) {
                vpnConsentInstantDenyCount = 0
                vpnReady = true
                maybeStartCarPlay()
            } else {
                // Issue #188 follow-up: on stripped API-17 ROMs the system VPN consent
                // activity can finish with CANCELED instantly — no user interaction is
                // possible, so this is a broken system dialog, not a user denial. Two
                // instant denials in a row mean the ROM can never grant consent: stop
                // the deny/retry loop and take the direct wired attempt, same as the
                // missing-dialog fallback. A genuine user denial takes longer than
                // VPN_CONSENT_MIN_HUMAN_MILLIS and keeps the retry dialog.
                val elapsed = SystemClock.elapsedRealtime() - vpnConsentLaunchElapsed
                vpnConsentInstantDenyCount =
                    if (elapsed < VPN_CONSENT_MIN_HUMAN_MILLIS) vpnConsentInstantDenyCount + 1 else 0
                if (vpnConsentInstantDenyCount >= VPN_CONSENT_INSTANT_DENY_LIMIT) {
                    vpnConsentInstantDenyCount = 0
                    vpnDeniedDialog?.dismiss()
                    vpnDeniedDialog = null
                    Log.w(TAG, "VPN consent dialog closed instantly twice; treating as broken system dialog")
                    appendLog("VPN consent dialog keeps closing instantly; attempting direct wired connection")
                    vpnConsentUnavailable = true
                    vpnReady = true
                    runCatching { maybeStartCarPlay() }
                        .onFailure { startError ->
                            appendLog("Wired fallback start failed ${startError.javaClass.simpleName}: ${startError.message}")
                        }
                    setStatus(getString(R.string.vpn_consent_launch_failed))
                } else {
                    setStatus(getString(R.string.vpn_consent_was_denied))
                    showVpnDeniedRetryDialog()
                }
            }
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            val connectState = if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) "granted" else "denied"
            val scanState = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) "n/a" else if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) "granted" else "denied"
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted (connect=$connectState scan=$scanState)"
                } else {
                    "Wireless startup permissions denied (connect=$connectState scan=$scanState)"
                },
            )
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            requestStartupPrerequisites()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                if (!menuOpen) {
                    AirPlayPersistence.saveLocationReportingEnabled(
                        this@CarPlayHostActivity,
                        false,
                    )
                }
                locationReportingSwitch?.isChecked = false
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            updateResolutionMenu()
            if (!menuOpen) requestStartupPrerequisites()
        }

    // Issues #300/#302/#307: listen for reverse camera exit broadcasts sent by head unit ROMs
    // (Yecon, Mstar, TS, SYU, etc.) to immediately refresh video and reapply layout.
    // Issue #315 adds the enter half for the legacy cold-start strategy.
    private val reverseCameraReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                "com.yecon.action.BACKCAR_STOP",
                "android.intent.action.BACK_CAR_STOP",
                "com.mstar.backcar.stop",
                "android.intent.action.BACKCAR_OFF",
                "com.autochips.backcar.stop",
                "xy.android.intent.action.BACKCAR_OFF",
                "com.xy.backcar.stop",
                "action.backcar.stop" -> {
                    handleReverseCameraExit(action)
                }
                "com.yecon.action.BACKCAR_START",
                "android.intent.action.BACK_CAR_START",
                "com.mstar.backcar.start",
                "android.intent.action.BACKCAR_ON",
                "com.autochips.backcar.start",
                "xy.android.intent.action.BACKCAR_ON",
                "com.xy.backcar.start",
                "action.backcar.start" -> {
                    handleReverseCameraEnter(action)
                }
                "com.syu.canbus.action.BACKCAR",
                "com.android.internal.car.ACTION_CAMERA_STATUS",
                "com.ts.car.backcar" -> {
                    val state = intent.getIntExtra("state", -1).takeIf { it != -1 }
                        ?: intent.getIntExtra("status", -1).takeIf { it != -1 }
                        ?: intent.getIntExtra("camerastatus", -1)
                    if (state == 0) {
                        handleReverseCameraExit("$action(state=$state)")
                    } else if (state == 1) {
                        // State-carrying broadcasts use 1 for camera-on by the
                        // same convention that reads 0 as camera-off above.
                        handleReverseCameraEnter("$action(state=$state)")
                    }
                }
            }
        }
    }

    /** Issue #315: true between a legacy reverse-enter and its delayed cold start. */
    private var legacyReverseActive = false

    /**
     * True from any reverse-enter broadcast until the post-exit restore has
     * run or stood down. Tracked independently of the #315 cold-start
     * setting: the camera owning the screen is a fact about the display,
     * not about which recovery strategy is enabled, and the focus-gain
     * side effects below key off it.
     */
    private var reverseCameraActive = false
    private var reverseCameraActiveSinceMs = 0L
    private var reverseExitRestoreAttempts = 0
    private var legacyReverseResumeAttempts = 0

    /** Increments on every focus loss; delayed fullscreen re-asserts capture
     * it so a loss-gain flap cannot leave flag churn running under an
     * overlay that has already taken the screen back. */
    private var windowFocusGeneration = 0L

    /**
     * Post-exit picture restore. An exit broadcast is a claim, not a proof:
     * CAN status broadcasts flap (a momentary camera-off state in the
     * middle of a manoeuvre), and rebuilding the video surface the instant
     * one arrives lets the returning CarPlay picture displace a reverse
     * camera that is still up — which then does not come back until the
     * next gear change, leaving CarPlay on screen for the rest of the
     * manoeuvre. So the restore waits briefly, runs only while this window
     * actually holds focus (the camera owns focus while it is up),
     * re-checks a few times, and is cancelled outright if reverse
     * re-engages. When it stands down unfocused, the focus-gain refresh
     * remains as the fallback for the genuine exit.
     */
    private val reverseExitRestoreRunnable: Runnable = Runnable {
        if (isDestroyed || isFinishing || activeAirPlaySession == null) {
            reverseCameraActive = false
            return@Runnable
        }
        if (!hasWindowFocus()) {
            if (++reverseExitRestoreAttempts <= REVERSE_EXIT_RESTORE_MAX_ATTEMPTS) {
                mainHandler.postDelayed(reverseExitRestoreRunnable, REVERSE_EXIT_RESTORE_RECHECK_MILLIS)
            } else {
                reverseCameraActive = false
            }
            return@Runnable
        }
        reverseCameraActive = false
        fallbackVideoView?.visibility = View.VISIBLE
        sink?.refreshPicture(SCREEN_TYPE_MAIN)
        reapplyVideoLayout()
        fallbackVideoView?.invalidate()
        mainHandler.postDelayed({
            if (activeAirPlaySession != null && !isDestroyed && !isFinishing && hasWindowFocus()) {
                sink?.refreshPicture(SCREEN_TYPE_MAIN)
            }
        }, 300)
    }

    /** Issue #315: delayed cold start after reverse exit; cancelled if reverse re-engages. */
    private val legacyReverseResumeRunnable: Runnable = Runnable {
        if (activeAirPlaySession == null || isDestroyed || isFinishing) {
            reverseCameraActive = false
            return@Runnable
        }
        // Same discipline as the post-exit restore: the delayed cold start
        // must not rebuild the video while the camera still owns the
        // screen (a flapped exit broadcast schedules it just the same);
        // re-check briefly, then stand down to the focus-gain fallback.
        if (!hasWindowFocus()) {
            if (++legacyReverseResumeAttempts <= LEGACY_REVERSE_RESUME_MAX_ATTEMPTS) {
                mainHandler.postDelayed(legacyReverseResumeRunnable, LEGACY_REVERSE_RESUME_RECHECK_MILLIS)
            } else {
                reverseCameraActive = false
            }
            return@Runnable
        }
        reverseCameraActive = false
        fallbackVideoView?.visibility = View.VISIBLE
        sink?.resumeVideoFromReverse(SCREEN_TYPE_MAIN)
        reapplyVideoLayout()
        fallbackVideoView?.invalidate()
    }

    private fun handleReverseCameraEnter(source: String) {
        // A (re-)engagement cancels any post-exit restore or cold start
        // still waiting to run, whatever recovery strategy is enabled.
        mainHandler.removeCallbacks(reverseExitRestoreRunnable)
        mainHandler.removeCallbacks(legacyReverseResumeRunnable)
        reverseExitRestoreAttempts = 0
        legacyReverseResumeAttempts = 0
        if (!reverseCameraActive) {
            reverseCameraActive = true
            reverseCameraActiveSinceMs = SystemClock.elapsedRealtime()
            appendLog("Display: reverse camera started by broadcast source=$source")
        }
        if (!AirPlayPersistence.loadLegacyReverseColdStart(this)) return
        if (activeAirPlaySession == null || isDestroyed || isFinishing) return
        if (legacyReverseActive) return
        legacyReverseActive = true
        appendLog("Display: reverse camera started by broadcast source=$source; legacy cold start: decoder released")
        sink?.suspendVideoForReverse(SCREEN_TYPE_MAIN)
        // Issue #315: on legacy chips/head units (e.g. PF8227/MTK 8227L), active SurfaceView hardware overlay
        // collides with the system reverse camera and dynamic trajectory layer in the GPU/hardware composer.
        // Hide the SurfaceView overlay during reverse so the car camera gets dedicated layer ownership.
        fallbackVideoView?.visibility = View.INVISIBLE
    }

    private fun handleReverseCameraExit(source: String) {
        appendLog("Display: reverse camera stopped by broadcast source=$source")
        if (activeAirPlaySession == null || isDestroyed || isFinishing) return
        if (AirPlayPersistence.loadLegacyReverseColdStart(this) && legacyReverseActive) {
            legacyReverseActive = false
            legacyReverseResumeAttempts = 0
            mainHandler.removeCallbacks(legacyReverseResumeRunnable)
            // Issue #315: on PF8227/MTK 8227L the hardware video channel
            // races the system reverse layer if the decoder comes back
            // immediately; wait for the system UI and Surface to settle,
            // then cold-start the decoder and request a fresh keyframe.
            appendLog("Display: legacy cold start: decoder restart delayed ${LEGACY_REVERSE_COLD_START_DELAY_MILLIS}ms after reverse exit")
            mainHandler.postDelayed(legacyReverseResumeRunnable, LEGACY_REVERSE_COLD_START_DELAY_MILLIS)
            return
        }
        // Not the immediate rebuild this used to be: see the restore
        // runnable's note. The exit claim is debounced and focus-confirmed
        // so a flapped broadcast cannot displace a camera that is still up.
        reverseExitRestoreAttempts = 0
        mainHandler.removeCallbacks(reverseExitRestoreRunnable)
        mainHandler.postDelayed(reverseExitRestoreRunnable, REVERSE_EXIT_RESTORE_DELAY_MILLIS)
    }

    // The full-window viewport stays independent of the letterboxed SurfaceView dimensions.
    private var videoView: View? = null
    private var mainPictureVisible = false
    private var mainPictureGeneration = 0
    private var fallbackVideoView: SurfaceView? = null
    private var fallbackVideoBounds: CarPlaySurfaceBounds? = null
    /** Issue #290: size established by the latest grow re-add, with its timestamp. */
    private var surfaceReaddBounds: CarPlaySurfaceBounds? = null
    // Issue #363: the view instance the shrink-guard baseline belongs to. A
    // different instance means a newly created surface, whose first
    // established size must arm the baseline (see updateVideoLayout).
    private var surfaceBaselineView: SurfaceView? = null
    private var surfaceReaddAtMs: Long = 0L

    // Smooth video (a setting): SurfaceView output with frames released at the iPhone's frame time.
    private var smoothVideo = false
    // Direct SurfaceView output: hardware overlay without TextureView composition overhead.
    private var directSurfaceView = false
    // Sinks whose sessions are being torn down; their decoders may still render to the current surface
    // until they have released their codecs, so a destroyed surface is detached from them too. A restart
    // and a shutdown can overlap, so this is a set.
    private val retiringSinks = java.util.concurrent.CopyOnWriteArraySet<AndroidMediaSink>()
    internal var sinkReleaseWaitMillis = SINK_RELEASE_WAIT_MILLIS
    private var videoSurfaceProbe: ViewTreeObserver.OnPreDrawListener? = null
    private var pictureBinding: CarPlayPicture.Binding? = null
    private var picturePanel: View? = null
    private var picturePanelGeneration = 0
    private var gestureOverlay: View? = null
    private var settingsMenu: View? = null
    private var mfiTargetGroup: RadioGroup? = null
    private var mfiI2cFields: View? = null
    private var mfiRemoteFields: View? = null
    private var mfiErrorView: TextView? = null
    private var mfiI2cPathInput: EditText? = null
    private var remoteMfiServerInput: EditText? = null
    private var remoteMfiTokenInput: EditText? = null
    private var settingsBaseline: SettingsBaseline? = null
    private var locationReportingSwitch: Switch? = null
    private var statusView: TextView? = null
    private var statusScrollView: ScrollView? = null
    private var stageStatusView: TextView? = null
    private var stageDiagnosticView: TextView? = null
    private var stageStartedAtMillis = 0L
    private var stageWatchdogGeneration = 0
    private var resolutionValueView: TextView? = null
    private var resolutionPreviewView: TextView? = null
    private var hotspotStatusView: TextView? = null
    private var manualHotspotFields: View? = null
    private var hostapdHotspotFields: View? = null
    private var hostapdErrorView: TextView? = null
    private var existingWifiFields: View? = null
    private var existingWifiErrorView: TextView? = null
    private var manualHotspotErrorView: TextView? = null
    private var iconPreviewView: ImageView? = null
    private var iconStatusView: TextView? = null
    private var safeAreaSummaryView: TextView? = null
    private var safeAreaEditor: View? = null
    private var safeAreaEditorView: SafeAreaEditorView? = null
    private var safeAreaEditSize: DisplaySize? = null
    private var safeAreaEditorActive = false
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private val videoSurfaceOwner = CarPlayVideoSurfaceOwner<Surface>(
        detach = { surface ->
            sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
            sink?.clearSurface(SCREEN_TYPE_ALT, surface)
        },
        release = { it.release() },
    )
    private val currentSurface: Surface? get() = videoSurfaceOwner.current
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var clusterPresentation: ClusterMapPresentation? = null
    private var clusterSurface: Surface? = null
    private var clusterTurnGuidance: com.shilapi.xcertplay.hud.ClusterTurnGuidance? = null
    private val clusterTurnOverlayListener: (com.shilapi.xcertplay.hud.ClusterTurnGuidance?) -> Unit = { guidance ->
        runOnUiThread {
            clusterTurnGuidance = guidance
            applyClusterTurnOverlay()
        }
    }
    // Copies of stream 111 outside the dashboard (centre card, launcher maps) each get their own decoder.
    private val mirrorSink: (String, Surface?) -> Unit = { key, surface -> sink?.setMirrorSurface(SCREEN_TYPE_ALT, key, surface) }
    private val mirrorsChanged: () -> Unit = {
        updateClusterMapShown()
        if (MapMirrors.launcherShowsMap) CenterMapOverlay.hide()
    }
    // With Usage Access the card shows only over a home screen; null = not known (no monitor).
    private var homeMonitor: HomeScreenMonitor? = null
    private var homeScreenVisible: Boolean? = null
    private var isActivityStarted = false
    private val hideIdleCenterMap = Runnable {
        if (SCREEN_TYPE_ALT !in activeScreenStreamTypes) CenterMapOverlay.hide()
    }
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var sessionDisplay: CarPlaySessionDisplay? = null
    private var touchOutsideContent = false
    private var displayScalePercent = 100
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var uiScalePercent = CarPlayUiScale.DEFAULT
    private var displayDiagnosticAttempt: String? = null
    private var hevcEnabled = true
    private var hevcSoftwareDecoderEnabled = false
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    private var navigationStreamType = android.media.AudioManager.STREAM_MUSIC
    private var debugLogsEnabled = false
    private var autoStartOnBoot = false
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var oemLabel = AirPlayPersistence.DEFAULT_OEM_LABEL
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var carPlayDock = CarPlayDock.AUTOMATIC
    private var dockAutoHideEnabled = false
    private var dockRevealed = false
    private var dockEdgeSwipeActive = false
    private var dockEdgeStartX = 0f
    private var dockEdgeStartY = 0f
    private var dockCloseSwipeActive = false
    private var dockCloseStartX = 0f
    private var dockCloseStartY = 0f
    private var dockGestureIntercepted = false
    private val hideDockRunnable = Runnable {
        if (dockRevealed) {
            dockRevealed = false
            videoView?.let { updateVideoLayout(it.width, it.height) }
        }
    }
    /** The view areas the next session declares (see createAirPlayConfig). */
    private var pendingViewAreas: CarPlayViewAreas? = null
    /** The dock the running session declared; null before the first connection. */
    private var sessionDock: CarPlayDock? = null
    private var hideTopBar = true
    private var hideBottomBar = true
    private var safeAreaDrawOutside = true
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    private var wirelessEnabled = false
    private var mfiTarget = MfiTarget.LOCAL
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var manualHotspotSsid = ""
    private var existingWifiSsid = ""
    private var existingWifiPassphrase = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var hostapdSsid = "DiPlay"
    private var hostapdPassphrase = ""
    private var hostapdChannel = 1
    private var hostapdAppleVendorIe = false
    private var carplayAutoDisconnectBt = false
    private var telephonyDownlinkViaMusic = false
    private var announcementDuckMedia = false
    private var dockBarRefrontEnabled = false
    private var dockRefrontAttempts = 0
    private var dockRefrontLastAttemptMs = 0L
    private var awaitingVpnConsent = false
    private var vpnConsentLaunchElapsed = 0L
    private var vpnConsentInstantDenyCount = 0
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var vpnReady = false
    /**
     * Issue #109: set when the system VPN consent dialog cannot be shown
     * (com.android.vpndialogs missing on stripped head-unit ROMs). Without consent,
     * VpnService.establish() always fails with "Cannot create interface" — proceeding
     * anyway only produces an infinite retry loop with a cryptic error.
     */
    private var vpnConsentUnavailable = false
    private var vpnDeniedDialog: android.app.AlertDialog? = null
    private var hotspotStatus = HotspotStatus(state = "off")
    private var menuOpen = false
    /** A quick-menu change that only applies to a new session; Done then reconnects. */
    private var quickMenuNeedsReconnect = false
    private var quickMenuDone: Button? = null
    /** The quick menu, not the full one, is on screen; only it shows the car's top bar. */
    private var quickMenuShowing = false
    private var latestStage = "Preparing CarPlay"
    private var darkMode = false
    private var paintWaitingScreen: () -> Unit = {}
    private var carPlayNightMode = CarPlayNightMode.SYSTEM
    private var nightSchedule = CarPlayNightSchedule()
    private var ambientLightThreshold = AmbientLightThreshold()
    private var ambientDelaySeconds = 2
    private var nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
    private val nightModeController by lazy {
        CarPlayNightModeController(
            light = AndroidAmbientLight(this),
            scheduler = MainThreadNightModeScheduler(),
            initialNight = darkMode,
            onNightChanged = { night ->
                darkMode = night
                paintWaitingScreen()
                applyClusterTurnOverlay()
                appendLog("CarPlay switched to ${if (night) "night" else "day"} mode")
                logThemeState(nightModeDiagnosticSource, resources.configuration)
                syncAirPlayDarkMode(nightModeDiagnosticSource)
            },
        )
    }
    private val themeDiagnostics = ThemeModeDiagnostics()
    private var lastConfiguration: Configuration? = null
    private var activeAirPlaySession: AirPlaySession? = null
    private val activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var startAfterHandshakeReset = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private var sessionLog: SessionLogFile? = null
    private var gestureFingerCount = 3
    private var settingsGestureHint: TextView? = null
    private var gestureSequenceActive = false
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var recoveryPendingAfterMenu = false
    private var failurePendingAfterMenu: CarPlayStatus.Failed? = null
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sidePanel: LinearLayout? = null
    private var sidePanelBattery: TextView? = null
    private var sidePanelShown = false
    private val sidePanelTick = object : Runnable {
        override fun run() {
            refreshSidePanel()
            if (sidePanelShown) mainHandler.postDelayed(this, SIDE_PANEL_REFRESH_MILLIS)
        }
    }
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<LogEntry>()
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    private val refreshTurnOverlay = object : Runnable {
        override fun run() {
            com.shilapi.xcertplay.hud.NavigationOutputs.refreshTurnOverlay()
            mainHandler.postDelayed(this, 1_000L)
        }
    }
    // Some head units (e.g. BYD DiLink) update resources.configuration for day/night
    // without delivering onConfigurationChanged, so poll while the activity is visible.
    private val pollConfiguration = object : Runnable {
        override fun run() {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.POLL)
            mainHandler.postDelayed(this, CONFIGURATION_POLL_INTERVAL_MILLIS)
        }
    }
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = currentSurface
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    videoSurfaceOwner.replace(it, releaseOnDetach = true)
                    currentSurfaceTexture = texture
                }
            }
            appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
            attachSurface(surface)
            // Issue #290: a surface rebuild can land while the view is still at an
            // intermediate size; the two-stage reapply (immediate + posted/settle)
            // is the only pass guaranteed to run again once the size has settled.
            reapplyVideoLayout()
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            reapplyVideoLayout()
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) return true
            invalidateMainPicture()
            currentSurface?.let(videoSurfaceOwner::clear)
            currentSurfaceTexture = null
            appendLog("Texture surface destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            if (currentSurfaceTexture === texture && SCREEN_TYPE_MAIN in activeScreenStreamTypes && isActivityStarted) {
                mainPictureVisible = true
                updateDebugOverlays()
            }
        }
    }

    private val fallbackSurfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (isDestroyed || holder !== fallbackVideoView?.holder) return
            val surface = holder.surface
            videoSurfaceOwner.replace(surface, releaseOnDetach = false)
            appendLog("SurfaceView video surface created valid=${surface.isValid}")
            attachSurface(surface)
            // Issue #290: same two-stage reapply as the TextureView path — a rebuild
            // with no following display-size change otherwise never refits.
            reapplyVideoLayout()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (holder !== fallbackVideoView?.holder) return
            // Holder dimensions describe the fitted video, not the host window/CarPlay canvas.
            reapplyVideoLayout()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (holder.surface === currentSurface) invalidateMainPicture()
            val surface = holder.surface
            // The SurfaceHolder contract: nothing may render to the surface once this returns. Every decoder
            // is asked at once, then waited for against one deadline; each confirms once it has moved,
            // parked or released its codec, and closing decoders count once their workers exit. With
            // smooth video the live session's main decoder parks off screen and keeps its state.
            val live = sink
            val detaches = (listOfNotNull(live) + retiringSinks).distinct()
                .map { owner -> owner.beginSurfaceDetach(surface, parkMain = smoothVideo && owner === live) }
            val confirmed = detaches.map { it.await() }.all { it }
            videoSurfaceOwner.clear(surface)
            appendLog("SurfaceView video surface destroyed detachConfirmed=$confirmed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        NavigationWidgetUpdater.attach(applicationContext)
        // USB-Bluetooth dongle: route Numeric Comparison pairing requests to
        // this Activity's dialog (no-op when no dongle stack is present).
        runCatching {
            UsbBtPairingUiRouter.installOn(
                com.shilapi.xcertplay.bluetooth.CarPlayBluetoothManager.get(this),
            )
        }
        CenterMapOverlay.requestShow = ::showCenterMap
        FloatingDotOverlay.restoreIfEnabled(applicationContext)
        MapMirrors.sink = mirrorSink
        MapMirrors.onChanged = mirrorsChanged
        languagePreferenceAtCreate = AppLocale.preference(this)
        if (isIphoneUsbAttachment(intent)) {
            AirPlayPersistence.saveWirelessEnabled(this, false)
        }
        if (runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.isFailure) {
            startActivity(Intent(this, DiPlayActivity::class.java))
            finish(); return
        }
        DiPlayBootstrap.restoreUsbBluetooth(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val displayManager = if (realSdkInt() >= Build.VERSION_CODES.M) {
                getSystemService(android.hardware.display.DisplayManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            }
            displayManager?.registerDisplayListener(clusterDisplayListener, mainHandler)
        }
        initializeSessionLog()
        installCrashDiagnostics()
        val reverseFilter = IntentFilter().apply {
            addAction("com.yecon.action.BACKCAR_STOP")
            addAction("android.intent.action.BACK_CAR_STOP")
            addAction("com.mstar.backcar.stop")
            addAction("android.intent.action.BACKCAR_OFF")
            addAction("com.autochips.backcar.stop")
            addAction("xy.android.intent.action.BACKCAR_OFF")
            addAction("com.xy.backcar.stop")
            addAction("action.backcar.stop")
            // Issue #315: legacy cold start also needs the enter half, so the
            // codec can be released before the reverse layer takes over.
            addAction("com.yecon.action.BACKCAR_START")
            addAction("android.intent.action.BACK_CAR_START")
            addAction("com.mstar.backcar.start")
            addAction("android.intent.action.BACKCAR_ON")
            addAction("com.autochips.backcar.start")
            addAction("xy.android.intent.action.BACKCAR_ON")
            addAction("com.xy.backcar.start")
            addAction("action.backcar.start")
            addAction("com.syu.canbus.action.BACKCAR")
            addAction("com.android.internal.car.ACTION_CAMERA_STATUS")
            addAction("com.ts.car.backcar")
        }
        runCatching { registerReceiver(reverseCameraReceiver, reverseFilter) }
        lastConfiguration = Configuration(resources.configuration)
        darkMode = savedInstanceState?.getBoolean("carplay_night_active")
            ?: nightModeOrNull(resources.configuration.uiMode) ?: false
        logThemeState(ThemeModeDiagnostics.Source.CREATE, resources.configuration)
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        setContentView(buildContentView())
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (picturePanel != null) {
                        closePicturePanel()
                    } else if (menuOpen) {
                        if (safeAreaEditorActive) closeSafeAreaEditor() else cancelSettingsEdits()
                    } else {
                        showDiPlayHome()
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            startupAutomationReady = true
            startupPrerequisitesRequested = true
            updateDebugOverlays()
        } else {
            startupAutomation = CarPlayStartup(this, { startupOverlayPermission.launch(it) }) {
                startupAutomationReady = true
                continueAutomaticStartup()
            }.also { it.start() }
        }
    }

    private fun continueAutomaticStartup() {
        if (!startupAutomationReady || !startupResumed || startupPrerequisitesRequested || shuttingDown.get()) return
        startupPrerequisitesRequested = true
        if (microphonePermissionResolved) requestStartupPrerequisites()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun loadPersistedSettings() {
        carPlayDock = CarPlayDock.load(this)
        dockAutoHideEnabled = AirPlayPersistence.loadDockAutoHide(this)
        carPlayNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        ambientLightThreshold = AirPlayPersistence.loadAmbientLightThreshold(this)
        ambientDelaySeconds = AirPlayPersistence.loadAmbientDelaySeconds(this)
        nightSchedule = AirPlayPersistence.loadCarPlayNightSchedule(this)
        nightModeController.configure(
            carPlayNightMode,
            nightModeOrNull(resources.configuration.uiMode) ?: false,
            ambientLightThreshold,
            ambientDelaySeconds,
            nightSchedule,
        )
        darkMode = nightModeController.night
        gestureFingerCount = AirPlayPersistence.loadSettingsGestureFingers(this)
        displayScalePercent = AirPlayPersistence.loadDisplayScalePercent(this)
        displayScaleTenths = CarPlayDisplayScale.sanitize((displayScalePercent + 5) / 10)
        // Size is now chosen only through CarPlaySize; ignore the canvas scale older builds stored.
        uiScalePercent = CarPlayUiScale.DEFAULT
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        navigationStreamType = AirPlayPersistence.loadNavigationStreamType(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        locationPermissionAvailable = hasFineLocationPermission()
        loadConnectionSettings()
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    /**
     * Connection settings owned by the settings screen ([DiPlayActivity]). It saves them in this
     * process while this screen keeps running, so they are re-read on every resume instead of
     * relying on the snapshot [onCreate] took: otherwise the next handshake would keep using the
     * mode, hotspot and MFI settings that were current when this screen was first opened.
     */
    private fun loadConnectionSettings() {
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(this)
        existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
        hostapdSsid = AirPlayPersistence.loadHostapdSsid(this)
        hostapdPassphrase = AirPlayPersistence.loadHostapdPassphrase(this)
        hostapdChannel = AirPlayPersistence.loadHostapdChannel(this)
        hostapdAppleVendorIe = AirPlayPersistence.loadHostapdAppleVendorIe(this)
        // The hold disconnects the phone from the car's Bluetooth. Not when sound is meant
        // to stay on that connection.
        carplayAutoDisconnectBt = AirPlayPersistence.loadCarplayAutoDisconnectBt(this) &&
            !AirPlayPersistence.loadCarBluetoothAudio(this)
        telephonyDownlinkViaMusic = AirPlayPersistence.loadTelephonyDownlinkViaMusic(this)
        announcementDuckMedia = AirPlayPersistence.loadAnnouncementDuckMedia(this)
        dockBarRefrontEnabled = AirPlayPersistence.loadDockBarRefront(this)
    }

    private fun requestStartupPrerequisites() {
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            requestVpnConsent()
        }
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestVpnConsent() {
        if (awaitingVpnConsent) return
        // The userspace NCM path creates no system VPN. Do not ask for an
        // unrelated, reboot-scoped KitKat VPN grant before starting it.
        if (createRuntimeConfig().userspaceNetworkEnabled) {
            vpnReady = true
            appendLog("Wired userspace network selected; system VPN consent is not required")
            maybeStartCarPlay()
            return
        }
        // Guangqi: the ROM never initialises its VPN table and has no consent dialog; repair
        // both as the system uid before the standard prepare.
        com.shilapi.xcertplay.network.GuangqiVpnSubsystem.prepare(this) { appendLog(it) }
        val consent = try {
            CarPlayVpnService.prepare(this)
        } catch (e: Exception) {
            Log.w(TAG, "CarPlayVpnService.prepare failed", e)
            null
        }
        if (consent == null) {
            vpnReady = true
            maybeStartCarPlay()
        } else {
            vpnReady = false
            awaitingVpnConsent = true
            vpnConsentLaunchElapsed = SystemClock.elapsedRealtime()
            try {
                vpnConsent.launch(consent)
            } catch (e: ActivityNotFoundException) {
                // Issue #109: the VPN consent dialog is missing on this ROM.
                // Ported from jiuyueji/diplay_changan@148eeb9: a ROM that drops the
                // dialog usually does not enforce consent either, so attempt the wired
                // path directly instead of giving up. Any rejection surfaces as
                // AttachResult.Failed, never a crash or infinite retry loop.
                Log.w(TAG, "VPN confirmation dialog not found on device (com.android.vpndialogs missing)", e)
                appendLog("VPN dialog unavailable (com.android.vpndialogs missing); attempting direct wired connection")
                awaitingVpnConsent = false
                vpnConsentUnavailable = true
                vpnReady = true
                runCatching { maybeStartCarPlay() }
                    .onFailure { startError ->
                        appendLog("Wired fallback start failed ${startError.javaClass.simpleName}: ${startError.message}")
                    }
                setStatus(getString(R.string.vpn_consent_launch_failed))
            } catch (e: SecurityException) {
                Log.w(TAG, "VPN confirmation dialog launch permission denied", e)
                appendLog("VPN dialog launch denied; VPN consent cannot be granted on this head unit")
                awaitingVpnConsent = false
                vpnConsentUnavailable = true
                setStatus(getString(R.string.vpn_consent_unavailable))
            }
        }
    }

    // Issue #188: denying VPN consent left the wired session dead with no way back
    // except killing the app. Offer a retry that re-runs the system consent dialog.
    private fun showVpnDeniedRetryDialog() {
        if (shuttingDown.get() || isFinishing || isDestroyed) return
        vpnDeniedDialog?.dismiss()
        vpnDeniedDialog = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.vpn_consent_was_denied))
            .setMessage(getString(R.string.vpn_consent_denied_retry_hint))
            .setPositiveButton(getString(R.string.vpn_consent_retry)) { _, _ -> requestVpnConsent() }
            .setNegativeButton(getString(R.string.cancel), null)
            .setOnDismissListener { vpnDeniedDialog = null }
            .show()
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> {
        val needsWifiRuntimePermission = wirelessHotspotMode != WirelessHotspotMode.MANUAL &&
            wirelessHotspotMode != WirelessHotspotMode.EXISTING_WIFI
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> buildList {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                if (needsWifiRuntimePermission) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> buildList {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                if (needsWifiRuntimePermission) {
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
            realSdkInt() >= Build.VERSION_CODES.M && needsWifiRuntimePermission -> listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            else -> emptyList()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isIphoneUsbAttachment(intent)) {
            // A fresh attach is the physical replug the stop messages ask
            // for (issue #359): the stopped episode is over.
            clearStartupRetryStop()
            if (wirelessEnabled) {
                if (menuOpen) cancelSettingsEdits()
                AirPlayPersistence.saveWirelessEnabled(this, false)
                wirelessEnabled = false
                wirelessPermissionsReady = true
                if (controller != null) {
                    restartCarPlay("switching to USB")
                }
                requestStartupPrerequisites()
            } else if (controller == null) {
                requestStartupPrerequisites()
            }
        }
    }

    private fun isIphoneUsbAttachment(intent: Intent): Boolean {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        val device = androidx.core.content.IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        return device?.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
        stationWifiPrompt.attach()
        logThemeState(ThemeModeDiagnostics.Source.START, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        mainHandler.post(pollConfiguration)
        CenterMapOverlay.onDiPlayScreenShown()
        homeMonitor?.stop()
        homeScreenVisible = null
        if (activeAirPlaySession != null) setConnectionStage(getString(R.string.opening_carplay), exact = true)
        currentSurface?.let(::attachSurface)
    }

    override fun onResume() {
        super.onResume()
        startupResumed = true
        startupAutomation?.onResume()
        continueAutomaticStartup()
        UsbBtPairingUiRouter.register(this)
        if (CarPlayBackgroundSession.hasSession()) {
            appendLog("Activity resumed while CarPlay controller is retained")
        }
        DiPlayBootstrap.restoreUsbBluetooth(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        val savedNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        val savedThreshold = AirPlayPersistence.loadAmbientLightThreshold(this)
        val savedDelay = AirPlayPersistence.loadAmbientDelaySeconds(this)
        val savedSchedule = AirPlayPersistence.loadCarPlayNightSchedule(this)
        val systemNight = nightModeOrNull(resources.configuration.uiMode) ?: false
        if (savedNightMode != carPlayNightMode || savedThreshold != ambientLightThreshold ||
            savedDelay != ambientDelaySeconds || savedSchedule != nightSchedule) {
            carPlayNightMode = savedNightMode
            ambientLightThreshold = savedThreshold
            ambientDelaySeconds = savedDelay
            nightSchedule = savedSchedule
            nightModeController.configure(carPlayNightMode, systemNight, ambientLightThreshold, ambientDelaySeconds,
                nightSchedule)
        }
        nightModeController.resume(systemNight)
        darkMode = nightModeController.night
        if (activeAirPlaySession != null) {
            syncAirPlayDarkMode(ThemeModeDiagnostics.Source.RESUME)
            val fallbackSurface = fallbackVideoView?.holder?.surface
            if (fallbackSurface != null && fallbackSurface.isValid) {
                videoSurfaceOwner.replace(fallbackSurface, releaseOnDetach = false)
                attachSurface(fallbackSurface)
            }
            sink?.refreshPicture(SCREEN_TYPE_MAIN)
            if (!AirPlayPersistence.loadCarBluetoothAudio(this)) {
                controller?.takeMainAudioAndScreen()
            }
        }
        if (!menuOpen) {
            displayScalePercent = AirPlayPersistence.loadDisplayScalePercent(this)
            displayScaleTenths = CarPlayDisplayScale.sanitize((displayScalePercent + 5) / 10)
            updateResolutionMenu()
        }
        if (intent.getBooleanExtra("picture_controls", false)) {
            intent.removeExtra("picture_controls")
            openPicturePanel()
        }
        val languagePreference = AppLocale.preference(this)
        if (Build.VERSION.SDK_INT < 33 && languagePreference != languagePreferenceAtCreate) {
            languagePreferenceAtCreate = languagePreference
            recreate()
            return
        }
        if (!menuOpen) {
            val previousWireless = wirelessEnabled
            loadConnectionSettings()
            if (previousWireless != wirelessEnabled) {
                if (!wirelessEnabled) {
                    runCatching {
                        com.shilapi.xcertplay.bluetooth.CarPlayBluetoothManager.get(this).disable()
                    }
                } else {
                    runCatching {
                        com.shilapi.xcertplay.bluetooth.CarPlayBluetoothManager.get(this).enable()
                    }
                }
                if (controller != null) {
                    clearStartupRetryStop()
                    restartCarPlay("switching to ${if (wirelessEnabled) "wireless" else "USB"}")
                }
                requestStartupPrerequisites()
            }
            val savedDockAutoHide = AirPlayPersistence.loadDockAutoHide(this)
            if (savedDockAutoHide != dockAutoHideEnabled) {
                dockAutoHideEnabled = savedDockAutoHide
                dockRevealed = false
                videoView?.let { updateVideoLayout(it.width, it.height) }
            }
        }
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable && !menuOpen) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        if (!menuOpen) gestureFingerCount = AirPlayPersistence.loadSettingsGestureFingers(this)
        settingsGestureHint?.text = getString(R.string.open_diplay_settings_hint, gestureFingerCount)
        ensureClusterPresentation()
        AirPlayPersistence.overlaySettingsListener = { runOnUiThread { applyClusterTurnOverlay() } }
        com.shilapi.xcertplay.hud.NavigationOutputs.setTurnOverlayListener(clusterTurnOverlayListener)
        mainHandler.removeCallbacks(refreshTurnOverlay)
        mainHandler.post(refreshTurnOverlay)
        var systemBarsChanged = false
        if (!menuOpen) {
            val savedHideTopBar = AirPlayPersistence.loadHideTopBar(this)
            val savedHideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
            systemBarsChanged = hideTopBar != savedHideTopBar ||
                hideBottomBar != savedHideBottomBar
            hideTopBar = savedHideTopBar
            hideBottomBar = savedHideBottomBar
        }
        maybeStartCarPlay()
        applyFullscreenMode()
        if (systemBarsChanged) refreshDisplaySizeAfterLayout()
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) {
                scheduleDisplaySize(view.width, view.height)
            }
        }
    }


    // The CarPlay instrument-cluster stream on the cluster's presentation display.
    private fun ensureClusterPresentation() {
        // The full main-stream mirror owns the second screen while it is showing.
        if (secondScreen.active) {
            dismissClusterPresentation()
            return
        }
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) {
            dismissClusterPresentation()
            return
        }
        if (clusterPresentation != null) return
        val guangqi = GuangqiCluster.present(this)
        // Guangqi: reclaim the Presentation that survived a previous Activity destroy.
        if (guangqi) {
            ClusterMapPresentation.reclaimGuangqi()?.let { retained ->
                retained.rebindSurfaceCallback { surface -> runOnUiThread { onClusterSurface(surface) } }
                clusterPresentation = retained
                updateClusterMapShown()
                retained.setStreamActive(SCREEN_TYPE_ALT in activeScreenStreamTypes)
                applyClusterTurnOverlay()
                appendLog("Cluster map: reclaimed retained guangqi presentation")
                return
            }
        }
        val display = ClusterMapPresentation.findDisplay(this) ?: run {
            appendLog("Cluster map: no cluster projection display among ${ClusterMapPresentation.describeDisplays(this)}")
            return
        }
        // Guangqi: use applicationContext so the Presentation window outlives this Activity.
        val ctx = if (guangqi) applicationContext else this
        val presentation = ClusterMapPresentation(ctx, display) { surface -> runOnUiThread { onClusterSurface(surface) } }
        // The system dismisses a presentation when its display goes away; allow a new one on resume.
        presentation.setOnDismissListener {
            if (clusterPresentation === presentation) {
                clusterPresentation = null
                updateClusterMapShown()
            }
            if (guangqi) ClusterMapPresentation.releaseGuangqi()
        }
        try {
            presentation.show()
            clusterPresentation = presentation
            if (guangqi) ClusterMapPresentation.retainGuangqi(presentation)
            updateClusterMapShown()
            presentation.setStreamActive(SCREEN_TYPE_ALT in activeScreenStreamTypes)
            applyClusterTurnOverlay()
            Log.i(ClusterMapPresentation.TAG, "cluster presentation shown display=${display.displayId} name=${display.name}")
            // Not "name=": DiagnosticRedactor drops lines that look like a device name.
            appendLog("Cluster map: presentation shown display=${display.displayId} \"${display.name}\"")
        } catch (error: RuntimeException) {
            Log.w(ClusterMapPresentation.TAG, "cluster presentation failed", error)
            appendLog("Cluster map: presentation failed ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun applyClusterTurnOverlay() {
        val overlay = CarPlayClusterDisplay.usesCustomTurnCard(AirPlayPersistence.loadClusterContent(this))
        for (presentation in listOfNotNull(clusterPresentation)) {
            presentation.setTurnCardOverlay(
                AirPlayPersistence.loadClusterTurnCardOverlayXPercent(this),
                AirPlayPersistence.loadClusterTurnCardOverlayYPercent(this),
                AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(this),
            )
            presentation.setTurnCardOpacity(AirPlayPersistence.loadClusterTurnCardOpacityPercent(this))
            presentation.setTurnCardNightMode(darkMode)
            presentation.setTurnCardGuidance(if (overlay) clusterTurnGuidance else null)
        }
    }

    /**
     * @param retain true when the Activity is being destroyed but the cluster should keep showing
     *   (Guangqi: the process stays alive, the Presentation outlives the Activity).
     */
    private fun dismissClusterPresentation(retain: Boolean = false) {
        val guangqiRetained = retain && GuangqiCluster.present(this) && clusterPresentation != null
        val presentations = listOfNotNull(clusterPresentation)
        clusterPresentation = null
        clusterSurface?.let { sink?.clearSurface(SCREEN_TYPE_ALT, it) }
        clusterSurface = null
        if (guangqiRetained) {
            // Keep the Presentation showing on the cluster; the static holder already has it.
            // Clear only the decoder surface binding so the Activity can be GC'd.
            presentations.forEach { it.rebindSurfaceCallback {} }
        } else {
            presentations.forEach { runCatching { it.dismiss() } }
            if (GuangqiCluster.present(this)) ClusterMapPresentation.releaseGuangqi()
        }
        controller?.setDashboardMapOutputVisible(false)
        if (!guangqiRetained) syncGuangqiClusterReady()
    }

    private fun onClusterSurface(surface: Surface?) {
        if (clusterSurface === surface) return
        clusterSurface?.let { old -> sink?.clearSurface(SCREEN_TYPE_ALT, old) }
        clusterSurface = surface
        // Never fall back to the main surface: two decoders must not draw into one Surface.
        if (surface != null) sink?.setSurface(SCREEN_TYPE_ALT, surface)
        updateClusterMapShown()
    }

    // Whether the running session's cluster stream was sized for a physical cluster display.
    private var clusterStreamOnDisplay = false

    // A cluster display can appear after the session has already asked for the virtual fallback stream.
    private val clusterDisplayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (isDestroyed || !AirPlayPersistence.loadClusterMapEnabled(this@CarPlayHostActivity)) return
            val display = ClusterMapPresentation.findDisplay(this@CarPlayHostActivity)
            if (display?.displayId != displayId) return
            appendLog("Cluster map: display ${display.name} appeared")
            ensureClusterPresentation()
            if (controller != null && !clusterStreamOnDisplay) {
                reconnectAfterLoss("Cluster display appeared; requesting its stream")
            }
        }
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    private fun clusterDisplayConfig(): AirPlayDisplayConfig? {
        clusterStreamOnDisplay = false
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) return null
        val display = ClusterMapPresentation.findDisplay(this)
        if (display != null) {
            val size = ClusterMapPresentation.sizeOf(display)
            if (size.x > 0 && size.y > 0) {
                clusterStreamOnDisplay = true
                val requestedScale = AirPlayPersistence.loadClusterResolutionPercent(this)
                val guangqi = GuangqiCluster.present(this)
                val tuning = GuangqiCluster.Tuning::systemProperty
                val clusterFps = AirPlayPersistence.loadClusterFps(this)
                fun streamAt(scale: Int) = CarPlayClusterDisplay.config(
                    size.x,
                    size.y,
                    scale,
                    AirPlayPersistence.loadClusterMarkerHorizontalStep(this),
                    AirPlayPersistence.loadClusterMarkerVerticalStep(this),
                    AirPlayPersistence.loadClusterContent(this),
                    fps = clusterFps,
                ).let { config ->
                    if (!guangqi) return@let config
                    val (tuned, used) = GuangqiCluster.Tuning.apply(
                        config.copy(safeArea = GuangqiCluster.safeArea(config.widthPixels, config.heightPixels)), tuning)
                    if (used.isNotEmpty()) appendLog("Cluster map: test overrides $used")
                    tuned
                }
                val requested = streamAt(requestedScale)
                // The smaller-map preset enlarges the encoded canvas beyond this panel. Probe
                // the same selected hardware decoder as the main-screen enlargement guard.
                val effective = if (requestedScale > 100) {
                    val support = largerCanvasSupport(requested)
                    appendLog("Cluster map: ${support.details}")
                    if (support.supported) requested else {
                        val native = streamAt(100)
                        val fallback = if (largerCanvasSupport(native).supported) 100
                            else CarPlayClusterDisplay.STREAM_SCALE_PERCENT
                        AirPlayPersistence.saveClusterResolutionPercent(this, fallback)
                        appendLog("Cluster map: scale $requestedScale% refused (${support.reason}); using $fallback%")
                        streamAt(fallback)
                    }
                } else requested
                return effective.also {
                    MapMirrors.streamAspect = it.widthPixels.toDouble() / it.heightPixels
                    appendLog("Cluster map: requesting ${it.widthPixels}x${it.heightPixels}@${it.fps}fps on ${size.x}x${size.y} " +
                        "physical=${it.widthPhysicalMm}x${it.heightPhysicalMm}mm safeArea=${it.safeArea} url=${it.initialUrl}")
                }
            }
        }
        // Fallback for head units without physical cluster projection (e.g. DiLink 3.0/3.5 without digital cluster):
        // Provide standard virtual cluster stream (1280x720, 16:9 aspect) for CenterMapOverlay and MapEmbedService.
        MapMirrors.streamAspect = MapMirrors.VIRTUAL_STREAM_ASPECT
        return CarPlayClusterDisplay.config(
            widthPixels = 1280,
            heightPixels = 720,
            scalePercent = 100,
            content = AirPlayPersistence.loadClusterContent(this),
            baseSafeArea = CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT,
        ).also {
            appendLog("Cluster map: requesting virtual ${it.widthPixels}x${it.heightPixels} (16:9) stream for launcher/center card")
        }
    }

    // Hardware navigation belongs to the iPhone-rendered CarPlay UI, not Android View focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val physicalKey = Triple(event.deviceId, event.keyCode, event.scanCode)
        val downOrUp = event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
        if (downOrUp && physicalKey in legacySiriPresses) {
            if (event.action == KeyEvent.ACTION_UP) {
                legacySiriPresses.remove(physicalKey)
                requestLegacySiri(event.keyCode)
            }
            return true
        }
        if (!menuOpen && AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this) &&
            CarPlayRemoteKeys.dispatch(event, controller)) {
            if (event.repeatCount == 0) {
                Log.d(
                    TAG,
                    "remote key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action}",
                )
            }
            return true
        }

        // During a CarPlay call the wheel's call key answers on the iPhone instead of opening BYD's phone app.
        if (CarPlayCallKeys.onKey(this, event.keyCode, event.action == KeyEvent.ACTION_DOWN, controller)) return true

        // Media keys that reach this window (typical on aftermarket head units whose wheel
        // keys are not routed to the media session) go to the iPhone as CarPlay media HID
        // presses, mirroring the GKUI/ECARX broadcast path. Consuming them here keeps the
        // media session from seeing the same press a second time.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            CarPlayMediaKeys.handleWheelMediaKey(event.keyCode)) {
            return true
        }

        // Keep DiPlay's existing steering-wheel/voice-key Siri handling intact.
        if (!CarPlayMediaButton.opensSiri(event.keyCode)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) legacySiriPresses.add(physicalKey)
        if (event.action == KeyEvent.ACTION_UP) {
            requestLegacySiri(event.keyCode)
        }
        return true
    }

    private fun requestLegacySiri(keyCode: Int) {
        val sent = controller?.requestSiri() == true
        appendLog("Siri: voice key $keyCode sent=$sent")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            gestureSequenceActive = false
            gestureTracking = false
            touchOutsideContent = false
            refreshConfiguration(source = ThemeModeDiagnostics.Source.WINDOW_FOCUS)
            // While a reverse camera is believed up, a focus gain is a flap
            // in its arrival, not its departure: applying fullscreen flags
            // and rebuilding the picture here disturbs the camera's tenure
            // and can displace it for the rest of the manoeuvre. The
            // post-exit restore runs the same work once the exit is real.
            // The age cap keeps a never-cleared flag (a missed exit
            // broadcast) from gating refreshes forever.
            val reverseGateAgeMs = SystemClock.elapsedRealtime() - reverseCameraActiveSinceMs
            if (reverseCameraActive && reverseGateAgeMs < REVERSE_ACTIVE_GATE_CAP_MILLIS) {
                appendLog("Display: focus gained while reverse camera is active; fullscreen and picture refresh deferred to reverse exit")
            } else {
                applyFullscreenMode()

                // Issues #251/#252: a reverse-camera overlay usually takes window focus
                // without pausing the activity, so onResume's picture refresh never runs
                // when it closes — the (still decoding) picture stayed black for seconds
                // until the stream happened to refresh itself. Refresh on focus regain.
                // Issue #290: the same cycle can leave the fitted video bounds at the
                // shrunken camera size; focus regain is when the window size is final,
                // so recompute the fit there too.
                if (activeAirPlaySession != null) {
                    sink?.refreshPicture(SCREEN_TYPE_MAIN)
                    reapplyVideoLayout()
                    mainHandler.postDelayed({
                        if (activeAirPlaySession != null && !isDestroyed && !isFinishing && hasWindowFocus()) {
                            sink?.refreshPicture(SCREEN_TYPE_MAIN)
                        }
                    }, 300)
                }
            }
        } else {
            windowFocusGeneration++
            mainHandler.removeCallbacks(hideDockRunnable)
            dockEdgeSwipeActive = false
            dockCloseSwipeActive = false
            dockGestureIntercepted = false
            if (dockRevealed) {
                dockRevealed = false
                videoView?.let { updateVideoLayout(it.width, it.height) }
            }

        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("carplay_night_active", darkMode)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        startupResumed = false
        startupAutomation?.onPause()
        mainHandler.removeCallbacks(hideDockRunnable)
        UsbBtPairingUiRouter.unregister(this)
        nightModeController.pause()
        super.onPause()
    }

    override fun onStop() {
        stationWifiPrompt.detach()
        closePicturePanel()
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        isActivityStarted = false
        invalidateMainPicture()
        if (CarPlayBackgroundSession.hasSession()) {
            appendLog("Activity stopped while CarPlay controller remains retained")
        }
        logThemeState(ThemeModeDiagnostics.Source.STOP, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    /** Shows the dashboard map as a card on the centre screen while DiPlay is in the background. */
    private fun showCenterMap() {
        if (isDestroyed || shuttingDown.get() || sink == null || isActivityStarted) return
        if (!AirPlayPersistence.loadCenterMapOverlay(this) || !AirPlayPersistence.loadClusterMapEnabled(this)) return
        if (!AirPlayPersistence.loadCenterMapFollowsDashboard(this)) return
        if (MapMirrors.launcherShowsMap) return // the launcher has the map on its own screen
        // Without the stream the card would stay black; it follows once the stream starts.
        if (SCREEN_TYPE_ALT !in activeScreenStreamTypes) return
        if (!CenterMapOverlay.permitted(this)) {
            appendLog("Centre map: no permission to draw over other apps")
            return
        }
        // Without Usage Access or when auto-hide is disabled, the card shows over any app, as before.
        if (AirPlayPersistence.loadCenterMapAutoHide(this) && HomeScreenMonitor.hasAccess(this)) {
            val monitor = homeMonitor ?: HomeScreenMonitor(this, ::onHomeScreenVisible).also { homeMonitor = it }
            if (!monitor.running) {
                monitor.start() // its first answer shows the card
                return
            }
            if (homeScreenVisible != true) {
                CenterMapOverlay.hide()
                return
            }
        }
        if (CenterMapOverlay.shown) return
        val shown = CenterMapOverlay.show(applicationContext, MapMirrors.streamAspect, ::onCenterMapSurface) {
            startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        appendLog("Centre map: card ${if (shown) "shown" else "failed"} streamActive=${SCREEN_TYPE_ALT in activeScreenStreamTypes}")
    }

    private fun onHomeScreenVisible(visible: Boolean) {
        homeScreenVisible = visible
        appendLog("Centre map: home screen ${if (visible) "in front" else "not in front"}")
        if (!AirPlayPersistence.loadCenterMapAutoHide(this)) {
            homeMonitor?.stop()
            if (!isActivityStarted && !CenterMapOverlay.diPlayInFront()) showCenterMap()
        } else if (!visible) CenterMapOverlay.hide()
        else if (!isActivityStarted && !CenterMapOverlay.diPlayInFront()) showCenterMap()
    }

    private fun onCenterMapSurface(surface: Surface?) {
        MapMirrors.set(MapMirrors.CARD, surface)
        appendLog(if (surface != null) "Centre map: mirroring the dashboard stream" else "Centre map: mirror stopped")
    }

    /** Guangqi: the cluster composites our layer only while a navigation source reports ready. */
    private fun syncGuangqiClusterReady() {
        // The cluster takes DiPlay's layer as soon as it shows, with the dark placeholder until the
        // CarPlay map arrives; it goes back to its own screen when the layer goes away.
        guangqiClusterReady.update(clusterPresentation?.isShowing == true)
    }

    // The dashboard map pause must not stop the stream while a copy of the map is on screen.
    private fun updateClusterMapShown() {
        syncGuangqiClusterReady()
        // Wheel zoom needs a physical map; centre-screen copies must not suppress that eligibility.
        val surface = clusterSurface?.takeIf { it.isValid }
        val presentation = clusterPresentation
        val presented = surface != null && presentation?.isShowing == true &&
            presentation.outputSurface === surface
        controller?.setDashboardMapOutputVisible(presented)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshConfiguration(newConfig, ThemeModeDiagnostics.Source.CALLBACK)
        applyFullscreenMode()
        stageStatusView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        scrollLogsToBottom()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyFullscreenMode()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onDestroy() {
        invalidateMainPicture()
        startupAutomation?.close()
        secondScreen.stop()
        runCatching { unregisterReceiver(reverseCameraReceiver) }
        resetSidePanel()
        nightModeController.pause()
        pictureBinding?.close()
        pictureBinding = null
        removeVideoSurfaceProbe()
        fallbackVideoView?.holder?.removeCallback(fallbackSurfaceCallback)
        mainHandler.removeCallbacks(refreshTurnOverlay)
        mainHandler.removeCallbacks(hideDockRunnable)
        AirPlayPersistence.overlaySettingsListener = null
        com.shilapi.xcertplay.hud.NavigationOutputs.setTurnOverlayListener(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val displayManager = if (realSdkInt() >= Build.VERSION_CODES.M) {
                getSystemService(android.hardware.display.DisplayManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            }
            displayManager?.unregisterDisplayListener(clusterDisplayListener)
        }
        mainHandler.removeCallbacks(hideIdleCenterMap)
        homeMonitor?.stop()
        CenterMapOverlay.hide()
        if (CenterMapOverlay.requestShow == (::showCenterMap)) CenterMapOverlay.requestShow = null
        if (MapMirrors.sink === mirrorSink) {
            MapMirrors.sink = null
            MapMirrors.setStreamActive(false)
        }
        if (MapMirrors.onChanged === mirrorsChanged) MapMirrors.onChanged = null
        dismissClusterPresentation(retain = CarPlayBackgroundSession.hasSession())
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(pollConfiguration)
        videoSurfaceOwner.clear()
        currentSurfaceTexture = null
        fallbackVideoView = null
        fallbackVideoBounds = null
        surfaceBaselineView = null
        sessionLog?.append("Activity destroyed")
        sessionLog?.close()
        if (crashDiagnosticLog === sessionLog) crashDiagnosticLog = null
        sessionLog = null
        vpnDeniedDialog?.dismiss()
        vpnDeniedDialog = null
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = TextureView(this).apply {
            isOpaque = false
            surfaceTextureListener = textureListener
            // Issue #240: on rectangular head-unit screens the system or theme may apply
            // rounded-corner clipping that cuts off CarPlay edges. The toggle forces a
            // flat right-angle fill instead.
            if (AirPlayPersistence.loadForceSquareCorners(this@CarPlayHostActivity)) {
                if (realSdkInt() >= 21) {
                    outlineProvider = null
                    clipToOutline = false
                }
            }
        }
        pictureBinding = CarPlayPicture.Binding(video)
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        // Measure the preparation content naturally, then fit it inside the safe viewport.
        val viewport = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                getChildAt(0)?.measure(
                    View.MeasureSpec.makeMeasureSpec((measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
            }
        }.apply {
            isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay)
        }
        panel.addView(icon, LinearLayout.LayoutParams(dp(88), dp(88)))
        val title = TextView(this).apply {
            text = getString(R.string.diplay)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        panel.addView(title)
        val stage = TextView(this).apply {
            text = getString(R.string.getting_carplay_ready)
            gravity = Gravity.CENTER
        }
        panel.addView(stage)
        val diagnostic = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(168, 182, 202))
            setPadding(0, dp(8), 0, 0)
            maxLines = 4
        }
        panel.addView(diagnostic)
        stageDiagnosticView = diagnostic
        val instructions = TextView(this).apply {
            text = if (wirelessEnabled) getString(R.string.keep_your_iphone_nearby_with_bluetooth_and_wi_fi_on_allow)
                else getString(R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an)
            gravity = Gravity.CENTER
        }
        panel.addView(instructions)
        val recovery = Button(this).apply {
            text = getString(R.string.reset_carplay_wi_fi)
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { showDiPlayHome("wireless-recovery") }
            wifiRecoveryButton = this
        }
        panel.addView(recovery, LinearLayout.LayoutParams(dp(300), dp(64)).apply { bottomMargin = dp(12) })
        val retry = Button(this).apply {
            text = getString(R.string.retry_carplay_connection)
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener {
                if (!CarPlayBackgroundSession.isOwner(this@CarPlayHostActivity) ||
                    shuttingDown.get() || menuOpen || handshakeResetInProgress) return@setOnClickListener
                startupRetryBudget.manualRetry()
                reconnectAttempts = 0
                clearStartupRetryStop()
                restartCarPlay(getString(R.string.connecting_to_your_iphone))
            }
            startupRetryButton = this
        }
        panel.addView(retry, LinearLayout.LayoutParams(dp(300), dp(64)).apply { bottomMargin = dp(12) })
        val back = Button(this).apply {
            text = getString(R.string.back_to_diplay)
            isAllCaps = false
            setTextColor(Color.rgb(12, 17, 27))
            background = GradientDrawable().apply {
                setColor(Color.rgb(166, 200, 255))
                cornerRadius = dp(20).toFloat()
            }
            setOnClickListener { showDiPlayHome() }
        }
        panel.addView(back, LinearLayout.LayoutParams(dp(300), dp(64)))
        val gestureHint = TextView(this).apply {
            text = getString(R.string.open_diplay_settings_hint, gestureFingerCount)
            gravity = Gravity.CENTER
        }
        panel.addView(gestureHint)
        paintWaitingScreen = {
            val colors = WaitingScreenColors.of()
            viewport.setBackgroundColor(colors.background)
            title.setTextColor(colors.text)
            stage.setTextColor(colors.text)
            instructions.setTextColor(colors.secondary)
            gestureHint.setTextColor(colors.secondary)
        }
        paintWaitingScreen()
        viewport.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        // Above the video and gesture layer, below the menus.
        sidePanel = buildSidePanel().also { root.addView(it, FrameLayout.LayoutParams(0, 0)) }
        var preparationHeight = -1
        fun updatePreparationLayout() {
            val height = viewport.height - viewport.paddingTop - viewport.paddingBottom
            if (height <= 0) return
            // Interpolate within the short viewport range; keep regular screens at their existing size.
            val fraction = ((height.toFloat() / resources.displayMetrics.density - 240f) / 240f).coerceIn(0f, 1f)
            fun size(short: Float, regular: Float) = short + (regular - short) * fraction
            fun spacing(short: Float, regular: Float) = dp(size(short, regular).toInt())
            val availableWidth = viewport.width - viewport.paddingLeft - viewport.paddingRight - dp(48)
            val buttonWidth = minOf(dp(300), availableWidth.coerceAtLeast(dp(48)))
            for (button in listOf(back, recovery, retry)) {
                if (button.layoutParams.width != buttonWidth) {
                    button.layoutParams = button.layoutParams.apply { width = buttonWidth }
                }
            }
            if (preparationHeight == height) return
            preparationHeight = height
            panel.setPadding(dp(24), spacing(16f, 32f), dp(24), spacing(16f, 32f))
            val iconSize = spacing(54f, 88f)
            icon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            title.textSize = size(26f, 34f)
            title.setPadding(0, spacing(8f, 18f), 0, spacing(6f, 14f))
            stage.textSize = size(19f, 22f)
            instructions.textSize = size(15f, 17f)
            instructions.setPadding(0, spacing(8f, 14f), 0, spacing(12f, 24f))
            for (button in listOf(back, recovery, retry)) {
                button.textSize = size(17f, 18f)
                button.layoutParams = button.layoutParams.apply { this.height = spacing(50f, 64f) }
            }
            gestureHint.textSize = size(12.5f, 13f)
            gestureHint.setPadding(0, spacing(10f, 20f), 0, 0)
        }
        fun fitPreparationContent() {
            val availableHeight = viewport.height - viewport.paddingTop - viewport.paddingBottom
            if (panel.height <= 0 || availableHeight <= 0) return
            val landscape = viewport.width - viewport.paddingLeft - viewport.paddingRight > availableHeight
            val scale = if (landscape) minOf(1f, availableHeight.toFloat() / panel.height) else 1f
            panel.scaleX = scale
            panel.scaleY = scale
        }
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreparationContent() }
        viewport.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePreparationLayout()
            fitPreparationContent()
        }
        ViewCompat.setOnApplyWindowInsetsListener(viewport) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            viewport.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            viewport.post { updatePreparationLayout() }
            insets
        }
        ViewCompat.requestApplyInsets(viewport)
        settingsMenu = buildQuickMenu().apply { visibility = View.GONE }
        root.addView(settingsMenu, FrameLayout.LayoutParams(-1, -1))
        safeAreaEditor = buildSafeAreaEditor().apply { visibility = View.GONE }
        root.addView(safeAreaEditor, FrameLayout.LayoutParams(-1, -1))
        videoView = video
        smoothVideo = AirPlayPersistence.loadSmoothVideo(this)
        directSurfaceView = AirPlayPersistence.loadDirectSurfaceView(this)
        observeVideoWindow(video)
        gestureOverlay = gestureLayer
        settingsGestureHint = gestureHint
        stageStatusView = stage
        connectionPanel = viewport
        updateDebugOverlays()
        return root
    }

    // DiPlay's side panel: placed over the part of the stream CarPlay leaves black (placeSidePanel), so it
    // follows the video when it is letterboxed; touches elsewhere still reach the gesture layer and CarPlay.
    private fun buildSidePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            visibility = View.GONE
            setBackgroundColor(Color.rgb(16, 16, 18))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        panel.addView(android.widget.TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm"
            textSize = 72f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        sidePanelBattery = TextView(this).apply {
            textSize = 30f
            setTextColor(Color.rgb(200, 200, 205))
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(32))
        }
        panel.addView(sidePanelBattery)
        panel.addView(Button(this).apply {
            text = getString(R.string.side_panel_full_screen)
            textSize = 22f
            setOnClickListener { showSidePanel(false) }
        })
        return panel
    }

    // The panel's strip of the stream, mapped through the video's layout in the window.
    private fun placeSidePanel(viewWidth: Int, viewHeight: Int) {
        val panel = sidePanel ?: return
        val display = sessionDisplay ?: return
        val strip = display.viewAreas?.let { it.panelRect(it.current) } ?: return
        val content = contentRect(viewWidth, viewHeight)
        val scaleX = content.width / display.width
        val scaleY = content.height / display.height
        val left = Math.round(content.left + strip.originX * scaleX)
        val top = Math.round(content.top + strip.originY * scaleY)
        panel.layoutParams = FrameLayout.LayoutParams(
            Math.round(content.left + (strip.originX + strip.width) * scaleX) - left,
            Math.round(content.top + (strip.originY + strip.height) * scaleY) - top,
            // Stream coordinates are physical pixels, independent of the UI's reading direction.
            Gravity.TOP or Gravity.LEFT,
        ).apply { leftMargin = left; topMargin = top }
    }

    private fun showSidePanel(show: Boolean) {
        val areas = sessionDisplay?.viewAreas ?: return
        val view = videoView ?: return
        val split = isMultiWindowActive()
        val portrait = if (split) screenPortrait() else view.height > view.width
        val target = if (show) areas.sidePanel(portrait) else areas.indexFor(view.width, view.height, split, portrait)
        val sent = target != null && controller?.showViewArea(target) == true
        appendLog("Side panel ${if (show) "shown" else "hidden"}: view area $target sent=$sent")
        if (!sent || target == null) return
        areas.use(target)
        sidePanelShown = show
        sidePanel?.visibility = if (show) View.VISIBLE else View.GONE
        mainHandler.removeCallbacks(sidePanelTick)
        if (show) sidePanelTick.run()
        updateVideoLayout(view.width, view.height)
    }

    private fun resetSidePanel() {
        sidePanelShown = false
        sidePanel?.visibility = View.GONE
        mainHandler.removeCallbacks(sidePanelTick)
    }

    // The battery shows only where DiPlay already reads it for the iPhone.
    private fun refreshSidePanel() {
        // Upstream showed the BYD traction battery here. This car has no reading for it.
        sidePanelBattery?.text = ""
    }

    /** Filled rounded background for the menu's buttons, in place of API 21+ background tints. */
    private fun menuButtonBackground(fill: Int, radiusDp: Int = 20): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), if (fill == MENU_BUTTON) MENU_BORDER else fill)
        }

    /**
     * The three-finger menu: cluster map, car Bluetooth audio or no Bluetooth auto-connect (one
     * excludes the other) and music buffer, each saved at once. Bottom left opens DiPlay settings or DiPlay's home page; everything else lives in DiPlay
     * settings. Done closes, and reconnects when a change only applies to a new session.
     */
    private fun buildQuickMenu(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(200, 6, 9, 15))
            isClickable = true
            setOnClickListener { closeQuickMenu() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(MENU_SURFACE)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), MENU_BORDER)
            }
            setPadding(dp(28), dp(22), dp(28), dp(22))
        }
        panel.addView(menuText(getString(R.string.settings_quick_settings), 22f, Color.WHITE, bold = true),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        fun divider() = View(this).apply { setBackgroundColor(MENU_BORDER) }
        fun addRow(view: View, first: Boolean = false) {
            if (!first) panel.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
            panel.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(10) })
        }
        // Title, a one-line note and the switch; the whole row toggles it.
        fun switchRowWith(label: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit): Pair<View, Switch> {
            val switch = Switch(this).apply {
                isChecked = checked
                if (realSdkInt() >= Build.VERSION_CODES.LOLLIPOP) { showText = false; textOn = ""; textOff = "" }
                SettingsWidgets.styleSwitch(this, this@CarPlayHostActivity, SettingsTheme.OVERLAY)
                contentDescription = label
                isFocusable = false
                setOnCheckedChangeListener { _, on -> onChanged(on) }
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener { switch.toggle() }
                addView(LinearLayout(this@CarPlayHostActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(menuText(label, 18f, Color.WHITE))
                    addView(menuText(description, 14f, MENU_SECONDARY).apply { setPadding(0, dp(4), dp(16), 0) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(switch)
            } to switch
        }
        fun switchRow(label: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit): View =
            switchRowWith(label, description, checked, onChanged).first

        addRow(switchRow(getString(R.string.carplay_map_on_instrument_cluster_experimental),
            getString(R.string.quick_cluster_map_note),
            AirPlayPersistence.loadClusterMapEnabled(this)) { enabled ->
            AirPlayPersistence.saveClusterMapEnabled(this, enabled)
            quickMenuChangedSession()
        }, first = true)

        // Car Bluetooth audio and "no Bluetooth auto-connect during CarPlay" exclude each other:
        // with car Bluetooth audio the phone must stay on the car's Bluetooth. Turning one on turns
        // the other off. A pair saved with both on keeps car Bluetooth audio, as the session does.
        if (AirPlayPersistence.loadCarBluetoothAudio(this) && AirPlayPersistence.loadCarplayAutoDisconnectBt(this)) {
            AirPlayPersistence.saveCarplayAutoDisconnectBt(this, false)
        }
        var carBluetoothAudioSwitch: Switch? = null
        var autoDisconnectSwitch: Switch? = null
        var switchGuard = false
        fun setQuietly(target: Switch?, on: Boolean) {
            switchGuard = true
            target?.isChecked = on
            switchGuard = false
        }
        fun applyCarBluetoothAudio(enabled: Boolean) {
            AirPlayPersistence.saveCarBluetoothAudio(this, enabled)
            if (enabled && AirPlayPersistence.loadCarplayAutoDisconnectBt(this)) {
                AirPlayPersistence.saveCarplayAutoDisconnectBt(this, false)
                setQuietly(autoDisconnectSwitch, false)
            }
            carplayAutoDisconnectBt = AirPlayPersistence.loadCarplayAutoDisconnectBt(this) && !enabled
            quickMenuChangedSession()
        }
        val carBluetoothAudioRow = switchRowWith(getString(R.string.settings_car_bluetooth_audio),
            getString(R.string.quick_car_bluetooth_audio_note),
            AirPlayPersistence.loadCarBluetoothAudio(this)) { enabled ->
            if (switchGuard) return@switchRowWith
            if (enabled && usbBluetoothDonglePresent()) {
                // As in DiPlay settings: the USB dongle has no A2DP sink, so this could leave no
                // sound at all. Undo the switch and turn it on only after an explicit confirm.
                setQuietly(carBluetoothAudioSwitch, false)
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.settings_car_bluetooth_audio))
                    .setMessage(getString(R.string.settings_car_bluetooth_audio_dongle_warning))
                    .setPositiveButton(getString(R.string.settings_car_bluetooth_audio_enable_anyway)) { _, _ ->
                        setQuietly(carBluetoothAudioSwitch, true)
                        applyCarBluetoothAudio(true)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            } else {
                applyCarBluetoothAudio(enabled)
            }
        }
        carBluetoothAudioSwitch = carBluetoothAudioRow.second
        addRow(carBluetoothAudioRow.first)
        val autoDisconnectRow = switchRowWith(getString(R.string.settings_carplay_auto_disconnect_bt),
            getString(R.string.quick_auto_connect_note),
            AirPlayPersistence.loadCarplayAutoDisconnectBt(this)) { enabled ->
            if (switchGuard) return@switchRowWith
            AirPlayPersistence.saveCarplayAutoDisconnectBt(this, enabled)
            if (enabled && AirPlayPersistence.loadCarBluetoothAudio(this)) {
                AirPlayPersistence.saveCarBluetoothAudio(this, false)
                setQuietly(carBluetoothAudioSwitch, false)
            }
            carplayAutoDisconnectBt = enabled && !AirPlayPersistence.loadCarBluetoothAudio(this)
            quickMenuChangedSession()
        }
        autoDisconnectSwitch = autoDisconnectRow.second
        addRow(autoDisconnectRow.first)

        val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
        addRow(quickChoiceRow(getString(R.string.music_buffer), getString(R.string.quick_music_buffer_note),
            listOf(getString(R.string.s_300_ms_default), getString(R.string.s_500_ms), getString(R.string.s_1000_ms_most_stable)),
            bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) { index ->
            AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[index])
            quickMenuChangedSession()
        })

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Quick-menu changes are already saved: leave through Done's path, never the discard path.
        footer.addView(quickMenuButton("${getString(R.string.app_name)} ${getString(R.string.settings)}", primary = false) {
            closeQuickMenu()
            showDiPlayHome("settings")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)))
        footer.addView(quickMenuButton(getString(R.string.quick_back_to_home), primary = false) {
            closeQuickMenu()
            showDiPlayHome()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { marginStart = dp(12) })
        footer.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        quickMenuDone = quickMenuButton(getString(R.string.done), primary = true) { closeQuickMenu() }
        footer.addView(quickMenuDone, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)))
        panel.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val scroll = ScrollView(this).apply { isFillViewport = false; addView(panel) }
        overlay.addView(scroll, FrameLayout.LayoutParams(dp(860), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(24), dp(24), dp(24), dp(24))
        })
        return overlay
    }

    /** True while a USB Bluetooth dongle is plugged in: it cannot play car Bluetooth audio. */
    private fun usbBluetoothDonglePresent(): Boolean = runCatching {
        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        com.shilapi.xcertplay.usbbt.UsbBtDongleDetector(applicationContext).findDongles(usbManager).isNotEmpty()
    }.getOrDefault(false)

    /** A title, a one-line note and one row of choices; [onSelect] gets the chosen index. */
    private fun quickChoiceRow(title: String, note: String, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(menuText(title, 18f, Color.WHITE))
        column.addView(menuText(note, 14f, MENU_SECONDARY).apply { setPadding(0, dp(4), dp(16), 0) })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = mutableListOf<TextView>()
        fun paint(chosen: Int) = views.forEachIndexed { index, chip ->
            val on = index == chosen
            chip.setTextColor(if (on) MENU_BUTTON_TEXT else Color.WHITE)
            chip.background = menuButtonBackground(if (on) MENU_ACCENT else MENU_BUTTON, radiusDp = 18)
        }
        labels.forEachIndexed { index, label ->
            val chip = TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(8), 0)
                isClickable = true
                contentDescription = label
                setOnClickListener {
                    onSelect(index)
                    paint(index)
                }
            }
            views += chip
            chips.addView(chip, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (index > 0) marginStart = dp(8) })
        }
        paint(selected)
        column.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return column
    }

    private fun quickMenuButton(title: String, primary: Boolean, click: () -> Unit): Button = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) MENU_BUTTON_TEXT else Color.WHITE)
        background = menuButtonBackground(if (primary) MENU_ACCENT else MENU_BUTTON, radiusDp = 18)
        minWidth = dp(140)
        setPadding(dp(22), 0, dp(22), 0)
        setOnClickListener { click() }
    }

    /** A quick-menu change for the next session: Done says it reconnects. */
    private fun quickMenuChangedSession() {
        quickMenuNeedsReconnect = true
        quickMenuDone?.text = getString(R.string.save_and_reconnect)
    }

    private fun captureSettingsBaseline(): SettingsBaseline {
        val safeAreaSize = currentActivitySize()
        val customIconBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the current AirPlay icon for settings rollback", error)
            null
        }
        return SettingsBaseline(
            safeAreaRects = mutableMapOf<DisplaySize, SafeAreaRect?>().apply {
                safeAreaSize?.let { put(it, AirPlayPersistence.loadSafeAreaRect(this@CarPlayHostActivity, it.width, it.height)) }
            },
            customIconBytes = customIconBytes,
        )
    }

    private fun restoreSettingsBaseline() {
        val baseline = settingsBaseline ?: return
        loadPersistedSettings()
        baseline.safeAreaRects.forEach { (size, savedRect) ->
            savedRect?.let { rect ->
                AirPlayPersistence.saveSafeAreaRect(
                    this,
                    size.width,
                    size.height,
                    rect,
                    commit = true,
                )
            } ?: AirPlayPersistence.clearSafeAreaRect(
                this,
                size.width,
                size.height,
                commit = true,
            )
        }
        try {
            baseline.customIconBytes?.let { bytes ->
                AirPlayPersistence.saveCustomAirPlayIcon(this, bytes)
            } ?: AirPlayPersistence.clearCustomAirPlayIcon(this)
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore the previous AirPlay icon", error)
        }
        settingsBaseline = null
        locationPermissionAvailable = hasFineLocationPermission()
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) getString(R.string.hotspot_state_stopped) else getString(R.string.hotspot_state_off))
        syncMfiSettingsControls()
        updateManualHotspotFields()
        updateAirPlayIconPreview()
        updateSafeAreaSummary()
        updateHotspotStatusBlock()
        updateResolutionMenu()
        updateDebugOverlays()
        applyFullscreenMode()
        refreshDisplaySizeAfterLayout()
    }

    private fun updateMfiTargetFields() {
        mfiI2cFields?.visibility = if (mfiTarget == MfiTarget.I2C) View.VISIBLE else View.GONE
        mfiRemoteFields?.visibility = if (mfiTarget == MfiTarget.REMOTE) View.VISIBLE else View.GONE
        mfiErrorView?.visibility = View.GONE
    }

    private fun syncMfiSettingsControls() {
        mfiTargetGroup?.let { group ->
            val button = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filterIsInstance<RadioButton>()
                .firstOrNull { it.tag == mfiTarget }
            button?.let { group.check(it.id) }
        }
        if (mfiI2cPathInput?.text?.toString() != mfiI2cPath) {
            mfiI2cPathInput?.setText(mfiI2cPath)
        }
        if (remoteMfiServerInput?.text?.toString() != remoteMfiServer) {
            remoteMfiServerInput?.setText(remoteMfiServer)
        }
        if (remoteMfiTokenInput?.text?.toString() != remoteMfiToken) {
            remoteMfiTokenInput?.setText(remoteMfiToken)
        }
        updateMfiTargetFields()
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.LOCAL -> getString(R.string.local_offline)
        MfiTarget.USB_CH341 -> getString(R.string.usb_ch341)
        MfiTarget.I2C -> getString(R.string.i2c)
        MfiTarget.REMOTE -> getString(R.string.remote)
    }

    private fun buildSafeAreaEditor(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val editor = SafeAreaEditorView(this)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.addView(
            menuText(getString(R.string.safe_area), 24f, Color.WHITE, bold = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(8))
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        controls.addView(
            Button(this).apply {
                text = getString(R.string.cancel)
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = menuButtonBackground(MENU_BUTTON)
                setOnClickListener { closeSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(
            Button(this).apply {
                text = getString(R.string.save)
                isAllCaps = false
                setTextColor(MENU_BUTTON_TEXT)
                background = menuButtonBackground(MENU_ACCENT)
                setOnClickListener { saveSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        overlay.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        safeAreaEditorView = editor
        return overlay
    }

    private fun afterTextChanged(onChanged: (String) -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(text: Editable?) {
                onChanged(text?.toString().orEmpty())
            }
        }

    private fun updateManualHotspotFields() {
        existingWifiFields?.visibility = if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) View.VISIBLE else View.GONE
        existingWifiErrorView?.visibility = View.GONE
        val visible = wirelessHotspotMode == WirelessHotspotMode.MANUAL
        manualHotspotFields?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) manualHotspotErrorView?.visibility = View.GONE
        val hostapdVisible = wirelessHotspotMode == WirelessHotspotMode.HOSTAPD
        hostapdHotspotFields?.visibility = if (hostapdVisible) View.VISIBLE else View.GONE
        if (!hostapdVisible) hostapdErrorView?.visibility = View.GONE
    }

    private fun manualHotspotSettingsError(): String? {
        if (wirelessHotspotMode == WirelessHotspotMode.HOSTAPD) {
            return when {
                hostapdSsid.isBlank() -> getString(R.string.hotspot_ssid_is_required)
                hostapdSsid.encodeToByteArray().size > 32 ->
                    getString(R.string.hotspot_ssid_must_be_at_most_32_utf_8_bytes)
                '\u0000' in hostapdSsid -> getString(R.string.hotspot_ssid_contains_u_0000)
                // Line breaks and other control characters would reach the root-run
                // hostapd.conf as extra directives (HostapdConfig.error).
                com.shilapi.xcertplay.network.HostapdConfig.hasControlCharacter(hostapdSsid) ->
                    getString(R.string.hotspot_ssid_contains_control_character)
                hostapdChannel !in 1..13 -> getString(R.string.hostapd_channel_must_be_1_13)
                '\u0000' in hostapdPassphrase -> getString(R.string.hotspot_password_contains_u_0000)
                com.shilapi.xcertplay.network.HostapdConfig.hasControlCharacter(hostapdPassphrase) ->
                    getString(R.string.hotspot_password_contains_control_character)
                hostapdPassphrase.encodeToByteArray().size !in 8..63 ->
                    getString(R.string.wpa2_wpa3_password_must_be_8_63_characters)
                else -> null
            }
        }
        if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
            val error = com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(existingWifiSsid, existingWifiPassphrase)
            return error?.let { getString(it.messageResource()) }
        }
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) return null
        return when {
            manualHotspotSsid.isBlank() -> getString(R.string.hotspot_ssid_is_required)
            manualHotspotSsid.encodeToByteArray().size > 32 ->
                getString(R.string.hotspot_ssid_must_be_at_most_32_utf_8_bytes)
            '\u0000' in manualHotspotSsid -> getString(R.string.hotspot_ssid_contains_u_0000)
            manualHotspotChannel !in 0..196 -> getString(R.string.channel_must_be_0_or_1_196)
            manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel) ->
                getString(R.string.channel_is_not_valid_for_the_selected_band)
            '\u0000' in manualHotspotPassphrase -> getString(R.string.hotspot_password_contains_u_0000)
            manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.isNotEmpty() ->
                getString(R.string.password_must_be_empty_when_security_is_open)
            manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.length !in 8..63 ->
                getString(R.string.wpa2_wpa3_password_must_be_8_63_characters)
            else -> null
        }
    }

    private fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> getString(R.string.wifi_direct)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.localonlyhotspot)
        WirelessHotspotMode.MANUAL -> getString(R.string.manual_hotspot)
        WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_title)
        WirelessHotspotMode.HOSTAPD -> getString(R.string.hotspot_mode_hostapd)
    }

    private fun menuText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
    }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = getString(R.string.starting))
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = getString(R.string.ready),
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(state = getString(R.string.waiting_for_paired_iphone))
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(state = getString(R.string.connecting_bluetooth))
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(state = getString(R.string.running))
            CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback ->
                hotspotStatus.copy(state = getString(R.string.active))
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(state = getString(R.string.starting_airplay_service))
            is CarPlayStatus.Failed -> hotspotStatus.copy(state = getString(R.string.error))
            else -> return
        }
        updateHotspotStatusBlock()
    }

    private fun updateHotspotStatusBlock() {
        if (!wirelessEnabled) {
            hotspotStatusView?.text = getString(R.string.wireless_hotspot_off)
            return
        }
        val status = hotspotStatus
        hotspotStatusView?.text = buildString {
            append(getString(R.string.hotspot_wireless_prefix)).append(status.state)
            status.ssid?.let { append(getString(R.string.hotspot_ssid_prefix)).append(it) }
            status.backend?.let { append(getString(R.string.hotspot_backend_prefix)).append(it) }
            status.band?.let { append(getString(R.string.hotspot_band_prefix)).append(it) }
            status.channel?.let {
                append(getString(R.string.hotspot_channel_prefix)).append(if (it == 0) getString(R.string.auto_label) else it.toString())
            }
        }
    }

    private fun updateResolutionMenu() {
        resolutionValueView?.text = "${displayScalePercent}%"
        val native = activeDisplaySize ?: currentActivitySize()
        val resolution = if (native == null) {
            getString(R.string.handshake_resolution_waiting_for_display)
        } else {
            val negotiated = CarPlayDisplayScale.applyPercent(
                AirPlayDisplayConfig(
                    widthPixels = native.width,
                    heightPixels = native.height,
                    widthPhysicalMm = widthPhysicalMm,
                    fps = fps,
                ),
                displayScalePercent,
            )
            "${getString(R.string.resolution_handshake_prefix)}${native.width} x ${native.height} -> " +
                "${negotiated.widthPixels} x ${negotiated.heightPixels}"
        }
        val transport = if (!hevcEnabled) {
            "H.264"
        } else {
            "HEVC (H.265, ${if (hevcSoftwareDecoderEnabled) "software" else "hardware"})"
        }
        val fullscreen = buildString {
            append(if (hideTopBar) getString(R.string.fullscreen_top_hidden) else getString(R.string.fullscreen_top_shown))
            append(", ")
            append(if (hideBottomBar) getString(R.string.fullscreen_bottom_hidden) else getString(R.string.fullscreen_bottom_shown))
        }
        resolutionPreviewView?.text = buildString {
            append(resolution).append('\n')
            append(getString(R.string.preview_identity)).append(normalizedManufacturer()).append(" / ")
                .append(normalizedModel()).append('\n')
            append(getString(R.string.preview_oem_label)).append(oemLabel.ifBlank { getString(R.string.preview_empty) }).append('\n')
            append(getString(R.string.preview_frame_rate)).append(fps).append(" fps\n")
            append(getString(R.string.preview_detected_maximum))
                .append(maximumDetectedWidthPixels).append(" x ")
                .append(maximumDetectedHeightPixels).append(" px\n")
            append(getString(R.string.preview_physical_reference))
                .append(
                    when (physicalSizeBasis) {
                        AirPlayPhysicalSizeBasis.WIDTH -> getString(R.string.basis_widest_width)
                        AirPlayPhysicalSizeBasis.HEIGHT -> getString(R.string.basis_longest_height)
                    },
                )
                .append(" = ").append(widthPhysicalMm).append(" mm\n")
            native?.let { size ->
                val physical = resolvePhysicalSize(size)
                append(getString(R.string.preview_carplay_physical_size))
                    .append(physical.widthMm).append(" x ")
                    .append(physical.heightMm).append(" mm\n")
            }
            append(getString(R.string.preview_driving_side)).append(if (rightHandDrive) getString(R.string.driving_side_right) else getString(R.string.driving_side_left)).append('\n')
            append(getString(R.string.preview_fullscreen)).append(fullscreen).append('\n')
            append(getString(R.string.preview_video_transport)).append(transport).append('\n')
            append(getString(R.string.preview_location_reporting))
                .append(if (locationReportingEnabled) getString(R.string.enabled_value) else getString(R.string.disabled_value))
                .append('\n')
            if (advancedAudioChannelMappingSupported) {
                append(getString(R.string.preview_audio_channel_mapping))
                    .append(if (advancedAudioChannelMapping) getString(R.string.mapping_aaos_buses) else getString(R.string.mapping_mobile_compatible))
                    .append('\n')
            }
            append(safeAreaSummary())
        }
    }

    private data class CanvasSupport(val supported: Boolean, val reason: String, val details: String)

    private fun largerCanvasSupport(display: AirPlayDisplayConfig): CanvasSupport =
        if (maxOf(display.widthPixels, display.heightPixels) > 3840 || minOf(display.widthPixels, display.heightPixels) > 2160) {
            CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
        } else decoderCanvasSupport(display)

    private fun decoderCanvasSupport(display: AirPlayDisplayConfig): CanvasSupport = try {
        if (realSdkInt() < 21) {
            CanvasSupport(false, "legacy_decoder_capabilities", "Keep the native screen canvas on Android 4.4")
        } else {
        val mime = if (hevcEnabled) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        // Match MediaCodec.createDecoderByType's first suitable decoder; do not silently force
        // an enlarged stream through a software decoder on a slower head unit.
        val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
        }
        if (decoder == null) {
            CanvasSupport(false, "no_decoder", "Decoder capability mime=$mime result=no_decoder")
        } else {
            val hardware = if (realSdkInt() >= 29) decoder.isHardwareAccelerated
                else !decoder.name.startsWith("OMX.google.") && !decoder.name.startsWith("c2.android.")
            val video = decoder.getCapabilitiesForType(mime).videoCapabilities
            val sizeSupported = video?.isSizeSupported(display.widthPixels, display.heightPixels) == true
            val rateSupported = sizeSupported && video?.areSizeAndRateSupported(
                display.widthPixels, display.heightPixels, display.fps.toDouble()) == true
            val reason = when {
                !hardware -> "software_decoder"
                hevcEnabled && hevcSoftwareDecoderEnabled -> "software_hevc_selected"
                video == null -> "no_video_capabilities"
                !sizeSupported -> "canvas_dimensions_unsupported"
                !rateSupported -> "frame_rate_unsupported"
                else -> "supported"
            }
            CanvasSupport(reason == "supported", reason,
                "Decoder capability codec=${decoder.name} mime=$mime hardware=$hardware " +
                    "sizeSupported=$sizeSupported rateSupported=$rateSupported " +
                    "widths=${video?.supportedWidths} heights=${video?.supportedHeights} " +
                    "alignment=${video?.widthAlignment}x${video?.heightAlignment} " +
                    "fpsRange=${video?.supportedFrameRates} result=$reason")
        }
        }
    } catch (error: Exception) {
        CanvasSupport(false, "capability_query_${error.javaClass.simpleName}",
            "Decoder capability query failed error=${error.javaClass.simpleName}")
    }

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        // The home settings page can change the name while this host stays alive.
        if (!menuOpen) oemLabel = AirPlayPersistence.loadOemLabel(this)
        val safeWidth = (size.width / 2 * 2).coerceAtLeast(2)
        val safeHeight = (size.height / 2 * 2).coerceAtLeast(2)
        val alignedSize = DisplaySize(safeWidth, safeHeight)
        val physical = resolvePhysicalSize(alignedSize)
        val knobPrimary = AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = alignedSize.width,
            heightPixels = alignedSize.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
            // Tell CarPlay to use its native knob/focus model on Android TV and other non-touch
            // hosts. Touch-capable head units remain touchscreen-primary.
            primaryInputDevice = if (knobPrimary) 3 else 1,
        )
        appendLog(
            "CarPlay primary input=${if (knobPrimary) "knob" else "touch"} " +
                "tv=${AndroidTvInputMode.isTelevision(this)} " +
                "touchscreen=${resources.configuration.touchscreen}",
        )
        val requestedResolutionPercent = displayScalePercent
        val requestedResolutionDisplay = CarPlayDisplayScale.applyPercent(baseDisplay, requestedResolutionPercent)
        var resolutionDisplay = requestedResolutionDisplay
        val requestedPercent = uiScalePercent
        var scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
        val candidate = scaledDisplay
        var support = when {
            uiScalePercent < CarPlayUiScale.DEFAULT && scaledDisplay === resolutionDisplay ->
                CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
            uiScalePercent < CarPlayUiScale.DEFAULT || scaledDisplay.widthPixels > baseDisplay.widthPixels ||
                scaledDisplay.heightPixels > baseDisplay.heightPixels -> largerCanvasSupport(scaledDisplay)
            else -> CanvasSupport(true, "not_enlarging", "Decoder capability enlargement check not required")
        }
        var smallerUiFallback = false
        var resolutionFallback = false
        if (!support.supported && uiScalePercent < CarPlayUiScale.DEFAULT) {
            val failedCanvas = support
            smallerUiFallback = true
            uiScalePercent = CarPlayUiScale.DEFAULT
            AirPlayPersistence.saveUiScalePercent(this, uiScalePercent)
            scaledDisplay = resolutionDisplay
            appendLog("Larger CarPlay canvas unavailable reason=${failedCanvas.reason}; using Default icon and text size")
            // Removing the smaller-controls enlargement may leave a separately enlarged resolution.
            if (displayScalePercent > 100) {
                val resolutionSupport = largerCanvasSupport(scaledDisplay)
                support = resolutionSupport.copy(
                    reason = if (resolutionSupport.supported) failedCanvas.reason else resolutionSupport.reason,
                    details = "${failedCanvas.details}\n${resolutionSupport.details}",
                )
            }
        }
        if (!support.supported && displayScalePercent > 100) {
            resolutionFallback = true
            displayScalePercent = 100
            displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
            AirPlayPersistence.saveDisplayScalePercent(this, displayScalePercent)
            AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
            resolutionDisplay = baseDisplay
            scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
            appendLog("Enlarged CarPlay resolution unavailable reason=${support.reason}; using 100%")
        }
        if (smallerUiFallback || resolutionFallback) {
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    getString(if (resolutionFallback) R.string.resolution_canvas_unsupported
                        else R.string.this_head_unit_cannot_use_the_smaller_size_at_this_resolut),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        appendLog("CarPlay size=${CarPlayUiScale.label(uiScalePercent)} canvas=${scaledDisplay.widthPixels}x${scaledDisplay.heightPixels}")
        val forceSquareCorners = AirPlayPersistence.loadForceSquareCorners(this)
        val display = scaledDisplay.copy(
            safeArea = if (forceSquareCorners) null else AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = if (forceSquareCorners) null else safeAreaDrawOutside,
            forceSquareCorners = forceSquareCorners,
        )
        // A fixed dock, split-screen support or a turning screen declares several areas the car switches
        // between live. A session that starts in split screen sizes its canvas to that window, so it keeps
        // the plain canvas and one area for it.
        val inSplitScreen = isMultiWindowActive()
        val dock = CarPlayDock.load(this).also { sessionDock = it }
        // The whole screen and CarPlay's full window in each orientation: the system bars take their
        // place in both, so a remembered split window is sized against the window of its orientation.
        val screen = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        val screenLong = maxOf(screen.widthPixels, screen.heightPixels)
        val screenShort = minOf(screen.widthPixels, screen.heightPixels)
        val (landscapeWindow, portraitWindow) = CarPlayRotation.turnedWindows(size.width, size.height,
            screen.widthPixels, screen.heightPixels)
        // Before DiPlay has seen a split window, expect one from the screen, its bars and Android's divider.
        val statusBar = systemDimension("status_bar_height")
        val navigationBar = systemDimension("navigation_bar_height")
        val divider = (systemDimension("docked_stack_divider_thickness") -
            2 * systemDimension("docked_stack_divider_insets")).coerceAtLeast(0)
        val splitWindow: (Boolean) -> Pair<Float, Float>? = { portrait ->
            if (SplitScreenSettings.enabled(this) && !inSplitScreen) {
                val window = if (portrait) portraitWindow else landscapeWindow
                val expected = SplitScreenSettings.expectedWindow(portrait, screenLong, screenShort,
                    statusBar, navigationBar, divider)
                SplitScreenSettings.ofWindow(SplitScreenSettings.window(this, portrait, expected),
                    if (portrait) screenShort else screenLong, if (portrait) screenLong else screenShort,
                    window.first, window.second)
            } else null
        }
        val longPixels = maxOf(display.widthPixels, display.heightPixels)
        val square = if (CarPlayRotation.enabled(this) && !inSplitScreen) {
            CarPlayRotation.squareSide(longPixels, CarPlayRotation.picture(this), hevcEnabled, hevcSoftwareDecoderEnabled)
        } else null
        val canvas = if (square == null) display else {
            // The square keeps the plain canvas's pixel density, so CarPlay's scale (and "CarPlay size")
            // stays the same in both orientations; it has no room for the custom safe area.
            val longMm = if (display.widthPixels >= display.heightPixels) display.widthPhysicalMm else display.heightPhysicalMm
            val squareMm = longMm?.let { (it.toLong() * square / longPixels).toInt() }
            display.copy(widthPixels = square, heightPixels = square, widthPhysicalMm = squareMm,
                heightPhysicalMm = squareMm, viewArea = null, safeArea = null)
        }
        val viewAreas = if (square == null) {
            CarPlayViewAreas.build(display.widthPixels, display.heightPixels, listOf(
                CarPlayViewAreas.Screen(display.widthPixels, display.heightPixels, portrait = display.heightPixels > display.widthPixels),
            ), dock, splitWindow, startPortrait = display.heightPixels > display.widthPixels,
                sidePanel = SidePanelSettings.enabled(this), rightHandDrive = rightHandDrive)
        } else {
            val (landscape, portrait) = CarPlayRotation.turningAreas(square, size.width, size.height,
                screen.widthPixels, screen.heightPixels)
            CarPlayViewAreas.build(square, square, listOf(
                CarPlayViewAreas.Screen(landscape.first, landscape.second, portrait = false),
                CarPlayViewAreas.Screen(portrait.first, portrait.second, portrait = true),
            ), dock, splitWindow, startPortrait = size.height > size.width, sidePanel = SidePanelSettings.enabled(this),
                rightHandDrive = rightHandDrive)
        }
        pendingViewAreas = viewAreas
        val declared = if (viewAreas == null) canvas else canvas.copy(viewAreas = viewAreas.areas, initialViewArea = viewAreas.current)
        if (square != null) appendLog("Turning screen: square canvas ${square}x$square, areas ${viewAreas?.areas}")
        if (SplitScreenSettings.enabled(this) && !inSplitScreen) {
            appendLog("Split screen expected from status bar=$statusBar navigation bar=$navigationBar divider=$divider px")
        }
        val requestSummary = "Display request selected=${CarPlayUiScale.label(requestedPercent)} percent=$requestedPercent " +
            "surface=${size.width}x${size.height} resolution=${requestedResolutionPercent}% " +
            "base=${requestedResolutionDisplay.widthPixels}x${requestedResolutionDisplay.heightPixels} " +
            "candidate=${candidate.widthPixels}x${candidate.heightPixels} fps=$fps " +
            "codec=${if (hevcEnabled) "HEVC" else "H.264"} softwareHevc=$hevcSoftwareDecoderEnabled"
        val effectiveSummary = "Display effective percent=$uiScalePercent resolution=${displayScalePercent}% " +
            "canvas=${display.widthPixels}x${display.heightPixels} decision=${support.reason} " +
            "physical=${physical.widthMm}x${physical.heightMm}mm safeArea=${display.safeArea} " +
            "drawOutside=${display.safeAreaDrawOutside} forceSquareCorners=${display.forceSquareCorners}"
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.begin(this, requestSummary, support.details, effectiveSummary)
        appendLog(requestSummary)
        appendLog(support.details)
        appendLog(effectiveSummary)
        if (AirPlayPersistence.loadCarBluetoothAudio(this)) {
            appendLog("Car Bluetooth audio: on; CarPlay audio not offered")
            // The USB dongle stack has no A2DP sink: if a dongle is plugged in, audio can only
            // play when the iPhone is also paired with the car's own Bluetooth. Flag it in the
            // log so "no sound at all" reports are diagnosable.
            val donglePresent = runCatching {
                (getSystemService(Context.USB_SERVICE) as? UsbManager)?.let { usbManager ->
                    com.shilapi.xcertplay.usbbt.UsbBtDongleDetector(applicationContext)
                        .findDongles(usbManager).isNotEmpty()
                } ?: false
            }.getOrDefault(false)
            if (donglePresent) {
                appendLog("Car Bluetooth audio: WARNING USB Bluetooth dongle present; the dongle cannot play audio, sound needs the car's own Bluetooth")
            }
        }
        // The car's own Bluetooth. A wireless session through a USB Bluetooth adapter replaces
        // btMac with the adapter's address, and this keeps the built-in one declared so the iPhone
        // releases it. Not declared when sound is meant to stay on that connection.
        val carBluetoothIds = if (AirPlayPersistence.loadCarBluetoothAudio(this)) {
            emptyList()
        } else {
            listOfNotNull(
                DiPlayBluetooth.localAddress(this),
                com.shilapi.xcertplay.bluetooth.BluetoothAddressResolver.resolve(this),
            ).distinct()
        }
        appendLog("Car Bluetooth addresses declared to the iPhone: ${carBluetoothIds.ifEmpty { "none" }}")
        return AirPlayConfig(
            deviceName = "DiPlay",
            deviceId = DiPlayBootstrap.deviceId(airPlayIdentity),
            btMac = DiPlayBluetooth.localAddress(this) ?: DiPlayBootstrap.deviceId(airPlayIdentity),
            additionalBluetoothIds = carBluetoothIds,
            sourceVersion = "950.7.1",
            main = declared,
            cluster = clusterDisplayConfig(),
            rightHandDrive = rightHandDrive,
            hevc = hevcEnabled,
            microphone = microphoneAvailable,
            microphoneOpus = com.shilapi.xcertplay.media.OpusEncoderSupport.isAvailable(),
            opusAudioOutput = true,
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon()),
            mainBufferedAudio = AirPlayPersistence.loadMainBufferedAudio(this),
            disableAudioOutput = AirPlayPersistence.loadCarBluetoothAudio(this),
        )
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = AirPlayPersistence.loadCustomAirPlayIconBytes(this)
        if (customBytes != null) {
            decodeAirPlayIcon(normalizeAirPlayIconBytes(customBytes))?.let { return it }
            Log.w(TAG, "Custom AirPlay icon could not be decoded, falling back to default")
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    /**
     * The icon bytes travel inline in the session's /info payload, so an
     * oversized custom icon breaks session setup on the phone side
     * (issue #263). Icons are already normalized to the stored limits when
     * saved; this load-time pass through the same normalization is the
     * safety net for icons saved by older versions.
     */
    private fun normalizeAirPlayIconBytes(encoded: ByteArray): ByteArray =
        AirPlayPersistence.normalizeCustomAirPlayIconBytes(encoded)

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        // Shown in CarPlay's app list as the "back to the car" button.
        resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }

    private fun updateAirPlayIconPreview() {
        val preview = iconPreviewView ?: return
        val customBitmap = AirPlayPersistence.loadCustomAirPlayIconBitmap(this)
        val bitmap = customBitmap ?: BitmapFactory.decodeResource(resources, R.raw.placeholder_icon)
        preview.setImageBitmap(bitmap)
        iconStatusView?.text =
            if (customBitmap != null) getString(R.string.custom_1_1_icon) else getString(R.string.default_placeholder_icon)
    }

    private fun currentActivitySize(): DisplaySize? {
        val view = videoView
        if (view != null && view.width > 0 && view.height > 0) {
            return DisplaySize(view.width, view.height)
        }
        return activeDisplaySize
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun safeAreaSummary(): String {
        val size = currentActivitySize() ?: return getString(R.string.safe_area_waiting_for_activity_size)
        val mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        return if (mapping == null) {
            "${getString(R.string.safe_area_full_screen_at)}${size.width} x ${size.height}"
        } else {
            getString(
                R.string.safe_area_mapping_summary,
                mapping.width, mapping.height, mapping.left, mapping.top, size.width, size.height,
            )
        }
    }

    private fun updateSafeAreaSummary() {
        safeAreaSummaryView?.text = safeAreaSummary()
    }

    private fun closeSafeAreaEditor() {
        if (!safeAreaEditorActive) return
        safeAreaEditorActive = false
        safeAreaEditSize = null
        safeAreaEditor?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area editor closed")
    }

    private fun saveSafeAreaEditor() {
        val size = safeAreaEditSize ?: currentActivitySize() ?: return
        val rect = safeAreaEditorView?.currentRectForSource() ?: return
        rememberSafeAreaBeforeEdit(size)
        AirPlayPersistence.saveSafeAreaRect(this, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        closeSafeAreaEditor()
    }

    private fun rememberSafeAreaBeforeEdit(size: DisplaySize) {
        val baseline = settingsBaseline ?: return
        if (!baseline.safeAreaRects.containsKey(size)) {
            baseline.safeAreaRects[size] = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        }
    }

    private fun refreshDisplaySizeAfterLayout() {
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun createMediaSink(
        videoWidth: Int,
        videoHeight: Int,
        controllerGeneration: Int,
    ): AndroidMediaSink {
        // Capture this session's log: late decoder shutdown must not write into a new session.
        val diagnosticLog = sessionLog
        return AndroidMediaSink(
            surface = null,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            telephonyDownlinkViaMusic = telephonyDownlinkViaMusic,
            announcementDuck = announcementDuckMedia,
            audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(this),
            audioFocusAutoYield = AirPlayPersistence.loadAudioFocusAutoYield(this),
            mediaFocusExclusive = AirPlayPersistence.loadMediaFocusExclusive(this),
            mediaChannel = AirPlayPersistence.loadMediaAudioChannel(this),
            navigationChannel = AirPlayPersistence.loadNavigationAudioChannel(this),
            mediaVolumePercent = AirPlayPersistence.loadMediaVolumePercent(this),
            navigationVolumePercent = AirPlayPersistence.loadNavigationVolumePercent(this),
            context = this,
            navigationStreamType = navigationStreamType,
            onScreenStreamActiveChanged = { type, active ->
                onScreenStreamStateChanged(controllerGeneration, type, active)
            },
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(this),
            onAudioDiagnostic = { message ->
                if (message.startsWith("Microphone: ")) {
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                } else {
                    diagnosticLog?.append(formattedLogLine(message, System.currentTimeMillis()))
                }
            },
            onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,
            callEchoCancellation = AirPlayPersistence.loadCallEchoCancellation(this),
            callVoiceFilter = AirPlayPersistence.loadCallVoiceFilter(this),
            callMicCompatibility = AirPlayPersistence.loadCallMicCompatibility(this),
            // Only a SurfaceView honours release timestamps; smooth video always selects one.
            videoPacingDelayMillis = if (smoothVideo) smoothVideoDelayMillis(fps) else 0,
        )
    }

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
        )

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            private val diagnosticLog = sessionLog

            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeAirPlaySession = session
                    CarPlayBackgroundSession.active = true
                    reconnectAttempts = 0
                    clearStartupRetryStop()
                    logThemeState(ThemeModeDiagnostics.Source.SESSION_ACTIVE, resources.configuration)
                    syncAirPlayDarkMode(ThemeModeDiagnostics.Source.SESSION_ACTIVE)
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                }
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || activeAirPlaySession !== session || shuttingDown.get()) return@runOnUiThread
                    if (SCREEN_TYPE_MAIN !in activeScreenStreamTypes) {
                        activeScreenStreamTypes.add(SCREEN_TYPE_MAIN)
                        updateDebugOverlays()
                    }
                    startSecondScreenMirror()
                    if (!startupRetryBudget.firstFrame(session, android.os.SystemClock.elapsedRealtime())) return@runOnUiThread
                    // Issue #290: a session showing its first frame breaks
                    // the failure streak — the phone did connect in between,
                    // so any later failures start a new streak and the prompt
                    // can show again. (A mid-session loss is not itself a
                    // counted failure; only FIRST_TCP_TIMEOUT counts.)
                    wirelessLinkFailurePrompt.onSessionActivated()
                    AirPlayPersistence.saveWirelessLinkFailures(this@CarPlayHostActivity, 0)
                    AirPlayPersistence.saveWirelessRepairPromptShown(this@CarPlayHostActivity, false)
                    mainHandler.postDelayed({
                        if (controllerGeneration == restartGeneration && activeAirPlaySession === session &&
                            !shuttingDown.get() && CarPlayBackgroundSession.isOwner(this@CarPlayHostActivity) &&
                            startupRetryBudget.resetIfStable(session, android.os.SystemClock.elapsedRealtime())) {
                            appendLog("wireless startup retry budget reset after stable video session")
                        }
                    }, WirelessStartupPolicy.STABLE_SESSION_MILLIS)
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    val active = activeAirPlaySession
                    if (active != null && active !== session) {
                        // 探针或非活动握手结束时，保留当前播放会话。
                        appendLog("Non-active AirPlay connection ended; keeping the live session")
                        return@runOnUiThread
                    }
                    if (controllerGeneration != restartGeneration || startupRetryStopped) return@runOnUiThread
                    startupRetryBudget.disconnected()
                    secondScreen.stop()
                    invalidateMainPicture()
                    activeAirPlaySession = null
                    CarPlayBackgroundSession.active = false
                    if (menuOpen) {
                        recoveryPendingAfterMenu = true
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    syncGuangqiClusterReady()
                    if (controller?.isWiredWaitingForIphone() == true) {
                        appendLog("Wired AirPlay session ended (USB detached); keeping controller waiting for iPhone")
                        setConnectionStage(getString(R.string.waiting_for_iphone_over_usb))
                        return@runOnUiThread
                    }
                    setConnectionStage(getString(R.string.carplay_session_ended_reconnecting))
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || startupRetryStopped) return@runOnUiThread
                    startupRetryBudget.disconnected()
                    secondScreen.stop()
                    invalidateMainPicture()
                    if (menuOpen) {
                        recoveryPendingAfterMenu = true
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    syncGuangqiClusterReady()
                    if (controller?.isWiredWaitingForIphone() == true) {
                        appendLog("Wired transport error after USB detach ($message); keeping controller waiting for iPhone")
                        setConnectionStage(getString(R.string.waiting_for_iphone_over_usb))
                        return@runOnUiThread
                    }
                    setConnectionStage(getString(R.string.transport_error_reconnecting))
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onDebugLog(message: String) {
                // Session log output switch (issue #186): drop everything before formatting,
                // redaction or file IO to spare old head units the collection cost.
                if (!LoggingGate.enabled) return
                if (DiagnosticRedactor.redact(message) == null) return
                if (message.startsWith(CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX + " ")) {
                    // Retain old-controller teardown evidence without accepting its UI/session state.
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                    return
                }
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    DisplayDiagnosticSnapshot.record(this@CarPlayHostActivity, displayDiagnosticAttempt, message)
                    if (message.startsWith(PROTOCOL_TRACE_PREFIX)) {
                        appendFileLog(message)
                    } else {
                        appendLog(message)
                    }
                }
            }

            override fun onModesChanged(
                session: AirPlaySession,
                appStates: List<Map<String, Any?>>,
                resources: List<Map<String, Any?>>,
            ) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || activeAirPlaySession !== session) return@runOnUiThread
                    val naviState = appStates.find { (it["appStateID"] as? Number)?.toInt() == 3 }
                    if (naviState != null) {
                        val active = naviState["state"] == true
                        appendLog("CarPlay navigation appState changed: active=$active")
                    }
                }
            }
        }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = report@{ status ->
        if (controllerGeneration != restartGeneration) return@report
        if (menuOpen) {
            if (status is CarPlayStatus.Failed) failurePendingAfterMenu = status
            return@report
        }
        updateHotspotStatus(status)
        val description = status.describe()
        if (status is CarPlayStatus.Failed) setFailedConnectionStage(status.message)
        else setConnectionStage(description, exact = true)
        when (status) {
            // The phone is gone (or was replugged): a fresh attach episode starts,
            // so the legacy wired no-TCP budget starts over (issues #275/#243).
            is CarPlayStatus.WaitingForIphone -> {
                wiredNoTcpFailures = 0
                wiredUsbBusyFailures = 0
                wiredUsbStallFailures = 0
            }
            is CarPlayStatus.Failed -> if (status.wifiResetRequired) {
                wifiRecoveryButton?.visibility = View.VISIBLE
            } else {
                wifiRecoveryButton?.visibility = View.GONE
                if (status.startupFailure != null) {
                    if (startupFailureGeneration == controllerGeneration) return@report
                    startupFailureGeneration = controllerGeneration
                }
                reconnectAfterLoss(description, status.startupFailure)
            }
            else -> Unit
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        // The sink's pacing is fixed when it is built; a session from before a Smooth video change (for
        // example one whose reconnect stopped at a prerequisite) is stopped instead of adopted. Callers
        // retry, and the next start matches this view.
        if (!backgroundSessionMatchesView(snapshot.sink.videoPacingEnabled, smoothVideo)) {
            appendLog("Background session smooth video=${snapshot.sink.videoPacingEnabled} differs from this view; " +
                "stopping it instead of adopting it")
            CarPlayBackgroundSession.stop { mainHandler.post { if (!isDestroyed) maybeStartCarPlay() } }
            return false
        }
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.currentAttempt(this)
        controller = snapshot.controller
        updateClusterMapShown()
        sink = snapshot.sink
        snapshot.sink.setOnAudioFocusGainListener {
            runOnUiThread {
                if (activeAirPlaySession != null && !AirPlayPersistence.loadCarBluetoothAudio(this)) {
                    controller?.takeMainAudio()
                }
            }
        }
        sessionDisplay = snapshot.display
        resetSidePanel()
        if (snapshot.display.viewAreas?.let { it.kindOf(it.current) == CarPlayViewAreas.Kind.SIDE_PANEL } == true) {
            // The live stream can outlast its Activity. Restore the Android view covering its
            // unused strip instead of leaving a blank third when a new host adopts the session.
            sidePanelShown = true
            sidePanel?.visibility = View.VISIBLE
            sidePanelTick.run()
        }
        MapMirrors.reapply()
        CarPlayBackgroundSession.store(snapshot.controller, snapshot.sink, snapshot.width, snapshot.height,
            this, snapshot.display) { completion ->
            runOnUiThread {
                shutdown(false, "DiPlay disconnect", completion)
                finish()
            }
        }
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
        }
        videoView?.let { updateVideoLayout(it.width, it.height) }
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) {
                scheduleDisplaySize(view.width, view.height)
            }
        }

        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(restartGeneration, type, active)
        }
        currentSurface?.let(::attachSurface)
        sink?.refreshPicture(SCREEN_TYPE_MAIN)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                getString(R.string.carplay_service_already_running)
            } else {
                getString(R.string.carplay_session_already_running)
            },
        )
        updateDebugOverlays()
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || controller != null) return
        if (wirelessEnabled) {
            val hotspotError = manualHotspotSettingsError()
            if (hotspotError != null) {
                appendLog("Wireless CarPlay configuration incomplete: $hotspotError")
                setConnectionStage(hotspotError)
                return
            }
        } else {
            runCatching {
                com.shilapi.xcertplay.bluetooth.CarPlayBluetoothManager.get(this).disable()
            }
        }
        val controllerGeneration = restartGeneration
        val config = createRuntimeConfig()
        val effectiveSize = if (isMultiWindowActive() && !AirPlayPersistence.loadAdaptPipResolution(this) &&
            maximumDetectedWidthPixels >= size.width && maximumDetectedHeightPixels >= size.height &&
            (maximumDetectedWidthPixels > size.width || maximumDetectedHeightPixels > size.height)) {
            DisplaySize(maximumDetectedWidthPixels, maximumDetectedHeightPixels)
        } else {
            size
        }
        val airPlayConfig = createAirPlayConfig(effectiveSize)
        val locationProvider: Iap2LocationProvider? =
            when {
                !config.locationReportingEnabled -> null
                GuangqiCluster.present(this) -> VehicleSpeedLocationProvider(
                    com.shilapi.xcertplay.location.GuangqiNmeaLocationProvider(this),
                    com.shilapi.xcertplay.hud.GuangqiVehicleSpeedSource,
                )
                else -> AndroidCarPlayLocationProvider(this)
            }
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${displayScalePercent}%) " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"}" +
                "${if (GuangqiCluster.present(this@CarPlayHostActivity)) "+guangqi-nmea+can-speed" else if (config.identification.vehicleSpeedEnabled) "+wheel-speed" else ""} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${displayScalePercent}% " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val renderer = createMediaSink(
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            controllerGeneration = controllerGeneration,
        )
        sink = renderer
        renderer.setOnAudioFocusGainListener {
            runOnUiThread {
                if (activeAirPlaySession != null && !AirPlayPersistence.loadCarBluetoothAudio(this)) {
                    controller?.takeMainAudio()
                }
            }
        }
        currentSurface?.let(::attachSurface)
        clusterSurface?.let { renderer.setSurface(SCREEN_TYPE_ALT, it) }
        MapMirrors.reapply()
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
            locationProvider = locationProvider,
            vehicleStatusProvider = null,
        )
        controller = next
        // Issues #327/#350: on a video-only recovery attempt, re-ask the phone
        // for the screen (changeModes Take Screen); wireless sessions no-op
        // inside takeMainAudioAndScreen by design.
        media.videoRecoveryAction = { next.takeMainAudioAndScreen() }
        resetSidePanel() // a new session starts without the side panel
        updateClusterMapShown()
        CarPlayMediaKeys.attach(this, next)
        if (airPlayConfig.videoInCar) CarPlayVideo.attach(this, next)
        val display = CarPlaySessionDisplay(
            airPlayConfig.main.widthPixels, airPlayConfig.main.heightPixels,
            displayRotation(), hideTopBar, hideBottomBar, effectiveSize.width, effectiveSize.height,
            viewAreas = pendingViewAreas,
        )
        sessionDisplay = display
        videoView?.let { updateVideoLayout(it.width, it.height) }
        CarPlayBackgroundSession.store(next, renderer, size.width, size.height, this, display) { completion ->
            runOnUiThread {
                shutdown(terminateProcess = false, reason = "DiPlay disconnect", completion = completion)
                finish()
            }
        }
        try {
            val service = Intent(this, DiPlaySessionService::class.java)
            if (realSdkInt() >= Build.VERSION_CODES.O) startForegroundService(service)
            else startService(service)
            next.start()
        } catch (error: Throwable) {
            appendLog("Connection could not start: ${error.javaClass.simpleName}")
            shutdown(false, "foreground service could not start")
            setConnectionStage(getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per))
        }
    }

    private fun refreshConfiguration(
        newConfig: Configuration = resources.configuration,
        source: ThemeModeDiagnostics.Source,
    ) {
        if (lastConfiguration == newConfig) {
            logThemeState(source, newConfig)
            return
        }
        // resources.configuration is mutated in place, so keep a copy to compare against.
        lastConfiguration = Configuration(newConfig)
        nightModeDiagnosticSource = source
        try {
            nightModeOrNull(newConfig.uiMode)?.let { nightModeController.systemChanged(it) }
        } finally {
            nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
        }
        logThemeState(source, newConfig)
    }

    private fun logThemeState(source: ThemeModeDiagnostics.Source, configuration: Configuration) {
        themeDiagnostics.observe(
            source, configuration.uiMode, darkMode, activeAirPlaySession != null, SystemClock.elapsedRealtime(),
        )?.let(::appendLog)
    }

    private fun syncAirPlayDarkMode(source: ThemeModeDiagnostics.Source) {
        val session = activeAirPlaySession
        val night = nightModeController.night
        darkMode = night
        if (session == null) {
            appendLog("THEME_DIAGNOSTIC request source=${source.label} applied=${if (night) "dark" else "light"} sessionActive=false")
            return
        }
        try {
            airPlayCommandExecutor.execute {
                try {
                    val sent = session.setNightMode(night)
                    // A successful write does not prove the iPhone changed its appearance.
                    appendLog(
                        "THEME_DIAGNOSTIC send source=${source.label} applied=${if (night) "dark" else "light"} commandWritten=$sent",
                    )
                    Log.i(
                        TAG,
                        "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                    )
            } catch (error: Throwable) {
                val failureClass = error.javaClass.simpleName.take(64)
                    .filter { it.isLetterOrDigit() || it == '_' || it == '$' }
                    .ifEmpty { "unknown" }
                appendLog("THEME_DIAGNOSTIC send source=${source.label} failureClass=$failureClass")
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            // Issue #310: the executor is shut down in teardown while a
            // resume can still race it, and execute() itself throws outside
            // the runnable's own try. Dropping this best-effort send is
            // correct — it must never crash resume.
            appendLog("THEME_DIAGNOSTIC send source=${source.label} failureClass=RejectedExecutionException")
            Log.w(TAG, "Could not send AirPlay dark mode update: executor shut down", error)
        }
    }

    private fun audioCaptureDirectory(): File? {
        if (!File(filesDir, AUDIO_CAPTURE_MARKER).isFile) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == pendingDisplaySize) return
        mainHandler.removeCallbacks(applyDisplaySize)
        if (size == activeDisplaySize && !displayLayoutChanged()) {
            pendingDisplaySize = null
            // A rotation back can cancel the last pending resize after teardown has finished.
            maybeStartCarPlay()
            return
        }
        pendingDisplaySize = size
        // With a turning screen's square canvas nothing reconnects, so the turn need not wait for the size to settle.
        mainHandler.postDelayed(applyDisplaySize,
            if (sessionDisplay?.viewAreas?.turnsWithScreen == true) 0L else DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun isMultiWindowActive(): Boolean {
        return realSdkInt() >= Build.VERSION_CODES.N && isInMultiWindowMode
    }


    private fun applyDisplaySize(size: DisplaySize) {
        val display = sessionDisplay
        if (display != null && applyViewArea(size, display)) return
        val layoutChanged = displayLayoutChanged(size)
        if (shuttingDown.get()) return
        if (size == activeDisplaySize && !layoutChanged) {
            // Teardown may have removed the session that made this same-size rotation pending.
            maybeStartCarPlay()
            return
        }
        val previous = activeDisplaySize
        activeDisplaySize = size
        recordDetectedMaximum(size)
        updateResolutionMenu()
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
            reapplyVideoLayout()
        } else if (controller == null && display == null) {
            appendLog(
                "Display updated before CarPlay startup: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
            maybeStartCarPlay()
        } else if (display != null && !layoutChanged &&
            size.width <= display.windowWidth && size.height <= display.windowHeight) {
            // Keep camera shrink/restore cycles within the original window connected. If the
            // session started in a camera window, growth beyond it needs a full-size canvas.
            val message = "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}; " +
                "keeping CarPlay session canvas=${display.width}x${display.height}"
            appendLog(message)
            Log.i(TAG, message)
            reapplyVideoLayout()
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    /**
     * Entering or leaving the head unit's split screen, or turning the screen with a square canvas, moves
     * CarPlay to the matching view area instead of reconnecting. The split window is remembered per screen
     * orientation for the next session's area. False when the change is something else (a camera window,
     * a turn without a square canvas), which the usual path handles.
     */
    private fun applyViewArea(size: DisplaySize, display: CarPlaySessionDisplay): Boolean {
        val areas = display.viewAreas ?: return false
        if (controller == null || menuOpen || handshakeResetInProgress || shuttingDown.get()) return false
        val split = isMultiWindowActive()
        // A full window's own shape tells the screen's orientation; during a turn the display metrics and
        // rotation can lag behind the window. A split-screen window can have either shape, so it asks the
        // display, which is settled then.
        val portrait = if (split) screenPortrait() else size.height > size.width
        val previous = activeDisplaySize
        if (areas.turnsWithScreen && display.rotation != displayRotation() && size == previous) {
            // The turn arrived before the window's new size: note it and wait for the size, which picks
            // the area, instead of letting the old rotation look like a changed layout.
            sessionDisplay = display.copy(rotation = displayRotation())
            Log.i(TAG, "Screen turned before the window resized (${size.width}x${size.height}); waiting for its size")
            return true
        }
        val turned = previous != null && !split && (previous.height > previous.width) != portrait
        if (turned && !areas.turnsWithScreen) return false
        if (split) {
            // Against the whole screen, which the system bars (shown in BYD's split screen) do not change.
            val screen = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            val screenLong = maxOf(screen.widthPixels, screen.heightPixels).toFloat()
            val screenShort = minOf(screen.widthPixels, screen.heightPixels).toFloat()
            if (screenLong > 0 && screenShort > 0) {
                if (portrait) SplitScreenSettings.saveWindow(this, true, size.width / screenShort, size.height / screenLong)
                else SplitScreenSettings.saveWindow(this, false, size.width / screenLong, size.height / screenShort)
            }
        }
        val target = areas.indexFor(size.width, size.height, split, portrait) ?: return false
        if (sidePanelShown) resetSidePanel() // a turn or the split screen ends the side panel
        // The same whole-screen area without a turn (a camera window, say) keeps the usual handling.
        if (!turned && target == areas.current && areas.kindOf(target) == CarPlayViewAreas.Kind.FULL_SCREEN) return false
        activeDisplaySize = size
        sessionDisplay = display.copy(rotation = displayRotation())
        val sent = if (target == areas.current) true else controller?.showViewArea(target) == true
        if (sent) areas.use(target)
        videoView?.let { updateVideoLayout(it.width, it.height) }
        val message = "Window ${previous?.width}x${previous?.height} -> ${size.width}x${size.height} " +
            "split=$split portrait=$portrait turned=$turned: view area $target sent=$sent"
        appendLog(message)
        Log.i(TAG, message)
        return true
    }

    /** Whether the screen itself is portrait now (DiPlay's own window may be a split-screen part of it). */
    @Suppress("DEPRECATION")
    private fun screenPortrait(): Boolean {
        val metrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.heightPixels > metrics.widthPixels
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = videoView?.display?.rotation ?: windowManager.defaultDisplay.rotation

    private fun displayLayoutChanged(newSize: DisplaySize? = activeDisplaySize): Boolean {
        val display = sessionDisplay ?: return false
        if (display.rotation != displayRotation()) return true
        if (display.hideTopBar != hideTopBar || display.hideBottomBar != hideBottomBar) return true
        if (newSize != null && newSize.width > 0 && newSize.height > 0) {
            val baseAspect = display.width.toDouble() / display.height
            val currentAspect = newSize.width.toDouble() / newSize.height
            val aspectDiff = kotlin.math.abs(currentAspect / baseAspect - 1.0)
            if (aspectDiff > 0.08 && AirPlayPersistence.loadAdaptPipResolution(this)) {
                return true
            }
        }
        return false
    }



    private fun contentRect(viewWidth: Int, viewHeight: Int): CarPlayVideoLayout {
        val display = sessionDisplay ?: return CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        // In the head unit's split screen, or on a turning screen's square canvas, CarPlay draws in part of
        // the canvas; that area fills the window and the rest falls outside it. Video and touches both
        // follow this. An area of the whole canvas keeps the usual fit.
        display.viewAreas?.let { areas ->
            val area = areas.layoutArea(areas.current)
            if (area.width != display.width || area.height != display.height) {
                val scaleX = viewWidth.toFloat() / area.width
                val scaleY = viewHeight.toFloat() / area.height
                return CarPlayVideoLayout(-area.originX * scaleX, -area.originY * scaleY,
                    display.width * scaleX, display.height * scaleY)
            }
        }
        val base = CarPlayVideoLayout.fit(display.width, display.height, viewWidth, viewHeight)
        if (dockAutoHideEnabled && !dockRevealed && display.width > display.height && carPlayDock != CarPlayDock.BOTTOM) {
            val dockRatio = 0.13f
            val appRatio = 1f - dockRatio
            if (base.width > 0f && appRatio > 0f) {
                val scale = viewWidth.toFloat() / (base.width * appRatio)
                val newWidth = base.width * scale
                val newLeft = if (rightHandDrive) 0f else -newWidth * dockRatio
                return CarPlayVideoLayout(newLeft, base.top, newWidth, base.height)
            }
        }
        return base
    }

    /**
     * Issue #290: true while [bounds] is a transitional shrink below the size the
     * surface was established at (initial creation, #363, or the latest grow
     * re-add), inside the same settle window the
     * reapply listener uses (3 s). A genuine later shrink (a new reverse entry)
     * either exceeds the re-added size or arrives after the window and applies
     * normally.
     */
    private fun isTransitionalShrinkAfterReadd(bounds: CarPlaySurfaceBounds): Boolean {
        val readd = surfaceReaddBounds ?: return false
        if (bounds.width >= readd.width || bounds.height >= readd.height) return false
        return SystemClock.elapsedRealtime() - surfaceReaddAtMs < 3_000L
    }

    /**
     * Issue #363 (v0.3.14 retest): the head unit's dock bar can squeeze the
     * window vertically long after the settle window above — the layout fit
     * then shrinks the surface proportionally (a 1024x600 canvas fitted into
     * a 1024x500 window becomes 854x500) and the black borders latch. That
     * squeeze is not a content size change: the window keeps its width, only
     * its height is compressed, the fit is height-driven (the fitted bounds
     * fill the squeezed height and keep the canvas aspect), and the session
     * canvas itself is unchanged. Recognize it at any age and keep the
     * established size: the window clips the surface while the bar is up
     * and the picture is intact when it drops. A genuine reverse entry is
     * not this — while reversing, the reverse flag is set and the video
     * view has already yielded the display surface.
     */
    /**
     * Issue #363: the reporter's discriminator proved the dock bar is
     * dropped by task re-fronting, not by focus gain (focus returns after
     * every reverse exit and the bar stays). Bounded per squeeze episode
     * (3 attempts, 5 s apart) so a
     * launcher that refuses is not fought forever; the episode resets
     * when full height is restored.
     *
     * The start target is the launcher activity with NEW_TASK — the exact
     * form the reporter's manual test proved. The first implementation
     * re-fronted this host itself with REORDER_TO_FRONT, which the
     * v0.3.15 retest showed is a no-op: a singleTask host already at the
     * top of its own task has nothing to reorder, only onNewIntent fires
     * and the window layer never moves.
     */
    private fun maybeRefrontForDockBar() {
        if (!dockBarRefrontEnabled) return
        if (activeAirPlaySession == null || menuOpen || shuttingDown.get() ||
            handshakeResetInProgress || reverseCameraActive) return
        val now = SystemClock.elapsedRealtime()
        if (dockRefrontAttempts >= 3 || now - dockRefrontLastAttemptMs < 5_000L) return
        dockRefrontAttempts += 1
        dockRefrontLastAttemptMs = now
        appendLog("Display: dock squeeze detected; bringing the app task to the front (attempt $dockRefrontAttempts)")
        runCatching {
            startActivity(
                Intent(this, DiPlayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { appendLog("Display: task re-front failed: ${it.message}") }
    }

    private fun isDockSqueezedShrink(bounds: CarPlaySurfaceBounds, viewWidth: Int, viewHeight: Int): Boolean {
        if (reverseCameraActive) return false
        val baseline = surfaceReaddBounds ?: return false
        if (bounds.width >= baseline.width || bounds.height >= baseline.height) return false
        if (viewHeight - bounds.height > 1) return false
        // The squeezed fit keeps the established aspect (within rounding).
        // Compare against the baseline, not the current display size: a
        // display-changed callback can rewrite the latter to the squeezed
        // size before this layout pass runs, which made a canvas-referenced
        // comparison fail and let the small size through (v0.3.15 retest).
        return kotlin.math.abs(bounds.width * baseline.height - bounds.height * baseline.width) <= baseline.height
    }

    private fun updateVideoLayout(viewWidth: Int, viewHeight: Int) {
        val view = videoView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val content = contentRect(viewWidth, viewHeight)
        val fallback = fallbackVideoView
        if (fallback != null) {
            val bounds = CarPlaySurfaceBounds.from(content)
            if (fallback !== surfaceBaselineView && bounds.width > 0 && bounds.height > 0) {
                // Issue #363: arm the shrink-guard baseline from CREATION,
                // not from a size change. The 0.3.9 arming sat inside the
                // bounds-changed branch below; when a recreated surface's
                // first size equalled the previous surface's last bounds
                // (both 1024x600), that branch never ran, no baseline was
                // recorded, and the settle shrink 0.7 s later applied
                // unguarded. Keying on the view instance cannot miss:
                // every new surface arms at the first size it establishes.
                surfaceBaselineView = fallback
                surfaceReaddBounds = bounds
                surfaceReaddAtMs = SystemClock.elapsedRealtime()
            }
            // Issue #290 (0.3.5 retest): right after the grow re-add below recreates
            // the Surface at the restored size, the video view passes through a
            // transitional smaller size; fitting to it would setFixedSize the new
            // Surface back down and the settle listener would keep re-asserting
            // the small size, so the loop ended small instead of converging.
            val readdBounds = surfaceReaddBounds
            if (dockRefrontAttempts > 0 && readdBounds != null &&
                bounds.height >= readdBounds.height) {
                appendLog("Display: full height restored; dock re-front episode closed (attempts=$dockRefrontAttempts)")
                dockRefrontAttempts = 0
            }
            val transitionalShrink = fallbackVideoBounds != bounds && isTransitionalShrinkAfterReadd(bounds)
            if (transitionalShrink) {
                appendLog(
                    "Display: ignored transitional shrink to ${bounds.width}x${bounds.height} " +
                        "after surface re-add; keeping ${surfaceReaddBounds?.width}x${surfaceReaddBounds?.height}",
                )
            }
            val dockSqueezedShrink = fallbackVideoBounds != bounds && !transitionalShrink &&
                isDockSqueezedShrink(bounds, viewWidth, viewHeight)
            if (dockSqueezedShrink) {
                appendLog(
                    "Display: ignored dock-squeezed shrink to ${bounds.width}x${bounds.height}; " +
                        "keeping ${surfaceReaddBounds?.width}x${surfaceReaddBounds?.height}",
                )
                maybeRefrontForDockBar()
            }
            if (fallbackVideoBounds != bounds && !transitionalShrink && !dockSqueezedShrink) {
                val previousBounds = fallbackVideoBounds
                fallbackVideoBounds = bounds
                if (previousBounds == null && bounds.width > 0 && bounds.height > 0) {
                    // Issue #363: the transitional-shrink guard below was only
                    // armed by the grow re-add, so a surface created directly
                    // at its full size (Activity resume) had no baseline: the
                    // settle shrink ~1 s later applied and latched, leaving
                    // black borders. Arm the same baseline at first
                    // establishment; a genuine shrink after the window still
                    // applies normally.
                    surfaceReaddBounds = bounds
                    surfaceReaddAtMs = SystemClock.elapsedRealtime()
                }
                fallback.layoutParams = FrameLayout.LayoutParams(bounds.width, bounds.height,
                    Gravity.TOP or Gravity.LEFT).apply {
                    leftMargin = bounds.left
                    topMargin = bounds.top
                }
                if (bounds.width > 0 && bounds.height > 0) {
                    fallback.holder.setFixedSize(bounds.width, bounds.height)
                    // Issue #290: the reapply log alone could not prove whether
                    // this branch ran, so a retest could not tell a skipped
                    // resize from a resize the Surface ignored. Trace it.
                    appendLog(
                        "Display: fallback surface fixed size " +
                            "${bounds.width}x${bounds.height} (was " +
                            "${previousBounds?.width}x${previousBounds?.height})",
                    )
                }
                fallback.requestLayout()
                fallback.invalidate()
                if (previousBounds != null && bounds.height > previousBounds.height) {
                    surfaceReaddBounds = bounds
                    surfaceReaddAtMs = SystemClock.elapsedRealtime()
                    // Issue #290: on some firmwares a live Surface ignores
                    // setFixedSize — the size is latched at creation. A
                    // reverse window is shorter than full screen, so on the
                    // return trip the window grows back taller but the
                    // surface stays at the reverse size, leaving borders on
                    // all four sides until something rebuilds it (re-entering
                    // from the desktop recreates the surface, which is why
                    // that workaround clears the borders). Force one
                    // recreation here: remove the view and re-add it so
                    // a new surface is created at the fixed size requested
                    // above. Height growth is the reverse-return signature;
                    // width-only growth (dock reveal) is left untouched.
                    // Issue #290 (0.3.4 retest): hiding and re-showing the
                    // same view did NOT recreate its Surface on this
                    // firmware — the picture stayed at the reverse size
                    // even though the recreate log fired. Removing the
                    // view from its parent and re-adding it forces
                    // surfaceDestroyed / surfaceCreated, the same
                    // lifecycle desktop re-entry already survives.
                    val parent = fallback.parent as? ViewGroup
                    if (parent != null) {
                        // Issue #290 (0.3.5/0.3.6 startup crash): removing and
                        // re-adding the view synchronously inside this layout
                        // pass crashes on Android 5.1.1 — the following layout
                        // dereferences the SurfaceView's not-yet-ready window
                        // (startup size oscillation 600->500->600, 6 of 6
                        // crashes). Post it to after the current traversal
                        // and re-validate the view state there.
                        fallback.post {
                            if (isDestroyed || fallbackVideoView !== fallback) return@post
                            val currentParent = fallback.parent as? ViewGroup ?: return@post
                            val index = currentParent.indexOfChild(fallback)
                            if (index < 0) return@post
                            currentParent.removeView(fallback)
                            currentParent.addView(fallback, index, fallback.layoutParams)
                            appendLog(
                                "Display: fallback surface re-add after grow to " +
                                    "${bounds.width}x${bounds.height} (was " +
                                    "${previousBounds.width}x${previousBounds.height})",
                            )
                            sink?.refreshPicture(SCREEN_TYPE_MAIN)
                        }
                    } else {
                        fallback.visibility = View.GONE
                        fallback.post {
                            if (fallbackVideoView === fallback) fallback.visibility = View.VISIBLE
                        }
                        appendLog(
                            "Display: fallback surface re-add after grow to " +
                                "${bounds.width}x${bounds.height} (was " +
                                "${previousBounds.width}x${previousBounds.height})",
                        )
                    }
                }
                if (previousBounds != null && (bounds.width > previousBounds.width || bounds.height > previousBounds.height)) {
                    sink?.refreshPicture(SCREEN_TYPE_MAIN)
                }
            }
        } else {
            (view as? TextureView)?.setTransform(Matrix().apply {
                setScale(content.width / viewWidth, content.height / viewHeight)
                postTranslate(content.left, content.top)
            })
        }
        if (sidePanelShown) placeSidePanel(viewWidth, viewHeight)
    }

    /**
     * Recompute the fitted video bounds now and once more after the pending layout
     * pass. Around camera shrink/restore cycles some ROMs deliver the size change
     * before the video view has actually been laid out at it, so a single pass can
     * fit the picture to the size the window just left — the picture then stays
     * letterboxed at the shrunken size until the process restarts (issue #290).
     */
    private fun reapplyVideoLayout() {
        val view = videoView ?: return
        updateVideoLayout(view.width, view.height)
        view.post {
            if (isDestroyed || videoView !== view) return@post
            updateVideoLayout(view.width, view.height)
            // Issue #290: the recompute left no trace in a report, so a retest
            // could not prove whether it ran. Log the settled view size and the
            // fitted content size it produced.
            val content = contentRect(view.width, view.height)
            appendLog(
                "Display: video layout reapplied view=${view.width}x${view.height} " +
                    "content=${content.width.toInt()}x${content.height.toInt()}",
            )
            armSettledVideoReapply(view)
        }
    }

    /**
     * Issue #290: some ROMs settle the video view's size over several layout
     * passes after a camera cycle, so even the posted recompute can run against
     * a mid-transition size and nothing re-triggers it afterwards. Recompute
     * on every size change inside the settle window, then disarm; the delayed
     * disarm keeps a later unrelated resize from being mistaken for this
     * cycle's settle. (An earlier one-shot version removed the listener on
     * the first change, so an intermediate size consumed it and the final
     * size never recomputed — the stuck small canvas behind the reverse
     * black border and the first-entry bottom bar.)
     */
    private fun armSettledVideoReapply(view: View) {
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                changedView: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                if ((right - left) == (oldRight - oldLeft) && (bottom - top) == (oldBottom - oldTop)) return
                if (isDestroyed || videoView !== changedView) return
                updateVideoLayout(changedView.width, changedView.height)
                val content = contentRect(changedView.width, changedView.height)
                appendLog(
                    "Display: video layout reapplied after settle view=${changedView.width}x${changedView.height} " +
                        "content=${content.width.toInt()}x${content.height.toInt()}",
                )
                sink?.refreshPicture(SCREEN_TYPE_MAIN)
            }
        }
        view.addOnLayoutChangeListener(listener)
        view.postDelayed({ runCatching { view.removeOnLayoutChangeListener(listener) } }, 3_000L)
    }

    private fun revealDockTemporarily(view: View) {
        if (!dockRevealed) {
            dockRevealed = true
            updateVideoLayout(view.width, view.height)
            controller?.sendTouch(emptyList())
        }
        mainHandler.removeCallbacks(hideDockRunnable)
        mainHandler.postDelayed(hideDockRunnable, 5000L)
    }

    private fun hideDock(view: View) {
        mainHandler.removeCallbacks(hideDockRunnable)
        if (dockRevealed) {
            dockRevealed = false
            updateVideoLayout(view.width, view.height)
            controller?.sendTouch(emptyList())
        }
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (shuttingDown.get() || !startupAutomationReady ||
            (startupAutomation != null && !startupPrerequisitesRequested)) return
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) {
            if (!adoptBackgroundSession()) mainHandler.postDelayed({ maybeStartCarPlay() }, 500)
            return
        }
        if (controller == null && adoptBackgroundSession()) return
        // The video view is chosen once per activity; a changed video surface setting needs a new one.
        if (controller == null && (AirPlayPersistence.loadSmoothVideo(this) != smoothVideo ||
            AirPlayPersistence.loadDirectSurfaceView(this) != directSurfaceView)) {
            appendLog("Video surface setting changed; rebuilding the video view")
            // No session runs here, but a restart keeps this host as the session owner; the new instance
            // must be able to start its own.
            if (CarPlayBackgroundSession.isOwner(this)) CarPlayBackgroundSession.clear()
            recreate()
            return
        }
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            menuOpen ||
            handshakeResetInProgress ||
            startupRetryStopped ||
            pendingDisplaySize != null ||
            controller != null
        ) {
            return
        }
        startCarPlay(size)
    }

    /**
     * Issues #143/#144: the head unit's netd VPN state can wedge (stale fwmark uid/rule
     * from a previous session not cleaned up). The framework's establish() then fails
     * identically every time with "Invalid argument" / "Device or resource busy" / "No such
     * device" — the app cannot clear netd state, only a reboot (or app restart) recovers it.
     * Detect this from the translated hint text so the retry loop stops instead of hammering
     * a doomed establish forever.
     */
    private fun isUnrecoverableVpnState(reason: String): Boolean {
        val lower = reason.lowercase()
        // Matches the hints produced by CarPlayVpnService.withStaleUidRuleHint /
        // withWedgedVpnHint: netd rejected the fwmark rule and only a reboot recovers it.
        return lower.contains("netd") &&
            (lower.contains("invalid argument") ||
                lower.contains("device or resource busy") ||
                lower.contains("no such device")) &&
            lower.contains("reboot")
    }

    // Issue #290: visible prompt after 4 consecutive wireless link failures.
    // Dismissing it does not reset the streak (the shown flag persists), so
    // it never nags twice for the same streak; only a session showing video
    // resets.
    private fun showWirelessRepairPrompt() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            appendLog("wireless link failures reached prompt threshold; showing forget-and-repair prompt")
            AlertDialog.Builder(this)
                .setTitle(R.string.wireless_forget_device_title)
                .setMessage(R.string.wireless_forget_device_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun reconnectAfterLoss(reason: String, startupFailure: WirelessStartupFailure? = null) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (menuOpen) recoveryPendingAfterMenu = true
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || startupRetryStopped) return
        if (reconnectScheduled) return
        // Issues #143/#144: when the head unit's netd VPN state is wedged (stale fwmark
        // uid/rule rejected with "Invalid argument" / "Device or resource busy" / "No such
        // device"), the app cannot clear it — every re-establish fails identically. Stop the
        // automatic retry loop instead of hammering a doomed establish; the user must reboot.
        if (isUnrecoverableVpnState(reason)) {
            appendLog("wired VPN netd state unrecoverable; automatic retry stopped, reboot required generation=$restartGeneration")
            setConnectionStage(reason)
            return
        }
        // Issues #275/#243: on pre-Lollipop units (T3 / Android 4.4 class) a wired
        // "No AirPlay TCP" failure is deterministic while the phone stays plugged in,
        // and every rebuild risks another USB re-enumeration cycle, which can wedge
        // the kernel USB hub and freeze the whole unit. Give it one automatic retry,
        // then stop and ask for a physical replug instead of looping forever. The
        // counter resets when a session activates, when the user taps Retry, or when
        // the controller reports it is waiting for a (re)plugged iPhone.
        if (!wirelessEnabled && realSdkInt() < Build.VERSION_CODES.LOLLIPOP &&
            reason.contains("No AirPlay TCP after CarPlay StartSession")
        ) {
            wiredNoTcpFailures += 1
            if (wiredNoTcpFailures >= WIRED_NO_TCP_FAILURE_LIMIT) {
                appendLog("wired no-TCP failures=$wiredNoTcpFailures on legacy API ${realSdkInt()}; automatic retry stopped, manual replug required generation=$restartGeneration")
                // Issue #298: mirror the wireless-exhausted branch — without this flag,
                // repeated failure reports kept re-entering here and re-logging.
                startupRetryStopped = true
                startupRetryButton?.visibility = View.VISIBLE
                setConnectionStage(getString(R.string.wired_no_tcp_replug_required))
                return
            }
        }
        // Issue #305: a USB-busy (EBUSY) failure repeats identically while the
        // previous round's interface claim lingers — the outer retry loop
        // would otherwise hammer it forever with a growing backoff. Mirror
        // the wired-no-TCP stop: after a few consecutive busy failures, stop
        // the automatic loop and ask for a physical replug instead. The VPN
        // guard above already claimed "Device or resource busy" reasons, so
        // matching "usbfs" here only catches the USB-select path.
        if (!wirelessEnabled && reason.contains("usbfs", ignoreCase = true)) {
            wiredUsbBusyFailures += 1
            if (wiredUsbBusyFailures >= WIRED_USB_BUSY_FAILURE_LIMIT) {
                appendLog("wired USB busy failures=$wiredUsbBusyFailures; automatic retry stopped, manual replug required generation=$restartGeneration")
                startupRetryStopped = true
                startupRetryButton?.visibility = View.VISIBLE
                setConnectionStage(getString(R.string.usb_iphone_busy_or_occupied))
                return
            }
        }
        // Issue #359: after an ENODEV drop the iPhone's USB state can wedge so
        // that every configuration request stalls outright; the outer loop then
        // repeats the identical failure forever (200+ STALLs in the reporter's
        // log, backoff already at its cap). Mirror the busy stop: after a few
        // consecutive all-stalled failures, stop the automatic loop and ask
        // for the phone-side reset that unwedges it (unplug + restart the
        // iPhone). The STALL hint only appears when every attempt of a
        // configuration request stalled, which is exactly the wedged state.
        if (!wirelessEnabled && reason.contains("USB STALL", ignoreCase = true)) {
            wiredUsbStallFailures += 1
            if (wiredUsbStallFailures >= WIRED_USB_STALL_FAILURE_LIMIT) {
                appendLog("wired USB stall failures=$wiredUsbStallFailures; automatic retry stopped, iPhone restart required generation=$restartGeneration")
                startupRetryStopped = true
                startupRetryButton?.visibility = View.VISIBLE
                setConnectionStage(getString(R.string.wired_usb_stall_replug_required))
                return
            }
        }
        // Issue #290: count only genuine link-establishment failures (the
        // iPhone never opens AirPlay TCP). Hotspot-not-ready and
        // hotspot-configuration failures are car-side problems that
        // forgetting the device on the iPhone would not fix, so they do not
        // count. At the threshold the driver gets a visible prompt to forget
        // the device on the iPhone and re-pair.
        if (startupFailure == WirelessStartupFailure.FIRST_TCP_TIMEOUT) {
            val showPrompt = wirelessLinkFailurePrompt.onFailure()
            AirPlayPersistence.saveWirelessLinkFailures(this, wirelessLinkFailurePrompt.consecutiveFailures)
            AirPlayPersistence.saveWirelessRepairPromptShown(this, wirelessLinkFailurePrompt.promptShown)
            if (showPrompt) showWirelessRepairPrompt()
        }
        val startupDelay = if (startupFailure != null && startupFailure != WirelessStartupFailure.HOTSPOT_CONFIGURATION)
            startupRetryBudget.nextDelayMillis() else null
        if (startupFailure != null && startupDelay == null) {
            startupRetryStopped = true
            startupRetryButton?.visibility = View.VISIBLE
            setConnectionStage(if (startupFailure == WirelessStartupFailure.HOTSPOT_CONFIGURATION) reason
                else "$reason\n${getString(R.string.wireless_startup_retries_exhausted)}")
            appendLog("wireless startup recovery stopped generation=$restartGeneration reason=$startupFailure detail=$reason retries=${startupRetryBudget.retries}")
            return
        }
        reconnectScheduled = true
        val generation = restartGeneration
        val delayMillis = if (startupDelay != null) {
            startupDelay
        } else if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            (RECONNECT_DELAY_MILLIS * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
        }
        reconnectAttempts += 1
        appendLog("$reason; retrying in ${delayMillis}ms generation=$generation startupFailure=$startupFailure startupRetries=${startupRetryBudget.retries}")
        mainHandler.postDelayed(
            {
                reconnectScheduled = false
                if (menuOpen && generation == restartGeneration) recoveryPendingAfterMenu = true
                if (
                    shuttingDown.get() ||
                    menuOpen ||
                    handshakeResetInProgress || startupRetryStopped ||
                    generation != restartGeneration
                ) {
                    return@postDelayed
                }
                restartCarPlay("Reconnecting after $reason")
            },
            delayMillis,
        )
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        // Issue #359: the stop is a hard gate for this connection episode.
        // Automatic restart paths (display changes, deferred recoveries,
        // controller callbacks) must not rebuild the stack behind it; only
        // clearStartupRetryStop() releases it.
        if (startupRetryStopped) {
            appendLog("restart suppressed while automatic retry is stopped: $reason")
            return
        }
        val size = activeDisplaySize ?: return
        startupRetryBudget.disconnected()
        startupRetryButton?.visibility = View.GONE
        appendLog(reason)
        secondScreen.stop()
        invalidateMainPicture()
        activeScreenStreamTypes.clear()
        syncGuangqiClusterReady()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        handshakeResetInProgress = true
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController, keepOwner = true)
        controller = null
        oldSink?.let(retiringSinks::add)
        sink = null
        sessionDisplay = null
        val diagnosticLog = sessionLog
        teardownExecutor.execute {
            val started = System.nanoTime()
            oldController?.close()
            val completed = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            AsyncDiagnosticLog.append(
                diagnosticLog,
                "${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} generation=$generation " +
                    "restart teardownWaitCompleted=$completed " +
                    "elapsedMs=${((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0)}",
            )
            oldSink?.let(::closeRetiringSink)
            runOnUiThread {
                if (!shuttingDown.get() && generation == restartGeneration) {
                    handshakeResetInProgress = false
                    // The display can rotate again while the old stack is closing. Use the
                    // latest accepted size, and wait for any pending resize to settle first.
                    maybeStartCarPlay()
                }
            }
        }
    }

    private fun showDiPlayHome(page: String = "home") {
        controller?.sendTouch(emptyList())
        startActivity(Intent(this, DiPlayActivity::class.java)
            .putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /** Keep a timed-out sink visible to surface teardown until its codecs have actually been released. */
    private fun closeRetiringSink(owner: AndroidMediaSink) {
        owner.close()
        val owners = retiringSinks
        owner.whenVideoReleased { owners.remove(owner) }
        // Preserve bounded teardown sequencing without blocking the UI or forgetting a live worker.
        owner.awaitVideoReleased(sinkReleaseWaitMillis)
    }

    private fun openSettingsMenu() {
        if (menuOpen) return
        controller?.sendTouch(emptyList())
        loadPersistedSettings()
        settingsBaseline = captureSettingsBaseline()
        // Rebuild controls from saved values so a cancelled edit cannot reappear on reopening.
        settingsMenu?.let { previous ->
            // The old menu may already be detached (hierarchy torn down and rebuilt);
            // a hard cast on a null parent used to crash the process from the gesture
            // handler after repeated opens. Fall back to the content root so the menu
            // still shows instead of crashing.
            val parent = previous.parent as? ViewGroup
                ?: findViewById<ViewGroup>(android.R.id.content)
                ?: return@let
            val index = parent.indexOfChild(previous).takeIf { it >= 0 } ?: parent.childCount
            if (previous.parent === parent) parent.removeView(previous)
            quickMenuNeedsReconnect = false
            settingsMenu = buildQuickMenu()
            parent.addView(settingsMenu, index.coerceAtMost(parent.childCount), FrameLayout.LayoutParams(-1, -1))
        }
        menuOpen = true
        quickMenuShowing = true
        applyFullscreenMode()
        gestureOverlay?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        safeAreaEditor?.visibility = View.GONE
        updateSafeAreaSummary()
        updateResolutionMenu()
        updateDebugOverlays()
    }

    private fun openPicturePanel() {
        closePicturePanel()
        val generation = picturePanelGeneration
        val root = videoView?.parent as? FrameLayout ?: return
        controller?.sendTouch(emptyList())
        root.post {
            if (generation != picturePanelGeneration || isFinishing || isDestroyed) return@post
            val panel = CarPlayPicturePanel(this,
                adjustmentsAvailable = fallbackVideoView == null, close = ::closePicturePanel)
            val availableWidth = (root.width - dp(24)).coerceAtLeast(1)
            val width = minOf(dp(420), if (root.width < dp(800)) availableWidth else (root.width * 0.42f).toInt())
            val height = minOf(dp(540), root.height - dp(24)).coerceAtLeast(1)
            root.addView(panel, FrameLayout.LayoutParams(width, height, Gravity.END or Gravity.TOP).apply {
                topMargin = dp(12); marginEnd = dp(12)
            })
            picturePanel = panel
        }
    }

    private fun closePicturePanel() {
        ++picturePanelGeneration
        picturePanel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        picturePanel = null
        CarPlayPicture.showOriginal(false)
    }

    private fun cancelSettingsEdits() {
        if (!menuOpen) return
        // Only the full menu buffers edits; quick-menu changes are already saved and may still need
        // the reconnect they announced.
        restoreSettingsBaseline()
        finishSettingsMenu("Settings changes discarded", reconnect = quickMenuNeedsReconnect)
    }

    /** Done in the quick menu: its changes are saved; reconnects only when one of them needs it. */
    private fun closeQuickMenu() {
        if (!menuOpen) return
        settingsBaseline = null
        finishSettingsMenu(if (quickMenuNeedsReconnect) "Quick settings saved" else "Quick settings closed",
            reconnect = quickMenuNeedsReconnect)
    }

    /** From the quick menu to the full in-session menu, in the same place. */
    private fun finishSettingsMenu(prefix: String, reconnect: Boolean) {
        if (!menuOpen) return
        menuOpen = false
        quickMenuNeedsReconnect = false
        quickMenuShowing = false
        applyFullscreenMode()
        settingsMenu?.visibility = View.GONE
        gestureOverlay?.visibility = View.VISIBLE
        settingsGestureHint?.text = getString(R.string.open_diplay_settings_hint, gestureFingerCount)
        updateDebugOverlays()
        logLines.clear()
        appendLog(
            "$prefix; resolution " +
                "${displayScalePercent}% with " +
                (if (hevcEnabled) "HEVC (H.265)" else "H.264") +
                ", MFI ${mfiTargetLabel(mfiTarget)}" +
                ", Wi-Fi session ${hotspotModeLabel(wirelessHotspotMode)}",
        )
        val failure = failurePendingAfterMenu
        failurePendingAfterMenu = null
        val recoveryPending = recoveryPendingAfterMenu || failure != null
        recoveryPendingAfterMenu = false
        if (reconnect) {
            startupRetryBudget.manualRetry()
            startupRetryStopped = false
        }
        if (failure?.startupFailure != null && !reconnect) {
            createStatusReporter(restartGeneration)(failure)
        } else if (failure?.wifiResetRequired == true && (!reconnect || wirelessEnabled)) {
            createStatusReporter(restartGeneration)(failure)
        } else if (handshakeResetInProgress) {
            startAfterHandshakeReset = true
        } else if ((reconnect || recoveryPending) && controller != null) {
            restartCarPlay(if (reconnect) "Settings saved; reconnecting" else "Connection lost while settings were open; reconnecting")
        } else {
            maybeStartCarPlay()
        }
    }

    private fun shutdown(terminateProcess: Boolean, reason: String, completion: () -> Unit = {}) {
        if (!shuttingDown.compareAndSet(false, true)) { completion(); return }
        startupAutomation?.close()
        secondScreen.stop()
        invalidateMainPicture()
        resetSidePanel()
        startupRetryBudget.disconnected()
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        oldSink?.let(retiringSinks::add)
        sink = null
        sessionDisplay = null
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.let(::closeRetiringSink)
            airPlayCommandExecutor.shutdown()
            if (terminateProcess) {
                applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java))
            }
            Log.i(TAG, "shutdown complete clean=$clean")
            applicationContext.stopService(Intent(applicationContext, DiPlaySessionService::class.java))
            teardownExecutor.shutdown()
            mainHandler.post { completion() }
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun observeVideoWindow(texture: TextureView) {
        val probe = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                // Current ViewCompat delegates directly to the API 19 method.
                // API 17/18 must inspect the window token without invoking that method.
                val attached = if (realSdkInt() >= Build.VERSION_CODES.KITKAT) {
                    texture.isAttachedToWindow
                } else {
                    texture.windowToken != null
                }
                if (!attached) return true
                removeVideoSurfaceProbe()
                if (isDestroyed || videoView !== texture) return true
                val mode = carPlayVideoSurfaceMode(texture.isHardwareAccelerated, smoothVideo, directSurfaceView)
                appendLog("Video output mode=$mode windowHardwareAccelerated=${texture.isHardwareAccelerated} " +
                    "smoothVideo=$smoothVideo directSurfaceView=$directSurfaceView")
                if (mode == CarPlayVideoSurfaceMode.TEXTURE) return true
                useFallbackVideoSurface(texture)
                return false // Measure the replacement before drawing the software window.
            }
        }
        videoSurfaceProbe = probe
        texture.viewTreeObserver.addOnPreDrawListener(probe)
    }

    private fun removeVideoSurfaceProbe() {
        val probe = videoSurfaceProbe ?: return
        videoView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(probe)
        videoSurfaceProbe = null
    }

    private fun useFallbackVideoSurface(texture: TextureView) {
        val root = texture.parent as? FrameLayout ?: return
        val index = root.indexOfChild(texture)
        val viewport = FrameLayout(this).apply {
            clipChildren = true
            // Issue #283: the wrapper must not reintroduce the rounded clipping the
            // toggle removed from the video views themselves.
            if (AirPlayPersistence.loadForceSquareCorners(this@CarPlayHostActivity) && realSdkInt() >= 21) {
                outlineProvider = null
                clipToOutline = false
            }
        }
        val surfaceView = SurfaceView(this).apply {
            // Same square-corner treatment as the TextureView path (issue #240).
            if (AirPlayPersistence.loadForceSquareCorners(this@CarPlayHostActivity) && realSdkInt() >= 21) {
                outlineProvider = null
                clipToOutline = false
            }
        }
        pictureBinding?.close()
        pictureBinding = null
        // Detach our wrapper before removing its TextureView; a late destruction callback
        // must not clear the framework-owned replacement surface.
        videoSurfaceOwner.clear()
        currentSurfaceTexture = null
        texture.surfaceTextureListener = null
        root.removeView(texture)
        videoView = viewport
        fallbackVideoView = surfaceView
        surfaceView.holder.addCallback(fallbackSurfaceCallback)
        viewport.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        viewport.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            updateVideoLayout(width, height)
            if (width != oldRight - oldLeft || height != oldBottom - oldTop) {
                scheduleDisplaySize(width, height)
            }
        }
        root.addView(viewport, index, texture.layoutParams)
        appendLog(when {
            smoothVideo ->
                "Using SurfaceView video output: smooth video, frames shown at the iPhone's frame time + a delay " +
                    "starting at ${smoothVideoDelayMillis(fps)} ms; picture adjustments unavailable"
            directSurfaceView ->
                "Using SurfaceView video output: direct hardware overlay (low CPU/GPU overhead); picture adjustments unavailable"
            else ->
                "Using SurfaceView video output: window has no hardware acceleration; picture adjustments unavailable"
        })
    }

    /** A dimension of the platform's own resources in pixels, or 0 when this build has none by [name]. */
    private fun systemDimension(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    }

    private fun invalidateMainPicture() {
        mainPictureGeneration++
        mainPictureVisible = false
        if (CarPlayBackgroundSession.isOwner(this)) sink?.cancelMainSurfaceFrameWait()
        updateDebugOverlays()
    }

    private fun attachSurface(surface: Surface) {
        val renderer = sink ?: return
        mainPictureVisible = false
        val generation = ++mainPictureGeneration
        updateDebugOverlays()
        renderer.setSurface(SCREEN_TYPE_MAIN, surface)
        if (fallbackVideoView != null) {
            renderer.awaitMainSurfaceFrame(surface) { delayMillis ->
                mainHandler.postDelayed({
                    if (generation != mainPictureGeneration || sink !== renderer || currentSurface !== surface ||
                        !isActivityStarted || isDestroyed || shuttingDown.get()) return@postDelayed
                    mainPictureVisible = true
                    updateDebugOverlays()
                }, delayMillis)
            }
        }
        // A retained, static CarPlay screen may not otherwise send a frame to the new output.
        renderer.refreshPicture(SCREEN_TYPE_MAIN)
        if (AirPlayPersistence.loadClusterMapEnabled(this)) {
            clusterSurface?.let { sink?.setSurface(SCREEN_TYPE_ALT, it) }
        } else {
            sink?.setSurface(SCREEN_TYPE_ALT, surface)
        }
    }

    private var lastAudioReclaimAttemptMillis = 0L

    private fun maybeReclaimMainAudioOnTouch() {
        if (activeAirPlaySession == null || AirPlayPersistence.loadCarBluetoothAudio(this)) return
        val now = System.currentTimeMillis()
        if (now - lastAudioReclaimAttemptMillis >= 4000L) {
            lastAudioReclaimAttemptMillis = now
            controller?.takeMainAudio()
        }
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == gestureFingerCount && !gestureSequenceActive) {
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    controller?.sendTouch(emptyList())
                    appendLog("Settings swipe tracking started; fingers=$gestureFingerCount")
                    return true
                }
            }
        }

        if (gestureSequenceActive) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.pointerCount != gestureFingerCount) {
                gestureTracking = false
            }
            if (!gestureTracking || event.pointerCount != gestureFingerCount) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                if (
                    deltaY >= dp(SETTINGS_SWIPE_DISTANCE_DP) &&
                    deltaY >= deltaX * SETTINGS_SWIPE_DIRECTION_RATIO
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                    openSettingsMenu()
                    return true
                }
            }
            return true
        }

        if (dockAutoHideEnabled && carPlayDock != CarPlayDock.BOTTOM &&
            (sessionDisplay?.let { it.width > it.height } ?: (view.width > view.height))) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dockEdgeSwipeActive = false
                    dockCloseSwipeActive = false
                    dockGestureIntercepted = false
                    if (!dockRevealed) {
                        val isNearEdge = if (rightHandDrive) {
                            event.x >= view.width - dp(48)
                        } else {
                            event.x <= dp(48)
                        }
                        if (isNearEdge) {
                            dockEdgeSwipeActive = true
                            dockEdgeStartX = event.x
                            dockEdgeStartY = event.y
                        }
                    } else {
                        val isOnDock = if (rightHandDrive) {
                            event.x >= view.width * 0.75f
                        } else {
                            event.x <= view.width * 0.25f
                        }
                        if (isOnDock) {
                            dockCloseSwipeActive = true
                            dockCloseStartX = event.x
                            dockCloseStartY = event.y
                        }
                        mainHandler.removeCallbacks(hideDockRunnable)
                        mainHandler.postDelayed(hideDockRunnable, 5000L)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dockGestureIntercepted) {
                        return true
                    }
                    if (dockEdgeSwipeActive) {
                        val dx = event.x - dockEdgeStartX
                        val dy = Math.abs(event.y - dockEdgeStartY)
                        val swipedToOpen = if (rightHandDrive) {
                            dx <= -dp(36) && -dx > dy * 1.2f
                        } else {
                            dx >= dp(36) && dx > dy * 1.2f
                        }
                        if (swipedToOpen) {
                            dockEdgeSwipeActive = false
                            dockGestureIntercepted = true
                            revealDockTemporarily(view)
                            return true
                        }
                    } else if (dockCloseSwipeActive) {
                        val dx = event.x - dockCloseStartX
                        val dy = Math.abs(event.y - dockCloseStartY)
                        val swipedToClose = if (rightHandDrive) {
                            dx >= dp(36) && dx > dy * 1.2f
                        } else {
                            dx <= -dp(36) && -dx > dy * 1.2f
                        }
                        if (swipedToClose) {
                            dockCloseSwipeActive = false
                            dockGestureIntercepted = true
                            hideDock(view)
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dockEdgeSwipeActive = false
                    dockCloseSwipeActive = false
                    if (dockGestureIntercepted) {
                        dockGestureIntercepted = false
                        return true
                    }
                }
            }
        }

        val content = contentRect(view.width, view.height)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOutsideContent = !content.contains(event.x, event.y)
            maybeReclaimMainAudioOnTouch()
        }
        if (touchOutsideContent) {
            // Ignore the entire touch sequence when it starts in a letterbox bar.
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchOutsideContent = false
            }
            return true
        }
        val contacts = CarPlayTouchMapper.contacts(event, content)
        val queued = controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    private fun startSecondScreenMirror() {
        // Guangqi: the cluster shows CarPlay's own cluster stream (111). Mirroring stream 110 there
        // ran a second decoder for the same stream and halved the main screen's frame rate.
        if (GuangqiCluster.present(this)) return
        val renderer = sink ?: return
        val display = sessionDisplay ?: return
        secondScreen.bind(renderer, display.width, display.height)
        secondScreen.onVideoReady()
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
            }
            if (type == SCREEN_TYPE_MAIN) {
                if (!active) {
                    invalidateMainPicture()
                    secondScreen.stop()
                } else {
                    if (!mainPictureVisible) currentSurface?.let(::attachSurface)
                    if (controller?.hasRenderedMainPicture() == true) startSecondScreenMirror()
                }
            }
            appendLog("Screen stream state: type=$type active=$active generation=$generation")
            if (type == SCREEN_TYPE_ALT) {
                Log.i(ClusterMapPresentation.TAG, "cluster stream active=$active")
                appendLog("Cluster map: stream active=$active")
                clusterPresentation?.setStreamActive(active)
                MapMirrors.setStreamActive(active)
                syncGuangqiClusterReady()
                if (active) {
                    mainHandler.removeCallbacks(hideIdleCenterMap)
                    if (!CenterMapOverlay.shown) CenterMapOverlay.scheduleShow()
                } else {
                    mainHandler.postDelayed(hideIdleCenterMap, CENTER_MAP_IDLE_MILLIS)
                }
            }
            updateDebugOverlays()
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            setConnectionStage(message)
            appendLog(message)
        }
    }

    private fun setConnectionStage(message: String, exact: Boolean = false) {
        latestStage = message
        stageStatusView?.text = if (exact) message else friendlyStage(message)
        // Monotonic, not wall clock: these units boot with unset RTCs
        // (2018) that jump years when time syncs, and a wall-clock age
        // turned that into a 278,984,999-second wait on issue #373.
        stageStartedAtMillis = SystemClock.elapsedRealtime()
        val generation = ++stageWatchdogGeneration
        stageDiagnosticView?.text = "阶段已进入 · 0 秒"
        fun tick() {
            if (generation != stageWatchdogGeneration || shuttingDown.get()) return
            val elapsed = ((SystemClock.elapsedRealtime() - stageStartedAtMillis) / 1000L).coerceAtLeast(0L)
            stageDiagnosticView?.text = if (elapsed >= 20L) {
                "当前阶段已等待 ${elapsed} 秒 · 若持续不变，请拍摄此界面"
            } else {
                "阶段已进入 · ${elapsed} 秒"
            }
            mainHandler.postDelayed({ tick() }, 1000L)
        }
        mainHandler.postDelayed({ tick() }, 1000L)
        updateDebugOverlays()
    }

    private fun setFailedConnectionStage(message: String) {
        latestStage = message
        stageWatchdogGeneration++
        stageDiagnosticView?.text = DiagnosticRedactor.redact(message)?.take(240)
        val friendly = friendlyStage(message)
        val base = if (friendly == getString(R.string.getting_carplay_ready)) {
            getString(R.string.connection_interrupted_retrying)
        } else {
            friendly
        }
        stageStatusView?.text = withRetryDetail(base, message)
        updateDebugOverlays()
    }

    private fun updateDebugOverlays() {
        statusScrollView?.visibility = View.GONE
        // A retained stream or a replayed session first-frame event is not a picture on this window.
        connectionPanel?.visibility = if (mainPictureVisible) View.GONE else View.VISIBLE
    }

    private fun friendlyStage(message: String): String = when {
        message == getString(R.string.waiting_for_mfi_coprocessor) ||
            message == getString(R.string.requesting_mfi_usb_permission) -> message
        message.contains("Turn on Wi-Fi", true) -> getString(R.string.turn_on_wi_fi_in_the_head_unit_s_settings_to_connect)
        message.contains("Allow precise Location", true) -> getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p)
        message.contains("Allow Nearby devices", true) -> getString(R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per)
        message.contains("createGroup failed", true) -> getString(R.string.the_head_unit_couldn_t_start_carplay_wi_fi_check_wi_fi_and)
        message.contains("needs a reset", true) -> getString(R.string.a_previous_wi_fi_direct_connection_is_still_running_reset)
        message.contains("socket", true) || message.contains("RFCOMM", true) ->
            getString(R.string.your_iphone_isn_t_available_unlock_it_and_check_bluetooth)
        message.contains("Bluetooth adapter is unavailable", true) -> getString(R.string.bluetooth_adapter_unavailable)
        message.contains("Bluetooth is turned off", true) -> getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first)
        message.contains("busy", true) || message.contains("EBUSY", true) -> getString(R.string.usb_iphone_busy_or_occupied)
        message.contains("permission was not granted", true) -> getString(R.string.usb_iphone_permission_not_granted)
        message.contains("unsupported", true) || message.contains("not supported", true) -> getString(R.string.this_head_unit_may_not_support_wireless_carplay_try_a_usb)
        message.contains("denied", true) || message.contains("permission", true) -> getString(R.string.allow_the_connection_permission_to_continue)
        message.contains("Failed", true) ||
            message.contains("error", true) ||
            message.contains("timed out", true) ||
            message.contains("closed", true) ||
            message.contains("authentication", true) ||
            message.contains("Could not", true) ->
            getString(R.string.connection_interrupted_retrying)
        message.contains("Waiting for iPhone", true) || message.contains("Discovering iPhone", true) -> getString(R.string.connect_your_iphone_with_a_usb_cable)
        message.contains("paired", true) -> getString(R.string.looking_for_your_paired_iphone)
        message.contains("Bluetooth", true) -> getString(R.string.connecting_to_your_iphone)
        message.contains("reconnect", true) || message.contains("ended", true) -> getString(R.string.reconnecting_to_your_iphone)
        message.contains("active", true) || message.contains("running", true) -> getString(R.string.opening_carplay)
        else -> getString(R.string.getting_carplay_ready)
    }

    private fun withRetryDetail(base: String, message: String): String {
        val detail = DiagnosticRedactor.redact(message)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(base, ignoreCase = true) }
            ?: return base
        return "$base\n$detail"
    }

    private fun appendLog(message: String) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        sessionLog?.append(formattedLogLine(safe, System.currentTimeMillis()))
    }

    private fun appendFileLog(message: String) {
        sessionLog?.append(formattedLogLine(message, System.currentTimeMillis()))
    }

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val logFile = File(File(filesDir, "logs"), "diplay.log")
        val activeLog = SessionLogFile(logFile)
        runCatching {
            activeLog.reset(
                "DiPlay log started " +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "pid=${Process.myPid()} path=${logFile.absolutePath}",
            )
        }
        sessionLog = activeLog
        crashDiagnosticLog = activeLog
    }

    /**
     * Records uncaught exceptions into the diagnostic log before the process dies, so
     * crash-class issues can be triaged from the exported report without adb. The previous
     * default handler still runs, preserving the normal crash flow. See issue #106.
     */
    private fun installCrashDiagnostics() {
        if (Thread.getDefaultUncaughtExceptionHandler() is DiPlayCrashHandler) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(DiPlayCrashHandler(previous))
    }

    private class DiPlayCrashHandler(
        private val next: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        private val reported = AtomicBoolean(false)

        override fun uncaughtException(thread: Thread, error: Throwable) {
            if (reported.compareAndSet(false, true)) {
                runCatching {
                    // safeStackTrace never recurses into the cause chain: a cyclic
                    // cause used to StackOverflow the crash reporter itself (#163)
                    // and hide the original error.
                    val trace = error.safeStackTrace(maxCauses = 4, maxFramesPerCause = 40)
                        .lineSequence().take(40).joinToString("\n")
                    crashDiagnosticLog?.append(
                        "FATAL thread=${thread.name} " +
                            "${error.javaClass.name}: ${error.message}\n$trace",
                    )
                }
            }
            runCatching { next?.uncaughtException(thread, error) }
        }
    }

    private fun refreshLogView(nowMillis: Long) {
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        while (logLines.firstOrNull()?.timestampMillis?.let { it <= cutoff } == true) {
            logLines.removeFirst()
        }
        statusView?.text = logLines.joinToString("\n") { it.text }
        scrollLogsToBottom()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstOrNull()?.let { oldest ->
            val delay = (oldest.timestampMillis + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun scrollLogsToBottom() {
        statusScrollView?.post {
            statusScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * While the three-finger quick menu is open the car's top bar shows again, so its home button
     * and climate controls can be reached. It lies over CarPlay and the menu: the window keeps
     * laying out behind it, so the CarPlay picture keeps its size. Closing the menu, or going on to
     * the full menu (which previews its own bar settings), hides it again.
     */
    private fun showCarBarOverMenu(hideBottom: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.statusBars())
        @Suppress("DEPRECATION")
        var flags = window.decorView.systemUiVisibility
        flags = flags and (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY).inv()
        flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        flags = if (hideBottom) flags or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION else flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.inv()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }

    private fun applyFullscreenMode() {
        val multiWindow = isMultiWindowActive()
        val hideTop = hideTopBar && !multiWindow
        val hideBottom = hideBottomBar && !multiWindow
        if (menuOpen && quickMenuShowing && hideTop) {
            showCarBarOverMenu(hideBottom)
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hideTop) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // On Android 4.4 (KitKat / API 19) and custom head-unit ROMs, WindowInsetsControllerCompat
        // does not toggle WindowManager.LayoutParams.FLAG_FULLSCREEN or sticky immersive systemUiVisibility flags.
        if (hideTop) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        @Suppress("DEPRECATION")
        var flags = window.decorView.systemUiVisibility
        if (hideTop) {
            flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN
        } else {
            flags = flags and View.SYSTEM_UI_FLAG_FULLSCREEN.inv()
        }
        if (hideBottom) {
            flags = flags or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        } else {
            flags = flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.inv()
        }
        if (hideTop || hideBottom) {
            flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> getString(R.string.preparing_mfi_authentication)
        CarPlayStatus.WaitingForMfi -> getString(R.string.waiting_for_mfi_coprocessor)
        CarPlayStatus.RequestingMfiPermission -> getString(R.string.requesting_mfi_usb_permission)
        CarPlayStatus.MfiReady -> getString(R.string.mfi_authentication_ready)
        CarPlayStatus.StartingHotspot -> getString(if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI)
            R.string.existing_wifi_attaching else R.string.starting_wireless_hotspot)
        is CarPlayStatus.HotspotReady ->
            if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
                getString(R.string.existing_wifi_ready, ssid, band, channel, address)
            } else getString(R.string.status_hotspot_ready, backend, ssid, band, if (channel == 0) getString(R.string.auto_value) else channel.toString())
        CarPlayStatus.WaitingForPairedIphone -> getString(R.string.waiting_for_paired_iphone)
        CarPlayStatus.ConnectingBluetooth -> getString(R.string.connecting_bluetooth)
        CarPlayStatus.RunningWireless -> getString(R.string.wireless_carplay_control_running)
        CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> getString(R.string.wireless_carplay_active)
        CarPlayStatus.DiscoveringIphone -> getString(R.string.discovering_iphone)
        CarPlayStatus.WaitingForIphone -> getString(R.string.waiting_for_iphone_over_usb)
        CarPlayStatus.RequestingIphonePermission -> getString(R.string.requesting_iphone_usb_permission)
        CarPlayStatus.WaitingForReenumeration -> getString(R.string.status_waiting_reenumeration)
        CarPlayStatus.SelectingConfiguration -> getString(R.string.selecting_carplay_configuration)
        CarPlayStatus.OpeningDataPaths -> getString(R.string.opening_usb_data_paths)
        CarPlayStatus.Pairing -> getString(R.string.pairing_with_iphone)
        CarPlayStatus.ConnectingControl -> getString(R.string.connecting_iap2_control)
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) getString(R.string.starting_airplay_service) else getString(R.string.status_attaching_ncm)
        CarPlayStatus.RunningControl -> getString(R.string.carplay_control_running)
        CarPlayStatus.ControlEnded -> getString(R.string.carplay_control_window_ended)
        is CarPlayStatus.Failed -> when (startupFailure) {
            WirelessStartupFailure.HOTSPOT_NOT_READY -> getString(R.string.hotspot_network_not_ready)
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> getString(R.string.first_tcp_timeout)
            else -> getString(R.string.status_failed, message)
        }
    }

    private companion object {
        const val SIDE_PANEL_REFRESH_MILLIS = 5_000L
        const val TAG = "xcertplay-usb"
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        // On the teardown thread: bounded sequencing wait. Release notification retains ownership beyond
        // this budget when a codec is still busy.
        const val SINK_RELEASE_WAIT_MILLIS = 2_000L
        private const val CENTER_MAP_IDLE_MILLIS = 3_000L // a reconnect is quicker; a session end is not
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val CONFIGURATION_POLL_INTERVAL_MILLIS = 2_000L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        /** Issue #315: legacy reverse cold start waits this long after reverse exit before restarting the decoder. */
        const val LEGACY_REVERSE_COLD_START_DELAY_MILLIS = 2_500L
        /** Post-exit restore: debounce before the first focus check, then
         * the recheck cadence and budget while the camera may still be up. */
        const val REVERSE_EXIT_RESTORE_DELAY_MILLIS = 600L
        const val REVERSE_EXIT_RESTORE_RECHECK_MILLIS = 500L
        const val REVERSE_EXIT_RESTORE_MAX_ATTEMPTS = 5
        const val LEGACY_REVERSE_RESUME_RECHECK_MILLIS = 800L
        const val LEGACY_REVERSE_RESUME_MAX_ATTEMPTS = 4
        /** A reverse manoeuvre never lasts this long; a reverse-active flag
         * older than this is a missed exit broadcast, not a camera. */
        const val REVERSE_ACTIVE_GATE_CAP_MILLIS = 120_000L
        /** Issues #275/#243: automatic wired no-TCP retries allowed on pre-Lollipop units before asking for a physical replug. */
        const val WIRED_NO_TCP_FAILURE_LIMIT = 2
        /** Issue #305: automatic wired USB-busy retries allowed before asking for a physical replug. */
        const val WIRED_USB_BUSY_FAILURE_LIMIT = 3
        const val WIRED_USB_STALL_FAILURE_LIMIT = 3
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        /**
         * Issue #188 follow-up: a human cannot read and dismiss the system VPN
         * consent dialog faster than this. A quicker CANCELED round-trip means the
         * ROM's consent activity is broken (returns instantly), not a user denial.
         */
        const val VPN_CONSENT_MIN_HUMAN_MILLIS = 1_500L
        const val VPN_CONSENT_INSTANT_DENY_LIMIT = 2
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val AUDIO_CAPTURE_MARKER = "audio-capture.enabled"
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        /**
         * Process-global target for [DiPlayCrashHandler]. Kept off the activity instance so the
         * default uncaught-exception handler never retains a destroyed activity. See issue #106.
         */
        @Volatile
        private var crashDiagnosticLog: SessionLogFile? = null
        const val PROTOCOL_TRACE_PREFIX = "TRACE "
        const val SETTINGS_SWIPE_DISTANCE_DP = 72
        const val SETTINGS_SWIPE_DIRECTION_RATIO = 1.15f
        const val MAX_SETTINGS_MENU_WIDTH_PX = 1200
        // The in-session menu shares the main settings palette (DiPlayActivity's
        // BG/SURFACE/BUTTON/BORDER and the CARD theme), so the two settings
        // surfaces read as one product.
        val MENU_BACKGROUND = Color.rgb(12, 17, 27)
        val MENU_SURFACE = Color.rgb(21, 30, 44)
        val MENU_BUTTON = Color.rgb(31, 43, 61)
        val MENU_BORDER = Color.rgb(42, 56, 75)
        val MENU_SECONDARY = Color.rgb(168, 182, 202)
        val MENU_ACCENT = Color.rgb(166, 200, 255)
        val MENU_ACCENT_TRACK = Color.rgb(0x32, 0x58, 0x8c)
        val MENU_TRACK_OFF = Color.rgb(42, 56, 75)
        val MENU_BUTTON_TEXT = Color.rgb(12, 17, 27)
        val MENU_DANGER = Color.rgb(190, 45, 45)
        val NO_VIDEO_BACKGROUND = Color.rgb(0x16, 0x16, 0x18)
    }

    private data class DisplaySize(val width: Int, val height: Int)
    private data class LogEntry(val timestampMillis: Long, val text: String)
    private data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )
}

internal data class CarPlaySessionDisplay(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hideTopBar: Boolean,
    val hideBottomBar: Boolean,
    // Compare unscaled startup window dimensions, not the scaled video canvas.
    val windowWidth: Int,
    val windowHeight: Int,
    /** The view areas this session declared, or null for the whole screen as one area. */
    val viewAreas: CarPlayViewAreas? = null,
)

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
internal object CarPlayBackgroundSession {
    @Volatile var active = false
    private var stopAction: (((() -> Unit)) -> Unit)? = null
    private var stopping = false
    private var owner: Any? = null
    @Synchronized fun isOwner(candidate: Any): Boolean = owner === candidate
    @Synchronized fun hasSession(): Boolean = stopAction != null || stopping
    private val stopWaiters = mutableListOf<() -> Unit>()

    fun stop(completion: () -> Unit = {}) {
        val action: (((() -> Unit)) -> Unit)?
        synchronized(this) {
            if (stopping) { stopWaiters.add(completion); return }
            action = stopAction
            if (action != null) { stopping = true; stopWaiters.add(completion) }
        }
        if (action == null) { completion(); return }
        try {
            action.invoke {
                val callbacks = synchronized(this) {
                    stopping = false
                    stopWaiters.toList().also { stopWaiters.clear() }
                }
                callbacks.forEach { it() }
            }
        } catch (t: Throwable) {
            val callbacks = synchronized(this) {
                stopping = false
                stopWaiters.toList().also { stopWaiters.clear() }
            }
            callbacks.forEach { it() }
        }
    }

    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
        val display: CarPlaySessionDisplay,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0
    private var display: CarPlaySessionDisplay? = null

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        val currentDisplay = display ?: return null
        return Snapshot(currentController, currentSink, width, height, currentDisplay)
    }

    @Synchronized
    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int,
        owner: Any, display: CarPlaySessionDisplay, stop: (() -> Unit) -> Unit) {
        this.stopAction = stop
        this.owner = owner
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
        this.display = display
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null, keepOwner: Boolean = false) {
        if (expected != null && controller !== expected) return
        controller = null
        sink = null
        if (!keepOwner) { stopAction = null; owner = null }
        active = false
        width = 0
        height = 0
        display = null
    }
}
