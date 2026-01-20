# Handy Voice

Voice-controlled web app for The Handy device, designed for users with disabilities who have no hand control.

## Status

**In Development** — Planning and initial setup phase.

## Purpose

This app provides hands-free voice control for The Handy (v1.1) device, enabling users with limited or no hand mobility to operate the device independently using voice commands.

## Key Features

- **Advanced Voice Recognition** — Deepgram AI for superior accuracy with non-standard voices and speech difficulties
- **Dual Engine Support** — Choose between Deepgram or browser Web Speech API
- **WiFi Connection** — Uses Handy REST API v3 (avoids finicky Bluetooth)
- **Accessible Design** — High contrast UI, large buttons, screen reader support
- **Simple Setup** — Easy configuration wizard for API credentials
- **Multiple Control Modes** — Speed control, stroke range adjustment, start/stop
- **Real-time Feedback** — Interim transcription shows what's being heard

## Tech Stack

- **Frontend**: HTML5, CSS3, Vanilla JavaScript
- **Voice Recognition**: Deepgram AI (primary) + Web Speech API (fallback)
- **Device API**: Handy REST API v3
- **Hosting**: Static web app (can run locally or deploy)

## Quick Start

### Prerequisites

1. The Handy device (v1.1 with Firmware v4+)
2. Handy account at [user.handyfeeling.com](https://user.handyfeeling.com)
3. Handy API key from your account
4. Device connection key
5. **Deepgram API key** (recommended - free at [console.deepgram.com](https://console.deepgram.com/signup))
6. Modern browser (Chrome or Edge recommended)

### Running Locally

```bash
# Clone or download this repository
cd handy-v1

# Open index.html in your browser
# Or use a simple HTTP server:
python -m http.server 8000
# Then visit http://localhost:8000
```

## Voice Commands

*(Coming soon - will include commands like "start", "stop", "speed up", "slow down", "set speed to 50", etc.)*

## Project Structure

```
handy-v1/
├── index.html              # Main app interface
├── css/
│   └── style.css          # Accessible styling
├── js/
│   ├── voice.js           # Voice recognition handler
│   ├── handy-api.js       # Handy API integration
│   └── app.js             # Main app logic
├── README.md              # This file
├── TODO.md                # Task tracking
└── LLM_ONBOARDING.md      # AI assistant reference
```

## Accessibility Features

- **Deepgram AI** — Superior accuracy for non-standard voices, speech impediments, and accents
- **Interim transcription** — See what's being heard in real-time
- Large, high-contrast buttons for users with limited vision
- Full keyboard navigation support
- Screen reader compatible
- Voice feedback for all actions
- Dual voice engine support (Deepgram + Web Speech API)

## Safety

- Built-in safety limits for speed and stroke range
- Emergency stop voice command
- Visual and audio feedback for all actions

## License

MIT

## Support

For issues or questions, refer to:
- [Handy API Documentation](https://www.handyfeeling.com/api/handy-rest/v3/docs/)
- [The Handy Support](https://intercom.help/ohdoki/en/)

---

**Note**: This is an independent accessibility project and is not officially affiliated with Ohdoki/The Handy.