// @vitest-environment jsdom
/**
 * DirectionHandleSync re-measures ReactFlow node handles when the reading direction
 * flips, so edges reconnect to the handles' NEW edge (top/bottom <-> left/right).
 *
 * Regression (2026-07-17): a SINGLE requestAnimationFrame fired before the browser
 * committed the moved handles' box, so `updateNodeInternals` read the OLD handle
 * position and every edge drew to where the handle USED to be - the visible gap
 * between the arrow and the handle on toggle. The fix re-measures across a DOUBLE
 * rAF (measure again after layout is committed). These tests pin that contract.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render } from '@testing-library/react';
import * as React from 'react';

const updateNodeInternals = vi.fn();
vi.mock('reactflow', () => ({
  useUpdateNodeInternals: () => updateNodeInternals,
}));

import { DirectionHandleSync } from '../DirectionHandleSync';

// A controllable requestAnimationFrame: callbacks queue and only run when we flush a
// frame, so we can assert what happens on the first vs the second frame.
let rafQueue: Array<() => void> = [];
let rafId = 0;
function flushFrame() {
  const toRun = rafQueue;
  rafQueue = [];
  toRun.forEach((cb) => cb());
}

beforeEach(() => {
  updateNodeInternals.mockClear();
  rafQueue = [];
  rafId = 0;
  vi.stubGlobal('requestAnimationFrame', (cb: () => void) => {
    rafQueue.push(cb);
    return ++rafId;
  });
  vi.stubGlobal('cancelAnimationFrame', vi.fn());
});
afterEach(() => {
  vi.unstubAllGlobals();
});

describe('DirectionHandleSync', () => {
  it('does NOT re-measure on the initial mount (no direction change yet)', () => {
    render(<DirectionHandleSync direction="horizontal" nodeIds={['a', 'b']} />);
    flushFrame();
    expect(updateNodeInternals).not.toHaveBeenCalled();
  });

  it('re-measures every node across TWO frames on a direction flip', () => {
    const { rerender } = render(<DirectionHandleSync direction="horizontal" nodeIds={['a', 'b']} />);
    updateNodeInternals.mockClear();

    rerender(<DirectionHandleSync direction="vertical" nodeIds={['a', 'b']} />);

    // First frame: measured once per node.
    flushFrame();
    expect(updateNodeInternals.mock.calls.map((c) => c[0])).toEqual(['a', 'b']);

    // Second frame (the fix): measured AGAIN per node, after layout is committed.
    flushFrame();
    expect(updateNodeInternals).toHaveBeenCalledTimes(4);
    expect(updateNodeInternals).toHaveBeenCalledWith('a');
    expect(updateNodeInternals).toHaveBeenCalledWith('b');
  });

  it('does NOT re-measure when re-rendered with the SAME direction (e.g. an unrelated edit)', () => {
    const { rerender } = render(<DirectionHandleSync direction="vertical" nodeIds={['a']} />);
    updateNodeInternals.mockClear();

    rerender(<DirectionHandleSync direction="vertical" nodeIds={['a', 'b', 'c']} />);
    flushFrame();
    flushFrame();
    expect(updateNodeInternals).not.toHaveBeenCalled();
  });

  it('uses the LATEST node ids at fire time, not the ids present when the flip started', () => {
    const { rerender } = render(<DirectionHandleSync direction="horizontal" nodeIds={['a']} />);
    updateNodeInternals.mockClear();

    // Flip direction and add a node in the same commit; the re-measure must cover the
    // node that was just added, not only the stale ['a'].
    rerender(<DirectionHandleSync direction="vertical" nodeIds={['a', 'b']} />);
    flushFrame();
    expect(updateNodeInternals.mock.calls.map((c) => c[0])).toEqual(['a', 'b']);
  });
});
