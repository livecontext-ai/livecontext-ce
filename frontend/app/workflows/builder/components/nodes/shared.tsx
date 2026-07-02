'use client';

import * as React from 'react';
import clsx from 'clsx';
import Image from 'next/image';
import {
  Copy, Trash2, Eye, EyeOff, Table,
  Network, Bot, Wrench, Workflow,
  MessageSquare, Play, Pencil, CheckCircle,
  HelpCircle, CircleX,
} from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useTheme } from '@/components/ThemeProvider';
import { AvatarDisplay } from '@/components/agents';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';

import { BTN_CLS } from './NodeBottomBar';
import type { NodeVisuals, BuilderNodeData, LoopChildDescriptor, BuilderNodeKind, DerivedNodeStatus } from '../../types';
import { resolveNodeIcon, NODE_ICON_REGISTRY } from '../../data/nodeVisuals';
import { getEffectiveDefaultProvider } from '@/hooks/useModels';
import { getProviderIconSlug } from '@/lib/ai-providers/providerIcons';

export type IconComponent = React.ComponentType<{ className?: string; strokeWidth?: number }>;

/**
 * Centralized border color based on node execution status.
 * All node components should use this instead of local copies.
 *
 * <p>Priority: execution status is authoritative once the node has run.
 * A terminal status (completed / failed / skipped / partial_success) reflects
 * what actually happened, so it wins over {@code hasError} - which is a
 * builder-time configuration warning (missing required param, unresolved
 * reference, etc.) that may be stale or irrelevant on a frozen snapshot
 * (e.g. marketplace preview of a successful run). Before execution (pending /
 * no status), {@code hasError} is still the right signal.
 *
 * <p>When {@code isFrozen} is true (run mode or epoch viewing), {@code hasError}
 * is ignored entirely - config validation never recolors a snapshot. This
 * prevents skipped/pending nodes in a run from showing a red border just
 * because the live plan has unresolved references.
 */
export function getStatusBorderColor(status?: DerivedNodeStatus, hasError?: boolean, isFrozen?: boolean): string {
  // Terminal execution states: trust the run result, ignore config warnings.
  if (status === 'completed') return '#10b981'; // emerald-500
  if (status === 'failed') return '#ef4444'; // red-500
  if (status === 'skipped') return '#94a3b8'; // slate-400
  if (status === 'partial_success') return '#f59e0b'; // amber-500

  // Before/during execution, a config error still colors the border red -
  // but only at builder time. In a frozen run view, the snapshot wins.
  if (hasError && !isFrozen) return '#f87171'; // red-400

  if (!status || status === 'pending') return 'var(--border-color)';
  switch (status) {
    case 'ready':
      return '#22c55e'; // green-500
    case 'running':
      return '#3b82f6'; // blue-500
    case 'awaiting_signal':
      return '#f59e0b'; // amber-500
    default:
      return 'var(--border-color)';
  }
}

/**
 * Extract iconSlug from node data (toolData, apiData, or AI provider)
 * Used to display custom service icons instead of default Lucide icons
 * Note: iconSlug is not in the TypeScript types but is added dynamically at runtime
 */
export function getIconSlug(data: BuilderNodeData | LoopChildDescriptor | { toolData?: { iconSlug?: string }; apiData?: { iconSlug?: string }; provider?: string; id?: string }): string | undefined {
  // Access iconSlug with type assertion since it's not in the TypeScript definitions
  // but is present at runtime
  const toolData = (data as { toolData?: { iconSlug?: string } }).toolData;
  const apiData = (data as { apiData?: { iconSlug?: string } }).apiData;
  const provider = (data as { provider?: string }).provider;
  const nodeId = (data as { id?: string }).id || '';

  // Only Classify and Guardrail nodes use the provider's icon.
  // Agent nodes use their own avatar (agentAvatarUrl) - no provider icon fallback.
  const isClassify = nodeId === 'classify' || nodeId.startsWith('classify-');
  const isGuardrail = nodeId === 'guardrail' || nodeId.startsWith('guardrail-');
  if (isClassify || isGuardrail) {
    const effectiveProvider = provider || getEffectiveDefaultProvider();
    const slug = getProviderIconSlug(effectiveProvider);
    if (slug) return slug;
  }

  return toolData?.iconSlug || apiData?.iconSlug;
}

