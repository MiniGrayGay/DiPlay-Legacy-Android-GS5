package com.shilapi.xcertplay.settings

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

object ConnectionSettingsSection {

    fun createMfiTargetChoice(
        context: Context,
        selected: MfiTarget,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onSelected: (MfiTarget) -> Unit,
    ): SettingsWidgets.ChoiceRowResult<MfiTarget> = SettingsWidgets.createChoiceRow(
        context = context,
        label = context.getString(R.string.mfi_certificate_signing_target),
        options = listOf(
            MfiTarget.LOCAL to context.getString(R.string.local_offline),
            MfiTarget.USB_CH341 to context.getString(R.string.usb_ch341),
        ),
        selected = selected,
        theme = theme,
        onSelected = onSelected,
    )

    fun createWirelessCarPlayRow(
        context: Context,
        checked: Boolean,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onChanged: (Boolean) -> Unit,
    ): SettingsWidgets.SwitchRowResult = SettingsWidgets.createSwitchRow(
        context = context,
        label = context.getString(R.string.wireless_carplay_2),
        description = context.getString(R.string.wireless_carplay_transport),
        checked = checked,
        theme = theme,
        contentDescription = context.getString(R.string.wireless_carplay_transport),
        labelSizeSp = if (theme.isOverlay) 20f else 18f,
        onChanged = onChanged,
    )

    fun createHotspotModeChoice(
        context: Context,
        selected: WirelessHotspotMode,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        rootHotspotEnabled: Boolean = false,
        onSelected: (WirelessHotspotMode) -> Unit,
    ): SettingsWidgets.ChoiceRowResult<WirelessHotspotMode> {
        val modes = buildList {
            // Android 9 has no usable Wi-Fi Direct group for this; Android 4.4 uses the legacy group,
            // which is the wireless path verified on the Guangqi head unit.
            if (com.shilapi.xcertplay.compat.realSdkInt() != Build.VERSION_CODES.P) {
                add(WirelessHotspotMode.WIFI_P2P to context.getString(R.string.wifi_direct))
            }
            add(WirelessHotspotMode.MANUAL to context.getString(R.string.built_in_car_hotspot))
            add(WirelessHotspotMode.EXISTING_WIFI to context.getString(R.string.existing_wifi_title))
            if (rootHotspotEnabled) {
                add(WirelessHotspotMode.HOSTAPD to context.getString(R.string.hotspot_mode_hostapd))
            }
        }
        return SettingsWidgets.createChoiceRow(
            context = context,
            label = context.getString(R.string.wi_fi_session),
            options = modes,
            selected = selected,
            theme = theme,
            onSelected = onSelected,
        )
    }
}
