'use client';

import React, { useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Bell, Bot, AppWindow, Workflow, Clock, Webhook, MessageSquare, FormInput, Zap, Trash2, ChevronLeft, ChevronRight, UserPlus, Table, Sparkles, BookOpen, Monitor, Share2, Copy, Check, ExternalLink, MessageCircle, MessagesSquare, FileText, Trophy } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { EpochStatusIcon } from '@/components/workflow/EpochStatusIcon';
import { getRunStatusLabel } from '@/lib/utils/runStatusUtils';
import { ServiceIcon } from '@/components/ui/service-icon';
import { AvatarDisplay } from '@/components/agents';
import { NodeIcon } from '@/app/workflows/builder/components/nodes/shared';
import { useHomeStatus, useRefreshHomeStatus } from '@/hooks/useHomeStatus';
import { useNotificationsPaged } from '@/hooks/useNotificationsPaged';
import { useRecentActivity } from '@/hooks/useRecentActivity';
import { useSharedConversations } from '@/hooks/useSharedConversations';
import { useCurrentOrg } from '@/lib/stores/current-org-store';
import type { NotificationItem } from '@/lib/api/orchestrator/home-status.service';
import type { ActiveAutomation, TriggerType } from '@/lib/api/orchestrator/dashboard.service';
import { KIND_TO_NODE_ICON_KEY, TRIGGER_KIND_ORDER } from '@/lib/api/orchestrator/dashboard.service';
import type { RecentActivityItem, ResourceKind } from '@/lib/api/orchestrator/recent-activity.service';
import { RESOURCE_KIND_ORDER } from '@/lib/api/orchestrator/recent-activity.service';
import { shareLinkUrl, type SharedLink } from '@/lib/api/sharing.service';
import { formatUtcDate, parseUtcAware } from '@/lib/utils/dateFormatters';
import { RunApprovalsDialog } from '@/components/approvals/RunApprovalsDialog';
import { TriggerRowActions, TRIGGER_ROW_ACTIONS_YIELD, hasTriggerRowActions } from './TriggerRowActions';

// 4-tab bell:
//   - 'inbox'    - actionable signals (failed runs, expired creds, etc.)
//   - 'triggers' - armed automations forward-looking view (was 'activity'
//                  internally pre-Part 2; renamed for clarity now that the
//                  third tab took the 'activity' name).
//   - 'activity' - Part 2: top-50 most-recently-edited resources across the
//                  active workspace. Visit-only (no polling when closed).
//   - 'shared'   - every conversation currently published via a SharedLink
//                  in the active workspace. Visit-only (zero polling cost
//                  when not visible). Inline copy / open / revoke actions
//                  mirror the ShareLinkDialog so the user never has to leave
//                  the bell to manage day-to-day share state.
type Tab = 'inbox' | 'triggers' | 'activity' | 'shared';

/**
 * Trigger-kind chip pre-selected whenever the Triggers tab is shown. Schedules
 * are the automations users open the bell to check (next fire countdown), so
 * the tab lands on them instead of on an unfiltered mixed list. Still
 * deselectable: clicking the active chip clears the filter and shows all kinds.
 */
const DEFAULT_TRIGGER_KIND_FILTER: TriggerType = 'SCHEDULE';

/**
 * Resource types backed by a SharedLink. Mirrors backend
 * {@code SharedLinkEntity.ResourceType} (publication-service):
 * CHAT, FORM, CONVERSATION, APPLICATION.
 * Ordered conversations + applications first because those are the primary
 * user-facing share surfaces; chat + form are publisher endpoints.
 */
type SharedResourceType = 'CONVERSATION' | 'APPLICATION' | 'CHAT' | 'FORM';
const SHARED_RESOURCE_TYPE_ORDER: SharedResourceType[] = [
  'CONVERSATION',
  'APPLICATION',
  'CHAT',
  'FORM',
];

const IMMINENT_WINDOW_MS = 5 * 60 * 1000;
const INBOX_PAGE_SIZE = 15;
/**
 * How long an ask or an answer keeps the Triggers rows fresh enough for landing on that tab
 * NOT to ask for them again. Exported so the test asserts the contract rather than a literal.
 *
 * <p>The rows ride the always-on home-status query (60s poll, 30s staleTime). The mutations
 * that change a row ask for it again where they happen, and this visit-ask is the net
 * underneath them: it catches a change made by another session, by a teammate in the same
 * workspace, or by a call site nobody wired.
 *
 * <p>Two seconds because the tab is read immediately after the user's own action, to check
 * that the action took. A bound long enough to outlive that reading turns the visit-ask into
 * nothing: the tab keeps showing the workflow as it was, and that is the report this exists
 * to answer. Short of that, the only thing the bound buys is that flipping between tabs is not
 * a request per click, which needs no more than a second or two. It is not a staleness policy
 * for the payload: the 60s poll remains that.
 */
export const TRIGGERS_ROWS_FRESH_FOR_MS = 2_000;

/**
 * Unified bell - two tabs:
 *
 * <ul>
 *   <li><b>Inbox</b> - actionable, reverse-looking. Failed pinned-runs (V1);
 *       future: approval pending, task assigned, credential expired. Has
 *       read-state cursor (unread dot, mark-all-read button).</li>
 *   <li><b>Activity</b> - informational, forward-looking. Pinned automations
 *       with armed triggers (schedules + webhooks). No read-state - just a
 *       live "what's about to fire" view.</li>
 * </ul>
 *
 * <p>The bell is ALWAYS rendered - it is the permanent entry point to all four
 * tabs (Inbox, Triggers, Activity, Shared). Activity/Shared are visit-only
 * (their hooks lazy-load on tab open), so gating visibility on them would force
 * an eager fetch; and the product intent is a permanent entry point, not a
 * "you have something" indicator that would strand the Activity/Shared tabs
 * whenever the Inbox and Triggers happen to be empty. The red dot still counts
 * ONLY the Inbox unread items (informational items don't bump the badge - that
 * would create permanent noise from running schedules).
 *
 * <p>A subtle blue ring pulses around the bell when at least one automation
 * is about to fire within {@link #IMMINENT_WINDOW_MS} (5 min) - same signal
 * the deleted {@code LiveWorkflowsBadge} carried, just relocated.
 */
