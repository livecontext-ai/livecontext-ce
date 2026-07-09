// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { ChannelHandler, WsConnectionStatus } from '../ws-types';

/**
 * Robustness guard for the singleton WS client's RECONNECTION logic - the prod
 * bug where, after a churn of connect/disconnect, the client latched off and
 * real-time events stopped (never reconnecting). Covers: auto-reconnect on a
 * network drop, intentional disconnect staying off, connect() re-enabling after a
 * disconnect, no duplicate sockets, reconnect() (workspace switch), and
 * online/visibility recovery.
 */

class MockWebSocket {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;
  static instances: MockWebSocket[] = [];

  url: string;
  readyState = MockWebSocket.CONNECTING;
  onopen: ((e?: unknown) => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: ((e?: unknown) => void) | null = null;
  onerror: ((e?: unknown) => void) | null = null;
  sent: string[] = [];

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
  }
  send(d: string) { this.sent.push(d); }
  close() {
    this.readyState = MockWebSocket.CLOSED;
    // Real browsers fire onclose async; ws-client nulls handlers before close()
    // when it initiates, so a self-close won't re-enter handleDisconnect.
    this.onclose?.();
  }
  // ── test helpers (server/network side) ──
  serverOpen() { this.readyState = MockWebSocket.OPEN; this.onopen?.(); }
  serverMsg(obj: unknown) { this.onmessage?.({ data: JSON.stringify(obj) }); }
  serverDrop() { this.readyState = MockWebSocket.CLOSED; this.onclose?.(); }
}

interface WsClientForTest {
  connect: (
    gatewayUrl: string,
    tokenProvider: () => Promise<string>,
    activeOrgProvider?: () => string | null,
  ) => void;
  disconnect: () => void;
  reconnect: () => void;
  subscribe: (channel: string, handler: ChannelHandler, requestSnapshot?: boolean) => () => void;
  status: WsConnectionStatus;
}

let wsClient: WsClientForTest;

const TOKEN = async () => 'tok';
const flush = () => vi.advanceTimersByTimeAsync(0); // flush microtasks (async token) + 0ms timers

/** Bring the client to a live 'connected' state (open + hello). */
async function bringUp() {
  wsClient.connect('ws://gw', TOKEN);
  await flush();
  const ws = MockWebSocket.instances.at(-1)!;
  ws.serverOpen();
  ws.serverMsg({ v: 1, type: 'hello', payload: { sessionId: 's1', heartbeatMs: 30000 } });
  return ws;
}

