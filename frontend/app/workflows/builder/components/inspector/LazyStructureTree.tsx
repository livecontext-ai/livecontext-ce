import * as React from 'react';
import clsx from 'clsx';
import { ArrowLeft, ArrowRight, ChevronRight, GripVertical } from 'lucide-react';
import { NodeIcon } from '../nodes/shared';
import { useStructureRoot, useStructurePath } from '../../hooks/useMcpData';
import { normalizeColumnType } from '../../utils/typeNormalizer';
import { getFieldTypeColor, normalizeFieldType } from '../../types';
import type { StructureNode } from './StructureExplorer';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { EmptyState } from '../shared/EmptyState';

interface LazyStructureTreeProps {
  structureId: string | null;
  dragPrefix?: string;
  isDraggable?: boolean;
  rootLabel?: string;
  parentLabel?: string;
  parentDirection?: 'prev' | 'next';
  onParentClick?: () => void;
  includeHttpStatus?: boolean; // Add HTTP Status for tool outputs
  isParentNodeError?: boolean; // Indique si le node parent est en erreur
  useIterationArrow?: boolean; // Use iteration arrow for navigation
  infoTooltip?: React.ReactNode; // Optional info tooltip to show next to navigation button
  parentNodeId?: string; // Node ID for NodeIcon in navigation button
  parentIconSlug?: string; // Icon slug for service icons in navigation button
  parentNodeKind?: string; // Node kind for icon resolution
  parentAvatarUrl?: string; // Agent avatar URL for navigation button
}

