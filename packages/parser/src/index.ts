import { EventEmitter } from 'events'
import * as readline from 'readline'
import {
  CCMessage,
  CCAssistantMessage,
  CCToolUseBlock,
  CCThinkingBlock,
  GlassState,
  GlyphState,
  CardEvent,
  TOOL_LABELS,
  DEFAULT_CONFIG,
  CCGConfig,
  summarizeThinking,
  truncateWhisper,
} from '@ccg/core'

// ─── Parser ───────────────────────────────────────────────────────────────────

export class ClaudeCodeParser extends EventEmitter {
  private config: CCGConfig
  private state: GlassState = {
    glyph: 'idle',
    whisper: null,
    card: null,
  }

  // Track in-progress tool inputs (streamed as partial JSON)
  private pendingTools: Map<number, { name: string; partialJson: string }> = new Map()

  constructor(config: Partial<CCGConfig> = {}) {
    super()
    this.config = { ...DEFAULT_CONFIG, ...config }
  }

  // ─── Public: pipe from stdin ────────────────────────────────────────────────

  start(input: NodeJS.ReadableStream = process.stdin): void {
    const rl = readline.createInterface({ input, terminal: false })

    rl.on('line', (line) => {
      if (!line.trim()) return
      try {
        const msg = JSON.parse(line) as CCMessage
        this.handleMessage(msg)
      } catch {
        // Non-JSON line from Claude Code (warnings etc.) — ignore
      }
    })

    rl.on('close', () => {
      this.setState({ glyph: 'idle', whisper: null, card: null })
      this.emit('close')
    })
  }

  // ─── Message Router ─────────────────────────────────────────────────────────

  private handleMessage(msg: CCMessage): void {
    switch (msg.type) {

      case 'system':
        // Session starting
        this.setState({ glyph: 'thinking', whisper: 'starting…', card: null })
        break

      case 'assistant':
        this.handleAssistantMessage(msg as CCAssistantMessage)
        break

      case 'stream_event':
        this.handleStreamEvent(msg)
        break

      case 'result':
        if (msg.subtype === 'success') {
          this.setState({
            glyph: 'done',
            whisper: truncateWhisper(`✓ ${msg.result?.split('\n')[0] ?? 'done'}`),
            card: null,
          })
          // Auto-clear after TTL
          setTimeout(() => {
            this.setState({ glyph: 'idle', whisper: null, card: null })
          }, this.config.whisperDurationMs * 2)
        } else {
          this.setState({
            glyph: 'error',
            whisper: truncateWhisper(`✕ ${msg.error ?? 'unknown error'}`),
            card: null,
          })
        }
        break
    }
  }

  // ─── Assistant Message Handler ───────────────────────────────────────────────

  private handleAssistantMessage(msg: CCAssistantMessage): void {
    for (const block of msg.message.content) {
      switch (block.type) {

        case 'thinking': {
          const b = block as CCThinkingBlock
          this.setState({
            glyph: 'thinking',
            whisper: summarizeThinking(b.thinking),
            card: null,
          })
          break
        }

        case 'text': {
          // Claude is writing a response — brief whisper of first meaningful line
          const firstLine = block.text
            .split('\n')
            .find(l => l.trim().length > 3)
            ?.trim() ?? ''
          if (firstLine) {
            this.setState({
              glyph: 'idle',
              whisper: truncateWhisper(firstLine),
              card: null,
            })
          }
          break
        }

        case 'tool_use': {
          const b = block as CCToolUseBlock
          const whisper = this.formatToolWhisper(b.name, b.input)
          this.setState({
            glyph: 'running',
            whisper,
            card: null,
          })
          break
        }
      }
    }
  }

  // ─── Stream Event Handler ────────────────────────────────────────────────────
  // Handles partial streaming events for real-time updates

