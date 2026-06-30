import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData, NodePolicy } from '../types';

/**
 * V2 Edge format - Pure graph model.
 *
 * === 7-PREFIX SYSTEM ===
 *
 * | Prefix     | Category  | Array     | Applies To                              |
 * |------------|-----------|-----------|----------------------------------------|
 * | trigger:   | Entry     | triggers  | All triggers (webhook, chat, schedule)  |
 * | mcp:       | Action    | mcps      | MCP tool calls                          |
 * | table:     | Data      | tables    | CRUD operations (database tables)       |
 * | agent:     | AI        | agents    | AI agents                               |
 * | core:      | Control   | cores     | Decision, Switch, Loop, Split, Merge, Fork, Transform, Wait, Download File, HTTP Request, Data Input, User Approval |
 * | note:      | Doc       | notes     | Notes                                   |
 * | interface: | UI        | interfaces| Interfaces                              |
 *
 * Each edge has a simple from/to structure where:
 * - `from`: Source node reference with optional port (e.g., "core:check:if", "core:process:body")
 * - `to`: Target node reference (e.g., "mcp:fetch_data", "core:process")
 * - `params`: Optional params mapping for the target node
 *
 * Ports are used for control nodes with multiple outputs:
 * - decision: :if, :else, :elseif_0, :elseif_1, ...
 * - switch: :case_0, :case_1, ..., :default
 * - loop: :body (first body step), :exit (when condition is false), :iterate (input, loop back)
 * - fork: :branch_0, :branch_1, ...
 */
export interface EdgeV2 {
  from: string;
  to: string;
  params?: Record<string, any>;
}

/**
 * Main workflow plan structure sent to the backend.
 */
