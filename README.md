# Handy Voice Control

Accessible control apps for The Handy device, designed for users with disabilities.

## Status

**Active Development** — V2 Web App functional, Android in progress.

## Apps

| App | Device | Status | Description |
|-----|--------|--------|-------------|
| **v2-web** | Handy V2 | Working | Web app with preset grid, safety limits, debug panel |
| **v1-web** | Handy V1 | Legacy | Original web app with voice control (Deepgram) |
| **android** | Handy V1 | In Progress | Android app with voice commands |

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

### Voice Commands (V1/Android)
- Start, Stop, Faster, Slower
- "Set speed to 50"
- "Set stroke to 75"

## Project Structure

```
handy-v1/
├── v2-web/                 # Handy V2 web app (ACTIVE)
│   ├── index.html
│   ├── style.css
│   ├── app.js
│   └── handy-v2-api.js
├── v1-web/                 # Handy V1 web app (legacy)
│   ├── index.html
│   ├── css/
│   └── js/
├── android/                # Android app (in progress)
├── docs/                   # Documentation
│   ├── HANDY_API_NOTES.md  # API findings (HAMP, HSSP, HBSP)
│   ├── SETUP_GUIDE.md
│   ├── DEEPGRAM_GUIDE.md
│   └── CONSTELLATION_INTEGRATION_GUIDE.md
├── README.md               # This file
├── TODO.md                 # Task tracking
└── LLM_ONBOARDING.md       # AI assistant reference
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