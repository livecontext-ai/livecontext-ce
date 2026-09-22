// @vitest-environment jsdom
/**
 * The applications board reuses {@link useWorkflowBoard} via the `source` param: it must
 * hit the APPLICATION-typed board endpoint, never the workflow one (and vice-versa). This
 * is the single switch that keeps the two boards on separate data sources.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';

// vi.mock is hoisted above module-scope consts, so the fns must come from vi.hoisted().
const { getWorkflowBoardColumn, getApplicationBoardColumn, pinVersion, refreshHomeStatus } = vi.hoisted(() => ({
  getWorkflowBoardColumn: vi.fn(),
  getApplicationBoardColumn: vi.fn(),
  pinVersion: vi.fn(),
  refreshHomeStatus: vi.fn(),
}));

vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getWorkflowBoardColumn, getApplicationBoardColumn },
}));
vi.mock('@/lib/api/orchestrator/execution.service', () => ({ executionService: {} }));
vi.mock('@/lib/api/orchestrator/version.service', () => ({ versionService: { pinVersion } }));
// Dragging a card across the board pins or unpins it, which is what puts a workflow in the
// bell's Triggers rows. The real hook reaches for a QueryClient this suite has no provider
// for; what it does with the cache is pinned in useRefreshHomeStatus.freshness.test.tsx.
vi.mock('@/hooks/useHomeStatus', () => ({ useRefreshHomeStatus: () => refreshHomeStatus }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

import { useWorkflowBoard } from '../useWorkflowBoard';
import type { WorkflowBoardCard } from '@/lib/api/orchestrator/types';

beforeEach(() => {
  getWorkflowBoardColumn.mockReset().mockResolvedValue({ column: 'draft', items: [], totalCount: 0, page: 0, size: 20 });
  getApplicationBoardColumn.mockReset().mockResolvedValue({ column: 'draft', items: [], totalCount: 0, page: 0, size: 20 });
  // Production shape: the endpoint answers `success`, and the ask is gated on it.
  pinVersion.mockReset().mockResolvedValue({ success: true, pinnedVersion: 2, productionRunIdPublic: null });
  refreshHomeStatus.mockReset();
});

describe('useWorkflowBoard - source', () => {
  it("source='workflow' (default) loads from the workflow board endpoint only", async () => {
    const { result } = renderHook(() => useWorkflowBoard());
    await waitFor(() => expect(result.current.initialLoading).toBe(false));

    expect(getWorkflowBoardColumn).toHaveBeenCalledTimes(4); // one page-0 fetch per column
    expect(getApplicationBoardColumn).not.toHaveBeenCalled();
  });

  it("source='application' loads from the applications board endpoint only", async () => {
    const { result } = renderHook(() => useWorkflowBoard('application'));
    await waitFor(() => expect(result.current.initialLoading).toBe(false));

    expect(getApplicationBoardColumn).toHaveBeenCalledTimes(4);
    expect(getWorkflowBoardColumn).not.toHaveBeenCalled();
  });

  it('asks for the bell automation rows again after a drag unpins a card (regression: the Triggers tab read one step behind)', async () => {
    // Moving a card back to draft unpins it, which REMOVES it from the bell's Triggers rows
    // and from the imminent-fire ring. Nothing else invalidates that payload, so without this
    // ask the row keeps counting down to a fire that will not happen.
    const { result } = renderHook(() => useWorkflowBoard());
    await waitFor(() => expect(result.current.initialLoading).toBe(false));

    await act(async () => {
      await result.current.moveCard(
        { workflowId: 'wf-1', column: 'production' } as WorkflowBoardCard,
        'draft',
      );
    });

    expect(pinVersion).toHaveBeenCalledWith('wf-1', null);
    expect(refreshHomeStatus).toHaveBeenCalledTimes(1);
    // Unbounded: the caller just changed the data, so a freshness bound here would make the
    // ask a no-op every time, since the rows were answered moments before the round trip.
    expect(refreshHomeStatus).toHaveBeenCalledWith();
  });

  it('asks for the bell automation rows again after confirming a pin from the board', async () => {
    const { result } = renderHook(() => useWorkflowBoard());
    await waitFor(() => expect(result.current.initialLoading).toBe(false));

    // Dropping a draft card on production opens the version picker rather than pinning blind.
    await act(async () => {
      await result.current.moveCard(
        { workflowId: 'wf-2', column: 'draft' } as WorkflowBoardCard,
        'production',
      );
    });
    await act(async () => { await result.current.confirmPin(2); });

    expect(pinVersion).toHaveBeenCalledWith('wf-2', 2);
    expect(refreshHomeStatus).toHaveBeenCalledTimes(1);
    expect(refreshHomeStatus).toHaveBeenCalledWith();
  });
});
