#!/usr/bin/env node

import { ClaudeCodeParser } from '@ccg/parser'
import { TerminalRenderer, simulate } from '@ccg/renderer-terminal'
import type { WebSocket as WSType } from 'ws'
import crypto from 'crypto'

const args = process.argv.slice(2)
const command = args[0] ?? 'start'

function getFlag(name: string, fallback: string): string {
  const idx = args.indexOf(name)
  return idx !== -1 ? args[idx + 1] : fallback
}

async function main() {
  switch (command) {

    case 'start': {
      const targetIdx = args.indexOf('--target')
      const target = targetIdx !== -1 ? args[targetIdx + 1] : 'terminal'
      const useRelay = args.includes('--relay')

      if (target === 'terminal') {
        const parser = new ClaudeCodeParser()
        const renderer = new TerminalRenderer(parser)
        renderer.start()
        parser.start(process.stdin)

        if (useRelay) {
          await startRelay(parser)
        }

        parser.on('close', () => {
          setTimeout(() => {
            renderer.stop()
            process.exit(0)
          }, 2000)
        })
      } else if (target === 'rayneo') {
        console.error('RayNeo renderer: coming in v0.1 — use --target terminal for now')
        process.exit(1)
      } else {
        console.error(`Unknown target: ${target}. Use: terminal | rayneo`)
        process.exit(1)
      }
      break
    }

    case 'simulate': {
      const fileIdx = args.indexOf('--file')
      const file = fileIdx !== -1
        ? args[fileIdx + 1]
        : './examples/basic/session.jsonl'

      console.log(`\nSimulating session: ${file}\n`)
      await simulate(file)
      process.exit(0)
      break
    }

    case 'status': {
      console.log('\nccg status: not connected\nRun: claude --output-format stream-json | ccg start\n')
      break
    }

    case 'help':
    default: {
      console.log(`
  claude-code-glasses v0.1.0
  Pipe Claude Code's brain to your face.

  Usage:
    claude --output-format stream-json | ccg start
    claude --output-format stream-json | ccg start --target terminal
    claude --output-format stream-json | ccg start --relay
    ccg simulate --file ./examples/basic/session.jsonl
    ccg status

  Options:
    --target      terminal | rayneo  (default: terminal)
    --file        path to .jsonl session file for simulate
    --relay       start a WebSocket relay server for glasses to connect
    --relay-port  port for the relay server (default: 9200)
      `)
    }
  }
}

// ─── Relay ─────────────────────────────────────────────────────────────────────

async function startRelay(parser: ClaudeCodeParser): Promise<void> {
  const port = parseInt(getFlag('--relay-port', '9200'), 10)
  const token = process.env.CCG_TOKEN ?? crypto.randomBytes(16).toString('hex')

  // Dynamic imports — only loaded when --relay is used
  const { createRelay } = await import('@ccg/relay')
  const qrcodeModule = await import('qrcode-terminal')
  const qrcode = qrcodeModule.default ?? qrcodeModule
  const { WebSocket } = await import('ws')

  const { wss } = createRelay(port)

  const url = `ws://localhost:${port}`
  const connectUri = `ccg://connect?url=${encodeURIComponent(url)}&token=${token}`

  console.log(`\nccg relay listening on ${url}`)
  console.log(`token: ${token}\n`)
  qrcode.generate(connectUri, { small: true })
  console.log(`\nScan the QR code with your glasses to connect.\n`)

  // Connect the parser as an internal publisher
  const pub: WSType = new WebSocket(url)

  pub.on('open', () => {
    pub.send(JSON.stringify({ type: 'auth', version: 1, token, role: 'publisher' }))
  })

  pub.on('message', (data) => {
    try {
      const msg = JSON.parse(data.toString()) as { type: string; action?: string }
      if (msg.type === 'action') {
        if (msg.action === 'approve') parser.approve()
        else if (msg.action === 'dismiss') parser.dismiss()
      }
    } catch {
      // Ignore malformed messages (including auth_ok)
    }
  })

  // Forward parser state to the relay
  parser.on('state', (state) => {
    if (pub.readyState === WebSocket.OPEN) {
      pub.send(JSON.stringify({ type: 'state', data: state }))
    }
  })
}

main().catch(console.error)
