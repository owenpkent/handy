# Alexa Integration Options for Handy Voice Control

## Overview

The Handy device is controlled via a cloud REST API (`https://www.handyfeeling.com/api/handy/v2`). Any system that can make HTTP requests with a connection key header can control it. This document evaluates approaches for adding Amazon Alexa voice control.

### Why Alexa?

For Owen's use case, Alexa offers a major advantage over the current Android app: **always-on, hands-free listening**. No need to press a mic button or hold a phone. Just speak from anywhere in the room.

---

## Approach 1: Alexa Custom Skill + AWS Lambda (Recommended)

**What it is:** A private Alexa skill you build and deploy to your own Amazon account. Alexa handles voice recognition and intent parsing. An AWS Lambda function receives the parsed intent and calls the Handy REST API.

```
You speak → Echo device → Alexa Cloud → Your Lambda → Handy API → Device
```

### How you'd use it

- "Alexa, open Handy" (launches the skill)
- "Start" / "Stop" / "Faster" / "Set speed to 70" / "Slow top"
- Or with one-shot: "Alexa, tell Handy to go fast"

### Pros

- **Full control over commands** — you define every voice command (intents) and exactly what API calls they trigger. Can replicate your entire existing command set: start, stop, speed, presets, stroke range, global limits.
- **Always listening** — no button press. Speak naturally from anywhere the Echo can hear you.
- **Free tier is generous** — AWS Lambda gives 1 million free requests/month. Alexa skill hosting is free. You will pay $0/month for this.
- **Serverless** — no machine to maintain, no uptime to worry about. Lambda runs only when you speak a command.
- **Private by default** — skills in "development" mode work on your account only. No app store review or publication needed.
- **Proven path** — this is the standard way to build Alexa integrations. Tons of documentation, tutorials, and examples.
- **Low latency** — Lambda in the same AWS region as Alexa is fast. Expect <1 second from speech to device response (plus Handy's own API latency).
- **Spoken confirmation** — Alexa can speak back "Speed set to 70" or "Started" after each command.

### Cons

- **Invocation phrase required** — you must say "Alexa, open Handy" or "Alexa, tell Handy to..." before commands. Alexa doesn't let custom skills listen passively. Once the skill is open, you can issue multiple commands without repeating "tell Handy", but the session times out after ~8 seconds of silence.
- **AWS account required** — you'll need an AWS account (free to create with your existing Amazon account). Initial setup involves navigating the AWS console and Alexa Developer Console.
- **Two consoles** — the skill definition lives in the Alexa Developer Console; the Lambda lives in the AWS Console. They're linked by ARN. First-time setup has some friction.
- **Session management** — Alexa sessions close after ~8 seconds of silence. To send another command, you either re-invoke ("Alexa, tell Handy to stop") or keep the session alive by responding within the timeout. This is manageable but different from the Android app where the mic stays open.

### What you'd need

- An AWS account (free tier)
- An Amazon Developer account (free, uses same Amazon login)
- The Alexa Developer Console (web-based, no software install)
- Your Handy connection key (stored as a Lambda environment variable)

### Estimated setup effort

Moderate. I can generate all the code and config files. You'd need to:
1. Create an Alexa skill in the developer console (copy-paste the interaction model JSON)
2. Create a Lambda function in AWS (copy-paste the handler code)
3. Link them together (paste the Lambda ARN into the skill config)
4. Set your connection key as an environment variable

---

## Approach 2: Alexa Custom Skill + Self-Hosted Server

**What it is:** Same as Approach 1, but instead of AWS Lambda, your own server handles the requests.

```
You speak → Echo device → Alexa Cloud → Your HTTPS server → Handy API → Device
```

### Pros

- **No AWS dependency** — runs on any server you control.
- **Full control** — same as Approach 1 for voice commands.
- **Persistent state** — easier to track device state between commands (e.g., current speed for "faster"/"slower" without querying the API each time).

### Cons

- **Requires an always-on server** — a VPS, Raspberry Pi, or similar. Must be running 24/7.
- **Requires a public HTTPS endpoint** — Alexa only talks to HTTPS with a valid SSL certificate. You need a domain name and cert (Let's Encrypt works but requires renewal).
- **More maintenance** — server updates, SSL renewal, uptime monitoring, crash recovery.
- **Costs money** — even a cheap VPS is ~$5/month vs. $0 for Lambda.
- **More complex deployment** — compared to pasting code into Lambda.
- **Overkill for this use case** — the Handy API calls are simple and stateless. There's nothing a server gives you that Lambda doesn't, for this specific project.

### Verdict

**Not recommended.** All the downsides of running infrastructure with no meaningful upside over Lambda for this use case.

---

## Approach 3: Alexa Routines (No Code)

**What it is:** Alexa Routines are simple automations you set up in the Alexa app (e.g., "when I say X, do Y"). They support triggering smart home devices, playing music, sending notifications, etc.

### Pros

- **Zero code** — configured entirely in the Alexa phone app.
- **Dead simple** — if it worked, it would be the easiest option by far.

### Cons

- **Cannot make HTTP API calls** — Routines can only trigger Alexa-integrated smart home devices, media, announcements, and a few built-in actions. There is no "call a URL" or "make an HTTP request" action.
- **The Handy has no native Alexa smart home integration** — it's not a smart home device that appears in Alexa's device list.
- **No way to pass parameters** — even if you hacked around the HTTP limitation, routines can't handle variable input like "set speed to 70".

### Verdict

**Not viable.** Routines can't make arbitrary HTTP calls, and The Handy doesn't have a native Alexa integration. This is a dead end.

---

## Approach 4: IFTTT / Webhooks Middleware

**What it is:** Use a service like IFTTT (If This Then That) as a bridge. Alexa triggers an IFTTT applet, which calls a webhook (HTTP request) to the Handy API.

```
You speak → Alexa → IFTTT → Webhook → Handy API → Device
```

### Pros

- **No code** — IFTTT webhooks are configured via a web UI.
- **Simple for basic commands** — "start" and "stop" would be straightforward.

### Cons

- **IFTTT Pro required** — webhooks are a paid feature (~$3.50/month).
- **One applet per command** — each voice command needs its own IFTTT applet. "Start", "stop", "fast", "slow" = 4 applets minimum. Presets and variable speeds would be impractical.
- **No variables or parameters** — you can't say "set speed to 70" and have IFTTT extract the number. Each speed would need its own applet ("set speed to 10", "set speed to 20", ... "set speed to 100" = 10 applets just for speed).
- **Slow** — IFTTT adds 1-15 seconds of latency. For an intimate device, responsiveness matters.
- **Fragile** — IFTTT occasionally has outages and the Alexa integration can break.
- **Limited feedback** — no spoken confirmation of what happened.

### Verdict

**Not recommended.** Could work for start/stop only, but falls apart for anything dynamic (speed, presets, stroke range). Paid, slow, and limited. The Lambda approach is free and handles everything.

---

## Approach 5: Alexa Smart Home Skill (Custom Device)

**What it is:** Build a smart home skill that makes The Handy appear as a device in Alexa (like a fan or dimmer). Use Alexa's built-in smart home voice model: "Alexa, turn on Handy", "Alexa, set Handy to 50%".

```
You speak → Alexa (smart home) → Your Lambda → Handy API → Device
```

### Pros

- **No invocation phrase** — "Alexa, turn on Handy" instead of "Alexa, tell Handy to start". More natural.
- **Built-in percentage control** — "Alexa, set Handy to 70%" maps naturally to speed.
- **Free** — same Lambda-based architecture as Approach 1.

### Cons

- **Limited command vocabulary** — smart home devices only support a fixed set of capabilities (power on/off, percentage, mode). You can't define custom commands like "slow top" or "set stroke to 40". You'd be limited to:
  - Power on/off → start/stop
  - Percentage → speed (0-100)
  - Maybe "mode" → a few presets
- **No spoken confirmation with details** — smart home responses are generic ("OK").
- **Account linking required** — smart home skills require OAuth account linking, which means you'd need to set up an OAuth server or use Login with Amazon. Significantly more infrastructure.
- **Harder to develop** — smart home skill development has more boilerplate and stricter requirements than custom skills.
- **Can't do everything** — stroke range, global limits, and the full preset grid don't map to smart home capabilities.

### Verdict

**Interesting but limited.** The "no invocation phrase" benefit is real, but losing custom commands (presets, stroke range, global limits) is a big trade-off. Could potentially be built as a complement to Approach 1 later, but shouldn't be the primary approach.

---

## Recommendation: Approach 1 (Custom Skill + AWS Lambda)

For Owen's use case, this is the clear winner:

| Criteria | Custom Skill + Lambda | Self-Hosted | Routines | IFTTT | Smart Home Skill |
|---|---|---|---|---|---|
| Full command support | Yes | Yes | No | No | Partial |
| Variable parameters ("set speed to 70") | Yes | Yes | No | No | Partial |
| Preset grid support | Yes | Yes | No | No | No |
| Spoken feedback | Yes | Yes | No | Limited | Generic only |
| Cost | Free | ~$5/mo | Free | ~$3.50/mo | Free |
| Setup complexity | Moderate | High | N/A | Low | High |
| Maintenance | None | Ongoing | N/A | Low | Low |
| Latency | Low | Low | N/A | High | Low |
| Invocation phrase needed | Yes | Yes | N/A | Yes | No |
| Viable | Yes | Yes | No | Barely | Partial |

### Language choice: Node.js vs Python

Either works well. Both have official Alexa Skills Kit (ASK) SDKs.

| | Node.js | Python |
|---|---|---|
| Matches existing code | Yes (web app is JS) | No |
| Lambda cold start | ~200ms | ~300ms |
| ASK SDK quality | Excellent | Excellent |
| HTTP calls | Built-in `fetch` | `requests` (needs layer) or `urllib3` |
| Code size for this project | ~150 lines | ~120 lines |
| Debugging | Good | Good |

**Recommendation: Node.js** — it matches your existing web app JavaScript, and Lambda's Node.js runtime has built-in `fetch` so there are zero external dependencies. The Lambda function would be a single file.

### Possible future enhancement

After getting the custom skill working, you could add a smart home skill alongside it for the most common commands. That would give you:
- "Alexa, turn on Handy" (smart home, no invocation phrase) for quick start/stop
- "Alexa, tell Handy to go slow top" (custom skill) for full control

But start with the custom skill — it covers everything.

---

## Next Steps

If you want to proceed with Approach 1:

1. I'll build the Lambda handler (Node.js), the Alexa interaction model JSON, and a step-by-step setup guide
2. You create an AWS account (if you don't have one) and an Amazon Developer account
3. Follow the guide to deploy — mostly copy-paste into web consoles
4. Test with your Echo device or the Alexa Developer Console's test simulator

No hardware purchase needed if you already have an Echo device. If not, even the Alexa app on your phone can be used for testing (though a real Echo is better for hands-free use).
