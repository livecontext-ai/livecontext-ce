'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { useLocale, useTranslations } from 'next-intl';
import { KeyRound, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { IS_CLOUD } from '@/lib/edition';
import { useOptionalAuth } from '@/lib/providers/smart-providers';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { useCurrentOrg, useIsCurrentOrgOwner } from '@/lib/stores/current-org-store';
import { organizationApi, type Organization } from '@/lib/api/organization-api';
import { useSubscription } from '@/lib/hooks/smart-hooks-complete';
import { readWorkspacePreference, workspacePreferenceKey } from '@/lib/preferences/workspacePreference';
import { MFA_STATUS_QUERY_KEY, TWO_FACTOR_RETURN_TO } from '@/components/settings/TwoFactorSettingsCard';
import { track } from '@/lib/analytics/analytics';

/**
 * A one-time suggestion to the OWNER of a team workspace: turn on two-factor, you now hold
 * the keys to a whole team (its connected apps, its credentials, its billing).
 *
 * <p>Why a dismissible card and not a task in the setup checklist: the checklist cannot be
 * dismissed by design, so a 2FA task there would make the factor mandatory by nagging, and a
 * workspace that already finished it is never asked again, so existing teams would never see
 * a new task. Members are not targeted: whether the whole team must use 2FA is the owner's
 * call (the "require two-factor" workspace policy), not ours.
 *
 * <p>Shown when ALL hold: cloud, an active workspace is resolved, the user owns it (OWNER only:
 * an ADMIN manages people but never holds the billing), its plan is Team or above (the same
 * strict rule that unlocks inviting teammates, so an unknown plan never shows it; a personal
 * workspace counts when its owner is on Team, since the server lets that owner invite into
 * it), two-factor is available and off, no enrollment is already
 * pending, and it was not dismissed. Dismissal is per user AND per workspace. It also goes
 * away by itself once two-factor is on: the status query is the settings card's own.
 *
 * <p>Ownership and plan are read from ONE record: the active workspace's own entry in the
 * membership list, whose `currentUserRole` and `planCode` (the plan of THAT workspace's owner)
 * the server computes per workspace. They must never come from two sources. The first version
 * took the role from the active-workspace store and the plan from the subscription's
 * `activeOrgPlanCode`, which the gateway derives from the user's DEFAULT workspace, not the
 * active one: a member of a Team workspace whose browser was still on their own personal
 * workspace (role OWNER there) combined "owner" from one workspace with "Team" from the other
 * and was told "You own this team workspace".
 *
 * <p>Two cheap pre-filters keep the list request off almost every page load, and the record
 * still decides: the store must see the user as owner, and the user's OWN subscription must be
 * Team or above. The second is a necessary condition, not a guess: the plan of a workspace is
 * its owner's plan, so for a workspace the user owns, it is their own (the two reads only part
 * in rare billing states, such as a newer incomplete checkout, where the suggestion is skipped
 * rather than shown to the wrong person). Both fail closed (an unknown or
 * still-loading plan asks nothing), and neither can make a non-owner eligible.
 *
 * <p>Mounted in the /app layout next to IncidentStrip, for the same reason (a fixed overlay
 * needs no AppShell surgery and cannot remount a running canvas).
 */

export const TWO_FACTOR_NUDGE_PREFIX = 'lc.twoFactorNudge';

type NudgeState = 'dismissed';
const isNudgeState = (value: string | null): value is NudgeState => value === 'dismissed';

/**
 * The active workspace's record says the user owns it and its owner is on Team or above (the
 * same strict rule that unlocks inviting teammates). A missing record (list still loading,
 * failed, or the workspace absent from it) is never an owner.
 */
function ownsTeamWorkspace(workspace: Organization | undefined): boolean {
  return !!workspace && workspace.currentUserRole === 'OWNER' && isTeamPlan(workspace.planCode);
}

function isTeamPlan(plan: string | null | undefined): boolean {
  return plan === 'TEAM' || (plan?.startsWith('ENTERPRISE') ?? false);
}

export default function TwoFactorNudge() {
  const t = useTranslations('twoFactorNudge');
  const locale = useLocale();
  const auth = useOptionalAuth();
  const { currentOrgId } = useCurrentOrg();
  const isOwner = useIsCurrentOrgOwner();
  // The user's OWN plan (per-user subscription), never `activeOrgPlanCode` (default workspace).
  const { subscription } = useSubscription();
  const ownPlanCode: string | null = subscription?.subscription?.planCode ?? null;
  const userKey = auth?.user?.sub ?? null;
  const prefix = `${TWO_FACTOR_NUDGE_PREFIX}:${userKey ?? 'anonymous'}`;

  // Read after mount (localStorage does not exist during SSR) and tagged with the key it was
  // read for, so a workspace switch never reuses the previous workspace's answer. Null until
  // read: nothing renders, so a dismissed card never flashes up.
  const [stored, setStored] = useState<{ key: string; dismissed: boolean } | null>(null);
  const key = workspacePreferenceKey(prefix, currentOrgId);
  useEffect(() => {
    // Syncing from localStorage on mount and on every workspace switch.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setStored({ key, dismissed: readWorkspacePreference(prefix, currentOrgId, isNudgeState) !== null });
  }, [key, prefix, currentOrgId]);
  const dismissed = stored === null || stored.key !== key ? null : stored.dismissed;

  const candidate =
    IS_CLOUD &&
    !!auth?.isAuthenticated &&
    !auth?.isLoading &&
    userKey !== null &&
    currentOrgId !== null &&
    isOwner &&
    isTeamPlan(ownPlanCode) &&
    dismissed === false;

  // Same query (and cache key) as the workspace switcher and the settings selectors, so the
  // list is fetched at most once for all of them.
  const { data: workspaces } = useQuery({
    queryKey: ['organizations', 'memberships'],
    queryFn: () => organizationApi.getOrganizations(),
    enabled: candidate,
    staleTime: 5 * 60 * 1000,
  });

  const eligible = candidate && ownsTeamWorkspace(workspaces?.find((w) => w.id === currentOrgId));

  const { data } = useQuery({
    queryKey: MFA_STATUS_QUERY_KEY,
    queryFn: () => unifiedApiService.getMfaStatus(),
    enabled: eligible,
    staleTime: 60 * 1000,
    retry: false,
  });

  const visible = eligible && !!data && data.available && !data.totpEnabled && !data.setupPending;

  const reportedFor = useRef<string | null>(null);
  useEffect(() => {
    if (visible && reportedFor.current !== key) {
      reportedFor.current = key;
      track('mfa_nudge_shown', {});
    }
  }, [visible, key]);

  if (!visible) return null;

  const dismiss = (action: 'dismiss' | 'open') => {
    setStored({ key, dismissed: true });
    try {
      window.localStorage.setItem(key, 'dismissed');
    } catch {
      // Storage unavailable: dismissed for this session only.
    }
    track(action === 'open' ? 'mfa_nudge_clicked' : 'mfa_nudge_dismissed', {});
  };

  return (
    <div className="fixed bottom-4 right-4 z-[55] w-[min(92vw,22rem)]" data-testid="two-factor-nudge">
      <div role="status" className="rounded-xl border border-theme bg-theme-primary p-4 shadow-lg space-y-3">
        <div className="flex items-start gap-3">
          <div className="w-8 h-8 shrink-0 bg-theme-secondary rounded-lg flex items-center justify-center">
            <KeyRound className="h-3.5 w-3.5 text-theme-primary" aria-hidden="true" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-sm font-semibold text-theme-primary">{t('title')}</p>
            <p className="text-sm text-theme-secondary mt-0.5">{t('body')}</p>
          </div>
          <button
            type="button"
            onClick={() => dismiss('dismiss')}
            aria-label={t('dismiss')}
            className="shrink-0 rounded-md p-0.5 text-theme-secondary hover:bg-black/10 dark:hover:bg-white/10 cursor-pointer"
          >
            <X className="h-3.5 w-3.5" aria-hidden="true" />
          </button>
        </div>
        <div className="flex justify-end">
          <Button asChild size="sm" className="h-8 px-3">
            <Link href={`/${locale}${TWO_FACTOR_RETURN_TO}`} onClick={() => dismiss('open')}>
              {t('cta')}
            </Link>
          </Button>
        </div>
      </div>
    </div>
  );
}
