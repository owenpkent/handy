# Alexa Skill Setup Guide

This guide walks you through deploying the Handy Voice Control Alexa skill. You'll set up two things: the Lambda function (runs your code) and the Alexa skill (handles voice).

**Time required:** ~20 minutes (mostly copy-paste into web consoles)
**Cost:** $0/month (AWS free tier)
**Prerequisites:** An Amazon account, your Handy connection key

---

## Part 1: Create the AWS Lambda Function

### Step 1: Open AWS Lambda

1. Go to [https://aws.amazon.com/lambda/](https://aws.amazon.com/lambda/)
2. Click **"Sign in to the Console"** (use your Amazon account — same login as Alexa/shopping)
3. If this is your first time, you'll create an AWS account. The free tier covers this project entirely.
4. In the AWS Console, search for **"Lambda"** in the top search bar and click it.

### Step 2: Set your region

In the top-right corner of the AWS Console, select a region close to you:
- **US East (N. Virginia)** — `us-east-1` (recommended for US, required for Alexa skills)
- **EU (Ireland)** — `eu-west-1` (for Europe)

**Important:** For Alexa skills, use **us-east-1** (N. Virginia) if you're in the US.

### Step 3: Create the function

1. Click **"Create function"**
2. Select **"Author from scratch"**
3. Configure:
   - **Function name:** `handy-alexa-control`
   - **Runtime:** `Node.js 22.x` (or the latest Node.js option available)
   - **Architecture:** `x86_64`
4. Click **"Create function"**

### Step 4: Paste the code

1. In the **Code** tab, you'll see an inline code editor with an `index.mjs` file
2. **Select all** the existing code and **delete it**
3. Open the file `alexa/lambda/index.mjs` from this project
4. **Copy the entire contents** and paste into the editor
5. Click **"Deploy"** (the orange button)

### Step 5: Set environment variables

1. Click the **"Configuration"** tab
2. Click **"Environment variables"** in the left sidebar
3. Click **"Edit"**
4. Add the following environment variables:

   | Key | Value | Required |
   |---|---|---|
   | `HANDY_CONNECTION_KEY` | Your Handy connection key (same one you use in the web app) | Yes |
   | `GLOBAL_MIN` | Default global minimum position, 0-100 (e.g., `10`) | No (default: 0) |
   | `GLOBAL_MAX` | Default global maximum position, 0-100 (e.g., `80`) | No (default: 100) |
   | `FUNSCRIPT_BASE_URL` | URL where your funscript files are hosted (see Part 4) | No |

5. Click **"Save"**

**Note:** Global limits can also be changed per-session via voice ("set global max to 80"). The env vars set the defaults.

### Step 6: Increase the timeout

Alexa allows up to 8 seconds for a response, and the Handy API calls take a moment.

1. Still in the **"Configuration"** tab, click **"General configuration"**
2. Click **"Edit"**
3. Set **Timeout** to **10 seconds** (0 min, 10 sec)
4. Click **"Save"**

### Step 7: Add the Alexa trigger

1. Click the **"Configuration"** tab, then **"Triggers"**
2. Click **"Add trigger"**
3. Select **"Alexa Skills Kit"** from the dropdown
4. For **"Skill ID verification"**, select **"Disable"** for now (we'll come back to add the skill ID later)
5. Click **"Add"**

### Step 8: Copy the Lambda ARN

1. At the top of the Lambda page, you'll see the **Function ARN** — it looks like:
   `arn:aws:lambda:us-east-1:123456789012:function:handy-alexa-control`
2. **Copy this ARN.** You'll need it in Part 2.

---

## Part 2: Create the Alexa Skill

### Step 1: Open the Alexa Developer Console

1. Go to [https://developer.amazon.com/alexa/console/ask](https://developer.amazon.com/alexa/console/ask)
2. Sign in with the **same Amazon account** your Echo device is registered to
3. Click **"Create Skill"**

### Step 2: Configure the skill

1. **Skill name:** `Handy Control`
2. **Primary locale:** `English (US)` (or your locale)
3. **Type of experience:** Choose **"Other"**
4. **Model:** Choose **"Custom"**
5. **Hosting:** Choose **"Provision your own"** (since we're using our own Lambda)
6. Click **"Next"**
7. On the template page, select **"Start from Scratch"**
8. Click **"Next"**, then **"Create Skill"**

### Step 3: Set up the interaction model

1. In the left sidebar, click **"Interaction Model"** then **"JSON Editor"**
2. **Delete everything** in the JSON editor
3. Open the file `alexa/interactionModels/en-US.json` from this project
4. **Copy the entire contents** and paste into the JSON editor
5. Click **"Save"**
6. Click **"Build Skill"** (top of the page) — wait for it to complete (~30 seconds)

### Step 4: Set the Lambda endpoint

1. In the left sidebar, click **"Endpoint"**
2. Select **"AWS Lambda ARN"**
3. In the **"Default Region"** field, paste the **Lambda ARN** you copied in Part 1, Step 8
4. Click **"Save Endpoints"**

### Step 5: Copy the Skill ID (optional, for tighter security)

1. The Skill ID is shown at the top of the endpoint page (starts with `amzn1.ask.skill.`)
2. Go back to AWS Lambda → your function → Configuration → Triggers
3. Edit the Alexa Skills Kit trigger and paste the Skill ID for verification

---

## Part 3: Test It

### Test in the Developer Console

1. Click the **"Test"** tab at the top of the Alexa Developer Console
2. Change the dropdown from **"Off"** to **"Development"**
3. In the text box, type: `open handy`
4. You should see: "Handy connected. What would you like to do?"
5. Type: `start` → should respond "Started."
6. Type: `fast full` → should respond "fast full preset activated."
7. Type: `stop` → should respond "Stopped."

### Test with your Echo device

Since the skill is in "Development" mode, it's automatically available on any Echo device registered to the same Amazon account:

- **"Alexa, open Handy"** — opens the skill, connects to your device
- **"Start"** — starts motion
- **"Fast full"** — activates the fast/full-range preset
- **"Set speed to 70"** — sets speed to 70%
- **"Faster"** / **"Slower"** — adjusts speed by 10%
- **"Stop"** — stops motion
- **"Alexa, stop"** — stops device and exits the skill

One-shot commands (without opening the skill first):
- **"Alexa, tell Handy to start"**
- **"Alexa, tell Handy to go fast full"**
- **"Alexa, tell Handy to stop"**

---

## Voice Command Reference

### Basic Controls

| Command | What it does |
|---|---|
| "start" / "go" / "begin" / "play" | Start motion |
| "stop" / "halt" / "pause" | Stop motion |
| "set speed to 70" | Set speed to 70% |
| "faster" / "speed up" | Increase speed by 10% |
| "slower" / "slow down" | Decrease speed by 10% |
| "help" | List available commands |

### Grid Presets (speed + position)

| Command | Speed | Stroke Range* |
|---|---|---|
| "slow top" | 25% | 50-90% |
| "slow middle" | 25% | 30-70% |
| "slow bottom" | 25% | 10-50% |
| "slow full" | 25% | 0-100% |
| "medium top" | 50% | 50-90% |
| "medium middle" | 50% | 30-70% |
| "medium bottom" | 50% | 10-50% |
| "medium full" | 50% | 0-100% |
| "fast top" | 80% | 50-90% |
| "fast middle" | 80% | 30-70% |
| "fast bottom" | 80% | 10-50% |
| "fast full" | 80% | 0-100% |

*Stroke ranges are scaled within your global min/max limits.

### Sensation Presets

| Command | Speed | Stroke Range* | Feeling |
|---|---|---|---|
| "vibrate" / "buzz" | 100% | 45-55% | Tiny rapid strokes, vibration-like |
| "pulse" / "throb" | 80% | 40-60% | Small fast strokes |
| "tease" | 15% | 75-95% | Slow, shallow at the tip |
| "deep" | 20% | 0-100% | Slow full-length strokes |
| "intense" / "hard" | 90% | 10-90% | Fast, wide strokes |
| "edge" | 70% | 60-90% | Fast short strokes at the top |
| "gentle" / "soft" | 10% | 30-70% | Very slow, medium range |
| "jackhammer" / "beast mode" | 100% | 0-100% | Max speed, max range |

*Stroke ranges are scaled within your global min/max limits.

### Funscript Patterns (experimental, requires HSSP)

| Command | Duration | Description |
|---|---|---|
| "play wave pattern" | 44s cycle | Starts fast, gradually slows, then rebuilds |
| "play surge pattern" | 40s cycle | Slow build to intense peak, then resets |
| "play heartbeat pattern" | 12.8s cycle | Double-pulse rhythm like a heartbeat |
| "play storm pattern" | 12s cycle | Chaotic, unpredictable bursts |
| "play breathe pattern" | 44s cycle | Slow rhythmic strokes like deep breathing |

These require `FUNSCRIPT_BASE_URL` to be configured and HSSP to work with your device. See Part 4.

### Range & Limit Controls

| Command | What it does |
|---|---|
| "set stroke 20 to 80" | Set stroke range (clamped to global limits) |
| "set global minimum to 10" | Set global min — all presets scale within this |
| "set global maximum to 80" | Set global max — nothing exceeds this |

---

## Troubleshooting

### "Handy is not connected"
- Make sure your Handy is powered on and connected to WiFi
- Check that the connection key in the Lambda environment variable is correct
- Test the connection key in the web app first

### Alexa says "There was a problem with the requested skill's response"
1. In AWS Lambda, click the **"Monitor"** tab
2. Click **"View CloudWatch Logs"**
3. Open the latest log stream to see error details
4. Common cause: wrong connection key, or Lambda timeout too short

### Skill doesn't appear on Echo
- Make sure you're signed into the Alexa Developer Console with the **same Amazon account** as your Echo
- Make sure the Test tab is set to **"Development"** (not "Off")
- Try saying "Alexa, enable Handy Control" as a fallback

### Commands aren't recognized
- Make sure you built the interaction model (Step 3.6)
- Try the exact phrases from the command reference above
- Use the Test tab's text input to rule out voice recognition issues

### Session times out too quickly
The Alexa session closes after ~8 seconds of silence. Options:
- Use one-shot commands: "Alexa, tell Handy to go fast full"
- Say your next command within 8 seconds
- Say "Alexa" to re-engage, then "tell Handy to..." for the next command

---

## Part 4: Funscript Patterns (Optional, Experimental)

Funscript patterns use HSSP mode to play time-based waveforms on the device. This is experimental — HSSP may not fully work with the public API (see `docs/HANDY_API_NOTES.md`).

### How it works

Funscripts define position (0-100) over time in milliseconds. The device downloads the file and plays it independently. Before playback, the Lambda sets the device's slide range to your global min/max, so all funscript positions are constrained within your limits.

### Hosting the funscript files

The files in `alexa/funscripts/` need to be hosted at a public URL. Options:

**Option A: GitHub (if your repo is public)**
1. Push the funscript files to your repo
2. Use raw GitHub URLs: `https://raw.githubusercontent.com/YOUR_USER/handy-v1/main/alexa/funscripts`
3. Set `FUNSCRIPT_BASE_URL` in Lambda to that URL

**Option B: AWS S3 (recommended for private hosting)**
1. Create an S3 bucket (e.g., `handy-funscripts`)
2. Upload the `.funscript` files from `alexa/funscripts/`
3. Make the files publicly readable (or use a CloudFront distribution)
4. Set `FUNSCRIPT_BASE_URL` to `https://handy-funscripts.s3.amazonaws.com`

**Option C: Any static file host**
- Netlify, Vercel, or any web server that can serve static JSON files
- Just make sure the files are accessible via HTTPS

### Testing

1. Set `FUNSCRIPT_BASE_URL` in your Lambda environment variables
2. In the Alexa test console, try: `play wave pattern`
3. If HSSP isn't supported, you'll get an error message and the skill will fall back to HAMP mode

### If HSSP doesn't work

This is expected — the HSSP API hasn't been fully tested. The sensation presets (vibrate, pulse, tease, etc.) work reliably since they use HAMP mode. The funscript patterns are there for when/if HSSP becomes accessible.

---

## File Reference

```
alexa/
  lambda/
    index.mjs          ← Lambda handler (paste into AWS console)
  interactionModels/
    en-US.json          ← Alexa interaction model (paste into skill JSON editor)
  funscripts/
    wave.funscript      ← Gradual speed wave pattern
    surge.funscript     ← Slow build to intense peak
    heartbeat.funscript ← Double-pulse heartbeat rhythm
    storm.funscript     ← Chaotic unpredictable bursts
    breathe.funscript   ← Slow rhythmic breathing pattern
  skill.json            ← Skill manifest (reference only, not needed for manual setup)
```

---

## Architecture

```
"Alexa, tell Handy to go fast full"
        │
        ▼
  Echo Device ──► Alexa Cloud (voice → intent parsing)
                       │
                       ▼
               AWS Lambda (index.mjs)
                       │
                       ▼  PUT /hamp/start
                       ▼  PUT /hamp/velocity {velocity: 80}
                       ▼  PUT /slide {min: 0, max: 100}
                       │
                       ▼
             Handy Cloud API ──► Your Handy Device
                       │
                       ▼
              "fast full preset activated."
                  (spoken by Alexa)
```