/**
 * Extract iconUrl from node data (toolData or apiData).
 * Used as fallback when the static SVG icon (from iconSlug) is not available,
 * e.g. custom APIs with user-uploaded icons stored in S3.
 */
export function getIconUrl(data: BuilderNodeData | LoopChildDescriptor | { toolData?: { iconUrl?: string }; apiData?: { iconUrl?: string } }): string | undefined {
  const toolData = (data as { toolData?: { iconUrl?: string } }).toolData;
  const apiData = (data as { apiData?: { iconUrl?: string } }).apiData;
  return toolData?.iconUrl || apiData?.iconUrl;
}

// ============================================
// Workflow Builder Action Icons (for chat activity feed)
// Single source of truth for workflow action icons
// ============================================
export interface WorkflowActionIconConfig {
  icon: IconComponent;
  bgClassName: string;
}

/**
 * Maps workflow builder action names (e.g. 'add_split') to NODE_ICON_REGISTRY keys.
 * The registry is the single source of truth for icons on the canvas; this mapping
 * ensures the activity feed uses the same icons.
 */
const ACTION_TO_REGISTRY_KEY: Record<string, string> = {
  // Triggers
  add_trigger: 'triggers',
  // AI nodes
  add_agent: 'ai-agent',
  add_guardrail: 'guardrail',
  add_classify: 'classify',
  // Flow nodes
  add_decision: 'decision',
  add_switch: 'switch',
  add_loop: 'loop',
  add_split: 'split',
  add_fork: 'fork',
  add_merge: 'merge',
  merge: 'merge',
  add_option: 'option',
  add_aggregate: 'aggregate',
  add_transform: 'transform',
  // Core nodes
  add_wait: 'wait',
  add_exit: 'exit',
  add_response: 'response',
  add_http_request: 'http_request',
  add_download_file: 'download_file',
  add_data_input: 'data_input',
  add_approval: 'user-approval',
  add_interface: 'interface',
  add_code: 'code',
  add_date_time: 'date_time',
  add_xml: 'xml',
  add_compression: 'compression',
  add_rss: 'rss',
  add_convert_to_file: 'convert_to_file',
  add_extract_from_file: 'extract_from_file',
  add_compare_datasets: 'compare_datasets',
  add_sub_workflow: 'sub_workflow',
  add_respond_to_webhook: 'respond_to_webhook',
  add_send_email: 'send_email',
  // Data processing nodes
  add_filter: 'filter',
  add_sort: 'sort',
  add_limit: 'limit',
  add_remove_duplicates: 'remove_duplicates',
  add_summarize: 'summarize',
  add_crypto_jwt: 'crypto_jwt',
  // CRUD / find
  add_find: 'tables-trigger',
  // End / note
  add_end: 'end',
  add_note: 'note',
  // Table / CRUD
  add_table: 'table',
  add_create_row: 'crud-create-row',
  add_insert_row: 'crud-create-row', // backend canonical alias of create_row
  add_create_column: 'crud-create-column',
  add_read_row: 'crud-read-row',
  add_read_rows: 'crud-read-row', // backend canonical (plural)
  add_update_row: 'crud-update-row',
  add_delete_row: 'crud-delete-row',
  add_find_row: 'crud-find-row',
  add_find_rows: 'crud-find-rows',
  // Trigger sub-types (resolved to specific trigger icons)
  add_webhook_trigger: 'webhook-trigger',
  add_schedule_trigger: 'schedule-trigger',
  add_chat_trigger: 'chat-trigger',
  add_form_trigger: 'form-trigger',
  add_manual_trigger: 'manual-trigger',
  // Backend short forms for triggers (WorkflowBuilderToolDefinitionFactory documents
  // type values: form, webhook, schedule, table, manual, chat - agent emits add_<type>)
  add_manual: 'manual-trigger',
  add_webhook: 'webhook-trigger',
  add_schedule: 'schedule-trigger',
  add_chat: 'chat-trigger',
  add_form: 'form-trigger',
  add_error: 'error-trigger',
  add_workflow: 'workflows-trigger', // sub-workflow trigger
  add_table_trigger: 'tables-trigger',
  add_workflow_trigger: 'workflows-trigger',
  // Misc
  add_set_state: 'setState',
  add_set: 'set',
  add_html_extract: 'html_extract',
  add_file_search: 'fileSearch',
};

