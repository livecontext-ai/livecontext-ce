/**
 * AUTO-GENERATED FILE - DO NOT EDIT DIRECTLY
 *
 * Generated from: shared/contracts/node-contracts.schema.json
 *
 * To regenerate: npm run contracts:generate:ts
 */

// ═══════════════════════════════════════════════════════════════
// MANUAL TRIGGER
// ═══════════════════════════════════════════════════════════════

export interface ManualTriggerCustomField {
  name: string;
  value: string;
}

/**
 * Parameters for Manual Trigger
 * Triggered manually by user clicking a button
 */
export interface ManualTriggerParameters {
  label: string;
  customFields?: Record<string, any>[];
}

/**
 * Outputs produced by Manual Trigger
 */
export interface ManualTriggerOutputs {
  /** Dynamic fields: {customField.name} - Dynamic fields from trigger.params(), flattened at root */
  [key: string]: any;
  /** ISO timestamp when the workflow was triggered. Emitted snake_case by ManualTriggerResolver, matching node_type_documentation seed (V11). */
  triggered_at?: string;
  /** Display name of the user who triggered the workflow (resolved via AuthClient.getDisplayName). Empty string when no profile is available. Never the raw tenantId. */
  triggered_by?: string;
  /** Added by TriggerNode for all trigger types */
  trigger_id?: string;
  /** Added by TriggerNode for all trigger types */
  item_id?: string;
  /** Added by TriggerNode for all trigger types */
  item_index?: number;
  /** Single-item array with triggered_at + triggered_by + custom fields */
  data?: any[];
  /** Always 1 for manual triggers */
  count?: number;
}

// ═══════════════════════════════════════════════════════════════
// CHAT TRIGGER
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Chat Trigger
 * Triggered when user sends a message matching a pattern
 */
export interface ChatTriggerParameters {
  matchType?: 'any' | 'startsWith' | 'endsWith' | 'contains' | 'equals' | 'regex' | 'command';
  pattern?: string;
  caseSensitive?: boolean;
  trimPrefix?: boolean;
  trimSuffix?: boolean;
}

/**
 * Outputs produced by Chat Trigger
 */
export interface ChatTriggerOutputs {
  /** Raw user message content */
  message?: string;
  /** Message with prefix/suffix trimmed based on trimPrefix/trimSuffix config */
  extracted_message?: string;
  /** Conversation identifier (snake_case) */
  conversation_id?: string;
  /** Whether the message matched the pattern */
  matched?: boolean;
  /** The match type used (any, starts_with, ends_with, contains, equals, regex) */
  match_type?: string;
  /** The configured pattern value */
  match_value?: string;
  /** File attachments sent with the message. Each item is a canonical FileRef: { _type:'file', path, name, mimeType, size } */
  attachments?: any[];
  /** ISO timestamp when trigger fired (snake_case, unified across triggers) */
  triggered_at?: string;
  /** Display name of the user whose chat message fired the trigger (empty when unknown). Never tenantId. */
  triggered_by?: string;
  /** Added by TriggerNode */
  trigger_id?: string;
  /** Added by TriggerNode */
  item_id?: string;
  /** Added by TriggerNode */
  item_index?: number;
  /** Single-item array with message, extracted_message, triggered_at, triggered_by, matched */
  data?: any[];
  /** 1 if matched, 0 if no match */
  count?: number;
}

// ═══════════════════════════════════════════════════════════════
// WEBHOOK TRIGGER
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Webhook Trigger
 * Triggered via HTTP request to a webhook URL. Body and query params are flattened at root level.
 */
export interface WebhookTriggerParameters {
  httpMethod?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  authType?: 'none' | 'basic' | 'header' | 'jwt';
  basicUsername?: string;
  basicPassword?: string;
  authHeaderName?: string;
  authHeaderValue?: string;
  jwtSecretKey?: string;
  jwtAlgorithm?: 'HS256' | 'HS384' | 'HS512';
}

/**
 * Outputs produced by Webhook Trigger
 */
export interface WebhookTriggerOutputs {
  /** Dynamic fields: {body_field} - All HTTP body fields flattened at root level. For GET requests, query params are used instead.; Dynamic fields: {query_param} - Query string params merged into root (for non-GET, merged with body; for GET, query only) */
  [key: string]: any;
  /** HTTP method used (GET, POST, etc.). Added by WebhookController. */
  _webhookMethod?: string;
  /** ISO timestamp when webhook was received. Added by WebhookController. */
  _webhookTimestamp?: string;
  /** ISO timestamp when the webhook fired (snake_case, unified across triggers) */
  triggered_at?: string;
  /** Display name of the workflow owner. Empty when the webhook is unauthenticated. */
  triggered_by?: string;
  /** Added by TriggerNode */
  trigger_id?: string;
  /** Added by TriggerNode */
  item_id?: string;
  /** Added by TriggerNode */
  item_index?: number;
}

// ═══════════════════════════════════════════════════════════════
// SCHEDULE TRIGGER
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Schedule Trigger
 * Triggered on a schedule defined by cron expression
 */
export interface ScheduleTriggerParameters {
  cron: string;
  timezone?: string;
  maxExecutions?: number;
  enabled?: boolean;
}

/**
 * Outputs produced by Schedule Trigger
 */
export interface ScheduleTriggerOutputs {
  /** ISO timestamp of trigger. Raw: triggeredAt (camelCase), SchemaMapper normalizes to triggered_at. */
  triggered_at?: string;
  /** Display name of the workflow owner. Schedule fires autonomously but still carries the owner identity for variable_mapping. */
  triggered_by?: string;
  /** Current execution number (1-based). Raw: executionCount (camelCase), SchemaMapper normalizes. */
  execution_count?: number;
  /** Next scheduled execution time after this fire. Raw: nextExecution (camelCase), SchemaMapper normalizes. */
  next_execution?: string;
  /** Cron expression from schedule config. From ScheduleExecutorService.buildPayload. */
  cron?: string;
  /** Timezone from schedule config. From ScheduleExecutorService.buildPayload. */
  timezone?: string;
  /** UUID of the ScheduledExecutionEntity. From ScheduleExecutorService.buildPayload. */
  scheduleId?: string;
  /** Added by TriggerNode */
  trigger_id?: string;
  /** Added by TriggerNode */
  item_id?: string;
  /** Added by TriggerNode */
  item_index?: number;
}

