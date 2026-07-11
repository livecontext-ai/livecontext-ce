'use client';

import React, { useEffect, useMemo, useState } from 'react';
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { usePathname, useRouter } from '@/i18n/navigation';
import { Trash2 } from 'lucide-react';
import { SearchField } from '@/components/ui/search-field';
import { ConfirmDeleteModal } from '@/components/chat/ConfirmDeleteModal';
import { dmApi, type DmInboxEvent, type DmThread } from '@/lib/api/dm-api';
import { organizationApi, type OrganizationMember } from '@/lib/api/organization-api';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { useUserProfile } from '@/hooks/useUserProfile';
import { useCurrentOrg } from '@/lib/stores/current-org-store';
import { useChannel } from '@/lib/websocket/use-channel';
import { AvatarDisplay } from '@/components/agents';

/** Conversation-list filter driven by the sidebar-header filter button. */
export type DmListFilter = 'all' | 'teammates' | 'others';

interface DmSidebarListProps {
  /** Show only workspace-teammate conversations, only the others, or everything. */
  filter?: DmListFilter;
  /** Render the conversation-search input at the top of the list. */
  searchOpen?: boolean;
}

/**
 * The sidebar "Messages" view (shown when the Chats⇄Messages toggle is flipped).
 * Conversations are grouped with a demarcation: workspace TEAMMATES first (existing
 * threads + teammates without one), then OTHER conversations (DMs are identity-level,
 * so a thread can outlive a workspace or involve someone outside it). The header's
 * filter narrows to one group; the search input filters conversations by name.
 * Clicking a row opens/navigates to that conversation; the open thread is highlighted.
 * Refreshes live on dm-inbox events.
 */