/** Extract all Tailwind bg-* classes (including dark: variants) from the registry's iconBg string.
 *  e.g. "bg-violet-100 dark:bg-violet-900/30 text-violet-600" → "bg-violet-100 dark:bg-violet-900/30" */
function extractBgClass(iconBg: string): string {
  const matches = iconBg.match(/(?:dark:)?bg-\S+/g);
  return matches ? matches.join(' ') : 'bg-gray-100 dark:bg-gray-800';
}

// Build WORKFLOW_ACTION_ICONS from NODE_ICON_REGISTRY (single source of truth)
const _builtIcons: Record<string, WorkflowActionIconConfig> = {};
for (const [action, registryKey] of Object.entries(ACTION_TO_REGISTRY_KEY)) {
  const entry = NODE_ICON_REGISTRY[registryKey];
  if (entry) {
    _builtIcons[action] = { icon: entry.icon, bgClassName: extractBgClass(entry.iconBg) };
  }
}

export const WORKFLOW_ACTION_ICONS: Record<string, WorkflowActionIconConfig> = {
  ..._builtIcons,
  // MCP nodes - resolved as fallback in resolveNodeIcon(), not in NODE_ICON_REGISTRY
  add_mcp: { icon: Wrench, bgClassName: 'bg-gray-100 dark:bg-gray-800' },
  // Edge / connection actions - not in registry (graph operations, not nodes)
  connect: { icon: Network, bgClassName: 'bg-gray-100 dark:bg-gray-800' },
  add_edge: { icon: Network, bgClassName: 'bg-gray-100 dark:bg-gray-800' },
  disconnect: { icon: Network, bgClassName: 'bg-gray-100 dark:bg-gray-800' },
  // Workflow lifecycle actions
  init: { icon: Workflow, bgClassName: 'bg-blue-100 dark:bg-blue-900/30' },
  finish: { icon: CheckCircle, bgClassName: 'bg-emerald-100 dark:bg-emerald-900/30' },
  validate: { icon: CheckCircle, bgClassName: 'bg-amber-100 dark:bg-amber-900/30' },
  execute: { icon: Play, bgClassName: 'bg-emerald-100 dark:bg-emerald-900/30' },
  modify: { icon: Pencil, bgClassName: 'bg-gray-100 dark:bg-gray-800' },
  remove: { icon: CircleX, bgClassName: 'bg-red-100 dark:bg-red-900/30' },
  help: { icon: HelpCircle, bgClassName: 'bg-gray-100 dark:bg-gray-800' },
};

/**
 * Get workflow action icon config for chat activity feed.
 * Returns icon component and background class for a workflow action.
 */
export function getWorkflowActionIcon(action: string): WorkflowActionIconConfig | null {
  if (!action) return null;
  return WORKFLOW_ACTION_ICONS[action] || WORKFLOW_FALLBACK_ICON;
}

/**
 * Render workflow action icon for chat activity feed.
 * Size: 18x18 container, 12x12 icon (similar to NodeIcon sm size).
 * Icons are black for consistency.
 */
const WORKFLOW_FALLBACK_ICON: WorkflowActionIconConfig = { icon: Workflow, bgClassName: 'bg-gray-100 dark:bg-gray-800' };

