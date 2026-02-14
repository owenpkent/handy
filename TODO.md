# TODO

## Current Sprint

- [x] Build V2 web app with preset grid
- [x] Add stroke position sliders (min/max)
- [x] Add safety limit feature (global max position)
- [x] Add speed multiplier control
- [x] Add debug panel with live API logging
- [x] Organize project folder structure
- [x] Update documentation
- [x] Experiment with HRPP/HSSP/HBSP modes
- [x] Document API findings (see docs/HANDY_API_NOTES.md)
- [ ] Add HSSP support (funscript playback)
- [ ] Add voice control to V2 web app
- [ ] Test Android app on real device

## Next Up

- [ ] **HSSP Integration** - Play funscripts from URLs
- [ ] **Custom Patterns** - Host looping funscripts for HRPP-like behavior
- [ ] **Voice Commands (V2)** - Port Deepgram voice to V2 web app
- [ ] **Android V2 Support** - Update Android app for V2 API

## Completed (V1 Legacy)

- [x] Research Handy API v3 capabilities
- [x] Build V1 web app with voice control
- [x] Implement Deepgram voice recognition
- [x] Android app structure

## Voice Commands to Implement

- [ ] "start" / "begin" - Start device
- [ ] "stop" / "halt" / "emergency stop" - Stop device
- [ ] "speed up" / "faster" - Increase speed by 10%
- [ ] "slow down" / "slower" - Decrease speed by 10%
- [ ] "set speed to [number]" - Set specific speed (0-100)
- [ ] "full speed" - Set to maximum safe speed
- [ ] "half speed" - Set to 50%
- [ ] "minimum speed" - Set to lowest speed
- [ ] "increase stroke" - Increase stroke range
- [ ] "decrease stroke" - Decrease stroke range
- [ ] "full stroke" - Maximum stroke range
- [ ] "short stroke" - Minimum stroke range

## Features

- [ ] Voice feedback (text-to-speech confirmation)
- [ ] Visual status indicators
- [ ] Connection status display
- [ ] Battery level indicator (if available via API)
- [ ] Command history log
- [ ] Settings persistence (localStorage)
- [ ] Offline mode detection
- [ ] Error handling and user feedback

## Accessibility Enhancements

- [ ] High contrast mode toggle
- [ ] Font size adjustment
- [ ] Keyboard shortcuts for all functions
- [ ] Screen reader ARIA labels
- [ ] Focus indicators
- [ ] Voice command help/tutorial
- [ ] Alternative input methods (switch control support)

## Documentation

- [ ] Complete voice commands reference
- [ ] Setup guide with screenshots
- [ ] Troubleshooting guide
- [ ] API key acquisition instructions
- [ ] Browser compatibility notes
- [ ] Privacy and security notes

## Testing

- [ ] Test on Chrome (primary browser)
- [ ] Test on Edge
- [ ] Test voice recognition accuracy
- [ ] Test with screen readers
- [ ] Test keyboard navigation
- [ ] Test error scenarios
- [ ] Test connection failures

## Future Enhancements

- [ ] Pattern/routine presets ("slow start", "gradual increase", etc.)
- [ ] Voice-activated timers
- [ ] Multi-language support
- [ ] Mobile app version (PWA)
- [ ] Integration with other accessibility tools
- [ ] Custom voice command training
