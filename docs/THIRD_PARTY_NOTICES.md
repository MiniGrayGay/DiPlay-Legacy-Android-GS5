# Credits and license notices

## Receiver

DiPlay is a modified version of [xcertplay by shilapi](https://github.com/shilapi/xcertplay). The upstream receiver is licensed under GNU GPL version 3; the full text is in `LICENSE` and the original README is retained in `docs/UPSTREAM-README.md`.

Upstream credits [LIVI](https://github.com/f-io/LIVI) and [Showcase](https://github.com/amineross/showcase) for protocol research. Existing source comments and attribution are preserved.

## Home and settings UI

`common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` adapts the palette, visual arrangement and interface copy of the [DiAuto project](https://github.com/shihabal3amri/DiAuto). DiAuto's source is licensed under AGPL version 3. The UI file is marked AGPL-3.0-only; its license text is included in `docs/licenses/DiAuto-AGPL-3.0.txt`.

## CarPlay icon

The unmodified icon was obtained from Apple's developer site at:

https://developer.apple.com/assets/elements/icons/carplay/carplay-96x96_2x.png

CarPlay and the CarPlay icon are Apple Inc. marks/assets. This asset is not covered by the project's open-source code license. Its use here does not imply Apple approval or certification.

## Runtime dependencies

- AndroidX and Jetpack Compose — Android Open Source Project; Apache License 2.0.
- Kotlin standard library — JetBrains; Apache License 2.0.
- Bouncy Castle 1.79 — The Legion of the Bouncy Castle Inc.; Bouncy Castle license (MIT-style).
- JmDNS 3.6.3 — JmDNS contributors; Apache License 2.0.
- SLF4J — QOS.ch; MIT license.

Gradle dependency declarations and version catalog accompany the source. License files available in the resolved artifacts are included under `docs/licenses/dependencies/`.

## Native call echo cancellation

DiPlay vendors a subset of [SpeexDSP 1.2.1](https://github.com/xiph/speexdsp/tree/SpeexDSP-1.2.1) from Xiph.org Foundation and its contributors, including the acoustic echo canceller, preprocessor and FFT/filterbank support. It is compiled into `libspeex_echo.so` for the optional experimental **Call echo cancellation** feature.

SpeexDSP uses a BSD-style three-clause license. The full copyright notices, redistribution conditions and disclaimer are retained in [the vendored COPYING file](../shared/src/main/jni/speexdsp/COPYING). Component source headers retain their additional notices, including KISS FFT's Mark Borgerding attribution. The [vendoring notes](../shared/src/main/jni/speexdsp/README.md) identify the source version and build configuration.

## Native audio and networking libraries

- **libopus 1.3.1** — Opus Audio Codec, Xiph.org Foundation and contributors; BSD-style license. Provides software Opus microphone encoding on legacy devices without a compatible system MediaCodec Opus encoder. The build retrieves the pinned upstream source (commit `e85ed7726db5d677c9c0677298ea0cb9c65bdd23`) using `scripts/prepare-libopus.py` and compiles it into `libopus_microphone.so`; the license text ships in the upstream source (COPYING).
- **lwIP 2.1.3** (STABLE-2_1_3_RELEASE) — the lwIP TCP/IP stack, originally by Adam Dunkels and the Swedish Institute of Computer Science, maintained by the lwIP contributors; BSD-style license (COPYING in the upstream source). The build retrieves the pinned commit using `scripts/prepare-lwip.py` and compiles it, with the port in `shared/src/main/jni/lwip_port`, into the userspace NCM relay library used by wired CarPlay.

## Bluetooth firmware

The external USB Bluetooth stack bundles 31 Realtek firmware blobs (RTL8761B/RTL8723-class adapters) under `shared/src/main/assets/usbbt_fw/`. They are redistributed under the terms of the license file bundled with them, `LICENCE.rtlwifi_firmware.txt` in the same directory (© Realtek Semiconductor Corp.). The firmware is loaded only when a supported USB Bluetooth adapter is attached and the external Bluetooth feature is enabled.

## Experimental authentication data

The public preview APK includes an accessory certificate/key pair recovered from public Carlinkit C2Air Allwinner V821 firmware during the owner's local investigation. These data are not newly generated Apple-issued credentials for DiPlay and are not relicensed as project source code. They are bundled in the preview APK to reproduce the offline experiment; continued acceptance and suitability for general distribution are unresolved. The source archive does not contain the private key, and the separate Android APK-signing key is never distributed.

## Download website

The static site layout, CSS and generator adapt DiAuto (AGPL-3.0). The AGPL license text is included with the source.