export function WorkflowActionIcon({ action }: { action: string }): React.ReactElement | null {
  if (!action) return null;
  const config = WORKFLOW_ACTION_ICONS[action] || WORKFLOW_FALLBACK_ICON;

  const IconComponent = config.icon;
  return (
    <span className={`flex items-center justify-center w-[18px] h-[18px] rounded-full shrink-0 ${config.bgClassName}`}>
      <IconComponent className="w-3 h-3 text-slate-900 dark:text-slate-100" strokeWidth={2} />
    </span>
  );
}

/**
 * Get icon for a node based on its ID, kind, and family.
 * Delegates to the centralized NODE_ICON_REGISTRY in nodeVisuals.ts.
 */
export function getNodeIconComponent(
  nodeId: string,
  nodeKind?: BuilderNodeKind,
  nodeFamily?: string
): IconComponent {
  return resolveNodeIcon(nodeId, nodeKind, nodeFamily).icon;
}

// ============================================
// NodeIcon - Centralized icon component
// ============================================

export type NodeIconSize = 'xs' | 'sm' | 'md' | 'lg';

const SIZE_CONFIG: Record<NodeIconSize, { container: string; icon: string; image: number }> = {
  xs: { container: 'h-6 w-6', icon: 'h-3.5 w-3.5', image: 16 },
  sm: { container: 'h-8 w-8', icon: 'h-5 w-5', image: 20 },
  md: { container: 'h-9 w-9', icon: 'h-6 w-6', image: 24 },
  lg: { container: 'h-11 w-11', icon: 'h-5 w-5', image: 28 },
};

export interface NodeIconProps {
  /** Service icon slug (e.g., 'openai', 'github') */
  iconSlug?: string | null;
  /** Dynamic icon URL (e.g., S3 proxy URL for custom API icons) - used as fallback when static SVG not found */
  iconUrl?: string | null;
  /** Fallback Lucide icon component */
  fallbackIcon?: IconComponent;
  /** Node ID to auto-detect icons and backgrounds */
  nodeId?: string;
  /** Node kind for determining icon and colors */
  nodeKind?: BuilderNodeKind;
  /** Node family for additional context (loop, condition, trigger, etc.) */
  nodeFamily?: string;
  /** Size variant */
  size?: NodeIconSize;
  /** Background class (overrides auto-detection) */
  bgClassName?: string;
  /** Whether this is an MCP node (shows MCP logo as fallback) */
  isMcp?: boolean;
  /** Agent avatar URL (preset or custom) - takes priority over iconSlug */
  avatarUrl?: string;
  /** Alt text for the icon */
  alt?: string;
  /** Additional class names for the container */
  className?: string;
}

/**
 * Centralized icon component for workflow nodes.
 * Handles service icons, trigger icons, CRUD icons, and MCP fallback.
 */
