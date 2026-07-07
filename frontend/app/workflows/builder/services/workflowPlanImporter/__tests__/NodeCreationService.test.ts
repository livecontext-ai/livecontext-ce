import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NodeCreationService } from '../NodeCreationService';
import { ToolDataService } from '../ToolDataService';

/**
 * Tests for NodeCreationService - data processing node input fallback.
 *
 * When the LLM/backend stores 'input' in the typed config (e.g., removeDuplicates.input,
 * summarize.input) rather than params.input, the plan importer must still pick it up.
 */
describe('NodeCreationService - Data Processing Input Fallback', () => {
  const defaultPosition = { x: 0, y: 0 };

  // Helper to create a minimal plan with a single core node and extract the created node.
  // createCoreNodesInline is private static - access via bracket notation.
  function createCoreNode(coreNode: any) {
    const result = (NodeCreationService as any).createCoreNodesInline(
      [coreNode],
      [],    // planSteps (mcps) - not needed for data processing nodes
      100,   // startX
      100,   // startY
    );
    return result.nodes[0];
  }

  describe('filter', () => {
    it('should read input from params.input', () => {
      const node = createCoreNode({
        type: 'filter',
        label: 'My Filter',
        filter: { conditions: [], mode: 'and' },
        params: { input: '{{trigger:data.output.items}}' },
      });
      expect(node.data.filterInput).toBe('{{trigger:data.output.items}}');
    });

    it('should fallback to filter.input when params.input absent', () => {
      const node = createCoreNode({
        type: 'filter',
        label: 'My Filter',
        filter: { conditions: [], mode: 'and', input: '{{core:code.output.items}}' },
      });
      expect(node.data.filterInput).toBe('{{core:code.output.items}}');
    });

    it('should fallback to top-level input', () => {
      const node = createCoreNode({
        type: 'filter',
        label: 'My Filter',
        filter: { conditions: [], mode: 'and' },
        input: '{{core:step.output.data}}',
      });
      expect(node.data.filterInput).toBe('{{core:step.output.data}}');
    });

    it('should return empty string when no input anywhere', () => {
      const node = createCoreNode({
        type: 'filter',
        label: 'My Filter',
        filter: { conditions: [], mode: 'and' },
      });
      expect(node.data.filterInput).toBe('');
    });
  });

  describe('sort', () => {
    it('should read input from params.input', () => {
      const node = createCoreNode({
        type: 'sort',
        label: 'My Sort',
        sort: { fields: [{ field: 'name', direction: 'asc' }] },
        params: { input: '{{core:filter.output.matched}}' },
      });
      expect(node.data.sortInput).toBe('{{core:filter.output.matched}}');
    });

    it('should fallback to sort.input', () => {
      const node = createCoreNode({
        type: 'sort',
        label: 'My Sort',
        sort: { fields: [], input: '{{core:filter.output.matched}}' },
      });
      expect(node.data.sortInput).toBe('{{core:filter.output.matched}}');
    });
  });

  describe('limit', () => {
    it('should read input from params.input', () => {
      const node = createCoreNode({
        type: 'limit',
        label: 'Take 5',
        limit: { count: 5, from: 'first' },
        params: { input: '{{core:sort.output.items}}' },
      });
      expect(node.data.limitInput).toBe('{{core:sort.output.items}}');
    });

    it('should fallback to limit.input', () => {
      const node = createCoreNode({
        type: 'limit',
        label: 'Take 5',
        limit: { count: 5, from: 'first', input: '{{core:sort.output.items}}' },
      });
      expect(node.data.limitInput).toBe('{{core:sort.output.items}}');
    });
  });

  describe('remove_duplicates', () => {
    it('should read input from params.input', () => {
      const node = createCoreNode({
        type: 'remove_duplicates',
        label: 'Dedup',
        removeDuplicates: { fields: ['name'], keep: 'first' },
        params: { input: '{{core:limit.output.items}}' },
      });
      expect(node.data.dedupInput).toBe('{{core:limit.output.items}}');
    });

    it('should fallback to removeDuplicates.input', () => {
      const node = createCoreNode({
        type: 'remove_duplicates',
        label: 'Dedup',
        removeDuplicates: { fields: ['name'], keep: 'first', input: '{{core:limit.output.items}}' },
      });
      expect(node.data.dedupInput).toBe('{{core:limit.output.items}}');
    });

    it('should return empty string when no input', () => {
      const node = createCoreNode({
        type: 'remove_duplicates',
        label: 'Dedup',
        removeDuplicates: { fields: ['name'], keep: 'first' },
      });
      expect(node.data.dedupInput).toBe('');
    });
  });

  describe('summarize', () => {
    it('should read input from params.input', () => {
      const node = createCoreNode({
        type: 'summarize',
        label: 'Stats',
        summarize: { aggregations: [], groupBy: [] },
        params: { input: '{{core:dedup.output.items}}' },
      });
      expect(node.data.summarizeInput).toBe('{{core:dedup.output.items}}');
    });

    it('should fallback to summarize.input', () => {
      const node = createCoreNode({
        type: 'summarize',
        label: 'Stats',
        summarize: { aggregations: [], groupBy: [], input: '{{core:dedup.output.items}}' },
      });
      expect(node.data.summarizeInput).toBe('{{core:dedup.output.items}}');
    });

    it('should return empty string when no input', () => {
      const node = createCoreNode({
        type: 'summarize',
        label: 'Stats',
        summarize: { aggregations: [], groupBy: [] },
      });
      expect(node.data.summarizeInput).toBe('');
    });
  });

  describe('priority order', () => {
    it('should prefer params.input over typed config input', () => {
      const node = createCoreNode({
        type: 'remove_duplicates',
        label: 'Dedup',
        removeDuplicates: { fields: ['name'], keep: 'first', input: '{{from_config}}' },
        params: { input: '{{from_params}}' },
        input: '{{from_top_level}}',
      });
      expect(node.data.dedupInput).toBe('{{from_params}}');
    });

    it('should prefer typed config over top-level input', () => {
      const node = createCoreNode({
        type: 'summarize',
        label: 'Stats',
        summarize: { aggregations: [], groupBy: [], input: '{{from_config}}' },
        input: '{{from_top_level}}',
      });
      expect(node.data.summarizeInput).toBe('{{from_config}}');
    });
  });
});

