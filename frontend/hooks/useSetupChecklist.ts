'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery, type QueryClient } from '@tanstack/react-query';
import { apiClient } from '@/lib/api/api-client';
import { conversationApi } from '@/lib/api/conversationApi';
import { agentService, chatChannelService, credentialService, workflowService } from '@/lib/api/orchestrator';
import { useOrgScopedQuery } from '@/lib/hooks/useOrgScopedQuery';
import { useOptionalAuth } from '@/lib/providers/smart-providers';
import { IS_CE } from '@/lib/edition';
import { useCurrentOrg } from '@/lib/stores/current-org-store';
import { readWorkspacePreference, workspacePreferenceKey } from '@/lib/preferences/workspacePreference';
import { needsOnboardingRedirect, type OnboardingStatus } from '@/components/security/onboardingStatus';
import { setupProgress, showSetupChecklist, type SetupProgress } from '@/lib/onboarding/setupChecklist';
import { track } from '@/lib/analytics/analytics';

/** Every evidence query carries this segment, so one predicate refreshes them all. */
export const SETUP_CHECKLIST_KEY = 'setup-checklist';

/**
 * Re-read every task's evidence: when the checklist is opened and when a credential is saved
 * (invalidateCredentialCaches). Harmless anywhere else: a few small requests, only for a
 * checklist that is still mounted and enabled.
 */
export function refreshSetupChecklist(queryClient: QueryClient): Promise<void> {
  return queryClient.refetchQueries({
    predicate: (q) => q.queryKey.includes(SETUP_CHECKLIST_KEY),
    type: 'active',
  });
}

/**
 * How the evidence is kept fresh. The app's defaults turn refetch-on-mount and on-focus OFF, and
 * the header that hosts this never remounts, so without these the counter would stay at whatever
 * it read first until a full reload. On focus matters most: a task is often completed in another
 * tab (an OAuth consent, a bot created in Telegram, a key copied from Discord's portal).
 */
const FRESHNESS = {
  staleTime: 30 * 1000,
  refetchOnMount: true,
  refetchOnWindowFocus: true,
  // A failing endpoint must not hide the pill while it retries: one failure reads as "not done".
  retry: false,
  // Never show the previous workspace's answer after a workspace switch.
  placeholderData: undefined,
} as const;

/**
 * The checklist's state in one workspace, persisted: 'shown' until every task is done, then
 * 'completed', after which nothing is asked again in that workspace (no request on any page).
 *
 * <p>There is no way to hide it before that, by design: four short tasks, each a link to where it
 * is done, are quicker to finish than to argue with. A 'dismissed' stored by an earlier build is
 * not a valid value any more, so it reads as 'shown' and the checklist comes back.
 */
type Visibility = 'shown' | 'completed';
const isVisibility = (value: string | null): value is Visibility =>
  value === 'shown' || value === 'completed';
const PREFERENCE_PREFIX = 'lc.setupChecklist';
/** Tells every mounted copy (the desktop and the mobile header) that the choice changed. */
const VISIBILITY_EVENT = 'lc:setup-checklist-visibility';

interface VisibilityChange {
  org: string | null;
  value: Visibility;
}

function useChecklistVisibility(): [Visibility | null, (next: Visibility) => void] {
  const { currentOrgId } = useCurrentOrg();
  const org = currentOrgId ?? null;
  // Tagged with the workspace it was read for: right after a workspace switch the previous
  // workspace's answer must not be used, even for one render, or the new workspace's evidence
  // would be fetched although the checklist is hidden there. Null until read after mount, so the
  // server render and the first client render agree.
  const [state, setState] = useState<{ org: string | null; value: Visibility } | null>(null);

  useEffect(() => {
    setState({ org, value: readWorkspacePreference(PREFERENCE_PREFIX, org, isVisibility) ?? 'shown' });
    // The value travels in the event itself, so the other copy follows even where storage is
    // blocked (and re-reading it would find nothing).
    const onChange = (event: Event) => {
      const change = (event as CustomEvent<VisibilityChange>).detail;
      if (change && change.org === org) {
        setState({ org, value: change.value });
      }
    };
    window.addEventListener(VISIBILITY_EVENT, onChange);
    return () => window.removeEventListener(VISIBILITY_EVENT, onChange);
  }, [org]);

  const set = useCallback((next: Visibility) => {
    setState({ org, value: next });
    try {
      window.localStorage.setItem(workspacePreferenceKey(PREFERENCE_PREFIX, org), next);
    } catch {
      // Storage unavailable: the choice still holds for this session.
    }
    window.dispatchEvent(new CustomEvent<VisibilityChange>(VISIBILITY_EVENT, { detail: { org, value: next } }));
  }, [org]);

  return [state && state.org === org ? state.value : null, set];
}

export interface SetupChecklistState {
  progress: SetupProgress;
  visible: boolean;
}

/**
 * Gathers the evidence for each setup task and decides whether the checklist shows.
 *
 * <p><b>Whose evidence.</b> "Talk to Orbi" is the person's own: a conversation THEY
 * started. The other three are about the workspace being set up (an app connected, an agent or a
 * workflow, a channel that reaches someone), because that is what the product needs to work for
 * them; someone invited into a workspace that already has all three is set up, and is not asked
 * to redo it.
 *
 * <p>Nothing is asked before onboarding is finished (on the cloud, a checklist over the onboarding
 * form would compete with it; CE has no profile onboarding, so there it only waits for the
 * session), and nothing once the checklist is done in this workspace.
 */
