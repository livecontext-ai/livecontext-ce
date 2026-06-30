'use client';

import { useState, useEffect, useCallback } from 'react';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { favoriteService, type FavoriteResourceType } from '@/lib/api/orchestrator/favorite.service';

/**
 * Single-resource favorite state for a breadcrumb star (workflow / table /
 * interface detail pages). Tracks whether `resourceId` of `type` is favorited and
 * toggles it optimistically (reverts on failure). Mirrors the application
 * breadcrumb favorite in `useBreadcrumbs`, generalized over the native favorite
 * store.
 *
 * Inert when `enabled` is false or `resourceId` is null (i.e. not on a matching
 * detail page): it returns `isFavorite = null` and never fetches, so the hook is
 * safe to call unconditionally for every resource type. Workspace-scoped:
 * re-checks on active-org change.
 */
export function useBreadcrumbFavorite(
  type: FavoriteResourceType,
  resourceId: string | null,
  enabled: boolean,
) {
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);
  const [isFavorite, setIsFavorite] = useState<boolean | null>(null);

  useEffect(() => {
    if (!enabled || !resourceId) { setIsFavorite(null); return; }
    let cancelled = false;
    favoriteService.getFavoriteIds(type)
      .then((ids) => { if (!cancelled) setIsFavorite(ids.includes(resourceId)); })
      .catch(() => { if (!cancelled) setIsFavorite(false); });
    return () => { cancelled = true; };
  }, [type, resourceId, enabled, currentOrgId]);

  const toggle = useCallback(() => {
    if (!resourceId) return;
    const wasFavorite = isFavorite === true;
    setIsFavorite(!wasFavorite); // optimistic
    const op = wasFavorite
      ? favoriteService.removeFavorite(type, resourceId)
      : favoriteService.addFavorite(type, resourceId);
    op.catch(() => setIsFavorite(wasFavorite)); // revert on failure
  }, [type, resourceId, isFavorite]);

  return { isFavorite, toggle };
}