/**
 * Tests for the user_approval round-trip passthrough.
 *
 * When an agent writes `approval: { approverRoles, requiredApprovals, timeoutMs, contextTemplate }`,
 * the frontend importer must preserve those fields on node.data so the subsequent exporter can
 * re-emit them verbatim. Without this, the first save round-trip silently drops
 * approverRoles/requiredApprovals/contextTemplate.
 */
describe('NodeCreationService - user_approval round-trip passthrough', () => {
  function createApprovalNode(coreNode: any) {
    const result = (NodeCreationService as any).createCoreNodesInline(
      [coreNode],
      [],
      100,
      100,
    );
    return result.nodes[0];
  }

  it('preserves approverRoles when agent provides them', () => {
    const node = createApprovalNode({
      type: 'approval',
      label: 'Manager Review',
      approval: { approverRoles: ['manager', 'admin'], requiredApprovals: 2, timeoutMs: 3600000 },
    });
    expect(node.data.approverRoles).toEqual(['manager', 'admin']);
    expect(node.data.requiredApprovals).toBe(2);
    expect(node.data.approvalTimeoutMs).toBe(3600000);
  });

  it('preserves contextTemplate written by the agent in the approval block (round-trip)', () => {
    const node = createApprovalNode({
      type: 'approval',
      label: 'Review',
      approval: {
        approverRoles: ['manager'],
        requiredApprovals: 1,
        timeoutMs: 1000,
        contextTemplate: 'Approve refund of {{trigger:form.output.amount}}?',
      },
    });
    expect(node.data.approvalContextTemplate).toBe('Approve refund of {{trigger:form.output.amount}}?');
  });

  it('omits approvalContextTemplate when the approval block has a blank contextTemplate', () => {
    const node = createApprovalNode({
      type: 'approval',
      label: 'Review',
      approval: { approverRoles: ['manager'], requiredApprovals: 1, timeoutMs: 1000, contextTemplate: '   ' },
    });
    expect(node.data.approvalContextTemplate).toBeUndefined();
  });

  it('omits approverRoles when plan has no approval block', () => {
    const node = createApprovalNode({
      type: 'approval',
      label: 'Generic Review',
    });
    expect(node.data.approverRoles).toBeUndefined();
    expect(node.data.requiredApprovals).toBeUndefined();
    expect(node.data.approvalContextTemplate).toBeUndefined();
  });

  it('filters non-string entries out of approverRoles', () => {
    const node = createApprovalNode({
      type: 'approval',
      label: 'Review',
      approval: { approverRoles: ['manager', 42, null, 'admin'], requiredApprovals: 1 },
    });
    expect(node.data.approverRoles).toEqual(['manager', 'admin']);
  });

  it('omits approverRoles when the array is empty after filtering', () => {
    const node = createApprovalNode({
      type: 'approval',
      label: 'Review',
      approval: { approverRoles: [], requiredApprovals: 1, timeoutMs: 500 },
    });
    expect(node.data.approverRoles).toBeUndefined();
    expect(node.data.requiredApprovals).toBe(1);
    expect(node.data.approvalTimeoutMs).toBe(500);
  });

  it('ignores non-numeric requiredApprovals from malformed plans', () => {
    const node = createApprovalNode({
      type: 'approval',
      label: 'Review',
      approval: { approverRoles: ['manager'], requiredApprovals: '2', timeoutMs: 100 },
    });
    expect(node.data.requiredApprovals).toBeUndefined();
    expect(node.data.approverRoles).toEqual(['manager']);
  });

  it('maps a full approval.delegation block to approvalDelegation (round-trip)', () => {
    const node = createApprovalNode({
      type: 'approval',
      label: 'Review',
      approval: {
        requiredApprovals: 1,
        delegation: {
          channel: 'telegram',
          credentialId: 123,
          chatId: '{{trigger:form.output.chat_id}}',
          messageTemplate: 'Approve {{trigger:form.output.amount}}?',
          allowedUserIds: ['12345678', '87654321'],
        },
      },
    });
    expect(node.data.approvalDelegation).toEqual({
      channel: 'telegram',
      credentialId: 123,
      chatId: '{{trigger:form.output.chat_id}}',
      messageTemplate: 'Approve {{trigger:form.output.amount}}?',
      allowedUserIds: ['12345678', '87654321'],
    });
  });

  it('omits approvalDelegation when the plan has no delegation block', () => {
    const node = createApprovalNode({
      type: 'approval',
      label: 'Review',
      approval: { requiredApprovals: 1 },
    });
    expect(node.data.approvalDelegation).toBeUndefined();
  });

  it('omits approvalDelegation when delegation has a blank channel', () => {
    const node = createApprovalNode({
      type: 'approval',
      label: 'Review',
      approval: { delegation: { channel: '  ', credentialId: 123, chatId: '-100' } },
    });
    expect(node.data.approvalDelegation).toBeUndefined();
  });

  it('drops blank/malformed optional delegation fields but keeps the channel', () => {
    const node = createApprovalNode({
      type: 'approval',
      label: 'Review',
      approval: {
        delegation: {
          channel: 'telegram',
          credentialId: '123',
          chatId: '   ',
          messageTemplate: '',
          allowedUserIds: ['', 42, null, '12345678'],
        },
      },
    });
    expect(node.data.approvalDelegation).toEqual({
      channel: 'telegram',
      allowedUserIds: ['12345678'],
    });
  });
});

