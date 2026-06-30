// @vitest-environment jsdom
/**
 * The applications board reuses {@link useWorkflowBoard} via the `source` param: it must
 * hit the APPLICATION-typed board endpoint, never the workflow one (and vice-versa). This
 * is the single switch that keeps the two boards on separate data sources.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';

// vi.mock is hoisted above module-scope consts, so the fns must come from vi.hoisted().
const { getWorkflowBoardColumn, getApplicationBoardColumn } = vi.hoisted(() => ({
  getWorkflowBoardColumn: vi.fn(),
  getApplicationBoardColumn: vi.fn(),
}));

vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getWorkflowBoardColumn, getApplicationBoardColumn },
}));
vi.mock('@/lib/api/orchestrator/execution.service', () => ({ executionService: {} }));
vi.mock('@/lib/api/orchestrator/version.service', () => ({ versionService: {} }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

import { useWorkflowBoard } from '../useWorkflowBoard';

beforeEach(() => {
  getWorkflowBoardColumn.mockReset().mockResolvedValue({ column: 'draft', items: [], totalCount: 0, page: 0, size: 20 });
  getApplicationBoardColumn.mockReset().mockResolvedValue({ column: 'draft', items: [], totalCount: 0, page: 0, size: 20 });
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
});
