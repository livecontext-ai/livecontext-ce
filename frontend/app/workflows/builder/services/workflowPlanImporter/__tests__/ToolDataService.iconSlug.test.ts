import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ToolDataService } from '../ToolDataService';
import { apiClient } from '@/lib/api';

/**
 * Root-cause guard for "MCP nodes show the generic API glyph instead of the
 * integration logo".
 *
 * Every WorkflowInspectorService query selects `COALESCE(a.icon_slug, 'mcp')`,
 * so an API with no icon of its own reports the literal slug `mcp`. That value
 * is TRUTHY, so the old `data.iconSlug || data.apiSlug` chains here accepted it
 * and wrote `iconSlug: "mcp"` into the node's toolData/apiData. From there it
 * flowed into the saved plan and, at publish time, into `publication.node_icons`
 * - so the canvas AND every marketplace card rendered
 * /icons/services/mcp.svg (a generic circled "API"). Because that request
 * SUCCEEDS, the MCP-logo fallback never fired either.
 *
 * This is the fix at its source: `resolveIconSlug` skips the sentinel so the
 * real apiSlug is used. NodeIcon's own sentinel guard is defence in depth for
 * rows already persisted with the bad value.
 */
describe('ToolDataService - the catalog "mcp" icon-slug sentinel never reaches node data', () => {
  beforeEach(() => {
    ToolDataService.clearCache();
    vi.restoreAllMocks();
  });
  afterEach(() => {
    ToolDataService.clearCache();
    vi.restoreAllMocks();
  });

  function mockBatch(payload: Record<string, unknown>) {
    return vi.spyOn(apiClient, 'post').mockResolvedValue(payload as never);
  }

  it('Regression - batch: the sentinel falls through to the real apiSlug', async () => {
    mockBatch({
      'slack-post-message': {
        id: 1, slug: 'slack-post-message', apiId: 7,
        apiSlug: 'slack', apiName: 'Slack',
        iconSlug: 'mcp', // catalog COALESCE default
      },
    });

    const result = await ToolDataService.fetchToolsBatch(['slack/slack-post-message']);
    const entry = result.get('slack-post-message');

    expect(entry?.toolData?.iconSlug).toBe('slack');
    expect(entry?.apiData?.iconSlug).toBe('slack');
  });

  it('a real iconSlug from the catalog is preserved verbatim', async () => {
    mockBatch({
      'sheets-append': {
        id: 2, slug: 'sheets-append', apiId: 8,
        apiSlug: 'google', apiName: 'Google Sheets',
        iconSlug: 'googlesheets',
      },
    });

    const result = await ToolDataService.fetchToolsBatch(['google/sheets-append']);

    expect(result.get('sheets-append')?.toolData?.iconSlug).toBe('googlesheets');
  });

  it('falls back to the tool slug when both the sentinel and the apiSlug are unusable', async () => {
    mockBatch({
      'mystery-tool': { id: 3, slug: 'mystery-tool', iconSlug: 'mcp' },
    });

    const result = await ToolDataService.fetchToolsBatch(['mystery-tool']);

    // apiData is omitted without an apiSlug, but toolData still gets a usable
    // slug rather than the sentinel.
    expect(result.get('mystery-tool')?.toolData?.iconSlug).toBe('mystery-tool');
  });

  it('a slug that merely contains "mcp" is not mistaken for the sentinel', async () => {
    mockBatch({
      'run-job': {
        id: 4, slug: 'run-job', apiId: 9, apiSlug: 'acme', iconSlug: 'mcpserver',
      },
    });

    const result = await ToolDataService.fetchToolsBatch(['acme/run-job']);

    expect(result.get('run-job')?.toolData?.iconSlug).toBe('mcpserver');
  });
});
