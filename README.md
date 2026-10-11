# SW411 Control

Mobile-first web controller for the FeinTech SW411 HDMI 2.1 switch over its USB-C/CH340 serial service port.

## Features

- HDMI input switching (1–4)
- Custom input names
- Power, Auto Source and eARC/ARC control
- Firmware version and temperature
- Server-Sent Events (SSE) live status — no browser polling loop
- Mobile-first dark UI
- GitHub self-update from the overflow menu
- Home Assistant REST API with Bearer token
- Optional MQTT + Home Assistant Discovery
- PWA manifest/service worker prepared
- Hold-to-confirm protection for reboot and software update

## Audio EDID

The SW411 exposes four audio modes in its normal UI/manual: 2.0, 5.1, 7.1 and PASS. The confirmed USB serial CLI in firmware 2.10.13 exposes eARC, input selection, power, Auto Source, temperature, firmware and HDCP commands, but no Audio EDID set/read command. SW411 Control therefore does not send guessed Audio EDID commands.

## Install / update

```bash
curl -fsSL https://raw.githubusercontent.com/FeromonDE/SW411-Control/main/install-sw411.sh -o /root/install-sw411.sh
chmod +x /root/install-sw411.sh
/root/install-sw411.sh
```

Then open the IP address printed by the installer.

## Home Assistant REST API

Open **⋮ → Settings** in the web UI and copy the generated Bearer token.

State:

```text
GET /api/ha/state
Authorization: Bearer TOKEN
```

Commands:

```text
POST /api/ha/input/1
POST /api/ha/auto      {"enabled": true}
POST /api/ha/earc      {"enabled": true}
POST /api/ha/power     {"enabled": true}
POST /api/ha/reboot
```

## MQTT

Enable MQTT in **⋮ → Settings**, enter the broker address and credentials, and keep the default discovery prefix `homeassistant` for Home Assistant MQTT Discovery.

Default base topic: `sw411`.


## Android app

The Android app in `android/` uses native Jetpack Compose screens. It reproduces the web panel's layout, colors, dimensions, SVG icons and controls without WebView, embedded HTML or browser navigation. It communicates directly with the Nexbox REST + SSE API.

Features:

- Native implementation of the web panel's visual design, including responsive grids and custom switches
- HDMI 1–4 with custom names from the server
- Power, Auto Source and eARC/ARC control
- Firmware, temperature and current input
- SSE live-status with automatic reconnect
- 1.5-second hold-to-confirm reboot / controller update protection with a visible progress line
- Configurable Nexbox address (default `http://192.168.2.159`)
- Full in-app settings for input names, MQTT and Home Assistant
- Server address in **⋮ → Адрес сервера**
- Connection status, refresh and address editing when Nexbox is unavailable
- Android Back closes settings or the overflow menu before leaving the app

Version 0.3.0 replaces the temporary WebView implementation from 0.2.0, preserves the saved server address and uses the same release certificate. SSE runs while the activity is in the foreground and its idle socket is cancelled when the app leaves the foreground. MQTT passwords and API tokens are not saved in Android preferences. An empty MQTT password keeps the existing server password.

The app controls the existing Nexbox service, which owns the USB connection to the SW411. The phone does not open its own USB serial port.

CI runs API unit tests and Android Lint. Optional Android 15 emulator tests use a local mock controller, verify source switching and settings saves, check short-press/hold reboot behavior and confirm that the activity contains no WebView. Screenshots, reports and emulator logs remain temporary on the runner and are not uploaded to Actions storage.

GitHub Actions builds APKs only through manual `workflow_dispatch`. The signed release APK (or debug APK when signing secrets are missing) is uploaded as the `SW411-Control-Android` artifact and kept for 1 day. Screenshots, UI reports and emulator logs are not uploaded. Unit tests, Android Lint and release signature verification still run; optional emulator tests can be enabled with the `ui_tests` input. Gradle caching is read-only to avoid adding new caches.

Workflow: `.github/workflows/android-apk.yml`.


## Android release signing

The Android app uses a dedicated stable release certificate. The private signing key is intentionally **not** stored in this public repository.

Public certificate:

`android/signing/SW411-Control-release-cert.pem`

Certificate SHA-256 fingerprint:

`0E:35:9C:7D:8F:31:19:6C:A4:2E:34:65:66:FC:50:FF:54:A1:F7:FF:08:D3:98:C0:3B:F4:7A:3B:CF:CA:39:B1`

GitHub Actions expects these repository secrets:

- `SW411_SIGNING_KEY_B64`
- `SW411_SIGNING_STORE_PASSWORD`
- `SW411_SIGNING_KEY_ALIAS`
- `SW411_SIGNING_KEY_PASSWORD`

When the secrets are present, the workflow builds `SW411-Control-release.apk` and verifies its APK signature with `apksigner`. Without them, CI falls back to a debug APK.

