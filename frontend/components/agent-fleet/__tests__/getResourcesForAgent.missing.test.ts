import { describe, expect, it, vi } from 'vitest';

// The hooks' modules pull API singletons at import time - stub the heavy ones so the
// pure getResourcesForAgent exports can be tested in isolation.
vi.mock('@/lib/api/orchestrator', () => ({ agentService: {}, skillService: {} }));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: {}, ApiError: class extends Error {} }));
vi.mock('@/lib/api/storage-api', () => ({ storageApi: {} }));
vi.mock('@/app/workflows/builder/hooks/useMcpData', () => ({ fetchApis: vi.fn() }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: vi.fn() }));

import { getResourcesForAgent as getFleetResources } from '../useAgentFleetState';
import { getResourcesForAgent as getSingleAgentResources } from '../useSingleAgentFleet';
import { missingResourceKey } from '../resolveFleetResourceLookups';

const emptyMaps = () => ({
  workflowNames: new Map<string, string>(),
  interfaceNames: new Map<string, string>(),
  dataSourceNames: new Map<string, string>(),
  fileNames: new Map<string, string>(),
  apiIconMap: new Map<string, string>(),
  apiNameMap: new Map<string, string>(),
  toolUuidMap: new Map<string, { apiSlug: string; toolSlug: string; iconSlug?: string }>(),
});

const agentWith = (toolsConfig: Record<string, unknown>) => ({
  id: 'agent-1',
  name: 'Agent',
  toolsConfig,
}) as any;

// Regression for the "deleted table keeps its node forever" bug: a grant whose entity
// is confirmed GONE (per-id lookup 404/410 → missingResourceIds) must yield NO chip,
// so the canvas node disappears on the next refresh instead of lingering as a ghost.
describe('getResourcesForAgent - confirmed-missing grants are dropped (fleet hook)', () => {
  const run = (toolsConfig: Record<string, unknown>, missing: Set<string>) => {
    const m = emptyMaps();
    return getFleetResources(
      agentWith(toolsConfig), [], [],
      m.workflowNames, m.interfaceNames, m.dataSourceNames, m.fileNames,
      m.apiIconMap, m.apiNameMap, m.toolUuidMap, missing,
    ).resources;
  };

  it('drops a deleted table grant (numeric id) and keeps the live ones', () => {
    const resources = run(
      { mode: 'none', webSearch: false, tables: [10, 20] },
      new Set([missingResourceKey('table', 10)]),
    );
    const tables = resources.filter(r => r.type === 'table');
    expect(tables.map(r => r.id)).toEqual(['20']);
  });

  it('drops deleted workflow and interface grants', () => {
    const resources = run(
      { mode: 'none', webSearch: false, workflows: ['wf-dead', 'wf-live'], interfaces: ['if-dead'] },
      new Set([missingResourceKey('workflow', 'wf-dead'), missingResourceKey('interface', 'if-dead')]),
    );
    expect(resources.filter(r => r.type === 'workflow').map(r => r.id)).toEqual(['wf-live']);
    expect(resources.filter(r => r.type === 'interface')).toHaveLength(0);
  });

  it('never drops a grant from ANOTHER family sharing the same raw id (namespaced keys)', () => {
    // table id "7" confirmed missing must not hide workflow "7".
    const resources = run(
      { mode: 'none', webSearch: false, tables: [7], workflows: ['7'] },
      new Set([missingResourceKey('table', 7)]),
    );
    expect(resources.filter(r => r.type === 'table')).toHaveLength(0);
    expect(resources.filter(r => r.type === 'workflow').map(r => r.id)).toEqual(['7']);
  });

  it('keeps an unresolved-but-NOT-missing grant with its fallback label (transient outage)', () => {
    const resources = run(
      { mode: 'none', webSearch: false, tables: [99] },
      new Set<string>(),
    );
    const tables = resources.filter(r => r.type === 'table');
    expect(tables).toHaveLength(1);
    expect(tables[0].label).toBe('Table 99');
  });

  it('defaults to dropping nothing when no missing set is provided (back-compat)', () => {
    const m = emptyMaps();
    const { resources } = getFleetResources(
      agentWith({ mode: 'none', webSearch: false, tables: [1, 2] }), [], [],
      m.workflowNames, m.interfaceNames, m.dataSourceNames, m.fileNames,
      m.apiIconMap, m.apiNameMap, m.toolUuidMap,
    );
    expect(resources.filter(r => r.type === 'table')).toHaveLength(2);
  });
});

describe('getResourcesForAgent - confirmed-missing grants are dropped (single-agent hook)', () => {
  it('drops deleted grants across all three entity families', () => {
    const m = emptyMaps();
    const { resources } = getSingleAgentResources(
      agentWith({ mode: 'none', webSearch: false, workflows: ['wf-dead'], interfaces: ['if-live'], tables: [7] }),
      [],
      m.workflowNames, m.interfaceNames, m.dataSourceNames, m.fileNames,
      m.apiIconMap, m.apiNameMap, m.toolUuidMap,
      new Set([missingResourceKey('workflow', 'wf-dead'), missingResourceKey('table', 7)]),
    );
    expect(resources.filter(r => r.type === 'workflow')).toHaveLength(0);
    expect(resources.filter(r => r.type === 'table')).toHaveLength(0);
    expect(resources.filter(r => r.type === 'interface').map(r => r.id)).toEqual(['if-live']);
  });
});
