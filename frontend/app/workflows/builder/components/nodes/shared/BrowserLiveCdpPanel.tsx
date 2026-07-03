'use client';

/**
 * BrowserLiveCdpPanel - side panel showing the live Chromium tab via CDP
 * screencast for an agent:browser_agent node.
 *
 * Per the design: only the page (interactive iframe) + a minimal footer
 * with status / cost / step / abort. NO step timeline, NO eval/memory/goal
 * text, NO action descriptions. The first user click in the iframe asks
 * for confirmation, then raises a BROWSER_USER_TAKEOVER blocking signal -
 * the workflow holds until the user resumes.
 *
 * For PR #6 SKELETON: the actual CDP-WS connection (wss://websearch-host/cdp/
 * {sessionId}?token=...) is wired when the orchestrator-side endpoint
 * lands. Until then this panel renders a placeholder with the session_id
 * and current state, so the UX shape is reviewable end-to-end.
 */

import * as React from 'react';
import { X, Lock, ExternalLink, Hand, Play, ArrowLeft, ArrowRight, RotateCw } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { apiClient } from '@/lib/api/api-client';

import type { DerivedNodeStatus } from '../../../types';

interface BrowserLiveCdpPanelProps {
  nodeId: string;
  /** Workflow run id - required to POST takeover-resume. */
  runId?: string;
  sessionId?: string;
  /**
   * WebSocket URL the websearch-service exposes (e.g.
   * {@code wss://websearch-host.../cdp/{sid}}). Forwarded by the
   * orchestrator on each per-step SSE event as {@code cdp_ws_url}.
   */
  cdpWsUrl?: string;
  /**
   * Short-lived HS256 JWT (5-min TTL) the orchestrator issues alongside
   * {@code cdpWsUrl}. Forwarded as {@code cdp_token}. When either is
   * missing the panel renders a "live view unavailable" fallback.
   */
  cdpToken?: string;
  status?: DerivedNodeStatus;
  stepIndex?: number;
  costUsd?: number;
  lastAction?: string;
  /**
   * When true, render as a flex-fill container inside the host slot
   * (chat right-side panel) instead of as a fixed-position aside.
   * The host already provides the outer chrome (close button, header).
   */
  embedded?: boolean;
  onClose: () => void;
}