// ═══════════════════════════════════════════════════════════════
// FORM TRIGGER
// ═══════════════════════════════════════════════════════════════

export interface FormTriggerFormField {
  name: string;
  type: 'text' | 'email' | 'password' | 'number' | 'textarea' | 'select' | 'multiselect' | 'checkbox' | 'checkboxGroup' | 'radio' | 'date' | 'datetime' | 'time' | 'file' | 'url' | 'tel' | 'hidden';
  label: string;
  placeholder?: string;
  required?: boolean;
  options?: Record<string, any>[];
}

/**
 * Parameters for Form Trigger
 * Triggered when user submits a custom form
 */
export interface FormTriggerParameters {
  formTitle?: string;
  formDescription?: string;
  submitButtonText?: string;
  authType?: 'none' | 'basic';
  basicUsername?: string;
  basicPassword?: string;
  fields: Record<string, any>[];
}

/**
 * Outputs produced by Form Trigger
 */
export interface FormTriggerOutputs {
  /** Dynamic fields: {field_name} - Dynamic per field definition, also at top level for easy SpEL access */
  [key: string]: any;
  /** Form submission identifier (UUID), generated at submit time by FormDispatchService */
  submission_id?: string;
  /** When the form was submitted (ISO timestamp). Set at dispatch time by FormDispatchService. */
  submitted_at?: string;
  /** All submitted form fields grouped as object. Populated at dispatch time - never empty when a form is submitted. */
  form_data?: Record<string, any>;
  /** Unified-trigger alias of submitted_at. Same ISO timestamp - use either. */
  triggered_at?: string;
  /** Display name of the form submitter (empty when anonymous). Never the raw tenantId. */
  triggered_by?: string;
  /** Added by TriggerNode */
  trigger_id?: string;
  /** Added by TriggerNode */
  item_id?: string;
  /** Added by TriggerNode */
  item_index?: number;
}

// ═══════════════════════════════════════════════════════════════
// DATASOURCE TRIGGER
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Datasource Trigger
 * Event-driven trigger: fires one run per row created/updated/deleted in a datasource. Replaces the legacy on-demand loader (use schedule + find for bulk scans).
 */
export interface TablesTriggerParameters {
  dataSourceId: number;
  label?: string;
  eventTypes: 'row_created' | 'row_updated' | 'row_deleted';
  filter?: Record<string, any>;
}

/**
 * Outputs produced by Datasource Trigger
 */
export interface TablesTriggerOutputs {
  /** Dynamic fields: {column} - Row columns are also flattened at top level for easy SpEL access ({{trigger.column_name}}). */
  [key: string]: any;
  /** The row that triggered the event (current state for create/update, last-known state for delete). */
  row?: Record<string, any>;
  /** Pre-change row. Populated for row_updated; null for row_created. */
  previous_row?: Record<string, any>;
  /** Which event fired this run. */
  event_type?: 'row_created' | 'row_updated' | 'row_deleted';
  /** ID of the row in data_source_items. */
  row_id?: number;
  /** ID of the datasource emitting the event. */
  datasource_id?: number;
  /** ISO timestamp when the event was emitted (after DB commit). */
  triggered_at?: string;
  /** Display name of the workflow owner. Empty when the row-event source is a system process. */
  triggered_by?: string;
  /** Added by TriggerNode */
  trigger_id?: string;
  /** Added by TriggerNode */
  item_id?: string;
  /** Added by TriggerNode (always 0 - one event = one run) */
  item_index?: number;
}

// ═══════════════════════════════════════════════════════════════
// WORKFLOW TRIGGER
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Workflow Trigger
 * Triggered when parent workflow completes
 */
export interface WorkflowsTriggerParameters {
  workflowId: string;
  workflowName?: string;
}

/**
 * Outputs produced by Workflow Trigger
 */
export interface WorkflowsTriggerOutputs {
  /** Dynamic fields: {parent_key} - Each output key from the parent's last completed node is also flattened at root level (e.g. {{trigger:child.output.my_parent_key}}). Names vary per parent configuration. */
  [key: string]: any;
  /** ISO timestamp when the parent workflow fired this one (snake_case, unified across triggers) */
  triggered_at?: string;
  /** Display name of the workflow owner. Empty when the parent ran in a system context. */
  triggered_by?: string;
  /** Parent workflow ID */
  parentWorkflowId?: string;
  /** Parent run ID */
  parentRunId?: string;
  /** Parent workflow completion status */
  parentStatus?: string;
  /** Merged step outputs from the parent workflow run. */
  result?: Record<string, any>;
  /** Execution statistics from the parent workflow run (completedSteps, failedSteps, totalSteps). Present only when the parent recorded statistics. */
  parentStatistics?: Record<string, any>;
  /** Added by TriggerNode */
  trigger_id?: string;
  /** Added by TriggerNode */
  item_id?: string;
  /** Added by TriggerNode */
  item_index?: number;
}

// ═══════════════════════════════════════════════════════════════
// AI AGENT
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for AI Agent
 * AI-powered agent that can use tools
 */
export interface AiAgentParameters {
  prompt: string;
  model: string;
  provider?: string;
  temperature?: number;
  maxTokens?: number;
  maxIterations?: number;
  maxTools?: number;
  tools?: string[];
  systemPrompt?: string;
}

/**
 * Outputs produced by AI Agent
 */
export interface AiAgentOutputs {
  /** Agent response text */
  response?: string;
  /** Alias for response (compatibility) */
  content?: string;
  model?: string;
  provider?: string;
  /** Total tokens used */
  tokens_used?: number;
  /** Prompt tokens used */
  promptTokens?: number;
  /** Completion tokens used */
  completionTokens?: number;
  iterations?: number;
  /** Count of tool calls made by the agent */
  tool_calls?: number;
  /** Detailed tool call info (name, input, output) */
  tool_calls_detail?: Record<string, any>[];
  durationMs?: number;
}

// ═══════════════════════════════════════════════════════════════
// CLASSIFY
// ═══════════════════════════════════════════════════════════════

export interface ClassifyCategory {
  label: string;
  description?: string;
}

