#!/usr/bin/env node

import { ClaudeCodeParser } from '@ccg/parser'
import { TerminalRenderer, simulate } from '@ccg/renderer-terminal'

const args = process.argv.slice(2)
const command = args[0] ?? 'start'

async function main() {
  switch (command) {

    case 'start': {
      const targetIdx = args.indexOf('--target')
      const target = targetIdx !== -1 ? args[targetIdx + 1] : 'terminal'

      if (target === 'terminal') {
        const parser = new ClaudeCodeParser()
        const renderer = new TerminalRenderer(parser)
        renderer.start()
        parser.start(process.stdin)

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
    ccg simulate --file ./examples/basic/session.jsonl
    ccg status

  Options:
    --target  terminal | rayneo  (default: terminal)
    --file    path to .jsonl session file for simulate
      `)
    }
  }
}

main().catch(console.error)
