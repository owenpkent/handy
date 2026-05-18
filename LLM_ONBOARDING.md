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
| `android-v2/` | 🟢 Scaffold | New Android app using **prerecorded voice samples** (MFCC + DTW). Inspired by `../vega`. See `android-v2/README.md`. |
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

### Android App V2 (`android-v2/app/src/main/java/com/handyvoice/v2/`)

New Android client that recognizes voice commands by matching against **prerecorded samples of the user's own voice**. Speaker-locked template matching with MFCC + DTW. Inspired by `../vega`. Co-exists with `android/`; both apps can be installed at once. See `android-v2/README.md` and `android-v2/docs/`.

| File | Purpose |
|------|---------|
| `HandyVoiceApp.kt` | `Application` subclass. Placeholder for future DI. |
| `ui/ControlActivity.kt` | Main screen — connection panel, mic button or always-on indicator, manual fallback row, debug log. Routes to TrainingActivity when no templates exist. |
| `ui/TrainingActivity.kt` | First-run onboarding — Euphonia-style card carousel, 9 commands × 3 takes, MFCCs saved after each take so a crash doesn't lose progress. |
| `ui/SettingsActivity.kt` | Settings — connection key, listening mode (button vs always-on), global max speed, match threshold, re-record button. |
| `api/HandyV2ApiClient.kt` | Handy V2 REST API client (Retrofit). Self-contained models (no V1 import). Single `PUT /slide` for stroke range. |
| `audio/AudioCapture.kt` | Thin AudioRecord wrapper. `readWindow(seconds)` for fixed captures, `stream(...)` for the always-on loop. Source = `MediaRecorder.AudioSource.VOICE_RECOGNITION`. |
| `audio/Vad.kt` | Energy + zero-crossing-rate voice activity detector. Used only by always-on mode. |
| `voice/Mfcc.kt` | **Hand-rolled MFCC** — pre-emphasis, Hamming window, radix-2 FFT (512-pt), mel filterbank, log, DCT. 13 coeffs, 25 ms / 10 ms framing. Allocation-free per call. |
| `voice/Dtw.kt` | **Sakoe-Chiba banded Dynamic Time Warping**. Path-length normalized so longer templates don't lose unfairly. |
| `voice/TemplateEngine.kt` | Interface: `addTake / match / labels / clearAll`. Future TFLite engine slots in here. |
| `voice/MfccDtwEngine.kt` | Default `TemplateEngine` impl. In-memory map of label → list of MFCC takes. `match()` returns `(label, score, runnerUp)`. |
| `voice/TemplateStore.kt` | JSON persistence under `filesDir/templates/<label>/take_N.json`. Format-versioned for forward-compat. |
| `voice/TfliteEmbeddingEngine.kt` | Phase 3 stub — Resnet50 embedding + cosine similarity à la EfficientWord-Net. |
| `dispatch/VoiceAction.kt` | Sealed class: `Stop / SetSpeed / AdjustSpeed / SetRange / Preset`. |
| `dispatch/Dispatch.kt` | `Map<label, VoiceAction>` + `dispatch(label, api, globalMaxSpeed)` suspending function. Mirrors `vega/commands.py`'s DISPATCH. |
| `data/PreferencesManager.kt` | DataStore prefs — connection key, listening mode, global max speed, match threshold, onboarded flag. |

### Layout Files (`android/app/src/main/res/layout/`)

| File | Purpose |
|------|---------|
| `activity_control.xml` | Control screen — 4×3 orange preset grid, sliders, mic, debug panel |
| `activity_settings.xml` | Settings — 3 radio buttons (Vosk/Android STT/Deepgram), API key inputs |

### Layout Files (`android-v2/app/src/main/res/layout/`)

| File | Purpose |
|------|---------|
| `activity_training.xml` | Euphonia-style card per command. Big record button (Record blue → Stop teal → Re-record light-blue), take dots, play button, prev/next/skip, finish CTA. |
| `activity_control.xml` | Setup panel ↔ control panel split. Voice card with mic button + last-match readout. Manual fallback row (start/stop/slower/faster). Debug panel with copy/clear. |
| `activity_settings.xml` | Connection key input, listening-mode radio group, global-max-speed slider, threshold slider, re-record button. |

### Documentation (`docs/`)

