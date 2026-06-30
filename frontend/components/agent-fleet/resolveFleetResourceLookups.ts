'use client';

import { orchestratorApi } from '@/lib/api';
import { ApiError } from '@/lib/api/api-client';
import { storageApi } from '@/lib/api/storage-api';
import type { Agent, DataSource, Interface, Workflow } from '@/lib/api/orchestrator/types';
import { getAllowedIds, type InternalResourceKey } from '@/lib/agents/toolsConfigAccess';
import { limitConcurrency } from '@/lib/utils/concurrency';

const MAX_RESOURCE_LOOKUP_CONCURRENT = 6;

export interface FleetResourceLookups {
  workflowNames: Map<string, string>;
  interfaceNames: Map<string, string>;
  dataSourceNames: Map<string, string>;
  fileNames: Map<string, string>;
  resourcesById: Map<string, unknown>;
  /**
   * Workflow/interface/table grant ids whose entity is confirmed GONE (the
   * per-id lookup returned 404/410 - e.g. a table deleted while still referenced
   * by an agent's toolsConfig). The canvas derivation drops these so a deleted
   * resource's node disappears instead of rendering a dangling "Table {id}" ghost.
   * Transient failures (network/5xx) and 403s are NOT in this set - those keep
   * the fallback-label rendering rather than silently hiding a live grant.
   *
   * Entries are namespaced by family (`workflow:{id}` / `interface:{id}` /
   * `table:{id}`) so a numeric table id can never accidentally drop a workflow
   * grant that happens to share the same string id. Use {@link missingResourceKey}.
   *
   * Files are deliberately NOT classified: the batch name endpoint returns only
   * accessible ids, so an absence cannot distinguish "deleted" from "no read
   * access" or a partial outage - dropping on absence would hide live grants,
   * the failure mode this set is designed to avoid.
   */
  missingResourceIds: Set<string>;
}

/** Canonical key for {@link FleetResourceLookups.missingResourceIds}. */
export function missingResourceKey(
  family: 'workflow' | 'interface' | 'table',
  id: string | number,
): string {
  return `${family}:${id}`;
}

/** True iff the lookup failure means the entity no longer exists (vs a transient error). */
function isGoneError(err: unknown): boolean {
  return err instanceof ApiError && (err.status === 404 || err.status === 410);
}

function collectAllowedResourceIds(agents: Agent[], key: InternalResourceKey): string[] {
  const ids = new Set<string>();
  agents.forEach(agent => {
    getAllowedIds(agent.toolsConfig, key).forEach(id => ids.add(id));
  });
  return Array.from(ids);
}

function addWorkflow(
  workflow: Workflow | null | undefined,
  workflowNames: Map<string, string>,
  resourcesById: Map<string, unknown>,
) {
  if (!workflow?.id) return;
  workflowNames.set(String(workflow.id), workflow.name || String(workflow.id).slice(0, 8));
  resourcesById.set(String(workflow.id), workflow);
}

function addInterface(
  iface: Interface | null | undefined,
  interfaceNames: Map<string, string>,
  resourcesById: Map<string, unknown>,
) {
  if (!iface?.id) return;
  interfaceNames.set(String(iface.id), iface.name || String(iface.id).slice(0, 8));
  resourcesById.set(String(iface.id), iface);
}

function addDataSource(
  dataSource: DataSource | null | undefined,
  dataSourceNames: Map<string, string>,
  resourcesById: Map<string, unknown>,
) {
  if (dataSource?.id == null) return;
  const id = String(dataSource.id);
  dataSourceNames.set(id, dataSource.name || `Table ${id}`);
  resourcesById.set(id, dataSource);
}

export async function resolveFleetResourceLookups(
  agents: Agent[],
  seed: {
    workflows: Workflow[];
    interfaces: Interface[];
    dataSources: DataSource[];
  },
): Promise<FleetResourceLookups> {
  const workflowNames = new Map<string, string>();
  const interfaceNames = new Map<string, string>();
  const dataSourceNames = new Map<string, string>();
  const fileNames = new Map<string, string>();
  const resourcesById = new Map<string, unknown>();
  const missingResourceIds = new Set<string>();

  seed.workflows.forEach(workflow => addWorkflow(workflow, workflowNames, resourcesById));
  seed.interfaces.forEach(iface => addInterface(iface, interfaceNames, resourcesById));
  seed.dataSources.forEach(dataSource => addDataSource(dataSource, dataSourceNames, resourcesById));

  const missingWorkflowIds = collectAllowedResourceIds(agents, 'workflows')
    .filter(id => !workflowNames.has(id));
  const missingInterfaceIds = collectAllowedResourceIds(agents, 'interfaces')
    .filter(id => !interfaceNames.has(id));
  const missingDataSourceIds = collectAllowedResourceIds(agents, 'tables')
    .filter(id => !dataSourceNames.has(id));
  // Files attached to agents (opt-in allow-list) - resolve their display names.
  const fileIds = collectAllowedResourceIds(agents, 'files');

  const workflowTasks = missingWorkflowIds.map(id => async () =>
    orchestratorApi.getWorkflow(id).catch((err): Workflow | null => {
      if (isGoneError(err)) missingResourceIds.add(missingResourceKey('workflow', id));
      return null;
    })
  );
  const interfaceTasks = missingInterfaceIds.map(id => async () =>
    orchestratorApi.getInterface(id).catch((err): Interface | null => {
      if (isGoneError(err)) missingResourceIds.add(missingResourceKey('interface', id));
      return null;
    })
  );
  const dataSourceTasks = missingDataSourceIds.map(id => async () =>
    orchestratorApi.getDataSource(id).catch((err): DataSource | null => {
      if (isGoneError(err)) missingResourceIds.add(missingResourceKey('table', id));
      return null;
    })
  );
  // Files: resolve ALL attached file names in ONE batch request (not N per-file
  // `getEntryPreview` round-trips - each of those streams the file's structure/text/
  // presigned-URL payload just to read a name, which made the fleet slow to load for
  // agents with many attached files). The batch returns only accessible, named ids;
  // anything missing falls back to an id-slice below.
  const filesPromise: Promise<Record<string, string>> =
    fileIds.length > 0 ? storageApi.getEntryNames(fileIds).catch(() => ({})) : Promise.resolve({});

  const [resolvedWorkflows, resolvedInterfaces, resolvedDataSources, resolvedFileNames] = await Promise.all([
    limitConcurrency(workflowTasks, MAX_RESOURCE_LOOKUP_CONCURRENT),
    limitConcurrency(interfaceTasks, MAX_RESOURCE_LOOKUP_CONCURRENT),
    limitConcurrency(dataSourceTasks, MAX_RESOURCE_LOOKUP_CONCURRENT),
    filesPromise,
  ]);

  resolvedWorkflows.forEach(workflow => addWorkflow(workflow, workflowNames, resourcesById));
  resolvedInterfaces.forEach(iface => addInterface(iface, interfaceNames, resourcesById));
  resolvedDataSources.forEach(dataSource => addDataSource(dataSource, dataSourceNames, resourcesById));
  fileIds.forEach(id => {
    fileNames.set(String(id), resolvedFileNames[id] || String(id).slice(0, 8));
  });

  return {
    workflowNames,
    interfaceNames,
    dataSourceNames,
    fileNames,
    resourcesById,
    missingResourceIds,
  };
}
