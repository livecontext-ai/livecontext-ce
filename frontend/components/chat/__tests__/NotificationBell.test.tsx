/**
 * @vitest-environment jsdom
 *
 * Tests for {@link NotificationBell} - unified Inbox/Activity tabs.
 *
 * Coverage:
 * - Inbox tab row click → /run/ singular (regression vs prior /runs/ 404)
 * - Activity tab does NOT contribute to the unread badge (otherwise armed
 *   schedules would create permanent noise)
 * - Switching tabs does NOT trigger mark-as-read (only explicit button does)
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import * as React from 'react';
import { NotificationBell, TRIGGERS_ROWS_FRESH_FOR_MS } from '../NotificationBell';
import type { NotificationItem } from '@/lib/api/orchestrator/home-status.service';
import { TRIGGER_ROW_ACTIONS_YIELD } from '../TriggerRowActions';

const pushMock = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: pushMock }),
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => {
    if (vars && typeof vars.n === 'number') return `${vars.n} item(s)`;
    return key;
  },
}));

// R10 - the new <NodeIcon> rendering on Triggers tab rows + filter chips
// reaches into the ThemeProvider context. Mock it so tests don't need to
// wrap render() in <ThemeProvider>. Returning a stable light theme is
// sufficient - NodeIcon only reads `theme` for dark-mode image fallback.
// `useOptionalTheme` is mocked alongside `useTheme`: the icons in this tree render
// through `useThemeSafely`, which reads the context OPTIONALLY so the same icons can
// render on the public marketplace outside any ThemeProvider. A mock missing it throws.
vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
  useOptionalTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
}));

// R10 - TriggerType widened 2→8 to match backend `ActiveAutomationDto.TriggerType`.
// Mocks accept the full union; `schedule` becomes optional because the 6 new
// kinds carry neither `schedule` nor `webhook`.
type AutomationMock = {
  resourceType: 'WORKFLOW' | 'APPLICATION' | 'AGENT';
  resourceId: string;
  name: string;
  triggerType:
    | 'SCHEDULE' | 'WEBHOOK'
    | 'MANUAL' | 'CHAT' | 'FORM' | 'DATASOURCE' | 'WORKFLOW' | 'ERROR';
  schedule?: {
    cronExpression: string;
    timezone: string;
    executionCount: number;
    nextFireAt?: string;
    // Present on a real armed schedule, and what the row menu keys off: a row without it
    // renders no menu, which is the case the yield rule must not touch.
    scheduleId?: string;
    /** Still true while a spending cap holds the schedule back: the two are separate facts. */
    armed?: boolean;
    /** The server's verdict that a spending cap is refusing this schedule's fires. */
    budgetBlocked?: boolean;
    /** When that cap lifts by itself; absent when it never does. */
    budgetBlockedUntil?: string;
  };
  /** Agent webhooks carry a method; workflow ones may not. Presence is what the row reads. */
  webhook?: { httpMethod?: string };
  /**
   * Backend-computed: the RESOURCE is disabled, so none of its triggers will run
   * (`ActiveAutomationsService` reads `agent.isActive` for an AGENT and the production
   * run's CANCELLED status for a workflow/application).
   */
  resourcePaused?: boolean;
  lastRunAt?: string;
  lastRunStatus?: string;
  productionRunIdPublic?: string;
};

const homeStatusMock = vi.hoisted(() => ({
  current: {
    items: [] as Array<unknown>,
    unreadCount: 0,
    automations: [
      {
        resourceType: 'WORKFLOW' as const,
        resourceId: 'wf-99',
        name: 'Daily Digest',
        triggerType: 'SCHEDULE' as const,
        schedule: {
          cronExpression: '0 8 * * *',
          timezone: 'UTC',
          executionCount: 12,
        },
      },
    ] as AutomationMock[],
    lastSeenAt: null as string | null,
    isLoading: false,
    error: null as unknown,
    markAllRead: vi.fn(async () => undefined),
  },
}));
// Two callers here: the row menu asks after acting on a schedule, and the Triggers tab asks
// on every visit. The real hook reaches for a QueryClient this suite has no provider for, so
// it is a spy - what it does with the key and the freshness bound is pinned against a real
// client in TriggerRowActions' and useHomeStatus' own tests. A single shared spy, returned
// by identity, so the bell's visit effect does not re-fire on every render.
const refreshHomeStatusMock = vi.hoisted(() => vi.fn());
// One spy, but handed out through a per-workspace wrapper, exactly as the real hook does: its
// identity changes when the active workspace does. That is what lets a test check the bell
// re-asks after a switch - with a single shared function the effect could not tell.
const refreshByOrg = vi.hoisted(() => new Map<string, () => void>());
vi.mock('@/hooks/useHomeStatus', () => ({
  useHomeStatus: () => homeStatusMock.current,
  useRefreshHomeStatus: () => {
    const org = currentOrgMock.current.currentOrgId ?? '__personal__';
    if (!refreshByOrg.has(org)) refreshByOrg.set(org, (...args: unknown[]) => refreshHomeStatusMock(...args));
    return refreshByOrg.get(org)!;
  },
}));

// Inbox items live behind useNotificationsPaged (split out from useHomeStatus
// so the bell can paginate). Mock it independently - the bell reads `items`
// from this hook, not from useHomeStatus.
const inboxMock = vi.hoisted(() => ({
  current: {
    items: [
      {
        subjectId: 'wf-1',
        subjectName: 'WF',
        subjectType: 'WORKFLOW' as const,
        runIdPublic: 'run_1',
        category: 'RUN_FAILED' as const,
        severity: 'error' as const,
        count: 1,
        firstEventAt: '2026-05-08T08:00:00Z',
        lastEventAt: '2026-05-08T09:00:00Z',
        unread: true,
      },
    ] as NotificationItem[],
    unreadCount: 1,
    page: 0,
    size: 15,
    hasMore: false,
    isLoading: false,
    error: null,
    deleteBuckets: vi.fn(async () => undefined),
  },
}));
vi.mock('@/hooks/useNotificationsPaged', () => ({
  useNotificationsPaged: () => inboxMock.current,
}));

// Part 2 - chunk 6: bell now also reads useCurrentOrg + useRecentActivity.
// Mock both so existing Inbox/Triggers tests run without spinning up the
// full AppDataProvider tree.
const currentOrgMock = vi.hoisted(() => ({
  current: {
    currentOrgId: null as string | null,
    currentOrgRole: null as 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER' | null,
    setCurrentOrg: vi.fn(),
    clear: vi.fn(),
  },
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrg: () => currentOrgMock.current,
}));
const recentActivityMock = vi.hoisted(() => ({
  current: {
    items: [] as Array<unknown>,
    peerScopeCount: 0,
    peerScopeLabel: undefined as string | undefined,
    isLoading: false,
    error: null as unknown,
  },
}));
// Records the `enabled` argument each visit-only hook is called with, so a
// test can assert the lazy gate stays closed while the popover is closed (the
// always-visible bell must NOT eager-fetch Activity/Shared on every render).
const hookEnabledCalls = vi.hoisted(() => ({
  activity: [] as boolean[],
  shared: [] as boolean[],
}));
vi.mock('@/hooks/useRecentActivity', () => ({
  useRecentActivity: (enabled: boolean) => {
    hookEnabledCalls.activity.push(enabled);
    return recentActivityMock.current;
  },
}));

// 4th-tab "Shared" - bell calls useSharedConversations on every render. Mock
// the hook the same way as useRecentActivity so the test tree never touches
// react-query / useAuth.
const sharedConversationsMock = vi.hoisted(() => ({
  current: {
    items: [] as Array<unknown>,
    isLoading: false,
    error: null as unknown,
    revoke: vi.fn(async () => undefined),
  },
}));
vi.mock('@/hooks/useSharedConversations', () => ({
  useSharedConversations: (enabled: boolean) => {
    hookEnabledCalls.shared.push(enabled);
    return sharedConversationsMock.current;
  },
}));

// The run-level approval review modal is covered by its own test file; here
// we only assert the bell wires it with the notification's runIdPublic.
vi.mock('@/components/approvals/RunApprovalsDialog', () => ({
  RunApprovalsDialog: ({ runId, open }: { runId: string; open: boolean }) =>
    open ? <div data-testid="run-approvals-dialog" data-run-id={runId} /> : null,
}));

