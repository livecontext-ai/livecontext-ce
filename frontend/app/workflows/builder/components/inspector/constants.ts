import { Bot, CheckCircle, Cpu, Repeat, ArrowLeftRight, Zap, Shield, Tag } from 'lucide-react';

export const AI_TYPES = [
  {
    id: 'ai-agent',
    name: 'AI Agent',
    description: 'Create autonomous AI agents',
    icon: Bot,
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
    icon: Cpu,
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
    icon: Repeat,
  },
  {
    id: 'transform',
    name: 'Transform',
    description: 'Transform data',
    icon: ArrowLeftRight,
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

export const TRIGGER_TYPES = [
  {
    id: 'webhook-trigger',
    name: 'Webhook',
    description: 'Trigger workflow via HTTP webhook',
    icon: Zap,
  },
  {
    id: 'schedule-trigger',
    name: 'Schedule',
    description: 'Trigger workflow on a schedule',
    icon: Zap,
  },
  {
    id: 'manual-trigger',
    name: 'Manual',
    description: 'Manually trigger workflow',
    icon: Zap,
  },
  {
    id: 'tables-trigger',
    name: 'Tables',
    description: 'Trigger from tables',
    icon: Zap,
  },
];
