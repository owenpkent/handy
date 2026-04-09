# Alexa Handy Control — Usage Guide

How to use the Alexa skill for hands-free Handy control.

---

## Getting Started

**Open the skill:**

> "Alexa, open Handy"

Alexa responds: *"Handy connected. Limits 0 to 30. What would you like to do?"*

The limits shown are your global min and max. These come from your Lambda environment variables (`GLOBAL_MIN` and `GLOBAL_MAX`) and are enforced on every command. If you set `GLOBAL_MAX=30`, no preset or command will ever move the device beyond 30%.

**One-shot commands** (without opening first):

> "Alexa, tell Handy to start"
> "Alexa, tell Handy to go vibrate"
> "Alexa, tell Handy to stop"

---

## Basic Commands

| Say this | What happens |
|---|---|
| "start" | Starts motion |
| "stop" | Stops motion |
| "faster" | Speed +10% |
| "slower" | Speed -10% |
| "set speed to 50" | Sets speed to exactly 50% |
| "help" | Lists commands and current limits |

---

## Grid Presets

Two-word commands: a **speed** (slow/medium/fast) and a **position** (top/middle/bottom/full).

| Say this | Speed | Actual stroke range (with max=30) |
|---|---|---|
| "slow full" | 25% | 0–30 |
| "slow top" | 25% | 15–27 |
| "medium middle" | 50% | 9–21 |
| "fast full" | 80% | 0–30 |
| "fast bottom" | 80% | 3–15 |

The stroke ranges shown are examples with `GLOBAL_MAX=30`. The preset's design range (e.g. "full" = 0–100%) gets scaled proportionally into your 0–30 window. With different limits, the numbers change accordingly.

**All 15 grid presets:**

| | Top (50-90%) | Middle (30-70%) | Bottom (10-50%) | Full (0-100%) | Natural (20-80%) |
|---|---|---|---|---|---|
| **Slow** (25%) | slow top | slow middle | slow bottom | slow full | slow natural |
| **Medium** (50%) | medium top | medium middle | medium bottom | medium full | medium natural |
| **Fast** (80%) | fast top | fast middle | fast bottom | fast full | fast natural |

---

## Sensation Presets

One-word commands for specific feelings. These are tuned speed + stroke combos.

| Say this | What it does | With max=30 |
|---|---|---|
| **"vibrate"** | Tiny rapid strokes, vibration-like buzz. Speed 100%, narrow range. | Range 14–17 |
| **"pulse"** | Small fast strokes. Speed 80%, slightly wider. | Range 12–18 |
| **"tease"** | Slow, shallow strokes near the tip. Speed 15%. | Range 23–29 |
| **"deep"** | Slow full-length strokes. Speed 20%. | Range 0–30 |
| **"intense"** | Fast, wide strokes. Speed 90%. | Range 3–27 |
| **"edge"** | Fast short strokes at the top. Speed 70%. | Range 18–27 |
| **"gentle"** | Very slow, medium range. Speed 10%. | Range 9–21 |
| **"jackhammer"** | Maximum speed, maximum range. Speed 100%. | Range 0–30 |

**Synonyms that also work:**
- "buzz" or "vibe" → vibrate
- "throb" → pulse
- "hard" or "aggressive" → intense
- "soft", "light", or "easy" → gentle
- "beast mode" or "full power" → jackhammer

---

## Funscript Patterns (Experimental)

These are time-based waveforms — the speed and position change over time in a repeating cycle. They use HSSP mode, which may or may not work with your device's firmware (see notes below).

| Say this | What it does |
|---|---|
| **"play wave pattern"** | Starts fast, gradually slows to near-still, then rebuilds. 44-second cycle. |
| **"play surge pattern"** | Gentle start, slowly builds to an intense burst, then resets. 40-second cycle. |
| **"play heartbeat pattern"** | Two quick strokes followed by a pause, like a heartbeat. 1.6-second cycle. |
| **"play storm pattern"** | Chaotic, unpredictable bursts of varying speed and depth. 12-second cycle. |
| **"play breathe pattern"** | Slow rhythmic strokes like deep breathing, gradually quickens. 44-second cycle. |

**How global limits work with patterns:** Before playback, the device's slide range is set to your global min/max. The funscript positions (0-100) get mapped within that range by the device firmware.

**If a pattern fails:** Alexa will say the error and fall back to HAMP mode. The sensation presets still work.

---

## Controlling Limits

### Permanent limits (recommended)

Set these once in your Lambda environment variables in the AWS Console:

| Variable | What it does | Example |
|---|---|---|
| `GLOBAL_MIN` | Minimum position the device will ever reach | `0` |
| `GLOBAL_MAX` | Maximum position the device will ever reach | `30` |

These apply to every session automatically. You never need to set them by voice.

### Temporary session limits

You can override limits during a session:

> "set global max to 40"

Alexa responds: *"Global maximum set to 40. This lasts until the session ends. To make it permanent, set GLOBAL_MAX in Lambda."*

The override applies to all subsequent commands in that session. When the session ends (you stop talking, say "Alexa stop", or ~8 seconds of silence), it reverts to the environment variable defaults.

### Direct stroke control

You can also set an explicit stroke range:

> "set stroke 5 to 20"

This is clamped to your global limits. If you say "set stroke 0 to 100" with max=30, it becomes 0 to 30.

---

## Session Behavior

Alexa sessions time out after about **8 seconds of silence**. When that happens:

- Session limits revert to your Lambda environment variable defaults
- The device **keeps doing whatever it was last told** (it doesn't stop)
- To issue another command, re-invoke: "Alexa, tell Handy to stop"

**To keep a session alive**, just keep talking within the 8-second window. You can chain commands:

> "Alexa, open Handy"
> "vibrate"
> "faster"
> "set global max to 40"
> "intense"
> "stop"

### Emergency stop

Any of these will stop the device:

- **"stop"** (within an active session)
- **"Alexa, stop"** (stops device and ends the skill)
- **"Alexa, tell Handy to stop"** (one-shot, works anytime)

---

## Example Sessions

### Quick use

> "Alexa, tell Handy to go vibrate"

One command. Connects, sets vibrate preset within your limits, starts motion.

### Exploring presets

> "Alexa, open Handy"
> *"Handy connected. Limits 0 to 30."*
> "gentle"
> *"gentle. Speed 10, range 9 to 21."*
> "faster"
> *"Speed increased to 20 percent."*
> "tease"
> *"tease. Speed 15, range 23 to 29."*
> "stop"
> *"Stopped."*

### Adjusting limits mid-session

> "Alexa, open Handy"
> *"Handy connected. Limits 0 to 30."*
> "set global max to 50"
> *"Global maximum set to 50. This lasts until the session ends."*
> "fast full"
> *"fast full. Range 0 to 50."*
> "stop"

---

## Tips

- **Start with sensations.** "Vibrate", "tease", "gentle" are the easiest way to explore different feelings.
- **Your limits protect you.** With `GLOBAL_MAX=30`, even "jackhammer" only goes to 0-30. You can experiment freely.
- **Speed and range are independent.** After a preset, you can still say "faster" or "slower" to fine-tune the speed without changing the stroke range.
- **One-shot is fastest.** "Alexa, tell Handy to vibrate" is one sentence, no session needed.
- **Help reminds you.** Say "help" anytime to hear available commands and your current limits.
