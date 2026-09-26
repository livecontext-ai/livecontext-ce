// @vitest-environment jsdom
/**
 * The mobile left drawer closes on a swipe towards the edge it hides behind.
 *
 * Pinned here: a long enough or fast enough left swipe dismisses; a short slow one
 * snaps back; a vertical gesture (scrolling the conversation list) never drags the
 * drawer; a disabled hook (desktop, closed drawer) does nothing; a cancelled touch
 * never dismisses; and every inline style the drag wrote is cleared on release, so
 * the class-driven slide transition takes over.
 */
import React, { useRef } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPortal } from 'react-dom';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useSwipeToDismiss } from '../useSwipeToDismiss';

const PANEL_WIDTH = 256;

function Drawer({ enabled, onDismiss }: { enabled: boolean; onDismiss: () => void }) {
  const panelRef = useRef<HTMLDivElement>(null);
  const backdropRef = useRef<HTMLDivElement>(null);
  const handlers = useSwipeToDismiss({ enabled, panelRef, backdropRef, onDismiss });
  return (
    <>
      <div ref={panelRef} data-testid="panel" {...handlers}>
        <span data-testid="row">row</span>
        {/* A menu opened from the drawer is portalled to <body>: React still bubbles its
            touches to the drawer's handlers, although it is not inside the drawer's node. */}
        {createPortal(<div data-testid="portal-menu">menu</div>, document.body)}
      </div>
      <div ref={backdropRef} data-testid="backdrop" {...handlers} />
    </>
  );
}

let now = 0;
beforeEach(() => {
  now = 1_000;
  vi.spyOn(Date, 'now').mockImplementation(() => now);
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(PANEL_WIDTH);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const touch = (x: number, y = 100) => ({ touches: [{ clientX: x, clientY: y }] });

/** Drives a one-finger gesture: start, a list of moves spread over `durationMs`, release. */
function swipe(el: HTMLElement, points: Array<[number, number]>, durationMs: number) {
  const [x0, y0] = points[0];
  fireEvent.touchStart(el, touch(x0, y0));
  for (const [x, y] of points.slice(1)) fireEvent.touchMove(el, touch(x, y));
  now += durationMs;
  fireEvent.touchEnd(el, { touches: [] });
}

function setup(enabled = true) {
  const onDismiss = vi.fn();
  render(<Drawer enabled={enabled} onDismiss={onDismiss} />);
  return { onDismiss, panel: screen.getByTestId('panel'), backdrop: screen.getByTestId('backdrop') };
}

describe('useSwipeToDismiss', () => {
  it('dismisses on a slow left swipe past 30% of the panel width', () => {
    const { onDismiss, panel } = setup();
    swipe(panel, [[200, 100], [180, 102], [100, 104]], 1_000);
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('dismisses on a short but fast left flick', () => {
    const { onDismiss, panel } = setup();
    swipe(panel, [[200, 100], [150, 100]], 50);
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('snaps back on a short slow drag and clears the inline drag styles', () => {
    const { onDismiss, panel, backdrop } = setup();
    fireEvent.touchStart(panel, touch(200));
    fireEvent.touchMove(panel, touch(160));
    expect(panel.style.transform).toBe('translateX(-40px)');
    expect(panel.style.transition).toBe('none');
    expect(Number(backdrop.style.opacity)).toBeCloseTo(1 - 40 / PANEL_WIDTH);

    now += 1_000;
    fireEvent.touchEnd(panel, { touches: [] });
    expect(onDismiss).not.toHaveBeenCalled();
    expect(panel.style.transform).toBe('');
    expect(panel.style.transition).toBe('');
    expect(backdrop.style.opacity).toBe('');
  });

  it('never drags the drawer on a vertical gesture (the list keeps scrolling)', () => {
    const { onDismiss, panel } = setup();
    swipe(panel, [[200, 100], [195, 140], [60, 400]], 100);
    expect(panel.style.transform).toBe('');
    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('ignores a swipe to the right: the drawer cannot be pulled past fully open', () => {
    const { onDismiss, panel } = setup();
    fireEvent.touchStart(panel, touch(100));
    fireEvent.touchMove(panel, touch(250));
    expect(panel.style.transform).toBe('translateX(0px)');
    now += 50;
    fireEvent.touchEnd(panel, { touches: [] });
    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('also closes from a swipe on the backdrop', () => {
    const { onDismiss, backdrop } = setup();
    swipe(backdrop, [[300, 100], [150, 100]], 400);
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('does nothing when disabled (desktop, or the drawer is closed)', () => {
    const { onDismiss, panel } = setup(false);
    swipe(panel, [[200, 100], [20, 100]], 50);
    expect(panel.style.transform).toBe('');
    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('snaps back without dismissing when the touch is cancelled', () => {
    const { onDismiss, panel } = setup();
    fireEvent.touchStart(panel, touch(200));
    fireEvent.touchMove(panel, touch(20));
    fireEvent.touchCancel(panel, { touches: [] });
    expect(onDismiss).not.toHaveBeenCalled();
    expect(panel.style.transform).toBe('');
  });

  it('ignores a multi-finger touch (pinch zoom)', () => {
    const { onDismiss, panel } = setup();
    fireEvent.touchStart(panel, { touches: [{ clientX: 200, clientY: 100 }, { clientX: 220, clientY: 300 }] });
    fireEvent.touchMove(panel, touch(20));
    fireEvent.touchEnd(panel, { touches: [] });
    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('ignores a swipe inside a menu portalled out of the drawer', () => {
    const { onDismiss, panel } = setup();
    swipe(screen.getByTestId('portal-menu'), [[200, 100], [20, 100]], 50);
    expect(panel.style.transform).toBe('');
    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('leaves a tap alone (under the axis lock), so the click still lands', () => {
    const { onDismiss, panel } = setup();
    swipe(screen.getByTestId('row'), [[200, 100], [195, 103]], 30);
    expect(panel.style.transform).toBe('');
    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('does not treat a fast but tiny jitter as a flick', () => {
    const { onDismiss, panel } = setup();
    swipe(panel, [[200, 100], [185, 100]], 5);
    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('stops following once a second finger joins mid-drag, then snaps back on release', () => {
    const { onDismiss, panel } = setup();
    fireEvent.touchStart(panel, touch(200));
    fireEvent.touchMove(panel, touch(160));
    fireEvent.touchMove(panel, { touches: [{ clientX: 20, clientY: 100 }, { clientX: 40, clientY: 300 }] });
    expect(panel.style.transform).toBe('translateX(-40px)');

    now += 1_000;
    fireEvent.touchEnd(panel, { touches: [] });
    expect(onDismiss).not.toHaveBeenCalled();
    expect(panel.style.transform).toBe('');
  });
});
