# Handy Voice V2 (Android)

An Android client for The Handy that recognizes voice commands by matching against **prerecorded samples of the user's own voice** rather than running speech-to-text. Lives alongside the Vosk-based `../android/` app; both can be installed at once.

## Status

🟢 **Phase 1 scaffold complete.** Buildable, JVM-unit-tested. Needs on-device validation by recording real templates and verifying matches.

## Why a second Android app

`../android/` uses Vosk offline ASR with a constrained JSGF grammar. That removes the worst Android `SpeechRecognizer` failures, but every utterance still has to be:

1. converted into a sequence of English-ish words by an acoustic model trained on a broad population, then
2. parsed into a command by fuzzy-matching against the dispatch vocabulary.

For users with non-standard speech, step (1) is the brittle part. Both steps can fail in ways that take work to diagnose.

`android-v2/` shortcuts both steps. It records 3 takes of each command in the user's own voice on first launch, builds an MFCC template per take, and at runtime asks: *"does the audio I just captured look like the samples Owen recorded for `faster`?"* Nothing ever has to map "what was said" to an English word. The matcher is speaker-locked by construction: it knows exactly one speaker's voice and exactly nine labels, and only fires when the incoming audio resembles one of them.

Inspired by [`../../vega/`](../../vega), which uses the same pattern-matching approach on a Raspberry Pi with EfficientWord-Net.

## Architecture (one-line per box)

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

Each piece is documented in detail in [docs/](docs/):

- **[ARCHITECTURE.md](docs/ARCHITECTURE.md)** — process model, threading, lifecycle, state, persistence, things deliberately missing.
- **[VOICE_ENGINE.md](docs/VOICE_ENGINE.md)** — every MFCC and DTW parameter explained, why these choices, how to tune the threshold, swappable-engine contract for the future TFLite path.
- **[COMMANDS.md](docs/COMMANDS.md)** — Phase 1 vocabulary, dispatch table, how to add a new command end-to-end.
- **[TRAINING.md](docs/TRAINING.md)** — user-facing recording guide. How to record well, when to retrain, reading match scores.
- **[TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)** — build and runtime issues with concrete fixes.

## Quick start

### Build

