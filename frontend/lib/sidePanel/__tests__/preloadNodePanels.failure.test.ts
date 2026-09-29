// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';

/**
 * A prefetch that fails must stay silent. The click that needs the panel imports it
 * again and reports a real failure there; an unhandled rejection from the prefetch
 * would only be noise (and fails a vitest run whose environment tears down mid-fetch).
 */
vi.mock('@/components/app/AgentPanelContent', () => { throw new Error('chunk failed to load'); });
vi.mock('@/components/app/DataSourcePanelContent', () => ({ DataSourcePanelContent: () => null }));
vi.mock('@/components/app/FileDetailView', () => ({ FileDetailView: () => null }));
vi.mock('@/app/workflows/builder/components/inspector/StorageExplorerTab', () => ({ StorageExplorerTab: () => null }));

import { preloadNodePanels, resetPreloadNodePanelsForTest } from '../preloadNodePanels';

describe('preloadNodePanels when a chunk fails', () => {
  it('swallows the failure instead of leaving an unhandled rejection', async () => {
    const unhandled = vi.fn();
    process.on('unhandledRejection', unhandled);
    vi.stubGlobal('requestIdleCallback', (cb: () => void) => { cb(); return 1; });
    resetPreloadNodePanelsForTest();
    try {
      preloadNodePanels();
      await vi.dynamicImportSettled();
      await new Promise((resolve) => setTimeout(resolve, 0));

      expect(unhandled).not.toHaveBeenCalled();
    } finally {
      process.off('unhandledRejection', unhandled);
      vi.unstubAllGlobals();
    }
  });
});
