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
| `ui/ControlActivity.kt` | Single-screen home for both control modes. **Material `TabLayout`** at the top switches between **Voice** and **Manual** panels; the big START / STOP row shows on the **Manual** tab only (the Voice tab has its own Begin / Stop shortcut buttons, so the pair would be redundant there). The header gear (`settingsButton`) opens `SettingsActivity` directly. Voice panel: mic toggle, live peak meter, "Heard:" readout, and tap-shortcut buttons for every voice label (Begin/Stop, Slower/Faster, Thunder/Ripple/Pulse) routed through `Dispatch.dispatch` for parity with the spoken path. Auto-scrolling debug log below. Recording management (Re-record / Clear all) lives in Settings, not on the Voice tab. Manual panel: stroke Min/Max sliders bounded to the global band, speed slider bounded to `[0, globalMaxSpeed]`, 4×3 pattern grid (3 buttons share voice labels via `Dispatch.dispatch`, 9 are tap-only), feedback line. Templates reload in `onResume` so new recordings take effect without restart. |
| `ui/TrainingActivity.kt` | First-run onboarding and re-recording. Single Record tap runs an auto-advance session through every (label, take) needing audio, with a pre-roll → "Listening, speak now" UI and a brief breathing pause between takes. Cross-validation flags outlier takes per label and offers an inline re-record. Build templates sets onboarded=true and returns to Control. Skip / Prev / Next stay enabled between captures so the user can navigate without canceling. Ack tone on every saved take. |
| `ui/SettingsActivity.kt` | Settings — connection key, listening mode (button vs always-on), global max speed, global stroke band (min + max), invert-stroke toggle, match threshold. **Re-record voice samples** opens Training without touching saved takes (earlier this button silently wiped everything — that was a footgun). **Clear all recordings** wipes templates with a confirmation dialog and then routes to Training. Header has a **←  Home** button that returns directly to `ControlActivity`. |
| `api/HandyV2ApiClient.kt` | Handy V2 REST API client (Retrofit). Self-contained models (no V1 import). Single `PUT /slide` for stroke range. |
| `audio/AudioCapture.kt` | Thin AudioRecord wrapper. `readWindow(seconds)` for fixed captures, `stream(...)` for the always-on loop. Source = `MediaRecorder.AudioSource.VOICE_RECOGNITION`. |
| `audio/Vad.kt` | Energy + zero-crossing-rate voice activity detector. Used only by always-on mode. |
| `voice/Mfcc.kt` | **Hand-rolled MFCC** — pre-emphasis, Hamming window, radix-2 FFT (512-pt), mel filterbank, log, DCT. 13 coeffs, 25 ms / 10 ms framing. Allocation-free per call. |
| `voice/Dtw.kt` | **Sakoe-Chiba banded Dynamic Time Warping**. Path-length normalized so longer templates don't lose unfairly. |
| `voice/TemplateEngine.kt` | Interface: `addTake / match / labels / clearAll`. Future TFLite engine slots in here. |
| `voice/MfccDtwEngine.kt` | Default `TemplateEngine` impl. In-memory map of label → list of MFCC takes. `match()` returns `(label, score, runnerUp)`. |
| `voice/TemplateStore.kt` | JSON persistence under `filesDir/templates/<label>/take_N.json`. Format-versioned for forward-compat. |
| `voice/TfliteEmbeddingEngine.kt` | Phase 3 stub — Resnet50 embedding + cosine similarity à la EfficientWord-Net. |
| `dispatch/VoiceAction.kt` | Sealed class: `Stop / SetSpeed / AdjustSpeed / SetRange / Preset / Pattern`. |
| `dispatch/Dispatch.kt` | `Map<label, VoiceAction>` + `dispatch(label, api, globalMaxSpeed, ..., patternEngine)` suspending function. Mirrors `vega/commands.py`'s DISPATCH, with `Pattern` actions delegating to the engine. |
| `dispatch/PatternEngine.kt` | Time-based envelope engine. 12 named patterns; each is a `suspend fun(api, intensity)` looping `(envelope, durationMs)` pairs into `api.setSpeed`. Ported from `homeassistant/.../patterns.py`. Cancellable; only one pattern runs at a time. |
| `data/PreferencesManager.kt` | DataStore prefs — connection key, listening mode, global max speed, match threshold, onboarded flag. |

### Layout Files (`android/app/src/main/res/layout/`)

