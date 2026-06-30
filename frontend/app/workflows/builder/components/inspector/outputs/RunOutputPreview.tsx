'use client';

/**
 * RunOutputPreview - Shows real execution data in run mode
 *
 * Uses same visual style as LazyStructureTree but displays actual run data
 * with item navigation for multi-item results (datasource, Split)
 * Supports drag and drop to create expressions like {{mcp:label.output.field}}
 */

import * as React from 'react';
import clsx from 'clsx';
import { ChevronRight, Database, GripVertical, Download, Eye } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from 'next-intl';
import { useRunOutputData } from '../../../hooks/useRunOutputData';
import { ItemNavigator, ALL_STATUSES_VALUE } from './ItemNavigator';
import type { StatusType } from '@/components/ui/StatusBadge';
import { EmptyState } from '../../shared/EmptyState';
import { isFileRef, normalizeFileRef, fileService, getFilePath, fileRefToUrl, type FileRef } from '@/lib/api/orchestrator/file.service';
import { openAuthedFileInNewTab } from '@/lib/utils/url-auth';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';

interface RunOutputPreviewProps {
  workflowId: string | undefined;
  runId: string | undefined;
  stepAlias: string | undefined;
  /** Enable drag and drop to create expressions */
  isDraggable?: boolean;
  /** Prefix for drag expressions, e.g. "mcp:step1.output" */
  dragPrefix?: string;
}

export function RunOutputPreview({
  workflowId,
  runId,
  stepAlias,
  isDraggable = false,
  dragPrefix,
}: RunOutputPreviewProps) {
  const t = useTranslations('workflowBuilder.inspector');
  const { viewingEpoch } = useWorkflowMode();
  const [statusFilter, setStatusFilter] = React.useState<string>(ALL_STATUSES_VALUE);
  const activeStatusFilter: StatusType | null =
    statusFilter === ALL_STATUSES_VALUE ? null : (statusFilter as StatusType);
  const {
    totalItems,
    isLoading,
    error,
    currentIndex,
    goToIndex,
    getObjectAtPath,
    availableStatuses,
  } = useRunOutputData({
    workflowId,
    runId,
    stepAlias,
    epoch: viewingEpoch,
    enabled: !!workflowId && !!runId && !!stepAlias,
    statusFilter: activeStatusFilter,
  });

  // State for loaded output data
  const [outputData, setOutputData] = React.useState<any>(null);
  const [isLoadingOutput, setIsLoadingOutput] = React.useState(false);

  // Load output data when currentIndex changes
  React.useEffect(() => {
    if (totalItems === 0) return;

    const loadOutput = async () => {
      setIsLoadingOutput(true);
      try {
        // getObjectAtPath('') will fetch path=output thanks to our hook modification
        const data = await getObjectAtPath('');
        setOutputData(data);
      } catch (err) {
        console.error('Failed to load output data:', err);
        setOutputData(null);
      } finally {
        setIsLoadingOutput(false);
      }
    };

    loadOutput();
  }, [currentIndex, totalItems, getObjectAtPath]);

  // Loading state
  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-6">
        <LoadingSpinner size="xs" />
        <span className="ml-2 text-sm text-slate-500">{t('loading')}</span>
      </div>
    );
  }

  // Error state
  if (error) {
    return <EmptyState message={error} />;
  }

  // No data state - keep navigator+filter visible so users can clear an
  // over-restrictive status filter without re-opening the column.
  if (totalItems === 0) {
    return (
      <div className="space-y-2">
        <ItemNavigator
          currentIndex={0}
          totalItems={0}
          onIndexChange={goToIndex}
          itemLabel={t('item')}
          statusOptions={availableStatuses}
          statusFilter={statusFilter}
          onStatusFilterChange={setStatusFilter}
        />
        <div className="py-4 text-center">
          <Database className="h-6 w-6 mx-auto mb-2 text-slate-300 dark:text-slate-600" />
          <p className="text-sm text-slate-500">{t('noExecutionData')}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {/* Item Navigator */}
      <ItemNavigator
        currentIndex={currentIndex}
        totalItems={totalItems}
        onIndexChange={goToIndex}
        itemLabel={t('item')}
        statusOptions={availableStatuses}
        statusFilter={statusFilter}
        onStatusFilterChange={setStatusFilter}
      />

      {/* Output Data Tree */}
      {isLoadingOutput ? (
        <div className="space-y-2">
          {[1, 2, 3].map((i) => (
            <div
              key={i}
              className="h-5 w-full rounded bg-slate-200 dark:bg-slate-700 animate-pulse"
            />
          ))}
        </div>
      ) : outputData !== null ? (
        <JsonValueTree
          data={outputData}
          isDraggable={isDraggable}
          dragPrefix={dragPrefix}
          path={[]}
          showBorder={false}
        />
      ) : (
        <EmptyState message={t('noOutputData')} />
      )}
    </div>
  );
}

