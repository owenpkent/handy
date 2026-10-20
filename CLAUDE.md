# LLM Onboarding

Quick reference for AI assistants working on this repository. **Read this entire file before making changes.**

---

## About the Owner

I'm Owen — a wheelchair user with muscular dystrophy.

- **Typing is hard**: I work mouse-first; the pointer is cheap. Be proactive. Make the call yourself on small or reversible things instead of asking for confirmation.
- **Ask only when the answer changes the outcome**: Then end the turn with 2 to 4 lettered options (A/B/C), mark the one you recommend, so I can answer with one character. Use clickable choices when the interface offers them. Never end on an open question that needs typed prose.
- **Keep replies short**: Long explanations cost me more than they give.
- **Dictated prompts**: My prompts are often dictated, so names may be mistranscribed. Check a plausible proper noun against the repo before treating it as something new.
- **PowerShell on Windows** — Use PowerShell syntax. Prefer single-line commands.
- **Accessibility matters** — This project is a tool I actually need.
- **Non-standard voice** — Browser Web Speech API and Android SpeechRecognizer both fail badly with my voice. The current answer on Android is **matching against my own prerecorded samples** (MFCC + DTW, no ASR at all) in `android-v2/`. Earlier attempts: Vosk offline + constrained grammar on Android (now `legacy/android/`), Deepgram on web.

---

## Project Overview

**Name:** Handy Voice Control  
**Purpose:** Accessible control apps for The Handy device, designed for users with disabilities.  
**Status:** Active Development

### Apps

**`android-v2/` is the only active app.** Everything else moved to `legacy/` (reference only, not maintained) — see `legacy/README.md`.

| App | Status | Description |
|-----|--------|-------------|
| `android-v2/` | 🟢 Active | The app. Android client using **prerecorded voice samples** (MFCC + DTW). Inspired by `../vega`. See `android-v2/README.md`. |
| `legacy/android/` | 🗄️ Frozen | Old Android app — Vosk offline ASR + constrained grammar. Superseded by `android-v2/`. |
| `legacy/v2-web/` | 🗄️ Frozen | Handy V2 web app — preset grid, sliders, debug panel. Good V2 REST reference. |
| `legacy/v1-web/` | 🗄️ Legacy | Handy V1 web app with Deepgram voice. |
| `legacy/alexa/` | 🗄️ Shelved | Alexa skill, blocked by Amazon content filter. |
| `legacy/homeassistant/` | 🗄️ Reference | HA component; its pattern envelopes were ported into android-v2's `PatternEngine`. |

---

## Key Files

> **Legacy apps live under `legacy/`** (`legacy/android/` Vosk app, `legacy/v2-web/`, `legacy/v1-web/`, `legacy/alexa/`, `legacy/homeassistant/`). They're frozen and not covered in detail here — see `legacy/README.md` for the index, and `legacy/android/README.md` for the v1 Vosk app's internals. Everything below is `android-v2/`.

### Android App V2 (`android-v2/app/src/main/java/com/handyvoice/v2/`)

The active app. Recognizes voice commands by matching against **prerecorded samples of the user's own voice**. Speaker-locked template matching with MFCC + DTW. Inspired by `../vega`. See `android-v2/README.md` and `android-v2/docs/`.

