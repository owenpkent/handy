# Architecture

How the pieces fit, what runs on which thread, what state lives where, and what is deliberately *not* implemented.

## Process and activity model

Single process, three activities, no foreground service. The mic is owned by exactly one component at any moment.

```
                    ┌──────────────────────────────────────┐
                    │  ControlActivity                     │
launcher ──────────►│  - Simple home (one listen button)   │
                    │  - More: Voice / Manual tabs         │
                    │  - mic toggle, live peak meter       │
                    │  - 12-button pattern grid            │
                    │  - PatternEngine (envelope runner)   │
                    │  - dispatch into HandyV2ApiClient    │
                    │  - debug log                         │
                    └──────────┬───────────────────────────┘
                               │  (first launch, "Re-record", or "Clear all")
                               ▼
                    ┌──────────────────────────────────────┐
                    │  TrainingActivity                    │
                    │  - 4 labels × 3 takes (auto-advance) │
                    │  - cross-validation gate per label   │
                    │  - Build templates, then back home   │
                    │  - ack tone on every saved take      │
                    └──────────────────────────────────────┘

                    ┌──────────────────────────────────────┐
                    │  SettingsActivity                    │
                    │  - prefs editor                      │
                    │  - re-record entry                   │
                    │  - ← Home button back to Control     │
                    └──────────────────────────────────────┘
```

`SplashActivity` is the launcher; it animates, starts the connect, and hands off to `ControlActivity`. On `onCreate` it reads a snapshot of `PreferencesManager`. If `onboarded == false` (first run), it launches `TrainingActivity` and returns. Otherwise it loads templates from `TemplateStore` into `MfccDtwEngine`, instantiates a `PatternEngine` bound to its `lifecycleScope`, restores the connection key, and wires up the tab listener + listening loop. The More screen's header gear opens `SettingsActivity` directly. The activity opens on a simplified home screen (`homePanel`: one Start / Stop listening button wired to `onMicTapped`, status, "Heard:"); `showMore(true)` swaps in the full UI (`mainScroll`) and system Back returns (`backToHome`). User-initiated connects go through `ConnectGuard` (single-flight, claims its slot before the first suspension). The Voice **and** Manual surfaces live on the More screen as tab panels — there is no separate `ManualActivity` anymore.

`TrainingActivity` is unidirectional. The user records, the takes hit disk, cross-validation flags outliers, and the activity finishes back to `ControlActivity`. It does not need a network connection or a Handy device to operate.

`SettingsActivity` writes prefs and can invoke the "wipe templates and re-train" path. The header's **← Home** button finishes Settings and returns to `ControlActivity` directly; `onResume` over there re-reads prefs and reloads templates.

## Mic ownership and the listening loop

The Android `AudioRecord` API allows only one active recorder at a time. We respect that with a single owner per moment:

| Mode | Who owns mic | When |
| --- | --- | --- |
| Training capture | `TrainingActivity.captureOneTake` | Per-take 2.5 s window via `AudioCapture.readWindow`. Released after each. The streaming peak callback drives the live meter during capture. |
| Voice tab (tap-mic or always-on) | `ControlActivity.startAlwaysOn` | `AudioCapture.stream(...)` runs continuously while the loop is active; cancelled on a mic tap off or the idle auto-stop (both then stop the device, after the session has joined; see `ListeningSession.cancelThen`), listening-mode change, or a screen-on navigate-away (`onStop` while interactive). A screen-off keeps the loop alive on a partial wake lock. Same loop services both tap-mic toggle and the always-on setting. |

`AudioCapture` doesn't pool `AudioRecord`s — each entry point constructs, records, releases. Source is `MediaRecorder.AudioSource.MIC` everywhere (not `VOICE_RECOGNITION`). The Android emulator's virtual mic delivers silence to `VOICE_RECOGNITION` while routing audio fine to `MIC`; we don't know what Google's path does differently but bare `MIC` works in every environment we've tested. We lose any AGC / noise suppression that a device's `VOICE_RECOGNITION` audio source might apply, which has not been a problem in practice.

## Threading

| Thread | Owner | Work |
| --- | --- | --- |
| Main (UI) | Activities | View updates, click handlers, navigation. Never blocks. |
| `Dispatchers.IO` | `lifecycleScope.launch(Dispatchers.IO)` | `AudioCapture` reads, `TemplateStore` reads/writes, `AudioTrack` playback, network through Retrofit's own threads. |
| `Dispatchers.Default` | One-off `withContext(Default)` in `ControlActivity.handleAudio` | MFCC + DTW. CPU-bound, no I/O. |
| Retrofit / OkHttp | OkHttp dispatcher pool | All HTTP. Retrofit's `suspend` functions resume on the calling coroutine. |