describe('NotificationBell - tabs Inbox/Activity', () => {
  beforeEach(() => {
    pushMock.mockReset();
    hookEnabledCalls.activity = [];
    hookEnabledCalls.shared = [];
    homeStatusMock.current.markAllRead.mockReset();
    refreshHomeStatusMock.mockReset();
    inboxMock.current.deleteBuckets.mockReset();
    // Reset org + recent-activity mocks to baseline so each test runs from
    // "personal scope, no recent edits" unless it overrides explicitly.
    currentOrgMock.current = {
      ...currentOrgMock.current,
      currentOrgId: null,
      currentOrgRole: null,
    };
    recentActivityMock.current = {
      ...recentActivityMock.current,
      items: [],
      peerScopeCount: 0,
      peerScopeLabel: undefined,
    };
    sharedConversationsMock.current = {
      ...sharedConversationsMock.current,
      items: [],
      isLoading: false,
    };
    sharedConversationsMock.current.revoke.mockReset();
    // Reset to the canonical mock state so test order doesn't matter - every
    // test starts from "1 inbox item, 1 automation, no production run, no
    // imminent fire". Tests then mutate only the field they care about.
    inboxMock.current = {
      ...inboxMock.current,
      items: [
        {
          subjectId: 'wf-1',
          subjectName: 'WF',
          subjectType: 'WORKFLOW' as const,
          runIdPublic: 'run_1',
          category: 'RUN_FAILED' as const,
          severity: 'error' as const,
          count: 1,
          firstEventAt: '2026-05-08T08:00:00Z',
          lastEventAt: '2026-05-08T09:00:00Z',
          unread: true,
        },
      ],
      unreadCount: 1,
    };
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-99',
          name: 'Daily Digest',
          triggerType: 'SCHEDULE' as const,
          schedule: {
            cronExpression: '0 8 * * *',
            timezone: 'UTC',
            executionCount: 12,
          },
        },
      ],
      unreadCount: 1,
      items: [],
    };
  });

  it('Inbox row click navigates to /app/workflow/{id}/run/{runId} (singular "run")', () => {
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    // Default tab = Inbox; the row's clickable surface is an absolute overlay
    // button labelled `Open <workflowName>` (the visible text is in a sibling
    // span with pointer-events-none, so we must target the overlay directly).
    fireEvent.click(screen.getByRole('button', { name: 'Open WF' }));

    expect(pushMock).toHaveBeenCalledTimes(1);
    const url = pushMock.mock.calls[0][0];
    expect(url).toBe('/app/workflow/wf-1/run/run_1');
    expect(url).not.toContain('/runs/');
  });

  it('Switching to Triggers tab renders armed automations and does NOT call markAllRead', () => {
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));

    // Switch tab - R10 renamed the i18n key activityTab → triggersTab.
    fireEvent.click(screen.getByText('triggersTab'));

    // Activity row is now visible.
    expect(screen.getByText('Daily Digest')).toBeTruthy();
    // Mark-all stays off - switching tabs is not "I've seen this".
    expect(homeStatusMock.current.markAllRead).not.toHaveBeenCalled();
  });

  it('Landing on the Triggers tab asks for the rows again (regression: the tab read one step behind a just-pinned workflow)', () => {
    // The rows ride the always-on home-status query and NO mutation invalidates it, so
    // pinning a workflow or arming a trigger left the tab showing the payload from before
    // the action, for as long as the 60s poll had left to run.
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));

    // The bell lands on Inbox here (the inbox mock has an item) and Inbox does not read
    // automations - nothing to top up yet.
    expect(refreshHomeStatusMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByText('triggersTab'));

    expect(refreshHomeStatusMock).toHaveBeenCalledTimes(1);
    // Asked WITH a freshness bound, not unconditionally: a bare ask would be a request per tab
    // click. The bound is read from the component so this pins the contract, not a literal.
    expect(refreshHomeStatusMock).toHaveBeenCalledWith({ freshForMs: TRIGGERS_ROWS_FRESH_FOR_MS });
    // Pinned exactly, not bracketed. The value is argued in the component: long enough to make
    // tab-flipping free, short enough that a user reading the tab to check their own action
    // never reads the state from before it. Both ends matter, so a change to it is a decision
    // that comes here to be re-argued rather than one that slips through a range.
    expect(TRIGGERS_ROWS_FRESH_FOR_MS).toBe(2_000);
  });

  it('Leaving and coming back to the Triggers tab asks again (one ask per visit)', () => {
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));

    fireEvent.click(screen.getByText('triggersTab'));
    fireEvent.click(screen.getByText('inboxTab'));
    fireEvent.click(screen.getByText('triggersTab'));

    // Twice, not three times: the Inbox detour is not a visit to Triggers.
    expect(refreshHomeStatusMock).toHaveBeenCalledTimes(2);
  });

  it('Asks again when the workspace changes while the Triggers tab is open', () => {
    // The rows are per workspace, so switching while looking at them must ask for the new
    // workspace's. The effect can only notice through the refresh's identity, which the hook
    // re-creates per workspace - so an effect that depends on the visible flag alone goes quiet
    // here and leaves the previous workspace's rows on screen.
    const { rerender } = render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));
    expect(refreshHomeStatusMock).toHaveBeenCalledTimes(1);

    currentOrgMock.current = { ...currentOrgMock.current, currentOrgId: 'org-2' };
    rerender(<NotificationBell />);

    expect(refreshHomeStatusMock).toHaveBeenCalledTimes(2);
  });

  it('Opening the bell straight onto Triggers through the empty-inbox fallback asks too', () => {
    // The landing tab is chosen from `automations` when the inbox is empty, so this path never
    // goes through a tab click - and it is the one a user with a quiet inbox always takes.
    inboxMock.current = { ...inboxMock.current, items: [], unreadCount: 0 };

    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));

    expect(screen.getByText('Daily Digest')).toBeTruthy();
    expect(refreshHomeStatusMock).toHaveBeenCalledTimes(1);
    expect(refreshHomeStatusMock).toHaveBeenCalledWith({ freshForMs: TRIGGERS_ROWS_FRESH_FOR_MS });
  });

  it('Does not ask while the Triggers tab is off screen, and asks again when the bell reopens onto it', () => {
    render(<NotificationBell />);
    const bell = screen.getByRole('button', { name: 'title' });

    fireEvent.click(bell);
    fireEvent.click(screen.getByText('triggersTab'));
    expect(refreshHomeStatusMock).toHaveBeenCalledTimes(1);

    // Closing the popover keeps `tab` on Triggers but takes it off screen - a hidden tab
    // must cost nothing.
    fireEvent.click(bell);
    expect(refreshHomeStatusMock).toHaveBeenCalledTimes(1);

    // Reopening lands straight back on Triggers: that IS a visit, and it is the exact
    // moment the user expects to be looking at current rows.
    fireEvent.click(bell);
    expect(refreshHomeStatusMock).toHaveBeenCalledTimes(2);
  });

  it('Triggers row click navigates to the resource', () => {
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));
    // The row-click target is an overlay button labelled with the automation name, not
    // the name text itself: the row also carries a schedule-actions menu, and a button
    // inside a button is invalid HTML. Same shape as the Shared tab rows.
    fireEvent.click(screen.getByRole('button', { name: 'Daily Digest' }));

    expect(pushMock).toHaveBeenCalledWith('/app/workflow/wf-99');
  });

  it('Triggers row with productionRunIdPublic routes to /run/{prodRun} (regression: Issue 2)', () => {
    // Pinned workflows surfaced via the bell MUST route to run mode - same
    // click target as the workflow board card. Without this, the user lands
    // in edit mode on every click and loses the live state.
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          ...homeStatusMock.current.automations[0],
          productionRunIdPublic: 'run_<id>',
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));
    fireEvent.click(screen.getByRole('button', { name: 'Daily Digest' }));

    expect(pushMock).toHaveBeenCalledWith(
      '/app/workflow/wf-99/run/run_<id>'
    );
  });

  it('SCHEDULE row shows lastRan subtitle when lastRunAt is set (Part 1)', () => {
    // Part 1 - SCHEDULE rows render BOTH the next-fire countdown (top-right)
    // and a muted "Last: {relative}" subtitle (bottom-right). DTO field
    // `lastRunAt` is pre-populated by ActiveAutomationsService:303 from
    // ScheduledExecutionDto.lastExecutionAt || workflow.lastExecutedAt fallback.
    const twoMinAgo = new Date(Date.now() - 2 * 60_000).toISOString();
    const inOneHour = new Date(Date.now() + 60 * 60_000).toISOString();
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-sched',
          name: 'Hourly job',
          triggerType: 'SCHEDULE' as const,
          schedule: {
            cronExpression: '0 * * * *',
            timezone: 'UTC',
            executionCount: 5,
            nextFireAt: inOneHour,
          },
          lastRunAt: twoMinAgo,
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));

    // The i18n mock returns the bare key for `t('lastRan')` → 'lastRan'.
    // Asserting the prefix appears in the DOM proves the subtitle line rendered.
    expect(screen.getByText(/lastRan/)).toBeTruthy();
  });

  it('SCHEDULE row with null lastRunAt renders neverRan placeholder (Part 1)', () => {
    // Never-fired schedule (e.g. just-pinned, cron hasn't reached its first
    // tick) must render the `neverRan` placeholder ("-") on the second line
    // instead of an empty string or "lastRan undefined".
    const inOneHour = new Date(Date.now() + 60 * 60_000).toISOString();
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-new',
          name: 'New schedule',
          triggerType: 'SCHEDULE' as const,
          schedule: {
            cronExpression: '0 * * * *',
            timezone: 'UTC',
            executionCount: 0,
            nextFireAt: inOneHour,
          },
          // no lastRunAt - never fired
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));

    expect(screen.getByText(/neverRan/)).toBeTruthy();
  });

  it('hovers to a light ground, not to the black tile `ghost` gives it by default', () => {
    // What the user saw: pointing at the bell turned it into a near-black square with a
    // pale glyph. `ghost` hovers by INVERTING (`hover:bg-[var(--text-primary)]`), which is
    // the app's legacy behaviour that dozens of call sites rely on, so the variant is left
    // alone and this one control overrides it. Measured in a browser: the old pair computed
    // to rgb(17,24,39) with white text, the new one to rgb(229,231,235) with black.
    render(<NotificationBell />);
    const bell = screen.getByRole('button', { name: 'title' });

    expect(bell.className).toContain('hover:bg-surface-hover');
    expect(bell.className).not.toContain('hover:bg-[var(--text-primary)]');
  });

  it('a schedule row hands its fire-time column the rule the row menu exports', () => {
    // The link between two files, asserted where it can actually break. The dots are
    // revealed ON TOP of this column, so the column has to step aside in exactly the
    // situations the menu appears in - and the rule that says which those are lives in
    // TriggerRowActions. Retyping it here would test a copy; reading the export means
    // deleting the class from the bell fails this, which is the regression it guards.
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-sched',
          name: 'Nightly report',
          triggerType: 'SCHEDULE' as const,
          schedule: {
            cronExpression: '0 3 * * *',
            timezone: 'UTC',
            executionCount: 2,
            scheduleId: 'sched-1',
            nextFireAt: new Date(Date.now() + 60 * 60_000).toISOString(),
          },
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));

    const column = screen.getByText(/lastRan|neverRan/).closest('span.flex-col')!;
    for (const token of TRIGGER_ROW_ACTIONS_YIELD.split(/\s+/)) {
      expect(column.className).toContain(token);
    }
  });

  it('a row with no menu keeps its label on hover, because nothing is revealed over it', () => {
    // Regression: the rule was applied to every row while the button renders only for
    // schedules. Hovering a webhook row therefore faded its own "Live" badge to nothing and
    // put no control in its place - a label that vanishes under the pointer, for no reason
    // the user can see.
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-webhook',
          name: 'Webhook flow',
          triggerType: 'WEBHOOK' as const,
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));

    // The Triggers tab opens filtered to SCHEDULE, so a webhook-only fixture is behind
    // its chip. (Worth knowing: the older webhook test above passes without this because
    // it only asserts an ABSENCE, which an empty list satisfies for free.)
    fireEvent.click(screen.getByRole('button', { name: 'kindLabel.webhook' }));

    const column = screen.getByText('liveBadge').closest('span.flex-col')!;
    expect(column.className).not.toContain('group-hover:opacity-0');
  });

  it('WEBHOOK row renders the lastRan line too - every kind answers "when did this last run"', () => {
    // The line used to be SCHEDULE-only, so a webhook row said "live" and nothing
    // about its history. A webhook that fired 2 minutes ago now says so, on the
    // same line shape as every other kind. It costs no row height: the left column
    // (name + subtitle) is already two lines tall.
    const twoMinAgo = new Date(Date.now() - 2 * 60_000).toISOString();
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-webhook',
          name: 'Webhook flow',
          triggerType: 'WEBHOOK' as const,
          lastRunAt: twoMinAgo,
          lastRunStatus: 'COMPLETED',
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));
    // The tab opens filtered to SCHEDULE - reach the webhook row through its chip.
    fireEvent.click(screen.getByRole('button', { name: 'kindLabel.webhook' }));

    // Both the forward-looking "live" badge and the backward-looking last-run line.
    expect(screen.getByText('liveBadge')).toBeTruthy();
    expect(screen.getByText(/lastRan/)).toBeTruthy();
  });

  it('lastRunStatus COMPLETED draws the emerald check the run panel uses; FAILED draws the red cross', () => {
    // The whole point of the badge: the bell must show the SAME verdict icon as
    // the epoch row it links to, so the two surfaces cannot disagree. Asserting on
    // the lucide class names is asserting on EpochStatusIcon's own branches - a
    // future divergence (a different icon, a different colour) fails here.
    const twoMinAgo = new Date(Date.now() - 2 * 60_000).toISOString();
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-ok',
          name: 'Good flow',
          triggerType: 'MANUAL' as const,
          lastRunAt: twoMinAgo,
          lastRunStatus: 'COMPLETED',
        },
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-ko',
          name: 'Broken flow',
          triggerType: 'MANUAL' as const,
          lastRunAt: twoMinAgo,
          lastRunStatus: 'FAILED',
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));
    // The tab opens filtered to SCHEDULE - reach the manual rows through their chip.
    fireEvent.click(screen.getByRole('button', { name: 'kindLabel.manual' }));

    // The popover renders through a portal, so query the document, not the container.
    // Asserting on the colour classes EpochStatusIcon itself applies (rather than on
    // lucide's own class names) keeps the test pinned to OUR verdict-to-icon mapping.
    expect(document.body.querySelectorAll('svg.text-emerald-500')).toHaveLength(1);
    expect(document.body.querySelectorAll('svg.text-red-500')).toHaveLength(1);
    // The icon is aria-hidden, so the verdict also has to exist as a WORD. The i18n mock
    // echoes keys, so getRunStatusLabel resolves to 'status.completed' / 'status.failed'.
    expect(document.body.querySelector('[data-last-run-status="COMPLETED"]')?.textContent)
      .toBe('status.completed');
    expect(document.body.querySelector('[data-last-run-status="FAILED"]')?.textContent)
      .toBe('status.failed');
  });

  it('a WEBHOOK row that has never run shows NO last-run line', () => {
    // The line speaks about a run. A webhook nobody has called yet has none, and it is not
    // waiting for one either, so a permanent "Last: -" would be noise. (A SCHEDULE row is the
    // exception and keeps saying "never" - it IS waiting for a fire that is scheduled.)
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'AGENT' as const,
          resourceId: 'agent-1',
          name: 'Briefing agent',
          triggerType: 'WEBHOOK' as const,
          webhook: { httpMethod: 'POST' },
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));
    fireEvent.click(screen.getByRole('button', { name: 'kindLabel.webhook' }));

    expect(screen.getByText('Briefing agent')).toBeTruthy();
    expect(screen.queryByText(/lastRan/)).toBeNull();
    expect(screen.queryByText(/neverRan/)).toBeNull();
  });

  it('an AGENT schedule row DOES show its last-run line - the schedule knows when it fired', () => {
    // Agents have no production run, so they are never badged. They do have a schedule with a
    // lastExecutionAt, and hiding that would drop information the row used to show.
    const twoMinAgo = new Date(Date.now() - 2 * 60_000).toISOString();
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'AGENT' as const,
          resourceId: 'agent-2',
          name: 'Morning briefing',
          triggerType: 'SCHEDULE' as const,
          schedule: {
            cronExpression: '0 7 * * *',
            timezone: 'UTC',
            executionCount: 4,
            nextFireAt: new Date(Date.now() + 60 * 60_000).toISOString(),
          },
          lastRunAt: twoMinAgo,
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));

    expect(screen.getByText(/lastRan/)).toBeTruthy();
    // ...and no verdict: there is no epoch behind an agent row to have one.
    expect(document.body.querySelectorAll('svg.text-emerald-500')).toHaveLength(0);
    expect(document.body.querySelectorAll('svg.text-red-500')).toHaveLength(0);
  });

  it('lastRunStatus RUNNING draws the live pulse, not a verdict glyph', () => {
    // RUNNING is a value the bell never used to receive: an epoch still open under an
    // executing run. It is also the only one carrying an animation into a popover that
    // refreshes on a timer, so it is the branch most worth pinning. EpochStatusIcon renders
    // it as pulsing spans, NOT an <svg> - the check/cross assertions elsewhere cannot see it.
    const secondsAgo = new Date(Date.now() - 30_000).toISOString();
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-live',
          name: 'Running flow',
          triggerType: 'MANUAL' as const,
          lastRunAt: secondsAgo,
          lastRunStatus: 'RUNNING',
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));
    fireEvent.click(screen.getByRole('button', { name: 'kindLabel.manual' }));

    expect(document.body.querySelectorAll('span.animate-ping').length).toBeGreaterThan(0);
    expect(document.body.querySelectorAll('svg.text-emerald-500')).toHaveLength(0);
    expect(document.body.querySelector('[data-last-run-status="RUNNING"]')?.textContent)
      .toBe('status.running');
  });

  it('a declared-kind row states its last run ONCE - the top label is the kind icon alone', () => {
    // The relative time used to be the row's top-right label. It moved into the "Last:" line
    // with the verdict beside it; leaving a copy behind would print the same timestamp twice,
    // one of them with no verdict, which reads as two different runs.
    const twoMinAgo = new Date(Date.now() - 2 * 60_000).toISOString();
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-once',
          name: 'Manual flow',
          triggerType: 'MANUAL' as const,
          lastRunAt: twoMinAgo,
          lastRunStatus: 'COMPLETED',
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));
    fireEvent.click(screen.getByRole('button', { name: 'kindLabel.manual' }));

    // The i18n mock renders a count-carrying key as "N item(s)", which is what
    // formatRelativePast produces here. It must appear exactly once in the row's right column.
    const column = screen.getByText(/lastRan/).closest('span.flex-col')!;
    expect(column.textContent!.match(/item\(s\)/g) ?? []).toHaveLength(1);
  });

  it('A row whose backend sends no lastRunStatus renders the time with NO verdict icon', () => {
    // Null is not "unknown status", it is "the backend has nothing honest to say"
    // (never fired, no production run, an epoch that ran nothing but its trigger).
    // Drawing any icon there would be an invented verdict - EpochStatusIcon keeps
    // the slot's width and draws nothing.
    const twoMinAgo = new Date(Date.now() - 2 * 60_000).toISOString();
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-silent',
          name: 'Silent flow',
          triggerType: 'MANUAL' as const,
          lastRunAt: twoMinAgo,
          // lastRunStatus deliberately omitted
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));
    fireEvent.click(screen.getByRole('button', { name: 'kindLabel.manual' }));

    expect(screen.getByText(/lastRan/)).toBeTruthy();
    expect(document.body.querySelectorAll('svg.text-emerald-500')).toHaveLength(0);
    expect(document.body.querySelectorAll('svg.text-red-500')).toHaveLength(0);
  });

  it('Bell pulses imminent + zero unread → opening lands directly on Activity tab (regression: Issue 1)', () => {
    // imminent => nextFireAt is within 5min from now. Zero unread inbox so the
    // pulse is the only signal. Opening MUST land on Activity, not Inbox -
    // otherwise the pulse is a mystery ping with no surface.
    inboxMock.current = { ...inboxMock.current, items: [], unreadCount: 0 };
    const inOneMinute = new Date(Date.now() + 60_000).toISOString();
    homeStatusMock.current = {
      ...homeStatusMock.current,
      unreadCount: 0,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-imminent',
          name: 'About to fire',
          triggerType: 'SCHEDULE' as const,
          schedule: {
            cronExpression: '0 * * * *',
            timezone: 'UTC',
            executionCount: 0,
            nextFireAt: inOneMinute,
          },
        },
      ],
    };
    render(<NotificationBell />);
    // Open the popover - there are no inbox items, so if we landed on the
    // default Inbox tab the activity row would be hidden.
    fireEvent.click(screen.getByRole('button', { name: 'title' }));

    expect(screen.getByText('About to fire')).toBeTruthy();
  });

  it('opening while the inbox is still loading stays on Inbox (no bounce to Activity)', () => {
    // While the first fetch is in flight the hook reports items: [] / unreadCount: 0,
    // which is indistinguishable from a genuinely empty inbox. Falling back then sent
    // the user to Activity and hid rows that landed one tick later - the CE approval
    // and org-invitation bell specs both failed exactly this way.
    inboxMock.current = { ...inboxMock.current, items: [], unreadCount: 0, isLoading: true };
    homeStatusMock.current = { ...homeStatusMock.current, unreadCount: 0, automations: [] };

    const view = render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));

    // Rows arrive a tick later; because we stayed on Inbox they are visible.
    inboxMock.current = {
      ...inboxMock.current,
      isLoading: false,
      unreadCount: 1,
      items: [
        {
          subjectId: 'wf-late',
          subjectName: 'Landed after the click',
          subjectType: 'WORKFLOW' as const,
          runIdPublic: 'run_late',
          category: 'APPROVAL_PENDING' as const,
          severity: 'warning' as const,
          count: 1,
          firstEventAt: '2026-05-08T08:00:00Z',
          lastEventAt: '2026-05-08T09:00:00Z',
          unread: true,
        },
      ] as NotificationItem[],
    };
    view.rerender(<NotificationBell />);

    expect(screen.getByText('Landed after the click')).toBeTruthy();
    expect(screen.queryByTestId('inbox-approval-open')).toBeTruthy();
  });

  it('opening a settled, genuinely empty inbox still falls back to Activity', () => {
    // The fallback must survive the loading guard: an inbox that has ANSWERED with
    // nothing should not open onto a blank tab.
    inboxMock.current = { ...inboxMock.current, items: [], unreadCount: 0, isLoading: false };
    homeStatusMock.current = { ...homeStatusMock.current, unreadCount: 0, automations: [] };

    recentActivityMock.current = { ...recentActivityMock.current, items: [], peerScopeCount: 0 };

    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));

    // Landing on Activity: its empty state, not the Inbox's.
    expect(screen.getByText(/emptyFirstRun/)).toBeTruthy();
  });

  it('Activity tab (Part 2) renders recent-edit rows when populated', () => {
    // Switch to the new Activity tab and verify the row from useRecentActivity
    // mock renders. Validates: 3rd tab is wired, RecentActivityList consumes
    // the hook, row name shows up.
    recentActivityMock.current = {
      ...recentActivityMock.current,
      items: [
        {
          kind: 'WORKFLOW',
          resourceId: 'wf-recent-1',
          name: 'Recent WF',
          lastEditedAt: new Date(Date.now() - 60_000).toISOString(),
          actorId: '42',
          actorDisplayName: 'Alice',
        },
      ],
      peerScopeCount: 0,
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('activityTab'));

    expect(screen.getByText('Recent WF')).toBeTruthy();
  });

  it('Activity tab APPLICATION row routes to /app/applications/{publicationId}, NOT the workflow id (regression: "Failed to load application")', () => {
    // The application page is keyed by PUBLICATION id, not workflow id. The
    // recent-activity resourceId IS the workflow id, so routing to
    // /app/applications/{resourceId} 404s with "Failed to load application".
    // The backend now carries publicationId; the row must route to it.
    recentActivityMock.current = {
      ...recentActivityMock.current,
      items: [
        {
          kind: 'APPLICATION',
          resourceId: 'wf-app-1', // workflow id - must NOT be the route target
          name: 'My Application',
          lastEditedAt: new Date(Date.now() - 60_000).toISOString(),
          actorId: '1',
          actorDisplayName: 'Me',
          publicationId: 'pub-abc',
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('activityTab'));
    fireEvent.click(screen.getByText('My Application'));

    expect(pushMock).toHaveBeenCalledWith('/app/applications/pub-abc');
    // Guard against re-introducing the workflow-id route that fails to load.
    expect(pushMock).not.toHaveBeenCalledWith('/app/applications/wf-app-1');
  });

  it('Activity tab AGENT row opens the agent panel instead of a route that 404s', () => {
    // Agents have no page of their own: `/app/agent/<id>` is a 404, and the board reads the
    // query to know which agent to open.
    recentActivityMock.current = {
      ...recentActivityMock.current,
      items: [
        {
          kind: 'AGENT',
          resourceId: 'ag-7',
          name: 'Nova',
          lastEditedAt: new Date(Date.now() - 60_000).toISOString(),
          actorId: '1',
          actorDisplayName: 'Me',
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('activityTab'));
    fireEvent.click(screen.getByText('Nova'));

    expect(pushMock).toHaveBeenCalledWith('/app/agent?openAgent=ag-7');
    expect(pushMock).not.toHaveBeenCalledWith('/app/agent/ag-7');
  });

  it('Activity tab SKILL row lands among the skills, not on the agents board', () => {
    // Skills share the agent shell but live on their own tab. Without naming it the row
    // dropped the user on the list of agents, with no sign of the skill they clicked.
    recentActivityMock.current = {
      ...recentActivityMock.current,
      items: [
        {
          kind: 'SKILL',
          resourceId: 'sk-3',
          name: 'Summarise',
          lastEditedAt: new Date(Date.now() - 120_000).toISOString(),
          actorId: '1',
          actorDisplayName: 'Me',
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('activityTab'));
    fireEvent.click(screen.getByText('Summarise'));

    expect(pushMock).toHaveBeenCalledWith('/app/agent?view=skills');
  });

  it('Activity tab APPLICATION row with no publicationId falls back to the workflow editor', () => {
    // Legacy applications without a source publication can't open the app page;
    // fall back to the workflow editor (mirrors the Triggers tab resourceHref).
    recentActivityMock.current = {
      ...recentActivityMock.current,
      items: [
        {
          kind: 'APPLICATION',
          resourceId: 'wf-app-2',
          name: 'Legacy Application',
          lastEditedAt: new Date(Date.now() - 60_000).toISOString(),
          actorId: '1',
          actorDisplayName: 'Me',
          // publicationId intentionally omitted
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('activityTab'));
    fireEvent.click(screen.getByText('Legacy Application'));

    expect(pushMock).toHaveBeenCalledWith('/app/workflow/wf-app-2');
  });

  it('Activity tab empty + peerScopeCount>0 + in org workspace → cross-scope hint with Switch CTA', () => {
    // Empty current scope BUT user has items in Personal. The 3-state empty
    // branch picks the cross-scope variant; the Switch-to-Personal CTA must
    // appear (resolves auditor C v5 must-fix that the empty-state hint must
    // surface peer-scope items rather than mislead with first-run copy).
    recentActivityMock.current = {
      ...recentActivityMock.current,
      items: [],
      peerScopeCount: 7,
      peerScopeLabel: 'Personal',
    };
    currentOrgMock.current = {
      ...currentOrgMock.current,
      currentOrgId: 'org-1',
      currentOrgRole: 'MEMBER',
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('activityTab'));

    // i18n mock returns the bare key for plurals when value bag has `n` →
    // "7 item(s)" per the mock at line 23. The Switch CTA renders via
    // t('switchToPeer').
    expect(screen.getByText(/switchToPeer/)).toBeTruthy();
  });

  it('Activity tab empty + peerScopeCount=0 → true first-run CTA (no cross-scope mislead)', () => {
    // Both current AND peer scopes empty: render the "Create your first
    // workflow"-style first-run CTA. Distinct from the cross-scope variant.
    recentActivityMock.current = {
      ...recentActivityMock.current,
      items: [],
      peerScopeCount: 0,
      peerScopeLabel: undefined,
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('activityTab'));

    expect(screen.getByText(/emptyFirstRun/)).toBeTruthy();
    // Cross-scope CTA must NOT render - would mislead the user.
    expect(screen.queryByText(/switchToPeer/)).toBeNull();
  });

  it('Mark all read fires only on explicit button click', () => {
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    // Bell-open alone must NOT mark read.
    expect(homeStatusMock.current.markAllRead).not.toHaveBeenCalled();

    // Explicit button click on the inbox tab.
    fireEvent.click(screen.getByText('markAllRead'));
    expect(homeStatusMock.current.markAllRead).toHaveBeenCalledTimes(1);
  });

  it('Bell stays VISIBLE when Inbox + Triggers are empty (always-visible entry point)', () => {
    // The bell is the permanent entry point to all four tabs (Activity/Shared
    // hold content the user could otherwise never reach). It must render even
    // with zero inbox items, zero unread, and zero automations.
    inboxMock.current = { ...inboxMock.current, items: [], unreadCount: 0 };
    homeStatusMock.current = {
      ...homeStatusMock.current,
      items: [],
      automations: [],
      unreadCount: 0,
    };
    render(<NotificationBell />);
    expect(screen.getByRole('button', { name: 'title' })).toBeTruthy();
  });

  it('Empty inbox + NO automations → opening lands on the Activity tab (always-visible fallback)', () => {
    // With nothing in Inbox or Triggers, opening must land on Activity - the
    // tab most likely to have content (recently-edited resources) - so the
    // always-visible bell never opens onto a blank surface.
    inboxMock.current = { ...inboxMock.current, items: [], unreadCount: 0 };
    homeStatusMock.current = {
      ...homeStatusMock.current,
      items: [],
      automations: [],
      unreadCount: 0,
    };
    recentActivityMock.current = {
      ...recentActivityMock.current,
      items: [
        {
          kind: 'WORKFLOW',
          resourceId: 'wf-recent-x',
          name: 'My Recent WF',
          lastEditedAt: new Date(Date.now() - 60_000).toISOString(),
          actorId: '1',
          actorDisplayName: 'Me',
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));

    // Landed on Activity without a manual tab switch → the recent row shows.
    expect(screen.getByText('My Recent WF')).toBeTruthy();
  });

  it('Empty inbox + automations present (non-imminent) → opening lands on the Triggers tab', () => {
    // The other arm of the default-tab ternary: when there ARE armed automations
    // (even non-imminent), an empty-inbox open must land on Triggers, not Activity.
    inboxMock.current = { ...inboxMock.current, items: [], unreadCount: 0 };
    homeStatusMock.current = {
      ...homeStatusMock.current,
      items: [],
      unreadCount: 0,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-armed',
          name: 'Nightly Backup',
          triggerType: 'SCHEDULE' as const,
          // No nextFireAt → NOT imminent, so this isolates the automations>0 arm
          // from the imminent-fire path.
          schedule: { cronExpression: '0 2 * * *', timezone: 'UTC', executionCount: 3 },
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));

    // Landed on Triggers → the automation row shows without a manual switch.
    expect(screen.getByText('Nightly Backup')).toBeTruthy();
  });

  it('Closed always-visible bell does NOT eager-fetch Activity/Shared (lazy gate preserved)', () => {
    // Headline risk of always-visible: the visit-only hooks must STILL be gated.
    // While the popover is closed, both hooks must be invoked with enabled=false
    // so they never fetch until their tab is actually opened.
    inboxMock.current = { ...inboxMock.current, items: [], unreadCount: 0 };
    homeStatusMock.current = {
      ...homeStatusMock.current,
      items: [],
      automations: [],
      unreadCount: 0,
    };
    render(<NotificationBell />);

    // Bell is present but NOT opened.
    expect(screen.getByRole('button', { name: 'title' })).toBeTruthy();
    // Every render so far had the popover closed → both gates resolved false.
    expect(hookEnabledCalls.activity.length).toBeGreaterThan(0);
    expect(hookEnabledCalls.activity.every((v) => v === false)).toBe(true);
    expect(hookEnabledCalls.shared.length).toBeGreaterThan(0);
    expect(hookEnabledCalls.shared.every((v) => v === false)).toBe(true);
  });

  // ============================================================================
  // P7 - subject-type routing (CREDENTIAL / AGENT_TASK / APPLICATION / TRIGGER)
  //
  // Regression for the prod bug where every bell row routed to /app/workflow/...
  // regardless of subject_type, 404-ing CRED_EXPIRED clicks. Each test below
  // asserts the click target matches the row's subject type, not the legacy
  // workflow-only fallback.
  // ============================================================================

  it('credentialRowRoutesToCredentialsPageNotWorkflow (regression - prod CRED_EXPIRED 404 bug)', () => {
    inboxMock.current = {
      ...inboxMock.current,
      items: [
        {
          subjectId: '4f903574-6db8-34a3-b9b0-dab41ca1873f', // synthetic UUID for cred-51
          subjectName: 'test',
          subjectType: 'CREDENTIAL' as const,
          runIdPublic: null,
          category: 'CRED_EXPIRED' as const,
          severity: 'warning' as const,
          count: 1,
          firstEventAt: '2026-05-12T11:39:42Z',
          lastEventAt: '2026-05-12T11:39:42Z',
          unread: true,
          integration: 'googlecalendar',
          credentialId: '51',
        },
      ],
      unreadCount: 1,
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByRole('button', { name: 'Open test' }));

    expect(pushMock).toHaveBeenCalledTimes(1);
    const url = pushMock.mock.calls[0][0];
    expect(url).toBe('/app/settings/credentials?credentialId=51');
    // The prod bug routed here - guard against re-introducing it.
    expect(url).not.toContain('/app/workflow/');
  });

  it('credentialRowWithNullCredentialIdRoutesToCredentialsRoot (graceful fallback)', () => {
    inboxMock.current = {
      ...inboxMock.current,
      items: [
        {
          subjectId: 'abc-uuid',
          subjectName: 'Legacy Cred',
          subjectType: 'CREDENTIAL' as const,
          runIdPublic: null,
          category: 'CRED_EXPIRED' as const,
          severity: 'warning' as const,
          count: 1,
          firstEventAt: '2026-05-12T11:39:42Z',
          lastEventAt: '2026-05-12T11:39:42Z',
          unread: true,
          integration: 'gmail',
          credentialId: null, // legacy row, missing payload field
        },
      ],
      unreadCount: 1,
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByRole('button', { name: 'Open Legacy Cred' }));

    expect(pushMock).toHaveBeenCalledWith('/app/settings/credentials');
  });

  it('credentialRowRendersServiceIcon (visual: API icon next to severity dot)', () => {
    inboxMock.current = {
      ...inboxMock.current,
      items: [
        {
          subjectId: 'uuid-x',
          subjectName: 'test',
          subjectType: 'CREDENTIAL' as const,
          runIdPublic: null,
          category: 'CRED_EXPIRED' as const,
          severity: 'warning' as const,
          count: 1,
          firstEventAt: '2026-05-12T11:39:42Z',
          lastEventAt: '2026-05-12T11:39:42Z',
          unread: true,
          integration: 'googlecalendar',
          credentialId: '51',
        },
      ],
      unreadCount: 1,
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));

    // next/image in jsdom emits the iconSlug in the src/srcSet (possibly inside
    // a /_next/image?url=... query). Check the rendered DOM serialization
    // contains the slug rather than coupling to next/image's exact element
    // shape - the contract is "the integration slug reaches the markup".
    expect(document.body.innerHTML).toContain('googlecalendar');
  });

  it('triggerRowWithKindRoutesToPublicAccessWithMatchingTab (webhook)', () => {
    // Each trigger kind has its own tab on /app/settings/public-access.
    // Emitter payload carries `triggerKind` (lowercase) which the bell forwards
    // verbatim as `?tab=...`. Webhook test pins the most common case.
    inboxMock.current = {
      ...inboxMock.current,
      items: [
        {
          subjectId: 'trigger-uuid',
          subjectName: 'My Webhook',
          subjectType: 'TRIGGER' as const,
          runIdPublic: null,
          category: 'WEBHOOK_TRIGGER_DISABLED' as const,
          severity: 'warning' as const,
          count: 1,
          firstEventAt: '2026-05-12T11:00:00Z',
          lastEventAt: '2026-05-12T11:00:00Z',
          unread: true,
          triggerKind: 'webhook',
        },
      ],
      unreadCount: 1,
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByRole('button', { name: 'Open My Webhook' }));

    const url = pushMock.mock.calls[0][0];
    expect(url).toBe('/app/settings/public-access?tab=webhook');
    // Must not be the old workflow-board placeholder (the bug class this fix
    // closes) nor `/app/dashboard` (route doesn't exist - would 404).
    expect(url).not.toBe('/app/workflow');
    expect(url).not.toContain('/app/dashboard');
  });

  it('triggerRowWithKindRoutesToPublicAccessWithMatchingTab (schedule)', () => {
    // The "cron expired" case the user was hitting in prod - disabled schedule
    // trigger must land on the schedule tab specifically, not on the workflow
    // board (where the user had no actionable surface for a suspended cron).
    inboxMock.current = {
      ...inboxMock.current,
      items: [
        {
          subjectId: 'trigger-uuid',
          subjectName: 'Daily Email Digest',
          subjectType: 'TRIGGER' as const,
          runIdPublic: null,
          category: 'WEBHOOK_TRIGGER_DISABLED' as const,
          severity: 'warning' as const,
          count: 1,
          firstEventAt: '2026-05-13T07:00:00Z',
          lastEventAt: '2026-05-13T07:00:00Z',
          unread: true,
          triggerKind: 'schedule',
        },
      ],
      unreadCount: 1,
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByRole('button', { name: 'Open Daily Email Digest' }));

    expect(pushMock).toHaveBeenCalledWith('/app/settings/public-access?tab=schedule');
  });

  it('triggerRowWithMissingKindFallsBackToWebhookTab', () => {
    // Defensive: legacy / future emitters that don't set triggerKind in the
    // payload must still produce a valid URL - the bell falls back to the
    // page's default tab (webhook). Without the fallback, the bell would push
    // `?tab=undefined` and the page would render the empty default tab anyway,
    // but the URL would be polluted in user history.
    inboxMock.current = {
      ...inboxMock.current,
      items: [
        {
          subjectId: 'trigger-uuid',
          subjectName: 'Legacy Trigger',
          subjectType: 'TRIGGER' as const,
          runIdPublic: null,
          category: 'WEBHOOK_TRIGGER_DISABLED' as const,
          severity: 'warning' as const,
          count: 1,
          firstEventAt: '2026-05-12T11:00:00Z',
          lastEventAt: '2026-05-12T11:00:00Z',
          unread: true,
          // triggerKind intentionally omitted
        },
      ],
      unreadCount: 1,
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByRole('button', { name: 'Open Legacy Trigger' }));

    expect(pushMock).toHaveBeenCalledWith('/app/settings/public-access?tab=webhook');
  });

  it('clearPageButtonIsGone (Inbox footer no longer carries the bulk-clear affordance)', () => {
    // The "Clear page" red button was removed because the per-row trash icon
    // already covers the "I'm done with this one" intent without a one-click
    // wipe-everything escape hatch that users found too easy to hit by mistake.
    inboxMock.current = {
      ...inboxMock.current,
      items: [
        {
          subjectId: 'wf-1',
          subjectName: 'WF',
          subjectType: 'WORKFLOW' as const,
          runIdPublic: 'run_1',
          category: 'RUN_FAILED' as const,
          severity: 'error' as const,
          count: 1,
          firstEventAt: '2026-05-08T08:00:00Z',
          lastEventAt: '2026-05-08T09:00:00Z',
          unread: true,
        },
      ],
      unreadCount: 1,
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));

    // The button rendered `t('clearPage')` which the test-mock translation
    // function returns verbatim, so the literal "clearPage" text was visible.
    // Asserting its absence is the cleanest regression guard.
    expect(screen.queryByText('clearPage')).toBeNull();
    expect(screen.queryByTitle('clearPage')).toBeNull();
    // The per-row trash icon (the surviving affordance) MUST still be there,
    // otherwise users have no way to dismiss a single row without "Mark all read".
    expect(screen.getByRole('button', { name: 'deleteRow' })).toBeTruthy();
  });

  it('agentTaskRowRoutesToBoardTasksTab', () => {
    inboxMock.current = {
      ...inboxMock.current,
      items: [
        {
          subjectId: 'task-uuid',
          subjectName: 'Review the report',
          subjectType: 'AGENT_TASK' as const,
          runIdPublic: null,
          category: 'AGENT_TASK_ASSIGNED' as const,
          severity: 'info' as const,
          count: 1,
          firstEventAt: '2026-05-12T11:00:00Z',
          lastEventAt: '2026-05-12T11:00:00Z',
          unread: true,
        },
      ],
      unreadCount: 1,
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByRole('button', { name: 'Open Review the report' }));

    expect(pushMock).toHaveBeenCalledWith('/app/board?resource=task');
  });

  it('applicationRowRoutesToApplicationShell', () => {
    inboxMock.current = {
      ...inboxMock.current,
      items: [
        {
          subjectId: 'pub-uuid',
          subjectName: 'My App',
          subjectType: 'APPLICATION' as const,
          runIdPublic: null,
          category: 'APP_EVENT' as const,
          severity: 'info' as const,
          count: 1,
          firstEventAt: '2026-05-12T11:00:00Z',
          lastEventAt: '2026-05-12T11:00:00Z',
          unread: true,
        },
      ],
      unreadCount: 1,
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByRole('button', { name: 'Open My App' }));

    expect(pushMock).toHaveBeenCalledWith('/app/applications/pub-uuid');
  });

  it('organizationInvitationRowRoutesToInvitationsInbox', () => {
    inboxMock.current = {
      ...inboxMock.current,
      items: [
        {
          subjectId: 'invitation-uuid',
          subjectName: 'Acme Community',
          subjectType: 'ORG_INVITATION' as const,
          runIdPublic: null,
          category: 'ORG_INVITATION_PENDING' as const,
          severity: 'info' as const,
          count: 1,
          firstEventAt: '2026-05-16T11:00:00Z',
          lastEventAt: '2026-05-16T11:00:00Z',
          unread: true,
        },
      ],
      unreadCount: 1,
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByRole('button', { name: 'Open Acme Community' }));

    expect(pushMock).toHaveBeenCalledWith('/app/invitations');
  });

  // ============================================================================
  // R10 - Triggers tab (was Activity) with 8-kind filter strip + per-kind rows
  // ============================================================================

  it('Triggers tab renders one row per declared kind (manual + chat) from the same workflow', () => {
    // Backend emits one DTO per (workflow, kind) for the 6 new kinds. A
    // workflow with both manual and chat triggers shows TWO rows in the bell.
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-multi',
          name: 'Multi-kind workflow',
          triggerType: 'MANUAL' as const,
          lastRunAt: '2026-05-15T10:00:00Z',
        },
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-multi',
          name: 'Multi-kind workflow',
          triggerType: 'CHAT' as const,
          lastRunAt: '2026-05-15T10:00:00Z',
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));
    // The tab opens pre-filtered on SCHEDULE; clear it to assert on the
    // unfiltered list this test is about.
    fireEvent.click(screen.getByRole('button', { name: 'kindLabel.schedule', pressed: true }));

    // Both rows are present; one per kind. The visible labels are the
    // subtitle keys from chat.home.live.kindLabel.* (manual/chat).
    expect(screen.getAllByText('Multi-kind workflow')).toHaveLength(2);
    expect(screen.getByText('kindLabel.manual')).toBeTruthy();
    expect(screen.getByText('kindLabel.chat')).toBeTruthy();
  });

  it('Filter chip strip has 8 chips, SCHEDULE pressed by default and the 7 others unpressed', () => {
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));

    // R10 chip-strip - 8 toggle buttons in a role=group container, single-select,
    // click-to-deselect. SCHEDULE is the default selection (first chip in
    // TRIGGER_KIND_ORDER), every other chip starts unpressed.
    const group = screen.getByRole('group', { name: 'filterByKind' });
    const chips = Array.from(group.querySelectorAll('button'));
    expect(chips).toHaveLength(8);
    expect(chips[0].getAttribute('aria-label')).toBe('kindLabel.schedule');
    expect(chips[0].getAttribute('aria-pressed')).toBe('true');
    chips.slice(1).forEach((chip) => expect(chip.getAttribute('aria-pressed')).toBe('false'));
  });

  it('Triggers tab opens filtered on SCHEDULE, and re-opening the bell restores that default', () => {
    // Regression: the Triggers tab must always land on schedules (the rows a
    // user opens the bell to check), whatever chip a previous visit left on.
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-sched',
          name: 'Scheduled job',
          triggerType: 'SCHEDULE' as const,
          schedule: { cronExpression: '0 9 * * *', timezone: 'UTC', executionCount: 1 },
        },
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-manual',
          name: 'Manual job',
          triggerType: 'MANUAL' as const,
          lastRunAt: '2026-05-15T10:00:00Z',
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));

    // Default: only the SCHEDULE row shows.
    expect(screen.getByText('Scheduled job')).toBeTruthy();
    expect(screen.queryByText('Manual job')).toBeNull();

    // Switch to MANUAL, then close and re-open the bell.
    fireEvent.click(screen.getByRole('button', { name: 'kindLabel.manual', pressed: false }));
    expect(screen.getByText('Manual job')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));

    // Back on SCHEDULE - the previous selection did not survive the re-open.
    expect(screen.getByRole('button', { name: 'kindLabel.schedule', pressed: true })).toBeTruthy();
    expect(screen.getByText('Scheduled job')).toBeTruthy();
    expect(screen.queryByText('Manual job')).toBeNull();
  });

  it('Clicking a chip filters rows to that kind; clicking the active chip again clears the filter', () => {
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-sched',
          name: 'Scheduled job',
          triggerType: 'SCHEDULE' as const,
          schedule: { cronExpression: '0 9 * * *', timezone: 'UTC', executionCount: 1 },
        },
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-manual',
          name: 'Manual job',
          triggerType: 'MANUAL' as const,
          lastRunAt: '2026-05-15T10:00:00Z',
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));
    // Clear the default SCHEDULE chip so the strip starts from "no filter".
    fireEvent.click(screen.getByRole('button', { name: 'kindLabel.schedule', pressed: true }));

    // Both rows visible before filtering.
    expect(screen.getByText('Scheduled job')).toBeTruthy();
    expect(screen.getByText('Manual job')).toBeTruthy();

    // Click the MANUAL chip - chip aria-label uses kindLabel.manual i18n key.
    const manualChip = screen.getByRole('button', { name: 'kindLabel.manual', pressed: false });
    fireEvent.click(manualChip);

    // Only the manual row remains; chip is now pressed.
    expect(screen.queryByText('Scheduled job')).toBeNull();
    expect(screen.getByText('Manual job')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'kindLabel.manual', pressed: true })).toBeTruthy();

    // Click the same chip again - filter clears, both rows return.
    fireEvent.click(screen.getByRole('button', { name: 'kindLabel.manual', pressed: true }));
    expect(screen.getByText('Scheduled job')).toBeTruthy();
    expect(screen.getByText('Manual job')).toBeTruthy();
  });

  it('Filter yields zero rows → empty-state copy via kindLabel-style key', () => {
    // Only a SCHEDULE row exists. Clicking the FORM chip filters everything out
    // and the empty-state copy clarifies the bell isn't broken.
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-sched',
          name: 'Scheduled job',
          triggerType: 'SCHEDULE' as const,
          schedule: { cronExpression: '0 9 * * *', timezone: 'UTC', executionCount: 1 },
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));

    fireEvent.click(screen.getByRole('button', { name: 'kindLabel.form', pressed: false }));
    expect(screen.getByText('emptyForKind')).toBeTruthy();
  });

  it('Declared-kind row with null lastRunAt says so on the lastRan line (never-fired sentinel)', () => {
    // The sentinel used to be the row's whole right-hand label. It now lives inside
    // the "Last:" line - a pinned-but-never-executed workflow still has to say that,
    // rather than render an empty right column.
    homeStatusMock.current = {
      ...homeStatusMock.current,
      automations: [
        {
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-fresh',
          name: 'Fresh pinned',
          triggerType: 'MANUAL' as const,
          // lastRunAt deliberately omitted - workflow pinned but never executed.
        },
      ],
    };
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));
    // Clear the default SCHEDULE chip - the row under test is a MANUAL one.
    fireEvent.click(screen.getByRole('button', { name: 'kindLabel.schedule', pressed: true }));

    // The i18n mock echoes keys, so the line reads "lastRan" + "neverRan".
    expect(screen.getByText(/neverRan/)).toBeTruthy();
    // ...and no verdict icon: there is no run to have a verdict about.
    expect(document.body.querySelectorAll('svg.text-emerald-500')).toHaveLength(0);
  });

  describe('APPROVAL_PENDING inbox actions', () => {
    const approvalItem = {
      subjectId: 'wf-2',
      subjectName: 'Refund flow',
      subjectType: 'WORKFLOW' as const,
      runIdPublic: 'run_appr_1',
      category: 'APPROVAL_PENDING' as const,
      severity: 'info' as const,
      count: 3,
      firstEventAt: '2026-05-08T08:00:00Z',
      lastEventAt: '2026-05-08T09:00:00Z',
      unread: true,
    };

    it('approval rows show the Open + Review actions; other categories do not', () => {
      inboxMock.current = {
        ...inboxMock.current,
        items: [approvalItem, ...inboxMock.current.items],
      };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      // Exactly one approval row -> exactly one pair of action buttons
      // (the RUN_FAILED baseline row must not grow actions).
      expect(screen.getAllByTestId('inbox-approval-open')).toHaveLength(1);
      expect(screen.getAllByTestId('inbox-approval-review')).toHaveLength(1);
    });

    it('Open navigates to the workflow run, same target as the row click', () => {
      inboxMock.current = { ...inboxMock.current, items: [approvalItem] };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByTestId('inbox-approval-open'));
      expect(pushMock).toHaveBeenCalledWith('/app/workflow/wf-2/run/run_appr_1');
    });

    it('Review opens the run approvals modal in place - no navigation, popover closed', () => {
      inboxMock.current = { ...inboxMock.current, items: [approvalItem] };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByTestId('inbox-approval-review'));
      expect(pushMock).not.toHaveBeenCalled();
      expect(screen.getByTestId('run-approvals-dialog').getAttribute('data-run-id')).toBe('run_appr_1');
      // The popover collapsed so the modal is not fighting it for focus.
      expect(screen.queryByText('inboxTab')).toBeNull();
    });

    it('renders NO actions when the approval notification lacks a runIdPublic', () => {
      inboxMock.current = {
        ...inboxMock.current,
        items: [{ ...approvalItem, runIdPublic: undefined }],
      };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      expect(screen.queryByTestId('inbox-approval-open')).toBeNull();
      expect(screen.queryByTestId('inbox-approval-review')).toBeNull();
    });
  });

  /**
   * A disabled resource keeps its armed schedule row: the cron row stays enabled in
   * trigger-service and the daemon still claims each slot, it just refuses at dispatch
   * ("Agent X is inactive, skipping schedule"). So `nextFireAt` keeps advancing on a
   * resource that will never run, and the bell - the one surface whose whole job is to
   * say what is ABOUT to happen - counted down to it and pulsed blue for it.
   *
   * Prod, 2026-09-17: an agent was disabled at 18:34:47 and the engine correctly skipped
   * 18:35 and 18:36, while the bell kept advertising it as armed.
   */
  describe('a schedule held back by a spending cap', () => {
    // A cap and a pause both keep nextFireAt accurate while the run is refused, so the
    // bell has to suppress the countdown for both. They must NOT share a badge: a pause is
    // undone by re-enabling the resource, a cap by raising it or waiting for the period to
    // roll, and one word for both sends the reader to the wrong switch.
    const inOneHour = () => new Date(Date.now() + 60 * 60_000).toISOString();
    const inOneMinute = () => new Date(Date.now() + 60_000).toISOString();

    const cappedAgentSchedule = (nextFireAt: string): AutomationMock => ({
      resourceType: 'AGENT',
      resourceId: 'agent-capped',
      name: 'Reporter',
      triggerType: 'SCHEDULE',
      schedule: {
        cronExpression: '* * * * *',
        timezone: 'UTC',
        executionCount: 4,
        nextFireAt,
        scheduleId: 'sched-capped',
        armed: true,
        budgetBlocked: true,
      },
    });

    const openTriggers = () => {
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByText('triggersTab'));
    };

    it('states the cap instead of counting down to a fire that will be refused', () => {
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [cappedAgentSchedule(inOneHour())],
      };
      openTriggers();

      expect(screen.getByText('budgetBlockedBadge')).toBeTruthy();
      // And NOT the pause word, which would point at a switch that is already on.
      expect(screen.queryByText('pausedBadge')).toBeNull();
    });

    it('does not pulse the closed bell for a fire that will not happen', () => {
      inboxMock.current = { ...inboxMock.current, items: [], unreadCount: 0 };
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [cappedAgentSchedule(inOneMinute())],
      };
      render(<NotificationBell />);

      expect(document.body.querySelector('span.bg-blue-500.animate-ping')).toBeNull();
    });

    it('counts down normally when the cap lifts BEFORE the next fire', () => {
      // budgetBlocked is true NOW; this row draws the NEXT fire. A monthly cap reached on
      // 30 September lifts at midnight and the 1 October fire runs, so labelling that row
      // capped, dimming it and hiding the countdown would be wrong about a run that is
      // going to happen. Reading only the boolean is what produces that.
      const liftsAt = new Date(Date.now() + 30 * 60_000).toISOString();
      const fireAfterThat = new Date(Date.now() + 90 * 60_000).toISOString();
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [{
          ...cappedAgentSchedule(fireAfterThat),
          schedule: {
            ...cappedAgentSchedule(fireAfterThat).schedule!,
            budgetBlockedUntil: liftsAt,
          },
        }],
      };
      openTriggers();

      expect(screen.queryByText('budgetBlockedBadge')).toBeNull();
    });

    it('still says capped when the next fire is INSIDE the blocked window', () => {
      // The other side of the same comparison: the cap lifts after this fire, so the fire
      // will be refused and the row must say so.
      const liftsAt = new Date(Date.now() + 90 * 60_000).toISOString();
      const fireBeforeThat = new Date(Date.now() + 30 * 60_000).toISOString();
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [{
          ...cappedAgentSchedule(fireBeforeThat),
          schedule: {
            ...cappedAgentSchedule(fireBeforeThat).schedule!,
            budgetBlockedUntil: liftsAt,
          },
        }],
      };
      openTriggers();

      expect(screen.getByText('budgetBlockedBadge')).toBeTruthy();
    });

    it('says capped with NO lift date, which is how a cumulative cap reads', () => {
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [cappedAgentSchedule(inOneHour())],
      };
      openTriggers();

      expect(screen.getByText('budgetBlockedBadge')).toBeTruthy();
    });
    it('a PAUSED resource says paused, not capped, even when both hold', () => {
      // The two are undone by different actions: a pause by re-enabling, a cap by raising
      // it or waiting. One word for both sends the reader to the wrong switch, so the
      // order of the two branches is load-bearing and pinned here.
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [{ ...cappedAgentSchedule(inOneHour()), resourcePaused: true }],
      };
      openTriggers();

      expect(screen.getByText('pausedBadge')).toBeTruthy();
      expect(screen.queryByText('budgetBlockedBadge')).toBeNull();
    });

    it('an unreadable lift date still suppresses the countdown', () => {
      // The server said blocked. A date this row cannot parse is not a reason to promise
      // a run: the fallback has to be the safe direction, not the optimistic one.
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [{
          ...cappedAgentSchedule(inOneHour()),
          schedule: { ...cappedAgentSchedule(inOneHour()).schedule!, budgetBlockedUntil: 'soon' },
        }],
      };
      openTriggers();

      expect(screen.getByText('budgetBlockedBadge')).toBeTruthy();
    });

    it('a row with a lift date but no next fire still suppresses', () => {
      // Nothing to compare the lift date against, so the same safe direction applies.
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [{
          ...cappedAgentSchedule(inOneHour()),
          schedule: {
            ...cappedAgentSchedule(inOneHour()).schedule!,
            nextFireAt: undefined,
            budgetBlockedUntil: new Date(Date.now() + 60_000).toISOString(),
          },
        }],
      };
      openTriggers();

      expect(screen.getByText('budgetBlockedBadge')).toBeTruthy();
    });

    it('still counts down when no cap is holding the schedule', () => {
      // The regression a suppression rule causes: every ordinary row goes quiet.
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [{
          ...cappedAgentSchedule(inOneHour()),
          schedule: { ...cappedAgentSchedule(inOneHour()).schedule!, budgetBlocked: false },
        }],
      };
      openTriggers();

      expect(screen.queryByText('budgetBlockedBadge')).toBeNull();
    });
  });
  describe('disabled resource (resourcePaused)', () => {
    /** Far enough out that an ACTIVE row would render a countdown rather than the ping. */
    const inOneHour = () => new Date(Date.now() + 60 * 60_000).toISOString();
    /** Inside the imminent window, which is what draws the blue ping on bell and row. */
    const inOneMinute = () => new Date(Date.now() + 60_000).toISOString();

    const pausedAgentSchedule = (nextFireAt: string): AutomationMock => ({
      resourceType: 'AGENT',
      resourceId: 'agent-off',
      name: 'Novass',
      triggerType: 'SCHEDULE',
      resourcePaused: true,
      schedule: {
        cronExpression: '* * * * *',
        timezone: 'UTC',
        executionCount: 4,
        nextFireAt,
        scheduleId: 'sched-off',
      },
    });

    it('states "disabled" instead of counting down to a fire that will not happen', () => {
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [pausedAgentSchedule(inOneHour())],
      };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByText('triggersTab'));

      expect(screen.getByText('pausedBadge')).toBeTruthy();
      // The countdown the row used to print: t('inHours', { n: 1 }) through this
      // suite's translator mock. Its presence WAS the bug.
      expect(screen.queryByText('1 item(s)')).toBeNull();
    });

    it('does NOT pulse the closed bell when its next slot is imminent', () => {
      // The reported symptom, and the only one visible without opening the popover:
      // a blue ping on the bell icon announcing a run that the engine will refuse.
      inboxMock.current = { ...inboxMock.current, items: [], unreadCount: 0 };
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [pausedAgentSchedule(inOneMinute())],
      };
      render(<NotificationBell />);

      expect(document.body.querySelector('span.bg-blue-500.animate-ping')).toBeNull();
    });

    it('draws neither the blue row highlight nor the row ping when imminent', () => {
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [pausedAgentSchedule(inOneMinute())],
      };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByText('triggersTab'));

      // No lastRunStatus on this row, so EpochStatusIcon contributes no pulse of its
      // own and every animate-ping left in the tree would be the imminent one.
      expect(document.body.querySelectorAll('span.animate-ping')).toHaveLength(0);
      expect(document.body.querySelector('div.bg-blue-50\\/60')).toBeNull();
    });

    it('states "disabled" on a WEBHOOK row instead of "live"', () => {
      // Not cosmetic symmetry: AgentWebhookDispatchService answers "Agent is inactive"
      // on a disabled agent, so "live" on that endpoint is false.
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [{
          resourceType: 'AGENT' as const,
          resourceId: 'agent-off',
          name: 'Novass',
          triggerType: 'WEBHOOK' as const,
          resourcePaused: true,
          webhook: { httpMethod: 'POST' },
        }],
      };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByText('triggersTab'));
      fireEvent.click(screen.getByRole('button', { name: 'kindLabel.webhook' }));

      expect(screen.getByText('pausedBadge')).toBeTruthy();
      expect(screen.queryByText('liveBadge')).toBeNull();
    });

    it('leaves an ACTIVE row untouched - countdown and imminent pulse both survive', () => {
      // The other half of the fix: `resourcePaused` is absent on the vast majority of
      // rows, and this pins that the new branch is inert for them.
      inboxMock.current = { ...inboxMock.current, items: [], unreadCount: 0 };
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [{ ...pausedAgentSchedule(inOneMinute()), resourcePaused: false }],
      };
      render(<NotificationBell />);

      expect(document.body.querySelector('span.bg-blue-500.animate-ping')).toBeTruthy();

      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByText('triggersTab'));
      // t('inMinutes', { n: 1 }) through this suite's translator mock: the countdown
      // this row is entitled to, and the one the paused row above must not print.
      expect(screen.getByText('1 item(s)')).toBeTruthy();
      expect(screen.queryByText('pausedBadge')).toBeNull();
    });

    it('dims the row CONTENT and not the row, so the action menu keeps full contrast', () => {
      // Opacity on the row would be a ceiling its children cannot exceed, and it would
      // take the row menu down with it - the menu being the only way back
      // ("Reactivate agent"). Pinning WHERE the dim lands, not just that it exists.
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [pausedAgentSchedule(inOneHour())],
      };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByText('triggersTab'));

      const row = screen.getByText('Novass').closest('div.group')!;
      expect(row.className).not.toContain('opacity-50');
      // Exactly the three content spans - avatar, name column, label column. A floor of
      // "at least one" would stay green if a refactor dropped two of them.
      expect(row.querySelectorAll('.opacity-50')).toHaveLength(3);
      // The menu is a sibling of the dimmed spans, never inside one.
      const menu = row.querySelector('button[aria-label="label"]');
      expect(menu).toBeTruthy();
      expect(menu!.closest('.opacity-50')).toBeNull();
    });

    it('pairs every dim with pointer-events-none, or the row stops being clickable', () => {
      // Not a style preference. An opacity below 1 gives the span its own stacking context,
      // so it paints - and hit-tests - above the row's `absolute inset-0 z-0` click target.
      // Without the pairing, clicking the agent name on a disabled row lands on nothing,
      // on the one row whose whole purpose is "go deal with this". jsdom loads no stylesheet
      // and resolves no paint order, so no render-and-click test in this file could catch
      // it; the class pairing is the invariant that can be pinned here.
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [pausedAgentSchedule(inOneHour())],
      };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByText('triggersTab'));

      const row = screen.getByText('Novass').closest('div.group')!;
      const dimmed = Array.from(row.querySelectorAll('.opacity-50'));
      expect(dimmed).not.toHaveLength(0);
      for (const el of dimmed) {
        expect(el.className).toContain('pointer-events-none');
      }
    });

    it('does not compound its dim with the "Last:" line\'s own opacity', () => {
      // Opacity multiplies through the tree. The last-run line carries opacity-70 of its
      // own, so leaving both on would render the popover's smallest text at 0.55 x 0.70 =
      // 0.385 - around 1.7:1 on the light ground, unreadable rather than inactive. The row
      // is dimmed once, by the level that owns the decision.
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [pausedAgentSchedule(inOneHour())],
      };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByText('triggersTab'));

      const lastRun = screen.getByText(/lastRan/).closest('span')!;
      expect(lastRun.className).not.toContain('opacity-70');
      // Replaced, not stacked: the line still steps back, once.
      expect(lastRun.className).toContain('opacity-50');
    });

    it('leaves the badge itself at full strength - it is the reason for the dim', () => {
      // The row is dimmed to say "this will not run". The badge is the only NEW word on
      // that row and the explanation of the dim, so dimming it too would drop the one
      // thing worth reading to roughly 2:1 against the popover ground, LESS legible than
      // the countdown it replaced. The dim covers what the row was already saying.
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [pausedAgentSchedule(inOneHour())],
      };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByText('triggersTab'));

      expect(screen.getByText('pausedBadge').closest('.opacity-50')).toBeNull();
    });

    it('dims a paused row that HAS already run, without disturbing its verdict icon', () => {
      // The commonest real disabled row: someone switches off an automation that has been
      // running for weeks. Every other fixture here has never fired, so this is the only
      // one that renders the last-run verdict and its screen-reader twin under the dim.
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [{
          ...pausedAgentSchedule(inOneHour()),
          lastRunAt: new Date(Date.now() - 2 * 60_000).toISOString(),
          lastRunStatus: 'COMPLETED',
        }],
      };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByText('triggersTab'));

      expect(screen.getByText('pausedBadge')).toBeTruthy();
      // The verdict still reaches a screen reader: the dim is visual, and a row being
      // disabled says nothing about how its last run ended.
      expect(document.body.querySelector('[data-last-run-status="COMPLETED"]')?.textContent)
        .toBe('status.completed');
      // Still the same three dimmed spans, and the row is still not the dimmed one.
      const row = screen.getByText('Novass').closest('div.group')!;
      expect(row.className).not.toContain('opacity-50');
      expect(row.querySelectorAll('.opacity-50')).toHaveLength(3);
    });

    it('a paused row does not silence the bell for a DIFFERENT row that really is imminent', () => {
      // `hasImminentFire` is a `.some()` over every automation, so one disabled row must
      // not decide for the list. Structurally safe, worth pinning: this is the failure
      // that would turn a bell fix into a bell that never rings.
      inboxMock.current = { ...inboxMock.current, items: [], unreadCount: 0 };
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [
          pausedAgentSchedule(inOneMinute()),
          { ...pausedAgentSchedule(inOneMinute()), resourceId: 'agent-on', name: 'Live one', resourcePaused: false },
        ],
      };
      render(<NotificationBell />);

      expect(document.body.querySelector('span.bg-blue-500.animate-ping')).toBeTruthy();
    });

    it('keeps the "Last:" line at opacity-70 on an ACTIVE row', () => {
      // The other side of the branch above: the muted treatment this line has always had
      // must survive for every row that is not disabled.
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [{ ...pausedAgentSchedule(inOneHour()), resourcePaused: false }],
      };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByText('triggersTab'));

      expect(screen.getByText(/lastRan/).closest('span')!.className).toContain('opacity-70');
    });

    it('leaves an ACTIVE row entirely undimmed', () => {
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [{ ...pausedAgentSchedule(inOneHour()), resourcePaused: false }],
      };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByText('triggersTab'));

      const row = screen.getByText('Novass').closest('div.group')!;
      expect(row.querySelectorAll('.opacity-50')).toHaveLength(0);
    });

    it('states "disabled" on a declared-kind row, which used to carry no label at all', () => {
      // The 6 declared kinds (MANUAL, CHAT, FORM, DATASOURCE, WORKFLOW, ERROR) print an
      // empty forward-looking label, so this is the largest per-row change in the fix: from
      // nothing to a word. The claim is true here - ChatDispatchService and its siblings all
      // refuse on a CANCELLED production run.
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [{
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-off',
          name: 'Cancelled flow',
          triggerType: 'MANUAL' as const,
          resourcePaused: true,
          // The backend derives resourcePaused FROM a resolved production run, so a row
          // carrying the flag always carries the run id too. Fixture kept that shape.
          productionRunIdPublic: 'run_cancelled',
        }],
      };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByText('triggersTab'));
      fireEvent.click(screen.getByRole('button', { name: 'kindLabel.manual' }));

      expect(screen.getByText('pausedBadge')).toBeTruthy();
    });

    it('does NOT claim "disabled" on an ERROR row - that lane ignores the production run', () => {
      // ErrorTriggerDispatchService resolves the newest NON-TERMINAL run and says in its own
      // comment that it "does NOT consult production_run_id", so a workflow whose production
      // run is CANCELLED still dispatches its error handler. Printing "disabled" there would
      // be the same green-when-wrong defect this whole change exists to remove, pointing the
      // other way: the bell asserting a refusal the engine does not make.
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [{
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-err',
          name: 'Error handler',
          triggerType: 'ERROR' as const,
          resourcePaused: true,
          productionRunIdPublic: 'run_cancelled',
        }],
      };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByText('triggersTab'));
      fireEvent.click(screen.getByRole('button', { name: 'kindLabel.error' }));

      expect(screen.queryByText('pausedBadge')).toBeNull();
      const row = screen.getByText('Error handler').closest('div.group')!;
      expect(row.querySelectorAll('.opacity-50')).toHaveLength(0);
    });

    it('dims a paused WORKFLOW row too - the flag means a CANCELLED production run there', () => {
      // Every other test in this block uses an AGENT, where `resourcePaused` is
      // `isActive=false`. On a workflow it is computed from an entirely different fact, and
      // it is also the type whose row menu is conditional on having a production run id.
      homeStatusMock.current = {
        ...homeStatusMock.current,
        automations: [{
          resourceType: 'WORKFLOW' as const,
          resourceId: 'wf-off',
          name: 'Cancelled flow',
          triggerType: 'SCHEDULE' as const,
          resourcePaused: true,
          productionRunIdPublic: 'run_cancelled',
          // No scheduleId on purpose: hasTriggerRowActions is satisfied here by the
          // production run id alone, which is the branch this row exists to exercise.
          schedule: {
            cronExpression: '0 8 * * *',
            timezone: 'UTC',
            executionCount: 3,
            nextFireAt: inOneHour(),
          },
        }],
      };
      render(<NotificationBell />);
      fireEvent.click(screen.getByRole('button', { name: 'title' }));
      fireEvent.click(screen.getByText('triggersTab'));

      expect(screen.getByText('pausedBadge')).toBeTruthy();
      expect(screen.queryByText('1 item(s)')).toBeNull();
      const row = screen.getByText('Cancelled flow').closest('div.group')!;
      expect(row.querySelectorAll('.opacity-50')).toHaveLength(3);
      // The menu still reaches full contrast on this shape too, where it is gated on the
      // production run id rather than on a schedule id.
      expect(row.querySelector('button[aria-label="label"]')!.closest('.opacity-50')).toBeNull();
    });
  });
});