export function NotificationBell() {
  const t = useTranslations('chat.home.notifications');
  const tLive = useTranslations('chat.home.live');
  const tShared = useTranslations('chat.home.shared');
  // Trophy names live in the `badges` namespace: a BADGE bell row carries the
  // badge code, and this is what turns it into the reader's language.
  const tBadges = useTranslations('badges');
  const router = useRouter();
  // Activity tab + global mark-all-read still come from the home-status hook;
  // it returns automations + the cursor-based unreadCount AND the legacy
  // (non-paginated) items list which we ignore here.
  const { automations, markAllRead } = useHomeStatus();
  // Same refresh every producer of a row uses after acting, asked here with a freshness bound
  // instead of unconditionally - see the effect below.
  const refreshAutomations = useRefreshHomeStatus();
  const tRecent = useTranslations('chat.home.recent');
  // Root namespace: the run-status words (`status.completed`, ...) are shared with the
  // run panel and are not scoped to the bell.
  const tStatus = useTranslations();
  const tCommon = useTranslations('chat.home.live'); // reuse justNow/minutesAgo/...
  const { currentOrgId, clear: clearCurrentOrg } = useCurrentOrg();
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<Tab>('inbox');
  // Activity tab - gates the useRecentActivity hook to "only fetch when
  // visible". When the bell is closed OR the user is on a different tab,
  // the hook stays at zero polling cost. enabled flips on the moment the
  // user lands on the Activity tab.
  const recentActivityEnabled = open && tab === 'activity';
  const {
    items: activityItems,
    peerScopeCount: activityPeerScopeCount,
    peerScopeLabel: activityPeerScopeLabel,
    isLoading: activityLoading,
  } = useRecentActivity(recentActivityEnabled);
  // Shared tab - same visit-only contract: the hook stays at zero polling
  // cost whenever the user isn't actively looking at the Shared list.
  const sharedEnabled = open && tab === 'shared';
  const {
    items: sharedItems,
    isLoading: sharedLoading,
    revoke: revokeShared,
  } = useSharedConversations(sharedEnabled);
  // Triggers tab - same visit-only spirit as the two tabs above, except the data is already
  // in hand (home-status is the always-on query behind the bell badge), so what the visit
  // buys is FRESHNESS rather than a lazy first load. Fires on the transition INTO the tab,
  // which covers opening the bell straight onto it through the empty-inbox fallback, and
  // never while the tab is off screen. The bound makes the call a no-op when the rows are
  // already current, so the effect can run as often as React wants it to.
  const triggersVisible = open && tab === 'triggers';
  useEffect(() => {
    if (!triggersVisible) return;
    refreshAutomations({ freshForMs: TRIGGERS_ROWS_FRESH_FOR_MS });
  }, [triggersVisible, refreshAutomations]);
  // Activity tab kind filter - null = show all rows; selected kind = filter
  // to that kind only. Client-side filter on the cached top-50, no extra
  // round-trip. (Previous "Modified by me" toggle dropped - `actorId` =
  // `tenant_id` per the cross-branch convention, which is a tenant string
  // not a userId, so the "by me" comparison was unreliable. Resurface if
  // we ever add a proper `updated_by` column.)
  const [activityKindFilter, setActivityKindFilter] = useState<ResourceKind | null>(null);
  // Active trigger-kind filter on the Triggers tab. null = show all rows.
  // Single-select with click-to-deselect: clicking the active chip clears
  // the filter (rather than requiring an explicit "All" chip).
  // Defaults to SCHEDULE (and is reset to it on every popover open, see
  // handleOpenChange): schedules are the automations users come here to check
  // (next fire countdown), so the tab always lands on them rather than on a
  // mixed list or on whatever chip was left selected in a previous visit.
  const [kindFilter, setKindFilter] = useState<TriggerType | null>(DEFAULT_TRIGGER_KIND_FILTER);
  // Active resource-type filter on the Shared tab (4 chips: conversations,
  // applications, chat, form). Same click-to-deselect contract as the
  // Triggers / Activity tabs above.
  const [sharedKindFilter, setSharedKindFilter] = useState<SharedResourceType | null>(null);
  // Inbox pagination: zero-based page, server-fixed page size. Resets to 0
  // whenever the user re-opens the popover so they always land on newest first.
  const [page, setPage] = useState(0);
  const {
    items,
    unreadCount,
    hasMore,
    deleteBuckets,
    isLoading: inboxLoading,
  } = useNotificationsPaged(page, INBOX_PAGE_SIZE);

  const imminent = useMemo(() => hasImminentFire(automations), [automations]);

  // When there's nothing in the inbox (no unread, no items on current page),
  // don't open onto a blank Inbox - land on the tab most likely to have
  // content: Triggers when there are armed automations, otherwise Activity
  // (recently-edited resources, almost always non-empty for a real user).
  // Also covers the original imminent-fire case (automations present →
  // Triggers).
  // Only fall back once the inbox has actually answered. While the first fetch is
  // in flight `items` is empty and `unreadCount` is 0, which is indistinguishable
  // from a genuinely empty inbox - so opening the bell early bounced the user to
  // Activity and hid rows that were about to land one tick later.
  const handleOpenChange = (next: boolean) => {
    if (next && !inboxLoading && unreadCount === 0 && items.length === 0) {
      setTab(automations.length > 0 ? 'triggers' : 'activity');
    }
    // The bell stays mounted for the whole session, so without this reset the
    // Triggers tab would keep whatever chip was selected on a previous visit.
    // Every open starts back on SCHEDULE.
    if (next) setKindFilter(DEFAULT_TRIGGER_KIND_FILTER);
    setOpen(next);
  };

  // The bell is ALWAYS rendered (no self-hide): it is the permanent entry
  // point to the four tabs. Activity/Shared hold content (recent edits,
  // shared links) that would be unreachable if the bell hid whenever Inbox +
  // Triggers were empty - and they are visit-only (lazy), so they can't gate
  // visibility without an eager fetch. The red unread dot and blue imminent
  // ring still convey state on top of the always-present icon.

  const handleRowClick = (item: NotificationItem) => {
    setOpen(false);
    router.push(notificationHref(item));
  };

  // APPROVAL_PENDING rows: review the run's pending approvals in a modal
  // right from the header, without navigating into the workflow. The dialog
  // lives OUTSIDE the popover (which closes when the modal opens).
  const [reviewRunId, setReviewRunId] = useState<string | null>(null);
  const handleReviewApprovals = (item: NotificationItem) => {
    if (!item.runIdPublic) return;
    setOpen(false);
    setReviewRunId(item.runIdPublic);
  };

  const handleAutomationClick = (automation: ActiveAutomation) => {
    setOpen(false);
    router.push(resourceHref(automation));
  };

  const handleMarkAll = (e: React.MouseEvent) => {
    e.stopPropagation();
    void markAllRead();
  };

  const handleDeleteRow = (e: React.MouseEvent, item: NotificationItem) => {
    e.stopPropagation();
    void deleteBuckets([{ subjectId: item.subjectId, category: item.category }]);
  };

  const handlePrev = (e: React.MouseEvent) => {
    e.stopPropagation();
    setPage((p) => Math.max(0, p - 1));
  };
  const handleNext = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (hasMore) setPage((p) => p + 1);
  };

  return (
    <>
    <Popover open={open} onOpenChange={handleOpenChange}>
      <PopoverTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          // `ghost` hovers by INVERTING (near-black ground, light glyph). That is the app's
          // legacy behaviour and dozens of call sites depend on it, so the variant is left
          // alone and overridden here: a bell that turns into a black square under the
          // pointer reads as a pressed state, not as "you are pointing at this". The same
          // override is applied to every quiet icon button on this bar (QUIET_ICON_HOVER in
          // ChatHeader) - fixing the bell alone left the controls either side of it doing
          // exactly what was reported.
          className={`w-8 h-8 relative text-black dark:text-white
                      hover:bg-surface-hover hover:text-[var(--text-primary)]
                      ${open ? 'bg-gray-100 dark:bg-gray-800' : ''}`}
          aria-label={t('title')}
          title={t('title')}
        >
          <Bell className="w-4 h-4" />
          {unreadCount > 0 && (
            <span
              className="absolute top-1 right-1 inline-flex h-2 w-2 rounded-full bg-red-500"
              aria-hidden="true"
            />
          )}
          {imminent && unreadCount === 0 && (
            // Imminent fire indicator - blue ping matching the "running"
            // status color used by the workflow board (`bg-blue-500
            // animate-pulse` on RunStatusBadge running). Same visual
            // language: blue pulse = workflow about to run / running.
            // Skipped when red unread dot is already there (avoid two
            // competing signals on the same 16px icon).
            <span
              className="absolute top-1 right-1 inline-flex h-2 w-2 rounded-full bg-blue-500 animate-ping opacity-60"
              aria-hidden="true"
            />
          )}
        </Button>
      </PopoverTrigger>

      <PopoverContent
        align="end"
        sideOffset={6}
        className="w-80 max-h-[60vh] overflow-y-auto p-0 bg-theme-primary
                   rounded-2xl border border-gray-300/70 dark:border-gray-600/70"
      >
        {/* Tab strip */}
        <div className="flex items-center border-b border-gray-200/70 dark:border-gray-700/70">
          <TabButton
            active={tab === 'inbox'}
            onClick={() => setTab('inbox')}
            label={t('inboxTab')}
            badge={unreadCount}
          />
          <TabButton
            active={tab === 'triggers'}
            onClick={() => setTab('triggers')}
            label={t('triggersTab')}
          />
          <TabButton
            active={tab === 'activity'}
            onClick={() => setTab('activity')}
            label={t('activityTab')}
          />
          <TabButton
            active={tab === 'shared'}
            onClick={() => setTab('shared')}
            label={t('sharedTab')}
          />
          <span className="flex-1" />
        </div>

        {/* Tab body */}
        {tab === 'inbox' ? (
          <>
            <InboxList
              items={items}
              onRowClick={handleRowClick}
              onDeleteRow={handleDeleteRow}
              onReviewApprovals={handleReviewApprovals}
              t={t}
              tBadges={tBadges}
            />
            {/* Footer: "Mark all read" on the left + pagination chevrons on
                the right. Renders whenever either control has something to
                offer (unread items present OR more than one page). The bulk
                "clear page" button used to live here but was removed - users
                found it too easy to wipe a page of legitimate notifications,
                and the per-row trash icon already covers the explicit case. */}
            {(items.length > 0 && (unreadCount > 0 || page > 0 || hasMore)) && (
              <div className="flex items-center justify-between gap-2 px-3 py-2 border-t border-gray-200/70 dark:border-gray-700/70">
                {unreadCount > 0 ? (
                  <button
                    type="button"
                    onClick={handleMarkAll}
                    className="text-xs text-blue-600 dark:text-blue-400 hover:underline"
                  >
                    {t('markAllRead')}
                  </button>
                ) : (
                  <span />
                )}
                {(page > 0 || hasMore) ? (
                  <div className="flex items-center gap-1">
                    <button
                      type="button"
                      onClick={handlePrev}
                      disabled={page === 0}
                      className="w-6 h-6 inline-flex items-center justify-center rounded
                                 text-theme-muted hover:text-theme-primary
                                 hover:bg-gray-100 dark:hover:bg-gray-800
                                 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                      aria-label={t('prevPage')}
                      title={t('prevPage')}
                    >
                      <ChevronLeft className="w-3.5 h-3.5" />
                    </button>
                    <span className="text-xs text-theme-muted px-1 tabular-nums">
                      {page + 1}
                    </span>
                    <button
                      type="button"
                      onClick={handleNext}
                      disabled={!hasMore}
                      className="w-6 h-6 inline-flex items-center justify-center rounded
                                 text-theme-muted hover:text-theme-primary
                                 hover:bg-gray-100 dark:hover:bg-gray-800
                                 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                      aria-label={t('nextPage')}
                      title={t('nextPage')}
                    >
                      <ChevronRight className="w-3.5 h-3.5" />
                    </button>
                  </div>
                ) : (
                  <span />
                )}
              </div>
            )}
          </>
        ) : tab === 'triggers' ? (
          <>
            <TriggerKindFilter
              active={kindFilter}
              onToggle={(k) => setKindFilter((prev) => (prev === k ? null : k))}
              t={tLive}
            />
            <ActivityList
              automations={automations}
              filter={kindFilter}
              onRowClick={handleAutomationClick}
              tStatus={tStatus}
              onNavigate={(href) => { setOpen(false); router.push(href); }}
              t={tLive}
            />
          </>
        ) : tab === 'activity' ? (
          // Activity tab - Part 2: top-50 recent edits across resource kinds.
          // Filter chips (Modified-by-me + 6 kinds) act client-side on the
          // cached top-50 - no extra round-trip on filter change.
          <>
            <RecentActivityFilter
              kindFilter={activityKindFilter}
              onToggleKind={(k) => setActivityKindFilter((prev) => (prev === k ? null : k))}
              t={tRecent}
            />
            <RecentActivityList
              items={activityItems}
              isLoading={activityLoading}
              kindFilter={activityKindFilter}
              peerScopeCount={activityPeerScopeCount}
              peerScopeLabel={activityPeerScopeLabel}
              currentOrgId={currentOrgId}
              onRowClick={(item) => {
                setOpen(false);
                router.push(resourceHrefForRecent(item));
              }}
              onSwitchToPersonal={() => {
                setOpen(false);
                clearCurrentOrg();
              }}
              t={tRecent}
              tCommon={tCommon}
            />
          </>
        ) : (
          // Shared tab - every conversation currently published via a
          // SharedLink in the active workspace. Click a row to jump to the
          // conversation; inline icons cover the day-to-day actions (copy
          // URL, open public link, revoke). The kind filter strip mirrors
          // the Triggers + Activity tabs above for visual consistency.
          <>
            <SharedResourceFilter
              active={sharedKindFilter}
              onToggle={(k) => setSharedKindFilter((prev) => (prev === k ? null : k))}
              t={tShared}
            />
            <SharedConversationsList
              items={sharedItems}
              isLoading={sharedLoading}
              kindFilter={sharedKindFilter}
              onRowClick={(link) => {
                setOpen(false);
                const href = hrefForSharedLink(link);
                if (href) router.push(href);
              }}
              onRevoke={(link) => { void revokeShared(link); }}
              t={tShared}
              tCommon={tCommon}
            />
          </>
        )}
      </PopoverContent>
    </Popover>
    {/* Mounted lazily: no signal fetch until a Review action is clicked. */}
    {reviewRunId != null && (
      <RunApprovalsDialog
        runId={reviewRunId}
        open
        onOpenChange={(next) => {
          if (!next) setReviewRunId(null);
        }}
      />
    )}
    </>
  );
}

