import type { XYPosition, Connection } from 'reactflow';

export type NodeStatus = 'pending' | 'ready' | 'running' | 'completed' | 'failed' | 'skipped' | 'awaiting_signal';
export type DerivedNodeStatus = NodeStatus | 'partial_success';

export type StatusCounts = Record<string, number>;

export type BuilderNodeKind =
  | 'entry'
  | 'reasoning'
  | 'tool'
  | 'mcp'
  | 'action'
  | 'condition'  // backward compat (old saved workflows)
  | 'branch'     // backward compat
  | 'loop'
  | 'output'
  | 'interface'
  | 'decision'
  | 'merge'
  | 'switch'
  | 'split'
  | 'fork'
  | 'parallel'   // backward compat
  | 'download_file'
  | 'aggregate'
  | 'response'
  | 'exit'
  | 'transform'
  | 'wait'
  | 'http_request'
  | 'data_input'
  | 'set'
  | 'html_extract'
  | 'task'
  | 'whileGroup'
  | 'approval'
  | 'option'
  | 'guardrail'
  | 'classify'
  | 'browser_agent'  // Browser-driving agent (agent:browser_agent)
  | 'crud'
  | 'find'
  | 'data'       // backward compat
  | 'filter'
  | 'sort'
  | 'limit'
  | 'remove_duplicates'
  | 'summarize'
  | 'date_time'
  | 'crypto_jwt'
  | 'xml'
  | 'compression'
  | 'rss'
  | 'convert_to_file'
  | 'extract_from_file'
  | 'compare_datasets'
  | 'sub_workflow'
  | 'respond_to_webhook'
  | 'send_email'
  | 'email_inbox'
  | 'code'
  | 'set'
  | 'html_extract'
  | 'task'
  | 'stop_on_error'
  | 'ssh'
  | 'sftp'
  | 'database';

export type BuilderNodeType = 'flowNode' | 'decisionNode' | 'switchNode' | 'optionNode' | 'userApprovalNode' | 'splitNode' | 'aggregateNode' | 'exitNode' | 'responseNode' | 'noteNode' | 'mergeNode' | 'classifyNode' | 'guardrailNode' | 'forkNode' | 'interfaceNode' | 'whileGroupNode';


export type ConditionType = 'if' | 'elseif' | 'else';

export interface ConditionRow {
  id: string;
  type: ConditionType;
  label: string;
  expression?: string; // Expression pour la condition (utilisée avec ExpressionEditor)
}

// Switch/Case types
export type SwitchCaseType = 'case' | 'default';

export interface SwitchCaseRow {
  id: string;
  type: SwitchCaseType;
  label: string;
  value?: string; // The value to match against (only for 'case', not 'default')
}

// Option node types - Logical branching with expression-based routing
export interface OptionChoice {
  id: string;
  label: string;
  expression?: string; // Expression to evaluate for this branch
}

// Approval node types - User approval with multiple outcome branches
export interface ApprovalOutput {
  id: string;
  label: string;
}

/**
 * Optional external-channel delegation for a user approval node (plan `approval.delegation`).
 * v1 supports Telegram only: the pending approval is pushed to the chat as a message with
 * inline approve/reject buttons; a tap resolves the approval in addition to the in-app paths.
 * chatId, messageTemplate and image are template-capable ({{...}}); a blank messageTemplate
 * falls back to the resolved contextTemplate. A non-blank image (e.g. an interface node's
 * screenshot output, or an HTTP image URL) turns the message into a single photo with the
 * message text as caption and the same buttons. Empty allowedUserIds = anyone in the chat
 * can decide. approveLabel/rejectLabel are optional custom button texts (template-capable);
 * blank = the channel defaults ("✅ Approve" / "❌ Reject"). Only the button text changes;
 * the approve/reject outcome is unaffected.
 */
export interface ApprovalDelegation {
  channel?: 'telegram';
  credentialId?: number;
  chatId?: string;
  messageTemplate?: string;
  image?: string;
  allowedUserIds?: string[];
  approveLabel?: string;
  rejectLabel?: string;
}

