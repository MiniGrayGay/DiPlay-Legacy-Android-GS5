# AGENTS.md

Instructions for coding agents that work on DiPlay.

## Checks

Run the CI command from `.github/workflows/android.yml` before you report a change as done:

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

Set `ANDROID_HOME` or `local.properties` if Gradle cannot find the SDK.

### Release Verification

Before publishing any Release:
1. Re-run `./gradlew :mobile:extractReleaseVersionControlInfo --rerun-tasks :mobile:assembleRelease` to guarantee that the generated APK's embedded `META-INF/version-control-info.textproto` points to the target release commit.
2. Run `node scripts/verify_release_apk.js` to ensure the Release APK matches the current source code and SHA-256 with 0 diffs before pushing tags or creating GitHub releases.
3. Keep SHA-256 checksum files organized under `checksums/` (e.g. `checksums/DiPlay-vX.Y.Z-legacy-release.apk.sha256`) instead of polluting the repository root.
4. **GitHub Release 发布规范**：
   - **标题命名规范**：Release 标题必须严格遵循完整版本规范 `DiPlay vX.Y.Z — [中文亮点概述]`（直接采用对应的 `docs/RELEASE-NOTES-X.Y.Z.md` 顶部的第一行大标题），严禁仅写简单的 `DiPlay vX.Y.Z`。
   - **附件完整性规范**：除了上传生成的 Release APK 以外，必须同时将计算好的 SHA-256 校验文件一并上传至该 Release 的 assets 附件中（如 `checksums/DiPlay-vX.Y.Z-legacy-release.apk.sha256`）。
   - 示例命令：
     ```sh
     gh release create vX.Y.Z DiPlay-vX.Y.Z-legacy-release.apk checksums/DiPlay-vX.Y.Z-legacy-release.apk.sha256 \
       -t "DiPlay vX.Y.Z — [中文亮点概述]" -F docs/RELEASE-NOTES-X.Y.Z.md
     ```

## Adding or moving a setting

The Settings screen is grouped by driver goal, not by implementation.
`SettingsCategory`, `SettingsSection` and `SettingsInformationArchitecture` at the top of
`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` define the groups.
`SettingsLayoutPolicyTest` fails when a section has no category or has more than one.

### Choose the category

Ask which goal the driver has when they look for the setting. Use the first row that fits.

| Category | Put a setting here when it controls… | Examples |
| --- | --- | --- |
| Connection | how the iPhone connects and how DiPlay starts | connection setup, connect on open, start with the car, car hotspot automation, iPhone choice, Android permissions |
| Display | how CarPlay looks on the head-unit screen | day/night mode, picture, size, resolution, frame rate, dock, system bars, multi-window resolution |
| Audio | what the driver hears | media and navigation streams, music buffer |
| Navigation | location and turn-by-turn guidance | location to iPhone |
| Vehicle | how CarPlay fits this car and its driver | driving side, car button, gestures that conflict with the head unit |
| Diagnostics | troubleshooting evidence | diagnostic reports |
| Advanced | experimental, firmware-specific or risky behavior | dashboard map, split screen, screen rotation, side panel, HEVC video, audio focus, audio channel mapping, buffered music, vehicle data |
| Overview | nothing new | see "Overview" below |

A setting goes to Advanced when it is experimental, depends on specific firmware, or is an opt-in that can break sound, video or the connection on some head units.
A control that fixes a common problem and is safe at its default (for example the audio stream choice) stays in its category.
Exception: a gated control that only its own audience sees, and that completes an everyday goal, stays in that goal's category.
Example: auto car hotspot appears only for car-hotspot users, so it is in Connection.

Mark an experimental setting or card with the title suffix ` (experimental)`, never `· experimental`, a dash, or an `Experimental …` prefix.
Each locale MUST use its existing form: `(experimental)`, `（实验性）`.

### Add the code

1. To extend an existing card, add the control inside the `filteredSection(...)` block of a card in the correct category.
2. For a new card, add a `SettingsSection` entry and map it to exactly one category in `SettingsInformationArchitecture.sectionsByCategory`.
3. Build the new card with `filteredSection(content, SettingsSection.X, …)` inside `allSettingsSections()`.
   A plain `section(...)` call there MUST NOT be used: it renders on every category page.
