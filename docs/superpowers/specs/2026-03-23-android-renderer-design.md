# Android Renderer for RayNeo X2 — Design Spec

**Date:** 2026-03-23
**Status:** Review
**Scope:** `apps/android/`, `apps/relay/`, `packages/core/transport.ts`

---

## 1. Overview

An Android APK for RayNeo X2 AR glasses that renders Claude Code agent state as a heads-up display. The glasses connect to a cloud relay via WebSocket and display three primitives: glyph (state dot), whisper (text strip), and card (approval surface). User input is via temple touchpad and contextual voice commands.

### Goals

- Render GlassState on the RayNeo X2 binocular waveguide display
- Support three connection modes: USB, WiFi, cloud relay
- Handle temple touchpad input for card navigation
- Contextual voice recognition for approve/reject
- QR code scanning as the primary connection method
- Stay within RayNeo power and APL constraints

### Prerequisites (changes to existing packages)

Before Android work begins, these changes are required in `@ccg/core`:

1. **Add `id` field to `CardEvent`**: `id: string` — needed for upstream action targeting
2. **Add `cardType` field to `CardEvent`**: `cardType: 'decision' | 'update'` — discriminates behavior (button count, timeout, glyph state)
3. **Parser must emit card events**: The current parser never populates `state.card`. Logic is needed to detect Claude Code permission prompts in the stream-json output and emit decision cards. This is a prerequisite — without it, the card/approval flow has no trigger.

### Non-Goals (v0.1)

- Even Realities G2 support
- Ring accessory input
- Always-on voice (voice is card-contextual only)
- 3 DOF or 6 DOF modes (0 DOF only)
- Phone companion/bridge app

### Intentional Deviations from CLAUDE.md Display Spec

The CLAUDE.md display primitives spec was written for terminal rendering. The following deviations are required by RayNeo hardware constraints:

- **Glyph colors**: Grey is invisible on waveguide outdoors. AR palette uses high-saturation cool tones per RayNeo guidelines.
- **Card border**: CLAUDE.md says "1px white border at 40% opacity." RayNeo requires >= 2px lines (1px breaks on waveguide). Cyan border chosen for outdoor visibility.
- **Card background**: CLAUDE.md says "semi-transparent dark background." On waveguide, dark = transparent. Using pure black (fully transparent) with cyan border to define card bounds.
- **Whisper font**: CLAUDE.md says monospace. Dropped for Noto Sans (humanist) — significantly more legible on low-PPD waveguide display. Terminal renderer retains monospace.

---

## 2. System Architecture

```
LAPTOP
  claude --output-format stream-json (stdout, NDJSON)
       |
       v
  @ccg/parser --> @ccg/transport --> Terminal Renderer
                       |
                       | WebSocket (publish GlassState)
                       v
                 Cloud Relay (apps/relay/)
                   auth: session token
                   stateless forwarder
                       |
                       | WebSocket (subscribe GlassState)
                       v
                 RAYNEO X2
                   Transport Client (WS)
                       |
                       v
                   GlassStateManager (StateFlow)
                       |
              +--------+--------+
              |        |        |
          GlyphView WhisperView CardView
                                  |
                           FocusHolder + VoiceCommandManager
                                  |
                           approve/dismiss (upstream via WS)
```

Data flows downstream (parser -> relay -> glasses) except card actions which flow upstream through the same WebSocket connection.

### Connection Modes

| Mode | Glasses connect to | Setup |
|---|---|---|
| USB | `ws://localhost:9200` | `adb forward tcp:9200 tcp:9200` |
| WiFi | `ws://<laptop-ip>:9200` | Same network |
| Relay | `wss://relay.example.com` | Cloud relay URL |

All three modes use the same WebSocket protocol. The glasses app connects to a URL — it doesn't know or care which mode is in use.

---

## 3. Cloud Relay

Location: `apps/relay/`

