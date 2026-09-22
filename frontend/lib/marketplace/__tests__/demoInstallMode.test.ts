// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

const hasRole = vi.fn();
vi.mock('@/lib/providers/smart-providers', () => ({
  useOptionalAuth: () => mockAuth,
}));

let mockAuth: { hasRole: (r: string) => boolean } | undefined;

import {
  setMarketplaceDemoInstall,
  useMarketplaceDemoInstall,
  useMarketplaceDemoInstallFlag,
} from '../demoInstallMode';

const KEY = 'lc.marketplace.demo-install';

describe('marketplace demo-install mode', () => {
  beforeEach(() => {
    window.localStorage.clear();
    hasRole.mockReset();
    mockAuth = { hasRole };
  });

  afterEach(() => {
    window.localStorage.clear();
  });

  it('is off by default so a normal marketplace is never altered by accident', () => {
    hasRole.mockReturnValue(true);
    const { result } = renderHook(() => useMarketplaceDemoInstall());
    expect(result.current).toBe(false);
  });

  it('turns on for an admin once the toggle is set, and off again', () => {
    hasRole.mockImplementation((r: string) => r === 'ADMIN');
    const { result } = renderHook(() => useMarketplaceDemoInstall());

    act(() => setMarketplaceDemoInstall(true));
    expect(result.current).toBe(true);

    act(() => setMarketplaceDemoInstall(false));
    expect(result.current).toBe(false);
  });

  it('stays off for a non-admin even when the stored flag says on', () => {
    window.localStorage.setItem(KEY, '1');
    hasRole.mockReturnValue(false);
    const { result } = renderHook(() => useMarketplaceDemoInstall());
    expect(result.current).toBe(false);
  });

  it('stays off with no auth provider at all (public marketplace surfaces)', () => {
    window.localStorage.setItem(KEY, '1');
    mockAuth = undefined;
    const { result } = renderHook(() => useMarketplaceDemoInstall());
    expect(result.current).toBe(false);
  });

  it('exposes the raw flag without the admin check for the settings control itself', () => {
    window.localStorage.setItem(KEY, '1');
    hasRole.mockReturnValue(false);
    const { result } = renderHook(() => useMarketplaceDemoInstallFlag());
    expect(result.current).toBe(true);
  });

  it('reacts to a change made in another tab', () => {
    hasRole.mockImplementation((r: string) => r === 'ADMIN');
    const { result } = renderHook(() => useMarketplaceDemoInstall());
    expect(result.current).toBe(false);

    act(() => {
      window.localStorage.setItem(KEY, '1');
      window.dispatchEvent(new StorageEvent('storage', { key: KEY, newValue: '1' }));
    });
    expect(result.current).toBe(true);
  });

  it('does not throw when the write is refused, and stays off', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('site data blocked');
    });
    hasRole.mockImplementation((r: string) => r === 'ADMIN');
    const { result } = renderHook(() => useMarketplaceDemoInstall());
    expect(() => act(() => setMarketplaceDemoInstall(true))).not.toThrow();
    // Nothing was persisted, so the read that follows the notification says off.
    expect(result.current).toBe(false);
    spy.mockRestore();
  });

  it('re-reads when the whole storage is cleared (storage event with a null key)', () => {
    window.localStorage.setItem(KEY, '1');
    hasRole.mockImplementation((r: string) => r === 'ADMIN');
    const { result } = renderHook(() => useMarketplaceDemoInstall());
    expect(result.current).toBe(true);

    act(() => {
      window.localStorage.clear();
      window.dispatchEvent(new StorageEvent('storage', { key: null }));
    });
    expect(result.current).toBe(false);
  });

  it('does not throw when localStorage is unavailable', () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('site data blocked');
    });
    hasRole.mockReturnValue(true);
    expect(() => renderHook(() => useMarketplaceDemoInstallFlag())).not.toThrow();
    spy.mockRestore();
  });
});