JDK 17+ required to launch the Gradle daemon (Android Studio's bundled JBR is fine). The wrapper downloads Gradle 9.4.1 on first run, and Gradle then auto-provisions a JDK 21 toolchain via Foojay (pinned in `gradle/gradle-daemon-jvm.properties`) for the build itself.

```powershell
# From this directory:
.\gradlew.bat :app:assembleDebug
```

Or open `android-v2/` in Android Studio and let it sync.

### First run on device

1. Install the debug APK.
2. Launch **Handy Voice V2**. Because no templates exist yet, the app routes you straight to **Training**.
3. Training is a 9-card carousel (one per command). On each card: tap **Record** → speak the displayed word during the 1.5 s capture → it auto-stops → tap **Play** to verify → repeat for take 2 and 3. **Skip** advances if you want to come back later; **Prev / Next** move freely.
4. After at least one take per command, the **Build templates** button at the bottom lights up. Tap it. You're returned to the main screen.
5. Enter your Handy connection key, tap **Connect**.
6. The mic button is now live: tap it, speak a command, the device responds. The matched label and score are shown next to the button.
7. *Optional*: **Settings → Always listening**. Once you trust your templates, this gives true hands-free.

Full training guide with screenshots-of-words in [docs/TRAINING.md](docs/TRAINING.md).

### Tests

JVM unit tests for the audio math run without the emulator:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

These cover MFCC determinism + shape + frequency discrimination, DTW symmetry + length-invariance + sanity, and engine label routing. 14 tests, ~30 ms total.

## Phase 1 vocabulary

Matches `vega/commands.py`'s DISPATCH so the two share one mental model.

| Label | Action |
| --- | --- |
| `start` | Set speed to 25 (design space; scaled by global max). |
| `stop` | Stop the device. |
| `faster` | Adjust speed by +15 design points. |
| `slower` | Adjust speed by -15 design points. |
| `heartbeat` | Slow speed, top range (`50-90`). Pattern stand-in. |
| `wave` | Medium speed, full range (`0-100`). Pattern stand-in. |
| `surge` | Fast speed, full range (`0-100`). Pattern stand-in. |
| `breathe` | Slow speed, full range (`0-100`). Pattern stand-in. |
| `storm` | Fast speed, top range (`50-90`). Pattern stand-in. |

The five "pattern" labels currently each map to a distinct fixed `(range, speed)` combo. Vega's full pattern engine (looping envelopes for heartbeat / wave / surge / breathe / storm) is a Phase 2 port from `vega/devices/handy.py`. The training vocabulary does not change when patterns land. See [docs/COMMANDS.md](docs/COMMANDS.md).

## Manual controls

Voice is the primary surface, but the app also ships a full tap-driven remote at parity with `../android/` (v1). Reach it from the gear icon in the header → **Manual controls**.

The manual screen exposes six sliders and a 4×3 preset grid:

| Slider | Effect |
| --- | --- |
| Min / Max | Stroke range. Each move pushes a `PUT /slide` to the device. |
| Speed | Design-space velocity. Scaled by `Global max speed` and clamped by `Spd.Lim` before being sent. |
| G.Min / G.Max | Persisted stroke-position band. Manual preset taps squash the preset's 0–100 position into `[G.Min, G.Max]`. Persists to prefs. |
| Spd.Lim | Persisted hard ceiling on the device velocity (applied after the `Global max speed` design-space scaling). Persists to prefs. |

Preset grid: 3 speeds × 4 positions. `slow=25, medium=50, fast=80` design speed; `top=50-90, mid=30-70, bot=10-50, full=0-100` position band (then squashed into G.Min..G.Max). Tapping a preset sends `setSlide → start → setSpeed` in that order (per `HANDY_API_NOTES`).

All three caps (`G.Min`, `G.Max`, `Spd.Lim`) are honoured by the voice path too: `Dispatch.dispatch()` takes them as parameters and applies the same squashing for `Preset` actions and the same `coerceIn(0, speedLimit)` clamp for `SetSpeed` / `AdjustSpeed`. ControlActivity reads the persisted values on each resume.

## Listening modes

Pick in **Settings → Listening mode**.

- **Tap mic to listen** *(default)*. Single 1.5 s capture per button press. Most predictable; never false-fires.
- **Always listening**. An energy + zero-crossing-rate VAD continuously watches the mic and opens a 1.5 s window when speech is detected. The matcher scores that window; a 1.5 s cooldown after each dispatch prevents the same utterance triggering twice.

Recommended path: start in button mode. After a session or two, if every match feels right, flip to always-on.

## Safety caps

- **Global max speed** *(default 30%)*. Every speed value sent to the device is scaled by this percentage. A voice command of `faster` (`+15` design points) becomes `+4.5` device points. The cap is enforced in `Dispatch.dispatch()`, not in prompts or UI; even a misheard command cannot exceed it. Configurable in Settings.
- **No stroke-range cap yet.** Phase 2 work to port vega's `GLOBAL_MAX_STROKE_PERCENT`. The Phase 1 preset stand-ins (`heartbeat` etc.) all use safe fixed ranges.

## Layout (file inventory)

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/handyvoice/v2/HandyVoiceApp.kt` | `Application` subclass. Holds nothing today; future place for DI. |
| `app/src/main/java/com/handyvoice/v2/api/HandyV2ApiClient.kt` | Handy REST v2 client. Retrofit + OkHttp. `connect / start / stop / setSpeed / setStrokeRange / adjustSpeed`. Maintains StateFlows for UI binding and a ring buffer of debug log lines. |
| `app/src/main/java/com/handyvoice/v2/audio/AudioCapture.kt` | Thin `AudioRecord` wrapper. Two methods: `readWindow(seconds)` for fixed captures, `stream(...)` for the always-on loop. Uses `MediaRecorder.AudioSource.VOICE_RECOGNITION`. |
| `app/src/main/java/com/handyvoice/v2/audio/Vad.kt` | Energy + ZCR voice activity detector. Used only by always-on mode. |
| `app/src/main/java/com/handyvoice/v2/voice/Mfcc.kt` | Hand-rolled MFCC: pre-emphasis, Hamming window, radix-2 FFT, mel filterbank, log, DCT. 13 coefficients, 25 ms / 10 ms framing. |
| `app/src/main/java/com/handyvoice/v2/voice/Dtw.kt` | Sakoe-Chiba banded DTW with path-length normalization. |
| `app/src/main/java/com/handyvoice/v2/voice/TemplateEngine.kt` | Engine interface: `addTake / match / labels / clearAll`. Two implementations behind this. |
| `app/src/main/java/com/handyvoice/v2/voice/MfccDtwEngine.kt` | Phase 1 implementation. Holds per-label `List<MFCC>` in memory. |
| `app/src/main/java/com/handyvoice/v2/voice/TemplateStore.kt` | JSON persistence under `filesDir/templates/<label>/take_N.json`. |
| `app/src/main/java/com/handyvoice/v2/voice/TfliteEmbeddingEngine.kt` | Phase 3 stub. Same interface, will use Resnet50 embedding + cosine similarity à la EfficientWord-Net. |
| `app/src/main/java/com/handyvoice/v2/dispatch/VoiceAction.kt` | Sealed class: `Stop / SetSpeed / AdjustSpeed / SetRange / Preset`. |
| `app/src/main/java/com/handyvoice/v2/dispatch/Dispatch.kt` | Label → action table + the suspending `dispatch(label, api)` that performs the API call sequence. |
| `app/src/main/java/com/handyvoice/v2/data/PreferencesManager.kt` | DataStore prefs: connection key, listening mode, global max speed, match threshold, onboarded flag. |
| `app/src/main/java/com/handyvoice/v2/ui/TrainingActivity.kt` | First-run onboarding. Card-per-command carousel. Save MFCCs after every take so a crash doesn't lose progress. |
| `app/src/main/java/com/handyvoice/v2/ui/ControlActivity.kt` | Main screen. Connection panel ↔ control panel, mic button or always-on indicator, manual fallback buttons, debug log. Routes to Training if no templates. Gear icon opens a popup menu (Manual controls / Settings). |
| `app/src/main/java/com/handyvoice/v2/ui/ManualActivity.kt` | Tap-driven remote with v1 parity: 6 sliders (Min/Max/Speed + G.Min/G.Max/Spd.Lim) and 4×3 preset grid. Uses its own `HandyV2ApiClient` instance against the saved connection key. |
| `app/src/main/java/com/handyvoice/v2/ui/SettingsActivity.kt` | Settings: connection key, listening mode, global max speed, threshold, retrain. |
| `app/src/test/java/com/handyvoice/v2/voice/*Test.kt` | JVM unit tests. 14 tests covering MFCC, DTW, and engine behavior. |
| `app/src/main/res/layout/activity_training.xml` | Training UI: prompt text, take-progress dots, record button, play/prev/next/skip, finish button. |
| `app/src/main/res/layout/activity_control.xml` | Control UI: header + status, setup panel (connection key), control panel (mic, manual buttons, debug). |
| `app/src/main/res/layout/activity_manual.xml` | Manual remote: 6 sliders + 4×3 preset grid + START/STOP. |
| `app/src/main/res/layout/activity_settings.xml` | Settings form. |

## Differences from `../android/`

| Aspect | `../android/` (v1) | `android-v2/` |
| --- | --- | --- |
| Voice engine | Vosk offline ASR + JSGF grammar + fuzzy match | MFCC + banded DTW on user's own recordings |
| Engine training | Pretrained Vosk model (~40 MB download) | User records 3 takes per command (~5 min onboarding) |
| Vocabulary | Defined by JSGF grammar + parsing rules | Fixed `Dispatch.LABELS` list, one template-trained per label |
| False fires | "surrounded the top" → parses as "slow top" | Below-threshold match → silently ignored |
| Adding a command | Grammar phrase + parser + dispatch + 3 exhaustive `when` updates | One `DISPATCH` row + record + retrain |
| Manual remote | Inline on main screen | Separate `ManualActivity` from gear menu (same 6 sliders + 4×3 preset grid) |
| Storage | None beyond Vosk model cache | `filesDir/templates/<label>/take_N.json` |
| Battery in always-on | Vosk runs continuously, ~XX% drain | MFCC+DTW gated by VAD; cheap when silent |
| Speaker-locked | No | Yes (by construction; lookalike voices may still match) |

## Phase 2 / 3 roadmap

- **Pattern engine port** from `vega/devices/handy.py`. The five pattern labels currently dispatch as static preset combos. Vega has looping envelope functions (heartbeat / wave / surge / breathe / storm) that modulate speed over time. Port the `_pattern_thread` loop and the per-pattern generators into a Kotlin coroutine; the `VoiceAction.Pattern(name)` shape is already ready.
- **TFLite embedding engine** behind `TfliteEmbeddingEngine`. Extract EfficientWord-Net's Resnet50_Arc_loss TFLite model from the vega install, write the Kotlin audio-preprocessing path, persist average embeddings per label. Drop-in replacement; training UX is unchanged.
- **Wake-word label**. Recording one extra template called `wake` lets always-on mode use the matcher itself as the wake stage, replacing VAD. Closer to vega's design once the matcher proves reliable.

## License

MIT. Independent accessibility project; not affiliated with Ohdoki / The Handy.
