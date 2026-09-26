/**
 * ResolvedParamsView - the Params column in run mode.
 *
 * Shows what a node actually ran with: its `resolved_params`, rendered through
 * the same tree as the Output column (JsonDataTree) but with top-level keys
 * relabelled from the input-label registry, so "duration" reads "Duration (ms)".
 *
 * Two things it does beyond displaying the payload:
 *
 *  - While the node is still executing or parked on a signal there is no step
 *    row to fetch, so it falls back to the parameters the node was LAUNCHED
 *    with (the configured expressions) instead of an empty panel.
 *  - After the run it lines the configured parameters up against the reported
 *    keys and calls out any the run did not report - the front/back naming
 *    drift that used to be invisible (see runParamAlignment).
 */

'use client';

import * as React from 'react';
import { Database } from 'lucide-react';
import { useTranslations } from 'next-intl';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useRunData } from '../../../hooks/useRunData';
import { useNodeLiveState } from '../../../hooks/useNodeLiveState';
import { ItemNavigator, ALL_STATUSES_VALUE } from './ItemNavigator';
import { JsonValueTree, PrimitiveValue } from './JsonDataTree';
import { NodeRunStateNotice } from './NodeRunStateNotice';
import { RunDataViewTabs, RawJsonView, JsonTableView, type RunDataViewMode } from './RunDataViews';
import { hasTableView } from './runValueUtils';
import {
  mergeResolvedAliases,
} from './runParamAlignment';
import { collectDeclaredParams } from './declaredParams';
import type { StatusType } from '@/components/ui/StatusBadge';
import { detectNodeType } from '../core/types';
import { getInputLabel, humanizeKey } from '../registry/input-label-registry';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
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
  const t = useTranslations('workflowBuilder.inspector.runData');
  const ti = useTranslations('workflowBuilder.inspector');
  const { isRunMode } = useWorkflowMode();
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
  const [viewMode, setViewMode] = React.useState<RunDataViewMode>('tree');
  const [expandedPaths, setExpandedPaths] = React.useState<Set<string>>(() => new Set());

  const toggleExpand = React.useCallback((pathKey: string) => {
    setExpandedPaths((prev) => {
      const next = new Set(prev);
      if (next.has(pathKey)) next.delete(pathKey);
      else next.add(pathKey);
      return next;
    });
  }, []);

  // Same contract as RunDataPreview: this instance is REUSED across node
  // switches, so the open paths and the loaded object have to be reset when the
  // panel starts pointing at something else.
  React.useEffect(() => {
    setExpandedPaths(new Set());
    setData(null);
    setViewMode('tree');
  }, [workflowId, stepAlias, runId]);

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

  const { liveState, pendingSignals } = useNodeLiveState(node, { isRunMode });
  // What the node declares in edit mode, read through the REAL plan generator so it is
  // exactly what the backend was handed. This is the panel's answer while there is no step
  // row yet: the node is still executing, or parked on a signal, or it left no row at all.
  //
  // MCP tool nodes are INCLUDED. They were excluded, and the reason given was that the
  // catalog owns their parameter names so a drift comparison has nothing to say about them.
  // That reason is about the COMPARISON; this value only feeds the display. The cost of the
  // confusion fell on the one node that spends real time RUNNING, because it is the one
  // waiting on a third party: a catalog step showed an empty Params column for the whole
  // call, and again afterwards on any path that leaves no row. Its labels already come from
  // the tool's own schema through `toolParamLabels`, so there was nothing for the exclusion
  // to protect.
  //
  // Every other node family already answers from the node alone, verified type by type
  // against the generator's own field names: `processEdgesV2` registers decision, switch,
  // split, option, fork, approval and while-group by walking the NODES, so their
  // configuration is here without the graph. A `merge` reports nothing because it has no
  // parameters of its own, which is honest rather than blind.
  //
  // Keyed on what the plan generator actually reads - node.id, node.data and node.type -
  // rather than the node object: ReactFlow re-creates that object on every status tick and
  // every drag frame, and this runs the REAL plan generator. `nodeType` is not a substitute
  // for `node.type`: detectNodeType collapses several node.type values onto one inspector
  // type, so a change between two of them would leave a stale plan entry.
  // Suppressed rather than satisfied: both rules want `node` itself in the deps, which is
  // the bug. Passing a reconstructed `{id, type, data}` would silence them honestly but
  // would also feed the plan generator a node this file invented, so the narrower lie is
  // the suppression.
  /* eslint-disable react-hooks/exhaustive-deps, react-hooks/memo-dependencies */
  const configuredParams = React.useMemo(
    () => collectDeclaredParams(node, nodeType),
    [node.id, node.type, node.data, nodeType],
  );
  /* eslint-enable react-hooks/exhaustive-deps, react-hooks/memo-dependencies */

  const merged = React.useMemo(
    () => (data && typeof data === 'object' && !Array.isArray(data) ? mergeResolvedAliases(data) : null),
    [data],
  );


  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-6">
        <LoadingSpinner size="xs" />
        <span className="ml-2 text-sm text-slate-500">{ti('loading')}</span>
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
    const configuredEntries = Object.entries(configuredParams);
    return (
      <div className="space-y-2" data-testid="resolved-params-view">
        <ItemNavigator
          currentIndex={0}
          totalItems={0}
          onIndexChange={goToIndex}
          itemLabel={ti('item')}
          statusOptions={availableStatuses}
          statusFilter={statusFilter}
          onStatusFilterChange={setStatusFilter}
        />
        {liveState ? (
          <>
            <NodeRunStateNotice state={liveState} column="params" pendingSignals={pendingSignals} />
            {configuredEntries.length > 0 && (
              <ConfiguredParamsList entries={configuredEntries} getLabel={getLabel} />
            )}
          </>
        ) : (
          <>
            <div className="py-4 text-center">
              <Database className="h-6 w-6 mx-auto mb-2 text-slate-300 dark:text-slate-600" />
              <p className="text-sm text-slate-500">{t('noResolvedParams')}</p>
            </div>
            {/* The configuration, under its own heading, when this run left no row for the
                node: it was skipped, or the branch was never taken, or the run ended above
                it. An empty panel makes the reader open the node in edit mode to learn what
                it would have run with, and says nothing about WHY there is no row. The
                heading keeps the two apart: this is what the node is configured with, not
                what it resolved. */}
            {configuredEntries.length > 0 && (
              <ConfiguredParamsList entries={configuredEntries} getLabel={getLabel} />
            )}
          </>
        )}
      </div>
    );
  }

  const tableAvailable = hasTableView(merged);
  const effectiveViewMode: RunDataViewMode =
    viewMode === 'table' && !tableAvailable ? 'tree' : viewMode;

  return (
    <div className="space-y-2" data-testid="resolved-params-view">
      <ItemNavigator
        currentIndex={currentIndex}
        totalItems={totalItems}
        onIndexChange={goToIndex}
        itemLabel={ti('item')}
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
      ) : merged ? (
        <>
          <RunDataViewTabs
            mode={effectiveViewMode}
            onModeChange={setViewMode}
            tableAvailable={tableAvailable}
            copyValue={merged}
            columnId="params"
          />
          {Object.keys(merged).length === 0 ? (
            <p className="py-4 text-center text-sm text-slate-500">{t('noResolvedParams')}</p>
          ) : effectiveViewMode === 'json' ? (
            <RawJsonView data={merged} />
          ) : effectiveViewMode === 'table' ? (
            // The WHOLE payload - see the note in RunDataPreview: pre-picking here
            // is what made the table an unlabelled subset of the tree.
            <JsonTableView data={merged} />
          ) : (
            <JsonValueTree
              data={merged}
              path={[]}
              showBorder={false}
              expandedPaths={expandedPaths}
              onToggleExpand={toggleExpand}
              labelForKey={getLabel}
            />
          )}
        </>
      ) : data !== null ? (
        <PrimitiveValue value={data} />
      ) : (
        <p className="py-4 text-center text-sm text-slate-500">{t('noResolvedParams')}</p>
      )}
    </div>
  );
}

