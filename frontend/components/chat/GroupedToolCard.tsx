'use client';

import React, { useState, useCallback, useRef, useEffect, useMemo } from 'react';
import { useExpandedState } from '@/hooks/useExpandedState';
import { createPortal } from 'react-dom';
import { useTranslations } from 'next-intl';
import Image from 'next/image';
import { ChevronDown, ChevronRight, Table, Monitor, Workflow, Bot, Loader2, Search, Globe, HelpCircle, KeyRound, ListChecks, Eye, Code, Plug, Play, Pencil, FolderOpen, Terminal, FileText, ExternalLink, Trash2, MoreVertical, ArrowUpRight } from 'lucide-react';
import { WorkflowActionIcon, getWorkflowActionIcon } from '@/app/workflows/builder/components/nodes/shared';
import { resolveNodeIcon } from '@/app/workflows/builder/data/nodeVisuals';
import { TasksPreviewBlock } from './TasksPreviewBlock';
import DiffView from './DiffView';
import GitStatusView from './GitStatusView';
import { CredentialCard } from './CredentialCard';
import { ConfirmDeleteModal } from './ConfirmDeleteModal';
import { AgentBrowseLivePreview } from './AgentBrowseLivePreview';
import { ToolResultFileRefPreviews } from './ToolResultFileRefPreviews';
import MarkdownRender from '@/components/MarkdownRender';
import { FaviconStack } from '@/components/ui/FaviconStack';
import { apiClient, orchestratorApi } from '@/lib/api';
import { useResourceQuery } from '@/lib/hooks/useResourceQuery';
import { extractWebSearchUrls } from '@/lib/utils/extractWebSearchUrls';
import type { ToolActivity } from './ActivityFeed';
import { normalizeIconSlug } from '@/lib/credentials/iconSlug';
import type { GroupedToolActivity } from '@/lib/utils/activityGrouping';
import { getToolDescription, getToolIconType, getToolIconSlug } from '@/lib/utils/activityGrouping';
import { isOpenableVisualization, toAutoOpenDetail } from '@/lib/chat/messageActivity';

// Tool icon mapping
const toolIcons: Record<string, React.ReactNode> = {
  table: <Table className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  interface: <Monitor className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  workflow: <Workflow className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  agent: <Bot className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  search: <Search className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  globe: <Globe className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  help: <HelpCircle className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  key: <KeyRound className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  tasks: <ListChecks className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  eye: <Eye className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  code: <Code className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  files: <FolderOpen className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  api: <Plug className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  play: <Play className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  pencil: <Pencil className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  // Native Claude Code tools (full agent toolset over the bridge).
  terminal: <Terminal className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
  file: <FileText className="w-3.5 h-3.5 text-theme-muted shrink-0" />,
};

/** Resolve icon for a node label using the centralized NODE_ICON_REGISTRY. */
function NodeLabelIcon({ label }: { label: string }): React.ReactElement {
  const { icon: IconComponent, iconBg } = resolveNodeIcon(label);
  const bgMatch = iconBg.match(/(?:dark:)?bg-\S+/g);
  const bgClass = bgMatch ? bgMatch.join(' ') : 'bg-gray-100 dark:bg-gray-800';
  return (
    <span className={`flex items-center justify-center w-[18px] h-[18px] rounded-full shrink-0 ${bgClass}`}>
      <IconComponent className="w-3 h-3 text-slate-900 dark:text-slate-100" strokeWidth={2} />
    </span>
  );
}



// For a connect/disconnect action, extract the from/to labels.
function getConnectEndpoints(toolName: string, args?: string): { fromLabel: string; toLabel: string } | null {
  if (toolName.toLowerCase() !== 'workflow' || !args) return null;
  try {
    let parsed = JSON.parse(args);
    if (parsed.raw && typeof parsed.raw === 'string') {
      try { parsed = JSON.parse(parsed.raw); } catch {}
    }
    if (parsed.action !== 'connect' && parsed.action !== 'disconnect') return null;
    const from = typeof parsed.from === 'string' ? parsed.from : '';
    const to = typeof parsed.to === 'string' ? parsed.to : '';
    if (!from && !to) return null;
    return { fromLabel: from, toLabel: to };
  } catch {
    return null;
  }
}

