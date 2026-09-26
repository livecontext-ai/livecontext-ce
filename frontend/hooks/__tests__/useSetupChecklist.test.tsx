// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { queryClient as appQueryClient } from '@/lib/query-client';

let auth: { isAuthenticated: boolean; isLoading: boolean; user?: { sub: string }; numericUserId?: number | null } | undefined;
vi.mock('@/lib/providers/smart-providers', () => ({ useOptionalAuth: () => auth }));

let edition = { IS_CE: false };
vi.mock('@/lib/edition', () => ({ get IS_CE() { return edition.IS_CE; } }));

let currentOrg = 'org-1';
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrg: () => ({ currentOrgId: currentOrg }),
  useCurrentOrgStore: (select: (s: { currentOrgId: string }) => unknown) => select({ currentOrgId: currentOrg }),
}));

const onboardingStatus = vi.fn();
vi.mock('@/lib/api/api-client', () => ({ apiClient: { get: (...a: unknown[]) => onboardingStatus(...a) } }));

const getConversations = vi.fn();
vi.mock('@/lib/api/conversationApi', () => ({ conversationApi: { getConversations: (...a: unknown[]) => getConversations(...a) } }));

const getCredentials = vi.fn();
const getAgentsPage = vi.fn();
const getWorkflowsPage = vi.fn();
const listChannels = vi.fn();
vi.mock('@/lib/api/orchestrator', () => ({
  credentialService: { getCredentials: (...a: unknown[]) => getCredentials(...a) },
  agentService: { getAgentsPage: (...a: unknown[]) => getAgentsPage(...a) },
  workflowService: { getWorkflowsPage: (...a: unknown[]) => getWorkflowsPage(...a) },
  chatChannelService: { list: (...a: unknown[]) => listChannels(...a) },
}));

const track = vi.fn();
vi.mock('@/lib/analytics/analytics', () => ({ track: (...a: unknown[]) => track(...a) }));

import { refreshSetupChecklist, useSetupChecklist } from '../useSetupChecklist';

/**
 * The APP's query defaults, not a test-friendly client: refetch on mount and on focus are OFF and
 * failures retry there. A checklist that only works with a permissive test client is the bug the
 * first version shipped (nothing ticked until a full reload).
 */
let client: QueryClient;
function wrapper({ children }: { children: React.ReactNode }) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

const cred = (id: number, integration: string) => ({ id, integration });

beforeEach(() => {
  client = new QueryClient({ defaultOptions: appQueryClient.getDefaultOptions() });
  // As on the cloud: the Keycloak subject is NOT the id conversations are stored under.
  auth = { isAuthenticated: true, isLoading: false, user: { sub: 'kc-5f1e' }, numericUserId: 42 };
  currentOrg = 'org-1';
  edition = { IS_CE: false };
  window.localStorage.clear();
  onboardingStatus.mockResolvedValue({ needsOnboarding: false, completed: true, skipped: false, emailVerified: true });
  getCredentials.mockResolvedValue({ credentials: [cred(1, 'gmail')], totalItems: 1 });
  getConversations.mockResolvedValue({ content: [] });
  getAgentsPage.mockResolvedValue({ items: [], totalCount: 0 });
  getWorkflowsPage.mockResolvedValue({ items: [], totalCount: 2 });
  listChannels.mockResolvedValue({ channels: [{ active: false, verifiedAt: null, credentialId: 9 }] });
});
afterEach(() => {
  client.clear();
  vi.clearAllMocks();
});

const doneOf = (progress: { tasks: Array<{ id: string; done: boolean }> }) =>
  Object.fromEntries(progress.tasks.map((t) => [t.id, t.done]));

