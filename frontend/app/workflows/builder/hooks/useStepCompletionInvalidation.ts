import { useEffect, useRef } from 'react';
import { normalizeLabel } from '../utils/labelNormalizer';

// Leading+trailing throttle window for stepExecutionCompleted-driven cache
// invalidation. An actively-executing run (loops/forks, STEP_BY_STEP) streams
// hundreds of stepExecutionCompleted events per minute. Each mounted inspector
// hook (useStepData, useRunData ×3 columns, useAggregatedSteps) used to refetch
// on its own 300ms trailing DEBOUNCE - which on a never-pausing stream fans out
// into thousands of /steps/paged calls per minute and crosses the per-user
// gateway rate limit (5000/min paid, 600/min free) → real HTTP 429 cascade
// (prod 2026-06-01: 13,742 /steps/paged calls, 2,069 throttled in one minute
// while a run fired ~1,313 step-completions/min). A throttle hard-caps each
// listener to ONE invalidation per window regardless of event density, while a
// debounce only fires once per quiet gap (unbounded under bursts) and can even
// starve mid-burst (never firing until the stream pauses).
export const INVALIDATION_THROTTLE_MS = 1500;

interface StepCompletionEventDetail {
  runId?: string;
  steps?: Array<{ stepAlias?: string }>;
}

/**
 * Subscribe to the global `stepExecutionCompleted` window event for one run and
 * run a THROTTLED invalidation callback. Replaces the per-hook 300ms debounce
 * that caused the /steps/paged 429 storm.
 *
 * - `runId` - only events whose `detail.runId` matches fire the callback.
 * - `stepAlias` - when a non-empty alias is given, the callback fires only if a
 *   completed step in the event matches it (normalized). When omitted (e.g.
 *   useAggregatedSteps), any completed step for the run fires it.
 * - `enabled` - gate the subscription. Callers that require an alias pass
 *   `!!stepAlias`, preserving the prior "no alias → no listener" behaviour.
 * - `onInvalidate` - invoked on the leading edge and at most once per throttle
 *   window thereafter, with a trailing call so the final state always lands.
 *   Read through a ref so a new callback identity between renders does NOT tear
 *   down and re-create the event subscription.
 */
export function useStepCompletionInvalidation({
  runId,
  stepAlias,
  enabled = true,
  onInvalidate,
}: {
  runId: string | undefined;
  stepAlias?: string;
  enabled?: boolean;
  onInvalidate: () => void;
}): void {
  const onInvalidateRef = useRef(onInvalidate);
  useEffect(() => {
    onInvalidateRef.current = onInvalidate;
  });

  useEffect(() => {
    if (!enabled || !runId) return;

    const requireAlias = stepAlias != null && stepAlias !== '';
    const normalizedAlias = requireAlias ? normalizeLabel(stepAlias as string) : null;

    // -Infinity (not 0) so the very first event is always a leading-edge fire,
    // independent of the clock origin.
    let lastRunAt = Number.NEGATIVE_INFINITY;
    let trailingTimer: ReturnType<typeof setTimeout> | null = null;

    const fire = () => {
      lastRunAt = Date.now();
      trailingTimer = null;
      onInvalidateRef.current();
    };

    const schedule = () => {
      const elapsed = Date.now() - lastRunAt;
      if (elapsed >= INVALIDATION_THROTTLE_MS) {
        // Leading edge: first event after a quiet window invalidates at once so
        // the inspector reacts immediately to a fresh execution.
        if (trailingTimer != null) {
          clearTimeout(trailingTimer);
          trailingTimer = null;
        }
        fire();
      } else if (trailingTimer == null) {
        // Trailing edge: a single deferred fire at the end of the current window
        // collapses every other event in the burst and guarantees the final
        // state is fetched (no "stuck on the old epoch" staleness).
        trailingTimer = setTimeout(fire, INVALIDATION_THROTTLE_MS - elapsed);
      }
    };

    const handleStepCompleted = (event: Event) => {
      const detail = (event as CustomEvent<StepCompletionEventDetail>).detail || {};
      if (detail.runId !== runId) return;
      if (requireAlias) {
        const matched = detail.steps?.some(
          (step) => normalizeLabel(step?.stepAlias || '') === normalizedAlias,
        );
        if (!matched) return;
      }
      schedule();
    };

    window.addEventListener('stepExecutionCompleted', handleStepCompleted as EventListener);
    return () => {
      window.removeEventListener('stepExecutionCompleted', handleStepCompleted as EventListener);
      if (trailingTimer != null) clearTimeout(trailingTimer);
    };
    // onInvalidate is intentionally excluded from the deps - it is read via
    // onInvalidateRef so the subscription survives callback-identity changes
    // between renders.
  }, [runId, stepAlias, enabled]);
}
