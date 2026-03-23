# Android Renderer for RayNeo X2 — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android APK for RayNeo X2 that renders Claude Code agent state (glyph, whisper, card) on AR glasses via a cloud WebSocket relay.

**Architecture:** Parser on laptop publishes GlassState through a cloud WebSocket relay. Android app on glasses subscribes and renders three display primitives. Temple touchpad and contextual voice commands handle card approval. QR code scanning for connection setup.

**Tech Stack:** TypeScript/Node.js (relay + core), Kotlin + ViewBinding + MercurySDK (Android), OkHttp (WebSocket client), ZXing (QR), Android SpeechRecognizer (voice)

**Spec:** `docs/superpowers/specs/2026-03-23-android-renderer-design.md`

---

## File Structure

### New files — @ccg/core & parser changes
- Modify: `packages/core/src/index.ts` — add `id`, `cardType` to CardEvent; add `relay` transport type
- Modify: `packages/parser/src/index.ts` — emit card events on permission prompts
- Create: `packages/core/src/__tests__/types.test.ts` — type validation tests
- Create: `packages/parser/src/__tests__/parser.test.ts` — parser card emission tests

### New files — Cloud Relay
- Create: `apps/relay/package.json`
- Create: `apps/relay/src/index.ts` — WebSocket relay server
- Create: `apps/relay/src/__tests__/relay.test.ts`

### Modified files — CLI
- Modify: `apps/cli/src/index.ts` — add relay start + QR code generation
- Modify: `apps/cli/package.json` — add qrcode-terminal dep

### New files — Android App
- Create: `apps/android/build.gradle` — root build
- Create: `apps/android/settings.gradle`
- Create: `apps/android/gradle.properties`
- Create: `apps/android/app/build.gradle` — app build with MercurySDK
- Create: `apps/android/app/src/main/AndroidManifest.xml`
- Create: `apps/android/app/src/main/java/com/ccg/glasses/CCGApplication.kt`
- Create: `apps/android/app/src/main/java/com/ccg/glasses/MainActivity.kt`
- Create: `apps/android/app/src/main/java/com/ccg/glasses/HudActivity.kt`
- Create: `apps/android/app/src/main/java/com/ccg/glasses/state/GlassStateManager.kt`
- Create: `apps/android/app/src/main/java/com/ccg/glasses/transport/TransportClient.kt`
- Create: `apps/android/app/src/main/java/com/ccg/glasses/transport/WebSocketClient.kt`
- Create: `apps/android/app/src/main/java/com/ccg/glasses/ui/GlyphView.kt`
- Create: `apps/android/app/src/main/java/com/ccg/glasses/ui/WhisperView.kt`
- Create: `apps/android/app/src/main/java/com/ccg/glasses/ui/CardView.kt`
- Create: `apps/android/app/src/main/java/com/ccg/glasses/voice/VoiceCommandManager.kt`
- Create: `apps/android/app/src/main/res/layout/activity_main.xml`
- Create: `apps/android/app/src/main/res/layout/activity_hud.xml`
- Create: `apps/android/app/src/main/res/values/colors.xml`
- Create: `apps/android/app/src/main/res/values/styles.xml`
- Create: `apps/android/app/src/main/res/drawable/card_border.xml`

---

## Chunk 1: Prerequisites — Core Type Changes & Parser Card Emission

### Task 1: Add `id` and `cardType` to CardEvent

**Files:**
- Modify: `packages/core/src/index.ts:19-25`

- [ ] **Step 1: Update CardEvent interface**

Add `id` and `cardType` fields. Make `dismissLabel` nullable for update cards:

```typescript
export type CardType = 'decision' | 'update'

export interface CardEvent {
  kind: 'card'
  id: string
  cardType: CardType
  message: string       // max 2 lines
  confirmLabel: string
  dismissLabel: string | null
  timeoutMs: number
}
```

- [ ] **Step 2: Add `relay` to TransportType**

```typescript
export type TransportType = 'usb' | 'wifi' | 'relay' | 'terminal'
```

- [ ] **Step 3: Verify terminal renderer still compiles**

Run: `cd /Users/powder/Documents/claude-code-glasses && pnpm build`
Expected: no errors (terminal renderer references `card.message`, `card.confirmLabel`, `card.dismissLabel` — all still exist)

- [ ] **Step 4: Commit**

```bash
git add packages/core/src/index.ts
git commit -m "feat(core): add id, cardType to CardEvent; add relay transport"
```

---

### Task 2: Parser emits card events for permission prompts

**Files:**
- Modify: `packages/parser/src/index.ts`

Claude Code emits a `system` message with `subtype: "permission_request"` when it needs tool approval. The parser needs to detect this and create a decision card.

- [ ] **Step 1: Add card ID counter to parser**

In `ClaudeCodeParser` class, add:

```typescript
private cardIdCounter = 0

private nextCardId(): string {
  return `card_${++this.cardIdCounter}`
}
```

- [ ] **Step 2: Handle permission_request in handleMessage**

Add a case in the `system` handler:

```typescript
case 'system':
  if (msg.subtype === 'permission_request') {
    const tool = (msg as Record<string, unknown>).tool as string ?? 'unknown'
    const description = (msg as Record<string, unknown>).description as string ?? `Allow ${tool}?`
    this.setState({
      glyph: 'awaiting',
      whisper: null,
      card: {
        kind: 'card',
        id: this.nextCardId(),
        cardType: 'decision',
        message: truncateWhisper(description),
        confirmLabel: 'Approve',
        dismissLabel: 'Reject',
        timeoutMs: 0,
      },
    })
  } else {
    // Session starting (existing behavior)
    this.setState({ glyph: 'thinking', whisper: 'starting…', card: null })
  }
  break
```

- [ ] **Step 3: Add permission_request event to session.jsonl fixture**

Add a new line to `examples/basic/session.jsonl` after line 8 (the Edit tool use):

```json
{"type":"system","subtype":"permission_request","tool":"Edit","description":"Delete legacy token handler?","session_id":"sess_abc123"}
```

- [ ] **Step 4: Test with simulator**

Run: `cd /Users/powder/Documents/claude-code-glasses && pnpm simulate`
Expected: See awaiting glyph (◎ purple) with card "Delete legacy token handler?" appear, then continue after timeout

- [ ] **Step 5: Commit**

```bash
git add packages/parser/src/index.ts examples/basic/session.jsonl
git commit -m "feat(parser): emit decision card on permission_request events"
```

---

## Chunk 2: Cloud Relay

### Task 3: Create relay package scaffold

