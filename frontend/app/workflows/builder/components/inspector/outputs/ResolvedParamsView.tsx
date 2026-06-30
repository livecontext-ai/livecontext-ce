/**
 * ResolvedParamsView - Displays resolved input parameters in run mode.
 *
 * Uses the exact same visual style as RunDataPreview (JsonNode + PrimitiveValue).
 * The only difference: raw keys are replaced by human-readable labels from the registry.
 *
 * - Uses useRunData hook for data fetching + pagination
 * - Uses detectNodeType to look up label registry
 * - For MCP tool nodes, maps toolParameters[].name -> labels
 * - Falls back to humanizeKey() for unknown keys
 */

'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Database, ChevronRight } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useRunData } from '../../../hooks/useRunData';
import { ItemNavigator, ALL_STATUSES_VALUE } from './ItemNavigator';
import { PrimitiveValue } from './RunDataPreview';
import type { StatusType } from '@/components/ui/StatusBadge';
import { detectNodeType } from '../core/types';
import { getInputLabel, humanizeKey } from '../registry/input-label-registry';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';

interface ResolvedParamsViewProps {
  workflowId: string | undefined;
  runId: string | undefined;
  stepAlias: string | undefined;
  node: Node<BuilderNodeData>;
  toolParameters?: any[];
}

export function ResolvedParamsView({
  workflowId,
  runId,
  stepAlias,
  node,
  toolParameters,
}: ResolvedParamsViewProps) {
  const [statusFilter, setStatusFilter] = React.useState<string>(ALL_STATUSES_VALUE);
  const activeStatusFilter: StatusType | null =
    statusFilter === ALL_STATUSES_VALUE ? null : (statusFilter as StatusType);
  const {
    totalItems,
    isLoading,
    error,
    currentIndex,
    currentItem,
    goToIndex,
    getObjectAtPath,
    availableStatuses,
  } = useRunData({
    workflowId,
    runId,
    stepAlias,
    dataType: 'input',
    enabled: !!workflowId && !!runId && !!stepAlias,
    statusFilter: activeStatusFilter,
  });

  const [data, setData] = React.useState<Record<string, any> | null>(null);
  const [isLoadingData, setIsLoadingData] = React.useState(false);

  // Load data when the displayed row changes. Keyed on currentItem.id and
  // guarded against out-of-order responses - same contract as RunDataPreview
  // (a page merge or targeted jump can swap WHICH row sits at the same index,
  // and a slow stale fetch must not overwrite the newer row's data).
  const loadSeqRef = React.useRef(0);
  React.useEffect(() => {
    if (totalItems === 0) return;

    const seq = ++loadSeqRef.current;
    const loadData = async () => {
      setIsLoadingData(true);
      try {
        const result = await getObjectAtPath('');
        if (seq !== loadSeqRef.current) return; // stale response
        // Every backend node persists its resolved configuration under `resolved_params`
        // (single source of truth - see StepDataPersistenceService.extractInputData).
        // If the fetched object already IS the unwrapped map (legacy/empty case), use it as-is.
        const resolvedParams = result && typeof result === 'object'
          ? (result.resolved_params ?? result)
          : result;
        setData(resolvedParams);
      } catch {
        if (seq !== loadSeqRef.current) return;
        setData(null);
      } finally {
        if (seq === loadSeqRef.current) setIsLoadingData(false);
      }
    };

    loadData();
  }, [currentIndex, currentItem?.id, totalItems, getObjectAtPath]);

  const nodeType = detectNodeType(node);

  // Build tool parameter label map for MCP nodes
  const toolParamLabels = React.useMemo(() => {
    if (nodeType !== 'tool' || !toolParameters) return null;
    const map: Record<string, string> = {};
    for (const param of toolParameters) {
      if (param.name) {
        map[param.name] = param.title || param.label || humanizeKey(param.name);
      }
    }
    return map;
  }, [nodeType, toolParameters]);

  // Resolve label for a given key
  const getLabel = React.useCallback(
    (key: string): string => {
      if (toolParamLabels && toolParamLabels[key]) {
        return toolParamLabels[key];
      }
      return getInputLabel(nodeType, key);
    },
    [nodeType, toolParamLabels],
  );

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-6">
        <LoadingSpinner size="xs" />
        <span className="ml-2 text-sm text-slate-500">Loading...</span>
      </div>
    );
  }

  if (error) {
    return (
      <div className="py-4 text-center">
        <p className="text-sm text-red-500">{error}</p>
      </div>
    );
  }

  if (totalItems === 0) {
    return (
      <div className="space-y-2">
        <ItemNavigator
          currentIndex={0}
          totalItems={0}
          onIndexChange={goToIndex}
          itemLabel="Item"
          statusOptions={availableStatuses}
          statusFilter={statusFilter}
          onStatusFilterChange={setStatusFilter}
        />
        <div className="py-4 text-center">
          <Database className="h-6 w-6 mx-auto mb-2 text-slate-300 dark:text-slate-600" />
          <p className="text-sm text-slate-500">No resolved parameters</p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <ItemNavigator
        currentIndex={currentIndex}
        totalItems={totalItems}
        onIndexChange={goToIndex}
        itemLabel="Item"
        statusOptions={availableStatuses}
        statusFilter={statusFilter}
        onStatusFilterChange={setStatusFilter}
      />

      {isLoadingData ? (
        <div className="space-y-2">
          {[1, 2, 3].map((i) => (
            <div
              key={i}
              className="h-5 w-full rounded bg-slate-200 dark:bg-slate-700 animate-pulse"
            />
          ))}
        </div>
      ) : data && typeof data === 'object' && !Array.isArray(data) ? (
        <LabeledDataTree data={data} getLabel={getLabel} />
      ) : data !== null ? (
        <PrimitiveValue value={data} />
      ) : (
        <div className="py-4 text-center">
          <p className="text-sm text-slate-500">No resolved parameters</p>
        </div>
      )}
    </div>
  );
}

