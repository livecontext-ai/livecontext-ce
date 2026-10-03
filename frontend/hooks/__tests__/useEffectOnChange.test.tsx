// @vitest-environment jsdom
import { StrictMode } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useEffectOnChange } from '../useEffectOnChange';

/**
 * The rule this hook carries is "the filter changed, go back to the first page". As a plain
 * effect it also ran on mount and threw away the page a reload had just restored from the
 * address. In development React runs every effect twice on mount, which is why it compares
 * values instead of skipping "the first run".
 */
describe('useEffectOnChange', () => {
  it('does not run for the values the component mounted with', () => {
    const effect = vi.fn();
    renderHook(() => useEffectOnChange(effect, ['invoice', 'name']));
    expect(effect).not.toHaveBeenCalled();
  });

  it('does not run on mount under StrictMode either, where effects run twice', () => {
    const effect = vi.fn();
    renderHook(() => useEffectOnChange(effect, ['invoice', 'name']), { wrapper: StrictMode });
    expect(effect).not.toHaveBeenCalled();
  });

  it('runs once each time a value changes, and not on a re-render with the same values', () => {
    const effect = vi.fn();
    const { rerender } = renderHook(
      ({ search }: { search: string }) => useEffectOnChange(effect, [search, 'name']),
      { initialProps: { search: 'invoice' }, wrapper: StrictMode },
    );
    rerender({ search: 'invoice' });
    expect(effect).not.toHaveBeenCalled();
    rerender({ search: 'report' });
    expect(effect).toHaveBeenCalledTimes(1);
    rerender({ search: 'report' });
    expect(effect).toHaveBeenCalledTimes(1);
  });
});
