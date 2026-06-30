/**
 * Hook for managing AbortController lifecycle
 * Eliminates duplication of AbortController setup/cleanup pattern
 *
 * DRY: Replaces 4+ occurrences of:
 *   if (abortControllerRef.current) {
 *     abortControllerRef.current.abort();
 *   }
 *   const controller = new AbortController();
 *   abortControllerRef.current = controller;
 */

'use client';

import { useRef, useCallback, useMemo, useEffect } from 'react';

export interface UseAbortControllerReturn {
  signal: AbortSignal | undefined;
  abort: () => void;
  renew: () => AbortController;
  isAborted: () => boolean;
}

/**
 * Standardized AbortController management
 *
 * @example
 * const { signal, renew, abort } = useAbortController();
 *
 * const fetchData = async () => {
 *   const controller = renew(); // Aborts previous request automatically
 *   const response = await fetch('/api/data', { signal: controller.signal });
 * };
 *
 * // Cleanup on unmount
 * useEffect(() => () => abort(), []);
 */
export function useAbortController(): UseAbortControllerReturn {
  const controllerRef = useRef<AbortController | null>(null);

  const abort = useCallback(() => {
    if (controllerRef.current) {
      controllerRef.current.abort();
      controllerRef.current = null;
    }
  }, []);

  const renew = useCallback((): AbortController => {
    // Abort previous controller
    abort();

    // Create new controller
    const controller = new AbortController();
    controllerRef.current = controller;

    return controller;
  }, [abort]);

  const isAborted = useCallback((): boolean => {
    return controllerRef.current?.signal.aborted ?? true;
  }, []);

  // Cleanup on unmount
  useEffect(() => {
    return () => abort();
  }, [abort]);

  return useMemo(() => ({
    signal: controllerRef.current?.signal,
    abort,
    renew,
    isAborted,
  }), [abort, renew, isAborted]);
}
