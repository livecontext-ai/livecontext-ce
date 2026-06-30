'use client';

import { useEffect, useRef } from 'react';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

/**
 * Fires {@code reset()} every time the active workspace changes
 * ({@code useCurrentOrgStore.currentOrgId} flips). Subscribes once on mount
 * via the {@code subscribeWithSelector} middleware shipped with the store,
 * unsubscribes on unmount, StrictMode-safe.
 *
 * <p>Hosts the standard reset pattern for resource accumulators that live
 * outside React Query (Zustand stores with a local cache, Context providers
 * that hold arrays of fetched rows, hooks with {@code useState} lists). The
 * goal is parity with the backend strict-isolation contract: when the user
 * switches workspace, no resource list from the previous workspace must
 * leak into the new sidebar / table / panel.
 *
 * <p>The {@code reset} callback is held in a ref updated on every render,
 * so callers can pass a freshly-bound lambda each render (the most common
 * case) without staling the subscriber on the first-render value. This is
 * the canonical "subscribe once, latest handler" React idiom - without the
 * ref indirection, the subscriber would keep calling the mount-time
 * closure referencing stale state.
 *
 * <p>Cross-tab safety: {@code subscribeCrossTabOrgSwitch}
 * ({@code current-org-store.ts}) listens for {@code storage} events from
 * sibling tabs and calls {@code setCurrentOrg(...)} locally; that flips
 * {@code currentOrgId}, which fires every {@code useOrgScopedReset}
 * subscriber. Correct by construction - switching workspaces in another
 * tab also clears caches in this tab.
 *
 * @param reset zero-arg side-effect invoked on every workspace change.
 *              Typically clears local state, refs, or zustand stores.
 *
 * @example
 *   const [items, setItems] = useState<Item[]>([]);
 *   const itemsRef = useRef<Item[]>([]);
 *   useOrgScopedReset(() => { setItems([]); itemsRef.current = []; });
 */
export function useOrgScopedReset(reset: () => void): void {
  const resetRef = useRef(reset);
  // Update the ref on every render so the subscriber always reads the
  // latest closure (captures fresh state).
  useEffect(() => {
    resetRef.current = reset;
  });
  // Subscribe once on mount; the callback reads ref.current each fire.
  useEffect(() => {
    return useCurrentOrgStore.subscribe(
      (s) => s.currentOrgId,
      () => resetRef.current(),
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
}