/**
 * Parameters for Classify
 * AI-powered classification with multiple categories
 */
export interface ClassifyParameters {
  categories: Record<string, any>[];
  input: string;
  model?: string;
  provider?: string;
}

/**
 * Outputs produced by Classify
 */
export interface ClassifyOutputs {
  /** The category label that was selected by the AI */
  selected_category?: string;
  /** Index of the selected category in the categories array */
  selected_category_index?: number;
  confidence?: number;
  /** AI reasoning for the classification */
  reasoning?: string;
  /** Human-readable classification result */
  response?: string;
  model?: string;
  provider?: string;
  tokens_used?: number;
  durationMs?: number;
}

// ═══════════════════════════════════════════════════════════════
// GUARDRAIL
// ═══════════════════════════════════════════════════════════════

export interface GuardrailGuardrailRule {
  type: 'pii_detection' | 'toxic_language' | 'prompt_injection' | 'keyword_filter' | 'topic_filter' | 'custom';
  action?: 'block' | 'sanitize' | 'flag';
  config?: Record<string, any>;
}

export interface GuardrailViolation {
  rule_type?: string;
  message?: string;
  severity?: string;
}

/**
 * Parameters for Guardrail
 * Content validation and safety checks
 */
export interface GuardrailParameters {
  rules: Record<string, any>[];
  input: string;
}

/**
 * Outputs produced by Guardrail
 */
export interface GuardrailOutputs {
  /** Whether content passed all checks */
  passed?: boolean;
  /** List of rule violations */
  violations?: Record<string, any>[];
  /** Detailed validation information */
  details?: string;
  /** Sanitized version of input (if applicable) */
  sanitized?: string;
  /** Human-readable result summary */
  response?: string;
  model?: string;
  provider?: string;
  tokens_used?: number;
  durationMs?: number;
}

// ═══════════════════════════════════════════════════════════════
// DECISION
// ═══════════════════════════════════════════════════════════════

export interface DecisionCondition {
  type: 'if' | 'elseif' | 'else';
  expression?: string;
  label?: string;
}

/**
 * Parameters for Decision
 * Conditional branching based on expressions
 */
export interface DecisionParameters {
  decisionConditions: Record<string, any>[];
}

/**
 * Outputs produced by Decision
 */
export interface DecisionOutputs {
  /** Branch type that matched: if, elsif, else */
  selected_branch?: string;
  /** Index of the selected branch (-1 if none) */
  selected_branch_index?: number;
  /** Branch types that were skipped */
  skipped_branches?: string[];
  /** Detailed evaluation results per branch */
  evaluations?: Record<string, any>[];
  /** Expression of the selected branch */
  condition_expression?: string;
  /** Whether any branch matched */
  condition_result?: boolean;
}

// ═══════════════════════════════════════════════════════════════
// LOOP
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Loop
 * While loop with condition
 */
export interface LoopParameters {
  loopCondition: string;
  maxIterations?: number;
  strategy?: 'continue-anyway' | 'stop-on-error';
}

/**
 * Outputs produced by Loop
 */
export interface LoopOutputs {
  /** Current iteration number (0 on first entry) */
  iteration?: number;
  /** Configured max iterations (camelCase in backend) */
  maxIterations?: number;
  /** Whether the loop has terminated */
  terminated?: boolean;
  /** Whether the loop body should be entered */
  enter_body?: boolean;
  /** body or exit */
  selected_path?: string;
  /** Exit reason, present only once terminated: condition_false or max_iterations_reached */
  reason?: string;
}

// ═══════════════════════════════════════════════════════════════
// SPLIT
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Split
 * Iterate over a list of items in parallel
 */
export interface SplitParameters {
  list: string;
  maxItems?: number;
  splitStrategy?: 'continue-anyway' | 'stop-on-error';
}

/**
 * Outputs produced by Split
 */
export interface SplitOutputs {
  current_item?: any;
  current_index?: number;
  /** The collection being split (persisted by SplitNodeExecutor for inspection) */
  items?: any[];
  /** Number of items being split */
  item_count?: number;
  /** Node ID of the split */
  split_id?: string;
  /** items_spawned or empty_list */
  spawn_reason?: string;
  /** Always true: split completes immediately after spawning */
  terminated?: boolean;
}

// ═══════════════════════════════════════════════════════════════
// MERGE
// ═══════════════════════════════════════════════════════════════

export interface MergeMergeInput {
  id: string;
  label: string;
}

/**
 * Parameters for Merge
 * Merge multiple branches (AND wait - waits for ALL predecessors)
 */
export interface MergeParameters {
  mergeInputs: Record<string, any>[];
}

/**
 * Outputs produced by Merge
 */
export interface MergeOutputs {
  /** Dynamic fields: {strategy_output} - Dynamic output from merge strategy (QUEUE_1_TO_1, COMBINE_ALL, FIRST_AVAILABLE) */
  [key: string]: any;
}

// ═══════════════════════════════════════════════════════════════
// FORK
// ═══════════════════════════════════════════════════════════════

export interface ForkForkOutput {
  id: string;
  label: string;
  targetStep?: string;
}

/**
 * Parameters for Fork
 * Parallel fork - ALL branches execute simultaneously (no conditions)
 */
export interface ForkParameters {
  forkOutputs: Record<string, any>[];
}

/**
 * Outputs produced by Fork
 */
export interface ForkOutputs {
  /** Number of parallel branches */
  branch_count?: number;
  /** List of branch info objects with index, id, label, target_count */
  branches?: any[];
}

// ═══════════════════════════════════════════════════════════════
// SWITCH
// ═══════════════════════════════════════════════════════════════

export interface SwitchSwitchCase {
  id: string;
  type: 'case' | 'default';
  label?: string;
  value?: string;
}

/**
 * Parameters for Switch
 * Switch/case branching based on expression value
 */
export interface SwitchParameters {
  switchExpression: string;
  switchCases: Record<string, any>[];
}

/**
 * Outputs produced by Switch
 */
export interface SwitchOutputs {
  /** The case type that matched (case_0, case_1, default) */
  selected_case?: string;
  /** Index of the selected case */
  selected_case_index?: number;
  /** Label of the selected case */
  selected_case_label?: string;
  /** The evaluated switch expression value */
  switch_value?: any;
  /** The value of the case that matched */
  matched_value?: any;
  /** Case types that were skipped */
  skipped_cases?: string[];
  /** Labels of the skipped cases */
  skipped_case_labels?: string[];
  /** Detailed evaluation results per case */
  evaluations?: Record<string, any>[];
}

