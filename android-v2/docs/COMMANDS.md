# Commands

The fixed vocabulary, what each command does, and how to add a new one.

## Vocabulary

Seven labels, picked for acoustic distinctness in MFCC+DTW space. `start → begin` (the `st-` cluster collides with `stop`). The fixed pattern words (`thunder / ripple / pulse`) were **dropped from voice** — they weren't useful spoken — and replaced with two stroke-length commands (`deeper / shallow`) and a `random` envelope trigger. `shallow` is used in place of "lower" because "lower" collides with "slower" for less-articulated speech. All pattern envelopes still exist as tap-only buttons on the Manual tab. See `dispatch/Dispatch.kt`.

| Label | Action | Notes |
| --- | --- | --- |
| `begin` | `SetSpeed(25)` | Slow preset. Starts the device if it isn't running. |
| `stop` | `Stop` | Immediate stop. Cancels any running pattern envelope as well. |
| `faster` | `AdjustSpeed(+25)` | Add 25 design points (`Dispatch.DEFAULT_STEP`) to current speed. Scaled by global max; clamped to `[0, globalMaxSpeed]`. **Glides** to the target via `api.rampSpeed` instead of jumping. |
| `slower` | `AdjustSpeed(-25)` | Subtract 25. Same scaling, clamp, and glide. |
| `deeper` | `AdjustDepth(deeper = true)` | Slides the stroke window toward the base (lowers min and max by the same band-scaled step), holding the stroke *length* fixed so the felt speed doesn't change. Clamps at `globalMinStroke`. |
| `shallow` | `AdjustDepth(deeper = false)` | Mirror image: slides the window toward the tip (raises min and max together), length held. Clamps at `globalMaxStroke`. |
| `random` | `RandomPattern` | Fires a randomly-chosen `PatternEngine` envelope. See [PatternEngine](#pattern-engine). |

## VoiceAction sealed class

`dispatch/VoiceAction.kt`:

```kotlin
sealed class VoiceAction {
    data object Stop : VoiceAction()
    data class SetSpeed(val percent: Int) : VoiceAction()
    data class AdjustSpeed(val delta: Int) : VoiceAction()
    data class AdjustDepth(val deeper: Boolean) : VoiceAction()
    data class SetRange(val min: Int, val max: Int) : VoiceAction()
    data class Preset(val rangeMin: Int, val rangeMax: Int, val speed: Int) : VoiceAction()
    data class Pattern(val name: String, val intensity: Int = 100) : VoiceAction()
    data class RandomPattern(val intensity: Int = 100) : VoiceAction()
}
```

| Action | Semantics |
| --- | --- |
| `Stop` | Cancel any running `PatternEngine` job, then `api.stop()` (`PUT /hamp/stop`). Clears `isRunning`. |
| `SetSpeed(percent)` | If `percent == 0`, calls `api.stop()`. Otherwise: ensure the device is started, then `api.setSpeed(scaled)`. `scaled = percent × globalMaxSpeed / 100`. |
| `AdjustSpeed(delta)` | Compute the target as `api.currentSpeed + (delta × globalMaxSpeed / 100)`, clamp to `[0, globalMaxSpeed]`, then `api.rampSpeed(target)`. The clamp ensures repeated "faster" can't sneak past the cap. `rampSpeed` glides to the target through several intermediate `PUT /hamp/velocity` legs (duration scales with distance, ~250–900 ms, ~1 point per leg capped at 12 legs) so the change is felt as a smooth ramp instead of a hard jump. The tap Slower/Faster buttons use the same `rampSpeed` path. |
| `AdjustDepth(deeper)` | Slides the stroke window at a fixed length, band-scaled like `AdjustSpeed`. `step = DEFAULT_STROKE_STEP × band / 100`, where `band = globalMaxStroke - globalMinStroke`. **deeper:** lower min by `step` (clamped to `floor`), then lower max by the same actual shift so the length is preserved. **shallow:** raise max by `step` (clamped to `ceiling`), then raise min by the same shift. Holding the length constant keeps the cycle rate constant, so depth never changes the felt speed; a window already against a band edge is a no-op. |
| `SetRange(min, max)` | Range is squashed into `[globalMinStroke, globalMaxStroke]`, then `api.setStrokeRange(squashedMin, squashedMax)`. The client also applies the invert flip if enabled. |
| `Preset(rangeMin, rangeMax, speed)` | One-shot static preset for the tap-only Manual buttons. Cancels any running pattern, squashes the range into the global band, then slide → start → velocity in that order. The Handy API resets velocity if it's written before `start`. |
| `Pattern(name, intensity)` | Opens the stroke range to the full global band, then calls `patternEngine.start(name, scaleSpeed(intensity))`. The engine launches a looping coroutine that emits `(envelope, durationMs)` pairs into `api.setSpeed`. |
| `RandomPattern(intensity)` | Same setup as `Pattern`, but calls `patternEngine.startRandom(scaleSpeed(intensity))`, which picks a random envelope from `PatternEngine.PATTERNS`. |

`Dispatch.dispatch(label, api, globalMaxSpeed, globalMinStroke, globalMaxStroke, patternEngine)` is a suspending function that runs the action's API calls and returns `true` on success. All parameters except `label` and `api` have safe defaults (`30, 0, 100, null`) so call sites can omit any they don't care about, **except** that `Pattern` / `RandomPattern` actions require a non-null `patternEngine` — passing `null` returns `false` from dispatch. `ControlActivity` always passes its engine instance.

## Caps and clamps

Three prefs shape every dispatched action. They live in **Settings** so both voice and manual surfaces honour them.

| Pref | Default | What it does |
| --- | --- | --- |
| `globalMaxSpeed` | 30 | Design-space velocity multiplier. `device = design * globalMaxSpeed / 100`. Owen's ceiling. |
| `globalMinStroke` | 0 | Stroke-position floor. Preset ranges are squashed into `[globalMinStroke, globalMaxStroke]`. |
| `globalMaxStroke` | 100 | Stroke-position ceiling. With defaults `0..100` no squashing happens. |

Worked examples with `globalMaxSpeed=30, globalMinStroke=0, globalMaxStroke=100`:

- `SetSpeed(25)` sends velocity 7 (`25 × 0.30 = 7.5`, rounded down).
- `AdjustSpeed(+25)` from current 7 ramps to velocity 14 (`7 + (25 × 0.30) = 14.5`, rounded down) over a few steps.
- `Preset(rangeMin=0, rangeMax=100, speed=80)` sends slide `0..100` and velocity 24.

Same prefs but with `globalMinStroke=20, globalMaxStroke=80`:

- `SetSpeed(25)` still sends 7.
- `Preset(rangeMin=0, rangeMax=100, speed=80)` sends velocity 24 and slide `20..80` (squashed into the band).

The caps are enforced in `Dispatch.dispatch()`, not in the API client. The client itself accepts and forwards 0–100.

## Adding a new command

Three steps, exactly as in `vega`.

### 1. Pick the action

If an existing `VoiceAction` shape fits, reuse it. Examples:

- "full" → `SetRange(min=0, max=100)`.
- "narrow" → `SetRange(min=40, max=60)`.
- "double" → `AdjustSpeed(+30)`.

If you need a new shape, add a `data class` to `dispatch/VoiceAction.kt` and a `when` branch to `Dispatch.dispatch()`.

### 2. Wire the dispatch

Edit `dispatch/Dispatch.kt`:

```kotlin
val LABELS: List<String> = listOf(
    "begin", "stop", "faster", "slower",
    "deeper", "shallow", "random",
    "edge",                             // <-- new
)

val PROMPTS: Map<String, String> = mapOf(
    ...
    "edge" to "edge",                   // <-- the displayed word on the training card
)

private val TABLE: Map<String, VoiceAction> = mapOf(
    ...
    "edge" to VoiceAction.Pattern("edge"),    // <-- new
)
```

Order in `LABELS` is the order the user sees during training.

### 3. Record templates

Re-record. From **Settings → Re-record voice samples** (opens Training without wiping existing takes) or **Settings → Clear all recordings** (wipes everything with confirmation, then opens Training). Tap **Build templates** when done. There's no post-Build verification pass anymore: the per-label cross-validation gate runs inline after each label's 3 takes (catches inconsistent takes when the audio is freshest), and the Voice tab gives instant feedback on the first real use — so a collision shows up the first time you try the new word, not after another forced 9-prompt gauntlet.

### Naming conventions

- One word, ideally one syllable. "deeper" works; "go deeper" is two captures and the matcher gets confused by the silence between words.
- **Avoid pairs that sound alike.** This bit us hard with `start` / `stop` (renamed `start → begin`) and with the original `storm / wave / heartbeat / surge / breathe` pattern set (all fricative-and-nasal-heavy, all collided). With CMN applied, MFCCs of acoustically similar words score close enough that the wrong template often wins. Maximise separation: vary syllable count, vowel position (front "ee" vs back "oh"), and consonant family (plosive vs nasal vs fricative).
- Prefer words with distinctive consonants. Fricatives (`s`, `sh`, `f`, `v`) survive noise well; vowel-only words like "oh" don't.
- Lowercase only. `TemplateStore.sanitize()` will lowercase anyway; using lowercase in source keeps debug output legible.

## Why fixed labels and not free speech

The whole point of v2 is to skip the speech-to-text step that fails for non-standard voices. Allowing free phrases requires either:

1. Running an ASR ("set speed to fifty" → `SetSpeed(50)`), which is exactly what v1 does and exactly what we're avoiding.
2. Running an LLM ("a little faster" → `AdjustSpeed(+10)`), which is what vega's *dormant* LLM path does. It works, but at the cost of cloud round-trips, API spend, and the failure modes of speech recognition we ran from in the first place.

Phase 1 chooses the constraint: seven labels, fixed actions, perfect determinism. New utterances become new labels in the dispatch table, not new parsing rules.

## Pattern engine

`dispatch/PatternEngine.kt` is a Kotlin port of `homeassistant/custom_components/handy/patterns.py`. Each named pattern is a `suspend fun(api, intensity)` that loops forever, calling `api.setSpeed(envelope × intensity)` then `delay(durationMs)`. The engine owns a single `Job` — starting a new pattern cancels the running one, and `STOP` cancels everything.

The 12 envelopes:

| Name | Envelope | Cycle |
| --- | --- | --- |
| `pulse` | lub (0.85, 150 ms) → gap (0, 180 ms) → dub (0.7, 150 ms) → rest (0, 1500 ms) | ~2 s |
| `ripple` | smooth sine 0..1..0 over 8 s, 32 steps | 8 s |
| `surge` | 24-step linear ramp to peak (12 s), hold (4 s), rest (3 s) | ~19 s |
| `breathe` | sine confined to 0.10..0.60 over 6 s, never silent, never peak | 6 s |
| `thunder` | random envelope 0..1 for 0.4..1.6 s, repeat | unpredictable |
| `tick` | steady metronome 0.75 / 200 ms ↔ 0 / 200 ms | 400 ms |
| `tease` | 0.40 / 0.5 s → silent 2 s → 0.30 / 0.3 s → silent 3 s | ~5.8 s |
| `edge` | 5 s ramp to 0.95, hold 6 s, drop 4 s | ~15 s |
| `rush` | constant 0.95, sliced into 500 ms chunks for cancellability | n/a |
| `throb` | heavy 0.90 / 400 ms ↔ 0.10 / 700 ms | ~1.1 s |
| `bounce` | sine 0.40..0.90 over 2 s, 16 steps | 2 s |
| `climax` | 0.30 → 0.50 → 0.70 → 0.85 (3 s each) → 1.0 hold (8 s) → reset (2 s) | ~22 s |

No envelope is bound to a voice label by name anymore. The `random` voice command picks one at random via `PatternEngine.startRandom` (`PATTERNS.keys.random()`); all twelve are also tap-only on the Manual tab, each with its own stroke range that's set just before the envelope starts (see `ControlActivity.TapPattern` for the data class). `tick` is the old `pulse` metronome envelope, renamed back when `pulse` was dropped from the spoken set.

### Lifecycle and interaction

- `PatternEngine.start(name, intensity)` cancels any prior pattern, calls `api.start()` if `isRunning` is false, and launches the envelope on the activity's `lifecycleScope`. Returns `false` on unknown name or `api.start()` failure.
- `PatternEngine.cancel()` cancels the job without stopping the device.
- `PatternEngine.stop()` cancels and calls `api.stop()`.
- The STOP button on `ControlActivity` calls `patternEngine.cancel()` then `api.stop()`. Voice `Stop` action does the same through dispatch.
- Activity destruction implicitly cancels everything via `lifecycleScope`.

### Adding a new pattern

1. Add a `suspend fun newpattern(api: HandyV2ApiClient, intensity: Int)` to `PatternEngine.companion` that loops forever emitting `(envelope, durationMs)` via the shared `emit()` helper.
2. Add the entry to the `PATTERNS` map: `"newpattern" to ::newpattern`.
3. To bind it to a voice label, add a row to `Dispatch.TABLE`: `"newword" to VoiceAction.Pattern("newpattern")`. Then add `"newword"` to `LABELS` + `PROMPTS` and re-record. (Or leave it tap-only and reach it via the `random` command, which already includes every pattern in `PATTERNS`.)
4. To bind it to a Manual tap button, add a grid button to `activity_control.xml`, an entry to `ControlActivity.tapOnlyPatterns` with its stroke range, and click-wire it in `wireManualPatterns`.

## Divergence from vega's vocabulary

We *used* to mirror vega's word list exactly so the habit would transfer between Pi and phone. That broke down when the pattern words (`heartbeat / wave / surge / breathe / storm`) turned out to collide acoustically for Owen's voice: the MFCC matcher had trouble distinguishing them, and verification kept failing on whichever one was second-trained. Android v2 first tried acoustically distant pattern replacements (`thunder / ripple / pulse`), then dropped fixed pattern words from voice altogether — they weren't useful spoken — in favour of `deeper / shallow` (live stroke-length control) and `random` (surprise envelope). The pattern envelopes remain tap-only on Manual.

If you change vocabulary here, decide whether vega should follow. The control words (`begin / stop / faster / slower`) still match. The pattern words don't, by design — they were chosen for *this* matcher on *this* voice.