The matcher is invoked synchronously inside a single `withContext(Default)` block per utterance. For the Phase 1 vocabulary (4 labels × 3 takes), a single match takes a few hundred microseconds, so there is no benefit from parallelizing across labels.

In always-on mode, `capture.stream(...)` runs an unbounded loop on `Dispatchers.IO`. It is gated by the session's own `isActive` check; cancelling the `ListeningSession` (`ui/ListeningSession.kt`) (on a screen-on navigate-away, a mic tap off, the idle auto-stop, or when listening mode changes) lets the loop drain and `AudioRecord.release()` run. The loop deliberately survives a screen-off (see Lifecycle → `onStop`), held up by the partial wake lock, so commands keep working with the screen dark.

## State

Three kinds of state, three different homes.

### Per-launch in-memory state (`ControlActivity` fields)

- `engine: MfccDtwEngine` — the loaded templates. Rebuilt from disk on every launch and again in `onResume` so newly-recorded takes appear without restart.
- `patternEngine: PatternEngine` — lazy-init runner for the 12 envelope patterns. Cancelled on STOP, on any new pattern, and (implicitly via `lifecycleScope`) on activity destruction.
- `listening: ListeningSession` — wraps the coroutine running the listening loop (used by both tap-mic and always-on modes).
- `ackTone: ToneGenerator?` — short-lived `STREAM_MUSIC` tone generator that beeps on every voice match. Released in `onDestroy`. TrainingActivity owns a parallel instance for take saves.
- `globalMaxSpeed`, `globalMinStroke`, `globalMaxStroke`, `threshold`, `listeningMode` — cached pref values, re-read on every `onResume`.
- `log: MutableList<String>` — ring buffer of debug lines for the on-screen panel; auto-scrolls to newest on update.

The `HandyV2ApiClient` itself holds device-side state as `StateFlow`s (`isRunning`, `currentSpeed`, `strokeMin`, `strokeMax`, `lastError`, `debugLog`), plus three injected configuration knobs that activities push in: `connectionKey`, `invertStroke`, `(globalStrokeMin, globalStrokeMax)`. The flows survive activity recreation as long as the client instance does (the activity recreates it on every `onCreate`, which is acceptable because the only persisted piece is the connection key).

### Persistent state (DataStore prefs)

`PreferencesManager` wraps Jetpack DataStore. One file, one set of keys:

| Key | Type | Default | Purpose |
| --- | --- | --- | --- |
| `connection_key` | String | `""` | Handy connection key. |
| `listening_mode` | String | `button` | One of `button`, `always_on`. Both share the same VAD-driven loop; `button` requires an explicit mic-tap to start it. |
| `global_max_speed` | Int | 30 | Hard speed cap (% of device max). Applied as a multiplier in `Dispatch` and as a peak for `PatternEngine`. |
| `global_min_stroke` | Int | 0 | Stroke-position floor. Preset / pattern ranges are squashed into `[min, max]`. The connect-time park targets this edge. |
| `global_max_stroke` | Int | 100 | Stroke-position ceiling. |
| `invert_stroke` | Boolean | false | Flip user-coord 0 to device-coord 100 at the API client boundary. Applied once in `setStrokeRange` and `parkAtRetracted`. |
| `match_threshold` | Float | 30.0 | Path-normalised DTW distance below which a match counts. Cross-validation also uses `threshold × 1.4` as its outlier cutoff. |
| `onboarded` | Boolean | false | Set true when the user taps **Build templates** **or** bails out via Training's own menu — either way ControlActivity stops force-routing them back. |

`PreferencesManager.snapshot()` returns a single `Snapshot` so a coroutine can read everything in one suspend without juggling individual Flows.

### Persistent state (template store)

`TemplateStore` writes plain JSON files under `applicationContext.filesDir/templates/`:

```
filesDir/
└── templates/
    ├── begin/
    │   ├── take_1.json    # SerializedTake{format=3, label="begin", mfcc=Array<DoubleArray>}
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
  "format": 3,
  "label": "begin",
  "mfcc": [[...13 numbers...], [...13 numbers...], ...]
}
```

The `format` field is the schema version. History:

- **1** — original 1.5 s capture window, MFCCs without normalisation.
- **2** — 2.5 s capture window (longer MFCC sequences, ~250 frames).
- **3** — cepstral mean normalisation applied per utterance (mean-centred features).

