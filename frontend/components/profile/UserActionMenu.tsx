'use client';

import React, { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
// Plain next/navigation on purpose: this menu is embedded in PublicationCard, which
// reaches MANY component trees (incl. chat) - next-intl's createNavigation cannot be
// resolved under vitest and would poison every consumer test. The locale prefix is
// re-applied by the middleware (same approach as ProfileContent's Message button).
import { useRouter } from 'next/navigation';
import { Loader2, MessageCircle, User } from 'lucide-react';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { dmApi } from '@/lib/api/dm-api';
import { cloudWebUrl } from '@/lib/edition/cloudWebUrl';
import { IS_CE } from '@/lib/edition';
import { useUserProfile } from '@/hooks/useUserProfile';

interface UserActionMenuProps {
  /** The displayed user's numeric id (publisher/reviewer ids arrive as strings). */
  userId: string | number | null | undefined;
  /** Trigger content - typically the existing avatar + name chip, rendered unchanged. */
  children: React.ReactNode;
  className?: string;
  /**
   * The displayed user is sourced from the CLOUD marketplace - a cloud-linked CE rendering
   * remote publications (publisher / reviewer ids are cloud user ids, absent from this
   * install's auth DB). A DM opened here cannot be delivered: the cloud user is not a member
   * of this install and would never see the thread (and the recipient would render as
   * "Unknown user" with the default agent avatar). So the "Send message" action is hidden in
   * remote mode. ("View profile" stays - a cloud profile proxy is a separate follow-up.)
   */
  remote?: boolean;
}

/**
 * Makes a displayed user (marketplace publisher, reviewer, app Info tab…) clickable:
 * a small popover offers "View profile" (resolves the @handle from the numeric id and
 * navigates to /app/u/{handle}) and "Send message" (opens/gets the 1:1 DM thread and
 * jumps into it). The message action is hidden on yourself, and on cloud-sourced users in a
 * cloud-linked CE ({@code remote}) where a DM can't be delivered. Without a usable id the
 * children render untouched (no dead affordance), and clicks never bubble to the
 * surrounding card.
 */
export function UserActionMenu({ userId, children, className, remote = false }: UserActionMenuProps) {
  const t = useTranslations('profile');
  const router = useRouter();
  // The DM-inbox cache refresh is best-effort: the menu is embedded in many surfaces
  // and must not require a QueryClientProvider to render (hook order stays stable -
  // useQueryClient is always called, it just throws without a provider).
  let queryClient: ReturnType<typeof useQueryClient> | null = null;
  try {
    // eslint-disable-next-line react-hooks/rules-of-hooks
    queryClient = useQueryClient();
  } catch {
    queryClient = null;
  }
  const { profile: me } = useUserProfile();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState<'profile' | 'message' | null>(null);

  if (userId == null || String(userId).trim() === '') {
    return <>{children}</>;
  }
  const targetId = String(userId);
  const isSelf = me?.id != null && String(me.id) === targetId;

  const viewProfile = async () => {
    if (busy) return;
    setBusy('profile');
    try {
      if (remote) {
        // Cloud-sourced user (cloud-linked CE): the id is a CLOUD user id absent
        // from the local auth DB, so resolve the @handle through the cloud proxy
        // and open the CLOUD profile page in a new tab. A local /app/u/{handle}
        // would 404 - and it is the cloud username/profile that the card shows.
        const profile = await unifiedApiService.getRemotePublicProfileById(targetId);
        if (profile?.handle) {
          setOpen(false);
          window.open(cloudWebUrl(`/app/u/${profile.handle}`), '_blank', 'noopener,noreferrer');
        }
        return;
      }
      // Local user: resolve the @handle (never the numeric id) and navigate locally.
      // The `remote` flag is not threaded onto every surface (e.g. the app Info
      // panel), so in CE a CLOUD publisher can reach here with a cloud id that has
      // no local profile - a local push would then bounce to the login page. So try
      // the local profile first; if there is none, fall back to the cloud profile in CE.
      let localHandle: string | undefined;
      try {
        localHandle = (await unifiedApiService.getPublicProfileById(targetId))?.handle;
      } catch {
        // No local profile (e.g. a cloud id in CE) - fall through to the cloud fallback.
      }
      if (localHandle) {
        setOpen(false);
        router.push(`/app/u/${localHandle}`);
        return;
      }
      if (IS_CE) {
        const remoteProfile = await unifiedApiService.getRemotePublicProfileById(targetId);
        if (remoteProfile?.handle) {
          setOpen(false);
          window.open(cloudWebUrl(`/app/u/${remoteProfile.handle}`), '_blank', 'noopener,noreferrer');
        }
      }
    } catch {
      // Unknown / PRIVATE profile - leave the menu open, nothing to navigate to.
    } finally {
      setBusy(null);
    }
  };

  const sendMessage = async () => {
    if (busy) return;
    setBusy('message');
    try {
      const thread = await dmApi.openThread(targetId);
      // The sidebar list caches ['dm','threads'] - surface the (possibly new) thread.
      queryClient?.invalidateQueries({ queryKey: ['dm', 'threads'] });
      setOpen(false);
      router.push(`/app/messages/${thread.id}`);
    } catch {
      // Not authenticated / self / backend error - keep the menu, no navigation.
    } finally {
      setBusy(null);
    }
  };

  const itemClass =
    'w-full flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-xs text-theme-primary transition-colors hover:bg-gray-100 dark:hover:bg-gray-700 disabled:opacity-60';

  return (
    // Click guard ABOVE the trigger: the chip is often embedded in a card wrapped in a
    // <Link> (marketplace grid), where stopPropagation alone is NOT enough - it skips
    // the Link's React handler but the anchor's NATIVE default navigation still fires.
    // And preventDefault cannot live on the trigger's own onClick: Radix composes it
    // with its internal toggle and SKIPS the toggle once the event is default-prevented.
    // Here the toggle runs first (button level), then the bubble reaches this span,
    // which kills both the Link handler and the native navigation. The menu content is
    // portaled, so its clicks never pass through this guard.
    <span
      className="contents"
      onClick={(e) => {
        e.preventDefault();
        e.stopPropagation();
      }}
    >
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          data-testid="user-action-trigger"
          className={`inline-flex min-w-0 cursor-pointer items-center gap-1.5 rounded-md transition-opacity hover:opacity-80 ${className ?? ''}`}
          title={t('viewPublicProfile')}
        >
          {children}
        </button>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        sideOffset={5}
        onClick={(e) => e.stopPropagation()}
        className="w-auto min-w-[170px] p-1.5 bg-theme-primary rounded-xl border border-gray-300/70 dark:border-gray-600/70 z-[100000]"
      >
        <div className="space-y-0.5">
          <button type="button" data-testid="user-action-view-profile" onClick={viewProfile} disabled={!!busy} className={itemClass}>
            {busy === 'profile' ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <User className="h-3.5 w-3.5" />}
            {t('viewPublicProfile')}
          </button>
          {!isSelf && !remote && (
            <button type="button" data-testid="user-action-send-message" onClick={sendMessage} disabled={!!busy} className={itemClass}>
              {busy === 'message' ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <MessageCircle className="h-3.5 w-3.5" />}
              {t('message')}
            </button>
          )}
        </div>
      </PopoverContent>
    </Popover>
    </span>
  );
}

export default UserActionMenu;
