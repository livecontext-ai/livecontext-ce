// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, cleanup, act } from '@testing-library/react';
import React from 'react';
import { useAnchorScrollToBottom } from '../useAnchorScrollToBottom';

function makeContainer({ height, viewport }: { height: number; viewport: number }) {
  const el = document.createElement('div');
  Object.defineProperty(el, 'scrollHeight', { configurable: true, value: height, writable: true });
  Object.defineProperty(el, 'clientHeight', { configurable: true, value: viewport, writable: true });
  let _scrollTop = 0;
  Object.defineProperty(el, 'scrollTop', {
    configurable: true,
    get: () => _scrollTop,
    set: (v: number) => { _scrollTop = v; },
  });
  return el;
}

class MockResizeObserver {
  callback: ResizeObserverCallback;
  static instances: MockResizeObserver[] = [];
  constructor(cb: ResizeObserverCallback) {
    this.callback = cb;
    MockResizeObserver.instances.push(this);
  }
  observe() {}
  unobserve() {}
  disconnect() {}
}

beforeEach(() => {
  MockResizeObserver.instances = [];
  (globalThis as any).ResizeObserver = MockResizeObserver;
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

async function flushRaf(times = 4) {
  // Each call resolves one rAF; the hook chains 2 internally.
  for (let i = 0; i < times; i++) {
    await act(async () => {
      await new Promise(r => requestAnimationFrame(() => r(undefined)));
    });
  }
}

describe('useAnchorScrollToBottom', () => {
  it('initialMountAnchorsToLatestMessageInsteadOfTopOfFirstPage', async () => {
    // Bug being guarded: page=0 returns the latest N messages but the viewport
    // lands at the top of that page (not on the most recent message). Fixed by
    // setting scrollTop = scrollHeight after rAFs and ResizeObserver settles.
    const container = makeContainer({ height: 5000, viewport: 800 });
    const ref = { current: container };

    renderHook(() => useAnchorScrollToBottom(ref, 'conv-1', true));

    await flushRaf();

    expect(container.scrollTop).toBe(5000);
  });

  it('emptyConversationDoesNotCrashOrLoop', async () => {
    // scrollHeight <= clientHeight → nothing to anchor; hook records "seen" and exits.
    const container = makeContainer({ height: 200, viewport: 800 });
    const ref = { current: container };

    renderHook(() => useAnchorScrollToBottom(ref, 'conv-empty', true));

    await flushRaf();

    expect(container.scrollTop).toBe(0); // never touched
  });

  it('initialScrollAbortsOnUserKeydown', async () => {
    const container = makeContainer({ height: 5000, viewport: 800 });
    const ref = { current: container };

    renderHook(() => useAnchorScrollToBottom(ref, 'conv-2', true));

    // Cancel BEFORE the rAFs run - scrollTop should never get set.
    await act(async () => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageDown' }));
    });
    await flushRaf();

    expect(container.scrollTop).toBe(0);
  });

  it('modifierOnlyKeydownDoesNotCancelTheAnchor', async () => {
    const container = makeContainer({ height: 5000, viewport: 800 });
    const ref = { current: container };

    renderHook(() => useAnchorScrollToBottom(ref, 'conv-3', true));

    // Pure Shift / Control / Alt / Meta keypresses are no-ops for scrolling
    // (modifier keys held alone don't move the viewport), so they must not
    // cancel the anchor window.
    await act(async () => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Shift' }));
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Control' }));
    });
    await flushRaf();

    expect(container.scrollTop).toBe(5000);
  });

  it('doesNotReFireOnReRenderForSameConversation', async () => {
    const container = makeContainer({ height: 5000, viewport: 800 });
    const ref = { current: container };

    const { rerender } = renderHook(
      ({ id }: { id: string }) => useAnchorScrollToBottom(ref, id, true),
      { initialProps: { id: 'conv-A' } },
    );
    await flushRaf();
    expect(container.scrollTop).toBe(5000);

    // User scrolls up to read older messages
    container.scrollTop = 1000;

    // Component re-renders for an unrelated reason - anchor must NOT yank the user back down.
    rerender({ id: 'conv-A' });
    await flushRaf();

    expect(container.scrollTop).toBe(1000);
  });

  it('reArmsOnConversationSwitch', async () => {
    const containerA = makeContainer({ height: 5000, viewport: 800 });
    const containerB = makeContainer({ height: 7000, viewport: 800 });
    let activeRef: { current: HTMLElement } = { current: containerA };

    const { rerender } = renderHook(
      ({ id }: { id: string }) => useAnchorScrollToBottom(activeRef, id, true),
      { initialProps: { id: 'conv-A' } },
    );
    await flushRaf();
    expect(containerA.scrollTop).toBe(5000);

    // Switch to a different conversation in a different container - fresh anchor.
    activeRef = { current: containerB };
    rerender({ id: 'conv-B' });
    await flushRaf();

    expect(containerB.scrollTop).toBe(7000);
  });

  it('lruEvictsOldestEntryWhenCapExceeded', async () => {
    // Configure a tiny cap to make eviction observable.
    const container = makeContainer({ height: 5000, viewport: 800 });
    const ref = { current: container };

    const { rerender } = renderHook(
      ({ id }: { id: string }) => useAnchorScrollToBottom(ref, id, true, { lruCap: 2 }),
      { initialProps: { id: 'a' } },
    );
    await flushRaf();
    rerender({ id: 'b' });
    await flushRaf();
    rerender({ id: 'c' });
    await flushRaf();
    // 'a' has now been evicted from the LRU.

    // Reset scrollTop and re-render with 'a' - because it was evicted, the anchor
    // re-fires (the hook treats it as a fresh conversation, which is the
    // documented LRU semantic).
    container.scrollTop = 0;
    rerender({ id: 'a' });
    await flushRaf();
    expect(container.scrollTop).toBe(5000);
  });
});
