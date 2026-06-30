'use client';

import React, { useMemo } from 'react';
import { NodeIcon, type NodeIconProps } from '@/app/workflows/builder/components/nodes/shared';
import type { NodeIconData } from '@/lib/api/orchestrator/types';

const DEFAULT_MAX_DISPLAY = 5;

type Size = 'inline' | 'compact' | 'default';

/**
 * Single-source-of-truth size config. The `iconSize` value here is the
 * `NodeIcon` `size` prop, which drives `SIZE_CONFIG` in
 * `app/workflows/builder/components/nodes/shared.tsx` - the bubble
 * dimensions must match its `container` mapping or the icon will visually
 * overflow the wrapper. Right now NodeIcon's `xs` is `h-6 w-6`, so the
 * inline wrapper is also `h-6 w-6`.
 */
const SIZE_PRESETS: Record<Size, {
  bubble: string;
  gap: string;
  iconSize: 'xs' | 'sm';
  overflowText: string;
}> = {
  inline: {
    bubble: 'h-6 w-6 rounded-full bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 shadow-sm',
    gap: 'gap-1',
    iconSize: 'xs',
    overflowText: 'text-[10px] font-medium text-slate-500 dark:text-slate-400',
  },
  compact: {
    bubble: 'h-7 w-7 rounded-full bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 shadow-sm',
    gap: 'gap-1.5',
    iconSize: 'xs',
    overflowText: 'text-xs font-medium text-slate-500 dark:text-slate-400',
  },
  default: {
    bubble: 'h-10 w-10 rounded-full bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 shadow-sm',
    gap: 'gap-2',
    iconSize: 'sm',
    overflowText: 'text-xs font-medium text-slate-500 dark:text-slate-400',
  },
};

interface WorkflowNodeIconsProps {
  nodeIcons?: NodeIconData[];
  /** Total node count from the plan - used to show "+N" for remaining nodes beyond displayed icons */
  totalNodeCount?: number;
  /**
   * One of: `default` (h-10), `compact` (h-7, board cards), `inline` (h-6,
   * publisher-row badges). Defaults to `default`.
   */
  size?: Size;
  /** @deprecated use {@link WorkflowNodeIconsProps.size} = 'compact' */
  compact?: boolean;
  /** Max icons to render (defaults to 5) */
  maxDisplay?: number;
  /**
   * When true, re-sort icons so MCP nodes come first, then triggers, then
   * the rest. Used on marketplace cards where the user cares most about
   * which integrations + entry points the workflow uses.
   */
  prioritizeMcpAndTriggers?: boolean;
  className?: string;
}

/** Order: mcp → trigger (entry) → everything else, preserving original within groups. */
function reorderForMarketplace(icons: NodeIconData[]): NodeIconData[] {
  const mcp: NodeIconData[] = [];
  const trig: NodeIconData[] = [];
  const rest: NodeIconData[] = [];
  for (const i of icons) {
    if ((i as { isMcp?: boolean }).isMcp) mcp.push(i);
    else if (i.nodeKind === 'entry') trig.push(i);
    else rest.push(i);
  }
  return [...mcp, ...trig, ...rest];
}

/** Best-effort human label for the bubble tooltip. */
function iconLabel(icon: NodeIconData): string {
  const slug = (icon.iconSlug as string | undefined);
  if (slug) return slug;
  const nodeId = (icon.nodeId as string | undefined);
  if (nodeId) return nodeId;
  if (icon.nodeKind) return icon.nodeKind;
  return 'node';
}

/**
 * Renders a row of node icons from pre-computed NodeIcon props.
 * Each entry is wrapped in a visible bubble and spread into the builder's NodeIcon component,
 * reusing the same icon resolution, service images, and dark mode support.
 */
export function WorkflowNodeIcons({
  nodeIcons,
  totalNodeCount,
  size,
  compact,
  maxDisplay,
  prioritizeMcpAndTriggers,
  className = '',
}: WorkflowNodeIconsProps) {
  const ordered = useMemo(() => {
    if (!nodeIcons || nodeIcons.length === 0) return [];
    return prioritizeMcpAndTriggers ? reorderForMarketplace(nodeIcons) : nodeIcons;
  }, [nodeIcons, prioritizeMcpAndTriggers]);

  if (ordered.length === 0) return null;

  const effectiveSize: Size = size ?? (compact ? 'compact' : 'default');
  const preset = SIZE_PRESETS[effectiveSize];

  const cap = maxDisplay ?? DEFAULT_MAX_DISPLAY;
  const displayed = ordered.slice(0, cap);
  // When the caller passes totalNodeCount, the "+N" reflects the full plan
  // (e.g. "+18" for a 23-node workflow when 5 icons cap the row). Without
  // totalNodeCount the badge reflects de-duplicated icon count only -
  // appropriate for marketplace surfaces that care about distinct
  // integrations rather than raw node count.
  const overflow = totalNodeCount != null
    ? totalNodeCount - displayed.length
    : ordered.length - cap;

  return (
    <div className={`flex items-center ${preset.gap} ${className}`}>
      {displayed.map((icon, i) => (
        <span
          key={`${icon.nodeId || icon.iconSlug || 'icon'}-${i}`}
          className={`flex items-center justify-center ${preset.bubble}`}
          title={iconLabel(icon)}
        >
          <NodeIcon
            {...icon as NodeIconProps}
            size={preset.iconSize}
          />
        </span>
      ))}
      {overflow > 0 && (
        <span
          className={`flex items-center justify-center ${preset.bubble} ${preset.overflowText}`}
          title={`+${overflow} more`}
        >
          +{overflow}
        </span>
      )}
    </div>
  );
}
