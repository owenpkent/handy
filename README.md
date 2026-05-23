# Handy Voice Control

Accessible control apps for The Handy device, designed for users with disabilities.

## Status

**Active Development** — V2 Web App functional, Android in progress.

## Apps

| App | Device | Status | Description |
|-----|--------|--------|-------------|
| **v2-web** | Handy V2 | Working | Web app with preset grid, safety limits, debug panel |
| **v1-web** | Handy V1 | Legacy | Original web app with voice control (Deepgram) |
| **android** | Handy V2 | Working | Android app — Vosk offline voice + preset grid; see `android/README.md` |
| **android-v2** | Handy V2 | Scaffold | New Android app: **prerecorded voice samples** (MFCC + DTW) for non-standard speech. Inspired by `../vega`. See [android-v2/README.md](android-v2/README.md) and [android-v2/docs/](android-v2/docs/) |
| **alexa** | Handy V2 | Shelved | Custom skill blocked by Amazon content filter; see [ALEXA_GUIDE.md](docs/ALEXA_GUIDE.md) |
| **homeassistant** | Handy V2 | Planned | Always-on hands-free voice via local HA voice pipeline; see [HOME_ASSISTANT_GUIDE.md](docs/HOME_ASSISTANT_GUIDE.md) |

## Quick Start (V2 Web App)

```powershell
cd v2-web
python -m http.server 8080
# Open http://localhost:8080
```

**Requirements:**
- Handy V2 with Firmware v4+
- Connection Key from [handyfeeling.com](https://user.handyfeeling.com)
- Modern browser (Chrome/Edge)

## Features

### V2 Web App
- **Preset Grid** — 15 speed/position presets (Slow/Med/Fast × Top/Middle/Bottom/Full/Natural)
- **Stroke Sliders** — Adjust min/max stroke position
- **Speed Multiplier** — Scale all speeds (10-100%), applies immediately
- **Safety Limit** — Global max position, applies immediately on change
- **Debug Panel** — Live API call logging
- **High Contrast UI** — Accessible dark theme

### Voice Commands (Android v1, Vosk)
- Start, Stop, Faster, Slower
- "Set speed to 50"
- "Set stroke to 75"
- Preset grid: "slow top", "fast bottom", etc.

### Voice Commands (Android v2, prerecorded samples)
- Same nine labels as `vega`: start / stop / faster / slower / heartbeat / wave / surge / breathe / storm
- Records 3 takes of each on first launch; matches against the user's own voice (no ASR)
- Mic-button mode (default) or always-on with VAD
- See [android-v2/docs/COMMANDS.md](android-v2/docs/COMMANDS.md)

## Project Structure

```
handy-v1/
├── v2-web/                  # Handy V2 web app (ACTIVE)
│   ├── index.html
│   ├── style.css
│   ├── app.js
│   └── handy-v2-api.js
├── v1-web/                  # Handy V1 web app (legacy)
│   ├── index.html
│   ├── css/
│   └── js/
├── android/                 # Android app — Vosk offline ASR (active)
├── android-v2/              # Android app — prerecorded voice samples (scaffold)
│   ├── README.md
│   └── docs/
│       ├── ARCHITECTURE.md
│       ├── VOICE_ENGINE.md
│       ├── COMMANDS.md
│       ├── TRAINING.md
│       └── TROUBLESHOOTING.md
├── alexa/                   # Lambda for Alexa skill (shelved)
├── homeassistant/           # HA custom component
├── docs/                    # Documentation
│   ├── HANDY_API_NOTES.md   # API findings (HAMP, HSSP, HBSP, HDSP park)
│   ├── SETUP_GUIDE.md
│   ├── DEEPGRAM_GUIDE.md
│   ├── ALEXA_GUIDE.md
│   ├── HOME_ASSISTANT_GUIDE.md
│   ├── CONSTELLATION_INTEGRATION_GUIDE.md
│   └── screenshots/         # UI screenshots
├── CLAUDE.md                # AI assistant reference (auto-loaded by Claude Code)
├── README.md                # This file
└── TODO.md                  # Task tracking
```

## Safety

- **Global max stroke position** — Device never exceeds this limit
- **Emergency stop** — Spacebar or Escape key
- **Visual feedback** — All actions confirmed on screen

## API Notes

| Device | API | Base URL |
|--------|-----|----------|
| V2 | REST v2 | `https://www.handyfeeling.com/api/handy/v2` |
| V1 | REST v3 | `https://www.handyfeeling.com/api/handy-rest/v3` |

## License

MIT

---

**Note**: Independent accessibility project, not affiliated with Ohdoki/The Handy.