// ---- Activity tab (Part 2: recent edits) ----

function RecentActivityFilter({
  kindFilter,
  onToggleKind,
  t,
}: {
  kindFilter: ResourceKind | null;
  onToggleKind: (kind: ResourceKind) => void;
  t: (key: string, values?: Record<string, string | number>) => string;
}) {
  return (
    <div
      role="group"
      aria-label={t('filterTitle')}
      className="flex flex-wrap items-center gap-1.5 px-3 py-2 border-b border-gray-200/70 dark:border-gray-700/70"
    >
      {/* 6 resource-kind chips - icons mirror the sidebar (AppSidebar.tsx
          chatNavItems) so the bell + sidebar share one visual vocabulary. */}
      {RESOURCE_KIND_ORDER.map((kind) => {
        const isActive = kindFilter === kind;
        const Icon = resourceKindIcon(kind);
        const label = t(`kindLabel.${kind.toLowerCase()}`);
        return (
          <button
            key={kind}
            type="button"
            aria-pressed={isActive}
            aria-label={label}
            title={label}
            onClick={() => onToggleKind(kind)}
            // Sized + filled to match NodeIcon "xs" (h-6 w-6, h-3.5 w-3.5
            // icon, bg always visible) so the Activity filter strip is
            // visually consistent with the Triggers filter strip (which
            // uses NodeIcon directly).
            className={`inline-flex items-center justify-center h-6 w-6 rounded-lg bg-theme-secondary transition-colors
                       ${isActive
                         ? 'ring-2 ring-[var(--accent-primary)] ring-offset-1 ring-offset-theme-primary'
                         : 'hover:ring-1 hover:ring-gray-300 dark:hover:ring-gray-600'}`}
          >
            <Icon className="h-3.5 w-3.5 text-theme-secondary" />
          </button>
        );
      })}
    </div>
  );
}

function RecentActivityList({
  items,
  isLoading,
  kindFilter,
  peerScopeCount,
  peerScopeLabel,
  currentOrgId,
  onRowClick,
  onSwitchToPersonal,
  t,
  tCommon,
}: {
  items: RecentActivityItem[];
  isLoading: boolean;
  kindFilter: ResourceKind | null;
  peerScopeCount: number;
  peerScopeLabel?: string;
  currentOrgId: string | null;
  onRowClick: (item: RecentActivityItem) => void;
  onSwitchToPersonal: () => void;
  t: (key: string, values?: Record<string, string | number>) => string;
  tCommon: (key: string, values?: Record<string, string | number>) => string;
}) {
  const filtered = kindFilter == null
    ? items
    : items.filter((item) => item.kind === kindFilter);

  if (isLoading && items.length === 0) {
    return <div className="p-6 text-center text-sm text-theme-muted">{t('loading')}</div>;
  }

  // 3-state empty (auditor v3.3 chunk-6 C1):
  //   - filter yields zero (chip selected, no matching row) → emptyForFilter
  //   - cross-scope hint (in org, zero items, peer has items) → cross-scope CTA
  //   - true first-run (zero items, zero peer) → first-run CTA
  if (filtered.length === 0) {
    if (kindFilter != null) {
      return <div className="p-6 text-center text-sm text-theme-muted">{t('emptyForFilter')}</div>;
    }
    if (items.length === 0 && peerScopeCount > 0 && peerScopeLabel && currentOrgId) {
      // Cross-scope hint - user has items in Personal, current workspace is empty
      return (
        <div className="p-4 text-center space-y-3">
          <p className="text-sm text-theme-muted">
            {t('emptyCrossScope', { n: peerScopeCount, scope: peerScopeLabel })}
          </p>
          <button
            type="button"
            onClick={onSwitchToPersonal}
            className="text-sm text-blue-600 dark:text-blue-400 hover:underline font-medium"
          >
            {t('switchToPeer', { scope: peerScopeLabel })}
          </button>
        </div>
      );
    }
    // True first-run
    return (
      <div className="p-6 text-center text-sm text-theme-muted">
        {t('emptyFirstRun')}
      </div>
    );
  }

  return (
    <div className="p-1.5 space-y-0.5">
      {filtered.map((item) => {
        const Icon = resourceKindIcon(item.kind);
        const actorLabel = item.actorDisplayName ?? t('actorUnknown');
        return (
          <button
            key={`${item.kind}:${item.resourceId}`}
            type="button"
            onClick={() => onRowClick(item)}
            className="w-full flex items-center gap-3 px-2.5 py-2 rounded-xl
                       hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors
                       cursor-pointer text-left"
          >
            <span
              className="relative inline-flex items-center justify-center
                          h-7 w-7 rounded-xl bg-theme-secondary shrink-0"
            >
              <Icon className="h-3.5 w-3.5 text-theme-secondary" />
            </span>
            <span className="flex-1 min-w-0">
              <span className="block text-sm text-theme-primary truncate">
                {item.name}
              </span>
              <span className="block text-xs text-theme-muted truncate">
                {t('byActor', { actor: actorLabel })} · {formatRelativePast(item.lastEditedAt, tCommon)}
              </span>
            </span>
          </button>
        );
      })}
      <p className="px-3 pt-2 text-[10px] text-theme-muted opacity-70 text-center">
        {t('top50Footer')}
      </p>
    </div>
  );
}

/**
 * Icon per ResourceKind for the Activity tab - matches the sidebar's resource
 * icons exactly (AppSidebar.tsx:62-69 chatNavItems) so the bell's resource
 * chips and the sidebar entries use the same visual vocabulary. Skill is the
 * only kind not in the sidebar today; uses Sparkles ("spark") per product
 * direction.
 */
function resourceKindIcon(kind: ResourceKind) {
  switch (kind) {
    case 'WORKFLOW':    return Workflow;
    case 'APPLICATION': return AppWindow;
    case 'INTERFACE':   return Monitor;
    case 'AGENT':       return Bot;
    case 'SKILL':       return Sparkles;
    case 'TABLE':       return Table;
    default:            return BookOpen;
  }
}

/**
 * Per-kind routing for a recent-activity row click. Mirrors the existing
 * Triggers tab routing logic for WORKFLOW + APPLICATION + AGENT, adds new
 * targets for INTERFACE + SKILL + TABLE.
 */
