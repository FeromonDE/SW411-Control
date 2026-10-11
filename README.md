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

A native Android client lives in `android/` and uses the same REST + SSE backend as the web UI.

Features:

- Native Jetpack Compose UI matching the mobile web panel
- HDMI 1–4 with custom names from the server
- Power, Auto Source and eARC/ARC control
- Firmware, temperature and current input
- SSE live-status with automatic reconnect
- Protected reboot / controller update confirmation
- Configurable Nexbox address (default `http://192.168.2.159`)
- Direct link back to the web settings panel

GitHub Actions only runs for changes under `android/**`, changes to the Android workflow itself, or manual dispatch. The installable debug APK is uploaded as the `SW411-Control-Android` artifact and kept for 7 days.

Workflow: `.github/workflows/android-apk.yml`.
