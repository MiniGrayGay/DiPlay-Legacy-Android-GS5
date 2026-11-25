package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import android.app.AlertDialog
import com.shilapi.xcertplay.compat.realSdkInt
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import kotlin.math.roundToInt

internal val MENU_SURFACE = Color.rgb(21, 30, 44)
internal val MENU_BUTTON = Color.rgb(31, 43, 61)
internal val MENU_BORDER = Color.rgb(42, 56, 75)
internal val MENU_SECONDARY = Color.rgb(168, 182, 202)
internal val MENU_ACCENT = Color.rgb(166, 200, 255)
internal val MENU_BUTTON_TEXT = Color.rgb(12, 17, 27)

internal fun Context.menuDp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

/** Filled rounded background for the menu's buttons, in place of API 21+ background tints. */
internal fun Context.menuButtonBackground(fill: Int, radiusDp: Int = 20): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = menuDp(radiusDp).toFloat()
        setStroke(menuDp(1), if (fill == MENU_BUTTON) MENU_BORDER else fill)
    }

internal fun Context.menuText(
    text: String,
    sizeSp: Float,
    color: Int,
    bold: Boolean = false,
): TextView = TextView(this@menuText).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    includeFontPadding = false
}

/** True while a USB Bluetooth dongle is plugged in: it cannot play car Bluetooth audio. */
internal fun Context.usbBluetoothDonglePresent(): Boolean = runCatching {
    val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
    com.shilapi.xcertplay.usbbt.UsbBtDongleDetector(applicationContext).findDongles(usbManager).isNotEmpty()
}.getOrDefault(false)

/**
 * The three-finger menu, over CarPlay, its waiting screen and DiPlay's own pages. Every control
 * saves at once; what that means for a running session is the [Host]'s business.
 */
internal class QuickMenu(private val activity: ComponentActivity, private val host: Host) {
    interface Host {
        /** A saved change that applies to the next session. */
        fun changedSession()
        /** "No Bluetooth auto-connect during CarPlay", after its exclusion with car Bluetooth audio. */
        fun autoDisconnectBtChanged(effective: Boolean) {}
        fun close()
        /** [page] of [DiPlayActivity]: "home" or "settings". The menu has been closed. */
        fun openDiPlay(page: String)
    }

    private var doneButton: Button? = null
    val view: View = build()

    var doneLabel: CharSequence
        get() = doneButton?.text ?: ""
        set(value) { doneButton?.text = value }

    private fun dp(value: Int) = activity.menuDp(value)

