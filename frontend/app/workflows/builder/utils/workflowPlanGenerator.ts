import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import type { WorkflowPlan } from './workflowPlanTypes';
import { createPlanGeneratorContext } from './planGeneratorContext';
import { processTriggers } from './triggerProcessor';
import { processSteps, processCrudNodes, processTransformAndWaitNodes } from './stepProcessor';
import { processAgents } from './agentProcessor';
import { collectNotes } from './noteProcessor';
import { collectInterfaces } from './interfaceProcessor';
import { processEdgesV2 } from './edgeProcessor';
import { attachNodePolicies } from './nodePolicyProcessor';

// Re-export types
export type { WorkflowPlan, EdgeV2 } from './workflowPlanTypes';

/**
 * Generates a V2 workflow plan from nodes and edges.
 *
 * V2 Plan Format:
 * - Pure graph model: nodes + simple edges (from/to)
 * - Control nodes (decision, switch, loop, split, merge) registered in cores
 * - Edges use ports for branching: core:label:if, core:label:body, etc.
 * - No nested structures (if.then.else, while.postActions)
 *
 * Processing order:
 * 1. Triggers - Entry points
 * 2. Steps (MCPs) - MCP catalog tool calls
 * 3. CRUD Nodes (Tables) - Database operations
 * 4. Transform/Wait (Cores) - Data transformation and wait nodes
 * 5. Agents - AI agent nodes
 * 6. Notes - Annotations
 * 7. Interfaces - UI interfaces
 * 8. Edges - All connections (includes control node registration)
 * 9. Node policies - Attach each node's `nodePolicy` block to its plan entry
 */
export function generateWorkflowPlan(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
  layoutDirection?: 'horizontal' | 'vertical',
): WorkflowPlan {
  const ctx = createPlanGeneratorContext(nodes, edges);
  // Stamp the workflow's reading direction into the plan (its DB identity). Only
  // written when a direction is passed (the builder's active direction on save);
  // omitted otherwise so callers that don't care never introduce the key.
  if (layoutDirection) ctx.plan.layoutDirection = layoutDirection;

  // 1. Process triggers
  processTriggers(ctx);

  // 2. Process steps
  processSteps(ctx);

  // 3. Process CRUD nodes
  processCrudNodes(ctx);

  // 4. Process Transform and Wait nodes (to cores)
  processTransformAndWaitNodes(ctx);

  // 5. Process agents
  processAgents(ctx);

  // 6. Collect notes
  collectNotes(ctx);

  // 7. Collect interfaces
  collectInterfaces(ctx);

  // 8. Process edges (V2 - also registers control nodes)
  processEdgesV2(ctx);

  // 9. Attach per-node execution policies (nodePolicy) to every emitted entry.
  // Must run LAST so the cores registered by processEdgesV2 are covered too.
  attachNodePolicies(ctx);

  return ctx.plan;
}