`TemplateStore.loadAll` and `TemplateStore.loadTakes` filter takes whose `format` doesn't match `CURRENT_FORMAT`. On launch, old-format takes are silently dropped and the user is prompted to re-record via the "No voice samples yet" hint on the Voice tab. Phase-3 TFLite embeddings will bump the version again so MFCC takes aren't silently mixed with embeddings.

File names use `take_N.json` where N is the next integer after the existing count. `TemplateStore.sanitize()` lowercases the label and replaces non-alphanumerics with underscores so labels never break the filesystem. Labels in `Dispatch.LABELS` are already clean, so sanitisation is a defence-in-depth measure.

## Lifecycle

`ControlActivity.onCreate`:

1. Inflate layout.
2. Initialise `PreferencesManager`, `TemplateStore`, `MfccDtwEngine`, `AudioCapture`, `Vad`.
3. Bind views and observers.
4. Launch the `bootstrap()` coroutine, which:
   1. Reads the prefs snapshot.
   2. If `!onboarded` (first run) → launches `TrainingActivity` and returns. Subsequent visits trust the flag, even if the template store is empty (user can deliberately bail onboarding via Training's menu).
   3. Otherwise loads MFCCs from disk into the engine.
   4. Restores the connection key into the input field and (if non-blank) immediately calls `api.connect()`.

`ControlActivity.onResume`:

- Re-reads the prefs snapshot. If connected and in always-on mode, re-arms the listening loop when it isn't already running (so it comes back after a navigate-away, and is a no-op when it survived a screen-off on the wake lock). In button mode it only re-applies when the listening mode actually changed (button listening is tap-gated and must not auto-start).

`ControlActivity.onStop`:

- **Screen-aware teardown.** Checks `PowerManager.isInteractive`. A genuine navigate-away (Home, or opening Settings) leaves the screen interactive → cancels the `ListeningSession`, the capture loop drains, `AudioRecord.release()` runs, and `keepAwake(false)` drops the wake lock. (The screen-on flag set in `onCreate` is only honored while the window is visible, so navigating away lets the screen sleep on its own.) A screen-off (power button, or an idle timeout) leaves the device non-interactive → the loop is left running so voice control keeps working with the screen dark; the partial wake lock holds the CPU up so capture doesn't suspend. (Older code cancelled unconditionally in `onPause`, which is why listening died the moment the screen slept.)

`ControlActivity` keep-screen-on (`onCreate`):

- `window.addFlags(FLAG_KEEP_SCREEN_ON)` is set unconditionally in `onCreate`, so the screen stays on the **whole time the app is in the foreground** — not just while listening. The OS only honors the flag while the window is visible, so it costs nothing when backgrounded and a navigate-away lets the screen sleep normally. `SettingsActivity` and `TrainingActivity` set the same flag. This is the "keep the phone awake while the app is running" behavior the user asked for.

`ControlActivity.keepAwake(on)`:

- A separate, listening-only concern from the screen flag above. Acquires/releases a non-reference-counted `PARTIAL_WAKE_LOCK` (`"HandyVoiceV2:listening"`) so a deliberate power-off (screen dark) can't suspend the CPU mid-capture. Acquired in `startAlwaysOn`, released in `stopAlwaysOn` and again in `onDestroy`. The `isHeld` guard keeps acquire/release idempotent. It no longer touches `FLAG_KEEP_SCREEN_ON` — that's owned by `onCreate`.

`ControlActivity` idle auto-stop:

- An inactivity watcher (`armIdleTimeout`, armed in `startAlwaysOn`) stops the session after a configurable idle window of no activity. The window is **Settings → Idle auto-stop (minutes)** (`PreferencesManager.idleTimeoutMin`, clamped 1–30, default 10), cached in `ControlActivity.idleTimeoutMs` and refreshed every `onResume` so a change lands on the watcher's next re-check. "Activity" is any voice match (`handleAudio` calls `noteActivity`) **or** any touch (`onUserInteraction` calls `noteActivity`, so working the Manual sliders counts and the device won't stop out from under hands-on use). The watcher is a single coroutine that sleeps to the deadline and re-checks a `lastActivityAt` timestamp — touches just stamp the long, they don't restart the coroutine, so a slider drag streaming touch events is cheap. On expiry, `onIdleTimeout` **stops the device** (reuses the `"stop"` dispatch — cancels any running pattern, then HAMP stop) and calls `stopAlwaysOn`, which drops the CPU wake lock. The screen itself stays on as long as the app is foregrounded (per the `onCreate` flag) — the idle stop is now about halting the device and freeing the mic, not about letting the screen sleep. Restart is a mic tap (in always-on mode too — `onMicTapped` re-arms the loop when it's down) or the next foreground resume.