// ============================================
// JSON Value Tree - Recursive JSON visualizer
// Same layout as LazyStructureTree
// ============================================

interface JsonValueTreeProps {
  data: any;
  isDraggable?: boolean;
  dragPrefix?: string;
  path: string[];
  showBorder?: boolean;
}

function JsonValueTree({ data, isDraggable = false, dragPrefix, path, showBorder = false }: JsonValueTreeProps) {
  if (data === null) {
    return <span className="text-sm font-mono text-slate-400">null</span>;
  }

  if (data === undefined) {
    return <span className="text-sm font-mono text-slate-400">undefined</span>;
  }

  if (typeof data !== 'object') {
    // Primitive value
    return <PrimitiveValue value={data} />;
  }

  // Top-level FileRef object (e.g., download_file output in DB format)
  if (!Array.isArray(data) && isFileRef(data)) {
    return <TopLevelFileRefView data={data} isDraggable={isDraggable} dragPrefix={dragPrefix} path={path} />;
  }

  // Step output containing a file (flat format with envelope fields)
  // Show a file preview card at the top, then all fields below
  if (!Array.isArray(data) && typeof data === 'object' && '_status' in data
      && typeof data.file_url === 'string' && typeof data.file_name === 'string') {
    // Pass raw data without _type so normalizeFileRef detects the flat format (file_url → path extraction)
    const normalized = normalizeFileRef(data as any);
    const entries = Object.entries(data);
    return (
      <div className="space-y-2">
        <FilePreviewCard fileRef={normalized} />
        <div className="space-y-1">
          {entries.map(([key, value]) => (
            <JsonNode
              key={key}
              nodeKey={key}
              value={value}
              isDraggable={isDraggable}
              dragPrefix={dragPrefix}
              path={[...path, key]}
            />
          ))}
        </div>
      </div>
    );
  }

  if (Array.isArray(data)) {
    if (data.length === 0) {
      return <span className="text-sm font-mono text-slate-400">[]</span>;
    }
    return (
      <div className={clsx(showBorder && "pl-3 border-l border-slate-200 dark:border-slate-700")}>
        <div className="space-y-1">
          {data.map((item, index) => (
            <JsonNode
              key={index}
              nodeKey={String(index)}
              value={item}
              isDraggable={isDraggable}
              dragPrefix={dragPrefix}
              path={[...path, String(index)]}
            />
          ))}
        </div>
      </div>
    );
  }

  // Object
  const entries = Object.entries(data);
  if (entries.length === 0) {
    return <span className="text-sm font-mono text-slate-400">{'{}'}</span>;
  }

  return (
    <div className={clsx(showBorder && "pl-3 border-l border-slate-200 dark:border-slate-700")}>
      <div className="space-y-1">
        {entries.map(([key, value]) => (
          <JsonNode
            key={key}
            nodeKey={key}
            value={value}
            isDraggable={isDraggable}
            dragPrefix={dragPrefix}
            path={[...path, key]}
          />
        ))}
      </div>
    </div>
  );
}