/**
 * Split-context continuation of a user approval node (plan `approval.continuationMode`).
 * all_items (default): downstream steps start once after every per-item approval is decided.
 * per_item: each decided item continues its own downstream chain immediately; the first
 * cross-item consumer (merge, aggregate, loop, fork, nested split) still waits for all items.
 * No effect outside a split.
 */
export type ApprovalContinuationMode = 'all_items' | 'per_item';

// Classify node types - AI-powered classification with N outputs
export interface ClassifyCategory {
  id: string;
  label: string;
  description?: string; // Description to help the AI understand this category
}

// Guardrail node types - Content validation and safety checks
export type GuardrailType =
  | 'pii_detection'      // Detect PII (email, phone, SSN, credit card)
  | 'toxic_language'     // Detect toxic/offensive content
  | 'prompt_injection'   // Detect jailbreak attempts
  | 'keyword_filter'     // Block/allow specific keywords
  | 'regex_pattern'      // Custom regex validation
  | 'length_check'       // Min/max length validation
  | 'topic_restriction'  // Block specific topics (AI-based)
  | 'competitor_mention' // Block competitor mentions
  | 'custom';            // Custom validation logic

export type GuardrailAction = 'block' | 'sanitize' | 'flag';

export interface GuardrailRule {
  id: string;
  type: GuardrailType;
  action: GuardrailAction;
  config?: {
    // For keyword_filter
    keywordsExpression?: string; // Expression for keywords (comma-separated or variable)
    mode?: 'block' | 'allow'; // block these keywords or allow only these
    // For regex_pattern
    pattern?: string;
    // For length_check
    minLength?: number;
    maxLength?: number;
    // For pii_detection
    piiTypes?: ('email' | 'phone' | 'ssn' | 'credit_card' | 'address')[];
    // For topic_restriction / competitor_mention
    topicsExpression?: string; // Expression for topics (comma-separated or variable)
    // For custom
    expression?: string;
  };
}

export function createDefaultGuardrailRules(nodeId: string): GuardrailRule[] {
  return [
    {
      id: `${nodeId}-rule-1`,
      type: 'pii_detection',
      action: 'block',
      config: { piiTypes: ['email', 'phone', 'credit_card'] },
    },
  ];
}

/**
 * Per-node execution policy - mirrors the backend `NodePolicy` record
 * (orchestrator `domain/workflow/NodePolicy.java`). Serialized verbatim as the
 * plan-level `nodePolicy` block on executable node entries (mcps / tables /
 * agents / cores / interfaces). Triggers and notes never carry one (the backend
 * parser ignores them). The BACKEND is the single validator - it rejects
 * negative values, `continueOnFailure` on decision/switch/option cores, and
 * `executeOnce` on split/aggregate/merge/loop cores. The UI only mirrors those
 * rules for gating (see `utils/nodePolicy.ts`).
 *
 * All fields are optional; an absent field means "default" (= today's behavior).
 * A fully-default policy is represented by OMITTING the block entirely so plans
 * without a policy stay byte-identical.
 */
export interface NodePolicy {
  /** Additional attempts after a failed one (total attempts = retryCount + 1). */
  retryCount?: number;
  /** Delay between attempts, in milliseconds. */
  retryBackoffMs?: number;
  /** When ALL attempts fail, continue to successors instead of cascading SKIPPED. */
  continueOnFailure?: boolean;
  /** PER-ATTEMPT execution timeout in ms (0/absent = disabled). Best effort. */
  timeoutMs?: number;
  /** In a split context, execute only for split item 0; other items are SKIPPED. */
  executeOnce?: boolean;
}

/**
 * Per-node mock block (plan-level `mock`, sibling of `nodePolicy`) - can attach
 * to every executable entry (mcps / tables / agents / cores / interfaces).
 * Triggers, notes and split/merge/aggregate/loop/fork cores never carry one
 * (the backend parser rejects it there). The BACKEND is the single validator;
 * the UI only mirrors its gating (see `utils/nodeMock.ts`).
 *
 * Exactly one source is meaningful per mock:
 *  - static output (`output`, and/or `port` on port-selecting nodes),
 *  - `source: 'catalog_example'` (mcp catalog tools only, no output),
 *  - `error` (simulated failure, no port).
 * An empty block = no mock: it is OMITTED entirely so plans stay byte-identical.
 */