| File | Purpose |
|------|---------|
| `HandyVoiceApp.kt` | `Application` subclass. Holds the process-shared `HandyV2ApiClient` (`api`) used by both Splash and Control, plus an `appScope` (`SupervisorJob` + `Dispatchers.Main`) for work that must outlive a single activity — notably the splash-time connect. |
| `ui/SplashActivity.kt` | Animated launch screen and the app's `LAUNCHER` entry point (replaced ControlActivity as launcher). Pure property animation (no AnimatedVectorDrawable) so it animates the same on every API level / the emulator: logo fades + scales in with overshoot, two rings pulse outward behind it like sound waves, the title fades up, then the screen fades out and hands off to `ControlActivity` (~1.7 s, hard-capped). Uses `Theme.HandyVoiceV2.Splash` (window background = `background_dark`) so there's no flash before the layout draws. While animating it also kicks off the device connection on the **shared** `HandyV2ApiClient` (held on `HandyVoiceApp`, along with an `appScope` so the connect outlives the splash finish), so Control comes up already connecting/connected and never flashes the Connect button. |
| `ui/ControlActivity.kt` | Single-screen home for both control modes. While a connection attempt is in flight the setup panel swaps the Connect button for a spinner + "Connecting…" row and disables the button + key field (`showConnecting`), so an impatient user can't fire a second connect; driven by both the tap handler and the `connectionState` observer (covers the auto-connect-on-launch path too). **On a failed connect the reason is shown, not swallowed:** the status line paints `api.lastError` (e.g. "Device not connected. Make sure your Handy is online.") in red instead of a bare "Disconnected", and a manual Connect tap also fires a toast. Both the manual tap and the splash-time auto-connect surface this, since both land in `DISCONNECTED` with the reason cached; a clean disconnect clears `lastError` and falls back to the plain label. The connecting/connected states reset the status color so a prior red reason doesn't bleed through. **Material `TabLayout`** at the top switches between **Voice** and **Manual** panels; the big START / STOP row shows on the **Manual** tab only (the Voice tab has its own Begin / Stop shortcut buttons, so the pair would be redundant there). The header gear (`settingsButton`) opens `SettingsActivity` directly. Voice panel: mic toggle, live peak meter, "Heard:" readout, and tap-shortcut buttons for every voice label (Begin/Stop, Slower/Faster) routed through `Dispatch.dispatch` for parity with the spoken path. Auto-scrolling debug log below. Recording management (Re-record / Clear all) lives in Settings, not on the Voice tab. Manual panel: stroke Min/Max sliders bounded to the global band, speed slider bounded to `[0, globalMaxSpeed]`, 4×3 pattern grid (3 buttons share voice labels via `Dispatch.dispatch`, 9 are tap-only), feedback line. Templates reload in `onResume` so new recordings take effect without restart. Owns the `humanizeEngine` (strength loaded from prefs in `bootstrap`/`onResume`, applied live) and passes it to every `Dispatch.dispatch` call; the Manual speed slider / START / faster-slower helpers also route through it when enabled so they match the voice path. Every stop path (STOP button + dispatch) cancels the humanize / pattern loops then calls `api.rampStop()` for the wind-down. **Picture-in-Picture:** a header float button (`pipButton`, shown once connected on PiP-capable devices) and `onUserLeaveHint` (auto-float on Home **while running**) both call `pip.enter()`. `onPictureInPictureModeChanged` swaps the full UI (`mainScroll`) for a compact `pipOverlay` status readout. PiP control taps arrive as broadcasts (`onPipControl`) and route through `Dispatch.dispatch`, noting activity + beeping since they don't reach `onUserInteraction`. The `PipController` receiver is registered for the activity's whole lifetime. |
| `ui/PipController.kt` | Owns Picture-in-Picture for ControlActivity: feature detection (`FEATURE_PICTURE_IN_PICTURE`), building the system **RemoteAction** control bar, the not-exported broadcast receiver the buttons fire, and entering PiP. A PiP window can't take touches on app views, so controls are RemoteActions; most phones cap them at 3 (`getMaxNumPictureInPictureActions`), some allow 5. The bar is the Slower/**Stop**/Faster trio, sized to the guaranteed 3-slot floor so it's never truncated. Each control value is a `Dispatch` label; distinct PendingIntent request codes per control (extras don't disambiguate PendingIntents). |
| `ui/TrainingActivity.kt` | First-run onboarding and re-recording. Single Record tap runs an auto-advance session through every (label, take) needing audio, with a pre-roll → "Listening, speak now" UI and a brief breathing pause between takes. Cross-validation flags outlier takes per label and offers an inline re-record. Build templates sets onboarded=true and returns to Control. Skip / Prev / Next stay enabled between captures so the user can navigate without canceling. Ack tone on every saved take. Trains `Dispatch.LABELS` by default, but accepts an `EXTRA_LABELS` intent extra to train a subset — Settings uses this to record just the reject/noise class (`Dispatch.NOISE_LABEL`). |
| `ui/SettingsActivity.kt` | Settings, grouped into four cards in order: **Connection** (connection key), **Motion limits** (global max speed, **Humanize (organic drift, 0 = off)** slider, global stroke band min + max, invert-stroke switch), **Voice recognition** (listening mode button vs always-on, match threshold, idle auto-stop, plus the Re-record / Clear-all recording buttons), and **About** (version + Check-for-updates button, see Auto-update). **Every control auto-applies the moment it changes**: sliders persist on release (`onStopTrackingTouch`, so a drag doesn't spam DataStore), radio / switch on toggle, and the connection key on focus-loss + `onPause`. There is no Save button; the **←  Home** button just returns to `ControlActivity`, which re-reads prefs in `onResume` and pushes them to the device. A `ready` flag gates the listeners so the initial `populate()` doesn't write the loaded values straight back. **Re-record voice samples** opens Training without touching saved takes (earlier this button silently wiped everything, a footgun). **Record device sound to ignore** launches Training scoped to the reject/noise class (`EXTRA_LABELS = [Dispatch.NOISE_LABEL]`) so the user captures the device's running motor sound — best done with the device already running. **Clear all recordings** wipes templates with a confirmation dialog and then routes to Training. |
| `api/HandyV2ApiClient.kt` | Handy V2 REST API client (Retrofit). Self-contained models (no V1 import). Single `PUT /slide` for stroke range. `setSpeed` does one velocity PUT; `rampSpeed(target)` **glides** to a target through several intermediate `PUT /hamp/velocity` legs (duration scales with distance, ~250–900 ms, ~1 point per leg, capped at 12 legs) so faster/slower feel smooth instead of jumping. Both share a private `putVelocity` so the ramp logs once. `rampStop()` is the **stop wind-down**: eases velocity to 0 over a *fixed* ~1.5 s (12 legs, not distance-scaled — so it's felt even at low capped speeds) then sends `hamp/stop`; backs every stop affordance. Quiet writers for the humanize loop: `setSpeedModulated` (velocity PUT, no log / no cache), `setStrokeRangeTransient` (/slide, no cache update — Manual sliders keep showing the user's band while the device drifts), and `reflectBaseSpeed` (display-only `currentSpeed` so the readout shows the steady base, not the wobble). |
| `audio/AudioCapture.kt` | Thin AudioRecord wrapper. `readWindow(seconds)` for fixed captures, `stream(...)` for the always-on loop. Source = `MediaRecorder.AudioSource.MIC` (not `VOICE_RECOGNITION` — see the design decisions below). |
| `audio/Vad.kt` | Energy + zero-crossing-rate voice activity detector. Used only by always-on mode. |
| `voice/Mfcc.kt` | **Hand-rolled MFCC** — pre-emphasis, Hamming window, radix-2 FFT (512-pt), mel filterbank, log, DCT. 13 coeffs, 25 ms / 10 ms framing. Allocation-free per call. |
| `voice/Dtw.kt` | **Sakoe-Chiba banded Dynamic Time Warping**. Path-length normalized so longer templates don't lose unfairly. |
| `voice/TemplateEngine.kt` | Interface: `addTake / match / labels / clearAll`. Future TFLite engine slots in here. |
| `voice/MfccDtwEngine.kt` | Default `TemplateEngine` impl. In-memory map of label → list of MFCC takes. `match()` returns `(label, score, runnerUp)`. |
| `voice/TemplateStore.kt` | JSON persistence under `filesDir/templates/<label>/take_N.json`. Format-versioned for forward-compat. |
| `voice/TfliteEmbeddingEngine.kt` | Phase 3 stub — Resnet50 embedding + cosine similarity à la EfficientWord-Net. |
| `dispatch/VoiceAction.kt` | Sealed class: `Stop / SetSpeed / AdjustSpeed / SetRange / Preset / Pattern`. `Stop` eases velocity to 0 (`rampStop`) before halting. |
| `dispatch/Dispatch.kt` | `Map<label, VoiceAction>` + `dispatch(label, api, globalMaxSpeed, ..., patternEngine, humanizeEngine)` suspending function. Mirrors `vega/commands.py`'s DISPATCH, with `Pattern` actions delegating to the pattern engine. When `humanizeEngine` is enabled (strength > 0), `SetSpeed`/`AdjustSpeed` route through it (it owns velocity + depth while running); `Stop` / patterns / presets cancel it first. |
| `dispatch/PatternEngine.kt` | Time-based envelope engine. 12 named patterns; each is a `suspend fun(api, intensity)` looping `(envelope, durationMs)` pairs into `api.setSpeed`. Ported from `homeassistant/.../patterns.py`. Cancellable; only one pattern runs at a time. |
| `dispatch/HumanizeEngine.kt` | **Humanizes regular (non-pattern) motion** so a steady speed doesn't feel robotic. While active it owns the device's velocity + stroke window, drifting both around the user's base: **speed** = eased base × a smooth multiplier (two slow incommensurate sines + a mean-reverting random walk), **depth** = the stroke window insets from the band by small slowly-varying amounts at each end (insets only, so it always has room; written via `setStrokeRangeTransient` so the Manual sliders stay put). Swing scales with strength (0 = off, from prefs; ±30% speed / 20-pt inset at 100). Speed base is `targetSpeed` (begin/faster/slower/slider); depth base is read live from `api.strokeMin/strokeMax`. Cancel restores the real band. Mirrors `PatternEngine`'s single-job lifecycle. |
| `update/Updater.kt` | In-app self-update. Polls the public releases repo's `/releases/latest`, compares the tag to `BuildConfig.VERSION_NAME`, downloads the `.apk` to `cacheDir/updates`, and hands it to the system installer via `FileProvider`. Quiet check on launch + manual button in Settings. See the Auto-update section below. |
| `data/PreferencesManager.kt` | DataStore prefs — connection key, listening mode, global max speed, humanize strength, match threshold, onboarded flag. |

### Layout Files (`android-v2/app/src/main/res/layout/`)

| File | Purpose |
|------|---------|
| `activity_splash.xml` | Animated launch screen. Centered logo (`ic_splash_logo`, the mic glyph), two `splash_ring` views that pulse outward behind it, app name + tagline. All animation is driven in `SplashActivity`. |
| `activity_training.xml` | Euphonia-style card per command. Big record button (Record electric-blue → Cancel indigo → Re-record sage), take dots, play button, prev/next/skip, Build templates CTA. |
| `activity_control.xml` | Root is a `FrameLayout`: a `mainScroll` ScrollView (the full UI) plus a `pipOverlay` (GONE; the compact status shown while floating in PiP). Header has a `pipButton` (float-to-PiP, GONE until connected) and the Settings gear. Setup panel has the connection-key field, Connect button, and a `connectProgressRow` (spinner + "Connecting…") shown only mid-connect. Voice card with mic toggle + last-match readout. Tap-shortcut rows below the mic: Begin/Stop, Slower/Faster — one button per voice label. Debug panel auto-scrolls to newest on update. Recording management buttons (Re-record, Clear all) live in Settings, not here. |
| `activity_manual.xml` | Manual control screen. Stroke min/max sliders + speed slider, 4×3 pattern grid (gold outlined), START/STOP. |
| `activity_settings.xml` | Four `MaterialCardView` sections in order (Connection / Motion limits / Voice recognition / About), each titled with the `SettingsSectionHeader` style. Connection key input; global-max-speed slider; global-stroke-band sliders (min + max); invert-stroke MaterialSwitch; listening-mode radio group; match-threshold slider; idle-auto-stop slider; Re-record + Clear-all-recordings buttons; version text + Check-for-updates button. No Save button (controls auto-apply on change). |

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
| `TROUBLESHOOTING.md` | Build issues (JAVA_HOME, SDK path, wrapper jar), runtime (mic permission, no match, false fires, threshold), dispatch (global max cap surprises), updates ("package conflicts" signing error). |
| `SIGNING.md` | App signing & the "App not installed as package conflicts with an existing package" update error. Why per-machine debug keys break updates, the committed shared keystore, and the original-machine runbook to seed it and publish without an uninstall. |

---

## Tech Stack

| Component | Technology |
|-----------|------------|
| Web | Vanilla HTML/CSS/JS (ES6+) |
| Android (v1, `legacy/android/`) | Kotlin, Android SDK, Material Design |
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

## Voice Control Architecture (legacy v1 — Vosk)

The frozen v1 app (`legacy/android/`) took a completely different approach: Vosk offline ASR with a constrained JSGF grammar, then a fuzzy parser over the transcript. Its engine internals, grammar phrase list, parser rules, and hard-won gotchas live in **[`legacy/android/README.md`](legacy/android/README.md)** — that's the only detailed record of them, so read it there rather than expecting them here.

The one thing worth carrying forward: Android's built-in `SpeechRecognizer` recognizes the entire English dictionary, so "slow top" came back as "surrounded the top". Constraining the vocabulary is what made voice control usable at all, and v2 takes that idea further by dropping the transcript step entirely.

---

## Voice Control Architecture (Android V2)

Completely different approach from the legacy Vosk app (`legacy/android/`). No ASR, no grammar parsing, no transcripts. The matcher compares incoming audio against the user's own pre-recorded samples and dispatches the best match (if it beats the threshold).

### Why a second app

The v1 app uses Vosk ASR with a constrained grammar. That works, but each utterance still has to be:

1. converted to English-ish words by an acoustic model trained on a broad population, then
2. parsed against the dispatch table.

For users with non-standard speech, step (1) is the brittle part. V2 cuts both steps. It records 3 takes per command in the user's voice and asks "does this audio look like the samples Owen recorded for `faster`?" Nothing has to map to dictionary words.

### Pipeline (one-line per box)

```
mic ─► AudioCapture (16 kHz mono int16)
       │
       ├─► [button mode]   tap mic ─► 2.5 s capture
       └─► [always-on]     VAD opens 2.5 s windows out of continuous stream
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

Four labels, picked for acoustic distinctness in MFCC+DTW space (different syllable counts, vowel positions, consonant families) so the matcher has wide gaps between any two trained words. The set has been trimmed twice: first the fixed pattern words (`thunder` / `ripple` / `pulse`) left the spoken set, then their replacements (`deeper` / `shallow` / `random`) followed. None of them earned their keep by voice, and every label removed widens the gaps between the ones that stay. Depth now lives on the Manual stroke sliders; the pattern envelopes are all still tap-able on the Manual tab.

| Label | Action |
|-------|--------|
| `begin` | `SetSpeed(25)` — ensure running, set speed. Renamed from `start` because "start" and "stop" collide in MFCC space for slower / less-articulated speech. |
| `stop` | `Stop` — immediate stop. Also cancels any running pattern. |
| `faster` | `AdjustSpeed(+25)` — design-space step (`Dispatch.DEFAULT_STEP`), scaled by globalMaxSpeed. **Glides** to the new speed via `api.rampSpeed` rather than jumping. |
| `slower` | `AdjustSpeed(-25)` — same, glided. |

Depth is adjusted on the **Manual tab's stroke sliders**, not by voice. There is no `AdjustDepth` action anymore: the old `deeper` / `shallow` pair slid a fixed-length window through the band, but the pair was dropped along with `random` because they were rarely worth speaking and each one crowded the MFCC space the remaining commands need.

The Voice tab carries tap-shortcut buttons for all four so the user has a one-tap fallback when speech isn't an option (or templates aren't trained yet). Tap shortcuts call `Dispatch.dispatch(label)` directly — same code path the voice match takes, same caps applied.

Pattern envelopes live in **`PatternEngine`** (`dispatch/PatternEngine.kt`) ported from `homeassistant/custom_components/handy/patterns.py`. Each pattern is a `suspend fun(api, intensity)` that loops forever, emitting `(envelope, durationMs)` pairs to `api.setSpeed(envelope × intensity)`. Cancelling the job stops the device. No pattern is reachable by voice at all; the Manual tab exposes all twelve envelopes as tap buttons (`thunder` / `ripple` / `pulse` / `surge` / `breathe` / `tick` / `tease` / `edge` / `rush` / `throb` / `bounce` / `climax`). `tick` is the old metronome envelope that used to be named `pulse`. `VoiceAction.Pattern(name, intensity)` is the dispatch shape.

### Listening modes

Set in `Settings → Listening mode`:

- **Tap mic** (default) — tapping the mic button starts continuous VAD-driven listening (and tapping again stops it **and stops the device**, via the same `"stop"` dispatch as the idle auto-stop, so motion never outlives voice control). Same loop as always-on, just gated by the user.
- **Always listening** — VAD-driven loop runs whenever the activity is in the foreground and connected. A mic tap while listening pauses it and stops the device (same as tap-mic mode); the next tap or resume re-arms it.

**Keep screen on while the app is running.** `ControlActivity.onCreate` sets the window's `FLAG_KEEP_SCREEN_ON` unconditionally, so the screen stays on the whole time the app is in the foreground, not just while listening. The OS only honors the flag while the window is visible, so it costs nothing when the app is backgrounded and a navigate-away lets the screen sleep normally. `SettingsActivity` and `TrainingActivity` set the same flag in their `onCreate`. NB: `android:keepScreenOn` on an `<activity>` in the manifest is silently ignored. It's a `View` / window flag, not an activity attribute, so this has to be set in code.

**Partial wake lock while listening (screen-off CPU).** Separately from the screen flag, `ControlActivity.keepAwake(true)` acquires a `PARTIAL_WAKE_LOCK` (`"HandyVoiceV2:listening"`) while the listening loop is active, dropped in `stopAlwaysOn` / `onDestroy`. This keeps the CPU alive for capture if the user **deliberately** powers the screen off mid-listen. `keepAwake` no longer touches `FLAG_KEEP_SCREEN_ON` (that's owned by `onCreate` now). Teardown is **screen-aware**: `onStop` only cancels the loop when `PowerManager.isInteractive` (a real navigate-away: Home / Settings). A screen-off (power button) leaves the loop running on the wake lock so voice control keeps working with the screen dark. `onResume` re-arms always-on if it isn't already running.

**Idle auto-stop (configurable, default 10 min).** An inactivity watcher (`armIdleTimeout`, armed in `startAlwaysOn`) stops the session after the idle window with no activity, where "activity" is any voice match *or* any screen touch (`onUserInteraction` → `noteActivity`, so Manual-slider use counts and the device won't stop mid hands-on use). The window is set in **Settings → Idle auto-stop (minutes)** (`PreferencesManager.idleTimeoutMin`, clamped 1–30, default 10); `ControlActivity` caches it as `idleTimeoutMs`, refreshed every `onResume`, so a change applies on the watcher's next re-check. On expiry, `onIdleTimeout` **stops the Handy** (reuses the `"stop"` dispatch: cancels any running pattern, then HAMP stop) and `stopAlwaysOn()` drops the CPU wake lock. The screen itself stays on as long as the app is foregrounded (per the `onCreate` flag); the idle stop now halts the device and frees the mic rather than letting the screen sleep. Restart is a mic tap (works in always-on mode too: `onMicTapped` re-arms the loop when it's down) or the next resume. The watcher re-checks a `lastActivityAt` timestamp rather than restarting per touch, so a slider drag is cheap.

Both modes use a **post-VAD delay** (~1.8 s) before snapshotting the rolling buffer. The VAD trigger fires ~120 ms into a word, so a same-instant snapshot would mostly be the silence preceding the utterance. The delay lets the buffer slide forward until the full word is inside the 2.5 s window — matching where words sit in the training takes.

A short (~0.6 s) cooldown after each dispatch debounces same-utterance double-fire. It's deliberately short: the stamp is set at snapshot time regardless of whether the match passed, so a long cooldown is dead air where a restart word is silently swallowed. It used to be 1.5 s, which — on top of the post-VAD wait and the ~1.5 s stop wind-down — left a ~3.3 s hole after every stop where "begin" wouldn't register. A fresh velocity command now supersedes an in-flight ramp at the client level (`velocityEpoch` in `HandyV2ApiClient`: `start`/`stop`/`setSpeed`/`setSpeedModulated` bump it; `rampSpeed`/`rampStop` bail when it changes), so a "begin" landing while a "stop" is still winding down wins instead of being ramped back to 0. That decoupling is what let the cooldown drop without reintroducing a begin-vs-stop race.

### Picture-in-Picture

ControlActivity can float into a PiP window so the device stays controllable while the user is in another app. Lives in `ui/PipController.kt`; ControlActivity owns one and wires the lifecycle.

**Why RemoteActions, not buttons.** A PiP window does **not** deliver touches to the activity's own views — the only controls Android allows are system `RemoteAction`s (icon buttons in the window's control overlay), each backed by a `PendingIntent`. So the floating control bar is built from RemoteActions, not the on-screen layout. (In PiP, `onPictureInPictureModeChanged` hides `mainScroll` and shows the compact `pipOverlay` status text — purely informational, since it can't be tapped.)

**The 3-action cap.** `getMaxNumPictureInPictureActions()` (API 26+) returns at least 3; some phones allow 5, and the action list is silently truncated past the cap. `buildActions()` returns the **Slower / Stop / Faster** trio, which fits inside the guaranteed floor, so nothing is ever truncated and **Stop is always present**. (It used to branch to a five-action bar including Deeper / Shallow, until those commands were removed.)

**Entry points.** The header `pipButton` (visible once connected, only where `FEATURE_PICTURE_IN_PICTURE` exists) calls `pip.enter()`. `onUserLeaveHint` also auto-enters **when connected AND `isRunning`** — pressing Home while the device moves floats it so Stop stays reachable; pressing Home while idle just backgrounds normally. `onUserLeaveHint` does **not** fire for in-app `startActivity` (Settings / Training), so those don't auto-float.

**Dispatch parity.** A PiP control tap arrives as a broadcast to a `RECEIVER_NOT_EXPORTED` receiver, handled by `onPipControl(label)`, which routes through the same `Dispatch.dispatch` as voice + tap shortcuts (same caps, ramp, stop-cancels-pattern). Because a broadcast never reaches `onUserInteraction`, `onPipControl` calls `noteActivity()` itself (so a PiP tap pushes back the idle auto-stop) and `playAck()` (audible confirmation, since the icons are silent).

**Gotchas.**
- **Distinct PendingIntent request codes per control.** `PendingIntent` equality ignores `Intent` extras, so five buttons built with the same request code collapse onto one action. Each control gets its own request code (1–5).
- **Manifest.** ControlActivity needs `android:supportsPictureInPicture="true"` and `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation|keyboardHidden"` so the PiP resize doesn't recreate the activity. Icons are `ic_pip_{slower,faster,stop}` + `ic_pip_enter` (vector drawables).
- The listening loop is **not** torn down on entering PiP (the activity stays STARTED while the PiP window is visible), so voice + the receiver keep working there.

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

**Reject/noise class kills device-noise false-fires.** The device's own motor sound was being heard as "stop" (it halted itself). Fix: a reserved `Dispatch.NOISE_LABEL` (`"__noise__"`) that is **not** a command — it holds templates of the device's running sound, trained from Settings → "Record device sound to ignore" (Training scoped via `EXTRA_LABELS`). It loads into the matcher like any label (`loadKnownTemplates` allow-lists `LABELS` + `NOISE_LABEL`), so when incoming audio is closest to the noise class it *wins* the match and `handleAudio` drops it — no dispatch, no ack beep, no idle-timer reset. Underscored so it can't collide with a spoken label; `TemplateStore.sanitize`'s regex keeps `_`, so it round-trips through save/load/match unchanged. Absent noise templates → behaviour is unchanged (nothing to match against). **It is opt-in — never part of first-run onboarding, which trains only the four commands (`Dispatch.LABELS`); recording noise is a deliberate Settings action, so normal use never requires it.** Because the noise takes are the actual device sound and speech is acoustically very different, a real "stop" still matches its own template better. `updateLastMatchForTemplates` counts command labels only so the reject class doesn't skew the "trained N/4" readout.

**Live peak meters everywhere we listen.** Training capture and the Voice tab each have a horizontal bar driven by the audio block peak amplitude. Killed the "is the mic even working?" diagnostic problem dead — if the bar moves, audio is reaching the app; if it's flat, something upstream is broken (permissions, emulator routing, etc.).

**Ack tone on success.** `ToneGenerator(STREAM_MUSIC).TONE_PROP_ACK` plays a short beep when (a) a training take is saved, (b) a voice match fires a command. Pure audible confirmation; no haptics yet.

**Global max speed cap is enforced in `Dispatch.dispatch()`**, not in the API client. The client accepts 0–100; dispatch scales `design × globalMaxSpeed / 100` before passing through. Voice "faster"/"slower" deltas are also clamped to `[0, globalMaxSpeed]` so repeated "faster" can't sneak past the cap. Manual sliders apply the same caps at the UI layer (slider bounds = global band).

**Faster/slower glide, they don't jump.** `AdjustSpeed` (and the tap Slower/Faster buttons) call `api.rampSpeed(target)`, which steps the device through several intermediate velocities to the clamped target instead of one `setSpeed`. Ramp duration scales with the distance travelled (~45 ms/point, clamped 250–900 ms), ~1 point per leg, capped at 12 legs, interval floored at 55 ms so it never floods the API. The ramp runs inside the dispatch coroutine and finishes well within the 1.5 s voice cooldown. Overlapping ramps (very rapid taps) aren't serialized — last write wins, which is fine for a monotonic faster/slower gesture. Tuning constants live at the top of `HandyV2ApiClient`.

**Stop winds down, it doesn't slam.** Every stop affordance (voice `stop`, its tap shortcut, PiP Stop, the idle auto-stop, and the big Manual STOP button) routes through `api.rampStop()`: it eases velocity to 0 over a **fixed** ~1.5 s (12 legs) then sends `hamp/stop`. Fixed-duration, *not* distance-scaled like `rampSpeed`, because at Owen's speed cap the device velocity is small (e.g. 7) and a distance-scaled ramp would be ~300 ms — read as instant. Callers cancel any running pattern / humanize loop first (each keeps writing velocity), then `rampStop`; after cancel the device is still at its last velocity, which is exactly what the ramp winds down from. The ~1.4 s suspend runs off the capture thread so listening never blocks. Tuning constants (`STOP_RAMP_MS`, `STOP_RAMP_STEPS`) sit with the other ramp constants in `HandyV2ApiClient`.

**Regular (non-pattern) motion is humanized.** A steady speed is otherwise a perfectly constant HAMP velocity, which reads as robotic. `HumanizeEngine` (a sibling to `PatternEngine`, owned by `ControlActivity`, threaded into `Dispatch.dispatch`) layers organic drift on top while regular motion runs: **speed** = an eased base velocity × a smooth multiplier built from two slow incommensurate sines + a mean-reverting random walk (never periodic); **depth** = the stroke window insets from the user's band by small, slowly-varying amounts at each end so the reach breathes. Depth **only insets** (never past the band) so it always has room, even at a full 0–100 band, and is written via `setStrokeRangeTransient` so the Manual sliders keep showing the user's chosen band while the device wanders within it. The speed readout stays on the steady base via `reflectBaseSpeed` (a display-only write) instead of jittering with the modulated velocity. Strength is a Settings slider (`humanize_strength`, 0 = off, default 30); swing scales linearly (±30% speed / 20-pt inset per end at 100). When enabled, `SetSpeed`/`AdjustSpeed` — voice, tap shortcuts, PiP, and the Manual speed slider / START / faster-slower buttons — all route through the engine so every path behaves identically; a stop, a pattern, or speed 0 cancels it (restoring the real band). The Manual stroke sliders retune the drift centre for free because the engine reads the base range live from `api.strokeMin/strokeMax` (transient writes never touch that cache). Disabled (strength 0) falls back to the plain `setSpeed`/`rampSpeed` path.

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

21 JVM unit tests cover the audio math and the update version-compare without needing Android:

- `MfccTest` (5) — extraction shape, determinism, frequency discrimination, empty/short input handling.
- `DtwTest` (5) — self-distance zero, symmetry, similar beats dissimilar, length invariance after normalization, empty handling.
- `MfccDtwEngineTest` (4) — no-templates returns null, trained label wins, two-takes-per-label preserves match, clearAll works.
- `UpdaterTest` (7) — `isNewer`: integer-component comparison (so `0.10 > 0.9`, the bug that motivates it), equal / older versions, leading `v` tolerated, differing component counts (`1.0 == 1.0.0`), and malformed versions never updating.

Run: `./gradlew :app:testDebugUnitTest` from `android-v2/`. ~40 ms total runtime.

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
1. **Unconstrained ASR is the wrong tool for a fixed command set.** Android's `SpeechRecognizer` searches the whole English dictionary, so "slow top" came back as "surrounded the top". Constraining the vocabulary (v1, via a Vosk grammar) fixed it; removing the transcript step altogether (v2) fixed it better.
2. The rest of the v1 Vosk lessons — AudioRecord warmup and the muted/unmuted pattern, partials that never finalize on a repeated word, `StateFlow` swallowing duplicate emissions — are specific to that frozen app and live in [`legacy/android/README.md`](legacy/android/README.md).

### API
1. **V2 stroke range** uses single `PUT /slide` with `{min, max}`, NOT separate `/slide/min` and `/slide/max` (those 404).
2. **Set speed AFTER start** — the web app explicitly does this and it matters.
3. **Set HAMP mode once** on connect, not on every action.
4. **Name collisions** — `SlideRequest` exists in both V1 (`HandyApiService.kt`, `position: Double`) and V2 clients. V2 uses `SlideRangeRequest` to avoid the collision.

### Android V2
1. **Save MFCCs after every take, not at the end of onboarding.** A crash should lose the active take, not 30 minutes of work.
2. **`MediaRecorder.AudioSource.MIC` is the source, not `VOICE_RECOGNITION`.** `VOICE_RECOGNITION` is the textbook choice (it applies the OS speech-tuning DSP chain), but the emulator's virtual mic delivers silence to it in many configurations while routing to `MIC` reliably. Emulator parity won. See the design-decisions note above and `docs/VOICE_ENGINE.md`.
3. **`AudioTrack.Builder` (not the deprecated constructor)** — `AudioTrack(STREAM_MUSIC, ...)` is deprecated since API 26 (our minSdk). Use the builder with `AudioAttributes.USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`.
4. **`MfccDtwEngine.match()` runs on `Dispatchers.Default`**, capture on `Dispatchers.IO`. The matcher is CPU-bound and short (~1 ms); IO blocks shouldn't compete for the same threads as filesystem writes.
5. **Always-on capture loop is gated by a `Job.isActive` check**, not a separate flag. Cancelling the job lets the unbounded loop drain and `AudioRecord.release()` run.
6. **Template format is versioned in JSON.** Even though Phase 1 only has `format: 1`, the field exists so future migrations are mechanical. Reads silently skip mismatched takes rather than crash.
7. **`TemplateStore.sanitize()` lowercases and replaces non-alphanumerics.** Dispatch labels are already clean, but this is defence-in-depth — a misconfigured label can never break the filesystem.
8. **The gradle wrapper jar in the legacy v1 app's `legacy/android/gradle/wrapper/` is the wrong file** (it's the launcher jar). `android-v2/` has a correct wrapper regenerated from gradle 9.2.1. If you copy the v1 wrapper, `gradlew.bat` will fail with `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`.

### Engine math (Android V2 specific)

1. **MFCC frame size = 400 samples (25 ms), hop = 160 samples (10 ms)**. Standard speech-feature framing. One second of audio yields ~98 frames of 13 coefficients, so the 2.5 s capture window yields ~248.
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

> Legacy apps (`legacy/v2-web/`, `legacy/android/`, etc.) are frozen — build instructions for them, if needed, live in their own folders. Only `android-v2/` is active.

### Android App V2 (`android-v2/`)
- Open `android-v2/` in Android Studio (lets it sync, generates wrapper).
- Or from the command line:
  ```powershell
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
  cd C:\Users\Owen\dev\handy\android-v2
  .\gradlew.bat :app:assembleDebug
  ```
- Tests (JVM, no emulator): `.\gradlew.bat :app:testDebugUnitTest` — 21 tests, ~40 ms.
- Install to attached device: `.\gradlew.bat :app:installDebug`.
- Every debug build drops a version-named copy of the APK into the repo-root `apks/` folder (`SmartDongV2-v<versionName>-debug.apk`) via the `copyDebugApkToRoot` task — a stable place to grab an installer for sideloading (USB, Google Drive, a local link). The task is hooked to `packageDebug` (not `assembleDebug`) so it fires for `installDebug` too. The `.apk` files are gitignored; bump `versionName` / `versionCode` in `app/build.gradle.kts` to cut a new versioned file. See `apks/README.md`.
- First launch on device routes straight to TrainingActivity. Record 3 takes per command, tap **Build templates**, then enter connection key.
- No models to download. Templates are user-recorded; nothing leaves the device.
- Reset everything: `adb shell pm clear com.handyvoice.v2` wipes prefs + templates.

### Auto-update (Android V2)

The app self-updates from a **public releases repo** so the source repo can stay private. Built APKs are published as GitHub Releases to **`owenpkent/handy-releases`** (`Updater.RELEASES_REPO`); the app polls that repo's `/releases/latest` and never needs an embedded token.

- **Code:** `update/Updater.kt`. Flow: GET `/releases/latest` → compare `tag_name` to `BuildConfig.VERSION_NAME` (`Updater.isNewer`, integer-component compare so `0.10 > 0.9`) → if newer, download the `.apk` asset to `cacheDir/updates/update.apk` → hand to the system installer via `FileProvider` (authority `${applicationId}.fileprovider`, see `res/xml/file_paths.xml`).
- **Triggers:** a quiet check in `ControlActivity.onCreate` (once per process; prompts only if a newer build exists) and a manual **Settings → About → Check for updates** button (`manual = true`, which also reports "up to date" / errors).
- **Permissions:** `REQUEST_INSTALL_PACKAGES` in the manifest; on first use the app routes the user to *install unknown apps* for itself if not yet granted. `buildConfig = true` is enabled so `BuildConfig.VERSION_NAME/CODE` exist.
- **Not silent.** Android always shows its own one-tap installer for a sideloaded app; truly silent install needs Play / device-owner. This is the closest possible.
- **Signing constraint.** Android only applies an update whose APK is signed with the **same key** as the installed build; a mismatch fails with *"App not installed as package conflicts with an existing package."* To make updates install over the top from **any** machine, the build signs with a committed shared keystore at `app/signing/handy.keystore` when present (`signingConfigs` in `app/build.gradle.kts`; standard debug creds `android`/`androiddebugkey`/`android`). It's a debug keystore — an identity, not a secret — so committing it is intentional. Seed it once by copying the debug keystore that signed the **currently installed** build (your main PC's `~/.android/debug.keystore`) into that path, so the first post-change update installs without an uninstall. If the file is absent the build falls back to the per-machine `~/.android/debug.keystore` (old behaviour: publish from one machine only). See `app/signing/README.md`.
- **Publishing:** bump `versionName` + `versionCode` in `app/build.gradle.kts`, then run `android-v2/publish-release.ps1` (optionally `-Notes "..."`). It builds the APK, creates the public repo on first run (`--add-readme` so a release tag has a commit to point at), and cuts/updates the `v<versionName>` release. Tests: `UpdaterTest` covers the version-compare gate.
- **Bootstrap:** v0.11 is the first build containing the updater. It must be sideloaded manually once; from v0.12 onward the in-app updater takes over. **v0.17** is the first build signed with the committed shared keystore (`app/signing/handy.keystore` was seeded from this PC's debug key); from v0.17 on, updates install over the top from any machine with no uninstall (see `docs/SIGNING.md`).

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
