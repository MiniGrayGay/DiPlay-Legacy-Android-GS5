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
| Connection | how the iPhone connects and how DiPlay starts | connection setup, connect on open, start with the car, USB permissions, car hotspot automation, iPhone choice, Android permissions |
| Display | how CarPlay looks on the head-unit screen | day/night mode, picture, size, resolution, frame rate, dock, system bars, multi-window resolution |
| Audio | what the driver hears | media and navigation streams, music buffer |
| Navigation | location and turn-by-turn guidance | location to iPhone, BYD HUD and cluster guidance |
| Vehicle | how CarPlay fits this car and its driver | driving side, wheel keys (Siri, BYD joystick and map zoom), car button, gestures that conflict with the head unit |
| Diagnostics | troubleshooting evidence | diagnostic reports |
| Advanced | experimental, firmware-specific or risky behavior | dashboard map, split screen, screen rotation, side panel, HEVC video, audio focus, audio channel mapping, buffered music, vehicle data |
| Overview | nothing new | see "Overview" below |

A setting goes to Advanced when it is experimental, depends on specific firmware, or is an opt-in that can break sound, video or the connection on some head units.
A control that fixes a common problem and is safe at its default (for example the audio stream choice) stays in its category.
Exception: a gated control that only its own audience sees, and that completes an everyday goal, stays in that goal's category.
Example: auto car hotspot needs ADB but appears only for car-hotspot users, so it is in Connection.

Mark an experimental setting or card with the title suffix ` (experimental)`, never `· experimental`, a dash, or an `Experimental …` prefix.
Each locale MUST use its existing form: `(تجريبي)`, `(experimental)`, `(экспериментально)`, `(експериментально)`, `（实验性）`, `（實驗性）`.

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
6. Add each new string to `common/src/main/res/values/` and to every `values-xx` locale folder.
   New Settings copy SHOULD use the `settings_` prefix. `SettingsTranslationsTest` fails when a `settings_*` string has no translation.
7. A control that opens a choice uses `button()` with the text `"Title · Value"`.
   That form renders as a setting row with the value and a chevron, and Search indexes the title.
8. When you move a control between categories, update `AdaptiveSettingsUiTest.settingsLiveWhereDriversLookForThem`.

### Overview

Overview holds the connection status, links to the categories, quick settings, About and Language.
Do not add a new setting to Overview. Add it to its category. Then promote it to quick settings only if drivers change it often.

## Guangqi head unit (GAEI AVNTG2A10)

This fork targets a Guangqi (GAEI) head unit: Freescale i.MX6 SABRESD, Android 4.4.2 (API 19), kernel 3.0.35.

- The ROM strips the USB and VPN permission activities, so an ordinary app never gets USB access.
  Every build for this unit MUST be platform-signed AND declare `android:sharedUserId="android.uid.system"` in `mobile/src/main/AndroidManifest.xml`.
  Platform certificate SHA-256: `7fbae817b7db24642d8959c347b561c003c6cfde2cb10f909c932f21a4d6d804` (`android@freescale.com`).
  A branch cut from upstream `main` must carry commit `256c41d` ("广汽: 使用平台签名并以 system uid 运行") before it is built for the car.
- The kernel TUN and the system VPN are unusable on 3.0.35, so wired CarPlay uses the userspace lwIP path (`lwip_ncm_relay.c`).
- The instrument cluster is the PRESENTATION display (display 1, 1280×720). Show content there with a plain `Presentation` and never call `setType()`: `setType(2002)` puts the window on the main screen.
  Until a navigation source reports ready, the cluster MCU shows its own "导航功能未启动" placeholder and does not composite our layer.
  Report ready the AMap way: broadcast `AUTONAVI_STANDARD_BROADCAST_SEND` to package `gaei.cluster.service` with `KEY_TYPE=10019` and `EXTRA_STATE=1`, then `EXTRA_STATE=3`. Tested on the car: this does not launch AMap and the cluster then shows our layer.
  Send `EXTRA_STATE=2` when the cluster content stops.
  Do not use the Tencent path (`WECARNAVIAUTO_STANDARD_BROADCAST_SEND`, `KEY_TYPE=2000`, `TYPE`): `gaei.cluster.service` launches `com.tencent.wecarnavi`, which must be force-stopped before our window can take the cluster.
- The VPU decodes about 37 fps of 720p High in total. Never run a second decoder for the same stream: show the CarPlay cluster stream (type 111) on the cluster, or draw one decoded frame to both screens with GL.
- The cluster path shows only 4-5 fps continuously (a 30 fps feed bursts to 30 fps for 1-2 s about every 15 s, then falls back), and the stock AMap and Tencent clusters push 4-5 fps. The cluster stream defaults to 4 fps; frames beyond that are decoded and never shown.
- Cluster declaration overrides for testing (`debug.diplay.cl.mm`, `debug.diplay.cl.url`, `debug.diplay.cl.safe`) are set with root `setprop` and apply at the next CarPlay connection; see `GuangqiCluster.Tuning`.
- Build car APKs in CI, not locally: `.github/workflows/guangqi-build.yml` takes the platform keystore and the MFi certificate and identity from repository secrets, and uploads the signed release APK as an artifact.
  Unit test failures do not block that build. Neither the platform key nor the MFi assets are checked into this repository.
- Changing the app between a normal uid and `android.uid.system` cannot be installed as an update. The installed DiPlay must be uninstalled first, which clears its data.
- Remote access to the unit goes through the car relay at `https://avnt.xyun.host` (`/status`, `/exec`, `/file`, `/app`).
  Send `Authorization: Bearer $CAR_OPERATOR_TOKEN` and never commit the token. Do not connect to port 55666 with the device token: a new hello disconnects the car.
  The unit's toolbox has no `head` or `tail`. DiPlay's log is `/data/data/com.shihab.diplay/files/logs/diplay.log`.
