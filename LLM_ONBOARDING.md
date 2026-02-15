# LLM Onboarding

Quick reference for AI assistants working on this repository. **Read this entire file before making changes.**

---

## About the Owner

I'm Owen — a wheelchair user with muscular dystrophy.

- **Typing is hard** — Be proactive. Make decisions. Don't ask for confirmation on small things.
- **Offer A/B/C choices** — I can type one letter instead of explaining.
- **PowerShell on Windows** — Use PowerShell syntax. Prefer single-line commands.
- **Accessibility matters** — This project is a tool I actually need.
- **Non-standard voice** — Browser Web Speech API and Android SpeechRecognizer both fail badly with my voice. We solved this with **Vosk offline + constrained grammar** on Android and **Deepgram** on web.

---

## Project Overview

**Name:** Handy Voice Control  
**Purpose:** Accessible control apps for The Handy device, designed for users with disabilities.  
**Status:** Active Development

### Apps

| App | Status | Description |
|-----|--------|-------------|
| `v2-web/` | ✅ Working | Handy V2 web app — preset grid, sliders, debug panel |
| `android/` | ✅ Working | Android app — Vosk voice control, preset grid, debug panel |
| `v1-web/` | 🗄️ Legacy | Handy V1 web app with Deepgram voice (reference only) |

---

## Key Files

### Web App (`v2-web/`)

| File | Purpose |
|------|---------|
| `index.html` | V2 web app interface — preset grid, sliders, debug panel |
| `style.css` | Dark theme, accessible styling |
| `app.js` | App logic — presets, sliders, global limits, speed multiplier |
| `handy-v2-api.js` | Handy REST API v2 client (single `HandyV2API` class) |

### Android App (`android/app/src/main/java/com/handyvoice/app/`)

| File | Purpose |
|------|---------|
| `ControlActivity.kt` | Main control screen — sliders, presets, mic, debug panel, voice command execution |
| `SettingsActivity.kt` | Settings — API keys, voice engine selection (Vosk/Android STT/Deepgram) |
| `MainActivity.kt` | Legacy main activity (voice commands handled here too, keep `when` exhaustive) |
| `api/HandyV2ApiClient.kt` | Handy V2 REST API client (Retrofit) — connect, start/stop, speed, stroke |
| `api/HandyApiClient.kt` | Legacy V1 API client |
| `api/HandyApiService.kt` | Legacy V1 Retrofit interface |
| `voice/VoskVoiceEngine.kt` | **Primary voice engine** — Vosk offline with constrained JSGF grammar |
| `voice/VoiceCommand.kt` | Sealed class + parser — fuzzy matching with Levenshtein distance |
| `voice/VoiceEngine.kt` | Common interface for all voice engines |
| `voice/DeepgramVoiceEngine.kt` | Deepgram WebSocket engine (requires API key) |
| `voice/VoiceRecognitionService.kt` | Android STT engine (poor accuracy for Owen) |
| `data/PreferencesManager.kt` | DataStore preferences — keys, voice engine selection |

### Layout Files (`android/app/src/main/res/layout/`)

| File | Purpose |
|------|---------|
| `activity_control.xml` | Control screen — 4×3 orange preset grid, sliders, mic, debug panel |
| `activity_settings.xml` | Settings — 3 radio buttons (Vosk/Android STT/Deepgram), API key inputs |

### Documentation (`docs/`)

| File | Purpose |
|------|---------|
| `HANDY_API_NOTES.md` | API findings, endpoint docs, gotchas |
| `DEEPGRAM_GUIDE.md` | Deepgram setup guide for web app |

---

## Tech Stack

| Component | Technology |
|-----------|------------|
| Web | Vanilla HTML/CSS/JS (ES6+) |
| Android | Kotlin, Android SDK, Material Design |
| Voice (Android) | **Vosk** offline (default), Deepgram WebSocket, Android STT |
| Voice (Web) | Deepgram AI (`nova-2` model) |
| HTTP (Android) | Retrofit + OkHttp + Gson |
| Preferences (Android) | Jetpack DataStore |
| Device API | `https://www.handyfeeling.com/api/handy/v2` |

---

## Voice Control Architecture (Android)

### Three Voice Engines

1. **Vosk Offline (default, recommended)** — `VoskVoiceEngine.kt`
   - Constrained JSGF grammar: can ONLY output ~100 predefined phrases
   - Eliminates false positives completely (no "surrounded the top" when you say "slow top")
   - ~40MB model downloaded on first launch, cached in app files
   - Fully offline after download
   - **Best for Owen's voice** — constrained vocabulary compensates for recognition difficulty