**Files:**
- Create: `apps/relay/package.json`
- Create: `apps/relay/src/index.ts`

- [ ] **Step 1: Create package.json**

```json
{
  "name": "@ccg/relay",
  "version": "0.1.0",
  "private": true,
  "main": "src/index.ts",
  "scripts": {
    "start": "tsx src/index.ts",
    "dev": "tsx watch src/index.ts",
    "test": "vitest run"
  },
  "dependencies": {
    "ws": "^8.16.0"
  },
  "devDependencies": {
    "@types/ws": "^8.5.10",
    "tsx": "^4.7.0",
    "vitest": "^1.2.0"
  }
}
```

- [ ] **Step 2: Implement relay server**

Create `apps/relay/src/index.ts`:

```typescript
import { WebSocketServer, WebSocket } from 'ws'
import { randomBytes } from 'crypto'

const PROTOCOL_VERSION = 1
const PORT = parseInt(process.env.CCG_RELAY_PORT ?? '9200', 10)

interface Session {
  token: string
  publisher: WebSocket | null
  renderers: Set<WebSocket>
  lastState: string | null
}

const sessions = new Map<string, Session>()

export function createRelay(port = PORT): { wss: WebSocketServer; close: () => void } {
  const wss = new WebSocketServer({ port })

  wss.on('connection', (ws) => {
    let authenticated = false
    let sessionToken: string | null = null
    let role: string | null = null

    // Auth timeout — must authenticate within 5s
    const authTimeout = setTimeout(() => {
      if (!authenticated) {
        ws.send(JSON.stringify({ type: 'auth_fail', reason: 'auth timeout' }))
        ws.close()
      }
    }, 5000)

    ws.on('message', (raw) => {
      let msg: Record<string, unknown>
      try {
        msg = JSON.parse(raw.toString())
      } catch {
        return
      }

      // Auth handshake
      if (!authenticated) {
        if (msg.type !== 'auth') {
          ws.send(JSON.stringify({ type: 'auth_fail', reason: 'expected auth message' }))
          ws.close()
          return
        }

        if (msg.version !== PROTOCOL_VERSION) {
          ws.send(JSON.stringify({ type: 'auth_fail', reason: 'unsupported protocol version' }))
          ws.close()
          return
        }

        const token = msg.token as string
        role = msg.role as string

        if (!token || !role || !['publisher', 'renderer'].includes(role)) {
          ws.send(JSON.stringify({ type: 'auth_fail', reason: 'invalid token or role' }))
          ws.close()
          return
        }

        // Get or create session
        if (!sessions.has(token)) {
          sessions.set(token, { token, publisher: null, renderers: new Set(), lastState: null })
        }
        const session = sessions.get(token)!

        if (role === 'publisher') {
          if (session.publisher) {
            ws.send(JSON.stringify({ type: 'auth_fail', reason: 'publisher already connected' }))
            ws.close()
            return
          }
          session.publisher = ws
        } else {
          session.renderers.add(ws)
        }

        authenticated = true
        sessionToken = token
        clearTimeout(authTimeout)
        ws.send(JSON.stringify({ type: 'auth_ok' }))

        // Send last known state to new renderer
        if (role === 'renderer' && session.lastState) {
          ws.send(session.lastState)
        }
        return
      }

      // Authenticated message routing
      const session = sessions.get(sessionToken!)
      if (!session) return

      if (role === 'publisher' && msg.type === 'state') {
        // Store and broadcast to all renderers
        const stateMsg = JSON.stringify(msg)
        session.lastState = stateMsg
        for (const renderer of session.renderers) {
          if (renderer.readyState === WebSocket.OPEN) {
            renderer.send(stateMsg)
          }
        }
      } else if (role === 'renderer' && msg.type === 'action') {
        // Forward action to publisher
        if (session.publisher?.readyState === WebSocket.OPEN) {
          session.publisher.send(JSON.stringify(msg))
        }
      }
    })

    ws.on('close', () => {
      clearTimeout(authTimeout)
      if (!sessionToken) return
      const session = sessions.get(sessionToken)
      if (!session) return

      if (role === 'publisher') {
        session.publisher = null
        // Notify renderers that publisher disconnected
        for (const renderer of session.renderers) {
          if (renderer.readyState === WebSocket.OPEN) {
            renderer.send(JSON.stringify({
              type: 'state',
              data: { glyph: 'error', whisper: 'publisher disconnected', card: null }
            }))
          }
        }
      } else {
        session.renderers.delete(ws)
      }

      // Clean up empty sessions
      if (!session.publisher && session.renderers.size === 0) {
        sessions.delete(sessionToken)
      }
    })
  })

  return {
    wss,
    close: () => {
      sessions.clear()
      wss.close()
    }
  }
}

// Token generation
export function generateToken(): string {
  return randomBytes(16).toString('hex')
}

// Start if run directly
if (process.argv[1]?.endsWith('index.ts') || process.argv[1]?.endsWith('index.js')) {
  const token = generateToken()
  const { wss } = createRelay(PORT)
  console.log(`ccg relay running on ws://localhost:${PORT}`)
  console.log(`token: ${token}`)
  wss.on('listening', () => {
    console.log(`ready`)
  })
}
```

- [ ] **Step 3: Install dependencies**

Run: `cd /Users/powder/Documents/claude-code-glasses && pnpm install`

- [ ] **Step 4: Commit**

```bash
git add apps/relay/
git commit -m "feat(relay): add WebSocket relay server with auth and session management"
```

---

### Task 4: Relay tests

**Files:**
- Create: `apps/relay/src/__tests__/relay.test.ts`

- [ ] **Step 1: Write relay test suite**

```typescript
import { describe, it, expect, afterEach } from 'vitest'
import WebSocket from 'ws'
import { createRelay, generateToken } from '../index'

const TEST_PORT = 9876

function connectClient(port: number, auth: Record<string, unknown>): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://localhost:${port}`)
    ws.on('open', () => {
      ws.send(JSON.stringify(auth))
      ws.once('message', (raw) => {
        const msg = JSON.parse(raw.toString())
        if (msg.type === 'auth_ok') resolve(ws)
        else reject(new Error(`Auth failed: ${msg.reason}`))
      })
    })
    ws.on('error', reject)
  })
}

function waitForMessage(ws: WebSocket): Promise<Record<string, unknown>> {
  return new Promise((resolve) => {
    ws.once('message', (raw) => resolve(JSON.parse(raw.toString())))
  })
}