| File | Purpose |
|------|---------|
| `HANDY_API_NOTES.md` | API findings, endpoint docs, gotchas |
| `DEEPGRAM_GUIDE.md` | Deepgram setup guide for web app |
| `ALEXA_GUIDE.md` | Alexa skill setup (shelved — Amazon content filter) |
| `HOME_ASSISTANT_GUIDE.md` | HA integration plan |
| `CONSTELLATION_INTEGRATION_GUIDE.md` | Dashboard integration |

### Documentation (`android-v2/docs/`)

| File | Purpose |
|------|---------|
| `ARCHITECTURE.md` | Process model, threading, state, lifecycle, always-on state machine, things deliberately missing. |
| `VOICE_ENGINE.md` | MFCC pipeline parameter-by-parameter, FFT details, DTW + banded path + normalization, threshold tuning loop, storage format, swappable-engine contract. |
| `COMMANDS.md` | Vocabulary table, dispatch table, VoiceAction semantics, how to add a new command end-to-end, pattern stand-ins + Phase 2 port. |
| `TRAINING.md` | User-facing recording guide. Recording habits, peak meter, retraining paths, reading match scores. |
| `TROUBLESHOOTING.md` | Build issues (JAVA_HOME, SDK path, wrapper jar), runtime (mic permission, no match, false fires, threshold), dispatch (global max cap surprises). |

---

## Tech Stack

| Component | Technology |
|-----------|------------|
| Web | Vanilla HTML/CSS/JS (ES6+) |
| Android (v1, `android/`) | Kotlin, Android SDK, Material Design |
| Android (v2, `android-v2/`) | Kotlin, Android SDK, Material 3 |
| Voice (Android v1) | **Vosk** offline (default), Deepgram WebSocket, Android STT |
| Voice (Android v2) | **MFCC + DTW** on user's own recordings (hand-rolled in Kotlin, no ML deps). TFLite embedding engine reserved for Phase 3. |
| Voice (Web) | Deepgram AI (`nova-2` model) |
| HTTP (Android) | Retrofit 3 + OkHttp 4 + Gson |
| Preferences (Android) | Jetpack DataStore |
| Device API | `https://www.handyfeeling.com/api/handy/v2` |
| Gradle / Kotlin | Gradle 9.2.1, Kotlin 2.2.10, AGP 9.0.1, JDK 17+ |
| Min / Target SDK | minSdk 26 (Android 8.0), targetSdk 35 |

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

## Voice Control Architecture (Android V2)

Completely different approach from `android/`. No ASR, no grammar parsing, no transcripts. The matcher compares incoming audio against the user's own pre-recorded samples and dispatches the best match (if it beats the threshold).

### Why a second app

The v1 app uses Vosk ASR with a constrained grammar. That works, but each utterance still has to be:

1. converted to English-ish words by an acoustic model trained on a broad population, then
2. parsed against the dispatch table.

For users with non-standard speech, step (1) is the brittle part. V2 cuts both steps. It records 3 takes per command in the user's voice and asks "does this audio look like the samples Owen recorded for `faster`?" Nothing has to map to dictionary words.

### Pipeline (one-line per box)

```
mic ─► AudioCapture (16 kHz mono int16)
       │
       ├─► [button mode]   tap mic ─► 1.5 s capture
       └─► [always-on]     VAD opens 1.5 s windows out of continuous stream
       │
       ▼
       Mfcc (13 coeffs, 25 ms frame, 10 ms hop, radix-2 FFT, mel filterbank, DCT)
       │
       ▼
       MfccDtwEngine.match() ── Sakoe-Chiba banded DTW vs every stored take ──► (label, score, runnerUp)
       │
       ▼
       Dispatch.dispatch(label) ─► HandyV2ApiClient ─► REST v2
```

### Phase 1 vocabulary

Mirrors `vega/commands.py`'s DISPATCH exactly. Nine labels:

| Label | Action |
|-------|--------|
| `start` | `SetSpeed(25)` — ensure running, set speed |
| `stop` | `Stop` — immediate stop |
| `faster` | `AdjustSpeed(+15)` |
| `slower` | `AdjustSpeed(-15)` |
| `heartbeat` | `Preset(50, 90, 25)` — slow, top range (pattern stand-in) |
| `wave` | `Preset(0, 100, 50)` — medium, full (pattern stand-in) |
| `surge` | `Preset(0, 100, 80)` — fast, full (pattern stand-in) |
| `breathe` | `Preset(0, 100, 25)` — slow, full (pattern stand-in) |
| `storm` | `Preset(50, 90, 80)` — fast, top range (pattern stand-in) |

