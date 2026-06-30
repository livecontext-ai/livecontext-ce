/**
 * Reconcile a workflow plan's pinned credential ids against the user's CURRENT
 * credentials, just before the plan is handed to the backend for a run.
 *
 * Why this exists: each MCP step may pin a `selectedCredentialId`. The backend
 * resolves a pinned user credential STRICTLY - no user/platform fallback (see
 * StepNode → catalog ApiService.credentialsRequiredError). So if the user has
 * since deleted or reconnected that credential (the id no longer exists), the
 * step fails at execution with `credentials_required`, even though the user has
 * a perfectly good current credential for the same integration. The inspector
 * already self-heals a stale id - but only for the node the user opens
 * (CredentialSection). Nodes never opened keep the dead id, and
 * `generateWorkflowPlan` serializes it verbatim into the run plan.
 *
 * This helper closes that gap for every step: a `selectedCredentialId` that is
 * not present in the user's current credentials is re-pointed to the best current
 * credential for the step's integration (iconSlug), or dropped so the backend
 * resolves by the integration default.
 *
 * Loaded vs not-loaded: `userCredentials === undefined` means the list hasn't
 * loaded yet - we return the plan UNCHANGED (reconciling against an unloaded list
 * would treat every pin as dead and drop valid credentials). A loaded-but-empty
 * list (`[]`) IS reconciled: with zero credentials every pin is genuinely dead.
 *
 * Pure + immutable: returns the SAME reference when nothing changed (so callers
 * can cheaply detect a no-op). Steps using a platform credential, or with no
 * pinned id, are left untouched.
 */
import type { Credential } from '@/lib/api/orchestrator';
import { findBestUserCredential } from './credentialMatching';

interface ReconcilableStep {
  iconSlug?: string;
  selectedCredentialId?: number;
  credentialSource?: 'user' | 'platform';
  [key: string]: unknown;
}

interface ReconcilablePlan {
  mcps?: ReconcilableStep[];
  [key: string]: unknown;
}

export function reconcilePlanCredentials<T>(
  plan: T,
  userCredentials: readonly Credential[] | undefined,
): T {
  // Not loaded yet → never reconcile (would drop valid pins). Loaded-but-empty
  // still reconciles below.
  if (userCredentials === undefined) return plan;

  const p = plan as unknown as ReconcilablePlan;
  if (!p || !Array.isArray(p.mcps) || p.mcps.length === 0) {
    return plan;
  }

  const validIds = new Set<number>(userCredentials.map((c) => c.id));
  let changed = false;

  const mcps = p.mcps.map((step) => {
    if (!step || typeof step !== 'object') return step;
    // Platform-sourced steps don't carry a user credential id - leave as-is.
    if (step.credentialSource === 'platform') return step;
    const pinned = step.selectedCredentialId;
    if (pinned == null) return step; // nothing pinned → backend resolves by default
    if (validIds.has(pinned)) return step; // still a live credential

    // Dead reference → re-pick the best current credential for the integration.
    const replacement = findBestUserCredential(userCredentials, step.iconSlug);
    changed = true;
    const next: ReconcilableStep = { ...step };
    if (replacement) {
      next.selectedCredentialId = replacement.id;
    } else {
      // No usable credential for this integration: drop the dead pin so the
      // backend resolves by the integration default (or surfaces a clean
      // "not configured" rather than chasing a non-existent id).
      delete next.selectedCredentialId;
    }
    return next;
  });

  return changed ? ({ ...p, mcps } as unknown as T) : plan;
}
