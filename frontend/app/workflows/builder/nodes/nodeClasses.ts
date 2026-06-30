import {
  Bot,
  Code,
  Cpu,
  Database,
  GitBranch,
  Network,
  Pencil,
  Plus,
  Search,
  Square,
  Trash2,
  User,
  Zap,
} from 'lucide-react';

import type { PaletteDragItem, BuilderNodeData, BuilderNodeKind, BuilderNodeType } from '../types';
import { resolveNodeIcon } from '../data/nodeVisuals';

type IconComponent = React.ComponentType<{ className?: string }>;

export type NodeFamily =
  | 'trigger'
  | 'mcp'
  | 'mcp-api'
  | 'mcp-tool'
  | 'mcp-resource'
  | 'ai'
  | 'condition'
  | 'loop'
  | 'note'
  | 'core'
  | 'data'
  | 'output';

export type InspectorTemplate =
  | 'trigger'
  | 'mcp'
  | 'ai'
  | 'condition'
  | 'option'
  | 'approval'
  | 'loop'
  | 'split'
  | 'aggregate'
  | 'exit'
  | 'response'
  | 'note'
  | 'transform'
  | 'merge'
  | 'fork'
  | 'wait'
  | 'download_file'
  | 'http_request'
  | 'data_input'
  | 'while-group'
  | 'default';

export type PaletteCategoryId = 'triggers' | 'mcp' | 'ai' | 'flow' | 'core' | 'data' | 'developer' | 'user';

export interface NodePaletteConfig {
  category: PaletteCategoryId;
  subcategory?: string;
  quickGroup?: 'Core' | 'Tools' | 'Logic' | 'Data';
  quickBg?: string;
  featured?: boolean;
  featuredOrder?: number;
}

interface NodeClassOptions {
  id: string;
  label: string;
  description: string;
  kind: BuilderNodeKind;
  nodeType: BuilderNodeType;
  family: NodeFamily;
  badge?: string;
  aliases?: string[];
  palette?: NodePaletteConfig;
  inspector?: InspectorTemplate;
  matches?: (data: BuilderNodeData) => boolean;
}

export class BuilderNodeClass {
  readonly id: string;
  readonly label: string;
  readonly description: string;
  readonly kind: BuilderNodeKind;
  readonly nodeType: BuilderNodeType;
  readonly family: NodeFamily;
  readonly badge?: string;
  readonly aliases: string[];
  readonly palette?: NodePaletteConfig;
  readonly inspector: InspectorTemplate;
  private readonly matchFn?: (data: BuilderNodeData) => boolean;

  constructor(options: NodeClassOptions) {
    this.id = options.id;
    this.label = options.label;
    this.description = options.description;
    this.kind = options.kind;
    this.nodeType = options.nodeType;
    this.family = options.family;
    this.badge = options.badge;
    this.aliases = options.aliases ?? [];
    this.palette = options.palette;
    this.inspector = options.inspector ?? 'default';
    this.matchFn = options.matches;
  }

  matches(data?: BuilderNodeData | null): boolean {
    if (!data) return false;
    if (this.matchFn) {
      return this.matchFn(data);
    }
    const targetId = data.id || '';
    const candidates = [this.id, ...this.aliases];
    return candidates.some((candidate) => targetId === candidate || targetId.startsWith(`${candidate}-`));
  }

  toPaletteItem(overrides?: Partial<PaletteDragItem>): PaletteDragItem {
    const base: PaletteDragItem = {
      id: overrides?.id ?? this.id,
      label: overrides?.label ?? this.label,
      description: overrides?.description ?? this.description,
      kind: overrides?.kind ?? this.kind,
      nodeType: overrides?.nodeType ?? this.nodeType,
      badge: overrides?.badge ?? this.badge,
    };

    return base;
  }
}