// ═══════════════════════════════════════════════════════════════
// NOTE
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Note
 * Documentation annotation on the workflow canvas
 */
export interface NoteParameters {
  text: string;
  color?: string;
  borderColor?: string;
  textColor?: string;
  width?: number;
  height?: number;
  position?: Record<string, any>;
}

/**
 * Outputs produced by Note
 */
export interface NoteOutputs {
}

// ═══════════════════════════════════════════════════════════════
// INTERFACE
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Interface
 * UI interface reference for user interaction. Registers INTERFACE_SIGNAL and may block or auto-advance depending on __continue in actionMapping.
 */
export interface InterfaceParameters {
  label?: string;
  interfaceId?: string;
  actionMapping?: Record<string, any>;
  position?: Record<string, any>;
}

/**
 * Outputs produced by Interface
 */
export interface InterfaceOutputs {
  /** ID of the interface being rendered */
  interface_id?: string;
  /** The action mapping configuration */
  action_mapping?: Record<string, any>;
  /** Whether this is the first interface in the workflow */
  is_entry_interface?: boolean;
}

// ═══════════════════════════════════════════════════════════════
// TRANSFORM
// ═══════════════════════════════════════════════════════════════

export interface TransformMapping {
  label: string;
  expression: string;
}

/**
 * Parameters for Transform
 * Transform data using expressions
 */
export interface TransformParameters {
  transformMappings: Record<string, any>[];
}

/**
 * Outputs produced by Transform
 */
export interface TransformOutputs {
  /** Dynamic fields: {mapping.label} - Dynamic per mapping definition - each mapping label becomes an output key */
  [key: string]: any;
}

// ═══════════════════════════════════════════════════════════════
// MCP TOOL
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for MCP Tool
 * Execute an MCP tool/API
 */
export interface McpToolParameters {
  toolId: string;
  apiId: string;
  paramExpressions?: Record<string, any>;
  credentials?: Record<string, any>;
}

/**
 * Outputs produced by MCP Tool
 */
export interface McpToolOutputs {
  /** Dynamic fields: {dynamicSchema} - Dynamic per tool response schema. Output comes from ToolsGateway execution. */
  [key: string]: any;
}

// ═══════════════════════════════════════════════════════════════
// WAIT
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Wait
 * Wait for a specified duration. Short waits (<=3s) use inline sleep, long waits register a WAIT_TIMER signal and yield.
 */
export interface WaitParameters {
  duration: number;
}

/**
 * Outputs produced by Wait
 */
export interface WaitOutputs {
  /** Duration waited in milliseconds (inline mode) */
  waited_ms?: number;
  /** completed (inline mode) */
  status?: string;
  /** ISO timestamp when wait started */
  started_at?: string;
  /** ISO timestamp when wait completed */
  completed_at?: string;
  /** Duration in ms (signal/yield mode) */
  duration_ms?: number;
  /** ISO timestamp when wait expires (signal/yield mode) */
  expires_at?: string;
}

// ═══════════════════════════════════════════════════════════════
// EXIT
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Exit
 * Ends execution along this branch - terminal node. Other parallel branches (fork, split) continue normally.
 */
export interface ExitParameters {
  label?: string;
}

/**
 * Outputs produced by Exit
 */
export interface ExitOutputs {
  /** Always true */
  exited?: boolean;
  /** Exit reason text from node configuration */
  reason?: string;
}

// ═══════════════════════════════════════════════════════════════
// STOP ON ERROR
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Stop on Error
 * Immediately stops the ENTIRE workflow (all branches) with an error. Unlike Exit (branch-only), this terminates everything and marks the run as FAILED.
 */
export interface StopOnErrorParameters {
  errorMessage: string;
  errorCode?: string;
}

/**
 * Outputs produced by Stop on Error
 */
export interface StopOnErrorOutputs {
  /** Resolved error message */
  error_message?: string;
  /** Optional error code */
  error_code?: string;
  /** ISO timestamp when stop was triggered */
  stopped_at?: string;
  /** Always 'failed' */
  status?: string;
}

// ═══════════════════════════════════════════════════════════════
// RESPONSE
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Response
 * Sends a message response to chat interface
 */
export interface ResponseParameters {
  message: string;
}

/**
 * Outputs produced by Response
 */
export interface ResponseOutputs {
  /** The resolved message that was sent */
  message?: string;
  /** ISO timestamp when message was sent */
  sent_at?: string;
}

// ═══════════════════════════════════════════════════════════════
// OPTION
// ═══════════════════════════════════════════════════════════════

export interface OptionOptionChoice {
  id: string;
  label: string;
  expression?: string;
}

/**
 * Parameters for Option
 * Multiple choice branching - evaluates expressions in order, first true wins
 */
export interface OptionParameters {
  optionChoices: Record<string, any>[];
}

/**
 * Outputs produced by Option
 */
export interface OptionOutputs {
  /** ID of the choice that matched */
  selected_choice?: string;
  /** Label of the selected choice */
  selected_label?: string;
  /** Index of the selected choice */
  selected_choice_index?: number;
  /** Array containing the selected branch label (or empty if none matched) */
  selected_branches?: string[];
  /** Labels of branches that were not selected */
  skipped_branches?: string[];
  /** Detailed evaluation results (choice_id, choice_label, expression, resolved_expression, result, error?) */
  evaluations?: Record<string, any>[];
}

// ═══════════════════════════════════════════════════════════════
// AGGREGATE
// ═══════════════════════════════════════════════════════════════

export interface AggregateAggregateField {
  label: string;
  expression: string;
}

/**
 * Parameters for Aggregate
 * Aggregates data from parallel executions (ForEach) using field expressions
 */
export interface AggregateParameters {
  aggregateFields: Record<string, any>[];
}

/**
 * Outputs produced by Aggregate
 */
export interface AggregateOutputs {
  /** Dynamic fields: {field.label} - Dynamic per field definition - each field label becomes an array of collected values */
  [key: string]: any;
  /** Number of items aggregated */
  aggregated_count?: number;
}