`ControlActivity` Picture-in-Picture (`ui/PipController.kt`):

- The activity can float into a PiP window (header `pipButton`, or `onUserLeaveHint` auto-floating when connected **and** `isRunning`). A PiP window can't receive touches on the app's own views, so the controls are system `RemoteAction`s — icon buttons in the window overlay, each backed by a `PendingIntent` broadcast. The bar holds at most `getMaxNumPictureInPictureActions()` actions (≥3 on every phone, sometimes 5), so it's the Slower/**Stop**/Faster trio — three fits the guaranteed floor and is never truncated. Taps arrive as broadcasts on a `RECEIVER_NOT_EXPORTED` receiver and route through `Dispatch.dispatch` (`onPipControl`), which also calls `noteActivity()` + `playAck()` since a broadcast never reaches `onUserInteraction`. `onPictureInPictureModeChanged` hides both screens (`homePanel`, `mainScroll`) for the compact `pipOverlay` status text and restores whichever was showing on exit. The listening loop is **not** torn down on entering PiP (the activity stays STARTED while the window is visible). Manifest: `supportsPictureInPicture` + PiP `configChanges`.

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
  │   ┌─ extract MFCCs from audio (with CMN)
  │   ├─ for each (label, [take₁ ... takeₙ]):
  │   │     best = min over takes of DTW(query, takeᵢ)
  │   └─ return (label_with_lowest_best, lowest_best, second_lowest_best)
  ▼
match: TemplateEngine.Match
  │
  ▼  passes = match.score ≤ threshold  AND  margin ≥ score × 0.05
ackTone.beep()
  │
  ▼
Dispatch.dispatch(label, api, globalMaxSpeed, globalMinStroke, globalMaxStroke, patternEngine, humanizeEngine)
  │   ┌─ Stop          → humanizeEngine.cancel() ; patternEngine.cancel() ; api.rampStop() (ease to 0, then hamp/stop)
  │   ├─ SetSpeed      → humanize on: humanizeEngine.start(scaled) ; else ensure running, api.setSpeed(scaled)
  │   ├─ AdjustSpeed   → humanize running: retarget the loop ; else api.rampSpeed(current + scaled-delta), clamped; glides
  │   ├─ SetRange      → api.setStrokeRange(squashed-into-band)
  │   ├─ Preset        → humanize/pattern cancel ; setStrokeRange(squashed) → start → setSpeed(scaled)
  │   └─ Pattern       → humanize cancel ; setStrokeRange(squashed full band) → patternEngine.start(name, scaleSpeed(intensity))
  ▼
HandyV2ApiClient   PatternEngine (looped)   HumanizeEngine (looped: modulates speed + depth around the base)
  │                              │
  │                              ▼  every (envelope, durationMs):
  │                              api.setSpeed((env × intensity).toInt())
  │                              delay(durationMs)
  ▼  Retrofit + OkHttp on background thread