export interface NodeMock {
  /** Default true. false = the mock is configured but parked (not applied). */
  enabled?: boolean;
  /** Where the mocked output comes from. Default 'static'. */
  source?: 'static' | 'catalog_example' | 'error';
  /** Static output object served instead of executing the node. */
  output?: Record<string, unknown>;
  /** Branch to take - only on decision/switch/option/approval cores + classify agents. */
  port?: string;
  /** Simulated failure (message required). Mutually exclusive with port. */
  error?: {
    message: string;
    output?: Record<string, unknown>;
  };
}

export interface LoopChildDescriptor {
  id: string;
  label: string;
  description?: string;
  kind: BuilderNodeKind;
  badge?: string;
  nodeType: BuilderNodeType;
  status?: DerivedNodeStatus; // Status from streaming events
  statusCounts?: StatusCounts; // Normalized status counts from streaming events
  metrics?: {
    tokens?: string;
    latency?: string;
  };
  // Données complètes du nœud pour l'inspecteur
  toolData?: BuilderNodeData['toolData'];
  apiData?: BuilderNodeData['apiData'];
  dataSourceData?: BuilderNodeData['dataSourceData'];
  paramExpressions?: Record<string, string>;
  params?: string;
  output?: string;
  prompt?: string;
  model?: string;
  temperature?: number;
  maxTokens?: number;
  // Autres champs nécessaires
  [key: string]: any;
}

