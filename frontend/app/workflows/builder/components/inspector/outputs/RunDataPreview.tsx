/**
 * RunDataPreview - the run-mode data panel for a node's input or output.
 *
 * Owns the fetching, the item navigator and the view chrome; the rendering of
 * the payload itself lives in JsonDataTree so the Params and Output columns
 * cannot drift apart. Three readings of the same payload are offered (tree, raw
 * JSON, table) plus a copy of the whole document, and a node that is still
 * executing or parked on a signal says so instead of reporting "no data".
 */

'use client';

import * as React from 'react';
import { Database, FlaskConical } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from 'next-intl';
import type { Node } from 'reactflow';
import { useRunData, type RunDataType } from '../../../hooks/useRunData';
import { useNodeLiveState } from '../../../hooks/useNodeLiveState';
import { ItemNavigator, ALL_STATUSES_VALUE } from './ItemNavigator';
import type { StatusType } from '@/components/ui/StatusBadge';
import { EmptyState } from '../../shared/EmptyState';
import { OutputError, SkipBanner } from '../shared/OutputError';
import { isMockedOutput } from '../../../utils/nodeMock';
import type { BuilderNodeData } from '../../../types';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { JsonValueTree } from './JsonDataTree';
import { NodeRunStateNotice } from './NodeRunStateNotice';
import { RunDataViewTabs, RawJsonView, JsonTableView, type RunDataViewMode } from './RunDataViews';
import { hasTableView } from './runValueUtils';


interface RunDataPreviewProps {
  workflowId: string | undefined;
  runId: string | undefined;
  stepAlias: string | undefined;
  /** Type of data to display: input or output */
  dataType: RunDataType;
  /** Enable drag and drop to create expressions */
  isDraggable?: boolean;
  /** Prefix for drag expressions, e.g. "mcp:step1.output" or "mcp:step1.params" */
  dragPrefix?: string;
  /**
   * The node this panel belongs to. Only used to report its LIVE state
   * (executing / parked on a signal) when there is no persisted data yet -
   * omit it and the panel simply falls back to the plain empty state.
   */
  node?: Node<BuilderNodeData> | null;
  /**
   * Publishes the currently loaded data object (null while loading / when
   * nothing is loaded / on unmount). The inspector lifts an OUTPUT preview's
   * object up to its column header, where the settings menu offers
   * "Use as mock output" on it.
   */
  onLoadedOutputChange?: (data: unknown | null) => void;
}

