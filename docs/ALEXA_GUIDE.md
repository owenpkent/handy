# Alexa Handy Control

Hands-free voice control for The Handy via an Alexa Custom Skill + AWS Lambda. Zero dependencies, $0/month (free tier).

```
"Alexa, tell Handy to go vibrate"
    │
    ▼
Echo Device ──► Alexa Cloud (voice → intent) ──► AWS Lambda ──► Handy API ──► Device
```

---

## Setup (~20 minutes)

### 1. Create the Lambda Function

1. Sign into [AWS Lambda Console](https://console.aws.amazon.com/lambda/) (same Amazon login as Alexa). Use region **us-east-1** (N. Virginia).
2. **Create function** → Author from scratch:
   - Name: `handy-alexa-control`
   - Runtime: `Node.js 22.x` or later
   - Architecture: `x86_64`
3. In the **Code** tab, replace the default `index.mjs` with the contents of `alexa/lambda/index.mjs` from this project. Click **Deploy**.
4. **Configuration → Environment variables → Edit**. Add:

   | Key | Value | Required |
   |---|---|---|
   | `HANDY_CONNECTION_KEY` | Your Handy connection key | Yes |
   | `GLOBAL_MIN` | Default minimum position, 0-100 | No (default: 0) |
   | `GLOBAL_MAX` | Default maximum position, 0-100 (recommended: `30`) | No (default: 100) |
   | `GLOBAL_MAX_SPEED` | Default maximum speed, 0-100 | No (default: 100) |
   | `FUNSCRIPT_BASE_URL` | URL where funscript files are hosted | No |

5. **Configuration → General configuration** → set **Timeout** to **10 seconds**.
6. **Configuration → Triggers → Add trigger** → select **Alexa Skills Kit**, disable skill ID verification for now. Click Add.
7. **Copy the Function ARN** from the top of the page.

### 2. Create the Alexa Skill

1. Go to the [Alexa Developer Console](https://developer.amazon.com/alexa/console/ask). Sign in with the **same Amazon account** as your Echo device.
2. **Create Skill**:
   - Name: `Handy Control`
   - Locale: `English (US)`
   - Type: Other → Custom → Provision your own → Start from Scratch
3. **Interaction Model → JSON Editor**: paste the contents of `alexa/interactionModels/en-US.json`. Click **Save**, then **Build Skill**. Pick an invocation name that's at least two words and unique — single common words like "handy" can collide with reserved words or public skills and produce *"X is not supported on this device"* errors.
4. **Endpoint** → AWS Lambda ARN → paste the ARN from step 1.7. Click **Save Endpoints**.
5. **Distribution tab** → fill in the required fields (One Sentence Description, Detailed Description, Example Phrases, Category, Keywords). Even for a private skill these must be filled before testing works. Anything reasonable is fine — this never gets published.
6. (Optional) Copy the Skill ID from the endpoint page back to your Lambda trigger for tighter security.

### 3. Test

In the Alexa Developer Console **Test** tab, set to **Development**, then type (replacing `<invocation>` with your chosen invocation name):
- `open <invocation>` → *"Handy connected. Range 0 to 30, max speed 50."*
- `vibrate` → *"vibrate. Speed 100, range 14 to 17."*
- `stop` → *"Stopped."*

If the simulator returns *"X is not supported on this device"* even after a successful build, use the **Manual JSON** tab with a `LaunchRequest` to test the Lambda directly. If that succeeds, the simulator is just stale — try a real Echo, or hard-refresh the dev console / toggle Test mode Off→Development.

The skill is automatically available on any Echo device registered to the same Amazon account.

---

## Usage

**Open the skill:**
> "Alexa, open Handy"

**One-shot commands** (no session needed):
> "Alexa, tell Handy to start"
> "Alexa, tell Handy to go vibrate"
> "Alexa, tell Handy to stop"

### Basic Commands

| Say this | What happens |
|---|---|
| "start" / "go" / "begin" | Start motion |
| "stop" / "halt" / "pause" | Stop motion |
| "set speed to 50" | Set speed to exactly 50% |
| "faster" / "speed up" | Speed +10% |
| "slower" / "slow down" | Speed -10% |
| "what can I say" / "what are my options" | Full walkthrough of all available commands |
| "help" | Quick summary of commands and current limits |

### Grid Presets

Two-word commands: **speed** + **position**. Stroke ranges are scaled within your global limits.

| | Top (50-90%) | Middle (30-70%) | Bottom (10-50%) | Full (0-100%) | Natural (20-80%) |
|---|---|---|---|---|---|
| **Slow** (25%) | slow top | slow middle | slow bottom | slow full | slow natural |
| **Medium** (50%) | medium top | medium middle | medium bottom | medium full | medium natural |
| **Fast** (80%) | fast top | fast middle | fast bottom | fast full | fast natural |

Example with `GLOBAL_MAX=30`: "fast full" → speed 80%, stroke 0–30.

### Sensation Presets

One-word commands for specific feelings.

| Say this | Speed | Stroke* | Feeling |
|---|---|---|---|
| **"vibrate"** (buzz, vibe) | 100% | 45-55% | Tiny rapid strokes, vibration-like |
| **"pulse"** (throb) | 80% | 40-60% | Small fast strokes |
| **"tease"** | 15% | 75-95% | Slow, shallow near the tip |
| **"deep"** | 20% | 0-100% | Slow full-length strokes |
| **"intense"** (hard) | 90% | 10-90% | Fast, wide strokes |
| **"edge"** | 70% | 60-90% | Fast short strokes at the top |
| **"gentle"** (soft, easy) | 10% | 30-70% | Very slow, medium range |
| **"jackhammer"** (beast mode) | 100% | 0-100% | Max everything |

*Stroke ranges are in design space — they get scaled into your global limits. With max=30, "vibrate" becomes range 14–17.

### Funscript Patterns (Experimental)

Time-based waveforms using HSSP mode. Requires `FUNSCRIPT_BASE_URL` env var. May not work with all firmware — falls back to HAMP on failure.

| Say this | Description |
|---|---|
| "play wave pattern" | Fast start, gradually slows, rebuilds. 44s cycle. |
| "play surge pattern" | Slow build to intense peak, resets. 40s cycle. |
| "play heartbeat pattern" | Double-pulse rhythm. 1.6s cycle. |
| "play storm pattern" | Chaotic unpredictable bursts. 12s cycle. |
| "play breathe pattern" | Slow rhythmic like deep breathing. 44s cycle. |

Global limits are applied before playback — the device's slide range is set to your min/max.

To host the funscripts, upload the files from `alexa/funscripts/` to any public HTTPS URL (S3 bucket, GitHub raw, Netlify, etc.) and set `FUNSCRIPT_BASE_URL` in Lambda.

### Range & Limit Controls

| Say this | What it does |
|---|---|
| "set stroke 20 to 80" | Set stroke range (clamped to global limits) |
| "set global minimum to 10" | Set global min — all presets scale within this |
| "set global maximum to 30" | Set global max — nothing exceeds this position |
| "set speed limit to 60" | Cap speed — no command will exceed this speed |

---

## Global Limits

Limits ensure the device never exceeds a safe range. All presets and commands respect them.

### Permanent (recommended)

Set these as Lambda environment variables — they apply to every session automatically:

| Variable | What it controls |
|---|---|
| `GLOBAL_MIN` | Minimum stroke position |
| `GLOBAL_MAX` | Maximum stroke position (recommended: `30`) |
| `GLOBAL_MAX_SPEED` | Maximum speed — no command will exceed this |

### Temporary (per-session)

> "set global max to 40"
> "set speed limit to 60"

Overrides the default for this session. Reverts to the env var value when the session ends (~8 seconds of silence or "Alexa, stop"). If you lower the speed limit mid-session and the current speed exceeds it, the speed is reduced immediately.

### Direct stroke control

> "set stroke 5 to 20"

Clamped to global limits. "Set stroke 0 to 100" with max=30 becomes 0–30.

---

## Session Behavior

- Sessions time out after **~8 seconds of silence**
- The device **keeps running** after session timeout — it doesn't auto-stop
- Session-set limits revert to env var defaults on timeout
- To stop after timeout: "Alexa, tell Handy to stop"
- To keep a session alive, issue commands within the 8-second window

### Emergency Stop

- **"stop"** — within an active session
- **"Alexa, stop"** — stops device and exits the skill
- **"Alexa, tell Handy to stop"** — one-shot, works anytime

---

## Example Sessions

**Quick one-shot:**
> "Alexa, tell Handy to go vibrate"

**Finding out what you can do:**
> "Alexa, open Handy" → *"Handy connected. Range 0 to 30, max speed 60."*
> "what can I say?" → *"Here's what you can tell me. To control: say start, stop, faster, slower..."*

**Exploring presets:**
> "Alexa, open Handy" → *"Limits 0 to 30."*
> "gentle" → *"Speed 10, range 9 to 21."*
> "faster" → *"Speed increased to 20."*
> "tease" → *"Speed 15, range 23 to 29."*
> "stop"

**Adjusting limits mid-session:**
> "Alexa, open Handy" → *"Limits 0 to 30."*
> "set global max to 50" → *"This lasts until the session ends."*
> "fast full" → *"Range 0 to 50."*
> "stop"

---

## Troubleshooting

| Problem | Fix |
|---|---|
| "Handy is not connected" | Device not powered on / not on WiFi / wrong connection key |
| "There was a problem with the requested skill's response" | Check CloudWatch Logs (Lambda → Monitor → View Logs). Usually wrong key or timeout too short. |
| "X is not supported on this device" (simulator) | Distribution tab fields not filled; or invocation name collides with a reserved/public skill name (try a unique multi-word name); or simulator cache is stale (toggle Test off→Development, hard-refresh). Confirm Lambda works via Manual JSON `LaunchRequest`. |
| "Dialog intents must not be empty" (build) | Remove the `dialog` block from the interaction model JSON. |
| Skill not on Echo | Alexa Console Test tab must be set to "Development". Must use same Amazon account as Echo. |
| Commands not recognized | Rebuild the interaction model. Try exact phrases from the tables above. |
| Session too short | Use one-shot commands ("Alexa, tell Handy to...") or speak within 8 seconds. |

---

## Files

```
alexa/
  lambda/index.mjs              ← Lambda handler (paste into AWS console)
  interactionModels/en-US.json  ← Interaction model (paste into skill JSON editor)
  funscripts/*.funscript        ← Pattern files for HSSP (host at a public URL)
  skill.json                    ← Skill manifest (reference only)
```