export const LazyStructureTree = ({
  structureId,
  dragPrefix,
  isDraggable = false,
  rootLabel = 'root',
  parentLabel,
  parentDirection = 'prev',
  onParentClick,
  includeHttpStatus = false,
  isParentNodeError = false,
  useIterationArrow: _useIterationArrow = false,
  parentNodeId,
  parentIconSlug,
  parentNodeKind,
  parentAvatarUrl,
  infoTooltip,
}: LazyStructureTreeProps) => {
  const [expandedPaths, setExpandedPaths] = React.useState<Set<string>>(new Set());
  const [pendingPath, setPendingPath] = React.useState<string[] | null>(null);
  const [structureCache, setStructureCache] = React.useState<Record<string, StructureNode[]>>({});
  const [httpStatusExpanded, setHttpStatusExpanded] = React.useState<boolean>(false);

  const rootQuery = useStructureRoot(structureId);
  const pathQuery = useStructurePath(pendingPath && structureId ? structureId : null, pendingPath || []);

  React.useEffect(() => {
    if (rootQuery.data && structureId) {
      setStructureCache(prev => ({ ...prev, ['']: rootQuery.data as StructureNode[] }));
    }
  }, [rootQuery.data, structureId]);

  React.useEffect(() => {
    if (pathQuery.data && pendingPath) {
      const key = pendingPath.join('.');
      setStructureCache(prev => ({ ...prev, [key]: pathQuery.data as StructureNode[] }));
      setPendingPath(null);
    }
  }, [pathQuery.data, pendingPath]);

  const togglePath = (path: string[], hasChildren: boolean) => {
    if (!hasChildren) return;
    const key = path.join('.');
    setExpandedPaths(prev => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
    if (!structureCache[key] && structureId) {
      setPendingPath(path);
    }
  };

  const renderNodes = (nodes: StructureNode[] | undefined, parentPath: string[] = [], showBorder: boolean = false, isRoot: boolean = false) => {
    return (
      <div className={clsx(showBorder && "pl-3 border-l border-slate-200 dark:border-slate-700")}>
        <div className="space-y-1">
          {/* Render HTTP Status first if it's the root level and includeHttpStatus is true */}
          {isRoot && includeHttpStatus && (
            <div className="flex flex-col gap-1">
              <div
                className={clsx(
                  "flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1",
                  "cursor-pointer hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800"
                )}
                onClick={() => setHttpStatusExpanded(prev => !prev)}
                title={dragPrefix ? `${dragPrefix}.httpstatus` : 'httpstatus'}
              >
                <div className="flex items-center gap-2 flex-1 min-w-0">
                  {isDraggable && <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing" />}
                  <span className="truncate flex-1 min-w-0 text-sm" title="httpstatus">httpstatus</span>
                  <ChevronRight
                    className={clsx(
                      "h-3 w-3 text-slate-400 dark:text-slate-500 transition-transform mr-2",
                      httpStatusExpanded && "rotate-90"
                    )}
                  />
                </div>
                <span className={clsx("text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0", getFieldTypeColor('object'))}>object</span>
              </div>
              {httpStatusExpanded && (
                <div className="pl-3 border-l border-slate-200 dark:border-slate-700 space-y-1">
                  <div
                    className={clsx(
                      "flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 transition-colors rounded-sm",
                      isDraggable
                        ? "cursor-grab active:cursor-grabbing hover:bg-slate-50"
                        : "cursor-default"
                    )}
                    draggable={isDraggable}
                    onDragStart={(e) => {
                      if (!isDraggable) return;
                      e.stopPropagation();
                      const fullPath = dragPrefix ? `${dragPrefix}.httpstatus.error` : 'httpstatus.error';
                      e.dataTransfer.setData('text/plain', `{{${fullPath}}}`);
                      e.dataTransfer.setData('application/x-field-type', 'text');
                      e.dataTransfer.effectAllowed = 'copy';
                    }}
                    title={dragPrefix ? `${dragPrefix}.httpstatus.error` : 'httpstatus.error'}
                  >
                    <div className="flex items-center gap-2 flex-1 min-w-0">
                      {isDraggable && <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing" />}
                      <span className="truncate flex-1 min-w-0 text-sm">error</span>
                    </div>
                    <span className={clsx("text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0", getFieldTypeColor('text'))}>text</span>
                  </div>
                  <div
                    className={clsx(
                      "flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 transition-colors rounded-sm",
                      isDraggable
                        ? "cursor-grab active:cursor-grabbing hover:bg-slate-50"
                        : "cursor-default"
                    )}
                    draggable={isDraggable}
                    onDragStart={(e) => {
                      if (!isDraggable) return;
                      e.stopPropagation();
                      const fullPath = dragPrefix ? `${dragPrefix}.httpstatus.code` : 'httpstatus.code';
                      e.dataTransfer.setData('text/plain', `{{${fullPath}}}`);
                      e.dataTransfer.setData('application/x-field-type', 'number');
                      e.dataTransfer.effectAllowed = 'copy';
                    }}
                    title={dragPrefix ? `${dragPrefix}.httpstatus.code` : 'httpstatus.code'}
                  >
                    <div className="flex items-center gap-2 flex-1 min-w-0">
                      {isDraggable && <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing" />}
                      <span className="truncate flex-1 min-w-0 text-sm">code</span>
                    </div>
                    <span className={clsx("text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0", getFieldTypeColor('number'))}>number</span>
                  </div>
                </div>
              )}
            </div>
          )}
          {nodes && nodes.length > 0 ? nodes.map(item => {
            const nextPath = [...parentPath, item.key];
            const key = nextPath.join('.');
            const isExpanded = expandedPaths.has(key);
            const children = structureCache[key];
            const loadingChild = pendingPath && pendingPath.join('.') === key && pathQuery.isLoading;
            return (
              <div key={key} className="flex flex-col gap-1">
                <div
                  className={clsx(
                    "flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1",
                    item.hasChildren
                      ? "cursor-pointer hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800"
                      : isDraggable
                        ? "cursor-grab active:cursor-grabbing"
                        : "cursor-default"
                  )}
                  onClick={() => togglePath(nextPath, item.hasChildren)}
                  draggable={isDraggable}
                  onDragStart={(e) => {
                    if (!isDraggable) return;
                    e.stopPropagation();
                    const fullPath = dragPrefix ? [dragPrefix, ...nextPath].join('.') : nextPath.join('.');
                    e.dataTransfer.setData('text/plain', `{{${fullPath}}}`);
                    e.dataTransfer.setData('application/x-field-type', item.type || 'text');
                    e.dataTransfer.effectAllowed = 'copy';
                  }}
                  title={nextPath.join('.')}
                >
                  <div className="flex items-center gap-2 flex-1 min-w-0">
                    {isDraggable && <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing" />}
                    <span className="truncate flex-1 min-w-0 text-sm" title={item.key}>{item.key}</span>
                    {item.hasChildren && (
                      <ChevronRight
                        className={clsx(
                          "h-3 w-3 text-slate-400 dark:text-slate-500 transition-transform mr-2",
                          isExpanded && "rotate-90"
                        )}
                      />
                    )}
                  </div>
                  <div className="flex items-center gap-2 flex-shrink-0">
                    {item.type ? (() => {
                      const normalizedType = normalizeFieldType(normalizeColumnType(item.type));
                      return (
                        <span className={clsx(
                          "text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0",
                          getFieldTypeColor(normalizedType)
                        )}>{normalizedType}</span>
                      );
                    })() : (
                      <span className="text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0 bg-gray-200 dark:bg-gray-700 text-gray-500 dark:text-gray-400">unknown</span>
                    )}
                  </div>
                </div>
                {item.hasChildren && isExpanded && (
                  renderNodes(children, nextPath, true, false)
                )}
              </div>
            );
          }) : (
            <EmptyState message="No data structure" className="pl-1" />
          )}
        </div>
      </div>
    );
  };

  return (
    <div className="w-full">
      {parentLabel && onParentClick && (
        <div className={clsx("mb-1 flex items-center gap-1", parentDirection === 'next' ? "justify-end" : "justify-start")}>
          <button
            onClick={onParentClick}
            className={clsx(
              "inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors",
              isParentNodeError
                ? "text-red-600 dark:text-red-400 hover:text-red-700 dark:hover:text-red-300 hover:bg-red-50 dark:hover:bg-red-900/20"
                : "text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
            )}
            title={`Go to ${parentLabel}`}
          >
            {parentDirection === 'prev' ? (
              <>
                <ArrowLeft className="h-3 w-3 flex-shrink-0" />
                {_useIterationArrow ? (
                  /* RefreshCcw icon for loop iteration */
                  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5" aria-hidden="true">
                    <path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"></path>
                    <path d="M3 3v5h5"></path>
                    <path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"></path>
                    <path d="M16 16h5v5"></path>
                  </svg>
                ) : (
                  /* Node icon for normal navigation */
                  <NodeIcon nodeId={parentNodeId || ''} iconSlug={parentIconSlug} nodeKind={parentNodeKind as any} avatarUrl={parentAvatarUrl} size="xs" />
                )}
                <span className="truncate max-w-[200px] font-medium">{parentLabel}</span>
              </>
            ) : (
              <>
                <NodeIcon nodeId={parentNodeId || ''} iconSlug={parentIconSlug} nodeKind={parentNodeKind as any} avatarUrl={parentAvatarUrl} size="xs" />
                <span className="truncate max-w-[200px] font-medium">{parentLabel}</span>
                {_useIterationArrow ? (
                  /* RefreshCcw icon for loop iteration */
                  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="h-3.5 w-3.5" aria-hidden="true">
                    <path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"></path>
                    <path d="M3 3v5h5"></path>
                    <path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"></path>
                    <path d="M16 16h5v5"></path>
                  </svg>
                ) : (
                  <ArrowRight className="h-3 w-3 flex-shrink-0" />
                )}
              </>
            )}
          </button>
          {infoTooltip}
        </div>
      )}
      {rootQuery.isLoading && (!structureCache[''] || structureCache[''].length === 0) ? (
        <div className="pl-3 border-l border-slate-200 dark:border-slate-700 space-y-2">
          {[1, 2, 3].map(i => <div key={i} className="h-5 w-full rounded bg-slate-200 dark:bg-slate-700 animate-pulse" />)}
        </div>
      ) : (
        renderNodes(structureCache[''], [], false, true)
      )}
    </div>
  );
};