// Single JSON node (key + value, expandable if object/array)
// Layout matches LazyStructureTree
interface JsonNodeProps {
  nodeKey: string;
  value: any;
  isDraggable?: boolean;
  dragPrefix?: string;
  path: string[];
}

function JsonNode({ nodeKey, value, isDraggable = false, dragPrefix, path }: JsonNodeProps) {
  const [isExpanded, setIsExpanded] = React.useState(false);

  // FileRef objects are expandable to show their properties
  const isFileRefValue = isFileRef(value);
  const isExpandable = value !== null && typeof value === 'object' && !Array.isArray(value) || Array.isArray(value);
  const isArray = Array.isArray(value);
  const itemCount = isExpandable ? (isArray ? value.length : Object.keys(value).filter(k => k !== '_type').length) : 0;

  // Build the full path for drag expression
  const fullPath = dragPrefix ? `${dragPrefix}.${path.join('.')}` : path.join('.');

  const handleDragStart = (e: React.DragEvent) => {
    if (!isDraggable) return;
    e.stopPropagation();
    e.dataTransfer.setData('text/plain', `{{${fullPath}}}`);
    e.dataTransfer.effectAllowed = 'copy';
  };

  // Primitive value - show inline
  if (!isExpandable) {
    return (
      <div
        className={clsx(
          "flex items-start justify-between text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1",
          isDraggable
            ? "cursor-grab active:cursor-grabbing hover:bg-slate-50 dark:hover:bg-slate-800"
            : "cursor-default"
        )}
        draggable={isDraggable}
        onDragStart={handleDragStart}
        title={isDraggable ? fullPath : undefined}
      >
        <div className="flex items-start gap-2 flex-1 min-w-0">
          {isDraggable && (
            <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing flex-shrink-0 mt-0.5" />
          )}
          <span className="truncate max-w-[120px] flex-shrink-0 text-sm" title={nodeKey}>{nodeKey}</span>
          <span className="text-slate-400 flex-shrink-0">:</span>
          <PrimitiveValue value={value} />
        </div>
      </div>
    );
  }

  // FileRef object - expandable with download button
  if (isFileRefValue) {
    return (
      <FileObjectNode
        nodeKey={nodeKey}
        fileRef={value}
        isDraggable={isDraggable}
        dragPrefix={dragPrefix}
        path={path}
        isExpanded={isExpanded}
        onToggle={() => setIsExpanded(!isExpanded)}
        onDragStart={handleDragStart}
        fullPath={fullPath}
      />
    );
  }

  // Object or Array - expandable (same layout as LazyStructureTree)
  return (
    <div className="flex flex-col gap-1">
      <div
        className={clsx(
          "flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1",
          "cursor-pointer hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800"
        )}
        draggable={isDraggable}
        onDragStart={handleDragStart}
        onClick={() => setIsExpanded(!isExpanded)}
        title={isDraggable ? fullPath : nodeKey}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          {isDraggable && (
            <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing flex-shrink-0" />
          )}
          <span className="truncate flex-1 min-w-0 text-sm" title={nodeKey}>{nodeKey}</span>
          <ChevronRight
            className={clsx(
              "h-3 w-3 text-slate-400 dark:text-slate-500 transition-transform flex-shrink-0 mr-2",
              isExpanded && "rotate-90"
            )}
          />
        </div>
        <span className="text-sm font-mono text-orange-600 dark:text-orange-400 flex-shrink-0">
          {isArray ? `[${itemCount}]` : `{${itemCount}}`}
        </span>
      </div>

      {isExpanded && (
        <JsonValueTree
          data={value}
          isDraggable={isDraggable}
          dragPrefix={dragPrefix}
          path={path}
          showBorder={true}
        />
      )}
    </div>
  );
}

// ============================================
// FilePreviewCard - Compact file card with view/download buttons
// Shown at the top of step outputs that contain a file
// ============================================

