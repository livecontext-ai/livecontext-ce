'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Minimal server-status shape a bundle "sync now" surface must expose. The
 * server owns the RUNNING flag (in-memory, single-pod CE), so this survives a
 * page navigation: on mount the hook reads the status and, if a sync is still
 * in flight, resumes the loading indicator and keeps polling until it ends.
 */
export interface BundleSyncStatusLike {
  running?: boolean;
  startedAt?: string | null;
}

export interface BundleSyncApi<S extends BundleSyncStatusLike> {
  getStatus: () => Promise<S>;
  syncNow: () => Promise<S>;
  syncCancel: () => Promise<S>;
}

export interface UseBundleSyncResult<S> {
  /** True while a sync is in flight (resumes across navigation). */
  running: boolean;
  /** Latest status row (updated on every poll / action). */
  status: S | null;
  /** Non-null after a start/stop that failed, cleared on the next action. */
  error: string | null;
  /** Trigger a sync now (no-op server-side if one is already running). */
  start: () => Promise<void>;
  /** Best-effort stop of the in-flight sync. */
  stop: () => Promise<void>;
}

const POLL_MS = 2000;

/**
 * Drives a bundle "sync now" control off SERVER state instead of local React
 * state, so the loading indicator is not lost when the user leaves and returns
 * to the page. Reusable across the model / skill / API-catalog bundle surfaces:
 * pass that surface's {getStatus, syncNow, syncCancel} calls.
 *
 * Polling is interval-based with a ref guard (no raw fetch-in-effect loop): it
 * runs only while `running` is true and is always cleared on unmount.
 */
export function useBundleSync<S extends BundleSyncStatusLike>(
  api: BundleSyncApi<S>,
): UseBundleSyncResult<S> {
  const [status, setStatus] = useState<S | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Keep the latest api in a ref so the poll effect never re-subscribes when the
  // caller passes a fresh object literal each render.
  const apiRef = useRef(api);
  apiRef.current = api;

  const apply = useCallback((s: S) => {
    setStatus(s);
    setRunning(Boolean(s.running));
  }, []);

  // Initial status read (resume a sync started before this mount).
  useEffect(() => {
    let cancelled = false;
    apiRef.current
      .getStatus()
      .then((s) => { if (!cancelled) apply(s); })
      .catch(() => { /* status read is best-effort; leave idle */ });
    return () => { cancelled = true; };
  }, [apply]);

  // Poll only while running; clears itself the moment the server reports idle.
  useEffect(() => {
    if (!running) return;
    let cancelled = false;
    const id = setInterval(() => {
      apiRef.current
        .getStatus()
        .then((s) => { if (!cancelled) apply(s); })
        .catch(() => { /* transient; next tick retries */ });
    }, POLL_MS);
    return () => { cancelled = true; clearInterval(id); };
  }, [running, apply]);

  const start = useCallback(async () => {
    setError(null);
    setRunning(true); // optimistic: show the spinner immediately
    try {
      apply(await apiRef.current.syncNow());
    } catch (e) {
      setRunning(false);
      setError(e instanceof Error ? e.message : 'sync failed');
    }
  }, [apply]);

  const stop = useCallback(async () => {
    setError(null);
    try {
      apply(await apiRef.current.syncCancel());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'stop failed');
    } finally {
      setRunning(false); // stop spinning even if the cancel call itself failed
    }
  }, [apply]);

  return { running, status, error, start, stop };
}
