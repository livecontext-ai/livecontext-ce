// @vitest-environment jsdom
/**
 * Tests for the WS-independent "last page" fallback in
 * {@link BrowserLiveCdpPanel}.
 *
 * The live CDP screencast often can't connect in prod (no public /cdp
 * route, internal-only cdp_ws_url, Cloudflare WS blocked), leaving the
 * panel stuck on "Waiting for the browser session to start…" forever.
 * The runner captures the FINAL page at session teardown and the
 * orchestrator serves it (run-ownership gated); the panel polls that
 * endpoint - independent of the WS - and renders the capture as a static
 * <img>. These tests pin: (a) it polls and renders the last page, (b) it
 * builds the (runId,nodeId)-keyed orchestrator URL, (c) it keeps showing
 * the "waiting/ended" text when no capture exists.
 */
import * as React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

const getMock = vi.fn();
const postMock = vi.fn();
vi.mock('@/lib/api/api-client', () => ({
  apiClient: {
    get: (...args: unknown[]) => getMock(...args),
    post: (...args: unknown[]) => postMock(...args),
  },
}));

import { BrowserLiveCdpPanel, canvasToPageCoords } from '../BrowserLiveCdpPanel';

const messages = {
  workflowBuilder: {
    nodes: {
      browserAgent: {
        panel: {
          ariaLabel: 'Browser agent live view',
          title: 'Browser Agent - Live',
          close: 'Close',
          waiting: 'Waiting for the browser session to start…',
          sessionEnded: 'Session ended',
          disconnectedTooltip: 'Disconnected',
          screencastTitle: 'Browser agent screencast',
          lastPageAlt: 'Last page the agent saw',
          stubTitle: 'Live screencast unavailable',
          stubBody: 'stub',
          clickToTakeControl: 'Click to take control',
        },
        takeover: { title: 't', description: 'd', cancel: 'c', confirm: 'ok', resume: 'Resume agent', banner: 'You have control' },
      },
    },
  },
};

function renderPanel(props: Partial<React.ComponentProps<typeof BrowserLiveCdpPanel>> = {}) {
  return render(
    <NextIntlClientProvider locale="en" messages={messages as any} onError={() => {}}>
      <BrowserLiveCdpPanel
        nodeId="node_1"
        runId="run_1"
        sessionId="ses_1"
        status="running"
        embedded
        onClose={() => {}}
        {...props}
      />
    </NextIntlClientProvider>,
  );
}