export function BrowserLiveCdpPanel({
  nodeId,
  runId,
  sessionId,
  cdpWsUrl,
  cdpToken,
  status,
  embedded = false,
  onClose,
}: BrowserLiveCdpPanelProps) {
  const t = useTranslations('workflowBuilder.nodes.browserAgent');
  const [takeoverActive, setTakeoverActive] = React.useState(false);
  const [wsConnected, setWsConnected] = React.useState(false);
  const [bridgeMode, setBridgeMode] = React.useState<'stub' | 'live' | null>(null);
  // Ref mirror of bridgeMode for the WS onmessage closure (state reads
  // there are stale - the socket handlers are bound once per socket).
  const bridgeModeRef = React.useRef<'stub' | 'live' | null>(null);
  // The agent's TASK is done (runner emitted the `final` step event) but
  // the browser may still be open: the runner's post-completion detached
  // hold keeps Chromium live while this panel is connected, so the user
  // can take control of the final page. Distinct from `sessionEnded`,
  // which means the browser itself is gone.
  const [taskFinished, setTaskFinished] = React.useState(false);
  // Letterbox geometry of the last painted frame (CSS px). paintFrame draws the
  // frame object-contain (aspect preserved, black bars) INSIDE a slot-filling
  // canvas - undistorted AND full-size - and records the drawn box here so a
  // takeover click maps back to the frame's device pixels past the bars.
  const frameLayoutRef = React.useRef<FrameLayout | null>(null);
  // True while a takeover-resume POST is in flight (disables the Resume button
  // so a double-click can't fire two resolves). The ref is the SYNCHRONOUS
  // guard - `resuming` state + the button's disabled attr only apply after a
  // re-render, so two clicks in the same tick would both pass a state check.
  const [resuming, setResuming] = React.useState(false);
  const resumingRef = React.useRef(false);
  // Current URL displayed in the address bar. Polled from the runner's
  // session status (which the runner updates via Page.frameNavigated CDP
  // event subscription). Falls back to "about:blank" until the first
  // navigation lands. Kept in state (not ref) so the address bar
  // re-renders when the agent navigates between pages.
  const [currentUrl, setCurrentUrl] = React.useState<string>('');
  const wsRef = React.useRef<WebSocket | null>(null);
  const canvasRef = React.useRef<HTMLCanvasElement | null>(null);
  // Latest screencast frame metadata. We need it to translate canvas-CSS
  // coordinates into Chromium page-pixel coordinates when forwarding
  // Input.dispatch* events. Updated on every Page.screencastFrame.
  const frameMetaRef = React.useRef<{
    deviceWidth: number;
    deviceHeight: number;
    offsetTop: number;
    pageScaleFactor: number;
  } | null>(null);
  // Monotonic id for OUR outbound CDP commands. Doesn't need to match
  // anything Chromium tracks beyond uniqueness within this WS - we
  // never read replies. Keeping it ref-scoped (not module-scoped) so a
  // remount starts at 1_000_000 and can't collide with the previous
  // socket's in-flight ids.
  const cmdIdRef = React.useRef<number>(1_000_000);
  // Cache of the most recently decoded frame. Used by the
  // ResizeObserver effect below to redraw the canvas at the new
  // backing-store size without waiting for the next screencast frame
  // (otherwise a panel resize would freeze a stale, mis-sized image
  // for ~67ms at 15fps - visibly jarring).
  const latestFrameRef = React.useRef<HTMLImageElement | null>(null);

  // ── Reconnect / token refresh ─────────────────────────────────────
  // The CDP JWT is short-lived (5 min). When the WS drops mid-takeover
  // because the token expired, we POST /cdp-token-refresh to mint a new
  // one and reopen the socket - the takeover state stays unchanged.
  // `activeToken` mirrors the cdpToken prop initially; refresh updates
  // it, which retriggers the WS-open effect below.
  const [activeToken, setActiveToken] = React.useState<string | undefined>(cdpToken);
  // True once we've successfully opened a WS at least once for the
  // current (cdpWsUrl, sessionId). Gates reconnect so a never-connected
  // panel (e.g. stale token from page navigation) doesn't burn retries
  // on a server that's already returned 404.
  const everConnectedRef = React.useRef(false);
  const reconnectAttemptsRef = React.useRef(0);
  const reconnectTimerRef = React.useRef<number | null>(null);
  // Monotonic generation id, bumped whenever the (cdpToken, sessionId,
  // cdpWsUrl) prop tuple changes OR the WS effect tears down. A pending
  // refresh callback compares its captured id to the current one and
  // refuses to apply a stale token if the generation has moved on -
  // prevents a late-arriving refresh from clobbering a parent-supplied
  // new prop or a different (sid, wsUrl) binding.
  const reconnectGenRef = React.useRef(0);
  // Stop retrying once the workflow has resumed (refresh returns 404)
  // or we've exhausted attempts. The user can dismiss the panel.
  const [reconnectExhausted, setReconnectExhausted] = React.useState(false);
  // Local "the runner finished" flag, set when we observe a `final`
  // step event on the WS (the runner XADDs one before closing). The
  // WS itself may stay open briefly after Chromium dies; without this
  // explicit signal the panel would sit on `wsConnected=true,
  // status='running'` forever - the "tab Running à vie" symptom.
  const [sessionEnded, setSessionEnded] = React.useState(false);
  // Final-page screenshot fallback (WS-independent). The live CDP
  // screencast often can't connect in prod (no public /cdp route,
  // internal-only cdp_ws_url, Cloudflare WS blocked), leaving the panel
  // stuck on "Waiting…" forever. The runner captures the FINAL page as a
  // JPEG at session teardown and the orchestrator serves it (run-ownership
  // gated); we poll it and render it as a static <img> so the user at
  // least sees the last page. `null` until the poll lands a capture.
  const [finalShotDataUrl, setFinalShotDataUrl] = React.useState<string | null>(null);
  // When embedded in the chat right-side panel, fire a window event
  // the moment we know the session is over (either via the runner's
  // `final` step on the WS, or via reconnect-exhausted/404). AppHeader
  // listens and re-labels the tab to "Browser Agent - disconnected"
  // so the user can see at a glance that the tab is no longer live.
  // The tab itself stays mounted (per UX feedback - user wants to
  // review the final state); only the title changes.
  React.useEffect(() => {
    if (!embedded) return;
    if (!nodeId) return;
    if (!sessionEnded && !reconnectExhausted) return;
    try {
      // Carry the sessionId so AppHeader re-labels the SAME tab the cards +
      // auto-open keyed on (agent-browse-{sessionId}); keep toolId for
      // back-compat with the older event shape.
      window.dispatchEvent(new CustomEvent('agentBrowseLiveTabDisconnected', {
        detail: { sessionId, toolId: nodeId },
      }));
    } catch {
      /* noop */
    }
  }, [embedded, nodeId, sessionId, sessionEnded, reconnectExhausted]);
  const RECONNECT_MAX_ATTEMPTS = 5;

  const cancelPendingReconnect = React.useCallback(() => {
    if (reconnectTimerRef.current !== null) {
      window.clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    // Bump the generation so any in-flight setTimeout-callback whose
    // fetch is already awaiting the network refuses to setActiveToken
    // when it returns.
    reconnectGenRef.current += 1;
  }, []);

  // Keep activeToken aligned with the prop when the parent supplies a
  // fresh one (new node, new session), and reset reconnect bookkeeping.
  React.useEffect(() => {
    setActiveToken(cdpToken);
    everConnectedRef.current = false;
    reconnectAttemptsRef.current = 0;
    setReconnectExhausted(false);
    cancelPendingReconnect();
  }, [cdpToken, sessionId, cdpWsUrl, cancelPendingReconnect]);

  // Cleanup any pending reconnect on unmount.
  React.useEffect(() => () => {
    cancelPendingReconnect();
  }, [cancelPendingReconnect]);

  // ── DPR-aware canvas resize ───────────────────────────────────────
  // Watch the canvas's CSS box and resize its bitmap buffer to match
  // (display CSS pixels × devicePixelRatio). Without this, the canvas
  // keeps its 1600×900 intrinsic size while CSS stretches it to
  // arbitrary device-pixel dimensions - fractional bilinear scale
  // softens the rendered text (the bug 3 Opus agents diagnosed
  // unanimously). Redraw the latest cached frame on resize so a panel
  // size change doesn't freeze a mis-sized stale image until the next
  // screencast frame arrives.
  React.useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ro = new ResizeObserver(() => {
      const img = latestFrameRef.current;
      if (img) paintFrame(canvas, img, frameLayoutRef);
    });
    ro.observe(canvas);
    return () => ro.disconnect();
  }, [activeToken, cdpWsUrl]);

  const scheduleReconnect = React.useCallback(() => {
    if (!runId || !sessionId) return;
    if (reconnectAttemptsRef.current >= RECONNECT_MAX_ATTEMPTS) {
      setReconnectExhausted(true);
      return;
    }
    const attempt = reconnectAttemptsRef.current;
    // 1s, 2s, 4s, 8s, 16s - capped (well under the 30s ceiling we
    // committed to in the M5 brief). Random jitter avoids thundering
    // herd if multiple panels reconnect after a backend blip.
    const baseMs = Math.min(30_000, 1_000 * (2 ** attempt));
    const jitterMs = Math.floor(Math.random() * 250);
    reconnectAttemptsRef.current = attempt + 1;
    // Capture the generation at schedule time. Any prop change or
    // explicit cancel will bump the generation, after which the
    // callback below short-circuits without calling setActiveToken.
    const scheduledGen = reconnectGenRef.current;
    if (reconnectTimerRef.current !== null) {
      window.clearTimeout(reconnectTimerRef.current);
    }
    reconnectTimerRef.current = window.setTimeout(async () => {
      reconnectTimerRef.current = null;
      // Pre-fetch generation check: parent may have re-rendered the
      // panel with a fresh cdpToken between scheduling and firing.
      if (scheduledGen !== reconnectGenRef.current) return;
      try {
        const resp = await apiClient.post<{
          cdp_token?: string;
          cdp_ws_url?: string;
          session_id?: string;
        }>(
          `/internal/browser-agent/runs/${encodeURIComponent(runId)}`
            + `/nodes/${encodeURIComponent(nodeId)}/cdp-token-refresh`,
          { session_id: sessionId },
        );
        // Post-fetch generation check: even if we made it past the
        // pre-check, the await above may have yielded long enough for
        // the parent to swap props. Drop the response on the floor in
        // that case.
        if (scheduledGen !== reconnectGenRef.current) return;
        const fresh = resp?.cdp_token;
        if (typeof fresh === 'string' && fresh.length > 0) {
          // New token → state change → WS-open effect fires again.
          setActiveToken(fresh);
        } else {
          scheduleReconnect();
        }
      } catch (err) {
        if (scheduledGen !== reconnectGenRef.current) return;
        // 404 means the workflow already advanced past this node - no
        // point retrying. Anything else, back off and try again.
        const status = (err as { status?: number; statusCode?: number })?.status
          ?? (err as { status?: number; statusCode?: number })?.statusCode;
        if (status === 404) {
          setReconnectExhausted(true);
        } else {
          scheduleReconnect();
        }
      }
    }, baseMs + jitterMs);
  }, [runId, nodeId, sessionId]);

  // ── WS lifecycle ──────────────────────────────────────────────────
  // Open exactly once per (cdpWsUrl, activeToken). Closing the WS does
  // NOT resume the workflow - that requires the explicit
  // takeover-resume POST. When the WS closes mid-takeover (typically
  // JWT expiry after 5 min) we trigger an automatic refresh+reconnect.
  React.useEffect(() => {
    if (!cdpWsUrl || !activeToken) {
      setWsConnected(false);
      return;
    }
    const url = `${cdpWsUrl}?token=${encodeURIComponent(activeToken)}`;
    let socket: WebSocket | null = null;
    try {
      socket = new WebSocket(url);
    } catch {
      return;
    }
    wsRef.current = socket;
    // Used only as a unique id for our own startScreencastAck commands -
    // does NOT need to match Chromium's CDP message ids (which are tied
    // to commands the runner/proxy sends, not us).
    let ackCmdId = 1_000_000;
    socket.onopen = () => {
      setWsConnected(true);
      everConnectedRef.current = true;
      // Reset attempts on a clean reconnect - if this socket later
      // drops, we get a fresh budget of retries.
      reconnectAttemptsRef.current = 0;
      setReconnectExhausted(false);
    };
    socket.onclose = (event) => {
      setWsConnected(false);
      wsRef.current = null;
      // Custom WS close codes from cdp.py for terminal session states:
      //   4404 = session_gone (Chromium killed; runner already finished)
      //   4403 = session_unauthorized (token rejected, no recovery)
      // Either is a definitive end signal - set sessionEnded so the
      // tab gets re-labelled "disconnected" even when the WS never
      // managed to connect (e.g. user opens chat history seconds
      // after a fast agent_browse completed). Without this, neither
      // `sessionEnded` nor `reconnectExhausted` would ever flip on the
      // never-connected path → tab stays labelled "Browser Agent".
      if (event.code === 4404 || event.code === 4403) {
        setSessionEnded(true);
        return;
      }
      // Only chase a reconnect when (a) we'd previously connected at
      // all (so refusing 401s on a stale-token open don't loop) and
      // (b) we still have a session to refresh against.
      if (everConnectedRef.current && runId && sessionId) {
        scheduleReconnect();
      }
    };
    socket.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        // The cdp.py router emits {type: "greeting", bridge_mode: "live" | "stub"}
        // immediately after the WS handshake. "live" means the proxy
        // bridged successfully to a Chromium page-target and screencast
        // frames will follow; "stub" means the runner could not expose
        // a CDP endpoint (browser-use binding gap, container down) and
        // the WS will only carry step_event tail messages.
        if (msg.type === 'greeting' && typeof msg.bridge_mode === 'string') {
          const mode = msg.bridge_mode === 'live' ? 'live' : 'stub';
          bridgeModeRef.current = mode;
          setBridgeMode(mode);
        }
        if (msg.type === 'paused') {
          setTakeoverActive(true);
        }
        // Runner emits `{type: "final", step_index, ...}` on the
        // per-session steps stream the moment the agent's TASK completes.
        // cdp.py forwards step events down the WS as {type: 'step_event',
        // payload: {...the runner event...}}. We watch for both shapes
        // (defensive - some runner builds wrap, others forward verbatim).
        //
        // `final` on a LIVE bridge does NOT end the session anymore: the
        // runner's post-completion detached hold keeps Chromium open
        // while this panel is connected, so the user can still take
        // control of the final page. The session truly ends when the WS
        // closes (4404/4403) or reconnects exhaust. In STUB mode there is
        // nothing interactive to hold for - terminal immediately, as are
        // `error`/`terminated` (crash paths tear the browser down inline).
        const stepPayload = msg.type === 'step_event' && msg.payload
          ? msg.payload
          : msg;
        if (stepPayload && stepPayload.type === 'final') {
          setTaskFinished(true);
          if (bridgeModeRef.current !== 'live') {
            setSessionEnded(true);
          }
        }
        if (stepPayload && (stepPayload.type === 'error'
            || stepPayload.type === 'terminated')) {
          setSessionEnded(true);
        }
        // Pick up navigations directly from step events so the address
        // bar updates instantly instead of waiting for the next 1 s
        // status poll. Runner step payloads carry the URL under any of
        // {url, current_url, last_url, final_url} depending on event
        // type - accept the first present, ignore otherwise.
        if (stepPayload && typeof stepPayload === 'object') {
          const candidate = stepPayload.current_url
            ?? stepPayload.url
            ?? stepPayload.last_url
            ?? stepPayload.final_url;
          if (typeof candidate === 'string' && candidate.length > 0) {
            setCurrentUrl(candidate);
          }
        }
        // CDP screencast frames. Each frame MUST be ACKed (see CDP spec:
        // "Acknowledges that a screencast frame has been received by
        // the frontend"). Without the ACK, Chromium pauses the
        // screencast after ~5 in-flight frames and the canvas freezes.
        if (msg.method === 'Page.screencastFrame' && msg.params?.data) {
          // Cache metadata for input-event coordinate translation FIRST
          // (cheap), then ACK Chromium IMMEDIATELY so it can pump the
          // next frame in parallel with our (slower) JPEG decode + draw.
          // Doing draw before ACK serialised the pipeline at the rate
          // of the slowest paint - ~3-5fps in practice with full-page
          // screenshots. ACK-first restores ~15fps observed in network.
          const meta = msg.params.metadata;
          if (meta && typeof meta.deviceWidth === 'number'
              && typeof meta.deviceHeight === 'number') {
            frameMetaRef.current = {
              deviceWidth: meta.deviceWidth,
              deviceHeight: meta.deviceHeight,
              offsetTop: typeof meta.offsetTop === 'number' ? meta.offsetTop : 0,
              pageScaleFactor: typeof meta.pageScaleFactor === 'number'
                ? meta.pageScaleFactor
                : 1,
            };
          }
          const sessionId = msg.params.sessionId;
          if (typeof sessionId === 'number' && socket?.readyState === WebSocket.OPEN) {
            try {
              socket.send(JSON.stringify({
                id: ackCmdId++,
                method: 'Page.screencastFrameAck',
                params: { sessionId },
              }));
            } catch {
              /* WS closing - frame loss is acceptable */
            }
          }
          // Now do the slow work (base64 decode + canvas draw). If a
          // newer frame arrives while we're decoding, the previous draw
          // is simply overwritten - no queue, no backlog, just the
          // latest visible frame wins. Chromium's screencast already
          // throttles to ~15fps so we won't drown in updates.
          drawJpegFrame(canvasRef.current, msg.params.data as string, latestFrameRef, frameLayoutRef);
        }
      } catch {
        /* non-JSON heartbeat - ignore */
      }
    };
    return () => {
      // Drop our handlers BEFORE close() so the close fired by this
      // cleanup doesn't loop into scheduleReconnect for a token rotation
      // we just initiated ourselves.
      if (socket) {
        try {
          socket.onopen = null;
          socket.onclose = null;
          socket.onmessage = null;
        } catch {
          /* noop */
        }
        try {
          socket.close();
        } catch {
          /* noop */
        }
      }
      wsRef.current = null;
      // If we're tearing down because (cdpWsUrl, activeToken, runId,
      // sessionId) changed, cancel any pending reconnect from the
      // PREVIOUS binding so its setActiveToken can't clobber the new
      // one when the network call eventually returns.
      cancelPendingReconnect();
    };
  }, [cdpWsUrl, activeToken, runId, sessionId, scheduleReconnect, cancelPendingReconnect]);

  // ── Live URL tracking ────────────────────────────────────────────
  // Poll the runner's `last_url` every 2s while the WS is connected.
  // The runner subscribes to Chromium's `Page.frameNavigated` and
  // stamps `session.last_url` on each navigation, so the address bar
  // stays in sync with what the agent is actually browsing. We poll
  // (rather than subscribe over the same WS) because `cdp.py` doesn't
  // forward navigation events to the WS client - only screencast
  // frames + greeting + step_event tail messages.
  React.useEffect(() => {
    if (!sessionId || !wsConnected) return;
    let cancelled = false;
    const tick = async () => {
      try {
        const status = await apiClient.get<{ last_url?: string }>(
          `/proxy/websearch/agent/sessions/${encodeURIComponent(sessionId)}/status`,
        );
        if (!cancelled && typeof status?.last_url === 'string') {
          setCurrentUrl(status.last_url);
        }
      } catch {
        /* session may have ended (404) - leave URL as last known */
      }
    };
    tick();
    const interval = window.setInterval(tick, 2000);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, [sessionId, wsConnected]);

  // ── Input forwarding (active during user takeover) ────────────────
  // While takeoverActive is true, mouse + keyboard events on the canvas
  // are translated into CDP Input.dispatch* commands and pumped through
  // the WS bridge to Chromium. Without this effect the user's clicks
  // and keystrokes hit the inert canvas and never reach the page.
  React.useEffect(() => {
    if (!takeoverActive) return;
    const canvas = canvasRef.current;
    const ws = wsRef.current;
    if (!canvas || !ws) return;

    const sendCdp = (method: string, params: Record<string, unknown>): void => {
      if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return;
      try {
        wsRef.current.send(JSON.stringify({
          id: cmdIdRef.current++,
          method,
          params,
        }));
      } catch {
        /* WS closing - drop the event silently rather than crashing the panel */
      }
    };

    const onMouse = (type: 'mousePressed' | 'mouseReleased' | 'mouseMoved') => (e: MouseEvent) => {
      e.preventDefault();
      const coords = canvasToPageCoords(e, canvas, frameMetaRef.current, frameLayoutRef.current);
      if (!coords) return;
      sendCdp('Input.dispatchMouseEvent', {
        type,
        x: coords.x,
        y: coords.y,
        modifiers: modifiersFromEvent(e),
        button: cdpButton(e.button),
        // CDP requires clickCount >= 1 for mousePressed/Released to
        // register as a click. Multi-clicks (dblclick, tripleclick)
        // come through as e.detail.
        clickCount: type === 'mousePressed' || type === 'mouseReleased'
          ? Math.max(1, e.detail || 1)
          : 0,
      });
    };

    const onWheel = (e: WheelEvent): void => {
      e.preventDefault();
      const coords = canvasToPageCoords(e, canvas, frameMetaRef.current, frameLayoutRef.current);
      if (!coords) return;
      sendCdp('Input.dispatchMouseEvent', {
        type: 'mouseWheel',
        x: coords.x,
        y: coords.y,
        modifiers: modifiersFromEvent(e),
        button: 'none',
        // Browser deltaMode 0 = pixel, 1 = line (~16px), 2 = page.
        // Chromium expects pixels.
        deltaX: e.deltaMode === 1 ? e.deltaX * 16
              : e.deltaMode === 2 ? e.deltaX * 100 : e.deltaX,
        deltaY: e.deltaMode === 1 ? e.deltaY * 16
              : e.deltaMode === 2 ? e.deltaY * 100 : e.deltaY,
      });
    };

    const onContextMenu = (e: Event): void => {
      // Don't pop the OS-level right-click menu over the canvas; the
      // right-click is already forwarded as a CDP mousePressed event.
      e.preventDefault();
    };

    const onKey = (type: 'keyDown' | 'keyUp') => (e: KeyboardEvent) => {
      // Stop browser-level shortcuts (Tab, F5, Ctrl+W, etc.) from
      // firing locally instead of on the streamed page. The user
      // explicitly took over the canvas - modifier-shortcuts should
      // address the page, not the host browser.
      e.preventDefault();
      e.stopPropagation();
      sendCdp('Input.dispatchKeyEvent', {
        type,
        key: e.key,
        code: e.code,
        modifiers: modifiersFromEvent(e),
        // Setting `text` on keyDown for printable characters is what
        // makes Chromium actually insert them into focused inputs.
        // Empty string for non-printable (Enter, Tab, Arrow*, …).
        text: type === 'keyDown' && e.key.length === 1 ? e.key : '',
        // Legacy fallback for sites that read keyCode (jQuery UI, …).
        windowsVirtualKeyCode: e.keyCode,
      });
    };

    // Paste (Ctrl/Cmd+V): the remote Chromium has no access to the user's
    // clipboard, so a forwarded Ctrl+V keystroke pastes nothing. Intercept the
    // host paste event, read the clipboard text, and inject it as CDP
    // Input.insertText - this is what makes filling a form by paste actually
    // work. Non-text payloads (images/files) are ignored.
    const onPaste = (e: ClipboardEvent): void => {
      const text = e.clipboardData?.getData('text');
      if (!text) return;
      e.preventDefault();
      sendCdp('Input.insertText', { text });
    };

    const onMouseDown = onMouse('mousePressed');
    const onMouseUp = onMouse('mouseReleased');
    const onMouseMove = onMouse('mouseMoved');
    const onKeyDown = onKey('keyDown');
    const onKeyUp = onKey('keyUp');

    canvas.addEventListener('mousedown', onMouseDown);
    canvas.addEventListener('mouseup', onMouseUp);
    canvas.addEventListener('mousemove', onMouseMove);
    canvas.addEventListener('wheel', onWheel, { passive: false });
    canvas.addEventListener('contextmenu', onContextMenu);
    canvas.addEventListener('keydown', onKeyDown);
    canvas.addEventListener('keyup', onKeyUp);
    canvas.addEventListener('paste', onPaste);
    // Pull focus so keyboard events hit the canvas. tabIndex is set in
    // the JSX below.
    try {
      canvas.focus();
    } catch {
      /* noop */
    }

    return () => {
      canvas.removeEventListener('mousedown', onMouseDown);
      canvas.removeEventListener('mouseup', onMouseUp);
      canvas.removeEventListener('mousemove', onMouseMove);
      canvas.removeEventListener('wheel', onWheel);
      canvas.removeEventListener('contextmenu', onContextMenu);
      canvas.removeEventListener('keydown', onKeyDown);
      canvas.removeEventListener('keyup', onKeyUp);
      canvas.removeEventListener('paste', onPaste);
    };
  }, [takeoverActive]);

  // Take control in ONE click (no confirm modal). A short browser task's live
  // window is only seconds long, so a confirmation step would waste it; the
  // "you have control" banner makes the paused state clear instead. Gated on
  // `liveAvailable` by the button. Pauses the agent AND - via the activity the
  // bridge now tracks - makes the runner hold Chromium open after the task
  // finishes so the user can keep driving.
  const handleConfirmTakeover = React.useCallback(() => {
    if (!sessionId || takeoverActive) return;
    setTakeoverActive(true);
    // Nudge the bridge with ONE benign Input.* so cdp.py sees "first user
    // input" and LPUSHes PAUSE on the runner control queue (the agent stops at
    // its next step boundary). A `mouseMoved` to the viewport centre triggers
    // the pause WITHOUT clicking anything - the old code sent a real
    // mousePressed at (0,0), which could click the top-left corner of the page.
    const meta = frameMetaRef.current;
    const x = meta ? Math.round(meta.deviceWidth / 2) : 1;
    const y = meta ? Math.round(meta.deviceHeight / 2) : 1;
    try {
      wsRef.current?.send(JSON.stringify({
        id: cmdIdRef.current++,
        method: 'Input.dispatchMouseEvent',
        params: { type: 'mouseMoved', x, y, button: 'none' },
      }));
    } catch {
      /* noop - PAUSE will be retried on the user's first real input */
    }
  }, [sessionId, takeoverActive]);

  // Give control back to the agent: resolve the BROWSER_USER_TAKEOVER signal
  // AND wake the runner's paused step loop (the controller does both). Optimistic
  // - we drop takeover locally even if the POST fails, since the resolve is
  // idempotent and the user clearly wants out; the worst case is the agent
  // resumes a beat later when the workflow advances.
  const handleResume = React.useCallback(async () => {
    if (!runId || resumingRef.current) return;
    resumingRef.current = true;
    setResuming(true);
    try {
      await apiClient.post(
        `/internal/browser-agent/runs/${encodeURIComponent(runId)}`
          + `/nodes/${encodeURIComponent(nodeId)}/takeover-resume`,
        // session_id lets the controller wake the runner for a CHAT
        // agent_browse, which has no workflow signal to read it from.
        sessionId ? { session_id: sessionId } : {},
      );
    } catch {
      /* best-effort: the signal may already be terminal (idempotent) */
    } finally {
      setTakeoverActive(false);
      setResuming(false);
      resumingRef.current = false;
    }
  }, [runId, nodeId, sessionId]);

  // Release the takeover hold when the panel closes while the user still holds
  // control, so the backend reclaims Chromium immediately instead of waiting
  // out the inactivity timeout. Fire-and-forget on unmount (no setState after
  // unmount); the runner's inactivity timeout is the backstop for a hard tab
  // close where this cleanup never runs.
  const takeoverActiveRef = React.useRef(false);
  React.useEffect(() => { takeoverActiveRef.current = takeoverActive; }, [takeoverActive]);
  React.useEffect(() => () => {
    if (takeoverActiveRef.current && runId && nodeId) {
      apiClient.post(
        `/internal/browser-agent/runs/${encodeURIComponent(runId)}`
          + `/nodes/${encodeURIComponent(nodeId)}/takeover-resume`,
        sessionId ? { session_id: sessionId } : {},
      ).catch(() => { /* best-effort: inactivity timeout reclaims anyway */ });
    }
  }, [runId, nodeId, sessionId]);

  // Component-level CDP sender (the one inside the takeover effect is scoped to
  // that effect). Used by the browser-chrome nav buttons, which drive the live
  // page whether or not takeover is active - back/forward/refresh are not
  // Input.* so they don't trip the pause-on-first-input, they just execute.
  const sendCdpCommand = React.useCallback((method: string, params: Record<string, unknown>): void => {
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    try {
      ws.send(JSON.stringify({ id: cmdIdRef.current++, method, params }));
    } catch {
      /* WS closing - drop silently */
    }
  }, []);

  // Browser-chrome nav: reload / history back / history forward on the live
  // Chromium. Page.reload is native; back/forward go through Runtime.evaluate
  // (CDP has no direct goBack) - fire-and-forget, we never read the reply.
  const handleRefresh = React.useCallback(() => {
    sendCdpCommand('Page.reload', { ignoreCache: false });
  }, [sendCdpCommand]);
  const handleBack = React.useCallback(() => {
    sendCdpCommand('Runtime.evaluate', { expression: 'history.back()' });
  }, [sendCdpCommand]);
  const handleForward = React.useCallback(() => {
    sendCdpCommand('Runtime.evaluate', { expression: 'history.forward()' });
  }, [sendCdpCommand]);

  // Red "stop" button (Apple traffic-light red): abort the session NOW - kills
  // Chromium via the run-ownership-gated /abort endpoint. Distinct from closing
  // the tab (ends only the viewer, keeps the hold) and from Resume (hands
  // control back to the agent). Optimistic: flip to the ended state locally so
  // the UI reacts instantly; the WS close (4404) confirms teardown.
  const [aborting, setAborting] = React.useState(false);
  const handleStop = React.useCallback(async () => {
    if (!runId || aborting) return;
    setAborting(true);
    try {
      await apiClient.post(
        `/internal/browser-agent/runs/${encodeURIComponent(runId)}`
          + `/nodes/${encodeURIComponent(nodeId)}/abort`,
        sessionId ? { session_id: sessionId } : {},
      );
    } catch {
      /* best-effort: the runner's own teardown is the backstop */
    } finally {
      setSessionEnded(true);
      setTakeoverActive(false);
    }
  }, [runId, nodeId, sessionId, aborting]);

  const isRunning = status === 'running';
  // The live screencast canvas is meaningful ONLY when the backend
  // confirmed `bridge_mode === 'live'`. In `stub` mode the WS is up
  // (auth + step stream tail) but no Page.screencastFrame events flow,
  // so the canvas would render an empty black box and mislead users
  // into clicking "Take control" against a non-existent input bridge.
  // Treat stub as not-live and render a clear placeholder instead.
  // `sessionEnded` flips the panel to its "Session ended" fallback
  // even when the WS happens to still be open (Chromium kept alive a
  // few seconds after the runner exited). Without this gate the user
  // would keep seeing the frozen last frame with no clear end-of-run
  // signal.
  const liveAvailable = Boolean(cdpWsUrl && cdpToken && wsConnected
    && bridgeMode === 'live' && !sessionEnded);

  // ── Final-page screenshot poll (WS-independent fallback) ──────────
  // While the live screencast isn't available and we don't yet have a
  // captured last page, poll the orchestrator's run-ownership-gated
  // endpoint. It 404s until the runner stores the capture at session
  // teardown; on the first hit we render it and stop. Capped so a run
  // that never produced a capture (early failure / feature off) doesn't
  // poll indefinitely. Skips entirely once live frames flow.
  React.useEffect(() => {
    if (!runId || !nodeId) return;
    if (liveAvailable) return;
    if (finalShotDataUrl) return;
    let cancelled = false;
    let attempts = 0;
    // ~12 min at 3s. The poll clock starts at MOUNT but the capture is
    // written only at teardown, which for a run that hits the 600s
    // wallclock cap lands just AFTER 600s (cancel -> finally -> CDP
    // round-trip). Budget past the run cap with headroom so a
    // max-duration run's last page is still caught instead of silently
    // reverting to "Session ended".
    const MAX_ATTEMPTS = 240;
    let timer: number | null = null;
    const poll = async () => {
      if (cancelled) return;
      attempts += 1;
      try {
        const shot = await apiClient.get<{ mime?: string; data_base64?: string }>(
          `/internal/browser-agent/runs/${encodeURIComponent(runId)}`
            + `/nodes/${encodeURIComponent(nodeId)}/final-screenshot`,
        );
        if (!cancelled && shot?.data_base64) {
          const mime = shot.mime || 'image/jpeg';
          setFinalShotDataUrl(`data:${mime};base64,${shot.data_base64}`);
          return; // captured - stop polling
        }
      } catch {
        /* 404 until the run ends + capture lands - keep polling */
      }
      if (!cancelled && attempts < MAX_ATTEMPTS) {
        timer = window.setTimeout(poll, 3000);
      }
    };
    poll();
    return () => {
      cancelled = true;
      if (timer !== null) window.clearTimeout(timer);
    };
  }, [runId, nodeId, liveAvailable, finalShotDataUrl]);

  // When `embedded` is true, the host (e.g. the chat right-side panel)
  // already provides the outer chrome - skip the fixed-position aside.
  // The flex column still applies so the canvas fills available height.
  // `overflow-hidden` + `min-w-0/min-h-0` are CRITICAL for embedded
  // mode: without them the high-resolution canvas (up to 1600×900
  // intrinsic) bursts out of the side-panel slot when the panel is
  // narrow, producing the "tab sort du right-side-panel" UX bug.
  const containerClass = embedded
    ? 'h-full w-full min-w-0 min-h-0 bg-theme-primary flex flex-col overflow-hidden'
    : 'fixed top-0 right-0 z-50 h-full w-[640px] max-w-full bg-theme-primary shadow-2xl flex flex-col';

  return (
    <aside
      className={containerClass}
      role="dialog"
      aria-label={t('panel.ariaLabel')}
    >
      {/* Header - browser chrome with traffic-light dots + address bar.
          Mirrors the style of WebSearchPanelContent so the live-view
          panel reads as the same UI primitive (a right-side browser)
          across the whole product. The address bar shows the live URL
          the agent is currently on (polled from session.last_url). */}
      <header className="flex items-center gap-2 px-3 py-2 bg-theme-secondary border-b border-theme min-w-0">
        {/* Traffic-light dots - the RED one is a real STOP button (aborts the
            session / kills Chromium at any moment); yellow + green stay
            cosmetic browser-chrome cues. */}
        <div className="flex items-center gap-1.5 shrink-0">
          <button
            type="button"
            onClick={handleStop}
            disabled={aborting || sessionEnded || !sessionId}
            className="group relative w-3 h-3 rounded-full bg-red-500 hover:bg-red-600 disabled:bg-red-400/40 disabled:cursor-not-allowed transition-colors flex items-center justify-center"
            title={t('chrome.stop')}
            aria-label={t('chrome.stop')}
          >
            <X className="w-2 h-2 text-red-900/0 group-hover:text-red-900/80" strokeWidth={3} />
          </button>
          <span className="w-3 h-3 rounded-full bg-yellow-400/70" />
          <span className="w-3 h-3 rounded-full bg-green-400/70" />
        </div>
        {/* Navigation - back / forward / refresh, like a real browser. Drive
            the live Chromium via CDP; enabled only while the live bridge is
            connected (in stub / ended states there's nothing to navigate). */}
        <div className="flex items-center gap-0.5 shrink-0">
          <button
            type="button"
            onClick={handleBack}
            disabled={!liveAvailable}
            className="p-1 rounded hover:bg-theme-tertiary transition-colors text-theme-muted disabled:opacity-40 disabled:cursor-not-allowed"
            title={t('chrome.back')}
            aria-label={t('chrome.back')}
          >
            <ArrowLeft className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            onClick={handleForward}
            disabled={!liveAvailable}
            className="p-1 rounded hover:bg-theme-tertiary transition-colors text-theme-muted disabled:opacity-40 disabled:cursor-not-allowed"
            title={t('chrome.forward')}
            aria-label={t('chrome.forward')}
          >
            <ArrowRight className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            onClick={handleRefresh}
            disabled={!liveAvailable}
            className="p-1 rounded hover:bg-theme-tertiary transition-colors text-theme-muted disabled:opacity-40 disabled:cursor-not-allowed"
            title={t('chrome.refresh')}
            aria-label={t('chrome.refresh')}
          >
            <RotateCw className="w-3.5 h-3.5" />
          </button>
        </div>
        {/* Address bar - full URL on hover via title; selectable so the
            user can copy it even when truncate hides the tail. */}
        <div className="flex-1 min-w-0 flex items-center gap-1.5 px-2.5 py-1.5 bg-theme-primary rounded-md border border-theme text-sm">
          <Lock className="w-3.5 h-3.5 text-theme-muted shrink-0" />
          <span
            className="truncate text-theme-secondary select-text"
            title={currentUrl || undefined}
          >
            {currentUrl || t('panel.waiting')}
          </span>
        </div>
        {/* Open in real browser - only when we have a URL */}
        {currentUrl && (
          <a
            href={currentUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="p-0.5 rounded hover:bg-theme-tertiary transition-colors shrink-0"
            title={currentUrl}
          >
            <ExternalLink className="w-3.5 h-3.5 text-theme-muted" />
          </a>
        )}
        {/* Live status indicator - pulses blue while the agent is actively
            streaming (status=running + WS connected + live bridge), pulses
            red on terminal disconnects (session ended OR reconnect budget
            exhausted), grey otherwise. Sole status surface on the panel
            now that the running banner footer has been removed; the dot
            sits to the right of the address bar so the URL stays the
            visual anchor. */}
        {(() => {
          const isDisconnected = sessionEnded || reconnectExhausted;
          const isLive = isRunning && wsConnected && bridgeMode === 'live' && !isDisconnected;
          const dotClass = isLive
            ? 'bg-blue-500 animate-pulse'
            : isDisconnected
              ? 'bg-red-500 animate-pulse'
              : 'bg-slate-400';
          const dotTitle = isLive
            ? t('panel.title')
            : isDisconnected
              ? t('panel.disconnectedTooltip')
              : t('panel.waiting');
          return (
            <span
              className={`inline-flex h-2 w-2 rounded-full shrink-0 ${dotClass}`}
              title={dotTitle}
            />
          );
        })()}
        {/* Close panel - hidden when embedded (the SidePanel host has
            its own close button). */}
        {!embedded && (
          <button
            onClick={onClose}
            className="p-0.5 rounded hover:bg-theme-tertiary transition-colors shrink-0 text-theme-muted"
            aria-label={t('panel.close')}
          >
            <X className="w-3.5 h-3.5" />
          </button>
        )}
      </header>

      {/* Live browser canvas */}
      <div className="relative flex-1 min-h-0 min-w-0 bg-black overflow-hidden flex items-center justify-center">
        {liveAvailable ? (
          <canvas
            ref={canvasRef}
            title={t('panel.screencastTitle')}
            // Fills the slot (h-full w-full); paintFrame draws the frame
            // object-contain INSIDE it (black bars), so it's undistorted AND
            // full-size - not the tiny box the earlier aspect-ratio CSS caused.
            // Blue inset ring + crosshair cue while you drive.
            className={`block h-full w-full outline-none ${takeoverActive ? 'ring-2 ring-inset ring-blue-500 cursor-crosshair' : ''}`}
            // tabIndex makes the canvas focusable so KeyboardEvents fire
            // on it during takeover. -1 keeps it out of the Tab order
            // (the user clicked to take over, no tab navigation needed).
            tabIndex={-1}
            style={{
              pointerEvents: takeoverActive ? 'auto' : 'none',
              imageRendering: 'high-quality' as React.CSSProperties['imageRendering'],
              WebkitImageRendering: '-webkit-optimize-contrast',
            } as React.CSSProperties}
          />
        ) : finalShotDataUrl ? (
          // Last-page fallback: the live screencast couldn't connect, but
          // the runner captured the final page at teardown. Show it as a
          // static image (object-contain so the full page stays visible,
          // letterboxed rather than cropped) instead of "Waiting…".
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={finalShotDataUrl}
            alt={t('panel.lastPageAlt')}
            title={t('panel.lastPageAlt')}
            className="block h-full w-full object-contain"
          />
        ) : (
          <div className="h-full w-full flex flex-col items-center justify-center text-center px-6 text-sm text-slate-400 gap-3">
            {bridgeMode === 'stub' ? (
              <>
                <span className="text-slate-300 font-medium">{t('panel.stubTitle')}</span>
                <span className="text-xs text-slate-500 max-w-md">
                  {t('panel.stubBody')}
                </span>
              </>
            ) : (
              <span>{(status === 'completed' || reconnectExhausted || sessionEnded)
                ? t('panel.sessionEnded') : t('panel.waiting')}</span>
            )}
          </div>
        )}

        {/* Task-finished ribbon: the agent is done but the page is still
            live (post-completion hold) - tell the user the result is in and
            the page remains theirs to drive until they close the panel. */}
        {liveAvailable && taskFinished && !takeoverActive && (
          <div className="absolute inset-x-0 top-0 z-10 flex items-center justify-center gap-1.5 bg-emerald-600/90 px-3 py-1.5 text-xs text-white">
            <span className="truncate">{t('panel.taskFinishedLive')}</span>
          </div>
        )}

        {/* Take control - shown while a live screencast is flowing and the
            user hasn't taken over yet. Clicking pauses the agent and hands the
            page to the user (forms, clicks, scroll, paste). */}
        {liveAvailable && !takeoverActive && (
          <div className="absolute inset-x-0 bottom-0 flex items-end justify-center pb-6 pointer-events-none">
            <button
              onClick={handleConfirmTakeover}
              className="pointer-events-auto inline-flex items-center gap-2 rounded-full bg-blue-600 hover:bg-blue-500 px-4 py-2 text-sm font-medium text-white shadow-lg transition-colors"
            >
              <Hand className="h-4 w-4" />
              {t('panel.clickToTakeControl')}
            </button>
          </div>
        )}

        {/* Active-takeover banner: the agent is paused and the user drives the
            page directly. The Resume button gives control back to the agent. */}
        {takeoverActive && (
          <div className="absolute inset-x-0 top-0 z-10 flex items-center justify-between gap-2 bg-blue-600/95 px-3 py-1.5 text-xs text-white">
            <span className="inline-flex items-center gap-1.5 min-w-0">
              <Hand className="h-3.5 w-3.5 shrink-0" />
              <span className="truncate">{t('takeover.banner')}</span>
            </span>
            <button
              onClick={handleResume}
              disabled={resuming}
              className="inline-flex items-center gap-1.5 rounded-full bg-white/20 hover:bg-white/30 px-2.5 py-1 font-medium disabled:opacity-60 shrink-0"
            >
              <Play className="h-3.5 w-3.5" />
              {t('takeover.resume')}
            </button>
          </div>
        )}

      </div>

    </aside>
  );
}

/**
 * Decode a base64-encoded JPEG (CDP `Page.screencastFrame` payload) and
 * draw it to the live canvas. Best-effort: a malformed frame is dropped
 * - the next frame supersedes it.
 */
/**
 * Paint a decoded image onto the canvas using a DPR-aware backing
 * store. The canvas's intrinsic width/height are sized to the display
 * rect × devicePixelRatio so the GPU-side blit canvas → screen is 1:1
 * (no CSS bilinear softening). The 1600×900 source JPEG is downscaled
 * by Chromium's higher-quality 2D `drawImage` path with
 * `imageSmoothingQuality='high'`. Result: text stays crisp at any
 * panel width on both DPR=1 and DPR=2 displays.
 */
/**
 * Letterbox geometry of the last painted frame, in CSS px relative to the
 * canvas's getBoundingClientRect: the box the frame actually occupies inside
 * the (slot-filling) canvas. Used to map a takeover click back to the frame's
 * device pixels past the black bars.
 */
type FrameLayout = { offX: number; offY: number; dispW: number; dispH: number };

function paintFrame(
  canvas: HTMLCanvasElement,
  img: HTMLImageElement,
  layoutRef?: React.MutableRefObject<FrameLayout | null>,
): void {
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  const rect = canvas.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  // Fall back to source dims if canvas isn't laid out yet (mounted but
  // CSS hasn't computed) - the next frame will catch up post-layout.
  const cssW = rect.width || img.width;
  const cssH = rect.height || img.height;
  const targetW = Math.max(1, Math.round(cssW * dpr));
  const targetH = Math.max(1, Math.round(cssH * dpr));
  if (canvas.width !== targetW) canvas.width = targetW;
  if (canvas.height !== targetH) canvas.height = targetH;
  // object-contain: scale the frame to fit the canvas preserving its aspect,
  // centered, with black letterbox bars. The canvas itself fills the slot
  // (CSS h-full w-full), so the page is shown undistorted AND full-size. (An
  // earlier attempt set the canvas to aspect-ratio CSS, which collapsed it to
  // a tiny box - hence drawing the letterbox in-canvas instead.)
  const scale = Math.min(targetW / img.width, targetH / img.height);
  const drawW = Math.max(1, Math.round(img.width * scale));
  const drawH = Math.max(1, Math.round(img.height * scale));
  const dx = Math.round((targetW - drawW) / 2);
  const dy = Math.round((targetH - drawH) / 2);
  ctx.fillStyle = '#000';
  ctx.fillRect(0, 0, targetW, targetH);
  ctx.imageSmoothingEnabled = true;
  // imageSmoothingQuality is non-standard but supported in Chromium &
  // Firefox; Safari falls back to default smoothing - no error.
  (ctx as CanvasRenderingContext2D & { imageSmoothingQuality?: 'low' | 'medium' | 'high' })
    .imageSmoothingQuality = 'high';
  ctx.drawImage(img, dx, dy, drawW, drawH);
  if (layoutRef) {
    // Store in CSS px (the units getBoundingClientRect uses) so the click
    // mapping divides by the displayed image box, not the full canvas.
    layoutRef.current = { offX: dx / dpr, offY: dy / dpr, dispW: drawW / dpr, dispH: drawH / dpr };
  }
}

function drawJpegFrame(
  canvas: HTMLCanvasElement | null,
  base64: string,
  latestFrameRef?: React.MutableRefObject<HTMLImageElement | null>,
  layoutRef?: React.MutableRefObject<FrameLayout | null>,
): void {
  if (!canvas || !base64) return;
  const img = new Image();
  img.onload = () => {
    if (latestFrameRef) latestFrameRef.current = img;
    paintFrame(canvas, img, layoutRef);
  };
  img.src = `data:image/jpeg;base64,${base64}`;
}

/**
 * Translate a browser MouseEvent (clientX/Y) into Chromium page-pixel
 * coordinates that {@code Input.dispatchMouseEvent} expects.
 *
 * The frame is drawn object-contain inside a slot-filling canvas, so it
 * occupies only the letterbox box {@code layout} (CSS px). We map the click
 * into that box, then scale to the frame's device pixels. A click on the black
 * bars (outside the box) returns null so it isn't forwarded. pageScaleFactor /
 * offsetTop from the metadata matter for pinch-zoom / visible URL bar.
 *
 * Returns null if the canvas isn't laid out yet or the click is off the frame.
 */
export function canvasToPageCoords(
  e: MouseEvent | WheelEvent,
  canvas: HTMLCanvasElement,
  meta: { deviceWidth: number; deviceHeight: number;
          offsetTop: number; pageScaleFactor: number } | null,
  layout: FrameLayout | null,
): { x: number; y: number } | null {
  const rect = canvas.getBoundingClientRect();
  if (rect.width <= 0 || rect.height <= 0) return null;
  const cssX = e.clientX - rect.left;
  const cssY = e.clientY - rect.top;
  const deviceW = meta?.deviceWidth ?? canvas.width ?? rect.width;
  const deviceH = meta?.deviceHeight ?? canvas.height ?? rect.height;
  const offsetTop = meta?.offsetTop ?? 0;
  const scale = meta?.pageScaleFactor ?? 1;
  // The frame occupies the letterbox box [offX, offX+dispW] x [offY, offY+dispH]
  // (CSS px). Before the first paint, fall back to the full canvas rect.
  const lb = layout ?? { offX: 0, offY: 0, dispW: rect.width, dispH: rect.height };
  if (lb.dispW <= 0 || lb.dispH <= 0) return null;
  const inX = cssX - lb.offX;
  const inY = cssY - lb.offY;
  if (inX < 0 || inY < 0 || inX > lb.dispW || inY > lb.dispH) return null; // black bar
  const x = (inX / lb.dispW) * deviceW;
  const y = ((inY / lb.dispH) * deviceH) / scale - offsetTop;
  return { x: Math.round(x), y: Math.round(y) };
}

/**
 * Build the CDP `modifiers` bitmask from a mouse / keyboard / wheel
 * event. CDP encoding: 1=Alt, 2=Ctrl, 4=Meta, 8=Shift. Standard
 * across all Input.dispatch* commands.
 */
function modifiersFromEvent(e: MouseEvent | KeyboardEvent | WheelEvent): number {
  return (e.altKey ? 1 : 0)
       | (e.ctrlKey ? 2 : 0)
       | (e.metaKey ? 4 : 0)
       | (e.shiftKey ? 8 : 0);
}

/**
 * Map a DOM MouseEvent.button (0=left, 1=middle, 2=right, 3=back,
 * 4=forward) to the CDP `button` enum string.
 */
function cdpButton(domButton: number): string {
  switch (domButton) {
    case 0: return 'left';
    case 1: return 'middle';
    case 2: return 'right';
    case 3: return 'back';
    case 4: return 'forward';
    default: return 'none';
  }
}
