# Home Assistant Voice Control

True hands-free, always-listening voice control for the Handy via Home Assistant + a local voice satellite. No Amazon, no Google, no content filter, no monthly fee.

```
"Computer, faster."
    │
    ▼
Voice satellite (always listening)
    │  wake word detected → audio stream
    ▼
Home Assistant (Linux, Docker)
    ├─ openWakeWord  ← wake word
    ├─ Whisper       ← speech-to-text (local)
    ├─ Intent match  ← "faster" → handy.faster
    └─ Handy custom integration ──► Handy API ──► Device
```

## Why this approach

The Alexa custom skill route is blocked by Amazon's content filter — the skill builds and the Lambda works, but voice invocation silently refuses on Echo and simulator alike. Home Assistant runs the entire voice pipeline locally with a wake word **you choose**, transcription model **you control**, and intents **you author**. No third party gates what you can say.

It's also the right architectural answer for an accessibility tool: nothing requires touching a screen or pressing a button, the satellite reboots itself if it ever wedges, and adding a second satellite for another room is plug-and-play.

---

## Hardware

### Compute

You already have a Linux machine. That's enough — no need to buy anything for HA itself. Recommended specs:

- **CPU:** any modern x86_64 (last 5 years). ARM works too (Pi 4/5, Apple Silicon).
- **RAM:** 4 GB free for the HA stack. Whisper `tiny-int8` is lightweight; `base` and `small` need more.
- **Disk:** 10 GB for HA + voice models.
- **Always-on:** the machine needs to be on whenever voice control is wanted.

### Voice satellite

There are two architectures:

**(A) All-in-one (recommended for this setup):** the HA host sits at the bedside with a USB mic plugged in, running everything — HA, Whisper, Piper, openWakeWord, *and* the satellite process — as containers on the same box. No second device, no LAN audio streaming. This is the right choice when the host is a quiet, capable mini PC like a Beelink GTR5.

**(B) Separate satellite over LAN:** HA host lives anywhere, a dedicated puck sits in the room. Pick if the host is loud, hot, or far from where the device is used.

| Option | Cost | When to pick |
|---|---|---|
| **All-in-one (HA host + USB mic)** | mic only | Host is quiet enough to bedside. **Recommended for the GTR5.** |
| **HA Voice Preview Edition (LAN satellite)** | $59 | Host lives elsewhere; want plug-and-play room hardware. |
| **DIY: M5Stack ATOM Echo (LAN satellite)** | ~$13 | Cheapest LAN option. Smaller mic array, less robust in noisy rooms. |
| **Old Android phone + Wyoming Satellite app** | $0 | LAN option using existing hardware. Battery/charging considerations. |

### Microphone reach

Voice pickup is clear within ~3 m / 10 ft in a quiet room. Place mic near where you'll be using the device. Bedside table is typical. Don't bury it in fabric.

For the all-in-one path, the USB mic is the limiting factor — not the host's compute. A basic single-element USB mic at arm's length will work; for room-scale pickup get a beamforming USB array. Solid options:

| Mic | ~Cost | Notes |
|---|---|---|
| **ReSpeaker USB 4-Mic Array** | $25 | Purpose-built for voice assistants, far-field beamforming. Best price/performance. |
| **Anker PowerConf S3** | $80 | Conference speaker w/ 4-mic array, doubles as TTS speaker. |
| **Jabra Speak 410/510** | $80-120 | Conference unit, omnidirectional pickup, very robust. |
| Any cheap USB plug-in mic | $5-15 | Works at arm's length / 1-2 ft. Frustrating from across the room. |

---

## Install: HA on Linux via Docker Compose

This runs HA + Whisper + Piper (TTS) + openWakeWord as separate containers. It does **not** take over your machine — your Linux host keeps doing whatever else it does.

### Prerequisites (Ubuntu 24)

Install Docker Engine + Compose plugin from Docker's official apt repo. Don't use Ubuntu's `docker.io` package — it lags behind and ships an older Compose.

```bash
# Remove any old Docker bits
for pkg in docker.io docker-doc docker-compose docker-compose-v2 podman-docker containerd runc; do
  sudo apt-get remove -y $pkg
done

# Add Docker's official GPG key + repo
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Run Docker without sudo (log out/in afterward to take effect)
sudo usermod -aG docker $USER

# Verify
docker --version
docker compose version
```

After running `usermod`, **log out and back in** (or `newgrp docker`) so the group membership takes effect — otherwise every `docker` command will need `sudo`.

### 1. Create the stack

```bash
mkdir -p ~/handy-ha/{config,whisper,piper,wakeword}
cd ~/handy-ha
```

Create `~/handy-ha/docker-compose.yml`:

```yaml
services:
  homeassistant:
    container_name: homeassistant
    image: ghcr.io/home-assistant/home-assistant:stable
    network_mode: host          # required for mDNS / satellite discovery
    volumes:
      - ./config:/config
      - /etc/localtime:/etc/localtime:ro
    restart: unless-stopped

  whisper:
    container_name: whisper
    image: rhasspy/wyoming-whisper:latest
    # base-int8 is the sweet spot for the GTR5 — much better accuracy than tiny,
    # still <1s transcription. Bump to small-int8 if you want even better recognition.
    command: --model base-int8 --language en
    volumes:
      - ./whisper:/data
    ports:
      - "10300:10300"
    restart: unless-stopped

  piper:
    container_name: piper
    image: rhasspy/wyoming-piper:latest
    command: --voice en_US-amy-medium
    volumes:
      - ./piper:/data
    ports:
      - "10200:10200"
    restart: unless-stopped

  openwakeword:
    container_name: openwakeword
    image: rhasspy/wyoming-openwakeword:latest
    command: --preload-model ok_nabu
    volumes:
      - ./wakeword:/data
    ports:
      - "10400:10400"
    restart: unless-stopped

  # All-in-one path: satellite runs on the same host with a USB mic plugged in.
  # If using a dedicated LAN satellite (Voice Preview Edition, ATOM Echo), omit this block.
  satellite:
    container_name: satellite
    image: rhasspy/wyoming-satellite:latest
    command: >
      --name 'bedside'
      --uri tcp://0.0.0.0:10700
      --mic-command 'arecord -r 16000 -c 1 -f S16_LE -t raw -D plughw:CARD=USB,DEV=0'
      --snd-command 'aplay -r 22050 -c 1 -f S16_LE -t raw -D plughw:CARD=USB,DEV=0'
      --wake-uri tcp://127.0.0.1:10400
      --wake-word-name ok_nabu
    devices:
      - /dev/snd:/dev/snd       # ALSA passthrough for USB mic + speaker
    group_add:
      - audio
    network_mode: host
    depends_on:
      - openwakeword
    restart: unless-stopped
```

The satellite's `--mic-command` and `--snd-command` use `arecord`/`aplay` with the USB device. After plugging your mic in, run `arecord -l` on the host to find the actual `CARD=` name for your device — it might be `Device`, `USB`, `Microphone`, etc., depending on the mic. Update the compose file accordingly.

