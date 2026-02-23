# GMouse

Gesture-controlled Bluetooth HID Mouse & TV Remote for Android.

## Features

- 🖱️ **Touchpad Mode** - Touch-based mouse control with real-time keyboard
- ✋ **Gesture Mode** - Camera-based hand gesture control using MediaPipe
- 📺 **TV Mode** - Android TV remote control with 2-second gesture confirmation

## Gestures

### PC/Laptop Mode
| Gesture | Action |
|---------|--------|
| ☝ Point (index only) | Move cursor |
| ✌ V-sign (index + middle) | Left click |
| 🤙 Point + Pinky | Right click |
| 👍 Thumbs up | Scroll up |
| ✊ Fist | Scroll down |
| ✋ Open hand | Pause |

### TV Mode (2-second hold required)
| Gesture | Action |
|---------|--------|
| ☝ Point | Tile RIGHT |
| ✌ V-sign | Tile LEFT |
| 👍 Thumbs up | Volume UP |
| 👎 Thumbs down | Volume DOWN |
| ✋ Open hand | HOME |
| ✊ Fist | BACK |

## Requirements

- Android 9.0+ (API 28+)
- Bluetooth
- Camera (for gesture mode)

## How to Build

1. Open this folder in Android Studio
2. Sync Gradle
3. Run on device

## Package Info

- Package: `com.gmouse.ag`
- Version: 6.0
