/**
 * @vitest-environment jsdom
 *
 * Tests for {@code useMissingCredentials}.
 *
 * Focus: the wiring between TanStack Query and the pure extractor - disabled
 * states (anonymous preview), refetch behavior after wizard completion, and
 * graceful empty-state defaults.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as React from 'react';
import { useMissingCredentials } from '../useMissingCredentials';

// Mock the network deps so the hook doesn't try to hit the real API.
vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getAllCredentials: vi.fn(async () => []),
  },
}));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: {
    getWorkflow: vi.fn(async () => ({ id: 'wf-1', plan: { mcps: [], cores: [] } })),
  },
}));
vi.mock('@/app/workflows/builder/services/workflowPlanImporter/ToolDataService', () => ({
  ToolDataService: {
    fetchToolsBatch: vi.fn(async () => new Map()),
  },
}));

import { orchestratorApi } from '@/lib/api/orchestrator';
import { workflowService } from '@/lib/api/orchestrator/workflow.service';

function wrapper() {
  // Each test gets a fresh QueryClient so caches don't bleed across cases.
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

describe('useMissingCredentials', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('Returns EMPTY_RESULT and fires no fetches when workflowId is undefined (anonymous preview)', async () => {
    const { result } = renderHook(
      () => useMissingCredentials({ workflowId: undefined }),
      { wrapper: wrapper() }
    );

    expect(result.current.count).toBe(0);
    expect(result.current.wizardable).toEqual([]);
    expect(result.current.manual).toEqual([]);
    expect(result.current.isLoading).toBe(false);
    expect(orchestratorApi.getAllCredentials).not.toHaveBeenCalled();
    expect(workflowService.getWorkflow).not.toHaveBeenCalled();
  });

  it('Returns EMPTY_RESULT when enabled=false even if workflowId is set (anonymous preview)', async () => {
    const { result } = renderHook(
      () => useMissingCredentials({ workflowId: 'wf-1', enabled: false }),
      { wrapper: wrapper() }
    );

    expect(result.current.count).toBe(0);
    expect(orchestratorApi.getAllCredentials).not.toHaveBeenCalled();
  });

  it('Skips the getWorkflow round-trip when planSnapshot is provided', async () => {
    const planSnapshot = { mcps: [], cores: [], triggers: [] };
    const { result } = renderHook(
      () => useMissingCredentials({ workflowId: 'wf-1', planSnapshot }),
      { wrapper: wrapper() }
    );

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    expect(workflowService.getWorkflow).not.toHaveBeenCalled();
    expect(result.current.count).toBe(0);
  });

  it('refetch invalidates the user-credentials query so a wizard completion is reflected', async () => {
    const planSnapshot = { mcps: [], cores: [], triggers: [] };
    const { result } = renderHook(
      () => useMissingCredentials({ workflowId: 'wf-1', planSnapshot }),
      { wrapper: wrapper() }
    );

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });
    expect(orchestratorApi.getAllCredentials).toHaveBeenCalledTimes(1);

    await result.current.refetch();
    // After invalidation, TanStack re-fires the query.
    await waitFor(() => {
      expect(orchestratorApi.getAllCredentials).toHaveBeenCalledTimes(2);
    });
  });
});
