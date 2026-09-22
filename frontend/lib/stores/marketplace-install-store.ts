import { create } from 'zustand';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import { track } from '@/lib/analytics/analytics';

/**
 * Global install (acquire) state machine for marketplace publications.
 *
 * Lived inside AcquirePublicationModal until 2026-07: the modal owned the
 * simulated 5-10s progress ramp AND the acquire HTTP call, so the install
 * died with the modal. It now lives here (module-global, component-free) so:
 * - the modal can close on confirm and the marketplace CARD renders the same
 *   progress (the interface preview un-greys as the gauge fills);
 * - an install started from the preview page survives the navigation back to
 *   /app/marketplace and keeps progressing on the matching card.
 *
 * Timing contract (identical to the old in-modal machine): a randomized
 * 5-10s visual budget eases 0→95% via easeOutCubic while the real acquire
 * call runs in parallel; completion is gated on BOTH the budget elapsing AND
 * the call returning, then the bar snaps to 100%, holds 300ms and flips to
 * 'success'. A backend slower than the budget parks the bar at 95% - the UI
 * never claims a completion that didn't happen.
 */

export type MarketplaceInstallStatus =
  | 'installing'
  | 'success'
  | 'error'
  /** Managed cloud cannot install a CE-exclusive publication (backend 403 CE_EXCLUSIVE). */
  | 'ce-exclusive'
  /**
   * The app uses a capability this workspace's PLAN does not include, today vector search
   * (backend 403 PLAN_UPGRADE_REQUIRED). Deliberately NOT 'ce-exclusive': that state renders a
   * dead end with no retry, while this one is lifted by an upgrade and must offer that route.
   */
  | 'plan-upgrade-required'
  | 'link-required'
  | 'insufficient-credits';

export interface ActiveMarketplaceInstall {
  publication: WorkflowPublication;
  /** CE remote mode: acquired from the cloud marketplace instead of local. */
  ceMode: boolean;
  /**
   * Admin demo mode: the whole animation runs but NO acquire call is made, so
   * nothing is created, billed or receipted. Consumers use it to withhold the
   * things that only make sense for a real install (the post-install analytics,
   * and the "installed" flip of the card, which is driven by a null acquiredId).
   */
  demo: boolean;
  /**
   * True when the install was started in inline-progress mode (marketplace
   * grid / preview header): the marketplace page renders the progress on the
   * CARD and consumes the terminal states. False for full-modal consumers
   * (ChatCore, ResourceMarketplaceGrid), whose modal owns the whole lifecycle -
   * the marketplace page must NOT consume or re-surface those installs, or the
   * two consumers fight over the shared state (double modals, stolen success).
   */
  inline: boolean;
  status: MarketplaceInstallStatus;
  /** 0-100, drives every progress rendering (modal bar or card gauge). */
  progress: number;
  /** Canonical id returned by the acquire endpoint (agentId / resourceId / workflowId). */
  acquiredId: string | null;
  /** Raw error message for the 'error' status (consumers fall back to their own copy). */
  error: string | null;
  /**
   * Per-type count of what the acquire actually created in the workspace
   * (interfaces / tables / agents / sub-workflows), reported by the backend.
   * Types that produced nothing are absent, so an empty object means the
   * publication itself was the whole install. Consumed by the post-install
   * summary so the user is told what landed instead of discovering new rows
   * in three different lists.
   */
  resources: InstalledResourceCounts;
  /**
   * The user ticked "also create an editable copy" on the acquire modal, so the
   * machine mints the WORKFLOW twin right after the acquire succeeds instead of
   * making them come back for it from the app's settings cog.
   */
  withEditableCopy: boolean;
  /**
   * Id of the editable WORKFLOW copy created alongside this install, or null when
   * none was requested or the request failed. Consumers link to it from the
   * success screen.
   */
  editableCopyWorkflowId: string | null;
  /**
   * The requested editable copy could not be created (quota, backend error). The
   * INSTALL itself still succeeded - the copy is best-effort on purpose, so a
   * failed copy must never turn a working install into an error screen. Consumers
   * surface it as a note, and the app's settings cog can retry it.
   */
  editableCopyFailed: boolean;
}

/**
 * What an install created, keyed by resource type. Deliberately open-ended: a new
 * backend type shows up here without a frontend change, and consumers must skip keys
 * they have no label for rather than render a raw key at the user.
 */
export type InstalledResourceCounts = Partial<Record<'workflows' | 'interfaces' | 'tables' | 'agents', number>>
  & Record<string, number | undefined>;

/**
 * Demo mode has no acquire response, so the summary is built from the counts the
 * publication already DECLARES. Those are real numbers taken from its own
 * metadata, never invented, but they are not the acquire tally: a publication
 * only carries the counts its publish step recorded, so an application does not
 * declare its sub-workflows and the summary stays silent about them rather than
 * guessing.
 *
 * The one place the two genuinely disagree is an agent publication, whose
 * `agentCount` COUNTS THE ROOT AGENT while a real acquire reports only the
 * sub-agents it cloned alongside it. Left as-is, a plain agent with no
 * sub-agents would show a phantom "1 agent" line that no real install produces,
 * so the root is subtracted back out here.
 */
