import { describe, it, expect, beforeAll, afterAll, beforeEach } from 'vitest';
import WebSocket from 'ws';
import { createRelay, generateToken } from '../index.js';

const TEST_PORT = 9876;

/** Open a WebSocket to the test relay and wait for the connection to be ready. */
function connect(): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${TEST_PORT}`);
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
  });
}

/** Send a JSON message over a WebSocket. */
function send(ws: WebSocket, data: object): void {
  ws.send(JSON.stringify(data));
}

/** Wait for the next JSON message on a WebSocket. */
function nextMessage(ws: WebSocket, timeoutMs = 2000): Promise<any> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('message timeout')), timeoutMs);
    ws.once('message', (raw) => {
      clearTimeout(timer);
      resolve(JSON.parse(raw.toString()));
    });
  });
}

/** Authenticate a WebSocket connection and return the auth response. */
async function authenticate(
  ws: WebSocket,
  token: string,
  role: 'publisher' | 'renderer',
  version = 1,
): Promise<any> {
  send(ws, { type: 'auth', version, token, role });
  return nextMessage(ws);
}

describe('relay server', () => {
  let relay: ReturnType<typeof createRelay>;

  beforeAll(() => {
    relay = createRelay(TEST_PORT);
  });

  afterAll(async () => {
    await relay.close();
  });

  it('authenticates publisher and renderer', async () => {
    const token = generateToken();
    const pub = await connect();
    const ren = await connect();

    const pubAuth = await authenticate(pub, token, 'publisher');
    expect(pubAuth).toEqual({ type: 'auth_ok' });

    const renAuth = await authenticate(ren, token, 'renderer');
    expect(renAuth).toEqual({ type: 'auth_ok' });

    pub.close();
    ren.close();
  });

  it('forwards state from publisher to renderer', async () => {
    const token = generateToken();
    const pub = await connect();
    const ren = await connect();

    await authenticate(pub, token, 'publisher');
    await authenticate(ren, token, 'renderer');

    const stateMsg = {
      type: 'state',
      glyph: { state: 'running' },
      whisper: { text: 'reading file' },
      card: null,
    };

    const receivePromise = nextMessage(ren);
    send(pub, stateMsg);
    const received = await receivePromise;

    expect(received).toEqual(stateMsg);

    pub.close();
    ren.close();
  });

  it('forwards action from renderer to publisher', async () => {
    const token = generateToken();
    const pub = await connect();
    const ren = await connect();

    await authenticate(pub, token, 'publisher');
    await authenticate(ren, token, 'renderer');

    const actionMsg = { type: 'action', action: 'approve' };

    const receivePromise = nextMessage(pub);
    send(ren, actionMsg);
    const received = await receivePromise;

    expect(received).toEqual(actionMsg);

    pub.close();
    ren.close();
  });

  it('sends last known state to newly connecting renderer', async () => {
    const token = generateToken();
    const pub = await connect();
    const ren1 = await connect();

    await authenticate(pub, token, 'publisher');
    await authenticate(ren1, token, 'renderer');

    const stateMsg = {
      type: 'state',
      glyph: { state: 'thinking' },
      whisper: { text: 'planning' },
      card: null,
    };

    const ren1Promise = nextMessage(ren1);
    send(pub, stateMsg);
    await ren1Promise;

    // New renderer connects after state was sent.
    // The server sends auth_ok then lastState in the same tick, so collect
    // both messages before asserting to avoid ordering sensitivity.
    const ren2 = await connect();
    const messages: any[] = [];
    const collectTwo = new Promise<void>((resolve) => {
      ren2.on('message', (raw) => {
        messages.push(JSON.parse(raw.toString()));
        if (messages.length === 2) resolve();
      });
    });
    send(ren2, { type: 'auth', version: 1, token, role: 'renderer' });
    await collectTwo;

    expect(messages).toContainEqual({ type: 'auth_ok' });
    expect(messages).toContainEqual(stateMsg);

    pub.close();
    ren1.close();
    ren2.close();
  });

  it('rejects invalid protocol version', async () => {
    const token = generateToken();
    const ws = await connect();

    const response = await authenticate(ws, token, 'publisher', 99);
    expect(response).toEqual({
      type: 'auth_fail',
      reason: 'unsupported protocol version',
    });

    // Connection should be closed by server.
    await new Promise<void>((resolve) => {
      if (ws.readyState === WebSocket.CLOSED) return resolve();
      ws.on('close', () => resolve());
    });
  });

  it('rejects duplicate publisher', async () => {
    const token = generateToken();
    const pub1 = await connect();
    const pub2 = await connect();

    const auth1 = await authenticate(pub1, token, 'publisher');
    expect(auth1).toEqual({ type: 'auth_ok' });

    const auth2 = await authenticate(pub2, token, 'publisher');
    expect(auth2).toEqual({
      type: 'auth_fail',
      reason: 'duplicate publisher',
    });

    pub1.close();
    // pub2 already closed by server
  });
});