A stateless Node.js WebSocket server (~100 lines) that pairs publishers (laptop) and renderers (glasses) by session token.

### Auth Handshake

```json
// Client sends on connect:
{"type": "auth", "version": 1, "token": "sess_abc123", "role": "renderer"}

// Server responds:
{"type": "auth_ok"}
// or:
{"type": "auth_fail", "reason": "invalid token"}
```

Protocol version starts at 1. If the relay receives a version it doesn't support, it responds with `auth_fail` and reason `"unsupported protocol version"`.

### Token Generation

The `ccg start` command generates a 16-byte random hex token (e.g., `a3f7bc4adb059a82`). The token is printed to the terminal and encoded in the QR code. Tokens are ephemeral — valid only for the lifetime of the relay process. No persistence, no revocation needed.

Roles:
- `publisher` — laptop/parser. One per token.
- `renderer` — glasses. Multiple allowed per token.

### Message Forwarding

- Publisher sends state -> relay broadcasts to all renderers with matching token
- Renderer sends action -> relay forwards to publisher with matching token
- No persistence, no database
- On renderer connect: relay sends last known state immediately

### Deployment

Deployable to Cloudflare Workers, Fly.io, or any Node.js host. Data volume is negligible (~200 bytes per event, ~50-100 events per session).

---

## 4. WebSocket Protocol

### Downstream (relay -> glasses)

```json
{"type": "state", "data": {"glyph": "running", "whisper": "reading src/auth.ts", "card": null}}
```

Decision card example:
```json
{
  "type": "state",
  "data": {
    "glyph": "awaiting",
    "whisper": null,
    "card": {
      "kind": "card",
      "id": "card_abc123",
      "cardType": "decision",
      "message": "Delete legacy token handler?",
      "confirmLabel": "Approve",
      "dismissLabel": "Reject",
      "timeoutMs": 0
    }
  }
}
```

Update card example:
```json
{
  "type": "state",
  "data": {
    "glyph": "running",
    "whisper": null,
    "card": {
      "kind": "card",
      "id": "card_def456",
      "cardType": "update",
      "message": "Tests passed. Committing changes.",
      "confirmLabel": "Got it",
      "dismissLabel": null,
      "timeoutMs": 30000
    }
  }
}
```

Card type behavior:
- `decision`: two buttons, no timeout, glyph = awaiting, voice active
- `update`: one button (confirmLabel only, dismissLabel is null), auto-dismiss after timeoutMs, glyph stays running, no voice

### Upstream (glasses -> relay -> parser)

```json
{"type": "action", "action": "approve", "cardId": "card_abc123"}
{"type": "action", "action": "dismiss", "cardId": "card_abc123"}
```

---

## 5. Android App Structure

### Framework

Kotlin + ViewBinding + XML layouts. Required by RayNeo MercurySDK.

### Dependencies

- MercurySDK `.aar` (binocular display, touch events, focus management)
- `androidx.core:core-ktx:1.8.0`
- `androidx.appcompat:appcompat:1.3.0`
- `androidx.fragment:fragment-ktx:1.5.3`
- `androidx.activity:activity-ktx:1.3.0-alpha08`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.1`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.1`
- `com.squareup.okhttp3:okhttp` (WebSocket client)
- `com.google.zxing:core` (QR scanning)

### File Structure

```
apps/android/
  app/
    libs/
      mercury-sdk.aar
    src/main/
      AndroidManifest.xml
      java/com/ccg/glasses/
        CCGApplication.kt            # MercurySDK.init()
        MainActivity.kt              # QR scanner (default landing screen)
        HudActivity.kt               # BaseMirrorActivity — the HUD
        state/
          GlassStateManager.kt       # StateFlow<GlassState>, drives UI
        transport/
          TransportClient.kt         # Interface: connect, disconnect, onState, sendAction
          WebSocketClient.kt         # OkHttp WebSocket implementation
        ui/
          GlyphView.kt               # Custom View — dot + pulse animation
          WhisperView.kt             # Custom View — text strip + auto-fade
          CardView.kt                # Custom View — card with focus buttons
        voice/
          VoiceCommandManager.kt     # SpeechRecognizer + voiceassistant audio mode
      res/
        layout/
          activity_main.xml           # QR scanner UI
          activity_hud.xml            # Glyph + Whisper + Card
        values/
          colors.xml                  # AR color palette
          styles.xml                  # Noto Sans theme
    build.gradle
  build.gradle
  settings.gradle
  gradle.properties
```

### Manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <application android:name=".CCGApplication">
        <meta-data
            android:name="com.rayneo.mercury.app"
            android:value="true" />
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity android:name=".HudActivity" />
    </application>
</manifest>
```

Required permissions: `INTERNET` (WebSocket), `CAMERA` (QR scanning), `RECORD_AUDIO` (voice commands).

### Key Classes

**CCGApplication** — calls `MercurySDK.init(this)` in `onCreate`.

**MainActivity** — QR scanner as default screen. Opens camera, scans for `ccg://connect?url=...&token=...` URI. On scan, launches HudActivity with URL and token as extras. Fallback: manual URL entry via swipe to second screen.

**HudActivity** — extends `BaseMirrorActivity<ActivityHudBinding>`. Gets binocular mirroring and temple input automatically. Collects `GlassStateManager.state` flow and updates GlyphView, WhisperView, CardView. Wires `templeActionViewModel.state` to `FixPosFocusTracker` for card navigation.

**GlassStateManager** — receives GlassState JSON from `TransportClient`, parses it, exposes as `StateFlow<GlassState>`. Single source of truth for UI.

**TransportClient** — interface: `connect(url, token)`, `disconnect()`, `stateFlow: Flow<GlassState>`, `sendAction(action)`. `WebSocketClient` is the implementation. USB mode uses `ws://localhost:9200` via ADB port forward — same protocol.

**VoiceCommandManager** — activated when a card appears. Sets `AudioManager.setParameters("audio_source_record=voiceassistant")`. Starts Android `SpeechRecognizer` with partial results. Matches against keyword set. Deactivates on card dismiss, sets `audio_source_record=off`.

---

## 6. Hardware Constraints

Source: RayNeo official design guidelines.

