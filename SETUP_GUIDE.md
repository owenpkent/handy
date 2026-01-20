# Setup Guide - Handy Voice Control

Step-by-step guide to get started with voice control for your Handy device.

---

## Prerequisites

1. **The Handy device** (v1.1 with Firmware v4 or later)
2. **Handy account** - Register at [user.handyfeeling.com](https://user.handyfeeling.com)
3. **Modern browser** - Chrome or Edge (for voice recognition support)
4. **Microphone** - Built-in or external

---

## Network Architecture

### How Connectivity Works

**Important**: Both your laptop and The Handy device connect to the internet independently. They communicate through Handy's cloud servers, not directly to each other.

```
Your Laptop (WiFi/Internet)
    ↓
Internet
    ├→ Deepgram servers (voice recognition)
    └→ Handy API servers (device control)
         ↓
    The Handy device (WiFi/Internet)
```

**What you need:**
- Your laptop: WiFi/internet connection (any network)
- The Handy device: WiFi/internet connection (any network)
- Both devices just need internet access (don't need to be on same network)

**Benefits:**
- No Bluetooth pairing required
- No local network configuration
- Works from anywhere with internet
- Can control device remotely

---

## Step 1: Get Your API Key

1. Go to [user.handyfeeling.com](https://user.handyfeeling.com)
2. Log in to your account
3. Navigate to **Settings** or **Developer** section
4. Find or generate your **API Key** (also called Application Key)
5. Copy and save this key securely

---

## Step 2: Get Your Connection Key

Your connection key identifies your specific Handy device.

**Option A: From The Handy App**
1. Open The Handy mobile app
2. Go to device settings
3. Find the **Connection Key**
4. Copy this key

**Option B: From Device Display**
1. Power on your Handy device
2. Navigate through the device menu
3. Find the connection key in device info
4. Note down the key

---

## Step 3: Run the App

### Local Setup

```bash
# Navigate to the project folder
cd handy-v1

# Option 1: Open directly in browser
# Just open index.html in Chrome or Edge

# Option 2: Use Python HTTP server
python -m http.server 8000
# Then visit http://localhost:8000

# Option 3: Use Node.js http-server
npx http-server -p 8000
# Then visit http://localhost:8000
```

### Online Hosting

You can also deploy this to:
- **Netlify** - Drag and drop the folder
- **Vercel** - Connect your GitHub repo
- **GitHub Pages** - Enable in repo settings

**Important**: Voice recognition requires HTTPS in production!

---

## Step 4: Configure the App

1. Open the app in your browser
2. In the **Setup** section:
   - Paste your **API Key**
   - Paste your **Connection Key**
3. Click **Save & Connect**
4. Wait for "Connected" status

---

## Step 5: Enable Microphone

1. Browser will ask for microphone permission
2. Click **Allow** or **Grant permission**
3. If blocked, click the lock icon in address bar
4. Change microphone permission to "Allow"

---

## Step 6: Start Voice Control

1. Click the **🎤 Start Listening** button
2. Button will turn red and show "🔴 Stop Listening"
3. Speak a command clearly
4. App will show what it heard and execute the command

---

## Voice Commands Reference

### Basic Controls
- **"start"** or **"begin"** - Start the device
- **"stop"** or **"halt"** - Stop the device
- **"emergency stop"** - Immediate stop

### Speed Control
- **"speed up"** or **"faster"** - Increase speed by 10%
- **"slow down"** or **"slower"** - Decrease speed by 10%
- **"set speed to [number]"** - Set specific speed (0-100)
  - Example: "set speed to 50"
- **"full speed"** - Maximum speed
- **"half speed"** - 50% speed
- **"minimum speed"** - Lowest speed

### Stroke Range
- **"increase stroke"** - Longer stroke range
- **"decrease stroke"** - Shorter stroke range
- **"full stroke"** - Maximum stroke range

---

## Troubleshooting

### Voice Recognition Not Working

**Problem**: Button says "Voice recognition not supported"
- **Solution**: Use Chrome or Edge browser (Firefox/Safari don't support Web Speech API)

**Problem**: Microphone not detected
- **Solution**: 
  1. Check browser permissions (lock icon in address bar)
  2. Check system microphone settings
  3. Try a different browser
  4. Restart browser

**Problem**: Commands not recognized
- **Solution**:
  1. Speak clearly and at normal pace
  2. Reduce background noise
  3. Check microphone volume
  4. Try alternative phrasings (e.g., "begin" instead of "start")

### Connection Issues

**Problem**: "Connection failed" error
- **Solution**:
  1. Verify API key is correct
  2. Verify connection key is correct
  3. Check device is powered on and connected to WiFi
  4. Check your internet connection
  5. Try regenerating API key

**Problem**: "Device not connected" error
- **Solution**:
  1. Ensure device is on same network
  2. Check device firmware is v4 or later
  3. Restart the device
  4. Check Handy server status

### HTTPS Required Error

**Problem**: Voice recognition requires HTTPS
- **Solution**:
  1. For local testing: Use `localhost` (automatically secure)
  2. For remote access: Deploy to Netlify/Vercel (automatic HTTPS)
  3. Or set up local HTTPS certificate

---

## Tips for Best Experience

### Voice Recognition
- Speak at normal volume and pace
- Reduce background noise
- Use a quality microphone
- Wait for confirmation before next command
- Use alternative phrasings if not recognized

### Accessibility
- Use keyboard shortcuts (Tab to navigate, Enter to activate)
- Enable high contrast mode in browser if needed
- Adjust browser zoom for larger text
- Use screen reader if needed (NVDA, JAWS, etc.)

### Safety
- Start with low speeds to test
- Keep emergency stop command ready
- Test manual controls first
- Monitor device temperature
- Follow manufacturer safety guidelines

---

## Privacy & Security

### API Key Storage
- Keys are stored in browser localStorage
- Only accessible by this app on this domain
- Clear browser data to remove keys
- Don't share your API key publicly

### Microphone Access
- Only used for voice commands
- No audio is recorded or transmitted
- Permission can be revoked anytime
- Check browser privacy settings

---

## Getting Help

### Resources
- [Handy API Documentation](https://www.handyfeeling.com/api/handy-rest/v3/docs/)
- [The Handy Support](https://intercom.help/ohdoki/en/)
- [Web Speech API Info](https://developer.mozilla.org/en-US/docs/Web/API/Web_Speech_API)

### Common Questions

**Q: Does this work on mobile?**
A: Voice recognition support varies by mobile browser. Best experience is on desktop Chrome/Edge.

**Q: Can I use this offline?**
A: No, the app requires internet to communicate with The Handy API servers.

**Q: Is my data private?**
A: Yes, all communication is directly between your browser and Handy's servers. No third parties involved.

**Q: Can I customize voice commands?**
A: Currently no, but this is planned for future updates.

---

## Next Steps

Once you're comfortable with basic commands:
1. Experiment with different speed settings
2. Try stroke range adjustments
3. Create your own usage patterns
4. Provide feedback for improvements

---

**Need more help?** Check the TODO.md file for known issues and planned features.