export function DmSidebarList({ filter = 'all', searchOpen = false }: DmSidebarListProps) {
  const t = useTranslations('dm');
  const tSearch = useTranslations('globalSearch');
  const router = useRouter();
  const pathname = usePathname();
  const { profile } = useUserProfile();
  const { currentOrgId } = useCurrentOrg();
  const myUserId = profile?.id != null ? String(profile.id) : undefined;
  const [query, setQuery] = useState('');
  const queryClient = useQueryClient();
  // Conversation pending deletion (confirm modal); null = closed.
  const [threadToDelete, setThreadToDelete] = useState<DmThread | null>(null);
  const [deleting, setDeleting] = useState(false);

  const { data: threads, isLoading: threadsLoading, refetch } = useQuery({
    queryKey: ['dm', 'threads'],
    queryFn: () => dmApi.listThreads(),
  });

  const { data: org, isLoading: orgLoading } = useQuery({
    queryKey: ['organization', currentOrgId, 'members'],
    queryFn: () => organizationApi.getOrganization(currentOrgId!),
    enabled: !!currentOrgId,
  });

  // Live: a new incoming DM refreshes the thread list (unread counts + ordering).
  useChannel<DmInboxEvent>(myUserId ? `dm-inbox:${myUserId}` : null, () => {
    refetch();
  });

  const memberById = useMemo(() => {
    const map = new Map<string, OrganizationMember>();
    for (const m of org?.members ?? []) {
      map.set(String(m.userId), m);
    }
    return map;
  }, [org]);

  const teammates = useMemo(
    () => (org?.members ?? []).filter((m) => String(m.userId) !== myUserId),
    [org, myUserId],
  );

  // Demarcation: a conversation is a "teammate" one iff the other participant is a
  // member of the ACTIVE workspace; everything else (left members, cross-workspace
  // contacts) falls in "other conversations".
  const teammateThreads = useMemo(
    () => (threads ?? []).filter((th) => memberById.has(th.otherUserId)),
    [threads, memberById],
  );
  const otherThreads = useMemo(
    () => (threads ?? []).filter((th) => !memberById.has(th.otherUserId)),
    [threads, memberById],
  );

  // Teammates that don't already have a thread → shown as "start a conversation" rows.
  const threadOtherIds = useMemo(
    () => new Set((threads ?? []).map((th) => th.otherUserId)),
    [threads],
  );
  const teammatesWithoutThread = teammates.filter((m) => !threadOtherIds.has(String(m.userId)));

  // Resolve display names for non-teammates (no org-member row to read from) via their
  // public profile. Bounded by the user's own thread list, cached per user id.
  const otherProfiles = useQueries({
    queries: otherThreads.map((th) => ({
      queryKey: ['publicProfile', 'id', th.otherUserId],
      queryFn: () => unifiedApiService.getPublicProfileById(th.otherUserId),
      retry: false,
      staleTime: 5 * 60_000,
    })),
  });
  const otherNameById = useMemo(() => {
    const map = new Map<string, { name?: string; avatarUrl?: string | null }>();
    otherThreads.forEach((th, i) => {
      const p = otherProfiles[i]?.data;
      if (p) map.set(th.otherUserId, { name: p.displayName ?? undefined, avatarUrl: p.avatarUrl });
    });
    return map;
  }, [otherThreads, otherProfiles]);

  const openTeammate = async (member: OrganizationMember) => {
    const thread = await dmApi.openThread(String(member.userId));
    router.push(`/app/messages/${thread.id}`);
  };

  const labelFor = (otherUserId: string) => {
    const m = memberById.get(otherUserId);
    if (m) return m.displayName || m.email;
    return otherNameById.get(otherUserId)?.name || t('unknownUser');
  };

  const avatarUrlFor = (otherUserId: string) => {
    const m = memberById.get(otherUserId);
    if (m) return m.avatarUrl ? `/api/users/${otherUserId}/avatar` : undefined;
    return otherNameById.get(otherUserId)?.avatarUrl ? `/api/users/${otherUserId}/avatar` : undefined;
  };

  const isActiveThread = (threadId: string) =>
    (pathname ?? '').endsWith(`/app/messages/${threadId}`);

  // One-sided soft delete: only offered for NON-teammate conversations - a teammate
  // is a permanent contact of the workspace (their row would come right back anyway).
  const confirmDelete = async () => {
    if (!threadToDelete || deleting) return;
    setDeleting(true);
    try {
      await dmApi.deleteThread(threadToDelete.id);
      await queryClient.invalidateQueries({ queryKey: ['dm', 'threads'] });
      if (isActiveThread(threadToDelete.id)) {
        router.push('/app/chat'); // the open conversation just vanished from the inbox
      }
      setThreadToDelete(null);
    } catch {
      // Keep the modal open - the user can retry or cancel.
    } finally {
      setDeleting(false);
    }
  };

  // A closed search must not keep filtering invisibly - drop the query the moment the
  // input is hidden (and ignore any stale text if it reopens before the state clears).
  useEffect(() => {
    if (!searchOpen) setQuery('');
  }, [searchOpen]);

  const q = searchOpen ? query.trim().toLowerCase() : '';
  const matches = (label: string) => !q || label.toLowerCase().includes(q);

  const visibleTeammateThreads = teammateThreads.filter((th) => matches(labelFor(th.otherUserId)));
  const visibleTeammatesWithoutThread = teammatesWithoutThread.filter((m) =>
    matches(m.displayName || m.email),
  );
  const visibleOtherThreads = otherThreads.filter((th) => matches(labelFor(th.otherUserId)));

  const showTeammatesGroup = filter !== 'others';
  const showOthersGroup = filter !== 'teammates';
  const loading = threadsLoading || (!!currentOrgId && orgLoading);

  // A div with button semantics: the row navigates, while the (non-teammate) trash is
  // a real nested control - <button> inside <button> would be invalid HTML.
  const threadRow = (th: DmThread) => {
    const isTeammate = memberById.has(th.otherUserId);
    return (
      <div
        key={th.id}
        role="button"
        tabIndex={0}
        onClick={() => router.push(`/app/messages/${th.id}`)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') router.push(`/app/messages/${th.id}`);
        }}
        data-testid={`dm-thread-${th.id}`}
        data-active={isActiveThread(th.id) || undefined}
        className={`group flex w-full cursor-pointer items-center gap-2 rounded-lg px-1 py-1.5 my-0.5 text-left transition-colors ${
          isActiveThread(th.id) ? 'bg-surface-hover font-medium' : 'hover:bg-surface-hover'
        }`}
      >
        <AvatarDisplay
          avatarUrl={avatarUrlFor(th.otherUserId)}
          name={labelFor(th.otherUserId)}
          size="sm"
          className="!h-6 !w-6 flex-shrink-0"
        />
        <span className="min-w-0 flex-1 truncate text-sm text-theme-secondary group-hover:text-theme-primary group-[.bg-surface-hover]:text-theme-primary transition-colors">{labelFor(th.otherUserId)}</span>
        {th.unreadCount > 0 && (
          <span className="flex-shrink-0 rounded-full bg-[var(--accent-primary)] px-1.5 text-xs font-medium text-white">
            {th.unreadCount}
          </span>
        )}
        {/* Delete (soft, one-sided) - NEVER offered for workspace teammates. */}
        {!isTeammate && (
          <button
            type="button"
            data-testid={`dm-thread-delete-${th.id}`}
            onClick={(e) => {
              e.stopPropagation();
              setThreadToDelete(th);
            }}
            className="flex-shrink-0 rounded p-0.5 text-theme-muted opacity-0 transition-opacity hover:text-red-500 focus:opacity-100 group-hover:opacity-100"
            title={t('deleteConversation')}
          >
            <Trash2 className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
    );
  };

  return (
    <div className="sidebar-scroll flex-1 space-y-0.5 overflow-y-auto px-4">
      {/* Conversation search - toggled from the sidebar-header search button. */}
      {searchOpen && (
        <div className="pb-1">
          <SearchField
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onClear={() => setQuery('')}
            clearLabel={tSearch('clear')}
            placeholder={t('searchPlaceholder')}
            data-testid="dm-search-input"
            autoFocus
          />
        </div>
      )}

      {loading ? (
        // Skeleton rows - avoids the avatar/name pop-in while threads + members load.
        <div data-testid="dm-list-skeleton" className="space-y-0.5">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={`dm-skel-${i}`} className="flex items-center gap-2 rounded-lg px-1 py-2">
              <div className="h-6 w-6 flex-shrink-0 animate-pulse rounded-full bg-theme-tertiary" />
              <div className="h-3.5 w-2/3 animate-pulse rounded bg-theme-tertiary" />
            </div>
          ))}
        </div>
      ) : (
        <>
          {showTeammatesGroup && (
            <>
              {(visibleTeammateThreads.length > 0 || visibleTeammatesWithoutThread.length > 0) && (
                <>
                  {/* Same demarcation as the one above "Other conversations". */}
                  <div className="my-2 border-t border-theme" data-testid="dm-teammates-divider" />
                  <p className="px-1 pb-1 pt-1 text-xs font-medium uppercase tracking-wide text-theme-muted">
                    {t('teammates')}
                  </p>
                </>
              )}
              {visibleTeammateThreads.map(threadRow)}
              {visibleTeammatesWithoutThread.map((m) => (
                <button
                  key={m.userId}
                  onClick={() => openTeammate(m)}
                  className="flex w-full items-center gap-2 rounded-lg px-1 py-2 text-left transition-colors hover:bg-surface-hover"
                >
                  <AvatarDisplay
                    avatarUrl={m.avatarUrl ? `/api/users/${m.userId}/avatar` : undefined}
                    name={m.displayName || m.email}
                    size="sm"
                    className="!h-6 !w-6 flex-shrink-0"
                  />
                  <span className="min-w-0 flex-1 truncate text-sm text-theme-secondary">{m.displayName || m.email}</span>
                </button>
              ))}
            </>
          )}

          {/* Demarcation between workspace teammates and everything else. */}
          {showTeammatesGroup && showOthersGroup && visibleOtherThreads.length > 0 && (
            <div className="my-2 border-t border-theme" data-testid="dm-groups-divider" />
          )}

          {showOthersGroup && visibleOtherThreads.length > 0 && (
            <>
              <p className="px-1 pb-1 pt-1 text-xs font-medium uppercase tracking-wide text-theme-muted">
                {t('otherConversations')}
              </p>
              {visibleOtherThreads.map(threadRow)}
            </>
          )}

          {/* Feedback only for a fruitless SEARCH - the bare empty state stays silent
              (historical behaviour: teammates double as default contacts). */}
          {q !== '' &&
            (!showTeammatesGroup || (visibleTeammateThreads.length === 0 && visibleTeammatesWithoutThread.length === 0)) &&
            (!showOthersGroup || visibleOtherThreads.length === 0) && (
              <p className="px-1 pt-2 text-sm text-theme-muted">{t('noThreads')}</p>
            )}
        </>
      )}

      <ConfirmDeleteModal
        isOpen={threadToDelete !== null}
        title={t('deleteConversation')}
        message={t('deleteConversationMessage', {
          name: threadToDelete ? labelFor(threadToDelete.otherUserId) : '',
        })}
        onConfirm={confirmDelete}
        onCancel={() => setThreadToDelete(null)}
        isLoading={deleting}
      />
    </div>
  );
}

export default DmSidebarList;