const QUICK_COLOR = {
  core: 'bg-blue-100 dark:bg-blue-900/30',
  logic: 'bg-orange-100 dark:bg-orange-900/30',
  tools: 'bg-yellow-100 dark:bg-yellow-900/30',
  data: 'bg-purple-100 dark:bg-purple-900/30',
  neutral: 'bg-gray-100 dark:bg-gray-800',
  output: 'bg-green-100 dark:bg-green-900/30',
};

export const NODE_CLASSES: BuilderNodeClass[] = [
  // Trigger & entry nodes
  new BuilderNodeClass({
    id: 'triggers',
    label: 'Triggers',
    description: 'Triggers start your workflow. Workflows can have multiple triggers.',
    kind: 'entry',
    nodeType: 'flowNode',
    family: 'trigger',
    aliases: ['intent'],
    palette: { category: 'triggers', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
    inspector: 'trigger',
    matches: (data) => data.kind === 'entry' && !data.id?.includes('trigger-'),
  }),
  new BuilderNodeClass({
    id: 'webhook-trigger',
    label: 'Webhook',
    description: 'Trigger workflow via HTTP webhook',
    kind: 'entry',
    nodeType: 'flowNode',
    family: 'trigger',
    palette: { category: 'triggers' },
    inspector: 'trigger',
  }),
  new BuilderNodeClass({
    id: 'schedule-trigger',
    label: 'Schedule',
    description: 'Trigger workflow on a schedule',
    kind: 'entry',
    nodeType: 'flowNode',
    family: 'trigger',
    palette: { category: 'triggers' },
    inspector: 'trigger',
  }),
  new BuilderNodeClass({
    id: 'manual-trigger',
    label: 'Manual',
    description: 'Manually trigger workflow',
    kind: 'entry',
    nodeType: 'flowNode',
    family: 'trigger',
    palette: { category: 'triggers' },
    inspector: 'trigger',
  }),
  new BuilderNodeClass({
    id: 'tables-trigger',
    label: 'Tables',
    description: 'Trigger from tables',
    kind: 'entry',
    nodeType: 'flowNode',
    family: 'trigger',
    palette: { category: 'triggers' },
    inspector: 'trigger',
  }),
  new BuilderNodeClass({
    id: 'chat-trigger',
    label: 'Chat',
    description: 'Trigger from conversation input',
    kind: 'entry',
    nodeType: 'flowNode',
    family: 'trigger',
    palette: { category: 'triggers' },
    inspector: 'trigger',
  }),
  new BuilderNodeClass({
    id: 'form-trigger',
    label: 'Form',
    description: 'Trigger workflow from a custom form submission',
    kind: 'entry',
    nodeType: 'flowNode',
    family: 'trigger',
    palette: { category: 'triggers' },
    inspector: 'trigger',
  }),

  // MCP nodes
  new BuilderNodeClass({
    id: 'mcp-interface',
    label: 'MCP Interface',
    description: 'Connect to MCP servers',
    kind: 'tool',
    nodeType: 'flowNode',
    family: 'mcp',
    palette: { category: 'mcp', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
    inspector: 'mcp',
  }),
  new BuilderNodeClass({
    id: 'mcp-tool',
    label: 'MCP Tool',
    description: 'Use MCP tools',
    kind: 'tool',
    nodeType: 'flowNode',
    family: 'mcp-tool',
    palette: { category: 'mcp', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
    inspector: 'mcp',
    matches: (data) => !!(data as any).toolData || data.id?.startsWith('tool-'),
  }),
  new BuilderNodeClass({
    id: 'mcp-resource',
    label: 'MCP Resource',
    description: 'Access MCP resources',
    kind: 'tool',
    nodeType: 'flowNode',
    family: 'mcp-resource',
    palette: { category: 'mcp', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
    inspector: 'mcp',
  }),
  new BuilderNodeClass({
    id: 'mcp',
    label: 'MCP',
    description: 'Model Context Protocol tools and interfaces',
    kind: 'tool',
    nodeType: 'flowNode',
    family: 'mcp',
    aliases: ['mcp-generic'],
    palette: { category: 'mcp', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
    inspector: 'mcp',
    matches: (data) => !!(data as any).apiData || !!(data as any).toolData || data.id?.startsWith('mcp-'),
  }),

  // AI nodes
  new BuilderNodeClass({
    id: 'ai-agent',
    label: 'Agents',
    description: 'Create autonomous AI agents',
    kind: 'reasoning',
    nodeType: 'flowNode',
    family: 'ai',
    aliases: ['agent'],
    palette: { category: 'ai', quickGroup: 'Core', quickBg: QUICK_COLOR.core },
    inspector: 'ai',
  }),
  new BuilderNodeClass({
    id: 'guardrail',
    label: 'Guardrail',
    description: 'Validate content with safety rules (PII, toxicity, keywords)',
    kind: 'guardrail',
    nodeType: 'guardrailNode',
    family: 'ai',
    aliases: ['guardrails', 'safety', 'validation', 'filter'],
    palette: { category: 'ai', quickGroup: 'Core', quickBg: QUICK_COLOR.logic },
    inspector: 'ai',
  }),
  new BuilderNodeClass({
    id: 'classify',
    label: 'Classify',
    description: 'AI-powered classification with multiple output branches',
    kind: 'classify',
    nodeType: 'classifyNode',
    family: 'ai',
    palette: { category: 'ai', quickGroup: 'Core', quickBg: QUICK_COLOR.logic },
    inspector: 'ai',
  }),
  new BuilderNodeClass({
    id: 'browser_agent',
    label: 'Browser Agent',
    description: 'LLM-driven browser: navigate, fill forms, extract structured data',
    kind: 'browser_agent',
    nodeType: 'flowNode',
    family: 'ai',
    aliases: ['browser-agent', 'browse', 'browser', 'web-agent'],
    palette: { category: 'ai', quickGroup: 'Core', quickBg: QUICK_COLOR.core },
    inspector: 'ai',
  }),

  // Flow control nodes
  new BuilderNodeClass({
    id: 'if-else',
    label: 'If / else',
    description: 'Conditional branching',
    kind: 'decision',
    nodeType: 'decisionNode',
    family: 'condition',
    aliases: ['ifElse'],
    badge: 'IF/ELSE',
    palette: { category: 'flow', quickGroup: 'Logic', quickBg: QUICK_COLOR.logic },
    inspector: 'condition',
  }),
  new BuilderNodeClass({
    id: 'switch',
    label: 'Switch',
    description: 'Multi-way branching based on value',
    kind: 'switch',
    nodeType: 'switchNode',
    family: 'condition',
    aliases: ['switchCase'],
    badge: 'SWITCH',
    palette: { category: 'flow', quickGroup: 'Logic', quickBg: QUICK_COLOR.logic },
    inspector: 'condition',
  }),
  new BuilderNodeClass({
    id: 'split',
    label: 'Split',
    description: 'Iterate over a list of items in parallel',
    kind: 'split',
    nodeType: 'splitNode',
    family: 'loop',
    badge: 'SPLIT',
    palette: { category: 'flow', quickGroup: 'Logic', quickBg: QUICK_COLOR.logic },
    inspector: 'split',
  }),
  new BuilderNodeClass({
    id: 'aggregate',
    label: 'Aggregate',
    description: 'Collect multiple items into a single list',
    kind: 'aggregate',
    nodeType: 'aggregateNode',
    family: 'loop',
    badge: 'AGGREGATE',
    palette: { category: 'flow', quickGroup: 'Logic', quickBg: QUICK_COLOR.logic },
    inspector: 'aggregate',
  }),
  new BuilderNodeClass({
    id: 'while-group',
    label: 'While',
    description: 'Repeat a group of steps while a condition is true',
    kind: 'whileGroup',
    nodeType: 'whileGroupNode',
    family: 'loop',
    badge: 'WHILE',
    palette: { category: 'flow', quickGroup: 'Logic', quickBg: QUICK_COLOR.logic },
    inspector: 'while-group',
  }),
  new BuilderNodeClass({
    id: 'exit',
    label: 'Exit',
    description: 'Exit the workflow execution',
    kind: 'action',
    nodeType: 'exitNode',
    family: 'core',
    badge: 'EXIT',
    palette: { category: 'core', quickGroup: 'Logic', quickBg: QUICK_COLOR.logic },
    inspector: 'exit',
  }),
  new BuilderNodeClass({
    id: 'response',
    label: 'Respond to Chat',
    description: 'Send a response to the chat',
    kind: 'output',
    nodeType: 'responseNode',
    family: 'output',
    badge: 'RESPONSE',
    palette: { category: 'core', quickGroup: 'Core', quickBg: QUICK_COLOR.output },
    inspector: 'response',
  }),
  new BuilderNodeClass({
    id: 'respond_to_webhook',
    label: 'Respond to Webhook',
    description: 'Send a response back to the webhook caller',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['respond-to-webhook', 'webhook-response'],
    palette: { category: 'core', quickGroup: 'Core', quickBg: QUICK_COLOR.output },
  }),
  // Option node hidden from palette - will be revisited later
  // new BuilderNodeClass({
  //   id: 'option',
  //   label: 'Option',
  //   description: 'User choice with multiple possible branches',
  //   kind: 'option',
  //   nodeType: 'optionNode',
  //   family: 'condition',
  //   badge: 'OPTION',
  //   palette: { category: 'flow', quickGroup: 'Logic', quickBg: QUICK_COLOR.logic },
  //   inspector: 'option',
  // }),
  new BuilderNodeClass({
    id: 'user-approval',
    label: 'User Approval',
    description: 'Pause workflow for user approval with configurable outcomes',
    kind: 'approval',
    nodeType: 'userApprovalNode',
    family: 'condition',
    badge: 'APPROVAL',
    palette: { category: 'flow', quickGroup: 'Logic', quickBg: QUICK_COLOR.logic },
    inspector: 'approval',
  }),
  new BuilderNodeClass({
    id: 'transform',
    label: 'Transform',
    description: 'Transform and reshape data',
    kind: 'transform',
    nodeType: 'flowNode',
    family: 'core',
    palette: { category: 'flow', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
    inspector: 'transform',
  }),
  new BuilderNodeClass({
    id: 'merge',
    label: 'Merge',
    description: 'Merge multiple branches into one',
    kind: 'merge',
    nodeType: 'mergeNode',
    family: 'core',
    palette: { category: 'flow', quickGroup: 'Logic', quickBg: QUICK_COLOR.logic },
    inspector: 'merge',
  }),
  new BuilderNodeClass({
    id: 'fork',
    label: 'Fork',
    description: 'Split flow into multiple parallel branches',
    kind: 'fork',
    nodeType: 'forkNode',
    family: 'core',
    palette: { category: 'flow', quickGroup: 'Logic', quickBg: QUICK_COLOR.logic },
    inspector: 'fork',
  }),
  new BuilderNodeClass({
    id: 'wait',
    label: 'Wait',
    description: 'Wait for a specified duration',
    kind: 'wait',
    nodeType: 'flowNode',
    family: 'core',
    palette: { category: 'core', quickGroup: 'Logic', quickBg: QUICK_COLOR.logic },
    inspector: 'wait',
  }),
  new BuilderNodeClass({
    id: 'download_file',
    label: 'Download File',
    description: 'Download a file from URL and store it for later access',
    kind: 'download_file',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['download', 'file-download'],
    palette: { category: 'core', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
    inspector: 'download_file',
  }),
  new BuilderNodeClass({
    id: 'http-request',
    label: 'HTTP Request',
    description: 'Make HTTP requests to external APIs',
    kind: 'http_request',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['http', 'api-request', 'rest', 'fetch'],
    palette: { category: 'core', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
    inspector: 'http_request',
  }),
  new BuilderNodeClass({
    id: 'data_input',
    label: 'Data Input',
    description: 'Provide text, files, and images as input data',
    kind: 'data_input',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['data-input', 'file-input'],
    palette: { category: 'core', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
    inspector: 'data_input',
  }),

  // CRUD operations on tables - Create sub-options
  new BuilderNodeClass({
    id: 'create-row',
    label: 'Create Row',
    description: 'Create new rows in a table',
    kind: 'crud',
    nodeType: 'flowNode',
    family: 'data',
    palette: { category: 'core', subcategory: 'data', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),
  new BuilderNodeClass({
    id: 'create-column',
    label: 'Create Column',
    description: 'Add new columns to a table',
    kind: 'crud',
    nodeType: 'flowNode',
    family: 'data',
    palette: { category: 'core', subcategory: 'data', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),
  new BuilderNodeClass({
    id: 'read-row',
    label: 'Get Row',
    description: 'Get rows from a table with column mappings',
    kind: 'crud',
    nodeType: 'flowNode',
    family: 'data',
    palette: { category: 'core', subcategory: 'data', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),
  new BuilderNodeClass({
    id: 'update-row',
    label: 'Update Row',
    description: 'Update existing rows in a table',
    kind: 'crud',
    nodeType: 'flowNode',
    family: 'data',
    palette: { category: 'core', subcategory: 'data', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),
  new BuilderNodeClass({
    id: 'delete-row',
    label: 'Delete Row',
    description: 'Delete rows from a table',
    kind: 'crud',
    nodeType: 'flowNode',
    family: 'data',
    palette: { category: 'core', subcategory: 'data', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),
  new BuilderNodeClass({
    id: 'find-row',
    label: 'Find Rows',
    description: 'Query a data source and process each row in parallel',
    kind: 'find',
    nodeType: 'flowNode',
    family: 'data',
    palette: { category: 'core', subcategory: 'data', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),
  new BuilderNodeClass({
    id: 'interface',
    label: 'Interface',
    description: 'Build interactive pages for your workflows',
    kind: 'interface',
    nodeType: 'interfaceNode',
    family: 'core',
    palette: { category: 'core' },
  }),

  // Utility nodes
  new BuilderNodeClass({
    id: 'code',
    label: 'Code',
    description: 'Execute custom code (JavaScript, Python, TypeScript, Bash)',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['script', 'run-code'],
    palette: { category: 'core', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
  }),
  new BuilderNodeClass({
    id: 'send_email',
    label: 'Send Email',
    description: 'Send an email via SMTP',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['send-email', 'email'],
    palette: { category: 'core', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
  }),
  new BuilderNodeClass({
    id: 'email_inbox',
    label: 'Email Inbox',
    description: 'Read a mailbox and act on messages via IMAP',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['email-inbox', 'imap', 'inbox', 'read-email'],
    palette: { category: 'core', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
  }),
  new BuilderNodeClass({
    id: 'sub_workflow',
    label: 'Sub-Workflow',
    description: 'Execute another workflow as a sub-workflow',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['sub-workflow', 'subworkflow'],
    palette: { category: 'flow', quickGroup: 'Logic', quickBg: QUICK_COLOR.logic },
  }),
  new BuilderNodeClass({
    id: 'rss',
    label: 'RSS',
    description: 'Fetch and parse RSS/Atom feeds',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    palette: { category: 'core', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
  }),

  // Data manipulation nodes
  new BuilderNodeClass({
    id: 'filter',
    label: 'Filter',
    description: 'Filter items based on conditions',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    palette: { category: 'core', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),
  new BuilderNodeClass({
    id: 'sort',
    label: 'Sort',
    description: 'Sort items by field',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    palette: { category: 'core', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),
  new BuilderNodeClass({
    id: 'limit',
    label: 'Limit',
    description: 'Limit the number of items',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    palette: { category: 'core', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),
  new BuilderNodeClass({
    id: 'remove_duplicates',
    label: 'Remove Duplicates',
    description: 'Remove duplicate items from a list',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['remove-duplicates', 'deduplicate', 'dedup'],
    palette: { category: 'core', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),
  new BuilderNodeClass({
    id: 'summarize',
    label: 'Summarize',
    description: 'Summarize data with aggregation functions (sum, avg, count, etc.)',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    palette: { category: 'core', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),
  new BuilderNodeClass({
    id: 'compare_datasets',
    label: 'Compare Datasets',
    description: 'Compare two datasets and find differences',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['compare-datasets'],
    palette: { category: 'core', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),
  new BuilderNodeClass({
    id: 'set',
    label: 'Set / Edit Fields',
    description: 'Assign or transform fields on the input data',
    kind: 'set',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['edit-fields', 'edit_fields', 'assign'],
    palette: { category: 'core', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),
  new BuilderNodeClass({
    id: 'html_extract',
    label: 'HTML Extract',
    description: 'Parse HTML using CSS selectors and extract structured data',
    kind: 'html_extract',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['html-extract', 'scrape', 'css-extract'],
    palette: { category: 'core', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),

  // File & format nodes
  new BuilderNodeClass({
    id: 'convert_to_file',
    label: 'Convert to File',
    description: 'Convert data to a file (CSV, JSON, XML, etc.)',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['convert-to-file'],
    palette: { category: 'core', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),
  new BuilderNodeClass({
    id: 'extract_from_file',
    label: 'Extract From File',
    description: 'Extract data from a file (CSV, JSON, XML, etc.)',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['extract-from-file'],
    palette: { category: 'core', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),
  new BuilderNodeClass({
    id: 'compression',
    label: 'Compression',
    description: 'Compress or decompress files (gzip, zip)',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['compress', 'decompress', 'zip', 'gzip'],
    palette: { category: 'core', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
  }),
  new BuilderNodeClass({
    id: 'xml',
    label: 'XML',
    description: 'Parse or build XML data',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    palette: { category: 'core', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),

  // Crypto & date nodes
  new BuilderNodeClass({
    id: 'crypto_jwt',
    label: 'Crypto / JWT',
    description: 'Sign, verify, encode, or decode JWT tokens',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['crypto-jwt', 'jwt'],
    palette: { category: 'core', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
  }),
  new BuilderNodeClass({
    id: 'date_time',
    label: 'Date & Time',
    description: 'Format, parse, and manipulate dates and times',
    kind: 'action',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['date-time', 'datetime'],
    palette: { category: 'core', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
  }),

  // Task management
  new BuilderNodeClass({
    id: 'task',
    label: 'Task',
    description: 'Create, read, update, delete, or list agent tasks',
    kind: 'task',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['task-crud', 'task_crud', 'manage-tasks'],
    palette: { category: 'core', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
  }),

  // Stop on Error - terminates the entire workflow
  new BuilderNodeClass({
    id: 'stop_on_error',
    label: 'Stop on Error',
    description: 'Stop the entire workflow with an error (all branches cancelled, run → FAILED)',
    kind: 'stop_on_error',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['stop-on-error', 'stop', 'halt'],
    palette: { category: 'core', quickGroup: 'Logic', quickBg: QUICK_COLOR.logic },
  }),

  // Remote server nodes
  new BuilderNodeClass({
    id: 'ssh',
    label: 'SSH',
    description: 'Execute commands on a remote server via SSH',
    kind: 'ssh',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['ssh-command', 'remote-command'],
    palette: { category: 'core', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
  }),
  new BuilderNodeClass({
    id: 'sftp',
    label: 'SFTP',
    description: 'Upload, download, list, or delete files on a remote server via SFTP',
    kind: 'sftp',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['sftp-transfer', 'remote-file'],
    palette: { category: 'core', quickGroup: 'Tools', quickBg: QUICK_COLOR.tools },
  }),
  new BuilderNodeClass({
    id: 'database',
    label: 'Database',
    description: 'Execute SQL queries on PostgreSQL, MySQL, or MSSQL databases',
    kind: 'database',
    nodeType: 'flowNode',
    family: 'core',
    aliases: ['sql', 'db-query', 'database-query'],
    palette: { category: 'core', quickGroup: 'Data', quickBg: QUICK_COLOR.data },
  }),

  // Additional triggers
  new BuilderNodeClass({
    id: 'error-trigger',
    label: 'Error',
    description: 'Trigger workflow on error',
    kind: 'entry',
    nodeType: 'flowNode',
    family: 'trigger',
    aliases: ['error'],
    palette: { category: 'triggers' },
    inspector: 'trigger',
  }),

  // Notes
  new BuilderNodeClass({
    id: 'note',
    label: 'Note',
    description: 'Add a note or comment.',
    kind: 'action',
    nodeType: 'noteNode',
    family: 'note',
    palette: { category: 'core', quickGroup: 'Core', quickBg: QUICK_COLOR.neutral },
    inspector: 'note',
  }),
];

export const NODE_CATEGORY_META: Record<
  PaletteCategoryId,
  { label: string; description: string; icon: IconComponent }
> = {
  triggers: {
    label: 'Triggers',
    description: 'Triggers start your workflow. Workflows can have multiple triggers.',
    icon: Zap,
  },
  mcp: {
    label: 'MCPs',
    description: 'Model Context Protocol tools and interfaces',
    icon: Network,
  },
  ai: {
    label: 'AI',
    description: 'Build autonomous agents, summarize or search documents, etc.',
    icon: Bot,
  },
  flow: {
    label: 'Flow',
    description: 'Control flow, conditions, loops, and branching',
    icon: GitBranch,
  },
  core: {
    label: 'Core',
    description: 'Tables, interfaces, and utilities',
    icon: Cpu,
  },
  data: {
    label: 'Data',
    description: 'Search or visualize your data',
    icon: Database,
  },
  developer: {
    label: 'Developer',
    description: 'Developer-centric utilities',
    icon: Code,
  },
  user: {
    label: 'User',
    description: 'User facing surfaces',
    icon: User,
  },
};

export interface PaletteCategoryNode {
  id: string;
  name: string;
  description: string;
  icon: IconComponent;
  type: 'category' | 'subcategory' | 'node';
  parentId?: string;
  children?: PaletteCategoryNode[];
  nodeClassId?: string;
}

const metaFallback: PaletteCategoryNode['icon'] = Square;

export function findNodeClassById(nodeId: string): BuilderNodeClass | undefined {
  if (!nodeId) return undefined;
  const exactMatch = NODE_CLASSES.find((klass) => nodeId === klass.id)
    ?? NODE_CLASSES.find((klass) => (klass.aliases ?? []).includes(nodeId));
  if (exactMatch) return exactMatch;
  
  // Then try prefix matches. Canonical IDs must win over aliases so an alias
  // like Guardrail's "filter" cannot shadow the actual Filter node.
  const sortedByIdLength = [...NODE_CLASSES].sort((a, b) => b.id.length - a.id.length);
  const idPrefixMatch = sortedByIdLength.find((klass) => nodeId.startsWith(`${klass.id}-`));
  if (idPrefixMatch) return idPrefixMatch;

  const sortedByAliasLength = [...NODE_CLASSES].sort((a, b) => {
    const aLength = Math.max(0, ...(a.aliases ?? []).map(alias => alias.length));
    const bLength = Math.max(0, ...(b.aliases ?? []).map(alias => alias.length));
    return bLength - aLength;
  });
  
  return sortedByAliasLength.find((klass) => {
    return (klass.aliases ?? []).some((candidate) => nodeId.startsWith(`${candidate}-`));
  });
}

export function matchNodeClass(data?: BuilderNodeData | null): BuilderNodeClass | null {
  if (!data) return null;
  // Prioritize explicit matches (tool/api/resources)
  const prioritized = [
    'mcp-tool',
    'mcp-resource',
    'mcp-interface',
    'mcp',
    'if-else',
    'switch',
    'while-group',
    'note',
  ];
  for (const id of prioritized) {
    const candidate = findNodeClassById(id);
    if (candidate?.matches(data)) return candidate;
  }
  const direct = findNodeClassById(data.id || '');
  if (direct) return direct;
  const fallback = NODE_CLASSES.find((klass) => klass.matches(data));
  return fallback ?? null;
}

export function getPaletteItemDataFromId(nodeId: string, label?: string, description?: string): PaletteDragItem {
  const klass = findNodeClassById(nodeId);
  if (klass) {
    return klass.toPaletteItem({ label, description });
  }
  return {
    id: nodeId,
    label: label || nodeId,
    description: description || '',
    kind: 'action',
    nodeType: 'flowNode',
  };
}

/**
 * Resolve icon for a node class.
 * Delegates to NODE_ICON_REGISTRY (single source of truth) via resolveNodeIcon().
 */
export function getNodeIcon(klass: BuilderNodeClass): IconComponent {
  return resolveNodeIcon(klass.id, klass.kind, klass.family).icon;
}

export function getPaletteCategoryTree(classes: BuilderNodeClass[] = NODE_CLASSES): PaletteCategoryNode[] {
  const categories = new Map<string, PaletteCategoryNode>();
  const ensureCategory = (categoryId: PaletteCategoryId) => {
    if (!categories.has(categoryId)) {
      const meta = NODE_CATEGORY_META[categoryId];
      categories.set(categoryId, {
        id: categoryId,
        name: meta?.label ?? categoryId,
        description: meta?.description ?? '',
        icon: meta?.icon ?? metaFallback,
        type: 'category',
        children: [],
      });
    }
    return categories.get(categoryId)!;
  };

  classes.forEach((klass) => {
    const palette = klass.palette;
    if (!palette) return;
    const categoryNode = ensureCategory(palette.category);
    const subcategoryId = palette.subcategory;

    if (subcategoryId) {
      let subcategoryNode = categoryNode.children?.find((child) => child.id === subcategoryId);
      if (!subcategoryNode) {
        const subcategoryNames: Record<string, string> = {
          logic: 'Logic',
          data: 'Tables',
          create: 'Create',
          read: 'Read',
          update: 'Update',
          delete: 'Delete',
        };
        const subcategoryDescriptions: Record<string, string> = {
          logic: 'Control flow, conditions, loops, and data transformations',
          data: 'Search or visualize your data',
          create: 'Create new records in data sources',
          read: 'Read and query data from sources',
          update: 'Update existing records',
          delete: 'Delete records from data sources',
        };
        const subcategoryIcons: Record<string, IconComponent> = {
          data: Database,
          create: Plus,
          read: Search,
          update: Pencil,
          delete: Trash2,
        };
        subcategoryNode = {
          id: subcategoryId,
          name: subcategoryNames[subcategoryId] || subcategoryId,
          description: subcategoryDescriptions[subcategoryId] || '',
          icon: subcategoryIcons[subcategoryId] || Code,
          type: 'subcategory',
          parentId: categoryNode.id,
          children: [],
        };
        categoryNode.children?.push(subcategoryNode);
      }
      subcategoryNode.children?.push({
        id: klass.id,
        name: klass.label,
        description: klass.description,
        icon: getNodeIcon(klass),
        type: 'node',
        parentId: subcategoryId,
        nodeClassId: klass.id,
      });
    } else {
      categoryNode.children?.push({
        id: klass.id,
        name: klass.label,
        description: klass.description,
        icon: getNodeIcon(klass),
        type: 'node',
        parentId: categoryNode.id,
        nodeClassId: klass.id,
      });
    }
  });

  return Array.from(categories.values());
}
