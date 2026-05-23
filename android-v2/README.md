# Handy Voice V2 (Android)

An Android client for The Handy that recognises voice commands by matching against **prerecorded samples of the user's own voice** rather than running speech-to-text. Lives alongside the Vosk-based `../android/` app; both can be installed at once.

## Status

🟢 **Phase 1 + 2 complete on-device.** Verified working on the Android emulator and real devices. Voice training, MFCC + CMN + banded DTW matching, the full HA-derived pattern engine (12 envelopes), tab-based UI, and an HDSP-driven connect-time park are all live.

## Why a second Android app

`../android/` uses Vosk offline ASR with a constrained JSGF grammar. That removes the worst Android `SpeechRecognizer` failures, but every utterance still has to be:

1. converted into a sequence of English-ish words by an acoustic model trained on a broad population, then
2. parsed into a command by fuzzy-matching against the dispatch vocabulary.

For users with non-standard speech, step (1) is the brittle part. Both steps can fail in ways that take work to diagnose.

`android-v2/` shortcuts both steps. It records 3 takes of each command in the user's own voice on first launch, builds an MFCC template per take, and at runtime asks: *"does the audio I just captured look like the samples Owen recorded for `faster`?"* Nothing ever has to map "what was said" to an English word. The matcher is speaker-locked by construction: it knows exactly one speaker's voice and exactly nine labels, and only fires when the incoming audio resembles one of them.

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
       Dispatch.dispatch(label, ..., patternEngine) ─► HandyV2ApiClient ─► REST v2
                                  │
                                  └─► VoiceAction.Pattern → PatternEngine.start(name, peak)
                                                              loop (envelope, duration) → api.setSpeed
