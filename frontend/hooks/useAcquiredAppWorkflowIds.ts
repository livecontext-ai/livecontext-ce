'use client';

import { useEffect, useRef, useState } from 'react';
import { publicationService } from '@/lib/api/orchestrator/publication.service';

/**
 * Returns a {@link Set} of workflow IDs that belong to applications the
 * current user has acquired. Used to decorate trigger settings cards (a
 * webhook/schedule/etc. that targets an APPLICATION-typed workflow) with an
 * "App" badge so users can distinguish ordinary workflow triggers from
 * application-bound triggers without leaving the page.
 *
 * <p>Backed by {@link publicationService.getAcquiredApplications}; one fetch
 * on mount, no polling. The set is empty on initial render and during fetch
 * so callers can render their default cards immediately and see them
 * upgrade-decorate when the response lands.
 *
 * <p>The cancellation guard prevents the late-resolving fetch from
 * setting state on an unmounted component (the StrictMode double-mount
 * case).
 */
export function useAcquiredAppWorkflowIds(): { workflowIds: Set<string>; loading: boolean } {
  const [workflowIds, setWorkflowIds] = useState<Set<string>>(() => new Set());
  const [loading, setLoading] = useState(true);
  const inFlightRef = useRef(false);

  useEffect(() => {
    if (inFlightRef.current) return;
    inFlightRef.current = true;
    let cancelled = false;
    (async () => {
      try {
        const res = await publicationService.getAcquiredApplications();
        if (cancelled) return;
        const ids = new Set<string>();
        for (const app of res.applications ?? []) {
          if (app.workflowId) ids.add(app.workflowId);
        }
        setWorkflowIds(ids);
      } catch {
        // Network/auth error - fall through to empty set so trigger cards
        // still render without the App decoration.
      } finally {
        if (!cancelled) setLoading(false);
        inFlightRef.current = false;
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  return { workflowIds, loading };
}