export function NodeIcon({
  iconSlug,
  iconUrl,
  fallbackIcon,
  nodeId = '',
  nodeKind,
  nodeFamily,
  size = 'lg',
  bgClassName,
  isMcp = false,
  avatarUrl,
  alt = '',
  className,
}: NodeIconProps) {
  const { theme } = useTheme();
  const isDark = theme === 'dark';
  const [imageError, setImageError] = React.useState(false);
  // Dynamic node icon (custom-API icon stored in object storage) - fetched with a
  // Bearer header and rendered from an in-memory blob: URL (no token in the URL).
  // A blob:/external/data URL passes through unchanged. See useAuthedObjectUrl.
  const { url: authIconUrl, error: iconUrlError } = useAuthedObjectUrl(iconUrl || null);

  const sizeConfig = SIZE_CONFIG[size];

  // Resolve icon + background from the centralized registry
  const resolved = React.useMemo(
    () => resolveNodeIcon(nodeId, nodeKind, nodeFamily),
    [nodeId, nodeKind, nodeFamily]
  );

  const IconToRender = fallbackIcon || resolved.icon;

  // Determine background class - no background for service icons
  const effectiveBgClassName = React.useMemo(() => {
    const hasVisibleIcon = (iconSlug && !imageError) || (iconUrl && !iconUrlError && authIconUrl);
    if (hasVisibleIcon) return 'rounded-full p-0.5 dark:bg-slate-100/10';
    if (bgClassName) return `${bgClassName} rounded-full overflow-hidden`;
    return `${resolved.iconBg} rounded-full overflow-hidden`;
  }, [bgClassName, iconSlug, iconUrl, imageError, iconUrlError, authIconUrl, resolved.iconBg]);

  // Handle image error - show fallback icon
  const handleImageError = React.useCallback(() => {
    setImageError(true);
  }, []);

  // Reset error state when iconSlug changes
  React.useEffect(() => {
    setImageError(false);
  }, [iconSlug]);

  // Agent avatar takes priority over everything else
  if (avatarUrl) {
    const avatarSize = size === 'lg' ? 'lg' : size === 'md' ? 'md' : 'sm';
    return (
      <AvatarDisplay avatarUrl={avatarUrl} name={alt} size={avatarSize} className={clsx(sizeConfig.container, 'flex-shrink-0', className)} />
    );
  }

  // Auto-detect MCP nodes from nodeId/nodeKind (centralized detection)
  const isEffectivelyMcp = isMcp || nodeId.startsWith('mcp-') || nodeKind === 'tool' || nodeKind === 'mcp';

  // Render service icon (from iconSlug)
  const shouldShowServiceIcon = iconSlug && !imageError;

  // Render dynamic icon URL (custom API icons from S3) - fallback when static SVG not found
  const shouldShowDynamicIcon = !shouldShowServiceIcon && authIconUrl && !iconUrlError;

  // Render MCP logo (when MCP node and no service/dynamic icon)
  const shouldShowMcpFallback = isEffectivelyMcp && !shouldShowServiceIcon && !shouldShowDynamicIcon;

  return (
    <div
      className={clsx(
        'flex items-center justify-center flex-shrink-0',
        sizeConfig.container,
        effectiveBgClassName,
        className
      )}
    >
      {shouldShowServiceIcon ? (
        <Image
          src={`/icons/services/${iconSlug}.svg`}
          alt={alt || iconSlug || ''}
          width={sizeConfig.image}
          height={sizeConfig.image}
          style={{ width: sizeConfig.image, height: sizeConfig.image }}
          onError={handleImageError}
        />
      ) : shouldShowDynamicIcon ? (
        /* eslint-disable-next-line @next/next/no-img-element */
        <img
          src={authIconUrl}
          alt={alt || 'Custom API icon'}
          width={sizeConfig.image}
          height={sizeConfig.image}
          style={{ width: sizeConfig.image, height: sizeConfig.image, objectFit: 'contain' }}
        />
      ) : shouldShowMcpFallback ? (
        <Image
          src={isDark ? '/mcp.png' : '/mcp_black.png'}
          alt="MCP"
          width={sizeConfig.image}
          height={sizeConfig.image}
          style={{ width: sizeConfig.image, height: sizeConfig.image }}
        />
      ) : (
        <IconToRender
          className={clsx(sizeConfig.icon, 'text-slate-900 dark:text-slate-100')}
          strokeWidth={1.7}
        />
      )}
    </div>
  );
}