4. A setting MUST NOT appear in two categories.
   Overview quick settings are the only duplicates. They MUST use the same persistence as the full control. Overview SHOULD NOT have more than four quick settings.
5. If the change reconnects CarPlay or applies at the next connection, the description MUST say so.
   A setting that only takes effect at the next connection MUST call `markReconnectNeeded()` after it saves.
   This shows the "Reconnect now" bar. It MUST NOT drop a running session without the driver's consent.
   Reconnect at once (`reconnectIfRunning()`) only after a dialog button that says "Apply and reconnect", or when the description says that the change reconnects CarPlay.
6. Add each new string to `common/src/main/res/values/` (English) and `values-zh-rCN/` (Simplified Chinese). These are the only two languages: the app defaults to Simplified Chinese (`AppLocale.DEFAULT`) until the driver picks English, and `mobile` keeps only `en` and `zh-rCN` library resources (`localeFilters`). Do not add other `values-xx` folders.
   New Settings copy SHOULD use the `settings_` prefix. `SettingsTranslationsTest` fails when a `settings_*` string has no translation.
7. A control that opens a choice uses `button()` with the text `"Title · Value"`.
   That form renders as a setting row with the value and a chevron, and Search indexes the title.
8. When you move a control between categories, update `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`.

### Overview

Overview holds the connection status, links to the categories, quick settings, About and Language.
Do not add a new setting to Overview. Add it to its category. Then promote it to quick settings only if drivers change it often.

## Guangqi head unit (GAEI AVNTG2A10)

`main` targets one head unit: Guangqi GS5, GAEI AVNTG2A10, Freescale i.MX6 SABRESD, Android 4.4.2 (API 19), kernel 3.0.35. The app version line is 0.4.x. The install id is `com.shihab.diplay`; the code package is `com.shilapi.xcertplay`. Ship `armeabi-v7a` only (`mobile/build.gradle.kts`).

### Platform identity

The ROM has no USB or VPN permission activity, so an ordinary app never gets USB access. Every car build MUST be platform-signed and MUST keep `android:sharedUserId="android.uid.system"` in `mobile/src/main/AndroidManifest.xml`. Android 4.4 needs v1 signing; v2 is also enabled. Platform certificate SHA-256: `7fbae817b7db24642d8959c347b561c003c6cfde2cb10f909c932f21a4d6d804` (`android@freescale.com`). On this line that change is commit `14bea79`. Commit `256c41d` is the same message on the old `guangqi` branch and is not an ancestor of `main`.

The platform keystore and the MFi certificate are not in this repository. `resolvePlatformKeystore()` in `mobile/build.gradle.kts` uses `ANDROID_KEYSTORE_PATH`, then `platform.jks` / `release-signing.jks` / `release-signing.keystore` in the repo root, then `../../signing/platform.p12` and `../../apps/EngineerMode/platform.jks` in the parent carlife tree. MFi assets come from `DIPLAY_AUTH_ASSETS_DIR`, `.private/runtime-assets`, or `auth-assets`. Debug and release both use that platform signing config. The debug build adds `applicationIdSuffix = ".hudtest"`, so it installs as `com.shihab.diplay.hudtest` and does not update the release system app.

`.github/workflows/android.yml` runs the check command on a push to `main`, on every pull request, and on manual dispatch. It does not provision the keystore or the MFi assets. `.github/workflows/dev-build.yml` builds the car APK on a push to `dev` and on manual dispatch: `assembleStandaloneRelease`, with `offline-mfi` and the platform keystore taken from Actions secrets. The old Guangqi Release Build workflow is disabled and is not this line's build. Publishing a GitHub Release runs `verify-release-apk-version.yml` and `verify-release-signature.yml`. Those check the uploaded APK; they do not build it.

Switching `sharedUserId`, or installing the release id over the `.hudtest` id, is not an update. Uninstall first. That clears the app's data.