describe('relay', () => {
  let relay: ReturnType<typeof createRelay>

  afterEach(() => {
    relay?.close()
  })

  it('authenticates publisher and renderer', async () => {
    relay = createRelay(TEST_PORT)
    const token = generateToken()

    const pub = await connectClient(TEST_PORT, { type: 'auth', version: 1, token, role: 'publisher' })
    const ren = await connectClient(TEST_PORT, { type: 'auth', version: 1, token, role: 'renderer' })

    pub.close()
    ren.close()
  })

  it('forwards state from publisher to renderer', async () => {
    relay = createRelay(TEST_PORT)
    const token = generateToken()

    const pub = await connectClient(TEST_PORT, { type: 'auth', version: 1, token, role: 'publisher' })
    const ren = await connectClient(TEST_PORT, { type: 'auth', version: 1, token, role: 'renderer' })

    const msgPromise = waitForMessage(ren)
    pub.send(JSON.stringify({ type: 'state', data: { glyph: 'running', whisper: 'test', card: null } }))
    const msg = await msgPromise

    expect(msg.type).toBe('state')
    expect((msg.data as Record<string, unknown>).glyph).toBe('running')

    pub.close()
    ren.close()
  })

  it('forwards action from renderer to publisher', async () => {
    relay = createRelay(TEST_PORT)
    const token = generateToken()

    const pub = await connectClient(TEST_PORT, { type: 'auth', version: 1, token, role: 'publisher' })
    const ren = await connectClient(TEST_PORT, { type: 'auth', version: 1, token, role: 'renderer' })

    const msgPromise = waitForMessage(pub)
    ren.send(JSON.stringify({ type: 'action', action: 'approve', cardId: 'card_1' }))
    const msg = await msgPromise

    expect(msg.type).toBe('action')
    expect(msg.action).toBe('approve')

    pub.close()
    ren.close()
  })

  it('sends last known state to new renderer', async () => {
    relay = createRelay(TEST_PORT)
    const token = generateToken()

    const pub = await connectClient(TEST_PORT, { type: 'auth', version: 1, token, role: 'publisher' })

    // Publish state before renderer connects
    pub.send(JSON.stringify({ type: 'state', data: { glyph: 'thinking', whisper: 'hello', card: null } }))
    await new Promise(r => setTimeout(r, 50))

    // Connect renderer — should get last state immediately
    const ren = await connectClient(TEST_PORT, { type: 'auth', version: 1, token, role: 'renderer' })
    const msg = await waitForMessage(ren)

    expect(msg.type).toBe('state')
    expect((msg.data as Record<string, unknown>).glyph).toBe('thinking')

    pub.close()
    ren.close()
  })

  it('rejects invalid protocol version', async () => {
    relay = createRelay(TEST_PORT)
    const ws = new WebSocket(`ws://localhost:${TEST_PORT}`)

    const result = await new Promise<Record<string, unknown>>((resolve) => {
      ws.on('open', () => {
        ws.send(JSON.stringify({ type: 'auth', version: 99, token: 'test', role: 'publisher' }))
        ws.on('message', (raw) => resolve(JSON.parse(raw.toString())))
      })
    })

    expect(result.type).toBe('auth_fail')
    expect(result.reason).toBe('unsupported protocol version')
    ws.close()
  })

  it('rejects duplicate publisher', async () => {
    relay = createRelay(TEST_PORT)
    const token = generateToken()

    const pub1 = await connectClient(TEST_PORT, { type: 'auth', version: 1, token, role: 'publisher' })

    const ws = new WebSocket(`ws://localhost:${TEST_PORT}`)
    const result = await new Promise<Record<string, unknown>>((resolve) => {
      ws.on('open', () => {
        ws.send(JSON.stringify({ type: 'auth', version: 1, token, role: 'publisher' }))
        ws.on('message', (raw) => resolve(JSON.parse(raw.toString())))
      })
    })

    expect(result.type).toBe('auth_fail')
    expect(result.reason).toBe('publisher already connected')

    pub1.close()
    ws.close()
  })
})
```

- [ ] **Step 2: Run tests**

Run: `cd /Users/powder/Documents/claude-code-glasses/apps/relay && npx vitest run`
Expected: All 6 tests pass

- [ ] **Step 3: Commit**

```bash
git add apps/relay/src/__tests__/
git commit -m "test(relay): add relay server test suite"
```

---

### Task 5: Update CLI with relay start + QR code

**Files:**
- Modify: `apps/cli/src/index.ts`
- Modify: `apps/cli/package.json`

- [ ] **Step 1: Add qrcode-terminal dependency**

Add to `apps/cli/package.json` dependencies:

```json
"qrcode-terminal": "^0.12.0"
```

Run: `pnpm install`

- [ ] **Step 2: Update CLI start command for relay mode**

In `apps/cli/src/index.ts`, update the `start` case to support `--relay` flag:

```typescript
case 'start': {
  const targetIdx = args.indexOf('--target')
  const target = targetIdx !== -1 ? args[targetIdx + 1] : 'terminal'
  const relayFlag = args.includes('--relay')

  if (target === 'terminal') {
    const parser = new ClaudeCodeParser()
    const renderer = new TerminalRenderer(parser)
    renderer.start()
    parser.start(process.stdin)

    if (relayFlag) {
      // Dynamic import to avoid requiring relay for terminal-only use
      const { createRelay, generateToken } = await import('@ccg/relay')
      const qrcode = await import('qrcode-terminal')
      const token = generateToken()
      const portIdx = args.indexOf('--relay-port')
      const port = portIdx !== -1 ? parseInt(args[portIdx + 1], 10) : 9200
      const { wss } = createRelay(port)

      // Forward parser state to relay as publisher
      const WebSocket = (await import('ws')).default
      let pubWs: InstanceType<typeof WebSocket> | null = null

      wss.on('listening', () => {
        const url = `ws://localhost:${port}`
        const connectUri = `ccg://connect?url=${encodeURIComponent(url)}&token=${token}`

        console.log(`\n  relay running on ${url}`)
        console.log(`  token: ${token}\n`)
        qrcode.generate(connectUri, { small: true })
        console.log(`\n  Or enter manually: ${url}?token=${token}\n`)

        // Connect parser as internal publisher
        pubWs = new WebSocket(url)
        pubWs.on('open', () => {
          pubWs!.send(JSON.stringify({ type: 'auth', version: 1, token, role: 'publisher' }))
        })

        parser.on('state', (state) => {
          if (pubWs?.readyState === WebSocket.OPEN) {
            pubWs.send(JSON.stringify({ type: 'state', data: state }))
          }
        })

        // Handle actions from renderers
        pubWs.on('message', (raw) => {
          const msg = JSON.parse(raw.toString())
          if (msg.type === 'action') {
            if (msg.action === 'approve') parser.approve()
            else if (msg.action === 'dismiss') parser.dismiss()
          }
        })
      })
    }

    parser.on('close', () => {
      setTimeout(() => {
        renderer.stop()
        process.exit(0)
      }, 2000)
    })
  } else if (target === 'rayneo') {
    console.error('RayNeo renderer: use --relay to start the relay, then connect from glasses')
    process.exit(1)
  } else {
    console.error(`Unknown target: ${target}. Use: terminal | rayneo`)
    process.exit(1)
  }
  break
}
```

- [ ] **Step 3: Update help text**

Update the help case to include relay options:

```typescript
console.log(`
  claude-code-glasses v0.1.0
  Pipe Claude Code's brain to your face.

  Usage:
    claude --output-format stream-json | ccg start
    claude --output-format stream-json | ccg start --relay
    claude --output-format stream-json | ccg start --relay --relay-port 9200
    ccg simulate --file ./examples/basic/session.jsonl
    ccg status

  Options:
    --target      terminal | rayneo  (default: terminal)
    --relay       start WebSocket relay for glasses connection
    --relay-port  relay port (default: 9200)
    --file        path to .jsonl session file for simulate
`)
```

- [ ] **Step 4: Add @ccg/relay as CLI dependency**

Add to `apps/cli/package.json` dependencies:

```json
"@ccg/relay": "workspace:*"
```

Run: `pnpm install`

- [ ] **Step 5: Test relay starts with simulate**

Run: `cd /Users/powder/Documents/claude-code-glasses && pnpm simulate`
Expected: Terminal renderer works as before (no --relay flag = no relay started)

- [ ] **Step 6: Commit**

```bash
git add apps/cli/ apps/relay/package.json
git commit -m "feat(cli): add --relay flag to start WebSocket relay with QR code"
```

---

## Chunk 3: Android Project Scaffold

### Task 6: Create Android Gradle project

**Files:**
- Create: `apps/android/build.gradle`
- Create: `apps/android/settings.gradle`
- Create: `apps/android/gradle.properties`
- Create: `apps/android/app/build.gradle`

- [ ] **Step 1: Create root build.gradle**

```groovy
// apps/android/build.gradle
buildscript {
    ext.kotlin_version = '1.9.22'
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.2.2'
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

task clean(type: Delete) {
    delete rootProject.buildDir
}
```

- [ ] **Step 2: Create settings.gradle**

```groovy
// apps/android/settings.gradle
rootProject.name = 'ccg-glasses'
include ':app'
```

- [ ] **Step 3: Create gradle.properties**

```properties
# apps/android/gradle.properties
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2048m
```

- [ ] **Step 4: Create app/build.gradle**

```groovy
// apps/android/app/build.gradle
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.ccg.glasses'
    compileSdk 34

    defaultConfig {
        applicationId "com.ccg.glasses"
        minSdk 29  // Android 10 (RayNeo X2 minimum)
        targetSdk 34
        versionCode 1
        versionName "0.1.0"
    }

    buildFeatures {
        viewBinding true
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }
}

