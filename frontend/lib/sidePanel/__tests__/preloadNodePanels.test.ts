// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The side panels a node can open are lazy (so the landing hero does not ship
 * them), and preloaded where a panel can actually open (so the first open in the
 * app is not blank). This pins the preloader: it fetches all four panel modules,
 * once, when the browser is idle.
 */
const loaded = vi.hoisted(() => ({ agent: 0, dataSource: 0, fileDetail: 0, explorer: 0 }));
vi.mock('@/components/app/AgentPanelContent', () => { loaded.agent += 1; return { AgentPanelContent: () => null }; });
vi.mock('@/components/app/DataSourcePanelContent', () => { loaded.dataSource += 1; return { DataSourcePanelContent: () => null }; });
vi.mock('@/components/app/FileDetailView', () => { loaded.fileDetail += 1; return { FileDetailView: () => null }; });
vi.mock('@/app/workflows/builder/components/inspector/StorageExplorerTab', () => { loaded.explorer += 1; return { StorageExplorerTab: () => null }; });

import { preloadNodePanels, resetPreloadNodePanelsForTest } from '../preloadNodePanels';

describe('preloadNodePanels', () => {
  let idle: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    resetPreloadNodePanelsForTest();
    idle = vi.fn((cb: () => void) => { cb(); return 1; });
    vi.stubGlobal('requestIdleCallback', idle);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('fetches every panel a node can open, when the browser is idle', async () => {
    preloadNodePanels();
    await vi.dynamicImportSettled();

    expect(idle).toHaveBeenCalledTimes(1);
    expect(loaded).toEqual({ agent: 1, dataSource: 1, fileDetail: 1, explorer: 1 });
  });

  it('schedules the work once per page load, however many nodes ask', () => {
    preloadNodePanels();
    preloadNodePanels();
    preloadNodePanels();

    expect(idle).toHaveBeenCalledTimes(1);
  });

  it('falls back to a timer where requestIdleCallback does not exist (Safari)', () => {
    vi.stubGlobal('requestIdleCallback', undefined);
    const timer = vi.spyOn(window, 'setTimeout');

    preloadNodePanels();

    expect(timer).toHaveBeenCalledTimes(1);
    timer.mockRestore();
  });
});
