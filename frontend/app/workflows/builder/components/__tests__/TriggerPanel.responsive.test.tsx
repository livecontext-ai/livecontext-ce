// @vitest-environment jsdom
/**
 * The trigger panel on a small screen: an application rendered at phone or
 * tablet size, a narrow side panel, a rotated tablet.
 *
 * The panel is `position: fixed` and bottom-anchored, so nothing recovers it
 * once it leaves the viewport - no scrollbar reaches it and the offset only
 * resets on the next open. Five properties keep it usable:
 *
 *  1. It can be dragged BY TOUCH. The header used to listen for `mousedown` /
 *     `mousemove` only, and a finger never synthesises those during a drag, so
 *     on a touch screen the panel simply could not be moved.
 *  2. One pointer owns the drag. A second finger, or a resting palm, must not
 *     teleport the panel or end the gesture.
 *  3. A drag stops at the viewport edges, and a viewport change pulls the
 *     panel back in.
 *  4. It fits WHAT IT FLOATS OVER, not just the window: an application at
 *     phone format inside a wide browser used to get a 32rem panel spilling
 *     past both its edges.
 *  5. Starting a drag must not cancel the pointer event. Cancelling
 *     `pointerdown` suppresses the whole compatibility mouse sequence, and
 *     outside-click dismissal across this app - this panel's own 3-dots menu
 *     included - is built on `document` mousedown listeners.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, screen, cleanup, act, fireEvent, within } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

vi.mock('@/lib/api/conversationApi', () => ({
  conversationApi: {
    findWorkflowConversation: vi.fn().mockResolvedValue(null),
    createWorkflowConversation: vi.fn(),
    getRecentMessagesAsc: vi.fn().mockResolvedValue([]),
    addMessage: vi.fn(),
  },
}));

import { conversationApi } from '@/lib/api/conversationApi';

/** Make the chat tab load a message, so its scrolling list renders. */
async function withChatHistory() {
  vi.mocked(conversationApi.findWorkflowConversation).mockResolvedValue({ id: 'conv-1' } as never);
  vi.mocked(conversationApi.getRecentMessagesAsc).mockResolvedValue(
    [{ id: 'm1', role: 'user', content: 'hello' }] as never,
  );
}

vi.mock('@/lib/api', () => ({ orchestratorApi: { getWorkflow: vi.fn(), triggerSpecific: vi.fn() } }));
vi.mock('@/lib/api/orchestrator/file.service', () => ({ fileService: { uploadFile: vi.fn() } }));

vi.mock('@/components/chat/MessageComposer', () => ({
  MessageComposer: () => <div data-testid="composer" />,
}));

import {
  TriggerPanel,
  BASE_BOTTOM_PX,
  VIEWPORT_MARGIN_PX,
  type TriggerPanelConfig,
} from '../TriggerPanel';

const formTrigger: TriggerPanelConfig = {
  triggerId: 'trigger:my_form',
  triggerLabel: 'My Form',
  type: 'form',
  submitButtonText: 'Go',
  fields: [
    { id: 'f1', name: 'city', label: 'City', type: 'text' },
    { id: 'f2', name: 'notes', label: 'Notes', type: 'textarea' },
  ],
};

const chatTrigger: TriggerPanelConfig = {
  triggerId: 'trigger:my_chat',
  triggerLabel: 'My Chat',
  type: 'chat',
};

const webhookTrigger: TriggerPanelConfig = {
  triggerId: 'trigger:hook',
  triggerLabel: 'Hook',
  type: 'webhook',
};

/** A phone-sized viewport: narrower AND shorter than the panel's natural size. */
const PHONE = { width: 390, height: 700 };

/** Size the stubbed layout reports for the panel. Hand-picked: jsdom applies no CSS. */
const PANEL = { width: 374, height: 420 };

const PRIMARY_POINTER = 1;

/**
 * @param scrollbar width of a classic scrollbar, in px. The window dimensions
 *   INCLUDE it; documentElement's client box - the initial containing block a
 *   `fixed` element is laid out against - does not. Default 0 = overlay
 *   scrollbars, where the two agree.
 */
function setViewport({ width, height, scrollbar = 0 }: { width: number; height: number; scrollbar?: number }) {
  Object.defineProperty(window, 'innerWidth', { value: width, configurable: true, writable: true });
  Object.defineProperty(window, 'innerHeight', { value: height, configurable: true, writable: true });
  Object.defineProperty(document.documentElement, 'clientWidth', { value: width - scrollbar, configurable: true });
  Object.defineProperty(document.documentElement, 'clientHeight', { value: height - scrollbar, configurable: true });
}

function rectOf(width: number, height: number, left = 0): DOMRect {
  return {
    width, height, left, top: 0, right: left + width, bottom: height, x: left, y: 0,
    toJSON: () => ({}),
  } as DOMRect;
}

/**
 * jsdom lays nothing out, so every rect is zero and the clamp deliberately
 * stands down on a zero rect. Give the panel (and, when the test uses one, the
 * anchor) a plausible size so the clamp has real numbers to work with.
 */
function stubLayout(panelSize: { width: number; height: number }) {
  const original = Element.prototype.getBoundingClientRect;
  Element.prototype.getBoundingClientRect = function (this: Element) {
    if (this.getAttribute?.('data-testid') === 'trigger-panel') {
      return rectOf(panelSize.width, panelSize.height);
    }
    const anchorWidth = this.getAttribute?.('data-anchor-width');
    if (anchorWidth) {
      anchorMeasurements += 1;
      return rectOf(Number(anchorWidth), 600, Number(this.getAttribute('data-anchor-left') ?? 0));
    }
    return original.call(this);
  };
  return () => { Element.prototype.getBoundingClientRect = original; };
}


/**
 * jsdom's CSS parser (cssstyle) understands `calc()` but silently DROPS `min()`:
 * writing `max-width: min(calc(100vw - 1rem), 374px)` reads back as the empty
 * string, and the card ends up with no `style` attribute at all. That erases the
 * anchor cap these tests exist to pin, and - worse - it makes the "no cap" case
 * indistinguishable from every capped one, so an assertion of `''` would pass
 * even for a panel that IS capped.
 *
 * Recording the value as it is WRITTEN keeps the assertion end to end (component,
 * to DOM write, to the string a browser would receive) instead of retreating to
 * re-asserting the component's own formula. The real declaration is still written
 * through, so nothing else in the suite changes behaviour.
 */
