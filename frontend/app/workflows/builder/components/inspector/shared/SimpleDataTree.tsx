'use client';

import React, { useState } from 'react';
import clsx from 'clsx';
import { ChevronRight } from 'lucide-react';
import { getFieldTypeColor } from '../../../types';

interface SimpleDataTreeProps {
  data: unknown;
  maxDepth?: number;
  initialExpanded?: boolean;
}

/**
 * Simple tree component for displaying raw JSON data.
 * Unlike LazyStructureTree, this renders data directly without fetching from an API.
 */
export function SimpleDataTree({
  data,
  maxDepth = 10,
  initialExpanded = true
}: SimpleDataTreeProps) {
  if (data === null || data === undefined) {
    return (
      <span className="text-sm text-muted-foreground italic">null</span>
    );
  }

  return (
    <div className="w-full">
      <DataNode value={data} depth={0} maxDepth={maxDepth} initialExpanded={initialExpanded} />
    </div>
  );
}

interface DataNodeProps {
  label?: string;
  value: unknown;
  depth: number;
  maxDepth: number;
  initialExpanded: boolean;
}

function DataNode({ label, value, depth, maxDepth, initialExpanded }: DataNodeProps) {
  const [isExpanded, setIsExpanded] = useState(initialExpanded && depth < 2);

  const type = getValueType(value);
  const isExpandable = type === 'object' || type === 'array';
  const canExpand = isExpandable && depth < maxDepth;

  const renderValue = () => {
    if (type === 'string') {
      const strValue = value as string;
      if (strValue.length > 100) {
        return (
          <span className="text-sm text-emerald-600 dark:text-emerald-400 break-all">
            "{strValue.substring(0, 100)}..."
          </span>
        );
      }
      return <span className="text-sm text-emerald-600 dark:text-emerald-400">"{strValue}"</span>;
    }
    if (type === 'number') {
      return <span className="text-sm text-blue-600 dark:text-blue-400">{String(value)}</span>;
    }
    if (type === 'boolean') {
      return <span className="text-sm text-purple-600 dark:text-purple-400">{String(value)}</span>;
    }
    if (type === 'null') {
      return <span className="text-sm text-muted-foreground italic">null</span>;
    }
    if (type === 'array') {
      const arr = value as unknown[];
      if (!isExpanded) {
        return <span className="text-sm text-muted-foreground">[{arr.length} items]</span>;
      }
      return null;
    }
    if (type === 'object') {
      const obj = value as Record<string, unknown>;
      const keys = Object.keys(obj);
      if (!isExpanded) {
        return <span className="text-sm text-muted-foreground">{`{${keys.length} keys}`}</span>;
      }
      return null;
    }
    return <span className="text-sm">{String(value)}</span>;
  };

  const renderChildren = () => {
    if (!canExpand || !isExpanded) return null;

    if (type === 'array') {
      const arr = value as unknown[];
      return (
        <div className="pl-3 border-l border-slate-200 dark:border-slate-700 space-y-1 mt-1">
          {arr.map((item, index) => (
            <DataNode
              key={index}
              label={`[${index}]`}
              value={item}
              depth={depth + 1}
              maxDepth={maxDepth}
              initialExpanded={initialExpanded}
            />
          ))}
        </div>
      );
    }

    if (type === 'object') {
      const obj = value as Record<string, unknown>;
      return (
        <div className="pl-3 border-l border-slate-200 dark:border-slate-700 space-y-1 mt-1">
          {Object.entries(obj).map(([key, val]) => (
            <DataNode
              key={key}
              label={key}
              value={val}
              depth={depth + 1}
              maxDepth={maxDepth}
              initialExpanded={initialExpanded}
            />
          ))}
        </div>
      );
    }

    return null;
  };

  return (
    <div className="flex flex-col">
      <div
        className={clsx(
          "flex items-center gap-2 text-sm px-1 py-0.5 rounded-sm",
          canExpand && "cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800"
        )}
        onClick={() => canExpand && setIsExpanded(!isExpanded)}
      >
        {canExpand && (
          <ChevronRight
            className={clsx(
              "h-3 w-3 text-slate-400 dark:text-slate-500 transition-transform flex-shrink-0",
              isExpanded && "rotate-90"
            )}
          />
        )}
        {!canExpand && <span className="w-3" />}
        {label && (
          <span className="text-sm font-medium text-slate-700 dark:text-slate-300">
            {label}:
          </span>
        )}
        {renderValue()}
        <span
          className={clsx(
            "text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0 ml-auto",
            getFieldTypeColor(type)
          )}
        >
          {type}
        </span>
      </div>
      {renderChildren()}
    </div>
  );
}

function getValueType(value: unknown): string {
  if (value === null) return 'null';
  if (value === undefined) return 'null';
  if (Array.isArray(value)) return 'array';
  const type = typeof value;
  if (type === 'object') return 'object';
  if (type === 'string') return 'text';
  if (type === 'number') return 'number';
  if (type === 'boolean') return 'boolean';
  return 'unknown';
}

export default SimpleDataTree;