function resourceHrefForRecent(item: RecentActivityItem): string {
  switch (item.kind) {
    case 'WORKFLOW':    return `/app/workflow/${item.resourceId}`;
    // The application page is keyed by PUBLICATION id, not workflow id
    // (resourceId here IS the workflow id). Routing to
    // /app/applications/{workflowId} fails to load ("Failed to load
    // application"), so use the publicationId the backend now carries.
    // Legacy applications with no publication fall back to the workflow editor,
    // mirroring the Triggers tab (resourceHref) behavior.
    case 'APPLICATION': return item.publicationId
      ? `/app/applications/${item.publicationId}`
      : `/app/workflow/${item.resourceId}`;
    case 'INTERFACE':   return `/app/interface/${item.resourceId}`;
    // No per-agent page exists (would 404). AgentTable reads ?openAgent=<id> and pops the
    // right-side panel for that agent - same visual as clicking the row in the list. It acts
    // on the id whenever it appears in the address, so the link works as often as it is used.
    case 'AGENT':       return `/app/agent?openAgent=${item.resourceId}`;
    // Skills live under the agent shell, on their own tab. Naming it lands the user among
    // skills; without it the row dropped them on the agents board instead.
    case 'SKILL':       return `/app/agent?view=skills`;
    case 'TABLE':       return `/app/data/${item.resourceId}`;
    default:            return '/app';
  }
}

// ---- Shared tab (conversations published via SharedLink) ----

/**
 * 4-chip filter strip on the Shared tab. Mirrors {@link TriggerKindFilter} and
 * {@link RecentActivityFilter} - single-select with click-to-deselect, chip
 * styling matches the workflow {@code NodeIcon} `xs` size config so the three
 * filter strips share one visual vocabulary.
 */
function SharedResourceFilter({
  active,
  onToggle,
  t,
}: {
  active: SharedResourceType | null;
  onToggle: (kind: SharedResourceType) => void;
  t: (key: string, values?: Record<string, string | number>) => string;
}) {
  return (
    <div
      role="group"
      aria-label={t('filterByKind')}
      className="flex flex-wrap items-center gap-1.5 px-3 py-2 border-b border-gray-200/70 dark:border-gray-700/70"
    >
      {SHARED_RESOURCE_TYPE_ORDER.map((kind) => {
        const isActive = active === kind;
        const label = t(`kindLabel.${kind.toLowerCase()}`);
        return (
          <button
            key={kind}
            type="button"
            aria-pressed={isActive}
            aria-label={label}
            title={label}
            onClick={() => onToggle(kind)}
            className={`inline-flex items-center justify-center h-6 w-6 rounded-lg bg-theme-secondary transition-colors
                       ${isActive
                         ? 'ring-2 ring-[var(--accent-primary)] ring-offset-1 ring-offset-theme-primary'
                         : 'hover:ring-1 hover:ring-gray-300 dark:hover:ring-gray-600'}`}
          >
            {renderSharedLinkIcon(kind)}
          </button>
        );
      })}
    </div>
  );
}

function SharedConversationsList({
  items,
  isLoading,
  kindFilter,
  onRowClick,
  onRevoke,
  t,
  tCommon,
}: {
  items: SharedLink[];
  isLoading: boolean;
  kindFilter: SharedResourceType | null;
  onRowClick: (link: SharedLink) => void;
  onRevoke: (link: SharedLink) => void;
  t: (key: string, values?: Record<string, string | number>) => string;
  tCommon: (key: string, values?: Record<string, string | number>) => string;
}) {
  const filtered = kindFilter == null
    ? items
    : items.filter((link) => link.resourceType === kindFilter);

  if (isLoading && items.length === 0) {
    return <div className="p-6 text-center text-sm text-theme-muted">{t('loading')}</div>;
  }
  if (items.length === 0) {
    return <div className="p-6 text-center text-sm text-theme-muted">{t('empty')}</div>;
  }
  if (filtered.length === 0) {
    // Chip selected but no matching row - surface explicitly so the user
    // doesn't mistake a filtered-out list for a broken tab.
    return <div className="p-6 text-center text-sm text-theme-muted">{t('emptyForKind')}</div>;
  }
  return (
    <div className="p-1.5 space-y-0.5">
      {filtered.map((link) => (
        <SharedConversationRow
          key={link.id}
          link={link}
          onRowClick={onRowClick}
          onRevoke={onRevoke}
          t={t}
          tCommon={tCommon}
        />
      ))}
    </div>
  );
}

function SharedConversationRow({
  link,
  onRowClick,
  onRevoke,
  t,
  tCommon,
}: {
  link: SharedLink;
  onRowClick: (link: SharedLink) => void;
  onRevoke: (link: SharedLink) => void;
  t: (key: string, values?: Record<string, string | number>) => string;
  tCommon: (key: string, values?: Record<string, string | number>) => string;
}) {
  const [copied, setCopied] = useState(false);
  const title = link.title?.trim() ? link.title : t('untitled');
  // Every shared link (FORM included) resolves through the unified /s/ route. Shared with
  // SharedLinksTabContent via shareLinkUrl so the bell and the settings page can never drift.
  const publicUrl = shareLinkUrl(link.token);
  const subtitle = link.lastAccessed
    ? `${t('accessCount', { n: link.accessCount })} · ${t('lastAccessed', { when: formatRelativePast(link.lastAccessed, tCommon) })}`
    : `${t('accessCount', { n: link.accessCount })} · ${t('createdAt', { when: formatRelativePast(link.createdAt, tCommon) })}`;

  const handleCopy = async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await navigator.clipboard.writeText(publicUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard API may be unavailable (insecure context). Fall back to a
      // hidden textarea + execCommand - same approach as ShareLinkDialog.
      const textarea = document.createElement('textarea');
      textarea.value = publicUrl;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    }
  };

  const handleOpen = (e: React.MouseEvent) => {
    e.stopPropagation();
    window.open(publicUrl, '_blank', 'noopener,noreferrer');
  };

  const handleRevoke = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (typeof window !== 'undefined' && !window.confirm(t('revokeConfirm', { name: title }))) {
      return;
    }
    onRevoke(link);
  };

  return (
    <div
      className={`group relative w-full flex items-center gap-3 px-2.5 py-2 rounded-xl
                 hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors
                 ${!link.isActive ? 'opacity-60' : ''}`}
    >
      {/* Row-click overlay - sits behind the inline action buttons (z-0 vs
          z-10) so the trash / copy / open hit areas always win. */}
      <button
        type="button"
        onClick={() => onRowClick(link)}
        className="absolute inset-0 z-0 rounded-xl cursor-pointer"
        aria-label={title}
      />
      <span
        className="relative z-[1] inline-flex items-center justify-center
                    h-7 w-7 rounded-xl bg-theme-secondary shrink-0"
        title={link.resourceType}
      >
        {renderSharedLinkIcon(link.resourceType)}
      </span>
      <span className="relative z-[1] flex-1 min-w-0 pointer-events-none">
        <span className="flex items-center gap-1.5 min-w-0">
          <span className="block text-sm text-theme-primary truncate">{title}</span>
          {!link.isActive && (
            <span className="inline-flex items-center px-1.5 rounded-md
                             bg-gray-200 dark:bg-gray-700 text-[10px] text-theme-muted shrink-0">
              {t('inactive')}
            </span>
          )}
        </span>
        <span className="block text-xs text-theme-muted truncate">{subtitle}</span>
      </span>
      {/* Actions sit out of the flex flow so the title+subtitle span the full
          row width by default. On hover they fade in over the right side of
          the text - the small bg matches the row hover so the overlay reads
          as a clean control surface rather than competing with the title. */}
      <span className="absolute right-2 top-1/2 -translate-y-1/2 z-10
                       flex items-center gap-0.5 shrink-0
                       rounded-lg px-1 py-0.5
                       bg-gray-100 dark:bg-gray-800
                       opacity-0 group-hover:opacity-100 focus-within:opacity-100
                       transition-opacity">
        <button
          type="button"
          onClick={handleCopy}
          className="inline-flex h-6 w-6 items-center justify-center rounded
                     text-theme-muted hover:text-theme-primary
                     hover:bg-gray-200 dark:hover:bg-gray-700 transition-colors"
          aria-label={t('copyLink')}
          title={copied ? t('copied') : t('copyLink')}
        >
          {copied
            ? <Check className="h-3.5 w-3.5 text-green-500" />
            : <Copy className="h-3.5 w-3.5" />}
        </button>
        <button
          type="button"
          onClick={handleOpen}
          className="inline-flex h-6 w-6 items-center justify-center rounded
                     text-theme-muted hover:text-theme-primary
                     hover:bg-gray-200 dark:hover:bg-gray-700 transition-colors"
          aria-label={t('openLink')}
          title={t('openLink')}
        >
          <ExternalLink className="h-3.5 w-3.5" />
        </button>
        <button
          type="button"
          onClick={handleRevoke}
          className="inline-flex h-6 w-6 items-center justify-center rounded
                     text-theme-muted hover:text-red-600 dark:hover:text-red-400
                     hover:bg-red-50 dark:hover:bg-red-900/30 transition-colors"
          aria-label={t('revoke')}
          title={t('revoke')}
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </span>
    </div>
  );
}

/**
 * Per-resourceType icon for Shared-tab rows. Reuses the exact lucide icons
 * the public-access settings page uses on {@code TriggerTypeTabs}, so the
 * bell row and the settings tab share one visual vocabulary for the same
 * resource kind. Source: {@code public-access/components/TriggerTypeTabs.tsx}.
 */