const writtenMaxWidth = new WeakMap<CSSStyleDeclaration, string>();
function stubMinAwareMaxWidth(): () => void {
  const proto = CSSStyleDeclaration.prototype;
  const original = Object.getOwnPropertyDescriptor(proto, 'maxWidth');
  Object.defineProperty(proto, 'maxWidth', {
    configurable: true,
    get(this: CSSStyleDeclaration) {
      const written = writtenMaxWidth.get(this);
      if (written !== undefined) return written;
      return (original?.get?.call(this) as string | undefined) ?? '';
    },
    set(this: CSSStyleDeclaration, value: string) {
      writtenMaxWidth.set(this, value ?? '');
      original?.set?.call(this, value);
    },
  });
  return () => {
    if (original) Object.defineProperty(proto, 'maxWidth', original);
    else delete (proto as unknown as Record<string, unknown>).maxWidth;
  };
}

/** An element standing in for the application container the panel centres on. */
function makeAnchor({ width, left }: { width: number; left: number }): HTMLElement {
  const el = document.createElement('div');
  el.setAttribute('data-anchor-width', String(width));
  el.setAttribute('data-anchor-left', String(left));
  document.body.appendChild(el);
  return el;
}

/**
 * The anchor measurement is coalesced onto an animation frame, so a test has to
 * flush frames to see it. Callbacks are collected rather than run inline: a
 * synchronous stub would assign the frame id AFTER the callback cleared it,
 * wedging the component's own "one pending frame" guard permanently.
 */
const frameCallbacks = new Map<number, FrameRequestCallback>();
let nextFrameId = 1;
const flushFrames = () => {
  const pending = [...frameCallbacks.values()];
  frameCallbacks.clear();
  act(() => { pending.forEach(cb => cb(0)); });
};

/** How many times the anchor's rect has been measured - the forced reflow we throttle. */
let anchorMeasurements = 0;

/**
 * What the component asked a ResizeObserver to watch, so a test can both fire
 * the callback AND check it was pointed at the right element.
 */
const observations: Array<{ cb: () => void; targets: Element[] }> = [];
const fireObservers = () => observations.forEach(o => o.cb());

function renderPanel(props: Partial<React.ComponentProps<typeof TriggerPanel>> = {}) {
  return render(
    <TriggerPanel
      isOpen
      onClose={() => {}}
      runId="run-1"
      workflowId="wf-1"
      triggerConfigs={[formTrigger]}
      onExecuteTrigger={vi.fn(async () => [])}
      {...props}
    />,
  );
}

const panel = () => screen.getByTestId('trigger-panel');
const card = () => screen.getByTestId('trigger-panel-card');
const handle = () => screen.getByTestId('trigger-panel-drag-handle');

/**
 * Horizontal offset the panel carries, in px. Reads both positioning modes:
 * `calc(50% + Xpx)` when centred on the viewport, and a plain `<n>px` absolute
 * coordinate when centred on an anchor (subtract the anchor's own centre to
 * recover the offset).
 */
function offsetX(anchorCentre?: number): number {
  const left = panel().style.left;
  // jsdom normalises `calc(50% + -255px)` to `calc(50% - 255px)`, so read the
  // sign off the operator rather than off the number.
  const calc = /calc\(50% ([+-]) ([\d.]+)px\)/.exec(left);
  if (calc) return (calc[1] === '-' ? -1 : 1) * Number(calc[2]);
  const px = /^(-?[\d.]+)px$/.exec(left);
  if (px && anchorCentre !== undefined) return Number(px[1]) - anchorCentre;
  throw new Error(`unexpected left: "${left}"`);
}

/**
 * Gap between the panel's bottom edge and the bottom of the viewport, in px.
 * The component writes it as a plain `<n>px`; anything else is a shape change
 * this helper should refuse loudly rather than silently reinterpret.
 */
function bottomGap(): number {
  const bottom = panel().style.bottom;
  const plain = /^(-?[\d.]+)px$/.exec(bottom);
  if (!plain) throw new Error(`unexpected bottom: "${bottom}"`);
  return Number(plain[1]);
}

/**
 * jsdom ships no PointerEvent. A MouseEvent carrying a `pointerId` is the
 * shape the handlers actually read, and carrying it is what lets these tests
 * see the one-pointer-owns-the-drag rule.
 */
function pointerEvent(
  type: string,
  init: { clientX?: number; clientY?: number; pointerId?: number; button?: number } = {},
) {
  // `cancelable: true` matters: preventDefault() on a non-cancelable event is a
  // silent no-op, and the uncancelled-pointerdown guard below would never bite.
  const e = new MouseEvent(type, {
    clientX: init.clientX ?? 0,
    clientY: init.clientY ?? 0,
    button: init.button ?? 0,
    bubbles: true,
    cancelable: true,
  });
  Object.defineProperty(e, 'pointerId', { value: init.pointerId ?? PRIMARY_POINTER });
  return e;
}

/** Press the drag handle. Returns the dispatched event so a test can inspect it. */
function pressHandle({ pointerId = PRIMARY_POINTER, target = handle() } = {}) {
  const e = pointerEvent('pointerdown', { clientX: 200, clientY: 500, pointerId });
  act(() => { target.dispatchEvent(e); });
  return e;
}

/** Drag the handle by (dx, dy) with a pointer gesture - what a finger emits. */
function pointerDrag(dx: number, dy: number, { release = true, pointerId = PRIMARY_POINTER } = {}) {
  pressHandle({ pointerId });
  act(() => {
    window.dispatchEvent(pointerEvent('pointermove', { clientX: 200 + dx, clientY: 500 + dy, pointerId }));
  });
  if (release) {
    act(() => { window.dispatchEvent(pointerEvent('pointerup', { pointerId })); });
  }
}

let restoreLayout: () => void = () => {};
let restoreMaxWidth: () => void = () => {};
let originalScrollIntoView: unknown;
let originalViewport = { width: 0, height: 0 };

