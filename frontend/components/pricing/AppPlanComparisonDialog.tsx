'use client';

import * as React from 'react';
import PlanComparisonDialog from '@/components/pricing/PlanComparisonDialog';
import { useSubscription } from '@/lib/hooks/smart-hooks-complete';
import { useCeCloudLinkStatus } from '@/hooks/useCeCloudLinkStatus';
import { CLOUD_NO_SUBSCRIPTION } from '@/lib/api/cloud-link.service';
import { IS_CE } from '@/lib/edition';

/**
 * The comparison dialog, told which plan the reader is on.
 *
 * <p>Split from the dialog itself so the dialog stays mountable where those
 * answers do not exist: the public landing has no subscription and no query
 * client, and a dialog that is only an upsell must never be the reason a page
 * cannot render.
 *
 * <p><b>Which plan counts as "current" is not the local one.</b> Both reads
 * mirror surfaces that already resolve this, and both share their react-query
 * cache keys, so mounting this adds no request:
 *
 * <ul>
 *   <li><b>Cloud</b>: the ACTIVE WORKSPACE's tier before the personal one, the
 *       precedence the sidebar badge uses - the plan that governs what the
 *       reader can do here is the workspace's, not their own subscription's.</li>
 *   <li><b>Self-hosted</b>: the LINKED CLOUD account's plan, which is what the
 *       backend bills against; the local plan is always FREE and marking Free as
 *       "current" for a linked install would be plainly wrong. With no cloud plan
 *       governing (unlinked, or connected with no subscription) it falls back to
 *       the local plan, matching the plans page.</li>
 * </ul>
 *
 * <p><b>It opens nothing by itself.</b> This table once opened on its own right
 * after onboarding, to state the new account's monthly credits. That moment now
 * belongs to {@code WelcomeGiftModal}, which says the same figure without
 * putting a five-column Free-to-Enterprise matrix in front of somebody who is not
 * choosing a plan. So the comparison is back to one in-app entry point, the
 * pricing page's "Compare plans" button, which is what
 * `plan-comparison-entry-points.test.ts` holds it to.
 */
export default function AppPlanComparisonDialog() {
  const { subscription } = useSubscription();
  const { status: cloudLinkStatus } = useCeCloudLinkStatus();

  const currentPlanCode = React.useMemo(() => {
    const billing = subscription as
      | { activeOrgPlanCode?: string | null; subscription?: { planCode?: string | null } }
      | null;
    const localPlanCode = billing?.subscription?.planCode ?? null;

    if (IS_CE) {
      const cloudPlanCode = cloudLinkStatus?.cloudPlanCode;
      const governing =
        cloudPlanCode && cloudPlanCode !== CLOUD_NO_SUBSCRIPTION ? cloudPlanCode : null;
      return governing ?? localPlanCode;
    }

    return billing?.activeOrgPlanCode ?? localPlanCode;
  }, [subscription, cloudLinkStatus]);

  return <PlanComparisonDialog currentPlanCode={currentPlanCode} />;
}
