// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';

const svc = vi.hoisted(() => ({ getFavoriteIds: vi.fn(), addFavorite: vi.fn(), removeFavorite: vi.fn() }));
const org = vi.hoisted(() => ({ currentOrgId: null as string | null }));
vi.mock('@/lib/api/orchestrator/favorite.service', () => ({ favoriteService: svc }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (sel: any) => sel({ currentOrgId: org.currentOrgId }),
}));

import { useBreadcrumbFavorite } from '../useBreadcrumbFavorite';

beforeEach(() => {
  vi.clearAllMocks();
  org.currentOrgId = null;
  svc.getFavoriteIds.mockResolvedValue([]);
  svc.addFavorite.mockResolvedValue(undefined);
  svc.removeFavorite.mockResolvedValue(undefined);
});

describe('useBreadcrumbFavorite', () => {
  it('is inert (isFavorite stays null, no fetch) when disabled', async () => {
    const { result } = renderHook(() => useBreadcrumbFavorite('WORKFLOW', 'w1', false));
    // give any (unwanted) effect a tick
    await Promise.resolve();
    expect(result.current.isFavorite).toBeNull();
    expect(svc.getFavoriteIds).not.toHaveBeenCalled();
  });

  it('is inert when there is no resourceId', async () => {
    const { result } = renderHook(() => useBreadcrumbFavorite('TABLE', null, true));
    await Promise.resolve();
    expect(result.current.isFavorite).toBeNull();
    expect(svc.getFavoriteIds).not.toHaveBeenCalled();
  });

  it('resolves isFavorite=true when the id is in the favorites', async () => {
    svc.getFavoriteIds.mockResolvedValue(['w1', 'w9']);
    const { result } = renderHook(() => useBreadcrumbFavorite('WORKFLOW', 'w1', true));
    await waitFor(() => expect(result.current.isFavorite).toBe(true));
    expect(svc.getFavoriteIds).toHaveBeenCalledWith('WORKFLOW');
  });

  it('resolves isFavorite=false when the id is not in the favorites', async () => {
    svc.getFavoriteIds.mockResolvedValue(['other']);
    const { result } = renderHook(() => useBreadcrumbFavorite('INTERFACE', 'if1', true));
    await waitFor(() => expect(result.current.isFavorite).toBe(false));
  });

  it('toggle optimistically favorites and calls addFavorite', async () => {
    svc.getFavoriteIds.mockResolvedValue([]);
    const { result } = renderHook(() => useBreadcrumbFavorite('WORKFLOW', 'w1', true));
    await waitFor(() => expect(result.current.isFavorite).toBe(false));
    act(() => result.current.toggle());
    expect(result.current.isFavorite).toBe(true); // optimistic
    await waitFor(() => expect(svc.addFavorite).toHaveBeenCalledWith('WORKFLOW', 'w1'));
  });

  it('toggle reverts to favorited when the unfavorite write fails', async () => {
    svc.getFavoriteIds.mockResolvedValue(['w1']);
    svc.removeFavorite.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useBreadcrumbFavorite('WORKFLOW', 'w1', true));
    await waitFor(() => expect(result.current.isFavorite).toBe(true));
    act(() => result.current.toggle());
    expect(result.current.isFavorite).toBe(false); // optimistic unfavorite
    await waitFor(() => expect(result.current.isFavorite).toBe(true)); // reverted
  });

  it('re-checks the favorite state when the active workspace changes', async () => {
    const { rerender } = renderHook(() => useBreadcrumbFavorite('WORKFLOW', 'w1', true));
    await waitFor(() => expect(svc.getFavoriteIds).toHaveBeenCalledTimes(1));
    org.currentOrgId = 'org-2';
    rerender();
    await waitFor(() => expect(svc.getFavoriteIds).toHaveBeenCalledTimes(2));
  });
});