beforeEach(async () => {
  vi.useFakeTimers();
  MockWebSocket.instances = [];
  (globalThis as unknown as { WebSocket: unknown }).WebSocket = MockWebSocket;
  vi.resetModules();
  ({ wsClient } = await import('../ws-client'));
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('WebSocketClient reconnection', () => {
  it('connects and reaches "connected" after open + hello', async () => {
    await bringUp();
    expect(MockWebSocket.instances).toHaveLength(1);
    expect(wsClient.status).toBe('connected');
  });

  it('AUTO-RECONNECTS after a network drop (core fix)', async () => {
    const ws = await bringUp();
    ws.serverDrop();
    expect(wsClient.status).toBe('reconnecting');
    // Backoff (≤30s) + jitter, then a fresh socket opens.
    await vi.advanceTimersByTimeAsync(35000);
    expect(MockWebSocket.instances.length).toBeGreaterThanOrEqual(2);
  });

  it('intentional disconnect() does NOT reconnect', async () => {
    await bringUp();
    wsClient.disconnect();
    expect(wsClient.status).toBe('disconnected');
    await vi.advanceTimersByTimeAsync(60000);
    expect(MockWebSocket.instances).toHaveLength(1); // no new socket
  });

  it('connect() after disconnect() RE-ENABLES the connection (the prod bug)', async () => {
    await bringUp();
    wsClient.disconnect();
    await vi.advanceTimersByTimeAsync(60000);
    expect(MockWebSocket.instances).toHaveLength(1);

    // Coming back must open a fresh socket - previously reconnectAttempt stayed
    // pinned at max and the client never reconnected.
    wsClient.connect('ws://gw', TOKEN);
    await flush();
    expect(MockWebSocket.instances).toHaveLength(2);
    const ws2 = MockWebSocket.instances.at(-1)!;
    ws2.serverOpen();
    ws2.serverMsg({ v: 1, type: 'hello', payload: { sessionId: 's2', heartbeatMs: 30000 } });
    expect(wsClient.status).toBe('connected');

    // ...and a subsequent drop still auto-reconnects (not latched off).
    ws2.serverDrop();
    await vi.advanceTimersByTimeAsync(35000);
    expect(MockWebSocket.instances.length).toBeGreaterThanOrEqual(3);
  });

  it('connect() re-enables reconnection even when the prior session never said hello (true latch repro)', async () => {
    // This is the discriminating regression test: NO successful hello resets the
    // backoff counter, so it exercises connect()'s reset of intentionalClose +
    // reconnectAttempt. On the pre-fix code the client stayed latched off here.
    await bringUp();
    wsClient.disconnect();           // pre-fix: reconnectAttempt pinned at max
    await flush();

    wsClient.connect('ws://gw', TOKEN); // opens socket #2 (no hello yet)
    await flush();
    expect(MockWebSocket.instances).toHaveLength(2);

    // Drop BEFORE any hello - nothing has reset the counter except connect().
    MockWebSocket.instances.at(-1)!.serverDrop();
    await vi.advanceTimersByTimeAsync(35000);
    // Pre-fix: stuck at 2 (latched). Post-fix: a 3rd socket opens.
    expect(MockWebSocket.instances.length).toBeGreaterThanOrEqual(3);
  });

  it('a zombie "connected" socket (dead readyState) does not block a reconnect', async () => {
    // doConnect must guard on the real ws.readyState, not the stale _status field.
    const ws = await bringUp();
    ws.readyState = MockWebSocket.CLOSED; // socket died but no onclose fired yet
    expect(wsClient.status).toBe('connected'); // status is now stale

    wsClient.connect('ws://gw', TOKEN); // pre-fix: no-op (status==='connected')
    await flush();
    expect(MockWebSocket.instances).toHaveLength(2); // post-fix: fresh socket opened
  });

  it('a visibilitychange to visible revives a dropped connection immediately', async () => {
    const ws = await bringUp();
    ws.serverDrop();
    const before = MockWebSocket.instances.length;
    // Don't advance the backoff timer - only the visibility handler should act.
    document.dispatchEvent(new Event('visibilitychange'));
    await flush();
    expect(MockWebSocket.instances.length).toBeGreaterThan(before);
  });

  it('does not open duplicate sockets when connect() is called repeatedly', async () => {
    wsClient.connect('ws://gw', TOKEN);
    wsClient.connect('ws://gw', TOKEN);
    await flush();
    wsClient.connect('ws://gw', TOKEN);
    await flush();
    expect(MockWebSocket.instances).toHaveLength(1);
  });

  it('reconnect() (workspace switch) drops the current socket and opens a fresh one, staying enabled', async () => {
    const ws1 = await bringUp();
    wsClient.reconnect();
    await flush();
    expect(MockWebSocket.instances).toHaveLength(2);
    expect(ws1.readyState).toBe(MockWebSocket.CLOSED);
    // still reconnect-enabled afterwards
    const ws2 = MockWebSocket.instances.at(-1)!;
    ws2.serverOpen();
    ws2.serverMsg({ v: 1, type: 'hello', payload: { sessionId: 's2', heartbeatMs: 30000 } });
    ws2.serverDrop();
    await vi.advanceTimersByTimeAsync(35000);
    expect(MockWebSocket.instances.length).toBeGreaterThanOrEqual(3);
  });

  it('an "online" event revives a dropped connection immediately', async () => {
    const ws = await bringUp();
    ws.serverDrop();
    const before = MockWebSocket.instances.length;
    window.dispatchEvent(new Event('online'));
    await flush();
    expect(MockWebSocket.instances.length).toBeGreaterThan(before);
  });

  it('after intentional disconnect(), an "online" event does NOT reconnect', async () => {
    await bringUp();
    wsClient.disconnect();
    window.dispatchEvent(new Event('online'));
    await vi.advanceTimersByTimeAsync(5000);
    expect(MockWebSocket.instances).toHaveLength(1);
  });
});

describe('WebSocketClient event payload normalization', () => {
  it('keeps flat channel payloads with a business payload field intact', async () => {
    const ws = await bringUp();
    const handler = vi.fn();
    wsClient.subscribe('task:board:42', handler);

    const businessPayload = {
      event: 'task_updated',
      payload: { taskId: 'task-1' },
      timestamp: '2026-06-29T10:00:00Z',
    };

    ws.serverMsg({
      v: 1,
      type: 'event',
      id: 'outer-flat-payload',
      channel: 'task:board:42',
      ts: Date.now(),
      payload: businessPayload,
    });

    expect(handler).toHaveBeenCalledWith(businessPayload);
  });

  it('unwraps standardized Redis envelopes and preserves the envelope type', async () => {
    const ws = await bringUp();
    const handler = vi.fn();
    wsClient.subscribe('workflow:run:run-1', handler);

    ws.serverMsg({
      v: 1,
      type: 'event',
      id: 'outer-standard-envelope',
      channel: 'workflow:run:run-1',
      ts: Date.now(),
      payload: {
        v: 1,
        type: 'batch-update',
        id: 'redis-event-1',
        ts: 123,
        payload: { runId: 'run-1', status: 'RUNNING' },
      },
    });

    expect(handler).toHaveBeenCalledWith({
      runId: 'run-1',
      status: 'RUNNING',
      type: 'batch-update',
    });
  });
});