function FilePreviewCard({ fileRef }: { fileRef: FileRef }) {
  const [isDownloading, setIsDownloading] = React.useState(false);

  const handleDownload = async () => {
    setIsDownloading(true);
    try {
      await fileService.downloadAndSave(fileRef, fileRef.name);
    } catch (err) {
      console.error('Download failed:', err);
    } finally {
      setIsDownloading(false);
    }
  };

  const handlePreview = async () => {
    try {
      // View via an authenticated fetch (no token in the URL).
      const url = fileRefToUrl(fileRef, { inline: true });
      if (url) await openAuthedFileInNewTab(url);
    } catch (err) {
      console.error('Preview failed:', err);
    }
  };

  return (
    <div className="flex items-center gap-2 px-2 py-1.5 rounded-md bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700">
      <div className="flex-1 min-w-0">
        <span className="text-sm font-medium truncate block">{fileRef.name}</span>
        <span className="text-xs text-slate-500 dark:text-slate-400">
          {fileRef.mimeType} · {fileService.formatFileSize(fileRef.size)}
        </span>
      </div>
      <div className="flex items-center gap-1 flex-shrink-0">
        <button
          onClick={handlePreview}
          className="p-1 rounded text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
          title="View file"
        >
          <Eye className="h-3.5 w-3.5" />
        </button>
        <button
          onClick={handleDownload}
          disabled={isDownloading}
          className="p-1 rounded text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          title="Download file"
        >
          <Download className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  );
}

// ============================================
// TopLevelFileRefView - Wraps FileObjectNode for top-level file refs
// When the entire output data IS a file ref (e.g., download_file DB format)
// ============================================

function TopLevelFileRefView({ data, isDraggable, dragPrefix, path }: {
  data: any;
  isDraggable: boolean;
  dragPrefix?: string;
  path: string[];
}) {
  const [isExpanded, setIsExpanded] = React.useState(false);
  const fullPath = dragPrefix ? `${dragPrefix}.${path.join('.')}` : path.join('.');
  const handleDragStart = (e: React.DragEvent) => {
    if (!isDraggable) return;
    e.stopPropagation();
    e.dataTransfer.setData('text/plain', `{{${fullPath}}}`);
    e.dataTransfer.effectAllowed = 'copy';
  };

  return (
    <FileObjectNode
      nodeKey="file"
      fileRef={data}
      isDraggable={isDraggable}
      dragPrefix={dragPrefix}
      path={path}
      isExpanded={isExpanded}
      onToggle={() => setIsExpanded(!isExpanded)}
      onDragStart={handleDragStart}
      fullPath={fullPath}
    />
  );
}

// ============================================
// FileObjectNode - Renders FileRef as tree object with download
// ============================================

interface FileObjectNodeProps {
  nodeKey: string;
  fileRef: FileRef;
  isDraggable: boolean;
  dragPrefix?: string;
  path: string[];
  isExpanded: boolean;
  onToggle: () => void;
  onDragStart: (e: React.DragEvent) => void;
  fullPath: string;
}

