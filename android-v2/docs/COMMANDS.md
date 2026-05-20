# Commands

The fixed vocabulary, what each command does, and how to add a new one.

## Vocabulary (Phase 1)

Mirrors `vega/commands.py`'s `DISPATCH` exactly. See `dispatch/Dispatch.kt`.

| Label | Action | Notes |
| --- | --- | --- |
| `start` | `SetSpeed(25)` | Slow preset. Starts the device if it isn't running. |
| `stop` | `Stop` | Immediate stop. Clears running state. |
| `faster` | `AdjustSpeed(+15)` | Add 15 design points to the current speed. Scaled by global max. |
| `slower` | `AdjustSpeed(-15)` | Subtract 15. Scaled by global max. |
| `heartbeat` | `Preset(rangeMin=50, rangeMax=90, speed=25)` | Pattern stand-in. Slow, top range. |
| `wave` | `Preset(0, 100, 50)` | Pattern stand-in. Medium, full range. |
| `surge` | `Preset(0, 100, 80)` | Pattern stand-in. Fast, full range. |
| `breathe` | `Preset(0, 100, 25)` | Pattern stand-in. Slow, full range. |
| `storm` | `Preset(50, 90, 80)` | Pattern stand-in. Fast, top range. |

## VoiceAction sealed class

`dispatch/VoiceAction.kt`:

```kotlin
sealed class VoiceAction {
    data object Stop : VoiceAction()
    data class SetSpeed(val percent: Int) : VoiceAction()
    data class AdjustSpeed(val delta: Int) : VoiceAction()
    data class SetRange(val min: Int, val max: Int) : VoiceAction()
    data class Preset(val rangeMin: Int, val rangeMax: Int, val speed: Int) : VoiceAction()
}
```

| Action | Semantics |
| --- | --- |
| `Stop` | `api.stop()` — sends `PUT /hamp/stop`. Sets `isRunning = false`. |
| `SetSpeed(percent)` | If `percent == 0`, calls `api.stop()`. Otherwise: ensure the device is started (one-time `api.start()` if not running) then `api.setSpeed(scaled)`. `scaled = (percent * globalMaxSpeed / 100).coerceIn(0, speedLimit)`. |
| `AdjustSpeed(delta)` | Compute the target as `api.currentSpeed + (delta * globalMaxSpeed / 100)`, clamp to `[0, speedLimit]`, then `api.setSpeed(target)`. |
| `SetRange(min, max)` | Range is squashed into `[globalMinStroke, globalMaxStroke]`, then `api.setStrokeRange(squashedMin, squashedMax)`. |
| `Preset(rangeMin, rangeMax, speed)` | Squash the range into the global band, then slide → start → velocity in that order. The Handy API resets velocity if it's written before `start`. |

`Dispatch.dispatch(label, api, globalMaxSpeed, speedLimit, globalMinStroke, globalMaxStroke)` is a suspending function that runs the action's API calls and returns `true` on success. All four cap parameters have safe defaults (`30, 100, 0, 100`) so call sites can omit any they don't care about.

## Caps and clamps

Four prefs shape every dispatched action. They mirror the v1 (`../android/`) controls and are exposed on the **Manual controls** screen so both surfaces honour them.

| Pref | Default | What it does |
| --- | --- | --- |
| `globalMaxSpeed` | 30 | Design-space velocity multiplier. `device = design * globalMaxSpeed / 100`. Owen's ceiling. |
| `speedLimit` | 100 | Hard ceiling on the device velocity, applied **after** the design-space scaling. Mirrors v1's `Spd.Lim`. |
| `globalMinStroke` | 0 | Stroke-position floor. Preset ranges are squashed into `[globalMinStroke, globalMaxStroke]`. |
| `globalMaxStroke` | 100 | Stroke-position ceiling. With defaults `0..100` no squashing happens. |

Worked examples with `globalMaxSpeed=30, speedLimit=100, globalMinStroke=0, globalMaxStroke=100`:

