// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';

const svc = vi.hoisted(() => ({ getFavoriteIds: vi.fn(), addFavorite: vi.fn(), removeFavorite: vi.fn() }));
const org = vi.hoisted(() => ({ currentOrgId: null as string | null }));
vi.mock('@/lib/api/orchestrator/favorite.service', () => ({ favoriteService: svc }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (sel: any) => sel({ currentOrgId: org.currentOrgId }),
}));

import { useResourceFavorites } from '../useResourceFavorites';

beforeEach(() => {
  vi.clearAllMocks();
  org.currentOrgId = null;
  svc.getFavoriteIds.mockResolvedValue([]);
  svc.addFavorite.mockResolvedValue(undefined);
  svc.removeFavorite.mockResolvedValue(undefined);
});

describe('useResourceFavorites', () => {
  it('loads the favorite ids for the type on mount', async () => {
    svc.getFavoriteIds.mockResolvedValue(['w1', 'w2']);
    const { result } = renderHook(() => useResourceFavorites('WORKFLOW'));
    await waitFor(() => expect(result.current.favoriteIds.has('w1')).toBe(true));
    expect(svc.getFavoriteIds).toHaveBeenCalledWith('WORKFLOW');
    expect(result.current.favoriteIds.has('w2')).toBe(true);
  });

  it('toggleFavorite optimistically adds an id and calls addFavorite', async () => {
    const { result } = renderHook(() => useResourceFavorites('TABLE'));
    await waitFor(() => expect(svc.getFavoriteIds).toHaveBeenCalled());
    act(() => result.current.toggleFavorite('t1'));
    expect(result.current.favoriteIds.has('t1')).toBe(true); // optimistic, before the request resolves
    await waitFor(() => expect(svc.addFavorite).toHaveBeenCalledWith('TABLE', 't1'));
    expect(svc.removeFavorite).not.toHaveBeenCalled();
  });

  it('toggleFavorite removes an already-favorited id and calls removeFavorite', async () => {
    svc.getFavoriteIds.mockResolvedValue(['t1']);
    const { result } = renderHook(() => useResourceFavorites('TABLE'));
    await waitFor(() => expect(result.current.favoriteIds.has('t1')).toBe(true));
    act(() => result.current.toggleFavorite('t1'));
    expect(result.current.favoriteIds.has('t1')).toBe(false); // optimistic removal
    await waitFor(() => expect(svc.removeFavorite).toHaveBeenCalledWith('TABLE', 't1'));
  });

  it('reverts the optimistic add and invokes onError when the write fails', async () => {
    const onError = vi.fn();
    svc.addFavorite.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useResourceFavorites('AGENT', onError));
    await waitFor(() => expect(svc.getFavoriteIds).toHaveBeenCalled());
    act(() => result.current.toggleFavorite('a1'));
    expect(result.current.favoriteIds.has('a1')).toBe(true); // optimistic
    await waitFor(() => expect(result.current.favoriteIds.has('a1')).toBe(false)); // reverted
    expect(onError).toHaveBeenCalledTimes(1);
  });

  it('leaves the set empty when the initial load fails (non-fatal)', async () => {
    svc.getFavoriteIds.mockRejectedValue(new Error('offline'));
    const { result } = renderHook(() => useResourceFavorites('INTERFACE'));
    await waitFor(() => expect(svc.getFavoriteIds).toHaveBeenCalled());
    expect(result.current.favoriteIds.size).toBe(0);
  });

  it('refetches the favorites when the active workspace changes', async () => {
    const { rerender } = renderHook(() => useResourceFavorites('WORKFLOW'));
    await waitFor(() => expect(svc.getFavoriteIds).toHaveBeenCalledTimes(1));
    org.currentOrgId = 'org-2';
    rerender();
    await waitFor(() => expect(svc.getFavoriteIds).toHaveBeenCalledTimes(2));
  });
});
