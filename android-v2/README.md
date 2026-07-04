# Handy Voice V2 (Android)

An Android client for The Handy that recognises voice commands by matching against **prerecorded samples of the user's own voice** rather than running speech-to-text. Lives alongside the Vosk-based `../android/` app; both can be installed at once.

## Status

🟢 **Phase 1 + 2 complete on-device.** Verified working on the Android emulator and real devices. Voice training, MFCC + CMN + banded DTW matching, the full HA-derived pattern engine (12 envelopes), tab-based UI, an HDSP-driven connect-time park, and a **Picture-in-Picture** control bar are all live. Recent additions: **humanized regular motion** (organic speed + depth drift), a **wind-down stop** (eases to 0 instead of a hard halt), and a **reject/noise class** that stops the device's own motor sound from false-firing commands.

## Why a second Android app

`../android/` uses Vosk offline ASR with a constrained JSGF grammar. That removes the worst Android `SpeechRecognizer` failures, but every utterance still has to be:

1. converted into a sequence of English-ish words by an acoustic model trained on a broad population, then
2. parsed into a command by fuzzy-matching against the dispatch vocabulary.

For users with non-standard speech, step (1) is the brittle part. Both steps can fail in ways that take work to diagnose.

`android-v2/` shortcuts both steps. It records 3 takes of each command in the user's own voice on first launch, builds an MFCC template per take, and at runtime asks: *"does the audio I just captured look like the samples Owen recorded for `faster`?"* Nothing ever has to map "what was said" to an English word. The matcher is speaker-locked by construction: it knows exactly one speaker's voice and exactly seven labels, and only fires when the incoming audio resembles one of them.

Inspired by [`../../vega/`](../../vega), which uses the same pattern-matching approach on a Raspberry Pi with EfficientWord-Net.

## Architecture (one-line per box)

```
mic ─► AudioCapture (16 kHz mono int16, AudioSource.MIC)
       │
       ├─► [button mode]   tap mic ─► VAD loop starts ──┐
       └─► [always-on]     VAD loop running while connected ──┤
                                                              │
                                                              ▼
                          ~120 ms voiced + ~1.8 s post-VAD wait ─► 2.5 s buffer snapshot
                                                              │
                                                              ▼
       Mfcc (13 coeffs, 25 ms / 10 ms framing, radix-2 FFT, mel filterbank, DCT)
       │            └─► CMN: subtract per-coefficient mean across the utterance
       ▼
       MfccDtwEngine.match() ── Sakoe-Chiba banded DTW vs every stored take ──► (label, score, runnerUp)
       │
       ├─► score ≤ threshold  AND  margin ≥ score × 0.05
       ▼
       (drop the match if label == __noise__ reject class)
       │
       ▼
       Dispatch.dispatch(label, ..., patternEngine, humanizeEngine) ─► HandyV2ApiClient ─► REST v2
                                  │
                                  ├─► VoiceAction.Pattern → PatternEngine.start(name, peak)
                                  │                           loop (envelope, duration) → api.setSpeed
                                  └─► SetSpeed/AdjustSpeed (humanize on) → HumanizeEngine
                                                              loop: speed + depth drift around the base
```

Each piece is documented in detail in [docs/](docs/):

- **[ARCHITECTURE.md](docs/ARCHITECTURE.md)** — process model, threading, lifecycle, state, persistence, things deliberately missing.
- **[VOICE_ENGINE.md](docs/VOICE_ENGINE.md)** — every MFCC and DTW parameter explained, why these choices, how to tune the threshold, the margin gate, swappable-engine contract.
- **[COMMANDS.md](docs/COMMANDS.md)** — vocabulary, dispatch table, pattern envelopes, how to add a new command end-to-end.
- **[TRAINING.md](docs/TRAINING.md)** — user-facing recording guide. Auto-advance, cross-validation, reading match scores.
- **[TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)** — build and runtime issues with concrete fixes (including the emulator microphone gotcha).

## Quick start

### Build