### Wired link

The kernel TUN driver works on 3.0.35: OpenVPN runs in tun mode, and a Go 1.27 probe opened the device (`docs/go-netprobe车机测试报告.md` in the parent carlife tree). The node is `/dev/tun` (char 10,200, owner 1000:1016). There is no `/dev/net/tun` until something links it, and `/dev` is tmpfs, so the link must be made again after every boot (`mkdir -p /dev/net && ln -sf /dev/tun /dev/net/tun`, as the OpenVPN start script does). Creating an interface (`TUNSETIFF`) needs `CAP_NET_ADMIN`: root has it, DiPlay's uid 1000 does not.

The system VPN can be brought up, but not the standard way. This ROM never sends `android.intent.action.USER_STARTING`, so `ConnectivityService.mVpns` stays empty until the app sends that broadcast itself. The ROM also has no `com.android.vpndialogs`, so `VpnService.prepare()` cannot show its consent screen, and preparing goes through Binder instead.

Wired CarPlay still does not use TUN. The kernel `cdc_ncm` driver competes for the iPhone's NCM interface, and unbinding it needs root. The NCM bulk-out endpoint only becomes writable after the iPhone has stopped resending its SYN. Wired CarPlay therefore uses the userspace lwIP relay in `shared/src/main/jni/lwip_ncm_relay.c`. Details are in `docs/DiPlay-VPN-TUN修复记录.md` in the parent carlife tree.

### Wireless link

A fresh install uses Wi-Fi Direct (`WirelessHotspotMode.WIFI_P2P`). That is the path `AirPlayPersistence.loadWirelessHotspotMode` treats as verified on this unit. API 19 starts it with `LegacyWifiP2pGroupManager`. `WifiP2pGroupManager` requires API 28 and is not this unit's path. The radio is an AR6003, 2.4 GHz only; `WifiP2pChannels.supportedChannels` is channels 1–11, and a 5 GHz choice falls back to Auto. On API 19 the preferred channel is pinned with the hidden `WifiP2pManager.setWifiP2pChannels` (the supplicant's `P2P_SET disallow_freq`); Auto clears the pin, a refused channel falls back to Auto, and the pin is cleared when the group closes. The ROM has no `iwconfig`, `iw` or `wpa_cli`, and as group owner the car does no legacy power save.

`HOSTAPD` is the root hotspot opt-in. With that opt-in off, a stored `HOSTAPD` value resolves back to `WIFI_P2P`. When legacy Wi-Fi Direct fails with `createGroup code=1`, leave it and use Existing Wi-Fi / Same LAN or a cable. The ECARX car-hotspot helpers are still in the tree for other units; they are not this unit's default.

### Bluetooth

The built-in module is Parrot. While a CarPlay session is up, `GuangqiAutoConnect` turns off the car's global auto-connect (`BT_CANSYNC` in the vendor backup, `BluetoothAdapter.setBTAutoConn`) and disconnects the phone the way the car's Bluetooth page does (`disconnectDevice`). The radio stays on. The previous switch value is restored when the session ends, including after this process dies, so a crash must not leave the driver's switch off.

A plugged-in USB dongle replaces that module for the data channel. `usbbt/` is the userspace stack (Realtek firmware, HCI, SDP, RFCOMM). `HybridBluetoothAdapter` prefers the dongle whenever it is present: the iPhone pairs to the dongle's BD_ADDR, and the built-in adapter cannot resume that RFCOMM session. Do not bring back the Nissan Hangsheng stack (`com.hsae.bluetoothservice`, `HsaeBluetoothAdapter`, `HsaeRfcommStream`).

### Instrument cluster

The cluster is the only other PRESENTATION display (display 1, 1280×720). Show it with a plain `Presentation`. Do not call `setType()` with `TYPE_PHONE` (2002) or `TYPE_SYSTEM_ALERT` (2003): that window lands on the main screen. `ClusterMapPresentation` keeps the Guangqi presentation across Home so the cluster layer stays up.

