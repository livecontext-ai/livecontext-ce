/**
 * @deprecated Use ValidationContext + rules-v2 (WorkflowValidator) instead.
 * This service is no longer used in production code - all validations have been
 * migrated to the centralized rules-v2 system (NodeConfigurationRule,
 * CrudValidationRule, CredentialValidationRule, etc.).
 * Kept temporarily for test compatibility; will be removed in a future cleanup.
 */

import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { InputValidationService, type ValidationResult } from './workflowPlanImporter/InputValidationService';
import { nodeRegistry } from '../registry/nodeRegistry';

export interface NodeValidationError {
  type: 'parameter' | 'condition' | 'datasource' | 'loop' | 'decision' | 'switch' | 'general';
  parameter?: string;
  message: string;
  severity: 'error' | 'warning';
  source: 'frontend' | 'backend';
}

export interface NodeValidationResult {
  isValid: boolean;
  errors: NodeValidationError[];
  errorCount: number;
  warningCount: number;
}

export class NodeValidationService {
  /**
   * Validates a specific node and returns all errors (frontend + backend)
   * @param node The node to validate
   * @param toolParameters Tool parameters (optional, for tool nodes)
   * @param backendErrors Backend validation errors (optional, array of error messages)
   * @returns An object containing all validation errors
   */
  static validateNode(
    node: Node<BuilderNodeData> | null,
    toolParameters?: any[],
    backendErrors?: string[]
  ): NodeValidationResult {
    if (!node) {
      return {
        isValid: true,
        errors: [],
        errorCount: 0,
        warningCount: 0,
      };
    }

    const errors: NodeValidationError[] = [];
    const data = node.data;
    const nodeType = node.type;

    // Get backend errors from node data if not provided
    const backendValidationIssues = backendErrors || 
      (Array.isArray((data as any)?.validationIssues) ? (data as any).validationIssues : []);

    // Add backend errors
    if (backendValidationIssues.length > 0) {
      backendValidationIssues.forEach((errorMessage: string) => {
        errors.push({
          type: 'general',
          message: errorMessage,
          severity: 'error',
          source: 'backend',
        });
      });
    }

    // Frontend validation for tool/API nodes
    if (data.toolData || data.apiData) {
      const toolValidation = this.validateToolNode(node, toolParameters);
      errors.push(...toolValidation);
    }

    // Frontend validation for datasource nodes
    if (data.dataSourceData) {
      const datasourceValidation = this.validateDataSourceNode(node);
      errors.push(...datasourceValidation);
    }

    // Frontend validation for split nodes - use nodeRegistry
    if (nodeRegistry.isSplitNode(node)) {
      const splitValidation = this.validateSplitNode(node);
      errors.push(...splitValidation);
    }

    // Frontend validation for decision (if/elseif/else) nodes - use nodeRegistry
    if (nodeRegistry.isDecisionNode(node)) {
      const decisionValidation = this.validateDecisionNode(node);
      errors.push(...decisionValidation);
    }

    // Frontend validation for switch (case/default) nodes - use nodeRegistry
    if (nodeRegistry.isSwitchNode(node)) {
      const switchValidation = this.validateSwitchNode(node);
      errors.push(...switchValidation);
    }

    // Frontend validation for loop/while nodes - use nodeRegistry
    if (nodeRegistry.isLoopNode(node)) {
      const condition = (data as any).whileCondition ?? (data as any).loopCondition;
      if (!condition || condition.trim() === '') {
        errors.push({
          type: 'loop',
          message: 'Loop condition is required',
          severity: 'error',
          source: 'frontend',
        });
      }
    }

    // Frontend validation for interface nodes (HTML template required)
    const nodeId = (data.id || node.id || '') as string;
    const isInterfaceNode =
      data.kind === 'interface' ||
      nodeId === 'interface' ||
      nodeId.startsWith('interface-');

    if (isInterfaceNode) {
      const interfaceData = (data as any)?.interfaceData || {};
      const editorExpression: string | undefined = interfaceData.editorExpression;
      
      // Extract interfaceId from node data or node ID
      const interfaceIdFromData = interfaceData.interfaceId;
      const isInterfaceFromDb = nodeId.startsWith('interface-') && nodeId !== 'interface';
      const extractedInterfaceId = isInterfaceFromDb ? nodeId.replace('interface-', '').replace(/--\d+$/, '') : null;
      const hasInterfaceId = !!interfaceIdFromData || !!extractedInterfaceId;

      // Only validate template as missing if there's no interfaceId
      // If interfaceId exists, the template will be loaded from DB asynchronously
      // Similar to how tool nodes handle parameters loading
      if (!editorExpression || editorExpression.trim() === '') {
        if (!hasInterfaceId) {
          // No interfaceId and no template = error
          errors.push({
            type: 'parameter',
            parameter: 'htmlTemplate',
            message: 'HTML template is required for interface nodes',
            severity: 'error',
            source: 'frontend',
          });
        }
        // If hasInterfaceId exists, template is being loaded from DB, don't mark as error
      }

      // Note: Interface unsaved changes and table attachment validation are handled by InterfaceValidationRule
      // to ensure they're part of the global workflow validation and disables the start button
    }

    // Deduplicate errors across backend + frontend to avoid repeated messages
    const seen = new Set<string>();
    const dedupedErrors = errors.filter((err) => {
      // Dedup across backend/frontend by severity + message (ignore differing parameter/source/type)
      const key = `${err.severity}:${err.message}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });

    const errorCount = dedupedErrors.filter(e => e.severity === 'error').length;
    const warningCount = dedupedErrors.filter(e => e.severity === 'warning').length;

    return {
      isValid: errorCount === 0,
      errors: dedupedErrors,
      errorCount,
      warningCount,
    };
  }

  /**
   * Valide un node tool
   */
  private static validateToolNode(
    node: Node<BuilderNodeData>,
    toolParameters?: any[]
  ): NodeValidationError[] {
    const errors: NodeValidationError[] = [];
    const data = node.data;
    
    const toolData = data.toolData;
    if (!toolData) {
      return errors;
    }

    // Obtenir les paramètres depuis toolParameters ou toolData.parameters
    const effectiveToolParameters = toolParameters !== undefined
      ? toolParameters
      : (toolData as any)?.parameters;

    // Si les paramètres ne sont pas encore chargés, ne pas valider
    if (effectiveToolParameters === undefined || !Array.isArray(effectiveToolParameters)) {
      return errors;
    }

    // Si le tableau est vide mais qu'on a un toolSlug, les paramètres ne sont peut-être pas encore chargés
    if (effectiveToolParameters.length === 0) {
      const toolSlug = (toolData as any)?.toolSlug;
      if (toolSlug) {
        // Ne pas valider si les paramètres ne sont pas encore chargés
        return errors;
      }
    }

    if (effectiveToolParameters.length > 0) {
      const paramExpressions = data.paramExpressions || {};
      
      for (const param of effectiveToolParameters) {
        const isRequired = param.isRequired === true || param.required === true;
        const paramName = param.name || param.id;
        const expression = paramExpressions[paramName];
        
        // Check if required parameter has a value
        if (isRequired) {
          if (!expression || expression.trim() === '') {
            errors.push({
              type: 'parameter',
              parameter: paramName,
              message: `Required parameter "${paramName}" is missing`,
              severity: 'error',
              source: 'frontend',
            });
          }
        } else if (!expression || expression.trim() === '') {
          // Optional parameter with default value not set
          if (param.defaultValue !== undefined && param.defaultValue !== null && param.defaultValue !== '') {
            errors.push({
              type: 'parameter',
              parameter: paramName,
              message: `Optional parameter "${paramName}" has a default value but is not set`,
              severity: 'warning',
              source: 'frontend',
            });
          }
        }
      }
    }

    return errors;
  }

  /**
   * Valide un node datasource
   */
  private static validateDataSourceNode(node: Node<BuilderNodeData>): NodeValidationError[] {
    const errors: NodeValidationError[] = [];
    const data = node.data;
    const dataSourceData = (data as any)?.dataSourceData;

    if (!dataSourceData) {
      return errors;
    }

    if (dataSourceData.dataSourceId) {
      const columnExpressions = dataSourceData.columnExpressions || {};
      const columnFields = Object.keys(columnExpressions);
      const nonEmptyMappings = Object.values(columnExpressions || {}).filter(
        (expr) => typeof expr === 'string' && expr.trim() !== ''
      );

      // Check that all mapped columns have a non-empty expression
      for (const columnField of columnFields) {
        const expression = columnExpressions[columnField];

        if (!expression || expression.trim() === '') {
          errors.push({
            type: 'datasource',
            parameter: columnField,
            message: `Mapped column "${columnField}" has an empty expression`,
            severity: 'error',
            source: 'frontend',
          });
        }
      }

      // Warning if no columns are mapped
      if (nonEmptyMappings.length === 0) {
        errors.push({
          type: 'datasource',
          message: 'No columns are mapped for this data source',
          severity: 'warning',
          source: 'frontend',
        });
      }
    }

    return errors;
  }

  /**
   * Valide un node split
   */
  private static validateSplitNode(node: Node<BuilderNodeData>): NodeValidationError[] {
    const errors: NodeValidationError[] = [];
    const data = node.data;
    const list = data.list;

    if (!list || list.trim() === '') {
      errors.push({
        type: 'loop',
        message: 'List expression is missing',
        severity: 'error',
        source: 'frontend',
      });
    }

    return errors;
  }

  /**
   * Valide un node decision (if/else)
   */
  private static validateDecisionNode(node: Node<BuilderNodeData>): NodeValidationError[] {
    const errors: NodeValidationError[] = [];
    const data = node.data;
    const conditions = data.decisionConditions || [];

    // All IF/ELSEIF branches must have non-empty expressions
    conditions
      .filter((c: any) => c.type === 'if' || c.type === 'elseif')
      .forEach((condition: any) => {
        const expr = condition?.expression?.trim() || '';
        if (expr === '') {
          const label = condition?.label || condition?.type || 'condition';
          errors.push({
            type: 'decision',
            message: `Condition "${label}" is missing an expression`,
            severity: 'error',
            source: 'frontend',
          });
        }
      });

    return errors;
  }

  /**
   * Valide un node switch (case/default)
   */
  private static validateSwitchNode(node: Node<BuilderNodeData>): NodeValidationError[] {
    const errors: NodeValidationError[] = [];
    const data = node.data as any;

    // Check switchExpression is not empty
    const switchExpression = data.switchExpression?.trim() || '';
    if (switchExpression === '') {
      errors.push({
        type: 'switch',
        message: 'Switch expression is missing',
        severity: 'error',
        source: 'frontend',
      });
    }

    // Check switchCases exist
    const switchCases = data.switchCases || [];
    if (switchCases.length === 0) {
      errors.push({
        type: 'switch',
        message: 'Switch must have at least one case',
        severity: 'error',
        source: 'frontend',
      });
      return errors;
    }

    // All 'case' type branches must have non-empty values
    const caseBranches = switchCases.filter((c: any) => c.type === 'case');
    if (caseBranches.length === 0) {
      errors.push({
        type: 'switch',
        message: 'Switch must have at least one case (not just default)',
        severity: 'error',
        source: 'frontend',
      });
    }

    caseBranches.forEach((caseItem: any) => {
      const value = caseItem?.value?.trim() || '';
      if (value === '') {
        const label = caseItem?.label || 'case';
        errors.push({
          type: 'switch',
          message: `Case "${label}" is missing a value`,
          severity: 'error',
          source: 'frontend',
        });
      }
    });

    return errors;
  }

  /**
   * Gets an error summary for display
   */
  static getErrorSummary(result: NodeValidationResult): string {
    if (result.isValid && result.warningCount === 0) {
      return '';
    }

    const parts: string[] = [];
    if (result.errorCount > 0) {
      parts.push(`${result.errorCount} error${result.errorCount > 1 ? 's' : ''}`);
    }
    if (result.warningCount > 0) {
      parts.push(`${result.warningCount} warning${result.warningCount > 1 ? 's' : ''}`);
    }

    return parts.join(', ');
  }

  /**
   * Valide tous les nodes d'un workflow
   */
  static validateAllNodes(nodes: Node<BuilderNodeData>[]): ValidationResult {
    return InputValidationService.validateNodes(nodes);
  }
}

