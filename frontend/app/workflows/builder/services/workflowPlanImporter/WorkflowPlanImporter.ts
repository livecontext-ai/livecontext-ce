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
import { applyDagreLayout, layoutConfigForDirection, needsLayout } from '../LayoutService';
import {
  DEFAULT_WORKFLOW_LAYOUT_DIRECTION,
  type WorkflowLayoutDirection,
} from '@/contexts/WorkflowLayoutDirectionContext';

export interface ImportResult {
  nodes: Node<BuilderNodeData>[];
  edges: Edge[];
  validation: ValidationResult;
  success: boolean;
  error?: string;
}

export class WorkflowPlanImporter {
  /**
   * Import a workflow plan from JSON string
   */
  /**
   * @param layoutDirection reading direction to lay the imported plan out in. This is
   *   a USER PREFERENCE living in a React context, and this importer is a plain
   *   service, so callers (all of them hooks or components) must pass it down rather
   *   than have the service reach for it. Defaults to horizontal, matching the
   *   context's own default, so an un-updated caller keeps the previous behaviour.
   */
  static async importPlan(
    jsonString: string,
    existingNodes: Node<BuilderNodeData>[] = [],
    layoutDirection: WorkflowLayoutDirection = DEFAULT_WORKFLOW_LAYOUT_DIRECTION
  ): Promise<ImportResult> {
    try {
      // Step 1: Parse and validate plan structure
      const parsedPlan: ParsedPlan = PlanParserService.parsePlan(jsonString);
      
      // Step 2: Create nodes
      const nodeResult: NodeCreationResult = await NodeCreationService.createNodes(
        parsedPlan.plan,
        existingNodes
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

      // Step 5: Apply automatic layout if needed
      // Always use Dagre (same algorithm as the toolbox auto-layout button) when any
      // node lacks a position. The old applyMixedLayout heuristic produced poor results.
      let layoutedNodes = updatedNodes;

      if (needsLayout(updatedNodes)) {
        console.log('[Import] Nodes without positions detected - applying Dagre layout');
        layoutedNodes = applyDagreLayout(updatedNodes, edgeResult.edges, layoutConfigForDirection(layoutDirection));
      } else {
        console.log('[Import] All nodes have positions - respecting manual layout');
      }

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