describe('useSetupChecklist', () => {
  it('reads each task from its evidence: a workflow counts as built, a channel never delivered to does not', async () => {
    const { result } = renderHook(() => useSetupChecklist(), { wrapper });

    await waitFor(() => expect(result.current.progress.known).toBe(true));
    expect(doneOf(result.current.progress)).toEqual({ integration: true, chat: false, build: true, channel: false });
    expect(result.current.visible).toBe(true);
  });

  it('regression: a task done after the first read ticks on refresh, under the app defaults, with no reload', async () => {
    const { result } = renderHook(() => useSetupChecklist(), { wrapper });
    await waitFor(() => expect(result.current.progress.known).toBe(true));
    expect(doneOf(result.current.progress).channel).toBe(false);

    // The person connects Telegram through the assistant, then opens the checklist.
    listChannels.mockResolvedValue({ channels: [{ active: true, verifiedAt: '2026-09-23T10:00:00Z' }] });
    await act(() => refreshSetupChecklist(client));

    await waitFor(() => expect(doneOf(result.current.progress).channel).toBe(true));
  });

  it('a credential that only backs a chat channel is not "an app": connecting Telegram must not also tick that task', async () => {
    getCredentials.mockResolvedValue({ credentials: [cred(9, 'telegram')], totalItems: 1 });

    const { result } = renderHook(() => useSetupChecklist(), { wrapper });

    await waitFor(() => expect(result.current.progress.known).toBe(true));
    expect(doneOf(result.current.progress).integration).toBe(false);
  });

  it('regression: Slack connected as an APP counts, although Slack is also a chat-channel service', async () => {
    // The first version excluded every credential whose integration was a channel service name,
    // so connecting Slack, the copy's own example, never ticked "connect an app".
    getCredentials.mockResolvedValue({ credentials: [cred(9, 'telegram'), cred(12, 'slack')], totalItems: 2 });

    const { result } = renderHook(() => useSetupChecklist(), { wrapper });

    await waitFor(() => expect(result.current.progress.known).toBe(true));
    expect(doneOf(result.current.progress).integration).toBe(true);
  });

  it('regression: "talk to the assistant" matches the platform user id, not the Keycloak subject', async () => {
    getConversations.mockResolvedValue({ content: [{ id: 'c1', userId: 'kc-5f1e' }] });
    const { result } = renderHook(() => useSetupChecklist(), { wrapper });
    await waitFor(() => expect(result.current.progress.known).toBe(true));
    // A conversation's userId is the numeric X-User-ID. Matching the Keycloak subject never
    // matched on the cloud, so the task, and with it the whole checklist, could never finish.
    expect(doneOf(result.current.progress).chat).toBe(false);

    getConversations.mockResolvedValue({ content: [{ id: 'c1', userId: 42 }] });
    await act(() => refreshSetupChecklist(client));
    await waitFor(() => expect(doneOf(result.current.progress).chat).toBe(true));
  });

  it('"talk to the assistant" is the person\'s own: a teammate\'s conversation does not tick it', async () => {
    getConversations.mockResolvedValue({ content: [{ id: 'c1', userId: '7' }] });
    const { result } = renderHook(() => useSetupChecklist(), { wrapper });
    await waitFor(() => expect(result.current.progress.known).toBe(true));
    expect(doneOf(result.current.progress).chat).toBe(false);

    getConversations.mockResolvedValue({ content: [{ id: 'c1', userId: '7' }, { id: 'c2', userId: '42' }] });
    await act(() => refreshSetupChecklist(client));
    await waitFor(() => expect(doneOf(result.current.progress).chat).toBe(true));
  });

  it('an agent alone counts as built, and nothing at all does not', async () => {
    getWorkflowsPage.mockResolvedValue({ items: [], totalCount: 0 });
    const { result } = renderHook(() => useSetupChecklist(), { wrapper });
    await waitFor(() => expect(result.current.progress.known).toBe(true));
    expect(doneOf(result.current.progress).build).toBe(false);

    getAgentsPage.mockResolvedValue({ items: [{ id: 'a1' }], totalCount: 1 });
    await act(() => refreshSetupChecklist(client));
    await waitFor(() => expect(doneOf(result.current.progress).build).toBe(true));
  });

  it('asks nothing while the cloud onboarding is not finished, so it never competes with the form', async () => {
    onboardingStatus.mockResolvedValue({ needsOnboarding: true, completed: false, skipped: false, emailVerified: true });

    const { result } = renderHook(() => useSetupChecklist(), { wrapper });

    await waitFor(() => expect(onboardingStatus).toHaveBeenCalled());
    expect(getCredentials).not.toHaveBeenCalled();
    expect(result.current.visible).toBe(false);
  });

  it('on CE, where there is no profile onboarding, it does not wait for one', async () => {
    edition = { IS_CE: true };

    const { result } = renderHook(() => useSetupChecklist(), { wrapper });

    await waitFor(() => expect(result.current.progress.known).toBe(true));
    expect(onboardingStatus).not.toHaveBeenCalled();
    expect(result.current.visible).toBe(true);
  });

  it('regression: a failing endpoint reads as not done at once, instead of hiding the pill through retries', async () => {
    listChannels.mockRejectedValue(new Error('503'));

    const { result } = renderHook(() => useSetupChecklist(), { wrapper });

    await waitFor(() => expect(result.current.progress.known).toBe(true));
    expect(doneOf(result.current.progress).channel).toBe(false);
    // Two reads use the channel list (the channel task, and the app task to exclude channel bots):
    // one call each, none retried.
    expect(listChannels).toHaveBeenCalledTimes(2);
    expect(result.current.visible).toBe(true);
  });

  it('once everything is done it is remembered as completed, and no later mount asks anything', async () => {
    getConversations.mockResolvedValue({ content: [{ id: 'c1', userId: '42' }] });
    listChannels.mockResolvedValue({ channels: [{ active: true, verifiedAt: '2026-09-23T10:00:00Z', credentialId: 9 }] });
    const first = renderHook(() => useSetupChecklist(), { wrapper });
    await waitFor(() => expect(window.localStorage.getItem('lc.setupChecklist:org-1')).toBe('completed'));
    expect(first.result.current.visible).toBe(false);
    first.unmount();
    vi.clearAllMocks();

    const later = renderHook(() => useSetupChecklist(), { wrapper });
    await new Promise((r) => setTimeout(r, 20));

    // A finished checklist costs no request on any page, for the rest of the account's life.
    expect(getCredentials).not.toHaveBeenCalled();
    expect(listChannels).not.toHaveBeenCalled();
    expect(later.result.current.visible).toBe(false);
  });

  it('reports setup_checklist_completed exactly once, on an open -> done transition seen in this session, and never on a later mount', async () => {
    getConversations.mockResolvedValue({ content: [{ id: 'c1', userId: '42' }] });
    const first = renderHook(() => useSetupChecklist(), { wrapper, reactStrictMode: true });
    // Read as open first: the channel has not received a message yet.
    await waitFor(() => expect(first.result.current.visible).toBe(true));
    expect(track).not.toHaveBeenCalledWith('setup_checklist_completed', expect.anything());

    // The last task gets done, and the checklist sees it.
    listChannels.mockResolvedValue({ channels: [{ active: true, verifiedAt: '2026-09-23T10:00:00Z', credentialId: 9 }] });
    await act(() => refreshSetupChecklist(client));
    await waitFor(() => expect(window.localStorage.getItem('lc.setupChecklist:org-1')).toBe('completed'));
    first.rerender();

    const completed = track.mock.calls.filter(([name]) => name === 'setup_checklist_completed');
    expect(completed).toEqual([['setup_checklist_completed', { total: 4 }]]);
    first.unmount();
    track.mockClear();

    // An already-complete checklist, mounted again: nothing to report.
    renderHook(() => useSetupChecklist(), { wrapper });
    await new Promise((r) => setTimeout(r, 20));
    expect(track).not.toHaveBeenCalled();
  });

  it('regression: a checklist already complete at its first read (a new device, nothing stored) is remembered but NOT reported', async () => {
    // The stored 'completed' is per browser: on a new device a person who finished long ago reads
    // every task done at once. That is not a completion happening now.
    getConversations.mockResolvedValue({ content: [{ id: 'c1', userId: '42' }] });
    listChannels.mockResolvedValue({ channels: [{ active: true, verifiedAt: '2026-09-23T10:00:00Z', credentialId: 9 }] });

    renderHook(() => useSetupChecklist(), { wrapper, reactStrictMode: true });

    await waitFor(() => expect(window.localStorage.getItem('lc.setupChecklist:org-1')).toBe('completed'));
    expect(track).not.toHaveBeenCalledWith('setup_checklist_completed', expect.anything());
  });

  it('a failed read is not evidence the checklist was open: done after it recovers is not reported', async () => {
    getConversations.mockResolvedValue({ content: [{ id: 'c1', userId: '42' }] });
    listChannels.mockRejectedValue(new Error('outage'));
    const { result } = renderHook(() => useSetupChecklist(), { wrapper });
    // The failed read shows the checklist (it counts as not done) ...
    await waitFor(() => expect(result.current.visible).toBe(true));

    // ... but the channel was in fact delivered to long ago.
    listChannels.mockResolvedValue({ channels: [{ active: true, verifiedAt: '2026-09-23T10:00:00Z', credentialId: 9 }] });
    await act(() => refreshSetupChecklist(client));
    await waitFor(() => expect(window.localStorage.getItem('lc.setupChecklist:org-1')).toBe('completed'));

    expect(track).not.toHaveBeenCalledWith('setup_checklist_completed', expect.anything());
  });

  it('does not report completion while a task is still open', async () => {
    const { result } = renderHook(() => useSetupChecklist(), { wrapper });
    await waitFor(() => expect(result.current.visible).toBe(true));

    expect(track).not.toHaveBeenCalledWith('setup_checklist_completed', expect.anything());
  });

  it('a "dismissed" stored by an earlier build no longer hides it: the checklist comes back', async () => {
    window.localStorage.setItem('lc.setupChecklist:org-1', 'dismissed');

    const { result } = renderHook(() => useSetupChecklist(), { wrapper });

    await waitFor(() => expect(result.current.visible).toBe(true));
    expect(getCredentials).toHaveBeenCalled();
  });

  it('offers no way to hide it', async () => {
    const { result } = renderHook(() => useSetupChecklist(), { wrapper });
    await waitFor(() => expect(result.current.visible).toBe(true));

    expect(Object.keys(result.current).sort()).toEqual(['progress', 'visible']);
  });

  it('without its numeric id the chat task is not asked nor guessed, and reads as not done so the pill still shows', async () => {
    auth = { isAuthenticated: true, isLoading: false, user: { sub: 'kc-5f1e' }, numericUserId: null };

    const { result } = renderHook(() => useSetupChecklist(), { wrapper });

    await waitFor(() => expect(result.current.progress.known).toBe(true));
    expect(getConversations).not.toHaveBeenCalled();
    expect(doneOf(result.current.progress).chat).toBe(false);
    expect(result.current.visible).toBe(true);
  });

  it('a channel-list outage does not also clear "connect an app"', async () => {
    listChannels.mockRejectedValue(new Error('503'));

    const { result } = renderHook(() => useSetupChecklist(), { wrapper });

    await waitFor(() => expect(result.current.progress.known).toBe(true));
    expect(doneOf(result.current.progress).integration).toBe(true);
    expect(doneOf(result.current.progress).channel).toBe(false);
  });

  it('after a workspace switch it asks nothing for a workspace where the checklist is finished', async () => {
    window.localStorage.setItem('lc.setupChecklist:org-2', 'completed');
    const view = renderHook(() => useSetupChecklist(), { wrapper });
    await waitFor(() => expect(view.result.current.visible).toBe(true));
    vi.clearAllMocks();

    currentOrg = 'org-2';
    view.rerender();
    await new Promise((r) => setTimeout(r, 20));

    // Not even one request with the previous workspace's "shown".
    expect(getCredentials).not.toHaveBeenCalled();
    expect(listChannels).not.toHaveBeenCalled();
    expect(view.result.current.visible).toBe(false);
  });

  it('shows nothing to a signed-out visitor', async () => {
    auth = { isAuthenticated: false, isLoading: false };

    const { result } = renderHook(() => useSetupChecklist(), { wrapper });

    await new Promise((r) => setTimeout(r, 20));
    expect(result.current.visible).toBe(false);
    expect(onboardingStatus).not.toHaveBeenCalled();
  });
});
