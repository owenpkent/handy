# Architecture

How the pieces fit, what runs on which thread, what state lives where, and what is deliberately *not* implemented.

## Process and activity model

Single process, three activities, no foreground service. The mic is owned by exactly one component at any moment.

```
                    ┌───────────────────────────────┐
                    │  ControlActivity              │
launcher ──────────►│  - mic button / always-on     │
                    │  - dispatch into HandyApi     │
                    │  - debug log                  │
                    └──────────┬────────────────────┘
                               │  (first launch or "Re-train" tapped)
                               ▼
                    ┌───────────────────────────────┐
                    │  TrainingActivity             │
                    │  - 9 cards × 3 takes          │
                    │  - saves MFCCs after each take│
                    └───────────────────────────────┘

                    ┌───────────────────────────────┐
                    │  SettingsActivity             │
                    │  - prefs editor               │
                    │  - re-train entry             │
                    └───────────────────────────────┘
```

`ControlActivity` is the launcher. On `onCreate` it reads a snapshot of `PreferencesManager` and asks `TemplateStore.hasAnyTemplates()`. If no templates or `onboarded == false`, it launches `TrainingActivity` and returns. If templates exist, it loads them into `MfccDtwEngine`, restores the connection key, and (in the future) wires up the listening loop.

`TrainingActivity` is unidirectional. The user records, the takes hit disk, and the activity finishes back to `ControlActivity`. It does not need a network connection or a Handy device to operate.

`SettingsActivity` writes prefs and can invoke the "wipe templates and re-train" path. It finishes back to `ControlActivity`, which re-reads prefs on `onResume`.

## Mic ownership and the two listening modes

The Android `AudioRecord` API allows only one active recorder at a time. We respect that with a single owner per moment:

| Mode | Who owns mic | When |
| --- | --- | --- |
| Training | `TrainingActivity` | Only while the user holds the record card and a capture is in flight. Released after each 1.5 s window. |
| Button | `ControlActivity` | Only between the user tapping the mic button and the 1.5 s window ending. |
| Always-on | `ControlActivity` | Continuously while the activity is in `RESUMED`; released in `onPause`. |

`AudioCapture` does no caching of its own. Each call constructs an `AudioRecord`, records, and releases. This is wasteful in raw throughput, but the v1 Android codebase already documented "Don't destroy/recreate `AudioRecord` on each mic toggle" as a Vosk-specific warmup issue. We have not seen the same issue with `MediaRecorder.AudioSource.VOICE_RECOGNITION` on modern Android. If it appears, the mitigation is to keep `AudioCapture.stream(...)` running for both modes and switch its consumer via a flag.

## Threading

| Thread | Owner | Work |
| --- | --- | --- |
| Main (UI) | Activities | View updates, click handlers, navigation. Never blocks. |
| `Dispatchers.IO` | `lifecycleScope.launch(Dispatchers.IO)` | `AudioCapture` reads, `TemplateStore` reads/writes, `AudioTrack` playback, network through Retrofit's own threads. |
| `Dispatchers.Default` | One-off `withContext(Default)` in `ControlActivity.handleAudio` | MFCC + DTW. CPU-bound, no I/O. |
| Retrofit / OkHttp | OkHttp dispatcher pool | All HTTP. Retrofit's `suspend` functions resume on the calling coroutine. |

The matcher is invoked synchronously inside a single `withContext(Default)` block per utterance. For the Phase 1 vocabulary (9 labels × 3 takes), a single match takes a few hundred microseconds, so there is no benefit from parallelizing across labels.

In always-on mode, `capture.stream(...)` runs an unbounded loop on `Dispatchers.IO`. It is gated by the `alwaysOnJob?.isActive == true` check; cancelling `alwaysOnJob` (in `onPause` or when listening mode changes) lets the loop drain and `AudioRecord.release()` run.

## State

Three kinds of state, three different homes.

### Per-launch in-memory state (`ControlActivity` fields)

- `engine: MfccDtwEngine` — the loaded templates. Rebuilt from disk on every launch.
- `isCapturing: Boolean` — guards re-entry of the mic button.
- `alwaysOnJob: Job?` — the coroutine running the always-on listening loop.
- `globalMaxSpeed`, `threshold`, `listeningMode` — cached pref values, re-read on `onResume`.
- `log: MutableList<String>` — ring buffer of debug lines for the on-screen panel.