function demoResourceCounts(publication: WorkflowPublication): InstalledResourceCounts {
  const counts: InstalledResourceCounts = {};
  const add = (key: string, value: number | undefined) => {
    if (typeof value === 'number' && value > 0) counts[key] = value;
  };
  add('workflows', publication.workflowCount);
  add('interfaces', publication.interfaceCount);
  add('tables', publication.datasourceCount);
  const agents = publication.publicationType === 'AGENT'
    ? (publication.agentCount ?? 0) - 1
    : publication.agentCount;
  add('agents', agents);
  return counts;
}

/** The acquire response's resource summary, tolerant of an older backend that omits it. */
function readResourceCounts(result: unknown): InstalledResourceCounts {
  const raw = (result as { resources?: unknown } | null)?.resources;
  if (!raw || typeof raw !== 'object') return {};
  const counts: InstalledResourceCounts = {};
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof value === 'number' && value > 0) counts[key] = value;
  }
  return counts;
}

interface MarketplaceInstallState {
  active: ActiveMarketplaceInstall | null;
  /**
   * Start installing a publication. Returns false (and does nothing) while
   * another install is running - one at a time; callers MUST surface the
   * refusal instead of pretending the install started. Starting a new install
   * replaces any previous terminal (success/error) state.
   */
  startInstall: (
    publication: WorkflowPublication,
    opts?: { ceMode?: boolean; inline?: boolean; withEditableCopy?: boolean; demo?: boolean },
  ) => boolean;
  /**
   * Consume a terminal 'success' for the given publication: drops it WITHOUT
   * invalidating anything else. Safe to call from stale async continuations
   * (e.g. a refetch's finally) - unlike clear(), it can never kill an install
   * that started in the meantime, because a newer install has already replaced
   * `active` and the id/status guard makes this a no-op.
   */
  consumeSuccess: (publicationId: string) => void;
  /** Drop the active install state AND invalidate any in-flight machine. */
  clear: () => void;
}

// Monotonic token: a clear()/new start invalidates the async continuations of
// any previous install so a stale acquire can't resurrect dead state.
let installSeq = 0;
let progressInterval: ReturnType<typeof setInterval> | null = null;

function stopProgressTicker() {
  if (progressInterval) {
    clearInterval(progressInterval);
    progressInterval = null;
  }
}