// Get workflow action from tool arguments.
// Resolves the unified `add_node` action to `add_{type}` so WORKFLOW_ACTION_ICONS can match.
function getWorkflowAction(toolName: string, args?: string): string | null {
  if (toolName.toLowerCase() !== 'workflow' || !args) return null;
  try {
    let parsed = JSON.parse(args);
    if (parsed.raw && typeof parsed.raw === 'string') {
      try { parsed = JSON.parse(parsed.raw); } catch {}
    }
    const action = parsed.action;
    if (!action) return null;
    if (action === 'add_node' && parsed.type && typeof parsed.type === 'string') {
      return `add_${parsed.type}`;
    }
    return action;
  } catch {
    return null;
  }
}

// For modify/remove, extract the target node label so we can show the node's icon.
function getWorkflowTargetNodeLabel(toolName: string, args?: string): string | null {
  if (toolName.toLowerCase() !== 'workflow' || !args) return null;
  try {
    let parsed = JSON.parse(args);
    if (parsed.raw && typeof parsed.raw === 'string') {
      try { parsed = JSON.parse(parsed.raw); } catch {}
    }
    if (parsed.action === 'modify' || parsed.action === 'remove') {
      return typeof parsed.node === 'string' ? parsed.node : null;
    }
    return null;
  } catch {
    return null;
  }
}

// Loading skeleton component
function LoadingSkeleton() {
  return (
    <div className="space-y-2 animate-pulse">
      <div className="h-3 bg-slate-200 dark:bg-slate-700 rounded w-3/4" />
      <div className="h-3 bg-slate-200 dark:bg-slate-700 rounded w-1/2" />
      <div className="h-3 bg-slate-200 dark:bg-slate-700 rounded w-2/3" />
    </div>
  );
}

// Service approval preview (inline display for historical messages)
// Shows which services were requested - no status tracking (status is on Conversation.approvedServices)
interface ServiceApprovalPreviewProps {
  serviceApproval: {
    services: Array<{
      serviceType: string;
      serviceName: string;
      iconSlug?: string;
      toolName?: string;
      toolId?: string;
      description?: string;
    }>;
    reason?: string;
  };
}

function ServiceApprovalPreview({ serviceApproval }: ServiceApprovalPreviewProps) {
  const { services, reason } = serviceApproval;
  const t = useTranslations('serviceApproval');
  const [imageErrors, setImageErrors] = useState<Record<string, boolean>>({});

  const handleImageError = (serviceType: string) => {
    setImageErrors(prev => ({ ...prev, [serviceType]: true }));
  };

  return (
    <div className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 p-4">
      {/* Header */}
      <div className="flex items-center gap-3 mb-3">
        <div className="flex items-center justify-center w-8 h-8 rounded-full bg-slate-100 dark:bg-slate-800">
          <KeyRound className="w-4 h-4 text-slate-600 dark:text-slate-400" />
        </div>
        <div>
          <h3 className="text-sm font-semibold text-slate-900 dark:text-slate-100">
            {t('title')}
          </h3>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            {t('subtitle')}
          </p>
        </div>
      </div>

      {/* Services list */}
      <div className="space-y-2">
        {services.map((service, index) => (
          <div
            key={`${service.serviceType}-${index}`}
            className="flex items-center gap-3 p-2.5 rounded-xl bg-slate-50 dark:bg-slate-800/50 border border-slate-100 dark:border-slate-700"
          >
            {/* Service icon */}
            <div className="flex items-center justify-center w-7 h-7 rounded-full bg-white dark:bg-slate-700 border border-slate-200 dark:border-slate-600">
              {service.iconSlug && !imageErrors[service.serviceType] ? (
                <Image
                  src={`/icons/services/${normalizeIconSlug(service.iconSlug)}.svg`}
                  alt={service.serviceName}
                  width={18}
                  height={18}
                  onError={() => handleImageError(service.serviceType)}
                />
              ) : (
                <KeyRound className="w-3.5 h-3.5 text-slate-400" />
              )}
            </div>
            {/* Service info */}
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-slate-900 dark:text-slate-100 truncate">
                {service.serviceName}
              </p>
              {service.toolName && (
                <p className="text-xs text-slate-500 dark:text-slate-400 truncate">
                  {t('serviceNeeded', { service: service.serviceName, tool: service.toolName })}
                </p>
              )}
            </div>
          </div>
        ))}
      </div>

      {/* Reason */}
      {reason && (
        <div className="mt-3 p-2.5 rounded-xl bg-slate-50 dark:bg-slate-800/50">
          <p className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">
            {t('reason')}
          </p>
          <p className="text-sm text-slate-600 dark:text-slate-300">
            {reason}
          </p>
        </div>
      )}
    </div>
  );
}