The `HandyV2ApiClient` itself holds device-side state as `StateFlow`s (`isRunning`, `currentSpeed`, `strokeMin`, `strokeMax`, `lastError`, `debugLog`). Those flows survive activity recreation as long as the client instance does (the activity recreates it on every `onCreate`, which is acceptable because the only persisted piece is the connection key).

### Persistent state (DataStore prefs)

`PreferencesManager` wraps Jetpack DataStore. One file, one set of keys:

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `connection_key` | String | `""` | Handy connection key. |
| `listening_mode` | String | `button` | One of `button`, `always_on`. |
| `global_max_speed` | Int | 30 | Hard speed cap (% of device max). |
| `match_threshold` | Float | 30.0 | Path-normalized DTW distance below which a match counts. |
| `onboarded` | Boolean | false | Set true after the user finishes Training. |

`PreferencesManager.snapshot()` returns a single `Snapshot` so a coroutine can read everything in one suspend without juggling individual Flows.

### Persistent state (template store)

`TemplateStore` writes plain JSON files under `applicationContext.filesDir/templates/`:

```
filesDir/
└── templates/
    ├── start/
    │   ├── take_1.json    # SerializedTake{format=1, label="start", mfcc=Array<DoubleArray>}
    │   ├── take_2.json
    │   └── take_3.json
    ├── stop/
    │   ├── take_1.json
    │   └── ...
    └── ...
```

Each file holds one `SerializedTake`:

```json
{
  "format": 1,
  "label": "start",
  "mfcc": [[...13 numbers...], [...13 numbers...], ...]
}
```

The `format` field exists so a future TFLite path (Phase 3) can store embeddings instead of MFCCs without breaking forward-compat. Reads check `format == 1` implicitly; mismatches silently skip the take.

File names use `take_N.json` where N is the next integer after the existing count. `TemplateStore.sanitize()` lowercases the label and replaces non-alphanumerics with underscores so labels never break the filesystem. Labels in `Dispatch.LABELS` are already clean, so sanitisation is a defence-in-depth measure.

## Lifecycle

`ControlActivity.onCreate`:

1. Inflate layout.
2. Initialise `PreferencesManager`, `TemplateStore`, `MfccDtwEngine`, `AudioCapture`, `Vad`.
3. Bind views and observers.
4. Launch the `bootstrap()` coroutine, which:
   1. Reads the prefs snapshot.
   2. If no templates / not onboarded → launches `TrainingActivity` and returns. No further work happens until the user comes back.
   3. Otherwise loads MFCCs from disk into the engine.
   4. Restores the connection key into the input field and (if non-blank) immediately calls `api.connect()`.

`ControlActivity.onResume`:

- Re-reads the prefs snapshot. If the listening mode changed (the user just visited Settings), re-applies it.

`ControlActivity.onPause`:

- Cancels `alwaysOnJob` if active. The capture loop drains and `AudioRecord.release()` runs.

`TrainingActivity.onCreate`:

1. Inflate layout.
2. Load existing take counts per label from disk (the user might be resuming an interrupted onboarding, or re-training a single command).
3. Seek to the first label with `takeCount < 3` so the user picks up where they left off.

`TrainingActivity` does not own a coroutine scope of its own beyond `lifecycleScope`. All capture work is `lifecycleScope.launch(Dispatchers.IO)`, so it cancels cleanly if the activity finishes mid-capture.

## Dispatch sequence

When `ControlActivity.handleAudio()` runs after a capture:

```
audio: ShortArray
  │
  ▼  Dispatchers.Default
engine.match(audio)
  │   ┌─ extract MFCCs from audio
  │   ├─ for each (label, [take₁ ... takeₙ]):
  │   │     best = min over takes of DTW(query, takeᵢ)
  │   └─ return (label_with_lowest_best, lowest_best, second_lowest_best)
  ▼
match: TemplateEngine.Match
  │
  ▼  if match.score <= prefs.match_threshold
Dispatch.dispatch(label, api, globalMaxSpeed)
  │   ┌─ Stop      → api.stop()
  │   ├─ SetSpeed  → ensure running, then api.setSpeed(scaled)
  │   ├─ AdjustSpeed → api.adjustSpeed(scaled)
  │   ├─ SetRange  → api.setStrokeRange(min, max)
  │   └─ Preset    → api.setStrokeRange → ensure running → api.setSpeed(scaled)
  ▼
HandyV2ApiClient
  │
  ▼  Retrofit + OkHttp on background thread
PUT https://www.handyfeeling.com/api/handy/v2/...
```

The dispatch path always emits a log line (matched or below-threshold) and updates `lastMatchText`. Below-threshold matches are shown to the user as `? heartbeat (score 41.2)` so they can see what the engine *almost* matched.

## Always-on listening state machine

```
                  ┌───────────────────┐
                  │ idle              │
                  │ voicedRun = 0     │
                  └────────┬──────────┘
                           │ block arrives
                           ▼
                  ┌───────────────────┐
                  │ accumulating      │
                  │ buffer slides     │
                  │ voicedRun tracks  │
        ┌─────────┤ consecutive       │◄──┐
        │ block   │ voiced blocks     │   │ next block
        │ silent  └────────┬──────────┘   │
        │                  │ voiced       │
        ▼                  ▼              │
voicedRun = 0    voicedRun >= 4 AND       │
                 cooldown elapsed         │
                           │              │
                           ▼              │
                  ┌───────────────────┐   │
                  │ trigger match     │   │
                  │ snapshot buffer   │   │
                  │ lastFireAt = now  │   │
                  │ voicedRun = 0     │   │
                  └────────┬──────────┘   │
                           │              │
                           └──────────────┘
```

Parameters (`ControlActivity` companion object):

- `VAD_BLOCK_MS = 30` — each `Vad.isSpeech` call is on a 30 ms block (480 samples).
- `VAD_SPEECH_FRAMES_TO_TRIGGER = 4` — ~120 ms of continuous voiced audio before a match runs.
- `COOLDOWN_MS = 1500` — minimum gap between triggers. Prevents the same utterance firing twice.

Match work happens on `Dispatchers.Default`; the capture loop doesn't block on the matcher. If matches stack up (the user speaks rapidly), the cooldown will throttle them.

## Things deliberately missing

- **No background service.** The mic is hot only while `ControlActivity` is `RESUMED`. This is intentional: an always-on background recorder is a privacy footgun, and Android imposes foreground-service requirements that complicate the app. If hands-free across screen-off becomes necessary, a `MediaProjection`-style foreground service with a persistent notification is the right path.
- **No automatic reconnection retries.** If `api.connect()` fails, the user must tap Connect again. The API client surfaces errors in `lastError` and `debugLog`. Retries belong in user-driven UI, not silently in the background.
- **No template versioning beyond `format: 1`.** A future migration script can read `format` and adapt; for now there is one shape.
- **No remote sync of templates.** Templates are speaker-locked by design; syncing them across devices defeats the point unless the device-to-device sync is between two devices used by the same speaker, which is rarely worth the complexity for nine labels.
- **No analytics, telemetry, or crash reporting.** This is a personal accessibility tool; everything stays on-device.
- **No A/B between engines.** `MfccDtwEngine` is hard-wired in `ControlActivity`. When `TfliteEmbeddingEngine` ships, the switch will be a single line plus a prefs toggle.
- **No microphone selection UI.** The default `VOICE_RECOGNITION` source is used. If the user has a Bluetooth headset and wants to record through it, that's a future setting.
- **No noise suppression / AGC.** Android's `AcousticEchoCanceler` and `NoiseSuppressor` are available but unhooked. MFCC + DTW with personal samples is fairly robust without them; if accuracy on Owen's voice in noisy rooms is poor, this is the first knob to try.

## Diagrams in the repo

- `vega/docs/ARCHITECTURE.md` — sibling project, same shape, deeper rationale on the wake-word-free design.
- `vega/docs/COMMANDS.md` — vocabulary tuning notes.
- `../docs/HANDY_API_NOTES.md` — operation-order rules that `Dispatch.dispatch` follows.