function renderSharedLinkIcon(type: string) {
  const cls = 'h-3.5 w-3.5 text-theme-secondary';
  switch (type) {
    case 'CONVERSATION': return <MessagesSquare className={cls} />;
    case 'APPLICATION':  return <AppWindow className={cls} />;
    case 'CHAT':         return <MessageCircle className={cls} />;
    case 'FORM':         return <FileText className={cls} />;
    default:             return <Share2 className={cls} />;
  }
}

/**
 * Resolve the in-app route for a SharedLink row.
 *
 * <ul>
 *   <li>{@code CONVERSATION} → {@code /app/c/{resourceId}} (the conversation
 *       transcript). The link's {@code resourceId} carries the conversation
 *       UUID for shares created after the ShareLinkDialog "store original ID"
 *       fix; legacy rows only have {@code resourceToken} (the {@code cs_}
 *       share token), which is not a valid conversation id - return null
 *       there so the bell falls back to a no-op rather than pushing a bad URL.</li>
 *   <li>{@code APPLICATION} → {@code /app/applications/{resourceId}}, matching
 *       {@link #resourceHref} for the Triggers tab.</li>
 *   <li>{@code CHAT} / {@code FORM} → the public-access settings page on the
 *       matching tab - these are trigger endpoints managed there, not
 *       in-app first-class resources.</li>
 * </ul>
 */
function hrefForSharedLink(link: SharedLink): string | null {
  switch (link.resourceType) {
    case 'CONVERSATION':
      return link.resourceId ? `/app/c/${link.resourceId}` : null;
    case 'APPLICATION':
      return link.resourceId ? `/app/applications/${link.resourceId}` : null;
    case 'CHAT':
      return '/app/settings/public-access?tab=chat';
    case 'FORM':
      return '/app/settings/public-access?tab=form';
    default:
      return '/app/settings/public-access';
  }
}

function TabButton({
  active,
  onClick,
  label,
  badge,
}: {
  active: boolean;
  onClick: () => void;
  label: string;
  badge?: number;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`px-3 py-2 text-sm transition-colors border-b-2
                 ${active
                   ? 'border-[var(--accent-primary)] text-theme-primary font-medium'
                   : 'border-transparent text-theme-muted hover:text-theme-primary'}`}
    >
      {label}
      {badge !== undefined && badge > 0 && (
        <span className="ml-1.5 inline-flex items-center justify-center
                         h-4 min-w-4 px-1 rounded-md bg-red-500 text-white text-[10px]">
          {badge > 99 ? '99+' : badge}
        </span>
      )}
    </button>
  );
}

type InboxT = ((key: string, values?: Record<string, string | number>) => string) & {
  has?: (key: string) => boolean;
};

function InboxList({
  items,
  onRowClick,
  onDeleteRow,
  onReviewApprovals,
  t,
  tBadges,
}: {
  items: NotificationItem[];
  onRowClick: (item: NotificationItem) => void;
  onDeleteRow: (e: React.MouseEvent, item: NotificationItem) => void;
  onReviewApprovals: (item: NotificationItem) => void;
  t: InboxT;
  tBadges: InboxT;
}) {
  if (items.length === 0) {
    return (
      <div className="p-6 text-center text-sm text-theme-muted">
        {t('empty')}
      </div>
    );
  }
  return (
    <div className="p-1.5 space-y-0.5">
      {items.map((item) => (
        // Outer is now a div (not a button) because we nest a delete button
        // inside, and nested buttons are invalid HTML. The row-click is
        // handled by an inner overlay button so accessibility / keyboard
        // navigation still work.
        <div
          key={`${item.subjectId}-${item.category}-${item.lastEventAt}`}
          className={`group relative w-full flex items-start gap-3 px-2.5 py-2 rounded-xl
                     hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors
                     ${item.unread ? 'bg-blue-50/50 dark:bg-blue-900/20' : ''}`}
        >
          {/* Click overlay - fills the row, lets the user click anywhere to
              navigate. Sits BEHIND the trash button (z-0 vs z-10) so the
              delete affordance always wins clicks on its hit area. */}
          <button
            type="button"
            onClick={() => onRowClick(item)}
            className="absolute inset-0 z-0 rounded-xl cursor-pointer"
            aria-label={`Open ${subjectLabel(item, tBadges)}`}
          />
          {/* Severity dot - only rendered while the row is unread, so
              mark-all-read clears the visual attention signal alongside the
              row background. Severity itself is still conveyed by the row
              title text ("1 expired credential", "X failed runs", …). */}
          {item.unread && (
            <span
              className={`relative z-[1] mt-1.5 inline-flex h-2 w-2 shrink-0 rounded-full
                         ${item.severity === 'error' ? 'bg-red-500' :
                           item.severity === 'warning' ? 'bg-yellow-500' : 'bg-blue-500'}`}
              aria-hidden="true"
            />
          )}
          {/* P7: CREDENTIAL rows render the integration's ServiceIcon next to
              the severity dot so users can recognize the API at a glance
              ("googlecalendar" → Google Calendar logo). TRIGGER rows render
              a kind-specific lucide icon (Clock / Webhook / chat / form) so
              "1 schedule disabled" is visually distinguishable from
              "1 webhook disabled" without reading the title. Other subject
              types keep the dot-only layout. */}
          {item.subjectType === 'CREDENTIAL' && item.integration && (
            <ServiceIcon
              iconSlug={item.integration}
              size="sm"
              className="relative z-[1] mt-0.5 shrink-0"
            />
          )}
          {item.subjectType === 'TRIGGER' && (() => {
            const Icon = triggerKindIcon(item.triggerKind);
            return (
              <Icon
                className="relative z-[1] mt-0.5 h-4 w-4 shrink-0 text-theme-muted"
                aria-hidden="true"
              />
            );
          })()}
          {item.subjectType === 'ORG_INVITATION' && (
            <UserPlus
              className="relative z-[1] mt-0.5 h-4 w-4 shrink-0 text-theme-muted"
              aria-hidden="true"
            />
          )}
          {item.subjectType === 'BADGE' && (
            <Trophy
              className="relative z-[1] mt-0.5 h-4 w-4 shrink-0 text-amber-500"
              aria-hidden="true"
            />
          )}
          <span className="relative z-[1] flex-1 min-w-0 pointer-events-none">
            <span className="block text-sm text-theme-primary truncate">
              {subjectLabel(item, tBadges)}
            </span>
            <span className="block text-xs text-theme-muted truncate">
              {categoryLabel(item.category, item.count, t)} · {formatRelativePast(item.lastEventAt, t)}
            </span>
            {/* Approval rows carry two explicit actions: open the workflow /
                app (same as the row click) and review the pending approvals
                in a modal without leaving the current page. pointer-events
                re-enabled locally - the text container disables them so the
                row overlay receives clicks. */}
            {item.category === 'APPROVAL_PENDING' && item.runIdPublic && (
              <span className="mt-1 flex items-center gap-1.5 pointer-events-auto">
                <button
                  type="button"
                  data-testid="inbox-approval-open"
                  onClick={(e) => {
                    e.stopPropagation();
                    onRowClick(item);
                  }}
                  className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium
                             bg-gray-100 text-gray-700 hover:bg-gray-200
                             dark:bg-gray-700/60 dark:text-gray-200 dark:hover:bg-gray-700 transition-colors"
                >
                  <ExternalLink className="h-3 w-3" />
                  {t('approvalOpen')}
                </button>
                <button
                  type="button"
                  data-testid="inbox-approval-review"
                  onClick={(e) => {
                    e.stopPropagation();
                    onReviewApprovals(item);
                  }}
                  className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium
                             bg-amber-100 text-amber-700 hover:bg-amber-200
                             dark:bg-amber-500/20 dark:text-amber-300 dark:hover:bg-amber-500/30 transition-colors"
                >
                  <MessagesSquare className="h-3 w-3" />
                  {t('approvalReview')}
                </button>
              </span>
            )}
          </span>
          {/* Trash icon - appears on row hover only, deletes this single
              bucket via the bulk endpoint with a 1-element list. */}
          <button
            type="button"
            onClick={(e) => onDeleteRow(e, item)}
            className="relative z-10 inline-flex h-6 w-6 shrink-0 items-center justify-center
                       rounded text-theme-muted hover:text-red-600 dark:hover:text-red-400
                       hover:bg-red-50 dark:hover:bg-red-900/30
                       opacity-0 group-hover:opacity-100 focus:opacity-100
                       transition-opacity"
            aria-label={t('deleteRow')}
            title={t('deleteRow')}
          >
            <Trash2 className="h-3.5 w-3.5" />
          </button>
        </div>
      ))}
    </div>
  );
}

/**
 * Row title.
 *
 * <p>BADGE rows carry the badge CODE in {@code subjectName}, not a label: the
 * emitter cannot know the reader's language, so the translated trophy name is
 * resolved here. Any other subject type already ships a human name, and an
 * unknown badge code (one retired from the catalog) falls back to the raw
 * value rather than rendering a namespaced key path.
 */