export interface BuilderNodeData {
  id: string;
  label: string;
  description?: string;
  kind: BuilderNodeKind;
  badge?: string;
  status?: DerivedNodeStatus; // Status from streaming events
  statusCounts?: StatusCounts; // Normalized status counts from streaming events
  metrics?: {
    tokens?: string;
    latency?: string;
  };
  loopChildren?: LoopChildDescriptor[];
  onExtractLoopChild?: (childId: string, action?: 'extract' | 'up' | 'down') => void;
  onLoopChildClick?: (childId: string) => void;
  onLoopClick?: () => void;
  selectedLoopChildId?: string;
  highlightState?: 'idle' | 'accepting';
  decisionConditions?: ConditionRow[];
  // Switch/Case fields
  switchExpression?: string; // The expression to switch on
  switchCases?: SwitchCaseRow[]; // The cases to match
  // Classify node fields - AI-powered classification with N outputs
  classifyCategories?: ClassifyCategory[]; // Categories for classification
  classifyParams?: string; // Params expression to classify
  // Guardrail node fields - Content validation and safety checks
  guardrailRules?: GuardrailRule[]; // Rules for validation
  guardrailParams?: string; // Params expression to validate
  // Option node fields - User choice with multiple branches
  optionChoices?: OptionChoice[]; // The available options for user selection
  // Approval node fields - User approval with outcome branches
  approvalOutputs?: ApprovalOutput[]; // The approval outcome paths
  approvalTimeoutMs?: number; // Timeout duration in milliseconds
  approvalContextTemplate?: string; // Template (literal + {{...}}) resolved at pause time and shown to the approver
  approvalDelegation?: ApprovalDelegation; // Optional external-channel delegation (v1: telegram); undefined = in-app only
  approvalContinuationMode?: ApprovalContinuationMode; // Split-context continuation; undefined = all_items (default)
  // Branch selection (set by streaming batch-update for decision/switch/approval nodes)
  selectedBranch?: string; // The selected port (e.g., "if", "else", "approved", "rejected")
  onDeleteNode?: (nodeId: string) => void;
  onDuplicateNode?: (nodeId: string) => void;
  onTogglePreview?: (nodeId: string) => void;
  onNodeUpdate?: (data: BuilderNodeData) => void;
  // Note-specific fields
  noteText?: string;
  // Expression-specific fields
  paramExpressions?: Record<string, string>; // Map param name to expression string
  // Per-node execution policy (retry / backoff / continue-on-fail / timeout / execute-once).
  // Only present when non-default; round-tripped as the plan-level `nodePolicy` block.
  nodePolicy?: NodePolicy;
  // Per-node mock block (editor runs serve it instead of executing the node).
  // Only present when configured; round-tripped as the plan-level `mock` block.
  mock?: NodeMock;
  noteColor?: string;
  noteBorderColor?: string;
  noteTextColor?: string;
  noteWidth?: number;
  noteHeight?: number;
  onNoteUpdate?: (updates: { noteText?: string; noteColor?: string; noteBorderColor?: string; noteTextColor?: string; noteWidth?: number; noteHeight?: number }) => void;
  // Advanced mode fields
  params?: string;
  output?: string;
  // Loop condition
  loopCondition?: string; // Expression pour la condition de boucle (utilisée avec ExpressionEditor)
  maxIterations?: number; // Nombre maximum d'itérations pour une boucle (1-50) ou pour un agent (tool calling rounds)
  strategy?: string; // Stratégie de la boucle ('continue-anyway' ou 'stop-on-error')
  currentIteration?: number; // Itération courante (mise à jour depuis les événements streaming)
  // Split-specific fields
  list?: string; // Expression SpEL returning a list of items to iterate over (alias: listExpression)
  maxItems?: number; // Maximum number of items to process from the list
  splitStrategy?: string; // Strategy for error handling ('continue-anyway' ou 'stop-on-error')
  // Agent-specific fields
  agentType?: 'agent' | 'guardrail' | 'classify' | 'browser_agent';  // Type of agent node
  prompt?: string;
  // Browser Agent fields (agent:browser_agent)
  // task        - natural-language goal (config-time, e.g. "go to gmail.com and read inbox")
  // lastBrowserSessionId  - populated by SSE stream consumer; used to open the live CDP panel
  // lastBrowserStepIndex  - last reported step number (e.g. 12)
  // lastBrowserCostUsd    - running cost of the browser session in USD
  // lastBrowserAction     - short label for the latest action ("click #login", "type ...")
  // lastBrowserCdpToken   - short-lived (5-min) JWT for the wss:// CDP upgrade; the orchestrator
  //                         re-mints via /cdp-token-refresh when the panel reconnects after expiry
  // lastBrowserCdpWsUrl   - wss://websearch-host/cdp/{sessionId} URL for the live-view bridge
  // lastBrowserRunId      - current workflow run id; required by the panel to call takeover-resume
  //                         and cdp-token-refresh on the right (runId, nodeId) pair
  task?: string;
  lastBrowserSessionId?: string;
  lastBrowserStepIndex?: number;
  lastBrowserCostUsd?: number;
  lastBrowserAction?: string;
  lastBrowserCdpToken?: string;
  lastBrowserCdpWsUrl?: string;
  lastBrowserRunId?: string;
  lastBrowserCurrentUrl?: string;
  // Control node id for the panel's REST calls (takeover-resume /
  // cdp-token-refresh / final-screenshot). Differs from the builder node id
  // when a GENERIC agent node hosts the browser session (it is the tool-call
  // id the runner is keyed by); equals the builder node id for the dedicated
  // agent:browser_agent node. Published on the wire as control_node_id.
  lastBrowserNodeId?: string;
  promptRequired?: boolean;
  model?: string;
  modelRequired?: boolean;
  temperature?: number;
  temperatureRequired?: boolean;
  maxTokens?: number;
  maxTokensRequired?: boolean;
  // Extended agent configuration fields
  provider?: string;
  maxTools?: number;
  autoDiscoverTools?: boolean;
  systemPrompt?: string;
  // Agent entity reference (for agentType='agent' only)
  agentConfigId?: string;        // UUID of the agent entity
  agentConfigName?: string;      // Display name (cached for UI)
  agentAvatarUrl?: string;       // Agent avatar (preset or custom URL)
  withMemory?: boolean;          // Toggle: use agent's conversation as context (default: true)
  // MCP API-specific fields
  apiData?: {
    apiId?: string;
    apiSlug?: string;
    apiName: string;
    baseUrl?: string;
    iconSlug?: string;
    iconUrl?: string;
  };
  // MCP Tool-specific fields
  toolData?: {
    toolId?: string;
    toolSlug?: string;
    toolName?: string;
    apiId?: string;
    apiSlug?: string;
    apiName: string;
    endpoint?: string;
    method: string;
    iconSlug?: string;
    iconUrl?: string;
    parameters?: Array<{
      id?: string;
      name: string;
      dataType?: string;
      type?: string;
      isRequired?: boolean;
      required?: boolean;
      description?: string;
      defaultValue?: string;
      exampleValue?: string;
    }>;
    credentials?: Array<{
      credentialName: string;
      isRequired: boolean;
      usage?: string;
      displayName?: string;
      description?: string;
      authType?: string;
      iconUrl?: string;
      credentialType?: string;
      testEndpoint?: string;
      documentationUrl?: string;
      properties?: string;
    }>;
    responses?: Array<Record<string, any>>;
  };
  // DataSource-specific fields
  dataSourceData?: {
    dataSourceId: number;
    dataSourceName: string;
    tableName?: string;
    schema?: string;
    // Expressions for each column (column field -> expression)
    columnExpressions?: Record<string, string>;
    // Custom labels for each column (column field -> label)
    columnLabels?: Record<string, string>;
    // Event-driven trigger config (tables-trigger only)
    // Which row changes should fire the workflow. Default: all three.
    eventTypes?: Array<'row_created' | 'row_updated' | 'row_deleted'>;
    // Optional server-side row filter - only matching rows fire the trigger.
    filter?: {
      column: string;
      operator: '=' | '!=' | '>' | '>=' | '<' | '<=' | 'in' | 'not_in' | 'contains' | 'starts_with' | 'ends_with' | 'is_null' | 'is_not_null';
      value?: unknown;
    } | null;
  };
  // Interface-specific fields
  interfaceData?: {
    interfaceId?: string;
    interfaceName?: string;
    editorExpression?: string;
    cssTemplate?: string;
    jsTemplate?: string;
    previewWidth?: number;
    previewHeight?: number;
    showPreview?: boolean;
    variableMapping?: Record<string, string>; // {genericVar: workflowExpression}
    actionMapping?: Record<string, string>; // {cssSelector: "trigger:label:actiontype"}
    dataSourceId?: string | number | null;
    isEntryInterface?: boolean; // Whether this is the entry interface (shown first)
    generateScreenshot?: boolean; // When true, capture a PNG of the rendered interface and expose it as the `screenshot` FileRef output
    generatePdf?: boolean; // When true, render the interface to a PDF and expose it as the `pdf` FileRef output
    pdfFormat?: string; // Page size for generatePdf: 'A4' | 'Letter' | 'Legal' (default A4)
    pdfLandscape?: boolean; // When true, render the generatePdf output in landscape orientation
    generateVideo?: boolean; // When true, record the interface's animation to an MP4 and expose it as the `video` FileRef output
    videoPreset?: string; // Capture format for generateVideo: 'vertical' (1080x1920) | 'horizontal' (1920x1080) | 'square' (1080x1080)
    videoMaxDurationSeconds?: number; // Recording ceiling in seconds for generateVideo (5-120, default 30)
    videoMode?: string; // Render mode for generateVideo: 'smooth' (offline frame-by-frame, fluid, default) | 'live' (real-time fallback)
    videoFps?: number; // Output frame rate for generateVideo (10-60, default 30)
    exposeRenderedSource?: boolean; // When true, expose `rendered_html`, `rendered_css`, `rendered_js` string outputs (resolved interface templates)
    templateVariables?: string[]; // Template variables from the interface DB entity
  };
  // Flag for unsaved interface changes
  hasUnsavedInterfaceChanges?: boolean;
  // Runtime items per page (for interface nodes in run mode)
  runtimeItemsPerPage?: number;
  // Parent loop ID for nodes inside a while loop
  parentLoopId?: string;
  // WhileGroup container fields
  whileCondition?: string;
  whileGroupWidth?: number;
  whileGroupHeight?: number;
  // Callbacks for node creation and connection
  onCreateNode?: (item: PaletteDragItem, position: XYPosition, options?: { parentId?: string }) => void;
  onConnect?: (connection: Connection) => void;
  // Merge node inputs
  mergeInputs?: Array<{
    id: string;
    label: string;
  }>;
  // Fork node outputs
  forkOutputs?: Array<{
    id: string;
    label: string;
  }>;
  // Transform node mappings
  transformMappings?: Array<{
    id: string;
    label: string;
    expression: string;
  }>;
  // Wait node duration (in milliseconds)
  waitDuration?: number;
  // Data Input node fields (multi-item)
  dataInputItems?: Array<{
    id: string;
    label: string;
    type: 'text' | 'file';
    text?: string;
    file?: { _type: 'file'; path: string; name: string; mimeType: string; size: number } | null;
  }>;
  dataInputWidth?: number;
  dataInputHeight?: number;
  // Download File node resize dimensions
  downloadFileWidth?: number;
  downloadFileHeight?: number;
  // HTTP Request node data
  httpRequestData?: {
    method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD' | 'OPTIONS';
    url: string;
    authType: 'none' | 'basic' | 'bearer' | 'api-key' | 'custom-header';
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
    queryParams?: Array<{ id: string; key: string; value: string }>;
    headers?: Array<{ id: string; key: string; value: string }>;
    bodyType?: 'none' | 'json' | 'form-data' | 'x-www-form-urlencoded' | 'raw';
    body?: string;
    contentType?: string;
    timeout?: number;
  };
  // Manual trigger data (custom fields for manual triggers)
  manualTriggerData?: {
    actionType: string;
    customFields?: Array<{
      id: string;
      name: string;
      value: string;
    }>;
  };
  // Form trigger data (custom form fields)
  formTriggerData?: {
    fields?: Array<{
      id: string;
      name: string;
      type: string;
      required?: boolean;
      defaultValue?: string;
    }>;
  };
  // Dynamic loop ID (added at runtime for loop child nodes)
  _loopId?: string;
  // Aggregate node fields (for collecting items from Split branches)
  itemsProcessed?: number;
  aggregateMode?: 'all' | 'first' | 'last' | 'count';
  // Filter node fields
  filterConditions?: Array<{
    id: string;
    field: string;
    operator: string;
    value: string;
  }>;
  filterMode?: 'and' | 'or';
  // Sort node fields
  sortFields?: Array<{
    id: string;
    field: string;
    direction: 'asc' | 'desc';
  }>;
  // Limit node fields
  limitCount?: number;
  limitFrom?: 'first' | 'last';
  limitOffset?: number;
  // RemoveDuplicates node fields
  deduplicateFields?: string[];
  deduplicateKeep?: 'first' | 'last';
  // Fleet canvas: bottom source handle for agent→resource connections
  fleetBottomHandles?: boolean;
  // Fleet canvas: top target handle for resource nodes
  fleetTopHandle?: boolean;
  // Fleet canvas: which bottom source handles to show on agent nodes
  fleetHandles?: ('model' | 'resources')[];
  // Fleet canvas: collapsible group node
  fleetCollapsible?: boolean;
  // Fleet canvas: number of children in this collapsible group
  fleetGroupChildCount?: number;
  // Fleet canvas: resource counts for agent-level collapse badges
  fleetResourceCounts?: {
    tools: number;
    workflows: number;
    interfaces: number;
    tables: number;
    conversations: number;
    skills: number;
    webSearch: boolean;
  };
  // Fleet canvas: current collapsed state (injected by canvas)
  fleetIsCollapsed?: boolean;
  // Fleet canvas: toggle collapse callback (injected by canvas)
  onToggleCollapse?: (nodeId: string) => void;
}