// ============================================
// LabeledDataTree - same layout as JsonValueTree but with labels
// ============================================

function LabeledDataTree({
  data,
  getLabel,
}: {
  data: Record<string, any>;
  getLabel: (key: string) => string;
}) {
  // Merge "resolved*" keys into their base key so only the resolved value is shown.
  // Old format: { message: "{{template}}", resolvedMessage: "actual value" }
  // New format: { message: "actual value" }
  const merged: Record<string, any> = {};
  const resolvedKeys = new Set<string>();

  for (const [key, value] of Object.entries(data)) {
    if (key.startsWith('resolved') && key.length > 8) {
      const baseKey = key.charAt(8).toLowerCase() + key.slice(9);
      merged[baseKey] = value;
      resolvedKeys.add(key);
      resolvedKeys.add(baseKey);
    }
  }
  for (const [key, value] of Object.entries(data)) {
    if (!resolvedKeys.has(key)) {
      merged[key] = value;
    }
  }

  const entries = Object.entries(merged);

  if (entries.length === 0) {
    return (
      <div className="py-4 text-center">
        <p className="text-sm text-slate-500">No resolved parameters</p>
      </div>
    );
  }

  return (
    <div className="space-y-1">
      {entries.map(([key, value]) => (
        <LabeledNode key={key} label={getLabel(key)} value={value} />
      ))}
    </div>
  );
}

// ============================================
// LabeledNode - same style as JsonNode but with label instead of raw key
// ============================================