The five pattern labels currently dispatch as static preset combos. The looping pattern engine (heartbeat envelope, etc.) from `vega/devices/handy.py` is a Phase 2 port; training vocabulary does not change when patterns land.

### Listening modes

Set in `Settings → Listening mode`:

- **Tap mic** (default) — single 1.5 s capture per button press. Most predictable; never false-fires.
- **Always listening** — VAD opens 1.5 s windows when speech is detected. 1.5 s cooldown after each dispatch prevents same-utterance double-fire.

Default is button. Owen flips to always-on once templates are proven reliable.

### Onboarding flow

First launch detects no templates on disk → routes to `TrainingActivity`. The user records 3 takes of each of the 9 commands. After each take, MFCCs are extracted and saved to `filesDir/templates/<label>/take_N.json`. After all takes (minimum 1 per label), **Build templates** finalizes onboarding and routes back to `ControlActivity`.

Re-train any time from `Settings → Re-record voice samples` — wipes the templates directory and relaunches Training.

### Key design decisions

**Speaker-locked by construction.** Templates are the user's own voice. Other speakers tend to score below threshold; the matcher is effectively a soft access control (not a security primitive).

**Hand-rolled MFCC.** No external audio libraries (TarsosDSP, etc). Pre-emphasis 0.97 → Hamming → radix-2 FFT (512-pt) → 26 mel filters → log → DCT (13 coeffs). About 200 lines of Kotlin, allocation-free per call, runs in ~3 ms on a Pixel-class CPU. Trade-off: we own the math, but it's verified by JVM unit tests and stays inside one file. See `android-v2/docs/VOICE_ENGINE.md`.

**Sakoe-Chiba banded DTW with path-length normalization.** Band ratio 0.15 (path can wander up to 15% of the longer sequence off-diagonal). Normalize by `n + m` so longer templates don't lose unfairly. Distance per cell is Euclidean over 13 MFCC coefficients.

**Per-label keep-all-takes.** Three takes per label → keep all three MFCC sequences; match against the best one. More robust than averaging templates (averaging blurs the per-take detail that helps reject lookalikes).

**Save MFCCs after every take.** A crash mid-onboarding loses at most the active take, not progress.

**Energy + ZCR VAD for always-on.** Energy alone trips on rumble; ZCR alone trips on hiss. Both together approximates speech reasonably well in ~50 lines. Requires ~120 ms of voiced audio (4 × 30 ms blocks) before triggering a match.

**Global max speed cap is enforced in `Dispatch.dispatch()`**, not in the API client. The client accepts 0–100; dispatch scales `design × globalMaxSpeed / 100` before passing through. This means: voice path is capped, future direct callers must apply the cap themselves.

### Adding a new voice command (Android V2)

Three steps (see `android-v2/docs/COMMANDS.md` for the full guide):

1. Add the label to `Dispatch.LABELS` and `Dispatch.PROMPTS` in `dispatch/Dispatch.kt`.
2. Add the row to `Dispatch.TABLE` mapping `label -> VoiceAction.<shape>`.
3. Re-train: open the app, Settings → Re-record voice samples. (Or ADB-push a take into the right folder.)

No grammar update, no parser update, no exhaustive-`when` cascade.

### Storage layout (Android V2)

```
applicationContext.filesDir/
└── templates/
    ├── start/
    │   ├── take_1.json    # {"format": 1, "label": "start", "mfcc": [[...13 doubles...], ...]}
    │   ├── take_2.json
    │   └── take_3.json
    ├── stop/
    │   └── ...
    └── ...
```

`format: 1` is the current schema. The TFLite engine path (Phase 3) bumps `format: 2` so MFCC takes aren't silently confused with embeddings.

### Tests (Android V2)

14 JVM unit tests cover the audio math without needing Android:

