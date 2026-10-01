'use client';

import React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { Bell, BellRing, Loader2 } from 'lucide-react';
import { orchestratorApi } from '@/lib/api';
import { ApiError } from '@/lib/api/api-client';
import type { CreatorFollowStatus } from '@/lib/api/orchestrator/publication.service';

/** Shared with the profile header, which shows the follower count from the same read. */
export const creatorFollowQueryKey = (creatorId: string | number) =>
  ['creatorFollow', String(creatorId)] as const;

/** The follow status read, shared by the button and the profile's follower count. */
export function useCreatorFollow(creatorId: string | number, enabled = true) {
  return useQuery<CreatorFollowStatus>({
    queryKey: creatorFollowQueryKey(creatorId),
    queryFn: () => orchestratorApi.getCreatorFollow(creatorId),
    enabled,
    retry: false,
    staleTime: 60_000,
  });
}

const isUnauthenticated = (error: unknown) => error instanceof ApiError && error.status === 401;

export interface FollowCreatorButtonProps {
  /** Numeric user id of the creator whose profile is shown. */
  creatorId: string | number;
}

/**
 * Subscribe to a creator from their profile: the viewer is then notified in the bell
 * each time this creator publishes a new app to the marketplace.
 *
 * <p>Filled when not yet following (the call to action), outlined once following.
 * A viewer who is not signed in is sent to the login page, like the Message button;
 * any other failure stays on the page with an inline message.
 */
export function FollowCreatorButton({ creatorId }: FollowCreatorButtonProps) {
  const t = useTranslations('profile');
  const queryClient = useQueryClient();
  const { data } = useCreatorFollow(creatorId);
  const following = data?.following ?? false;

  const mutation = useMutation({
    mutationFn: (follow: boolean) =>
      follow ? orchestratorApi.followCreator(creatorId) : orchestratorApi.unfollowCreator(creatorId),
    onSuccess: (status) => queryClient.setQueryData(creatorFollowQueryKey(creatorId), status),
    onError: (error) => {
      if (isUnauthenticated(error)) {
        window.location.href = '/login';
      }
    },
  });

  return (
    <div className="flex flex-shrink-0 flex-col items-center gap-1 sm:items-end">
      <button
        type="button"
        onClick={() => mutation.mutate(!following)}
        disabled={mutation.isPending}
        aria-pressed={following}
        title={following ? t('unfollowHint') : t('followHint')}
        data-testid="follow-creator-button"
        className={
          following
            ? 'inline-flex items-center gap-1.5 rounded-lg border border-theme bg-transparent px-3 py-1.5 text-sm text-theme-primary transition-colors hover:bg-surface-hover disabled:opacity-60'
            : 'inline-flex items-center gap-1.5 rounded-lg bg-[var(--accent-primary)] px-3 py-1.5 text-sm font-medium text-[var(--accent-foreground)] transition-opacity hover:opacity-90 disabled:opacity-60'
        }
      >
        {mutation.isPending ? (
          <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
        ) : following ? (
          <BellRing className="h-3.5 w-3.5" aria-hidden="true" />
        ) : (
          <Bell className="h-3.5 w-3.5" aria-hidden="true" />
        )}
        {following ? t('following') : t('follow')}
      </button>
      {mutation.isError && !isUnauthenticated(mutation.error) && (
        <p role="alert" className="text-xs text-red-500">
          {t('followError')}
        </p>
      )}
    </div>
  );
}