dependencies {
    // MercurySDK
    implementation fileTree(dir: "libs", include: ["*.aar"])

    // Core AndroidX + Kotlin (versions validated against MercurySDK)
    implementation "androidx.core:core-ktx:1.8.0"
    implementation "androidx.appcompat:appcompat:1.3.0"
    implementation "androidx.fragment:fragment-ktx:1.5.3"
    implementation "androidx.activity:activity-ktx:1.3.0-alpha08"
    implementation "androidx.recyclerview:recyclerview:1.1.0"
    implementation "androidx.lifecycle:lifecycle-runtime-ktx:2.6.2"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.1"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.1"

    // WebSocket
    implementation "com.squareup.okhttp3:okhttp:4.12.0"

    // QR scanning
    implementation "com.google.zxing:core:3.5.2"
    implementation "com.journeyapps:zxing-android-embedded:4.3.0"
}
```

- [ ] **Step 5: Create libs directory placeholder**

Run: `mkdir -p /Users/powder/Documents/claude-code-glasses/apps/android/app/libs`

Note: The `mercury-sdk.aar` must be manually placed in `apps/android/app/libs/` from the RayNeo developer portal. The build will fail without it. Add a README note about this.

- [ ] **Step 6: Commit**

```bash
git add apps/android/build.gradle apps/android/settings.gradle apps/android/gradle.properties apps/android/app/build.gradle
git commit -m "feat(android): scaffold Gradle project with MercurySDK dependencies"
```

---

### Task 7: AndroidManifest + Application class

**Files:**
- Create: `apps/android/app/src/main/AndroidManifest.xml`
- Create: `apps/android/app/src/main/java/com/ccg/glasses/CCGApplication.kt`

- [ ] **Step 1: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <application
        android:name=".CCGApplication"
        android:label="CCG"
        android:theme="@style/Theme.CCG"
        android:supportsRtl="true">

        <meta-data
            android:name="com.rayneo.mercury.app"
            android:value="true" />

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity android:name=".HudActivity" />
    </application>
</manifest>
```

- [ ] **Step 2: Create CCGApplication.kt**

```kotlin
package com.ccg.glasses

import android.app.Application
import com.ffalcon.mercury.android.sdk.MercurySDK

class CCGApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MercurySDK.init(this)
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add apps/android/app/src/
git commit -m "feat(android): add manifest with permissions and MercurySDK init"
```

---

### Task 8: Color and style resources

**Files:**
- Create: `apps/android/app/src/main/res/values/colors.xml`
- Create: `apps/android/app/src/main/res/values/styles.xml`
- Create: `apps/android/app/src/main/res/drawable/card_border.xml`

- [ ] **Step 1: Create colors.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Base -->
    <color name="black">#FF000000</color>
    <color name="white">#FFFFFFFF</color>
    <color name="white_dim">#66FFFFFF</color>

    <!-- AR Glyph Palette -->
    <color name="glyph_cyan">#FF4DE2FF</color>
    <color name="glyph_cyan_dim">#664DE2FF</color>
    <color name="glyph_green">#FF44FF66</color>
    <color name="glyph_purple">#FF8566FF</color>
    <color name="glyph_red">#FFFF4444</color>

    <!-- Card -->
    <color name="card_border">#FF4DE2FF</color>
    <color name="card_background">#FF000000</color>
    <color name="button_focus_bracket">#FF44FF66</color>
    <color name="button_dim_bracket">#664DE2FF</color>
