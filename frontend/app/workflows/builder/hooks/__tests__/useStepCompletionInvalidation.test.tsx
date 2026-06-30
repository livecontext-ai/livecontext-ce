// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import {
  useStepCompletionInvalidation,
  INVALIDATION_THROTTLE_MS,
} from '../useStepCompletionInvalidation';

// Regression coverage for the 2026-06-01 /steps/paged 429 storm: a busy run
// streamed ~1,313 stepExecutionCompleted events/min and the inspector's per-hook
// 300ms debounce fanned out into ~13,742 /steps/paged calls (2,069 rate-limited
// in one minute). The shared throttle replaces the debounce; these tests pin the
// invariant that a burst can never produce one invalidation per event.

const RUN = 'run_abc';

function dispatchCompleted(detail: { runId?: string; steps?: Array<{ stepAlias?: string }> }) {
  window.dispatchEvent(new CustomEvent('stepExecutionCompleted', { detail }));
}

describe('useStepCompletionInvalidation', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('fires immediately on the leading edge', () => {
    const onInvalidate = vi.fn();
    renderHook(() => useStepCompletionInvalidation({ runId: RUN, onInvalidate }));

    dispatchCompleted({ runId: RUN });

    expect(onInvalidate).toHaveBeenCalledTimes(1);
  });

  it('collapses a burst into one leading + one trailing fire (429-storm regression)', () => {
    const onInvalidate = vi.fn();
    renderHook(() => useStepCompletionInvalidation({ runId: RUN, onInvalidate }));

    for (let i = 0; i < 50; i++) dispatchCompleted({ runId: RUN });

    // Leading fire only - NOT one per event.
    expect(onInvalidate).toHaveBeenCalledTimes(1);

    // Trailing fire lands the final state once the window elapses.
    vi.advanceTimersByTime(INVALIDATION_THROTTLE_MS);
    expect(onInvalidate).toHaveBeenCalledTimes(2);

    // Burst is drained - no further fires.
    vi.advanceTimersByTime(INVALIDATION_THROTTLE_MS * 5);
    expect(onInvalidate).toHaveBeenCalledTimes(2);
  });

  it('caps a continuous high-frequency stream to ~one fire per window', () => {
    const onInvalidate = vi.fn();
    renderHook(() => useStepCompletionInvalidation({ runId: RUN, onInvalidate }));

    // 10s of events at 50ms cadence (200 events) - 200 invalidations if unthrottled.
    for (let t = 0; t < 10_000; t += 50) {
      dispatchCompleted({ runId: RUN });
      vi.advanceTimersByTime(50);
    }

    // ~10s / 1.5s window ⇒ single-digit fires, not 200.
    expect(onInvalidate.mock.calls.length).toBeGreaterThan(0);
    expect(onInvalidate.mock.calls.length).toBeLessThanOrEqual(10);
  });

  it('ignores events for a different run', () => {
    const onInvalidate = vi.fn();
    renderHook(() => useStepCompletionInvalidation({ runId: RUN, onInvalidate }));

    dispatchCompleted({ runId: 'run_other' });
    vi.advanceTimersByTime(INVALIDATION_THROTTLE_MS);

    expect(onInvalidate).not.toHaveBeenCalled();
  });

  it('requires a matching stepAlias when one is provided', () => {
    const onInvalidate = vi.fn();
    renderHook(() =>
      useStepCompletionInvalidation({ runId: RUN, stepAlias: 'send_email', onInvalidate }),
    );

    dispatchCompleted({ runId: RUN, steps: [{ stepAlias: 'other_node' }] });
    expect(onInvalidate).not.toHaveBeenCalled();

    dispatchCompleted({ runId: RUN, steps: [{ stepAlias: 'send_email' }] });
    expect(onInvalidate).toHaveBeenCalledTimes(1);
  });

  it('fires for any step when no alias is provided (aggregated case)', () => {
    const onInvalidate = vi.fn();
    renderHook(() => useStepCompletionInvalidation({ runId: RUN, onInvalidate }));

    dispatchCompleted({ runId: RUN, steps: [{ stepAlias: 'whatever' }] });

    expect(onInvalidate).toHaveBeenCalledTimes(1);
  });

  it('does not subscribe when disabled', () => {
    const onInvalidate = vi.fn();
    renderHook(() =>
      useStepCompletionInvalidation({ runId: RUN, stepAlias: 'x', enabled: false, onInvalidate }),
    );

    dispatchCompleted({ runId: RUN, steps: [{ stepAlias: 'x' }] });
    vi.advanceTimersByTime(INVALIDATION_THROTTLE_MS);

    expect(onInvalidate).not.toHaveBeenCalled();
  });

  it('removes the listener and clears a pending trailing timer on unmount', () => {
    const onInvalidate = vi.fn();
    const { unmount } = renderHook(() =>
      useStepCompletionInvalidation({ runId: RUN, onInvalidate }),
    );

    dispatchCompleted({ runId: RUN }); // leading fire
    expect(onInvalidate).toHaveBeenCalledTimes(1);

    dispatchCompleted({ runId: RUN }); // schedules a trailing fire
    unmount();
    vi.advanceTimersByTime(INVALIDATION_THROTTLE_MS * 2);
    dispatchCompleted({ runId: RUN }); // listener gone - ignored

    expect(onInvalidate).toHaveBeenCalledTimes(1);
  });

  it('uses the latest callback without re-subscribing across renders', () => {
    const first = vi.fn();
    const second = vi.fn();
    const addSpy = vi.spyOn(window, 'addEventListener');

    const { rerender } = renderHook(
      ({ cb }) => useStepCompletionInvalidation({ runId: RUN, onInvalidate: cb }),
      { initialProps: { cb: first } },
    );
    const subsAfterMount = addSpy.mock.calls.filter((c) => c[0] === 'stepExecutionCompleted').length;

    rerender({ cb: second });
    const subsAfterRerender = addSpy.mock.calls.filter((c) => c[0] === 'stepExecutionCompleted').length;
    expect(subsAfterRerender).toBe(subsAfterMount); // no tear-down/re-subscribe

    dispatchCompleted({ runId: RUN });
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);

    addSpy.mockRestore();
  });
});
