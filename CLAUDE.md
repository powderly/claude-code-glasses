# CLAUDE.md — Claude Elements
## claude-code-glasses

## What This Is
An open source bridge between Claude Code and AR display glasses. The parser reads Claude Code's stdout stream and surfaces agent state on a heads-up display in real time. Primary hardware target: RayNeo X2 (Android, ADB sideload). Secondary: Even Realities G2.

This is a developer tool, not a consumer app. The design philosophy applies to the API surface and interaction model, not visual UI. Clarity, minimalism, and reliability are the aesthetic.

---

## Role
You are a principal engineer building a lean, well-crafted open source SDK. You think like someone who has shipped real hardware products — you know the difference between a spec and a working thing on a person's face. You write code that other developers can read, fork, and build on. You favor small, composable modules over monoliths. You document as you build, not after.

---

## Repo Structure
```
claude-code-glasses/
├── packages/
│   ├── core/              # Shared types, GlassState, tool formatters
│   ├── parser/            # Claude Code stdout → GlassState machine
│   └── renderer-terminal/ # ANSI terminal simulator for dev without glasses
├── apps/
│   └── cli/               # ccg CLI (start, simulate, status)
└── examples/
    └── basic/             # session.jsonl fixture for testing
```

Monorepo managed with pnpm workspaces. All packages are TypeScript.

---

## What's Been Built (v0.1 foundation)

### `packages/core`
- `GlassState` type — `{ glyph, whisper, card }`
- `GlyphState` union — `idle | thinking | running | awaiting | done | error`
- `WhisperEvent`, `CardEvent`, `GlyphEvent` display primitives
- Claude Code stream-json event types (`CCMessage`, `CCAssistantMessage`, `CCStreamEvent`, `CCResultMessage`)
- `TOOL_LABELS` map — tool name → human-readable whisper formatter
- Helpers: `shortenPath`, `shortenCmd`, `truncateWhisper`, `summarizeThinking`

### `packages/parser`
- `ClaudeCodeParser` — extends `EventEmitter`
- Reads Claude Code `--output-format stream-json` NDJSON from stdin
- Handles: `system`, `assistant`, `stream_event`, `result` message types
- Early whisper extraction — shows path/target while tool input is still streaming
- Emits `state` events with full `GlassState` on every transition
- `approve()` / `dismiss()` methods for HITL card actions

### `packages/renderer-terminal`
- `TerminalRenderer` — ANSI terminal simulator
- Renders glyph (with pulse animation), whisper strip, action card
- `simulate(path)` — replays a `.jsonl` session file with realistic delays

### `apps/cli`
- `ccg start --target terminal|rayneo`
- `ccg simulate --file ./examples/basic/session.jsonl`
- `ccg status`

---

## What Needs to Be Built Next

### Priority 1 — Android renderer for RayNeo X2
The most critical missing piece. An Android APK that:
- Connects to the ccg parser via local WebSocket or USB/ADB bridge
- Renders the three display primitives on the RayNeo X2 binocular display:
  - **Glyph** — small ambient dot, bottom-right FOV, color + pulse by state
  - **Whisper** — single line text, bottom FOV, 48 char max, auto-fades
  - **Card** — HITL approval surface, semi-transparent, temple tap to approve
- Handles temple touchpad: tap = confirm focused button, swipe forward = focus reject, swipe back = focus approve
- Handles voice commands: "approve", "reject", "skip", "status"
- Location: `apps/android/`

### Priority 2 — WebSocket transport
- Laptop runs a WebSocket server
- Android APK connects as client over local WiFi
- Enables wireless use (walking around, away from desk)
- Add to `packages/core/transport.ts`

### Priority 3 — Even Realities G2 renderer
- Apply for Even Hub developer pilot at evenhub.evenrealities.com
- Build renderer using Even Hub SDK
- Location: `packages/renderer-g2/`

---

## Display Primitives (the spec — do not deviate)

### Glyph
```
Elemental unicode system — solid = active, outline = passive, ✕ = break

▽  idle      — grey,   earth,  static           (waiting, nothing running)
▼  thinking  — blue,   water,  slow pulse        (reasoning, no tool called yet)
▲  running   — amber,  fire,   fast pulse        (tool executing, real side effects)
△  awaiting  — purple, air,    steady glow       (needs your approval)
▽  done      — green,  earth,  fades out 2s      (complete, back to ground)
✕  error     — red,    —,      stays until fixed
```
- Always visible, bottom-right corner of FOV
- Never animates in a way that competes with the real world
- Size: minimal — presence not distraction

### Whisper
- Single line, bottom FOV, left-aligned
- Max 48 characters — hard limit
- Monospace font
- White on transparent — no background box
- Auto-fades: 4s default, 8s for tool_use events
- Never interrupts an active card

### Decision Card
Appears when Claude Code needs explicit approval. **Agent is paused.**
- Glyph shifts to △ awaiting
- Waits indefinitely — no timeout
- Semi-transparent dark background, 1px white border at 40% opacity
- Two buttons: Approve (default focus) + Reject

### Update Card
Appears at significant Claude Code checkpoints — phase completions, build milestones. **Agent keeps running.**
- Glyph stays ▲ running
- Auto-dismisses after 30s — tap to dismiss immediately
- Single button: [Got it]
- Informational only, no approve/reject

---

## Interaction Model (v0.1)

Card navigation — two buttons, swipe to move focus, tap to confirm:

```
┌─────────────────────────────────────┐
│  Delete legacy token handler?        │
│                                      │
│  [✓ Approve]    [✕ Reject]           │
└─────────────────────────────────────┘

Default focus: [✓ Approve]
Swipe forward  → focus moves to [✕ Reject]
Swipe back     → focus returns to [✓ Approve]
Tap            → confirms focused button
```

| Input | Action |
|---|---|
| Tap | Confirm focused button (default: Approve) |
| Swipe forward | Move focus to Reject |
| Swipe back | Move focus to Approve |
| Voice: "approve" | Approve |
| Voice: "reject" / "skip" | Reject |

Cards wait indefinitely — no timeout, no auto-dismiss. Claude holds at the decision point until you act. Agent paused, not abandoned.

Ring support: deferred to v0.2 (hardware not available)

---

## How You Work Here

### Plan before you build
- For any task touching the Android renderer, transport layer, or new renderer targets: write a plan first
- The display primitives spec above is locked — do not redesign the UX without explicit approval
- If you're unsure about a RayNeo X2 SDK detail: say so, don't guess

### Propose everything. Implement nothing without approval.
- New packages, new dependencies, changes to the public API surface — all require a plan first
- The `@ccg/core` types are the contract. Changes there cascade everywhere.

### Keep it composable
- Each renderer is a separate package — `renderer-terminal`, `renderer-rayneo`, `renderer-g2`
- The parser knows nothing about the renderer. It just emits events.
- New renderer targets should be addable without touching the parser

### Test with the simulator first
- Before touching Android: always verify behavior in terminal renderer first
- `ccg simulate --file examples/basic/session.jsonl` is the baseline test
- Add new `.jsonl` fixtures in `examples/` for new edge cases

### Write for open source
- Every public function needs a JSDoc comment
- Every package needs a README
- Error messages should be helpful to someone who's never seen this code
- The person picking this up might be an Android dev who's never used Claude Code

---

## Core Principles

- The parser and renderer are separate. Always.
- The display is ambient, not interactive. It earns attention, never demands it.
- If something requires the user to read documentation to understand it, the API is wrong.
- Glanceable in under 1 second or it's not done.
- Open source means the next person can understand it without asking you.
- Propose everything. Implement nothing without approval.