PUT https://www.handyfeeling.com/api/handy/v2/...
```

The dispatch path always emits a log line (matched or below-threshold) and updates `lastMatchText`. Below-threshold matches are shown to the user as `? slower (score 41.2)` so they can see what the engine *almost* matched.

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
- `COOLDOWN_MS = 600` — minimum gap between triggers; a light debounce against one utterance firing twice. The stamp is set at snapshot time regardless of whether the match passed, so this window is dead air with no capture and no feedback. It was 1500, which (stacked on the 1.8 s post-VAD wait and the ~1.5 s stop wind-down) left a ~3.3 s hole after every stop where a restart word was silently swallowed. It no longer has to cover the wind-down: a fresh velocity command supersedes an in-flight ramp at the client level (see `velocityEpoch` in `HandyV2ApiClient`), so a "begin" spoken right after a "stop" wins instead of being stomped back to 0.

Match work happens on `Dispatchers.Default`; the capture loop doesn't block on the matcher. If matches stack up (the user speaks rapidly), the cooldown will throttle them.

**Ramp supersession (`velocityEpoch`).** `rampSpeed`/`rampStop` write velocity across ~1.5 s in their own coroutines. A monotonic epoch, bumped by every single-shot velocity write (`start`/`stop`/`setSpeed`/`setSpeedModulated`) and captured by each ramp, lets a ramp abandon its remaining legs — and skip its terminal `hamp/stop` — the instant a newer command bumps it. Without this, a "begin" landing while a "stop" ramp was still draining would be overwritten back to 0 a beat later, so the device appeared to refuse to restart.

## Device transports

`HandyV2ApiClient` owns all motion policy (velocity epoch, `rampSpeed` / `rampStop`, invert, global band, transient writes, state flows, debug log, `lastError`) and is the single device object the rest of the app uses. Below it sits a small `DeviceTransport` (`api/DeviceTransport.kt`) that does only wire work:

- `HandyRestTransport`: the Handy V2 REST calls (default).
- `LovenseBleTransport`: a Lovense toy over BLE GATT (`Vibrate:N;`, levels 0..20; protocol helpers in the Android-free `LovenseProtocol`).

`HandyVoiceApp.configureDevice(prefs snapshot)` installs the transport chosen in Settings; `useTransport` only works while DISCONNECTED. Transports report back through `TransportListener` (log lines, battery, device name, and an unexpected link drop, which flips the client to DISCONNECTED with a `lastError` so the usual red status line shows it). GATT operations are serialized inside the Lovense transport (one Mutex, suspend until callback, ~1 s timeout); a 30 s `Battery;` poll doubles as keep-awake.

### Toy page

When the selected device is Lovense, `ControlActivity` shows `ToyPanel` (`ui/ToyPanel.kt`, layout `panel_toy.xml`) in place of the voice `homePanel`; `showMore` and `syncDevice` choose between them. `ToyPanel` owns only view wiring and routes motion through the same client, `HumanizeEngine` and `PatternEngine` calls the Manual tab uses (maths in the Android-free `ToyIntensity`). Intensity persists as `toy_intensity`. Listening is gated: `toyPageShowing` stops `applyListeningMode` and the `onResume` re-arm from starting the mic, even in always-on mode, until the user opens More.

## Things deliberately missing

- **Toy foreground service (the one exception).** While a Lovense toy is connected, `service/ToyConnectionService` (connectedDevice foreground service, ongoing notification with a Stop action, `HandyV2:toy` partial wake lock) keeps the process and the BLE link plus its keep-alive alive with the screen off or the app backgrounded. `HandyVoiceApp` starts/stops it from the shared client's connection state, and owns the motion engines (appScope) and `stopMotion()` so the notification's Stop works with no activity. The service holds no mic and no connection logic. A silent `Vibrate:0;` keep-alive is written on the 30 s poll while the motor is stopped.
- **No mic background service.** The mic is hot while `ControlActivity` is in the foreground task — including across a screen-off, where a partial wake lock keeps the listening loop alive so commands still work with the screen dark (see Lifecycle → `onStop`). It is *not* hot once the user navigates away (Home/Settings): that tears the loop down and drops the wake lock. A true background recorder (mic still hot after Home, or after the task is swapped away) is intentionally avoided — it's a privacy footgun and Android imposes foreground-service requirements that complicate the app. If always-listening-regardless-of-foreground becomes necessary, a `MediaProjection`-style foreground service with a persistent notification is the right path.
- **No automatic reconnection retries.** If `api.connect()` fails, the user must tap Connect again. The API client surfaces errors in `lastError` and `debugLog`, and `ControlActivity` shows the reason on the status line in red (plus a toast on a manual Connect tap) so the failure is visible rather than a silent "Disconnected". This covers both the manual tap and the splash-time auto-connect, since both end in `DISCONNECTED` with the reason cached. Retries belong in user-driven UI, not silently in the background.
- **No template versioning beyond `format: 1`.** A future migration script can read `format` and adapt; for now there is one shape.
- **No remote sync of templates.** Templates are speaker-locked by design; syncing them across devices defeats the point unless the device-to-device sync is between two devices used by the same speaker, which is rarely worth the complexity for four labels.
- **No analytics, telemetry, or crash reporting.** This is a personal accessibility tool; everything stays on-device.
- **No A/B between engines.** `MfccDtwEngine` is hard-wired in `ControlActivity`. When `TfliteEmbeddingEngine` ships, the switch will be a single line plus a prefs toggle.
- **No microphone selection UI.** Whatever `AudioSource.MIC` routes to is what gets recorded. If the user has a Bluetooth headset and wants to record through it, that's a future setting.
- **No noise suppression / AGC.** Android's `AcousticEchoCanceler` and `NoiseSuppressor` are available but unhooked. MFCC + DTW with personal samples is fairly robust without them; if accuracy on Owen's voice in noisy rooms is poor, this is the first knob to try.

## Diagrams in the repo

- `vega/docs/ARCHITECTURE.md` — sibling project, same shape, deeper rationale on the wake-word-free design.
- `vega/docs/COMMANDS.md` — vocabulary tuning notes.
- `../docs/HANDY_API_NOTES.md` — operation-order rules that `Dispatch.dispatch` follows.
