// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: api }));

import { workflowService } from '../workflow.service';

/**
 * What the node-type filter actually puts on the wire, and what it reads back.
 *
 * <p>The component tests assert the options OBJECT handed to a mocked service,
 * so they pin the caller, not the request. The server reads `nodeTypes` as a
 * single comma-separated `@RequestParam String` and `includeNodeTypeFacets` as
 * a boolean flag: a change of shape here - an array serialized as repeated
 * params, a missing flag - returns a perfectly successful page with the filter
 * silently ignored, or with no options for the picker to offer.
 */
beforeEach(() => {
  vi.clearAllMocks();
  api.get.mockResolvedValue({ workflows: [], count: 0, totalCount: 0, page: 0, size: 25 });
});

function paramsOf(): Record<string, string> {
  return api.get.mock.calls.at(-1)![1].params;
}

describe('getWorkflowsPage: the node-type filter on the wire', () => {
  it('sends the selected types as ONE comma-separated value', async () => {
    await workflowService.getWorkflowsPage({ nodeTypes: ['mcp:gmail', 'core:loop'] });

    expect(paramsOf().nodeTypes).toBe('mcp:gmail,core:loop');
  });

  it('omits the parameter entirely when nothing is selected', async () => {
    // An empty string would reach the server as a present-but-blank filter.
    await workflowService.getWorkflowsPage({ nodeTypes: [] });

    expect(paramsOf()).not.toHaveProperty('nodeTypes');
  });

  it('omits it when the caller passes no node types at all', async () => {
    await workflowService.getWorkflowsPage({});

    expect(paramsOf()).not.toHaveProperty('nodeTypes');
  });

  it('asks for the facets only when the caller wants them', async () => {
    // The node pickers read this same endpoint and have no filter UI; counting
    // walks every workflow's plan, so it is opt-in.
    await workflowService.getWorkflowsPage({ includeNodeTypeFacets: true });
    expect(paramsOf().includeNodeTypeFacets).toBe('true');

    await workflowService.getWorkflowsPage({});
    expect(paramsOf()).not.toHaveProperty('includeNodeTypeFacets');
  });

  it('reads the facets back off the page envelope', async () => {
    api.get.mockResolvedValue({
      workflows: [],
      count: 0,
      totalCount: 0,
      page: 0,
      size: 25,
      nodeTypeFacets: [{ value: 'mcp:gmail', count: 3 }],
    });

    const page = await workflowService.getWorkflowsPage({ includeNodeTypeFacets: true });

    expect(page.nodeTypeFacets).toEqual([{ value: 'mcp:gmail', count: 3 }]);
  });

  it('answers an empty option list when the server sends none, rather than undefined', async () => {
    // The picker maps over this; undefined would throw where "no options yet" is
    // a perfectly normal answer.
    const page = await workflowService.getWorkflowsPage({});

    expect(page.nodeTypeFacets).toEqual([]);
  });
});