beforeEach(() => {
  vi.clearAllMocks();
  observations.length = 0;
  frameCallbacks.clear();
  anchorMeasurements = 0;
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
    const id = nextFrameId++;
    frameCallbacks.set(id, cb);
    return id;
  });
  vi.stubGlobal('cancelAnimationFrame', (id: number) => { frameCallbacks.delete(id); });
  originalScrollIntoView = (Element.prototype as unknown as Record<string, unknown>).scrollIntoView;
  (Element.prototype as unknown as { scrollIntoView: () => void }).scrollIntoView = vi.fn();
  vi.stubGlobal('ResizeObserver', class {
    private entry: { cb: () => void; targets: Element[] };
    constructor(cb: () => void) {
      this.entry = { cb, targets: [] };
      observations.push(this.entry);
    }
    observe(el: Element) {
      // The real API throws on a null target; a permissive stub would hide
      // exactly the kind of "observe whatever the ref happened to hold" bug
      // this component is guarded against.
      if (!el) throw new TypeError("Failed to execute 'observe' on 'ResizeObserver': parameter 1 is not of type 'Element'.");
      this.entry.targets.push(el);
    }
    unobserve() { /* no-op */ }
    disconnect() { this.entry.targets.length = 0; }
  });
  originalViewport = { width: window.innerWidth, height: window.innerHeight };
  setViewport(PHONE);
  restoreLayout = stubLayout(PANEL);
  restoreMaxWidth = stubMinAwareMaxWidth();
});

afterEach(() => {
  // Unmount FIRST, while every stub is still in place. cleanup() unmounts
  // inside act(), and act() flushes any passive effect React has not run yet.
  // A test that ends the instant its DOM commits (findBy* resolves on the
  // mutation, before React's scheduled effect flush) leaves the chat tab's
  // auto-scroll effect pending; restoring jsdom's missing scrollIntoView before
  // that flush made the effect throw "scrollIntoView is not a function", only
  // when the runner was loaded enough to lose that race (full CI run).
  cleanup();
  setViewport(originalViewport);
  restoreMaxWidth();
  restoreLayout();
  (Element.prototype as unknown as Record<string, unknown>).scrollIntoView = originalScrollIntoView;
  vi.unstubAllGlobals();
  document.body.innerHTML = '';
});

