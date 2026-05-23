# Commands

The fixed vocabulary, what each command does, and how to add a new one.

## Vocabulary

Mirrors `vega/commands.py`'s `DISPATCH` closely (one rename: `start` → `begin` — see "Naming conventions" below). See `dispatch/Dispatch.kt`.

| Label | Action | Notes |
| --- | --- | --- |
| `begin` | `SetSpeed(25)` | Slow preset. Starts the device if it isn't running. |
| `stop` | `Stop` | Immediate stop. Cancels any running pattern envelope as well. |
| `faster` | `AdjustSpeed(+15)` | Add 15 design points to current speed. Scaled by global max; clamped to `[0, globalMaxSpeed]`. |
| `slower` | `AdjustSpeed(-15)` | Subtract 15. Same scaling + clamp. |
| `heartbeat` | `Pattern("heartbeat")` | Lub-dub envelope. See [PatternEngine](#pattern-engine). |
| `wave` | `Pattern("wave")` | Sine swell envelope. |
| `surge` | `Pattern("surge")` | Ramp-hold-rest envelope. |
| `breathe` | `Pattern("breathe")` | Gentle 0.10..0.60 sine. |
| `storm` | `Pattern("storm")` | Random walk envelope. |

## VoiceAction sealed class

`dispatch/VoiceAction.kt`:

```kotlin
sealed class VoiceAction {
    data object Stop : VoiceAction()
    data class SetSpeed(val percent: Int) : VoiceAction()
    data class AdjustSpeed(val delta: Int) : VoiceAction()
    data class SetRange(val min: Int, val max: Int) : VoiceAction()
    data class Preset(val rangeMin: Int, val rangeMax: Int, val speed: Int) : VoiceAction()
    data class Pattern(val name: String, val intensity: Int = 100) : VoiceAction()
}
```

| Action | Semantics |
| --- | --- |
| `Stop` | Cancel any running `PatternEngine` job, then `api.stop()` (`PUT /hamp/stop`). Clears `isRunning`. |
| `SetSpeed(percent)` | If `percent == 0`, calls `api.stop()`. Otherwise: ensure the device is started, then `api.setSpeed(scaled)`. `scaled = percent × globalMaxSpeed / 100`. |
| `AdjustSpeed(delta)` | Compute the target as `api.currentSpeed + (delta × globalMaxSpeed / 100)`, clamp to `[0, globalMaxSpeed]`, then `api.setSpeed(target)`. The clamp ensures repeated "faster" can't sneak past the cap. |
| `SetRange(min, max)` | Range is squashed into `[globalMinStroke, globalMaxStroke]`, then `api.setStrokeRange(squashedMin, squashedMax)`. The client also applies the invert flip if enabled. |
| `Preset(rangeMin, rangeMax, speed)` | One-shot static preset for the tap-only Manual buttons. Cancels any running pattern, squashes the range into the global band, then slide → start → velocity in that order. The Handy API resets velocity if it's written before `start`. |
| `Pattern(name, intensity)` | Opens the stroke range to the full global band, then calls `patternEngine.start(name, scaleSpeed(intensity))`. The engine launches a looping coroutine that emits `(envelope, durationMs)` pairs into `api.setSpeed`. |

`Dispatch.dispatch(label, api, globalMaxSpeed, globalMinStroke, globalMaxStroke, patternEngine)` is a suspending function that runs the action's API calls and returns `true` on success. All parameters except `label` and `api` have safe defaults (`30, 0, 100, null`) so call sites can omit any they don't care about, **except** that `Pattern` actions require a non-null `patternEngine` — passing `null` returns `false` from dispatch. `ControlActivity` always passes its engine instance.

## Caps and clamps

Three prefs shape every dispatched action. They live in **Settings** so both voice and manual surfaces honour them.

| Pref | Default | What it does |
| --- | --- | --- |
| `globalMaxSpeed` | 30 | Design-space velocity multiplier. `device = design * globalMaxSpeed / 100`. Owen's ceiling. |
| `globalMinStroke` | 0 | Stroke-position floor. Preset ranges are squashed into `[globalMinStroke, globalMaxStroke]`. |
| `globalMaxStroke` | 100 | Stroke-position ceiling. With defaults `0..100` no squashing happens. |

Worked examples with `globalMaxSpeed=30, globalMinStroke=0, globalMaxStroke=100`:

- `SetSpeed(25)` sends velocity 7 (`25 × 0.30 = 7.5`, rounded down).
- `AdjustSpeed(+15)` from current 7 sends velocity 11 (`7 + (15 × 0.30) = 11.5`, rounded down).
- `Preset(rangeMin=0, rangeMax=100, speed=80)` sends slide `0..100` and velocity 24.

Same prefs but with `globalMinStroke=20, globalMaxStroke=80`:

- `SetSpeed(25)` still sends 7.
- `Preset(rangeMin=0, rangeMax=100, speed=80)` sends velocity 24 and slide `20..80` (squashed into the band).

The caps are enforced in `Dispatch.dispatch()`, not in the API client. The client itself accepts and forwards 0–100.

## Adding a new command

Three steps, exactly as in `vega`.

### 1. Pick the action

If an existing `VoiceAction` shape fits, reuse it. Examples:

- "deeper" → `SetRange(min=0, max=100)`.
- "shallower" → `SetRange(min=40, max=60)`.
- "double" → `AdjustSpeed(+30)`.

If you need a new shape, add a `data class` to `dispatch/VoiceAction.kt` and a `when` branch to `Dispatch.dispatch()`.

### 2. Wire the dispatch

Edit `dispatch/Dispatch.kt`:

```kotlin
val LABELS: List<String> = listOf(
    "begin", "stop", "faster", "slower",
    "heartbeat", "wave", "surge", "breathe", "storm",
    "deeper",                           // <-- new
)

val PROMPTS: Map<String, String> = mapOf(
    ...
    "deeper" to "deeper",               // <-- the displayed word on the training card
)

private val TABLE: Map<String, VoiceAction> = mapOf(
    ...
    "deeper" to VoiceAction.SetRange(0, 100),    // <-- new
)
```

Order in `LABELS` is the order the user sees during training.

### 3. Record templates

Re-record. From the Voice tab tap **Re-record** (opens Training without wiping existing takes) or **Clear all** (wipes everything, with confirmation, then opens Training). After Build templates the verification pass runs to confirm recognition; if your new label collides phonetically with an existing one, that's where you'll find out.

### Naming conventions

- One word, ideally one syllable. "deeper" works; "go deeper" is two captures and the matcher gets confused by the silence between words.
- **Avoid pairs that sound alike.** This is what bit us with `start` / `stop` — both begin with the `st-` cluster and end with `-p`/`-t`, and even with CMN the MFCCs scored close enough that `start` audio routinely matched `stop` templates better than its own. We renamed to `begin` / `stop`; you'll hit similar problems with any pair sharing first-syllable phonetics. The post-Build verification pass is the safety net but it's better to pick distinct words up-front.
- Prefer words with distinctive consonants. Fricatives (`s`, `sh`, `f`, `v`) survive noise well; vowel-only words like "oh" don't.
- Lowercase only. `TemplateStore.sanitize()` will lowercase anyway; using lowercase in source keeps debug output legible.

## Why fixed labels and not free speech

The whole point of v2 is to skip the speech-to-text step that fails for non-standard voices. Allowing free phrases requires either:

1. Running an ASR ("set speed to fifty" → `SetSpeed(50)`), which is exactly what v1 does and exactly what we're avoiding.
2. Running an LLM ("a little faster" → `AdjustSpeed(+10)`), which is what vega's *dormant* LLM path does. It works, but at the cost of cloud round-trips, API spend, and the failure modes of speech recognition we ran from in the first place.

Phase 1 chooses the constraint: nine labels, fixed actions, perfect determinism. New utterances become new labels in the dispatch table, not new parsing rules.

## Pattern engine

`dispatch/PatternEngine.kt` is a Kotlin port of `homeassistant/custom_components/handy/patterns.py`. Each named pattern is a `suspend fun(api, intensity)` that loops forever, calling `api.setSpeed(envelope × intensity)` then `delay(durationMs)`. The engine owns a single `Job` — starting a new pattern cancels the running one, and `STOP` cancels everything.

The 12 envelopes:

| Name | Envelope | Cycle |
| --- | --- | --- |
| `heartbeat` | lub (0.85, 150 ms) → gap (0, 180 ms) → dub (0.7, 150 ms) → rest (0, 1500 ms) | ~2 s |
| `wave` | smooth sine 0..1..0 over 8 s, 32 steps | 8 s |
| `surge` | 24-step linear ramp to peak (12 s), hold (4 s), rest (3 s) | ~19 s |
| `breathe` | sine confined to 0.10..0.60 over 6 s, never silent, never peak | 6 s |
| `storm` | random envelope 0..1 for 0.4..1.6 s, repeat | unpredictable |
| `pulse` | steady metronome 0.75 / 200 ms ↔ 0 / 200 ms | 400 ms |
| `tease` | 0.40 / 0.5 s → silent 2 s → 0.30 / 0.3 s → silent 3 s | ~5.8 s |
| `edge` | 5 s ramp to 0.95, hold 6 s, drop 4 s | ~15 s |
| `rush` | constant 0.95, sliced into 500 ms chunks for cancellability | n/a |
| `throb` | heavy 0.90 / 400 ms ↔ 0.10 / 700 ms | ~1.1 s |
| `bounce` | sine 0.40..0.90 over 2 s, 16 steps | 2 s |
| `climax` | 0.30 → 0.50 → 0.70 → 0.85 (3 s each) → 1.0 hold (8 s) → reset (2 s) | ~22 s |

The first five share labels with the voice dispatch table. The remaining seven are tap-only on the Manual tab; each has its own stroke range that's set just before the envelope starts (see `ControlActivity.TapPattern` for the data class).

### Lifecycle and interaction

- `PatternEngine.start(name, intensity)` cancels any prior pattern, calls `api.start()` if `isRunning` is false, and launches the envelope on the activity's `lifecycleScope`. Returns `false` on unknown name or `api.start()` failure.
- `PatternEngine.cancel()` cancels the job without stopping the device.
- `PatternEngine.stop()` cancels and calls `api.stop()`.
- The STOP button on `ControlActivity` calls `patternEngine.cancel()` then `api.stop()`. Voice `Stop` action does the same through dispatch.
- Activity destruction implicitly cancels everything via `lifecycleScope`.

### Adding a new pattern

1. Add a `suspend fun newpattern(api: HandyV2ApiClient, intensity: Int)` to `PatternEngine.companion` that loops forever emitting `(envelope, durationMs)` via the shared `emit()` helper.
2. Add the entry to the `PATTERNS` map: `"newpattern" to ::newpattern`.
3. To bind it to a voice label, add a row to `Dispatch.TABLE`: `"newword" to VoiceAction.Pattern("newpattern")`. Then add `"newword"` to `LABELS` + `PROMPTS` and re-record.
4. To bind it to a Manual tap button, add a grid button to `activity_control.xml`, an entry to `ControlActivity.tapOnlyPatterns` with its stroke range, and click-wire it in `wireManualPatterns`.

## Why mirror vega's vocabulary

A user who learns to say "start, stop, faster, slower, heartbeat, wave, surge, breathe, storm" on the Pi (vega) gets the same surface on Android (v2). Same actions, same trigger conditions. The two devices are interchangeable from the user's perspective, which is the point: voice control is a habit, and the habit should transfer.

If a label is too hard to say reliably on Owen's voice in either implementation, change it in *both* places at once (this file and `vega/commands.py`). Don't let them diverge.