function LabeledNode({ label, value }: { label: string; value: any }) {
  const [isExpanded, setIsExpanded] = React.useState(false);

  const isExpandable = value !== null && typeof value === 'object';
  const isArray = Array.isArray(value);
  const itemCount = isExpandable
    ? (isArray ? value.length : Object.keys(value).length)
    : 0;

  // Primitive value - inline: label : value
  if (!isExpandable) {
    return (
      <div className="flex items-start text-sm font-normal text-[var(--text-primary)] w-full rounded-sm px-1 py-1">
        <div className="flex items-start gap-2 flex-1 min-w-0">
          <span className="truncate max-w-[120px] flex-shrink-0 text-sm" title={label}>{label}</span>
          <span className="text-slate-400 flex-shrink-0">:</span>
          <PrimitiveValue value={value} />
        </div>
      </div>
    );
  }

  // Object or Array - expandable
  return (
    <div className="flex flex-col gap-1">
      <div
        className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1 cursor-pointer hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800"
        onClick={() => setIsExpanded(!isExpanded)}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          <span className="truncate flex-1 min-w-0 text-sm" title={label}>{label}</span>
          <ChevronRight
            className={clsx(
              'h-3 w-3 text-slate-400 dark:text-slate-500 transition-transform flex-shrink-0 mr-2',
              isExpanded && 'rotate-90',
            )}
          />
        </div>
        <span className="text-sm font-mono text-orange-600 dark:text-orange-400 flex-shrink-0">
          {isArray ? `[${itemCount}]` : `{${itemCount}}`}
        </span>
      </div>

      {isExpanded && (
        <NestedValueTree data={value} />
      )}
    </div>
  );
}

// ============================================
// NestedValueTree - recursive display for nested objects/arrays (raw keys)
// ============================================

function NestedValueTree({ data }: { data: any }) {
  if (data === null || data === undefined) {
    return <span className="font-mono text-sm text-slate-400">null</span>;
  }

  if (typeof data !== 'object') {
    return <PrimitiveValue value={data} />;
  }

  if (Array.isArray(data)) {
    if (data.length === 0) {
      return <span className="font-mono text-sm text-slate-400">[]</span>;
    }
    return (
      <div className="pl-3 border-l border-slate-200 dark:border-slate-700">
        <div className="space-y-1">
          {data.map((item, index) => (
            <NestedNode key={index} nodeKey={String(index)} value={item} />
          ))}
        </div>
      </div>
    );
  }

  const entries = Object.entries(data);
  if (entries.length === 0) {
    return <span className="font-mono text-sm text-slate-400">{'{}'}</span>;
  }

  return (
    <div className="pl-3 border-l border-slate-200 dark:border-slate-700">
      <div className="space-y-1">
        {entries.map(([key, value]) => (
          <NestedNode key={key} nodeKey={key} value={value} />
        ))}
      </div>
    </div>
  );
}

// ============================================
// NestedNode - same as JsonNode (raw keys, no drag)
// ============================================

function NestedNode({ nodeKey, value }: { nodeKey: string; value: any }) {
  const [isExpanded, setIsExpanded] = React.useState(false);

  const isExpandable = value !== null && typeof value === 'object';
  const isArray = Array.isArray(value);
  const itemCount = isExpandable
    ? (isArray ? value.length : Object.keys(value).length)
    : 0;

  if (!isExpandable) {
    return (
      <div className="flex items-start text-sm font-normal text-[var(--text-primary)] w-full rounded-sm px-1 py-1">
        <div className="flex items-start gap-2 flex-1 min-w-0">
          <span className="truncate max-w-[120px] flex-shrink-0 text-sm" title={nodeKey}>{nodeKey}</span>
          <span className="text-slate-400 flex-shrink-0">:</span>
          <PrimitiveValue value={value} />
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-1">
      <div
        className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1 cursor-pointer hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800"
        onClick={() => setIsExpanded(!isExpanded)}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          <span className="truncate flex-1 min-w-0 text-sm" title={nodeKey}>{nodeKey}</span>
          <ChevronRight
            className={clsx(
              'h-3 w-3 text-slate-400 dark:text-slate-500 transition-transform flex-shrink-0 mr-2',
              isExpanded && 'rotate-90',
            )}
          />
        </div>
        <span className="text-sm font-mono text-orange-600 dark:text-orange-400 flex-shrink-0">
          {isArray ? `[${itemCount}]` : `{${itemCount}}`}
        </span>
      </div>

      {isExpanded && (
        <NestedValueTree data={value} />
      )}
    </div>
  );
}
