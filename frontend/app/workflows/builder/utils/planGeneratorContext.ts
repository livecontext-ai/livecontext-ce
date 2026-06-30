import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import type { WorkflowPlan } from './workflowPlanTypes';

/**
 * Context object shared across all plan generator processors.
 * Holds input data, output plan, and tracking maps.
 */
export interface PlanGeneratorContext {
  // Input
  readonly nodes: Node<BuilderNodeData>[];
  readonly edges: Edge[];

  // Output
  plan: WorkflowPlan;

  // Tracking maps for node references
  stepLabelMap: Map<string, string>;           // nodeId -> normalized label
  triggerSlugMap: Map<string, string>;         // nodeId -> trigger slug
  triggerPlanByNodeId: Map<string, any>;       // nodeId -> trigger plan object
  stepPlanByNodeId: Map<string, any>;          // nodeId -> step plan object
  agentPlanByNodeId: Map<string, any>;         // nodeId -> agent plan object

  // Interface tracking
  interfaceNodeIdMap: Map<string, { realId: string; label: string }>;  // nodeId -> interface info
}

/**
 * Creates a new PlanGeneratorContext with initialized empty collections.
 * The plan contains only workflow definition data (triggers, steps, edges, etc.).
 * Metadata (workflowId, tenantId) lives in dedicated DB columns, not in the plan.
 */
export function createPlanGeneratorContext(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[]
): PlanGeneratorContext {
  return {
    // Input (readonly)
    nodes,
    edges,

    // Output plan - pure workflow definition, no metadata
    plan: {
      triggers: [],
      mcps: [],
      agents: [],
      tables: [],
      cores: [],
      notes: [],
      interfaces: [],
      edges: [],
    },

    // Tracking maps
    stepLabelMap: new Map(),
    triggerSlugMap: new Map(),
    triggerPlanByNodeId: new Map(),
    stepPlanByNodeId: new Map(),
    agentPlanByNodeId: new Map(),

    // Interface tracking
    interfaceNodeIdMap: new Map(),
  };
}
