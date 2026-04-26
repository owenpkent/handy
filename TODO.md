# TODO

## Completed

- [x] Build V2 web app with preset grid
- [x] Add stroke position sliders (min/max)
- [x] Add safety limit feature (global min/max)
- [x] Add speed multiplier control
- [x] Add debug panel with live API logging (web)
- [x] Organize project folder structure
- [x] Document API findings (see docs/HANDY_API_NOTES.md)
- [x] Research Handy API v3 capabilities
- [x] Build V1 web app with voice control
- [x] Implement Deepgram voice recognition (web)
- [x] Android app structure + V2 API integration
- [x] Android preset grid (4×3: Top/Mid/Bot/Full × Slow/Med/Fast)
- [x] Android Vosk offline voice engine with constrained grammar
- [x] Android voice commands: start/stop/faster/slower/speed/presets/global min/max
- [x] Android debug panel with Copy button
- [x] Fix V2 stroke range API (single PUT /slide, not separate min/max)
- [x] Fix operation order (stroke → start → speed)
- [x] Fix Vosk mic warmup (muted/unmuted pattern)
- [x] Fix sequential faster/slower (partial timeout auto-promotion)
- [x] Fix global slider ranges (0-100, matching web app)
- [x] Android settings: Vosk/Android STT/Deepgram voice engine selection
- [x] Update LLM onboarding documentation

## Next Up

- [ ] **Home Assistant integration** — primary path forward for hands-free voice control. See [docs/HOME_ASSISTANT_GUIDE.md](docs/HOME_ASSISTANT_GUIDE.md). Stages:
  - [ ] Stand up HA + Whisper + Piper + openWakeWord + satellite stack via Docker Compose on the Beelink GTR5 (Ubuntu 24)
  - [x] Build `custom_components/handy/` v0.1 — fan entity (start/stop + speed%), services `set_range`/`preset`/`faster`/`slower`, UI config flow with connection-key validation
  - [ ] Deploy integration to HA host (`~/handy-ha/config/custom_components/handy/`) and validate via Developer Tools → Services
  - [ ] Pair USB mic + satellite container; configure ALSA device passthrough
  - [ ] Author custom intents YAML (`config/custom_sentences/en/handy.yaml`) for natural commands
  - [ ] v0.2: add global min/max + speed-limit safety caps as services or as part of config options
  - [ ] v0.3: sensation presets (vibrate, pulse, tease, deep, intense, edge, gentle, jackhammer)
- [ ] **HSSP Integration** — Play funscripts from URLs
- [ ] **Custom Patterns** — Host looping funscripts for HRPP-like behavior
- [ ] **Android UX Polish** — Better connection flow, error recovery, onboarding

## Shelved / Abandoned

- [~] **Alexa custom skill** — Lambda + Handy API verified working end-to-end (Manual JSON LaunchRequest succeeds). Voice invocation fails identically on simulator AND real Echo with *"X is not supported on this device"* despite all settings verified correct (Test stage = Development, locale match, account match, Distribution fields filled, multiple invocation names tried). Strongly suspect Amazon content-filter is silently blocking voice routing for adult-themed skills. Pivoting to Home Assistant which has no such filter. Skill ID kept for reference: `amzn1.ask.skill.08af7a77-4701-4292-b027-3840509fcabd`. Lambda kept deployed in case revisiting.
- [~] **Voice Commands (V2 Web)** — Web Speech API requires button press to start and tab focus, which fails the accessibility use case. Subsumed by Home Assistant voice pipeline above.

## Future Enhancements

- [ ] Pattern/routine presets ("slow start", "gradual increase", etc.)
- [ ] Voice-activated timers
- [ ] HSSP funscript support
- [ ] Android settings persistence for global limits and speed multiplier
