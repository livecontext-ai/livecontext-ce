import type {
  WsEnvelope,
  WsConnectionStatus,
  ChannelHandler,
  HelloPayload,
  ChannelEventPayload,
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

  // Reconnection - backoff only. Reconnection is attempted INDEFINITELY (capped
  // backoff + jitter) so a real-time client always recovers from transient drops;
  // `intentionalClose` is the ONLY thing that stops it (logout / explicit teardown).
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private static readonly MAX_BACKOFF_EXPONENT = 5; // 2^5 * 1s = 32s → capped to 30s
  private intentionalClose = false;     // true only after disconnect(); blocks reconnects
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
    this.bindLifecycleListeners();
    this.doConnect();
  }

  /**
   * Disconnect and STOP reconnection. Use only for real teardown (logout / app
   * unmount). For a workspace switch use {@link reconnect} instead.
   */
  disconnect(): void {
    this.intentionalClose = true;
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

    if (this._status === 'connected') {
      if (isNewChannel) {
        // First subscriber opens the channel (and pulls a snapshot if asked).
        this.sendSubscribe(channel, requestSnapshot);
      } else if (requestSnapshot) {
        // A LATER subscriber joining an already-open channel still needs the
        // current state. Redis pub/sub does not replay, so without re-requesting
        // a snapshot this handler would only ever receive FUTURE deltas and
        // render stale/empty. This is the real cause of a re-navigated (or
        // second-gateway-pod) workflow canvas entering run mode but never
        // painting the run: the channel was already subscribed by an earlier
        // connection, so `new=false` skipped the snapshot. Re-sending subscribe
        // with requestSnapshot is idempotent server-side (state is state).
        this.sendSubscribe(channel, true);
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
      // Send unsubscribe message to server
      if (this._status === 'connected') {
        this.sendUnsubscribe(channel);
      }
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
      this.send(envelope);

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
    if (!this.tokenProvider || this._status !== 'connected') return;

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
    if (this.intentionalClose || this._status === 'connected') return;
    this.reconnectAttempt = 0; // network is back - recover at full speed
    this.reconnectNow();
  };

  private handleVisibility = (): void => {
    if (this.intentionalClose) return;
    if (typeof document !== 'undefined'
        && document.visibilityState === 'visible'
        && this._status !== 'connected') {
      this.reconnectAttempt = 0;
      this.reconnectNow();
    }
  };

  /** Cancel any pending backoff and attempt a connection immediately. */
  private reconnectNow(): void {
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
        // Subscription confirmed - no action needed
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
        this.disconnect();
        break;
      case 'error':
        console.warn('[WS] Server error:', envelope.payload);
        break;
    }
  }

  private handleHello(payload: HelloPayload): void {
    this.sessionId = payload.sessionId;
    this.reconnectAttempt = 0;
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

    // Re-subscribe all channels after (re)connect
    this.resubscribeAll();
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

  private resubscribeAll(): void {
    for (const channel of this.channelHandlers.keys()) {
      this.sendSubscribe(channel, true);
    }
  }

  private sendSubscribe(channel: string, requestSnapshot?: boolean): void {
    const envelope: WsEnvelope = {
      v: 1,
      type: 'subscribe',
      id: crypto.randomUUID(),
      channel,
      ts: Date.now(),
      payload: requestSnapshot ? { requestSnapshot: true } : undefined,
    };
    this.send(envelope);
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

  private send(envelope: WsEnvelope): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    this.ws.send(JSON.stringify(envelope));
  }

  private setStatus(status: WsConnectionStatus): void {
    if (this._status === status) return;
    this._status = status;
    for (const listener of this.statusListeners) {
      listener();
    }
  }

  private cleanup(): void {
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
  }
}

/** Singleton WebSocket client instance. */
export const wsClient = new WebSocketClient();