</resources>
```

- [ ] **Step 2: Create styles.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.CCG" parent="Theme.AppCompat.NoActionBar">
        <item name="android:windowBackground">@color/black</item>
        <item name="android:colorBackground">@color/black</item>
        <item name="android:fontFamily">sans-serif</item>
        <item name="android:windowFullscreen">true</item>
        <item name="android:windowNoTitle">true</item>
    </style>
</resources>
```

- [ ] **Step 3: Create card_border.xml drawable**

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/card_background" />
    <stroke
        android:width="2dp"
        android:color="@color/card_border" />
</shape>
```

- [ ] **Step 4: Commit**

```bash
git add apps/android/app/src/main/res/
git commit -m "feat(android): add AR color palette, theme, and card border drawable"
```

---

## Chunk 4: Android Transport Layer

### Task 9: TransportClient interface + WebSocket implementation

**Files:**
- Create: `apps/android/app/src/main/java/com/ccg/glasses/transport/TransportClient.kt`
- Create: `apps/android/app/src/main/java/com/ccg/glasses/transport/WebSocketClient.kt`

- [ ] **Step 1: Create TransportClient interface**

```kotlin
package com.ccg.glasses.transport

import kotlinx.coroutines.flow.Flow

data class GlassStateData(
    val glyph: String,
    val whisper: String?,
    val card: CardData?
)

data class CardData(
    val id: String,
    val cardType: String,  // "decision" or "update"
    val message: String,
    val confirmLabel: String,
    val dismissLabel: String?,
    val timeoutMs: Long
)

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Error(val reason: String) : ConnectionState()
}