function subjectLabel(item: NotificationItem, tBadges: InboxT): string {
  if (item.subjectType !== 'BADGE') return item.subjectName;
  const key = `item.${item.subjectName}.name`;
  if (typeof tBadges.has === 'function' && !tBadges.has(key)) return item.subjectName;
  return tBadges(key);
}

/**
 * P2a multi-category label resolution. Reads from
 * {@code chat.home.notifications.category.{CATEGORY}}; falls back to the
 * legacy {@code failuresCount} key for unknown categories so an in-flight
 * deploy where a new backend category lands before the frontend bundle
 * still renders something meaningful instead of the raw namespaced path.
 *
 * <p>Uses {@code t.has(key)} (next-intl v3+) - the older
 * {@code resolved === key} sniff is dead code on v4 because the default
 * {@code getMessageFallback} returns {@code "${namespace}.${key}"}, never
 * matching the relative key.
 */
function categoryLabel(
  category: string,
  count: number,
  t: InboxT,
): string {
  const key = `category.${category}`;
  if (typeof t.has === 'function' && !t.has(key)) {
    return t('failuresCount', { n: count });
  }
  return t(key, { count });
}

function ActivityList({
  automations,
  filter,
  onRowClick,
  onNavigate,
  t,
  tStatus,
}: {
  automations: ActiveAutomation[];
  filter: TriggerType | null;
  onRowClick: (a: ActiveAutomation) => void;
  onNavigate: (href: string) => void;
  t: (key: string, values?: Record<string, string | number>) => string;
  /** Root translator - the run-status vocabulary lives at `status.*`, outside this namespace. */
  tStatus: (key: string) => string;
}) {
  // Row actions report here rather than through a toast: the bell mounts no toast
  // container, and a result the user cannot see is the same as no result at all.
  const [feedback, setFeedback] = useState<{ kind: 'success' | 'error'; message: string } | null>(null);
  const filtered = filter == null
    ? automations
    : automations.filter((a) => a.triggerType === filter);

  if (filtered.length === 0) {
    // Two cases collapsed: nothing-pinned (filter == null → original empty
    // state) vs filter-yields-zero (chip selected, no matching row). The
    // second case is more informative - surface it explicitly so the user
    // knows the bell isn't broken.
    return (
      <div className="p-6 text-center text-sm text-theme-muted">
        {filter == null ? t('countLive', { n: 0 }) : t('emptyForKind')}
      </div>
    );
  }
  return (
    <div className="p-1.5 space-y-0.5">
      {feedback && (
        <p
          role="status"
          className={`mb-1 rounded-lg px-2.5 py-1.5 text-xs ${
            feedback.kind === 'success'
              ? 'bg-emerald-50 text-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-100'
              : 'bg-red-50 text-red-900 dark:bg-red-950/40 dark:text-red-100'
          }`}
        >
          {feedback.message}
        </p>
      )}
      {filtered.map((a) => {
        const ResourceIcon = resourceIcon(a.resourceType);
        // Trigger icon = the NodeIcon for this kind, matching the workflow
        // builder palette. Falls back to the previous Clock/Webhook lucide
        // pair if the kind is somehow missing from the map (defensive - the
        // parity test guarantees the 8 entries align).
        const kindNodeId = KIND_TO_NODE_ICON_KEY[a.triggerType] ?? null;
        const FallbackIcon = a.triggerType === 'SCHEDULE' ? Clock : Webhook;
        // The pulse on the bell tells the user "something is about to fire";
        // re-applying the same blue ping on the imminent rows ties the two
        // signals together visually so the user can pick out the responsible
        // automation in a single glance.
        const isImminent = isImminentFire(a);
        // Top-right label - what is AHEAD of this automation, never behind it:
        //   paused   → pausedBadge, ahead of the three branches below, because a
        //              disabled resource has no future fire to describe. The engine
        //              already refuses every lane: ScheduleExecutorService skips the
        //              tick ("Agent X is inactive, skipping schedule"),
        //              AgentWebhookDispatchService answers "Agent is inactive", and
        //              the workflow webhook / form / chat / datasource / workflow
        //              lanes all refuse on a CANCELLED production run. The bell used
        //              to print the countdown and the blue imminent pulse anyway, so
        //              the one surface whose job is to say what is about to happen
        //              was the one contradicting the engine.
        //   budget   → budgetBlockedBadge, for the same reason and one rank lower: the
        //              schedule is still armed and nextFireAt is still the next tick, but
        //              the spending cap refuses the run. A separate badge on purpose - a
        //              pause is undone by re-enabling, a cap by raising it or waiting for
        //              the period to roll, so one word for both sent the reader to the
        //              wrong switch.
        //   SCHEDULE → countdown to nextFireAt
        //   WEBHOOK  → liveBadge ("Live", indicating no schedule)
        //   others   → nothing; the 6 declared kinds fire on demand, so the kind
        //              icon alone is their forward-looking statement.
        // What is behind it - when it last ran and how that ended - is the "Last:"
        // line below, on every kind. It used to live here for the declared kinds,
        // which is why they now read as icon-only.
        //
        // ERROR is the ONE kind `resourcePaused` does not speak for, so it keeps the
        // live treatment - see fireSuppression, which owns both rules for this row and
        // for the imminence check, so the two cannot drift apart.
        const suppression = fireSuppression(a);
        const pausedAndSilent = suppression !== null;
        const fireLabel = suppression === 'paused'
          ? t('pausedBadge')
          : suppression === 'budget'
            ? t('budgetBlockedBadge')
            : a.triggerType === 'SCHEDULE'
              ? formatNextFire(a.schedule?.nextFireAt, t)
              : a.triggerType === 'WEBHOOK'
                ? t('liveBadge')
                : '';

        // The "Last:" line, on every row that has something to say - and every row that said
        // something before this change still does. A never-fired SCHEDULE keeps its "never"
        // (it is waiting for a fire that is on the calendar), and so does a never-fired
        // declared kind, which used to print that sentinel as its whole right-hand label. A
        // WEBHOOK row is the one exception: its label is "live", it has never carried a
        // last-run line, and a permanent "Last: -" on an endpoint nobody has called yet would
        // be noise rather than news.
        const showLastRun = a.lastRunAt != null || a.triggerType !== 'WEBHOOK';
        // Dimmed while the resource is disabled, so a glance down the list separates what
        // is going to run from what is only still listed. Applied to the row's CONTENT and
        // not to the row, because opacity on the parent is a ceiling its children cannot
        // exceed - it would take the action menu down with it, and that menu is the way
        // back ("Reactivate agent"). Opacity rather than a muted text colour: the row
        // carries an avatar and a status icon, which no colour swap reaches.
        //
        // `pointer-events-none` is NOT decoration, it is what keeps the row clickable. An
        // opacity below 1 makes the element paint as its own stacking context, so these
        // spans would otherwise paint AND hit-test above the row's click target, which is
        // the `absolute inset-0 z-0` overlay below - clicking the name or the label would
        // land on nothing at all. None of the three spans holds an interactive element, so
        // making them click-transparent hands every pixel back to the overlay. This is the
        // same pairing the Shared tab row uses for the same reason (`relative z-[1] ...
        // pointer-events-none`), and jsdom cannot see it: with no stylesheet the overlay
        // wins either way, which is why the guard is asserted on the class pairing.
        //
        // 50% and not some third value: it is what the agenda already dims a "will not
        // fire" occurrence by (AgendaListView, OccurrenceChip).
        //
        // What it deliberately does NOT cover is the badge itself. The badge is the one
        // piece of NEW information on a disabled row, and it is the explanation of the
        // dim, so dimming it would drop the only word that says why to about 2:1 against
        // the popover ground - less legible than the countdown it replaces. The word
        // stays at full strength; everything the row was already saying steps back.
        const dimWhenPaused = pausedAndSilent ? 'opacity-50 pointer-events-none' : '';
        // The verdict as a word, for the sr-only twin of the icon. Same helper the run panel
        // uses, so the two surfaces name a status identically.
        const statusLabel = a.lastRunAt && a.lastRunStatus
          ? getRunStatusLabel(a.lastRunStatus, tStatus)
          : null;
        // Subtitle under the workflow name - 3-branch:
        //   SCHEDULE → cron expression
        //   WEBHOOK  → endpoint hint
        //   others   → kind label (matches the chip's accessible name)
        const subtitle = a.triggerType === 'SCHEDULE' && a.schedule?.cronExpression
          ? a.schedule.cronExpression
          : a.triggerType === 'WEBHOOK'
            ? t('webhookEndpoint')
            : t(`kindLabel.${a.triggerType.toLowerCase()}`);
        return (
          // A div, not a button: the row carries its own action menu, and a button
          // inside a button is invalid HTML that browsers resolve by dropping one of
          // them. The row-click target is an absolutely positioned overlay BEHIND the
          // actions (z-0 vs z-10), the same pattern the Shared tab rows use.
          <div
            key={`${a.resourceId}:${a.triggerType}`}
            className={`group relative w-full flex items-center gap-3 px-2.5 py-2 rounded-xl
                       hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors
                       ${isImminent ? 'bg-blue-50/60 dark:bg-blue-900/20' : ''}`}
          >
            <button
              type="button"
              onClick={() => onRowClick(a)}
              className="absolute inset-0 z-0 rounded-xl cursor-pointer"
              aria-label={a.name}
            />
            <span
              className={`relative inline-flex items-center justify-center
                          h-7 w-7 rounded-full bg-theme-secondary shrink-0 overflow-hidden
                          ${dimWhenPaused}
                          ${isImminent ? 'ring-2 ring-blue-500/70' : ''}`}
            >
              {/* AGENT rows render the agent's avatar (or initials fallback)
                  instead of the generic Bot icon - users recognise their
                  agents by avatar in the rest of the app (sidebar, AgentTable
                  card), so the bell should match. WORKFLOW / APPLICATION rows
                  keep the lucide icon. */}
              {a.resourceType === 'AGENT' ? (
                <AvatarDisplay avatarUrl={a.avatarUrl} name={a.name} size="sm" className="!w-7 !h-7" />
              ) : (
                <ResourceIcon className="h-3.5 w-3.5 text-theme-secondary" />
              )}
              {isImminent && (
                <span
                  className="absolute -top-0.5 -right-0.5 inline-flex h-2 w-2
                             rounded-full bg-blue-500 animate-ping opacity-70"
                  aria-hidden="true"
                />
              )}
            </span>
            <span className={`flex-1 min-w-0 ${dimWhenPaused}`}>
              <span className="block text-sm text-theme-primary truncate">
                {a.name}
              </span>
              <span className="block text-xs text-theme-muted truncate">
                {subtitle}
              </span>
            </span>
            {/* The column the row menu is revealed ON TOP of, so it steps aside while the
                menu is showing. The rule is the menu's own (TRIGGER_ROW_ACTIONS_YIELD),
                imported rather than retyped: the three dots and this text have to agree
                about when they are on screen, and they live in two different files.

                Only on rows that HAVE a menu. A webhook, chat or form row has no schedule
                to act on and renders no button, so yielding there blanked the column on
                hover and put nothing in its place. */}
            <span
              className={`flex flex-col items-end gap-0.5 shrink-0 ${
                hasTriggerRowActions(a) ? TRIGGER_ROW_ACTIONS_YIELD : ''
              }`}
            >
              <span
                className={`inline-flex items-center gap-1 text-xs
                           ${isImminent ? 'text-blue-600 dark:text-blue-400 font-medium' : 'text-theme-muted'}`}
              >
                {kindNodeId
                  ? <NodeIcon nodeId={kindNodeId} size="xs" />
                  : <FallbackIcon className="h-3 w-3" />}
                {fireLabel}
              </span>
              {/* "Last: <outcome> 2m ago" on EVERY kind - when the automation last ran
                  and how that run ended, badged with the SAME icon the run panel draws
                  on that epoch (EpochStatusIcon, fed by the DTO's `lastRunStatus`) so
                  the bell cannot contradict the epoch row one click away. A row whose
                  backend has no honest verdict sends no status and the icon slot renders
                  empty, keeping its width so the times stay in one column.
                  It costs no row height: the left column is already two lines.

                  On a disabled row the paused dim REPLACES its own 70% rather than stacking
                  with it: opacity multiplies through the tree, so the two together would put
                  the popover's smallest text at 0.35 - unreadable rather than inactive, on
                  the line that tells the user what this automation last did, which is what
                  they need in order to decide what to do about it. Dimmed once.

                  `pointer-events-none` is unconditional, and for the same reason it is on
                  the dimmed spans: ANY opacity below 1 gives this line its own stacking
                  context, so it paints and hit-tests above the row's `absolute inset-0 z-0`
                  click target. It has held a dead strip across the bottom of every row since
                  it was written; it carries nothing interactive, so handing the pixels back
                  to the overlay is pure gain. */}
              {showLastRun && (
              <span className={`flex items-center gap-1 text-[10px] text-theme-muted leading-none
                                whitespace-nowrap pointer-events-none
                                ${pausedAndSilent ? dimWhenPaused : 'opacity-70'}`}>
                {t('lastRan')}
                {a.lastRunAt && <EpochStatusIcon status={a.lastRunStatus} />}
                {/* EpochStatusIcon is aria-hidden by construction: it is a colour and a shape.
                    The WORD has to reach a screen reader (and anyone who cannot separate the
                    red from the green), the same way EpochSelector supplies it for the very
                    same icon. */}
                {statusLabel && (
                  <span className="sr-only" data-last-run-status={a.lastRunStatus}>{statusLabel}</span>
                )}
                {a.lastRunAt ? formatRelativePast(a.lastRunAt, t) : t('neverRan')}
              </span>
              )}
            </span>
            <TriggerRowActions
              automation={a}
              onNavigate={onNavigate}
              onResult={(kind, message) => setFeedback({ kind, message })}
            />
          </div>
        );
      })}
    </div>
  );
}

