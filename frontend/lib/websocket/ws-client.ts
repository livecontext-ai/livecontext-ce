import type {
  WsEnvelope,
  WsConnectionStatus,
  ChannelHandler,
  HelloPayload,
  ChannelEventPayload,
  GoawayPayload,
  ErrorPayload,
} from './ws-types';

function isChannelEventPayload(payload: unknown): payload is ChannelEventPayload {
  if (!payload || typeof payload !== 'object') return false;
  const candidate = payload as Partial<ChannelEventPayload>;
  return (
    typeof candidate.v === 'number' &&
    typeof candidate.type === 'string' &&
    typeof candidate.id === 'string' &&
    typeof candidate.ts === 'number' &&
    Object.prototype.hasOwnProperty.call(candidate, 'payload')
  );
}

function normalizeChannelEventPayload(payload: unknown): unknown {
  if (!isChannelEventPayload(payload)) return payload;

  const innerPayload = payload.payload;
  if (
    innerPayload &&
    typeof innerPayload === 'object' &&
    !Array.isArray(innerPayload) &&
    typeof (innerPayload as { type?: unknown }).type !== 'string'
  ) {
    return { ...(innerPayload as Record<string, unknown>), type: payload.type };
  }

  return innerPayload;
}

/**
 * Singleton WebSocket client managing a single connection to the Gateway.
 *
 * Features:
 * - Channel-based pub/sub (subscribe/unsubscribe)
 * - Automatic reconnection with exponential backoff + jitter
 * - Heartbeat ping/pong
 * - Message deduplication (bounded set)
 * - Fire-and-forget actions with ack tracking
 */
class WebSocketClient {
  private ws: WebSocket | null = null;
  private _status: WsConnectionStatus = 'disconnected';
  private gatewayUrl = '';
  private tokenProvider: (() => Promise<string>) | null = null;

  // Channel subscriptions
  private channelHandlers: Map<string, Set<ChannelHandler>> = new Map();

  // Status listeners (for useSyncExternalStore)
  private statusListeners: Set<() => void> = new Set();

  // Resync listeners: told when a session is established AFTER an earlier one. Events
  // published while no session existed are gone for good (the gateway keeps no backlog),
  // so every surface that renders live state must re-read it from REST on this signal.
  private reconnectListeners: Set<() => void> = new Set();
  private hadSession = false;
  // The re-subscribe frames of the current resync, not answered yet. Listeners are told only
  // once this is empty: a REST re-read that ran before the server confirmed a subscription
  // could miss a change published in between.
  private pendingResyncIds: Set<string> | null = null;
  private everConnected = false;
  private lastFailure: 'goaway' | 'socket' | null = null;

  // Reconnection - backoff only. Reconnection is attempted INDEFINITELY (capped
  // backoff + jitter) so a real-time client always recovers from transient drops;
  // `intentionalClose` is the ONLY thing that stops it (logout / explicit teardown).
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private static readonly MAX_BACKOFF_EXPONENT = 5; // 2^5 * 1s = 32s → capped to 30s
  private intentionalClose = false;     // true only after disconnect(); blocks reconnects
  // Set by a goaway: the server asked us not to come back before this instant. Honoured by
  // every path that would otherwise reconnect at once (focus, network back).
  private goawayUntil = 0;
  private static readonly DEFAULT_GOAWAY_RETRY_MS = 30000;
  private static readonly GOAWAY_JITTER_MS = 5000;
  private connecting = false;           // guards the async token-fetch window in doConnect
  private lifecycleBound = false;       // online/visibility listeners attached once

  // Heartbeat - armed on every server ping; if the server stops pinging
  // (zombie TCP) we trigger a disconnect → reconnect after this timeout.
  // Multiplier 2.5x heartbeatMs leaves room for one missed ping + jitter.
  private pongTimer: ReturnType<typeof setTimeout> | null = null;
  private heartbeatMs: number = 30000;  // default; overridden by hello.heartbeatMs
  private static readonly HEARTBEAT_TIMEOUT_MULTIPLIER = 2.5;
  private static readonly AUTH_SUBPROTOCOL = 'lc.auth';
  private static readonly TOKEN_SUBPROTOCOL_PREFIX = 'lc.jwt.';
  private static readonly ORG_SUBPROTOCOL_PREFIX = 'lc.org.';