- `MfccTest` (5) — extraction shape, determinism, frequency discrimination, empty/short input handling.
- `DtwTest` (5) — self-distance zero, symmetry, similar beats dissimilar, length invariance after normalization, empty handling.
- `MfccDtwEngineTest` (4) — no-templates returns null, trained label wins, two-takes-per-label preserves match, clearAll works.

Run: `./gradlew :app:testDebugUnitTest` from `android-v2/`. ~30 ms total runtime.

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

### Android V2
1. **Save MFCCs after every take, not at the end of onboarding.** A crash should lose the active take, not 30 minutes of work.
2. **`MediaRecorder.AudioSource.VOICE_RECOGNITION` is the right source** — applies the OS speech-tuning DSP chain. Mic and Camcorder sources sound worse on most phones.
3. **`AudioTrack.Builder` (not the deprecated constructor)** — `AudioTrack(STREAM_MUSIC, ...)` is deprecated since API 26 (our minSdk). Use the builder with `AudioAttributes.USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`.
4. **`MfccDtwEngine.match()` runs on `Dispatchers.Default`**, capture on `Dispatchers.IO`. The matcher is CPU-bound and short (~1 ms); IO blocks shouldn't compete for the same threads as filesystem writes.
5. **Always-on capture loop is gated by a `Job.isActive` check**, not a separate flag. Cancelling the job lets the unbounded loop drain and `AudioRecord.release()` run.
6. **Template format is versioned in JSON.** Even though Phase 1 only has `format: 1`, the field exists so future migrations are mechanical. Reads silently skip mismatched takes rather than crash.
7. **`TemplateStore.sanitize()` lowercases and replaces non-alphanumerics.** Dispatch labels are already clean, but this is defence-in-depth — a misconfigured label can never break the filesystem.
8. **The gradle wrapper jar in this repo's `android/gradle/wrapper/` is the wrong file** (it's the launcher jar). `android-v2/` has a correct wrapper regenerated from gradle 9.2.1. If you copy the v1 wrapper, `gradlew.bat` will fail with `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`.

### Engine math (Android V2 specific)

1. **MFCC frame size = 400 samples (25 ms), hop = 160 samples (10 ms)**. Standard speech-feature framing. A 1.5 s capture yields ~98 frames of 13 coefficients.
2. **FFT size = 512** (next power of two ≥ 400). Frame is zero-padded. Don't change this without re-running the unit tests.
3. **Mel filterbank uses 26 triangular filters**, 20 Hz → 8 kHz (Nyquist). Build matrix is precomputed in the `Mfcc` constructor; the inner loop is one dot product per filter per frame.
4. **DCT-II with orthogonal normalization** (`sqrt(1/N)` for c₀, `sqrt(2/N)` otherwise). Same as scipy / librosa. Precomputed 13×26 matrix.
5. **DTW band ratio 0.15** with `max(bandWidth, |n - m| + 1)` clamp. Without the clamp, slightly-different-length templates can be rejected by the band.
6. **DTW distance normalization is by `n + m`** (upper bound on path length). Cheap and "close enough" for ranking; the actual-path-length variant requires backtracking and isn't worth it.
7. **Match output carries `runnerUp` distance.** Phase 1 dispatch only checks `score <= threshold`; a future "require margin > N" check would reject low-confidence matches. Worth turning on if two trained labels keep colliding.

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

### Android App (`android/`)
- Open `android/` in Android Studio
- Build: `./gradlew assembleDebug` from `android/` directory
- Key dependency: `com.alphacephei:vosk-android:0.3.47`
- Vosk model (~40MB) auto-downloads on first run to app's internal storage

### Android App V2 (`android-v2/`)
- Open `android-v2/` in Android Studio (lets it sync, generates wrapper).
- Or from the command line:
  ```powershell
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
  cd C:\Users\Owen\dev\handy-v1\android-v2
  .\gradlew.bat :app:assembleDebug
  ```
- Tests (JVM, no emulator): `.\gradlew.bat :app:testDebugUnitTest` — 14 tests, ~30 ms.
- Install to attached device: `.\gradlew.bat :app:installDebug`.
- First launch on device routes straight to TrainingActivity. Record 3 takes per command, tap **Build templates**, then enter connection key.
- No models to download. Templates are user-recorded; nothing leaves the device.
- Reset everything: `adb shell pm clear com.handyvoice.v2` wipes prefs + templates.

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
