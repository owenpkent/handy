# Handy Voice Control

[![Android tests](https://github.com/owenpkent/handy/actions/workflows/android-tests.yml/badge.svg)](https://github.com/owenpkent/handy/actions/workflows/android-tests.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/owenpkent/handy-releases?label=release)](https://github.com/owenpkent/handy-releases/releases/latest)

Accessible control apps for The Handy device (and, from v0.23, Lovense Bluetooth toys), designed for users with disabilities.

## Status

**Active Development**: all current work is in [`android-v2/`](android-v2/). Current build is **v0.23** (Lovense toy support, a test build from the unmerged `feat/lovense-ble` branch, PRs #3 and #4 open). Everything else has moved to [`legacy/`](legacy/) and is kept for reference only. A second, early-stage track designs our own low-profile wearable device: see [Wearable device](#wearable-device-early-stage).

## Active app: android-v2

A hands-free Android client that recognizes spoken commands by matching against **the user's own prerecorded voice samples** (MFCC + DTW, no cloud ASR). Built for non-standard speech, where general-purpose speech recognition fails.

- Records 3 takes of each command on first launch; matches against the user's voice on-device, fully offline.
- Voice vocabulary: `begin` / `stop` / `faster` / `slower`. Deliberately small — every extra label narrows the acoustic gaps the matcher relies on.
- **One-button home screen** (v0.21): opens on a single big Start / Stop listening button; everything else is behind "More controls".
- **Lovense toy support** (v0.23, untested on hardware): Settings can switch the device from The Handy to a Lovense Bluetooth toy (built for a Gush 2). A dedicated toy page replaces the voice home, a foreground service keeps the link alive with the screen off, and a test panel probes commands and measures the toy's idle shut-off. Published as a test build from the unmerged `feat/lovense-ble` branch. See [android-v2/docs/LOVENSE.md](android-v2/docs/LOVENSE.md).
- Tap-shortcut buttons mirror every voice command, plus a Manual tab with sliders and a 4×3 pattern grid.
- **Humanized motion** — a steady speed drifts organically (speed *and* stroke depth) so it never feels robotic. Strength is a Settings slider (0 = off).
- **Gentle stop** — every stop eases the speed down to 0 over ~1.5 s instead of a hard halt.
- **Mic off stops the device**: turning listening off (mic tap, or the idle auto-stop) also stops motion, so the device never keeps running with no voice control left.
- **Ignores its own motor noise** *(optional)* — if the running device is ever misheard as a command (it was false-firing "stop"), record its sound once (Settings → Record device sound to ignore) and it's added to a reject class. Purely opt-in — never part of setup, and normal use doesn't need it.
- **Picture-in-Picture** — float a compact control bar (stop / slower / faster) over other apps.
- In-app self-update from GitHub Releases.

See [android-v2/README.md](android-v2/README.md) and [android-v2/docs/](android-v2/docs/) for architecture, the voice engine, the command vocabulary, training, and troubleshooting.

### Install

Download the latest APK from the [releases page](https://github.com/owenpkent/handy-releases/releases/latest) and open it on an Android phone (Android 8.0 or newer). Android will ask you to allow installing from that source, since the app is not in an app store. After the first install the app updates itself: it offers each new release with one tap.

First launch walks you through recording your voice commands, then asks for your Handy connection key (or, for a Lovense toy, Bluetooth permission).

### Build

Needs JDK 17 or newer and the Android SDK (Android Studio provides both). From `android-v2/`:

```bash
./gradlew :app:testDebugUnitTest  # JVM unit tests, a few seconds, no device needed
./gradlew :app:assembleDebug      # build
./gradlew :app:installDebug       # install to an attached device
```

On Windows use `.\gradlew.bat`, and if Gradle cannot find Java set `JAVA_HOME` first, for example `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'`.

Every debug build drops a version-named APK into [`apks/`](apks/) for sideloading.

## Wearable device (early stage)

A DIY wearable meant to be worn under trousers while seated and to work around an external condom catheter, controlled by the same app over BLE (the app's `DeviceTransport` seam and Lovense BLE support from v0.23 are the groundwork). Vibration first (five LRAs in an open C-shaped silicone sleeve, one DRV2605L each), soft pneumatics later. Nothing physical is built yet.

- Research, safety constraints, parts and build plan: [docs/WEARABLE_DEVICE_RESEARCH.md](docs/WEARABLE_DEVICE_RESEARCH.md)
- CAD assembly ([nl-cad](https://github.com/owenpkent/nl-cad) project, v5): [hardware/wearable-sleeve/](hardware/wearable-sleeve/)
- Vendor CAD sources: [hardware/cad/README.md](hardware/cad/README.md)

## Legacy apps

Moved to [`legacy/`](legacy/) — see [legacy/README.md](legacy/README.md):

| Folder | Device | Was | Status |
|--------|--------|-----|--------|
| `legacy/android` | Handy V2 | Android app — Vosk offline ASR | Working, frozen (superseded by android-v2) |
| `legacy/v2-web` | Handy V2 | Web app — preset grid, sliders | Working, frozen |
| `legacy/v1-web` | Handy V1 | Web app — Deepgram voice | Legacy |
| `legacy/alexa` | Handy V2 | Alexa skill | Shelved (Amazon content filter) |
| `legacy/homeassistant` | Handy V2 | HA custom component | Reference |

## Project Structure

```
handy/
├── android-v2/             # Android app — prerecorded voice samples (ACTIVE)
│   ├── README.md
│   └── docs/               # ARCHITECTURE, VOICE_ENGINE, COMMANDS, TRAINING, TROUBLESHOOTING, LOVENSE, SIGNING
├── apks/                   # Version-named debug APK installers
├── docs/                   # Shared reference (Handy API notes, integration guides)
│   ├── HANDY_API_NOTES.md
│   ├── DEEPGRAM_GUIDE.md
│   ├── ALEXA_GUIDE.md
│   ├── HOME_ASSISTANT_GUIDE.md
│   ├── CONSTELLATION_INTEGRATION_GUIDE.md
│   └── WEARABLE_DEVICE_RESEARCH.md
├── hardware/               # Wearable device CAD
│   ├── cad/                # Vendor CAD source list (files gitignored)
│   └── wearable-sleeve/    # nl-cad project: brief, parts, assembly, builds
├── legacy/                 # Archived apps (see legacy/README.md)
├── .github/                # Issue forms, pull request template, test workflow
├── CLAUDE.md               # AI assistant reference (auto-loaded by Claude Code)
├── CODE_OF_CONDUCT.md
├── CONTRIBUTING.md
├── LICENSE
├── SECURITY.md
├── README.md               # This file
└── TODO.md                 # Task tracking
```

## Handy V2 API

| Item | Value |
|------|-------|
| Base URL | `https://www.handyfeeling.com/api/handy/v2` |
| Auth | `X-Connection-Key` header (key from [handyfeeling.com](https://user.handyfeeling.com)) |

API findings and gotchas: [docs/HANDY_API_NOTES.md](docs/HANDY_API_NOTES.md).

## Safety

- **Global max speed + stroke band** — the device never exceeds the configured limits.
- **Stop** — always one tap (or word) away, including in the Picture-in-Picture bar. Eases the speed down to 0 over ~1.5 s rather than slamming to a halt.
- **Ignores the device's own sound** — a trained reject class stops the motor noise from false-triggering commands.
- **Visual + audible feedback** — every action is confirmed on screen and with an ack tone.

## Contributing

Contributions are welcome, and you do not need to write code to help. The most useful thing right now is a **device report** from anyone who owns a Lovense toy: support was written from public protocol notes and has barely been tested on hardware.

- How to help, build and submit changes: [CONTRIBUTING.md](CONTRIBUTING.md)
- [Report a bug](https://github.com/owenpkent/handy/issues/new?template=bug_report.yml), [report how a device behaves](https://github.com/owenpkent/handy/issues/new?template=device_report.yml), or [suggest an idea](https://github.com/owenpkent/handy/issues/new?template=feature_request.yml)
- Security problems, privately: [SECURITY.md](SECURITY.md)
- Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md)

## License

[MIT](LICENSE). The software comes with no warranty. It is not a medical device, and it controls motors that act on the body: set the speed and stroke limits in Settings before use, and use it at your own risk.

---

**Note**: Independent accessibility project. It is not affiliated with, endorsed by or supported by Ohdoki (The Handy) or Lovense; those names are trademarks of their owners and are used only to say which devices the app works with.
