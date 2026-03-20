import { GlassState, GlyphState } from '@ccg/core'
import { ClaudeCodeParser } from '@ccg/parser'

// ─── ANSI helpers ─────────────────────────────────────────────────────────────

const ESC = '\x1b['
const c = {
  reset:   `${ESC}0m`,
  bold:    `${ESC}1m`,
  dim:     `${ESC}2m`,
  // Foreground colors
  white:   `${ESC}97m`,
  blue:    `${ESC}94m`,
  yellow:  `${ESC}93m`,
  purple:  `${ESC}95m`,
  green:   `${ESC}92m`,
  red:     `${ESC}91m`,
  gray:    `${ESC}90m`,
  // Background
  bgBlack: `${ESC}40m`,
}

const clearLine  = `${ESC}2K\r`
const cursorUp   = `${ESC}1A`
const hideCursor = `${ESC}?25l`
const showCursor = `${ESC}?25h`

// ─── Glyph config ─────────────────────────────────────────────────────────────

const GLYPH_CONFIG: Record<GlyphState, { char: string; color: string; label: string }> = {
  idle:     { char: '○', color: c.gray,   label: 'idle'     },
  thinking: { char: '◉', color: c.blue,   label: 'thinking' },
  running:  { char: '●', color: c.yellow, label: 'running'  },
  awaiting: { char: '◎', color: c.purple, label: 'awaiting' },
  done:     { char: '✓', color: c.green,  label: 'done'     },
  error:    { char: '✕', color: c.red,    label: 'error'    },
}

// ─── Terminal Renderer ────────────────────────────────────────────────────────

export class TerminalRenderer {
  private parser: ClaudeCodeParser
  private currentState: GlassState = { glyph: 'idle', whisper: null, card: null }
  private lineCount = 0
  private frameInterval: ReturnType<typeof setInterval> | null = null
  private frameTick = 0

  constructor(parser: ClaudeCodeParser) {
    this.parser = parser
    this.parser.on('state', (state: GlassState) => {
      this.currentState = state
      this.render()
    })
  }

  start(): void {
    // Hide cursor for clean rendering
    process.stdout.write(hideCursor)

    // Draw header once
    this.drawHeader()

    // Animate glyph for pulsing states
    this.frameInterval = setInterval(() => {
      this.frameTick++
      if (['thinking', 'running', 'awaiting'].includes(this.currentState.glyph)) {
        this.render()
      }
    }, 500)

    // Restore on exit
    process.on('exit', () => {
      process.stdout.write(showCursor + '\n')
    })
    process.on('SIGINT', () => {
      process.stdout.write(showCursor + '\n')
      process.exit(0)
    })
  }

  stop(): void {
    if (this.frameInterval) clearInterval(this.frameInterval)
    process.stdout.write(showCursor + '\n')
  }

  // ─── Render ────────────────────────────────────────────────────────────────

  private render(): void {
    // Clear previously drawn lines
    for (let i = 0; i < this.lineCount; i++) {
      process.stdout.write(clearLine + (i < this.lineCount - 1 ? cursorUp : ''))
    }

    const lines: string[] = []
    const { glyph, whisper, card } = this.currentState
    const glyphCfg = GLYPH_CONFIG[glyph]

    // Simulate glyph pulse with alternating brightness
    const pulsing = ['thinking', 'running', 'awaiting'].includes(glyph)
    const glyphChar = pulsing && this.frameTick % 2 === 0
      ? glyphCfg.char
      : (pulsing ? '·' : glyphCfg.char)

    // ── Glyph row ──
    const glyphStr = `${glyphCfg.color}${c.bold}${glyphChar}${c.reset}`
    const labelStr = `${c.dim}${glyphCfg.label}${c.reset}`
    lines.push(`  ${glyphStr}  ${labelStr}`)

    // ── Whisper row ──
    if (whisper) {
      lines.push(`  ${c.dim}│${c.reset}`)
      lines.push(`  ${c.dim}│${c.reset}  ${c.white}${whisper}${c.reset}`)
    }

    // ── Card ──
    if (card) {
      lines.push(`  ${c.dim}│${c.reset}`)
      lines.push(`  ${c.dim}╔${'═'.repeat(50)}╗${c.reset}`)
      lines.push(`  ${c.dim}║${c.reset}  ${c.white}${c.bold}${card.message.padEnd(48)}${c.reset}${c.dim}║${c.reset}`)
      lines.push(`  ${c.dim}║${c.reset}                                                  ${c.dim}║${c.reset}`)
      lines.push(`  ${c.dim}║${c.reset}  ${c.green}[↵ approve]${c.reset}   ${c.gray}[esc dismiss]${c.reset}               ${c.dim}║${c.reset}`)
      lines.push(`  ${c.dim}╚${'═'.repeat(50)}╝${c.reset}`)
    }

    // Write all lines
    const output = lines.join('\n')
    process.stdout.write(output)
    this.lineCount = lines.length
  }

  // ─── Header ────────────────────────────────────────────────────────────────

  private drawHeader(): void {
    const width = 58
    const border = `${c.dim}${'─'.repeat(width)}${c.reset}`

    process.stdout.write('\n')
    process.stdout.write(`  ${c.bold}${c.white}claude-code-glasses${c.reset}  ${c.dim}terminal renderer${c.reset}\n`)
    process.stdout.write(`  ${border}\n`)
    process.stdout.write(`  ${c.dim}claude --output-format stream-json | ccg start --target terminal${c.reset}\n`)
    process.stdout.write(`  ${border}\n\n`)
  }
}

// ─── Simulate from recorded session ──────────────────────────────────────────

export async function simulate(sessionPath: string): Promise<void> {
  const fs = await import('fs')
  const { Readable } = await import('stream')

  const parser = new ClaudeCodeParser()
  const renderer = new TerminalRenderer(parser)
  renderer.start()

  // Read JSONL file and stream it with realistic delays
  const lines = fs.readFileSync(sessionPath, 'utf-8').trim().split('\n')
  const readable = new Readable({ read() {} })

  parser.start(readable)

  for (const line of lines) {
    readable.push(line + '\n')
    await sleep(80) // simulate ~80ms between events
  }

  readable.push(null)
  await sleep(2000)
  renderer.stop()
}

function sleep(ms: number): Promise<void> {
  return new Promise(r => setTimeout(r, ms))
}