2. **Deepgram WebSocket** — `DeepgramVoiceEngine.kt`
   - Cloud-based, requires API key
   - Best raw accuracy, but unconstrained vocabulary
   - Has keyword boosting for command words

3. **Android SpeechRecognizer** — `VoiceRecognitionService.kt`
   - Built-in, free, but unconstrained and poor accuracy for Owen

### Voice Engine Selection
- Default: `"vosk"` (in `PreferencesManager.kt`)
- Changed in Settings → radio buttons → Save Voice Settings
- `ControlActivity.initVoiceEngine()` reads pref and creates the right engine

### Vosk Key Design Decisions

**Muted/unmuted pattern (not create/destroy):**
- `SpeechService` is created ONCE during `initialize()` and runs continuously
- Pressing MIC toggles a `muted` flag — when muted, callbacks are ignored
- This avoids Android AudioRecord warmup issues that caused the first 2-3 mic presses to fail
- `stopListening()` = set `muted = true` (SpeechService keeps running)
- `startListening()` = set `muted = false` (instant, no AudioRecord recreation)

**Partial result timeout (1.2s):**
- Vosk doesn't finalize when you repeat the same word ("faster... faster... faster")
- It keeps it as a partial because the recognizer sees one continuous utterance
- Fix: after 1.2s of a stable partial, auto-promote it to a final result
- This allows sequential "faster" / "slower" commands

**Duplicate detection with timestamp suffix:**
- Kotlin `StateFlow` doesn't emit when the new value equals the old value
- If you say "faster" twice, the finalResult would be "faster" both times → second one ignored
- Fix: `emitFinal()` appends `" " + System.currentTimeMillis()` to force uniqueness
- `ControlActivity` strips this suffix with regex before parsing: `rawTranscript.replace(Regex("\\s+\\d{13,}$"), "")`

### Voice Command Parser (`VoiceCommand.kt`)

Uses **word-based fuzzy matching** with Levenshtein distance:
- Each word in the transcript is compared to known command words
- Threshold: 40% edit distance ratio
- Checks in priority order: presets → two-word commands → single-word → speed numbers → global min/max
- Best match (lowest combined score) wins

### Vosk Grammar Phrases

All phrases Vosk can output (anything else → `[unk]`):
- **Presets:** `slow/medium/med/fast` × `top/mid/middle/bot/bottom/full` (24 combos)
- **Basic:** `start`, `stop`, `go`, `begin`, `play`, `halt`, `pause`, `end`
- **Speed:** `faster`, `slower`, `speed up`, `slow down`, `quicker`
- **Speed set:** `speed ten/twenty/.../hundred`
- **Global limits:** `maximum/minimum/max/min` × `ten/twenty/.../hundred`

**To add a new voice command:**
1. Add the phrase(s) to `GRAMMAR` list in `VoskVoiceEngine.kt`
2. Add the `VoiceCommand` subclass in `VoiceCommand.kt`
3. Add parsing logic in `VoiceCommandParser.parse()`
4. Add handling in `ControlActivity.executeCommand()`
5. Add branch in `MainActivity.handleVoiceCommand()` (keep `when` exhaustive!)

---

## Handy V2 API

### Authentication
- Only requires `X-Connection-Key` header (no API key needed for V2)
- Connection key from user's account at handyfeeling.com

### Key Endpoints

```
PUT /mode              → {"mode": 0}          # Set HAMP mode (do once on connect)
PUT /hamp/start        → (no body)            # Start motion
PUT /hamp/stop         → (no body)            # Stop motion
PUT /hamp/velocity     → {"velocity": 0-100}  # Set speed
GET /hamp/velocity     → {"velocity": N}      # Get current speed
PUT /slide             → {"min": 0-100, "max": 0-100}  # Set stroke range
GET /slide             → {"min": N, "max": N}           # Get current range
GET /connected         → {"connected": true/false}
```

### ⚠️ CRITICAL: Operation Order

The web app (which works) uses this exact order for presets:
1. `PUT /slide` — set stroke range
2. `PUT /hamp/start` — start motion
3. `PUT /hamp/velocity` — set speed **AFTER** starting

**Speed must be set AFTER start.** Setting speed before start causes it to reset.

### ⚠️ CRITICAL: Stroke Range Endpoint

