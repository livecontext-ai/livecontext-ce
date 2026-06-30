/**
 * Service responsable uniquement du parsing et de la validation de la structure du plan
 * Single Responsibility: Parse and validate plan structure
 */

import {
  parseEdgeRef as parseEdgeRefCore,
  hasPort as hasPortCore,
  getNodeKey as getNodeKeyCore,
  type EdgeRef,
} from '../../utils/edgeRefParser';

import type { EdgeV2 } from '../../utils/workflowPlanTypes';

// Re-export EdgeV2 from workflowPlanTypes for backward compatibility
export type { EdgeV2 } from '../../utils/workflowPlanTypes';

export interface WorkflowPlan {
  id: string;
  tenant_id?: string; // Optional - backend extracts from JWT
  schedule?: {
    cron?: string; // Cron expression for scheduled execution
  } | null;
  triggers: Array<{
    id: string;
    type: string;
    label?: string;
    params?: Record<string, any>;
    position?: { x: number; y: number };
    graphNodeId?: string;
  }>;
  mcps: Array<{
    id: string;
    type?: 'mcp'; // Only MCP catalog tools
    label: string;
    position?: { x: number; y: number };
    params?: Record<string, any>;
    graphNodeId?: string;
  }>;
  agents?: Array<{
    id: string;
    type: 'agent' | 'guardrail' | 'classify' | 'browser_agent'; // Required: agent type
    label: string;
    provider?: string;
    model?: string;
    systemPrompt?: string;
    prompt?: string;
    temperature?: number;
    maxTokens?: number;
    maxIterations?: number;
    maxTools?: number;
    tools?: string[]; // References to tool nodes: "mcp:label" or "agent:label"
    position?: { x: number; y: number };
    params?: Record<string, any>;
    graphNodeId?: string;
    // Classify-specific properties (classifyCategories is canonical, categories is legacy)
    classifyCategories?: Array<{ id: string; label: string; description?: string }>;
    categories?: Array<{ id?: string; label: string; description?: string }>; // Legacy name
    classifyParams?: string;
    // Guardrail-specific properties
    guardrailRules?: Array<{ id: string; type: string; action?: string; config?: any }>;
    guardrailParams?: string;
  }>;
  tables?: Array<{
    id?: string;
    type: 'crud-create-row' | 'crud-read-row' | 'crud-update-row' | 'crud-delete-row' | 'crud-create-column' | 'crud-find' | 'crud-find-rows' | 'crud-find-row'; // Required: CRUD type
    label: string;
    position?: { x: number; y: number };
    params?: Record<string, any>;
    graphNodeId?: string;
    dataSourceId?: string | number;
    crud?: {
      where?: { column: string; operator: string; value?: any };
      set?: Record<string, any>;
      rows?: Array<{ id: string; columns: Record<string, any> }>;
      columns?: Array<{ name: string; type: string; defaultValue?: any }>;
      limit?: number;
    };
  }>;
  cores?: Array<{
    id: string;
    type: 'decision' | 'switch' | 'loop' | 'split' | 'merge' | 'fork' | 'transform' | 'filter' | 'sort' | 'limit' | 'remove_duplicates' | 'summarize' | 'compare_datasets' | 'date_time' | 'crypto_jwt' | 'xml' | 'compression' | 'rss' | 'convert_to_file' | 'extract_from_file' | 'wait' | 'download_file' | 'aggregate' | 'exit' | 'response' | 'option' | 'http_request' | 'approval' | 'data_input' | 'sub_workflow' | 'respond_to_webhook' | 'send_email' | 'email_inbox' | 'code' | 'set' | 'html_extract';
    label?: string;
    position?: { x: number; y: number };
    params?: Record<string, any>;
    graphNodeId?: string;
    // For decision nodes
    decisionConditions?: Array<{
      id: string;
      type: 'if' | 'elseif' | 'else';
      label: string;
      expression?: string;
    }>;
    // For switch nodes
    switchExpression?: string;
    switchCases?: Array<{
      id: string;
      type: 'case' | 'default';
      label: string;
      value?: string;
    }>;
    // For loop nodes
    loopCondition?: string;
    maxIterations?: number;
    strategy?: string;
    // For split nodes (formerly forEach)
    list?: string; // Canonical name (listExpression accepted for backward compat)
    maxItems?: number;
    splitStrategy?: string;
    // For fork nodes
    forkOutputs?: Array<{
      id: string;
      label: string;
      targetStep?: string;
    }>;
    // For transform nodes
    transform?: {
      mappings: Array<{ label: string; expression: string }>;
    };
    // For wait nodes
    wait?: {
      duration: number;
    };
    // For download_file nodes
    download?: {
      url: string;
      filename?: string;
      mimeType?: string;
    };
    // For aggregate nodes
    aggregate?: {
      fields: Array<{ label: string; expression: string }>;
    };
    // For response nodes
    response?: {
      message: string;
    };
    // For http_request nodes
    httpRequest?: {
      method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD' | 'OPTIONS';
      url: string;
      authType?: 'none' | 'basic' | 'bearer' | 'api-key' | 'custom-header';
      authConfig?: {
        username?: string;
        password?: string;
        bearerToken?: string;
        apiKeyName?: string;
        apiKeyValue?: string;
        apiKeyLocation?: 'header' | 'query';
        headerName?: string;
        headerValue?: string;
      };
      queryParams?: Array<{ key: string; value: string }>;
      headers?: Array<{ key: string; value: string }>;
      bodyType?: 'none' | 'json' | 'form-data' | 'x-www-form-urlencoded' | 'raw';
      body?: string;
      contentType?: string;
      timeout?: number;
    };
  }>;
  notes?: Array<{
    id: string;
    label?: string;
    text: string;
    color: string;
    borderColor: string;
    textColor: string;
    width: number;
    height: number;
    position?: { x: number; y: number };
  }>;
  interfaces?: Array<{
    id: string;
    label?: string;
    position?: { x: number; y: number };
    // Enriched snapshot fields (added at publication time by enrichPlanWithInterfaceData)
    _snapshot_htmlTemplate?: string;
    _snapshot_cssTemplate?: string;
    _snapshot_jsTemplate?: string;
    // Additional fields that may be present in plans
    previewWidth?: number;
    previewHeight?: number;
    showPreview?: boolean;
    variableMapping?: Record<string, string>;
    actionMapping?: Record<string, string>;
  }>;
  edges: EdgeV2[];
}