// Parse badge from description (format: "text ||BADGE||")
function parseBadge(text: string | null): { text: string; badge: string | null } {
  if (!text) return { text: '', badge: null };
  const match = text.match(/^(.+?)\s*\|\|([^|]+)\|\|$/);
  if (match) {
    return { text: match[1].trim(), badge: match[2].trim() };
  }
  return { text, badge: null };
}

interface GroupedToolCardProps {
  group: GroupedToolActivity;
  /** During streaming, items are expanded by default */
  isStreaming?: boolean;
}

export function GroupedToolCard({ group, isStreaming = false }: GroupedToolCardProps) {
  const [isExpanded, toggleExpanded] = useExpandedState(group.id, isStreaming, undefined, undefined, true);
  const { toolName, calls, overallStatus, totalDurationMs } = group;

  const isPending = overallStatus === 'pending';
  const pendingCount = calls.filter(c => c.status === 'pending').length;
  const hasError = calls.some(c => c.status === 'error');
  const hasSuccess = calls.some(c => c.status === 'success');

  // Get icon for tool type (parent only)
  const iconType = getToolIconType(toolName);
  const icon = iconType ? toolIcons[iconType] : null;

  // Status dot color: red only when ALL are errors (no success), otherwise gray
  const dotColor = hasError && !hasSuccess
    ? 'bg-red-500'
    : 'bg-slate-400 dark:bg-slate-500';

  return (
    <div className="relative flex gap-2 pl-[7px] pb-3 w-full min-w-0">
      {/* Status dot */}
      <div className={`absolute left-0 top-[6px] h-1.5 w-1.5 rounded-full ${dotColor}`} />

      {/* Timeline line */}
      <div className="absolute left-[2.5px] top-[14px] bottom-[-12px] w-px bg-slate-200 dark:bg-slate-700" />

      <div className="flex-1 ml-3 min-w-0">
        {/* Header - clickable to collapse, chevron on hover */}
        <button
          onClick={toggleExpanded}
          className="group/parent flex items-center gap-2 w-full text-left"
        >
          {/* Icon for tool type (parent only) */}
          {icon}
          <span className="text-sm text-slate-700 dark:text-slate-200 leading-5">
            {formatToolName(toolName)}
          </span>

          {/* Pending indicator with count */}
          {isPending && (
            <span className="flex items-center gap-1 text-xs text-slate-400">
              <Loader2 className="h-3 w-3 animate-spin" />
              {pendingCount}
            </span>
          )}

          {/* Total duration for group */}
          {!isPending && totalDurationMs > 0 && (
            <span className="text-xs text-slate-400 whitespace-nowrap shrink-0">
              {formatDurationMs(totalDurationMs)}
            </span>
          )}

          {/* Chevron on hover */}
          <div className="shrink-0 opacity-0 group-hover/parent:opacity-100 transition-opacity">
            {isExpanded ? (
              <ChevronDown className="h-3.5 w-3.5 text-slate-400" />
            ) : (
              <ChevronRight className="h-3.5 w-3.5 text-slate-400" />
            )}
          </div>
        </button>

        {/* Sub-items */}
        {isExpanded && (
        <div className="mt-2 pl-1 min-w-0">
          {calls.map((call, index) => (
            <CallTimelineItem
              key={call.id}
              call={call}
              index={index}
              isStreaming={isStreaming}
            />
          ))}
        </div>
        )}
      </div>
    </div>
  );
}