    /**
     * Cluster map, car Bluetooth audio or no Bluetooth auto-connect (one excludes the other), music
     * buffer and the Guangqi rows. Bottom left opens DiPlay settings or its home page; everything
     * else lives there.
     */
    private fun build(): View {
        val overlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(200, 6, 9, 15))
            isClickable = true
            setOnClickListener { host.close() }
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(MENU_SURFACE)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), MENU_BORDER)
            }
            setPadding(dp(28), dp(22), dp(28), dp(22))
        }
        panel.addView(activity.menuText(activity.getString(R.string.settings_quick_settings), 22f, Color.WHITE, bold = true),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        fun divider() = View(activity).apply { setBackgroundColor(MENU_BORDER) }
        fun addRow(view: View, first: Boolean = false) {
            if (!first) panel.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
            panel.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(10) })
        }
        // Title, a one-line note and the switch; the whole row toggles it.
        fun switchRowWith(label: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit): Pair<View, Switch> {
            val switch = Switch(activity).apply {
                isChecked = checked
                if (realSdkInt() >= Build.VERSION_CODES.LOLLIPOP) { showText = false; textOn = ""; textOff = "" }
                SettingsWidgets.styleSwitch(this, activity, SettingsTheme.OVERLAY)
                contentDescription = label
                isFocusable = false
                setOnCheckedChangeListener { _, on -> onChanged(on) }
            }
            return LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener { switch.toggle() }
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(activity.menuText(label, 18f, Color.WHITE))
                    addView(activity.menuText(description, 14f, MENU_SECONDARY).apply { setPadding(0, dp(4), dp(16), 0) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(switch)
            } to switch
        }
        fun switchRow(label: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit): View =
            switchRowWith(label, description, checked, onChanged).first

        addRow(switchRow(activity.getString(R.string.carplay_map_on_instrument_cluster_experimental),
            activity.getString(R.string.quick_cluster_map_note),
            AirPlayPersistence.loadClusterMapEnabled(activity)) { enabled ->
            AirPlayPersistence.saveClusterMapEnabled(activity, enabled)
            host.changedSession()
        }, first = true)

        // Car Bluetooth audio and "no Bluetooth auto-connect during CarPlay" exclude each other:
        // with car Bluetooth audio the phone must stay on the car's Bluetooth. Turning one on turns
        // the other off. A pair saved with both on keeps car Bluetooth audio, as the session does.
        if (AirPlayPersistence.loadCarBluetoothAudio(activity) && AirPlayPersistence.loadCarplayAutoDisconnectBt(activity)) {
            AirPlayPersistence.saveCarplayAutoDisconnectBt(activity, false)
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
            AirPlayPersistence.saveCarBluetoothAudio(activity, enabled)
            if (enabled && AirPlayPersistence.loadCarplayAutoDisconnectBt(activity)) {
                AirPlayPersistence.saveCarplayAutoDisconnectBt(activity, false)
                setQuietly(autoDisconnectSwitch, false)
            }
            host.autoDisconnectBtChanged(AirPlayPersistence.loadCarplayAutoDisconnectBt(activity) && !enabled)
            host.changedSession()
        }
        val carBluetoothAudioRow = switchRowWith(activity.getString(R.string.settings_car_bluetooth_audio),
            activity.getString(R.string.quick_car_bluetooth_audio_note),
            AirPlayPersistence.loadCarBluetoothAudio(activity)) { enabled ->
            if (switchGuard) return@switchRowWith
            if (enabled && activity.usbBluetoothDonglePresent()) {
                // As in DiPlay settings: the USB dongle has no A2DP sink, so this could leave no
                // sound at all. Undo the switch and turn it on only after an explicit confirm.
                setQuietly(carBluetoothAudioSwitch, false)
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.settings_car_bluetooth_audio))
                    .setMessage(activity.getString(R.string.settings_car_bluetooth_audio_dongle_warning))
                    .setPositiveButton(activity.getString(R.string.settings_car_bluetooth_audio_enable_anyway)) { _, _ ->
                        setQuietly(carBluetoothAudioSwitch, true)
                        applyCarBluetoothAudio(true)
                    }
                    .setNegativeButton(activity.getString(R.string.cancel), null)
                    .show()
            } else {
                applyCarBluetoothAudio(enabled)
            }
        }
        carBluetoothAudioSwitch = carBluetoothAudioRow.second
        addRow(carBluetoothAudioRow.first)
        val autoDisconnectRow = switchRowWith(activity.getString(R.string.settings_carplay_auto_disconnect_bt),
            activity.getString(R.string.quick_auto_connect_note),
            AirPlayPersistence.loadCarplayAutoDisconnectBt(activity)) { enabled ->
            if (switchGuard) return@switchRowWith
            AirPlayPersistence.saveCarplayAutoDisconnectBt(activity, enabled)
            if (enabled && AirPlayPersistence.loadCarBluetoothAudio(activity)) {
                AirPlayPersistence.saveCarBluetoothAudio(activity, false)
                setQuietly(carBluetoothAudioSwitch, false)
            }
            host.autoDisconnectBtChanged(enabled && !AirPlayPersistence.loadCarBluetoothAudio(activity))
            host.changedSession()
        }
        autoDisconnectSwitch = autoDisconnectRow.second
        addRow(autoDisconnectRow.first)

        val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
        addRow(quickChoiceRow(activity.getString(R.string.music_buffer), activity.getString(R.string.quick_music_buffer_note),
            listOf(activity.getString(R.string.s_300_ms_default), activity.getString(R.string.s_500_ms), activity.getString(R.string.s_1000_ms_most_stable)),
            bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(activity)).coerceAtLeast(0)) { index ->
            AirPlayPersistence.saveMediaBufferMillis(activity, bufferPresets[index])
            host.changedSession()
        })

        // Guangqi: the volume keys go back to the car the moment this is off; no reconnect.
        if (com.shilapi.xcertplay.orchestration.GuangqiVolumeTakeover.supported()) {
            addRow(switchRow(activity.getString(R.string.settings_volume_takeover), activity.getString(R.string.quick_volume_takeover_note),
                com.shilapi.xcertplay.orchestration.GuangqiVolumeTakeover.enabled(activity)) { enabled ->
                com.shilapi.xcertplay.orchestration.GuangqiVolumeTakeover.setEnabled(activity, enabled)
            })
            addRow(switchRow(activity.getString(R.string.settings_standing_microphone), activity.getString(R.string.quick_standing_microphone_note),
                com.shilapi.xcertplay.orchestration.GuangqiStandingMicrophone.enabled(activity)) { enabled ->
                com.shilapi.xcertplay.orchestration.GuangqiStandingMicrophone.setEnabled(activity, enabled)
            })
            // The head unit follows the car's power and has no power button: without this a restart
            // (for example to load a newer volume hook into system_server) needs the engine off.
            addRow(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(activity.menuText(activity.getString(R.string.quick_reboot_head_unit), 18f, Color.WHITE))
                    addView(activity.menuText(activity.getString(R.string.quick_reboot_head_unit_note), 14f, MENU_SECONDARY)
                        .apply { setPadding(0, dp(4), dp(16), 0) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(rebootHeadUnitButton(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)))
            })
        }

        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(quickMenuButton("${activity.getString(R.string.app_name)} ${activity.getString(R.string.settings)}", primary = false) {
            host.close()
            host.openDiPlay("settings")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)))
        footer.addView(quickMenuButton(activity.getString(R.string.quick_back_to_home), primary = false) {
            host.close()
            host.openDiPlay("home")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { marginStart = dp(12) })
        footer.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        doneButton = quickMenuButton(activity.getString(R.string.done), primary = true) { host.close() }
        footer.addView(doneButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)))
        panel.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val scroll = ScrollView(activity).apply { isFillViewport = false; addView(panel) }
        overlay.addView(scroll, FrameLayout.LayoutParams(dp(860), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(24), dp(24), dp(24), dp(24))
        })
        return overlay
    }

    /**
     * The quick menu's restart button, for testing: a tap runs `svc power reboot` (Android's own
     * restart: services are told and data is written first); held for [REBOOT_HOLD_MILLIS] it runs
     * `setprop sys.powerctl reboot` (init restarts at once). This ROM's shutdown never tells the MCU
     * to cut power, so the head unit comes back by itself while the car is on. DiPlay's uid (system)
     * may do both.
     */
    private fun rebootHeadUnitButton(): Button {
        val label = activity.getString(R.string.quick_reboot_head_unit_action)
        val button = quickMenuButton(label, primary = false) {}
        var held = false
        val hard = Runnable {
            held = true
            button.text = label
            rebootHeadUnit(hard = true)
        }
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    held = false
                    view.isPressed = true
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    button.text = activity.getString(R.string.quick_reboot_head_unit_holding)
                    view.postDelayed(hard, REBOOT_HOLD_MILLIS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(hard)
                    view.isPressed = false
                    button.text = label
                    val inside = event.x >= 0 && event.y >= 0 && event.x <= view.width && event.y <= view.height
                    if (event.actionMasked == MotionEvent.ACTION_UP && !held && inside) rebootHeadUnit(hard = false)
                }
            }
            true
        }
        return button
    }

    private fun rebootHeadUnit(hard: Boolean) {
        val command = if (hard) "setprop sys.powerctl reboot" else "svc power reboot diplay"
        Log.i(TAG, "head unit restart from the quick menu: $command")
        Thread({
            val failure = runCatching {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val output = (process.inputStream.bufferedReader().readText() +
                    process.errorStream.bufferedReader().readText()).trim()
                val code = process.waitFor()
                // svc prints "Failed to reboot." when the power service refused.
                if (code != 0 || output.contains("Failed")) "exit $code $output".trim() else null
            }.getOrElse { it.toString() } ?: return@Thread
            Log.w(TAG, "head unit restart failed: $command: $failure")
            activity.runOnUiThread {
                android.widget.Toast.makeText(activity, activity.getString(R.string.quick_reboot_head_unit_failed, failure),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }, "diplay-reboot").start()
    }

    /** A title, a one-line note and one row of choices; [onSelect] gets the chosen index. */
    private fun quickChoiceRow(title: String, note: String, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(activity.menuText(title, 18f, Color.WHITE))
        column.addView(activity.menuText(note, 14f, MENU_SECONDARY).apply { setPadding(0, dp(4), dp(16), 0) })
        val chips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val views = mutableListOf<TextView>()
        fun paint(chosen: Int) = views.forEachIndexed { index, chip ->
            val on = index == chosen
            chip.setTextColor(if (on) MENU_BUTTON_TEXT else Color.WHITE)
            chip.background = activity.menuButtonBackground(if (on) MENU_ACCENT else MENU_BUTTON, radiusDp = 18)
        }
        labels.forEachIndexed { index, label ->
            val chip = TextView(activity).apply {
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

    private fun quickMenuButton(title: String, primary: Boolean, click: () -> Unit): Button = Button(activity).apply {
        text = title
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) MENU_BUTTON_TEXT else Color.WHITE)
        background = activity.menuButtonBackground(if (primary) MENU_ACCENT else MENU_BUTTON, radiusDp = 18)
        minWidth = dp(140)
        setPadding(dp(22), 0, dp(22), 0)
        setOnClickListener { click() }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        // The restart button: held this long it restarts at once (sys.powerctl).
        const val REBOOT_HOLD_MILLIS = 3_000L
    }
}
