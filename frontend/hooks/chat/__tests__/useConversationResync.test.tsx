/**
 * @vitest-environment jsdom
 *
 * useConversationResync: the one place that says WHEN a surface showing a conversation's saved
 * messages re-reads them (WebSocket reconnect, a live stream's end, a live stream's error). Each
 * surface brings its own reader; these tests pin the moments.
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';

const h = vi.hoisted(() => ({ listeners: new Set<() => void>() }));
vi.mock('@/lib/websocket', () => ({
  useWsReconnected: (callback: () => void) => {
    // Mirrors the real hook: registered once, always calls the latest callback.
    h.listeners.add(callback);
  },
}));

import { useConversationResync } from '../useConversationResync';

const reconnect = () => {
  // Every render re-registers; the latest registration is the live one.
  const latest = Array.from(h.listeners).pop();
  latest?.();
};

describe('useConversationResync', () => {
  beforeEach(() => {
    h.listeners.clear();
  });

  it('re-reads the conversation on screen when the WebSocket session comes back', () => {
    const reread = vi.fn();
    renderHook(() => useConversationResync('conv-a', reread));

    act(() => reconnect());

    expect(reread).toHaveBeenCalledWith('conv-a');
  });

  it('has nothing to re-read before the conversation exists', () => {
    const reread = vi.fn();
    renderHook(() => useConversationResync(null, reread));

    act(() => reconnect());

    expect(reread).not.toHaveBeenCalled();
  });

  it('follows the conversation the surface moved to', () => {
    const reread = vi.fn();
    const { rerender } = renderHook(({ id }) => useConversationResync(id, reread), { initialProps: { id: 'conv-a' } });
    rerender({ id: 'conv-b' });

    act(() => reconnect());

    expect(reread).toHaveBeenCalledWith('conv-b');
    expect(reread).not.toHaveBeenCalledWith('conv-a');
  });

  it('re-reads when a live stream of the conversation completes or reports an error', () => {
    const reread = vi.fn();
    const { result } = renderHook(() => useConversationResync('conv-a', reread));

    result.current.onStreamComplete('conv-a');
    result.current.onError({ message: 'bridge link failed', retryable: true }, 'conv-a');

    expect(reread).toHaveBeenNthCalledWith(1, 'conv-a');
    expect(reread).toHaveBeenNthCalledWith(2, 'conv-a');
  });

  it('does not re-read a refused send (an error with no conversation id saved nothing)', () => {
    const reread = vi.fn();
    const { result } = renderHook(() => useConversationResync('conv-a', reread));

    result.current.onError({ message: 'Failed to send message', retryable: true });

    expect(reread).not.toHaveBeenCalled();
  });

  it('hands out stable callbacks that always call the latest reader', () => {
    const first = vi.fn();
    const second = vi.fn();
    const { result, rerender } = renderHook(({ reread }) => useConversationResync('conv-a', reread), {
      initialProps: { reread: first },
    });
    const callbacks = result.current;
    rerender({ reread: second });

    expect(result.current).toBe(callbacks);
    result.current.onStreamComplete('conv-a');
    expect(second).toHaveBeenCalledWith('conv-a');
    expect(first).not.toHaveBeenCalled();
  });
});
