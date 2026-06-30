'use client';

import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useMemo } from 'react';
import { useAuth } from '@/lib/providers/smart-providers';
import { useCurrentOrg } from '@/lib/stores/current-org-store';
import { sharingService, type SharedLink } from '@/lib/api/sharing.service';
import { conversationSharingService } from '@/lib/api/conversation-sharing.service';

const STALE_TIME_MS = 30_000;

const EMPTY: SharedLink[] = [];

export interface UseSharedConversationsResult {
  items: SharedLink[];
  isLoading: boolean;
  error: unknown;
  revoke: (link: SharedLink) => Promise<void>;
}

/**
 * Bell's 4th-tab (Shared) data hook - lists every resource the active
 * workspace has currently published via a {@code SharedLink}, regardless of
 * type (CONVERSATION, APPLICATION, CHAT, FORM). Visit-only by default:
 * callers pass {@code enabled} to gate the fetch on the tab being open,
 * matching the {@link useRecentActivity} pattern.
 *
 * <p>Cache key includes the active workspace ({@code currentOrgId}) so an
 * org switch invalidates without a manual refetch. The backend filters by
 * tenantId injected from {@code X-User-ID}; the hook only needs the org id
 * for cache-key purposes.
 *
 * <p>{@code revoke} drops the publication-side {@code SharedLink}. For
 * CONVERSATION rows it ALSO flips {@code conversation.share_mode} back to
 * 'off' - same reason {@link ShareLinkDialog#handleToggleActive} does it:
 * the conversation row carries its own {@code share_mode} that the gateway
 * uses for {@code cs_} token resolution, so disabling only the publication
 * link would leave the conversation reachable via its raw share token.
 * Other resource types (APPLICATION / CHAT / FORM) have no parallel
 * second-layer flag, so a single delete is enough.
 */
export function useSharedConversations(enabled: boolean): UseSharedConversationsResult {
  const { isLoading: authLoading, isAuthenticated } = useAuth();
  const { currentOrgId } = useCurrentOrg();
  const queryClient = useQueryClient();

  const queryKey = useMemo(
    () => ['shared-links-all', currentOrgId ?? 'personal'] as const,
    [currentOrgId],
  );

  const query = useQuery({
    queryKey,
    queryFn: () => sharingService.getAll(),
    enabled: enabled && !authLoading && isAuthenticated,
    staleTime: STALE_TIME_MS,
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
  });

  const revoke = useCallback(async (link: SharedLink) => {
    // Optimistic remove from the cached list so the row disappears immediately.
    const previous = queryClient.getQueryData<SharedLink[]>(queryKey) ?? EMPTY;
    queryClient.setQueryData<SharedLink[]>(
      queryKey,
      previous.filter((l) => l.id !== link.id),
    );

    try {
      // 1) Drop the publication-side shared link (kills the public URL).
      await sharingService.delete(link.id);

      // 2) CONVERSATION-only: also flip share_mode back to 'off' so the
      //    raw cs_ share token no longer resolves through the gateway. Other
      //    resource types don't carry a parallel runtime flag. Legacy rows
      //    without resourceId still succeed on the publication layer - the
      //    conversation's share_mode stays 'read' but the public URL is gone.
      if (link.resourceType === 'CONVERSATION' && link.resourceId) {
        await conversationSharingService.disableSharing(link.resourceId).catch(() => {});
      }
    } catch (e) {
      // Rollback the optimistic update on hard failure so the user sees the
      // row again rather than a misleading "revoked" state.
      queryClient.setQueryData<SharedLink[]>(queryKey, previous);
      throw e;
    }
  }, [queryClient, queryKey]);

  return {
    items: query.data ?? EMPTY,
    isLoading: authLoading || query.isLoading,
    error: query.error,
    revoke,
  };
}