| File | Purpose |
|------|---------|
| `activity_control.xml` | Control screen — 4×3 orange preset grid, sliders, mic, debug panel |
| `activity_settings.xml` | Settings — 3 radio buttons (Vosk/Android STT/Deepgram), API key inputs |

### Layout Files (`android-v2/app/src/main/res/layout/`)

| File | Purpose |
|------|---------|
| `activity_training.xml` | Euphonia-style card per command. Big record button (Record electric-blue → Cancel indigo → Re-record sage), take dots, play button, prev/next/skip, Build templates CTA. |
| `activity_control.xml` | Setup panel ↔ control panel split. Voice card with mic toggle + last-match readout. Tap-shortcut rows below the mic: Begin/Stop, Slower/Faster, Thunder/Ripple/Pulse — one button per voice label. Debug panel auto-scrolls to newest on update. Recording management buttons (Re-record, Clear all) live in Settings, not here. |
| `activity_manual.xml` | Manual control screen. Stroke min/max sliders + speed slider, 4×3 pattern grid (gold outlined), START/STOP. |
| `activity_settings.xml` | Connection key input, listening-mode radio group, global-max-speed slider, global-stroke-band sliders (min + max), invert-stroke MaterialSwitch, threshold slider, Re-record voice samples + Clear all recordings + Save buttons. |

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

### Voice vocabulary

Seven labels, picked for acoustic distinctness in MFCC+DTW space (different syllable counts, vowel positions, consonant families) so the matcher has wide gaps between any two trained words. The original vega set had `heartbeat / wave / surge / breathe / storm` for patterns; those collapsed into a similar fricative-and-nasal corner of MFCC space and kept colliding, so the kept patterns were renamed and the rest dropped from the spoken set (envelopes are still tap-able on the Manual tab).

| Label | Action |
|-------|--------|
| `begin` | `SetSpeed(25)` — ensure running, set speed. Renamed from `start` because "start" and "stop" collide in MFCC space for slower / less-articulated speech. |
| `stop` | `Stop` — immediate stop. Also cancels any running pattern. |
| `faster` | `AdjustSpeed(+25)` — design-space step (`Dispatch.DEFAULT_STEP`), scaled by globalMaxSpeed. |
| `slower` | `AdjustSpeed(-25)` |
| `thunder` | `Pattern("thunder")` — random-walk envelope (was `storm`). |
| `ripple` | `Pattern("ripple")` — sine swell (was `wave`). |
| `pulse` | `Pattern("pulse")` — lub-dub rhythm (was `heartbeat`). |

The Voice tab carries tap-shortcut buttons for all seven so the user has a one-tap fallback when speech isn't an option (or templates aren't trained yet). Tap shortcuts call `Dispatch.dispatch(label)` directly — same code path the voice match takes, same caps applied.

Pattern envelopes live in **`PatternEngine`** (`dispatch/PatternEngine.kt`) ported from `homeassistant/custom_components/handy/patterns.py`. Each pattern is a `suspend fun(api, intensity)` that loops forever, emitting `(envelope, durationMs)` pairs to `api.setSpeed(envelope × intensity)`. Cancelling the job stops the device. The Manual tab exposes all twelve envelopes: the three voice-addressable ones (`thunder` / `ripple` / `pulse`), the two voice-dropped ones (`surge` / `breathe`) as tap-only with their old wide range, plus seven other tap-only envelopes (`tick` / `tease` / `edge` / `rush` / `throb` / `bounce` / `climax`). `tick` is the old metronome envelope that used to be named `pulse`; renamed to free the `pulse` name for the voice-addressable lub-dub. `VoiceAction.Pattern(name, intensity)` is the dispatch shape.

### Listening modes

Set in `Settings → Listening mode`:

- **Tap mic** (default) — tapping the mic button starts continuous VAD-driven listening (and tapping again stops it). Same loop as always-on, just gated by the user.
- **Always listening** — VAD-driven loop runs whenever the activity is in the foreground and connected.