```

Each piece is documented in detail in [docs/](docs/):

- **[ARCHITECTURE.md](docs/ARCHITECTURE.md)** — process model, threading, lifecycle, state, persistence, things deliberately missing.
- **[VOICE_ENGINE.md](docs/VOICE_ENGINE.md)** — every MFCC and DTW parameter explained, why these choices, how to tune the threshold, the margin gate, swappable-engine contract.
- **[COMMANDS.md](docs/COMMANDS.md)** — vocabulary, dispatch table, pattern envelopes, how to add a new command end-to-end.
- **[TRAINING.md](docs/TRAINING.md)** — user-facing recording guide. Auto-advance, cross-validation, verification pass, reading match scores.
- **[TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)** — build and runtime issues with concrete fixes (including the emulator microphone gotcha).

## Quick start

### Build

JDK 17+ required to launch the Gradle daemon (Android Studio's bundled JBR is fine). The wrapper downloads Gradle 9.4.1 on first run, and Gradle then auto-provisions a JDK 21 toolchain via Foojay for the build itself.

```powershell
# From this directory:
.\gradlew.bat :app:assembleDebug
```

The signed debug APK lands at `app/build/intermediates/apk/debug/app-debug.apk`. Or open `android-v2/` in Android Studio and let it sync.

### First run on device

1. Install the debug APK (`adb install -r app/build/intermediates/apk/debug/app-debug.apk` with USB debugging on, or drag-drop).
2. Launch **Handy Voice V2**. On first run (`onboarded` flag is false) the app routes straight to **Training**.
3. Training is a 9-card carousel. **Tap Record once** — the app records 3 takes of each label automatically with a brief "Get ready…" pre-roll, a 2.5 s "Listening — speak now" capture, and an ack beep on each saved take. Encouragement toasts ("Nice! Next: stop") fire at label transitions. Skip / Prev / Next stay available between takes.
4. After the last take of each label, a **cross-validation gate** scores the takes against each other; if one looks like an outlier you'll get a "Re-record take N?" dialog.
5. Tap **Build templates** to finish. Before onboarded is flipped, a **verification pass** runs: you'll be asked to say each label once, and if the engine returns the wrong label you'll get a "Re-record samples for X?" dialog with inline re-record + retry.
6. Back on the main screen, enter your Handy connection key and tap **Connect**. The device performs a slow HDSP slide to position 0 (or `globalStrokeMin` if you've set one) on a successful connect.
7. Use the **Voice / Manual** tabs. Voice tab: tap MIC to start continuous VAD-driven listening, tap again to stop. Live peak meter under "Heard:" confirms the mic is hot. Manual tab: stroke / speed sliders + 12 pattern buttons. START / STOP is always visible below the tabs.

### Tests

JVM unit tests for the audio math run without the emulator:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

These cover MFCC determinism + shape + frequency discrimination, DTW symmetry + length-invariance + sanity, and engine label routing. 14 tests, ~30 ms total.

## Vocabulary

Mirrors `vega/commands.py`'s DISPATCH closely (one rename: `start` → `begin` because the former collides phonetically with `stop` in MFCC space for slower speech).

| Label | Action |
| --- | --- |
| `begin` | Set speed to 25 design points (scaled by global max). |
| `stop` | Stop the device + cancel any running pattern. |
| `faster` | Adjust speed by +15 design points, clamped to `globalMaxSpeed`. |
| `slower` | Adjust speed by -15 design points, clamped to `[0, globalMaxSpeed]`. |
| `heartbeat` | Run the **heartbeat** envelope (lub-dub-rest). |
| `wave` | Run the **wave** envelope (sine 0..1..0, 8 s cycle). |
| `surge` | Run the **surge** envelope (24 s ramp + hold + rest). |
| `breathe` | Run the **breathe** envelope (gentle 0.10..0.60 sine). |
| `storm` | Run the **storm** envelope (random walk). |

See [docs/COMMANDS.md](docs/COMMANDS.md) for envelope definitions and the full pattern engine.

## UI layout

One activity (`ControlActivity`) hosts both control surfaces via a `TabLayout`:

```
┌──────────────────────────────────────────┐
│ Handy Voice V2          ● Connected  ⚙   │   header (status + Settings cog)
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
│  [ Slower ]  [ Faster ]                  │
│  [ Re-record ]  [ Clear all ]            │
│  Show Debug                              │
│                                          │
│  Min  ▬▬▬▬◉▬▬▬  [10]                     │   MANUAL TAB
│  Max  ▬▬▬▬▬▬▬◉  [90]                     │
│  Speed ▬◉▬▬▬▬▬   [20]                     │
│  ┌────────┬────────┬────────┐            │
│  │Heartbt │ Wave   │ Surge  │            │
│  │Breathe │ Storm  │ Pulse  │            │
│  │ Tease  │ Edge   │ Rush   │            │
│  │ Throb  │ Bounce │ Climax │            │
│  └────────┴────────┴────────┘            │
│  "Pattern: Heartbeat"                    │
│                                          │
├──────────────────────────────────────────┤
│ [    START   ]   [    STOP    ]          │   always visible below the tabs
└──────────────────────────────────────────┘
```

- **Settings cog** (top right of every screen) opens the overflow menu. On Settings there's a **← Home** button instead, which goes straight back to this screen.
- **Re-record** opens Training without wiping anything. **Clear all** wipes every template (with confirmation) then routes to Training.

## Manual tab details

| Slider | Effect |
| --- | --- |
| Min / Max | Stroke range, bounded to the configured `[globalMinStroke, globalMaxStroke]` band. Each move pushes a `PUT /slide` to the device. |
| Speed | Design-space velocity (0–100). Scaled by `globalMaxSpeed` before being sent — slider 100 = device velocity = `globalMaxSpeed`. |

### Pattern grid (12 envelopes)

Every button runs a time-based velocity envelope through `PatternEngine`. STOP, any other pattern, or any preset taps cancel the running one before starting.

| Button | Envelope | Default range |
| --- | --- | --- |
| Heartbeat | Lub (0.85, 150 ms) → gap → dub (0.7, 150 ms) → rest 1.5 s | Configured band |
| Wave | Smooth sine 0 → 1 → 0, 8 s cycle | Configured band |
| Surge | 24-step linear ramp to peak, 4 s hold, 3 s rest | Configured band |
| Breathe | Sine confined to 0.10..0.60, 6 s cycle (never silent / never peak) | Configured band |
| Storm | Random walk envelope 0..1 for 0.4..1.6 s, repeat | Configured band |
| Pulse | Steady metronome 0.75 / 200 ms ↔ 0 / 200 ms | 40–60% (narrow mid) |
| Tease | Sparse light touches with 2–3 s silences | 0–50% (lower half) |
| Edge | 5 s ramp → 0.95 hold for 6 s → drop 4 s | 70–90% (top) |
| Rush | Aggressive constant 0.95 (sliced in 500 ms chunks) | 0–100% (full) |
| Throb | Heavy pulses 0.90 / 400 ms ↔ 0.10 / 700 ms | 40–70% |
| Bounce | Sine 0.40..0.90, 2 s cycle | 20–80% |
| Climax | Stairstep 0.30→0.50→0.70→0.85→1.0 (8 s peak hold) → reset | 30–90% |

All envelopes scale to the caller's intensity, which is `globalMaxSpeed` for both voice and Manual taps. So `Heartbeat` at `globalMaxSpeed=30` peaks at `0.85 × 30 ≈ 26`.

## Listening modes

Pick in **Settings → Listening mode**.

- **Tap mic** *(default)*. Tap the mic button to start continuous VAD-driven listening; tap again to stop. Same underlying loop as always-on, just gated by an explicit tap.
- **Always listening**. The VAD loop runs whenever the screen is visible and connected. Hands-free.

Both modes use a **post-VAD wait** (~1.8 s) before snapshotting the rolling buffer, so the full word is in the 2.5 s window — without this, the snapshot would be mostly silence followed by the first ~120 ms of speech, and matches would land on whichever template happened to be closest by accident.

## Safety caps

- **Global max speed** *(default 30%)*. Every speed sent to the device is scaled by this percentage. `Dispatch.dispatch()` applies it before `setSpeed`; pattern envelopes scale their peak by it; "faster" / "slower" deltas are clamped to `[0, globalMaxSpeed]` so repeated taps can't sneak past the cap. Configurable in Settings.
- **Global stroke band** *(default 0–100)*. Hard floor and ceiling on stroke position. Manual sliders are physically bounded to this band; pattern range fields are squashed into it. Configurable in Settings.
- **Invert stroke**. Toggle in Settings flips user-coord 0 to device-coord 100 (and vice versa) at the API client boundary. All callers think in user coords; the flip happens once in `setStrokeRange` and `parkAtRetracted`.
- **Connect-time park**. After a successful connect, the client switches to HDSP, sends a single `xpt` command to position 0 (or 100 inverted, clamped by the global band), and switches back to HAMP. Slow controlled slide instead of a stroke.

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
| `app/src/main/java/com/handyvoice/v2/dispatch/VoiceAction.kt` | Sealed class: `Stop / SetSpeed / AdjustSpeed / SetRange / Preset / Pattern`. |
| `app/src/main/java/com/handyvoice/v2/dispatch/Dispatch.kt` | `LABELS`, `PROMPTS`, `TABLE`, and a suspending `dispatch(label, api, ..., patternEngine)`. |
| `app/src/main/java/com/handyvoice/v2/dispatch/PatternEngine.kt` | 12-pattern looping envelope engine. Ported from `homeassistant/.../patterns.py`. |
| `app/src/main/java/com/handyvoice/v2/data/PreferencesManager.kt` | DataStore prefs: connection key, listening mode, global max speed, global stroke band, invert toggle, match threshold, onboarded flag. |
| `app/src/main/java/com/handyvoice/v2/ui/MainMenu.kt` | `Activity.showMainMenu(anchor)` extension. Overflow menu offers Voice + Settings. |
| `app/src/main/java/com/handyvoice/v2/ui/TrainingActivity.kt` | First-run onboarding + re-recording + verification. Auto-advance session, cross-validation, post-Build verify loop. Ack tones on save + verify pass. |
| `app/src/main/java/com/handyvoice/v2/ui/ControlActivity.kt` | Single home screen with Voice / Manual tabs. Hosts mic toggle, peak meter, debug log, all 12 pattern buttons, stroke + speed sliders, and the always-visible START / STOP row. Owns the `PatternEngine` for the activity. Ack tone on voice match. |
| `app/src/main/java/com/handyvoice/v2/ui/SettingsActivity.kt` | Settings form. ← Home button in the header. |
| `app/src/test/java/com/handyvoice/v2/voice/*Test.kt` | JVM unit tests covering MFCC, DTW, and engine behaviour. |
| `app/src/main/res/layout/activity_training.xml` | Training UI: prompt text, take-progress dots (3), peak bar, record button. |
| `app/src/main/res/layout/activity_control.xml` | Combined voice + manual UI with TabLayout and shared START/STOP row. |
| `app/src/main/res/layout/activity_settings.xml` | Settings form with the ← Home header button. |

## Differences from `../android/`

| Aspect | `../android/` (v1) | `android-v2/` |
| --- | --- | --- |
| Voice engine | Vosk offline ASR + JSGF grammar + fuzzy match | MFCC + CMN + banded DTW on user's own recordings |
| Engine training | Pretrained Vosk model (~40 MB download) | User records 3 takes per command (~3 min onboarding + verify) |
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