export function useSetupChecklist(): SetupChecklistState {
  const auth = useOptionalAuth();
  const isAuthenticated = auth?.isAuthenticated ?? false;
  const isAuthLoading = auth?.isLoading ?? false;
  const userId = auth?.user?.sub;
  // The id conversations are stored under (X-User-ID): the platform's numeric user id. On the
  // cloud it is NOT the Keycloak subject, so comparing with `sub` would never match.
  const ownerId = auth?.numericUserId != null ? String(auth.numericUserId) : null;

  // Same key and endpoint as FirstLoginGuard and useChangelog, so this reads their cache.
  const { data: onboarding } = useQuery({
    queryKey: ['user', 'onboarding-status', userId],
    queryFn: () => apiClient.get<OnboardingStatus>('/auth-service/api/onboarding/status'),
    enabled: !IS_CE && !isAuthLoading && isAuthenticated,
    staleTime: 5 * 60 * 1000,
    retry: false,
  });
  const onboardingDone = IS_CE || (!!onboarding && !needsOnboardingRedirect(onboarding));

  const [visibility, setVisibility] = useChecklistVisibility();
  const enabled = !isAuthLoading && isAuthenticated && onboardingDone && visibility === 'shown';

  const integration = useOrgScopedQuery({
    queryKey: [SETUP_CHECKLIST_KEY, 'integration'],
    queryFn: async () => {
      // A credential that only backs a chat channel is not "an app": connecting Telegram must
      // not tick this too. Excluded by the ids the channels use, not by service name, because
      // Slack, Discord and Teams are ALSO ordinary app integrations under the very same names.
      const [page, channels] = await Promise.all([
        credentialService.getCredentials({ page: 1, pageSize: 20 }),
        // A channel-list outage must not also clear this task: without it, nothing is excluded.
        chatChannelService.list().catch(() => ({ channels: [] })),
      ]);
      const channelCredentials = new Set(channels.channels.map((c) => c.credentialId));
      return page.credentials.some((c) => !channelCredentials.has(c.id));
    },
    enabled,
    ...FRESHNESS,
  });
  const chat = useOrgScopedQuery({
    queryKey: [SETUP_CHECKLIST_KEY, 'chat', ownerId],
    queryFn: async () => {
      // The workspace listing, narrowed to the caller: a teammate's conversation is not this
      // person having talked to the assistant. Only the 20 most recent are read: in a busy
      // workspace a person's only conversation can sit further back, and the task then stays
      // open until they talk again, which is the direction to err in.
      const page = await conversationApi.getConversations(0, 20, 'chat');
      return (page.content ?? []).some((c) => String(c.userId) === ownerId);
    },
    enabled: enabled && ownerId !== null,
    ...FRESHNESS,
  });
  const build = useOrgScopedQuery({
    queryKey: [SETUP_CHECKLIST_KEY, 'build'],
    queryFn: async () => {
      const [agents, workflows] = await Promise.all([
        agentService.getAgentsPage({ page: 0, size: 1 }),
        workflowService.getWorkflowsPage({ page: 0, size: 1 }),
      ]);
      return agents.totalCount > 0 || workflows.totalCount > 0;
    },
    enabled,
    ...FRESHNESS,
  });
  // A destination counts once a real message reached it (the backend activates a link exactly
  // then): a connected but silent one is not a channel anyone is reached on.
  const channel = useOrgScopedQuery({
    queryKey: [SETUP_CHECKLIST_KEY, 'channel'],
    queryFn: async () => (await chatChannelService.list()).channels.some((c) => c.verifiedAt !== null),
    enabled,
    ...FRESHNESS,
  });

  // A failed read counts as not done rather than unknown: the checklist then still shows, and
  // offers the task, instead of disappearing because one endpoint had a bad minute.
  const evidenceOf = (query: { data?: boolean; isError: boolean }) => (query.isError ? false : query.data);
  const progress = setupProgress({
    integration: evidenceOf(integration),
    // No platform id (its lookup failed): the task cannot be checked, so it reads as not done,
    // like any failed read, instead of leaving the whole checklist unknown and hidden for good.
    chat: ownerId === null ? false : evidenceOf(chat),
    build: evidenceOf(build),
    channel: evidenceOf(channel),
  });

  // Done for good in this workspace: remembered, so the four reads stop for every later page.
  //
  // Reported completed only on a transition SEEN in this session: the checklist must first have
  // been read as open (every task known, at least one not done, no failed read standing in for
  // "not done"), then as all done. The stored 'completed' is per browser, so someone who finished
  // long ago and signs in on a new device reads all done straight away: that is not a completion,
  // and is only remembered. The refs are keyed by workspace (finishing a second workspace later in
  // the same session counts too) and keep StrictMode's doubled effect, or a re-render before the
  // stored value lands, from counting it twice.
  const { currentOrgId } = useCurrentOrg();
  const orgKey = currentOrgId ?? '';
  const seenOpenFor = useRef<Set<string>>(new Set());
  const reportedFor = useRef<Set<string>>(new Set());
  const anyReadFailed = integration.isError || chat.isError || build.isError || channel.isError;
  const seenOpen = enabled && progress.known && !progress.allDone && !anyReadFailed;
  useEffect(() => {
    if (seenOpen) seenOpenFor.current.add(orgKey);
  }, [seenOpen, orgKey]);
  useEffect(() => {
    if (enabled && progress.allDone) {
      if (seenOpenFor.current.has(orgKey) && !reportedFor.current.has(orgKey)) {
        reportedFor.current.add(orgKey);
        track('setup_checklist_completed', { total: progress.total });
      }
      setVisibility('completed');
    }
  }, [enabled, progress.allDone, progress.total, orgKey, setVisibility]);

  return {
    progress,
    visible: enabled && showSetupChecklist(progress),
  };
}