describe('BrowserLiveCdpPanel - final-page screenshot fallback', () => {
  beforeEach(() => {
    getMock.mockReset();
  });

  it('polls the orchestrator and renders the captured last page as an <img>', async () => {
    getMock.mockResolvedValue({ mime: 'image/jpeg', data_base64: 'aGVsbG8=' });

    renderPanel();

    const img = await screen.findByAltText('Last page the agent saw');
    expect(img).toBeTruthy();
    expect(img.getAttribute('src')).toBe('data:image/jpeg;base64,aGVsbG8=');
  });

  it('stops polling after the first successful capture (no re-poll scheduled)', async () => {
    getMock.mockResolvedValue({ mime: 'image/jpeg', data_base64: 'aGVsbG8=' });

    renderPanel();

    await screen.findByAltText('Last page the agent saw');
    // The success path returns without scheduling another timer.
    expect(getMock).toHaveBeenCalledTimes(1);
  });

  it('calls the (runId,nodeId)-keyed orchestrator endpoint (no client session id)', async () => {
    getMock.mockResolvedValue({ mime: 'image/jpeg', data_base64: 'aGVsbG8=' });

    renderPanel({ runId: 'run_42', nodeId: 'node_42' });

    await waitFor(() => expect(getMock).toHaveBeenCalled());
    expect(getMock).toHaveBeenCalledWith(
      '/internal/browser-agent/runs/run_42/nodes/node_42/final-screenshot',
    );
  });

  it('defaults the mime to image/jpeg when the reply omits it', async () => {
    getMock.mockResolvedValue({ data_base64: 'eHl6' });

    renderPanel();

    const img = await screen.findByAltText('Last page the agent saw');
    expect(img.getAttribute('src')).toBe('data:image/jpeg;base64,eHl6');
  });

  it('keeps the waiting/ended text when no capture exists (endpoint 404s)', async () => {
    getMock.mockRejectedValue({ status: 404 });

    renderPanel({ status: 'running' });

    // The poll fires but never yields an image; the placeholder text stays.
    // ("Waiting…" renders in both the address bar and the canvas placeholder,
    // so assert on the multi-match count rather than a single node.)
    await waitFor(() => expect(getMock).toHaveBeenCalled());
    expect(screen.queryByAltText('Last page the agent saw')).toBeNull();
    expect(
      screen.getAllByText('Waiting for the browser session to start…').length,
    ).toBeGreaterThan(0);
  });

  it('stops polling after unmount (cleanup clears the scheduled re-poll)', async () => {
    vi.useFakeTimers();
    try {
      getMock.mockRejectedValue({ status: 404 }); // never captures -> keeps scheduling
      const { unmount } = renderPanel();
      // Flush the immediate first poll() + its 404 + the 3s re-poll scheduling.
      await vi.advanceTimersByTimeAsync(0);
      expect(getMock).toHaveBeenCalledTimes(1);
      unmount();
      // Past the 3s interval: the cleared timer must NOT fire another poll
      // (no state-update-after-unmount, no runaway polling on a closed panel).
      await vi.advanceTimersByTimeAsync(10000);
      expect(getMock).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('caps polling at MAX_ATTEMPTS so a run that never captures cannot poll forever', async () => {
    vi.useFakeTimers();
    try {
      getMock.mockRejectedValue({ status: 404 });
      renderPanel();
      // Drive well past the 240-attempt ceiling (240 * 3s = 720s).
      for (let i = 0; i < 245; i++) {
        await vi.advanceTimersByTimeAsync(3000);
      }
      // Bounded: it polled many times but never exceeded the cap.
      expect(getMock.mock.calls.length).toBeGreaterThan(1);
      expect(getMock.mock.calls.length).toBeLessThanOrEqual(240);
    } finally {
      vi.useRealTimers();
    }
  });
});

/**
 * Live precedence: when the CDP screencast WS actually connects, the live
 * canvas must win over the static screenshot fallback (the <img> must NOT
 * take over). Needs a minimal WebSocket + ResizeObserver stub.
 */
let wsSent: string[] = [];
let wsLast: FakeWebSocket | null = null;

function rememberWebSocket(socket: FakeWebSocket) {
  wsLast = socket;
}

class FakeWebSocket {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;
  readyState = 0;
  onopen: ((ev: unknown) => void) | null = null;
  onclose: ((ev: { code: number }) => void) | null = null;
  onmessage: ((ev: { data: string }) => void) | null = null;
  onerror: ((ev: unknown) => void) | null = null;
  constructor(public url: string) {
    rememberWebSocket(this);
    queueMicrotask(() => {
      this.readyState = 1;
      this.onopen?.({});
      // Greeting flips the panel into live mode (bridge_mode === 'live').
      this.onmessage?.({ data: JSON.stringify({ type: 'greeting', bridge_mode: 'live' }) });
    });
  }
  send(data: string) { wsSent.push(data); }
  close() {
    this.readyState = 3;
    this.onclose?.({ code: 1000 });
  }
  // Test helper: deliver a screencast frame so the panel learns the live
  // viewport's aspect ratio (drives the canvas aspect-ratio CSS).
  pushFrame(deviceWidth: number, deviceHeight: number) {
    this.onmessage?.({ data: JSON.stringify({
      method: 'Page.screencastFrame',
      params: { data: 'AA==', sessionId: 1, metadata: { deviceWidth, deviceHeight, offsetTop: 0, pageScaleFactor: 1 } },
    }) });
  }
}

describe('BrowserLiveCdpPanel - live screencast takes precedence over the screenshot', () => {
  const realWS = (globalThis as any).WebSocket;
  const realRO = (globalThis as any).ResizeObserver;

  beforeEach(() => {
    getMock.mockReset();
    (globalThis as any).WebSocket = FakeWebSocket as any;
    (globalThis as any).ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  });

  afterEach(() => {
    (globalThis as any).WebSocket = realWS;
    (globalThis as any).ResizeObserver = realRO;
  });

  it('renders the live canvas (not the screenshot) once the live bridge connects', async () => {
    // Even with a capture available, the live canvas must win.
    getMock.mockResolvedValue({ mime: 'image/jpeg', data_base64: 'aGVsbG8=' });

    render(
      <NextIntlClientProvider locale="en" messages={messages as any} onError={() => {}}>
        <BrowserLiveCdpPanel
          nodeId="node_1"
          runId="run_1"
          sessionId="ses_1"
          cdpWsUrl="ws://internal/cdp/ses_1"
          cdpToken="tok"
          status="running"
          embedded
          onClose={() => {}}
        />
      </NextIntlClientProvider>,
    );

    // Live bridge connected → the screencast canvas renders.
    await screen.findByTitle('Browser agent screencast');
    // The screenshot fallback must NOT take over while live.
    expect(screen.queryByAltText('Last page the agent saw')).toBeNull();
  });
});

/**
 * Takeover (take control / resume): re-enabled robust path. While a live
 * screencast flows, the user can pause the agent, drive the page directly, and
 * hand control back. These pin: the affordance only shows when live, taking
 * control flips into the takeover banner, and Resume hits the
 * (runId,nodeId)-keyed takeover-resume endpoint.
 */
describe('BrowserLiveCdpPanel - takeover (take control / resume)', () => {
  const realWS = (globalThis as any).WebSocket;
  const realRO = (globalThis as any).ResizeObserver;

  beforeEach(() => {
    getMock.mockReset();
    getMock.mockRejectedValue({ status: 404 }); // no final-screenshot; keep the live path
    postMock.mockReset();
    postMock.mockResolvedValue({ status: 'resumed' });
    wsSent = [];
    wsLast = null;
    (globalThis as any).WebSocket = FakeWebSocket as any;
    (globalThis as any).ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  });

  afterEach(() => {
    (globalThis as any).WebSocket = realWS;
    (globalThis as any).ResizeObserver = realRO;
  });

  function renderLive() {
    return render(
      <NextIntlClientProvider locale="en" messages={messages as any} onError={() => {}}>
        <BrowserLiveCdpPanel
          nodeId="node_9"
          runId="run_9"
          sessionId="ses_9"
          cdpWsUrl="ws://internal/cdp/ses_9"
          cdpToken="tok"
          status="running"
          embedded
          onClose={() => {}}
        />
      </NextIntlClientProvider>,
    );
  }

  it('offers Take control while live, pauses on confirm, then resumes via the (runId,nodeId) POST', async () => {
    renderLive();
    // Live → the Take control affordance shows (label reuses panel.clickToTakeControl).
    // 1-click: taking control needs no confirm modal (a short task's window is tiny).
    fireEvent.click(await screen.findByText('Click to take control'));
    // Now in control: the banner + Resume show.
    expect(screen.getByText('You have control')).toBeTruthy();
    fireEvent.click(screen.getByText('Resume agent'));
    await waitFor(() => expect(postMock).toHaveBeenCalledWith(
      '/internal/browser-agent/runs/run_9/nodes/node_9/takeover-resume', { session_id: 'ses_9' },
    ));
  });

  it('hides Take control once takeover is active (no duplicate trigger)', async () => {
    renderLive();
    fireEvent.click(await screen.findByText('Click to take control'));    expect(screen.queryByText('Click to take control')).toBeNull();
    expect(screen.getByText('You have control')).toBeTruthy();
  });

  it('triggers the pause with a benign mouseMoved, NOT a mousePressed click at (0,0)', async () => {
    renderLive();
    fireEvent.click(await screen.findByText('Click to take control'));    const msgs = wsSent.map(m => JSON.parse(m));
    const nudge = msgs.find(m => m.method === 'Input.dispatchMouseEvent');
    expect(nudge).toBeTruthy();
    expect(nudge.params.type).toBe('mouseMoved');
    // Regression guard: the old code clicked the page's top-left corner.
    expect(msgs.some(m => m.method === 'Input.dispatchMouseEvent'
      && m.params.type === 'mousePressed' && m.params.x === 0 && m.params.y === 0)).toBe(false);
  });

  it('pastes clipboard text into the page as CDP Input.insertText during takeover', async () => {
    renderLive();
    fireEvent.click(await screen.findByText('Click to take control'));    const canvas = await screen.findByTitle('Browser agent screencast');
    wsSent = [];
    fireEvent.paste(canvas, { clipboardData: { getData: (t: string) => (t === 'text' ? 'hello@x.com' : '') } });
    const insert = wsSent.map(m => JSON.parse(m)).find(m => m.method === 'Input.insertText');
    expect(insert).toBeTruthy();
    expect(insert.params.text).toBe('hello@x.com');
  });

  it('keeps the canvas filling its slot - letterbox is drawn in-canvas, NOT via aspect-ratio CSS', async () => {
    // Regression: an earlier fix set the canvas to aspect-ratio CSS
    // (max-w/max-h-full + style.aspectRatio), which collapsed it to a tiny box
    // (and a tiny canvas = nothing to click during takeover). The frame is now
    // letterboxed by paintFrame INSIDE a slot-filling canvas, so the canvas
    // must stay h-full/w-full and never switch to the max-w-full sizing.
    renderLive();
    const canvas = await screen.findByTitle('Browser agent screencast');
    expect(canvas.className).toContain('h-full');
    expect(canvas.className).toContain('w-full');
    expect(canvas.className).not.toContain('max-w-full');
    // A frame must NOT collapse it to an aspect-ratio box.
    await act(async () => { wsLast?.pushFrame(1600, 900); });
    expect(canvas.className).toContain('h-full');
    expect(canvas.className).toContain('w-full');
    expect(canvas.className).not.toContain('max-w-full');
  });

  it('guards Resume against a double-click (a single takeover-resume POST)', async () => {
    let release: (v: unknown) => void = () => {};
    postMock.mockImplementation(() => new Promise(r => { release = r; }));
    renderLive();
    fireEvent.click(await screen.findByText('Click to take control'));    const resume = screen.getByText('Resume agent');
    fireEvent.click(resume);
    fireEvent.click(resume); // second click while the first POST is still in flight
    release({ status: 'resumed' });
    await waitFor(() => expect(postMock).toHaveBeenCalledTimes(1));
  });

  it('releases the takeover hold (POST takeover-resume) when the panel closes mid-control', async () => {
    const { unmount } = renderLive();
    fireEvent.click(await screen.findByText('Click to take control'));
    expect(screen.getByText('You have control')).toBeTruthy();
    postMock.mockClear();
    unmount(); // user closes the side-panel tab while still holding control
    await waitFor(() => expect(postMock).toHaveBeenCalledWith(
      '/internal/browser-agent/runs/run_9/nodes/node_9/takeover-resume', { session_id: 'ses_9' },
    ));
  });
});

describe('canvasToPageCoords - letterbox click mapping (the takeover interaction fix)', () => {
  // The frame is drawn object-contain inside a slot-filling canvas, so a click
  // is mapped through the letterbox box (not the full canvas) to land on the
  // right page pixel, and clicks on the black bars are ignored.
  const canvas = { getBoundingClientRect: () => ({ left: 0, top: 0, width: 600, height: 400 }) } as unknown as HTMLCanvasElement;
  const meta = { deviceWidth: 1000, deviceHeight: 500, offsetTop: 0, pageScaleFactor: 1 };
  // 600x400 canvas, 1000x500 frame -> contain scale 0.6 -> 600x300 centred (offY=50).
  const layout = { offX: 0, offY: 50, dispW: 600, dispH: 300 };

  it('maps a click at the image centre to the frame centre', () => {
    expect(canvasToPageCoords({ clientX: 300, clientY: 200 } as MouseEvent, canvas, meta, layout))
      .toEqual({ x: 500, y: 250 });
  });

  it('maps the image top-left corner to (0,0)', () => {
    expect(canvasToPageCoords({ clientX: 0, clientY: 50 } as MouseEvent, canvas, meta, layout))
      .toEqual({ x: 0, y: 0 });
  });

  it('returns null for a click on the black letterbox bar (not forwarded)', () => {
    expect(canvasToPageCoords({ clientX: 300, clientY: 10 } as MouseEvent, canvas, meta, layout))
      .toBeNull();
  });

  it('returns null when the canvas has no layout yet (zero-size rect)', () => {
    const zero = { getBoundingClientRect: () => ({ left: 0, top: 0, width: 0, height: 0 }) } as unknown as HTMLCanvasElement;
    expect(canvasToPageCoords({ clientX: 1, clientY: 1 } as MouseEvent, zero, meta, layout)).toBeNull();
  });
});
