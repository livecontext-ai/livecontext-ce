import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { nodeRegistry } from '../registry/nodeRegistry';
import { cloudWebUrl } from '@/lib/edition';

/**
 * Builds the pre-filled `/contact?category=bug&message=…` deep-link used by the
 * inspector "Report a problem" (Flag) button.
 *
 * The ticket is pre-filled - like the marketplace publication report - but with
 * the context support needs to triage a workflow node: the node identity, its
 * curated configuration, and a bounded summary of the surrounding plan. The
 * resulting message is kept well under the contact form's 5000-char ceiling so
 * the (URL-encoded) link stays within proxy/header limits.
 */

export interface NodeReportContext {
  node: Node<BuilderNodeData>;
  workflowId?: string | null;
  runId?: string | null;
  isRunMode: boolean;
  allNodes: Node<BuilderNodeData>[];
  edges: Edge[];
}

/** Subset of next-intl's translator signature this util needs. */
export type ReportTranslator = (key: string, values?: Record<string, string | number>) => string;

// auth-service ContactService rejects messages over 5000 chars; we cap well below
// that so the URL-encoded query (~3x the raw length) stays within proxy/header limits.
const MAX_MESSAGE_LENGTH = 3000;
const MAX_CONFIG_CHARS = 1200;
const MAX_PLAN_NODES = 50;
const MAX_PLAN_EDGES = 60;

function truncate(value: string, max: number): string {
  if (value.length <= max) return value;
  return `${value.slice(0, Math.max(0, max - 1)).trimEnd()}…`;
}

/**
 * A node's semantic type family for the ticket. `kind` is the always-present
 * BuilderNodeData field ('reasoning', 'action', 'wait', …). We deliberately do
 * NOT use `data.id` - it is a ReactFlow instance id (e.g. 'switch-1717…'), not a
 * type; the specific instance id is reported separately on the Node ID line.
 */
function nodeKindOf(data: BuilderNodeData | undefined): string {
  return data?.kind || '?';
}

/**
 * Extract a small, predictable view of the node's configuration. We whitelist
 * fields (rather than dumping `node.data`) to avoid leaking runtime noise
 * (status, metrics, loop children) and to keep the payload bounded.
 */
function curateNodeConfig(data: BuilderNodeData | undefined): Record<string, unknown> {
  const cfg: Record<string, unknown> = {};
  if (!data) return cfg;
  const d = data as Record<string, any>;
  if (d.description) cfg.description = d.description;
  if (d.paramExpressions && Object.keys(d.paramExpressions).length) cfg.parameters = d.paramExpressions;
  if (d.prompt) cfg.prompt = d.prompt;
  if (d.model) cfg.model = d.model;
  if (d.temperature != null) cfg.temperature = d.temperature;
  if (d.maxTokens != null) cfg.maxTokens = d.maxTokens;
  if (d.decisionConditions) cfg.decisionConditions = d.decisionConditions;
  if (d.switchCases) cfg.switchCases = d.switchCases;
  if (d.approvalOutputs) cfg.approvalOutputs = d.approvalOutputs;
  if (d.toolData) cfg.tool = { api: d.toolData.apiName, name: d.toolData.toolName ?? d.toolData.toolSlug };
  if (d.apiData) cfg.api = { name: d.apiData.apiName, baseUrl: d.apiData.baseUrl };
  if (d.dataSourceData) cfg.dataSource = { id: d.dataSourceData.dataSourceId, table: d.dataSourceData.tableName };
  if (d.workflowData) cfg.workflow = { id: d.workflowData.workflowId };
  return cfg;
}

/**
 * Compact, human-readable summary of the plan structure: node list + edge list,
 * each capped. Notes are excluded - they are annotations, not part of execution.
 */
function buildPlanSummary(
  allNodes: Node<BuilderNodeData>[],
  edges: Edge[],
  t: ReportTranslator,
): string {
  const nodes = (allNodes || []).filter((n) => n && !nodeRegistry.isNoteNode(n));
  const safeEdges = edges || [];
  const lines: string[] = [];

  lines.push(t('msgPlanHeading', { nodes: nodes.length, edges: safeEdges.length }));
  nodes.slice(0, MAX_PLAN_NODES).forEach((n) => {
    const label = n.data?.label || n.id;
    lines.push(`- ${n.id} "${label}" [${nodeKindOf(n.data)}]`);
  });
  if (nodes.length > MAX_PLAN_NODES) {
    lines.push(t('msgMore', { count: nodes.length - MAX_PLAN_NODES }));
  }

  if (safeEdges.length) {
    lines.push(t('msgEdgesHeading'));
    safeEdges.slice(0, MAX_PLAN_EDGES).forEach((e) => {
      const port = (e as any).sourceHandle ? ` (${(e as any).sourceHandle})` : '';
      lines.push(`- ${e.source} -> ${e.target}${port}`);
    });
    if (safeEdges.length > MAX_PLAN_EDGES) {
      lines.push(t('msgMore', { count: safeEdges.length - MAX_PLAN_EDGES }));
    }
  }

  return lines.join('\n');
}

/** Build the pre-filled contact-form message body for a node report. */
export function buildNodeReportMessage(ctx: NodeReportContext, t: ReportTranslator): string {
  const { node, workflowId, runId, isRunMode, allNodes, edges } = ctx;
  const data = node?.data;
  const none = t('none');

  const configJson = truncate(JSON.stringify(curateNodeConfig(data), null, 2), MAX_CONFIG_CHARS);
  const planSummary = buildPlanSummary(allNodes, edges, t);

  const parts = [
    t('msgHeading'),
    '',
    t('msgWorkflowId', { id: workflowId || none }),
    t('msgRunId', { id: runId || none }),
    t('msgMode', { mode: isRunMode ? t('modeRun') : t('modeEdit') }),
    '',
    t('msgNodeHeading'),
    t('msgNodeLabel', { label: data?.label || none }),
    t('msgNodeType', { type: data?.kind || none }),
    t('msgNodeId', { id: node.id }),
    '',
    t('msgConfigHeading'),
    configJson,
    '',
    planSummary,
    '',
    t('msgReasonHeading'),
    t('msgReasonPlaceholder'),
  ];

  return truncate(parts.join('\n'), MAX_MESSAGE_LENGTH);
}

/**
 * Build the `/contact?category=bug&message=…` deep-link for a node report.
 *
 * In CE the link is rewritten onto the cloud origin (cloudWebUrl): the support
 * ticket must reach the cloud operator's contact form, not the local
 * self-hosted install (localhost), whose form lands nowhere useful. No-op on
 * cloud, where the path is already same-origin.
 */
export function buildNodeReportHref(ctx: NodeReportContext, t: ReportTranslator): string {
  return cloudWebUrl(`/contact?category=bug&message=${encodeURIComponent(buildNodeReportMessage(ctx, t))}`);
}
