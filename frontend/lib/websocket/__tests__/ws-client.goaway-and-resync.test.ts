// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { ChannelHandler, WsConnectionStatus } from '../ws-types';

/**
 * Prod, 2026-09-29. The gateway refused one user's new socket 8 times that day (per-user
 * connection cap) and answered each refusal with `goaway`, which made this client call
 * disconnect(): no reconnect ever again, no indicator, for the life of the page. That tab
 * then received no event at all - a chat reply that was saved but never appeared, an app
 * run that finished at 15:04:45 while the page said "running" until a reload at 15:07.
 *
 * Pinned here: a refused tab comes back on its own after the server's retry-after; a
 * re-established session tells the app to re-read what it missed; a channel the server
 * refused for capacity is announced again once a slot frees up.
 */

class MockWebSocket {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;
  static instances: MockWebSocket[] = [];

  readyState = MockWebSocket.CONNECTING;
  onopen: ((e?: unknown) => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: ((e?: unknown) => void) | null = null;
  onerror: ((e?: unknown) => void) | null = null;
  sent: string[] = [];

  constructor(public url: string) {
    MockWebSocket.instances.push(this);
  }
  send(d: string) { this.sent.push(d); }
  close() { this.readyState = MockWebSocket.CLOSED; this.onclose?.(); }
  serverOpen() { this.readyState = MockWebSocket.OPEN; this.onopen?.(); }
  serverMsg(obj: unknown) { this.onmessage?.({ data: JSON.stringify(obj) }); }
  frames(type: string) {
    return this.sent.map((raw) => JSON.parse(raw) as Record<string, unknown>).filter((f) => f.type === type);
  }
}

interface WsClientForTest {
  connect: (gatewayUrl: string, tokenProvider: () => Promise<string>) => void;
  disconnect: () => void;
  subscribe: (channel: string, handler: ChannelHandler, requestSnapshot?: boolean) => () => void;
  onReconnected: (listener: () => void) => () => void;
  status: WsConnectionStatus;
}

let wsClient: WsClientForTest;
let realWebSocket: unknown;
const flush = () => vi.advanceTimersByTimeAsync(0);

async function openSession(sessionId: string) {
  await flush();
  const ws = MockWebSocket.instances.at(-1)!;
  ws.serverOpen();
  ws.serverMsg({ v: 1, type: 'hello', payload: { sessionId, heartbeatMs: 30000 } });
  return ws;
}

beforeEach(async () => {
  vi.useFakeTimers();
  MockWebSocket.instances = [];
  realWebSocket = (globalThis as unknown as { WebSocket: unknown }).WebSocket;
  (globalThis as unknown as { WebSocket: unknown }).WebSocket = MockWebSocket;
  vi.resetModules();
  ({ wsClient } = await import('../ws-client'));
});

afterEach(() => {
  wsClient.disconnect();
  vi.useRealTimers();
  vi.restoreAllMocks();
  (globalThis as unknown as { WebSocket: unknown }).WebSocket = realWebSocket;
});

describe('goaway (connection cap)', () => {
  it('does not latch the client off: it reconnects after the retry-after and re-announces its channels', async () => {
    wsClient.subscribe('workflow:run:r1', vi.fn());
    wsClient.connect('ws://gw', async () => 'tok');
    await flush();
    // What the gateway actually does at the cap: open, then goaway INSTEAD of hello.
    const ws1 = MockWebSocket.instances.at(-1)!;
    ws1.serverOpen();
    ws1.serverMsg({ v: 1, type: 'goaway', ts: Date.now(),
      payload: { reason: 'max_connections', limit: 10, retryAfterMs: 30000 } });
    expect(wsClient.status).toBe('reconnecting');

    await vi.advanceTimersByTimeAsync(30000 + 5000); // retry-after + max jitter
    const ws2 = await openSession('s2');

    expect(ws2).not.toBe(ws1);
    expect(wsClient.status).toBe('connected');
    expect(ws2.frames('subscribe').map((f) => f.channel)).toContain('workflow:run:r1');
  });

  it('stays "reconnecting" through the retry, so the outage stays visible while the new socket comes up', async () => {
    wsClient.connect('ws://gw', async () => 'tok');
    await flush();
    const refused = MockWebSocket.instances.at(-1)!;
    refused.serverOpen();
    refused.serverMsg({ v: 1, type: 'goaway', ts: Date.now(), payload: { reason: 'max_connections', retryAfterMs: 30000 } });

    await vi.advanceTimersByTimeAsync(35000); // the retry opened a socket, no hello yet
    expect(MockWebSocket.instances.length).toBe(2);
    expect(wsClient.status).toBe('reconnecting');
  });

  it('refocusing the tab does not jump the retry-after the server asked for', async () => {
    wsClient.connect('ws://gw', async () => 'tok');
    const ws1 = await openSession('s1');
    ws1.serverMsg({ v: 1, type: 'goaway', ts: Date.now(), payload: { reason: 'max_connections', retryAfterMs: 30000 } });
    const socketsBefore = MockWebSocket.instances.length;

    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
    document.dispatchEvent(new Event('visibilitychange'));
    window.dispatchEvent(new Event('online'));
    await flush();

    expect(MockWebSocket.instances.length).toBe(socketsBefore);
    // ...and does not reset the backoff either: the scheduled retry still reports a REconnection.
    await vi.advanceTimersByTimeAsync(35000);
    expect(MockWebSocket.instances.length).toBe(socketsBefore + 1);
    expect(wsClient.status).toBe('reconnecting');
    delete (document as unknown as { visibilityState?: unknown }).visibilityState;
  });

  it('falls back to a default delay when the server gives none (a gateway older than this client)', async () => {
    wsClient.connect('ws://gw', async () => 'tok');
    const ws1 = await openSession('s1');
    ws1.serverMsg({ v: 1, type: 'goaway', ts: Date.now(), payload: { reason: 'max_connections' } });
    const socketsBefore = MockWebSocket.instances.length;

    await vi.advanceTimersByTimeAsync(29000);
    expect(MockWebSocket.instances.length).toBe(socketsBefore);
    await vi.advanceTimersByTimeAsync(6000);
    expect(MockWebSocket.instances.length).toBe(socketsBefore + 1);
  });
});

describe('onReconnected', () => {
  it('fires when a session is re-established, never for the first one', async () => {
    const listener = vi.fn();
    wsClient.onReconnected(listener);
    wsClient.connect('ws://gw', async () => 'tok');
    const ws1 = await openSession('s1');
    expect(listener).not.toHaveBeenCalled();

    ws1.close(); // the server dropped us
    await vi.advanceTimersByTimeAsync(2000);
    await openSession('s2');

    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('fires only once the server has answered every re-subscribe, so a re-read cannot miss a change', async () => {
    const listener = vi.fn();
    wsClient.subscribe('conversation:c1', vi.fn());
    wsClient.subscribe('workflow:run:r1', vi.fn());
    wsClient.connect('ws://gw', async () => 'tok');
    const ws1 = await openSession('s1');
    wsClient.onReconnected(listener);

    ws1.close();
    await vi.advanceTimersByTimeAsync(2000);
    const ws2 = await openSession('s2');
    const [conv, run] = ws2.frames('subscribe');
    expect(conv.payload).toEqual({ requestSnapshot: true });
    expect(listener).not.toHaveBeenCalled(); // frames sent, nothing confirmed yet

    ws2.serverMsg({ v: 1, type: 'subscribed', ref: conv.id, ts: Date.now(), payload: {} });
    expect(listener).not.toHaveBeenCalled();
    // A refusal is an answer too.
    ws2.serverMsg({ v: 1, type: 'error', ref: run.id, ts: Date.now(), payload: { message: 'Access denied to channel: workflow:run:r1' } });
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('a server that never answers a re-subscribe still gets the re-read when that watchdog gives up', async () => {
    const listener = vi.fn();
    wsClient.subscribe('conversation:c1', vi.fn());
    wsClient.connect('ws://gw', async () => 'tok');
    const ws1 = await openSession('s1');
    wsClient.onReconnected(listener);

    ws1.close();
    await vi.advanceTimersByTimeAsync(2000);
    await openSession('s2');
    expect(listener).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(30000); // subscribe watchdog
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('a page whose FIRST socket was refused still resyncs once it gets in (the gateway sends goaway instead of hello)', async () => {
    const listener = vi.fn();
    wsClient.onReconnected(listener);
    wsClient.connect('ws://gw', async () => 'tok');
    await flush();
    const refused = MockWebSocket.instances.at(-1)!;
    refused.serverOpen();
    refused.serverMsg({ v: 1, type: 'goaway', ts: Date.now(), payload: { reason: 'max_connections', retryAfterMs: 30000 } });

    await vi.advanceTimersByTimeAsync(35000);
    await openSession('s1');

    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('a new page lifetime after disconnect() (logout) is a first session again, not a resync', async () => {
    const listener = vi.fn();
    wsClient.onReconnected(listener);
    wsClient.connect('ws://gw', async () => 'tok');
    await openSession('s1');
    wsClient.disconnect();

    wsClient.connect('ws://gw', async () => 'tok');
    await openSession('s2');

    expect(listener).not.toHaveBeenCalled();
  });
});

describe('subscribe refused for capacity', () => {
  it('is announced again as soon as another channel frees a slot', async () => {
    wsClient.connect('ws://gw', async () => 'tok');
    const ws = await openSession('s1');
    const offAgents = wsClient.subscribe('agent:activity:a1', vi.fn());
    wsClient.subscribe('conversation:c1', vi.fn());
    const [agentSub, convSub] = ws.frames('subscribe');
    ws.serverMsg({ v: 1, type: 'subscribed', ref: agentSub.id, ts: Date.now(), payload: {} });
    ws.serverMsg({ v: 1, type: 'error', ref: convSub.id, ts: Date.now(),
      payload: { message: 'Max subscriptions reached', code: 'max_subscriptions' } });

    offAgents();

    const again = ws.frames('subscribe').filter((f) => f.channel === 'conversation:c1');
    expect(again).toHaveLength(2);
    expect(ws.frames('unsubscribe').map((f) => f.channel)).toEqual(['agent:activity:a1']);
  });

  it('once the refused channel is finally accepted, the page is told to re-read what it missed', async () => {
    const listener = vi.fn();
    wsClient.onReconnected(listener);
    wsClient.connect('ws://gw', async () => 'tok');
    const ws = await openSession('s1');
    const offAgents = wsClient.subscribe('agent:activity:a1', vi.fn());
    wsClient.subscribe('conversation:c1', vi.fn());
    const [agentSub, convSub] = ws.frames('subscribe');
    ws.serverMsg({ v: 1, type: 'subscribed', ref: agentSub.id, ts: Date.now(), payload: {} });
    ws.serverMsg({ v: 1, type: 'error', ref: convSub.id, ts: Date.now(),
      payload: { message: 'Max subscriptions reached', code: 'max_subscriptions' } });

    offAgents();
    const retry = ws.frames('subscribe').filter((f) => f.channel === 'conversation:c1').at(-1)!;
    expect(listener).not.toHaveBeenCalled();
    ws.serverMsg({ v: 1, type: 'subscribed', ref: retry.id, ts: Date.now(), payload: {} });

    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('a channel refused for ACCESS is not retried (it would loop)', async () => {
    wsClient.connect('ws://gw', async () => 'tok');
    const ws = await openSession('s1');
    const offOther = wsClient.subscribe('agent:activity:a1', vi.fn());
    wsClient.subscribe('conversation:not-mine', vi.fn());
    const [, denied] = ws.frames('subscribe');
    ws.serverMsg({ v: 1, type: 'error', ref: denied.id, ts: Date.now(),
      payload: { message: 'Access denied to channel: conversation:not-mine' } });

    offOther();

    expect(ws.frames('subscribe').filter((f) => f.channel === 'conversation:not-mine')).toHaveLength(1);
  });

  it('dropping the refused channel itself sends no unsubscribe for a channel the server never held', async () => {
    wsClient.connect('ws://gw', async () => 'tok');
    const ws = await openSession('s1');
    const off = wsClient.subscribe('conversation:c1', vi.fn());
    const [sub] = ws.frames('subscribe');
    ws.serverMsg({ v: 1, type: 'error', ref: sub.id, ts: Date.now(),
      payload: { message: 'Max subscriptions reached', code: 'max_subscriptions' } });

    off();

    expect(ws.frames('unsubscribe')).toHaveLength(0);
  });
});
