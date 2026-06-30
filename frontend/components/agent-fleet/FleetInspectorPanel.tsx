'use client';

import React, { useMemo, useState, useCallback, useEffect } from 'react';
import Image from 'next/image';
import { GripVertical, Zap, Workflow, Monitor, Table, Activity, CheckCircle2, AlertCircle, XCircle, X, Minus, FolderOpen, Folder, FileText, ChevronRight, ChevronLeft, Maximize2, Globe, RefreshCw, Clock, Cpu, Coins, Wrench, MoreVertical, Trash2, Pencil } from 'lucide-react';
import { useTranslations } from 'next-intl';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import type { Agent, AgentSkill, SkillFolder, Skill } from '@/lib/api/orchestrator/types';
import { AvatarDisplay } from '@/components/agents/AvatarPicker';
import { NodeIcon, getIconSlug } from '@/app/workflows/builder/components/nodes/shared';
import { NodeStatusBadge } from '@/app/workflows/builder/components/NodeStatusBadge';
import { deriveStatusFromCounts } from '@/app/workflows/builder/utils/statusCounts';
import type { FleetAggregatedItem } from './fleetLayout';
import { disconnectFleetResource } from '@/lib/agents/agentResourceMutations';
import { ConfirmDeleteModal } from '@/components/chat/ConfirmDeleteModal';
import { FleetInspectorActionButtons } from './FleetInspectorActionButtons';
import { openFleetSidePanelTab, type FleetSidePanelAction } from './fleetSidePanelActions';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { Label } from '@/components/ui/label';
import { formatCost, isCeMode } from '@/lib/format-cost';
import { Input } from '@/components/ui/input';
import { Select, SelectTrigger, SelectContent, SelectItem, SelectValue } from '@/components/ui/select';
import { useMcpToolDetails } from '@/app/workflows/builder/hooks/useMcpData';
import { OptionalSection } from '@/app/workflows/builder/components/inspector/OptionalSection';
import { ExpressionField } from '@/app/workflows/builder/components/inspector/ExpressionField';
import { CredentialSection } from '@/app/workflows/builder/components/inspector/CredentialSection';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import type { AgentExecutionRecord } from '@/lib/api/orchestrator/agent-metrics.types';
import { AgentExecutionInspectorDetail } from './AgentExecutionInspectorDetail';
import { StatusBadge } from '@/components/ui/StatusBadge';
import { formatDuration, formatRelativeDateI18n } from '@/lib/utils/dateFormatters';
import { cn } from '@/lib/utils';
import { useAgentActivity } from './hooks/useAgentActivityStream';
import { getProviderIconSlug, getProviderDisplayName } from '@/lib/ai-providers/providerIcons';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import { AgentFamilyAccessSection } from './AgentFamilyAccessSection';

// ─── Fleet resource icon fallbacks (same map as FlowNode.tsx) ───
const FLEET_RESOURCE_ICONS: Record<string, { icon: React.ComponentType<{ className?: string }>; bg: string }> = {
  skill: { icon: Zap, bg: 'bg-amber-50 dark:bg-amber-900/30 text-amber-500 dark:text-amber-400' },
  folder: { icon: FolderOpen, bg: 'bg-amber-50 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400' },
  workflow: { icon: Workflow, bg: 'bg-purple-50 dark:bg-purple-900/30 text-purple-500 dark:text-purple-400' },
  interface: { icon: Monitor, bg: 'bg-teal-50 dark:bg-teal-900/30 text-teal-500 dark:text-teal-400' },
  table: { icon: Table, bg: 'bg-orange-50 dark:bg-orange-900/30 text-orange-500 dark:text-orange-400' },
  file: { icon: FileText, bg: 'bg-sky-50 dark:bg-sky-900/30 text-sky-500 dark:text-sky-400' },
  web_search: { icon: Globe, bg: 'bg-blue-50 dark:bg-blue-900/30 text-blue-500 dark:text-blue-400' },
};

interface FleetInspectorPanelProps {
  node: Node<BuilderNodeData>;
  allNodes: Node<BuilderNodeData>[];
  agents: Agent[];
  skillsByAgent: Map<string, AgentSkill[]>;
  skillFolders?: SkillFolder[];
  resourcesById: Map<string, any>;
  onClose: () => void;
  onDragHandleMouseDown?: (e: React.MouseEvent) => void;
  isMinimized?: boolean;
  onMinimizedChange?: (minimized: boolean) => void;
  /** Available width from the panel position to the container edge */
  availableWidth?: number;
  /** Refresh fleet data after mutations (e.g. resource deletion) */
  onRefresh?: () => void;
}

/**
 * Parse node ID to extract the node category and relevant IDs.
 * Patterns: agent-{id}, res-{agentId}-{type}-{resId}, provider-{agentId}-{apiSlug}
 */
export function parseNodeId(nodeId: string): {
  category: 'agent' | 'resource' | 'provider' | 'folder' | 'categoryGroup' | 'aggregate';
  agentId?: string;
  resourceType?: string;
  resourceId?: string;
  apiSlug?: string;
  folderId?: string;
  groupType?: string;
} {
  if (nodeId.startsWith('agent-')) {
    return { category: 'agent', agentId: nodeId.slice('agent-'.length) };
  }
  if (nodeId.startsWith('agg-')) {
    // The "Resources (N)" consolidation node. consolidateFleetResources keys agents by
    // their NODE id (`agent-{uuid}`), so the real canvas id is `agg-agent-{uuid}` -
    // strip BOTH prefixes to recover the raw agent uuid. Without this branch the
    // aggregator fell to the bottom fallback ({category:'resource'}, NO agentId) and
    // every consumer that resolves the owning agent silently no-op'd.
    const rest = nodeId.slice('agg-'.length);
    const agentId = rest.startsWith('agent-') ? rest.slice('agent-'.length) : rest;
    return { category: 'aggregate', agentId };
  }
  if (nodeId.startsWith('category-')) {
    // category-{agentId}-{resourceType} - agentId is a UUID (36 chars with dashes)
    const rest = nodeId.slice('category-'.length);
    const agentId = rest.slice(0, 36);
    const groupType = rest.slice(37); // skip the dash after UUID
    return { category: 'categoryGroup', agentId, groupType };
  }
  if (nodeId.startsWith('folder-')) {
    // folder-{agentId}-{folderId} - agentId is a UUID (36 chars with dashes)
    const rest = nodeId.slice('folder-'.length);
    const agentId = rest.slice(0, 36);
    const folderId = rest.slice(37); // skip the dash after UUID
    return { category: 'folder', agentId, folderId };
  }
  if (nodeId.startsWith('provider-')) {
    // provider-{agentId}-{apiSlug} - agentId is a UUID (36 chars with dashes)
    const rest = nodeId.slice('provider-'.length);
    const agentId = rest.slice(0, 36);
    const apiSlug = rest.slice(37); // skip the dash after UUID
    return { category: 'provider', agentId, apiSlug };
  }
  if (nodeId.startsWith('res-')) {
    // res-{agentId}-{type}-{resId}
    const rest = nodeId.slice('res-'.length);
    const parts = rest.split('-');
    // UUID = 5 segments (8-4-4-4-12), so first 5 parts are the agentId
    if (parts.length >= 7) {
      const agentId = parts.slice(0, 5).join('-');
      const resourceType = parts[5];
      const resourceId = parts.slice(6).join('-');
      return { category: 'resource', agentId, resourceType, resourceId };
    }
  }
  return { category: 'resource' };
}

// ─── Service icon helper ───
function ServiceIcon({ iconSlug, size = 16, className }: { iconSlug: string; size?: number; className?: string }) {
  const [error, setError] = useState(false);
  if (error) return null;
  return (
    <Image
      src={`/icons/services/${iconSlug}.svg`}
      alt={iconSlug}
      width={size}
      height={size}
      className={className}
      onError={() => setError(true)}
    />
  );
}

// ─── Resource item with three-dot delete menu ───
// Disconnect routes through the shared agentResourceMutations helper (the single
// code path also used by the fleet canvas), behind a confirmation modal that spells
// out the update. Skills go to skillService; everything else to toolsConfig.
function buildRemoveDescription(
  t: ReturnType<typeof useTranslations>,
  type: string,
  name: string,
  agentName: string,
): string {
  if (type === 'web_search') return t('confirmRemoveWebSearchDesc', { agent: agentName });
  if (type === 'tool') return t('confirmRemoveToolDesc', { name, agent: agentName });
  if (type === 'agent' || type === 'sub_agent') return t('confirmRemoveSubAgentDesc', { name, agent: agentName });
  return t('confirmRemoveResourceDesc', { name, agent: agentName });
}