interface TransportClient {
    val stateFlow: Flow<GlassStateData>
    val connectionState: Flow<ConnectionState>
    fun connect(url: String, token: String)
    fun disconnect()
    fun sendApprove(cardId: String)
    fun sendDismiss(cardId: String)
}
```

- [ ] **Step 2: Create WebSocketClient implementation**

```kotlin
package com.ccg.glasses.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CCGWebSocketClient : TransportClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var currentUrl: String? = null
    private var currentToken: String? = null
    private var reconnectAttempt = 0
    private var shouldReconnect = false

    private val _stateFlow = MutableSharedFlow<GlassStateData>(replay = 1)
    override val stateFlow: Flow<GlassStateData> = _stateFlow

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: Flow<ConnectionState> = _connectionState

    override fun connect(url: String, token: String) {
        currentUrl = url
        currentToken = token
        shouldReconnect = true
        reconnectAttempt = 0
        doConnect(url, token)
    }

    override fun disconnect() {
        shouldReconnect = false
        ws?.close(1000, "client disconnect")
        ws = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun sendApprove(cardId: String) {
        sendAction("approve", cardId)
    }

    override fun sendDismiss(cardId: String) {
        sendAction("dismiss", cardId)
    }

    private fun sendAction(action: String, cardId: String) {
        val msg = JSONObject().apply {
            put("type", "action")
            put("action", action)
            put("cardId", cardId)
        }
        ws?.send(msg.toString())
    }

    private fun doConnect(url: String, token: String) {
        _connectionState.value = ConnectionState.Connecting
        val request = Request.Builder().url(url).build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Send auth
                val auth = JSONObject().apply {
                    put("type", "auth")
                    put("version", 1)
                    put("token", token)
                    put("role", "renderer")
                }
                webSocket.send(auth.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = JSONObject(text)
                when (msg.optString("type")) {
                    "auth_ok" -> {
                        reconnectAttempt = 0
                        _connectionState.value = ConnectionState.Connected
                    }
                    "auth_fail" -> {
                        shouldReconnect = false
                        _connectionState.value = ConnectionState.Error(msg.optString("reason", "auth failed"))
                        webSocket.close(1000, "auth failed")
                    }
                    "state" -> {
                        val data = msg.getJSONObject("data")
                        val card = if (data.isNull("card")) null else {
                            val c = data.getJSONObject("card")
                            CardData(
                                id = c.getString("id"),
                                cardType = c.getString("cardType"),
                                message = c.getString("message"),
                                confirmLabel = c.getString("confirmLabel"),
                                dismissLabel = c.optString("dismissLabel", null),
                                timeoutMs = c.getLong("timeoutMs")
                            )
                        }
                        val state = GlassStateData(
                            glyph = data.getString("glyph"),
                            whisper = if (data.isNull("whisper")) null else data.getString("whisper"),
                            card = card
                        )
                        scope.launch { _stateFlow.emit(state) }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.Error(t.message ?: "connection failed")
                attemptReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.Disconnected
                attemptReconnect()
            }
        })
    }

    private fun attemptReconnect() {
        if (!shouldReconnect || currentUrl == null || currentToken == null) return

        scope.launch {
            reconnectAttempt++
            val delayMs = minOf(1000L * (1 shl minOf(reconnectAttempt - 1, 4)), 30000L)
            _stateFlow.emit(GlassStateData(glyph = "error", whisper = "reconnecting...", card = null))
            delay(delayMs)
            if (shouldReconnect) {
                doConnect(currentUrl!!, currentToken!!)
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add apps/android/app/src/main/java/com/ccg/glasses/transport/
git commit -m "feat(android): add TransportClient interface and WebSocket implementation with reconnect"
```

---

### Task 10: GlassStateManager

**Files:**
- Create: `apps/android/app/src/main/java/com/ccg/glasses/state/GlassStateManager.kt`

- [ ] **Step 1: Create GlassStateManager**

```kotlin
package com.ccg.glasses.state

import com.ccg.glasses.transport.CardData
import com.ccg.glasses.transport.GlassStateData
import com.ccg.glasses.transport.TransportClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GlassStateManager(
    private val transport: TransportClient,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(GlassStateData(glyph = "idle", whisper = null, card = null))
    val state: StateFlow<GlassStateData> = _state

    private var idleSinceMs: Long = System.currentTimeMillis()

    fun start() {
        scope.launch {
            transport.stateFlow.collect { newState ->
                _state.value = newState
                if (newState.glyph == "idle") {
                    if (idleSinceMs == 0L) idleSinceMs = System.currentTimeMillis()
                } else {
                    idleSinceMs = 0L
                }
            }
        }
    }

    fun isIdle(): Boolean = _state.value.glyph == "idle"

    fun idleDurationMs(): Long {
        if (idleSinceMs == 0L) return 0
        return System.currentTimeMillis() - idleSinceMs
    }

    fun approve() {
        val card = _state.value.card ?: return
        transport.sendApprove(card.id)
    }

    fun dismiss() {
        val card = _state.value.card ?: return
        transport.sendDismiss(card.id)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add apps/android/app/src/main/java/com/ccg/glasses/state/
git commit -m "feat(android): add GlassStateManager with idle tracking"
```

---

## Chunk 5: Android HUD — Custom Views

### Task 11: GlyphView

**Files:**
- Create: `apps/android/app/src/main/java/com/ccg/glasses/ui/GlyphView.kt`

- [ ] **Step 1: Create GlyphView**

```kotlin
package com.ccg.glasses.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.ccg.glasses.R

class GlyphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class GlyphConfig(
        val char: String,
        val colorRes: Int,
        val pulseMs: Long  // 0 = no pulse
    )

    private val configs = mapOf(
        "idle" to GlyphConfig("\u25BD", R.color.glyph_cyan_dim, 0),       // ▽
        "thinking" to GlyphConfig("\u25BC", R.color.glyph_cyan, 2000),    // ▼
        "running" to GlyphConfig("\u25B2", R.color.glyph_green, 1000),    // ▲
        "awaiting" to GlyphConfig("\u25B3", R.color.glyph_purple, 0),     // △
        "done" to GlyphConfig("\u25BD", R.color.glyph_green, 0),          // ▽
        "error" to GlyphConfig("\u2715", R.color.glyph_red, 0),           // ✕
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f * resources.displayMetrics.density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private var currentState = "idle"
    private var pulseAnimator: ValueAnimator? = null
    private var currentAlpha = 1f

    fun setState(glyphState: String) {
        if (currentState == glyphState) return
        currentState = glyphState
        pulseAnimator?.cancel()

        val config = configs[glyphState] ?: configs["idle"]!!
        paint.color = context.getColor(config.colorRes)

        if (config.pulseMs > 0) {
            pulseAnimator = ValueAnimator.ofFloat(0.4f, 1.0f).apply {
                duration = config.pulseMs
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    currentAlpha = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            currentAlpha = if (glyphState == "idle") 0.4f else 1f
            invalidate()
        }

        // Done state: fade out over 2s
        if (glyphState == "done") {
            pulseAnimator?.cancel()
            pulseAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 2000
                addUpdateListener {
                    currentAlpha = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val config = configs[currentState] ?: configs["idle"]!!
        paint.alpha = (currentAlpha * 255).toInt()
        val x = width / 2f
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(config.char, x, y, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add apps/android/app/src/main/java/com/ccg/glasses/ui/GlyphView.kt
git commit -m "feat(android): add GlyphView with state-driven color and pulse animation"
```

---

### Task 12: WhisperView

**Files:**
- Create: `apps/android/app/src/main/java/com/ccg/glasses/ui/WhisperView.kt`

- [ ] **Step 1: Create WhisperView**

```kotlin
package com.ccg.glasses.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import com.ccg.glasses.R

class WhisperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var fadeAnimator: ObjectAnimator? = null

    init {
        setTextColor(context.getColor(R.color.white))
        textSize = 20f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        maxLines = 1
        alpha = 0f  // hidden by default
    }

    fun showWhisper(whisperText: String?, ttlMs: Long = 4000) {
        fadeAnimator?.cancel()

        if (whisperText == null) {
            alpha = 0f
            text = ""
            return
        }

        // Enforce 48 char limit
        val truncated = if (whisperText.length > 48) whisperText.take(47) + "\u2026" else whisperText
        text = truncated
        alpha = 1f

        // Auto-fade after TTL
        fadeAnimator = ObjectAnimator.ofFloat(this, "alpha", 1f, 0f).apply {
            startDelay = ttlMs
            duration = 500
            start()
        }
    }

    fun hide() {
        fadeAnimator?.cancel()
        alpha = 0f
        text = ""
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fadeAnimator?.cancel()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add apps/android/app/src/main/java/com/ccg/glasses/ui/WhisperView.kt
git commit -m "feat(android): add WhisperView with auto-fade and 48-char limit"
```

---

### Task 13: CardView

**Files:**
- Create: `apps/android/app/src/main/java/com/ccg/glasses/ui/CardView.kt`

- [ ] **Step 1: Create CardView**

```kotlin
package com.ccg.glasses.ui

import android.content.Context
import android.graphics.Typeface
import android.os.CountDownTimer
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.ccg.glasses.R
import com.ccg.glasses.transport.CardData

class CardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val messageView: TextView
    private val buttonContainer: LinearLayout
    private val btnApprove: TextView
    private val btnReject: TextView

    var onApprove: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private var focusedIndex = 0
    private var currentCard: CardData? = null
    private var autoDismissTimer: CountDownTimer? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = context.getDrawable(R.drawable.card_border)
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        visibility = GONE

        messageView = TextView(context).apply {
            setTextColor(context.getColor(R.color.white))
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        addView(messageView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (16 * resources.displayMetrics.density).toInt()
        })

        buttonContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }

        btnApprove = createButton()
        btnReject = createButton()

        val buttonMargin = (24 * resources.displayMetrics.density).toInt()
        buttonContainer.addView(btnApprove, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = buttonMargin
        })
        buttonContainer.addView(btnReject, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        addView(buttonContainer)
    }

    private fun createButton(): TextView {
        return TextView(context).apply {
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
    }

    fun showCard(card: CardData) {
        currentCard = card
        messageView.text = card.message
        focusedIndex = 0

        if (card.cardType == "decision") {
            btnApprove.visibility = VISIBLE
            btnReject.visibility = VISIBLE
            updateButtonText(btnApprove, "\u2713 ${card.confirmLabel}", true)
            updateButtonText(btnReject, "\u2715 ${card.dismissLabel ?: "Reject"}", false)
        } else {
            // Update card — single button
            btnApprove.visibility = VISIBLE
            btnReject.visibility = GONE
            updateButtonText(btnApprove, card.confirmLabel, true)

            // Auto-dismiss
            if (card.timeoutMs > 0) {
                autoDismissTimer?.cancel()
                autoDismissTimer = object : CountDownTimer(card.timeoutMs, card.timeoutMs) {
                    override fun onTick(millisUntilFinished: Long) {}
                    override fun onFinish() { onDismiss?.invoke() }
                }.start()
            }
        }

        visibility = VISIBLE
    }

    fun hideCard() {
        autoDismissTimer?.cancel()
        autoDismissTimer = null
        currentCard = null
        visibility = GONE
    }

    fun focusNext() {
        if (currentCard?.cardType != "decision") return
        focusedIndex = 1
        updateButtonText(btnApprove, "\u2713 ${currentCard!!.confirmLabel}", false)
        updateButtonText(btnReject, "\u2715 ${currentCard!!.dismissLabel ?: "Reject"}", true)
    }

    fun focusPrev() {
        if (currentCard?.cardType != "decision") return
        focusedIndex = 0
        updateButtonText(btnApprove, "\u2713 ${currentCard!!.confirmLabel}", true)
        updateButtonText(btnReject, "\u2715 ${currentCard!!.dismissLabel ?: "Reject"}", false)
    }

    fun confirmFocused() {
        if (focusedIndex == 0) onApprove?.invoke()
        else onDismiss?.invoke()
    }

    private fun updateButtonText(btn: TextView, label: String, focused: Boolean) {
        val bracketColor = if (focused) context.getColor(R.color.button_focus_bracket)
                           else context.getColor(R.color.button_dim_bracket)
        val textColor = if (focused) context.getColor(R.color.white)
                        else context.getColor(R.color.white_dim)

        val ssb = SpannableStringBuilder()
        ssb.append("[", ForegroundColorSpan(bracketColor), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.append(label, ForegroundColorSpan(textColor), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.append("]", ForegroundColorSpan(bracketColor), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        btn.text = ssb
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        autoDismissTimer?.cancel()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add apps/android/app/src/main/java/com/ccg/glasses/ui/CardView.kt
git commit -m "feat(android): add CardView with decision/update modes and focus management"
```

---

## Chunk 6: Android HUD Activity + Connection

### Task 14: HUD layout XML

**Files:**
- Create: `apps/android/app/src/main/res/layout/activity_hud.xml`

- [ ] **Step 1: Create activity_hud.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="640dp"
    android:layout_height="480dp"
    android:background="@color/black"
    android:padding="24dp">

    <!-- Glyph — bottom-right -->
    <com.ccg.glasses.ui.GlyphView
        android:id="@+id/glyphView"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:layout_gravity="bottom|end" />

    <!-- Whisper — bottom-left -->
    <com.ccg.glasses.ui.WhisperView
        android:id="@+id/whisperView"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|start"
        android:layout_marginEnd="64dp" />

    <!-- Card — centered -->
    <com.ccg.glasses.ui.CardView
        android:id="@+id/cardView"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center" />

</FrameLayout>
```

- [ ] **Step 2: Commit**

```bash
git add apps/android/app/src/main/res/layout/activity_hud.xml
git commit -m "feat(android): add HUD layout with glyph, whisper, and card views"
```

---

### Task 15: HudActivity

**Files:**
- Create: `apps/android/app/src/main/java/com/ccg/glasses/HudActivity.kt`

- [ ] **Step 1: Create HudActivity**

```kotlin
package com.ccg.glasses

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ccg.glasses.databinding.ActivityHudBinding
import com.ccg.glasses.state.GlassStateManager
import com.ccg.glasses.transport.CCGWebSocketClient
import com.ccg.glasses.transport.ConnectionState
import com.ccg.glasses.voice.VoiceCommandManager
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.focus.FocusHolder
import com.ffalcon.mercury.android.sdk.focus.FocusInfo
import com.ffalcon.mercury.android.sdk.focus.FixPosFocusTracker
import com.ffalcon.mercury.android.sdk.event.TempleAction
import kotlinx.coroutines.launch

class HudActivity : BaseMirrorActivity<ActivityHudBinding>() {

    private lateinit var transport: CCGWebSocketClient
    private lateinit var stateManager: GlassStateManager
    private lateinit var voiceManager: VoiceCommandManager

    private var focusTracker: FixPosFocusTracker? = null
    private var cardVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("url") ?: return finish()
        val token = intent.getStringExtra("token") ?: return finish()

        transport = CCGWebSocketClient()
        stateManager = GlassStateManager(transport, lifecycleScope)
        voiceManager = VoiceCommandManager(this)

        // Connect
        transport.connect(url, token)
        stateManager.start()

        // Observe state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                stateManager.state.collect { state ->
                    mBindingPair.updateView {
                        glyphView.setState(state.glyph)

                        if (state.card != null) {
                            whisperView.hide()
                            cardView.showCard(state.card)
                            if (!cardVisible) {
                                cardVisible = true
                                setupCardFocus()
                                if (state.card.cardType == "decision") {
                                    voiceManager.start { command ->
                                        when (command) {
                                            "approve", "yes" -> { stateManager.approve(); clearCard() }
                                            "reject", "no", "skip" -> { stateManager.dismiss(); clearCard() }
                                            "status" -> {
                                                mBindingPair.updateView {
                                                    whisperView.showWhisper("status: ${state.glyph}", 3000)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            if (cardVisible) clearCard()
                            whisperView.showWhisper(state.whisper)
                        }
                    }
                }
            }
        }

        // Observe connection state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                transport.connectionState.collect { connState ->
                    when (connState) {
                        is ConnectionState.Error -> {
                            mBindingPair.updateView {
                                glyphView.setState("error")
                                whisperView.showWhisper(connState.reason, 8000)
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

        // Temple input
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    if (cardVisible) {
                        when (action) {
                            is TempleAction.Click -> {
                                mBindingPair.updateView { cardView.confirmFocused() }
                            }
                            is TempleAction.SlideForward -> {
                                mBindingPair.updateView { cardView.focusNext() }
                            }
                            is TempleAction.SlideBackward -> {
                                mBindingPair.updateView { cardView.focusPrev() }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    private fun setupCardFocus() {
        // Focus management is handled by CardView internally
        // The temple actions are routed directly to CardView methods
    }

    private fun clearCard() {
        cardVisible = false
        voiceManager.stop()
        focusTracker = null
        mBindingPair.updateView { cardView.hideCard() }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.stop()
        transport.disconnect()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add apps/android/app/src/main/java/com/ccg/glasses/HudActivity.kt
git commit -m "feat(android): add HudActivity with state observation, temple input, and voice wiring"
```

---

### Task 16: VoiceCommandManager

**Files:**
- Create: `apps/android/app/src/main/java/com/ccg/glasses/voice/VoiceCommandManager.kt`

- [ ] **Step 1: Create VoiceCommandManager**

```kotlin
package com.ccg.glasses.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VoiceCommandManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var audioManager: AudioManager? = null
    private var onCommand: ((String) -> Unit)? = null
    private var isListening = false

    private val keywords = setOf("approve", "yes", "reject", "no", "skip", "status")

    fun start(callback: (String) -> Unit) {
        if (isListening) return
        isListening = true
        onCommand = callback

        // Set voiceassistant audio mode
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.setParameters("audio_source_record=voiceassistant")

        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                processResults(results)
                // Restart listening for continuous recognition
                if (isListening) startListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                processResults(partialResults)
            }

            override fun onError(error: Int) {
                // Restart on error if still active
                if (isListening && error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    startListening()
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startListening()
    }

    fun stop() {
        isListening = false
        onCommand = null
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        audioManager?.setParameters("audio_source_record=off")
        audioManager = null
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer?.startListening(intent)
    }

    private fun processResults(bundle: Bundle?) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_KEY) ?: return
        for (match in matches) {
            val word = match.trim().lowercase()
            if (word in keywords) {
                onCommand?.invoke(word)
                return
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add apps/android/app/src/main/java/com/ccg/glasses/voice/
git commit -m "feat(android): add VoiceCommandManager with contextual SpeechRecognizer"
```

---

### Task 17: MainActivity — QR Scanner

**Files:**
- Create: `apps/android/app/src/main/res/layout/activity_main.xml`
- Create: `apps/android/app/src/main/java/com/ccg/glasses/MainActivity.kt`

- [ ] **Step 1: Create activity_main.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="640dp"
    android:layout_height="480dp"
    android:background="@color/black"
    android:padding="24dp">

    <!-- QR scanner viewfinder -->
    <com.journeyapps.barcodescanner.DecoratedBarcodeView
        android:id="@+id/barcodeScanner"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:layout_gravity="center" />

    <!-- Status text -->
    <TextView
        android:id="@+id/statusText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|center_horizontal"
        android:text="Scan QR to connect"
        android:textColor="@color/white"
        android:textSize="20sp"
        android:fontFamily="sans-serif-medium" />

</FrameLayout>
```

- [ ] **Step 2: Create MainActivity.kt**

```kotlin
package com.ccg.glasses

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ccg.glasses.databinding.ActivityMainBinding
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("ccg", MODE_PRIVATE)

        // Check for previous session
        val lastUrl = prefs.getString("last_url", null)
        val lastToken = prefs.getString("last_token", null)
        if (lastUrl != null && lastToken != null) {
            binding.statusText.text = "Tap to reconnect, or scan new QR"
            // Single tap reconnects via temple action
        }

        // Start QR scanner
        binding.barcodeScanner.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                val text = result?.text ?: return
                handleScan(text)
            }
        })
    }

    private fun handleScan(rawText: String) {
        try {
            val uri = Uri.parse(rawText)
            if (uri.scheme != "ccg" || uri.host != "connect") {
                binding.statusText.text = "invalid QR code"
                return
            }

            val url = uri.getQueryParameter("url")
            val token = uri.getQueryParameter("token")

            if (url == null || token == null) {
                binding.statusText.text = "invalid QR code"
                return
            }

            // Save for reconnection
            prefs.edit()
                .putString("last_url", url)
                .putString("last_token", token)
                .apply()

            // Launch HUD
            binding.barcodeScanner.pause()
            startActivity(Intent(this, HudActivity::class.java).apply {
                putExtra("url", url)
                putExtra("token", token)
            })
        } catch (e: Exception) {
            binding.statusText.text = "invalid QR code"
        }
    }

    override fun onResume() {
        super.onResume()
        binding.barcodeScanner.resume()
    }

    override fun onPause() {
        super.onPause()
        binding.barcodeScanner.pause()
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add apps/android/app/src/main/java/com/ccg/glasses/MainActivity.kt apps/android/app/src/main/res/layout/activity_main.xml
git commit -m "feat(android): add MainActivity with QR scanner and session persistence"
```

---

### Task 18: Android README

**Files:**
- Create: `apps/android/README.md`

- [ ] **Step 1: Create README**

```markdown
# CCG Glasses — Android Renderer for RayNeo X2

Renders Claude Code agent state on RayNeo X2 AR glasses.

## Prerequisites

1. Android Studio Arctic Fox or later
2. RayNeo MercurySDK `.aar` — place in `app/libs/mercury-sdk.aar`
   (Available from the RayNeo developer portal: https://open.rayneo.com/)
3. RayNeo X2 glasses with firmware 1.2.66+

## Build

```bash
cd apps/android
./gradlew assembleDebug
```

## Install on RayNeo X2

```bash
# Enable sideloading (firmware 1.2.66+)
adb shell settings put global mercury_install_allowed 1

# Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Connect

1. On your laptop: `claude --output-format stream-json | ccg start --relay`
2. A QR code appears in the terminal
3. On the glasses: open CCG app, scan the QR code
4. Done — glyph, whispers, and cards now appear on your glasses

## Development

Use `scrcpy` to mirror the glasses display on your computer:

```bash
scrcpy --display-id=0
```
```

- [ ] **Step 2: Commit**

```bash
git add apps/android/README.md
git commit -m "docs(android): add README with build, install, and connection instructions"
```

---

## Chunk 7: Integration Test & Final Verification

### Task 19: End-to-end smoke test

- [ ] **Step 1: Run relay tests**

Run: `cd /Users/powder/Documents/claude-code-glasses/apps/relay && npx vitest run`
Expected: All tests pass

- [ ] **Step 2: Test CLI with relay flag**

Run: `cd /Users/powder/Documents/claude-code-glasses && echo '{"type":"system","subtype":"init","session_id":"test"}' | pnpm ccg -- --relay`
Expected: Terminal shows relay URL, QR code, and token. Glyph transitions to thinking.

- [ ] **Step 3: Test simulator still works**

Run: `pnpm simulate`
Expected: Session replays correctly, including the new permission_request card event

- [ ] **Step 4: Verify Android project compiles** (requires MercurySDK .aar)

Run: `cd /Users/powder/Documents/claude-code-glasses/apps/android && ./gradlew assembleDebug`
Expected: Build succeeds (or fails only due to missing mercury-sdk.aar, which is expected without the device SDK)

- [ ] **Step 5: Run acceptance checklist from spec**

Verify against `docs/superpowers/specs/2026-03-23-android-renderer-design.md` Section 11:

- [ ] Background is pure black (#000000) — verify in colors.xml and activity_hud.xml
- [ ] Min font size > 16px — verify 20px in WhisperView, 24px in CardView
- [ ] Line width >= 2px — verify in card_border.xml
- [ ] No double-tap handler in HudActivity — verify only Click, SlideForward, SlideBackward handled
- [ ] QR scan parses ccg:// URI — verify in MainActivity.handleScan
- [ ] Reconnection logic in WebSocketClient — verify exponential backoff in attemptReconnect
- [ ] Voice keywords match spec — verify in VoiceCommandManager.keywords

- [ ] **Step 6: Final commit**

```bash
git add -A
git commit -m "chore: verify end-to-end integration and acceptance checklist"
```
