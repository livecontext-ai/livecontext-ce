/**
 * Fetch, in the background, the code of the side panels a canvas node can open.
 *
 * Those panels are `next/dynamic` in `useNodeContextualButtons` and
 * `openFilesPanel`, so they stay out of every canvas that cannot open them (the
 * landing hero renders real nodes and has no side panel). Where a panel CAN open,
 * loading its code on the first click would show it blank for a moment, so the
 * hook calls this once a side panel is available: by the time anyone clicks, the
 * modules are in the cache and the panel renders at once, as it did when it was a
 * static import.
 *
 * Runs once per page load, when the browser is idle.
 */
let started = false;

export function preloadNodePanels(): void {
  if (started || typeof window === 'undefined') return;
  started = true;
  // A failed prefetch is not an error: the click that needs the panel imports it again
  // and surfaces any real failure there. Swallowing it here also keeps a page (or a
  // test environment) that goes away mid-fetch from reporting an unhandled rejection.
  const ignore = () => {};
  const load = () => {
    import('@/components/app/AgentPanelContent').catch(ignore);
    import('@/components/app/DataSourcePanelContent').catch(ignore);
    import('@/components/app/FileDetailView').catch(ignore);
    import('@/app/workflows/builder/components/inspector/StorageExplorerTab').catch(ignore);
  };
  if (typeof window.requestIdleCallback === 'function') window.requestIdleCallback(load, { timeout: 3000 });
  else window.setTimeout(load, 1);
}

/** Test seam: lets each test start from a page that has preloaded nothing. */
export function resetPreloadNodePanelsForTest(): void {
  started = false;
}
