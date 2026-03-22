# claude-code-glasses

> Pipe Claude Code's brain to your face. Open source.

Claude is the first "piece of software" that has radically changed my process and expanded my perspective in four decades of using a computer. More than Final cut, Arduino or Unity did before it, Claude Code has empowered me to make all the things I image and damatically shortened the time from brain-to-prod. Sometimes on my way to work on the BART or when im stretching before Pilates, or just coming out of sedation from a colonoscopy, I cant stop thinking about our work together. I want anytime-anywhere access to be able to approve a build plan, brainstorm a design approach or just check in on the status of ongoing work. This software project is my attempt to connect stream-of-Claude to stream-of-Powderly consciousnesses on AR display glasses with the most minimal yet accessibe amount of i/o to enable me to monitor, approve actions and provide feedback to my fave agent on-the-go and at-the-ready without needing to touch my laptop.

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
| **Glyph** | Ambient dot — idle / thinking / running / awaiting / done / error |
| **Whisper** | Single line, bottom of FOV — current task, 48 chars max, auto-fades |
| **Card** | HITL approval surface — appears when Claude Code needs a decision |

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

## Repo Structure

```
claude-code-glasses/
├── packages/
│   ├── core/              # Shared types, formatters, constants
│   ├── parser/            # Claude Code stdout → GlassState machine
│   └── renderer-terminal/ # Terminal simulator for development
├── apps/
│   └── cli/               # ccg CLI
└── examples/
    └── basic/             # session.jsonl test fixture
```

## Roadmap

- **v0.1** — RayNeo X2 core (parser + terminal renderer + Android APK)
- **v0.2** — Even Realities G2, WiFi transport, ring support, peek layer
- **v0.3** — XREAL One, Android XR, plugin API

## Contributing

Come build this with us:

- **r/claudeCodeGlasses** — community, demos, build logs
- Android / RayNeo SDK dev needed for the renderer APK
- Even Realities G2 renderer (v0.2)
- Real-world Claude Code session testing for parser edge cases

Open an issue or drop into the subreddit.

## Why

Claude Code is the first AI agent that genuinely changes how I work. But it's screen-bound. The moment you look away — to think, to walk, to talk to someone — you lose the thread.

This answers one question: **what's the minimum viable display surface to keep a human meaningfully in the loop with an AI agent?**

A dot. A line of text. A card. That's it.

---

MIT License · Not affiliated with Anthropic or RayNeo  
Built at the Amazon AGI SF Lab · Author: James Powderly