/**
 * Tests for the chat-trigger chatMatch round-trip.
 *
 * Chat triggers carry `chatMatch: { type, value, caseSensitive }` at the TRIGGER NODE
 * TOP-LEVEL (mirroring frontend exporter triggerProcessor.buildChatMatchConfig).
 * The importer must translate backend snake_case types into the frontend's camelCase
 * matchType, preserve value + caseSensitive, and detect the "slash-prefix = command" shape.
 */
describe('NodeCreationService - chat trigger chatMatch round-trip', () => {
  async function createTriggerNode(trigger: any) {
    const result = await (NodeCreationService as any).createTriggerNodesInline(
      [trigger],
      'tenant-1',
      100,
      100,
    );
    return result.nodes[0];
  }

  it('maps starts_with → startsWith with preserved value and caseSensitive', async () => {
    const node = await createTriggerNode({
      type: 'chat',
      label: 'Help Chat',
      chatMatch: { type: 'starts_with', value: 'help', caseSensitive: true },
    });
    expect(node.data.chatTriggerData).toBeDefined();
    expect(node.data.chatTriggerData.matchType).toBe('startsWith');
    expect(node.data.chatTriggerData.pattern).toBe('help');
    expect(node.data.chatTriggerData.caseSensitive).toBe(true);
  });

  it('recognises slash-prefixed starts_with as a command', async () => {
    const node = await createTriggerNode({
      type: 'chat',
      label: 'Slash',
      chatMatch: { type: 'starts_with', value: '/run', caseSensitive: false },
    });
    expect(node.data.chatTriggerData.matchType).toBe('command');
    expect(node.data.chatTriggerData.pattern).toBe('run');
    expect(node.data.chatTriggerData.caseSensitive).toBe(false);
  });

  it('accepts frontend camelCase alias "startsWith"', async () => {
    const node = await createTriggerNode({
      type: 'chat',
      label: 'Alias Chat',
      chatMatch: { type: 'startsWith', value: 'hi' },
    });
    expect(node.data.chatTriggerData.matchType).toBe('startsWith');
    expect(node.data.chatTriggerData.pattern).toBe('hi');
  });

  it('falls back to matchType "any" with empty pattern when chatMatch missing', async () => {
    const node = await createTriggerNode({ type: 'chat', label: 'Any Chat' });
    expect(node.data.chatTriggerData.matchType).toBe('any');
    expect(node.data.chatTriggerData.pattern).toBe('');
    expect(node.data.chatTriggerData.caseSensitive).toBe(false);
  });

  it('preserves regex type and pattern verbatim', async () => {
    const node = await createTriggerNode({
      type: 'chat',
      label: 'Regex Chat',
      chatMatch: { type: 'regex', value: '^hello.*$' },
    });
    expect(node.data.chatTriggerData.matchType).toBe('regex');
    expect(node.data.chatTriggerData.pattern).toBe('^hello.*$');
  });
});

