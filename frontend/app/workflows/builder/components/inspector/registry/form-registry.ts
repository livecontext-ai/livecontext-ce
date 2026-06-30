/**
 * Form Registry - Central configuration for all inspector forms.
 *
 * Each node type registers its form component and metadata here.
 * Adding a new node type = 1 entry in this file.
 *
 * Categories: triggers, agents, cores, tools, tables, notes, interface
 */

import { FormDefinition, InspectorNodeType } from '../core/types';

// Form adapters for all forms
import {
  // Triggers
  ManualTriggerFormAdapter,
  ChatTriggerFormAdapter,
  WebhookTriggerFormAdapter,
  ScheduleTriggerFormAdapter,
  FormTriggerFormAdapter,
  TablesTriggerFormAdapter,
  WorkflowsTriggerFormAdapter,
  ErrorTriggerFormAdapter,
  // Cores
  TransformFormAdapter,
  MergeFormAdapter,
  ForkFormAdapter,
  WaitFormAdapter,
  DecisionFormAdapter,
  SwitchFormAdapter,
  LoopFormAdapter,
  SplitFormAdapter,
  WhileGroupFormAdapter,
  AggregateFormAdapter,
  DownloadFileFormAdapter,
  HttpRequestFormAdapter,
  DataInputFormAdapter,
  ResponseFormAdapter,
  FilterFormAdapter,
  SortFormAdapter,
  LimitFormAdapter,
  RemoveDuplicatesFormAdapter,
  SummarizeFormAdapter,
  DateTimeFormAdapter,
  CryptoJwtFormAdapter,
  XmlFormAdapter,
  CompressionFormAdapter,
  RssFormAdapter,
  ConvertToFileFormAdapter,
  ExtractFromFileFormAdapter,
  CompareDatasetsFormAdapter,
  SetFormAdapter,
  HtmlExtractFormAdapter,
  TaskFormAdapter,
  SubWorkflowFormAdapter,
  RespondToWebhookFormAdapter,
  SendEmailFormAdapter,
  EmailInboxFormAdapter,
  CodeFormAdapter,
  StopOnErrorFormAdapter,
  SshFormAdapter,
  SftpFormAdapter,
  DatabaseFormAdapter,
  // Agents
  AgentFormAdapter,
  BrowserAgentFormAdapter,
  ClassifyFormAdapter,
  GuardrailFormAdapter,
  // Tools
  ToolFormAdapter,
  // Tables (CRUD)
  CreateRowFormAdapter,
  ReadRowFormAdapter,
  UpdateRowFormAdapter,
  DeleteRowFormAdapter,
  FindRowFormAdapter,
  ListRowsFormAdapter,
} from '../core/form-adapters';

/**
 * Form registry - maps node types to form definitions.
 *
 * Structure:
 * - component: The React component to render
 * - displayName: Human-readable name for headers
 * - hasExpressions: Whether the form uses expression fields
 * - hasOptionalParams: Whether the form has collapsible optional params
 */