export const useMarketplaceInstallStore = create<MarketplaceInstallState>((set, get) => ({
  active: null,

  startInstall: (publication, opts) => {
    if (get().active?.status === 'installing') return false;
    const ceMode = Boolean(opts?.ceMode);
    const inline = Boolean(opts?.inline);
    const demo = Boolean(opts?.demo);
    // A demo install creates nothing, so there is nothing to make an editable
    // copy OF: the request would be a real backend write in a mode whose whole
    // point is that it performs none.
    const withEditableCopy = Boolean(opts?.withEditableCopy) && !demo;
    const token = ++installSeq;
    stopProgressTicker();

    const isFree = !publication.creditsPerUse || publication.creditsPerUse === 0;
    const isAgent = publication.publicationType === 'AGENT';
    // TABLE / INTERFACE / SKILL go through /publications/acquire-resource/{id},
    // not the workflow /acquire endpoint (same routing the modal always had).
    const isResource =
      publication.publicationType === 'TABLE' ||
      publication.publicationType === 'INTERFACE' ||
      publication.publicationType === 'SKILL';

    // A rehearsed install is not a product signal. Emitting it would inflate the
    // install funnel with events that have no acquisition behind them.
    if (!demo) {
      track('app_install_started', {
        publication_id: publication.id,
        publication_type: publication.publicationType ?? null,
        is_free: isFree,
        ce_mode: ceMode,
      });
    }

    set({
      active: {
        publication,
        ceMode,
        demo,
        inline,
        status: 'installing',
        progress: 0,
        acquiredId: null,
        error: null,
        resources: {},
        withEditableCopy,
        editableCopyWorkflowId: null,
        editableCopyFailed: false,
      },
    });

    const minDuration = 5000;
    const maxDuration = 10000;
    const targetDuration = minDuration + Math.floor(Math.random() * (maxDuration - minDuration + 1));
    const startTime = performance.now();

    const setProgress = (progress: number) => {
      if (token !== installSeq) return;
      const active = get().active;
      if (!active) return;
      set({ active: { ...active, progress } });
    };

    progressInterval = setInterval(() => {
      if (token !== installSeq) {
        stopProgressTicker();
        return;
      }
      const elapsed = performance.now() - startTime;
      const ratio = Math.min(elapsed / targetDuration, 1);
      const eased = 1 - Math.pow(1 - ratio, 3); // easeOutCubic
      setProgress(Math.min(95, eased * 95));
      if (ratio >= 1) stopProgressTicker();
    }, 50);

    // CE-cloud (ceMode): every type installs through the unified remote acquire
    // (/publications/remote/{id}/acquire). Off CE-cloud, each type keeps its own
    // local acquire endpoint.
    // Demo mode must not even CONSTRUCT the call: these service methods fire the
    // request on the spot, so building the promise and ignoring it would still
    // acquire the publication.
    const acquireCall = demo
      ? null
      : ceMode
        ? publicationService.acquireRemotePublication(publication.id)
        : isAgent
          ? publicationService.acquireAgentPublication(publication.id)
          : isResource
            ? publicationService.acquireResourcePublication(publication.id)
            : publicationService.acquirePublication(publication.id);

    void runInstall();
    return true;

    async function runInstall() {
      try {
        const result = acquireCall ? await acquireCall : null;
        if (token !== installSeq) return;

        // Requested editable copy: fire it as soon as the install landed, so it runs
        // DURING the remaining progress budget instead of adding its own wait. It is
        // best-effort by design - `.catch` keeps a failed copy out of the install's
        // error path, because the application itself is installed either way.
        const editableCopyCall = withEditableCopy
          ? publicationService
              .createEditableWorkflowCopy(publication.id, ceMode)
              .then((copy) => copy.workflowId ?? null)
              .catch(() => null)
          : null;

        // Wait out the visual budget so a sub-second backend never flashes.
        const remaining = Math.max(0, targetDuration - (performance.now() - startTime));
        if (remaining > 0) {
          await new Promise((r) => setTimeout(r, remaining));
        }
        if (token !== installSeq) return;

        const editableCopyWorkflowId = editableCopyCall ? await editableCopyCall : null;
        if (token !== installSeq) return;

        stopProgressTicker();
        setProgress(100);
        await new Promise((r) => setTimeout(r, 300));
        if (token !== installSeq) return;

        // Result shape varies by acquire endpoint - agent → {agentId},
        // resource → {resourceId, type}, workflow/remote → {workflowId, ...}.
        // Demo mode has no id on purpose: consumers gate "open what you just
        // installed" on it, and there is nothing to open.
        const id = demo
          ? null
          : isAgent
            ? (result as { agentId: string }).agentId
            : isResource
              ? (result as { resourceId: string }).resourceId
              : (result as { workflowId: string }).workflowId;

        const active = get().active;
        if (!active) return;
        set({
          active: {
            ...active,
            status: 'success',
            progress: 100,
            acquiredId: id,
            resources: demo ? demoResourceCounts(publication) : readResourceCounts(result),
            editableCopyWorkflowId,
            editableCopyFailed: withEditableCopy && !editableCopyWorkflowId,
          },
        });
        if (!demo) {
          track('app_install_succeeded', {
            publication_id: publication.id,
            publication_type: publication.publicationType ?? null,
            is_free: isFree,
            acquired_id: id,
            duration_ms: Math.round(performance.now() - startTime),
          });
        }
      } catch (err: any) {
        if (token !== installSeq) return;
        stopProgressTicker();
        const active = get().active;
        if (!active) return;
        let status: MarketplaceInstallStatus;
        let outcome: string;
        if (err?.status === 403 && err?.code === 'CLOUD_ACCOUNT_NOT_LINKED') {
          status = 'link-required';
          outcome = 'link_required';
        } else if (err?.status === 403 && err?.code === 'PLAN_UPGRADE_REQUIRED') {
          status = 'plan-upgrade-required';
          outcome = 'plan_upgrade_required';
        } else if (err?.status === 403 && err?.code === 'CE_EXCLUSIVE') {
          // Defense in depth: the UI already hides Install for these on cloud,
          // so reaching here means a stale card or a direct call. Surface the
          // real reason instead of a generic failure.
          status = 'ce-exclusive';
          outcome = 'ce_exclusive';
        } else if (err?.status === 402) {
          status = 'insufficient-credits';
          outcome = 'insufficient_credits';
        } else {
          status = 'error';
          outcome = 'error';
        }
        set({ active: { ...active, status, error: err?.message || null } });
        if (!demo) {
          track('app_install_failed', {
            publication_id: publication.id,
            publication_type: publication.publicationType ?? null,
            outcome,
            error_code: err?.code ?? (err?.status != null ? String(err.status) : null),
          });
        }
      }
    }
  },

  consumeSuccess: (publicationId) => {
    const active = get().active;
    if (active?.publication.id === publicationId && active.status === 'success') {
      // A terminal success has no in-flight continuation, so no installSeq
      // bump: a machine started after this success stays untouched.
      set({ active: null });
    }
  },

  clear: () => {
    installSeq++;
    stopProgressTicker();
    set({ active: null });
  },
}));
