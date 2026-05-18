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
- [x] **Android v2 scaffold** — new app using prerecorded voice samples (MFCC + DTW); see `android-v2/`
- [x] Android v2: hand-rolled MFCC (radix-2 FFT, mel filterbank, DCT) with 14 JVM unit tests
- [x] Android v2: Sakoe-Chiba banded DTW with path-length normalization
- [x] Android v2: TemplateEngine interface + MfccDtwEngine + TemplateStore (JSON, format-versioned)
- [x] Android v2: Euphonia-style TrainingActivity (9 commands × 3 takes, save-as-you-go)
- [x] Android v2: ControlActivity with mic-button and always-on (VAD-gated) modes
- [x] Android v2: SettingsActivity (connection key, listening mode, global max speed, threshold, retrain)
- [x] Android v2: Dispatch table mirroring `vega/commands.py` (9 labels: start/stop/faster/slower + 5 pattern stand-ins)
- [x] Android v2: thorough documentation (README, ARCHITECTURE, VOICE_ENGINE, COMMANDS, TRAINING, TROUBLESHOOTING)

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
- [ ] **Android v2: on-device validation** — record real templates on Owen's phone, walk every command twice, tune `match_threshold` and `Vad.energyThreshold` defaults from observed score distributions
- [ ] **Android v2: pattern engine port** from `vega/devices/handy.py` (looping heartbeat / wave / surge / breathe / storm envelopes). Add `VoiceAction.Pattern(name, intensity)`, replace the 5 Preset stand-ins. Recording vocabulary unchanged.
- [ ] **Android v2: preset grid UI parity with v1** — 4×3 slow/med/fast × top/mid/bot/full + sliders. Voice-first stays primary; grid is touch fallback.
- [ ] **Android v2: TFLite embedding engine** — extract EfficientWord-Net's Resnet50 model, port preprocessing to Kotlin, store one average embedding per label. Cosine similarity threshold. Same training UX.
- [ ] **Android v2: wake-word label** — record one template called `wake`; always-on uses the matcher itself as the wake stage instead of VAD. Closer to vega's design.
- [ ] **Android v2: stroke-range cap** — wire `GLOBAL_MAX_STROKE_PERCENT` (default 30 per vega) into Settings and `Dispatch.dispatch()`.
- [ ] **Android v2: per-command retrain** — Settings → "Re-record a single command" without wiping everything.
- [ ] **Android v2: foreground service for true background listening** — `MediaProjection`-style with persistent notification. Optional, behind a setting; useful for hands-free across screen-off.

## Shelved / Abandoned

- [~] **Alexa custom skill** — Lambda + Handy API verified working end-to-end (Manual JSON LaunchRequest succeeds). Voice invocation fails identically on simulator AND real Echo with *"X is not supported on this device"* despite all settings verified correct (Test stage = Development, locale match, account match, Distribution fields filled, multiple invocation names tried). Strongly suspect Amazon content-filter is silently blocking voice routing for adult-themed skills. Pivoting to Home Assistant which has no such filter. Skill ID kept for reference: `amzn1.ask.skill.08af7a77-4701-4292-b027-3840509fcabd`. Lambda kept deployed in case revisiting.
- [~] **Voice Commands (V2 Web)** — Web Speech API requires button press to start and tab focus, which fails the accessibility use case. Subsumed by Home Assistant voice pipeline above.

## Future Enhancements

- [ ] Pattern/routine presets ("slow start", "gradual increase", etc.)
- [ ] Voice-activated timers
- [ ] HSSP funscript support
- [ ] Android settings persistence for global limits and speed multiplier
- [ ] Share Android v2's template-matching engine as a standalone library (used by `android-v2/` here and any future Pi-without-Python rewrites)
- [ ] Cross-device template sync between two devices used by the same speaker (e.g. phone + tablet) — currently template files are per-install