/**
 * 8-chip filter strip rendered above the Triggers tab list. Single-select with
 * click-to-deselect (clicking the active chip clears the filter, the same
 * behavior as the existing tab buttons).
 *
 * <p>A11y: rendered as a {@code role="group"} of {@code aria-pressed} toggle
 * buttons. This is the standard chip-strip pattern (matches MUI Chip + ARIA
 * APG toolbar variants). Radiogroup would be wrong - radios cannot deselect.
 *
 * <p>Layout: 8 NodeIcons at {@code xs} (24px) + 7×6px gaps = 234px, fits one
 * row inside the popover's 288px content width (w-80 minus px-3). On a
 * narrower viewport, {@code flex-wrap} naturally drops to 2 rows.
 */
function TriggerKindFilter({
  active,
  onToggle,
  t,
}: {
  active: TriggerType | null;
  onToggle: (kind: TriggerType) => void;
  t: (key: string, values?: Record<string, string | number>) => string;
}) {
  return (
    <div
      role="group"
      aria-label={t('filterByKind')}
      className="flex flex-wrap items-center gap-1.5 px-3 py-2 border-b border-gray-200/70 dark:border-gray-700/70"
    >
      {TRIGGER_KIND_ORDER.map((kind) => {
        const isActive = active === kind;
        const label = t(`kindLabel.${kind.toLowerCase()}`);
        const nodeId = KIND_TO_NODE_ICON_KEY[kind];
        return (
          <button
            key={kind}
            type="button"
            aria-pressed={isActive}
            aria-label={label}
            title={label}
            onClick={() => onToggle(kind)}
            className={`inline-flex items-center justify-center rounded-md
                       transition-colors
                       ${isActive
                         ? 'ring-2 ring-[var(--accent-primary)] ring-offset-1 ring-offset-theme-primary'
                         : 'hover:ring-1 hover:ring-gray-300 dark:hover:ring-gray-600'}`}
          >
            <NodeIcon nodeId={nodeId} size="xs" />
          </button>
        );
      })}
    </div>
  );
}

// ---- helpers (relocated from the deleted LiveWorkflowsBadge) ----

/**
 * Whether the backend's `resourcePaused` flag means THIS row will not fire.
 *
 * <p>It does for seven of the eight kinds: `ScheduleExecutorService` skips the tick
 * ("Agent X is inactive, skipping schedule"), `AgentWebhookDispatchService` answers
 * "Agent is inactive", and the workflow webhook / form / chat / datasource / workflow
 * lanes all refuse on a CANCELLED production run.
 *
 * <p>ERROR is the exception. `ErrorTriggerDispatchService` resolves the newest
 * NON-TERMINAL run and its own comment states it "does NOT consult production_run_id",
 * so a workflow whose production run is CANCELLED still dispatches its error handler.
 * Saying "disabled" there would be the very defect this rule exists to remove, pointing
 * the other way: the bell asserting a refusal the engine does not make.
 *
 * <p>ONE function because the rule has two consumers - the row's label and dim, and
 * {@link isImminentFire}. The second is unreachable today (a declared-kind row carries no
 * schedule, so it has no fire time to be imminent), which is exactly why the two copies
 * would drift unnoticed if the rule were written twice.
 */
function isSilencedByPause(a: ActiveAutomation): boolean {
  return Boolean(a.resourcePaused) && a.triggerType !== 'ERROR';
}

/**
 * Why this row's countdown would be a lie, or null when it is honest.
 *
 * <p>Two conditions keep a schedule's `nextFireAt` accurate and its run refused, and the
 * bell has to tell them apart because they are undone by different actions: a PAUSED
 * resource is re-enabled, a BUDGET-blocked one has its cap raised or waits for the period
 * to roll. Collapsing them into one badge sent the reader to the wrong switch.
 *
 * <p>Pause wins when both hold: it is the one the reader can act on immediately, and a
 * paused resource would not run even with budget to spare.
 *
 * <p>Same shape as the agenda's, which greys the same fires from the same two facts. One
 * function here for the same reason it is one there: the row label, the dim, and the
 * imminence pulse are three sizes of a single claim.
 */