export interface WorkflowPlan {
  name?: string; // Workflow name - used for breadcrumb editing
  description?: string; // Workflow description
  triggers: Array<{
    id: string;
    type: string;
    label?: string;
    params?: Record<string, any>;
    position?: { x: number; y: number };
  }>;
  mcps: Array<{
    id: string;
    type?: 'mcp'; // Only MCP catalog tools (default: mcp)
    label: string;
    iconSlug?: string; // Service icon slug for workflow card display
    position?: { x: number; y: number };
    params?: Record<string, any>;
    selectedCredentialId?: number; // User credential pinned in the workflow builder
    credentialSource?: 'user' | 'platform';
    platformCredentialId?: number; // Platform credential pinned when credentialSource=platform
    nodePolicy?: NodePolicy; // Per-node execution policy (retry/backoff/continue-on-fail/timeout/execute-once)
  }>;
  agents?: Array<{
    id: string;
    type: 'agent' | 'guardrail' | 'classify' | 'browser_agent'; // Required: agent type
    label: string;
    agentConfigId?: string;  // UUID of agent entity (for type='agent' only)
    agentConfigName?: string; // Display name of the agent entity (cached for UI)
    withMemory?: boolean;    // Use agent's conversation as context (default: true)
    provider?: string;
    model?: string;
    systemPrompt?: string;
    prompt?: string; // Required for type='agent'
    temperature?: number;
    maxTokens?: number;
    maxIterations?: number;
    maxTools?: number;
    tools?: string[]; // References to tool nodes: "mcp:label" or "agent:label"
    position?: { x: number; y: number };
    params?: Record<string, any>;
    // Classify-specific fields (required for type='classify')
    classifyCategories?: Array<{
      id: string;
      label: string;
      description?: string;
    }>;
    classifyParams?: string;
    // Guardrail-specific fields (required for type='guardrail')
    guardrailRules?: Array<{
      id: string;
      type: string;
      action: string;
      config?: Record<string, any>;
    }>;
    guardrailParams?: string;
    nodePolicy?: NodePolicy; // Per-node execution policy (retry/backoff/continue-on-fail/timeout/execute-once)
  }>;
  tables?: Array<{
    id?: string;
    type: 'crud-create-row' | 'crud-read-row' | 'crud-update-row' | 'crud-delete-row' | 'crud-create-column' | 'crud-find'; // CRUD type (required)
    label: string;
    position?: { x: number; y: number };
    params?: Record<string, any>;
    dataSourceId?: string;
    crud?: {
      where?: { column: string; operator: string; value: any };
      set?: Record<string, any>;
      rows?: Array<{ id: string; columns: Record<string, any> }>;
      columns?: Array<{ name: string; type: string; defaultValue?: any }>;
      limit?: number;
      offset?: number;
    };
    // For crud-find: list expression for parallel iteration over rows
    list?: string;
    maxItems?: number;
    splitStrategy?: string;
    nodePolicy?: NodePolicy; // Per-node execution policy (retry/backoff/continue-on-fail/timeout/execute-once)
  }>;
  cores?: Array<{
    id: string;
    type: 'decision' | 'switch' | 'loop' | 'split' | 'merge' | 'fork' | 'transform' | 'wait' | 'download_file' | 'aggregate' | 'exit' | 'response' | 'option' | 'http_request' | 'data_input' | 'approval'
      | 'filter' | 'sort' | 'limit' | 'remove_duplicates' | 'summarize'
      | 'date_time' | 'crypto_jwt' | 'xml' | 'compression' | 'rss'
      | 'convert_to_file' | 'extract_from_file' | 'compare_datasets'
      | 'sub_workflow' | 'respond_to_webhook' | 'send_email' | 'email_inbox' | 'code' | 'end'
      | 'set' | 'html_extract' | 'task' | 'stop_on_error' | 'ssh' | 'sftp' | 'database';
    label?: string;
    position?: { x: number; y: number };
    params?: Record<string, any>;
    // For decision nodes (if/elseif/else)
    decisionConditions?: Array<{
      id: string;
      type: 'if' | 'elseif' | 'else';
      label: string;
      expression?: string;
    }>;
    // For switch nodes (switch/case)
    switchExpression?: string;
    switchCases?: Array<{
      id: string;
      type: 'case' | 'default';
      label: string;
      value?: string; // Value to match (null for default)
    }>;
    // For loop nodes
    loopCondition?: string;
    maxIterations?: number;
    strategy?: string;
    // For split nodes (formerly forEach)
    list?: string; // Canonical name (listExpression accepted for backward compat)
    maxItems?: number;
    splitStrategy?: string;
    // For fork nodes (parallel branching)
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
      headers?: Record<string, string>;
      timeout?: number;
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
    // For option nodes (logical branching with expression-based routing)
    optionChoices?: Array<{
      id: string;
      label: string;
      expression?: string;
    }>;
    // For data input nodes (multi-item)
    dataInput?: {
      items: Array<{
        id: string;
        label: string;
        type: 'text' | 'file';
        text?: string;
        file?: { _type: string; path: string; name: string; mimeType: string; size: number } | null;
      }>;
    };
    dataInputWidth?: number;
    dataInputHeight?: number;
    // For approval nodes
    approval?: {
      timeoutMs?: number;
      approverRoles?: string[];
      requiredApprovals?: number;
    };
    message?: string;
    approvalOutputs?: Array<{
      id: string;
      label: string;
      targetStep?: string;
    }>;
    // For filter nodes
    filter?: {
      conditions: Array<{ field: string; operator: string; value: string }>;
      mode: string;
    };
    // For sort nodes
    sort?: {
      fields: Array<{ field: string; direction: string }>;
    };
    // For limit nodes
    limit?: {
      count: number;
      from: string;
      offset: number;
    };
    // For remove_duplicates nodes
    removeDuplicates?: {
      fields: string[];
      keep: string;
    };
    // For summarize nodes
    summarize?: {
      aggregations: Array<Record<string, any>>;
      groupBy: string[];
    };
    // For date_time nodes
    dateTime?: {
      operation?: string;
      value?: string;
      inputFormat?: string;
      outputFormat?: string;
      timezone?: string;
      targetTimezone?: string;
      durationUnit?: string;
      durationAmount?: number;
      secondValue?: string;
      extractPart?: string;
    };
    // For crypto_jwt nodes
    cryptoJwt?: {
      operation?: string;
      algorithm?: string;
      value?: string;
      key?: string;
      secret?: string;
      token?: string;
      payload?: string;
      encoding?: string;
    };
    // For xml nodes
    xml?: {
      operation?: string;
      value?: string;
      rootElement?: string;
      preserveAttributes?: boolean;
    };
    // For compression nodes
    compression?: {
      operation?: string;
      format?: string;
      value?: string;
      filename?: string;
    };
    // For rss nodes
    rss?: {
      url?: string;
      maxItems?: number;
    };
    // For convert_to_file nodes
    convertToFile?: {
      format?: string;
      value?: string;
      filename?: string;
      delimiter?: string;
      includeHeaders?: boolean;
    };
    // For extract_from_file nodes
    extractFromFile?: {
      format?: string;
      value?: string;
      delimiter?: string;
      sheetName?: string;
      hasHeaders?: boolean;
    };
    // For compare_datasets nodes
    compareDatasets?: {
      inputA?: string;
      inputB?: string;
      matchFields?: string[];
      returnMatched?: boolean;
      returnOnlyA?: boolean;
      returnOnlyB?: boolean;
    };
    // For sub_workflow nodes
    subWorkflow?: {
      workflowId?: string;
      inputMapping?: Record<string, string>;
      timeoutSeconds?: number;
      maxDepth?: number;
    };
    // For respond_to_webhook nodes
    respondToWebhook?: {
      statusCode?: number;
      body?: string;
      contentType?: string;
      headers?: Record<string, string>;
    };
    // For send_email nodes
    sendEmail?: {
      smtpHost?: string;
      smtpPort?: number;
      smtpUsername?: string;
      smtpPassword?: string;
      smtpUseTls?: boolean;
      fromEmail?: string;
      fromName?: string;
      toEmail?: string;
      ccEmail?: string;
      bccEmail?: string;
      subject?: string;
      body?: string;
      isHtml?: boolean;
      inReplyTo?: string;
      references?: string;
    };
    // For email_inbox nodes
    emailInbox?: {
      credentialId?: number | null;
      folder?: string;
      unreadOnly?: boolean;
      limit?: number;
      markSeen?: boolean;
      sinceDays?: number;
      action?: string;
      messageUid?: string;
      targetFolder?: string;
      fromContains?: string;
      subjectContains?: string;
      bodyContains?: string;
      flaggedOnly?: boolean;
      beforeDays?: number;
      downloadAttachments?: boolean;
    };
    // For code nodes
    code?: {
      language?: string;
      code?: string;
      timeoutSeconds?: number;
    };
    // Per-node execution policy (retry/backoff/continue-on-fail/timeout/execute-once).
    // Backend rejects continueOnFailure on decision/switch/option and
    // executeOnce on split/aggregate/merge/loop (see WorkflowPlanParser).
    nodePolicy?: NodePolicy;
  }>;
  notes?: Array<{
    id: string;
    type?: 'note'; // Always 'note'
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
    showPreview?: boolean;
    previewWidth?: number;
    previewHeight?: number;
    variableMapping?: Record<string, string>;
    actionMapping?: Record<string, string>;
    nodePolicy?: NodePolicy; // Per-node execution policy (retry/backoff/continue-on-fail/timeout/execute-once)
  }>;
  edges: EdgeV2[];
}

/**
 * Type aliases for React Flow nodes/edges with builder data.
 */
export type BuilderNode = Node<BuilderNodeData>;
export type BuilderEdge = Edge;
