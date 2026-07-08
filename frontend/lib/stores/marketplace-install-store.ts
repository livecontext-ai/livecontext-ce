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
  | 'link-required'
  | 'insufficient-credits';

export interface ActiveMarketplaceInstall {
  publication: WorkflowPublication;
  /** CE remote mode: acquired from the cloud marketplace instead of local. */
  ceMode: boolean;
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
}

interface MarketplaceInstallState {
  active: ActiveMarketplaceInstall | null;
  /**
   * Start installing a publication. Returns false (and does nothing) while
   * another install is running - one at a time; callers MUST surface the
   * refusal instead of pretending the install started. Starting a new install
   * replaces any previous terminal (success/error) state.
   */
  startInstall: (publication: WorkflowPublication, opts?: { ceMode?: boolean; inline?: boolean }) => boolean;
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

    track('app_install_started', {
      publication_id: publication.id,
      publication_type: publication.publicationType ?? null,
      is_free: isFree,
      ce_mode: ceMode,
    });

    set({
      active: { publication, ceMode, inline, status: 'installing', progress: 0, acquiredId: null, error: null },
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
    const acquireCall = ceMode
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
        const result = await acquireCall;
        if (token !== installSeq) return;

        // Wait out the visual budget so a sub-second backend never flashes.
        const remaining = Math.max(0, targetDuration - (performance.now() - startTime));
        if (remaining > 0) {
          await new Promise((r) => setTimeout(r, remaining));
        }
        if (token !== installSeq) return;

        stopProgressTicker();
        setProgress(100);
        await new Promise((r) => setTimeout(r, 300));
        if (token !== installSeq) return;

        // Result shape varies by acquire endpoint - agent → {agentId},
        // resource → {resourceId, type}, workflow/remote → {workflowId, ...}.
        const id = isAgent
          ? (result as { agentId: string }).agentId
          : isResource
            ? (result as { resourceId: string }).resourceId
            : (result as { workflowId: string }).workflowId;

        const active = get().active;
        if (!active) return;
        set({ active: { ...active, status: 'success', progress: 100, acquiredId: id } });
        track('app_install_succeeded', {
          publication_id: publication.id,
          publication_type: publication.publicationType ?? null,
          is_free: isFree,
          acquired_id: id,
          duration_ms: Math.round(performance.now() - startTime),
        });
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
        } else if (err?.status === 402) {
          status = 'insufficient-credits';
          outcome = 'insufficient_credits';
        } else {
          status = 'error';
          outcome = 'error';
        }
        set({ active: { ...active, status, error: err?.message || null } });
        track('app_install_failed', {
          publication_id: publication.id,
          publication_type: publication.publicationType ?? null,
          outcome,
          error_code: err?.code ?? (err?.status != null ? String(err.status) : null),
        });
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
