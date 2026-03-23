// ─── Glyph States ────────────────────────────────────────────────────────────

export type GlyphState =
  | 'idle'
  | 'thinking'
  | 'running'
  | 'awaiting'
  | 'done'
  | 'error'

// ─── Display Primitives ───────────────────────────────────────────────────────

export interface WhisperEvent {
  kind: 'whisper'
  text: string          // max 48 chars
  ttl: number           // ms before fade
}

export interface CardEvent {
  kind: 'card'
  id: string
  cardType: 'decision' | 'update'
  message: string       // max 2 lines
  confirmLabel: string
  dismissLabel: string | null
  timeoutMs: number
}

export interface GlyphEvent {
  kind: 'glyph'
  state: GlyphState
}

export type DisplayEvent = WhisperEvent | CardEvent | GlyphEvent

// ─── Glass State (aggregated) ─────────────────────────────────────────────────

export interface GlassState {
  glyph: GlyphState
  whisper: string | null
  card: CardEvent | null
}

// ─── Claude Code Stream Event Types ──────────────────────────────────────────
// From: claude --output-format stream-json --verbose

export interface CCStreamEvent {
  type: 'stream_event'
  event: {
    type: string
    index?: number
    delta?: {
      type: string
      text?: string
      partial_json?: string
    }
    content_block?: {
      type: string
      id?: string
      name?: string
      text?: string
    }
  }
  message?: never
}

export interface CCAssistantMessage {
  type: 'assistant'
  message: {
    id: string
    content: CCContentBlock[]
    usage?: {
      input_tokens: number
      output_tokens: number
    }
  }
}

export interface CCSystemMessage {
  type: 'system'
  subtype: string
  session_id?: string
  [key: string]: unknown
}

export interface CCResultMessage {
  type: 'result'
  subtype: 'success' | 'error_during_execution' | 'no_content_in_response'
  result?: string
  error?: string
  session_id: string
  usage?: {
    input_tokens: number
    output_tokens: number
  }
}

export type CCMessage =
  | CCStreamEvent
  | CCAssistantMessage
  | CCSystemMessage
  | CCResultMessage

// ─── Claude Code Content Block Types ─────────────────────────────────────────

export interface CCTextBlock {
  type: 'text'
  text: string
}

export interface CCToolUseBlock {
  type: 'tool_use'
  id: string
  name: string
  input: Record<string, unknown>
}

export interface CCToolResultBlock {
  type: 'tool_result'
  tool_use_id: string
  content: string | Array<{ type: string; text?: string }>
}

export interface CCThinkingBlock {
  type: 'thinking'
  thinking: string
}

export type CCContentBlock =
  | CCTextBlock
  | CCToolUseBlock
  | CCToolResultBlock
  | CCThinkingBlock

// ─── Transport ────────────────────────────────────────────────────────────────

export type TransportType = 'usb' | 'wifi' | 'terminal'

export interface CCGConfig {
  target: 'rayneo' | 'terminal'
  transport: TransportType
  brightness: number          // 0-100
  whisperDurationMs: number
  cardTimeoutMs: number
}

export const DEFAULT_CONFIG: CCGConfig = {
  target: 'terminal',
  transport: 'usb',
  brightness: 60,
  whisperDurationMs: 4000,
  cardTimeoutMs: 30000,
}

// ─── User Actions (from glasses back to parser) ───────────────────────────────

export type UserAction = 'approve' | 'dismiss' | 'status'

// ─── Tool name → human label mapping ─────────────────────────────────────────

export const TOOL_LABELS: Record<string, (input: Record<string, unknown>) => string> = {
  Read:           (i) => `reading ${shortenPath(i.file_path as string)}`,
  Write:          (i) => `writing ${shortenPath(i.file_path as string)}`,
  Edit:           (i) => `editing ${shortenPath(i.file_path as string)}`,
  MultiEdit:      (i) => `editing ${shortenPath(i.file_path as string)}`,
  Bash:           (i) => `running: ${shortenCmd(i.command as string)}`,
  Glob:           (i) => `finding files: ${i.pattern}`,
  Grep:           (i) => `searching: ${i.pattern}`,
  LS:             (i) => `listing ${shortenPath(i.path as string)}`,
  TodoWrite:      ()  => `updating task list`,
  TodoRead:       ()  => `reading task list`,
  WebSearch:      (i) => `searching: ${shortenStr(i.query as string, 30)}`,
  WebFetch:       (i) => `fetching: ${shortenUrl(i.url as string)}`,
  NotebookRead:   (i) => `reading notebook ${shortenPath(i.notebook_path as string)}`,
  NotebookEdit:   (i) => `editing notebook ${shortenPath(i.notebook_path as string)}`,
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

export function shortenPath(p: string | undefined): string {
  if (!p) return '...'
  const parts = p.replace(/^\/.*?\//, '').split('/')
  return parts.slice(-2).join('/')
}

export function shortenCmd(cmd: string | undefined): string {
  if (!cmd) return '...'
  return cmd.trim().split('\n')[0].slice(0, 38)
}

export function shortenUrl(url: string | undefined): string {
  if (!url) return '...'
  try {
    const u = new URL(url)
    return u.hostname + u.pathname.slice(0, 20)
  } catch {
    return url.slice(0, 30)
  }
}

export function shortenStr(s: string | undefined, max: number): string {
  if (!s) return '...'
  return s.length > max ? s.slice(0, max - 1) + '…' : s
}

export function truncateWhisper(text: string): string {
  return text.length > 48 ? text.slice(0, 47) + '…' : text
}

export function summarizeThinking(thinking: string): string {
  // Take first meaningful sentence, strip filler phrases
  const firstSentence = thinking
    .replace(/^(I need to|I should|I'll|Let me|First,?\s+)/i, '')
    .split(/[.!?\n]/)[0]
    .trim()
    .toLowerCase()
  return truncateWhisper(firstSentence + '…')
}