Until a navigation source reports ready, the cluster MCU draws its own "导航功能未启动" placeholder and does not composite our layer. Report ready the AMap way, from `GuangqiCluster`: broadcast `AUTONAVI_STANDARD_BROADCAST_SEND` to `gaei.cluster.service` with `KEY_TYPE=10019` and `EXTRA_STATE=1`, then `EXTRA_STATE=3`. Tested on the car: this does not launch AMap, and the cluster then shows our layer. Send `EXTRA_STATE=2` when the layer stops. Do not use the Tencent broadcast (`WECARNAVIAUTO_STANDARD_BROADCAST_SEND`, `KEY_TYPE=2000`): the service launches `com.tencent.wecarnavi`, which then owns the cluster.

The VPU decodes about 37 fps of 720p in total, and SurfaceFlinger alternates the main and cluster layers. On this unit the cluster stream defaults to 4 fps at 100% of 1280×720 (`GuangqiCluster.CLUSTER_FPS`, `CLUSTER_SCALE_PERCENT`). The safe area measured on the car is 964×550 inside that layer: left 158, top 82, right 158, bottom 88. `CarPlayClusterDisplay`'s 30 fps and BYD-panel safe area are the generic defaults and are not used when `gaei.cluster.service` is installed.

Test overrides `debug.diplay.cl.mm`, `debug.diplay.cl.url`, and `debug.diplay.cl.safe` are root `setprop`s and apply at the next CarPlay connection (`GuangqiCluster.Tuning`).

### Location, speed, and wheel keys

Read GNSS through `GuangqiNmeaLocationProvider`: LocationManager's `gps` provider and the HAL's NMEA. The receiver is the u-blox on `/dev/ttyACM17` behind `hw/gps.SABRESD.so`. `/dev/athrnmea` (Atheros, `ingsvcd`) is not the receiver in use.

Speed and gear come from `GuangqiVehicleSpeedSource`: `ServiceManager.getService("vehicle")`, `getDefinedPacket` (transact 7). The reply is `[noException][hasPacket][typeId]` and then the fields in DEX alphabetical order. Type 5 is speed (`m_nSpeed`, km/h). Type 10 is gear (`m_byteGear`: 11 S, 12 D, 13 P, 14 R, 15 N). The packet layout lives in the parent carlife tree at `Trumpchi-GS5/docs/can_packet_definitions.txt`, not in this repository.

The wheel's call (137), hang-up (138), and long-press voice (57) keys never reach an app. `GuangqiCarlifeKeys` takes them the way stock CarLife does: register with the `keyback` service under `com.thundersoft.connectivity.AudioRsManager`, and broadcast the CarLife connected state. It force-stops stock CarLife first, because that service keeps one listener under that name. Taking the keys is opt-in (`WheelIntegrationSettings`). `NavigationOutputs` still owns the turn overlay, the call state those keys read, and the one-second cluster stream tick. Keep the tick until the iPhone has reported the cluster URL.

### Left in the tree on purpose

These are shared leftovers, not this unit's path. Do not wire them back up for the GS5:

- BYD HUD, DiLink cluster layouts, `LocalAdb`, the USB-permission accessibility service, and `WheelKeyService`. This image has no `android.hardware.bydauto`, no accessibility settings, and adbd does not listen on TCP.
- The in-app updater. A platform-signed APK will not match an upstream release signature.
- Upstream notes under `docs/` that still describe BYD. The GS5 behavior is `GuangqiCluster`, `GuangqiAutoConnect`, `GuangqiCarlifeKeys`, `GuangqiNmeaLocationProvider`, and `GuangqiVehicleSpeedSource`.

### On the car

Remote access goes through `https://avnt.xyun.host` (`/status`, `/exec`, `/file`, `/app`). Send `Authorization: Bearer $CAR_OPERATOR_TOKEN` and never commit the token. Do not open port 55666 with the device token: a new hello disconnects the car. The toolbox has no `head` or `tail`.

A release install logs to `/data/data/com.shihab.diplay/files/logs/diplay.log`. A debug (`.hudtest`) install logs under `/data/data/com.shihab.diplay.hudtest/files/logs/diplay.log`.
