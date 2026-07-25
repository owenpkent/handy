# Legacy / reference apps

These are earlier or shelved versions of Handy Voice Control. **Active development happens only in `../android-v2/`.** Everything here is kept for reference and is not maintained.

| Folder | Was | Status | Notes |
|--------|-----|--------|-------|
| `android/` | Handy V2 Android app — Vosk offline ASR + constrained grammar | Working, frozen | Superseded by `android-v2/` (prerecorded voice samples, MFCC + DTW). Engine internals, grammar phrases, and gotchas: [`android/README.md`](android/README.md). |
| `v2-web/` | Handy V2 web app — preset grid, sliders, debug panel | Working, frozen | Vanilla HTML/CSS/JS. Useful as an API reference for the V2 REST flow. |
| `v1-web/` | Handy V1 web app — Deepgram voice | Legacy | Original prototype. |
| `alexa/` | Alexa skill Lambda | Shelved | Blocked by Amazon content filter. See `../docs/ALEXA_GUIDE.md`. |
| `homeassistant/` | Home Assistant custom component | Reference | Pattern envelopes here were ported into `android-v2`'s `PatternEngine`. See `../docs/HOME_ASSISTANT_GUIDE.md`. |

Nothing in this folder is built or shipped. To resurrect one, move it back to the repo root with `git mv`.
