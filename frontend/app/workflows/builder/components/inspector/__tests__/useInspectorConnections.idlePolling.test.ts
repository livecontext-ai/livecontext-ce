// @vitest-environment jsdom
/**
 * Regression tests for the handle-position poll in {@link useInspectorConnections}.
 *
 * Bug: an interval re-measured every connection handle every 100 ms for as long as
 * the inspector was open, and pushed a FRESH Map into state each tick. A new Map is
 * never referentially equal to the previous one, so React re-rendered the whole
 * panel - the step Logs grid included - ten times a second, while the user did
 * nothing. Measured on a real run with the Logs grid open: ~1.34 s of main-thread
 * JS over 5 idle seconds, against ~0 after the fix. In run mode the panel renders
 * no handles at all, so those were ten forced layouts per second for an empty map.
 *
 * The fix publishes only a real move (rounded to whole pixels, so a scrollbar's
 * sub-pixel drift is not one) and skips the measurement entirely when there is
 * nothing to measure. The positions themselves must keep updating - a handle that
 * moves still has to move the wire drawn to it.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import * as React from 'react';

import { useInspectorConnections } from '../useInspectorConnections';

type Rect = { left: number; top: number; width: number; height: number };

const rectOf = ({ left, top, width, height }: Rect) => ({
  left, top, width, height, right: left + width, bottom: top + height, x: left, y: top,
  toJSON: () => ({}),
}) as DOMRect;

/** A panel element whose own rect is fixed at the origin. */
function makePanel(): { ref: React.RefObject<HTMLDivElement>; el: HTMLDivElement } {
  const el = document.createElement('div');
  el.getBoundingClientRect = () => rectOf({ left: 0, top: 0, width: 400, height: 800 });
  document.body.appendChild(el);
  return { ref: { current: el } as React.RefObject<HTMLDivElement>, el };
}

/** A handle whose rect the test can move at will. */
function makeHandle(pos: { left: number; top: number }) {
  const el = document.createElement('div');
  const at = { ...pos };
  el.getBoundingClientRect = () => rectOf({ left: at.left, top: at.top, width: 10, height: 10 });
  return { el, moveTo: (left: number, top: number) => { at.left = left; at.top = top; } };
}

// STABLE across renders, like the real props. Rebuilding these per render makes the
// hook's effect deps change on every state update, so the poll's own setState would
// tear down and rebuild the interval - a harness artifact that buries the behaviour
// under test (and, on the pre-fix hook, never terminates).
const NODE = { id: 'n1', type: 'agentNode', position: { x: 0, y: 0 }, data: { id: 'n1', label: 'N1' } } as never;
const ALL_NODES: never[] = [];
const ON_UPDATE = vi.fn();

const setup = (panelRef: React.RefObject<HTMLDivElement>) =>
  renderHook(() =>
    useInspectorConnections({
      node: NODE,
      connectionType: 'bezier',
      allNodes: ALL_NODES,
      onUpdate: ON_UPDATE,
      panelRef,
    })
  );

/**
 * Real timers: the poll runs every 100 ms, so two ticks cost 250 ms.
 *
 * On the pre-fix hook these fail as ordinary assertions: the positions object is a
 * different Map after an idle tick, and the panel is measured when there is nothing
 * to measure. In a browser the same pathology cost ~1.34 s of main-thread JS per 5
 * idle seconds with the step Logs grid open, against ~0 after.
 */
const twoTicks = () => act(async () => { await new Promise((r) => setTimeout(r, 250)); });

afterEach(() => {
  document.body.innerHTML = '';
});