export function useHoverVisibility<T extends HTMLElement>(delay = 500) {
  const [isVisible, setIsVisible] = React.useState(false);
  const targetRef = React.useRef<T>(null);
  const timeoutRef = React.useRef<ReturnType<typeof setTimeout> | null>(null);

  const show = React.useCallback(() => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
    setIsVisible(true);
  }, []);

  const hide = React.useCallback(() => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
    }
    timeoutRef.current = setTimeout(() => setIsVisible(false), delay);
  }, [delay]);

  React.useEffect(() => {
    const target = targetRef.current;
    if (!target) return undefined;

    target.addEventListener('mouseenter', show);
    target.addEventListener('mouseleave', hide);

    return () => {
      target.removeEventListener('mouseenter', show);
      target.removeEventListener('mouseleave', hide);
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, [show, hide]);

  return { targetRef, isVisible, show, hide };
}

interface NodeActionButtonsProps {
  isVisible: boolean;
  onDelete?: () => void;
  onDuplicate?: () => void;
  onTogglePreview?: () => void;
  isPreviewMode?: boolean;
  showPreviewButton?: boolean;
  onHover?: () => void;
  // View data button for table nodes
  onViewData?: () => void;
  // View conversation button for memory nodes
  onViewConversation?: () => void;
}

/**
 * Hover-only action buttons rendered below the node (delete / duplicate / preview / view),
 * with the same round-button style as the persistent {@link NodeBottomBar} buttons.
 *
 * <p>Nodes that render an edit-mode {@link NodeBottomBar} (FlowNode, WorkflowNode,
 * BrowserAgentNode) pass `hoverActions` to the bar instead, so the hover buttons
 * join the persistent row without overlapping it.
 *
 * <p>The play button used to live here too, but it moved to the bottom bar so
 * triggers can expose an Auto / Step-by-step launcher menu and non-trigger
 * nodes no longer carry a disabled greyed-out button. See
 * {@link TriggerEditLaunchButton} and {@link NodeBottomBar}.
 */
export function NodeActionButtons({
  isVisible,
  onDelete,
  onDuplicate,
  onTogglePreview,
  isPreviewMode = false,
  showPreviewButton = false,
  onHover,
  onViewData,
  onViewConversation,
}: NodeActionButtonsProps) {
  const t = useTranslations('workflowBuilder.nodes');
  const { isRunMode, isPreviewOnly } = useWorkflowMode();

  // In run or preview-only mode hide the edit buttons (delete, duplicate, preview).
  const showEditButtons = !isRunMode && !isPreviewOnly;

  if (!onDelete && !onDuplicate && !onTogglePreview && !onViewData && !onViewConversation) {
    return null;
  }
  if (!showEditButtons && !onViewData && !onViewConversation) {
    return null;
  }

  // For preview button, only show when hovering like other nodes
  const shouldShowPreviewButton = showPreviewButton && onTogglePreview;

  // Buttons (including preview) are shown on hover, like other nodes
  const finalOpacity = isVisible ? 1 : 0;
  const finalPointerEvents = isVisible ? 'all' : 'none';

  // Same round-button look as the persistent NodeBottomBar buttons.
  const btnCls = `${BTN_CLS} nodrag nopan`;
  const btnStyle = { borderWidth: 2, borderStyle: 'solid' as const, borderColor: 'var(--border-color)' };

  return (
    <div
      className="absolute left-1/2 pointer-events-none transition-opacity duration-200 ease-out flex flex-row gap-1.5"
      style={{
        opacity: finalOpacity,
        pointerEvents: finalPointerEvents,
        top: 'calc(100% + 8px)',
        transform: 'translateX(-50%)',
        zIndex: 10,
      }}
      onMouseEnter={onHover}
    >
      {showEditButtons && onDelete ? (
        <button
          onClick={(e) => {
            e.stopPropagation();
            onDelete();
          }}
          className={btnCls}
          style={btnStyle}
          title={t('deleteNode')}
        >
          <span className="relative z-10"><Trash2 className="h-3 w-3" strokeWidth={2} /></span>
        </button>
      ) : null}
      {showEditButtons && onDuplicate ? (
        <button
          onClick={(e) => {
            e.stopPropagation();
            onDuplicate();
          }}
          className={btnCls}
          style={btnStyle}
          title={t('duplicateNode')}
        >
          <span className="relative z-10"><Copy className="h-3 w-3" strokeWidth={2} /></span>
        </button>
      ) : null}
      {showEditButtons && shouldShowPreviewButton ? (
        <button
          onClick={(e) => {
            e.stopPropagation();
            onTogglePreview();
          }}
          className={btnCls}
          style={btnStyle}
          title={isPreviewMode ? t('showNormalView') : t('showPreview')}
        >
          <span className="relative z-10">
            {isPreviewMode ? (
              <EyeOff className="h-3 w-3" strokeWidth={2} />
            ) : (
              <Eye className="h-3 w-3" strokeWidth={2} />
            )}
          </span>
        </button>
      ) : null}
      {/* View data button for table nodes */}
      {onViewData && (
        <button
          onClick={(e) => {
            e.stopPropagation();
            onViewData();
          }}
          className={btnCls}
          style={btnStyle}
          title={t('viewTable')}
        >
          <span className="relative z-10"><Table className="h-3 w-3" strokeWidth={2} /></span>
        </button>
      )}
      {/* View conversation button for memory nodes */}
      {onViewConversation && (
        <button
          onClick={(e) => {
            e.stopPropagation();
            onViewConversation();
          }}
          className={btnCls}
          style={btnStyle}
          title={t('viewConversation')}
        >
          <span className="relative z-10"><MessageSquare className="h-3 w-3" strokeWidth={2} /></span>
        </button>
      )}
    </div>
  );
}

interface NodeHeaderProps {
  visuals: NodeVisuals;
  label: string;
  iconSlug?: string;
  iconUrl?: string; // Dynamic icon URL (S3 proxy) for custom API icons
  customIcon?: IconComponent; // Custom icon to override the default
  customIconBg?: string; // Custom background color for the icon
  nodeId?: string; // Node ID for auto-detecting icons
  nodeKind?: BuilderNodeKind; // Node kind for additional context
  nodeFamily?: string; // Node family for icon detection (loop, condition, etc.)
  avatarUrl?: string; // Agent avatar URL - replaces NodeIcon when provided
}

export function NodeHeader({
  visuals,
  label,
  iconSlug,
  iconUrl,
  customIcon,
  customIconBg,
  nodeId,
  nodeKind,
  nodeFamily,
  avatarUrl,
}: NodeHeaderProps) {
  // Detect if it's a MCP/tool node (by family, not color)
  const isMcpNode = nodeFamily === 'mcp' || nodeFamily === 'mcp-tool' || nodeFamily === 'mcp-resource' || nodeKind === 'tool' || nodeKind === 'mcp';

  return (
    <div className="flex items-center gap-3 min-w-0">
      {avatarUrl ? (
        <AvatarDisplay avatarUrl={avatarUrl} name={label} size="lg" className="w-11 h-11 flex-shrink-0" />
      ) : (
        <NodeIcon
          iconSlug={iconSlug}
          iconUrl={iconUrl}
          fallbackIcon={customIcon}
          nodeId={nodeId}
          nodeKind={nodeKind}
          nodeFamily={nodeFamily}
          bgClassName={customIconBg}
          isMcp={isMcpNode && !customIcon}
          alt={label}
          size="lg"
        />
      )}

      <div className="flex-1 min-w-0 overflow-hidden">
        <p className="text-sm text-slate-900 dark:text-slate-100 truncate">{label}</p>
      </div>
    </div>
  );
}

/**
 * Green "focused and playable" scan overlay for step-by-step READY nodes.
 * Same visual language as the blue running shimmer (shimmer-scan keyframes in
 * globals.css); the green matches the ready border (#22c55e) and the play
 * button's ready shimmer, so the whole node reads as "you can execute this one
 * now". Callers pass the same positioning classes their running shimmer uses.
 */
export function ReadyShimmerOverlay({ className }: { className: string }) {
  return (
    <div
      data-testid="ready-shimmer"
      className={className}
      style={{
        background: 'linear-gradient(90deg, transparent 0%, rgba(34, 197, 94, 0.15) 50%, transparent 100%)',
        backgroundSize: '200% 100%',
        animation: 'shimmer-scan 2.5s ease-in-out infinite',
      }}
    />
  );
}