function ResourceItem({
  item,
  type,
  agentId,
  agentName,
  onRefresh,
}: {
  item: { label: string; iconSlug?: string; resourceId: string };
  type: string;
  agentId: string;
  agentName: string;
  onRefresh?: () => void;
}) {
  const t = useTranslations('fleetInspector');
  const sidePanel = useSidePanelSafe();
  const [menuOpen, setMenuOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);

  // The synthetic "All tools" chip (mode:'all') is not a removable single tool -
  // deleting it from toolsConfig.tools[] can't persist (mode stays 'all'). Same
  // rule (and same literal id) as the canvas, which suppresses its buttons too.
  const isAllAccessChip = type === 'tool' && item.resourceId === 'all-tools';
  // Workflow/table/interface open in the side panel - the same edit affordance the
  // canvas pencil offers, so editing works from the inspector even when the agent's
  // resource nodes were consolidated away.
  const openableType = (type === 'workflow' || type === 'table' || type === 'interface') ? type : null;

  const handleConfirm = useCallback(async () => {
    setDeleting(true);
    try {
      await disconnectFleetResource(agentId, type, item.resourceId);
      setConfirmOpen(false);
      onRefresh?.();
    } catch (err) {
      console.error('Failed to remove resource:', err);
    } finally {
      setDeleting(false);
    }
  }, [agentId, type, item.resourceId, onRefresh]);

  const handleOpen = useCallback(() => {
    if (!sidePanel || !openableType) return;
    openFleetSidePanelTab(sidePanel, { type: openableType, resourceId: item.resourceId, label: item.label });
  }, [sidePanel, openableType, item.resourceId, item.label]);

  return (
    <div className="group/res flex items-center gap-2 text-sm text-slate-700 dark:text-slate-300 relative">
      {item.iconSlug && (
        <ServiceIcon iconSlug={item.iconSlug} size={14} className="h-3.5 w-3.5 flex-shrink-0" />
      )}
      <span className="truncate flex-1">{item.label}</span>
      {sidePanel && openableType && (
        <button
          onClick={(e) => { e.stopPropagation(); handleOpen(); }}
          className="opacity-0 group-hover/res:opacity-100 p-0.5 rounded hover:bg-slate-100 dark:hover:bg-slate-700 transition-opacity"
          title={t('edit')}
        >
          <Pencil className="h-3.5 w-3.5 text-slate-400" />
        </button>
      )}
      {!isAllAccessChip && (
      <div className="relative">
        <button
          onClick={(e) => { e.stopPropagation(); setMenuOpen(!menuOpen); }}
          className="opacity-0 group-hover/res:opacity-100 p-0.5 rounded hover:bg-slate-100 dark:hover:bg-slate-700 transition-opacity"
        >
          <MoreVertical className="h-3.5 w-3.5 text-slate-400" />
        </button>
        {menuOpen && (
          <>
            <div className="fixed inset-0 z-40" onClick={() => setMenuOpen(false)} />
            <div className="absolute right-0 top-6 z-50 bg-white dark:bg-gray-800 border border-slate-200 dark:border-slate-600 rounded-lg shadow-xl py-1 min-w-[120px]">
              <button
                onClick={() => { setMenuOpen(false); setConfirmOpen(true); }}
                className="w-full flex items-center gap-2 px-3 py-1.5 text-xs text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
              >
                <Trash2 className="h-3 w-3" />
                {t('removeAction')}
              </button>
            </div>
          </>
        )}
      </div>
      )}
      {confirmOpen && (
        <ConfirmDeleteModal
          isOpen
          title={t('confirmRemoveTitle')}
          message={buildRemoveDescription(t, type, item.label, agentName)}
          confirmLabel={t('removeAction')}
          isLoading={deleting}
          onConfirm={handleConfirm}
          onCancel={() => { if (!deleting) setConfirmOpen(false); }}
        />
      )}
    </div>
  );
}