// ═══════════════════════════════════════════════════════════════
// SUMMARIZE
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Summarize
 * Groups items by field(s) and applies aggregation functions (sum, avg, count, min, max, countDistinct, concatenate) per group.
 */
export interface SummarizeParameters {
  input: string;
  aggregations: Record<string, any>[];
  groupBy?: string[];
}

/**
 * Outputs produced by Summarize
 */
export interface SummarizeOutputs {
  /** Dynamic fields: {alias} - When no groupBy is configured, each aggregation alias is also stored at the top level for convenient SpEL access (e.g. {{core:summarize.output.total_salary}}). Alias names vary per configuration. */
  [key: string]: any;
  /** Array of group objects. Each group has group_key, group_count, groupBy field values, and each aggregation alias. */
  groups?: any[];
  /** Number of distinct groups. */
  total_groups?: number;
  /** Total items processed. */
  total_items?: number;
  /** Number of aggregation operations applied. */
  aggregation_count?: number;
}

// ═══════════════════════════════════════════════════════════════
// LIMIT
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Limit
 * Passes through only the first or last N items from a collection, with optional offset
 */
export interface LimitParameters {
  input: string;
  count?: number;
  from?: 'first' | 'last';
  offset?: number;
}

/**
 * Outputs produced by Limit
 */
export interface LimitOutputs {
  /** The limited subset of items */
  items?: any[];
  /** Number of items in the limited result */
  count?: number;
  /** Number of items before limiting */
  original_count?: number;
  /** Limit configuration: { input, input_count, count, from, offset } */
  config?: Record<string, any>;
}

// ═══════════════════════════════════════════════════════════════
// DOWNLOAD FILE
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Download File
 * Downloads a file from a URL and stores it in object storage
 */
export interface DownloadFileParameters {
  url: string;
  filename?: string;
}

/**
 * Outputs produced by Download File
 */
export interface DownloadFileOutputs {
  /** Canonical FileRef {_type:'file', path, name, mimeType, size}. Reference via {{core:label.output.file}} to render in interfaces; marketplace + share previews recognise this shape (showcase HMAC rewriter). */
  file?: Record<string, any>;
  /** Original URL that was downloaded from */
  source_url?: string;
}

// ═══════════════════════════════════════════════════════════════
// COMPRESSION
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Compression
 * Compresses or decompresses data. Compress produces a file stored in object storage; decompress returns the decompressed string.
 */
export interface CompressionParameters {
  operation: 'compress' | 'decompress';
  format?: 'gzip' | 'zip' | 'base64' | 'deflate';
  value: string;
  filename?: string;
}

/**
 * Outputs produced by Compression
 */
export interface CompressionOutputs {
  /** The decompressed result (decompress operation only - compress emits `file` instead). */
  result?: string;
  /** Canonical FileRef {_type:'file', path, name, mimeType, size} (compress only). Reference via {{core:label.output.file}}; marketplace + share previews recognise this shape. */
  file?: Record<string, any>;
  /** The compression operation performed */
  operation?: string;
  /** The compression format used */
  format?: string;
  /** Whether the operation was successful */
  success?: boolean;
}

// ═══════════════════════════════════════════════════════════════
// CONVERT TO FILE
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Convert to File
 * Converts data (list of maps) to a file format (CSV, Excel/XLSX, JSON, TXT) and stores it in object storage
 */
export interface ConvertToFileParameters {
  format?: 'csv' | 'xlsx' | 'json' | 'txt';
  value: string;
  filename?: string;
  delimiter?: string;
  includeHeaders?: 'yes' | 'no';
}

/**
 * Outputs produced by Convert to File
 */
export interface ConvertToFileOutputs {
  /** Canonical FileRef {_type:'file', path, name, mimeType, size}. Reference via {{core:label.output.file}}; marketplace + share previews recognise this shape. */
  file?: Record<string, any>;
  /** Output file format used */
  format?: string;
  /** Number of rows written to the file */
  row_count?: number;
  /** Whether conversion was successful */
  success?: boolean;
}

// ═══════════════════════════════════════════════════════════════
// SFTP
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for SFTP
 * File operations on remote servers via SFTP: upload, download, list, delete, rename, mkdir
 */
export interface SftpParameters {
  host: string;
  port?: number;
  username: string;
  authMethod?: 'password' | 'privateKey';
  password?: string;
  privateKey?: string;
  operation: 'upload' | 'download' | 'list' | 'delete' | 'rename' | 'mkdir';
  remotePath: string;
  localContent?: string;
  newPath?: string;
  timeout?: number;
  credentialId?: number;
}

/**
 * Outputs produced by SFTP
 */
export interface SftpOutputs {
  /** True if the SFTP operation succeeded */
  success?: boolean;
  /** The SFTP operation performed */
  operation?: string;
  /** The remote file/directory path */
  remote_path?: string;
  /** Array of file entries for list operation: {name, size, is_dir, modified} */
  files?: any[];
  /** Number of file entries returned (list operation) */
  file_count?: number;
  /** Canonical FileRef {_type:'file', path, name, mimeType, size} (download operation). Reference via {{core:label.output.file}}; marketplace + share previews recognise this shape. */
  file?: Record<string, any>;
  /** New file path after rename operation */
  new_path?: string;
  /** Number of bytes written to the remote server (upload operation only) */
  uploaded_size?: number;
  /** Total operation time in milliseconds */
  duration_ms?: number;
}

// ═══════════════════════════════════════════════════════════════
// HTTP REQUEST
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for HTTP Request
 * Makes HTTP calls to external APIs. Supports GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS with various auth and body types.
 */
export interface HttpRequestParameters {
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD' | 'OPTIONS';
  url: string;
  authType?: 'none' | 'basic' | 'bearer' | 'api-key' | 'custom-header';
  authConfig?: Record<string, any>;
  queryParams?: Record<string, any>[];
  headers?: Record<string, any>[];
  bodyType?: 'none' | 'json' | 'form-data' | 'x-www-form-urlencoded' | 'raw';
  body?: string;
  contentType?: string;
  timeout?: number;
}

/**
 * Outputs produced by HTTP Request
 */
