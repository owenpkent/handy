# Training guide

How to record your voice samples for the best results. User-facing; no code knowledge needed.

## How it works in one paragraph

The first time you open Handy Voice V2, the app asks you to record three takes of each of nine commands. Each take is a 2.5 second window with a brief "Get ready..." prompt before the mic opens. After all 27 recordings, the app turns those into mathematical fingerprints (MFCC sequences, mean-normalised per utterance) that live on the phone forever. Every time you later speak a command, the app compares what you just said against the nine fingerprints and picks the one that looks most similar. The app never tries to "understand" English — it only checks "does this sound like the way Owen said `faster`?"

That's why the system works for non-standard speech: it doesn't need to. It only needs your voice to be similar to itself between training and use.

## What you'll see

The Training screen shows one command at a time on a big card:

```
        Teach Me Your Voice
        Command 1 of 9

  ┌─────────────────────────────┐
  │                             │
  │      Say:                   │
  │      begin                  │
  │                             │
  │      Listening — speak now  │
  │      ● ○ ○                  │
  │                             │
  │      ▒▒▒▒▒░░░░░░░░ peak     │
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
- **Peak meter** is a thin horizontal bar that grows with your voice while the mic is open. If it stays flat-zero you have a microphone problem (see [TROUBLESHOOTING](TROUBLESHOOTING.md#mic-not-picking-anything-up)).
- **Record button**:
  - **Blue (Record)**: idle, tap once to start the auto-advance session.
  - **Indigo (Cancel)**: a session is running. Tap to cancel.
  - **Sage (Re-record)**: this label is complete with 3 takes. Tapping starts a re-record of *this label only*.
- **Play** plays back the most recent take so you can verify it sounds right.
- **Prev / Next / Skip** move between commands. Skip during a running session jumps the session forward.
- **Build templates** stays enabled the whole time. Tap it whenever you're done; the verification pass runs before onboarding completes.

## Auto-advance recording session

You tap **Record** *once*. The app then walks the whole catalog automatically.

For each (label, take):

1. The card updates: "Get ready..." (about half a second). The mic is *not* open yet.
2. The card flips to "Listening — speak now" and the peak meter activates. The mic is open for 2.5 seconds.
3. **Speak the word once, clearly, at normal volume.** When the mic closes, an ack beep plays and a dot fills in.
4. Brief pause (~0.9 s) and the next take starts. On the third take, after a pause (~1.5 s), a toast pops ("Nice! Next: stop") and the prompt changes to the next label.

After all takes of a label, a **cross-validation gate** scores them against each other. If one take is noticeably different from the others, a dialog pops up:

> Take 2 of 'begin' looks off — Re-record this take so the engine can be more confident?

Tap **Re-record** to delete that take and capture a fresh one; tap **Skip** to accept all three as-is.

The session ends when all 27 takes are recorded. Tap **Build templates** to continue.

## Verification pass (post-Build)

When you tap **Build templates**, the app runs one final check: for each label it asks you to say it once and confirms the engine returns that exact label.

For each label:

1. The prompt shows the word and "Test — get ready...".
2. The mic opens for 2.5 seconds. **Say the word once.**
3. If the engine picks the right label, the card says "✓ Recognised 'begin'", a beep plays, and the next label starts.
4. If the engine picks a different label (or a score above your threshold), a dialog appears:

   > 'begin' not recognised. Heard 'stop' instead. Re-record samples for 'begin'?

   - **Re-record** wipes that label's takes and runs the same auto-advance loop you used in training (3 fresh takes), then retries the verification.
   - **Skip** accepts the takes as-is and continues. You can fix it later from the Voice tab's **Re-record** button.

The verification pass is the most reliable predictor of in-use behaviour. If two of your labels keep colliding (you say A but the engine returns B), the dialog will catch it before you leave training rather than after.

## Manual single-take re-record

The **Sage Re-record** state of the record button (after a label is fully taken) is for fixing one specific label without redoing everything. Tap it on the label you want to fix; the app clears just that label's 3 takes and starts a fresh session for it.

## How to record well

These four habits are the difference between "matches every time" and "have to repeat myself a lot."

### 1. Speak naturally, not into the mic

Hold the phone the way you normally would when using it. Don't lean in close to the microphone for training and then sit back when using the app — the two will not match.

If you're going to use Handy Voice V2 with the phone propped on the bedside table, prop it on the bedside table now and record from there. If you'll use it with the phone in your lap, record with it in your lap. **Match the recording posture to the using posture.**

### 2. Speak the same way each take

Three takes of `faster` should each be *you saying `faster`*, in the same posture, at the same volume, at roughly the same speed. Variation between takes makes the template tolerate variation later — but only the variation you actually expect to use.

Do *not* deliberately say it three different ways "to give it more to work with." That trains the matcher on a wider range and lets unrelated utterances slip in.

### 3. Wait for the "Listening — speak now" prompt

The capture is 2.5 seconds from the moment the prompt flips from "Get ready..." to "Listening — speak now" and the peak meter activates. The pre-roll gives you about half a second to focus; speak any time after the prompt changes.

Practical rule: **wait, then speak.** A small pause at the start of the capture is fine; speaking *before* the prompt changes means the start of your word lands in the silent pre-roll and gets cut off.

### 4. One word per take

The capture window is 2.5 seconds. Most single words fit comfortably with room to spare. Two-word commands are tight; multi-word commands (`set speed to fifty`) don't fit and shouldn't be on this list.

If a command in `Dispatch.LABELS` ever takes more than 2.5 seconds to say comfortably, `WINDOW_SECONDS` needs adjustment — see [VOICE_ENGINE.md](VOICE_ENGINE.md). Keep `TrainingActivity.WINDOW_SECONDS` and `ControlActivity.WINDOW_SECONDS` in sync; the matcher's DTW band can't bridge a mismatch.

## Re-training

Three paths, easiest first:

**Re-record one label** — Training screen, navigate to the label, the **Sage Re-record button** wipes just that label and runs a fresh 3-take session. Or, from the Voice tab tap **Re-record** to open Training without wiping anything; the auto-advance session skips already-full labels.

**Re-record everything (in place)** — Voice tab → **Re-record** opens Training; if all labels are already full, navigate to one and tap the Sage Re-record to start fresh on that label. Repeat for every label you want to redo. Existing labels you don't touch are preserved.

**Wipe and start over** — Voice tab → **Clear all** (with confirmation) deletes every template and routes back to Training fresh. Use this if you changed mics, your voice has changed significantly, or matches feel uniformly bad. Also use this after any code change that touches `WINDOW_SECONDS` or `Mfcc` (the format version bumps and old takes won't match anyway, but the user-facing path is the Clear all button).

Settings → **Re-record voice samples** is also available; it does the same thing as Voice tab → Re-record.

## Reading match scores

Open the debug panel on the Voice tab (**Show Debug** below the buttons). Every match emits a line:

```
[14:23:01] Heard: faster  score=8.2 margin=1.4
[14:23:05] Heard: stop  score=9.94 margin=0.04  (low confidence (margin 0.0))
[14:23:09] Heard: surge  score=45.1 margin=3.2  (below threshold)
```

- `score` is the [path-normalised DTW distance](VOICE_ENGINE.md#dtw). Lower is better. With CMN applied, expect scores in the 5–15 range for good matches; 30+ is the default cutoff.
- `margin` is the gap between this label and the next-best label. The match has to clear a relative margin gate (`runner-up − winner ≥ winner × 0.05`) in addition to the score threshold, or it's rejected as "low confidence".

**Reading the log to fix problems:**

- **`score=5–10` for correct matches** — typical with CMN. Your templates are clean.
- **`score=20+` for correct matches** — either your templates are inconsistent or the environment is much louder/different from training. Re-record in the same environment you'll use the app in.
- **`score=8` for *incorrect* matches** — two of your templates sound alike to the matcher. The post-training verification pass should have caught this; if not, re-record the offending label, or rename it to something more phonetically distinct.
- **`(low confidence)` repeating** — the winner and runner-up are too close. Either re-record one of them more crisply, or accept the rejection (you can also speak a touch slower so the consonants land clearer).
- **`(below threshold)`** — match was rejected by score (winner's distance > 30). Either your voice has drifted from training, or the audio capture was poor. Check the live peak meter on the Voice tab during your next attempt.

## When always-on listening misbehaves

Always-on mode runs a voice-activity detector on every block of mic input. When the VAD thinks you spoke, it waits about 1.8 seconds (so the rolling 2.5 s buffer ends up containing the *full* word, not just its first ~120 ms) and then hands the buffer to the matcher. Two failure modes:

- **Background noise triggers it.** Coughs, TV, room HVAC. The default VAD thresholds in `audio/Vad.kt` reject most non-speech, but they're not perfect. **Workaround:** switch to button mode in noisy environments (tap mic to start a continuous listening session, tap again to stop). **Long-term fix:** tighten VAD thresholds, or train a wake-word label.
- **Your speech triggers it before you finish, and the match fires on a partial word.** Less of a problem now that we wait 1.8 s after the trigger before snapshotting, but possible for unusually long words. **Workaround:** speak the command in one breath. If you have a word that genuinely takes more than 2 seconds to say, bump `WINDOW_SECONDS` in both Training and Control (they must match).

If always-on mode never fires when you speak, watch the live peak meter on the Voice tab. If it's flat-zero, the mic isn't reaching the app (see [TROUBLESHOOTING](TROUBLESHOOTING.md#mic-not-picking-anything-up)). If the meter moves but no match fires, your speech may be below the VAD energy threshold — speak a bit louder or move closer to the mic.

## Glossary

- **Take** — one 2.5 s recording of a command. You make three per command.
- **Template** — the MFCC sequence extracted from a take. Three templates per label.
- **MFCC** — mel-frequency cepstral coefficient. The mathematical fingerprint of a short window of audio. We compute one MFCC vector every 10 ms of the take and then subtract the per-coefficient mean across the utterance (CMN) to remove static loudness offset.
- **CMN** — cepstral mean normalisation. Without it, the energy-bearing c0 coefficient dominates the DTW distance for similarly-loud words.
- **DTW** — dynamic time warping. The algorithm that compares two MFCC sequences without requiring them to be the same length or aligned in time.
- **Threshold** — the maximum DTW distance that still counts as a match. Lower = stricter.
- **Margin gate** — additionally, the winner has to beat the runner-up by at least 5% of the winning score, or the match is rejected as ambiguous.
- **Design space** vs **device space** — internal representation. The Handy device's actual speed is `design × globalMaxSpeed / 100`. Voice commands speak design percentages; the cap lives in code.

## See also

- [VOICE_ENGINE.md](VOICE_ENGINE.md) — what MFCC and DTW are actually doing.
- [COMMANDS.md](COMMANDS.md) — the dispatch table; what each command does.
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md) — when something's wrong.
