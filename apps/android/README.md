# CCG Glasses - Android Renderer for RayNeo X2

Android app that renders Claude Code agent state on RayNeo X2 AR glasses.

## Prerequisites

- Android Studio (2023.1+)
- RayNeo X2 glasses connected via USB/ADB
- RayNeo MercurySDK `.aar` file (not included in this repo)
- JDK 17

## Setup

1. **Place the MercurySDK AAR** in `app/libs/`:

   ```
   app/libs/mercury-sdk-x.x.x.aar
   ```

   The build.gradle is configured to include all `.aar` files from `app/libs/`.

2. **Open in Android Studio** and sync Gradle.

3. **Build the APK:**

   ```bash
   cd apps/android
   ./gradlew assembleDebug
   ```

## Install via ADB

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Connection

The app connects to the ccg parser running on your laptop via WebSocket.

### QR Code Pairing

1. Start the ccg WebSocket server on your laptop:
   ```bash
   ccg start --target rayneo
   ```
   This prints a QR code to your terminal.

2. Launch the CCG app on the glasses. It opens the QR scanner.

3. Scan the QR code. The code contains a URI:
   ```
   ccg://connect?url=ws://192.168.1.100:9200&token=abc123
   ```

4. The HUD activates and begins displaying agent state.

### Reconnecting

On app resume, if a previous session exists, tap anywhere to reconnect
without scanning again.

## Display Primitives

The HUD renders three elements:

| Element | Position | Description |
|---------|----------|-------------|
| Glyph   | Bottom-right | Unicode symbol with state-driven color and pulse |
| Whisper  | Bottom-left  | Single-line text, 48 char max, auto-fades |
| Card     | Center       | Decision (approve/reject) or update (got it) |

## Input

### Temple Touchpad
- **Tap** - Confirm focused card button
- **Swipe forward** - Move focus to Reject
- **Swipe backward** - Move focus to Approve

### Voice Commands (active during decision cards)
- "approve" / "yes" - Approve
- "reject" / "no" / "skip" - Reject
- "status" - Show current glyph state

## Architecture

```
MainActivity (QR scan) --> HudActivity (BaseMirrorActivity)
                               |
                     GlassStateManager
                               |
                     CCGWebSocketClient
                               |
                    WebSocket to laptop
```

- `transport/` - WebSocket client with auto-reconnect
- `state/` - State manager wrapping transport as StateFlow
- `ui/` - Custom views: GlyphView, WhisperView, CardView
- `voice/` - SpeechRecognizer-based voice command detection

## Notes

- The MercurySDK `.aar` is proprietary and must be obtained from RayNeo.
  Without it, the project will not compile. This is expected.
- `BaseMirrorActivity` handles binocular rendering. All UI updates go
  through `mBindingPair.updateView{}` to update both displays.
- The app targets SDK 34, min SDK 29.
