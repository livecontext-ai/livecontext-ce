'use client';

import * as React from 'react';
import { Lock } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useValidationOptional } from '../../contexts/ValidationContext';
import { InfoPopover } from '@/components/ui/info-popover';

/**
 * "This node needs a higher plan", as a marker beside the node's name.
 *
 * <p>A padlock and the shared info affordance, nothing more. The first version
 * was a full-width banner under the title and it dominated a panel whose job is
 * to configure the node: the restriction is a fact ABOUT the node, not the
 * subject of the panel, so it gets the weight of a marker and puts its sentence
 * behind the same {@code i} every other explained field uses.
 *
 * <p><b>The padlock states the restriction, it does not act.</b> The sentence
 * behind the info button names the plan that would include this node, which is
 * what a reader of "available from PRO" needs here. What that plan brings
 * beyond this node is a pricing question, answered on the pricing page: a
 * workflow being configured is not somewhere to open a full-screen plan matrix
 * over.
 *
 * <p><b>It reads the verdict, it does not recompute it.</b> `PlanAvailabilityRule`
 * already walks every node with the gate in hand, so asking again here would be a
 * second implementation of the same comparison, free to drift, and would drag
 * react-query and the auth context into every surface that renders a node header.
 * Reading the rule's issue also means this needs no provider of its own: outside
 * the validation context it renders nothing.
 */
export function NodePlanNotice({ nodeId }: { nodeId: string }) {
  const t = useTranslations('billing.planLocked');
  const validation = useValidationOptional();

  const requiredPlan = React.useMemo(() => {
    const result = validation?.state?.fullResult;
    if (!result) return null;
    for (const issues of Object.values(result.issuesByElement)) {
      for (const issue of issues) {
        if (issue.ruleName !== 'PlanAvailability') continue;
        if (issue.context?.nodeId !== nodeId) continue;
        const plan = issue.context?.requiredPlan;
        if (typeof plan === 'string' && plan) return plan;
      }
    }
    return null;
  }, [validation?.state?.fullResult, nodeId]);

  if (!requiredPlan) return null;

  return (
    <span
      className="inline-flex items-center gap-0.5 align-middle"
      data-testid="node-plan-notice"
      // The lock alone would be a shape with no name; the label is what a screen
      // reader gets, since the sentence itself lives behind the info button.
      role="status"
      aria-label={t('nodeNotice', { plan: requiredPlan })}
    >
      {/* A marker, not a control: the plan comparison is reachable from the
          pricing page only, so the lock states the restriction and the info
          button beside it explains which plan lifts it. */}
      <Lock className="h-3.5 w-3.5 text-amber-500" aria-hidden />
      <InfoPopover
        label={t('nodeNotice', { plan: requiredPlan })}
        // A short name for the button: the sentence is already the status label above and
        // the panel's text, so naming the button with it too would read it three times.
        accessibleName={t('infoLabel', { plan: requiredPlan })}
        size="sm" side="bottom" align="end">{t('nodeNotice', { plan: requiredPlan })}</InfoPopover>
    </span>
  );
}

export default NodePlanNotice;