  // Subscribe frames whose answer has not arrived yet, keyed by frame id. A subscription
  // is only real once the server acknowledges it; see sendSubscribe.
  private pendingSubscribeAcks: Map<string, { timer: ReturnType<typeof setTimeout>; channel: string }> =
    new Map();
  // Channels the server refused because this socket holds too many. Still tracked (their
  // handlers stay registered) and announced again as soon as a slot frees up.
  private capacityRefused: Set<string> = new Set();
  // Subscribe frames that re-announce a channel refused for capacity. Their `subscribed`
  // answer ends a gap in that channel's events, which the page must re-read.
  private capacityRetryIds: Set<string> = new Set();
  /**
   * Unanswered subscribes since the last answered one. Survives reconnects on purpose:
   * the whole point is to notice that rebuilding the connection is not helping, and every
   * reconnect resets the attempt counter this would otherwise rely on.
   */
  private consecutiveAckTimeouts = 0;
  /**
   * How long to wait for the server's answer to a subscribe.
   *
   * Generous on purpose: the gateway authorizes a conversation channel with an HTTP call
   * to another service, so a slow answer is a slow service, not a dead socket. This cannot
   * be set above every plausible latency (the gateway's own client allows two minutes) and
   * still be useful, so it is sized to beat the heartbeat watchdog - the only thing that
   * used to notice - by a wide margin, and a false positive costs one backoff-governed
   * reconnect rather than a loop. See forceReconnect.
   */
  private static readonly SUBSCRIBE_ACK_TIMEOUT_MS = 30000;

  // Deduplication
  private messageDedup: Set<string> = new Set();
  private dedupMaxSize = 5000;

  // Pending action acks
  private pendingActions: Map<string, { resolve: (v: unknown) => void; reject: (e: Error) => void }> =
    new Map();

  // Session info
  private sessionId: string | null = null;

  get status(): WsConnectionStatus {
    return this._status;
  }

  /**
   * Optional provider returning the user's currently active organization id.
   * Threaded to the gateway via a WebSocket subprotocol so the WS session's
   * `organizationId` reflects the active workspace, not just the default.
   * Returns null/empty for personal scope.
   * PR25 R1 fix - see WsHandshakeAuthInterceptor.resolveActiveOrgId.
   */
  private activeOrgProvider: (() => string | null) | null = null;

  /**
   * Connect to the WebSocket gateway.
   */
  connect(
    gatewayUrl: string,
    tokenProvider: () => Promise<string>,
    activeOrgProvider?: () => string | null,
  ): void {
    this.gatewayUrl = gatewayUrl;
    this.tokenProvider = tokenProvider;
    this.activeOrgProvider = activeOrgProvider ?? null;
    // A fresh connect() MUST re-enable reconnection and reset the backoff window -
    // otherwise a prior disconnect() (or a string of failed attempts) would leave
    // the singleton permanently stuck and real-time events would never recover.
    this.intentionalClose = false;
    this.reconnectAttempt = 0;
    this.goawayUntil = 0;
    this.bindLifecycleListeners();
    this.doConnect();
  }

  /**
   * Disconnect and STOP reconnection. Use only for real teardown (logout / app
   * unmount). For a workspace switch use {@link reconnect} instead.
   */
  disconnect(): void {
    this.intentionalClose = true;
    this.hadSession = false; // the next connect() starts a new page lifetime, not a resync
    this.cleanup();
    this.setStatus('disconnected');
  }

  /**
   * Force a fresh connection while KEEPING reconnection enabled - e.g. the active
   * workspace changed, so the WS session must re-handshake with a new
   * `?activeOrg`. Unlike {@link disconnect} this never latches the client off.
   */
  reconnect(): void {
    this.intentionalClose = false;
    this.reconnectAttempt = 0;
    this.goawayUntil = 0;
    this.cleanup(); // drops the current socket (handlers nulled first → no onclose storm)
    this.doConnect();
  }

  /**
   * Subscribe to a channel. Returns an unsubscribe function.
   */
  subscribe(channel: string, handler: ChannelHandler, requestSnapshot?: boolean): () => void {
    let handlers = this.channelHandlers.get(channel);
    if (!handlers) {
      handlers = new Set();
      this.channelHandlers.set(channel, handlers);
    }

    const isNewChannel = handlers.size === 0;
    handlers.add(handler);

    console.log(`[WS:client] subscribe ch=${channel} new=${isNewChannel} status=${this._status} snapshot=${!!requestSnapshot} handlers=${handlers.size}`);

    // Announce the channel if this is the first handler for it.
    //
    // A subscription that never reaches the gateway is invisible: the server keeps
    // publishing to a channel with no subscriber and the page just sits there. In prod on
    // 2026-09-17 a chat turn published 80 seconds of content, tool events and a question
    // card into an empty channel because the tab that sent the message never got its
    // subscribe frame to the server; a manual reload was the only thing that fixed it.
    //
    // Three states, three different right answers. The previous single test
    // (`this._status === 'connected'`) got the third one wrong - it is the one that
    // matters, and the one it was silently wrong about:
    //  - socket OPEN and the session established: send now.
    //  - socket OPEN but no `hello` yet: send NOTHING. `resubscribeAll()` announces every
    //    tracked channel on hello, and sending here too would double the frame AND the
    //    snapshot replay the gateway performs for it.
    //  - socket not OPEN: the frame cannot leave. The status may still say 'connected'
    //    (a throttled or frozen tab processes `onclose` late), so this is also the moment
    //    the belief is proven wrong: rebuild the connection instead of dropping the frame
    //    silently and waiting up to 75 s for the heartbeat watchdog to notice.
    //
    // A frame that DOES leave is not yet a subscription - see the ack watchdog in
    // `sendSubscribe`, which covers the case where the socket is OPEN and the connection
    // is nevertheless dead.
    if (isNewChannel) {
      if (this.isSocketOpen) {
        if (this._status === 'connected') this.sendSubscribe(channel, requestSnapshot);
      } else {
        this.recoverStaleConnection();
      }
    }

    return () => {
      this.unsubscribe(channel, handler);
    };
  }

