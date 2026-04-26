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

- [ ] **Alexa skill — finish testing** — Lambda + Handy verified working via Manual JSON LaunchRequest (returned *"Handy connected. Range 0 to 30, max speed 50."*). Simulator voice invocation returns *"X is not supported on this device"* — likely stale simulator cache. Pending: test on a real Echo. Skill ID `amzn1.ask.skill.08af7a77-4701-4292-b027-3840509fcabd`. Current invocation name in JSON: `my stroke control` (changed from `handy machine` during debugging — pick a final name and rebuild).
- [ ] **HSSP Integration** — Play funscripts from URLs
- [ ] **Custom Patterns** — Host looping funscripts for HRPP-like behavior
- [ ] **Voice Commands (V2 Web)** — Port Deepgram voice to V2 web app
- [ ] **Android UX Polish** — Better connection flow, error recovery, onboarding

## Future Enhancements

- [ ] Pattern/routine presets ("slow start", "gradual increase", etc.)
- [ ] Voice-activated timers
- [ ] HSSP funscript support
- [ ] Android settings persistence for global limits and speed multiplier