JDK 17+ required to launch the Gradle daemon (Android Studio's bundled JBR is fine). The wrapper downloads Gradle 9.4.1 on first run, and Gradle then auto-provisions a JDK 21 toolchain via Foojay for the build itself.

```powershell
# From this directory:
.\gradlew.bat :app:assembleDebug
```

The signed debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Or open `android-v2/` in Android Studio and let it sync.

A version-named copy is also dropped into the repo-root `apks/` folder (`SmartDongV2-v<versionName>-debug.apk`) by the `copyDebugApkToRoot` task, so there's a stable, human-named installer to grab for sideloading. The `.apk` files are gitignored; bump `versionName` / `versionCode` in `app/build.gradle.kts` to cut a new versioned file. See [`apks/README.md`](../apks/README.md).

### First run on device

1. Install the debug APK (`adb install -r app/build/intermediates/apk/debug/app-debug.apk` with USB debugging on, or drag-drop).
2. Launch **Smart Dong v2**. On first run (`onboarded` flag is false) the app routes straight to **Training**.
3. Training is a 7-card carousel. **Tap Record once** — the app records 3 takes of each label automatically with a brief "Get ready…" pre-roll, a 2.5 s "Listening, speak now" capture, and an ack beep on each saved take. Encouragement toasts ("Nice! Next: stop") fire at label transitions. Skip / Prev / Next stay available between takes.
4. After the last take of each label, a **cross-validation gate** scores the takes against each other; if one looks like an outlier you'll get a "Re-record take N?" dialog.
5. Tap **Build templates** to finish; `onboarded` flips and you return to Control. (Earlier versions ran a post-Build verification pass that asked you to say every label once more; that was removed because the cross-validation gate above plus first-use feedback on the Voice tab covers the same ground without a second gauntlet.)
6. Back on the main screen, enter your Handy connection key and tap **Connect**. The device performs a slow HDSP slide to position 0 (or `globalStrokeMin` if you've set one) on a successful connect.
7. Use the **Voice / Manual** tabs. Voice tab: tap MIC to start continuous VAD-driven listening, tap again to stop. Live peak meter under "Heard:" confirms the mic is hot. Tap-shortcut buttons for every voice label (Begin/Stop, Slower/Faster, Thunder/Ripple/Pulse) sit below. Manual tab: stroke / speed sliders + all 12 pattern buttons. The big START / STOP row shows on the Manual tab only (the Voice tab's Begin / Stop shortcuts cover it there).

### Tests

JVM unit tests for the audio math run without the emulator:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

These cover MFCC determinism + shape + frequency discrimination, DTW symmetry + length-invariance + sanity, and engine label routing. 14 tests, ~30 ms total.

## Vocabulary

Seven labels, picked for acoustic distinctness in MFCC+DTW space. The vega set had `heartbeat / wave / surge / breathe / storm` for patterns; those collapsed in a similar corner of MFCC space and kept colliding, so the kept patterns were renamed (and the rest dropped from voice; their envelopes are still on the Manual tab as tap-only buttons).

| Label | Action |
| --- | --- |
| `begin` | Set speed to 25 design points (scaled by global max). Renamed from `start` because `start`/`stop` collide for slower speech. |
| `stop` | Stop the device + cancel any running pattern / humanize loop. Eases the speed down to 0 over ~1.5 s (`rampStop`) rather than a hard halt. |
| `faster` | Adjust speed by +25 design points, clamped to `globalMaxSpeed`. Glides to the new speed (`rampSpeed`) instead of jumping. |
| `slower` | Adjust speed by -25 design points, clamped to `[0, globalMaxSpeed]`. Same glide. |
| `thunder` | Run the **thunder** envelope (random walk; was `storm`). |
| `ripple` | Run the **ripple** envelope (sine 0..1..0, 8 s cycle; was `wave`). |
| `pulse` | Run the **pulse** envelope (lub-dub-rest; was `heartbeat`). |

See [docs/COMMANDS.md](docs/COMMANDS.md) for envelope definitions and the full pattern engine. The Voice tab carries a tap-shortcut button for every voice label so the user has a one-tap fallback when speech isn't an option.

**Reject/noise class.** A reserved non-command label `__noise__` holds recordings of the device's own motor sound (trained from **Settings → Record device sound to ignore**). When incoming audio matches it best, the match is dropped instead of dispatched — the fix for the running device being misheard as "stop". Absent those recordings, behaviour is unchanged.

**Humanized regular motion.** By default a steady speed isn't a flat velocity — `HumanizeEngine` drifts both the speed (layered slow sines + a random walk) and the stroke depth (the window breathes within your band) so it doesn't feel robotic. Strength is a **Settings → Motion limits → Humanize** slider (0 = off, default 30); it scales the swing and respects every cap. Turn it to 0 for the old constant-speed behaviour.

## UI layout

One activity (`ControlActivity`) hosts both control surfaces via a `TabLayout`:

```
┌──────────────────────────────────────────┐
│ Smart Dong v2        ● Connected  ⧉  ⚙   │   header (status + float-to-PiP + Settings cog)
├──────────────────────────────────────────┤
│  [ Voice ]   Manual                      │   modeTabs (Material TabLayout)
├──────────────────────────────────────────┤
│  (voicePanel)            (manualPanel)   │   one panel visible at a time:
│                                          │
│  voice mode label                        │   VOICE TAB
│      ▼                                   │
│  ⬤  (MIC button)                          │
│  ▒▒▒▒▒░░░░░ peak meter                   │
│  "Heard: begin (score 8.2)"              │
│  [ Begin   ]  [ Stop    ]                │
│  [ Slower  ]  [ Faster  ]                │
│  [Thunder][Ripple][ Pulse ]              │
│  Show Debug                              │
│                                          │
│  Min  ▬▬▬▬◉▬▬▬  [10]                     │   MANUAL TAB
│  Max  ▬▬▬▬▬▬▬◉  [90]                     │
│  Speed ▬◉▬▬▬▬▬   [20]                     │
│  ┌────────┬────────┬────────┐            │
│  │ Pulse  │ Ripple │ Surge  │            │
│  │Breathe │Thunder │ Tick   │            │
│  │ Tease  │ Edge   │ Rush   │            │
│  │ Throb  │ Bounce │ Climax │            │
│  └────────┴────────┴────────┘            │
│  "Pattern: Pulse"                        │
│                                          │
├──────────────────────────────────────────┤
│ [    START   ]   [    STOP    ]          │   Manual tab only
└──────────────────────────────────────────┘
```

- **Float button** (⧉, top right, shown once connected) drops the app into a **Picture-in-Picture** window with a control bar. See [Picture-in-Picture](#picture-in-picture).
- **Settings cog** (top right) opens Settings directly. On Settings there's a **← Home** button, which goes straight back to this screen.
- **Voice-tab shortcut buttons** (Begin/Stop, Slower/Faster, Thunder/Ripple/Pulse) all route through `Dispatch.dispatch(label)` — same code path as the voice match — so taps and voice produce identical device state.
- **Recording management** (Re-record voice samples, Clear all recordings) lives in Settings, not on the Voice tab.

## Manual tab details

| Slider | Effect |
| --- | --- |
| Min / Max | Stroke range, bounded to the configured `[globalMinStroke, globalMaxStroke]` band. Each move pushes a `PUT /slide` to the device. |
| Speed | Design-space velocity (0–100). Scaled by `globalMaxSpeed` before being sent — slider 100 = device velocity = `globalMaxSpeed`. |

### Pattern grid (12 envelopes)

Every button runs a time-based velocity envelope through `PatternEngine`. STOP, any other pattern, or any preset taps cancel the running one before starting.

| Button | Envelope | Default range | Voice? |
| --- | --- | --- | --- |
| Pulse | Lub (0.85, 150 ms) → gap → dub (0.7, 150 ms) → rest 1.5 s | Configured band | ✓ `pulse` |
| Ripple | Smooth sine 0 → 1 → 0, 8 s cycle | Configured band | ✓ `ripple` |
| Surge | 24-step linear ramp to peak, 4 s hold, 3 s rest | Configured band | — |
| Breathe | Sine confined to 0.10..0.60, 6 s cycle (never silent / never peak) | Configured band | — |
| Thunder | Random walk envelope 0..1 for 0.4..1.6 s, repeat | Configured band | ✓ `thunder` |
| Tick | Steady metronome 0.75 / 200 ms ↔ 0 / 200 ms | 40–60% (narrow mid) | — |
| Tease | Sparse light touches with 2–3 s silences | 0–50% (lower half) | — |
| Edge | 5 s ramp → 0.95 hold for 6 s → drop 4 s | 70–90% (top) | — |
| Rush | Aggressive constant 0.95 (sliced in 500 ms chunks) | 0–100% (full) | — |
| Throb | Heavy pulses 0.90 / 400 ms ↔ 0.10 / 700 ms | 40–70% | — |
| Bounce | Sine 0.40..0.90, 2 s cycle | 20–80% | — |
| Climax | Stairstep 0.30→0.50→0.70→0.85→1.0 (8 s peak hold) → reset | 30–90% | — |

Three envelopes are voice-addressable; the rest are tap-only on this tab. `Pulse` / `Ripple` / `Thunder` are renames of the old `heartbeat` / `wave` / `storm` (chosen for MFCC distinctness on Owen's voice); `Tick` is the old `pulse` metronome, renamed to free the `pulse` name. `Surge` and `Breathe` were dropped from the voice set entirely but kept here as tap-only.

All envelopes scale to the caller's intensity, which is `globalMaxSpeed` for both voice and Manual taps. So `Pulse` at `globalMaxSpeed=30` peaks at `0.85 × 30 ≈ 26`.

## Listening modes

Pick in **Settings → Listening mode**.

- **Tap mic** *(default)*. Tap the mic button to start continuous VAD-driven listening; tap again to stop. Same underlying loop as always-on, just gated by an explicit tap.
- **Always listening**. The VAD loop runs whenever the screen is visible and connected. Hands-free.

Both modes use a **post-VAD wait** (~1.8 s) before snapshotting the rolling buffer, so the full word is in the 2.5 s window — without this, the snapshot would be mostly silence followed by the first ~120 ms of speech, and matches would land on whichever template happened to be closest by accident.

## Picture-in-Picture

Float the app into a small always-on-top window so you can keep controlling the device while using another app (browser, video, anything).

**How to enter:**

- Tap the **⧉ float button** in the header (appears once connected), or
- Press **Home / Recents while the device is running** — the app auto-floats so Stop stays reachable. (If nothing is moving, Home just backgrounds the app normally.)

**Controls in the window.** A PiP window can't take touches on the app's own views, so the controls are the system **RemoteAction** bar (icon buttons in the window's control overlay). Android caps that bar at **3 actions on most phones**, though some allow 5. So:

| Phone allows | Bar shows |
| --- | --- |
| 5 actions | ⏪ Slower · ⏩ Faster · ⏹ **Stop** · ▽ Deeper · △ Shallow |
| 3 actions | ⏪ Slower · ⏹ **Stop** · ⏩ Faster |

On a 3-slot phone, **Stop is always present**; Deeper / Shallow are reached by tapping the window to expand back to the full app. The window itself shows a clean readout — `▶ <speed>%` (green) while running, `■ Stopped` (red) otherwise.

Each button routes through the same `Dispatch.dispatch(label)` path as voice and the in-app tap shortcuts, so caps, the faster/slower glide, and stop-cancels-pattern all behave identically. A PiP tap also notes activity (pushing back the idle auto-stop) and plays the ack beep, since the icons are silent. Implemented in [`ui/PipController.kt`](app/src/main/java/com/handyvoice/v2/ui/PipController.kt); the activity declares `supportsPictureInPicture` + the PiP `configChanges` in the manifest.

## Safety caps

- **Global max speed** *(default 30%)*. Every speed sent to the device is scaled by this percentage. `Dispatch.dispatch()` applies it before `setSpeed`; pattern envelopes scale their peak by it; "faster" / "slower" deltas are clamped to `[0, globalMaxSpeed]` so repeated taps can't sneak past the cap, and glide to the clamped target via `rampSpeed` rather than jumping. Configurable in Settings.
- **Global stroke band** *(default 0–100)*. Hard floor and ceiling on stroke position. Manual sliders are physically bounded to this band; pattern range fields are squashed into it. Configurable in Settings.
- **Invert stroke**. Toggle in Settings flips user-coord 0 to device-coord 100 (and vice versa) at the API client boundary. All callers think in user coords; the flip happens once in `setStrokeRange` and `parkAtRetracted`.
- **Connect-time park**. After a successful connect, the client switches to HDSP, sends a single `xpt` command to position 0 (or 100 inverted, clamped by the global band), and switches back to HAMP. Slow controlled slide instead of a stroke.
- **Stop winds down**. Every stop (voice, tap, PiP, idle auto-stop, Manual STOP) eases velocity to 0 over a fixed ~1.5 s via `rampStop` before `hamp/stop`, so a stop is a controlled ease-off rather than a slam.

## Layout (file inventory)

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/handyvoice/v2/HandyVoiceApp.kt` | `Application` subclass. Future place for DI. |
| `app/src/main/java/com/handyvoice/v2/api/HandyV2ApiClient.kt` | Handy REST v2 client. `connect / start / stop / setSpeed / setStrokeRange / hdspXpt`. Maintains StateFlows for UI binding and a ring buffer of debug log lines. Owns the connect-time park and the invert + global-band transforms. |
| `app/src/main/java/com/handyvoice/v2/audio/AudioCapture.kt` | Thin `AudioRecord` wrapper using `AudioSource.MIC` (not `VOICE_RECOGNITION` — see TROUBLESHOOTING for why). `readWindow(seconds, onPeak)` for fixed captures with live peak callback, `stream(...)` for the always-on loop. |
| `app/src/main/java/com/handyvoice/v2/audio/Vad.kt` | Energy + ZCR voice activity detector. Used by the always-on / tap-mic loop. |
| `app/src/main/java/com/handyvoice/v2/voice/Mfcc.kt` | Hand-rolled MFCC + cepstral mean normalization: pre-emphasis, Hamming window, radix-2 FFT, mel filterbank, log, DCT, then subtract per-coefficient mean across the utterance. |
| `app/src/main/java/com/handyvoice/v2/voice/Dtw.kt` | Sakoe-Chiba banded DTW with path-length normalisation. |
| `app/src/main/java/com/handyvoice/v2/voice/TemplateEngine.kt` | Engine interface: `addTake / match / labels / clearAll`. |
| `app/src/main/java/com/handyvoice/v2/voice/MfccDtwEngine.kt` | Current implementation. Holds per-label `List<MFCC>` in memory; `match()` returns `(label, score, runnerUp)`. |
| `app/src/main/java/com/handyvoice/v2/voice/TemplateStore.kt` | JSON persistence under `filesDir/templates/<label>/take_N.json`. Schema version 3 (post-CMN); older takes auto-filter on load. |
| `app/src/main/java/com/handyvoice/v2/voice/TfliteEmbeddingEngine.kt` | Phase-3 stub for a Resnet50 embedding + cosine-similarity engine. |
| `app/src/main/java/com/handyvoice/v2/dispatch/VoiceAction.kt` | Sealed class: `Stop / SetSpeed / AdjustSpeed / AdjustDepth / SetRange / Preset / Pattern / RandomPattern`. `Stop` eases to 0 before halting. |
| `app/src/main/java/com/handyvoice/v2/dispatch/Dispatch.kt` | `LABELS`, `PROMPTS`, `TABLE`, the reserved `NOISE_LABEL` reject class, and a suspending `dispatch(label, api, ..., patternEngine, humanizeEngine)`. Routes regular motion through the humanize engine when it's enabled. |
| `app/src/main/java/com/handyvoice/v2/dispatch/PatternEngine.kt` | 12-pattern looping envelope engine. Ported from `homeassistant/.../patterns.py`. |
| `app/src/main/java/com/handyvoice/v2/dispatch/HumanizeEngine.kt` | Organic drift for regular (non-pattern) motion: modulates speed + stroke depth around the base while running. Strength-scaled (0 = off). Sibling to `PatternEngine`. |
| `app/src/main/java/com/handyvoice/v2/data/PreferencesManager.kt` | DataStore prefs: connection key, listening mode, global max speed, humanize strength, global stroke band, invert toggle, match threshold, onboarded flag. |
| `app/src/main/java/com/handyvoice/v2/ui/TrainingActivity.kt` | First-run onboarding + re-recording. Auto-advance session, per-label cross-validation gate (offers inline re-record of an outlier take). Build templates sets `onboarded=true` and returns to Control. Ack tone on every saved take. |
| `app/src/main/java/com/handyvoice/v2/ui/ControlActivity.kt` | Single home screen with Voice / Manual tabs. Voice tab: mic toggle, peak meter, "Heard:" readout, tap-shortcut buttons for every voice label (Begin/Stop, Slower/Faster, Thunder/Ripple/Pulse), debug log. Manual tab: stroke + speed sliders and all 12 pattern buttons. START / STOP row below (Manual tab only). Owns the `PatternEngine`, the `HumanizeEngine` (strength from prefs, passed to every dispatch), and the `PipController`. Drops matches on the `__noise__` reject class before dispatching. Ack tone on voice match. Floats into PiP on the header button or on Home-while-running. |
| `app/src/main/java/com/handyvoice/v2/ui/PipController.kt` | Picture-in-Picture: feature detection, building the system RemoteAction control bar (5 controls where the phone allows, else the Slower/Stop/Faster trio with Stop always present), registering the not-exported broadcast receiver the buttons fire, and entering PiP. Each control is a `Dispatch` label, routed back through the same dispatch path as voice. |
| `app/src/main/java/com/handyvoice/v2/ui/SettingsActivity.kt` | Settings form. ← Home button in the header. Humanize-strength slider under Motion limits. Re-record voice samples (opens Training, preserves takes), Record device sound to ignore (trains just the `__noise__` reject class), and Clear all recordings (wipes with confirmation, then opens Training) live here. |
| `app/src/test/java/com/handyvoice/v2/voice/*Test.kt` | JVM unit tests covering MFCC, DTW, and engine behaviour. |
| `app/src/main/res/layout/activity_training.xml` | Training UI: prompt text, take-progress dots (3), peak bar, record button. |
| `app/src/main/res/layout/activity_control.xml` | Combined voice + manual UI with TabLayout. `startStopRow` (Manual tab only) holds the big START/STOP pair. |
| `app/src/main/res/layout/activity_settings.xml` | Settings form with the ← Home header button. |

## Differences from `../android/`

| Aspect | `../android/` (v1) | `android-v2/` |
| --- | --- | --- |
| Voice engine | Vosk offline ASR + JSGF grammar + fuzzy match | MFCC + CMN + banded DTW on user's own recordings |
| Engine training | Pretrained Vosk model (~40 MB download) | User records 3 takes per command (~2 min onboarding) |
| Vocabulary | Defined by JSGF grammar + parsing rules | Fixed `Dispatch.LABELS` list, one template-set per label |
| False fires | "surrounded the top" → parses as "slow top" | Below-threshold or low-margin match → silently ignored |
| Adding a command | Grammar phrase + parser + dispatch + 3 exhaustive `when` updates | One `DISPATCH` row + record + retrain |
| Manual remote | Separate activity | Tab on the main screen, shared connection state |
| Patterns | Static range+speed combos | 12 time-based envelopes via `PatternEngine` |
| Storage | None beyond Vosk model cache | `filesDir/templates/<label>/take_N.json` (format 3) |
| Battery in always-on | Vosk runs continuously, noticeable drain | MFCC+DTW gated by VAD; cheap when silent |
| Speaker-locked | No | Yes (by construction; lookalike voices may still match) |

## Roadmap

- **TFLite embedding engine** behind `TfliteEmbeddingEngine`. Extract EfficientWord-Net's Resnet50_Arc_loss TFLite model, write the Kotlin audio-preprocessing path, persist average embeddings per label. Drop-in replacement; training UX unchanged.
- **Wake-word label**. Recording one extra template called `wake` lets always-on mode use the matcher itself as the wake stage, replacing VAD. Closer to vega's design once matching proves reliable.
- **Haptic feedback** alongside the ack tone, for users running the device with the phone in their hand.

## License

MIT. Independent accessibility project; not affiliated with Ohdoki / The Handy.