export interface HttpRequestOutputs {
  /** Whether the HTTP request succeeded (2xx) or failed (4xx/5xx). Workflow continues either way. */
  success?: boolean;
  /** HTTP status code (200, 404, 500, etc.) */
  status?: number;
  /** HTTP status text */
  statusText?: string;
  /** Response headers (single-value map) */
  headers?: Record<string, any>;
  /** Response body (parsed JSON or raw string). Empty object if no body. */
  data?: any;
  /** Error message (only on HTTP error responses) */
  error?: string;
  /** Snapshot of resolved request params (method, url, body, bodyType, authType) for inspector */
  input_data?: Record<string, any>;
}

// ═══════════════════════════════════════════════════════════════
// DATA INPUT
// ═══════════════════════════════════════════════════════════════

export interface DataInputDataInputItem {
  label: string;
  type: 'text' | 'file';
  text?: string;
  file?: Record<string, any>;
}

/**
 * Parameters for Data Input
 * Provides multiple labeled text and/or file inputs to the workflow. Each item's label becomes an output key.
 */
export interface DataInputParameters {
  items: Record<string, any>[];
}

/**
 * Outputs produced by Data Input
 */
export interface DataInputOutputs {
  /** Dynamic fields: {item.label} - Dynamic per item - text items resolve SpEL, file items pass FileRef object */
  [key: string]: any;
  /** Raw expressions before resolution, for inspector visibility */
  input_data?: Record<string, any>;
}

// ═══════════════════════════════════════════════════════════════
// USER APPROVAL
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for User Approval
 * Branching node with ports: approved, rejected, timeout. Registers USER_APPROVAL signal and yields until resolved.
 */
export interface UserApprovalParameters {
  approverRoles?: string[];
  requiredApprovals?: number;
  timeoutMs?: number;
  contextTemplate: string;
  continuationMode?: 'all_items' | 'per_item';
  delegation?: Record<string, any>;
}

/**
 * Outputs produced by User Approval
 */
export interface UserApprovalOutputs {
  /** Configured approver roles (in AWAITING_SIGNAL output) */
  approver_roles?: string[];
  /** Configured required approvals count */
  required_approvals?: number;
  /** ISO timestamp when approval expires */
  expires_at?: string;
  /** approved, rejected, or timeout - set after signal resolution */
  selected_port?: string;
  /** Resolved contextTemplate shown to the approver, carried into the resolved output (present only when a context template resolved to non-blank text) */
  approval_context?: string;
  /** External channel the approval was delegated to (e.g. telegram). Present only when delegation is configured. */
  delegated_channel?: string;
}

// ═══════════════════════════════════════════════════════════════
// TASK
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Task
 * CRUD operations on agent-board tasks from a workflow: create_task, get_task, update_task, delete_task, list_tasks. create_task with agentId auto-triggers the assignee asynchronously (the DAG does NOT wait).
 */
export interface TaskParameters {
  operation: 'create_task' | 'get_task' | 'update_task' | 'delete_task' | 'list_tasks';
  taskId?: string;
  title?: string;
  instructions?: string;
  priority?: 'low' | 'normal' | 'high' | 'urgent';
  agentId?: string;
  reviewerAgentId?: string;
  status?: 'pending' | 'in_progress' | 'in_review' | 'completed' | 'failed' | 'cancelled';
  search?: string;
  limit?: number;
  taskContext?: Record<string, any>;
}

/**
 * Outputs produced by Task
 */
export interface TaskOutputs {
  /** Always 'TASK' */
  node_type?: string;
  /** The operation that was executed */
  operation?: string;
  /** Whether the operation succeeded */
  success?: boolean;
  /** The task object (create/get/update). status starts pending when agentId auto-triggers; result stays null until the agent finishes */
  task?: Record<string, any>;
  /** The cancelled task ID (delete_task only) */
  task_id?: string;
  /** Array of task objects (list_tasks only, workspace-scoped) */
  tasks?: Record<string, any>[];
  /** Number of tasks in the current page (list_tasks only) */
  count?: number;
  /** Total matching tasks (list_tasks only) */
  total?: number;
  /** Snapshot of resolved request params for the inspector */
  resolved_params?: Record<string, any>;
}

// ═══════════════════════════════════════════════════════════════
// INSERT ROW
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Insert Row
 * Insert new rows in a database table
 */
export interface InsertRowParameters {
  tableId: number;
  columns: Record<string, any>;
}

/**
 * Outputs produced by Insert Row
 */
export interface InsertRowOutputs {
  /** CRUD operation name (e.g. create-row) */
  operation?: string;
  /** Whether the operation succeeded */
  success?: boolean;
  /** Human-readable result message */
  message?: string;
  row_id?: string;
  created_at?: string;
  /** Number of rows inserted */
  inserted_count?: number;
  /** Map of column names to inserted values */
  inserted_values?: Record<string, any>;
}

// ═══════════════════════════════════════════════════════════════
// GET ROWS
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Get Rows
 * Get rows from a database table with filters
 */
export interface GetRowsParameters {
  tableId: number;
  where?: Record<string, any>;
  limit?: number;
}

/**
 * Outputs produced by Get Rows
 */
export interface GetRowsOutputs {
  /** CRUD operation name (e.g. read-row) */
  operation?: string;
  /** Whether the operation succeeded */
  success?: boolean;
  /** Human-readable result message */
  message?: string;
  rows?: Record<string, any>[];
  /** Number of rows returned */
  row_count?: number;
  /** Alias for row_count (camelCase) */
  rowCount?: number;
  /** Whether more rows exist beyond limit */
  has_more?: boolean;
  /** Pagination offset used */
  offset?: number;
}

// ═══════════════════════════════════════════════════════════════
// UPDATE ROW
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Update Row
 * Update existing rows in a database table
 */
export interface UpdateRowParameters {
  tableId: number;
  where: Record<string, any>;
  set: Record<string, any>;
}

/**
 * Outputs produced by Update Row
 */
export interface UpdateRowOutputs {
  /** CRUD operation name (e.g. update-row) */
  operation?: string;
  /** Whether the operation succeeded */
  success?: boolean;
  /** Human-readable result message */
  message?: string;
  updated_count?: number;
  rows_affected?: number;
  updated_at?: string;
}

// ═══════════════════════════════════════════════════════════════
// DELETE ROW
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Delete Row
 * Delete rows from a database table
 */