// ─── Agent detail section (uses same component patterns as AgentConfigurationPanel) ───
export function AgentInspectorContent({
  agent,
  skills,
  nodes,
  t,
  onRefresh,
}: {
  agent: Agent;
  skills: AgentSkill[];
  nodes: Node<BuilderNodeData>[];
  t: ReturnType<typeof useTranslations>;
  onRefresh?: () => void;
}) {
  // Collect actual resource nodes grouped by type. Past the aggregation threshold
  // (consolidateFleetResources) the agent's `res-*` nodes are REMOVED from the canvas
  // and replaced by ONE `agg-{agentId}` node - so scanning res-* nodes alone left this
  // inspector section EMPTY (no mapping, nothing to edit/delete) for any agent with 6+
  // resources, e.g. an agent freshly created by another agent. Merge the aggregator's
  // `fleetAggregatedItems` (the folded leaves, captured pre-removal) back in.
  const resourcesByType = useMemo(() => {
    const groups: Record<string, { label: string; iconSlug?: string; resourceId: string }[]> = {};
    const push = (resType: string | undefined, resourceId: string | undefined, label: string, iconSlug?: string) => {
      if (!resType || resType === 'model' || !resourceId) return;
      if (!groups[resType]) groups[resType] = [];
      if (groups[resType].some(existing => existing.resourceId === resourceId)) return;
      groups[resType].push({ label, iconSlug, resourceId });
    };
    nodes.forEach(n => {
      if (n.id.startsWith(`res-${agent.id}-`)) {
        const parsed = parseNodeId(n.id);
        const d = n.data as any;
        push(d.fleetResourceType as string | undefined, parsed.resourceId, d.label || n.id, d.toolData?.iconSlug || d.apiData?.iconSlug);
      }
    });
    // Resolve via parseNodeId - the real aggregator id is `agg-agent-{uuid}`, never
    // string-build it here (a hand-built `agg-${agent.id}` silently matches nothing).
    const aggData = nodes.find(n => n.id.startsWith('agg-') && parseNodeId(n.id).agentId === agent.id)?.data as any;
    const aggItems: FleetAggregatedItem[] = Array.isArray(aggData?.fleetAggregatedItems) ? aggData.fleetAggregatedItems : [];
    aggItems.forEach(item => {
      const parsed = parseNodeId(item.nodeId);
      push(item.type, parsed.resourceId, item.label, item.iconSlug);
    });
    return groups;
  }, [nodes, agent.id]);

  const providerIconSlug = getProviderIconSlug(agent.modelProvider);
  const providerDisplayName = getProviderDisplayName(agent.modelProvider);

  // Compute metrics from agent data
  const fleetMetrics = useMemo(() => {
    const total = agent.totalExecutions || 0;
    if (total === 0) return null;
    const successCount = agent.successCount || 0;
    const failureCount = agent.failureCount || 0;
    const successRate = (successCount + failureCount) > 0
      ? Math.round((successCount / (successCount + failureCount)) * 100)
      : null;
    return {
      totalExecutions: total,
      successRate,
      totalTokens: agent.totalTokensUsed || 0,
      totalToolCalls: agent.totalToolCalls || 0,
      avgDurationMs: (total && agent.totalDurationMs) ? Math.round(agent.totalDurationMs / total) : null,
      lastExecutionAt: agent.lastExecutionAt || null,
    };
  }, [agent]);

  const formatTokens = (tk: number) => tk >= 1_000_000 ? `${(tk / 1_000_000).toFixed(1)}M` : tk >= 1_000 ? `${(tk / 1_000).toFixed(1)}K` : String(tk);
  const formatDur = (ms: number) => ms >= 60_000 ? `${(ms / 60_000).toFixed(1)}m` : ms >= 1_000 ? `${(ms / 1_000).toFixed(1)}s` : `${ms}ms`;
  // Real-time activity from WebSocket (shared hook with optimized equality)
  const activity = useAgentActivity(agent.id);

  return (
    <div className="space-y-5 pt-2">
      {/* Live activity indicator */}
      {activity?.isRunning && (
        <div className="flex items-center gap-2 px-3 py-2.5 rounded-lg bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800">
          <div className="h-2 w-2 rounded-full bg-blue-500 animate-pulse flex-shrink-0" />
          <div className="flex-1 min-w-0">
            <p className="text-sm font-medium text-blue-700 dark:text-blue-300">
              {activity.currentToolName
                ? `Calling ${activity.currentToolName}...`
                : 'Running...'}
            </p>
            {activity.toolCallCount > 0 && (
              <p className="text-xs text-blue-500 dark:text-blue-400">
                {activity.toolCallCount} tool call{activity.toolCallCount !== 1 ? 's' : ''} completed
              </p>
            )}
          </div>
        </div>
      )}

      {/* Metrics summary */}
      {fleetMetrics && (
        <div className="space-y-2">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('metrics')}
          </Label>
          <div className="grid grid-cols-2 gap-2">
            <div className="flex items-center gap-2 rounded-lg bg-slate-50 dark:bg-slate-800/60 px-3 py-2">
              <Activity className="h-3.5 w-3.5 text-slate-400" />
              <div>
                <p className="text-sm font-semibold text-slate-700 dark:text-slate-200">{fleetMetrics.totalExecutions}</p>
                <p className="text-xs text-slate-400">{t('totalRuns')}</p>
              </div>
            </div>
            {fleetMetrics.successRate !== null && (
              <div className="flex items-center gap-2 rounded-lg bg-slate-50 dark:bg-slate-800/60 px-3 py-2">
                <CheckCircle2 className={`h-3.5 w-3.5 ${fleetMetrics.successRate >= 95 ? 'text-emerald-500' : fleetMetrics.successRate >= 80 ? 'text-amber-500' : 'text-red-500'}`} />
                <div>
                  <p className={`text-sm font-semibold ${fleetMetrics.successRate >= 95 ? 'text-emerald-600 dark:text-emerald-400' : fleetMetrics.successRate >= 80 ? 'text-amber-600 dark:text-amber-400' : 'text-red-600 dark:text-red-400'}`}>
                    {fleetMetrics.successRate}%
                  </p>
                  <p className="text-xs text-slate-400">{t('successRate')}</p>
                </div>
              </div>
            )}
            {fleetMetrics.totalTokens > 0 && (
              <div className="flex items-center gap-2 rounded-lg bg-slate-50 dark:bg-slate-800/60 px-3 py-2">
                <Cpu className="h-3.5 w-3.5 text-slate-400" />
                <div>
                  <p className="text-sm font-semibold text-slate-700 dark:text-slate-200">{formatTokens(fleetMetrics.totalTokens)}</p>
                  <p className="text-xs text-slate-400">{t('tokens')}</p>
                </div>
              </div>
            )}
            {fleetMetrics.avgDurationMs !== null && (
              <div className="flex items-center gap-2 rounded-lg bg-slate-50 dark:bg-slate-800/60 px-3 py-2">
                <Clock className="h-3.5 w-3.5 text-slate-400" />
                <div>
                  <p className="text-sm font-semibold text-slate-700 dark:text-slate-200">{formatDur(fleetMetrics.avgDurationMs)}</p>
                  <p className="text-xs text-slate-400">{t('avgDuration')}</p>
                </div>
              </div>
            )}
            {(agent.creditBudget != null || (agent.creditsConsumed != null && agent.creditsConsumed > 0)) && (() => {
              // Percentage gauge - the raw 4-decimal figures surface in the hover title so the
              // card stays compact while still carrying precise NUMERIC(19,4) data on demand.
              const consumed = agent.creditsConsumed ?? 0;
              const reserved = agent.creditsReserved ?? 0;
              const total = agent.creditBudget;
              const hasBudget = total != null;
              const pct = hasBudget && total > 0 ? Math.min(100, Math.round((consumed / total) * 100)) : 0;
              const reservedPct = hasBudget && total > 0 ? Math.min(100 - pct, Math.round((reserved / total) * 100)) : 0;
              const over = hasBudget && (consumed + reserved) >= total;
              const title = hasBudget
                ? `${t('creditsUsed')}: ${formatCost(consumed, 4)} · ${t('creditsReservedLabel')}: ${formatCost(reserved, 4)} / ${formatCost(total, 4)}`
                : `${t('creditsUsed')}: ${formatCost(consumed, 4)} · ${t('noLimit')}`;
              return (
                <div className="flex items-center gap-2 rounded-lg bg-slate-50 dark:bg-slate-800/60 px-3 py-2" title={title}>
                  <Coins className="h-3.5 w-3.5 text-amber-500" />
                  <div className="min-w-0">
                    {hasBudget ? (
                      <>
                        <div className="flex items-center gap-2">
                          <div className="w-16 h-1.5 flex rounded-full bg-slate-200 dark:bg-slate-700 overflow-hidden">
                            <div
                              className={cn('h-full', over ? 'bg-red-500' : 'bg-emerald-500')}
                              style={{ width: `${pct}%` }}
                            />
                            {reservedPct > 0 && (
                              <div className="h-full bg-amber-500" style={{ width: `${reservedPct}%` }} />
                            )}
                          </div>
                          <p className={cn(
                            'text-sm font-semibold tabular-nums',
                            over ? 'text-red-500' : 'text-slate-700 dark:text-slate-200'
                          )}>
                            {pct}%
                          </p>
                        </div>
                        <p className="text-xs text-slate-400">{isCeMode ? t('costLabel') : t('credits')}</p>
                      </>
                    ) : (
                      <>
                        <p className="text-sm font-semibold tabular-nums text-slate-700 dark:text-slate-200">
                          {formatCost(consumed, 4)}
                        </p>
                        <p className="text-xs text-slate-400">{isCeMode ? t('costLabel') : t('credits')}</p>
                      </>
                    )}
                  </div>
                </div>
              );
            })()}
          </div>
          {fleetMetrics.lastExecutionAt && (
            <p className="text-xs text-slate-400 dark:text-slate-500">
              {t('lastRun')}: {formatRelativeDateI18n(fleetMetrics.lastExecutionAt, t)}
            </p>
          )}
        </div>
      )}

      {/* Description */}
      {agent.description && (
        <div className="space-y-2">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('description')}
          </Label>
          <p className="text-sm text-slate-600 dark:text-slate-300 leading-relaxed">{agent.description}</p>
        </div>
      )}

      {/* System Prompt */}
      {agent.systemPrompt && (
        <div className="space-y-2">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('systemPrompt')}
          </Label>
          <div className="text-sm text-slate-600 dark:text-slate-300 leading-relaxed whitespace-pre-wrap max-h-40 overflow-y-auto rounded-md border border-slate-200 dark:border-slate-700 p-2 bg-slate-50 dark:bg-slate-900/50">
            {agent.systemPrompt}
          </div>
        </div>
      )}

      {/* Provider - disabled Select (same as AgentConfigurationPanel) */}
      {agent.modelProvider && (
        <div className="space-y-2">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('provider')}
          </Label>
          <Select value={agent.modelProvider} disabled>
            <SelectTrigger className="w-full">
              <div className="flex items-center gap-2">
                {providerIconSlug && (
                  <ServiceIcon iconSlug={providerIconSlug} size={16} className="h-4 w-4 flex-shrink-0" />
                )}
                <span>{providerDisplayName}</span>
              </div>
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={agent.modelProvider}>{providerDisplayName}</SelectItem>
            </SelectContent>
          </Select>
        </div>
      )}

      {/* Model - disabled Select */}
      {agent.modelName && (
        <div className="space-y-2">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('model')}
          </Label>
          <Select value={agent.modelName} disabled>
            <SelectTrigger className="w-full">
              <SelectValue>{agent.modelName}</SelectValue>
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={agent.modelName}>{agent.modelName}</SelectItem>
            </SelectContent>
          </Select>
        </div>
      )}

      {/* Temperature - disabled Input */}
      {agent.temperature != null && (
        <div className="space-y-2">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('temperature')}
          </Label>
          <Input
            type="number"
            value={agent.temperature}
            disabled
            className="w-full"
          />
        </div>
      )}

      {/* Max Tokens - disabled Input */}
      {agent.maxTokens != null && (
        <div className="space-y-2">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('maxTokens')}
          </Label>
          <Input
            type="number"
            value={agent.maxTokens}
            disabled
            className="w-full"
          />
        </div>
      )}

      {/* Max Iterations - disabled Input */}
      {agent.maxIterations != null && (
        <div className="space-y-2">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('maxIterations')}
          </Label>
          <Input
            type="number"
            value={agent.maxIterations}
            disabled
            className="w-full"
          />
        </div>
      )}

      {/* Execution Timeout - disabled Input */}
      {agent.executionTimeout != null && (
        <div className="space-y-2">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400" title={t('executionTimeoutHelp')}>
            {t('executionTimeout')}
          </Label>
          <Input
            type="number"
            value={agent.executionTimeout}
            disabled
            className="w-full"
          />
        </div>
      )}

      {/* Inactivity Timeout - disabled Input */}
      {agent.inactivityTimeout != null && (
        <div className="space-y-2">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400" title={t('inactivityTimeoutHelp')}>
            {t('inactivityTimeout')}
          </Label>
          <Input
            type="number"
            value={agent.inactivityTimeout}
            disabled
            className="w-full"
          />
        </div>
      )}

      {/* Credit Budget - disabled Input */}
      {agent.creditBudget != null && (
        <div className="space-y-2">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('creditBudget')}
          </Label>
          <Input type="number" value={agent.creditBudget} disabled className="w-full" />
        </div>
      )}

      {/* Budget Reset Mode - disabled Input */}
      {agent.budgetResetMode && (
        <div className="space-y-2">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('budgetResetMode')}
          </Label>
          <Input value={agent.budgetResetMode} disabled className="w-full" />
        </div>
      )}

      {/* Tools Access Mode - disabled Input (catalogue MCP `mode`, NOT a per-family axis) */}
      {(agent.toolsConfig?.mode || !agent.toolsConfig) && (
        <div className="space-y-2">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('toolsAccessMode')}
          </Label>
          <Input value={agent.toolsConfig?.mode || 'all'} disabled className="w-full" />
        </div>
      )}

      {/* Per-family access grant + read/write (the two independent toolsConfig axes) */}
      <AgentFamilyAccessSection agent={agent} t={t} />

      {/* Skills */}
      {skills.length > 0 && (
        <div className="space-y-2">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('skills')} ({skills.length})
          </Label>
          <div className="space-y-1">
            {skills.map(as => (
              <div key={as.id} className="text-sm text-slate-700 dark:text-slate-300 truncate">
                {as.skill?.name || as.skillId.slice(0, 8)}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Resources - list grouped by type with delete action */}
      {Object.keys(resourcesByType).length > 0 && (
        <div className="space-y-3">
          <Label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('resources')}</Label>
          {Object.entries(resourcesByType).map(([type, items]) => (
            <div key={type} className="space-y-1">
              <span className="text-xs font-medium text-slate-400 dark:text-slate-500 uppercase tracking-wide">
                {type}{items.length > 1 ? 's' : ''} ({items.length})
              </span>
              <div className="space-y-1">
                {items.map((item, idx) => (
                  <ResourceItem
                    key={idx}
                    item={item}
                    type={type}
                    agentId={agent.id}
                    agentName={agent.name}
                    onRefresh={onRefresh}
                  />
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

    </div>
  );
}

// ─── Model detail section - shows provider + model like AgentInspectorContent ───
function ModelInspectorContent({
  agentId,
  agents,
  t,
}: {
  agentId?: string;
  agents: Agent[];
  t: ReturnType<typeof useTranslations>;
}) {
  const agent = agentId ? agents.find(a => a.id === agentId) : undefined;
  const providerIconSlug = getProviderIconSlug(agent?.modelProvider);
  const providerDisplayName = getProviderDisplayName(agent?.modelProvider);

  return (
    <div className="space-y-3 pt-2">
      {/* Provider */}
      {agent?.modelProvider && (
        <div className="rounded-lg bg-theme-secondary/50 px-3 py-2.5">
          <span className="text-xs text-theme-muted leading-tight">{t('provider')}</span>
          <div className="flex items-center gap-2 mt-1">
            {providerIconSlug && (
              <ServiceIcon iconSlug={providerIconSlug} size={16} className="h-4 w-4 flex-shrink-0" />
            )}
            <span className="text-sm font-medium text-theme-primary">{providerDisplayName}</span>
          </div>
        </div>
      )}

      {/* Model */}
      {agent?.modelName && (
        <div className="rounded-lg bg-theme-secondary/50 px-3 py-2.5">
          <span className="text-xs text-theme-muted leading-tight">{t('model')}</span>
          <div className="flex items-center gap-2 mt-1">
            <Cpu className="h-3.5 w-3.5 text-theme-muted flex-shrink-0" />
            <span className="text-sm font-medium text-theme-primary">{agent.modelName}</span>
          </div>
        </div>
      )}

      {!agent?.modelProvider && !agent?.modelName && (
        <p className="text-sm text-theme-muted italic py-4 text-center">{t('noDetails')}</p>
      )}
    </div>
  );
}

// ─── Simple read-only field (label + value as plain text) ───
function InfoRow({ label, value, icon, mono }: { label: string; value: string; icon?: React.ReactNode; mono?: boolean }) {
  return (
    <div className="rounded-lg bg-theme-secondary/50 px-3 py-2.5">
      <span className="text-xs text-theme-muted leading-tight">{label}</span>
      <div className={`flex items-center gap-2 text-sm font-medium text-theme-primary mt-0.5 ${mono ? 'font-mono text-xs' : ''}`}>
        {icon}
        <span>{value}</span>
      </div>
    </div>
  );
}

// ─── Tool detail section - same display as workflow InspectorPanel (uses ExpressionField in read-only mode) ───
function ToolInspectorContent({
  node,
  t,
}: {
  node: Node<BuilderNodeData>;
  t: ReturnType<typeof useTranslations>;
}) {
  const data = node.data as any;
  const toolData = data.toolData as Record<string, any> | undefined;
  const toolSlug = toolData?.toolSlug || null;
  const [showOptional, setShowOptional] = useState(false);

  const { data: toolDetails, isLoading } = useMcpToolDetails(toolSlug);

  const parameters = (toolDetails?.parameters || []) as any[];
  const credentials = (toolDetails?.credentials || []) as any[];

  // Filter out params that share a name with a credential (same as McpToolSelector)
  const credentialNames = useMemo(() => {
    return new Set(credentials.map((c: any) => (c.credentialName || c.name || '').toLowerCase()));
  }, [credentials]);

  const filteredParams = useMemo(() => {
    return parameters.filter((p: any) => {
      const name = (p.name || p.id || '').toLowerCase();
      return !credentialNames.has(name);
    });
  }, [parameters, credentialNames]);

  const requiredParams = filteredParams.filter((p: any) => p.isRequired === true || p.required === true);
  const optionalParams = filteredParams.filter((p: any) => p.isRequired !== true && p.required !== true);

  if (isLoading) {
    return (
      <div className="space-y-4 pt-2">
        <div className="animate-pulse space-y-3">
          <div className="h-4 bg-theme-secondary rounded w-3/4" />
          <div className="h-4 bg-theme-secondary rounded w-1/2" />
          <div className="h-4 bg-theme-secondary rounded w-2/3" />
        </div>
      </div>
    );
  }

  // Render a parameter using ExpressionField in read-only mode (same as McpToolSelector)
  const renderParam = (param: any, isRequired: boolean) => {
    const paramName = param.name || param.id;
    const paramDescription = param.description || '';
    const paramType = param.dataType || param.type || 'string';

    return (
      <ExpressionField
        key={param.id || paramName}
        label={paramName}
        value=""
        onChange={() => {}}
        nodeId={node.id}
        fieldName={`param-${paramName}`}
        placeholder={paramDescription || `${paramName}...`}
        isRequired={isRequired}
        isRunMode={true}
        typeHint={paramType}
        description={paramDescription}
      />
    );
  };

  return (
    <div className="space-y-6">
      {/* Credentials - actual CredentialSection component (same as workflow InspectorPanel) */}
      {credentials.length > 0 && (
        <CredentialSection
          toolCredentials={credentials}
          selectedCredentialId={null}
          onCredentialSelect={() => {}}
          integration={toolData?.iconSlug || toolData?.apiSlug || ''}
          isReadOnly
        />
      )}

      {/* Parameters - rendered with ExpressionField in read-only mode (same as McpToolSelector) */}
      <div className="space-y-4 pt-2">
        {requiredParams.map((param: any) => renderParam(param, true))}

        {optionalParams.length > 0 && (
          <OptionalSection
            isOpen={showOptional}
            onToggle={() => setShowOptional(prev => !prev)}
            count={optionalParams.length}
          >
            {optionalParams.map((param: any) => renderParam(param, false))}
          </OptionalSection>
        )}

        {filteredParams.length === 0 && credentials.length === 0 && (
          <div className="text-center py-4 text-theme-muted text-sm">
            {t('noParameters')}
          </div>
        )}
      </div>
    </div>
  );
}

// ─── Resource detail section - shows details for the clicked resource (workflow, interface, table, skill) ───
function ResourceDetailContent({
  node,
  resourcesById,
  skillsByAgent,
  agentId,
  resourceType,
  resourceId,
  t,
}: {
  node: Node<BuilderNodeData>;
  resourcesById: Map<string, any>;
  skillsByAgent: Map<string, AgentSkill[]>;
  agentId?: string;
  resourceType: string;
  resourceId?: string;
  t: ReturnType<typeof useTranslations>;
}) {
  const data = node.data as any;
  const isAllNode = resourceId?.startsWith('all-');

  // Hooks must be at top level - collect "all" resources unconditionally
  const allResources = useMemo(() => {
    if (!isAllNode) return [];
    const items: any[] = [];
    resourcesById.forEach((res) => {
      if (resourceType === 'workflow' && res.plan !== undefined) items.push(res);
      else if (resourceType === 'interface' && (res.htmlTemplate !== undefined || res.workflowId !== undefined) && res.plan === undefined) items.push(res);
      else if (resourceType === 'table' && res.plan === undefined && res.htmlTemplate === undefined) items.push(res);
    });
    return items;
  }, [resourcesById, resourceType, isAllNode]);

  // ─── "All" nodes: list every resource of this type ───
  if (isAllNode) {
    // Skills: list all skills for this agent
    if (resourceType === 'skill' && agentId) {
      const skills = skillsByAgent.get(agentId) || [];
      return (
        <div className="space-y-2 pt-2">
          {skills.map(as => (
            <ResourceListItem key={as.id} name={as.skill?.name || as.skillId.slice(0, 8)} description={as.skill?.description} />
          ))}
        </div>
      );
    }

    return (
      <div className="space-y-2 pt-2">
        {allResources.map((res) => (
          <ResourceListItem
            key={res.id}
            name={res.name}
            description={res.description}
            date={res.updatedAt || res.updated_at || res.createdAt || res.created_at}
          />
        ))}
        {allResources.length === 0 && (
          <p className="text-sm text-theme-muted italic py-4 text-center">{t('noDetails')}</p>
        )}
      </div>
    );
  }

  // ─── Single skill ───
  if (resourceType === 'skill' && agentId) {
    const skills = skillsByAgent.get(agentId) || [];
    const skill = skills.find(as => as.skillId === resourceId || as.skill?.name === data.label)?.skill;
    return (
      <div className="space-y-3 pt-2">
        {skill?.description && (
          <div className="rounded-lg bg-theme-secondary/50 px-3 py-2.5">
            <span className="text-xs text-theme-muted leading-tight">{t('description')}</span>
            <p className="text-sm text-theme-primary leading-relaxed mt-0.5">{skill.description}</p>
          </div>
        )}
        {skill?.instructions && (
          <div className="rounded-lg bg-theme-secondary/50 px-3 py-2.5">
            <span className="text-xs text-theme-muted leading-tight">{t('instructions')}</span>
            <div className="text-sm text-theme-primary leading-relaxed whitespace-pre-wrap max-h-40 overflow-y-auto mt-1 rounded-md bg-theme-tertiary/50 p-2">
              {skill.instructions}
            </div>
          </div>
        )}
        {skill && !skill.description && !skill.instructions && (
          <p className="text-sm text-theme-muted italic py-4 text-center">{t('noDetails')}</p>
        )}
      </div>
    );
  }

  // ─── Single workflow / interface / table ───
  const resource = resourceId ? resourcesById.get(resourceId) : null;

  if (!resource) {
    return (
      <div className="pt-2">
        <p className="text-sm text-theme-muted italic py-4 text-center">{t('noDetails')}</p>
      </div>
    );
  }

  return (
    <div className="space-y-3 pt-2">
      {resource.description && (
        <div className="rounded-lg bg-theme-secondary/50 px-3 py-2.5">
          <span className="text-xs text-theme-muted leading-tight">{t('description')}</span>
          <p className="text-sm text-theme-primary leading-relaxed mt-0.5">{resource.description}</p>
        </div>
      )}

      {resourceType === 'workflow' && resource.plan && (
        <InfoRow label={t('nodeCount')} value={String(countWorkflowNodes(resource.plan))} />
      )}

      {(resource.updatedAt || resource.updated_at) && (
        <InfoRow
          label={t('lastUpdated')}
          value={formatRelativeDateI18n(resource.updatedAt || resource.updated_at, t)}
        />
      )}

      {!(resource.updatedAt || resource.updated_at) && (resource.createdAt || resource.created_at) && (
        <InfoRow
          label={t('createdAt')}
          value={formatRelativeDateI18n(resource.createdAt || resource.created_at, t)}
        />
      )}

      {!resource.description && !(resource.updatedAt || resource.updated_at) && !(resource.createdAt || resource.created_at) && (
        <p className="text-sm text-theme-muted italic py-4 text-center">{t('noDetails')}</p>
      )}
    </div>
  );
}

// ─── Compact list item for "All resources" view ───
function ResourceListItem({ name, description, date }: {
  name: string;
  description?: string;
  date?: string;
}) {
  const t = useTranslations('fleetInspector');
  return (
    <div className="rounded-lg bg-theme-secondary/50 px-3 py-2.5 space-y-0.5">
      <div className="flex items-center gap-2">
        <span className="text-sm font-medium text-theme-primary truncate flex-1">{name}</span>
        {date && (
          <span className="text-[11px] text-theme-muted flex-shrink-0">{formatRelativeDateI18n(date, t)}</span>
        )}
      </div>
      {description && (
        <p className="text-xs text-theme-muted line-clamp-2">{description}</p>
      )}
    </div>
  );
}

// ─── Helpers for resource details ───
function countWorkflowNodes(plan: any): number {
  let count = 0;
  if (plan?.triggers) count += plan.triggers.length;
  if (plan?.mcps) count += plan.mcps.length;
  if (plan?.cores) count += plan.cores.length;
  if (plan?.tables) count += plan.tables.length;
  if (plan?.agents) count += plan.agents.length;
  return count;
}

// ─── Provider group detail section - lists all tools under this provider ───
function ProviderInspectorContent({
  node,
  allNodes,
  agentId,
  apiSlug,
  t,
}: {
  node: Node<BuilderNodeData>;
  allNodes: Node<BuilderNodeData>[];
  agentId?: string;
  apiSlug?: string;
  t: ReturnType<typeof useTranslations>;
}) {
  const data = node.data as any;
  const apiData = data.apiData as Record<string, any> | undefined;

  // Collect all tool nodes under this provider for this agent
  const tools = useMemo(() => {
    if (!agentId) return [];
    return allNodes.filter(n => {
      const d = n.data as any;
      return n.id.startsWith(`res-${agentId}-tool-`) && d.apiData?.apiSlug === apiSlug;
    });
  }, [allNodes, agentId, apiSlug]);

  return (
    <div className="space-y-3 pt-2">
      {apiData?.apiName && (
        <InfoRow
          label={t('apiName')}
          value={apiData.apiName}
          icon={apiData.iconSlug ? <ServiceIcon iconSlug={apiData.iconSlug} size={14} className="h-3.5 w-3.5 flex-shrink-0" /> : undefined}
        />
      )}

      {/* List all tools under this provider */}
      {tools.length > 0 && (
        <div className="rounded-lg bg-theme-secondary/50 px-3 py-2.5">
          <span className="text-xs text-theme-muted leading-tight">
            Tools ({tools.length})
          </span>
          <div className="space-y-1.5 mt-1.5">
            {tools.map(toolNode => {
              const td = toolNode.data as any;
              const iconSlug = td.toolData?.iconSlug || td.apiData?.iconSlug;
              return (
                <div key={toolNode.id} className="flex items-center gap-2 text-sm text-theme-primary">
                  {iconSlug && (
                    <ServiceIcon iconSlug={iconSlug} size={14} className="h-3.5 w-3.5 flex-shrink-0" />
                  )}
                  <span className="truncate">{td.label || toolNode.id}</span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Category group inspector: lists children of a category group (Workflows, Interfaces, etc.) ───
function CategoryGroupInspectorContent({
  node,
  allNodes,
  allEdges,
  t,
}: {
  node: Node<BuilderNodeData>;
  allNodes: Node<BuilderNodeData>[];
  allEdges?: { source: string; target: string }[];
  t: ReturnType<typeof useTranslations>;
}) {
  const data = node.data as any;
  const groupType = data.fleetResourceType as string | undefined;
  const childCount = data.fleetGroupChildCount || 0;

  // Find children by edge (category node → child) or by prefix match
  const children = useMemo(() => {
    if (allEdges) {
      const childIds = new Set(
        allEdges.filter(e => e.source === node.id).map(e => e.target),
      );
      return allNodes.filter(n => childIds.has(n.id));
    }
    // Fallback: match by node ID pattern
    return allNodes.filter(n => {
      const d = n.data as any;
      return d.fleetResourceType === groupType && n.id !== node.id && n.id.startsWith('res-');
    });
  }, [allNodes, allEdges, node.id, groupType]);

  const iconInfo = groupType ? FLEET_RESOURCE_ICONS[groupType] : undefined;
  const IconComp = iconInfo?.icon;

  return (
    <div className="space-y-3 pt-2">
      {children.length > 0 && (
        <div className="rounded-lg bg-theme-secondary/50 px-3 py-2.5">
          <span className="text-xs text-theme-muted leading-tight">
            {data.label} ({childCount})
          </span>
          <div className="space-y-1.5 mt-1.5">
            {children.map(child => {
              const cd = child.data as any;
              const iconSlug = cd.toolData?.iconSlug || cd.apiData?.iconSlug;
              return (
                <div key={child.id} className="flex items-center gap-2 text-sm text-theme-primary">
                  {iconSlug ? (
                    <ServiceIcon iconSlug={iconSlug} size={14} className="h-3.5 w-3.5 flex-shrink-0" />
                  ) : IconComp ? (
                    <IconComp className="h-3.5 w-3.5 flex-shrink-0" />
                  ) : null}
                  <span className="truncate">{cd.label || child.id}</span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Aggregated-resources inspector: expandable breakdown for a "Resources (N)" node ───
// The fleet collapses an agent's resources into one aggregator node past the threshold
// (see consolidateFleetResources). That node carries `fleetAggregatedItems` (the actual
// resources it folded, each with its call counts), so this panel groups them by family
// and lets the user expand each family to the individual resources - with cumulative
// ✓/✗ counts at the family level and per-resource counts at the leaf level.

// Family → display order, icon, and i18n label key.
const AGG_FAMILY_ORDER = ['tool', 'skill', 'workflow', 'interface', 'table', 'file', 'web_search'] as const;
const AGG_FAMILY_ICON: Record<string, React.ComponentType<{ className?: string }>> = {
  tool: Wrench, skill: Zap, workflow: Workflow, interface: Monitor, table: Table, file: FileText, web_search: Globe,
};
const AGG_FAMILY_LABEL_KEY: Record<string, string> = {
  tool: 'tools', skill: 'skills', workflow: 'workflows', interface: 'interfaces', table: 'tables', file: 'files', web_search: 'webSearch',
};

// One folded resource inside the aggregated panel: icon + label + counts + a hover
// trash (so resources hidden inside a consolidated "Resources (N)" stay removable).
function AggregatedItemRow({
  item,
  FamilyIcon,
  agentId,
  agentName,
  onRefresh,
  t,
}: {
  item: FleetAggregatedItem;
  FamilyIcon: React.ComponentType<{ className?: string }>;
  agentId: string;
  agentName: string;
  onRefresh?: () => void;
  t: ReturnType<typeof useTranslations>;
}) {
  const sidePanel = useSidePanelSafe();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const itStatus = item.statusCounts ? deriveStatusFromCounts(item.statusCounts) : 'pending';
  const parsed = parseNodeId(item.nodeId);
  const resourceId = parsed.resourceId || '';
  // Same affordance rules as ResourceItem: "All tools" is not removable (mode change,
  // not a single tool), and workflow/table/interface open in the side panel for edit.
  const isAllAccessChip = item.type === 'tool' && resourceId === 'all-tools';
  const openableType = (item.type === 'workflow' || item.type === 'table' || item.type === 'interface') ? item.type : null;

  const handleConfirm = useCallback(async () => {
    if (!agentId || !resourceId) { setConfirmOpen(false); return; }
    setDeleting(true);
    try {
      await disconnectFleetResource(agentId, item.type, resourceId);
      setConfirmOpen(false);
      onRefresh?.();
    } catch (err) {
      console.error('Failed to remove resource:', err);
    } finally {
      setDeleting(false);
    }
  }, [agentId, resourceId, item.type, onRefresh]);

  return (
    <div className="group/aggitem flex items-center gap-2 h-7 text-sm">
      {item.iconSlug
        ? <ServiceIcon iconSlug={item.iconSlug} size={14} className="h-3.5 w-3.5 flex-shrink-0" />
        : <FamilyIcon className="h-3.5 w-3.5 flex-shrink-0 text-theme-secondary" />}
      <span className="flex-1 truncate text-theme-primary">{item.label}</span>
      {item.statusCounts && itStatus !== 'pending' && <NodeStatusBadge status={itStatus} statusCounts={item.statusCounts} />}
      {sidePanel && openableType && resourceId && (
        <button
          onClick={(e) => { e.stopPropagation(); openFleetSidePanelTab(sidePanel, { type: openableType, resourceId, label: item.label }); }}
          className="opacity-0 group-hover/aggitem:opacity-100 p-0.5 rounded text-theme-secondary hover:bg-slate-100 dark:hover:bg-slate-700 transition-opacity"
          title={t('edit')}
        >
          <Pencil className="h-3 w-3" />
        </button>
      )}
      {agentId && resourceId && !isAllAccessChip && (
        <button
          onClick={(e) => { e.stopPropagation(); setConfirmOpen(true); }}
          className="opacity-0 group-hover/aggitem:opacity-100 p-0.5 rounded text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20 transition-opacity"
          title={t('removeAction')}
        >
          <Trash2 className="h-3 w-3" />
        </button>
      )}
      {confirmOpen && (
        <ConfirmDeleteModal
          isOpen
          title={t('confirmRemoveTitle')}
          message={buildRemoveDescription(t, item.type, item.label, agentName)}
          confirmLabel={t('removeAction')}
          isLoading={deleting}
          onConfirm={handleConfirm}
          onCancel={() => { if (!deleting) setConfirmOpen(false); }}
        />
      )}
    </div>
  );
}

function sumItemCounts(items: FleetAggregatedItem[]): Record<string, number> {
  const acc: Record<string, number> = {};
  for (const it of items) {
    for (const [k, v] of Object.entries(it.statusCounts ?? {})) {
      const n = Number(v);
      if (Number.isFinite(n) && n !== 0) acc[k] = (acc[k] ?? 0) + n;
    }
  }
  return acc;
}

function AggregatedResourcesInspectorContent({
  node,
  t,
  agentName,
  onRefresh,
}: {
  node: Node<BuilderNodeData>;
  t: ReturnType<typeof useTranslations>;
  agentName?: string;
  onRefresh?: () => void;
}) {
  const data = node.data as any;
  const items: FleetAggregatedItem[] = Array.isArray(data.fleetAggregatedItems) ? data.fleetAggregatedItems : [];
  // The aggregator node id is `agg-agent-{uuid}` → resolve the owning agent via
  // parseNodeId. The previous `node.id.slice('agg-'.length)` kept the `agent-` prefix,
  // so every drill-down delete called the agent API with `agent-{uuid}` → 404 → the
  // remove button silently did nothing (the reported "deletion doesn't work" bug).
  const agentId = (node.id.startsWith('agg-') && parseNodeId(node.id).agentId) || '';

  // Group items by family, in a stable display order.
  const families = useMemo(() => {
    const map = new Map<string, FleetAggregatedItem[]>();
    for (const it of items) {
      if (!map.has(it.type)) map.set(it.type, []);
      map.get(it.type)!.push(it);
    }
    const ordered: [string, FleetAggregatedItem[]][] = [];
    for (const fam of AGG_FAMILY_ORDER) if (map.has(fam)) ordered.push([fam, map.get(fam)!]);
    for (const [fam, list] of map) if (!AGG_FAMILY_ORDER.includes(fam as any)) ordered.push([fam, list]);
    return ordered;
  }, [items]);

  // Default = every family expanded; track only the explicitly collapsed ones (so a
  // newly-selected aggregator with different families still starts fully expanded).
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const toggle = useCallback((fam: string) => {
    setCollapsed(prev => {
      const next = new Set(prev);
      if (next.has(fam)) next.delete(fam); else next.add(fam);
      return next;
    });
  }, []);

  const aggCounts = (data.statusCounts ?? undefined) as Record<string, number> | undefined;
  const aggStatus = aggCounts ? deriveStatusFromCounts(aggCounts) : 'pending';
  // Header total mirrors the node's "Resources (N)" label (derived from
  // fleetResourceCounts the same way consolidateFleetResources computes N), so the
  // panel and the canvas node always agree. Falls back to the rendered item count.
  const total = useMemo(() => {
    const c = data.fleetResourceCounts as Record<string, number | boolean> | undefined;
    if (!c) return items.length;
    let tot = 0;
    for (const k of ['tools', 'skills', 'workflows', 'interfaces', 'tables', 'files']) {
      const v = Number((c as any)[k]);
      if (Number.isFinite(v) && v > 0) tot += v;
    }
    if (c.webSearch) tot += 1;
    return tot > 0 ? tot : items.length;
  }, [data.fleetResourceCounts, items.length]);

  // Fallback: pre-existing nodes without fleetAggregatedItems → count-only breakdown.
  if (items.length === 0) {
    const counts = data.fleetResourceCounts as Record<string, number | boolean> | undefined;
    const rows: { key: string; icon: React.ComponentType<{ className?: string }>; count: number }[] = [];
    const push = (key: string, icon: React.ComponentType<{ className?: string }>, count: number) => {
      if (count > 0) rows.push({ key, icon, count });
    };
    if (counts) {
      push('tools', Wrench, Number(counts.tools) || 0);
      push('skills', Zap, Number(counts.skills) || 0);
      push('workflows', Workflow, Number(counts.workflows) || 0);
      push('interfaces', Monitor, Number(counts.interfaces) || 0);
      push('tables', Table, Number(counts.tables) || 0);
      push('files', FileText, Number(counts.files) || 0);
      if (counts.webSearch) rows.push({ key: 'webSearch', icon: Globe, count: 1 });
    }
    const countTotal = rows.reduce((sum, r) => sum + r.count, 0);
    return (
      <div className="space-y-3 pt-2">
        <div className="rounded-lg bg-theme-secondary/50 px-3 py-2.5">
          <span className="text-xs text-theme-muted leading-tight">{t('resources')} ({countTotal})</span>
          <div className="space-y-1.5 mt-1.5">
            {rows.map(r => {
              const Icon = r.icon;
              return (
                <div key={r.key} className="flex items-center justify-between gap-2 text-sm text-theme-primary">
                  <span className="flex items-center gap-2 min-w-0">
                    <Icon className="h-3.5 w-3.5 flex-shrink-0 text-theme-secondary" />
                    <span className="truncate">{t(r.key)}</span>
                  </span>
                  <span className="tabular-nums text-theme-secondary">{r.count}</span>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-3 pt-2">
      <div className="rounded-lg bg-theme-secondary/50 px-3 py-2.5">
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs text-theme-muted leading-tight">{t('resources')} ({total})</span>
          {aggCounts && aggStatus !== 'pending' && <NodeStatusBadge status={aggStatus} statusCounts={aggCounts} />}
        </div>
        <div className="mt-1.5 space-y-0.5">
          {families.map(([fam, famItems]) => {
            const Icon = AGG_FAMILY_ICON[fam] ?? Wrench;
            const isOpen = !collapsed.has(fam);
            const famCounts = sumItemCounts(famItems);
            const famStatus = deriveStatusFromCounts(famCounts);
            const hasFamCounts = Object.keys(famCounts).length > 0;
            return (
              <div key={fam}>
                <button
                  type="button"
                  onClick={() => toggle(fam)}
                  className="w-full flex items-center gap-2 h-8 text-sm text-theme-primary hover:bg-theme-secondary/60 rounded-lg transition-colors px-1"
                >
                  <ChevronRight className={`h-3.5 w-3.5 flex-shrink-0 text-theme-secondary transition-transform duration-200 ${isOpen ? 'rotate-90' : ''}`} />
                  <Icon className="h-3.5 w-3.5 flex-shrink-0 text-theme-secondary" />
                  <span className="flex-1 text-left truncate font-medium">{t(AGG_FAMILY_LABEL_KEY[fam] ?? fam)}</span>
                  {hasFamCounts && famStatus !== 'pending' && <NodeStatusBadge status={famStatus} statusCounts={famCounts} />}
                  <span className="tabular-nums text-theme-secondary">{famItems.length}</span>
                </button>
                {isOpen && (
                  <div className="ml-[22px] border-l border-theme pl-2 space-y-0.5">
                    {famItems.map((it, i) => (
                      <AggregatedItemRow
                        key={`${it.nodeId}-${i}`}
                        item={it}
                        FamilyIcon={Icon}
                        agentId={agentId}
                        agentName={agentName ?? ''}
                        onRefresh={onRefresh}
                        t={t}
                      />
                    ))}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

// ─── Folder inspector: read-only skill tree from a given folder downward ───
// Same look as SkillFolderTreeSelect but without checkboxes/selection.
const TREE_ML = 'ml-[23px]';
const TREE_PL = 'pl-3';

interface FolderTreeNode {
  folder: SkillFolder;
  children: FolderTreeNode[];
}

function FolderInspectorContent({
  folderId,
  agentId,
  skillsByAgent,
  allFolders,
  t,
}: {
  folderId: string;
  agentId?: string;
  skillsByAgent: Map<string, AgentSkill[]>;
  allFolders: SkillFolder[];
  t: ReturnType<typeof useTranslations>;
}) {
  const [expandedIds, setExpandedIds] = useState<Set<string>>(() => {
    // Start fully expanded
    return new Set(allFolders.map(f => f.id));
  });

  // Collect agent skills and group by folderId
  const agentSkills = agentId ? (skillsByAgent.get(agentId) || []) : [];

  const skillsByFolder = useMemo(() => {
    const map = new Map<string | null, Skill[]>();
    for (const as of agentSkills) {
      if (!as.skill) continue;
      const key = as.skill.folderId || null;
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(as.skill);
    }
    return map;
  }, [agentSkills]);

  // Build tree starting from folderId (not root)
  const tree = useMemo((): FolderTreeNode | null => {
    const folderMap = new Map(allFolders.map(f => [f.id, f]));
    const rootFolder = folderMap.get(folderId);
    if (!rootFolder) return null;

    const build = (parentId: string): FolderTreeNode[] =>
      allFolders
        .filter(f => f.parentId === parentId)
        .sort((a, b) => a.name.localeCompare(b.name))
        .map(folder => ({ folder, children: build(folder.id) }));

    return { folder: rootFolder, children: build(folderId) };
  }, [allFolders, folderId]);

  const toggleExpand = useCallback((id: string) => {
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }, []);

  const renderSkillRow = (skill: Skill) => (
    <div
      key={skill.id}
      className="flex items-center h-8 text-sm rounded-lg"
    >
      <span className="w-[16px] flex-shrink-0" />
      <Zap className="h-3.5 w-3.5 flex-shrink-0 text-theme-secondary mx-2" />
      <div className="flex-1 min-w-0">
        <span className="text-sm text-theme-primary truncate block">{skill.name}</span>
      </div>
    </div>
  );

  const renderNode = (node: FolderTreeNode, isRoot = false) => {
    const isExpanded = expandedIds.has(node.folder.id);
    const folderSkills = skillsByFolder.get(node.folder.id) || [];

    return (
      <div key={node.folder.id}>
        <div
          className="flex items-center h-8 cursor-pointer text-sm text-theme-primary hover:bg-theme-secondary/50 rounded-lg transition-colors"
          onClick={() => toggleExpand(node.folder.id)}
        >
          <button
            className="flex-shrink-0 p-0 mr-0.5"
            onClick={(e) => { e.stopPropagation(); toggleExpand(node.folder.id); }}
          >
            <ChevronRight className={`h-3.5 w-3.5 text-theme-secondary transition-transform duration-200 ${isExpanded ? 'rotate-90' : ''}`} />
          </button>
          {isExpanded
            ? <FolderOpen className="h-4 w-4 text-theme-secondary flex-shrink-0 mr-2" />
            : <Folder className="h-4 w-4 text-theme-secondary flex-shrink-0 mr-2" />
          }
          <span className={`truncate ${isRoot ? 'font-semibold' : 'font-medium'}`}>{node.folder.name}</span>
        </div>
        {isExpanded && (
          <div className={`${TREE_ML} border-l border-theme ${TREE_PL}`}>
            {node.children.map(child => renderNode(child))}
            {folderSkills.map(skill => renderSkillRow(skill))}
          </div>
        )}
      </div>
    );
  };

  if (!tree) {
    return <p className="text-sm text-theme-muted italic py-4 text-center">{t('noDetails')}</p>;
  }

  return (
    <div className="w-full pt-2">
      <nav>
        {renderNode(tree, true)}
      </nav>
    </div>
  );
}

/**
 * Resolve the display kind label for the header subtitle.
 * Same pattern as InspectorPanelHeader: uppercase tracking-wide subtitle.
 */
function getDisplayKind(parsed: ReturnType<typeof parseNodeId>, data: any): string {
  if (data.fleetAggregator) return 'Resources';
  if (parsed.category === 'agent') return 'Agent';
  if (parsed.category === 'folder') return 'Folder';
  if (parsed.category === 'provider') return 'Provider';
  if (parsed.category === 'categoryGroup') return 'Group';
  const rt = data.fleetResourceType;
  if (rt === 'model') return 'Model';
  if (rt === 'tool') return 'Tool';
  if (rt === 'skill') return 'Skill';
  if (rt === 'workflow') return 'Workflow';
  if (rt === 'interface') return 'Interface';
  if (rt === 'table') return 'Table';
  return 'Resource';
}

// ─── Execution list helpers ───
const mapExecStatus = (status: string): 'completed' | 'failed' | 'running' | 'pending' | 'cancelled' => {
  const map: Record<string, 'completed' | 'failed' | 'running' | 'pending' | 'cancelled'> = {
    COMPLETED: 'completed', FAILED: 'failed', RUNNING: 'running', PENDING: 'pending', CANCELLED: 'cancelled',
  };
  return map[status] || 'pending';
};

// ─── Inline execution list for the Executions tab ───
export function AgentExecutionList({
  agentId,
  onSelectExecution,
  selectedExecutionId,
  t,
}: {
  agentId: string;
  onSelectExecution: (id: string) => void;
  selectedExecutionId: string | null;
  t: ReturnType<typeof useTranslations>;
}) {
  const [executions, setExecutions] = React.useState<AgentExecutionRecord[]>([]);
  const [loading, setLoading] = React.useState(false);
  const [loadingMore, setLoadingMore] = React.useState(false);
  const [hasMore, setHasMore] = React.useState(true);
  const pageRef = React.useRef(0);
  const observerTarget = React.useRef<HTMLDivElement>(null);
  const PAGE_SIZE = 15;

  const fetchExecutions = React.useCallback(async (reset = false) => {
    const page = reset ? 0 : pageRef.current;
    try {
      if (reset) { setLoading(true); setExecutions([]); pageRef.current = 0; setHasMore(true); }
      else { setLoadingMore(true); }
      const data = await agentService.getAgentExecutions(agentId, page, PAGE_SIZE);
      const items = data.content || [];
      if (reset) setExecutions(items);
      else setExecutions(prev => [...prev, ...items]);
      setHasMore(!data.last);
      pageRef.current = page + 1;
    } catch { /* silent */ } finally {
      setLoading(false); setLoadingMore(false);
    }
  }, [agentId]);

  React.useEffect(() => { fetchExecutions(true); }, [fetchExecutions]);

  // Auto-refresh when agent execution completes (real-time WebSocket).
  // Only on completion to avoid resetting pagination mid-execution.
  const activity = useAgentActivity(agentId);
  const lastEventIdRef = React.useRef<string | null>(null);

  // Phase 6c (2026-05-19) - drop the executions paginator on workspace
  // switch. Inline executions tab inside the inspector keepMounted
  // shell - without this reset the previous workspace's executions
  // remain in the table.
  useOrgScopedReset(() => {
    setExecutions([]);
    setHasMore(true);
    pageRef.current = 0;
    lastEventIdRef.current = null;
  });
  React.useEffect(() => {
    if (!activity?.lastEvent) return;
    const ev = activity.lastEvent;
    if (ev.event === 'execution_completed' && ev.executionId !== lastEventIdRef.current) {
      lastEventIdRef.current = ev.executionId;
      fetchExecutions(true);
    }
  }, [activity?.lastEvent?.event, activity?.lastEvent?.executionId, fetchExecutions]);

  // Infinite scroll
  React.useEffect(() => {
    if (!hasMore || loadingMore) return;
    const observer = new IntersectionObserver(
      (entries) => { if (entries[0].isIntersecting && hasMore && !loadingMore) fetchExecutions(false); },
      { threshold: 0.1 },
    );
    const target = observerTarget.current;
    if (target) observer.observe(target);
    return () => { if (target) observer.unobserve(target); };
  }, [hasMore, loadingMore, fetchExecutions, executions.length]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <RefreshCw className="w-5 h-5 animate-spin text-slate-400" />
      </div>
    );
  }

  if (executions.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-center">
        <Activity className="w-10 h-10 text-slate-300 dark:text-slate-600 mb-3" />
        <p className="text-sm text-slate-400 dark:text-slate-500">{t('noExecutions')}</p>
      </div>
    );
  }

  return (
    <div className="space-y-0.5 pt-1">
      {/* Header row */}
      <div className="grid grid-cols-[auto_1fr_56px_56px_80px] items-center gap-x-3 px-3 py-1">
        <span />
        <span className="text-[10px] font-medium text-theme-muted uppercase tracking-wider">{t('model')}</span>
        <span className="text-[10px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('tokens')}</span>
        <span className="text-[10px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('duration')}</span>
        <span className="text-[10px] font-medium text-theme-muted uppercase tracking-wider text-right">{t('date')}</span>
      </div>
      {executions.map(exec => {
        const isSelected = selectedExecutionId === exec.id;
        return (
          <React.Fragment key={exec.id}>
            <div
              onClick={() => onSelectExecution(exec.id)}
              className={cn(
                'grid grid-cols-[auto_1fr_56px_56px_80px] items-center gap-x-3 px-3 py-2 rounded-lg cursor-pointer transition-colors',
                isSelected ? 'bg-theme-tertiary' : 'hover:bg-theme-secondary',
              )}
            >
              {exec.status === 'COMPLETED' ? (
                <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />
              ) : exec.status === 'CANCELLED' ? (
                <XCircle className="h-3.5 w-3.5 text-purple-500" />
              ) : exec.status === 'RUNNING' ? (
                <RefreshCw className="h-3.5 w-3.5 text-blue-500 animate-spin" />
              ) : (
                <AlertCircle className="h-3.5 w-3.5 text-red-500" />
              )}
              <div className="flex items-center gap-1.5 min-w-0">
                <span className="text-sm text-theme-primary truncate">
                  {exec.model || exec.provider || exec.id.slice(0, 8)}
                </span>
                {exec.source && (
                  <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-slate-100 dark:bg-slate-700/50 text-theme-muted flex-shrink-0">
                    {exec.source}
                  </span>
                )}
                {exec.agentType && exec.agentType !== 'agent' && (
                  <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400 flex-shrink-0">
                    {exec.agentType}
                  </span>
                )}
              </div>
              <span className="text-xs text-theme-muted tabular-nums text-right">
                {exec.totalTokens >= 1_000_000 ? `${(exec.totalTokens / 1_000_000).toFixed(1)}M`
                  : exec.totalTokens >= 1_000 ? `${(exec.totalTokens / 1_000).toFixed(1)}K`
                  : exec.totalTokens > 0 ? String(exec.totalTokens) : '-'}
              </span>
              <span className="text-xs text-theme-muted text-right">
                {exec.durationMs != null ? formatDuration(exec.durationMs) : '-'}
              </span>
              <span className="text-xs text-theme-muted text-right">
                {formatRelativeDateI18n(exec.startedAt, t)}
              </span>
            </div>
            {exec.errorMessage && (
              <div className="px-3 pb-1 pl-8">
                <span className="text-xs text-red-500 truncate block">
                  {exec.errorMessage.length > 80 ? `${exec.errorMessage.slice(0, 80)}…` : exec.errorMessage}
                </span>
              </div>
            )}
          </React.Fragment>
        );
      })}
      {loadingMore && (
        <div className="flex items-center justify-center py-4">
          <RefreshCw className="w-4 h-4 animate-spin text-theme-secondary" />
        </div>
      )}
      {hasMore && !loadingMore && (
        <div ref={observerTarget} className="h-8 flex items-center justify-center">
          <span className="text-xs text-theme-secondary opacity-50">{t('scrollToLoadMore')}</span>
        </div>
      )}
      {!hasMore && executions.length > 0 && (
        <div className="text-center py-3 text-xs text-theme-secondary opacity-50">{t('noMoreExecutions')}</div>
      )}
    </div>
  );
}

/**
 * FleetInspectorPanel - read-only inspector panel for the Agent Fleet canvas.
 * Matches the InspectorPanel visual style: rounded-[32px], bg-white, drag handle
 * on left edge with hover reveal, NodeIcon in header, uppercase subtitle.
 * All fields use disabled Select/Input components (same as run mode).
 */
export function FleetInspectorPanel({
  node,
  allNodes,
  agents,
  skillsByAgent,
  skillFolders = [],
  resourcesById,
  onClose,
  onDragHandleMouseDown,
  isMinimized,
  onMinimizedChange,
  availableWidth,
  onRefresh,
}: FleetInspectorPanelProps) {
  const t = useTranslations('fleetInspector');
  const parsed = parseNodeId(node.id);
  const data = node.data as any;

  // Resolve agent for agent nodes
  const agent = parsed.category === 'agent'
    ? agents.find(a => a.id === parsed.agentId)
    : undefined;

  const skills = agent ? (skillsByAgent.get(agent.id) || []) : [];

  // Header info
  const headerLabel = data.label || agent?.name || node.id;
  const isAgent = parsed.category === 'agent' && !!agent;
  const displayKind = getDisplayKind(parsed, data);

  // Tab mode for agent nodes: Configuration vs Executions
  const [viewMode, setViewMode] = useState<'configuration' | 'executions'>('configuration');
  // Execution detail navigation (inside the Executions tab)
  const [viewingExecutionId, setViewingExecutionId] = useState<string | null>(null);

  // Reset execution state when switching nodes
  useEffect(() => {
    setViewMode('configuration');
    setViewingExecutionId(null);
  }, [node.id]);

  // Compute contextual side-panel action (only for openable resource types)
  const sidePanelAction = useMemo((): FleetSidePanelAction | null => {
    if (parsed.category === 'agent' && parsed.agentId) {
      return { type: 'agent', resourceId: parsed.agentId, label: headerLabel, avatarUrl: agent?.avatarUrl };
    }
    if (parsed.category === 'resource' && parsed.resourceId && !parsed.resourceId.startsWith('all-')) {
      const rt = data.fleetResourceType as string | undefined;
      if (rt === 'workflow' || rt === 'interface' || rt === 'table') {
        return { type: rt, resourceId: parsed.resourceId, label: headerLabel };
      }
    }
    return null;
  }, [parsed.category, parsed.agentId, parsed.resourceId, data.fleetResourceType, headerLabel]);

  const handleMinimize = useCallback(() => {
    onMinimizedChange?.(true);
  }, [onMinimizedChange]);

  // ─── Minimized pill (desktop only) ───
  if (isMinimized) {
    return (
      <div
        onClick={(e) => e.stopPropagation()}
        onMouseDown={(e) => e.stopPropagation()}
        className="relative"
      >
        <div
          data-inspector-panel
          className="flex items-center gap-2.5 px-3 py-2 bg-white dark:bg-gray-800 rounded-full pointer-events-auto cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-700/50 transition-colors relative inset-auto z-[150]"
          onClick={() => onMinimizedChange?.(false)}
        >
          {isAgent && agent?.avatarUrl ? (
            <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="sm" />
          ) : (() => {
            const iconSlug = getIconSlug(data);
            const fleetResourceType = data.fleetResourceType as string | undefined;
            const fallback = fleetResourceType && !iconSlug ? FLEET_RESOURCE_ICONS[fleetResourceType] : undefined;
            return (
              <NodeIcon
                iconSlug={iconSlug}
                fallbackIcon={fallback?.icon}
                bgClassName={fallback?.bg}
                nodeId={data?.id || ''}
                nodeKind={data.kind}
                size="sm"
              />
            );
          })()}
          <span className="text-sm font-medium text-gray-900 dark:text-gray-100 truncate max-w-[140px]">
            {headerLabel}
          </span>
          <Maximize2 className="h-3.5 w-3.5 text-gray-400 dark:text-gray-500 flex-shrink-0" />
        </div>
      </div>
    );
  }

  return (
    <div className="relative">
      {/* Outside action buttons */}
      <FleetInspectorActionButtons
        onClose={onClose}
        onMinimize={handleMinimize}
        sidePanelAction={sidePanelAction}
      />

      <div
        data-inspector-panel
        className={cn(
          // Mobile: full-screen overlay
          'fixed inset-0 w-full h-full z-[9999]',
          // Desktop: sized panel
          'lg:relative lg:inset-auto lg:w-auto lg:h-auto lg:z-[150]',
          'lg:max-h-[90vh] lg:rounded-[32px] bg-white dark:bg-gray-800 flex flex-col pointer-events-auto overflow-hidden group/inspector transition-[width] duration-200',
          isAgent && viewMode === 'executions' ? 'lg:w-[35vw] lg:min-w-[480px]' : 'lg:w-[300px]',
        )}
        style={availableWidth ? { maxWidth: typeof window !== 'undefined' && window.innerWidth >= 1024 ? Math.max(300, availableWidth - 16) : undefined } : undefined}
      >
        {/* ─── Header (same layout as InspectorPanelHeader) ─── */}
        <div className="flex gap-3 px-5 pt-5 pb-3 relative items-center flex-shrink-0">
          {/* Mobile close/minimize - inline in header since FleetInspectorActionButtons is hidden on mobile */}
          <div className="lg:hidden flex items-center gap-1.5 absolute right-3 top-3 z-10">
            <button
              onClick={() => onMinimizedChange?.(true)}
              className="h-7 w-7 flex items-center justify-center rounded-full hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
              title={t('collapse')}
            >
              <Minus className="h-3.5 w-3.5 text-theme-secondary" />
            </button>
            <button
              onClick={onClose}
              className="h-7 w-7 flex items-center justify-center rounded-full hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
              title={t('close')}
            >
              <X className="h-3.5 w-3.5 text-theme-secondary" />
            </button>
          </div>

          {/* Drag handle - left edge, visible on hover (desktop only) */}
          {onDragHandleMouseDown && (
            <div
              data-drag-handle
              onMouseDown={onDragHandleMouseDown}
              className="hidden lg:flex absolute left-0 top-0 bottom-0 w-6 cursor-grab active:cursor-grabbing rounded-l-[32px] items-center justify-center opacity-0 group-hover/inspector:opacity-100 transition-opacity hover:bg-slate-100 dark:hover:bg-slate-800"
              title={t('dragToMove')}
            >
              <GripVertical className="h-4 w-4 text-slate-400 dark:text-slate-500" />
            </div>
          )}

          {/* Icon - same fallback logic as FlowNode: service icon → fleet resource icon → kind fallback */}
          {isAgent && agent?.avatarUrl ? (
            <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="lg" />
          ) : (() => {
            const iconSlug = getIconSlug(data);
            const fleetResourceType = data.fleetResourceType as string | undefined;
            const fallback = fleetResourceType && !iconSlug ? FLEET_RESOURCE_ICONS[fleetResourceType] : undefined;
            return (
              <NodeIcon
                iconSlug={iconSlug}
                fallbackIcon={fallback?.icon}
                bgClassName={fallback?.bg}
                nodeId={data?.id || ''}
                nodeKind={data.kind}
                size="lg"
              />
            );
          })()}

          {/* Title section */}
          <div className="flex-1 min-w-0">
            <div className="mb-1 h-5">
              <p className="text-sm font-semibold uppercase tracking-[0.3em] text-theme-muted truncate">
                {displayKind}
              </p>
            </div>
            <div className="w-full text-lg font-semibold text-theme-primary truncate">
              {headerLabel}
            </div>
          </div>
        </div>

        {/* ─── Column header: tabs for agent, simple label for others ─── */}
        {isAgent ? (
          <div className="flex border-b border-slate-200 dark:border-slate-700 shrink-0">
            <button
              onClick={() => { setViewMode('configuration'); setViewingExecutionId(null); }}
              className={cn(
                'flex-1 px-3 py-2.5 text-xs font-semibold uppercase tracking-[0.15em] transition-all border-b-2 -mb-px',
                viewMode === 'configuration'
                  ? 'border-[var(--accent-primary)] text-theme-primary'
                  : 'border-transparent text-slate-400 dark:text-slate-500 hover:text-theme-primary',
              )}
            >
              {t('configuration')}
            </button>
            <button
              onClick={() => { setViewMode('executions'); }}
              className={cn(
                'flex-1 px-3 py-2.5 text-xs font-semibold uppercase tracking-[0.15em] transition-all border-b-2 -mb-px',
                viewMode === 'executions'
                  ? 'border-[var(--accent-primary)] text-theme-primary'
                  : 'border-transparent text-slate-400 dark:text-slate-500 hover:text-theme-primary',
              )}
            >
              {t('executions')}
            </button>
          </div>
        ) : (
          <div className="p-3 flex items-center justify-center gap-2 shrink-0">
            <label className="text-sm font-semibold uppercase tracking-[0.2em] text-theme-muted block">
              {t('details')}
            </label>
          </div>
        )}

        {/* Back button when viewing execution detail */}
        {isAgent && viewMode === 'executions' && viewingExecutionId && (
          <div className="px-3 pt-2 shrink-0">
            <button
              onClick={() => setViewingExecutionId(null)}
              className="flex items-center gap-1 text-xs text-slate-500 dark:text-slate-400 hover:text-theme-primary transition-colors"
            >
              <ChevronLeft className="h-3.5 w-3.5" />
              {t('executionHistory')}
            </button>
          </div>
        )}

        {/* ─── Content - scrollable (same as InspectorColumn) ─── */}
        <div className="flex-1 min-h-0 overflow-y-auto overflow-x-auto px-3 pb-3 custom-scrollbar" style={{ height: 0 }}>
          {(data as any).fleetAggregator ? (
            // key={node.id} → fresh (fully-expanded) collapse state per aggregator
            <AggregatedResourcesInspectorContent
              key={node.id}
              node={node}
              t={t}
              agentName={agents.find(a => a.id === parseNodeId(node.id).agentId)?.name}
              onRefresh={onRefresh}
            />
          ) : isAgent && agent ? (
            viewMode === 'executions' ? (
              viewingExecutionId ? (
                <AgentExecutionInspectorDetail executionId={viewingExecutionId} agents={agents} />
              ) : (
                <AgentExecutionList
                  agentId={agent.id}
                  onSelectExecution={setViewingExecutionId}
                  selectedExecutionId={viewingExecutionId}
                  t={t}
                />
              )
            ) : (
              <AgentInspectorContent agent={agent} skills={skills} nodes={allNodes} t={t} onRefresh={onRefresh} />
            )
          ) : parsed.category === 'folder' && parsed.folderId ? (
            <FolderInspectorContent folderId={parsed.folderId} agentId={parsed.agentId} skillsByAgent={skillsByAgent} allFolders={skillFolders} t={t} />
          ) : parsed.category === 'provider' ? (
            <ProviderInspectorContent node={node} allNodes={allNodes} agentId={parsed.agentId} apiSlug={parsed.apiSlug} t={t} />
          ) : parsed.category === 'categoryGroup' ? (
            <CategoryGroupInspectorContent node={node} allNodes={allNodes} t={t} />
          ) : (data as any).fleetResourceType === 'tool' ? (
            <ToolInspectorContent node={node} t={t} />
          ) : (data as any).fleetResourceType === 'model' ? (
            <ModelInspectorContent agentId={parsed.agentId} agents={agents} t={t} />
          ) : (
            <ResourceDetailContent
              node={node}
              resourcesById={resourcesById}
              skillsByAgent={skillsByAgent}
              agentId={parsed.agentId}
              resourceType={(data as any).fleetResourceType || ''}
              resourceId={parsed.resourceId}
              t={t}
            />
          )}
        </div>
      </div>
    </div>
  );
}
