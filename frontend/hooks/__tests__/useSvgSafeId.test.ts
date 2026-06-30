// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useSvgSafeId } from '../useSvgSafeId';

describe('useSvgSafeId', () => {
  it('returns a non-empty id containing only SVG-IRI-safe characters', () => {
    const { result } = renderHook(() => useSvgSafeId());
    expect(result.current).toBeTruthy();
    expect(result.current).toMatch(/^[a-zA-Z0-9_-]+$/);
  });

  it('is stable across re-renders of the same component', () => {
    const { result, rerender } = renderHook(() => useSvgSafeId());
    const first = result.current;
    rerender();
    expect(result.current).toBe(first);
  });

  it('gives distinct ids to two simultaneously mounted components', () => {
    const a = renderHook(() => useSvgSafeId());
    const b = renderHook(() => useSvgSafeId());
    expect(a.result.current).not.toBe(b.result.current);
  });
});
