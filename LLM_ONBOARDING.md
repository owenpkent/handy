# LLM Onboarding

Quick reference for AI assistants working on this repository.

---

## Project Overview

**Name:** Handy Voice Control  
**Purpose:** Accessible control apps for The Handy device, designed for users with disabilities.  
**Status:** Active Development

### Apps
- **v2-web/** — Handy V2 web app (ACTIVE, working)
- **v1-web/** — Handy V1 web app with voice control (legacy)
- **android/** — Android app with voice commands (in progress)

## About the Owner

I'm Owen — a wheelchair user with muscular dystrophy.

- **Typing is hard** — Be proactive. Make decisions. Don't ask for confirmation on small things.
- **Offer A/B/C choices** — I can type one letter instead of explaining.
- **PowerShell on Windows** — Use PowerShell syntax. Prefer single-line commands.
- **Accessibility matters** — This project is a tool I actually need.
- **Non-standard voice** — I have a non-standard voice. Browser Web Speech API struggles with my speech. **Always use Deepgram** for voice recognition - it's significantly more accurate for non-standard voices and speech difficulties.

---

## Key Files

| File | Purpose |
|------|---------|
| `README.md` | Project overview, status, quick start |
| `TODO.md` | Task tracking (use `- [ ]` checkbox format) |
| `v2-web/index.html` | V2 web app interface |
| `v2-web/style.css` | V2 accessible styling |
| `v2-web/app.js` | V2 app logic with presets |
| `v2-web/handy-v2-api.js` | Handy REST API v2 client |
| `v1-web/` | Legacy V1 web app (Deepgram voice) |
| `android/` | Android Studio project |
| `docs/` | Setup guides and documentation |

---

## Tech Stack

- **Language:** HTML5, CSS3, JavaScript (ES6+), Kotlin (Android)
- **Framework:** Vanilla JS (web), Android SDK (mobile)
- **Voice Recognition:** Deepgram AI (V1), Android SpeechRecognizer
- **Device API:**
  - V2: `https://www.handyfeeling.com/api/handy/v2`
  - V1: `https://www.handyfeeling.com/api/handy-rest/v3`
- **Hosting:** Static files (run locally with `python -m http.server`)

---

## API Integration Notes

### Authentication
- Requires API key (Application Key) from user's Handy account
- Must generate client token for device operations
- Connection key required for device-specific commands

### Key Endpoints
- `/auth/token` - Generate client token
- `/hamp/state` - Get/set HAMP (Handy Alternate Motion Protocol) state
- `/hamp/start` - Start device
- `/hamp/stop` - Stop device
- `/info/device` - Get device info and status

### HAMP Protocol
- Controls alternating motion (up/down strokes)
- Speed: 0-100 (strokes per minute)
- Stroke range: min/max positions (0.0-1.0)

---

## Accessibility Requirements

**Critical**: This app must be fully accessible for users with no hand control AND non-standard voices.

- **Deepgram AI** — Use Deepgram for voice recognition (not Web Speech API) for superior accuracy with non-standard voices
- **Interim transcription** — Show real-time feedback of what's being heard
- **Voice-first design** — All functions must be voice-controllable
- **Large touch targets** — Minimum 44x44px for any clickable elements
- **High contrast** — WCAG AAA compliance
- **Screen reader support** — Proper ARIA labels and semantic HTML
- **Keyboard navigation** — Full keyboard support as backup
- **Voice feedback** — Text-to-speech confirmation of actions
- **Simple language** — Clear, concise instructions and feedback

---

## Voice Command Design

### Principles
- Natural language (conversational)
- Multiple phrases for same action (synonyms)
- Clear confirmation feedback
- Emergency stop always available
- Avoid complex multi-step commands

### Example Patterns
```javascript
// Good: Multiple ways to say the same thing
"start", "begin", "go"

// Good: Natural numbers
"set speed to fifty" or "set speed to 50"

// Good: Relative adjustments
"faster", "speed up", "increase speed"
```

---

## Git Commits

Use conventional commits:
```
feat: add voice command for speed control
fix: correct API authentication flow
docs: update voice commands reference
refactor: simplify voice recognition logic
chore: update dependencies
```

PowerShell:
```powershell
git add -A; git commit -m "feat: description"; git push
```

---

## Development Workflow

### Local Testing
```powershell
# Start local server
python -m http.server 8000

# Or use Node.js http-server
npx http-server -p 8000
```

### Browser Requirements
- Chrome 25+ (best voice support)
- Edge 79+ (Chromium-based)
- Firefox has limited Web Speech API support
- Safari has no Web Speech API support

---

## Security Considerations

- **Never hardcode API keys** — Use localStorage or prompt user
- **HTTPS required** — Web Speech API requires secure context
- **API key storage** — Warn users about browser storage risks
- **Connection key** — Keep separate from API key

---

## Testing Checklist

Before marking features complete:
- [ ] Test voice commands with different phrasings
- [ ] Test with screen reader (NVDA/JAWS)
- [ ] Test keyboard navigation
- [ ] Test error scenarios (no connection, invalid credentials)
- [ ] Test on Chrome and Edge
- [ ] Verify ARIA labels
- [ ] Check color contrast ratios

---

## Constellation

This repo is tracked by [Constellation](https://github.com/owenpkent/constellation).

For the dashboard to pick up this project:
1. Have a `README.md` with a `## Status` section
2. Have a `TODO.md` with checkbox items (`- [ ]` / `- [x]`)
3. Add the repo path to Constellation's `projects.yaml`

---

## Common Tasks

### Adding a new voice command
1. Add pattern to `js/voice.js` command parser
2. Add handler function in `js/app.js`
3. Update voice commands list in README
4. Add to TODO.md testing checklist

### Adding a new API endpoint
1. Add method to `js/handy-api.js`
2. Handle errors appropriately
3. Add loading states in UI
4. Test with invalid credentials

### Improving accessibility
1. Test with screen reader first
2. Check ARIA labels
3. Verify keyboard navigation
4. Test color contrast
5. Get feedback from actual users if possible
