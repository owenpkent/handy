# Training guide

How to record your voice samples for the best results. User-facing; no code knowledge needed.

## How it works in one paragraph

The first time you open Handy Voice V2, the app asks you to record three takes of each of nine commands. Each take is 1.5 seconds long. After all 27 recordings, the app turns those into mathematical fingerprints (MFCC sequences) that live on the phone forever. Every time you later speak a command, the app compares what you just said against the nine fingerprints and picks the one that looks most similar. The app never tries to "understand" English — it only checks "does this sound like the way Owen said `faster`?"

That's why the system works for non-standard speech: it doesn't need to. It only needs your voice to be similar to itself between training and use.

## What you'll see

The Training screen shows one command at a time on a big card:

```
        Teach Me Your Voice
        Command 1 of 9

  ┌─────────────────────────────┐
  │                             │
  │      Say:                   │
  │      start                  │
  │                             │
  │      Take 1 of 3            │
  │      ● ○ ○                  │
  │                             │
  │         ┌─────┐             │
  │         │ REC │             │
  │         └─────┘             │
  │                             │
  │      [ Play ]               │
  │                             │
  └─────────────────────────────┘

   [Prev]    [Skip]    [Next]

   [ Build templates ]
```

- **Top text** tells you which command (1 of 9, etc.).
- **Big word in the middle** is what you should say.
- **Take dots** show your progress: filled dots = completed takes for this command.
- **Record button**:
  - **Blue (Record)**: idle, tap to start recording.
  - **Teal (Stop)**: currently recording. The capture auto-stops after 1.5 seconds; you do not need to tap it.
  - **Light blue (Re-record)**: this take is done. Tap again to replace it.
- **Play** plays back the most recent take so you can verify it sounds right.
- **Prev / Next** move between commands without recording.
- **Skip** advances to the next command (you can come back later).
- **Build templates** lights up green after every command has at least one take. Tap it to finish.

## The recording loop

For each command:

1. Wait until you're ready. Tap **Record**.
2. The button turns teal. **Speak the word once, clearly and at normal volume.** The capture lasts 1.5 seconds.
3. After 1.5 seconds the button changes to "Re-record" (light blue). One dot in the row fills in.
4. *(Optional)* Tap **Play** to hear your take. If it sounds wrong (too quiet, mumbled, cut off), tap **Re-record** to replace it.
5. Tap **Record** again to make your second take. The take counter advances; the third take auto-advances you to the next command.

After all nine commands have at least one take, **Build templates** lights up. You can either:

- Tap it now (you've done the minimum) — but matches will be less reliable with only one take per command.
- Go back to any unfinished command and add takes 2 and 3.

## How to record well

These four habits are the difference between "matches every time" and "have to repeat myself a lot."

### 1. Speak naturally, not into the mic

Hold the phone the way you normally would when using it. Don't lean in close to the microphone for training and then sit back when using the app — the two will not match.

If you're going to use Handy Voice V2 with the phone propped on the bedside table, prop it on the bedside table now and record from there. If you'll use it with the phone in your lap, record with it in your lap. **Match the recording posture to the using posture.**

### 2. Speak the same way each take

Three takes of `faster` should each be *you saying `faster`*, in the same posture, at the same volume, at roughly the same speed. Variation between takes makes the template tolerate variation later — but only the variation you actually expect to use.

Do *not* deliberately say it three different ways "to give it more to work with." That trains the matcher on a wider range and lets unrelated utterances slip in.

### 3. Wait for the button to turn teal

If you start speaking before tapping Record, the start of your word is cut off. The capture is 1.5 seconds *from the moment the button turns teal*. There is no countdown — Android starts the mic immediately.

Practical rule: **tap, then breathe, then speak.** A small pause at the start is fine; cutting off the start is not.

### 4. One word per take

The capture window is 1.5 seconds. Most single words fit comfortably. Two-word commands (`slow top`) are tight; multi-word commands (`set speed to fifty`) don't fit and shouldn't be on this list.

If a command in `Dispatch.LABELS` ever takes more than 1.5 seconds to say comfortably, the frame size needs adjustment (see [VOICE_ENGINE.md](VOICE_ENGINE.md)).

## Re-training

Two paths:

**Re-record everything.** Settings → **Re-record voice samples** wipes the templates directory and starts Training from scratch. Use this if:

- Many commands feel unreliable.
- You changed mics (e.g. new phone, new car-mount setup).
- Your voice has changed significantly (illness, medication, throat surgery).

**Re-record one command.** Open Training (Settings → Re-record voice samples → just record the one you care about → Build templates). The other commands keep their existing takes.

Actually — Phase 1 doesn't have a per-command rewipe yet. You can ADB-delete a single folder under `filesDir/templates/<label>/` to reset just that label, or do a full re-record. A per-command "clear and redo" button in Settings is a [reasonable Phase 2 addition](../README.md#phase-2--3-roadmap).

## Reading match scores

Open the debug panel on the main screen (**Show Debug** at the bottom). Every match emits a line:

```
[14:23:01] Heard: faster  score=18.2 margin=12.4
[14:23:05] Heard: stop  score=45.1 margin=2.1  (below threshold)
```

- `score` is the [path-normalised DTW distance](VOICE_ENGINE.md#dtw). Lower is better. Anything under your threshold (default 30) counts as a match.
- `margin` is the gap between this label and the next-best label. A big margin means the engine is confident; a small one means two commands are competing.

**Reading the log to fix problems:**

- **`score=8.4`-ish for correct matches** — excellent, your templates are tight.
- **`score=20–30` for correct matches** — typical, threshold of 30 works.
- **`score=35+` for correct matches** — your templates are too inconsistent. Re-record.
- **`score=15` for *incorrect* matches** — two of your templates sound alike. Pick different words, or re-record the offenders to be more distinctive.
- **`margin < 3` consistently** — same problem: templates are crowding each other.

## When always-on listening misbehaves

Always-on mode runs a voice-activity detector on every block of mic input. When the VAD thinks you spoke, it hands the last 1.5 seconds to the matcher. Two failure modes:

- **Background noise triggers it.** Coughs, TV, room HVAC. The default VAD thresholds in `audio/Vad.kt` reject most non-speech, but they're not perfect. **Workaround:** switch to button mode in noisy environments. **Long-term fix:** tighten VAD thresholds, or train a wake-word label (see [README roadmap](../README.md#phase-2--3-roadmap)).
- **Your speech triggers it before you finish.** If you pause mid-utterance, VAD may decide voice has stopped and the matcher scores the half-utterance. **Workaround:** speak the command in one breath. **Long-term fix:** longer minimum-voice-duration before triggering; tunable.

If always-on mode never fires when you speak, your speech is below the energy threshold. Speak louder or move closer to the mic.

## Glossary

- **Take** — one 1.5 s recording of a command. You make three per command.
- **Template** — the MFCC sequence extracted from a take. Three templates per label.
- **MFCC** — mel-frequency cepstral coefficient. The mathematical fingerprint of a short window of audio. We compute one MFCC vector every 10 ms of the take.
- **DTW** — dynamic time warping. The algorithm that compares two MFCC sequences without requiring them to be the same length or aligned in time.
- **Threshold** — the maximum DTW distance that still counts as a match. Lower = stricter.
- **Design space** vs **device space** — internal representation. The Handy device's actual speed is `design × globalMaxSpeed / 100`. Voice commands speak design percentages; the cap lives in code.

## See also

- [VOICE_ENGINE.md](VOICE_ENGINE.md) — what MFCC and DTW are actually doing.
- [COMMANDS.md](COMMANDS.md) — the dispatch table; what each command does.
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md) — when something's wrong.
