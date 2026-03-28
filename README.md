# Claude Elements
aka claude-code-glasses

> Pipe Claude Code's brain to your face. Open source.

Claude is the first "piece of software" that has radically changed how I work and expanded my perspective in over a decade. Like Final Cut, Arduino and Unity did before it, Claude Code has really empowered me to make all the things I can imagine and dramatically shortened the time it takes me to go from brain to prod. But, sometimes when on my way to work on the BART or stretching it out before Pilates, or just waking up from twilight sedation after a colonoscopy, I can't stop thinking about our work together. I want anytime-anywhere access to Claude to be able to approve a build plan, brainstorm a design approach or just check in on the status of ongoing work. This software project is my attempt to connect stream-of-Claude to stream-of-Powderly consciousness using AR display glasses with the most minimal yet accessible amount of I/O to enable me to monitor, approve and provide feedback on the actions of my fave agent when I'm on-the-go but at-the-ready without needing to touch the dirty keys on my laptop.

** Current primary hardware target: RayNeo X2** (Android, sideload via ADB)  
**Also planned:** Even Realities G2, XREAL One, anything except Meta ray-banned. 

---

## Quick Start

```bash
npm install -g claude-code-glasses

# Pipe any Claude Code session into ccg (terminal mode — no glasses needed)
claude --output-format stream-json | ccg start --target terminal

# Test with a recorded session
ccg simulate --file ./examples/basic/session.jsonl
```

## What You'll See

Three minimal display primitives, nothing more:

| Primitive | What it is |
|---|---|
| **Glyph** | Ambient ascii symbols inspired by the elements — idle (earth) / thinking (water) / running (fire) / waiting (air) / done (earth, again) / error |
| **Whisper** | Single line, bottom of the FOV — current task, <50 chars max, auto-fades |
| ** Decision Card** | HITL approval surface — appears when Claude Code needs a decision | 
| ** Update Card** | Status update surface — appears when Claude Code completes a phase or a build |

### UI Components

Glyph

glyph  state      element  meaning                            agent     card
─────────────────────────────────────────────────────────────────────────────
▽      idle       earth    Waiting. Nothing running.          paused    —
▼      thinking   water    Reasoning. No tool called yet.     running   —
▲      running    fire     Tool executing. Side effects.      running   update
△      awaiting   air      Needs your decision.               paused    decision
▽      done       earth    Task/phase complete. Fades in 2s.  complete  update
✕      error      X        Something failed. Stays.           paused    —

Decision Card
┌─────────────────────────────────────┐
│  Delete legacy token handler?        │
│                                      │
│  [✓ Approve]    [✕ Reject]           │
└─────────────────────────────────────┘

Update Card
┌─────────────────────────────────────┐
│  ✓ Phase 1 complete                  │
│  Auth middleware refactored          │
│                                      │
│  [Got it]                            │
└─────────────────────────────────────┘


## Hardware Setup (RayNeo X2)

```bash
# Enable ADB on your RayNeo X2 (Settings → Developer Options → USB Debugging)
adb devices                          # confirm glasses show up

# Build and sideload the Android renderer
pnpm build:android
adb install apps/android/build/outputs/apk/debug/ccg.apk

# Run against glasses
claude --output-format stream-json | ccg start --target rayneo
```

Full setup guide: [docs/rayneo-x2-setup.md](docs/rayneo-x2-setup.md)

## Interaction Model (v0.1)

| Input | Action |
|---|---|
| Single temple tap | Approve / confirm |
| Double temple tap | Dismiss / skip |
| Voice: "approve" | Confirm card |
| Voice: "skip" | Dismiss card |

## Cloud Relay (Fly.io)

The glasses need to talk to your laptop even when you're walking around on a different network. A tiny WebSocket relay bridges them — your laptop publishes agent state, your glasses subscribe. The relay is stateless, handles auth via session tokens, and costs nothing on Fly.io's free tier.

**Why Fly.io?** The relay is ~100 lines of code pushing ~200 bytes per event. It needs WebSocket support, global edge deployment, and the ability to sleep when idle. Fly.io's free tier gives us 3 shared VMs with auto-stop/auto-start — the relay wakes on first connection and sleeps when you disconnect. Zero cost for casual use.

### Deploy your own relay

```bash
# Install Fly CLI
brew install flyctl
fly auth login

# Deploy (from apps/relay/)
cd apps/relay
fly launch --name your-relay-name --region sjc
fly deploy
```

That's it. Your relay is live at `wss://your-relay-name.fly.dev`. The CLI will print a QR code encoding this URL + a session token when you run `ccg start --relay`.

### How it works

```
Laptop (parser) ──websocket──> Relay (Fly.io) <──websocket── Glasses (renderer)
                               stateless forwarder
                               auth via session token
```

- Laptop connects as `publisher`, glasses connect as `renderer`
- One publisher per token, multiple renderers allowed (pair programming, demos)
- Relay stores last known state — late-joining glasses get caught up immediately
- Auto-stops after idle, auto-starts on next connection

## Repo Structure

```
claude-code-glasses/
├── packages/
│   ├── core/              # Shared types, formatters, constants
│   ├── parser/            # Claude Code stdout → GlassState machine
│   └── renderer-terminal/ # Terminal simulator for development
├── apps/
│   ├── cli/               # ccg CLI (start, simulate, status)
│   ├── relay/             # WebSocket relay server (Fly.io)
│   └── android/           # RayNeo X2 AR renderer (Kotlin)
└── examples/
    └── basic/             # session.jsonl test fixture
```

## Roadmap

- **v0.1** — RayNeo X2 core (parser + terminal renderer + Android APK)
- **v0.2** — Even Realities G2, WiFi transport, ring support, peek layer
- **v0.3** — XREAL One, Android XR, plugin API

## Contributing

Come build this with us:

- **r/ClaudeElements** — community, demos, build logs, WIP
- Even Realities G2 API/SDK support needed (v0.2)
- Real-world Claude Code session testing for parser edge cases

Open an issue or drop into the subreddit.

## Why

Claude Code is the ghost in the machine with the most. But it's trapped in a screen like Zod at the end of Superman 2. 
So this project is about enabling me to click approve, approve, approve when Im walking down Bush st to get a coffee at Blue Bottle. 
If there is a kernal of a research question in this projects it is this: **what's the minimum viable UI surface needed to keep a human meaningfully in the loop with an AI agent?**

Dare to dream. Ascii is the limit friends. 

---

MIT License · Not affiliated with Anthropic or RayNeo or Amazon AGI SF Lab (where I just happen to work). 
Built in San Francisco duh · Author: James Powderly
