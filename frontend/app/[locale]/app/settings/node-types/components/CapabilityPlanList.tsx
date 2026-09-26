'use client';

import { useTranslations } from 'next-intl';
import { PlanRequirementSelect } from './PlanRequirementSelect';

/**
 * A product capability that is gated by plan but is not a node type, a catalog API, or one of its
 * endpoints.
 *
 * <p>The other two tabs derive their keys from a catalogue: the node list comes from the node
 * documentation table, the integration list from the API catalogue. A capability has no such
 * catalogue to derive from, so this list is written out here. That is a deliberate trade: the
 * alternative is a registry table whose only reader would be this screen, and a hardcoded row is
 * honest about there being one of them.
 *
 * <p>The key must match, string for string, what the backend gate asks for, or the control edits a
 * row nothing reads. `VectorFeatureGate.FEATURE_KEY` in datasource-service and
 * `CeExclusiveAcquisitionGuard.VECTOR_SEARCH_FEATURE_KEY` in publication-service are the two
 * backend spellings, and `VECTOR_FEATURE_KEY` in `hooks/useVectorFeatureLock` is the frontend one.
 */
const CAPABILITIES: ReadonlyArray<{ key: string; labelKey: string; descriptionKey: string }> = [
  {
    key: 'feature:vector_search',
    labelKey: 'vectorSearch.label',
    descriptionKey: 'vectorSearch.description',
  },
  {
    // NotificationEmailEntitlement.FEATURE_KEY in orchestrator-service is the backend spelling.
    key: 'feature:notification_email',
    labelKey: 'notificationEmail.label',
    descriptionKey: 'notificationEmail.description',
  },
];

interface CapabilityPlanListProps {
  /** featureKey -> stored minimum plan, from the admin listing. */
  requirements: Record<string, string>;
  onChangePlan: (featureKey: string, minPlan: string, label: string) => void;
  savingKeys: Set<string>;
  planOptions?: readonly string[];
}

export function CapabilityPlanList({
  requirements,
  onChangePlan,
  savingKeys,
  planOptions,
}: CapabilityPlanListProps) {
  const t = useTranslations('nodeTypeSettings.capabilities');

  return (
    <div className="space-y-3">
      {CAPABILITIES.map((capability) => (
        <div
          key={capability.key}
          className="flex items-start justify-between gap-4 rounded-lg border border-theme p-4"
        >
          <div className="min-w-0">
            <p className="text-sm font-medium text-theme-primary">{t(capability.labelKey)}</p>
            <p className="mt-1 text-sm text-theme-secondary">{t(capability.descriptionKey)}</p>
          </div>
          <PlanRequirementSelect
            value={requirements[capability.key] ?? null}
            onChange={(minPlan) => onChangePlan(capability.key, minPlan, t(capability.labelKey))}
            disabled={savingKeys.has(capability.key)}
            options={planOptions}
          />
        </div>
      ))}
    </div>
  );
}