export interface DeleteRowParameters {
  tableId: number;
  where: Record<string, any>;
}

/**
 * Outputs produced by Delete Row
 */
export interface DeleteRowOutputs {
  /** CRUD operation name (e.g. delete-row) */
  operation?: string;
  /** Whether the operation succeeded */
  success?: boolean;
  /** Human-readable result message */
  message?: string;
  deleted_count?: number;
  rows_affected?: number;
  deleted_at?: string;
}

// ═══════════════════════════════════════════════════════════════
// CREATE COLUMN
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Create Column
 * Create new columns in a database table
 */
export interface CreateColumnParameters {
  tableId: number;
  columns: any[];
}

/**
 * Outputs produced by Create Column
 */
export interface CreateColumnOutputs {
  /** CRUD operation name (create-column) */
  operation?: string;
  /** Whether the operation succeeded */
  success?: boolean;
  /** Human-readable result message */
  message?: string;
  /** List of column names created (camelCase, from CrudToolExecutor) */
  createdColumns?: any[];
}

// ═══════════════════════════════════════════════════════════════
// FIND ROW
// ═══════════════════════════════════════════════════════════════

/**
 * Parameters for Find Row
 * Queries a data source and returns matching rows as a collection (does not spawn parallel per-row contexts; use a Split node on the items for that).
 */
export interface FindRowParameters {
  tableId: number;
  where?: Record<string, any>;
  maxItems?: number;
}

/**
 * Outputs produced by Find Row
 */
export interface FindRowOutputs {
  /** All found rows (after limit applied) */
  items?: Record<string, any>[];
  /** Number of items found */
  item_count?: number;
  /** Total rows before maxItems cap */
  total_before_limit?: number;
  /** Whether more rows exist beyond the limit */
  has_more?: boolean;
  /** items_found or empty_result */
  exit_reason?: string;
  /** Node ID of the find node */
  find_id?: string;
  /** Configured max items cap */
  max_items?: number;
}

// ═══════════════════════════════════════════════════════════════
// TYPE GUARDS
// ═══════════════════════════════════════════════════════════════

// Type guards for node types

export function isManualTrigger(nodeId: string): boolean {
  return nodeId === 'manual-trigger' || nodeId.startsWith('manual-trigger-');
}

export function isChatTrigger(nodeId: string): boolean {
  return nodeId === 'chat-trigger' || nodeId.startsWith('chat-trigger-');
}

export function isWebhookTrigger(nodeId: string): boolean {
  return nodeId === 'webhook-trigger' || nodeId.startsWith('webhook-trigger-');
}

export function isScheduleTrigger(nodeId: string): boolean {
  return nodeId === 'schedule-trigger' || nodeId.startsWith('schedule-trigger-');
}

export function isFormTrigger(nodeId: string): boolean {
  return nodeId === 'form-trigger' || nodeId.startsWith('form-trigger-');
}

export function isTablesTrigger(nodeId: string): boolean {
  return nodeId === 'tables-trigger' || nodeId.startsWith('tables-trigger-');
}

export function isWorkflowsTrigger(nodeId: string): boolean {
  return nodeId === 'workflows-trigger' || nodeId.startsWith('workflows-trigger-');
}

export function isAiAgent(nodeId: string): boolean {
  return nodeId === 'ai-agent' || nodeId.startsWith('ai-agent-');
}

export function isClassify(nodeId: string): boolean {
  return nodeId === 'classify' || nodeId.startsWith('classify-');
}

export function isGuardrail(nodeId: string): boolean {
  return nodeId === 'guardrail' || nodeId.startsWith('guardrail-');
}

export function isDecision(nodeId: string): boolean {
  return nodeId === 'decision' || nodeId.startsWith('decision-');
}

export function isLoop(nodeId: string): boolean {
  return nodeId === 'loop' || nodeId.startsWith('loop-');
}

export function isSplit(nodeId: string): boolean {
  return nodeId === 'split' || nodeId.startsWith('split-');
}

export function isMerge(nodeId: string): boolean {
  return nodeId === 'merge' || nodeId.startsWith('merge-');
}

export function isFork(nodeId: string): boolean {
  return nodeId === 'fork' || nodeId.startsWith('fork-');
}

export function isSwitch(nodeId: string): boolean {
  return nodeId === 'switch' || nodeId.startsWith('switch-');
}

export function isNote(nodeId: string): boolean {
  return nodeId === 'note' || nodeId.startsWith('note-');
}

export function isInterface(nodeId: string): boolean {
  return nodeId === 'interface' || nodeId.startsWith('interface-');
}

export function isTransform(nodeId: string): boolean {
  return nodeId === 'transform' || nodeId.startsWith('transform-');
}

export function isMcpTool(nodeId: string): boolean {
  return nodeId === 'mcp-tool' || nodeId.startsWith('mcp-tool-');
}

export function isWait(nodeId: string): boolean {
  return nodeId === 'wait' || nodeId.startsWith('wait-');
}

export function isExit(nodeId: string): boolean {
  return nodeId === 'exit' || nodeId.startsWith('exit-');
}

export function isStopOnError(nodeId: string): boolean {
  return nodeId === 'stop_on_error' || nodeId.startsWith('stop_on_error-');
}

export function isResponse(nodeId: string): boolean {
  return nodeId === 'response' || nodeId.startsWith('response-');
}

export function isOption(nodeId: string): boolean {
  return nodeId === 'option' || nodeId.startsWith('option-');
}

export function isAggregate(nodeId: string): boolean {
  return nodeId === 'aggregate' || nodeId.startsWith('aggregate-');
}

export function isSummarize(nodeId: string): boolean {
  return nodeId === 'summarize' || nodeId.startsWith('summarize-');
}

export function isLimit(nodeId: string): boolean {
  return nodeId === 'limit' || nodeId.startsWith('limit-');
}

export function isDownloadFile(nodeId: string): boolean {
  return nodeId === 'download_file' || nodeId.startsWith('download_file-');
}

export function isCompression(nodeId: string): boolean {
  return nodeId === 'compression' || nodeId.startsWith('compression-');
}

export function isConvertToFile(nodeId: string): boolean {
  return nodeId === 'convert_to_file' || nodeId.startsWith('convert_to_file-');
}

