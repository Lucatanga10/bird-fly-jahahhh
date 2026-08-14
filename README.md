# BumpBot — Flappy Bird Auto-Player

Android overlay app that plays Flappy Bird mini-games automatically using screen capture + computer vision + auto-tap.

## How it works

1. Captures your screen at ~60fps via MediaProjection (downscaled for performance)
2. Detects the bird position and pipe gaps using color-distance analysis
3. Auto-taps via AccessibilityService when the bird needs to go up
4. Runs as a floating overlay (MOD menu style) on top of any app

## Setup

### Build
```bash
./gradlew assembleDebug
```
APK will be at `app/build/outputs/apk/debug/app-debug.apk`

### Install
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Permissions needed
1. **Overlay** — draws the floating menu on top of other apps
2. **Accessibility** — injects screen taps without root
3. **Screen capture** — reads the game screen

### First run
1. Open BumpBot
2. Tap "Enable Accessibility" → find BumpBot in the list → enable it
3. Tap "Launch Bot" → approve screen capture
4. Switch to Bump → open the Flappy Bird game
5. In the floating menu, tap **Calibrate** (samples background color)
6. Tap **START**

## Floating Menu Controls

- **START** — begins auto-play
- **STOP** — pauses the bot
- **Calibrate** — re-samples background color (do this when game colors change)
- **Close** — kills the service
- Drag the menu to reposition it

## Architecture

- `FlappyBot` — vision engine: finds bird Y, scans for pipe gaps, bang-bang controller decides tap/wait
- `OverlayService` — floating menu + screen capture loop + bot invocation
- `TapAccessibilityService` — injects taps via `dispatchGesture()`
- `MainActivity` — permission flow launcher