export interface PaletteItem {
  id: string;
  label: string;
  description: string;
  kind: BuilderNodeKind;
  nodeType: BuilderNodeType;
  badge?: string;
  group: 'Inputs' | 'Actions' | 'Logic' | 'Loops' | 'Outputs' | 'Data';
}

export type PaletteDragItem = Omit<PaletteItem, 'group'>;

export interface NodeVisuals {
  accent: string;
  iconBg: string;
  badgeBg: string;
  textClass: string;
  bgColor?: string;
  borderColor?: string;
}

export function createDefaultDecisionConditions(nodeId: string): ConditionRow[] {
  return [
    { id: `${nodeId}-if`, type: 'if', label: 'IF', expression: '' },
    { id: `${nodeId}-else`, type: 'else', label: 'ELSE', expression: '' },
  ];
}

export function createDefaultSwitchCases(nodeId: string): SwitchCaseRow[] {
  return [
    { id: `${nodeId}-case-1`, type: 'case', label: 'Case 1', value: '' },
    { id: `${nodeId}-case-2`, type: 'case', label: 'Case 2', value: '' },
    { id: `${nodeId}-default`, type: 'default', label: 'Default' },
  ];
}

export function createDefaultOptionChoices(nodeId: string): OptionChoice[] {
  return [
    { id: `${nodeId}-option-1`, label: 'Option 1', expression: '' },
    { id: `${nodeId}-option-2`, label: 'Option 2', expression: '' },
  ];
}

