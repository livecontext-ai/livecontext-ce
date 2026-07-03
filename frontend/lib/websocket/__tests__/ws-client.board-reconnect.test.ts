// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { ChannelHandler, WsConnectionStatus } from '../ws-types';

/**
 * BOARD-LEVEL reconnect composition test. ws-client.test.ts proves the client
 * reconnects; useTaskBoardStream.test.ts proves the reducer applies events.
 * Nothing composed the two: nobody dropped the socket mid-session and asserted
 * that a live channel subscription (task:board:X) survives the reconnect
 * without losing or duplicating task events. This file covers that seam:
 *   (a) after a drop + reconnect, the subscribe frame is RE-SENT with
 *       requestSnapshot=true (resubscribeAll) so the board can rebuild state,
 *   (b) a snapshot replay carrying an envelope id already delivered before the
 *       drop is DEDUPED (handler called once, no duplicated board events),
 *   (c) fresh events published after the reconnect still reach the handler
 *       (no lost events, no dead subscription).
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
  subscribe: (channel: string, handler: ChannelHandler, requestSnapshot?: boolean) => () => void;
  status: WsConnectionStatus;
}

let wsClient: WsClientForTest;

const TOKEN = async () => 'tok';
const CHANNEL = 'task:board:tenant-1';
const flush = () => vi.advanceTimersByTimeAsync(0);

/** Frames a mock socket has sent, JSON-parsed. */
function framesOf(ws: MockWebSocket): Array<Record<string, unknown>> {
  return ws.sent.map((raw) => JSON.parse(raw) as Record<string, unknown>);
}

function subscribeFramesFor(ws: MockWebSocket, channel: string): Array<Record<string, unknown>> {
  return framesOf(ws).filter((f) => f.type === 'subscribe' && f.channel === channel);
}

/** Bring the client to a live 'connected' state (open + hello). */
async function bringUp() {
  wsClient.connect('ws://gw', TOKEN);
  await flush();
  const ws = MockWebSocket.instances.at(-1)!;
  ws.serverOpen();
  ws.serverMsg({ v: 1, type: 'hello', payload: { sessionId: 's1', heartbeatMs: 30000 } });
  return ws;
}

/** Drop the socket, let the backoff elapse, and complete the reconnect handshake. */
async function dropAndReconnect(ws: MockWebSocket): Promise<MockWebSocket> {
  const socketsBefore = MockWebSocket.instances.length;
  ws.serverDrop();
  expect(wsClient.status).toBe('reconnecting');
  await vi.advanceTimersByTimeAsync(35000); // backoff (<=30s) + jitter
  expect(MockWebSocket.instances.length).toBeGreaterThan(socketsBefore);
  const ws2 = MockWebSocket.instances.at(-1)!;
  ws2.serverOpen();
  ws2.serverMsg({ v: 1, type: 'hello', payload: { sessionId: 's2', heartbeatMs: 30000 } });
  expect(wsClient.status).toBe('connected');
  return ws2;
}

function taskEvent(envelopeId: string, taskId: string, event = 'task_updated') {
  return {
    v: 1,
    type: 'event',
    id: envelopeId,
    channel: CHANNEL,
    ts: Date.now(),
    payload: { event, payload: { taskId }, timestamp: '2026-07-02T10:00:00Z' },
  };
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

describe('board-level channel subscription across a mid-session reconnect', () => {
  it('(a) re-sends the subscribe frame with requestSnapshot=true after the socket drops', async () => {
    const ws1 = await bringUp();
    const handler = vi.fn();
    wsClient.subscribe(CHANNEL, handler);

    // Initial subscribe went out on socket #1, WITHOUT a snapshot request
    // (the board's initial state comes from the REST fetch, not the WS).
    const initial = subscribeFramesFor(ws1, CHANNEL);
    expect(initial).toHaveLength(1);
    expect(initial[0].payload).toBeUndefined();

    const ws2 = await dropAndReconnect(ws1);

    // resubscribeAll must re-announce the channel on the NEW socket and ask
    // for a snapshot so events missed during the outage are replayed.
    const resub = subscribeFramesFor(ws2, CHANNEL);
    expect(resub).toHaveLength(1);
    expect(resub[0].payload).toEqual({ requestSnapshot: true });
  });

  it('(b) dedupes a snapshot replay that carries an envelope id already delivered before the drop', async () => {
    const ws1 = await bringUp();
    const handler = vi.fn();
    wsClient.subscribe(CHANNEL, handler);

    ws1.serverMsg(taskEvent('env-dup-1', 'task-1'));
    expect(handler).toHaveBeenCalledTimes(1);

    const ws2 = await dropAndReconnect(ws1);

    // Server replays the same event (same envelope id) in the post-reconnect
    // snapshot - the board must NOT see it twice.
    ws2.serverMsg(taskEvent('env-dup-1', 'task-1'));
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('(c) events published after the reconnect reach the original handler (no lost events)', async () => {
    const ws1 = await bringUp();
    const handler = vi.fn();
    wsClient.subscribe(CHANNEL, handler);

    ws1.serverMsg(taskEvent('env-1', 'task-1', 'task_created'));

    const ws2 = await dropAndReconnect(ws1);
    ws2.serverMsg(taskEvent('env-2', 'task-2', 'task_updated'));

    // Exactly two distinct events, in order, each delivered once.
    expect(handler).toHaveBeenCalledTimes(2);
    expect(handler.mock.calls[0][0]).toMatchObject({ event: 'task_created', payload: { taskId: 'task-1' } });
    expect(handler.mock.calls[1][0]).toMatchObject({ event: 'task_updated', payload: { taskId: 'task-2' } });
  });

  it('composed drop mid-session: resubscribe + dedup + fresh delivery in a single session', async () => {
    const ws1 = await bringUp();
    const handler = vi.fn();
    wsClient.subscribe(CHANNEL, handler);

    // Pre-drop traffic.
    ws1.serverMsg(taskEvent('env-A', 'task-A', 'task_created'));
    expect(handler).toHaveBeenCalledTimes(1);

    const ws2 = await dropAndReconnect(ws1);

    // (a) resubscribed with snapshot
    expect(subscribeFramesFor(ws2, CHANNEL)[0]?.payload).toEqual({ requestSnapshot: true });

    // (b) snapshot replays env-A (already seen) + delivers env-B (missed during outage)
    ws2.serverMsg(taskEvent('env-A', 'task-A', 'task_created'));
    ws2.serverMsg(taskEvent('env-B', 'task-B', 'task_updated'));
    // (c) live event after the snapshot
    ws2.serverMsg(taskEvent('env-C', 'task-B', 'task_updated'));

    // env-A deduped; env-B and env-C delivered once each: 3 total, none lost, none doubled.
    expect(handler).toHaveBeenCalledTimes(3);
    const taskIds = handler.mock.calls.map((c) => (c[0] as { payload: { taskId: string } }).payload.taskId);
    expect(taskIds).toEqual(['task-A', 'task-B', 'task-B']);
  });
});