describe('TriggerPanel - dragging on a touch screen', () => {
  it('moves the panel on a pointer drag, the gesture family a finger emits', () => {
    renderPanel();
    const before = bottomGap();

    // -40px on Y = dragged upward; the panel is bottom-anchored so its gap grows.
    pointerDrag(0, -40);

    expect(bottomGap()).toBeGreaterThan(before);
  });

  it('does not depend on the mouse-only event family a finger never emits', () => {
    renderPanel();
    const before = bottomGap();

    act(() => { fireEvent.mouseDown(handle(), { clientX: 200, clientY: 500 }); });
    act(() => { window.dispatchEvent(new MouseEvent('mousemove', { clientX: 200, clientY: 460 })); });

    expect(bottomGap()).toBe(before);
  });

  it('leaves the pointerdown uncancelled, so compatibility mouse events still fire', () => {
    // Cancelling pointerdown suppresses the whole mousedown/mouseup sequence
    // for that gesture, and outside-click dismissal across this app is built on
    // document-level mousedown listeners.
    renderPanel();

    const event = pressHandle();

    expect(event.defaultPrevented).toBe(false);
  });

  it('shields its overflow menu from document-level outside-click handlers', () => {
    // The menu is portalled to document.body. React attaches its listeners at
    // the root CONTAINER, below document, so stopping propagation there really
    // does stop a document listener - which is what keeps a click INSIDE the
    // menu from being read as a click outside some other popover. Both event
    // families are guarded, because stopping one does not stop the other.
    renderPanel();
    act(() => { fireEvent.click(screen.getByTitle('menu')); });
    const item = within(screen.getByRole('menu')).getAllByRole('menuitem')[0];

    const seen: string[] = [];
    const spy = (e: Event) => seen.push(e.type);
    document.addEventListener('mousedown', spy);
    document.addEventListener('pointerdown', spy);
    act(() => {
      item.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
      item.dispatchEvent(pointerEvent('pointerdown', {}));
    });
    document.removeEventListener('mousedown', spy);
    document.removeEventListener('pointerdown', spy);

    expect(seen).toEqual([]);
  });

  it('dismisses the menu on a press that does NOT win the drag', () => {
    // A right-click, or a second finger while a first one drags, still means
    // the user is done with the menu - and can still carry the panel away from
    // the rect the menu was positioned against.
    renderPanel();
    pointerDrag(0, -40, { release: false });
    act(() => { fireEvent.click(screen.getByTitle('menu')); });
    expect(screen.getByRole('menu')).toBeTruthy();

    pressHandle({ pointerId: 2 });

    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('closes its own overflow menu when a drag starts, so the menu cannot be left floating', () => {
    // The menu is `fixed` at a rect captured when it opened; dragging the panel
    // out from under it would strand it in mid-air.
    renderPanel();
    act(() => { fireEvent.click(screen.getByTitle('menu')); });
    expect(screen.getByRole('menu')).toBeTruthy();

    pressHandle();

    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('stops following the finger when the browser cancels the touch gesture', () => {
    renderPanel();

    pointerDrag(0, -40, { release: false });
    const afterFirstMove = bottomGap();
    expect(afterFirstMove).toBeGreaterThan(16);  // the drag actually took hold

    // pointercancel, not pointerup: a scroll takeover fires only this one.
    act(() => { window.dispatchEvent(pointerEvent('pointercancel', {})); });
    act(() => { window.dispatchEvent(pointerEvent('pointermove', { clientX: 200, clientY: 200 })); });

    expect(bottomGap()).toBe(afterFirstMove);
  });

  it('does not start a drag from a tab button', () => {
    renderPanel({ triggerConfigs: [formTrigger, webhookTrigger] });
    const before = bottomGap();

    const tab = document.querySelector('[data-tab-button]') as HTMLElement;
    pressHandle({ target: tab });
    act(() => { window.dispatchEvent(pointerEvent('pointermove', { clientX: 200, clientY: 300 })); });

    expect(bottomGap()).toBe(before);

    // ...and the same gesture on the header itself DOES drag, so the guard
    // above is the tab exclusion at work and not a dead drag path.
    pointerDrag(0, -40);
    expect(bottomGap()).toBeGreaterThan(before);
  });
});

describe('TriggerPanel - one pointer owns the drag', () => {
  it('ignores moves from a second finger', () => {
    renderPanel();
    pointerDrag(0, -40, { release: false });
    const owned = bottomGap();

    // A palm or a second finger landing elsewhere on the tablet.
    act(() => {
      window.dispatchEvent(pointerEvent('pointermove', { clientX: 50, clientY: 50, pointerId: 2 }));
    });

    expect(bottomGap()).toBe(owned);
  });

  it('does not end the drag when a second finger lifts', () => {
    renderPanel();
    pointerDrag(0, -40, { release: false });
    const afterFirstMove = bottomGap();

    act(() => { window.dispatchEvent(pointerEvent('pointerup', { pointerId: 2 })); });
    act(() => {
      window.dispatchEvent(pointerEvent('pointermove', { clientX: 200, clientY: 440, pointerId: PRIMARY_POINTER }));
    });

    expect(bottomGap()).toBeGreaterThan(afterFirstMove);
  });

  it('does not let a second finger landing ON the header hijack the drag', () => {
    // The most likely physical form of the multi-touch case: a palm settling on
    // the header while a finger is already dragging it.
    renderPanel();
    pointerDrag(0, -40, { release: false });
    const owned = bottomGap();

    pressHandle({ pointerId: 2 });
    act(() => {
      window.dispatchEvent(pointerEvent('pointermove', { clientX: 200, clientY: 250, pointerId: 2 }));
    });
    expect(bottomGap()).toBe(owned);

    // The first finger still owns the gesture.
    act(() => {
      window.dispatchEvent(pointerEvent('pointermove', { clientX: 200, clientY: 430, pointerId: PRIMARY_POINTER }));
    });
    expect(bottomGap()).toBeGreaterThan(owned);
  });

  it('resists two presses landing in the same batch', () => {
    // Ownership is guarded on the ref, not on `isDragging`: two pointerdowns
    // React batches together would both read a state that has not updated yet.
    renderPanel();
    const before = bottomGap();

    act(() => {
      handle().dispatchEvent(pointerEvent('pointerdown', { clientX: 200, clientY: 500, pointerId: 1 }));
      handle().dispatchEvent(pointerEvent('pointerdown', { clientX: 100, clientY: 100, pointerId: 2 }));
    });
    act(() => {
      window.dispatchEvent(pointerEvent('pointermove', { clientX: 100, clientY: 60, pointerId: 2 }));
    });
    expect(bottomGap()).toBe(before);  // pointer 2 never took ownership

    act(() => {
      window.dispatchEvent(pointerEvent('pointermove', { clientX: 200, clientY: 460, pointerId: 1 }));
    });
    expect(bottomGap()).toBeGreaterThan(before);
  });

  it('releases ownership on pointerup, so the next gesture can take it', () => {
    // The sentinel the ownership guard reads must be restored, or the panel
    // becomes undraggable after its first drag.
    renderPanel();
    pointerDrag(0, -40);
    const afterFirst = bottomGap();

    pointerDrag(0, -40, { pointerId: 7 });

    expect(bottomGap()).toBeGreaterThan(afterFirst);
  });

  it('drops a stale drag shield when the panel is reopened', () => {
    // A pointerup lost while the panel closed would otherwise reopen with a
    // full-viewport shield up, swallowing every click on the page.
    const { rerender } = renderPanel();
    pointerDrag(0, -40, { release: false });
    expect(document.querySelector('[aria-hidden="true"].fixed.inset-0')).not.toBeNull();

    const props = {
      isOpen: false, onClose: () => {}, runId: 'run-1', workflowId: 'wf-1',
      triggerConfigs: [formTrigger], onExecuteTrigger: vi.fn(async () => []),
    };
    act(() => { rerender(<TriggerPanel {...props} />); });
    act(() => { rerender(<TriggerPanel {...props} isOpen />); });

    expect(document.querySelector('[aria-hidden="true"].fixed.inset-0')).toBeNull();
  });

  it('ignores a non-primary mouse button', () => {
    // A right-click would otherwise start a drag that runs until the next
    // pointerup, with the context menu free to swallow it.
    renderPanel();
    const before = bottomGap();

    act(() => {
      handle().dispatchEvent(pointerEvent('pointerdown', { clientX: 200, clientY: 500, button: 2 }));
    });
    act(() => { window.dispatchEvent(pointerEvent('pointermove', { clientX: 200, clientY: 300 })); });

    expect(bottomGap()).toBe(before);
  });

  it('ends the drag when the window loses focus, whatever the pointer', () => {
    renderPanel();
    pointerDrag(0, -40, { release: false });
    const afterFirstMove = bottomGap();

    act(() => { window.dispatchEvent(new Event('blur')); });
    act(() => { window.dispatchEvent(pointerEvent('pointermove', { clientX: 200, clientY: 200 })); });

    expect(bottomGap()).toBe(afterFirstMove);
  });
});

describe('TriggerPanel - staying inside the viewport', () => {
  it('stops a rightward drag at the viewport edge', () => {
    // A viewport wide enough that the panel has somewhere to go, so this
    // asserts "moved and then stopped", not "never moved".
    setViewport({ width: 900, height: PHONE.height });
    renderPanel();

    pointerDrag(5000, 0);

    expect(offsetX()).toBeGreaterThan(0);
    // visual right edge = viewportCentre + x + width / 2, kept 8px inside.
    expect(900 / 2 + offsetX() + PANEL.width / 2).toBeLessThanOrEqual(900 - 8);
  });

  it('stops a leftward drag at the viewport edge', () => {
    setViewport({ width: 900, height: PHONE.height });
    renderPanel();

    pointerDrag(-5000, 0);

    expect(offsetX()).toBeLessThan(0);
    expect(900 / 2 + offsetX() - PANEL.width / 2).toBeGreaterThanOrEqual(8);
  });

  it('stops an upward drag before the panel leaves the top of the screen', () => {
    renderPanel();

    pointerDrag(0, -5000);

    expect(bottomGap()).toBeGreaterThan(16);
    // top = innerHeight - bottomGap - height, kept 8px inside.
    expect(PHONE.height - bottomGap() - PANEL.height).toBeGreaterThanOrEqual(8);
  });

  it('stops a downward drag before the panel sinks below the bottom of the screen', () => {
    renderPanel();

    pointerDrag(0, 5000);

    expect(bottomGap()).toBeLessThan(16);
    expect(bottomGap()).toBeGreaterThanOrEqual(8);
  });

  it('pulls a panel dragged to the edge back in when the viewport shrinks', () => {
    // Start on a tablet in landscape, where the panel has room to be dragged
    // far to the right, then rotate to portrait.
    const LANDSCAPE = { width: 900, height: 600 };
    const PORTRAIT = { width: 500, height: 900 };
    setViewport(LANDSCAPE);
    renderPanel();
    pointerDrag(5000, 0);
    const landscapeOffset = offsetX();
    expect(LANDSCAPE.width / 2 + landscapeOffset + PANEL.width / 2).toBeCloseTo(LANDSCAPE.width - 8);

    // The offset chosen against the old width now points off-screen.
    act(() => {
      setViewport(PORTRAIT);
      window.dispatchEvent(new Event('resize'));
    });

    expect(offsetX()).toBeLessThan(landscapeOffset);
    expect(PORTRAIT.width / 2 + offsetX() + PANEL.width / 2).toBeLessThanOrEqual(PORTRAIT.width - 8);
  });

  it('re-clamps on an orientationchange, not only on a resize', () => {
    // A rotation on iOS reports through this event; a suite that only ever
    // fires `resize` cannot tell the listener is there.
    setViewport({ width: 900, height: 600 });
    renderPanel();
    pointerDrag(5000, 0);
    const landscapeOffset = offsetX();

    act(() => {
      setViewport({ width: 500, height: 900 });
      window.dispatchEvent(new Event('orientationchange'));
    });

    expect(offsetX()).toBeLessThan(landscapeOffset);
    expect(500 / 2 + offsetX() + PANEL.width / 2).toBeLessThanOrEqual(500 - 8);
  });

  it('renders and watches nothing when it is open but has no trigger to show', () => {
    // The application swaps its panel configs asynchronously when the user
    // switches app, so "open with an empty config list" is a real transient
    // state. Rendering it would dereference a trigger that is not there.
    const addSpy = vi.spyOn(window, 'addEventListener');
    const anchor = makeAnchor({ width: 400, left: 100 });
    render(
      <TriggerPanel
        isOpen onClose={() => {}} runId="run-1" workflowId="wf-1"
        triggerConfigs={[]} onExecuteTrigger={vi.fn(async () => [])}
        anchorElement={anchor}
      />,
    );

    expect(screen.queryByTestId('trigger-panel')).toBeNull();
    expect(addSpy.mock.calls.map(c => c[0])).not.toContain('resize');
    expect(observations).toHaveLength(0);
    addSpy.mockRestore();
  });

  it('does not watch the viewport for a panel that is closed', () => {
    // Same rationale as the anchor measurement: a closed panel needs no
    // listeners. It also renders null, so there is no ResizeObserver to count -
    // the listener registration is the only observable.
    const addSpy = vi.spyOn(window, 'addEventListener');
    render(
      <TriggerPanel
        isOpen={false} onClose={() => {}} runId="run-1" workflowId="wf-1"
        triggerConfigs={[formTrigger]} onExecuteTrigger={vi.fn(async () => [])}
      />,
    );

    const events = addSpy.mock.calls.map(c => c[0]);
    expect(events).not.toContain('resize');
    expect(events).not.toContain('orientationchange');
    expect(observations).toHaveLength(0);
    addSpy.mockRestore();
  });

  it('re-clamps when the panel itself grows, not only when the window changes', () => {
    // Collapsing/expanding or switching to a taller tab moves the panel's top
    // edge exactly as a rotation does; the ResizeObserver is what notices.
    const SHORT = { width: 390, height: 500 };
    setViewport(SHORT);
    renderPanel();
    pointerDrag(0, -5000);
    const gapWhileShort = bottomGap();

    // The panel grows past what the current offset leaves room for.
    restoreLayout();
    restoreLayout = stubLayout({ width: PANEL.width, height: 460 });
    // The observer must be pointed at the panel itself, not at nothing.
    expect(observations.some(o => o.targets.includes(panel()))).toBe(true);
    act(fireObservers);

    expect(bottomGap()).toBeLessThan(gapWhileShort);
    expect(SHORT.height - bottomGap() - 460).toBeGreaterThanOrEqual(8);
  });

  it('falls back to the window when the document reports no client box', () => {
    // Some embeddings (a detached document, an older jsdom) report 0 there;
    // clamping against 0 would pin the panel to a corner.
    setViewport({ width: 900, height: 700 });
    Object.defineProperty(document.documentElement, 'clientWidth', { value: 0, configurable: true });
    Object.defineProperty(document.documentElement, 'clientHeight', { value: 0, configurable: true });
    renderPanel();

    pointerDrag(5000, -5000);

    expect(900 / 2 + offsetX() + PANEL.width / 2).toBeCloseTo(900 - 8);
    // ...and the vertical half of the same fallback.
    expect(700 - bottomGap() - PANEL.height).toBeCloseTo(8);
  });

  it('stops at the scrollbar, not under it', () => {
    // The panel is `left: calc(50% + Xpx)`, and 50% of a `fixed` element
    // resolves against the initial containing block, which EXCLUDES a classic
    // scrollbar - while window.innerWidth includes it. Measuring against the
    // window puts the right margin out by the scrollbar's whole width.
    const SCROLLBAR = 15;
    setViewport({ width: 1000, height: 800, scrollbar: SCROLLBAR });
    renderPanel();

    pointerDrag(5000, 0);

    const layoutWidth = 1000 - SCROLLBAR;
    expect(layoutWidth / 2 + offsetX() + PANEL.width / 2).toBeCloseTo(layoutWidth - 8);
  });

  it('keeps the bottom margin clear of a horizontal scrollbar too', () => {
    const SCROLLBAR = 15;
    setViewport({ width: 900, height: 800, scrollbar: SCROLLBAR });
    renderPanel();

    pointerDrag(0, -5000);

    // top = layoutHeight - bottomGap - height, kept 8px inside.
    expect((800 - SCROLLBAR) - bottomGap() - PANEL.height).toBeCloseTo(8);
  });

  it('pins the BOTTOM edge in view when the panel is taller than the viewport', () => {
    // Vertical counterpart of the pin below. The bottom is where the submit
    // button lives, so that is the edge that must survive.
    setViewport({ width: PHONE.width, height: 300 });
    renderPanel();

    pointerDrag(0, -5000);

    expect(bottomGap()).toBe(8);
  });

  it('leaves the offset alone while only ONE axis has been laid out', () => {
    // Mid-collapse the card can report a width with no height yet. Either zero
    // means "not laid out": clamping against a half-rect snaps the panel to a
    // position computed from a dimension that does not exist.
    restoreLayout();
    restoreLayout = stubLayout({ width: PANEL.width, height: 0 });
    renderPanel();

    pointerDrag(0, -5000);

    expect(bottomGap()).toBe(16 + 5000);
  });

  it('leaves the offset alone while the panel has no layout yet', () => {
    // A zero rect means "not laid out": clamping a real offset against it
    // would snap the panel to a meaningless position.
    restoreLayout();
    restoreLayout = stubLayout({ width: 0, height: 0 });
    renderPanel();

    pointerDrag(-5000, 0);

    expect(offsetX()).toBe(-5000);
  });

  it('pins the left edge in view when the panel is wider than the viewport', () => {
    // Defence in depth: the CSS caps should make this unreachable, but if one
    // fails to apply the left edge is the one that must stay visible.
    setViewport({ width: 300, height: PHONE.height });
    renderPanel();

    pointerDrag(5000, 0);

    expect(300 / 2 + offsetX() - PANEL.width / 2).toBe(8);
  });
});

describe('TriggerPanel - centred on the application it floats over', () => {
  it('clamps against the viewport in the anchored mode too', () => {
    // The application sits hard against the right of a wide window; the panel
    // centres on IT, so an unclamped centre would hang off the screen.
    setViewport({ width: 1000, height: 800 });
    const anchor = makeAnchor({ width: 400, left: 600 });
    renderPanel({ anchorElement: anchor });
    const anchorCentre = 600 + 400 / 2;

    pointerDrag(5000, 0);

    // Moved (so this is not a dead drag path passing by accident)...
    expect(offsetX(anchorCentre)).toBeGreaterThan(0);
    // ...and stopped at the window's edge, not the application's.
    const visualLeft = anchorCentre + offsetX(anchorCentre) - PANEL.width / 2;
    expect(visualLeft + PANEL.width).toBeCloseTo(1000 - 8);
  });

  it('pulls the panel in on open when the application sits near a viewport edge', () => {
    // No drag involved: centring on an app hard against the right of the
    // window would put half the panel off-screen from the very first frame.
    setViewport({ width: 1000, height: 800 });
    const anchor = makeAnchor({ width: 300, left: 700 });
    renderPanel({ anchorElement: anchor });
    const anchorCentre = 700 + 300 / 2;

    expect(offsetX(anchorCentre)).toBeLessThan(0);
    const visualLeft = anchorCentre + offsetX(anchorCentre) - PANEL.width / 2;
    expect(visualLeft + PANEL.width).toBeCloseTo(1000 - 8);
  });

  it('does not reposition on a scroll that left the application where it was', () => {
    // measure() runs on every capture-phase scroll. Publishing an equal rect as
    // a fresh object would re-render, and through clampPosition's identity tear
    // down and rebuild the re-clamp listeners and their ResizeObserver, dozens
    // of times per touch scroll.
    setViewport({ width: 1000, height: 800 });
    const anchor = makeAnchor({ width: 400, left: 600 });
    renderPanel({ anchorElement: anchor });
    const before = panel().style.left;
    const observersBefore = observations.length;

    act(() => { window.dispatchEvent(new Event('scroll')); });
    flushFrames();

    expect(panel().style.left).toBe(before);
    expect(observations.length).toBe(observersBefore);

    // Positive control: a scroll that DID move the application repositions it.
    anchor.setAttribute('data-anchor-left', '300');
    act(() => { window.dispatchEvent(new Event('scroll')); });
    flushFrames();

    expect(panel().style.left).not.toBe(before);
  });

  it('drops a pending measurement when the application it was measuring goes away', () => {
    // A frame queued against the OLD anchor would otherwise land after the new
    // one is in place and overwrite its rect with the departed element's.
    setViewport({ width: 1000, height: 800 });
    const anchor = makeAnchor({ width: 400, left: 600 });
    const { rerender } = renderPanel({ anchorElement: anchor });

    act(() => { window.dispatchEvent(new Event('scroll')); });  // queues a frame
    act(() => {
      rerender(
        <TriggerPanel
          isOpen onClose={() => {}} runId="run-1" workflowId="wf-1"
          triggerConfigs={[formTrigger]} onExecuteTrigger={vi.fn(async () => [])}
          anchorElement={makeAnchor({ width: 200, left: 0 })}
        />,
      );
    });
    anchorMeasurements = 0;
    flushFrames();

    // The queued frame was cancelled with its effect; only the new anchor's own
    // synchronous measure ran, and it ran before this point.
    expect(anchorMeasurements).toBe(0);
  });

  it('watches nothing while the panel is closed', () => {
    // Every application tab mounts one of these. A closed panel needs no rect,
    // and a capture-phase window scroll listener plus a ResizeObserver per tab
    // is a real cost for a panel the user never opened.
    setViewport({ width: 1000, height: 800 });
    const anchor = makeAnchor({ width: 400, left: 600 });
    render(
      <TriggerPanel
        isOpen={false} onClose={() => {}} runId="run-1" workflowId="wf-1"
        triggerConfigs={[formTrigger]} onExecuteTrigger={vi.fn(async () => [])}
        anchorElement={anchor}
      />,
    );
    anchorMeasurements = 0;

    act(() => { window.dispatchEvent(new Event('scroll')); });
    flushFrames();

    expect(anchorMeasurements).toBe(0);
    expect(observations).toHaveLength(0);
  });

  it('measures the application at most once per frame however many scrolls fire', () => {
    // getBoundingClientRect forces a synchronous reflow, and the listener is
    // capture-phase on every scroller on the page.
    setViewport({ width: 1000, height: 800 });
    const anchor = makeAnchor({ width: 400, left: 600 });
    renderPanel({ anchorElement: anchor });
    anchorMeasurements = 0;

    act(() => {
      for (let i = 0; i < 20; i++) window.dispatchEvent(new Event('scroll'));
    });
    expect(anchorMeasurements).toBe(0);  // nothing measured before the frame runs

    flushFrames();
    expect(anchorMeasurements).toBe(1);
  });

  it('follows the application when it is RESIZED without moving', () => {
    // A side panel opening narrows the app in place: `left` is unchanged and
    // only `width` moves - and width is the quantity the cap consumes. A
    // change-detection that compared only `left` would freeze the cap here.
    setViewport({ width: 1400, height: 900 });
    const anchor = makeAnchor({ width: 800, left: 0 });
    renderPanel({ anchorElement: anchor });
    expect(card().style.maxWidth).toBe('min(calc(100vw - 1rem), 784px)');

    anchor.setAttribute('data-anchor-width', '390');
    act(() => { window.dispatchEvent(new Event('resize')); });
    flushFrames();

    expect(card().style.maxWidth).toBe('min(calc(100vw - 1rem), 374px)');
  });

  it('clamps a live drag against an application that moved mid-gesture', () => {
    // The drag effect closes over clampPosition; if it did not re-subscribe on
    // an anchor change, a drag begun before the rect landed would clamp against
    // the viewport centre for its whole duration.
    setViewport({ width: 1000, height: 800 });
    const anchor = makeAnchor({ width: 400, left: 0 });
    renderPanel({ anchorElement: anchor });

    pressHandle();
    act(() => {
      anchor.setAttribute('data-anchor-left', '600');
      window.dispatchEvent(new Event('resize'));
    });
    flushFrames();
    act(() => { window.dispatchEvent(pointerEvent('pointermove', { clientX: 5000, clientY: 500 })); });
    act(() => { window.dispatchEvent(pointerEvent('pointerup', {})); });

    // Clamped against the anchor's NEW centre: right edge 8px inside the window.
    const anchorCentre = 600 + 400 / 2;
    expect(anchorCentre + offsetX(anchorCentre) + PANEL.width / 2).toBeCloseTo(1000 - 8);
  });

  it('measures the application when the triggers arrive AFTER the panel opened', () => {
    // The application fills its config list asynchronously and clears it when
    // the user switches app, so "open with nothing to show, then configs" is a
    // real transient. Keying the measurement on `isOpen` while GUARDING it on
    // "has something to render" meant the effect never re-ran: no anchor rect,
    // no width cap, and the panel fell back to its full 32rem centred on the
    // viewport - the reported bug, restored.
    setViewport({ width: 1400, height: 900 });
    const anchor = makeAnchor({ width: 390, left: 500 });
    const props = {
      isOpen: true, onClose: () => {}, runId: 'run-1', workflowId: 'wf-1',
      onExecuteTrigger: vi.fn(async () => []), anchorElement: anchor,
    };
    const { rerender } = render(<TriggerPanel {...props} triggerConfigs={[]} />);
    expect(screen.queryByTestId('trigger-panel')).toBeNull();

    act(() => { rerender(<TriggerPanel {...props} triggerConfigs={[formTrigger]} />); });

    expect(card().style.maxWidth).toBe('min(calc(100vw - 1rem), 374px)');
  });

  it('caps its width to the application, not just to the window', () => {
    // The reported symptom: an app at phone format inside a wide browser got
    // the full 32rem panel spilling past both its edges. A viewport-only cap
    // never sees this.
    setViewport({ width: 1400, height: 900 });
    const anchor = makeAnchor({ width: 390, left: 500 });
    renderPanel({ anchorElement: anchor });

    // 390 minus the 8px margin on each side.
    expect(card().style.maxWidth).toBe('min(calc(100vw - 1rem), 374px)');
  });

  it('stops following a very narrow application rather than becoming a sliver', () => {
    setViewport({ width: 1400, height: 900 });
    const anchor = makeAnchor({ width: 120, left: 0 });
    renderPanel({ anchorElement: anchor });

    expect(card().style.maxWidth).toBe('min(calc(100vw - 1rem), 280px)');
  });

  it('applies no anchor cap when it floats over the whole viewport', () => {
    renderPanel();

    expect(card().style.maxWidth).toBe('');
  });
});

describe('TriggerPanel - without a ResizeObserver', () => {
  // Every test above stubs the API in, which is what let a null-dereference in
  // the no-observer branch's cleanup survive several reviews. These unstub it.
  beforeEach(() => { vi.unstubAllGlobals(); });

  it('still opens, positions and drags when the API is missing', () => {
    expect(typeof ResizeObserver).toBe('undefined');
    renderPanel();

    expect(bottomGap()).toBe(16);
    pointerDrag(0, -40);
    expect(bottomGap()).toBeGreaterThan(16);
  });

  it('still re-clamps on a resize, the listener path it degrades to', () => {
    setViewport({ width: 900, height: 600 });
    renderPanel();
    pointerDrag(5000, 0);
    const landscapeOffset = offsetX();

    act(() => {
      setViewport({ width: 500, height: 900 });
      window.dispatchEvent(new Event('resize'));
    });

    expect(offsetX()).toBeLessThan(landscapeOffset);
  });

  it('tears down cleanly, both with and without an anchor', () => {
    // The cleanup used to call `disconnect()` on an observer this branch never
    // creates - a TypeError thrown from a layout-effect cleanup, i.e. detached
    // from anything that would point at its cause.
    const anchor = makeAnchor({ width: 400, left: 100 });
    const withAnchor = renderPanel({ anchorElement: anchor });
    expect(() => withAnchor.unmount()).not.toThrow();

    const withoutAnchor = renderPanel();
    expect(() => withoutAnchor.unmount()).not.toThrow();
  });
});

describe('TriggerPanel - fitting a small screen', () => {
  it('caps the card at the viewport width and height', () => {
    renderPanel();

    // The caps must reserve exactly what the clamp reserves, or the clamp
    // window empties and the panel starts overflowing an edge. Tailwind
    // literals cannot be derived from the constants, so the coupling the
    // comment above them asserts is enforced HERE instead.
    const horizontal = (2 * VIEWPORT_MARGIN_PX) / 16;
    const vertical = (BASE_BOTTOM_PX + VIEWPORT_MARGIN_PX) / 16;
    expect(card().className).toContain(`max-w-[calc(100vw-${horizontal}rem)]`);
    expect(card().className).toContain(`max-h-[calc(100dvh-${vertical}rem)]`);
  });

  it('lays the card out as a column so the cap can squeeze the body', () => {
    // Without flex-col the body's min-h-0 and overflow-y-auto do nothing and
    // the card overflows its own max-height anyway.
    renderPanel();

    expect(card().className).toMatch(/(^|\s)flex(\s|$)/);
    expect(card().className).toContain('flex-col');
  });

  it('scrolls the form body rather than growing past the cap', () => {
    renderPanel();
    const body = screen.getByTestId('trigger-panel-body');

    expect(body.className).toContain('overflow-y-auto');
    expect(body.className).toContain('min-h-0');
  });

  it('keeps the chat composer pinned and scrolls the messages instead', () => {
    // The chat body must NOT scroll as a whole: the composer is the point of
    // the tab and would end up below the fold of the outer scroller.
    renderPanel({ triggerConfigs: [chatTrigger] });
    const body = screen.getByTestId('trigger-panel-body');

    expect(body.className).toContain('flex-col');

    // The composer must be the child that CANNOT be squeezed...
    const composerWrapper = screen.getByTestId('composer').parentElement!;
    expect(composerWrapper.className).toContain('shrink-0');

    // ...and the column it lives in must be allowed to shrink around it, or
    // the pinning does nothing.
    const column = composerWrapper.parentElement!;
    expect(column.className).toContain('flex-col');
    expect(column.className).toContain('min-h-0');
  });

  it('lets the chat message list shrink and scroll rather than pushing the composer down', async () => {
    await withChatHistory();
    renderPanel({ triggerConfigs: [chatTrigger] });

    const list = await screen.findByTestId('trigger-panel-chat-messages');
    // flex-1 + min-h-0: the list is the child that gives way when the card is
    // squeezed. Without min-h-0 a flex child keeps its content height and
    // pushes the composer out instead.
    expect(list.className).toContain('flex-1');
    expect(list.className).toContain('min-h-0');
    expect(list.className).toContain('overflow-y-auto');
    // A scroll that reaches its end must not chain to the page behind.
    expect(list.className).toContain('overscroll-contain');
  });

  it('gives the chat body a last-resort scroller so the composer can never be clipped', async () => {
    await withChatHistory();
    renderPanel({ triggerConfigs: [chatTrigger] });
    const body = screen.getByTestId('trigger-panel-body');

    expect(body.className).toContain('overflow-y-auto');
    expect(body.className).toContain('overscroll-contain');
  });

  it('claims the touch gesture on the drag handle so the page does not scroll under the finger', () => {
    renderPanel();

    expect(handle().className).toContain('touch-none');
    // Unconditionally, not only while dragging: the isDragging flip only
    // reaches the DOM on the next render, after the press has already been
    // able to start a text selection.
    expect(handle().className).toContain('select-none');
  });

  it('claims the touch gesture on the drag shield too, so a finger cannot hand it back as a scroll', () => {
    renderPanel();
    pointerDrag(0, -40, { release: false });

    const shield = document.querySelector('[aria-hidden="true"].fixed.inset-0') as HTMLElement;
    expect(shield).not.toBeNull();
    expect(shield.className).toContain('touch-none');
  });

  it('centres itself on its left coordinate', () => {
    // `left` is the panel's CENTRE, not its left edge - the whole clamp is
    // written against that, and every offset assertion in this file assumes it.
    renderPanel();

    expect(panel().style.transform).toBe('translateX(-50%)');
  });

  it('rests one 1rem gap above the bottom of the viewport', () => {
    // The clamp is written from this same constant; a test that only ever
    // asserted relative movement would let the two drift apart silently.
    renderPanel();

    expect(bottomGap()).toBe(16);
  });

  it('lets a long trigger label truncate instead of pushing the menu out of the header', () => {
    renderPanel({
      triggerConfigs: [{ ...formTrigger, formTitle: 'Customer Onboarding Request Form' }],
    });

    const label = screen.getByText('Customer Onboarding Request Form');
    expect(label.className).toContain('truncate');
    expect(label.parentElement?.className).toContain('min-w-0');
  });

  it('lets a long tab label truncate too, in the multi-trigger header', () => {
    renderPanel({
      triggerConfigs: [
        { ...formTrigger, triggerLabel: 'Customer Onboarding Request Form' },
        webhookTrigger,
      ],
    });

    const label = screen.getByText('Customer Onboarding Request Form');
    expect(label.className).toContain('truncate');
    // Both the row AND the header slot it sits in must be allowed to shrink
    // below the widest tab: a flex child defaults to min-width:auto, and one
    // un-shrinkable ancestor is enough to push the tabs out of the header.
    const row = label.closest('[data-tab-button]')!.parentElement!;
    expect(row.className).toContain('min-w-0');
    expect(row.parentElement?.className).toContain('min-w-0');
  });
});

describe('TriggerPanel - suite teardown', () => {
  it('survives a test that ends the instant the chat messages commit, before their auto-scroll effect ran', async () => {
    // What a loaded CI runner does to the findBy* tests above, made
    // deterministic: resolve on the DOM mutation itself, so the test ends with
    // the scroll-to-bottom effect still pending. The teardown must unmount
    // before it removes the scrollIntoView stub, or that pending effect throws
    // "scrollIntoView is not a function" and fails this test.
    await withChatHistory();
    renderPanel({ triggerConfigs: [chatTrigger] });
    await new Promise<void>((resolve) => {
      const done = () => document.querySelector('[data-testid="trigger-panel-chat-messages"]');
      if (done()) return resolve();
      const mo = new MutationObserver(() => { if (done()) { mo.disconnect(); resolve(); } });
      mo.observe(document.body, { subtree: true, childList: true });
    });
  });
});