- **CORRECT:** `PUT /slide` with `{"min": N, "max": N}` (single request)
- **WRONG:** `PUT /slide/min` + `PUT /slide/max` (separate requests → 404)
- The Android V2 client originally used separate endpoints and got 404s. Fixed to use single `PUT /slide`.
- Android model: `SlideRangeRequest(min: Int, max: Int)` (renamed from `SlideRequest` to avoid collision with V1's `SlideRequest` in `HandyApiService.kt`)

### Preset Values

| Speed | Value | Position | Min | Max |
|-------|-------|----------|-----|-----|
| slow | 25 | top | 50 | 90 |
| medium | 50 | middle/mid | 30 | 70 |
| fast | 80 | bottom/bot | 10 | 50 |
| | | full | 0 | 100 |

Presets are scaled by global limits: `scaledMin = globalMin + (posMin / 100) * globalRange`

---

## UI Conventions (Android)

### Colors
- **Orange** (`@color/slider_orange`) — preset buttons (stroke/border and text)
- **Green** (`@color/status_connected`) — start button, success feedback, connected status
- **Red** (`@color/emergency_red`) — stop button, error feedback
- **Red** (`@color/slider_red`) — global limit sliders (G.Min, G.Max)

### Preset Grid
- 4×3 `GridLayout` — rows: Top, Mid, Bot, Full × columns: Slow, Med, Fast
- All buttons: `MaterialButton` with `OutlinedButton` style, orange stroke/text
- Button IDs: `presetSlowTop`, `presetMedTop`, `presetFastTop`, etc.

### Sliders
- All sliders go 0-100 in XML — constraints enforced in code only
- Global Min: `coerceIn(0, globalMax - 10)`
- Global Max: `coerceIn(globalMin + 10, 100)`
- When global limits change, stroke sliders' min/max are updated to match (same as web app)

### Debug Panel
- Toggle button: "Show Debug" / "Hide Debug"
- Scrollable monospace log with timestamps: `[HH:mm:ss] TAG: message`
- **Copy** button (orange) — copies entire log to clipboard for easy sharing
- **Clear** button — resets log
- Logs: engine init, voice state, partials, finals, parsed commands, API calls, API errors
- API client internal log is piped to debug panel via `api.debugLog` StateFlow

---

## Lessons Learned / Gotchas

### Voice Recognition
1. **Android SpeechRecognizer** recognizes the entire English dictionary — terrible for constrained-domain commands. "Slow top" becomes "surrounded the top."
2. **Vosk with grammar constraints** solves this perfectly — it can ONLY output phrases you define.
3. **Don't destroy/recreate AudioRecord** on each mic toggle — Android has warmup issues. Use muted/unmuted pattern instead.
4. **Vosk doesn't finalize repeated words** — same word said twice stays as partial. Use a timeout to auto-promote.
5. **StateFlow ignores duplicate values** — append a timestamp to force emission of repeated commands.

### API
1. **V2 stroke range** uses single `PUT /slide` with `{min, max}`, NOT separate `/slide/min` and `/slide/max` (those 404).
2. **Set speed AFTER start** — the web app explicitly does this and it matters.
3. **Set HAMP mode once** on connect, not on every action.
4. **Name collisions** — `SlideRequest` exists in both V1 (`HandyApiService.kt`, `position: Double`) and V2 clients. V2 uses `SlideRangeRequest` to avoid the collision.

### Android
1. **`when` expressions on sealed classes must be exhaustive** — when you add a new `VoiceCommand` subclass, update ALL `when` blocks in both `ControlActivity.kt` and `MainActivity.kt` or you get a compile error.
2. **`onResume` reinitializes the voice engine** — this is intentional so settings changes take effect, but be aware it destroys and recreates the engine.

---

## Git Commits

Use conventional commits:
```
feat: add voice command for speed control
fix: correct API authentication flow
docs: update voice commands reference
refactor: simplify voice recognition logic
chore: update dependencies
```

PowerShell:
```powershell
git add -A; git commit -m "feat: description"; git push
```

---

## Development Workflow

### Web App
```powershell
python -m http.server 8000   # Serve v2-web/ locally
```

### Android App
- Open `android/` in Android Studio
- Build: `./gradlew assembleDebug` from `android/` directory
- Key dependency: `com.alphacephei:vosk-android:0.3.47`
- Vosk model (~40MB) auto-downloads on first run to app's internal storage

### Browser Requirements (Web)
- Chrome 25+ (best voice support)
- Edge 79+ (Chromium-based)

---

## Security

- **Never hardcode API keys** — stored in DataStore (Android) or localStorage (web)
- **Deepgram API key** — only needed if using Deepgram engine (not default)
- **Connection key** — required for all Handy API operations

---

## Constellation

This repo is tracked by [Constellation](https://github.com/owenpkent/constellation).

For the dashboard to pick up this project:
1. Have a `README.md` with a `## Status` section
2. Have a `TODO.md` with checkbox items (`- [ ]` / `- [x]`)
3. Add the repo path to Constellation's `projects.yaml`
