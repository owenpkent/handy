# Handy Voice - Android App

Voice-controlled Android app for The Handy device, designed for users with disabilities.

## Status

🚧 **In Progress** - Basic structure complete, needs testing on real device.

> **Note**: The V2 web app (`../v2-web/`) is currently more developed and recommended for daily use. This Android app targets the V1 API and is being developed in parallel.

## Features

- **Continuous Voice Listening** - Phone listens for specific commands
- **Accessible UI** - Large buttons, high contrast, screen reader support
- **Voice Feedback** - Text-to-speech confirmation of actions
- **Emergency Stop** - Always-visible large stop button

## Voice Commands

| Command | Examples |
|---------|----------|
| Start device | "Start", "Go", "Begin" |
| Stop device | "Stop", "Halt", "Pause" |
| Increase speed | "Faster", "Speed up" |
| Decrease speed | "Slower", "Slow down" |
| Set specific speed | "Set speed to 50", "Speed fifty" |
| Set stroke length | "Set stroke to 75", "Stroke length 50" |

## Setup

### Requirements
- Android 8.0+ (API 26)
- Handy API Key from [user.handyfeeling.com](https://user.handyfeeling.com)
- Device Connection Key

### Building

1. Open the `android` folder in Android Studio
2. Sync Gradle
3. Build and run on device

### First Run

1. Tap the settings icon (top right)
2. Enter your Handy API Key
3. Enter your Connection Key
4. Tap Save
5. Return to main screen - device will connect automatically

## Usage

1. Tap the large microphone button to start listening
2. Speak commands naturally
3. See real-time transcription of what's heard
4. Get voice + visual confirmation of recognized commands
5. Use the red EMERGENCY STOP button anytime

## Permissions

- **Microphone** - Required for voice recognition
- **Internet** - Required for Handy API communication

## Architecture

```
app/src/main/java/com/handyvoice/app/
├── HandyVoiceApp.kt          # Application class
├── MainActivity.kt           # Main voice control screen
├── SettingsActivity.kt       # API credentials settings
├── api/
│   ├── HandyApiClient.kt     # Handy REST API client
│   └── HandyApiService.kt    # Retrofit API interface
├── data/
│   └── PreferencesManager.kt # DataStore preferences
└── voice/
    ├── VoiceCommand.kt       # Command types & parser
    └── VoiceRecognitionService.kt # Speech recognition
```

## Notes

- Uses Android's built-in SpeechRecognizer for voice recognition
- Continuous listening mode automatically restarts after each utterance
- All network calls use coroutines for async operation
