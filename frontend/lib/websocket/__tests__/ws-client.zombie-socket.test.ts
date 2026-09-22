// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { ChannelHandler, WsConnectionStatus } from '../ws-types';

/**
 * A subscription must never be lost in silence.
 *
 * A browser does not always report a dead socket: the TCP connection goes away, no
 * `close` event fires, and the client keeps reporting `connected`. Every decision this
 * module used to take on that belief - send a subscribe frame, recover on refocus,
 * recover when the network returns - therefore took the WRONG branch precisely when it
 * mattered, and `send()` dropped the frame with no error and no retry. The only repair
 * was `resubscribeAll()` on the next `hello`, and no hello was coming, because nothing
 * had noticed. The heartbeat watchdog needs heartbeatMs * 2.5 (75 s) to get there.
 *
 * Prod, 2026-09-17: a chat turn published 80 seconds of content, tool events and a
 * question card into a conversation channel with ZERO subscribers, because the tab that
 * sent the message never got its subscribe frame out. The screen looked frozen; a manual
 * reload was the only thing that fixed it.
 *
 * These tests put the client in exactly that state - an OPEN-then-silently-dead socket -
 * and pin that a subscription made in it still reaches a server.
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
  send(d: string) {
    if (this.deaf) return;
    this.sent.push(d);
    // A socket that dies part-way through a burst: the browser accepts this frame and
    // then reports the socket closed, so the NEXT send cannot leave.
    if (this.dieAfterFrames !== null && this.sent.length >= this.dieAfterFrames) {
      this.readyState = MockWebSocket.CLOSED;
    }
  }
  dieAfterFrames: number | null = null;
  close() {
    this.readyState = MockWebSocket.CLOSED;
    this.onclose?.();
  }
  serverOpen() { this.readyState = MockWebSocket.OPEN; this.onopen?.(); }
  serverMsg(obj: unknown) { this.onmessage?.({ data: JSON.stringify(obj) }); }
  /**
   * One failure mode under test: the browser has noticed the socket is gone but the
   * `close` event has not been processed yet (a throttled or frozen tab), so the client
   * still believes it is connected while `send()` can no longer deliver anything.
   */
  dieSilently() { this.readyState = MockWebSocket.CLOSED; }
  /**
   * The other, nastier one: the peer is gone and NOTHING locally knows. readyState stays
   * OPEN, `send()` reports success, and the bytes go nowhere. Only the absence of an
   * answer can reveal it.
   */
  dieWhileClaimingOpen() { this.deaf = true; }
  /** True once the peer is gone: frames are accepted and dropped, like a real dead TCP. */
  deaf = false;
}

interface WsClientForTest {
  connect: (
    gatewayUrl: string,
    tokenProvider: () => Promise<string>,
    activeOrgProvider?: () => string | null,
  ) => void;
  disconnect: () => void;
  subscribe: (channel: string, handler: ChannelHandler, requestSnapshot?: boolean) => () => void;
  sendAction: (action: string, data: unknown) => Promise<unknown>;
  refreshToken: () => Promise<void>;
  status: WsConnectionStatus;
}

let wsClient: WsClientForTest;

const TOKEN = async () => 'tok';
const CHANNEL = 'conversation:a017fa4e-8781-40e0-9422-ef62ca617749';
const flush = () => vi.advanceTimersByTimeAsync(0);

function subscribeFramesFor(ws: MockWebSocket, channel: string) {
  return ws.sent
    .map((raw) => JSON.parse(raw) as Record<string, unknown>)
    .filter((f) => f.type === 'subscribe' && f.channel === channel);
}

function framesOfType(ws: MockWebSocket, type: string) {
  return ws.sent
    .map((raw) => JSON.parse(raw) as Record<string, unknown>)
    .filter((f) => f.type === type);
}

/** Answer a subscribe frame the way the gateway does, so its watchdog settles. */
function ackSubscribe(ws: MockWebSocket, frame: Record<string, unknown>) {
  ws.serverMsg({ v: 1, type: 'subscribed', ref: frame.id, ts: Date.now(),
    payload: { channel: frame.channel } });
}

async function bringUp() {
  wsClient.connect('ws://gw', TOKEN);
  await flush();
  const ws = MockWebSocket.instances.at(-1)!;
  ws.serverOpen();
  ws.serverMsg({ v: 1, type: 'hello', payload: { sessionId: 's1', heartbeatMs: 30000 } });
  return ws;
}

