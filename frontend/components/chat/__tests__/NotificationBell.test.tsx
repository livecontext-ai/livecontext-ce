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
import { NotificationBell } from '../NotificationBell';
import type { NotificationItem } from '@/lib/api/orchestrator/home-status.service';

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
vi.mock('@/components/ThemeProvider', () => ({
  useTheme: () => ({ theme: 'light', toggleTheme: () => {}, setTheme: () => {} }),
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
  };
  lastRunAt?: string;
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
vi.mock('@/hooks/useHomeStatus', () => ({
  useHomeStatus: () => homeStatusMock.current,
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

  it('Triggers row click navigates to the resource', () => {
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));
    fireEvent.click(screen.getByText('Daily Digest'));

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
    fireEvent.click(screen.getByText('Daily Digest'));

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
    // Asserting the prefix appears in the DOM proves the new subtitle line
    // rendered (matches the SCHEDULE-only conditional in NotificationBell.tsx).
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

  it('Non-SCHEDULE rows (WEBHOOK) do NOT render the lastRan subtitle (Part 1)', () => {
    // Part 1 explicitly scopes the dual-label to SCHEDULE rows only. Other
    // trigger kinds keep their existing single-line right label (kind label
    // or relative-past via lastRunAt) - adding a 2nd line everywhere would
    // bloat the popover vertical density.
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

    expect(screen.queryByText(/lastRan/)).toBeNull();
    expect(screen.queryByText(/neverRan/)).toBeNull();
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

    // Both rows are present; one per kind. The visible labels are the
    // subtitle keys from chat.home.live.kindLabel.* (manual/chat).
    expect(screen.getAllByText('Multi-kind workflow')).toHaveLength(2);
    expect(screen.getByText('kindLabel.manual')).toBeTruthy();
    expect(screen.getByText('kindLabel.chat')).toBeTruthy();
  });

  it('Filter chip strip has 8 chips in canonical order, each with aria-pressed=false initially', () => {
    render(<NotificationBell />);
    fireEvent.click(screen.getByRole('button', { name: 'title' }));
    fireEvent.click(screen.getByText('triggersTab'));

    // R10 chip-strip - 8 toggle buttons in a role=group container, single-select,
    // click-to-deselect. All start unpressed.
    const group = screen.getByRole('group', { name: 'filterByKind' });
    const chips = group.querySelectorAll('button');
    expect(chips).toHaveLength(8);
    chips.forEach((chip) => expect(chip.getAttribute('aria-pressed')).toBe('false'));
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

  it('Declared-kind row with null lastRunAt renders "-" (never-fired sentinel)', () => {
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

    expect(screen.getByText('-')).toBeTruthy();
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
});