function FileObjectNode({
  nodeKey,
  fileRef,
  isDraggable,
  dragPrefix,
  path,
  isExpanded,
  onToggle,
  onDragStart,
  fullPath,
}: FileObjectNodeProps) {
  // Normalize DB/flattened format (file_url, file_name, etc.) to canonical FileRef
  const normalized = normalizeFileRef(fileRef);
  const [isDownloading, setIsDownloading] = React.useState(false);

  const handleDownload = async (e: React.MouseEvent) => {
    e.stopPropagation();
    setIsDownloading(true);
    try {
      await fileService.downloadAndSave(normalized, normalized.name);
    } catch (err) {
      console.error('Download failed:', err);
    } finally {
      setIsDownloading(false);
    }
  };

  // Get the file path (supports both 'path' and legacy 'key') - for the displayed props only
  const filePath = getFilePath(normalized);

  // Properties to display (excluding _type discriminator)
  const displayProps = [
    { key: 'path', value: filePath },
    { key: 'name', value: normalized.name },
    { key: 'mimeType', value: normalized.mimeType },
    { key: 'size', value: normalized.size },
  ];

  const handlePreview = async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      // View via an authenticated fetch (no token in the URL).
      const url = fileRefToUrl(normalized, { inline: true });
      if (url) await openAuthedFileInNewTab(url);
    } catch (err) {
      console.error('Preview failed:', err);
    }
  };

  return (
    <div className="flex flex-col gap-1">
      {/* Header row with file icon, name, count, and download button */}
      <div
        className={clsx(
          "flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1",
          "cursor-pointer hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800"
        )}
        draggable={isDraggable}
        onDragStart={onDragStart}
        onClick={onToggle}
        title={isDraggable ? fullPath : nodeKey}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          {isDraggable && (
            <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing flex-shrink-0" />
          )}
          <span className="truncate flex-1 min-w-0 text-sm" title={nodeKey}>{nodeKey}</span>
          <ChevronRight
            className={clsx(
              "h-3 w-3 text-slate-400 dark:text-slate-500 transition-transform flex-shrink-0 mr-2",
              isExpanded && "rotate-90"
            )}
          />
        </div>
        {/* Object count indicator (same style as regular objects) */}
        <span className="text-sm font-mono text-orange-600 dark:text-orange-400 flex-shrink-0">
          {`{${displayProps.length}}`}
        </span>
        {/* Action buttons */}
        <div className="flex items-center gap-1 ml-2">
          {/* View file button */}
          <button
            onClick={handlePreview}
            className="p-1 rounded text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
            title="View file"
          >
            <Eye className="h-3.5 w-3.5" />
          </button>
          {/* Download button */}
          <button
            onClick={handleDownload}
            disabled={isDownloading}
            className="p-1 rounded text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            title="Download file"
          >
            {isDownloading ? (
              <LoadingSpinner size="xs" />
            ) : (
              <Download className="h-3.5 w-3.5" />
            )}
          </button>
        </div>
      </div>

      {/* Expanded content - show file properties */}
      {isExpanded && (
        <div className="pl-3 border-l border-slate-200 dark:border-slate-700">
          <div className="space-y-1">
            {displayProps.map(({ key, value }) => (
              <div
                key={key}
                className={clsx(
                  "flex items-start gap-2 text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1",
                  isDraggable
                    ? "cursor-grab active:cursor-grabbing hover:bg-slate-50 dark:hover:bg-slate-800"
                    : "cursor-default"
                )}
                draggable={isDraggable}
                onDragStart={(e) => {
                  if (!isDraggable) return;
                  e.stopPropagation();
                  const propPath = dragPrefix ? `${dragPrefix}.${[...path, key].join('.')}` : [...path, key].join('.');
                  e.dataTransfer.setData('text/plain', `{{${propPath}}}`);
                  e.dataTransfer.effectAllowed = 'copy';
                }}
                title={isDraggable ? `${fullPath}.${key}` : undefined}
              >
                {isDraggable && (
                  <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing flex-shrink-0 mt-0.5" />
                )}
                <span className="truncate max-w-[120px] flex-shrink-0 text-sm" title={key}>{key}</span>
                <span className="text-slate-400 flex-shrink-0">:</span>
                <PrimitiveValue value={value} />
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

// Render primitive values with appropriate styling
// Colors: text=blue, number=green, boolean=yellow, null=slate
function PrimitiveValue({ value }: { value: any }) {
  if (value === null) {
    return <span className="font-mono text-sm text-slate-400">null</span>;
  }
  if (value === undefined) {
    return <span className="font-mono text-sm text-slate-400">undefined</span>;
  }
  if (typeof value === 'boolean') {
    return (
      <span className="font-mono text-sm text-yellow-700 dark:text-yellow-300">
        {String(value)}
      </span>
    );
  }
  if (typeof value === 'number') {
    return <span className="font-mono text-sm text-green-700 dark:text-green-300">{value}</span>;
  }
  if (typeof value === 'string') {
    return (
      <span className="font-mono text-sm text-blue-700 dark:text-blue-300 break-all">
        "{value}"
      </span>
    );
  }
  return <span className="font-mono text-sm text-slate-500">{String(value)}</span>;
}