export function RunDataPreview({
  workflowId,
  runId,
  stepAlias,
  dataType,
  isDraggable = false,
  dragPrefix,
  node,
  onLoadedOutputChange,
}: RunDataPreviewProps) {
  const t = useTranslations('workflowBuilder.inspector');
  const tMock = useTranslations('workflowBuilder.mock');
  const { isRunMode } = useWorkflowMode();
  const [statusFilter, setStatusFilter] = React.useState<string>(ALL_STATUSES_VALUE);
  const activeStatusFilter: StatusType | null =
    statusFilter === ALL_STATUSES_VALUE ? null : (statusFilter as StatusType);

  // Persistent expand/collapse state, keyed by JSON path. Lives on the parent so
  // it survives item navigation (35 → 34): each JsonNode used to own its own
  // useState(false), which the data-swap re-instantiated to collapsed every time.
  // Storing paths here means the same tree shape stays open across items.
  const [expandedPaths, setExpandedPaths] = React.useState<Set<string>>(() => new Set());
  const toggleExpand = React.useCallback((pathKey: string) => {
    setExpandedPaths((prev) => {
      const next = new Set(prev);
      if (next.has(pathKey)) next.delete(pathKey);
      else next.add(pathKey);
      return next;
    });
  }, []);

  const [viewMode, setViewMode] = React.useState<RunDataViewMode>('tree');

  // Clear when the user switches to a different workflow / step / run / column.
  // React reconciles parents (UnifiedNodeOutput, ParentNodesDataPreview, …) by
  // tree position, so this RunDataPreview instance is reused with new props
  // instead of being remounted - without this reset, paths opened on node A
  // would re-apply to node B's structurally-similar tree (auto-expanding nodes
  // the user never asked to see). workflowId in the deps is technically
  // redundant (a workflow swap also unmounts the inspector), but listed
  // explicitly so the contract is unambiguous.
  React.useEffect(() => {
    setExpandedPaths(new Set());
    setViewMode('tree');
  }, [workflowId, stepAlias, runId, dataType]);
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
    dataType,
    enabled: !!workflowId && !!runId && !!stepAlias,
    statusFilter: activeStatusFilter,
  });

  // State for loaded data
  const [data, setData] = React.useState<any>(null);
  const [isLoadingData, setIsLoadingData] = React.useState(false);

  const { liveState, pendingSignals } = useNodeLiveState(node, { isRunMode });

  // This instance is REUSED across node switches (see the expandedPaths reset
  // above): without this reset, node A's data would keep rendering (and keep
  // being published below) when node B has nothing to load (zero items /
  // error) - the load effect early-returns in those cases and would never
  // overwrite it.
  React.useEffect(() => {
    setData(null);
  }, [workflowId, stepAlias, runId, dataType]);

  // Publish the loaded object to the caller (ref: identity changes must not
  // re-run the effect). null while the hook or the object fetch is loading, on
  // a hook error, when there is nothing to display (zero items), and on
  // unmount - a consumer acting on the published value (the Output header's
  // "Use as mock output") must NEVER see another node's stale object.
  const onLoadedOutputChangeRef = React.useRef(onLoadedOutputChange);
  onLoadedOutputChangeRef.current = onLoadedOutputChange;
  const showsLoadedData = !isLoading && !error && totalItems > 0 && !isLoadingData;
  React.useEffect(() => {
    onLoadedOutputChangeRef.current?.(showsLoadedData ? data : null);
  }, [data, showsLoadedData]);
  React.useEffect(() => {
    return () => {
      onLoadedOutputChangeRef.current?.(null);
    };
  }, []);

  // Load data when the displayed row changes. Keyed on currentItem.id (not
  // just the index): a page merge or targeted jump can swap WHICH row sits at
  // the same index. loadSeqRef discards out-of-order responses - without it a
  // slow fetch for the previous row can resolve last and overwrite the data of
  // the row the navigator now points at (label/content desync).
  const loadSeqRef = React.useRef(0);
  React.useEffect(() => {
    if (totalItems === 0) {
      return;
    }

    const seq = ++loadSeqRef.current;
    const loadData = async () => {
      setIsLoadingData(true);
      try {
        // getObjectAtPath('') will fetch the full input/output object
        const result = await getObjectAtPath('');
        if (seq !== loadSeqRef.current) return; // stale response - a newer row is displayed
        setData(result);
      } catch (err) {
        if (seq !== loadSeqRef.current) return;
        console.error(`Failed to load ${dataType} data:`, err);
        setData(null);
      } finally {
        if (seq === loadSeqRef.current) setIsLoadingData(false);
      }
    };

    loadData();
  }, [currentIndex, currentItem?.id, totalItems, getObjectAtPath, dataType, stepAlias]);

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

  // No data state - keep the navigator visible (with filter) so users can
  // clear an over-restrictive status filter without re-opening the column.
  // A node that is executing right now, or parked on a signal, has no step row
  // to fetch yet: say that instead of "no data", which reads as "it produced
  // nothing".
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
        {liveState ? (
          <NodeRunStateNotice
            state={liveState}
            column={dataType === 'input' ? 'params' : 'output'}
            pendingSignals={pendingSignals}
          />
        ) : (
          <div className="py-4 text-center">
            <Database className="h-6 w-6 mx-auto mb-2 text-slate-300 dark:text-slate-600" />
            <p className="text-sm text-slate-500">{t('noData')}</p>
          </div>
        )}
      </div>
    );
  }

  const isOutputMocked = dataType === 'output' && isMockedOutput(data);
  const tableAvailable = hasTableView(data);
  const effectiveViewMode: RunDataViewMode =
    viewMode === 'table' && !tableAvailable ? 'tree' : viewMode;

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

      {/* Mocked pill - provenance of the loaded output object ("Use as mock
          output" itself lives in the Output header's settings menu) */}
      {isOutputMocked && (
        <div className="flex items-center gap-2">
          <span
            data-testid="run-data-mocked-pill"
            className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs font-medium bg-indigo-100 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300"
          >
            <FlaskConical className="h-3 w-3" />
            {tMock('mockedBadge')}
          </span>
        </div>
      )}

      {/* Error/Skip banners from envelope fields */}
      {data?._error && <OutputError error={data._error} />}
      {data?._skip_reason && <SkipBanner reason={data._skip_reason} sourceNode={data._skip_source_node} />}

      {/* Data */}
      {isLoadingData ? (
        <div className="space-y-2">
          {[1, 2, 3].map((i) => (
            <div
              key={i}
              className="h-5 w-full rounded bg-slate-200 dark:bg-slate-700 animate-pulse"
            />
          ))}
        </div>
      ) : data !== null ? (
        <>
          <RunDataViewTabs
            mode={effectiveViewMode}
            onModeChange={setViewMode}
            tableAvailable={tableAvailable}
            copyValue={data}
            columnId={dataType === 'input' ? 'params' : 'output'}
          />
          {effectiveViewMode === 'json' ? (
            <RawJsonView data={data} />
          ) : effectiveViewMode === 'table' ? (
            // The WHOLE payload, not the pre-picked rows: JsonTableView names the
            // field it lays out and offers a selector when several qualify, and it
            // can do neither once the caller has already thrown the other fields away.
            <JsonTableView data={data} />
          ) : (
            <JsonValueTree
              data={data}
              isDraggable={isDraggable}
              dragPrefix={dragPrefix}
              path={[]}
              showBorder={false}
              expandedPaths={expandedPaths}
              onToggleExpand={toggleExpand}
            />
          )}
        </>
      ) : (
        <EmptyState message={t('noData')} />
      )}
    </div>
  );
}