export interface ParsedPlan {
  plan: WorkflowPlan;
  triggerLabelMap: Map<string, string>; // trigger id -> label
  stepLabelMap: Map<string, string>; // step label -> node id (to be created)
}

export class PlanParserService {
  /**
   * Parse and validate a JSON plan string
   */
  static parsePlan(jsonString: string): ParsedPlan {
    try {
      const plan: WorkflowPlan = JSON.parse(jsonString);

      // Validate plan structure
      this.validatePlanStructure(plan);
      
      // Create label maps
      const triggerLabelMap = new Map<string, string>();
      plan.triggers.forEach((trigger) => {
        // Utiliser le label du trigger pour la clÃ© (fallback sur id si pas de label)
        const triggerLabel = trigger.label || trigger.id;
        triggerLabelMap.set(trigger.id, triggerLabel);
      });
      
      const stepLabelMap = new Map<string, string>();
      plan.mcps.forEach((step) => {
        // Will be populated with actual node IDs during node creation
        stepLabelMap.set(step.label, step.label);
      });

      // Also add agents to stepLabelMap (they are referenced similarly in edges)
      if (plan.agents) {
        plan.agents.forEach((agent) => {
          // Will be populated with actual node IDs during node creation
          stepLabelMap.set(agent.label, agent.label);
        });
      }

      return {
        plan,
        triggerLabelMap,
        stepLabelMap,
      };
    } catch (error) {
      throw new Error(`Failed to parse plan: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  }
  
  /**
   * Validate the plan structure
   */
  private static validatePlanStructure(plan: any): asserts plan is WorkflowPlan {
    if (!plan || typeof plan !== 'object') {
      throw new Error('Plan must be an object');
    }
    
    if (!Array.isArray(plan.triggers)) {
      throw new Error('Plan must have a triggers array');
    }
    
    if (!Array.isArray(plan.mcps)) {
      throw new Error('Plan must have a mcps array');
    }
    
    if (!Array.isArray(plan.edges)) {
      throw new Error('Plan must have an edges array');
    }
    
    // Validate interfaces (optional)
    if (plan.interfaces) {
      if (!Array.isArray(plan.interfaces)) {
        throw new Error('interfaces must be an array when provided');
      }
      plan.interfaces.forEach((iface: any, index: number) => {
        if (!iface.id || typeof iface.id !== 'string') {
          throw new Error(`Interface at index ${index} must have a valid id`);
        }
      });
    }
    // Validate triggers
    plan.triggers.forEach((trigger: any, index: number) => {
      if (!trigger.id || typeof trigger.id !== 'string') {
        throw new Error(`Trigger at index ${index} must have a valid id`);
      }
    });

    // Validate mcps (steps)
    plan.mcps.forEach((step: any, index: number) => {
      if (!step.id || typeof step.id !== 'string') {
        throw new Error(`Step at index ${index} must have a valid id`);
      }
      if (!step.label || typeof step.label !== 'string') {
        throw new Error(`Step at index ${index} must have a valid label`);
      }
      // Validate CRUD-specific fields
      if (step.id.startsWith('crud/')) {
        // Extract operation, handling formats like "crud/update_rows/45" or "crud/update-row"
        let crudOperation = step.id.replace('crud/', '');

        // Remove any trailing /ID or other suffixes (e.g., "update_rows/45" -> "update_rows")
        if (crudOperation.includes('/')) {
          crudOperation = crudOperation.split('/')[0];
        }

        // Support multiple legacy formats:
        // - Singular with dash: create-row, update-row, delete-row, read-row
        // - Singular with underscore: insert_row, update_row, delete_row, read_row
        // - Plural with underscore: insert_rows, update_rows, delete_rows, read_rows
        const validOperations = [
          'create-row', 'create-column', 'read-row', 'update-row', 'delete-row', 'find-row', 'find-rows', 'find',
          'insert_row', 'update_row', 'delete_row', 'read_row', 'find_row',
          'insert_rows', 'update_rows', 'delete_rows', 'read_rows', 'find_rows'
        ];

        if (!validOperations.includes(crudOperation)) {
          throw new Error(`CRUD step at index ${index} has invalid operation: ${crudOperation}`);
        }

        // dataSourceId validation - warn but don't block loading for design-time flexibility
        if (!step.dataSourceId) {
          console.warn(`CRUD step at index ${index} has no dataSourceId - will need to be configured`);
          // Assign a placeholder dataSourceId to allow loading
          step.dataSourceId = 0;
        } else if (typeof step.dataSourceId !== 'number') {
          console.warn(`CRUD step at index ${index} has invalid dataSourceId type (${typeof step.dataSourceId}) - attempting conversion`);
          // Try to convert to number
          const parsedId = parseInt(step.dataSourceId as any, 10);
          if (!isNaN(parsedId)) {
            step.dataSourceId = parsedId;
          } else {
            console.warn(`CRUD step at index ${index} dataSourceId cannot be converted to number - using placeholder 0`);
            step.dataSourceId = 0;
          }
        }

        // crud object validation - warn but don't block loading
        if (!step.crud || typeof step.crud !== 'object') {
          console.warn(`CRUD step at index ${index} has no crud object - creating empty crud config`);
          step.crud = {};
        }

        // Validate operation-specific fields
        // Use warnings instead of errors for incomplete configurations to allow design-time flexibility
        switch (crudOperation) {
          case 'create-row':
          case 'insert_row': // Legacy format - treat same as create-row
          case 'insert_rows': // Legacy plural format - treat same as create-row
            if (!step.crud.rows || !Array.isArray(step.crud.rows) || step.crud.rows.length === 0) {
              // Don't throw - allow empty rows for design-time flexibility
              console.warn(`${crudOperation} step at index ${index} has no rows in crud.rows - will need to be configured`);
            } else {
              step.crud.rows.forEach((row: any, rowIndex: number) => {
                if (!row.id || typeof row.id !== 'string') {
                  console.warn(`${crudOperation} step at index ${index}, row ${rowIndex} has no valid id - will need to be configured`);
                }
                if (!row.columns || typeof row.columns !== 'object') {
                  console.warn(`${crudOperation} step at index ${index}, row ${rowIndex} has no columns object - will need to be configured`);
                }
              });
            }
            break;

          case 'create-column':
            if (!step.crud.columns || !Array.isArray(step.crud.columns) || step.crud.columns.length === 0) {
              // Don't throw - allow empty columns for design-time flexibility
              console.warn(`create-column step at index ${index} has no columns in crud.columns - will need to be configured`);
            } else {
              step.crud.columns.forEach((col: any, colIndex: number) => {
                if (!col.name || typeof col.name !== 'string') {
                  console.warn(`create-column step at index ${index}, column ${colIndex} has no valid name - will need to be configured`);
                }
                if (!col.type || typeof col.type !== 'string') {
                  console.warn(`create-column step at index ${index}, column ${colIndex} has no valid type - will need to be configured`);
                }
              });
            }
            break;

          case 'read-row':
          case 'read_row': // Legacy underscore format
          case 'read_rows': // Legacy plural format
          case 'update-row':
          case 'update_row': // Legacy underscore format
          case 'update_rows': // Legacy plural format
          case 'delete-row':
          case 'delete_row': // Legacy underscore format
          case 'delete_rows': // Legacy plural format
            // Where condition validation - warn but don't block
            if (!step.crud.where || typeof step.crud.where !== 'object') {
              console.warn(`${crudOperation} step at index ${index} has no where condition - will need to be configured`);
            } else {
              if (!step.crud.where.column || typeof step.crud.where.column !== 'string') {
                console.warn(`${crudOperation} step at index ${index} has no where.column - will need to be configured`);
              }
              if (!step.crud.where.operator || typeof step.crud.where.operator !== 'string') {
                console.warn(`${crudOperation} step at index ${index} has no where.operator - will need to be configured`);
              }
              // value is optional for IS NULL / IS NOT NULL operators
              // Also allow undefined/empty value for design-time (will be filled via template expressions at runtime)
              const noValueOperators = ['IS NULL', 'IS NOT NULL'];
              if (step.crud.where.operator && !noValueOperators.includes(step.crud.where.operator) && step.crud.where.value === undefined) {
                console.warn(`${crudOperation} step at index ${index} has no where.value for operator ${step.crud.where.operator} - will need to be set at runtime`);
              }
            }

            // update-row should have set columns - warn but don't block loading
            if (crudOperation === 'update-row' || crudOperation === 'update_row' || crudOperation === 'update_rows') {
              if (!step.crud.set || typeof step.crud.set !== 'object' || Object.keys(step.crud.set).length === 0) {
                console.warn(`${crudOperation} step at index ${index} has no columns in crud.set - will need to be configured`);
              }
            }

            // read-row limit validation - warn but don't block
            if ((crudOperation === 'read-row' || crudOperation === 'read_row' || crudOperation === 'read_rows') && step.crud.limit !== undefined) {
              if (typeof step.crud.limit !== 'number' || step.crud.limit < 1 || step.crud.limit > 50) {
                console.warn(`read-row step at index ${index} has invalid limit (must be 1-50) - will use default`);
              }
            }
            break;
        }
      }
    });

    // Validate agents (optional)
    if (plan.agents) {
      if (!Array.isArray(plan.agents)) {
        throw new Error('agents must be an array when provided');
      }
      plan.agents.forEach((agent: any, index: number) => {
        if (!agent.label || typeof agent.label !== 'string' || !agent.label.trim()) {
          throw new Error(`Agent at index ${index} must have a valid label`);
        }
      });
    }

    // Validate tables (CRUD operations - optional)
    if (plan.tables) {
      if (!Array.isArray(plan.tables)) {
        throw new Error('tables must be an array when provided');
      }
      const validCrudTypes = ['crud-create-row', 'crud-read-row', 'crud-update-row', 'crud-delete-row', 'crud-create-column', 'crud-find', 'crud-find-rows', 'crud-find-row'];
      plan.tables.forEach((table: any, index: number) => {
        if (!table.type || typeof table.type !== 'string') {
          throw new Error(`Table at index ${index} must have a valid type`);
        }
        if (!validCrudTypes.includes(table.type)) {
          throw new Error(`Table at index ${index} has invalid type: ${table.type}. Valid types: ${validCrudTypes.join(', ')}`);
        }
        if (!table.label || typeof table.label !== 'string' || !table.label.trim()) {
          throw new Error(`Table at index ${index} must have a valid label`);
        }
      });
    }

    // Validate V2 edges - simple from/to format with ports
    plan.edges.forEach((edge: any, index: number) => {
      // V2 edges require both from and to as non-empty strings
      if (!edge.from || typeof edge.from !== 'string' || edge.from.trim() === '') {
        throw new Error(`Edge at index ${index} must have a valid from field`);
      }
      if (!edge.to || typeof edge.to !== 'string' || edge.to.trim() === '') {
        throw new Error(`Edge at index ${index} must have a valid to field`);
      }
    });
  }
  
  /**
   * Extract tool slug from step id (format: "api-slug/tool-slug" or "tool-slug")
   */
  static extractToolSlug(stepId: string): { apiSlug?: string; toolSlug: string } {
    const parts = stepId.split('/');
    if (parts.length === 2) {
      return { apiSlug: parts[0], toolSlug: parts[1] };
    }
    return { toolSlug: parts[0] };
  }

  /**
   * Extract CRUD operation from step id (format: "crud/{operation}")
   * @returns The CRUD operation (create-row, read-row, etc.) or null if not a CRUD step
   */
  static extractCrudOperation(stepId: string): string | null {
    if (stepId.startsWith('crud/')) {
      return stepId.replace('crud/', '');
    }
    return null;
  }

  /**
   * Check if a step is a CRUD step
   */
  static isCrudStep(stepId: string): boolean {
    return stepId.startsWith('crud/');
  }
  
  /**
   * Extract trigger ID from "trigger:xxx" format
   */
  static extractTriggerId(fromOrTo: string | null | undefined): string | null {
    if (!fromOrTo || typeof fromOrTo !== 'string') {
      return null;
    }
    if (fromOrTo.startsWith('trigger:')) {
      return fromOrTo.replace('trigger:', '');
    }
    return null;
  }
  
  /**
   * Extract step label from "mcp:xxx" format
   */
  static extractMcpLabel(fromOrTo: string | null | undefined): string | null {
    if (!fromOrTo || typeof fromOrTo !== 'string') {
      return null;
    }
    if (fromOrTo.startsWith('mcp:')) {
      return fromOrTo.replace('mcp:', '');
    }
    return null;
  }

  /**
   * Extract agent label from "agent:xxx" format
   */
  static extractAgentLabel(fromOrTo: string | null | undefined): string | null {
    if (!fromOrTo || typeof fromOrTo !== 'string') {
      return null;
    }
    if (fromOrTo.startsWith('agent:')) {
      return fromOrTo.replace('agent:', '');
    }
    return null;
  }

  /**
   * Extract table label from "table:xxx" format (for CRUD operations)
   */
  static extractTableLabel(fromOrTo: string | null | undefined): string | null {
    if (!fromOrTo || typeof fromOrTo !== 'string') {
      return null;
    }
    if (fromOrTo.startsWith('table:')) {
      return fromOrTo.replace('table:', '');
    }
    return null;
  }

  /**
   * Extract merge label from "core:xxx" format
   */
  static extractMergeLabel(fromOrTo: string | null | undefined): string | null {
    if (!fromOrTo || typeof fromOrTo !== 'string') {
      return null;
    }
    if (fromOrTo.startsWith('core:')) {
      return fromOrTo.replace('core:', '');
    }
    return null;
  }

  /**
   * Extract step or agent label from "mcp:xxx" or "agent:xxx" format
   * Returns the label and whether it's an agent
   */
  static extractStepOrAgentLabel(fromOrTo: string | null | undefined): { label: string; isAgent: boolean } | null {
    if (!fromOrTo || typeof fromOrTo !== 'string') {
      return null;
    }
    if (fromOrTo.startsWith('mcp:')) {
      return { label: fromOrTo.replace('mcp:', ''), isAgent: false };
    }
    if (fromOrTo.startsWith('agent:')) {
      return { label: fromOrTo.replace('agent:', ''), isAgent: true };
    }
    return null;
  }

  /**
   * Extract control node label from "core:xxx" format.
   * Used for all control flow nodes (decision, switch, loop, split).
   */
  static extractCoreLabel(fromOrTo: string | null | undefined): string | null {
    if (!fromOrTo || typeof fromOrTo !== 'string') {
      return null;
    }
    if (fromOrTo.startsWith('core:')) {
      return fromOrTo.replace('core:', '');
    }
    return null;
  }

  /**
   * Parse a V2 edge ref (e.g., "core:check:if", "core:process:body", "mcp:fetch")
   * Returns { nodeType, nodeLabel, port } where port is optional.
   * Returns null for invalid refs (graceful handling for import).
   */
  static parseEdgeRef(ref: string | null | undefined): EdgeRef | null {
    if (!ref || typeof ref !== 'string') {
      return null;
    }
    try {
      return parseEdgeRefCore(ref);
    } catch {
      return null;
    }
  }

  /**
   * Check if an edge ref has a port (e.g., "core:check:if" has port, "mcp:fetch" doesn't)
   */
  static hasPort(ref: string | null | undefined): boolean {
    if (!ref || typeof ref !== 'string') {
      return false;
    }
    try {
      return hasPortCore(ref);
    } catch {
      return false;
    }
  }

  /**
   * Get the node key (nodeType:nodeLabel) from a V2 edge ref, stripping the port.
   */
  static getNodeKey(ref: string | null | undefined): string | null {
    if (!ref || typeof ref !== 'string') {
      return null;
    }
    try {
      return getNodeKeyCore(ref);
    } catch {
      return null;
    }
  }
}

