export const formRegistry: Record<InspectorNodeType, FormDefinition> = {
  // ============================================================================
  // TRIGGERS
  // ============================================================================
  'manual-trigger': {
    component: ManualTriggerFormAdapter,
    displayName: 'Manual Trigger',
    hasExpressions: false,
  },
  'chat-trigger': {
    component: ChatTriggerFormAdapter,
    displayName: 'Chat Trigger',
    hasExpressions: false,
  },
  'webhook-trigger': {
    component: WebhookTriggerFormAdapter,
    displayName: 'Webhook Trigger',
    hasExpressions: false,
  },
  'schedule-trigger': {
    component: ScheduleTriggerFormAdapter,
    displayName: 'Schedule Trigger',
    hasExpressions: false,
  },
  'form-trigger': {
    component: FormTriggerFormAdapter,
    displayName: 'Form Trigger',
    hasExpressions: false,
  },
  'tables-trigger': {
    component: TablesTriggerFormAdapter,
    displayName: 'Tables Trigger',
    hasExpressions: true,
  },
  'workflows-trigger': {
    component: WorkflowsTriggerFormAdapter,
    displayName: 'Workflows Trigger',
    hasExpressions: false,
  },
  'error-trigger': {
    component: ErrorTriggerFormAdapter,
    displayName: 'Error Trigger',
    hasExpressions: false,
  },

  // ============================================================================
  // AGENTS
  // ============================================================================
  'agent': {
    component: AgentFormAdapter,
    displayName: 'AI Agent',
    hasExpressions: true,
    hasOptionalParams: true,
  },
  'browser_agent': {
    component: BrowserAgentFormAdapter,
    displayName: 'Browser Agent',
    hasExpressions: true,
    hasOptionalParams: true,
  },
  'summarize': {
    component: AgentFormAdapter, // Uses same agent config
    displayName: 'Summarize',
    hasExpressions: true,
    hasOptionalParams: true,
  },
  'guardrail': {
    component: GuardrailFormAdapter,
    displayName: 'Guardrail',
    hasExpressions: true,
  },
  'classify': {
    component: ClassifyFormAdapter,
    displayName: 'Classify',
    hasExpressions: true,
  },

  // ============================================================================
  // CORES (control flow)
  // ============================================================================
  'decision': {
    component: DecisionFormAdapter,
    displayName: 'Decision (If/Else)',
    hasExpressions: true,
  },
  'switch': {
    component: SwitchFormAdapter,
    displayName: 'Switch',
    hasExpressions: true,
  },
  'loop': {
    component: LoopFormAdapter,
    displayName: 'Loop',
    hasExpressions: true,
    hasOptionalParams: true,
  },
  'split': {
    component: SplitFormAdapter,
    displayName: 'Split',
    hasExpressions: true,
    hasOptionalParams: true,
  },
  'while-group': {
    component: WhileGroupFormAdapter,
    displayName: 'While',
    hasExpressions: true,
    hasOptionalParams: true,
  },
  'aggregate': {
    component: AggregateFormAdapter,
    displayName: 'Aggregate',
    hasExpressions: true,
    hasOptionalParams: false,
  },
  'transform': {
    component: TransformFormAdapter,
    displayName: 'Transform',
    hasExpressions: true,
  },
  'merge': {
    component: MergeFormAdapter,
    displayName: 'Merge',
    hasExpressions: false,
  },
  'wait': {
    component: WaitFormAdapter,
    displayName: 'Wait',
    hasExpressions: true,
  },
  'fork': {
    component: ForkFormAdapter,
    displayName: 'Fork',
    hasExpressions: false,
  },
  'download_file': {
    component: DownloadFileFormAdapter,
    displayName: 'Download File',
    hasExpressions: true,
  },
  'http_request': {
    component: HttpRequestFormAdapter,
    displayName: 'HTTP Request',
    hasExpressions: true,
  },
  'data_input': {
    component: DataInputFormAdapter,
    displayName: 'Data Input',
    hasExpressions: true,
  },
  'response': {
    component: ResponseFormAdapter,
    displayName: 'Respond to Chat',
    hasExpressions: true,
  },
  'exit': {
    component: () => null, // Exit node has no configurable parameters
    displayName: 'Exit',
    hasExpressions: false,
  },
  'filter': {
    component: FilterFormAdapter,
    displayName: 'Filter',
    hasExpressions: false,
  },
  'sort': {
    component: SortFormAdapter,
    displayName: 'Sort',
    hasExpressions: false,
  },
  'limit': {
    component: LimitFormAdapter,
    displayName: 'Limit',
    hasExpressions: false,
  },
  'remove_duplicates': {
    component: RemoveDuplicatesFormAdapter,
    displayName: 'Remove Duplicates',
    hasExpressions: false,
  },
  'summarize_data': {
    component: SummarizeFormAdapter,
    displayName: 'Summarize',
    hasExpressions: false,
  },
  'date_time': {
    component: DateTimeFormAdapter,
    displayName: 'Date/Time',
    hasExpressions: false,
  },
  'crypto_jwt': {
    component: CryptoJwtFormAdapter,
    displayName: 'Crypto/JWT',
    hasExpressions: false,
  },
  'xml': {
    component: XmlFormAdapter,
    displayName: 'XML',
    hasExpressions: false,
  },
  'compression': {
    component: CompressionFormAdapter,
    displayName: 'Compression',
    hasExpressions: false,
  },
  'rss': {
    component: RssFormAdapter,
    displayName: 'RSS',
    hasExpressions: false,
  },
  'convert_to_file': {
    component: ConvertToFileFormAdapter,
    displayName: 'Convert to File',
    hasExpressions: false,
  },
  'extract_from_file': {
    component: ExtractFromFileFormAdapter,
    displayName: 'Extract from File',
    hasExpressions: false,
  },
  'compare_datasets': {
    component: CompareDatasetsFormAdapter,
    displayName: 'Compare Datasets',
    hasExpressions: false,
  },
  'set': {
    component: SetFormAdapter,
    displayName: 'Set / Edit Fields',
    hasExpressions: false,
  },
  'html_extract': {
    component: HtmlExtractFormAdapter,
    displayName: 'HTML Extract',
    hasExpressions: false,
  },
  'task': {
    component: TaskFormAdapter,
    displayName: 'Task',
    hasExpressions: false,
  },
  'sub_workflow': {
    component: SubWorkflowFormAdapter,
    displayName: 'Sub-Workflow',
    hasExpressions: false,
  },
  'respond_to_webhook': {
    component: RespondToWebhookFormAdapter,
    displayName: 'Respond to Webhook',
    hasExpressions: true,
  },
  'send_email': {
    component: SendEmailFormAdapter,
    displayName: 'Send Email',
    hasExpressions: true,
  },
  'email_inbox': {
    component: EmailInboxFormAdapter,
    displayName: 'Email Inbox',
    hasExpressions: true,
  },
  'code': {
    component: CodeFormAdapter,
    displayName: 'Code',
    hasExpressions: true,
  },
  'stop_on_error': {
    component: StopOnErrorFormAdapter,
    displayName: 'Stop on Error',
    hasExpressions: false,
  },
  'ssh': {
    component: SshFormAdapter,
    displayName: 'SSH',
    hasExpressions: true,
  },
  'sftp': {
    component: SftpFormAdapter,
    displayName: 'SFTP',
    hasExpressions: true,
  },
  'database': {
    component: DatabaseFormAdapter,
    displayName: 'Database',
    hasExpressions: true,
  },

  // ============================================================================
  // TOOLS
  // ============================================================================
  'tool': {
    component: ToolFormAdapter,
    displayName: 'Tool',
    hasExpressions: true,
  },

  // ============================================================================
  // TABLES (CRUD)
  // ============================================================================
  'create-row': {
    component: CreateRowFormAdapter,
    displayName: 'Create Row',
    hasExpressions: true,
  },
  'read-row': {
    component: ReadRowFormAdapter,
    displayName: 'Read Row',
    hasExpressions: true,
  },
  'update-row': {
    component: UpdateRowFormAdapter,
    displayName: 'Update Row',
    hasExpressions: true,
  },
  'delete-row': {
    component: DeleteRowFormAdapter,
    displayName: 'Delete Row',
    hasExpressions: true,
  },
  'find-row': {
    component: FindRowFormAdapter,
    displayName: 'Find Rows',
    hasExpressions: true,
  },
  'list-rows': {
    component: ListRowsFormAdapter,
    displayName: 'List Rows',
    hasExpressions: true,
  },

  // Note: rendered directly in ParameterColumn for reliable interaction
  'note': {
    component: () => null,
    displayName: 'Note',
    hasExpressions: false,
  },

  // ============================================================================
  // INTERFACE
  // ============================================================================
  'interface': {
    component: () => null, // Interface handled by InterfaceMappingsColumn
    displayName: 'Interface',
    hasExpressions: false,
  },

  // ============================================================================
  // UNKNOWN
  // ============================================================================
  'unknown': {
    component: () => null, // No form for unknown types
    displayName: 'Unknown',
    hasExpressions: false,
  },
};

/**
 * Get form definition for a node type.
 */
export function getFormDefinition(nodeType: InspectorNodeType): FormDefinition {
  return formRegistry[nodeType];
}
