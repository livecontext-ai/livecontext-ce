/**
 * Hook for operation timeout warnings
 * Eliminates duplication of setTimeout/clearTimeout pattern for warnings
 *
 * DRY: Replaces 4+ occurrences of:
 *   const timeoutRef = useRef<NodeJS.Timeout | null>(null);
 *   if (timeoutRef.current) clearTimeout(timeoutRef.current);
 *   timeoutRef.current = setTimeout(() => { warn(...) }, ms);
 */

'use client';

import { useRef, useCallback, useMemo, useEffect } from 'react';

export interface UseTimeoutWarningReturn {
  start: () => void;
  clear: () => void;
  isActive: () => boolean;
}

/**
 * Standardized timeout warning management
 *
 * @param timeoutMs - Timeout duration in milliseconds
 * @param onTimeout - Callback to execute on timeout
 * @param warningMessage - Optional warning message to log
 *
 * @example
 * const { start, clear } = useTimeoutWarning(
 *   60000,
 *   () => setLoadingTimeout(true),
 *   'Loading messages timeout'
 * );
 *
 * const loadData = async () => {
 *   start();
 *   try {
 *     await api.load();
 *     clear();
 *   } catch (err) {
 *     clear();
 *   }
 * };
 */
export function useTimeoutWarning(
  timeoutMs: number,
  onTimeout: () => void,
  warningMessage?: string
): UseTimeoutWarningReturn {
  const timeoutRef = useRef<NodeJS.Timeout | null>(null);

  // Latest-ref pattern. Without this, an inline-lambda `onTimeout` (the canonical
  // call site shape - `useTimeoutWarning(60000, () => setLoadingTimeout(true))`)
  // gets a fresh identity on every parent render, which forces `start` and the
  // outer `useMemo` to return fresh objects. Any consumer that puts the returned
  // bundle in a hook dep array (e.g. `useMessages` → `loadMessages` useCallback
  // deps → `ConversationPanelContent` useEffect deps) sees their identity flip
  // every render, retriggering the effect → setState → re-render → infinite
  // fetch loop. Surfaced as ~30 GET /messages/page calls per second after the
  // pagination commit (ce78e7e09) wired `useMessages` into the right-side panel.
  const onTimeoutRef = useRef(onTimeout);
  onTimeoutRef.current = onTimeout;

  const clear = useCallback(() => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
  }, []);

  const start = useCallback(() => {
    // Clear any existing timeout
    clear();

    // Set new timeout
    timeoutRef.current = setTimeout(() => {
      const message = warningMessage || `⚠️ Operation timeout after ${timeoutMs}ms`;
      console.warn(message);
      onTimeoutRef.current();
      timeoutRef.current = null;
    }, timeoutMs);
  }, [timeoutMs, warningMessage, clear]);

  const isActive = useCallback(() => {
    return timeoutRef.current !== null;
  }, []);

  // Cleanup on unmount
  useEffect(() => {
    return () => clear();
  }, [clear]);

  return useMemo(() => ({
    start,
    clear,
    isActive,
  }), [start, clear, isActive]);
}
