'use client';

import * as React from 'react';
import { useStepData, WorkflowStepData } from '../../hooks/useStepData';
import { useAggregatedSteps, AggregatedStepData } from '../../hooks/useAggregatedSteps';
import LoadingSpinner from '@/components/LoadingSpinner';
import { StatusBadge, StatusType, mapBackendStatusToStatusType } from '@/components/ui/StatusBadge';
import { Eye } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Breadcrumb } from '@/components/ui/breadcrumb';
import { StepDataTableView } from './StepDataTableView';
import { EmptyState } from '../shared/EmptyState';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { LoadOlderSentinel } from '@/components/agent-fleet/LoadOlderSentinel';

interface StepDataTableProps {
  runId: string | undefined;
  stepAlias: string | undefined;
  workflowId?: string;
  onBreadcrumbChange?: (items: Array<{ label: string; onClick?: () => void }>) => void;
}

export function StepDataTable({ runId, stepAlias, workflowId, onBreadcrumbChange }: StepDataTableProps) {
  // Use aggregated steps when stepAlias is not provided (result mode)
  // Use individual step data when stepAlias is provided
  const useAggregated = !stepAlias;
  const [selectedAlias, setSelectedAlias] = React.useState<string | null>(null);

  // When an alias is selected from aggregated view, load individual steps for that alias
  const effectiveStepAlias = selectedAlias || stepAlias;

  // When an alias is selected from aggregated view, load individual steps for that alias.
  // The hook now uses useInfiniteQuery (backend cap 500/page); hasNextPage + fetchNextPage
  // power the LoadOlderSentinel at the bottom of the table - so workflows like Daily Email
  // Digest with 3490 rows per alias surface all rows instead of being silently truncated
  // to the latest page (audit 2026-05-13).
  const {
    stepData,
    loading: loadingSteps,
    error: errorSteps,
    hasNextPage: stepsHasNextPage,
    fetchNextPage: stepsFetchNextPage,
    isFetchingNextPage: stepsIsFetchingNextPage,
    totalElements: stepsTotalElements,
  } = useStepData(runId, effectiveStepAlias);

  // Capture the scroll container so the IntersectionObserver fires when the user
  // scrolls inside the table's own overflow:auto, not the viewport.
  const tableScrollRef = React.useRef<HTMLDivElement | null>(null);

  const handleLoadMoreSteps = React.useCallback(() => {
    if (stepsHasNextPage && !stepsIsFetchingNextPage) {
      void stepsFetchNextPage();
    }
  }, [stepsHasNextPage, stepsIsFetchingNextPage, stepsFetchNextPage]);
  const { aggregatedSteps, loading: loadingAggregated, error: errorAggregated } = useAggregatedSteps(useAggregated && !selectedAlias ? runId : undefined);
  
  const loading = useAggregated && !selectedAlias ? loadingAggregated : loadingSteps;
  const error = useAggregated && !selectedAlias ? errorAggregated : errorSteps;
  const [jsonPath, setJsonPath] = React.useState<string>('');
  const [selectedStepId, setSelectedStepId] = React.useState<number | null>(null);
  
  // Handle click on aggregated alias to show individual items
  const handleAliasClick = React.useCallback((alias: string) => {
    setSelectedAlias(alias);
    setSelectedStepId(null);
    setJsonPath('');
  }, []);
  
  // Handle back to aggregated view
  const handleBackToAggregated = React.useCallback(() => {
    setSelectedAlias(null);
    setSelectedStepId(null);
    setJsonPath('');
  }, []);
  
  // Find the step that matches stepAlias or selectedAlias
  const currentStep = React.useMemo(() => {
    const aliasToFind = selectedAlias || stepAlias;
    if (!aliasToFind || !stepData) return null;
    return stepData.find(s => s.stepAlias === aliasToFind);
  }, [stepAlias, selectedAlias, stepData]);
  
  // Find the step that matches selectedStepId for output view
  const selectedStep = React.useMemo(() => {
    if (!selectedStepId || !stepData) return null;
    return stepData.find(s => s.id === selectedStepId);
  }, [selectedStepId, stepData]);
  
  const handleViewOutput = (e: React.MouseEvent, outputStorageId: string) => {
    // Prevent event propagation to avoid closing the modal/panel
    e.stopPropagation();
    e.preventDefault();
    
    // Find the step with this outputStorageId
    const step = stepData.find(s => s.outputStorageId === outputStorageId);
    if (step) {
      setSelectedStepId(step.id);
      setJsonPath(''); // Reset path when viewing a new step
    }
  };
  
  const handleNavigate = (newPath: string) => {
    // Update breadcrumb path without changing URL
    setJsonPath(newPath);
  };
  
  const breadcrumbItems = React.useMemo(() => {
    const items: Array<{ label: string; onClick?: () => void }> = [];
    
    // If in aggregated mode and viewing a specific alias, add back to aggregated option
    if (useAggregated && selectedAlias) {
      items.push({
        label: 'Aggregated Steps',
        onClick: handleBackToAggregated,
      });
      // Always show the alias, even if we're viewing output
      items.push({
        label: selectedAlias,
        onClick: selectedStepId ? () => {
          setSelectedStepId(null);
          setJsonPath('');
        } : undefined,
      });
    } else {
      // Always add "Workflow Steps" as first item
      items.push({
        label: 'Workflow Steps',
        onClick: selectedStepId ? () => {
          setSelectedStepId(null);
          setJsonPath('');
        } : undefined,
      });
      
      // Add workflowId if available
      if (workflowId) {
        items.push({
          label: workflowId.substring(0, 8),
        });
      }
      
      // Add runId if available
      if (runId) {
        items.push({
          label: runId,
        });
      }
      
      // Add stepAlias if we're viewing a step (either in the list or in output view)
      if (currentStep) {
        items.push({
          label: currentStep.stepAlias || 'Unknown',
          onClick: selectedStepId ? () => {
            setSelectedStepId(null);
            setJsonPath('');
          } : undefined,
        });
      }
    }
    
    // Add path segments if navigating in JSON (works for both aggregated and non-aggregated modes)
    if (jsonPath && selectedStepId) {
      const pathSegments = jsonPath.split('.').filter(segment => segment.length > 0);
      pathSegments.forEach((segment, index) => {
        const pathUpToSegment = pathSegments.slice(0, index + 1).join('.');
        items.push({
          label: segment,
          onClick: () => setJsonPath(pathUpToSegment),
        });
      });
    }
    
    return items;
  }, [workflowId, runId, currentStep, selectedStepId, jsonPath, useAggregated, selectedAlias, handleBackToAggregated]);

  // Notify parent component of breadcrumb changes
  React.useEffect(() => {
    if (onBreadcrumbChange) {
      onBreadcrumbChange(breadcrumbItems);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [breadcrumbItems]); // onBreadcrumbChange is stable from parent, no need in deps

  if (loading) {
    return (
      <div className="flex items-center justify-center py-8">
        <LoadingSpinner size="md" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="text-sm text-red-600 p-4">
        Error loading step data: {error}
      </div>
    );
  }

  // If a step is selected, show its output with StepDataTableView
  if (selectedStepId && selectedStep && workflowId && runId) {
    return (
      <StepDataTableView
        stepId={selectedStepId}
        stepAlias={selectedStep.stepAlias}
        workflowId={workflowId}
        runId={runId}
        jsonPath={jsonPath}
        onNavigate={handleNavigate}
      />
    );
  }

  // Render aggregated steps table when in result mode (no stepAlias)
  if (useAggregated && !selectedAlias) {
    if (!aggregatedSteps || aggregatedSteps.length === 0) {
      return <EmptyState message="No aggregated step data available for this run" className="p-4 text-center" />;
    }

    const formatDate = (dateStr: string | null): string => {
      if (!dateStr) return '-';
      try {
        return formatUtcDateTime(dateStr);
      } catch {
        return dateStr;
      }
    };

    const formatStatusCounts = (counts?: AggregatedStepData['statusCounts']): string => {
      if (!counts) return '-';
      const parts: string[] = [];
      if (counts.completed) parts.push(`${counts.completed} completed`);
      if (counts.failed) parts.push(`${counts.failed} failed`);
      if (counts.skipped) parts.push(`${counts.skipped} skipped`);
      if (counts.running) parts.push(`${counts.running} running`);
      return parts.length > 0 ? parts.join(', ') : '-';
    };

    return (
      <div className="w-full h-full flex flex-col overflow-hidden">
        <div className="min-h-0 overflow-x-auto overflow-y-auto border border-theme rounded-xl" style={{ flex: '0 1 auto' }}>
          <table className="w-full text-sm" style={{ tableLayout: 'auto' }}>
            <thead className="bg-theme-secondary border-b border-theme sticky top-0 z-20">
              <tr>
                <th className="px-3 py-3 text-center font-medium text-theme-primary w-12 sticky left-0 z-30 bg-theme-secondary">
                  #
                </th>
                <th className="px-3 py-3 text-left font-medium text-theme-primary whitespace-nowrap">
                  Status
                </th>
                <th className="px-3 py-3 text-left font-medium text-theme-primary whitespace-nowrap">
                  Alias
                </th>
                <th className="px-3 py-3 text-left font-medium text-theme-primary whitespace-nowrap">
                  Tool ID
                </th>
                <th className="px-3 py-3 text-left font-medium text-theme-primary whitespace-nowrap">
                  Start Time
                </th>
                <th className="px-3 py-3 text-left font-medium text-theme-primary whitespace-nowrap">
                  End Time
                </th>
                <th className="px-3 py-3 text-left font-medium text-theme-primary whitespace-nowrap">
                  Status Counts
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-theme">
              {aggregatedSteps.map((step, index) => (
                <tr
                  key={step.alias}
                  className="border border-transparent transition-colors hover-row-datasource cursor-pointer h-14"
                  onClick={() => handleAliasClick(step.alias)}
                >
                  <td className="px-3 py-2 w-12 min-w-[48px] sticky left-0 z-10 text-center font-medium text-theme-primary bg-theme-primary">
                    {index + 1}
                  </td>
                  <td className="px-3 py-2 text-theme-primary whitespace-nowrap">
                    <StatusBadge status={mapBackendStatusToStatusType(step.status)} variant="noBackground" />
                  </td>
                  <td className="px-3 py-2 text-theme-primary whitespace-nowrap">
                    <span className="font-medium text-sm">{step.alias}</span>
                  </td>
                  <td className="px-3 py-2 text-theme-primary whitespace-nowrap">
                    <span className="font-mono text-xs text-theme-secondary">{step.toolId}</span>
                  </td>
                  <td className="px-3 py-2 text-theme-primary whitespace-nowrap">
                    <span className="text-sm">{formatDate(step.startTime)}</span>
                  </td>
                  <td className="px-3 py-2 text-theme-primary whitespace-nowrap">
                    <span className="text-sm">{formatDate(step.endTime)}</span>
                  </td>
                  <td className="px-3 py-2 text-theme-primary whitespace-nowrap">
                    <span className="text-xs text-theme-secondary">{formatStatusCounts(step.statusCounts)}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    );
  }

  // Render individual step data table when stepAlias is provided or when an alias is selected from aggregated view
  // Show back button if we're in aggregated mode and viewing a specific alias
  if (useAggregated && selectedAlias) {
    if (loading) {
      return (
        <div className="flex items-center justify-center py-8">
          <LoadingSpinner size="md" />
        </div>
      );
    }
    
    if (error) {
      return (
        <div className="text-sm text-red-600 p-4">
          Error loading step data: {error}
        </div>
      );
    }
    
    if (!stepData || stepData.length === 0) {
      return <EmptyState message={`No step data available for alias: ${selectedAlias}`} className="p-4 text-center" />;
    }
  }
  
  if (!stepData || stepData.length === 0) {
    return <EmptyState message="No step data available for this node" className="p-4 text-center" />;
  }

  // Check if any step has condition evaluation data (from metadata or from dedicated fields)
  const hasConditionData = stepData.some(step => {
    const metadata = step.metadata || {};
    return metadata.conditionEvaluations || metadata.conditionalSelection || metadata.conditionalSkip
      || step.conditionExpression || step.selectedBranch;
  });

  // Check which node-type-specific columns have data
  const hasNodeType = stepData.some(step => step.nodeType);
  const hasNormalizedKey = stepData.some(step => step.normalizedKey);
  const hasLoopData = stepData.some(step => step.loopId || step.loopIteration != null || step.loopExitReason);
  const hasMergeData = stepData.some(step => step.mergeStrategy || step.mergeReceivedBranches?.length || step.mergeSkippedBranches?.length);
  const hasSkipData = stepData.some(step => step.skipReason || step.skipSourceNode);
  const hasItemNumber = stepData.some(step => step.itemNumber != null);

  // Condition-related metadata shown in dedicated column
  const CONDITION_METADATA_KEYS = new Set([
    'conditionEvaluations', 'conditionalSelection', 'conditionalSkip',
  ]);

  // Build columns data-driven: only include fields that have actual data
  const allKeys = new Set<string>();
  stepData.forEach(step => {
    if (step.inputData) {
      Object.keys(step.inputData).forEach(key => allKeys.add(`input.${key}`));
    }
    if (step.metadata) {
      Object.keys(step.metadata).forEach(key => {
        if (!CONDITION_METADATA_KEYS.has(key)) {
          allKeys.add(`metadata.${key}`);
        }
      });
    }
    // Standard fields - only add when they have data
    if (step.status) allKeys.add('status');
    if (step.itemIndex != null && step.itemIndex > 0) allKeys.add('itemIndex');
    if ((step as any).iteration != null) allKeys.add('iteration');
    if ((step as any).epoch != null) allKeys.add('epoch');
    if ((step as any).spawn) allKeys.add('spawn');
    if (step.startTime) allKeys.add('startTime');
    if (step.endTime) allKeys.add('endTime');
    if (step.httpStatus) allKeys.add('httpStatus');
    if (step.errorMessage) allKeys.add('errorMessage');
  });

  // Add response column if any step has outputStorageId
  const hasOutput = stepData.some(step => step.outputStorageId);
  if (hasOutput) {
    allKeys.add('response');
  }

  // Add condition column if any step has condition data
  if (hasConditionData) {
    allKeys.add('conditions');
  }

  // Add node-type-specific columns only when data exists
  if (hasNodeType) allKeys.add('nodeType');
  if (hasNormalizedKey) allKeys.add('normalizedKey');
  if (hasLoopData) {
    allKeys.add('loopId');
    allKeys.add('loopIteration');
    allKeys.add('loopExitReason');
  }
  if (hasMergeData) {
    allKeys.add('mergeStrategy');
    allKeys.add('mergeReceivedBranches');
    allKeys.add('mergeSkippedBranches');
  }
  if (hasSkipData) {
    allKeys.add('skipReason');
    allKeys.add('skipSourceNode');
  }
  if (hasItemNumber) allKeys.add('itemNumber');

  // Organize columns: status first, then key identity fields, then response, conditions, then type-specific, then others
  const allColumns = Array.from(allKeys);
  const priorityColumns = [
    'status', 'nodeType', 'normalizedKey', 'itemIndex', 'itemNumber', 'response', 'conditions',
    'selectedBranch', 'conditionExpression', 'conditionResult',
    'loopId', 'loopIteration', 'loopExitReason',
    'mergeStrategy', 'mergeReceivedBranches', 'mergeSkippedBranches',
    'skipReason', 'skipSourceNode',
  ];
  const otherColumns = allColumns.filter(col => !priorityColumns.includes(col)).sort();

  // Build final column order: priority fields first (if they exist), then others
  const columns: string[] = [];
  for (const col of priorityColumns) {
    if (allColumns.includes(col)) columns.push(col);
  }
  columns.push(...otherColumns);

  const getValue = (step: WorkflowStepData, key: string): any => {
    if (key.startsWith('input.')) {
      const inputKey = key.replace('input.', '');
      return step.inputData?.[inputKey];
    }
    if (key.startsWith('metadata.')) {
      const metaKey = key.replace('metadata.', '');
      return step.metadata?.[metaKey];
    }
    return (step as any)[key];
  };

  const formatValue = (value: any): string => {
    if (value === null || value === undefined) return '-';
    if (Array.isArray(value)) return value.length > 0 ? value.join(', ') : '-';
    if (typeof value === 'object') return JSON.stringify(value);
    if (typeof value === 'boolean') return value ? 'true' : 'false';
    return String(value);
  };

  // Format condition data for display (uses dedicated fields first, then metadata fallback)
  const formatConditionData = (step: WorkflowStepData): string => {
    // Use dedicated fields first (from new columns)
    if (step.selectedBranch) {
      const expr = step.conditionExpression ? `: ${step.conditionExpression}` : '';
      const result = step.conditionResult != null ? ` = ${step.conditionResult}` : '';
      return `→ ${step.selectedBranch}${expr}${result}`;
    }

    // Fallback to metadata
    const metadata = step.metadata || {};

    // Check for conditionEvaluations (step SOURCE)
    if (metadata.conditionEvaluations && Array.isArray(metadata.conditionEvaluations)) {
      const evaluations = metadata.conditionEvaluations as any[];
      if (evaluations.length > 0) {
        const evalData = evaluations[0];
        const selectedBranch = evalData.selectedBranch || 'none';
        const branchType = evalData.selectedBranchType || 'unknown';
        return `→ ${selectedBranch} (${branchType})`;
      }
    }

    // Check for conditionalSelection (step TARGET selected)
    if (metadata.conditionalSelection) {
      const selection = metadata.conditionalSelection as any;
      const condition = selection.condition || selection.resolvedCondition || 'else';
      const branchType = selection.branchType || 'unknown';
      return `✓ Selected (${branchType}): ${condition}`;
    }

    // Check for conditionalSkip (step TARGET skipped)
    if (metadata.conditionalSkip) {
      const skip = metadata.conditionalSkip as any;
      const reason = skip.reason || skip.skipReason || 'Not selected';
      return `✗ Skipped: ${reason}`;
    }

    return '-';
  };

  // Human-readable column labels
  const columnLabels: Record<string, string> = {
    status: 'Status',
    nodeType: 'Node Type',
    normalizedKey: 'Key',
    itemIndex: 'Item Index',
    itemNumber: 'Item #',
    response: 'Response',
    conditions: 'Conditions',
    selectedBranch: 'Branch',
    conditionExpression: 'Condition',
    conditionResult: 'Result',
    loopId: 'Loop ID',
    loopIteration: 'Loop Iter.',
    loopExitReason: 'Exit Reason',
    mergeStrategy: 'Merge',
    mergeReceivedBranches: 'Received',
    mergeSkippedBranches: 'Skipped',
    skipReason: 'Skip Reason',
    skipSourceNode: 'Skip Source',
    iteration: 'Iteration',
    epoch: 'Epoch',
    spawn: 'Spawn',
    startTime: 'Start Time',
    endTime: 'End Time',
    httpStatus: 'HTTP',
    errorMessage: 'Error',
  };

  // Count line: shown when there are more pages to load OR when we have the
  // true total from the backend (totalElements). Helps the user see at a glance
  // that 100 rows ≠ everything for runs with thousands of steps.
  const loadedCount = stepData.length;
  const showCountHeader = stepsTotalElements > loadedCount;

  return (
    <div className="w-full h-full flex flex-col overflow-hidden">
      {showCountHeader && (
        <div className="px-3 py-2 text-xs text-theme-secondary">
          Showing {loadedCount} of {stepsTotalElements} rows - scroll down to load older
        </div>
      )}
      <div
        ref={tableScrollRef}
        className="min-h-0 flex-1 max-h-[calc(100vh-200px)] overflow-x-auto overflow-y-auto border border-theme rounded-xl"
      >
        <table className="w-full text-sm" style={{ tableLayout: 'auto' }}>
          <thead className="bg-theme-secondary border-b border-theme sticky top-0 z-20">
            <tr>
              <th className="px-3 py-3 text-center font-medium text-theme-primary w-12 sticky left-0 z-30 bg-theme-secondary">
                #
              </th>
              {columns.map((col) => (
                <th
                  key={col}
                  className="px-3 py-3 text-left font-medium text-theme-primary whitespace-nowrap"
                >
                  {columnLabels[col] || col}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-theme">
            {stepData.map((step, index) => (
              <tr
                key={step.id}
                className="border border-transparent transition-colors hover-row-datasource h-14"
              >
                <td className="px-3 py-2 w-12 min-w-[48px] sticky left-0 z-10 text-center font-medium text-theme-primary bg-theme-primary">
                  {index + 1}
                </td>
                {columns.map((col) => {
                  const value = getValue(step, col);
                  const isStatus = col === 'status';
                  const isResponseColumn = col === 'response';
                  const isConditionsColumn = col === 'conditions';
                  return (
                    <td
                      key={col}
                      className="px-3 py-2 text-theme-primary whitespace-nowrap"
                    >
                      {isStatus ? (
                        <StatusBadge status={mapBackendStatusToStatusType(value)} variant="noBackground" />
                      ) : isResponseColumn ? (
                        step.outputStorageId ? (
                          <Button
                            variant="ghost"
                            size="sm"
                            className="inline-flex items-center gap-1 text-xs text-blue-600 hover:text-blue-800 h-auto p-1"
                            onClick={(e) => handleViewOutput(e, step.outputStorageId!)}
                          >
                            View Response
                            <Eye className="h-3 w-3" />
                          </Button>
                        ) : (
                          <span className="text-sm text-theme-secondary">-</span>
                        )
                      ) : isConditionsColumn ? (
                        <span className="text-xs font-mono text-blue-600" title={JSON.stringify(step.metadata?.conditionEvaluations || step.metadata?.conditionalSelection || step.metadata?.conditionalSkip || {}, null, 2)}>
                          {formatConditionData(step)}
                        </span>
                      ) : (
                        <span className="text-sm">{formatValue(value)}</span>
                      )}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
        <LoadOlderSentinel
          hasMore={stepsHasNextPage}
          loading={stepsIsFetchingNextPage}
          onLoadOlder={handleLoadMoreSteps}
          placement="bottom"
          scrollRoot={tableScrollRef.current}
          loadingLabel="Loading older rows…"
          idleLabel="Scroll to load older rows"
        />
      </div>
    </div>
  );
}