/**
 * The same ceiling the backend puts on a reported value, applied to the configured one.
 *
 * A code node keeps its whole source in the plan and an sftp upload its whole payload, and
 * this list renders on every node the run left no row for. Without a cap a skipped code node
 * prints its entire source inline. The number matches ResolvedValuePreview.MAX_SCALAR_CHARS,
 * so a value reads the same length here as it does once the node has run.
 */
const MAX_CONFIGURED_CHARS = 120;

function shortenConfigured(value: unknown): string {
  const text = typeof value === 'string' ? value : JSON.stringify(value) ?? '';
  return text.length > MAX_CONFIGURED_CHARS ? `${text.slice(0, MAX_CONFIGURED_CHARS)}…` : text;
}


/**
 * The parameters a node is CONFIGURED with, shown when the run has no row for it (still
 * running, parked, skipped, never reached): raw expressions, unresolved by definition. The
 * heading says so, because under "Launched with" a raw {{...}} read as a reference the node
 * had failed to resolve. The full value is on hover; the inline text is capped.
 */
function ConfiguredParamsList({
  entries,
  getLabel,
}: {
  entries: Array<[string, unknown]>;
  getLabel: (key: string) => string;
}) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  return (
    <div className="space-y-1">
      <p className="px-1 text-sm font-semibold uppercase tracking-wider text-slate-500 dark:text-slate-400">
        {t('configuredParamsTitle')}
      </p>
      {entries.map(([key, expression]) => (
        <div
          key={key}
          className="flex items-start gap-2 rounded-sm px-1 py-1 text-sm text-[var(--text-primary)]"
        >
          <span className="truncate max-w-[120px] flex-shrink-0" title={getLabel(key)}>
            {getLabel(key)}
          </span>
          <span className="flex-shrink-0 text-slate-400">:</span>
          <span
            className="min-w-0 break-all font-mono text-sm text-slate-600 dark:text-slate-300"
            title={typeof expression === 'string' ? expression : JSON.stringify(expression) ?? ''}
          >
            {shortenConfigured(expression)}
          </span>
        </div>
      ))}
    </div>
  );
}

