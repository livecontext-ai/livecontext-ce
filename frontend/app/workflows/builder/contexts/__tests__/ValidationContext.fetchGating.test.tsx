/**
 * @vitest-environment jsdom
 *
 * Regression: ValidationProvider mounted over an EMPTY canvas must not fire its
 * tenant data queries (/workflows/capabilities via useFeatureCapabilities and
 * /credentials via getAllCredentials).
 *
 * Bug (CE-MARKETPLACE-PREVIEW-020): AgentFleetCanvas always wraps its canvas in
 * <ValidationProvider nodes={[]} edges={[]}> - including on marketplace snapshot
 * preview pages, whose contract is "render from the frozen snapshot, zero live
 * tenant calls". When the optional-component availability fetch was added to
 * ValidationContext, every snapshot preview started leaking a live
 * GET /workflows/capabilities. With no nodes there is nothing to validate, so
 * neither query may run; both must run once a node is present.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import * as React from 'react';
import { render, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Node, Edge } from 'reactflow';
import { ValidationProvider } from '../ValidationContext';
import type { BuilderNodeData } from '../../types';

const getFeatureCapabilities = vi.fn(async () => ({
  screenshotRenderer: true,
  browserAgent: true,
  webSearch: true,
}));
const getAllCredentials = vi.fn(async () => []);

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isLoading: false, isAuthenticated: true }),
}));

vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: {
    getFeatureCapabilities: () => getFeatureCapabilities(),
  },
}));

vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getAllCredentials: () => getAllCredentials(),
  },
}));

// Keep validation itself out of scope: this test pins the FETCH gating only.
vi.mock('../../services/validation/WorkflowValidator', () => ({
  WorkflowValidator: {
    validate: vi.fn(() => ({
      isValid: true,
      issuesByElement: {},
      globalIssues: [],
      errorCount: 0,
      warningCount: 0,
    })),
    clearCache: vi.fn(),
  },
}));

function renderProvider(nodes: Node<BuilderNodeData>[]) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ValidationProvider nodes={nodes} edges={[] as Edge[]}>
        <div data-testid="child" />
      </ValidationProvider>
    </QueryClientProvider>,
  );
}

const oneNode: Node<BuilderNodeData>[] = [
  {
    id: 'n1',
    type: 'mcpNode',
    position: { x: 0, y: 0 },
    data: { label: 'My Tool' } as BuilderNodeData,
  },
];

describe('ValidationContext tenant-fetch gating (empty canvas)', () => {
  beforeEach(() => {
    getFeatureCapabilities.mockClear();
    getAllCredentials.mockClear();
  });

  it('regression: nodes=[] fires NO capabilities and NO credentials fetch (snapshot preview contract)', async () => {
    renderProvider([]);

    // Give any (incorrectly) enabled query a tick to fire before asserting.
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(getFeatureCapabilities).not.toHaveBeenCalled();
    expect(getAllCredentials).not.toHaveBeenCalled();
  });

  it('fetches capabilities and credentials once nodes are present (builder warnings still work)', async () => {
    renderProvider(oneNode);

    await waitFor(() => {
      expect(getFeatureCapabilities).toHaveBeenCalled();
      expect(getAllCredentials).toHaveBeenCalled();
    });
  });

  it('a canvas that starts empty and then loads nodes starts fetching at that point', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const { rerender } = render(
      <QueryClientProvider client={queryClient}>
        <ValidationProvider nodes={[]} edges={[]}>
          <div />
        </ValidationProvider>
      </QueryClientProvider>,
    );
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(getFeatureCapabilities).not.toHaveBeenCalled();

    rerender(
      <QueryClientProvider client={queryClient}>
        <ValidationProvider nodes={oneNode} edges={[]}>
          <div />
        </ValidationProvider>
      </QueryClientProvider>,
    );
    await waitFor(() => {
      expect(getFeatureCapabilities).toHaveBeenCalled();
      expect(getAllCredentials).toHaveBeenCalled();
    });
  });
});
