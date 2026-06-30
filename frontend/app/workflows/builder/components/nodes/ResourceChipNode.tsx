'use client';

import React, { useState } from 'react';
import Image from 'next/image';
import { Handle, Position, type NodeProps } from 'reactflow';
import { Wrench, Zap, GitBranch, Layout, Database, Cpu } from 'lucide-react';
import type { BuilderNodeKind } from '../../types';

/**
 * Data shape for ResourceChipNode.
 * Reuses BuilderNodeData fields but the node renders as a compact pill.
 */
export interface ResourceChipData {
  id: string;
  label: string;
  kind: BuilderNodeKind;
  badge?: string;
  resourceType?: 'tool' | 'skill' | 'workflow' | 'interface' | 'table' | 'model';
  /** SVG icon slug for /icons/services/{iconSlug}.svg (used for model provider icons) */
  iconSlug?: string;
}

// Icon + color per resource type
const RESOURCE_STYLES: Record<string, {
  icon: React.ElementType;
  bg: string;
  iconColor: string;
  border: string;
}> = {
  tool: {
    icon: Wrench,
    bg: 'bg-blue-50 dark:bg-blue-950/40',
    iconColor: 'text-blue-500 dark:text-blue-400',
    border: 'border-blue-200 dark:border-blue-800',
  },
  skill: {
    icon: Zap,
    bg: 'bg-amber-50 dark:bg-amber-950/40',
    iconColor: 'text-amber-500 dark:text-amber-400',
    border: 'border-amber-200 dark:border-amber-800',
  },
  workflow: {
    icon: GitBranch,
    bg: 'bg-purple-50 dark:bg-purple-950/40',
    iconColor: 'text-purple-500 dark:text-purple-400',
    border: 'border-purple-200 dark:border-purple-800',
  },
  interface: {
    icon: Layout,
    bg: 'bg-emerald-50 dark:bg-emerald-950/40',
    iconColor: 'text-emerald-500 dark:text-emerald-400',
    border: 'border-emerald-200 dark:border-emerald-800',
  },
  table: {
    icon: Database,
    bg: 'bg-orange-50 dark:bg-orange-950/40',
    iconColor: 'text-orange-500 dark:text-orange-400',
    border: 'border-orange-200 dark:border-orange-800',
  },
  model: {
    icon: Cpu,
    bg: 'bg-slate-50 dark:bg-slate-950/40',
    iconColor: 'text-slate-500 dark:text-slate-400',
    border: 'border-theme',
  },
};

const DEFAULT_STYLE = RESOURCE_STYLES.tool;

/**
 * ResourceChipNode - compact pill-shaped node for the Agent Fleet canvas.
 * Displays a resource (tool, skill, workflow, interface, table) with
 * a small icon and label. Has a top target handle for connection from
 * the parent agent node.
 */
export function ResourceChipNode({ data }: NodeProps<ResourceChipData>) {
  const style = RESOURCE_STYLES[data.resourceType || ''] || DEFAULT_STYLE;
  const Icon = style.icon;
  const [imgError, setImgError] = useState(false);

  return (
    <div
      className={`
        flex items-center gap-1.5 rounded-full px-3 py-1.5
        border ${style.border} ${style.bg}
        shadow-sm hover:shadow-md transition-shadow
        cursor-default select-none
        max-w-[200px]
      `}
    >
      {/* Top target handle */}
      <Handle
        type="target"
        position={Position.Top}
        id="target-top"
        className="!h-2.5 !w-2.5 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
        style={{
          top: -5,
          left: '50%',
          transform: 'translateX(-50%)',
          backgroundColor: 'var(--border-color)',
        }}
      />

      {/* Icon - service image if iconSlug provided, else Lucide icon */}
      {data.iconSlug && !imgError ? (
        <Image
          src={`/icons/services/${data.iconSlug}.svg`}
          alt={data.label}
          width={14}
          height={14}
          className="h-3.5 w-3.5 flex-shrink-0"
          onError={() => setImgError(true)}
        />
      ) : (
        <div className={`flex-shrink-0 ${style.iconColor}`}>
          <Icon className="h-3.5 w-3.5" strokeWidth={2} />
        </div>
      )}

      {/* Label */}
      <span className="text-xs font-medium text-slate-700 dark:text-slate-300 truncate">
        {data.label}
      </span>

      {/* Badge (optional) */}
      {data.badge && (
        <span className="flex-shrink-0 text-[10px] font-medium text-slate-400 dark:text-slate-500">
          {data.badge}
        </span>
      )}
    </div>
  );
}
