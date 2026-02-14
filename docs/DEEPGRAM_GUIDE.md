# Deepgram Integration Guide

This app uses Deepgram AI for superior voice recognition accuracy, especially important for users with non-standard voices, speech impediments, or disabilities affecting speech.

---

## Why Deepgram?

### Superior Accuracy for Non-Standard Voices

Browser-based Web Speech API can struggle with:
- Speech impediments
- Non-standard speech patterns
- Accents and dialects
- Quiet or unclear speech
- Background noise

**Deepgram's AI models** are trained on diverse speech patterns and provide:
- **Better accuracy** for non-standard voices
- **Real-time transcription** with interim results
- **Noise robustness** for challenging environments
- **Continuous learning** from diverse speech data

---

## Getting Your Deepgram API Key

### Step 1: Sign Up

1. Go to [console.deepgram.com/signup](https://console.deepgram.com/signup)
2. Create a free account
3. Verify your email

### Step 2: Get API Key

1. Log in to [console.deepgram.com](https://console.deepgram.com)
2. Navigate to **API Keys** section
3. Click **Create a New API Key**
4. Give it a name (e.g., "Handy Voice Control")
5. Copy the key immediately (you won't see it again!)

### Free Tier

Deepgram offers a generous free tier:
- **$200 in free credits** for new accounts
- Approximately **45 hours** of transcription
- No credit card required initially
- Perfect for personal use

---

## Setting Up Deepgram in the App

### 1. Enter Your Keys

In the app's **Setup** section:
1. Enter your **Handy API Key** and **Connection Key**
2. Select **"Deepgram"** from the Voice Recognition Engine dropdown
3. Enter your **Deepgram API Key**
4. Click **"Save Voice Settings"**

### 2. Start Using Voice Control

1. Click **"🎤 Start Listening"**
2. Grant microphone permission when prompted
3. Speak your commands clearly
4. Watch the interim transcript to see what's being heard

---

## Features

### Interim Transcription

Unlike Web Speech API, Deepgram shows you what it's hearing **in real-time**:
- See partial transcripts as you speak
- Confirm the system is hearing you correctly
- Adjust your speech if needed

### Better Command Recognition

Deepgram understands:
- Natural variations in speech
- Different pronunciations
- Partial or unclear words
- Commands with pauses or hesitations

### Continuous Connection

- Maintains persistent WebSocket connection
- Automatic reconnection if connection drops
- Lower latency than Web Speech API

---

## Privacy & Security

### API Key Security

**Important**: Your Deepgram API key is stored in browser localStorage and is visible in browser developer tools.

**For personal use**: This is acceptable if you trust your device.

**For production/shared use**: You should:
1. Set up a proxy server to hide the API key
2. Use server-side authentication
3. Implement rate limiting

### Audio Data

- Audio is sent to Deepgram's servers for processing
- Deepgram does not store audio by default
- Review [Deepgram's privacy policy](https://deepgram.com/privacy) for details

### Recommendations

- Use a dedicated API key for this app
- Regenerate keys periodically
- Monitor usage in Deepgram console
- Set up usage alerts if available

---

## Fallback to Web Speech API

If you don't want to use Deepgram or run out of credits:

1. Go to **Setup** section
2. Change **Voice Recognition Engine** to **"Browser Web Speech API"**
3. Click **"Save Voice Settings"**
4. No API key needed - uses browser's built-in recognition

**Note**: Web Speech API:
- Works offline (in some browsers)
- No API key required
- May be less accurate for non-standard voices
- Only available in Chrome/Edge

---

## Troubleshooting

### "Deepgram API key not set"

**Solution**: 
1. Check you've entered the API key in Setup
2. Click "Save Voice Settings"
3. Refresh the page if needed

### "Connection error"

**Solution**:
1. Check your internet connection
2. Verify API key is correct
3. Check Deepgram console for account status
4. Try regenerating your API key

### "No speech detected"

**Solution**:
1. Check microphone permissions in browser
2. Test microphone in system settings
3. Speak louder or closer to microphone
4. Check for background noise
5. Try a different microphone

### Low accuracy

**Solution**:
1. Reduce background noise
2. Speak at normal pace (not too fast/slow)
3. Use a quality microphone
4. Position microphone closer
5. Check interim transcript to see what's being heard

### Out of credits

**Solution**:
1. Check usage in Deepgram console
2. Add payment method for additional credits
3. Switch to Web Speech API as fallback
4. Contact Deepgram support for assistance

---

## Technical Details

### API Configuration

The app uses these Deepgram settings:
- **Model**: `nova-2` (latest, most accurate)
- **Language**: `en-US`
- **Punctuation**: Enabled
- **Interim Results**: Enabled (for real-time feedback)
- **Endpointing**: 300ms (silence before finalizing)
- **VAD Events**: Enabled (voice activity detection)

### WebSocket Connection

- Connects to `wss://api.deepgram.com/v1/listen`
- Sends audio in 250ms chunks
- Receives transcripts in real-time
- Auto-reconnects on disconnect

### Audio Format

- **Format**: WebM (browser default)
- **Sample Rate**: Browser default (usually 48kHz)
- **Channels**: Mono (1 channel)
- **Encoding**: Opus codec

---

## Optimizing for Your Voice

### Training Tips

While you can't train Deepgram directly, you can optimize your experience:

1. **Consistent environment**: Use the same microphone and location
2. **Clear pronunciation**: Speak clearly but naturally
3. **Command patterns**: Use the same phrasings consistently
4. **Feedback loop**: Watch interim transcripts and adjust

### Alternative Phrasings

The app recognizes multiple ways to say commands:
- "start" = "begin" = "go" = "turn on"
- "stop" = "halt" = "pause" = "turn off"
- "speed up" = "faster" = "go faster"

Use whichever phrasing is easiest for you to say clearly.

---

## Cost Management

### Monitoring Usage

1. Log in to [console.deepgram.com](https://console.deepgram.com)
2. Check **Usage** dashboard
3. See how many hours you've used
4. Track remaining credits

### Reducing Costs

- Only enable voice control when needed
- Use manual controls when possible
- Switch to Web Speech API for testing
- Close browser tab when not in use

### Typical Usage

For this app:
- ~1 minute of audio per session
- ~10-20 sessions per day = 10-20 minutes
- ~5-10 hours per month
- Well within free tier for most users

---

## Support

### Deepgram Support

- [Documentation](https://developers.deepgram.com)
- [Community Discord](https://discord.gg/deepgram)
- [Support Email](mailto:support@deepgram.com)

### App Support

- Check [SETUP_GUIDE.md](./SETUP_GUIDE.md) for general setup
- Review [TODO.md](./TODO.md) for known issues
- Check browser console for error messages

---

## Future Enhancements

Planned improvements for Deepgram integration:

- [ ] Custom vocabulary for device-specific terms
- [ ] Language selection (multi-language support)
- [ ] Voice profile customization
- [ ] Confidence threshold adjustment
- [ ] Alternative model selection
- [ ] Proxy server setup guide for production

---

**Remember**: Deepgram is recommended but optional. The app works with browser Web Speech API if you prefer not to use an external service.
