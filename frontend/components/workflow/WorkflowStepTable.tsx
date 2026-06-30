'use client';

import { useMemo, useState, useCallback } from 'react';
import { useTranslations } from 'next-intl';
import { Search } from 'lucide-react';
import DataTable from '@/components/DataTable';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { StatusBadge, type StatusType } from '@/components/ui/StatusBadge';
import { useStepData } from '@/app/workflows/builder/hooks/useStepData';
import type { DataSourceItemRow, ServerFilters } from '@/components/data-table/types';

export interface WorkflowStepTableProps {
  workflowId: string;
  runId: string;
  stepAlias: string;
  jsonPath?: string;
  onNavigate?: (path: string) => void;
  showIdColumn?: boolean;
}

const STATUS_OPTIONS: StatusType[] = [
  'completed',
  'failed',
  'running',
  'pending',
  'skipped',
  'cancelled',
  'timeout',
  'partial_success',
];

const ALL_STATUSES = '__all__';
const ALL_EPOCHS = '__all__';

function rowMatchesSearch(row: DataSourceItemRow, query: string): boolean {
  if (!query) return true;
  const needle = query.toLowerCase();
  const data = (row as { data?: Record<string, unknown> }).data;
  if (!data) return false;
  for (const value of Object.values(data)) {
    if (value == null) continue;
    if (typeof value === 'string') {
      if (value.toLowerCase().includes(needle)) return true;
    } else if (typeof value === 'number' || typeof value === 'boolean') {
      if (String(value).toLowerCase().includes(needle)) return true;
    } else {
      try {
        if (JSON.stringify(value).toLowerCase().includes(needle)) return true;
      } catch {
        // skip values that can't be stringified (cycles, etc.)
      }
    }
  }
  return false;
}

/**
 * Table component for displaying workflow step outputs.
 * Wraps DataTable with workflow-specific context.
 *
 * Toolbar (search + status select + epoch select):
 *  - status & epoch are forwarded server-side via {@link DataTable}'s
 *    {@code serverFilters} so totals + page count reflect the filtered set.
 *  - search stays client-side because the backend has no full-text predicate;
 *    it scopes to the current page's rows (page size = 20 by default).
 */
export function WorkflowStepTable({
  workflowId,
  runId,
  stepAlias,
  jsonPath,
  onNavigate,
  showIdColumn = true,
}: WorkflowStepTableProps) {
  const t = useTranslations('dataTable');
  const tCommon = useTranslations('common');
  const tRunSteps = useTranslations('workflow.runSteps');

  const [searchQuery, setSearchQuery] = useState('');
  const [statusValue, setStatusValue] = useState<string>(ALL_STATUSES);
  const [epochValue, setEpochValue] = useState<string>(ALL_EPOCHS);

  // Fetch step rows to derive the available epoch values for the filter Select.
  // useStepData is also used by useRunOutputData (inspector panel) for the
  // status filter dropdown, sharing the {@code ['step-data', ...]} cache key
  // - when both this table and the inspector are mounted on the same alias,
  // a single fetch satisfies both consumers.
  const { stepData } = useStepData(runId, stepAlias);
  // Most-recent epoch first - matches the inspector "Item N / N = latest"
  // navigator semantics, so the top of the dropdown is the freshest run.
  const availableEpochs = useMemo<number[]>(() => {
    const set = new Set<number>();
    for (const s of stepData ?? []) {
      if (typeof s.epoch === 'number') set.add(s.epoch);
    }
    return Array.from(set).sort((a, b) => b - a);
  }, [stepData]);

  // Memoize workflowContext to prevent infinite re-renders
  const workflowContext = useMemo(() => ({
    workflowId,
    runId,
    stepAlias,
  }), [workflowId, runId, stepAlias]);

  // Client-side filter for the search box only - status/epoch are pushed
  // server-side via serverFilters below so totals/pagination stay correct.
  const rowFilter = useMemo(() => {
    const hasSearch = searchQuery.trim().length > 0;
    if (!hasSearch) return null;
    const query = searchQuery.trim();
    return (row: DataSourceItemRow) => rowMatchesSearch(row, query);
  }, [searchQuery]);

  const serverFilters = useMemo<ServerFilters>(() => ({
    status: statusValue === ALL_STATUSES ? null : statusValue,
    epoch: epochValue === ALL_EPOCHS ? null : Number(epochValue),
  }), [statusValue, epochValue]);

  const handleStatusChange = useCallback((next: string) => setStatusValue(next), []);
  const handleEpochChange = useCallback((next: string) => setEpochValue(next), []);

  return (
    <div className="w-full h-full flex flex-col overflow-hidden">
      <div className="flex flex-col sm:flex-row sm:flex-wrap sm:items-center gap-2 mb-3 flex-shrink-0">
        <div className="relative flex-1 min-w-0 w-full sm:w-auto">
          <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-theme-muted pointer-events-none" />
          <Input
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder={t('searchPlaceholder')}
            className="pl-10 w-full focus-visible:ring-inset focus-visible:ring-offset-0"
            aria-label={t('searchPlaceholder')}
          />
        </div>
        <Select value={statusValue} onValueChange={handleStatusChange}>
          <SelectTrigger
            className="w-full sm:w-44 flex-shrink-0 focus:ring-inset"
            aria-label={tCommon('status')}
          >
            <SelectValue placeholder={tCommon('status')} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL_STATUSES}>
              <span className="text-sm font-medium">{t('allStatuses')}</span>
            </SelectItem>
            {STATUS_OPTIONS.map((s) => (
              <SelectItem key={s} value={s}>
                <StatusBadge status={s} variant="noBackground" />
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select
          value={epochValue}
          onValueChange={handleEpochChange}
          disabled={availableEpochs.length === 0}
        >
          <SelectTrigger
            className="w-full sm:w-36 flex-shrink-0 focus:ring-inset"
            aria-label="Epoch"
          >
            <SelectValue placeholder="Epoch" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL_EPOCHS}>
              <span className="text-sm font-medium">{tRunSteps('allEpochs')}</span>
            </SelectItem>
            {availableEpochs.map((e) => (
              <SelectItem key={e} value={String(e)}>
                <span className="text-sm font-medium">Epoch {e}</span>
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      <div className="flex-1 min-h-0 overflow-hidden">
        <DataTable
          dataSourceId={0}
          jsonPath={jsonPath}
          workflowContext={workflowContext}
          onNavigate={onNavigate}
          showIdColumn={showIdColumn}
          readOnly
          rowFilter={rowFilter}
          serverFilters={serverFilters}
          infiniteScroll
        />
      </div>
    </div>
  );
}
