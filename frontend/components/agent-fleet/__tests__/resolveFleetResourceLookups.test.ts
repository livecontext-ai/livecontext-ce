import { beforeEach, describe, expect, it, vi } from 'vitest';

import { resolveFleetResourceLookups } from '../resolveFleetResourceLookups';
import { orchestratorApi } from '@/lib/api';
import { ApiError } from '@/lib/api/api-client';
import { storageApi } from '@/lib/api/storage-api';

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflow: vi.fn(),
    getInterface: vi.fn(),
    getDataSource: vi.fn(),
  },
}));

vi.mock('@/lib/api/storage-api', () => ({
  storageApi: {
    getEntryNames: vi.fn(),
    getEntryPreview: vi.fn(),
  },
}));

const mockedOrchestratorApi = vi.mocked(orchestratorApi);
const mockedStorageApi = vi.mocked(storageApi);

describe('resolveFleetResourceLookups', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('resolves explicitly granted resources that are missing from paged list seeds', async () => {
    mockedOrchestratorApi.getWorkflow.mockResolvedValue({
      id: 'workflow-1',
      name: 'Direct Workflow',
    } as any);
    mockedOrchestratorApi.getInterface.mockResolvedValue({
      id: 'interface-1',
      name: 'Direct Interface',
    } as any);
    mockedOrchestratorApi.getDataSource.mockResolvedValue({
      id: 42,
      name: 'Direct Table',
    } as any);

    const lookups = await resolveFleetResourceLookups([
      {
        id: 'agent-1',
        name: 'Agent',
        toolsConfig: {
          mode: 'none',
          workflows: ['workflow-1'],
          interfaces: ['interface-1'],
          tables: [42],
        },
      } as any,
    ], {
      workflows: [],
      interfaces: [],
      dataSources: [],
    });

    expect(lookups.workflowNames.get('workflow-1')).toBe('Direct Workflow');
    expect(lookups.interfaceNames.get('interface-1')).toBe('Direct Interface');
    expect(lookups.dataSourceNames.get('42')).toBe('Direct Table');
    expect(lookups.resourcesById.has('workflow-1')).toBe(true);
    expect(lookups.resourcesById.has('interface-1')).toBe(true);
    expect(lookups.resourcesById.has('42')).toBe(true);

    expect(mockedOrchestratorApi.getWorkflow).toHaveBeenCalledWith('workflow-1');
    expect(mockedOrchestratorApi.getInterface).toHaveBeenCalledWith('interface-1');
    expect(mockedOrchestratorApi.getDataSource).toHaveBeenCalledWith('42');
  });

  it('resolves ALL attached file names in ONE batch call (not N per-file previews)', async () => {
    // Two agents share a file + each has its own → 3 distinct ids, resolved in a
    // single getEntryNames call. Perf fix: avoids N heavy getEntryPreview round-trips.
    mockedStorageApi.getEntryNames.mockResolvedValue({
      'file-a': 'invoice.pdf',
      'file-b': 'photo.png',
      // 'file-c' deliberately absent → must fall back to id-slice
    });

    const lookups = await resolveFleetResourceLookups([
      { id: 'agent-1', name: 'A', toolsConfig: { mode: 'none', files: ['file-a', 'file-b'] } } as any,
      { id: 'agent-2', name: 'B', toolsConfig: { mode: 'none', files: ['file-a', 'file-c'] } } as any,
    ], { workflows: [], interfaces: [], dataSources: [] });

    // ONE batch call, with the de-duplicated id set - never per-file getEntryPreview.
    expect(mockedStorageApi.getEntryNames).toHaveBeenCalledTimes(1);
    expect(mockedStorageApi.getEntryNames.mock.calls[0][0].sort()).toEqual(['file-a', 'file-b', 'file-c']);
    expect(mockedStorageApi.getEntryPreview).not.toHaveBeenCalled();

    expect(lookups.fileNames.get('file-a')).toBe('invoice.pdf');
    expect(lookups.fileNames.get('file-b')).toBe('photo.png');
    // Unresolved id degrades to an id-slice label, never undefined.
    expect(lookups.fileNames.get('file-c')).toBe('file-c'.slice(0, 8));
  });

  it('makes no storage call when no agent has attached files', async () => {
    await resolveFleetResourceLookups(
      [{ id: 'agent-1', name: 'A', toolsConfig: { mode: 'none' } } as any],
      { workflows: [], interfaces: [], dataSources: [] },
    );
    expect(mockedStorageApi.getEntryNames).not.toHaveBeenCalled();
  });

  // Regression: a table deleted while still referenced by an agent's toolsConfig used
  // to keep rendering as a dangling "Table {id}" ghost node forever. The 404 from the
  // per-id lookup must be surfaced as a confirmed-missing id so the canvas drops it.
  it('classifies 404/410 per-id lookups as missingResourceIds (deleted entities)', async () => {
    mockedOrchestratorApi.getDataSource.mockRejectedValue(new ApiError('not found', 404, 'HTTP_404'));
    mockedOrchestratorApi.getWorkflow.mockRejectedValue(new ApiError('gone', 410, 'HTTP_410'));
    mockedOrchestratorApi.getInterface.mockResolvedValue({ id: 'if-1', name: 'Live Interface' } as any);

    const lookups = await resolveFleetResourceLookups([
      {
        id: 'agent-1',
        name: 'Agent',
        toolsConfig: { mode: 'none', workflows: ['wf-dead'], interfaces: ['if-1'], tables: [123] },
      } as any,
    ], { workflows: [], interfaces: [], dataSources: [] });

    // Keys are family-namespaced so a numeric table id can never collide with a
    // workflow/interface grant that shares the same string id.
    expect(lookups.missingResourceIds.has('table:123')).toBe(true);
    expect(lookups.missingResourceIds.has('workflow:wf-dead')).toBe(true);
    // A resource that resolved fine is NOT missing.
    expect(lookups.missingResourceIds.has('interface:if-1')).toBe(false);
    expect(lookups.missingResourceIds.size).toBe(2);
    expect(lookups.dataSourceNames.has('123')).toBe(false);
  });

  it('does NOT classify transient failures (5xx / network) or 403s as missing - those keep the fallback label', async () => {
    mockedOrchestratorApi.getDataSource.mockRejectedValue(new ApiError('boom', 500, 'HTTP_500'));
    mockedOrchestratorApi.getWorkflow.mockRejectedValue(new Error('network down'));
    // 403 = the entity EXISTS but the viewer can't read it - a live grant, not a ghost.
    mockedOrchestratorApi.getInterface.mockRejectedValue(new ApiError('forbidden', 403, 'HTTP_403'));

    const lookups = await resolveFleetResourceLookups([
      {
        id: 'agent-1',
        name: 'Agent',
        toolsConfig: { mode: 'none', workflows: ['wf-flaky'], interfaces: ['if-private'], tables: [55] },
      } as any,
    ], { workflows: [], interfaces: [], dataSources: [] });

    // Unresolved but not confirmed-gone: must stay renderable (fallback label),
    // never silently hidden by a transient outage or an access restriction.
    expect(lookups.missingResourceIds.size).toBe(0);
  });

  it('does not flag seeded (already-known) resources as missing', async () => {
    const lookups = await resolveFleetResourceLookups([
      { id: 'agent-1', name: 'Agent', toolsConfig: { mode: 'none', tables: [7] } } as any,
    ], {
      workflows: [],
      interfaces: [],
      dataSources: [{ id: 7, name: 'Customers' } as any],
    });

    expect(lookups.dataSourceNames.get('7')).toBe('Customers');
    expect(lookups.missingResourceIds.size).toBe(0);
    expect(mockedOrchestratorApi.getDataSource).not.toHaveBeenCalled();
  });
});