function fireSuppression(a: ActiveAutomation): 'paused' | 'budget' | null {
  if (isSilencedByPause(a)) return 'paused';
  if (a.triggerType !== 'SCHEDULE' || !a.schedule?.budgetBlocked) return null;
  // `budgetBlocked` is true NOW; this row is about the NEXT fire, which may well be on
  // the far side of the reset. A monthly cap reached on 30 September lifts at midnight,
  // and the 1 October fire runs - so labelling that row "capped", dimming it and
  // withholding the countdown would be wrong about a run that is going to happen.
  //
  // Same comparison the agenda makes per occurrence; the bell makes it once, against the
  // one fire it draws. No date at all means the cap never lifts, which suppresses
  // everything - and an unparseable one suppresses too, because the server said blocked
  // and a date we cannot read is not a reason to promise a run.
  const until = a.schedule.budgetBlockedUntil;
  if (!until) return 'budget';
  const liftsAt = Date.parse(until);
  const nextFire = a.schedule.nextFireAt ? Date.parse(a.schedule.nextFireAt) : NaN;
  if (Number.isFinite(liftsAt) && Number.isFinite(nextFire) && nextFire >= liftsAt) {
    return null;
  }
  return 'budget';
}

function isImminentFire(a: ActiveAutomation, nowMs: number = Date.now()): boolean {
  // A disabled resource keeps its nextFireAt: the schedule row stays armed and the
  // daemon still claims the slot, it just refuses at dispatch. So the timestamp alone
  // says "about to fire" about something that will not fire. Read here rather than at
  // the two call sites because both of them are the same claim in two sizes: the row's
  // blue ping and the bell's own pulse.
  if (fireSuppression(a) !== null) return false;
  const iso = a.schedule?.nextFireAt;
  if (!iso) return false;
  const target = Date.parse(iso);
  return Number.isFinite(target)
      && target - nowMs <= IMMINENT_WINDOW_MS
      && target - nowMs >= 0;
}

function hasImminentFire(items: ActiveAutomation[]): boolean {
  const now = Date.now();
  return items.some((a) => isImminentFire(a, now));
}

function resourceIcon(type: ActiveAutomation['resourceType']) {
  switch (type) {
    case 'AGENT': return Bot;
    case 'APPLICATION': return AppWindow;
    case 'WORKFLOW':
    default: return Workflow;
  }
}

/**
 * Per-kind lucide icon for TRIGGER bell rows. Mirrors the emitter contract in
 * {@code TriggerLifecycleManager.emitTriggerDisabledAfterCommit} which writes
 * one of {@code "schedule" | "webhook" | "chat" | "form"} to
 * {@code payload.triggerKind}. Unknown/missing kinds fall back to a generic
 * trigger icon ({@link Zap}) so the row still has a visual anchor instead of
 * the bare severity dot.
 */
function triggerKindIcon(kind: string | null | undefined) {
  switch (kind) {
    case 'schedule': return Clock;
    case 'webhook':  return Webhook;
    case 'chat':     return MessageSquare;
    case 'form':     return FormInput;
    default:         return Zap;
  }
}

/**
 * P7 routing - maps a bell row to the right resource page based on
 * `subjectType`. Closes the prod bug where every row routed to
 * `/app/workflow/...` regardless of subject, 404-ing CRED_EXPIRED clicks.
 *
 * Routes per subjectType:
 * <ul>
 *   <li>{@code WORKFLOW} → live run if {@code runIdPublic} is set, else edit</li>
 *   <li>{@code CREDENTIAL} → credentials settings page with focus deep-link
 *       ({@code ?credentialId=N}). Falls back to the page root when the
 *       payload didn't include a credentialId (legacy / buggy emitter).</li>
 *   <li>{@code AGENT_TASK} → tasks board (per-task deep-link is a P6 follow-up
 *       - emitter has no query-param convention today).</li>
 *   <li>{@code APPLICATION} → applications shell (subjectId IS the publication id).</li>
 *   <li>{@code TRIGGER} → triggers settings page
 *       (`/app/settings/public-access?tab={triggerKind}`). The emitter payload
 *       carries a lowercase {@code triggerKind} that maps 1:1 to the
 *       {@code TriggerTab} string union (webhook / schedule / chat / form),
 *       so the user lands directly on the correct tab. Falls back to the
 *       webhook tab (page default) when {@code triggerKind} is missing.</li>
 *   <li>default → app root (forward-compat for unknown subject_types).</li>
 * </ul>
 *
 * <p><strong>Never</strong> route to {@code /app/dashboard} - that path doesn't
 * exist in this app (would fall through to the [...notFound] catch-all). The
 * regression tests {@code triggerRowWithKindRoutesToPublicAccessWithMatchingTab}
 * (webhook + schedule variants) and
 * {@code triggerRowWithMissingKindFallsBackToWebhookTab} pin this contract.
 */
function notificationHref(item: NotificationItem): string {
  switch (item.subjectType) {
    case 'WORKFLOW':
      return item.runIdPublic
        ? `/app/workflow/${item.subjectId}/run/${item.runIdPublic}`
        : `/app/workflow/${item.subjectId}`;
    case 'CREDENTIAL':
      return item.credentialId
        ? `/app/settings/credentials?credentialId=${encodeURIComponent(item.credentialId)}`
        : `/app/settings/credentials`;
    case 'AGENT_TASK':
      return `/app/board?resource=task`;
    case 'APPLICATION':
      return `/app/applications/${item.subjectId}`;
    case 'TRIGGER': {
      // The trigger management UI lives at /app/settings/public-access with
      // one tab per kind (webhook / schedule / chat / form). The emitter
      // payload (TriggerLifecycleManager#emitTriggerDisabledAfterCommit)
      // includes a lowercase `triggerKind` that maps 1:1 to the TriggerTab
      // string union, so we forward it as `?tab=` for a direct deep-link.
      // Fallback to `webhook` (the page's default tab) when triggerKind is
      // missing - defensive for any legacy emitter that didn't set the field.
      const tab = item.triggerKind ?? 'webhook';
      return `/app/settings/public-access?tab=${encodeURIComponent(tab)}`;
    }
    case 'ORG_INVITATION':
      return `/app/invitations`;
    case 'BADGE':
      // The trophy wall is a tab of the settings overview page, which reads the
      // active tab from `?tab=` on mount.
      return `/app/settings/overview?tab=trophies`;
    default:
      // Forward-compat for unknown subject_types: land on the app root
      // which redirects to the user's chat home. Never `/app/dashboard`
      // (route doesn't exist - would 404, the bug class this fix closes).
      return `/app`;
  }
}

function resourceHref(a: ActiveAutomation): string {
  switch (a.resourceType) {
    case 'AGENT':
      // No per-agent page exists. Land on /app/agent with an ?openAgent=<id> query that
      // AgentTable consumes to open the right-side panel for the target agent. It clears the
      // id once it has acted on it, and acts again the next time one appears.
      return `/app/agent?openAgent=${a.resourceId}`;
    case 'APPLICATION':
      // v5 F4 PUB-HIJACK observability fix: the application page is keyed by
      // PUBLICATION id (`/app/applications/[publicationId]`), not workflow id.
      // Pre-fix this routed to /app/applications/{workflowId} → 404. Backend
      // now emits a.publicationId for APPLICATION rows (sourcePublicationId
      // from the workflow entity). Legacy APPLICATION rows with no publication
      // id (pre-V187) fall back to the workflow editor as a defensive route.
      return a.publicationId
        ? `/app/applications/${a.publicationId}`
        : `/app/workflow/${a.resourceId}`;
    case 'WORKFLOW':
    default:
      // Pinned workflows route to /run/{productionRunId} so the user lands on
      // the live run (matching the workflow board card's click target). When
      // the production run can't be resolved (just-pinned, no trusted run yet),
      // fall back to edit mode.
      return a.productionRunIdPublic
        ? `/app/workflow/${a.resourceId}/run/${a.productionRunIdPublic}`
        : `/app/workflow/${a.resourceId}`;
  }
}

function formatNextFire(
  iso: string | undefined,
  t: (key: string, values?: Record<string, string | number>) => string,
): string {
  if (!iso) return t('soon');
  const target = parseUtcAware(iso).getTime();
  if (Number.isNaN(target)) return t('soon');
  const deltaMs = target - Date.now();
  if (deltaMs <= 0) return t('imminent');
  const mins = Math.round(deltaMs / 60_000);
  if (mins < 1) return t('subMinute');
  if (mins < 60) return t('inMinutes', { n: mins });
  const hours = Math.round(mins / 60);
  if (hours < 24) return t('inHours', { n: hours });
  const days = Math.round(hours / 24);
  if (days < 7) return t('inDays', { n: days });
  return formatUtcDate(new Date(target));
}

function formatRelativePast(
  iso: string,
  t: (key: string, values?: Record<string, string | number>) => string,
): string {
  const target = parseUtcAware(iso).getTime();
  if (Number.isNaN(target)) return '';
  const deltaMs = Date.now() - target;
  const mins = Math.round(deltaMs / 60_000);
  if (mins < 1) return t('justNow');
  if (mins < 60) return t('minutesAgo', { n: mins });
  const hours = Math.round(mins / 60);
  if (hours < 24) return t('hoursAgo', { n: hours });
  const days = Math.round(hours / 24);
  if (days < 7) return t('daysAgo', { n: days });
  return formatUtcDate(new Date(target));
}