describe('useInspectorConnections - idle handle polling', () => {
  it('keeps the SAME positions object while nothing moves', async () => {
    const { ref } = makePanel();
    const { result } = setup(ref);
    const handle = makeHandle({ left: 100, top: 200 });

    act(() => { result.current.handleSetHandleRef('param-a', handle.el); });
    await twoTicks();
    const first = result.current.handlePositions;
    expect(first.get('param-a')).toMatchObject({ x: 105, y: 205 });

    // More ticks, nothing moving: before the fix each one published a fresh Map.
    await twoTicks();

    // Referential identity is the whole point: React re-renders on a new Map.
    expect(result.current.handlePositions).toBe(first);
  });

  it('publishes a new positions object as soon as a handle really moves', async () => {
    const { ref } = makePanel();
    const { result } = setup(ref);
    const handle = makeHandle({ left: 100, top: 200 });

    act(() => { result.current.handleSetHandleRef('param-a', handle.el); });
    await twoTicks();
    const before = result.current.handlePositions;

    handle.moveTo(140, 260);
    await twoTicks();

    expect(result.current.handlePositions).not.toBe(before);
    expect(result.current.handlePositions.get('param-a')).toMatchObject({ x: 145, y: 265 });
  });

  it('treats a sub-pixel drift as no move, and a whole pixel as one', async () => {
    // The comparison rounds: a scrollbar or a zoom level can jitter a rect by a
    // fraction without anything actually moving, and republishing on that would put
    // the 10 Hz re-render back exactly as it was.
    const { ref } = makePanel();
    const { result } = setup(ref);
    const handle = makeHandle({ left: 100, top: 200 });

    act(() => { result.current.handleSetHandleRef('param-a', handle.el); });
    await twoTicks();
    const before = result.current.handlePositions;

    handle.moveTo(100.2, 200.3);
    await twoTicks();
    expect(result.current.handlePositions).toBe(before);

    handle.moveTo(101, 200);
    await twoTicks();
    expect(result.current.handlePositions).not.toBe(before);
  });

  it('republishes when a handle appears or disappears', async () => {
    const { ref } = makePanel();
    const { result } = setup(ref);
    const first = makeHandle({ left: 100, top: 200 });
    const second = makeHandle({ left: 100, top: 300 });

    act(() => { result.current.handleSetHandleRef('param-a', first.el); });
    await twoTicks();
    const oneHandle = result.current.handlePositions;

    act(() => { result.current.handleSetHandleRef('param-b', second.el); });
    await twoTicks();
    expect(result.current.handlePositions).not.toBe(oneHandle);
    expect(result.current.handlePositions.size).toBe(2);

    const twoHandles = result.current.handlePositions;
    act(() => { result.current.handleSetHandleRef('param-b', null); });
    await twoTicks();
    expect(result.current.handlePositions).not.toBe(twoHandles);
    expect(result.current.handlePositions.size).toBe(1);
  });

  it('republishes when the same slot holds a DIFFERENT handle', async () => {
    // Same count, same coordinates, different id: comparing sizes alone would call
    // that unchanged and leave a wire pointing at a handle that no longer exists.
    const { ref } = makePanel();
    const { result } = setup(ref);
    const handle = makeHandle({ left: 100, top: 200 });

    act(() => { result.current.handleSetHandleRef('param-a', handle.el); });
    await twoTicks();
    const before = result.current.handlePositions;

    act(() => {
      result.current.handleSetHandleRef('param-a', null);
      result.current.handleSetHandleRef('param-b', handle.el);
    });
    await twoTicks();

    expect(result.current.handlePositions).not.toBe(before);
    expect([...result.current.handlePositions.keys()]).toEqual(['param-b']);
  });

  it('does not measure the panel at all when there is no handle to measure', async () => {
    // Run mode: the panel shows the step Logs and renders no handles. Reading the
    // panel rect anyway forced a layout ten times a second for an empty result.
    const { ref, el } = makePanel();
    const measured = vi.fn(() => rectOf({ left: 0, top: 0, width: 400, height: 800 }));
    el.getBoundingClientRect = measured;

    const { result } = setup(ref);
    await twoTicks();

    expect(measured).not.toHaveBeenCalled();
    expect(result.current.handlePositions.size).toBe(0);
  });
});
