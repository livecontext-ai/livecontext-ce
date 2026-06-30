'use client';

import { useState, useEffect, useCallback } from 'react';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { favoriteService, type FavoriteResourceType } from '@/lib/api/orchestrator/favorite.service';

/**
 * Per-user, workspace-scoped favorites for one native resource type
 * (workflow / table / interface / agent). Mirrors the Applications page handler:
 * load the favorited-id set on mount and on workspace switch, and toggle a single
 * id optimistically (revert + onError on failure).
 *
 * Returns the `favoriteIds` set (paint each card's star + float favorites to the
 * top) and `toggleFavorite(id)`. Loading is non-fatal: favorites are a
 * non-critical enhancement, so a failed fetch just yields an empty set.
 *
 * @param type    the resource kind these favorites belong to.
 * @param onError invoked when a toggle write fails (after the optimistic state is
 *                reverted) so the caller can surface a toast.
 */
export function useResourceFavorites(type: FavoriteResourceType, onError?: () => void) {
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);
  const [favoriteIds, setFavoriteIds] = useState<Set<string>>(new Set());

  // Refetch on workspace switch so personal vs org favorites never bleed across
  // workspaces (parity with the Applications page).
  useEffect(() => {
    let cancelled = false;
    favoriteService.getFavoriteIds(type)
      .then((ids) => { if (!cancelled) setFavoriteIds(new Set(ids)); })
      .catch(() => { /* favorites are a non-critical enhancement */ });
    return () => { cancelled = true; };
  }, [type, currentOrgId]);

  const toggleFavorite = useCallback((id: string) => {
    const wasFavorite = favoriteIds.has(id);
    // Optimistic flip.
    setFavoriteIds((prev) => {
      const next = new Set(prev);
      if (wasFavorite) next.delete(id); else next.add(id);
      return next;
    });
    const op = wasFavorite
      ? favoriteService.removeFavorite(type, id)
      : favoriteService.addFavorite(type, id);
    op.catch(() => {
      // Revert on failure.
      setFavoriteIds((prev) => {
        const next = new Set(prev);
        if (wasFavorite) next.add(id); else next.delete(id);
        return next;
      });
      onError?.();
    });
  }, [favoriteIds, type, onError]);

  return { favoriteIds, toggleFavorite };
}