interface CallTimelineItemProps {
  call: ToolActivity;
  index: number;
  /** During streaming, items are expanded by default */
  isStreaming?: boolean;
}

interface FullToolResult {
  id: string;
  toolName: string;
  success: boolean;
  durationMs: number;
  content: string;
  error: string;
  createdAt: string;
}

function CallTimelineItem({ call, index, isStreaming = false }: CallTimelineItemProps) {
  const t = useTranslations('workflow.draft');
  const tChat = useTranslations('chat');
  // Extract action for workflow to check against TOOLS_EXPANDED_BY_DEFAULT allowlist
  const workflowAction = getWorkflowAction(call.toolName, call.arguments);
  // Pass toolName and action to check against TOOLS_EXPANDED_BY_DEFAULT allowlist
  const [isExpanded, toggleExpanded] = useExpandedState(call.id, isStreaming, call.toolName, workflowAction || undefined);
  const [isDeleting, setIsDeleting] = useState(false);
  const [isDeleted, setIsDeleted] = useState(false);
  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [showMenu, setShowMenu] = useState(false);
  const [menuPosition, setMenuPosition] = useState({ top: 0, left: 0 });
  const [mounted, setMounted] = useState(false);
  const menuButtonRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  // Track mounted state for portal
  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  // Update menu position when opening
  useEffect(() => {
    if (showMenu && menuButtonRef.current) {
      const rect = menuButtonRef.current.getBoundingClientRect();
      setMenuPosition({
        top: rect.bottom + 4,
        left: rect.left,
      });
    }
  }, [showMenu]);

  // Close menu when clicking outside
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (
        menuRef.current && !menuRef.current.contains(event.target as Node) &&
        menuButtonRef.current && !menuButtonRef.current.contains(event.target as Node)
      ) {
        setShowMenu(false);
      }
    }

    if (showMenu) {
      document.addEventListener('mousedown', handleClickOutside);
      return () => document.removeEventListener('mousedown', handleClickOutside);
    }
  }, [showMenu]);

  // Get user-friendly description of the tool action
  const description = getToolDescription(call.toolName, call.arguments, call.visualization, call.result);
  const isPending = call.status === 'pending';
  const isError = call.status === 'error' || !!call.error;
  // Openable resource (workflow/table/interface/application/datasource/agent_browse):
  // surface a ↗ that opens the right side panel, in addition to the inline expand.
  const openable = isOpenableVisualization(call.visualization);
  const tasksData = call.tasksData;
  const hasResult = call.result || call.resultId;

  // Fetch result via React Query - auto-fetches when expanded and no inline content.
  // useResourceQuery disables on 404 (no infinite loop) and deduplicates requests.
  const { data: fetchedResult, isLoading: isLoadingResult, error: loadError } = useResourceQuery<FullToolResult, string>({
    queryKey: ['tool-result', call.resultId || call.toolId || ''],
    queryFn: () => call.resultId
      ? apiClient.get<FullToolResult>(`/tool-results/${call.resultId}`)
      : apiClient.get<FullToolResult>(`/tool-results/by-tool-call/${call.toolId}`),
    enabled: isExpanded && !!hasResult && !call.result && !!(call.resultId || call.toolId),
    select: (data) => data.content || '',
  });

  // Get draft_id from result content
  const getDraftId = useCallback(async (): Promise<string | null> => {
    const resultContent = call.result || fetchedResult;

    if (resultContent) {
      try {
        const parsed = JSON.parse(resultContent);
        if (parsed.draft_id) return parsed.draft_id;
      } catch {
        // Not JSON
      }
    }

    // If not in result, fetch it
    if (call.resultId) {
      try {
        const response = await apiClient.get<FullToolResult>(`/tool-results/${call.resultId}`);
        if (response.content) {
          const parsed = JSON.parse(response.content);
          return parsed.draft_id || null;
        }
      } catch {
        // Failed to fetch
      }
    }

    return null;
  }, [call.result, call.resultId, fetchedResult]);

  // Handle opening the workflow
  const handleOpenWorkflow = useCallback(async () => {
    const draftId = await getDraftId();
    if (draftId) {
      window.open(`/app/workflow/${draftId}`, '_blank');
    }
  }, [getDraftId]);

  // Handle draft deletion confirmation
  const handleConfirmDelete = useCallback(async () => {
    try {
      setIsDeleting(true);
      const draftId = await getDraftId();

      if (draftId) {
        await orchestratorApi.deleteWorkflow(draftId);
        setIsDeleted(true);
      } else {
        console.error('Could not find draft_id in result');
      }
    } catch (err) {
      console.error('Failed to delete draft:', err);
    } finally {
      setIsDeleting(false);
      setShowDeleteModal(false);
    }
  }, [getDraftId]);

  const displayContent = call.result || fetchedResult;

  // Favicon stack for web_search (search + fetch). Built from arguments while pending,
  // upgraded once the tool result lands. Replaces the old sidepanel preview card.
  // Memoised so the JSON.parse in extractWebSearchUrls doesn't run on every WS chunk.
  const webSearchUrls = useMemo(
    () => extractWebSearchUrls(call.toolName, call.arguments, displayContent),
    [call.toolName, call.arguments, displayContent],
  );

  // Build display name: label (toolName) if label is available, otherwise displayToolName or description
  // Format for workflow add_node: "Send Email (Gmail Send Email)"
  const rawDisplayName = call.label
    ? (call.displayToolName
        ? `${call.label} (${call.displayToolName.replace(/_/g, ' ')})`
        : call.label)
    : (call.displayToolName
        ? call.displayToolName.replace(/_/g, ' ')
        : description);

  // Parse badge from display name (format: "text ||BADGE||")
  const { text: displayName, badge } = parseBadge(rawDisplayName);
  const isWorkflowInit = workflowAction === 'init' && !isDeleted;
  const hasActionIcon = !!(call.iconSlug || getToolIconSlug(call.toolName, call.arguments) || getWorkflowTargetNodeLabel(call.toolName, call.arguments) || (workflowAction && getWorkflowActionIcon(workflowAction)));

  return (
    <div className={`mb-2 min-w-0 ${isStreaming ? 'animate-tool-call-in' : ''}`}>
      {/* Call header - clickable to expand/collapse */}
      <div
        onClick={toggleExpanded}
        className="group/call flex items-center gap-2 text-left w-full cursor-pointer"
      >
        {/* Status dot - hide when a proper icon exists or for tasks */}
        {!tasksData && !hasActionIcon && (
          <div className={`h-1.5 w-1.5 rounded-full flex-shrink-0 ${
            isError ? 'bg-red-500' : 'bg-slate-400 dark:bg-slate-500'
          }`} />
        )}
        {/* Badge label */}
        {badge && !isDeleted && (
          <span className="text-xs px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400 font-medium">
            {badge}
          </span>
        )}
        {/* Workflow init action menu (three dots) */}
        {isWorkflowInit && (
          <button
            ref={menuButtonRef}
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              e.preventDefault();
              setShowMenu(prev => !prev);
            }}
            className="inline-flex items-center justify-center w-5 h-5 rounded-full hover:bg-gray-200 dark:hover:bg-gray-700 transition-colors cursor-pointer"
          >
            <MoreVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400" />
            {showMenu && mounted && createPortal(
              <div
                ref={menuRef}
                className="fixed w-48 bg-theme-primary border border-gray-300/70 dark:border-gray-600/70 rounded-2xl shadow-lg p-2"
                style={{
                  top: `${menuPosition.top}px`,
                  left: `${menuPosition.left}px`,
                  zIndex: 9999,
                }}
              >
                <div className="space-y-1">
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      setShowMenu(false);
                      handleOpenWorkflow();
                    }}
                    className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-theme-primary hover:bg-gray-100 dark:hover:bg-gray-800"
                  >
                    <ExternalLink className="h-4 w-4" />
                    <span className="text-sm">{t('openWorkflow')}</span>
                  </button>
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      setShowMenu(false);
                      setShowDeleteModal(true);
                    }}
                    className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/30"
                  >
                    <Trash2 className="h-4 w-4" />
                    <span className="text-sm">{t('delete')}</span>
                  </button>
                </div>
              </div>,
              document.body
            )}
          </button>
        )}
        {/* API icon from iconSlug (if available) or extracted from arguments */}
        {(call.iconSlug || getToolIconSlug(call.toolName, call.arguments)) ? (
          <Image
            src={`/icons/services/${normalizeIconSlug(call.iconSlug || getToolIconSlug(call.toolName, call.arguments))}.svg`}
            alt=""
            width={14}
            height={14}
            className="shrink-0"
            onError={(e) => {
              e.currentTarget.style.display = 'none';
            }}
          />
        ) : (() => {
          const endpoints = getConnectEndpoints(call.toolName, call.arguments);
          if (endpoints) return null;
          const targetLabel = getWorkflowTargetNodeLabel(call.toolName, call.arguments);
          if (targetLabel) return <NodeLabelIcon label={targetLabel} />;
          return <WorkflowActionIcon action={getWorkflowAction(call.toolName, call.arguments) || ''} />;
        })()}
        {(() => {
          const endpoints = getConnectEndpoints(call.toolName, call.arguments);
          // For connect/disconnect: parse displayName around the arrow and place icons to the
          // right of each endpoint label (e.g. "Download Report 📥 → Wait All ⏸").
          if (endpoints && displayName) {
            const arrowMatch = displayName.match(/^(.*?)\s*(?:→|->|⟶)\s*(.*)$/);
            if (arrowMatch) {
              const [, leftRaw, rightRaw] = arrowMatch;
              // Strip leading verb ("Connect "/"Disconnect ") from the first segment.
              const left = leftRaw.replace(/^(?:connect(?:ed)?|disconnect(?:ed)?)\s*:?\s*/i, '');
              return (
                <span className="text-sm text-slate-700 dark:text-slate-200 flex items-center gap-1 min-w-0 flex-wrap">
                  <NodeLabelIcon label={endpoints.fromLabel} />
                  <span className="truncate">{left}</span>
                  <span className="text-slate-400 shrink-0">→</span>
                  <NodeLabelIcon label={endpoints.toLabel} />
                  <span className="truncate">{rightRaw}</span>
                </span>
              );
            }
          }
          return (
            <span className="text-sm text-slate-700 dark:text-slate-200 truncate min-w-0">
              {displayName || `#${index + 1}`}
            </span>
          );
        })()}
        {/* Show deleted state */}
        {isDeleted && (
          <span className="text-xs px-1.5 py-0.5 rounded bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400 font-medium line-through">
            {t('deleted')}
          </span>
        )}
        {/* Favicon stack - search results / fetched URLs. Pushed to the right of the row. */}
        {webSearchUrls.length > 0 && (
          <FaviconStack urls={webSearchUrls} className="ml-auto" max={4} size={18} />
        )}
        {/* Duration - never wraps to a second line (stays one column). */}
        {!isPending && call.durationMs !== undefined && (
          <span className="text-xs text-slate-400 whitespace-nowrap shrink-0">
            {formatDurationMs(call.durationMs)}
          </span>
        )}
        {/* Collapse/expand chevron (hover-revealed). */}
        <div className="shrink-0 opacity-0 group-hover/call:opacity-100 transition-opacity">
          {isExpanded ? (
            <ChevronDown className="h-3.5 w-3.5 text-slate-400" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5 text-slate-400" />
          )}
        </div>
        {/* Open the produced resource in the right side panel - to the RIGHT of the
            collapse chevron. Does not toggle the inline expand. Has its own
            hover/focus highlight. Only for openable visualizations. */}
        {openable && call.visualization && (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              window.dispatchEvent(
                new CustomEvent('sidePanelAutoOpen', { detail: toAutoOpenDetail(call.visualization!) }),
              );
            }}
            title={tChat('openInSidePanel')}
            className="inline-flex shrink-0 items-center justify-center rounded-md p-0.5 text-slate-400 transition-colors hover:bg-black/10 hover:text-theme-primary focus-visible:bg-black/10 focus-visible:text-theme-primary focus-visible:outline-none dark:hover:bg-white/15 dark:focus-visible:bg-white/15"
          >
            <ArrowUpRight className="h-3.5 w-3.5" />
          </button>
        )}
      </div>

      {/* Delete confirmation modal for workflow init */}
      {isWorkflowInit && (
        <ConfirmDeleteModal
          isOpen={showDeleteModal}
          title={t('deleteTitle')}
          message={t('deleteMessage')}
          onConfirm={handleConfirmDelete}
          onCancel={() => setShowDeleteModal(false)}
          isLoading={isDeleting}
        />
      )}

      {/* Expanded content */}
      {isExpanded && (
        <>
          {/* Show credential card if tool requires credentials */}
          {call.credentialRequired && call.iconSlug && (
            <div className="mt-2 ml-3.5">
              <CredentialCard
                toolId={call.toolId}
                iconSlug={call.iconSlug}
                serviceName={call.displayToolName || call.label}
                className="my-0"
              />
            </div>
          )}
          {/* Live agent_browse session - opens the right-side panel
              with the live CDP canvas. Surfaces the moment
              BrowserSessionLifecycleService publishes the bootstrap
              event (typically 100-300ms after the LLM's tool call,
              well before the agent_browse blocking call returns). */}
          {call.agentBrowseSession && (
            <div className="mt-2 ml-3.5">
              <AgentBrowseLivePreview
                toolId={call.toolId}
                session={call.agentBrowseSession}
                isLive={call.status === 'pending'}
              />
            </div>
          )}
          {/* Show expanded content: tasks, service approval, or text result */}
          {tasksData ? (
            <div className="mt-2 ml-3.5">
              <TasksPreviewBlock tasksData={tasksData} />
            </div>
          ) : call.serviceApproval ? (
            <div className="mt-2 ml-3.5">
              <ServiceApprovalPreview serviceApproval={call.serviceApproval} />
            </div>
          ) : call.diff ? (
            <div className="mt-2 ml-3.5 mr-4 w-[calc(100%-2rem)]">
              <DiffView diff={call.diff} />
            </div>
          ) : call.gitStatus ? (
            <div className="mt-2 ml-3.5 mr-4 w-[calc(100%-2rem)]">
              <GitStatusView status={call.gitStatus} />
            </div>
          ) : (
            <div className="mt-2 ml-3.5 mr-4 max-h-72 overflow-y-auto overflow-x-auto w-[calc(100%-2rem)] border border-slate-200 dark:border-slate-700 rounded-lg p-3">
              {isLoadingResult ? (
                <LoadingSkeleton />
              ) : loadError ? (
                <div className="text-sm text-red-500">Failed to load result</div>
              ) : displayContent ? (
                <>
                  {/* Render any catalog-dehydrated FileRefs (images, audio,
                      video, PDFs, …) as inline previews above the textual
                      result. The textual content itself only carries the
                      FileRef metadata - the binary lives in storage. */}
                  <ToolResultFileRefPreviews rawResult={displayContent} />
                  <div className="text-sm prose-compact [&>*]:max-w-full [&_pre]:max-w-full [&_pre]:overflow-x-auto [&_table]:max-w-full [&_table]:overflow-x-auto [&_table]:block [&_code]:break-all">
                    <MarkdownRender text={formatResultForMarkdown(displayContent)} />
                  </div>
                </>
              ) : (
                <div className="text-sm text-slate-400 italic">No content</div>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}

function formatToolName(name: string): string {
  // Map tool names to display names
  const displayNames: Record<string, string> = {
    datasource: 'Table',
    table: 'Table',
    interface: 'Interface',
    workflow: 'Workflow',
    catalog: 'Catalog',
    agent: 'Agent',
    skill: 'Skill',
    application: 'Application',
    web_search: 'Web Search',
  };
  const normalized = name.toLowerCase();
  if (displayNames[normalized]) {
    return displayNames[normalized];
  }
  return name.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}

function formatDurationMs(ms: number): string {
  if (ms < 1000) return '< 1s';
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins}m ${secs}s`;
}

function formatJson(str: string): string {
  try {
    const parsed = JSON.parse(str);
    return JSON.stringify(parsed, null, 2);
  } catch {
    return str;
  }
}

/**
 * Format result content for markdown display.
 * - Extracts readable message/content from JSON if available
 * - Formats arrays as tables if appropriate
 * - Falls back to JSON code block for complex structures
 */
function formatResultForMarkdown(content: string): string {
  try {
    const parsed = JSON.parse(content);

    // If it's a string, return directly
    if (typeof parsed === 'string') {
      return parsed;
    }

    // Look for common message/content fields
    const messageFields = ['message', 'content', 'result', 'summary', 'description', 'text', 'output'];
    for (const field of messageFields) {
      if (parsed[field] && typeof parsed[field] === 'string') {
        // If there's additional data, append it
        const otherKeys = Object.keys(parsed).filter(k => k !== field && k !== 'display' && k !== 'success');
        if (otherKeys.length > 0) {
          const additionalData = otherKeys.reduce((acc, k) => {
            acc[k] = parsed[k];
            return acc;
          }, {} as Record<string, unknown>);
          return `${parsed[field]}\n\n\`\`\`json\n${JSON.stringify(additionalData, null, 2)}\n\`\`\``;
        }
        return parsed[field];
      }
    }

    // If it's an array, try to format as table
    if (Array.isArray(parsed) && parsed.length > 0) {
      const firstItem = parsed[0];
      if (typeof firstItem === 'object' && firstItem !== null && !Array.isArray(firstItem)) {
        // Array of objects - format as markdown table
        const keys = Object.keys(firstItem).slice(0, 5); // Limit columns
        if (keys.length > 0) {
          const header = `| ${keys.join(' | ')} |`;
          const separator = `| ${keys.map(() => '---').join(' | ')} |`;
          const rows = parsed.slice(0, 10).map(item => {
            return `| ${keys.map(k => String(item[k] ?? '')).join(' | ')} |`;
          });
          const tableMarkdown = [header, separator, ...rows].join('\n');
          if (parsed.length > 10) {
            return `${tableMarkdown}\n\n*...et ${parsed.length - 10} autres lignes*`;
          }
          return tableMarkdown;
        }
      }
    }

    // Fallback: JSON code block
    return `\`\`\`json\n${JSON.stringify(parsed, null, 2)}\n\`\`\``;
  } catch {
    // Not JSON, return as-is (might be markdown or plain text)
    return content;
  }
}

export default GroupedToolCard;