  private handleStreamEvent(msg: CCMessage & { type: 'stream_event' }): void {
    const ev = msg.event
    if (!ev) return

    switch (ev.type) {

      case 'message_start':
        this.setState({ glyph: 'thinking', whisper: null, card: null })
        break

      case 'content_block_start': {
        const cb = ev.content_block
        if (!cb) break
        if (cb.type === 'thinking') {
          this.setState({ glyph: 'thinking', whisper: 'thinking…', card: null })
        } else if (cb.type === 'tool_use' && ev.index !== undefined) {
          // Start tracking this tool's streaming input
          this.pendingTools.set(ev.index, { name: cb.name ?? '', partialJson: '' })
          this.setState({ glyph: 'running', whisper: this.formatToolName(cb.name ?? ''), card: null })
        }
        break
      }

      case 'content_block_delta': {
        const delta = ev.delta
        if (!delta) break

        if (delta.type === 'thinking_delta' && delta.text) {
          // Update thinking whisper as it streams
          this.setState({
            glyph: 'thinking',
            whisper: summarizeThinking(delta.text),
            card: null,
          })
        } else if (delta.type === 'input_json_delta' && ev.index !== undefined) {
          // Accumulate tool input JSON
          const pending = this.pendingTools.get(ev.index)
          if (pending && delta.partial_json) {
            pending.partialJson += delta.partial_json
            // Try to extract a path or key value early for whisper
            const earlyWhisper = this.extractEarlyWhisper(pending.name, pending.partialJson)
            if (earlyWhisper) {
              this.setState({ glyph: 'running', whisper: earlyWhisper, card: null })
            }
          }
        }
        break
      }

      case 'content_block_stop': {
        if (ev.index !== undefined) {
          const pending = this.pendingTools.get(ev.index)
          if (pending) {
            // Tool input is complete — parse and get final whisper
            try {
              const input = JSON.parse(pending.partialJson) as Record<string, unknown>
              const whisper = this.formatToolWhisper(pending.name, input)
              this.setState({ glyph: 'running', whisper, card: null })
            } catch {
              // Partial JSON parse failed — keep current whisper
            }
            this.pendingTools.delete(ev.index)
          }
        }
        break
      }

      case 'message_stop':
        // Don't change state here — wait for the result message
        break
    }
  }

  // ─── Tool Whisper Formatting ──────────────────────────────────────────────────

  private formatToolWhisper(name: string, input: Record<string, unknown>): string {
    const formatter = TOOL_LABELS[name]
    if (formatter) return truncateWhisper(formatter(input))
    return truncateWhisper(`${this.formatToolName(name)}…`)
  }

  private formatToolName(name: string): string {
    // CamelCase → lowercase with spaces
    return name.replace(/([A-Z])/g, ' $1').trim().toLowerCase()
  }

  // Extract early whisper from partial JSON (before it's fully streamed)
  private extractEarlyWhisper(toolName: string, partialJson: string): string | null {
    // Try to pull a file path out of partial JSON early
    const pathMatch = partialJson.match(/"(?:file_path|path|notebook_path)"\s*:\s*"([^"]+)"/)
    if (pathMatch) {
      const label = TOOL_LABELS[toolName]
      if (label) {
        try {
          return truncateWhisper(label({ file_path: pathMatch[1], path: pathMatch[1] }))
        } catch { /* ignore */ }
      }
    }
    return null
  }

  // ─── State Management ─────────────────────────────────────────────────────────

  private setState(next: GlassState): void {
    this.state = next
    this.emit('state', this.state)
    this.emit(next.glyph) // convenience: emit named glyph events
  }

  // ─── User Actions ─────────────────────────────────────────────────────────────

  approve(): void {
    if (this.state.card) {
      this.emit('approved', this.state.card)
      this.setState({ glyph: 'running', whisper: '✓ approved', card: null })
    }
  }

  dismiss(): void {
    if (this.state.card) {
      this.emit('dismissed', this.state.card)
      this.setState({ glyph: 'idle', whisper: null, card: null })
    }
  }

  getState(): GlassState {
    return this.state
  }
}