export function createDefaultApprovalOutputs(nodeId: string): ApprovalOutput[] {
  return [
    { id: `${nodeId}-approved`, label: 'Approved' },
    { id: `${nodeId}-rejected`, label: 'Rejected' },
    { id: `${nodeId}-timeout`, label: 'Timeout' },
  ];
}

export function createDefaultClassifyCategories(nodeId: string): ClassifyCategory[] {
  return [
    { id: `${nodeId}-category-1`, label: 'Category 1', description: '' },
    { id: `${nodeId}-category-2`, label: 'Category 2', description: '' },
  ];
}

export function createDefaultForkOutputs(nodeId: string): Array<{ id: string; label: string }> {
  return [
    { id: `${nodeId}-output-1`, label: 'Branch 1' },
    { id: `${nodeId}-output-2`, label: 'Branch 2' },
  ];
}

// ============================================================================
// Field Types - Unified type system for Input/Output columns
// ============================================================================

/**
 * Unified field types used across Input/Output columns.
 * Use these constants instead of raw strings for consistency.
 */
export type FieldType = 'text' | 'number' | 'boolean' | 'datetime' | 'object' | 'array';

/**
 * Color classes for each field type.
 * Follows the pattern: bg-{color}-100 dark:bg-{color}-900/30 text-{color}-700 dark:text-{color}-300
 */
