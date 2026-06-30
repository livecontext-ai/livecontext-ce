/**
 * Service responsable uniquement de la validation des inputs et paramètres
 * Single Responsibility: Validate that all required inputs are filled
 */

import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { nodeRegistry } from '../../registry/nodeRegistry';

export interface ValidationResult {
  isValid: boolean;
  errors: Array<{
    nodeId: string;
    nodeLabel: string;
    parameter: string;
    message: string;
  }>;
  warnings: Array<{
    nodeId: string;
    nodeLabel: string;
    message: string;
  }>;
}

export class InputValidationService {
  /**
   * Validate all nodes have required inputs filled
   */
  static validateNodes(nodes: Node<BuilderNodeData>[]): ValidationResult {
    const errors: ValidationResult['errors'] = [];
    const warnings: ValidationResult['warnings'] = [];
    
    for (const node of nodes) {
      // Skip decision nodes and loop nodes (they don't have parameters)
      if (nodeRegistry.isDecisionNode(node) || nodeRegistry.isLoopNode(node) || nodeRegistry.isNoteNode(node)) {
        continue;
      }
      
      // Validate tool nodes
      if (node.data.toolData) {
        const toolValidation = this.validateToolNode(node);
        errors.push(...toolValidation.errors);
        warnings.push(...toolValidation.warnings);
      }
      
      // Validate datasource nodes - skip triggers (event-driven, no column mappings).
      if (node.data.dataSourceData && !nodeRegistry.isTrigger(node)) {
        const datasourceValidation = this.validateDataSourceNode(node);
        errors.push(...datasourceValidation.errors);
        warnings.push(...datasourceValidation.warnings);
      }
    }
    
    return {
      isValid: errors.length === 0,
      errors,
      warnings,
    };
  }
  
  /**
   * Validate a tool node
   */
  private static validateToolNode(node: Node<BuilderNodeData>): {
    errors: ValidationResult['errors'];
    warnings: ValidationResult['warnings'];
  } {
    const errors: ValidationResult['errors'] = [];
    const warnings: ValidationResult['warnings'] = [];
    
    const toolData = node.data.toolData;
    if (!toolData || !toolData.parameters) {
      return { errors, warnings };
    }
    
    const paramExpressions = node.data.paramExpressions || {};
    
    for (const param of toolData.parameters) {
      const isRequired = param.isRequired === true || param.required === true;
      const paramName = param.name;
      const hasValue = paramExpressions[paramName] !== undefined && 
                       paramExpressions[paramName] !== null && 
                       paramExpressions[paramName] !== '';
      
      if (isRequired && !hasValue) {
        errors.push({
          nodeId: node.id,
          nodeLabel: node.data.label || node.id,
          parameter: paramName,
          message: `Required parameter "${paramName}" is missing`,
        });
      } else if (!isRequired && !hasValue && param.defaultValue) {
        // Warning: optional parameter with defaultValue not set
        warnings.push({
          nodeId: node.id,
          nodeLabel: node.data.label || node.id,
          message: `Optional parameter "${paramName}" has a default value but is not set`,
        });
      }
    }
    
    return { errors, warnings };
  }
  
  /**
   * Validate a datasource node
   */
  private static validateDataSourceNode(node: Node<BuilderNodeData>): {
    errors: ValidationResult['errors'];
    warnings: ValidationResult['warnings'];
  } {
    const errors: ValidationResult['errors'] = [];
    const warnings: ValidationResult['warnings'] = [];
    
    const dataSourceData = node.data.dataSourceData;
    if (!dataSourceData) {
      return { errors, warnings };
    }
    
    const columnExpressions = dataSourceData.columnExpressions || {};
    
    // Check if at least one column expression is set
    const hasAnyExpression = Object.keys(columnExpressions).length > 0 && 
                             Object.values(columnExpressions).some(expr => expr && expr.trim() !== '');
    
    if (!hasAnyExpression) {
      warnings.push({
        nodeId: node.id,
        nodeLabel: node.data.label || node.id,
        message: 'No column expressions are set for this datasource',
      });
    }
    
    return { errors, warnings };
  }
  
  /**
   * Validate that all edges have valid connections
   */
  static validateEdges(
    nodes: Node<BuilderNodeData>[],
    edges: any[]
  ): ValidationResult {
    const errors: ValidationResult['errors'] = [];
    const warnings: ValidationResult['warnings'] = [];
    
    const nodeMap = new Map<string, Node<BuilderNodeData>>();
    nodes.forEach(node => nodeMap.set(node.id, node));
    
    for (const edge of edges) {
      // Check source node exists
      if (!nodeMap.has(edge.source)) {
        errors.push({
          nodeId: edge.source,
          nodeLabel: edge.source,
          parameter: 'source',
          message: `Source node "${edge.source}" does not exist`,
        });
      }
      
      // Check target node exists
      if (edge.target && !nodeMap.has(edge.target)) {
        errors.push({
          nodeId: edge.target,
          nodeLabel: edge.target,
          parameter: 'target',
          message: `Target node "${edge.target}" does not exist`,
        });
      }
    }
    
    return {
      isValid: errors.length === 0,
      errors,
      warnings,
    };
  }
  
  /**
   * Get validation summary message
   */
  static getValidationSummary(result: ValidationResult): string {
    if (result.isValid && result.warnings.length === 0) {
      return 'All inputs are valid';
    }
    
    const parts: string[] = [];
    if (result.errors.length > 0) {
      parts.push(`${result.errors.length} error(s)`);
    }
    if (result.warnings.length > 0) {
      parts.push(`${result.warnings.length} warning(s)`);
    }
    
    return parts.join(', ');
  }
}