| Attribute | Spec | Our Compliance |
|---|---|---|
| Canvas | 640x480px | All layout targets this |
| Background | Pure black (#000000) | Yes — black = transparent on waveguide |
| Min font size | 16px | Our min is 20px |
| Min line width | 2px | Card border is 2px |
| APL continuous | < 13% | ~6% without card, ~11% with card |
| APL peak | < 25% | ~11% max |
| Refresh rate | 30fps | Animations capped at 30fps |
| Logical threads | <= 4 | WebSocket + StateFlow + UI + Voice (when active) |
| Safety margin | 16-30px | 24px on all edges |
| DOF mode | 0 DOF | Fixed HUD, no head tracking |

---

## 7. Visual Design

### Color Palette (AR-adapted)

Terminal renderer colors are unchanged. These apply to the AR renderer only.

| State | Color | Hex | Animation |
|---|---|---|---|
| idle | dim cyan | #4DE2FF @ 40% alpha | static |
| thinking | cyan | #4DE2FF | slow pulse, alpha 0.4-1.0, 2s period |
| running | fluorescent green | #44FF66 | fast pulse, alpha 0.4-1.0, 1s period |
| awaiting | purple | #8566FF | steady glow, alpha 1.0 |
| done | fluorescent green | #44FF66 | fade 1.0 -> 0.0 over 2s |
| error | red | #FF4444 | static, alpha 1.0 |

**Why not the CLAUDE.md colors?** Grey is invisible on a waveguide lens outdoors. Amber is a warm tone with higher power draw. The AR palette uses RayNeo's recommended high-saturation cool tones for maximum visibility.

### Typography

- Font: Noto Sans (ships on Android, no bundling)
- Whisper: 20px Medium, white (#FFFFFF)
- Card message: 24px Medium, white (#FFFFFF)
- Card buttons: 20px Medium
- Glyph character: 28px Bold
- Line spacing: 1.2x
- No monospace on AR (dropped for legibility on waveguide)

### Layout

```
640x480 canvas, pure black, 0 DOF

+----------------------------------------------------------------+
|  24px safety margin                                            |
|                                                                |
|                                                                |
|              +--------------------------------+                |
|              |                                |  CardView      |
|              |  Delete legacy token handler?  |  (centered)    |
|              |                                |  2px cyan      |
|              |  [* Approve]     [x Reject]    |  border        |
|              |                                |  black bg      |
|              +--------------------------------+                |
|                                                                |
|                                                                |
|  reading src/auth.ts                              V            |
|  ^ WhisperView (bottom-left)         GlyphView ^ (bot-right)  |
|  24px safety margin                                            |
+----------------------------------------------------------------+
```

### Glyph

- Position: bottom-right, 24px from edges
- Pulse animation via `ValueAnimator` on alpha, 30fps cap
- Size: 28px Bold — visible but not dominant

Unicode codepoints:

| State | Character | Unicode | Semantic |
|---|---|---|---|
| idle | ▽ | U+25BD | outline down — passive, grounded |
| thinking | ▼ | U+25BC | solid down — active, internal |
| running | ▲ | U+25B2 | solid up — active, external |
| awaiting | △ | U+25B3 | outline up — passive, waiting |
| done | ▽ | U+25BD | outline down — back to ground |
| error | ✕ | U+2715 | break — something wrong |

### Whisper

- Position: bottom-left, 24px from edges
- Single line, 48 character hard limit
- White text, no background (floats on transparent black)
- Auto-fade via `ObjectAnimator`: starts after TTL (4s default, 8s for tool_use)
- Hidden when a card is active

### Card

- Position: centered in canvas
- Background: pure black (#000000) — transparent on waveguide
- Border: 2px solid cyan (#4DE2FF)
- Message: white, 24px Medium
- Buttons: two, horizontal layout

Button styling:
- **Focused button**: white text, green (#44FF66) brackets `[* ...]`
- **Unfocused button**: dim white (#FFFFFF @ 40%) text, dim cyan (#4DE2FF @ 40%) brackets

Card types:
- **Decision card**: two buttons (Approve/Reject), no timeout, glyph shows awaiting
- **Update card**: single button (Got it), auto-dismiss 30s, glyph stays running

---

## 8. Input

### Temple Touchpad

HudActivity extends `BaseMirrorActivity`, providing `templeActionViewModel.state` as a Kotlin Flow of `TempleAction` events.

**No card showing:**

| Action | Result |
|---|---|
| Tap | No-op |
| Swipe | No-op |

**Card showing:**

| Action | Result |
|---|---|
| Tap | Confirm focused button |
| Swipe forward | Focus -> Reject |
| Swipe backward | Focus -> Approve |

No double-tap handler. Removed to eliminate ~300ms tap latency.

**Update Card (single button):**

| Action | Result |
|---|---|
| Tap | Dismiss (confirm "Got it") |
| Swipe | No-op (single focus target, nowhere to move) |

`FocusHolder` contains one `FocusInfo` for the "Got it" button. Swipes cycle on a single item — effectively no-op.

**Decision Card implementation:**
```kotlin
val focusHolder = FocusHolder(loop = true)

mBindingPair.setLeft {
    focusHolder.addFocusTarget(
        FocusInfo(btnApprove, eventHandler = { onApprove() }, focusChangeHandler = { updateFocus(it) }),
        FocusInfo(btnReject, eventHandler = { onReject() }, focusChangeHandler = { updateFocus(it) })
    )
    focusHolder.currentFocus(mBindingPair.left.btnApprove)
}

fixPosFocusTracker = FixPosFocusTracker(focusHolder)
```

### Voice Commands (Contextual)

Active only when a card is displayed. Uses Android `SpeechRecognizer` with RayNeo `voiceassistant` audio mode (2 temple mics, prioritizes wearer's voice).

**Lifecycle:**
1. Card appears -> `VoiceCommandManager.start()`
2. `AudioManager.setParameters("audio_source_record=voiceassistant")`
3. `SpeechRecognizer` starts with partial results enabled
4. Match against keywords
5. Card dismissed -> `VoiceCommandManager.stop()`, `audio_source_record=off`

**Keywords:**

| Voice | Action |
|---|---|
| "approve" / "yes" | Confirm approve |
| "reject" / "no" / "skip" | Confirm reject |
| "status" | Flash whisper with current glyph state name (e.g., "status: running") |

---

## 9. Connection Flow

### QR Code (Primary)

QR scanning is the default landing screen. No typing required.

1. Laptop runs `ccg start` -> parser + relay start -> terminal prints QR code
2. QR encodes: `ccg://connect?url=wss://relay.example.com&token=sess_abc123`
3. Glasses app opens camera, scans QR, connects automatically
4. If QR contains malformed URI: show error whisper "invalid QR code", return to scanner
5. If connection fails (unreachable URL): show error whisper "cannot connect", return to scanner
6. Manual URL entry available as fallback (swipe to second screen)

### QR Generation (Laptop Side)

Terminal prints QR using ANSI block characters:
```
ccg relay running on wss://relay.example.com

Scan to connect:
[QR CODE]

Or enter manually: wss://relay.example.com?token=sess_abc123
```

### Session Persistence

- Last URL + token stored in `SharedPreferences`
- On app launch: if previous session exists, show "Reconnect?" with single tap
- Avoids re-scanning for repeated use

### Reconnection

- Auto-reconnect on disconnect (exponential backoff: 1s, 2s, 4s, max 30s)
- During disconnect: glyph shows error, whisper shows "reconnecting..."
- On reconnect: relay sends last known state immediately
- If a decision card was active when connection dropped: card is dismissed on the glasses side. The parser on the laptop still holds at the decision point. On reconnect, if the parser still has a pending card, it will be re-sent via the state update.

---

## 10. App States

```
Connect (QR scan) --scan--> Connecting (spinner) --auth_ok--> HUD (active)
                                    |                              |
                                auth_fail                     disconnect
                                    v                              v
                               Error (bad token)          Reconnecting (auto-retry)
```

### Rendering Priority

1. **Card** — highest. Whisper hides when card is active. Glyph shifts to awaiting.
2. **Whisper** — shown when no card. Auto-fades on timer.
3. **Glyph** — always visible, never hidden.

### Power Management

- Idle >60s: dim glyph to 20% alpha. Idle >5min: blank display entirely (glyph hidden, wake on next state change)
- WebSocket keepalive: ping every 30s
- Voice recognizer: only during card display
- No background threads beyond WS + StateFlow + UI + (voice when active)

---

## 11. Acceptance Checklist

Per RayNeo guidelines, before delivery:

- [ ] Background is pure black (#000000)
- [ ] Minimum font size > 16px (our min: 20px)
- [ ] Line width >= 2px (card border: 2px)
- [ ] APL < 13% continuous (verified: ~6% normal, ~11% with card)
- [ ] Walking scene: center FOV unblocked (glyph bottom-right, whisper bottom)
- [ ] Voice/touch operation guidance provided
- [ ] Binocular display works via BaseMirrorActivity
- [ ] Temple tap responds instantly (no double-tap delay)
- [ ] QR scan connects successfully
- [ ] Reconnection works after disconnect
- [ ] Voice commands recognized in voiceassistant mode