A note on the wake word: `ok_nabu` ("Okay Nabu") is the default. Other prebuilt options include `hey_jarvis`, `alexa`, `hey_mycroft`, `hey_rhasspy`. To train a fully custom wake word (your name, anything), see [openWakeWord training](https://github.com/dscripka/openWakeWord#training-new-models).

### 2. Start

```bash
cd ~/handy-ha
docker compose up -d
docker compose logs -f homeassistant     # watch startup, Ctrl+C to detach
```

Open `http://<your-linux-ip>:8123` in a browser. Walk through the HA onboarding (create account, set location, etc.).

### 3. Wire the voice services into HA

In HA web UI: **Settings → Devices & Services → Add Integration → Wyoming Protocol**.

Add three integrations (one per service):

| Service | Host | Port |
|---|---|---|
| Whisper (STT) | `127.0.0.1` | `10300` |
| Piper (TTS) | `127.0.0.1` | `10200` |
| openWakeWord | `127.0.0.1` | `10400` |

Then **Settings → Voice assistants → Add assistant**:

- Name: `Handy`
- Conversation agent: `Home Assistant`
- Speech-to-text: Whisper (the one you just added)
- Text-to-speech: Piper
- Wake word: openWakeWord → `ok_nabu`

Set this as the default assistant.

### 4. Pair the voice satellite

**All-in-one (the satellite container above):** add it via **Settings → Devices & Services → Add Integration → Wyoming Protocol** with host `127.0.0.1` port `10700`. Assign to the `Handy` assistant.

**HA Voice Preview Edition (LAN):** Plug in USB-C, connect to power. HA discovers it on your network within ~30s. UI walks through Wi-Fi setup. Assign it to the `Handy` assistant.

**M5Stack ATOM Echo (LAN):** Flash the [Wyoming Satellite firmware](https://github.com/rhasspy/wyoming-satellite) via web installer at <https://esphome.github.io/wake-word-voice-assistants/>.

**Android phone (LAN):** Install [Wyoming Satellite for Android](https://github.com/Frenoid/wyoming-satellite-android), point at your HA URL, leave it plugged in.

Test with the wake word: "Okay Nabu, what time is it?" — should respond.

---

## Handy custom integration

This is the bit that connects HA to your existing v2 API. Modeled as a `fan` entity because fans natively have on/off + speed%, which maps cleanly to start/stop + speed multiplier and works out-of-the-box with Alexa Smart Home, Google, and HA voice.

### File layout

```
~/handy-ha/config/custom_components/handy/
  __init__.py
  manifest.json
  config_flow.py
  fan.py
  services.yaml
```

### Status

**v0.1.0 built and ready to deploy.** Source: [`homeassistant/custom_components/handy/`](../homeassistant/custom_components/handy/) in this repo. Deploy + test instructions in that folder's [README](../homeassistant/custom_components/handy/README.md).

What's exposed:

- `fan.handy` — on/off (HAMP start/stop), `percentage` (speed 0-100), with `stroke_min` / `stroke_max` as state attributes.
- `handy.set_range` — set stroke min/max.
- `handy.preset` — speed (slow/medium/fast) × position (top/middle/bottom/full/natural).
- `handy.faster` / `handy.slower` — adjust speed by a delta.

Reuses the same v2 API logic proven in `v2-web/handy-v2-api.js` — protocol details (mode → start → speed ordering, `/slide` PUT) per `docs/HANDY_API_NOTES.md`.

Not yet (tracked in `TODO.md`): global limits / speed limit, sensation presets, HSSP funscripts, multi-device.

### Voice intents

After the integration ships, custom intents in `config/custom_sentences/en/handy.yaml` will enable phrases like:

```yaml
language: en
intents:
  HandyStart:
    data:
      - sentences:
          - "start"
          - "go"
          - "begin"

  HandySetSpeed:
    data:
      - sentences:
          - "set speed to {speed}"
          - "speed {speed}"
        slots:
          speed:
            range:
              from: 0
              to: 100

  HandyFaster:
    data:
      - sentences:
          - "faster"
          - "speed up"

  HandyPreset:
    data:
      - sentences:
          - "{speed} {position}"
        slots:
          speed: ["slow", "medium", "fast"]
          position: ["top", "middle", "bottom", "full"]
```

Wake word + the intent: *"Okay Nabu, fast full"* → preset fired.

---

## Validation path (recommended order)

1. **Stand up the Docker stack** (above). Confirm HA loads, voice services connect.
2. **Build the Handy custom integration.** Test via HA's Developer Tools → Services panel — call `fan.turn_on` on `fan.handy` and confirm the device starts. Iterate until the entity is rock-solid.
3. **Test via existing Echo (optional, free with Nabu Casa trial).** Once the integration works, link HA to Nabu Casa Cloud → enable Alexa integration. Echo sees `fan.handy` as a smart fan: *"Alexa, set Handy to forty percent."* Validates the integration works end-to-end before you buy voice hardware.
4. **Add voice satellite.** Plug in HA Voice Preview Edition; pair to the Handy assistant.
5. **Author custom intents.** Add the YAML above; test wake-word commands from the satellite.
6. **Tune.** Wake word sensitivity, Whisper model size (upgrade `tiny-int8` → `base-int8` if accuracy is weak), satellite placement.

Each stage is independently useful — you don't need to commit to all of it before testing.

---

## Costs

| Item | One-time | Monthly |
|---|---|---|
| Linux machine | already have | $0 |
| Docker stack | free | $0 |
| HA Voice Preview Edition | $59 | $0 |
| **Total minimum** | **$59** | **$0** |
| Optional: Nabu Casa Cloud (for Echo bridge) | $0 | $6.50 |
| Optional: extra satellite (multi-room) | $13-59 each | $0 |

---

## Troubleshooting

| Problem | Fix |
|---|---|
| HA doesn't load at `:8123` | `docker compose logs homeassistant` — usually a config volume permissions issue or port conflict. |
| Wyoming integration won't connect | Ensure `network_mode: host` for HA, OR use the Linux host's LAN IP instead of `127.0.0.1` if running HA in bridge mode. |
| Wake word doesn't trigger | Check satellite is paired to the right assistant. Try saying it louder/closer. Lower sensitivity in openWakeWord config. |
| Wake word triggers but command not understood | Whisper `tiny-int8` is fast but weaker — upgrade to `base-int8` or `small-int8` (edit `command:` in compose). |
| "Handy is not connected" from intent | Same as web app — device must be on, on Wi-Fi, with correct connection key in integration config. |
| Satellite not discovered | mDNS issue. Confirm HA running with `network_mode: host`. Restart satellite. |

---

## Files added by this milestone

```
docs/HOME_ASSISTANT_GUIDE.md      ← this file
~/handy-ha/docker-compose.yml     ← stack definition (lives on your Linux box, not in repo)
~/handy-ha/config/                ← HA config (not in repo)
```

The Handy custom integration source will live at `homeassistant/custom_components/handy/` in this repo once built, ready to be symlinked or copied into `~/handy-ha/config/custom_components/`.
