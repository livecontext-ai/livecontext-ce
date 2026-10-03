'use client';

import { useEffect, useRef, type DependencyList } from 'react';

/**
 * `useEffect` that does not run for the values the component MOUNTED with, only when one of
 * them later changes.
 *
 * <p>For "the filter changed, go back to the first page". Written as a plain effect that rule
 * also fires on mount, which was harmless while the page number always started at 0 and wipes
 * it now that it can start from the address: a reload on page 3 would land on page 1.
 *
 * <p>It compares against the previous values rather than skipping "the first run", because in
 * development React runs every effect twice on mount and a first-run flag is already spent by
 * the second.
 */
export function useEffectOnChange(effect: () => void, deps: DependencyList): void {
  const previous = useRef<DependencyList>(deps);
  useEffect(() => {
    const before = previous.current;
    previous.current = deps;
    if (before.length === deps.length && before.every((value, i) => Object.is(value, deps[i]))) {
      return;
    }
    effect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}
