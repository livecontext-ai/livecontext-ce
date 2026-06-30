import {
  Bot,
  Globe,
  RotateCw,
  MessageCircleReply,
  MessageSquare,
  Workflow,
  CheckCircle,
  LogOut,
  Wrench,
  ArrowRightLeft,
  CirclePause,
  Download,
  FoldVertical,
  FileInput,
  MousePointerClick,
  Webhook,
  CalendarClock,
  Table,
  FileText,
  Tag,
  Shield,
  List,
  Code,
  Milestone,
  Shuffle,
  Search,
  Plus,
  Pencil,
  Trash2,
  Square,
  Database,
  Cpu,
  Monitor,
  Zap,
  BarChart3,
  KeyRound,
  FileCode2,
  Archive,
  Rss,
  FileOutput,
  FileInput as FileInputIcon,
  GitCompareArrows,
  Send,
  Mail,
  Inbox,
  AlertTriangle,
  ClipboardList,
  OctagonX,
  Terminal,
  HardDrive,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { createElement, forwardRef } from 'react';

const LAYERS_BASE = [
  'M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 .83.18 2 2 0 0 0 .83-.18l8.58-3.9a1 1 0 0 0 0-1.831z',
  'M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 .825.178',
  'M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l2.116-.962',
];
const SVG_DEFAULTS = { xmlns: 'http://www.w3.org/2000/svg', width: 24, height: 24, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round' };

/* eslint-disable react/display-name -- displayName is assigned after the forwardRef cast */
/** Custom icon: Layers with a + sign (split) */
const LayersPlus = forwardRef<SVGSVGElement, React.SVGProps<SVGSVGElement>>((props, ref) =>
  createElement('svg', { ref, ...SVG_DEFAULTS, ...props },
    ...LAYERS_BASE.map(d => createElement('path', { key: d.slice(0, 10), d })),
    createElement('path', { d: 'M16 17h6' }),
    createElement('path', { d: 'M19 14v6' }),
  )
) as unknown as LucideIcon;
LayersPlus.displayName = 'LayersPlus';

/** Custom icon: Layers with a - sign (aggregate) */
const LayersMinus = forwardRef<SVGSVGElement, React.SVGProps<SVGSVGElement>>((props, ref) =>
  createElement('svg', { ref, ...SVG_DEFAULTS, ...props },
    ...LAYERS_BASE.map(d => createElement('path', { key: d.slice(0, 10), d })),
    createElement('path', { d: 'M16 17h6' }),
  )
) as unknown as LucideIcon;
LayersMinus.displayName = 'LayersMinus';

/** Custom icon: Horizontal GitFork - 1 input left → 2 outputs right (diverge) */
const HorizontalFork = forwardRef<SVGSVGElement, React.SVGProps<SVGSVGElement>>((props, ref) =>
  createElement('svg', { ref, ...SVG_DEFAULTS, ...props },
    createElement('circle', { cx: 6, cy: 12, r: 3 }),
    createElement('circle', { cx: 18, cy: 6, r: 3 }),
    createElement('circle', { cx: 18, cy: 18, r: 3 }),
    createElement('path', { d: 'M15 6h-2c-.6 0-1 .4-1 1v10c0 .6.4 1 1 1h2' }),
    createElement('path', { d: 'M12 12H9' }),
  )
) as unknown as LucideIcon;
HorizontalFork.displayName = 'HorizontalFork';

/** Custom icon: Horizontal GitMerge - 2 inputs left → 1 output right (converge) */
const HorizontalMerge = forwardRef<SVGSVGElement, React.SVGProps<SVGSVGElement>>((props, ref) =>
  createElement('svg', { ref, ...SVG_DEFAULTS, ...props },
    createElement('circle', { cx: 6, cy: 6, r: 3 }),
    createElement('circle', { cx: 6, cy: 18, r: 3 }),
    createElement('circle', { cx: 18, cy: 12, r: 3 }),
    createElement('path', { d: 'M9 6h2c.6 0 1 .4 1 1v10c0 .6-.4 1-1 1H9' }),
    createElement('path', { d: 'M12 12h3' }),
  )
) as unknown as LucideIcon;
HorizontalMerge.displayName = 'HorizontalMerge';
/* eslint-enable react/display-name */

import type { BuilderNodeKind, NodeVisuals } from '../types';

export const NODE_VISUALS: Record<BuilderNodeKind, NodeVisuals> = {
  entry: {
    accent: '#34d399',
    iconBg: 'bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600',
    badgeBg: 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700',
    textClass: 'text-emerald-700',
  },
  reasoning: {
    accent: '#3b82f6',
    iconBg: 'bg-sky-50 dark:bg-sky-900/30 text-sky-600',
    badgeBg: 'bg-sky-100 dark:bg-sky-900/30 text-sky-700',
    textClass: 'text-sky-700',
  },
  tool: {
    accent: '#f97316',
    iconBg: 'bg-orange-50 dark:bg-orange-900/30 text-orange-600',
    badgeBg: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700',
    textClass: 'text-orange-700',
  },
  action: {
    accent: '#8b5cf6',
    iconBg: 'bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600',
    badgeBg: 'bg-indigo-100 dark:bg-indigo-900/30 text-indigo-700',
    textClass: 'text-indigo-700',
  },
  condition: {
    accent: '#facc15',
    iconBg: 'bg-yellow-50 dark:bg-yellow-900/30 text-yellow-600',
    badgeBg: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700',
    textClass: 'text-yellow-700',
  },
  branch: {
    accent: '#f472b6',
    iconBg: 'bg-pink-50 dark:bg-pink-900/30 text-pink-600',
    badgeBg: 'bg-pink-100 dark:bg-pink-900/30 text-pink-700',
    textClass: 'text-pink-700',
  },
  loop: {
    accent: '#22d3ee',
    iconBg: 'bg-cyan-50 dark:bg-cyan-900/30 text-cyan-600',
    badgeBg: 'bg-cyan-100 dark:bg-cyan-900/30 text-cyan-700',
    textClass: 'text-cyan-700',
  },
  output: {
    accent: '#a855f7',
    iconBg: 'bg-purple-50 dark:bg-purple-900/30 text-purple-600',
    badgeBg: 'bg-purple-100 dark:bg-purple-900/30 text-purple-700',
    textClass: 'text-purple-700',
  },
  interface: {
    accent: '#14b8a6',
    iconBg: 'bg-teal-50 dark:bg-teal-900/30 text-teal-600',
    badgeBg: 'bg-teal-100 dark:bg-teal-900/30 text-teal-700',
    textClass: 'text-teal-700',
  },
  decision: {
    accent: '#facc15',
    iconBg: 'bg-yellow-50 dark:bg-yellow-900/30 text-yellow-600',
    badgeBg: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700',
    textClass: 'text-yellow-700',
  },
  merge: {
    accent: '#f472b6',
    iconBg: 'bg-pink-50 dark:bg-pink-900/30 text-pink-600',
    badgeBg: 'bg-pink-100 dark:bg-pink-900/30 text-pink-700',
    textClass: 'text-pink-700',
  },
  switch: {
    accent: '#fb923c',
    iconBg: 'bg-orange-50 dark:bg-orange-900/30 text-orange-600',
    badgeBg: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700',
    textClass: 'text-orange-700',
  },
  split: {
    accent: '#2dd4bf',
    iconBg: 'bg-teal-50 dark:bg-teal-900/30 text-teal-600',
    badgeBg: 'bg-teal-100 dark:bg-teal-900/30 text-teal-700',
    textClass: 'text-teal-700',
  },
  fork: {
    accent: '#818cf8',
    iconBg: 'bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600',
    badgeBg: 'bg-indigo-100 dark:bg-indigo-900/30 text-indigo-700',
    textClass: 'text-indigo-700',
  },
  parallel: {
    accent: '#818cf8',
    iconBg: 'bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600',
    badgeBg: 'bg-indigo-100 dark:bg-indigo-900/30 text-indigo-700',
    textClass: 'text-indigo-700',
  },
  approval: {
    accent: '#facc15',
    iconBg: 'bg-yellow-50 dark:bg-yellow-900/30 text-yellow-600',
    badgeBg: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700',
    textClass: 'text-yellow-700',
  },
  option: {
    accent: '#facc15',
    iconBg: 'bg-yellow-50 dark:bg-yellow-900/30 text-yellow-600',
    badgeBg: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700',
    textClass: 'text-yellow-700',
  },
  guardrail: {
    accent: '#3b82f6',
    iconBg: 'bg-sky-50 dark:bg-sky-900/30 text-sky-600',
    badgeBg: 'bg-sky-100 dark:bg-sky-900/30 text-sky-700',
    textClass: 'text-sky-700',
  },
  classify: {
    accent: '#3b82f6',
    iconBg: 'bg-sky-50 dark:bg-sky-900/30 text-sky-600',
    badgeBg: 'bg-sky-100 dark:bg-sky-900/30 text-sky-700',
    textClass: 'text-sky-700',
  },
  browser_agent: {
    accent: '#3b82f6',
    iconBg: 'bg-sky-50 dark:bg-sky-900/30 text-sky-600',
    badgeBg: 'bg-sky-100 dark:bg-sky-900/30 text-sky-700',
    textClass: 'text-sky-700',
  },
  crud: {
    accent: '#8b5cf6',
    iconBg: 'bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600',
    badgeBg: 'bg-indigo-100 dark:bg-indigo-900/30 text-indigo-700',
    textClass: 'text-indigo-700',
  },
  find: {
    accent: '#8b5cf6',
    iconBg: 'bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600',
    badgeBg: 'bg-indigo-100 dark:bg-indigo-900/30 text-indigo-700',
    textClass: 'text-indigo-700',
  },
  data: {
    accent: '#8b5cf6',
    iconBg: 'bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600',
    badgeBg: 'bg-indigo-100 dark:bg-indigo-900/30 text-indigo-700',
    textClass: 'text-indigo-700',
  },
  response: {
    accent: '#10b981',
    iconBg: 'bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600',
    badgeBg: 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700',
    textClass: 'text-emerald-700',
  },
  exit: {
    accent: '#64748b',
    iconBg: 'bg-slate-50 dark:bg-slate-900/30 text-slate-600',
    badgeBg: 'bg-slate-100 dark:bg-slate-900/30 text-slate-700',
    textClass: 'text-slate-700',
  },
  mcp: {
    accent: '#f97316',
    iconBg: 'bg-orange-50 dark:bg-orange-900/30 text-orange-600',
    badgeBg: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700',
    textClass: 'text-orange-700',
  },
  transform: {
    accent: '#6366f1',
    iconBg: 'bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600',
    badgeBg: 'bg-indigo-100 dark:bg-indigo-900/30 text-indigo-700',
    textClass: 'text-indigo-700',
  },
  wait: {
    accent: '#64748b',
    iconBg: 'bg-slate-50 dark:bg-slate-900/30 text-slate-600',
    badgeBg: 'bg-slate-100 dark:bg-slate-900/30 text-slate-700',
    textClass: 'text-slate-700',
  },
  download_file: {
    accent: '#0ea5e9',
    iconBg: 'bg-sky-50 dark:bg-sky-900/30 text-sky-600',
    badgeBg: 'bg-sky-100 dark:bg-sky-900/30 text-sky-700',
    textClass: 'text-sky-700',
  },
  aggregate: {
    accent: '#8b5cf6',
    iconBg: 'bg-violet-50 dark:bg-violet-900/30 text-violet-600',
    badgeBg: 'bg-violet-100 dark:bg-violet-900/30 text-violet-700',
    textClass: 'text-violet-700',
  },
  http_request: {
    accent: '#0ea5e9',
    iconBg: 'bg-sky-50 dark:bg-sky-900/30 text-sky-600',
    badgeBg: 'bg-sky-100 dark:bg-sky-900/30 text-sky-700',
    textClass: 'text-sky-700',
  },
  data_input: {
    accent: '#f59e0b',
    iconBg: 'bg-amber-50 dark:bg-amber-900/30 text-amber-600',
    badgeBg: 'bg-amber-100 dark:bg-amber-900/30 text-amber-700',
    textClass: 'text-amber-700',
  },
  whileGroup: {
    accent: '#22d3ee',
    iconBg: 'bg-cyan-50 dark:bg-cyan-900/30 text-cyan-600',
    badgeBg: 'bg-cyan-100 dark:bg-cyan-900/30 text-cyan-700',
    textClass: 'text-cyan-700',
  },
  filter: {
    accent: '#f59e0b',
    iconBg: 'bg-amber-50 dark:bg-amber-900/30 text-amber-600',
    badgeBg: 'bg-amber-100 dark:bg-amber-900/30 text-amber-700',
    textClass: 'text-amber-700',
  },
  sort: {
    accent: '#8b5cf6',
    iconBg: 'bg-violet-50 dark:bg-violet-900/30 text-violet-600',
    badgeBg: 'bg-violet-100 dark:bg-violet-900/30 text-violet-700',
    textClass: 'text-violet-700',
  },
  limit: {
    accent: '#0ea5e9',
    iconBg: 'bg-sky-50 dark:bg-sky-900/30 text-sky-600',
    badgeBg: 'bg-sky-100 dark:bg-sky-900/30 text-sky-700',
    textClass: 'text-sky-700',
  },
  remove_duplicates: {
    accent: '#14b8a6',
    iconBg: 'bg-teal-50 dark:bg-teal-900/30 text-teal-600',
    badgeBg: 'bg-teal-100 dark:bg-teal-900/30 text-teal-700',
    textClass: 'text-teal-700',
  },
  summarize: {
    accent: '#f97316',
    iconBg: 'bg-orange-50 dark:bg-orange-900/30 text-orange-600',
    badgeBg: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700',
    textClass: 'text-orange-700',
  },
  date_time: {
    accent: '#6366f1',
    iconBg: 'bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600',
    badgeBg: 'bg-indigo-100 dark:bg-indigo-900/30 text-indigo-700',
    textClass: 'text-indigo-700',
  },
  crypto_jwt: {
    accent: '#ec4899',
    iconBg: 'bg-pink-50 dark:bg-pink-900/30 text-pink-600',
    badgeBg: 'bg-pink-100 dark:bg-pink-900/30 text-pink-700',
    textClass: 'text-pink-700',
  },
  xml: {
    accent: '#059669',
    iconBg: 'bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600',
    badgeBg: 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700',
    textClass: 'text-emerald-700',
  },
  compression: {
    accent: '#7c3aed',
    iconBg: 'bg-violet-50 dark:bg-violet-900/30 text-violet-600',
    badgeBg: 'bg-violet-100 dark:bg-violet-900/30 text-violet-700',
    textClass: 'text-violet-700',
  },
  rss: {
    accent: '#ea580c',
    iconBg: 'bg-orange-50 dark:bg-orange-900/30 text-orange-600',
    badgeBg: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700',
    textClass: 'text-orange-700',
  },
  convert_to_file: {
    accent: '#0891b2',
    iconBg: 'bg-cyan-50 dark:bg-cyan-900/30 text-cyan-600',
    badgeBg: 'bg-cyan-100 dark:bg-cyan-900/30 text-cyan-700',
    textClass: 'text-cyan-700',
  },
  extract_from_file: {
    accent: '#0d9488',
    iconBg: 'bg-teal-50 dark:bg-teal-900/30 text-teal-600',
    badgeBg: 'bg-teal-100 dark:bg-teal-900/30 text-teal-700',
    textClass: 'text-teal-700',
  },
  compare_datasets: {
    accent: '#7c3aed',
    iconBg: 'bg-violet-50 dark:bg-violet-900/30 text-violet-600',
    badgeBg: 'bg-violet-100 dark:bg-violet-900/30 text-violet-700',
    textClass: 'text-violet-700',
  },
  sub_workflow: {
    accent: '#2563eb',
    iconBg: 'bg-blue-50 dark:bg-blue-900/30 text-blue-600',
    badgeBg: 'bg-blue-100 dark:bg-blue-900/30 text-blue-700',
    textClass: 'text-blue-700',
  },
  respond_to_webhook: {
    accent: '#059669',
    iconBg: 'bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600',
    badgeBg: 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700',
    textClass: 'text-emerald-700',
  },
  send_email: {
    accent: '#ea580c',
    iconBg: 'bg-orange-50 dark:bg-orange-900/30 text-orange-600',
    badgeBg: 'bg-orange-100 dark:bg-orange-900/30 text-orange-700',
    textClass: 'text-orange-700',
  },
  email_inbox: {
    accent: '#0d9488',
    iconBg: 'bg-teal-50 dark:bg-teal-900/30 text-teal-600',
    badgeBg: 'bg-teal-100 dark:bg-teal-900/30 text-teal-700',
    textClass: 'text-teal-700',
  },
  code: {
    accent: '#6366f1',
    iconBg: 'bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600',
    badgeBg: 'bg-indigo-100 dark:bg-indigo-900/30 text-indigo-700',
    textClass: 'text-indigo-700',
  },
  set: {
    accent: '#0891b2',
    iconBg: 'bg-cyan-50 dark:bg-cyan-900/30 text-cyan-600',
    badgeBg: 'bg-cyan-100 dark:bg-cyan-900/30 text-cyan-700',
    textClass: 'text-cyan-700',
  },
  html_extract: {
    accent: '#db2777',
    iconBg: 'bg-pink-50 dark:bg-pink-900/30 text-pink-600',
    badgeBg: 'bg-pink-100 dark:bg-pink-900/30 text-pink-700',
    textClass: 'text-pink-700',
  },
  task: {
    accent: '#10b981',
    iconBg: 'bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600',
    badgeBg: 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700',
    textClass: 'text-emerald-700',
  },
  stop_on_error: {
    accent: '#64748b',
    iconBg: 'bg-slate-50 dark:bg-slate-900/30 text-slate-600',
    badgeBg: 'bg-slate-100 dark:bg-slate-900/30 text-slate-700',
    textClass: 'text-slate-700',
  },
  ssh: {
    accent: '#64748b',
    iconBg: 'bg-slate-50 dark:bg-slate-900/30 text-slate-600',
    badgeBg: 'bg-slate-100 dark:bg-slate-900/30 text-slate-700',
    textClass: 'text-slate-700',
  },
  sftp: {
    accent: '#0891b2',
    iconBg: 'bg-cyan-50 dark:bg-cyan-900/30 text-cyan-600',
    badgeBg: 'bg-cyan-100 dark:bg-cyan-900/30 text-cyan-700',
    textClass: 'text-cyan-700',
  },
  database: {
    accent: '#2563eb',
    iconBg: 'bg-blue-50 dark:bg-blue-900/30 text-blue-600',
    badgeBg: 'bg-blue-100 dark:bg-blue-900/30 text-blue-700',
    textClass: 'text-blue-700',
  },
};

export function getNodeVisual(kind: BuilderNodeKind): NodeVisuals {
  return NODE_VISUALS[kind] ?? NODE_VISUALS.entry;
}

// ============================================
// NODE_ICON_REGISTRY - Single source of truth for node icons & backgrounds
// Maps baseNodeId → { icon, iconBg }
// ============================================

export interface NodeIconEntry {
  icon: LucideIcon;
  iconBg: string;
}

const TRIGGER_BG = 'bg-orange-100 dark:bg-orange-900/30 text-orange-600';
const FLOW_BG = 'bg-violet-100 dark:bg-violet-900/30 text-violet-600';
const AI_BG = 'bg-blue-100 dark:bg-blue-900/30 text-blue-600';
const CORE_BG = 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-600';
const MCP_BG = 'bg-gray-100 dark:bg-gray-800/50 text-gray-600 dark:text-gray-400';
const PURPLE_BG = 'bg-purple-50 dark:bg-purple-900/30 text-purple-600';

export const NODE_ICON_REGISTRY: Record<string, NodeIconEntry> = {
  // --- Trigger category ---
  'triggers': { icon: Zap, iconBg: TRIGGER_BG },

  // --- Triggers (orange) ---
  'manual-trigger': { icon: MousePointerClick, iconBg: TRIGGER_BG },
  'webhook-trigger': { icon: Webhook, iconBg: TRIGGER_BG },
  'schedule-trigger': { icon: CalendarClock, iconBg: TRIGGER_BG },
  'tables-trigger': { icon: Table, iconBg: TRIGGER_BG },
  'workflows-trigger': { icon: Workflow, iconBg: TRIGGER_BG },
  'chat-trigger': { icon: MessageSquare, iconBg: TRIGGER_BG },
  'form-trigger': { icon: FileText, iconBg: TRIGGER_BG },
  'error-trigger': { icon: AlertTriangle, iconBg: TRIGGER_BG },

  // --- Category groups ---
  'ai-agent': { icon: Bot, iconBg: AI_BG },
  'agent': { icon: Bot, iconBg: AI_BG },
  'browser_agent': { icon: Globe, iconBg: AI_BG },
  'browserAgent': { icon: Globe, iconBg: AI_BG },
  'intent': { icon: Zap, iconBg: TRIGGER_BG },

  // --- Flow nodes (violet) ---
  'if-else': { icon: Milestone, iconBg: FLOW_BG },
  'ifElse': { icon: Milestone, iconBg: FLOW_BG },
  'decision': { icon: Milestone, iconBg: FLOW_BG },
  'condition': { icon: Milestone, iconBg: FLOW_BG },
  'switch': { icon: Milestone, iconBg: FLOW_BG },
  'switchCase': { icon: Milestone, iconBg: FLOW_BG },
  'while': { icon: RotateCw, iconBg: FLOW_BG },
  'while-group': { icon: RotateCw, iconBg: FLOW_BG },
  'whileGroup': { icon: RotateCw, iconBg: FLOW_BG },
  'loop': { icon: RotateCw, iconBg: FLOW_BG },
  'split': { icon: LayersPlus, iconBg: FLOW_BG },
  'merge': { icon: HorizontalMerge, iconBg: FLOW_BG },
  'user-approval': { icon: CheckCircle, iconBg: FLOW_BG },
  'approval': { icon: CheckCircle, iconBg: FLOW_BG },
  'option': { icon: Milestone, iconBg: FLOW_BG },
  'fork': { icon: HorizontalFork, iconBg: FLOW_BG },
  'aggregate': { icon: LayersMinus, iconBg: FLOW_BG },
  'transform': { icon: Shuffle, iconBg: FLOW_BG },
  'subworkflow': { icon: Workflow, iconBg: FLOW_BG },

  // --- AI nodes (blue) ---
  'guardrail': { icon: Shield, iconBg: AI_BG },
  'guardrails': { icon: Shield, iconBg: AI_BG },
  'classify': { icon: Tag, iconBg: AI_BG },

  // --- Core nodes (yellow) ---
  'wait': { icon: CirclePause, iconBg: CORE_BG },
  'exit': { icon: LogOut, iconBg: CORE_BG },
  'action': { icon: Cpu, iconBg: CORE_BG },
  'response': { icon: MessageCircleReply, iconBg: CORE_BG },
  'output': { icon: MessageCircleReply, iconBg: CORE_BG },
  'download_file': { icon: Download, iconBg: CORE_BG },
  'download': { icon: Download, iconBg: CORE_BG },
  'file-download': { icon: Download, iconBg: CORE_BG },
  'http-request': { icon: Globe, iconBg: CORE_BG },
  'http_request': { icon: Globe, iconBg: CORE_BG },
  'http': { icon: Globe, iconBg: CORE_BG },
  'api-request': { icon: Globe, iconBg: CORE_BG },
  'data_input': { icon: FileInput, iconBg: CORE_BG },
  'data-input': { icon: FileInput, iconBg: CORE_BG },
  'interface': { icon: Monitor, iconBg: CORE_BG },
  'note': { icon: FileText, iconBg: CORE_BG },
  'end': { icon: Square, iconBg: CORE_BG },
  'fileSearch': { icon: Search, iconBg: CORE_BG },
  'setState': { icon: Database, iconBg: CORE_BG },
  'filter': { icon: List, iconBg: CORE_BG },
  'sort': { icon: ArrowRightLeft, iconBg: CORE_BG },
  'limit': { icon: Milestone, iconBg: CORE_BG },
  'remove_duplicates': { icon: FoldVertical, iconBg: CORE_BG },
  'remove-duplicates': { icon: FoldVertical, iconBg: CORE_BG },
  'summarize': { icon: BarChart3, iconBg: CORE_BG },
  'date_time': { icon: CalendarClock, iconBg: CORE_BG },
  'date-time': { icon: CalendarClock, iconBg: CORE_BG },
  'crypto_jwt': { icon: KeyRound, iconBg: CORE_BG },
  'crypto-jwt': { icon: KeyRound, iconBg: CORE_BG },
  'xml': { icon: FileCode2, iconBg: CORE_BG },
  'compression': { icon: Archive, iconBg: CORE_BG },
  'rss': { icon: Rss, iconBg: CORE_BG },
  'convert_to_file': { icon: FileOutput, iconBg: CORE_BG },
  'convert-to-file': { icon: FileOutput, iconBg: CORE_BG },
  'extract_from_file': { icon: FileInputIcon, iconBg: CORE_BG },
  'extract-from-file': { icon: FileInputIcon, iconBg: CORE_BG },
  'compare_datasets': { icon: GitCompareArrows, iconBg: CORE_BG },
  'compare-datasets': { icon: GitCompareArrows, iconBg: CORE_BG },
  'set': { icon: Pencil, iconBg: CORE_BG },
  'html_extract': { icon: FileCode2, iconBg: CORE_BG },
  'html-extract': { icon: FileCode2, iconBg: CORE_BG },
  'task': { icon: ClipboardList, iconBg: CORE_BG },
  'sub_workflow': { icon: Workflow, iconBg: FLOW_BG },
  'sub-workflow': { icon: Workflow, iconBg: FLOW_BG },
  'respond_to_webhook': { icon: Send, iconBg: CORE_BG },
  'respond-to-webhook': { icon: Send, iconBg: CORE_BG },
  'send_email': { icon: Mail, iconBg: CORE_BG },
  'send-email': { icon: Mail, iconBg: CORE_BG },
  'email_inbox': { icon: Inbox, iconBg: CORE_BG },
  'email-inbox': { icon: Inbox, iconBg: CORE_BG },
  'code': { icon: Code, iconBg: CORE_BG },
  'script': { icon: Code, iconBg: CORE_BG },
  'run-code': { icon: Code, iconBg: CORE_BG },
  'email': { icon: Mail, iconBg: CORE_BG },
  'webhook-response': { icon: Send, iconBg: CORE_BG },
  'deduplicate': { icon: FoldVertical, iconBg: CORE_BG },
  'dedup': { icon: FoldVertical, iconBg: CORE_BG },
  'compress': { icon: Archive, iconBg: CORE_BG },
  'decompress': { icon: Archive, iconBg: CORE_BG },
  'zip': { icon: Archive, iconBg: CORE_BG },
  'gzip': { icon: Archive, iconBg: CORE_BG },
  'jwt': { icon: KeyRound, iconBg: CORE_BG },
  'datetime': { icon: CalendarClock, iconBg: CORE_BG },

  // --- CRUD / Table nodes (yellow/core) ---
  'data': { icon: Table, iconBg: CORE_BG },
  'table': { icon: Table, iconBg: CORE_BG },
  'create-row': { icon: Plus, iconBg: CORE_BG },
  'crud-create-row': { icon: Plus, iconBg: CORE_BG },
  'create-column': { icon: Plus, iconBg: CORE_BG },
  'crud-create-column': { icon: Plus, iconBg: CORE_BG },
  'read-row': { icon: Search, iconBg: CORE_BG },
  'crud-read-row': { icon: Search, iconBg: CORE_BG },
  'update-row': { icon: Pencil, iconBg: CORE_BG },
  'crud-update-row': { icon: Pencil, iconBg: CORE_BG },
  'delete-row': { icon: Trash2, iconBg: CORE_BG },
  'crud-delete-row': { icon: Trash2, iconBg: CORE_BG },
  'find': { icon: Search, iconBg: CORE_BG },
  'find-row': { icon: Search, iconBg: CORE_BG },
  'crud-find': { icon: Search, iconBg: CORE_BG },
  'crud-find-rows': { icon: Search, iconBg: CORE_BG },
  'crud-find-row': { icon: Search, iconBg: CORE_BG },

  // --- New utility nodes ---
  'stop_on_error': { icon: OctagonX, iconBg: CORE_BG },
  'stop-on-error': { icon: OctagonX, iconBg: CORE_BG },
  'ssh': { icon: Terminal, iconBg: CORE_BG },
  'sftp': { icon: HardDrive, iconBg: CORE_BG },
  'database': { icon: Database, iconBg: CORE_BG },
  'db': { icon: Database, iconBg: CORE_BG },
  'sql': { icon: Database, iconBg: CORE_BG },
};

const DEFAULT_ICON_ENTRY: NodeIconEntry = { icon: Cpu, iconBg: 'bg-gray-100 dark:bg-gray-800 text-gray-500' };

// Registry keys sorted by length descending to match longest prefix first
// (e.g., "tables-trigger" before "table", "create-row" before "create")
const REGISTRY_KEYS_BY_LENGTH = Object.keys(NODE_ICON_REGISTRY).sort((a, b) => b.length - a.length);

/**
 * Extract the base node ID from a potentially suffixed ID (e.g., "wait-2" → "wait").
 * Handles multi-part bases like "webhook-trigger-abc123" → "webhook-trigger".
 * Also strips plan normalized key prefixes like "core:", "mcp:", "trigger:", "agent:".
 */
function extractBaseNodeId(nodeId: string): string {
  // Try direct match first
  for (const key of REGISTRY_KEYS_BY_LENGTH) {
    if (nodeId === key || nodeId.startsWith(`${key}-`)) {
      return key;
    }
  }

  // Strip plan prefixes (core:label, mcp:label, etc.) and retry
  const prefixMatch = nodeId.match(/^(?:core|mcp|trigger|agent|table|interface):(.+)/);
  if (prefixMatch) {
    const stripped = prefixMatch[1];
    for (const key of REGISTRY_KEYS_BY_LENGTH) {
      if (stripped === key || stripped.startsWith(`${key}-`)) {
        return key;
      }
      // Only match underscore separator for numeric suffixes (e.g., wait_2, http_request_3)
      // NOT for arbitrary text (e.g., wait_for_all should NOT match "wait")
      if (stripped.startsWith(`${key}_`)) {
        const afterKey = stripped.slice(key.length + 1);
        if (/^\d+$/.test(afterKey)) {
          return key;
        }
      }
    }
  }

  return nodeId;
}

/**
 * Resolve icon + background for a node.
 * Single source of truth: NODE_ICON_REGISTRY (by baseNodeId) → family fallback → MCP fallback → default.
 */
export function resolveNodeIcon(nodeId: string, nodeKind?: BuilderNodeKind, nodeFamily?: string): NodeIconEntry {
  // 1. Direct registry lookup by base ID
  const baseId = extractBaseNodeId(nodeId);
  const registryEntry = NODE_ICON_REGISTRY[baseId];
  if (registryEntry) return registryEntry;

  // 2. Family-based overrides (for nodes not in registry)
  if (nodeFamily === 'loop') return { icon: RotateCw, iconBg: FLOW_BG };
  if (nodeFamily === 'condition') return { icon: Milestone, iconBg: FLOW_BG };

  // 4. MCP nodes (id starts with mcp- or kind is tool/mcp)
  if (nodeId.startsWith('mcp-') || nodeKind === 'tool' || nodeKind === 'mcp') {
    return { icon: Wrench, iconBg: MCP_BG };
  }

  // 5. Kind-based lookup (covers cases where nodeId doesn't match registry format, e.g. "transform_data")
  if (nodeKind) {
    const kindEntry = NODE_ICON_REGISTRY[nodeKind];
    if (kindEntry) return kindEntry;
  }

  return DEFAULT_ICON_ENTRY;
}