/**
 * Tests for the error trigger round-trip.
 *
 * Regression: an error trigger created in the builder, saved, then re-imported
 * was being mis-classified as a table (datasource) trigger because:
 *   1. `error` was missing from the isDatasourceTrigger exclusion list, so the
 *      importer hit ToolDataService.fetchDataSourceData with the parent workflow UUID
 *      and got back a (non-empty) datasource result.
 *   2. The dataId fell into the `else` branch and got `tables-trigger-<uuid>-…`.
 *   3. The frontend then rendered the node as a table trigger.
 *
 * These tests guard the fix in NodeCreationService.createTriggerNodesInline:
 *   - 'error' must be in the isDatasourceTrigger exclusion list (no datasource fetch)
 *   - dataId must be `error-trigger-<parentWorkflowId>-<suffix>`
 *   - workflowData must be populated from trigger.id + params.workflowName
 */
describe('NodeCreationService - error trigger round-trip', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  async function importTrigger(trigger: any) {
    const result = await (NodeCreationService as any).createTriggerNodesInline(
      [trigger],
      'tenant-1',
      100,
      100,
    );
    return result.nodes[0];
  }

  it('produces an error-trigger dataId (NOT tables-trigger) after re-import', async () => {
    const fetchSpy = vi.spyOn(ToolDataService, 'fetchDataSourceData');
    const parentWorkflowId = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';

    const node = await importTrigger({
      type: 'error',
      id: parentWorkflowId,
      label: 'On Failure',
      params: { workflowName: 'Parent WF' },
    });

    expect(node.data.id).toMatch(/^error-trigger-/);
    expect(node.data.id).not.toMatch(/^tables-trigger-/);
    expect(node.data.id).toContain(parentWorkflowId);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('populates workflowData from trigger.id and params.workflowName', async () => {
    const parentWorkflowId = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';

    const node = await importTrigger({
      type: 'error',
      id: parentWorkflowId,
      label: 'On Failure',
      params: { workflowName: 'Parent WF' },
    });

    expect(node.data.workflowData).toEqual({
      workflowId: parentWorkflowId,
      workflowName: 'Parent WF',
    });
  });

  it('falls back to label when params.workflowName missing', async () => {
    const parentWorkflowId = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';

    const node = await importTrigger({
      type: 'error',
      id: parentWorkflowId,
      label: 'On Failure',
    });

    expect(node.data.workflowData?.workflowName).toBe('On Failure');
    expect(node.data.workflowData?.workflowId).toBe(parentWorkflowId);
  });

  it('does not fetch datasource data for error triggers', async () => {
    const fetchSpy = vi.spyOn(ToolDataService, 'fetchDataSourceData');

    await importTrigger({
      type: 'error',
      id: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
      label: 'On Failure',
    });

    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('marks the node as an entry node with kind="entry"', async () => {
    const node = await importTrigger({
      type: 'error',
      id: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
      label: 'On Failure',
      params: { workflowName: 'Parent WF' },
    });

    expect(node.data.kind).toBe('entry');
    expect(node.type).toBe('flowNode');
  });
});

/**
 * Tests for the download_file headers/timeout round-trip passthrough.
 *
 * The agent creator (UtilityNodeCreator.executeAddDownloadFile) stores optional
 * `download.headers` and `download.timeout` on the plan. The UI does not surface
 * these, but the first frontend save round-trip must still preserve them so the
 * agent's intent survives persistence.
 */
describe('NodeCreationService - download_file round-trip passthrough', () => {
  function createDownloadNode(coreNode: any) {
    const result = (NodeCreationService as any).createCoreNodesInline(
      [coreNode],
      [],
      100,
      100,
    );
    return result.nodes[0];
  }

  it('preserves headers object when valid (string→string entries only)', () => {
    const node = createDownloadNode({
      type: 'download_file',
      label: 'Auth Download',
      download: {
        url: 'https://example.com/file',
        headers: { Authorization: 'Bearer abc', 'X-Trace': 'test' },
      },
    });
    expect(node.data.downloadHeaders).toEqual({
      Authorization: 'Bearer abc',
      'X-Trace': 'test',
    });
  });

  it('preserves numeric timeout', () => {
    const node = createDownloadNode({
      type: 'download_file',
      label: 'Slow Download',
      download: { url: 'https://example.com/file', timeout: 60000 },
    });
    expect(node.data.downloadTimeout).toBe(60000);
  });

  it('omits downloadHeaders when headers missing', () => {
    const node = createDownloadNode({
      type: 'download_file',
      label: 'Basic',
      download: { url: 'https://example.com/file' },
    });
    expect(node.data.downloadHeaders).toBeUndefined();
  });

  it('omits downloadHeaders when headers object is empty', () => {
    const node = createDownloadNode({
      type: 'download_file',
      label: 'Empty Headers',
      download: { url: 'https://example.com/file', headers: {} },
    });
    expect(node.data.downloadHeaders).toBeUndefined();
  });

  it('omits downloadTimeout when timeout is not a number', () => {
    const node = createDownloadNode({
      type: 'download_file',
      label: 'Bad Timeout',
      download: { url: 'https://example.com/file', timeout: '30000' },
    });
    expect(node.data.downloadTimeout).toBeUndefined();
  });

  it('filters non-string header values out of headers map', () => {
    const node = createDownloadNode({
      type: 'download_file',
      label: 'Mixed',
      download: {
        url: 'https://example.com/file',
        headers: { Authorization: 'Bearer abc', 'X-Count': 42, 'X-Null': null },
      },
    });
    expect(node.data.downloadHeaders).toEqual({ Authorization: 'Bearer abc' });
  });
});

/**
 * Regression: a CRUD table node imported from a saved plan was showing
 * "DataSource 38" (the placeholder built from the numeric id) in the
 * InspectorPanel and FlowNode title, instead of the user-given datasource
 * name. The fix makes createTableNodesInline async and resolves the real
 * name via {@link ToolDataService.fetchDataSourceData}.
 */
describe('NodeCreationService - table node datasource name resolution', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  async function importTable(table: any) {
    const result = await (NodeCreationService as any).createTableNodesInline(
      [table],
      100,
      100,
      'tenant-1',
    );
    return result.nodes[0];
  }

  it('uses the real datasource name from ToolDataService instead of the "DataSource <id>" placeholder', async () => {
    vi.spyOn(ToolDataService, 'fetchDataSourceData').mockResolvedValue({
      dataSourceData: {
        dataSourceId: 38,
        dataSourceName: 'Customer Records',
        tableName: 'customers',
        schema: 'public',
      },
    });

    const node = await importTable({
      type: 'crud-read-row',
      label: 'Get Customer',
      dataSourceId: 38,
      crud: {},
    });

    expect(node.data.dataSourceData.dataSourceName).toBe('Customer Records');
    // The placeholder must NOT leak through - that was the user-facing bug.
    expect(node.data.dataSourceData.dataSourceName).not.toBe('DataSource 38');
    expect(node.data.dataSourceData.dataSourceId).toBe(38);
  });

  it('falls back to the placeholder when the datasource fetch fails so import never blocks', async () => {
    vi.spyOn(ToolDataService, 'fetchDataSourceData').mockRejectedValue(new Error('network down'));

    const node = await importTable({
      type: 'crud-read-row',
      label: 'Get Customer',
      dataSourceId: 38,
      crud: {},
    });

    expect(node.data.dataSourceData.dataSourceName).toBe('DataSource 38');
  });

  it('does not call the datasource fetcher when dataSourceId is missing', async () => {
    const fetchSpy = vi.spyOn(ToolDataService, 'fetchDataSourceData');

    const node = await importTable({
      type: 'crud-read-row',
      label: 'Orphan CRUD',
      crud: {},
    });

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(node.data.dataSourceData.dataSourceName).toBe('DataSource (not configured)');
  });
});

/**
 * Sibling regression to the table-import datasource-name fix: legacy plans
 * carry CRUD steps in {@code plan.mcps[]} with id like {@code 'crud/read-row'}
 * instead of the modern {@code plan.tables[]} array. That path goes through
 * {@code StepNodeCreator.buildCrudDataSourceData} (not
 * {@code createTableNodesInline}) and had the same "DataSource <id>"
 * placeholder bug. Test imports an mcp-shaped CRUD step and asserts the real
 * name lands on the node.
 */
describe('NodeCreationService - legacy CRUD step datasource name resolution', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('legacyCrudStepFromMcpsArrayResolvesRealDatasourceName', async () => {
    vi.spyOn(ToolDataService, 'fetchDataSourceData').mockResolvedValue({
      dataSourceData: {
        dataSourceId: 38,
        dataSourceName: 'Customer Records',
        tableName: 'customers',
        schema: 'public',
      },
    });
    // Avoid the unrelated tool-data fetch that fires for non-CRUD mcps.
    vi.spyOn(ToolDataService, 'fetchToolDataFromPlan').mockResolvedValue({});
    vi.spyOn(ToolDataService, 'getFromBatchCache').mockReturnValue(null);

    const { createStepNodes } = await import('../StepNodeCreator');
    const result = await createStepNodes(
      [
        {
          id: 'crud/read-row',
          label: 'Get Customer',
          dataSourceId: 38,
          crud: {},
        },
      ] as any,
      new Set<string>(),
      100,
      100,
      0,
    );

    const node = result.nodes[0];
    expect(node.data.dataSourceData.dataSourceName).toBe('Customer Records');
    expect(node.data.dataSourceData.dataSourceName).not.toBe('DataSource 38');
    expect(node.data.dataSourceData.dataSourceId).toBe(38);
  });
});

/**
 * Tests for the extract_from_file chunkUnit round-trip.
 *
 * Text-mode chunking gained a chunkUnit field (char | token). The importer must surface
 * it on node.data.extractChunkUnit so the exporter can re-emit it, and must default legacy
 * plans (no chunkUnit) to 'char' so existing char-based workflows are unaffected.
 */
describe('NodeCreationService - extract_from_file chunkUnit round-trip', () => {
  function createExtractNode(coreNode: any) {
    const result = (NodeCreationService as any).createCoreNodesInline(
      [coreNode],
      [],
      100,
      100,
    );
    return result.nodes[0];
  }

  it('surfaces chunkUnit=token from the plan config', () => {
    const node = createExtractNode({
      type: 'extract_from_file',
      label: 'Chunk Doc',
      extractFromFile: {
        format: 'pdf', mode: 'text', chunking: true,
        chunkSize: 256, overlap: 32, chunkingStrategy: 'recursive', chunkUnit: 'token',
      },
    });
    expect(node.data.extractChunkUnit).toBe('token');
  });

  it('defaults chunkUnit to char for legacy plans without the field', () => {
    const node = createExtractNode({
      type: 'extract_from_file',
      label: 'Chunk Doc',
      extractFromFile: {
        format: 'pdf', mode: 'text', chunking: true,
        chunkSize: 500, overlap: 50, chunkingStrategy: 'fixed_size',
      },
    });
    expect(node.data.extractChunkUnit).toBe('char');
  });
});
