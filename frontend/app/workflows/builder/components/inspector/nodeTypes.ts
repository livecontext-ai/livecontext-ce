import { Zap, Bot, Shield, Tag, Code, RotateCw, CheckCircle, ArrowRightLeft, Cpu, MousePointerClick, MessageSquare, Play, Layout, Webhook, Table, Clock, Workflow, FileText, List, Milestone, FoldVertical, BarChart3, CalendarClock, KeyRound, FileCode2, Archive, Rss, FileOutput, FileInput, GitCompareArrows, Send, Mail, Inbox, Shuffle, OctagonX, Terminal, HardDrive, Database, AlertTriangle, Globe } from 'lucide-react';

export const AI_TYPES = [
  {
    id: 'ai-agent',
    name: 'AI Agent',
    description: 'Create autonomous AI agents',
    icon: Bot,
  },
  {
    id: 'browser_agent',
    name: 'Browser Agent',
    description: 'LLM-driven browser navigation: click, fill forms, extract data',
    icon: Globe,
  },
  {
    id: 'ai-summarize',
    name: 'Summarize',
    description: 'Summarize documents and text',
    icon: Bot,
  },
  {
    id: 'guardrail',
    name: 'Guardrail',
    description: 'Apply compliance checks and guardrails',
    icon: Shield,
  },
  {
    id: 'classify',
    name: 'Classify',
    description: 'Classify and categorize content',
    icon: Tag,
  },
];

export const CORE_LOGIC_TYPES = [
  {
    id: 'if-else',
    name: 'If / else',
    description: 'Conditional branching',
    icon: Milestone,
  },
  {
    id: 'switch',
    name: 'Switch',
    description: 'Multi-way branching based on value',
    icon: Milestone,
  },
  {
    id: 'user-approval',
    name: 'User approval',
    description: 'Wait for user approval',
    icon: CheckCircle,
  },
  {
    id: 'while',
    name: 'While',
    description: 'Loop while condition is true',
    icon: RotateCw,
  },
  {
    id: 'transform',
    name: 'Transform',
    description: 'Transform data',
    icon: Shuffle,
  },
  {
    id: 'filter',
    name: 'Filter',
    description: 'Keep only items matching conditions',
    icon: List,
  },
  {
    id: 'sort',
    name: 'Sort',
    description: 'Reorder items by fields',
    icon: ArrowRightLeft,
  },
  {
    id: 'limit',
    name: 'Limit',
    description: 'Pass through only first/last N items',
    icon: Milestone,
  },
  {
    id: 'remove-duplicates',
    name: 'Remove Duplicates',
    description: 'Deduplicate items by fields',
    icon: FoldVertical,
  },
  {
    id: 'summarize',
    name: 'Summarize',
    description: 'Aggregate data: sum, avg, count, min, max',
    icon: BarChart3,
  },
  {
    id: 'date-time',
    name: 'Date/Time',
    description: 'Parse, format, convert date/time values',
    icon: CalendarClock,
  },
  {
    id: 'crypto-jwt',
    name: 'Crypto/JWT',
    description: 'Hash, encrypt, JWT, base64',
    icon: KeyRound,
  },
  {
    id: 'xml',
    name: 'XML',
    description: 'Parse/build XML data',
    icon: FileCode2,
  },
  {
    id: 'compression',
    name: 'Compression',
    description: 'Compress/decompress data',
    icon: Archive,
  },
  {
    id: 'rss',
    name: 'RSS',
    description: 'Fetch and parse RSS feeds',
    icon: Rss,
  },
  {
    id: 'convert-to-file',
    name: 'Convert to File',
    description: 'Export data to CSV, Excel, JSON, or text',
    icon: FileOutput,
  },
  {
    id: 'extract-from-file',
    name: 'Extract from File',
    description: 'Import data from CSV, Excel, or JSON files',
    icon: FileInput,
  },
  {
    id: 'compare-datasets',
    name: 'Compare Datasets',
    description: 'Compare two datasets, find matches and differences',
    icon: GitCompareArrows,
  },
  {
    id: 'sub-workflow',
    name: 'Sub-Workflow',
    description: 'Execute another workflow as a function',
    icon: Workflow,
  },
  {
    id: 'respond-to-webhook',
    name: 'Respond to Webhook',
    description: 'Control HTTP response to webhook caller',
    icon: Send,
  },
  {
    id: 'send-email',
    name: 'Send Email',
    description: 'Send emails via SMTP',
    icon: Mail,
  },
  {
    id: 'email-inbox',
    name: 'Email Inbox',
    description: 'Read a mailbox & act on messages via IMAP',
    icon: Inbox,
  },
  {
    id: 'code',
    name: 'Code',
    description: 'Execute custom code (JS, Python, TS, Bash)',
    icon: Code,
  },
  {
    id: 'stop-on-error',
    name: 'Stop on Error',
    description: 'Fail workflow with an error message',
    icon: OctagonX,
  },
  {
    id: 'ssh',
    name: 'SSH',
    description: 'Execute commands on remote servers via SSH',
    icon: Terminal,
  },
  {
    id: 'sftp',
    name: 'SFTP',
    description: 'File operations via SFTP',
    icon: HardDrive,
  },
  {
    id: 'database',
    name: 'Database',
    description: 'Execute SQL queries (PostgreSQL, MySQL, MSSQL)',
    icon: Database,
  },
];

export const CORE_DIRECT_TYPES = [
  {
    id: 'http-request',
    name: 'HTTP Request',
    description: 'Make HTTP requests',
    icon: Cpu,
  },
  {
    id: 'webhook',
    name: 'Webhook',
    description: 'Set up webhooks',
    icon: Cpu,
  },
];

export const CORE_INTERFACE_TYPES = [
  {
    id: 'interface',
    name: 'Interfaces',
    description: 'Visual interfaces & interactive apps',
    icon: Layout,
  },
];

export const TRIGGER_TYPES = [
  {
    id: 'webhook-trigger',
    name: 'Webhook',
    description: 'Trigger workflow via HTTP webhook',
    icon: Webhook,
  },
  {
    id: 'manual-trigger',
    name: 'Manual',
    description: 'Manually trigger workflow',
    icon: MousePointerClick,
  },
  {
    id: 'tables-trigger',
    name: 'Tables',
    description: 'Trigger from tables',
    icon: Table,
  },
  {
    id: 'workflows-trigger',
    name: 'Workflows',
    description: 'Triggered when another workflow completes',
    icon: Workflow,
  },
  {
    id: 'error-trigger',
    name: 'Error',
    description: 'Pick a workflow to watch - fires when its run fails',
    icon: AlertTriangle,
  },
  {
    id: 'chat-trigger',
    name: 'Chat',
    description: 'Trigger from conversation input',
    icon: MessageSquare,
  },
  {
    id: 'schedule-trigger',
    name: 'Scheduler',
    description: 'Schedule workflow with cron or time intervals',
    icon: Clock,
  },
  {
    id: 'form-trigger',
    name: 'Form',
    description: 'Trigger workflow from a custom form submission',
    icon: FileText,
  },
];