export function isSftp(nodeId: string): boolean {
  return nodeId === 'sftp' || nodeId.startsWith('sftp-');
}

export function isHttpRequest(nodeId: string): boolean {
  return nodeId === 'http_request' || nodeId.startsWith('http_request-');
}

export function isDataInput(nodeId: string): boolean {
  return nodeId === 'data_input' || nodeId.startsWith('data_input-');
}

export function isUserApproval(nodeId: string): boolean {
  return nodeId === 'user_approval' || nodeId.startsWith('user_approval-');
}

export function isTask(nodeId: string): boolean {
  return nodeId === 'task' || nodeId.startsWith('task-');
}

export function isInsertRow(nodeId: string): boolean {
  return nodeId === 'insert-row' || nodeId.startsWith('insert-row-');
}

export function isGetRows(nodeId: string): boolean {
  return nodeId === 'get-rows' || nodeId.startsWith('get-rows-');
}

export function isUpdateRow(nodeId: string): boolean {
  return nodeId === 'update-row' || nodeId.startsWith('update-row-');
}

export function isDeleteRow(nodeId: string): boolean {
  return nodeId === 'delete-row' || nodeId.startsWith('delete-row-');
}

export function isCreateColumn(nodeId: string): boolean {
  return nodeId === 'create-column' || nodeId.startsWith('create-column-');
}

export function isFindRow(nodeId: string): boolean {
  return nodeId === 'find-row' || nodeId.startsWith('find-row-');
}

// ═══════════════════════════════════════════════════════════════
// NODE REGISTRY
// ═══════════════════════════════════════════════════════════════

// Node type registry

export const NODE_TYPES = {
  'manual-trigger': {
    id: 'manual-trigger',
    name: 'Manual Trigger',
    category: 'trigger',
  },
  'chat-trigger': {
    id: 'chat-trigger',
    name: 'Chat Trigger',
    category: 'trigger',
  },
  'webhook-trigger': {
    id: 'webhook-trigger',
    name: 'Webhook Trigger',
    category: 'trigger',
  },
  'schedule-trigger': {
    id: 'schedule-trigger',
    name: 'Schedule Trigger',
    category: 'trigger',
  },
  'form-trigger': {
    id: 'form-trigger',
    name: 'Form Trigger',
    category: 'trigger',
  },
  'tables-trigger': {
    id: 'tables-trigger',
    name: 'Datasource Trigger',
    category: 'trigger',
  },
  'workflows-trigger': {
    id: 'workflows-trigger',
    name: 'Workflow Trigger',
    category: 'trigger',
  },
  'ai-agent': {
    id: 'ai-agent',
    name: 'AI Agent',
    category: 'ai',
  },
  'classify': {
    id: 'classify',
    name: 'Classify',
    category: 'ai',
  },
  'guardrail': {
    id: 'guardrail',
    name: 'Guardrail',
    category: 'ai',
  },
  'decision': {
    id: 'decision',
    name: 'Decision',
    category: 'control_flow',
  },
  'loop': {
    id: 'loop',
    name: 'Loop',
    category: 'control_flow',
  },
  'split': {
    id: 'split',
    name: 'Split',
    category: 'control_flow',
  },
  'merge': {
    id: 'merge',
    name: 'Merge',
    category: 'control_flow',
  },
  'fork': {
    id: 'fork',
    name: 'Fork',
    category: 'control_flow',
  },
  'switch': {
    id: 'switch',
    name: 'Switch',
    category: 'control_flow',
  },
  'note': {
    id: 'note',
    name: 'Note',
    category: 'documentation',
  },
  'interface': {
    id: 'interface',
    name: 'Interface',
    category: 'ui',
  },
  'transform': {
    id: 'transform',
    name: 'Transform',
    category: 'action',
  },
  'mcp-tool': {
    id: 'mcp-tool',
    name: 'MCP Tool',
    category: 'action',
  },
  'wait': {
    id: 'wait',
    name: 'Wait',
    category: 'control_flow',
  },
  'exit': {
    id: 'exit',
    name: 'Exit',
    category: 'control_flow',
  },
  'stop_on_error': {
    id: 'stop_on_error',
    name: 'Stop on Error',
    category: 'control_flow',
  },
  'response': {
    id: 'response',
    name: 'Response',
    category: 'control_flow',
  },
  'option': {
    id: 'option',
    name: 'Option',
    category: 'control_flow',
  },
  'aggregate': {
    id: 'aggregate',
    name: 'Aggregate',
    category: 'control_flow',
  },
  'summarize': {
    id: 'summarize',
    name: 'Summarize',
    category: 'control_flow',
  },
  'limit': {
    id: 'limit',
    name: 'Limit',
    category: 'core',
  },
  'download_file': {
    id: 'download_file',
    name: 'Download File',
    category: 'action',
  },
  'compression': {
    id: 'compression',
    name: 'Compression',
    category: 'action',
  },
  'convert_to_file': {
    id: 'convert_to_file',
    name: 'Convert to File',
    category: 'action',
  },
  'sftp': {
    id: 'sftp',
    name: 'SFTP',
    category: 'action',
  },
  'http_request': {
    id: 'http_request',
    name: 'HTTP Request',
    category: 'action',
  },
  'data_input': {
    id: 'data_input',
    name: 'Data Input',
    category: 'action',
  },
  'user_approval': {
    id: 'user_approval',
    name: 'User Approval',
    category: 'control_flow',
  },
  'task': {
    id: 'task',
    name: 'Task',
    category: 'action',
  },
  'insert-row': {
    id: 'insert-row',
    name: 'Insert Row',
    category: 'action',
  },
  'get-rows': {
    id: 'get-rows',
    name: 'Get Rows',
    category: 'action',
  },
  'update-row': {
    id: 'update-row',
    name: 'Update Row',
    category: 'action',
  },
  'delete-row': {
    id: 'delete-row',
    name: 'Delete Row',
    category: 'action',
  },
  'create-column': {
    id: 'create-column',
    name: 'Create Column',
    category: 'action',
  },
  'find-row': {
    id: 'find-row',
    name: 'Find Row',
    category: 'action',
  },
} as const;

export type NodeTypeId = keyof typeof NODE_TYPES;