**Keep-awake while listening.** Whenever the listening loop is active (either mode), `ControlActivity.keepAwake(true)` sets the window's `FLAG_KEEP_SCREEN_ON` and acquires a `PARTIAL_WAKE_LOCK` (`"HandyVoiceV2:listening"`); both are dropped in `stopAlwaysOn` / `onDestroy`. This is what stops the phone screen sleeping out from under an active listen. Teardown is **screen-aware**: `onStop` only cancels the loop when `PowerManager.isInteractive` (a real navigate-away — Home / Settings); a screen-off (power button) leaves the loop running on the wake lock so voice control keeps working with the screen dark. `onResume` re-arms always-on if it isn't already running. NB: `android:keepScreenOn` on an `<activity>` in the manifest is silently ignored — it's a `View` / window flag, not an activity attribute, so the keep-awake had to move into code. `TrainingActivity` sets the same window flag in `onCreate` so recording sessions don't idle out.

Both modes use a **post-VAD delay** (~1.8 s) before snapshotting the rolling buffer. The VAD trigger fires ~120 ms into a word, so a same-instant snapshot would mostly be the silence preceding the utterance. The delay lets the buffer slide forward until the full word is inside the 2.5 s window — matching where words sit in the training takes.

A 1.5 s cooldown after each dispatch prevents same-utterance double-fire.

### Onboarding flow

First launch (or after **Clear all**) → no templates on disk → routes to `TrainingActivity`. The user records **3 takes of each label**. Capture is a 2.5 s window with a ~450 ms "Get ready…" pre-roll so the first syllable isn't clipped by reaction time. MFCCs are saved per take so a crash mid-flow keeps partial progress. A short ack tone fires after each saved take so the user knows it landed.

After the last take of a label, a **cross-validation gate** scores each take against its siblings via DTW. If one is an outlier (mean distance to its siblings > `matchThreshold × 1.4`), a dialog asks "Take N looks off, re-record?". Re-record wipes that one take and the loop captures another; Skip accepts as-is.

Tapping **Build templates** sets `onboarded=true` and returns to Control. There is no post-training verification pass: the cross-validation gate already catches inconsistent takes per label, and the Voice tab gives instant feedback on the first real use. An earlier verification pass made the user say every command again after recording and could get stuck looping on a missed word; it was removed.

Re-train any time from `Settings → Re-record voice samples` (preserves existing takes; user can navigate to specific labels and re-record them individually). `Settings → Clear all recordings` wipes every template (with confirmation) and relaunches Training fresh. The Voice tab itself stays clean — recording-management buttons live in Settings now to keep stop-adjacent surface area minimal.

### Key design decisions

**Speaker-locked by construction.** Templates are the user's own voice. Other speakers tend to score below threshold; the matcher is effectively a soft access control (not a security primitive).

**Hand-rolled MFCC + CMN.** No external audio libraries (TarsosDSP, etc). Pre-emphasis 0.97 → Hamming → radix-2 FFT (512-pt) → 26 mel filters → log → DCT (13 coeffs) → **cepstral mean normalization** (subtract per-coefficient mean across the utterance). CMN was added because the energy-bearing c0 term otherwise dominates DTW distance for similarly-loud words; without it, "start" and "stop" looked almost identical to the matcher. About 200 lines of Kotlin, allocation-free per call, runs in ~3 ms on a Pixel-class CPU. See `android-v2/docs/VOICE_ENGINE.md`.

**Sakoe-Chiba banded DTW with path-length normalization.** Band ratio 0.15 (path can wander up to 15% of the longer sequence off-diagonal). Normalize by `n + m` so longer templates don't lose unfairly. Distance per cell is Euclidean over 13 MFCC coefficients.

**Per-label keep-all-takes.** Three takes per label → keep all three MFCC sequences; match against the best one. More robust than averaging templates (averaging blurs the per-take detail that helps reject lookalikes).

**Save MFCCs after every take.** A crash mid-onboarding loses at most the active take, not progress.

**Energy + ZCR VAD for always-on.** Energy alone trips on rumble; ZCR alone trips on hiss. Both together approximates speech reasonably well in ~50 lines. Requires ~120 ms of voiced audio (4 × 30 ms blocks) before triggering, then waits a further ~1.8 s so the rolling buffer contains the full word before snapshotting.

**AudioRecord source is `MIC`, not `VOICE_RECOGNITION`.** The Android emulator's virtual mic often delivers silence to `VOICE_RECOGNITION` even when the host mic is enabled — Google Assistant uses a different audio path so it works while our app didn't. `MIC` is the bare microphone source every Android device and emulator routes to. Trade-off: we lose any AGC / noise suppression a device might apply to `VOICE_RECOGNITION`, but emulator parity and reliability matter more.