  /**
   * Unsubscribe a handler from a channel.
   */
  unsubscribe(channel: string, handler: ChannelHandler): void {
    const handlers = this.channelHandlers.get(channel);
    if (!handlers) return;

    handlers.delete(handler);

    if (handlers.size === 0) {
      this.channelHandlers.delete(channel);
      if (this.capacityRefused.delete(channel)) return; // the server never held it
      // Send unsubscribe message to server. Unlike subscribe, a lost frame here is
      // harmless (a reconnect re-subscribes only what is still tracked), so this one
      // does not force a reconnect - it just uses the socket rather than the belief.
      this.sendUnsubscribe(channel);
      this.retryCapacityRefused();
    }
  }

  /**
   * Send an action to the server. Returns a promise that resolves on ack.
   */
  async sendAction(action: string, data: unknown): Promise<unknown> {
    const id = crypto.randomUUID();
    const envelope: WsEnvelope = {
      v: 1,
      type: 'action',
      id,
      ts: Date.now(),
      payload: { action, data },
    };

    return new Promise((resolve, reject) => {
      this.pendingActions.set(id, { resolve, reject });
      if (!this.send(envelope)) {
        // The frame never left, so no ack can ever arrive. Fail now with the real reason
        // instead of making the caller wait 30 s for a timeout that blames the server,
        // and take the dropped frame as the signal to rebuild the connection.
        this.pendingActions.delete(id);
        this.recoverStaleConnection();
        reject(new Error('WebSocket not connected'));
        return;
      }

      // Timeout after 30s
      setTimeout(() => {
        if (this.pendingActions.has(id)) {
          this.pendingActions.delete(id);
          reject(new Error('Action timeout'));
        }
      }, 30000);
    });
  }

  /**
   * Refresh the auth token on an existing connection.
   */
  async refreshToken(): Promise<void> {
    // Socket state, not `_status`: with no open socket there is nothing to refresh ON,
    // and fetching a token to write into a dead one is pure waste. Returning here does
    // not repair anything - whatever reconnect eventually happens re-authenticates with a
    // fresh token, and until then this call is simply a no-op instead of a wasted fetch.
    if (!this.tokenProvider || !this.isSocketOpen) return;

    const token = await this.tokenProvider();
    const envelope: WsEnvelope = {
      v: 1,
      type: 'auth.refresh',
      id: crypto.randomUUID(),
      ts: Date.now(),
      payload: { token },
    };
    this.send(envelope);
  }

  /**
   * Subscribe to status changes (for useSyncExternalStore).
   */
  subscribeStatus(listener: () => void): () => void {
    this.statusListeners.add(listener);
    return () => this.statusListeners.delete(listener);
  }

  /**
   * Get a snapshot of the current status (for useSyncExternalStore).
   */
  getStatusSnapshot = (): WsConnectionStatus => this._status;

  /**
   * Whether this page ever had a live session. Lives here, not in a component: a component
   * remounts when the reader moves between layouts, and would forget.
   */
  get hasEverConnected(): boolean {
    return this.everConnected;
  }

  /** Whether the last failed attempt was the server refusing us (goaway), not a lost socket. */
  get lastFailureWasRefusal(): boolean {
    return this.lastFailure === 'goaway';
  }

  /**
   * Called every time a session is RE-established (a drop, a goaway, a workspace switch),
   * never on the first session of the page, and when a channel the server had refused for
   * capacity is finally accepted (it missed events too). It fires once every tracked channel
   * has been re-announced AND the server has answered each of those subscribes (or its
   * watchdog gave up on it), so a REST re-read here cannot miss a change published before
   * the subscription existed.
   */
  onReconnected(listener: () => void): () => void {
    this.reconnectListeners.add(listener);
    return () => { this.reconnectListeners.delete(listener); };
  }

  // ── Network / visibility recovery ──
  // Browsers throttle timers in background tabs and don't surface dropped sockets
  // promptly, so a pending backoff reconnect can stall. These listeners revive the
  // connection immediately when the network returns or the tab is refocused.

