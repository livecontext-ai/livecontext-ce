/**
 * Service principal responsable de l'orchestration de l'import d'un plan
 * Single Responsibility: Orchestrate the import process
 */

import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { PlanParserService, type ParsedPlan } from './PlanParserService';
import { NodeCreationService, type NodeCreationResult } from './NodeCreationService';
import { EdgeCreationService, type EdgeCreationResult } from './EdgeCreationService';
import { InputValidationService, type ValidationResult } from './InputValidationService';
import {
  applyDagreLayout,
  hasValidPosition,
  layoutConfigForDirection,
  placeUnpositionedNodes,
} from '../LayoutService';
import type { InterfaceFormatContext } from './InterfaceFormatService';
import {
  DEFAULT_WORKFLOW_LAYOUT_DIRECTION,
  type WorkflowLayoutDirection,
} from '@/contexts/WorkflowLayoutDirectionContext';
import { resolvePlanLayout, type PlanLayoutOptions } from '../../utils/planLayoutDirection';

export type { PlanLayoutOptions };

export interface ImportResult {
  nodes: Node<BuilderNodeData>[];
  edges: Edge[];
  validation: ValidationResult;
  success: boolean;
  error?: string;
  /**
   * True when the plan carried no position at all and the whole graph was laid out
   * (an agent build). False when stored positions were kept, even if a few new nodes
   * were placed around them.
   */
  laidOutFromScratch?: boolean;
  /**
   * The direction the nodes were placed in, which the canvas MUST render in: the stored
   * positions were kept only if they were computed in it, and re-laid out otherwise.
   */
  layoutDirection: WorkflowLayoutDirection;
}

export class WorkflowPlanImporter {
  /**
   * Import a workflow plan from JSON string
   */
  /**
   * @param layout how the surface reads the plan: the viewer's default direction and, when
   *   the surface fixes one, that direction. The direction itself is decided here, from the
   *   plan's stamp and positions (see {@link resolvePlanLayout}), and returned as
   *   `layoutDirection` for the canvas to render in. Those preferences live in a React
   *   context and this importer is a plain service, so callers pass them down.
   * @param context what the surface is: its React Query client, and whether it is showing
   *   a run. Passed down for the same reason as the direction above - a plain service must
   *   not reach into a React context, and a module-level singleton would hand the wrong
   *   client to a page that mounts its own. See {@link InterfaceFormatService}.
   */
  static async importPlan(
    jsonString: string,
    existingNodes: Node<BuilderNodeData>[] = [],
    layout: PlanLayoutOptions = { fallbackDirection: DEFAULT_WORKFLOW_LAYOUT_DIRECTION },
    context: InterfaceFormatContext = {}
  ): Promise<ImportResult> {
    try {
      // Step 1: Parse and validate plan structure
      const parsedPlan: ParsedPlan = PlanParserService.parsePlan(jsonString);
      
      // Step 2: Create nodes
      const nodeResult: NodeCreationResult = await NodeCreationService.createNodes(
        parsedPlan.plan,
        existingNodes,
        context
      );
      
      // Step 3: Create edges and update nodes with paramExpressions
      const edgeResult: EdgeCreationResult = EdgeCreationService.createEdges(
        parsedPlan.plan,
        nodeResult.nodes,
        nodeResult.labelToNodeIdMap,
        nodeResult.triggerIdToNodeIdMap,
        nodeResult.interfaceIdToNodeIdMap,
        nodeResult.interfaceLabelToNodeIdMap
      );
      
      // Step 4: Apply node updates (paramExpressions)
      const updatedNodes = nodeResult.nodes.map(node => {
        const updates = edgeResult.nodeUpdates.get(node.id);
        let updatedNode = node;

        if (updates) {
          updatedNode = {
            ...node,
            data: {
              ...node.data,
              ...updates,
            },
          };
        }

        // Step 4.5: Normalize interface node types
        // Interface nodes must use 'interfaceNode' type to render with InterfacePreviewNode
        const isInterfaceNode =
          updatedNode.id.startsWith('interface-') ||
          (updatedNode.data as any)?.interfaceData?.interfaceId != null ||
          updatedNode.data?.kind === 'interface';

        if (isInterfaceNode && updatedNode.type !== 'interfaceNode') {
          console.log('[Import] Normalizing interface node type:', updatedNode.id, updatedNode.type, '→ interfaceNode');
          updatedNode = {
            ...updatedNode,
            type: 'interfaceNode',
            data: {
              ...updatedNode.data,
              kind: 'interface',  // Ensure kind is also set for nodeRegistry consistency
            },
          };
        }

        return updatedNode;
      });

      // Step 5: Decide the reading direction, then position the nodes. A stored position
      // is kept (the user saved it, see placeUnpositionedNodes) unless it was computed in
      // the other direction: kept, it would draw a left-to-right graph with top-to-bottom
      // handles, so the whole graph is laid out again instead.
      const { direction: layoutDirection, relayout } = resolvePlanLayout({
        storedDirection: (parsedPlan.plan as { layoutDirection?: unknown }).layoutDirection,
        hasStoredPositions: updatedNodes.some(hasValidPosition),
        fallbackDirection: layout.fallbackDirection,
        forcedDirection: layout.forcedDirection,
        unstampedPositionsDirection: layout.unstampedPositionsDirection,
      });
      const layoutConfig = layoutConfigForDirection(layoutDirection);
      const { nodes: layoutedNodes, laidOutFromScratch } = relayout
        ? { nodes: applyDagreLayout(updatedNodes, edgeResult.edges, layoutConfig), laidOutFromScratch: true }
        : placeUnpositionedNodes(updatedNodes, edgeResult.edges, layoutConfig);

      // Step 6: Validate inputs
      const validation = InputValidationService.validateNodes(layoutedNodes);
      const edgeValidation = InputValidationService.validateEdges(layoutedNodes, edgeResult.edges);
      
      // Combine validation results
      const combinedValidation: ValidationResult = {
        isValid: validation.isValid && edgeValidation.isValid,
        errors: [...validation.errors, ...edgeValidation.errors],
        warnings: [...validation.warnings, ...edgeValidation.warnings],
      };
      
      return {
        nodes: layoutedNodes,
        edges: edgeResult.edges,
        validation: combinedValidation,
        success: true,
        laidOutFromScratch,
        layoutDirection,
      };
    } catch (error) {
      return {
        nodes: [],
        edges: [],
        validation: {
          isValid: false,
          errors: [{
            nodeId: 'import',
            nodeLabel: 'Import',
            parameter: 'plan',
            message: error instanceof Error ? error.message : 'Unknown error during import',
          }],
          warnings: [],
        },
        success: false,
        error: error instanceof Error ? error.message : 'Unknown error',
        layoutDirection: layout.forcedDirection ?? layout.fallbackDirection,
      };
    }
  }
  
  /**
   * Validate a plan without importing it
   */
  static validatePlan(jsonString: string): ValidationResult {
    try {
      const parsedPlan = PlanParserService.parsePlan(jsonString);
      
      // Basic structure validation is done in parsePlan
      // Additional validations can be added here
      
      return {
        isValid: true,
        errors: [],
        warnings: [],
      };
    } catch (error) {
      return {
        isValid: false,
        errors: [{
          nodeId: 'plan',
          nodeLabel: 'Plan',
          parameter: 'structure',
          message: error instanceof Error ? error.message : 'Unknown validation error',
        }],
        warnings: [],
      };
    }
  }
}