export const FIELD_TYPE_COLORS: Record<FieldType, string> = {
  text: 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300',
  number: 'bg-green-100 dark:bg-green-900/30 text-green-700 dark:text-green-300',
  boolean: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-300',
  datetime: 'bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300',
  object: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-300',
  array: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700 dark:text-orange-300',
};

/**
 * Default color class for unknown field types.
 */
export const FIELD_TYPE_DEFAULT_COLOR = 'bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-400';

/**
 * Get the color class for a field type.
 * Returns the default color if the type is unknown.
 */
export function getFieldTypeColor(type: string | undefined): string {
  if (!type) return FIELD_TYPE_DEFAULT_COLOR;
  return FIELD_TYPE_COLORS[type as FieldType] || FIELD_TYPE_DEFAULT_COLOR;
}

/**
 * Map legacy/alternative type names to unified FieldType.
 * Use this to normalize types from external sources (e.g., database columns).
 */
export function normalizeFieldType(type: string | undefined): FieldType {
  if (!type) return 'text';

  const normalized = type.toLowerCase();

  // Map legacy 'date' to 'datetime'
  if (normalized === 'date') return 'datetime';

  // Map 'json' and 'obj' to 'object'
  if (normalized === 'json' || normalized === 'obj') return 'object';

  // Map 'arr' to 'array'
  if (normalized === 'arr') return 'array';

  // Map integer types to 'number'
  if (normalized === 'integer' || normalized === 'int' || normalized === 'float' || normalized === 'double') {
    return 'number';
  }

  // Map string types to 'text'
  if (normalized === 'string' || normalized === 'varchar' || normalized === 'char') {
    return 'text';
  }

  // Map bool types to 'boolean'
  if (normalized === 'bool') return 'boolean';

  // Return as-is if it's a valid FieldType
  if (normalized in FIELD_TYPE_COLORS) return normalized as FieldType;

  // Default to 'text' for unknown types
  return 'text';
}