**Match-confidence gates.** Two checks before a voice command fires:
1. `score ≤ matchThreshold` (default 30; configurable in Settings).
2. `runnerUp - score ≥ score × 0.05` — relative-to-score margin gate. Absolute margin doesn't work because DTW distances scale with the noise floor; with CMN applied, winning scores can land in the 6-12 range and an absolute "must beat by 4" gate would reject everything. Relative margin scales naturally. When only one label is trained, runnerUp is `+∞` and the gate is a no-op.

**Live peak meters everywhere we listen.** Training capture and the Voice tab each have a horizontal bar driven by the audio block peak amplitude. Killed the "is the mic even working?" diagnostic problem dead — if the bar moves, audio is reaching the app; if it's flat, something upstream is broken (permissions, emulator routing, etc.).

**Ack tone on success.** `ToneGenerator(STREAM_MUSIC).TONE_PROP_ACK` plays a short beep when (a) a training take is saved, (b) a voice match fires a command. Pure audible confirmation; no haptics yet.

**Global max speed cap is enforced in `Dispatch.dispatch()`**, not in the API client. The client accepts 0–100; dispatch scales `design × globalMaxSpeed / 100` before passing through. Voice "faster"/"slower" deltas are also clamped to `[0, globalMaxSpeed]` so repeated "faster" can't sneak past the cap. Manual sliders apply the same caps at the UI layer (slider bounds = global band).

**Stroke inversion is applied inside the API client.** `HandyV2ApiClient.setStrokeRange` accepts user-coord `(min, max)` and, if `invertStroke` is on, sends `(100 - userMax, 100 - userMin)` to the device. All callers (Manual sliders, voice presets, tap presets) think in user coords; the flip happens once.

**Settings that affect the stroke range are re-asserted to the device in `ControlActivity.onResume`.** Invert and the global stroke band only reach the device when something pushes a range, so a change made in Settings used to sit dormant until the next pattern / preset / manual-slider commit (it "only worked for patterns"). `onResume` now detects an invert or band change and pushes immediately while connected: a band change sends the new full band `gMin..gMax` (matching what a pattern's `squashRange(0,100)` sends, and working in both directions); an invert-only change re-pushes the current cached range so the orientation flips without changing the range. If a third stroke-affecting setting ever shows the same lag, the cleaner fix is to make `setGlobalStrokeBand` / `setInvertStroke` push to the device themselves when connected.

**Connect-time park uses HDSP first, with a HAMP `slide(0,0)` fallback.** On a successful connect we open the slide range to the configured global band (so HDSP's `position` isn't clamped by a narrow leftover range from a prior session), switch to mode 2 (HDSP), and send one `PUT /hdsp/xpt` with `position=<globalStrokeMin or globalStrokeMax if inverted>, duration=2500ms, stopOnTarget=true, immediateResponse=true`. If that fails (older firmware, error code), fall back to HAMP `slide(target, target)` with `velocity=0` — a degenerate zero-width range that parks the device without oscillation. HAMP is always restored at the end either way. Older code did the park with HAMP+velocity which produced a visible pumping motion; HDSP gives a single slow slide.

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
    ├── begin/
    │   ├── take_1.json    # {"format": 3, "label": "begin", "mfcc": [[...13 doubles...], ...]}
    │   ├── take_2.json
    │   └── take_3.json
    ├── stop/
    │   └── ...
    └── ...
```

**Format version is 3.** History: 1 = original 1.5 s capture window, 2 = 2.5 s capture window (longer MFCC sequences), 3 = CMN applied (mean-centered features). Takes with a non-current `format` are filtered out on load (in `TemplateStore.loadAll` / `loadTakes`) and the user is re-prompted to record. Phase-3 TFLite embeddings will bump to format 4 so MFCC takes aren't silently mixed with embeddings. Re-numbering is preserved when a take is deleted (cross-validation re-record), via the two-phase rename in `TemplateStore.deleteTake`.

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
- Every debug build drops a version-named copy of the APK into the repo-root `apks/` folder (`SmartDongV2-v<versionName>-debug.apk`) via the `copyDebugApkToRoot` task — a stable place to grab an installer for sideloading (USB, Google Drive, a local link). The task is hooked to `packageDebug` (not `assembleDebug`) so it fires for `installDebug` too. The `.apk` files are gitignored; bump `versionName` / `versionCode` in `app/build.gradle.kts` to cut a new versioned file. See `apks/README.md`.
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