- `SetSpeed(25)` sends velocity 7 (`25 × 0.30 = 7.5`, rounded down, ≤ 100).
- `AdjustSpeed(+15)` from current 7 sends velocity 11 (`7 + (15 × 0.30) = 11.5`, rounded down, ≤ 100).
- `Preset(rangeMin=0, rangeMax=100, speed=80)` sends slide `0..100` and velocity 24.

Same prefs but with `speedLimit=20, globalMinStroke=20, globalMaxStroke=80`:

- `SetSpeed(25)` still computes 7, ≤ 20, so sends 7.
- `Preset(rangeMin=0, rangeMax=100, speed=80)` computes 24, **clamped to 20** by `speedLimit`. Slide becomes `20..80` (squashed into the band).

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
    "start", "stop", "faster", "slower",
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

Re-train. In the running app: **Settings → Re-record voice samples**. This wipes the templates directory and routes back to `TrainingActivity`. Or, if you only want to add the new label without re-recording the others, use `adb` to push a new take into the right folder, but for everyday workflow the wipe-and-rerecord path is simpler.

### Naming conventions

- One word, ideally one syllable. "deeper" works; "go deeper" is two captures and the matcher gets confused by the silence between words.
- Avoid pairs that sound alike. "slow" and "stop" share the same starting phoneme and can score close to each other; choose one or use distinct alternates ("halt" vs "go").
- Prefer words with distinctive consonants. Fricatives (`s`, `sh`, `f`, `v`) survive noise well; vowel-only words like "oh" don't.
- Lowercase only. `TemplateStore.sanitize()` will lowercase anyway; using lowercase in source keeps debug output legible.

## Why fixed labels and not free speech

The whole point of v2 is to skip the speech-to-text step that fails for non-standard voices. Allowing free phrases requires either:

1. Running an ASR ("set speed to fifty" → `SetSpeed(50)`), which is exactly what v1 does and exactly what we're avoiding.
2. Running an LLM ("a little faster" → `AdjustSpeed(+10)`), which is what vega's *dormant* LLM path does. It works, but at the cost of cloud round-trips, API spend, and the failure modes of speech recognition we ran from in the first place.

Phase 1 chooses the constraint: nine labels, fixed actions, perfect determinism. New utterances become new labels in the dispatch table, not new parsing rules.

## Patterns and the Phase 2 port

The five pattern labels (`heartbeat`, `wave`, `surge`, `breathe`, `storm`) are currently mapped to static `Preset(range, speed)` combinations. That's deliberately a stand-in.

Vega's `devices/handy.py` implements real patterns as background threads that modulate speed over time:

```python
def _heartbeat(intensity: int) -> Iterator[tuple[float, float]]:
    while True:
        yield (intensity, 0.10)   # thump
        yield (0,         0.06)
        yield (intensity, 0.08)   # echo
        yield (0,         0.42)   # rest
```

Each generator yields `(speed_design_percent, duration_seconds)` pairs; a controller loop calls `_apply_speed` and `time.sleep` per pair. The same pattern in Kotlin is a `Flow<Pair<Int, Long>>` consumed by a `launch(Dispatchers.Default)` block that suspends on `delay()` between writes.

Phase 2 work, in order:

1. Add a `VoiceAction.Pattern(name: String, intensity: Int = 100)` case.
2. Port `vega/devices/handy.py`'s pattern generators to Kotlin.
3. Add a `PatternEngine` singleton in `dispatch/` that owns the running coroutine, cancels it on any explicit speed command (just like vega does).
4. Replace the `Preset(...)` rows in `TABLE` with `Pattern(...)` rows.

The recording vocabulary does not change — the same five labels are already trained. This is the whole reason the Phase 1 stand-ins use the same label names.

## Why mirror vega's vocabulary

A user who learns to say "start, stop, faster, slower, heartbeat, wave, surge, breathe, storm" on the Pi (vega) gets the same surface on Android (v2). Same actions, same trigger conditions. The two devices are interchangeable from the user's perspective, which is the point: voice control is a habit, and the habit should transfer.

If a label is too hard to say reliably on Owen's voice in either implementation, change it in *both* places at once (this file and `vega/commands.py`). Don't let them diverge.
