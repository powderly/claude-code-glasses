import { randomBytes } from 'node:crypto';
import { WebSocketServer, WebSocket } from 'ws';
import type { IncomingMessage } from 'node:http';

// ── Protocol types ──────────────────────────────────────────────────────────

/** Protocol version — bump when the wire format changes. */
const PROTOCOL_VERSION = 1;

/** Auth timeout in milliseconds. Clients must authenticate within this window. */
const AUTH_TIMEOUT_MS = 5000;

interface AuthMessage {
  type: 'auth';
  version: number;
  token: string;
  role: 'publisher' | 'renderer';
}

interface AuthOk {
  type: 'auth_ok';
}

interface AuthFail {
  type: 'auth_fail';
  reason: string;
}

interface StateMessage {
  type: 'state';
  [key: string]: unknown;
}

interface ActionMessage {
  type: 'action';
  [key: string]: unknown;
}

// ── Session bookkeeping ─────────────────────────────────────────────────────

interface Session {
  publisher: WebSocket | null;
  renderers: Set<WebSocket>;
  lastState: StateMessage | null;
}

// ── Helpers ─────────────────────────────────────────────────────────────────

/**
 * Generate a cryptographically random session token (16 bytes, hex-encoded).
 * Share this token between the publisher and its renderers to pair them.
 */
export function generateToken(): string {
  return randomBytes(16).toString('hex');
}

function send(ws: WebSocket, data: object): void {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(data));
  }
}

// ── Relay server ────────────────────────────────────────────────────────────

/**
 * Create a WebSocket relay server.
 *
 * The relay pairs a single **publisher** (laptop running the ccg parser) with
 * one or more **renderer** clients (AR glasses) using a shared session token.
 *
 * - Publisher sends `state` messages → relay broadcasts to all renderers.
 * - Renderer sends `action` messages → relay forwards to the publisher.
 * - Last state is cached so late-joining renderers get immediate context.
 *
 * @param port - TCP port to listen on (default: 9200)
 * @returns `{ wss, close }` — the underlying WebSocketServer and a cleanup fn.
 */
export function createRelay(port: number = 9200) {
  const sessions = new Map<string, Session>();

  const wss = new WebSocketServer({ port });

  /** Get-or-create a session for a given token. */
  function getSession(token: string): Session {
    let session = sessions.get(token);
    if (!session) {
      session = { publisher: null, renderers: new Set(), lastState: null };
      sessions.set(token, session);
    }
    return session;
  }

  /** Remove a session if it has no publisher and no renderers. */
  function pruneSession(token: string): void {
    const session = sessions.get(token);
    if (session && !session.publisher && session.renderers.size === 0) {
      sessions.delete(token);
    }
  }

  wss.on('connection', (ws: WebSocket, _req: IncomingMessage) => {
    let authenticated = false;
    let clientRole: 'publisher' | 'renderer' | null = null;
    let clientToken: string | null = null;

    // Enforce auth timeout — close if no valid auth message within the window.
    const authTimer = setTimeout(() => {
      if (!authenticated) {
        send(ws, { type: 'auth_fail', reason: 'auth timeout' } satisfies AuthFail);
        ws.close();
      }
    }, AUTH_TIMEOUT_MS);

    ws.on('message', (raw: Buffer | string) => {
      let msg: any;
      try {
        msg = JSON.parse(typeof raw === 'string' ? raw : raw.toString('utf-8'));
      } catch {
        send(ws, { type: 'auth_fail', reason: 'invalid JSON' } satisfies AuthFail);
        ws.close();
        return;
      }

      // ── Authentication gate ─────────────────────────────────────────────
      if (!authenticated) {
        if (msg.type !== 'auth') {
          send(ws, { type: 'auth_fail', reason: 'expected auth message' } satisfies AuthFail);
          ws.close();
          return;
        }

        const auth = msg as AuthMessage;

        if (auth.version !== PROTOCOL_VERSION) {
          send(ws, {
            type: 'auth_fail',
            reason: 'unsupported protocol version',
          } satisfies AuthFail);
          ws.close();
          return;
        }

        if (!auth.token || typeof auth.token !== 'string') {
          send(ws, { type: 'auth_fail', reason: 'missing token' } satisfies AuthFail);
          ws.close();
          return;
        }

        if (auth.role !== 'publisher' && auth.role !== 'renderer') {
          send(ws, { type: 'auth_fail', reason: 'invalid role' } satisfies AuthFail);
          ws.close();
          return;
        }

        const session = getSession(auth.token);

        // Only one publisher per token.
        if (auth.role === 'publisher' && session.publisher) {
          send(ws, {
            type: 'auth_fail',
            reason: 'duplicate publisher',
          } satisfies AuthFail);
          ws.close();
          return;
        }

        // Auth succeeded.
        clearTimeout(authTimer);
        authenticated = true;
        clientRole = auth.role;
        clientToken = auth.token;

        if (auth.role === 'publisher') {
          session.publisher = ws;
        } else {
          session.renderers.add(ws);
          // Send last known state so late-joining renderers have context.
          if (session.lastState) {
            send(ws, session.lastState);
          }
        }

        send(ws, { type: 'auth_ok' } satisfies AuthOk);
        return;
      }

      // ── Post-auth message routing ───────────────────────────────────────
      const session = sessions.get(clientToken!);
      if (!session) return;

      if (clientRole === 'publisher' && msg.type === 'state') {
        // Cache latest state and broadcast to all renderers.
        session.lastState = msg as StateMessage;
        for (const renderer of session.renderers) {
          send(renderer, msg);
        }
      } else if (clientRole === 'renderer' && msg.type === 'action') {
        // Forward action to the publisher.
        if (session.publisher) {
          send(session.publisher, msg);
        }
      }
      // All other message types are silently dropped.
    });

    ws.on('close', () => {
      clearTimeout(authTimer);
      if (!clientToken) return;

      const session = sessions.get(clientToken);
      if (!session) return;

      if (clientRole === 'publisher') {
        session.publisher = null;
        // Notify all renderers that the publisher disconnected.
        const errorState: StateMessage = {
          type: 'state',
          glyph: { state: 'error' },
          whisper: { text: 'publisher disconnected' },
          card: null,
        };
        for (const renderer of session.renderers) {
          send(renderer, errorState);
        }
      } else if (clientRole === 'renderer') {
        session.renderers.delete(ws);
      }

      pruneSession(clientToken);
    });
  });

  /** Gracefully shut down the relay. Closes all connections and the server. */
  function close(): Promise<void> {
    return new Promise((resolve, reject) => {
      for (const client of wss.clients) {
        client.close();
      }
      wss.close((err) => {
        if (err) reject(err);
        else resolve();
      });
    });
  }

  return { wss, close };
}

// ── Standalone entry point ──────────────────────────────────────────────────

const isMainModule =
  typeof process !== 'undefined' &&
  process.argv[1] &&
  (process.argv[1].endsWith('/index.ts') || process.argv[1].endsWith('/index.js'));

if (isMainModule) {
  const port = parseInt(process.env.CCG_RELAY_PORT ?? '9200', 10);
  const { close } = createRelay(port);
  console.log(`ccg relay listening on ws://0.0.0.0:${port}`);

  process.on('SIGINT', async () => {
    console.log('\nshutting down...');
    await close();
    process.exit(0);
  });
}
