# Handy Voice Control

Accessible control apps for The Handy device, designed for users with disabilities.

## Status

**Active Development** — all current work is in [`android-v2/`](android-v2/). Everything else has moved to [`legacy/`](legacy/) and is kept for reference only.

## Active app: android-v2

A hands-free Android client that recognizes spoken commands by matching against **the user's own prerecorded voice samples** (MFCC + DTW, no cloud ASR). Built for non-standard speech, where general-purpose speech recognition fails.

- Records 3 takes of each command on first launch; matches against the user's voice on-device, fully offline.
- Voice vocabulary: `begin` / `stop` / `faster` / `slower` / `deeper` / `shallow` / `random`.
- Tap-shortcut buttons mirror every voice command, plus a Manual tab with sliders and a 4×3 pattern grid.
- **Picture-in-Picture** — float a compact control bar (stop / slower / faster, and deeper / shallow where the phone allows) over other apps.
- In-app self-update from GitHub Releases.

See [android-v2/README.md](android-v2/README.md) and [android-v2/docs/](android-v2/docs/) for architecture, the voice engine, the command vocabulary, training, and troubleshooting.

### Build

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
cd android-v2
.\gradlew.bat :app:assembleDebug      # build
.\gradlew.bat :app:installDebug       # install to attached device
.\gradlew.bat :app:testDebugUnitTest  # JVM unit tests (~30 ms)
```

Every debug build drops a version-named APK into [`apks/`](apks/) for sideloading.

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
│   └── docs/               # ARCHITECTURE, VOICE_ENGINE, COMMANDS, TRAINING, TROUBLESHOOTING
├── apks/                   # Version-named debug APK installers
├── docs/                   # Shared reference (Handy API notes, integration guides)
│   ├── HANDY_API_NOTES.md
│   ├── DEEPGRAM_GUIDE.md
│   ├── ALEXA_GUIDE.md
│   ├── HOME_ASSISTANT_GUIDE.md
│   └── CONSTELLATION_INTEGRATION_GUIDE.md
├── legacy/                 # Archived apps (see legacy/README.md)
├── CLAUDE.md               # AI assistant reference (auto-loaded by Claude Code)
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
- **Emergency stop** — always one tap away (including in the Picture-in-Picture bar).
- **Visual + audible feedback** — every action is confirmed on screen and with an ack tone.

## License

MIT

---

**Note**: Independent accessibility project, not affiliated with Ohdoki/The Handy.
