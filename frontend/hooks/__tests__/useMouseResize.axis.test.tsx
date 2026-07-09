/**
 * @vitest-environment jsdom
 *
 * useMouseResize drives both the right-docked panel (WIDTH, x axis) and the
 * bottom-docked panel (HEIGHT, y axis). These tests pin the axis math: x uses
 * innerWidth - clientX, y uses innerHeight - clientY, both clamped to [min, max].
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useMouseResize } from '../useMouseResize';

function setViewport(width: number, height: number) {
  Object.defineProperty(window, 'innerWidth', { value: width, configurable: true });
  Object.defineProperty(window, 'innerHeight', { value: height, configurable: true });
}

afterEach(() => {
  setViewport(1024, 768);
});

describe('useMouseResize axis', () => {
  it('x axis (default) sizes from the right edge: innerWidth - clientX', () => {
    setViewport(1000, 800);
    const setSize = vi.fn();
    const { result } = renderHook(() =>
      useMouseResize(setSize, { minWidth: 100, maxFraction: 0.9 }),
    );

    act(() => result.current.startResize());
    act(() => window.dispatchEvent(new MouseEvent('mousemove', { clientX: 700, clientY: 10 })));

    // 1000 - 700 = 300, within [100, 900]
    expect(setSize).toHaveBeenLastCalledWith(300);
  });

  it('y axis sizes from the bottom edge: innerHeight - clientY', () => {
    setViewport(1000, 800);
    const setSize = vi.fn();
    const { result } = renderHook(() =>
      useMouseResize(setSize, { axis: 'y', minWidth: 100, maxFraction: 0.9 }),
    );

    act(() => result.current.startResize());
    act(() => window.dispatchEvent(new MouseEvent('mousemove', { clientX: 10, clientY: 500 })));

    // 800 - 500 = 300, within [100, 720]
    expect(setSize).toHaveBeenLastCalledWith(300);
  });

  it('y axis clamps to the min and the max fraction of innerHeight', () => {
    setViewport(1000, 800);
    const setSize = vi.fn();
    const { result } = renderHook(() =>
      useMouseResize(setSize, { axis: 'y', minWidth: 200, maxFraction: 0.6 }),
    );
    act(() => result.current.startResize());

    // Near the bottom -> below min -> clamps up to 200.
    act(() => window.dispatchEvent(new MouseEvent('mousemove', { clientX: 0, clientY: 790 })));
    expect(setSize).toHaveBeenLastCalledWith(200);

    // Near the top -> above max (800 * 0.6 = 480) -> clamps down to 480.
    act(() => window.dispatchEvent(new MouseEvent('mousemove', { clientX: 0, clientY: 10 })));
    expect(setSize).toHaveBeenLastCalledWith(480);
  });
});