/** Complete the handshake on the newest socket the client opened. */
async function completeHandshakeOnLatest() {
  const ws = MockWebSocket.instances.at(-1)!;
  ws.serverOpen();
  ws.serverMsg({ v: 1, type: 'hello', payload: { sessionId: 's2', heartbeatMs: 30000 } });
  return ws;
}

// Globals these tests replace, restored afterwards: `vi.restoreAllMocks()` does not undo
// an assignment or an Object.defineProperty, and a test that leaves document.visibilityState
// pinned makes the NEXT one depend on the order it ran in.
let realWebSocket: unknown;
let realVisibility: PropertyDescriptor | undefined;

beforeEach(async () => {
  vi.useFakeTimers();
  MockWebSocket.instances = [];
  realWebSocket = (globalThis as unknown as { WebSocket: unknown }).WebSocket;
  realVisibility = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
  (globalThis as unknown as { WebSocket: unknown }).WebSocket = MockWebSocket;
  vi.resetModules();
  ({ wsClient } = await import('../ws-client'));
});

afterEach(() => {
  wsClient.disconnect();
  vi.useRealTimers();
  vi.restoreAllMocks();
  (globalThis as unknown as { WebSocket: unknown }).WebSocket = realWebSocket;
  delete (document as unknown as { visibilityState?: unknown }).visibilityState;
  if (realVisibility) Object.defineProperty(Document.prototype, 'visibilityState', realVisibility);
});

