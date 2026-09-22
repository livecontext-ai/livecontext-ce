import { agentService } from './agent.service';
import { executionService } from './execution.service';
import type { ResourceType } from './dashboard.service';

export interface ProductionResourceControl {
  resourceType: ResourceType;
  resourceId: string;
  name?: string;
  productionRunIdPublic?: string;
  /** Agenda occurrences and markers expose the same production run under this API name. */
  runIdPublic?: string;
  resourcePaused?: boolean;
}

export type ProductionResourceKind = 'workflow' | 'agent' | 'interface';

/** The concrete noun shown by production controls instead of the ambiguous "resource". */
export function productionResourceKind(resourceType: ResourceType): ProductionResourceKind {
  if (resourceType === 'AGENT') return 'agent';
  if (resourceType === 'APPLICATION') return 'interface';
  return 'workflow';
}

function productionRunId(resource: ProductionResourceControl): string | undefined {
  return resource.productionRunIdPublic || resource.runIdPublic;
}

/** Whether the existing resource APIs can pause or resume this production resource. */
export function canControlProductionResource(resource: ProductionResourceControl): boolean {
  return resource.resourceType === 'AGENT'
    ? Boolean(resource.name?.trim())
    : Boolean(productionRunId(resource));
}

/**
 * Pause or resume the resource itself, independently of one schedule.
 *
 * Workflows and applications share the production-run lifecycle used by the board.
 * Agents use their existing active flag. Keeping this decision here prevents the agenda
 * and notification bell from developing two slightly different control paths.
 */
export async function setProductionResourcePaused(
  resource: ProductionResourceControl,
  paused: boolean,
): Promise<void> {
  if (resource.resourceType === 'AGENT') {
    const name = resource.name?.trim();
    if (!name) throw new Error('Agent name is unavailable');
    await agentService.updateAgent(resource.resourceId, { name, isActive: !paused });
    return;
  }
  const runId = productionRunId(resource);
  if (!runId) {
    throw new Error('Production run is unavailable');
  }
  if (paused) {
    await executionService.cancelWorkflow(runId);
  } else {
    await executionService.reactivateWorkflow(runId);
  }
}