  private bindLifecycleListeners(): void {
    if (this.lifecycleBound || typeof window === 'undefined') return;
    this.lifecycleBound = true;
    window.addEventListener('online', this.handleOnline);
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', this.handleVisibility);
    }
  }

  private handleOnline = (): void => {
    // Guard on the REAL socket, never on `_status`: a zombie 'connected' is exactly the
    // state these listeners exist to rescue, and reading the belief made them refuse to.
    if (this.intentionalClose || this.isSocketOpen) return;
    if (this.isHeldByGoaway) return; // the server's retry-after stands
    this.reconnectAttempt = 0; // network is back - recover at full speed
    this.reconnectNow();
  };

  private handleVisibility = (): void => {
    if (this.intentionalClose) return;
    if (typeof document !== 'undefined'
        && document.visibilityState === 'visible'
        && !this.isSocketOpen
        && !this.isHeldByGoaway) {
      this.reconnectAttempt = 0;
      this.reconnectNow();
    }
  };

  /** The server refused us and said when to come back: until then its scheduled retry stands. */
  private get isHeldByGoaway(): boolean {
    return Date.now() < this.goawayUntil;
  }

  /** Cancel any pending backoff and attempt a connection immediately. */
  private reconnectNow(): void {
    if (this.isHeldByGoaway) return;
    if (this.reconnectTimer) { clearTimeout(this.reconnectTimer); this.reconnectTimer = null; }
    this.doConnect();
  }

  // ── Internal ──

  private async doConnect(): Promise<void> {
    if (this.intentionalClose) return;
    // A token fetch is already in flight - don't start a second attempt.
    if (this.connecting) return;
    // A live or in-flight socket already exists - no-op. Guard on the REAL socket
    // state, NEVER the (possibly stale) status field, so a zombie 'connected'
    // status can't silently block a genuine reconnect.
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) return;

    // We're connecting now - cancel any pending backoff timer so it can't fire a
    // duplicate attempt on top of this one.
    if (this.reconnectTimer) { clearTimeout(this.reconnectTimer); this.reconnectTimer = null; }

    // Detach whatever socket we are replacing. A CLOSING socket still delivers its
    // `onclose` later, and by then `this.ws` is the NEW socket - so handleDisconnect
    // would cleanup() the connection we just opened and fall back into backoff, which
    // is slower than doing nothing. Dropping the handlers first makes the old socket's
    // last breath a no-op.
    if (this.ws) {
      this.ws.onopen = null;
      this.ws.onmessage = null;
      this.ws.onclose = null;
      this.ws.onerror = null;
      this.ws = null;
      // An answer can only ever arrive on the socket being replaced, so its watchdogs go
      // with it. Leaving them armed means one fires minutes later and tears down the
      // connection that replaced it - the healthy one - which is worse than the silence
      // they exist to break. (cleanup() does this too, and this path bypasses cleanup.)
      this.clearSubscribeAckWatchdogs();
    }

    this.connecting = true;
    this.setStatus(this.reconnectAttempt > 0 ? 'reconnecting' : 'connecting');

    try {
      if (!this.tokenProvider) throw new Error('No token provider');
      const token = await this.tokenProvider();
      // disconnect() may have been called while awaiting the token.
      if (this.intentionalClose) { this.connecting = false; return; }
      const activeOrg = this.activeOrgProvider?.() ?? null;
      const url = `${this.gatewayUrl}/ws`;

      console.log(`[WS:client] Opening connection to: ${this.gatewayUrl}/ws (attempt=${this.reconnectAttempt})`);
      const ws = this.openAuthenticatedSocket(url, token, activeOrg);
      this.ws = ws;
      // Socket created - the readyState guard above now protects against duplicates.
      this.connecting = false;

      ws.onopen = () => {
        console.log('[WS:client] Connection opened, waiting for hello');
      };

      ws.onmessage = (event: MessageEvent) => {
        this.handleMessage(event.data as string);
      };

      ws.onclose = () => {
        this.handleDisconnect();
      };

      ws.onerror = (event) => {
        // onclose always follows onerror and drives the reconnect - nothing to do here.
        console.error('[WS:client] Connection error', event);
      };
    } catch {
      this.connecting = false;
      this.handleDisconnect();
    }
  }

  private openAuthenticatedSocket(url: string, token: string, activeOrg: string | null): WebSocket {
    const protocols = [
      WebSocketClient.AUTH_SUBPROTOCOL,
      `${WebSocketClient.TOKEN_SUBPROTOCOL_PREFIX}${token}`,
    ];

    if (activeOrg && activeOrg.length > 0) {
      protocols.push(`${WebSocketClient.ORG_SUBPROTOCOL_PREFIX}${activeOrg}`);
    }

    return new WebSocket(url, protocols);
  }

  private handleMessage(raw: string): void {
    let envelope: WsEnvelope;
    try {
      envelope = JSON.parse(raw);
    } catch {
      return;
    }

    // Debug: log all non-ping WS messages. Gated to non-prod - this runs synchronously in
    // onmessage for EVERY frame (incl. the ~20 agent:activity snapshot burst), and logging a
    // live payload object forces devtools retention/serialization. process.env.NODE_ENV is
    // statically inlined by Next.js, so this block is dead-code-eliminated in the prod bundle.
    if (envelope.type !== 'ping' && process.env.NODE_ENV !== 'production') {
      console.log(`[WS:raw] type=${envelope.type} ch=${envelope.channel || '-'}`, envelope.type === 'event' ? envelope.payload : '');
    }

    switch (envelope.type) {
      case 'hello':
        this.handleHello(envelope.payload as HelloPayload);
        break;
      case 'ping':
        this.handlePing();
        break;
      case 'subscribed':
        // The only proof a subscription actually landed - see sendSubscribe's watchdog.
        this.settleSubscribeAck(envelope.ref);
        if (envelope.ref && this.capacityRetryIds.delete(envelope.ref)) {
          // Everything that channel published while it was refused is gone: same answer as
          // a reconnect, re-read.
          this.notifyReconnected();
        }
        break;
      case 'unsubscribed':
        // Unsubscription confirmed - no action needed
        break;
      case 'event':
        this.handleEvent(envelope);
        break;
      case 'action.ack':
        this.handleActionAck(envelope);
        break;
      case 'action.error':
        this.handleActionError(envelope);
        break;
      case 'auth.refreshed':
        // Token refresh acknowledged
        break;
      case 'goaway':
        this.handleGoaway(envelope.payload as GoawayPayload | undefined);
        break;
      case 'error': {
        // A refusal is an ANSWER: the connection is alive and the server decided. Settle
        // the watchdog so a channel this user may not read cannot drive a reconnect loop.
        const refused = this.settleSubscribeAck(envelope.ref);
        if (envelope.ref) this.capacityRetryIds.delete(envelope.ref);
        const error = envelope.payload as ErrorPayload | undefined;
        // Refused for capacity, not for access: keep it and announce it again when a slot
        // frees up. The message check covers a server that predates the code.
        if (refused && this.channelHandlers.has(refused)
            && (error?.code === 'max_subscriptions' || error?.message === 'Max subscriptions reached')) {
          this.capacityRefused.add(refused);
        }
        console.warn('[WS] Server error:', envelope.payload);
        break;
      }
    }
  }

  /**
   * The gateway refused this connection (the user holds too many). This used to call
   * disconnect(), which latched the client off for the life of the page: the tab then
   * received no event at all (in prod, a chat reply that never appeared and an app run that
   * stayed "running" after it finished). Come back when the server says, not before: an
   * immediate retry is exactly the hammering the refusal is about.
   */
  private handleGoaway(payload?: GoawayPayload): void {
    const retryAfterMs = typeof payload?.retryAfterMs === 'number' && payload.retryAfterMs > 0
      ? payload.retryAfterMs
      : WebSocketClient.DEFAULT_GOAWAY_RETRY_MS;
    console.warn(`[WS:client] server refused the connection (${payload?.reason ?? 'goaway'}); `
      + `retrying in ${Math.round(retryAfterMs / 1000)}s`);
    this.connecting = false;
    this.cleanup(); // handlers are detached first, so the server's close does not re-enter here
    // A refusal is a gap in the page's live updates even when it hits the page's FIRST socket
    // (the gateway answers goaway instead of hello): the session that finally gets in must
    // tell the page to re-read what it missed.
    this.hadSession = true;
    this.lastFailure = 'goaway';
    // The retry is a reconnection, not a first attempt: doConnect then reports
    // 'reconnecting' rather than 'connecting', so the outage stays visible across retries.
    this.reconnectAttempt = Math.max(1, this.reconnectAttempt);
    this.goawayUntil = Date.now() + retryAfterMs;
    this.setStatus('reconnecting');
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.doConnect();
    }, retryAfterMs + Math.random() * WebSocketClient.GOAWAY_JITTER_MS);
  }

  private handleHello(payload: HelloPayload): void {
    this.sessionId = payload.sessionId;
    // 2026-05-04 hot-fix (audit MEGA #3): capture server's expected heartbeat
    // interval. Used by handlePing to arm a watchdog - if the server stops
    // pinging (zombie TCP), the watchdog triggers handleDisconnect → reconnect.
    // Without this, "events temps réel s'arrêtent" in prod (multi-tab) was
    // caused by a TCP-alive but server-muet WS that kept reporting `connected`.
    if (typeof payload.heartbeatMs === 'number' && payload.heartbeatMs > 0) {
      this.heartbeatMs = payload.heartbeatMs;
    }
    this.setStatus('connected');

    // Arm the heartbeat watchdog on initial connect - server should ping
    // within heartbeatMs; if not, we treat the connection as zombie.
    this.armPongWatchdog();

    // Re-subscribe all channels after (re)connect.
    //
    // The backoff counter is reset HERE, and only if that succeeded, rather than on the
    // handshake above. A session whose very first frames cannot leave never worked, and
    // treating it as a success is what turns a socket dying mid-handshake into a fast
    // loop: reset, fail, reconnect at attempt 0, reset again. Resetting on the first
    // thing the session actually carried makes the backoff mean what it says.
    const resubscribed = this.resubscribeAll();
    if (resubscribed) {
      this.reconnectAttempt = 0;
      if (this.hadSession) this.notifyWhenAnswered(resubscribed);
      this.hadSession = true;
      this.everConnected = true;
      this.lastFailure = null;
    }
  }

  /** Tell the reconnect listeners once every frame in `ids` has been answered (or given up on). */
  private notifyWhenAnswered(ids: string[]): void {
    if (ids.length === 0) {
      this.pendingResyncIds = null;
      this.notifyReconnected();
      return;
    }
    this.pendingResyncIds = new Set(ids);
  }

  /** One re-subscribe of the current resync got its answer (or its watchdog gave up on it). */
  private resyncFrameSettled(id: string): void {
    if (!this.pendingResyncIds?.delete(id)) return;
    if (this.pendingResyncIds.size > 0) return;
    this.pendingResyncIds = null;
    this.notifyReconnected();
  }

  private notifyReconnected(): void {
    for (const listener of this.reconnectListeners) {
      try {
        listener();
      } catch (err) {
        console.error('[WS] reconnect listener failed', err);
      }
    }
  }

  private armPongWatchdog(): void {
    if (this.pongTimer) clearTimeout(this.pongTimer);
    const timeout = this.heartbeatMs * WebSocketClient.HEARTBEAT_TIMEOUT_MULTIPLIER;
    this.pongTimer = setTimeout(() => {
      console.warn('[WS] Heartbeat timeout - server muet for', timeout, 'ms. Reconnecting…');
      this.pongTimer = null;
      // Force reconnect: close the socket, scheduleReconnect handles backoff
      this.handleDisconnect();
    }, timeout);
  }

  private handlePing(): void {
    // Server pinged us - re-arm the watchdog (alive).
    this.armPongWatchdog();

    // Respond with pong
    const pong: WsEnvelope = {
      v: 1,
      type: 'pong',
      id: crypto.randomUUID(),
      ts: Date.now(),
      payload: {
        channels: Object.fromEntries(
          Array.from(this.channelHandlers.keys()).map((ch) => [ch, 0])
        ),
      },
    };
    this.send(pong);
  }

  private handleEvent(envelope: WsEnvelope): void {
    const { channel, id, payload } = envelope;
    if (!channel) return;

    // Deduplication
    if (id && this.messageDedup.has(id)) return;
    if (id) {
      this.messageDedup.add(id);
      if (this.messageDedup.size > this.dedupMaxSize) {
        // Evict oldest entries (Set is insertion-ordered)
        const iter = this.messageDedup.values();
        for (let i = 0; i < 1000; i++) {
          const val = iter.next().value;
          if (val) this.messageDedup.delete(val);
        }
      }
    }

    // Dispatch to channel handlers
    const handlers = this.channelHandlers.get(channel);
    if (!handlers) {
      if (channel?.startsWith('conversation:')) {
        console.warn('[WS] No handlers for conversation channel:', channel, 'registered:', Array.from(this.channelHandlers.keys()));
      }
      return;
    }

    // Backend services publish either flat channel payloads or standardized
    // Redis event envelopes. Only unwrap the standardized envelope shape so
    // business payloads with their own `payload` field stay intact.
    const eventPayload = normalizeChannelEventPayload(payload);

    // Debug: trace conversation channel events
    if (channel?.startsWith('conversation:')) {
      console.log(`[WS:event] ch=${channel} handlers=${handlers.size}`, eventPayload);
    }

    for (const handler of handlers) {
      try {
        handler(eventPayload);
      } catch (err) {
        console.error('[WS] Handler error on channel', channel, err);
      }
    }
  }

  private handleActionAck(envelope: WsEnvelope): void {
    const ref = envelope.ref;
    if (!ref) return;
    const pending = this.pendingActions.get(ref);
    if (pending) {
      this.pendingActions.delete(ref);
      pending.resolve(envelope.payload);
    }
  }

  private handleActionError(envelope: WsEnvelope): void {
    const ref = envelope.ref;
    if (!ref) return;
    const pending = this.pendingActions.get(ref);
    if (pending) {
      this.pendingActions.delete(ref);
      const errPayload = envelope.payload as { error?: string } | undefined;
      pending.reject(new Error(errPayload?.error ?? 'Action failed'));
    }
  }

  private handleDisconnect(): void {
    this.connecting = false;
    this.cleanup();
    if (this.intentionalClose) {
      this.setStatus('disconnected');
      return;
    }
    // Always attempt recovery - a real-time client must never silently stay dead.
    // Only intentionalClose (logout/teardown) stops this.
    this.lastFailure = 'socket';
    this.setStatus('reconnecting');
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (this.intentionalClose) return;
    if (this.reconnectTimer) return; // a reconnect is already scheduled - don't stack
    const exponent = Math.min(this.reconnectAttempt, WebSocketClient.MAX_BACKOFF_EXPONENT);
    const baseDelay = Math.min(1000 * Math.pow(2, exponent), 30000);
    const jitter = Math.random() * 1000;

    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.reconnectAttempt = Math.min(this.reconnectAttempt + 1, WebSocketClient.MAX_BACKOFF_EXPONENT);
      this.doConnect();
    }, baseDelay + jitter);
  }

  /**
   * Re-announce every tracked channel on a fresh session. This is the safety net the
   * subscribe path relies on, so it must not become the next silent drop: if the socket
   * dies part-way through the loop, the channels after it would never be announced and
   * nothing would notice. Stop at the first frame that cannot leave and rebuild instead.
   *
   * @returns the ids of the frames sent, or null when the socket died part-way - i.e.
   *          whether this session carried anything at all, which is what the caller uses to
   *          decide if it counts as a successful connection for backoff purposes.
   */
  private resubscribeAll(): string[] | null {
    this.capacityRefused.clear(); // every tracked channel is announced again below
    this.capacityRetryIds.clear();
    const ids: string[] = [];
    for (const channel of this.channelHandlers.keys()) {
      const id = this.sendSubscribe(channel, true);
      if (id !== null) ids.push(id);
      if (id === null) {
        console.warn('[WS:client] socket died while re-announcing channels - reconnecting');
        // handleDisconnect, NOT recoverStaleConnection. This runs inside handleHello,
        // which has just set the status to 'connected' and reset the backoff counter, so
        // the "was it believed live" test there is true by construction and would
        // reconnect with NO delay - and since doConnect detaches the dead socket's
        // handlers, the onclose that would normally arm the backoff never fires either.
        // A socket that dies during its own handshake would then loop as fast as the
        // network allows, straight into the gateway's per-user connection cap (whose goaway
        // used to latch this client off entirely). Measured at 42 sockets with zero
        // elapsed time before this line said handleDisconnect.
        this.handleDisconnect();
        return null;
      }
    }
    return ids;
  }

  /**
   * Announce one channel and WATCH FOR THE ANSWER.
   *
   * A frame leaving the socket does not mean it arrived. The worst version of a dead
   * connection is the one where `readyState` is still OPEN - the peer is gone but nothing
   * has told the browser, so `send()` writes into a void and reports success. Nothing
   * else in this client would notice for up to 75 s (heartbeatMs * 2.5).
   *
   * The server always answers a subscribe, and the answer is unambiguous: `subscribed`
   * on success, `error` when access is denied (WsProtocolHandler). Both carry `ref` =
   * this frame's id. So an id that gets NO answer at all is proof the connection is not
   * carrying traffic, and is the only signal that distinguishes that from a channel the
   * user may not read - which must NOT trigger a reconnect, or a denied channel would
   * loop forever.
   */
  private sendSubscribe(channel: string, requestSnapshot?: boolean): string | null {
    const id = crypto.randomUUID();
    const envelope: WsEnvelope = {
      v: 1,
      type: 'subscribe',
      id,
      channel,
      ts: Date.now(),
      payload: requestSnapshot ? { requestSnapshot: true } : undefined,
    };
    if (!this.send(envelope)) return null;
    this.armSubscribeAckWatchdog(id, channel);
    return id;
  }

  private armSubscribeAckWatchdog(id: string, channel: string): void {
    const timer = setTimeout(() => {
      this.pendingSubscribeAcks.delete(id);
      this.capacityRetryIds.delete(id);
      if (this.intentionalClose) return;
      // A resync must not wait forever on a server that stays silent: re-read anyway.
      this.resyncFrameSettled(id);
      console.warn(`[WS:client] no answer to subscribe ch=${channel} after `
        + `${WebSocketClient.SUBSCRIBE_ACK_TIMEOUT_MS}ms - the connection is not carrying traffic`);
      this.forceReconnect();
    }, WebSocketClient.SUBSCRIBE_ACK_TIMEOUT_MS);
    this.pendingSubscribeAcks.set(id, { timer, channel });
  }

  /**
   * An answer arrived for a frame we were watching - `subscribed` or a refusal alike.
   * @returns the channel that frame announced, when it was one we were watching.
   */
  private settleSubscribeAck(ref: string | undefined): string | undefined {
    if (!ref) return undefined;
    const pending = this.pendingSubscribeAcks.get(ref);
    if (!pending) return undefined;
    clearTimeout(pending.timer);
    this.pendingSubscribeAcks.delete(ref);
    // An answer of any kind means the server is talking to us again.
    this.consecutiveAckTimeouts = 0;
    this.resyncFrameSettled(ref);
    return pending.channel;
  }

  private clearSubscribeAckWatchdogs(): void {
    for (const { timer } of this.pendingSubscribeAcks.values()) clearTimeout(timer);
    this.pendingSubscribeAcks.clear();
  }

  /** A slot freed up on this socket: announce one channel the server refused for capacity. */
  private retryCapacityRefused(): void {
    if (this._status !== 'connected' || !this.isSocketOpen) return;
    const next = this.capacityRefused.values().next();
    if (next.done) return;
    this.capacityRefused.delete(next.value);
    const id = this.sendSubscribe(next.value, true);
    if (id) this.capacityRetryIds.add(id);
  }

  /**
   * Rebuild the connection even though the socket claims to be usable. Used when the
   * socket is OPEN but demonstrably not carrying traffic, which `doConnect` alone cannot
   * act on: it guards on the real readyState and would treat this socket as healthy.
   *
   * Silence is evidence, not proof. The gateway authorizes a conversation channel with an
   * HTTP call to another service, so a degraded one leaves a subscribe unanswered on a
   * perfectly live connection - and each reconnect then re-asks that same slow service for
   * every tracked channel AND re-triggers a snapshot re-broadcast to everyone on the
   * conversation. Repeating that against a struggling service is how a slowdown becomes an
   * outage.
   *
   * The ordinary exponential backoff cannot bound that, and it would be comfortable to
   * assume it does: every successful handshake resets the attempt counter, and this path
   * always gets one (the connection is fine, it is the ANSWER that never comes). So the
   * repetition is bounded explicitly instead - after this many unanswered sessions in a
   * row, stop rebuilding. The connection is not the problem, and a truly dead socket is
   * still caught by the heartbeat watchdog.
   */
  private static readonly MAX_CONSECUTIVE_ACK_TIMEOUTS = 3;

  private forceReconnect(): void {
    if (this.intentionalClose) return;
    this.consecutiveAckTimeouts += 1;
    if (this.consecutiveAckTimeouts > WebSocketClient.MAX_CONSECUTIVE_ACK_TIMEOUTS) {
      console.warn(`[WS:client] ${this.consecutiveAckTimeouts} subscribes in a row went `
        + 'unanswered - the server is not answering, not unreachable. Leaving the connection '
        + 'alone rather than rebuilding it again.');
      return;
    }
    this.handleDisconnect();
  }

  private sendUnsubscribe(channel: string): void {
    const envelope: WsEnvelope = {
      v: 1,
      type: 'unsubscribe',
      id: crypto.randomUUID(),
      channel,
      ts: Date.now(),
    };
    this.send(envelope);
  }

  /** True when a real, open socket exists - the only trustworthy readiness signal. */
  private get isSocketOpen(): boolean {
    return !!this.ws && this.ws.readyState === WebSocket.OPEN;
  }

  /**
   * Send one frame. Returns whether it actually left, so a caller that cannot afford a
   * silent drop (subscribe) can react instead of assuming success.
   */
  private send(envelope: WsEnvelope): boolean {
    const socket = this.ws;
    if (!socket || socket.readyState !== WebSocket.OPEN) return false;
    socket.send(JSON.stringify(envelope));
    return true;
  }

  /**
   * Called when a frame could not leave because the socket is not OPEN. Reconnect now
   * rather than waiting for the heartbeat watchdog (75 s by default) or for the user to
   * reload the page.
   *
   * Four states where doing nothing is the right answer, in the order they are checked:
   * after an intentional teardown; before the app has ever asked to connect; while a
   * socket is already coming up; and while a backoff reconnect is already scheduled.
   * Only the last case can repeat, which is why it is a hard return rather than a
   * shorter delay: a component that mounts in a loop must not translate into one
   * connection attempt per mount against a server that is already down.
   */
  private recoverStaleConnection(): void {
    if (this.intentionalClose) return;
    // Nobody has asked for a connection yet. The provider calls `connect()` only AFTER
    // awaiting the gateway's runtime config (ws-provider), so the window is a network
    // round trip, not one React commit - every subscription mounted in it lands here.
    // Attempting a connection would fail on the missing token provider and leave the
    // client in 'reconnecting' with a backoff running before the app has tried once. The
    // channel is registered, and the `hello` that follows the real connect announces it.
    if (!this.tokenProvider) return;
    // A connection is already coming up: `hello` will resubscribe every tracked channel.
    if (this.ws && this.ws.readyState === WebSocket.CONNECTING) return;
    // A backoff reconnect is already scheduled. Jumping the queue here would let a
    // component that mounts and unmounts in a loop hammer an unreachable gateway once
    // per mount, which is exactly what the backoff exists to prevent.
    if (this.reconnectTimer) return;

    // Only a connection we BELIEVED was live earns a fresh, full-speed attempt: that is
    // the zombie case, where the backoff never started because nothing reported a drop.
    // In any other state the existing backoff is the honest pace.
    const wasBelievedLive = this._status === 'connected';
    if (wasBelievedLive) {
      // Worth a line: the client reported a healthy connection and it was not true.
      // Every other state here is an ordinary reconnect, and warning about those would
      // bury this one - the same noise problem the placeholder-channel fix removes.
      console.warn('[WS:client] frame dropped on a socket reported as connected - reconnecting now');
      this.reconnectAttempt = 0;
    }
    this.reconnectNow();
  }

  private setStatus(status: WsConnectionStatus): void {
    if (this._status === status) return;
    this._status = status;
    for (const listener of this.statusListeners) {
      listener();
    }
  }

  private cleanup(): void {
    // Answers can only arrive on the socket being torn down, so the watchdogs armed for
    // it must go with it - otherwise they fire later and force a reconnect on a
    // connection that has already been replaced.
    this.clearSubscribeAckWatchdogs();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.pongTimer) {
      clearTimeout(this.pongTimer);
      this.pongTimer = null;
    }
    if (this.ws) {
      this.ws.onopen = null;
      this.ws.onmessage = null;
      this.ws.onclose = null;
      this.ws.onerror = null;
      if (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING) {
        this.ws.close();
      }
      this.ws = null;
    }
    this.sessionId = null;
    // Those frames belonged to the session being torn down; the next hello starts a new resync.
    this.pendingResyncIds = null;
  }
}

/** Singleton WebSocket client instance. */
export const wsClient = new WebSocketClient();