describe('subscribing on a socket that died without saying so', () => {
  it('reconnects immediately instead of waiting for the 75s heartbeat watchdog', async () => {
    const ws1 = await bringUp();
    const socketsBefore = MockWebSocket.instances.length;
    ws1.dieSilently();

    wsClient.subscribe(CHANNEL, vi.fn());
    await flush();

    expect(MockWebSocket.instances.length).toBeGreaterThan(socketsBefore);
  });

  it('delivers the subscription on the new socket, so the channel is not left empty', async () => {
    const ws1 = await bringUp();
    ws1.dieSilently();

    wsClient.subscribe(CHANNEL, vi.fn(), true);
    await flush();

    const ws2 = await completeHandshakeOnLatest();
    // A genuinely NEW socket, not the dead one: asserting only "a frame exists somewhere"
    // would pass on the broken build, where the zombie is the only socket there is.
    expect(ws2).not.toBe(ws1);
    // What the incident lost: this frame. It must reach a live socket, with the snapshot
    // request, so the events published while nobody was listening are replayed.
    const frames = subscribeFramesFor(ws2, CHANNEL);
    expect(frames).toHaveLength(1);
    expect(frames[0].payload).toEqual({ requestSnapshot: true });
    // And nothing was written into the dead socket and lost.
    expect(subscribeFramesFor(ws1, CHANNEL)).toHaveLength(0);
  });

  it('refocusing the tab rebuilds the connection (the stale status used to block it)', async () => {
    const ws1 = await bringUp();
    wsClient.subscribe(CHANNEL, vi.fn());
    const socketsBefore = MockWebSocket.instances.length;
    ws1.dieSilently();

    // The old guard read `_status !== 'connected'`, which is false here, so refocusing
    // a tab whose socket had quietly died did nothing at all.
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    await flush();

    expect(MockWebSocket.instances.length).toBeGreaterThan(socketsBefore);
  });

  it('the network coming back rebuilds the connection too', async () => {
    const ws1 = await bringUp();
    const socketsBefore = MockWebSocket.instances.length;
    ws1.dieSilently();

    window.dispatchEvent(new Event('online'));
    await flush();

    expect(MockWebSocket.instances.length).toBeGreaterThan(socketsBefore);
  });

  it('an action sent into the void fails at once with the real reason, not a 30s timeout', async () => {
    const ws1 = await bringUp();
    ws1.dieSilently();

    const rejection = expect(wsClient.sendAction('task.claim', { id: 'x' }))
      .rejects.toThrow('WebSocket not connected');
    await flush();
    await rejection;
  });

  it('does not jump an already-scheduled backoff, however many subscriptions arrive', async () => {
    // Gateway unreachable: the client is in backoff. A screen that mounts and unmounts
    // subscribers in a loop must not turn each attempt into an immediate new socket -
    // that is a retry storm against a server that is already down.
    const ws1 = await bringUp();
    ws1.close(); // a REPORTED drop: onclose fires, so the backoff timer is armed
    await flush();
    const socketsAfterDrop = MockWebSocket.instances.length;

    for (let i = 0; i < 5; i++) {
      wsClient.subscribe(`${CHANNEL}-${i}`, vi.fn());
      await flush();
    }

    expect(MockWebSocket.instances.length).toBe(socketsAfterDrop);
  });

  it('subscribing before the app has connected does not start a connection of its own', async () => {
    // React runs child effects BEFORE the provider's, so the first subscription of a page
    // lands here before connect() has supplied a token provider. Opening a socket now
    // would fail on the missing provider and leave the client in 'reconnecting' with a
    // backoff running, before the app had even tried to connect once.
    wsClient.subscribe(CHANNEL, vi.fn(), true);
    await flush();

    expect(MockWebSocket.instances).toHaveLength(0);
    expect(wsClient.status).toBe('disconnected');

    // And the channel is not lost: the real connect announces it on hello.
    const ws = await bringUp();
    expect(subscribeFramesFor(ws, CHANNEL)).toHaveLength(1);
  });

  it('an OPEN socket whose peer is gone is caught by the missing answer, not left for 75s', async () => {
    // The worst shape of a dead connection: readyState still OPEN, send() reports success,
    // the bytes go nowhere. Nothing local can tell - only the fact that the server never
    // answers. The gateway acks every subscribe, so an unanswered one IS the signal.
    const ws1 = await bringUp();
    const socketsBefore = MockWebSocket.instances.length;
    ws1.dieWhileClaimingOpen();

    wsClient.subscribe(CHANNEL, vi.fn(), true);
    await flush();
    expect(MockWebSocket.instances.length).toBe(socketsBefore); // nothing suspicious yet

    // Past the ack timeout, and still well under the 75s heartbeat watchdog - being
    // faster than that watchdog is this mechanism's entire reason to exist.
    await vi.advanceTimersByTimeAsync(31000);
    await vi.advanceTimersByTimeAsync(2000); // the backoff-governed first attempt

    expect(MockWebSocket.instances.length).toBeGreaterThan(socketsBefore);
  });

  it('an answered subscribe never triggers a reconnect, however long the session lasts', async () => {
    const ws1 = await bringUp();
    const socketsBefore = MockWebSocket.instances.length;

    wsClient.subscribe(CHANNEL, vi.fn(), true);
    ackSubscribe(ws1, subscribeFramesFor(ws1, CHANNEL)[0]);

    await vi.advanceTimersByTimeAsync(60000);

    expect(MockWebSocket.instances.length).toBe(socketsBefore);
  });

  it('a REFUSED subscribe is an answer too, so a forbidden channel cannot loop', async () => {
    // The gateway replies `error` when a channel is not readable by this user. That is a
    // decision, not silence: reconnecting would re-ask forever and never be allowed.
    const ws1 = await bringUp();
    const socketsBefore = MockWebSocket.instances.length;

    wsClient.subscribe('conversation:someone-elses', vi.fn());
    const frame = subscribeFramesFor(ws1, 'conversation:someone-elses')[0];
    ws1.serverMsg({ v: 1, type: 'error', ref: frame.id, ts: Date.now(),
      payload: { error: 'Access denied to channel: conversation:someone-elses' } });

    await vi.advanceTimersByTimeAsync(60000);

    expect(MockWebSocket.instances.length).toBe(socketsBefore);
  });

  it('subscribing between OPEN and hello sends ONE frame, not two', async () => {
    // resubscribeAll announces every tracked channel on hello. Sending here as well would
    // double the frame and, on the gateway, the snapshot replay it performs for each one.
    wsClient.connect('ws://gw', TOKEN);
    await flush();
    const ws = MockWebSocket.instances.at(-1)!;
    ws.serverOpen(); // OPEN, but no hello yet

    wsClient.subscribe(CHANNEL, vi.fn(), true);
    await flush();
    expect(subscribeFramesFor(ws, CHANNEL)).toHaveLength(0);

    ws.serverMsg({ v: 1, type: 'hello', payload: { sessionId: 's1', heartbeatMs: 30000 } });
    expect(subscribeFramesFor(ws, CHANNEL)).toHaveLength(1);
  });

  it('a recovery opened while the old socket is CLOSING survives that socket dying', async () => {
    // The old socket's late onclose used to run handleDisconnect on `this.ws`, which by
    // then is the REPLACEMENT - tearing down the connection just opened and falling back
    // into backoff, i.e. slower than doing nothing at all.
    const ws1 = await bringUp();
    ws1.readyState = MockWebSocket.CLOSING;

    wsClient.subscribe(CHANNEL, vi.fn(), true);
    await flush();
    const ws2 = MockWebSocket.instances.at(-1)!;
    expect(ws2).not.toBe(ws1);

    ws1.onclose?.(); // the old socket's last breath, after the replacement exists
    ws2.serverOpen();
    ws2.serverMsg({ v: 1, type: 'hello', payload: { sessionId: 's2', heartbeatMs: 30000 } });

    expect(wsClient.status).toBe('connected');
    expect(subscribeFramesFor(ws2, CHANNEL)).toHaveLength(1);
  });

  it('a subscribe after the server said goaway does not resurrect the connection', async () => {
    // goaway is how the gateway sheds a user who is at their connection cap. Reconnecting
    // into it is precisely the hammering it asked us to stop.
    const ws1 = await bringUp();
    ws1.serverMsg({ v: 1, type: 'goaway', ts: Date.now(), payload: {} });
    const socketsBefore = MockWebSocket.instances.length;

    wsClient.subscribe(CHANNEL, vi.fn());
    await vi.advanceTimersByTimeAsync(60000);

    expect(MockWebSocket.instances.length).toBe(socketsBefore);
    expect(wsClient.status).toBe('disconnected');
  });

  it('an action on a healthy socket still resolves on its ack', async () => {
    // The new early-return must not have broken the only caller (step-by-step workflow
    // execution): the success path has to keep working exactly as before.
    const ws1 = await bringUp();

    const pending = wsClient.sendAction('workflow.step', { runId: 'r1' });
    const frame = framesOfType(ws1, 'action')[0];
    ws1.serverMsg({ v: 1, type: 'action.ack', ref: frame.id, ts: Date.now(), payload: { ok: true } });

    await expect(pending).resolves.toEqual({ ok: true });
  });

  it('unsubscribe still reaches a live server, and a lost one does not force a reconnect', async () => {
    const ws1 = await bringUp();
    const unsubscribe = wsClient.subscribe(CHANNEL, vi.fn());
    ackSubscribe(ws1, subscribeFramesFor(ws1, CHANNEL)[0]);

    unsubscribe();
    expect(framesOfType(ws1, 'unsubscribe')).toHaveLength(1);

    // On a dead socket the frame is lost, and that is fine: a reconnect re-announces only
    // what is still tracked. What must NOT happen is a reconnect for an unsubscribe.
    const handler = vi.fn();
    const unsubscribe2 = wsClient.subscribe(`${CHANNEL}-2`, handler);
    ackSubscribe(ws1, subscribeFramesFor(ws1, `${CHANNEL}-2`)[0]);
    ws1.dieSilently();
    const socketsBefore = MockWebSocket.instances.length;
    unsubscribe2();
    await flush();
    expect(MockWebSocket.instances.length).toBe(socketsBefore);
  });

  it('a watchdog never outlives the socket it was armed for', async () => {
    // The nastiest shape of this feature turning on its owner: channel A's watchdog is
    // armed, the socket dies, a recovery opens a NEW socket and everything is acked on
    // it - and then A's orphan timer fires and tears down the healthy connection.
    const ws1 = await bringUp();
    // NOT acked: an answered subscribe has no watchdog left to orphan, so acking here
    // would make this test pass with the cleanup deleted - which is exactly what it is
    // supposed to prevent.
    wsClient.subscribe(CHANNEL, vi.fn(), true);

    ws1.dieSilently();
    wsClient.subscribe(`${CHANNEL}-2`, vi.fn(), true);
    await flush();
    const ws2 = await completeHandshakeOnLatest();
    for (const f of [...subscribeFramesFor(ws2, CHANNEL), ...subscribeFramesFor(ws2, `${CHANNEL}-2`)]) {
      ackSubscribe(ws2, f);
    }
    const socketsAfterRecovery = MockWebSocket.instances.length;

    // Twice the ack timeout, still under the 75s heartbeat watchdog, which would
    // legitimately reconnect a server that has stopped pinging and is not the subject here.
    await vi.advanceTimersByTimeAsync(70000);

    expect(MockWebSocket.instances.length).toBe(socketsAfterRecovery);
    expect(wsClient.status).toBe('connected');
  });

  it('a watchdog does not outlive a socket that closed normally either', async () => {
    // The second place watchdogs are cleared: an ordinary onclose goes through cleanup().
    // Both sites are load-bearing and each needs its own scenario - with only the silent
    // death covered, deleting the cleanup() call leaves the whole suite green.
    const ws1 = await bringUp();
    wsClient.subscribe(CHANNEL, vi.fn(), true);

    ws1.close(); // reported close: onclose fires, backoff arms
    await vi.advanceTimersByTimeAsync(2000);
    const ws2 = await completeHandshakeOnLatest();
    for (const f of subscribeFramesFor(ws2, CHANNEL)) ackSubscribe(ws2, f);
    const socketsAfterRecovery = MockWebSocket.instances.length;

    await vi.advanceTimersByTimeAsync(70000);

    expect(MockWebSocket.instances.length).toBe(socketsAfterRecovery);
    expect(wsClient.status).toBe('connected');
  });

  it('an unanswered subscribe backs off instead of reconnecting forever', async () => {
    // A gateway that completes the handshake but answers no subscribe is a SLOW service,
    // not necessarily a dead socket - the gateway authorizes each channel with an HTTP
    // call. Reconnecting on a fixed timer would re-ask that same service for every
    // channel, and re-trigger a snapshot re-broadcast to everyone on the conversation,
    // once per cycle, for as long as it stays slow.
    await bringUp();
    wsClient.subscribe(CHANNEL, vi.fn(), true);

    // Faithful to the scenario: every replacement connects and says hello, and every
    // subscribe on it goes unanswered. A test that never brings the replacement up
    // measures nothing, because the second cycle never starts.
    for (let i = 0; i < 12; i++) {
      await vi.advanceTimersByTimeAsync(31000);
      const latest = MockWebSocket.instances.at(-1)!;
      if (latest.readyState === MockWebSocket.CONNECTING) {
        latest.serverOpen();
        latest.serverMsg({ v: 1, type: 'hello', payload: { sessionId: `s${i}`, heartbeatMs: 30000 } });
      }
    }

    // Bounded, and bounded by the ack-timeout counter rather than by the backoff: every
    // successful handshake resets the attempt counter, so the exponent never grows on this
    // path. After MAX_CONSECUTIVE_ACK_TIMEOUTS ignored sessions the client stops rebuilding
    // on that signal - the connection is not the problem. The few remaining reconnects in
    // this window come from the heartbeat watchdog, legitimately: a server that answers
    // nothing here also sends no pings. Unbounded, this scenario produced one reconnect
    // every ~30s forever.
    expect(MockWebSocket.instances.length).toBeLessThanOrEqual(8);
  });

  it('re-announcing channels stops at the first frame that cannot leave', async () => {
    // resubscribeAll is the safety net the subscribe path leans on. If the socket dies
    // part-way through the loop, the channels after it would never be announced and
    // nothing would notice - the same silent drop, one level up.
    const ws1 = await bringUp();
    for (const id of ['a', 'b', 'c']) {
      wsClient.subscribe(`${CHANNEL}-${id}`, vi.fn(), true);
      ackSubscribe(ws1, subscribeFramesFor(ws1, `${CHANNEL}-${id}`)[0]);
    }

    ws1.dieSilently();
    window.dispatchEvent(new Event('online'));
    await flush();
    const ws2 = MockWebSocket.instances.at(-1)!;
    ws2.serverOpen();
    ws2.dieAfterFrames = 1; // the first re-announce leaves, then the socket is gone
    const socketsBefore = MockWebSocket.instances.length;
    ws2.serverMsg({ v: 1, type: 'hello', payload: { sessionId: 's2', heartbeatMs: 30000 } });
    await flush();

    // Exactly one channel made it out; the loop did not keep writing into a dead socket.
    expect(ws2.sent.filter((f) => JSON.parse(f).type === 'subscribe')).toHaveLength(1);

    // Nothing was opened SYNCHRONOUSLY, and that is the point. This runs inside
    // handleHello, which has just set the status to 'connected' and reset the backoff, so
    // recovering from here without the ordinary disconnect path reconnects with NO delay:
    // each replacement says hello, fails the same way and recurses, as fast as the network
    // allows, until the gateway's per-user cap answers goaway and latches this client off.
    // Measured at 42 sockets with zero elapsed time before the fix.
    expect(MockWebSocket.instances.length).toBe(socketsBefore);
    expect(wsClient.status).toBe('reconnecting');

    // Let every replacement come up and die the same way, and count. With the backoff in
    // the path, the count tracks elapsed time instead of loop iterations.
    for (let i = 0; i < 20; i++) {
      await vi.advanceTimersByTimeAsync(1000);
      const latest = MockWebSocket.instances.at(-1)!;
      if (latest.readyState !== MockWebSocket.CONNECTING) continue;
      latest.serverOpen();
      latest.dieAfterFrames = 1;
      latest.serverMsg({ v: 1, type: 'hello', payload: { sessionId: `s${i}`, heartbeatMs: 30000 } });
      await flush();
    }
    expect(MockWebSocket.instances.length).toBeLessThanOrEqual(socketsBefore + 6);
  });

  it('unsubscribe uses the socket, not the belief: it is sent before hello too', async () => {
    // The old gate was `_status === 'connected'`, which is false between OPEN and hello.
    wsClient.connect('ws://gw', TOKEN);
    await flush();
    const ws = MockWebSocket.instances.at(-1)!;
    ws.serverOpen();
    const unsubscribe = wsClient.subscribe(CHANNEL, vi.fn());

    unsubscribe();

    expect(framesOfType(ws, 'unsubscribe')).toHaveLength(1);
  });

  it('an action that cannot be sent also rebuilds the connection, not just rejects', async () => {
    const ws1 = await bringUp();
    const socketsBefore = MockWebSocket.instances.length;
    ws1.dieSilently();

    await expect(wsClient.sendAction('task.claim', { id: 'x' })).rejects.toThrow();
    await flush();

    // Rejecting alone would leave the tab on a dead socket until something else noticed.
    expect(MockWebSocket.instances.length).toBeGreaterThan(socketsBefore);
  });

  it('refreshing the token on a dead socket is a no-op, not a wasted fetch', async () => {
    const ws1 = await bringUp();
    let tokenFetches = 0;
    wsClient.connect('ws://gw', async () => { tokenFetches += 1; return 'tok'; });
    await flush();
    const ws2 = MockWebSocket.instances.at(-1)!;
    ws2.serverOpen();
    ws2.serverMsg({ v: 1, type: 'hello', payload: { sessionId: 's2', heartbeatMs: 30000 } });
    const fetchesAfterConnect = tokenFetches;
    ws2.dieSilently();

    await wsClient.refreshToken();

    expect(tokenFetches).toBe(fetchesAfterConnect);
    expect(framesOfType(ws2, 'auth.refresh')).toHaveLength(0);
    void ws1;
  });

  it('a healthy socket is untouched: one subscribe frame, no reconnect', async () => {
    const ws1 = await bringUp();
    const socketsBefore = MockWebSocket.instances.length;

    wsClient.subscribe(CHANNEL, vi.fn());
    await flush();

    expect(subscribeFramesFor(ws1, CHANNEL)).toHaveLength(1);
    expect(MockWebSocket.instances.length).toBe(socketsBefore);
  });

  it('while a socket is still CONNECTING, subscribing waits for hello rather than churning', async () => {
    // A frame cannot be sent yet, but the connection is coming up on its own and
    // resubscribeAll will announce the channel. Reconnecting here would kill it.
    wsClient.connect('ws://gw', TOKEN);
    await flush();
    const connecting = MockWebSocket.instances.at(-1)!;
    expect(connecting.readyState).toBe(MockWebSocket.CONNECTING);
    const socketsBefore = MockWebSocket.instances.length;

    wsClient.subscribe(CHANNEL, vi.fn(), true);
    await flush();

    expect(MockWebSocket.instances.length).toBe(socketsBefore);

    connecting.serverOpen();
    connecting.serverMsg({ v: 1, type: 'hello', payload: { sessionId: 's1', heartbeatMs: 30000 } });
    expect(subscribeFramesFor(connecting, CHANNEL)).toHaveLength(1);
  });
});
