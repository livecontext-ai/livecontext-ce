'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import { useTranslations } from 'next-intl';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Input } from '@/components/ui/input';
import { OptionalSection } from '../OptionalSection';
import type { BuilderNodeData } from '../../../types';

type EventType = 'row_created' | 'row_updated' | 'row_deleted';
const ALL_EVENT_TYPES: EventType[] = ['row_created', 'row_updated', 'row_deleted'];

type FilterOperator =
  | '=' | '!=' | '>' | '>=' | '<' | '<='
  | 'in' | 'not_in'
  | 'contains' | 'starts_with' | 'ends_with'
  | 'is_null' | 'is_not_null';

const COMPARISON_OPERATORS: FilterOperator[] = [
  '=', '!=', '>', '>=', '<', '<=',
  'in', 'not_in',
  'contains', 'starts_with', 'ends_with',
  'is_null', 'is_not_null',
];

/**
 * Display-only label map. The wire format stays `=` (matches backend TriggerCreator
 * VALID_FILTER_OPERATORS + all tooling). We surface `==` in the UI because a single
 * `=` reads as an assignment to anyone with a programming reflex.
 */
const OPERATOR_LABELS: Record<FilterOperator, string> = {
  '=': '==',
  '!=': '!=',
  '>': '>',
  '>=': '>=',
  '<': '<',
  '<=': '<=',
  'in': 'in',
  'not_in': 'not in',
  'contains': 'contains',
  'starts_with': 'starts with',
  'ends_with': 'ends with',
  'is_null': 'is null',
  'is_not_null': 'is not null',
};

const VALUELESS_OPERATORS = new Set<FilterOperator>(['is_null', 'is_not_null']);

interface TablesTriggerParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  columns?: Array<{ name: string }> | undefined;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
}

/**
 * Event-driven tables trigger configuration.
 * Lets the user pick which row changes fire the workflow, and an optional server-side
 * filter so only matching rows wake it up.
 */
export function TablesTriggerParametersForm({
  data,
  columns,
  isRunMode = false,
  onUpdate,
}: TablesTriggerParametersFormProps) {
  const t = useTranslations('workflowBuilder.inspector.tablesTrigger');

  const dsData = data.dataSourceData;
  const eventTypes: EventType[] = React.useMemo(() => {
    const stored = dsData?.eventTypes;
    if (Array.isArray(stored) && stored.length > 0) return stored as EventType[];
    return [...ALL_EVENT_TYPES];
  }, [dsData?.eventTypes]);

  const filter = dsData?.filter ?? null;
  const [filterEnabled, setFilterEnabled] = React.useState<boolean>(!!filter);
  const [filterDraft, setFilterDraft] = React.useState<{
    column: string;
    operator: FilterOperator;
    value: string;
  }>(() => ({
    column: filter?.column ?? '',
    operator: (filter?.operator as FilterOperator) ?? '=',
    value: filter?.value === undefined || filter?.value === null ? '' : String(filter.value),
  }));
  const [showAdvanced, setShowAdvanced] = React.useState<boolean>(!!filter);

  const patch = React.useCallback(
    (next: Partial<NonNullable<BuilderNodeData['dataSourceData']>>) => {
      if (isRunMode) return;
      if (!dsData) return;
      onUpdate({
        ...data,
        dataSourceData: { ...dsData, ...next },
      } as BuilderNodeData);
    },
    [data, dsData, isRunMode, onUpdate],
  );

  const toggleEventType = React.useCallback(
    (evt: EventType) => {
      if (isRunMode) return;
      const has = eventTypes.includes(evt);
      let next: EventType[];
      if (has) {
        next = eventTypes.filter((e) => e !== evt);
        // Enforce: at least one event must stay selected, else no trigger fires at all
        if (next.length === 0) return;
      } else {
        next = [...eventTypes, evt];
      }
      patch({ eventTypes: next });
    },
    [eventTypes, isRunMode, patch],
  );

  const persistFilter = React.useCallback(
    (next: { column: string; operator: FilterOperator; value: string }, enabled: boolean) => {
      if (isRunMode) return;
      if (!enabled || !next.column) {
        patch({ filter: null });
        return;
      }
      const valueless = VALUELESS_OPERATORS.has(next.operator);
      patch({
        filter: {
          column: next.column,
          operator: next.operator,
          value: valueless ? undefined : next.value,
        },
      });
    },
    [isRunMode, patch],
  );

  const handleFilterEnabledChange = React.useCallback(
    (enabled: boolean) => {
      setFilterEnabled(enabled);
      persistFilter(filterDraft, enabled);
    },
    [filterDraft, persistFilter],
  );

  const handleFilterColumnChange = React.useCallback(
    (column: string) => {
      const next = { ...filterDraft, column };
      setFilterDraft(next);
      persistFilter(next, filterEnabled);
    },
    [filterDraft, filterEnabled, persistFilter],
  );

  const handleFilterOperatorChange = React.useCallback(
    (operator: FilterOperator) => {
      const next = { ...filterDraft, operator };
      setFilterDraft(next);
      persistFilter(next, filterEnabled);
    },
    [filterDraft, filterEnabled, persistFilter],
  );

  const handleFilterValueChange = React.useCallback(
    (value: string) => {
      const next = { ...filterDraft, value };
      setFilterDraft(next);
      persistFilter(next, filterEnabled);
    },
    [filterDraft, filterEnabled, persistFilter],
  );

  const columnNames: string[] = React.useMemo(() => {
    if (!columns || columns.length === 0) return [];
    const names = columns
      .map((c) => (c && typeof c.name === 'string' ? c.name.replace(/^data\./, '') : ''))
      .filter((n) => n.length > 0);
    return Array.from(new Set(names));
  }, [columns]);

  const optionalCount = filterEnabled ? 1 : 0;
  const valueDisabled = VALUELESS_OPERATORS.has(filterDraft.operator);

  return (
    <div className="space-y-4 pt-2">
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
          {t('eventTypesLabel')}
        </label>
        <p className="text-xs text-slate-400 dark:text-slate-500 leading-relaxed">
          {t('eventTypesHelp')}
        </p>
        <div className="flex flex-col gap-1.5">
          {ALL_EVENT_TYPES.map((evt) => {
            const checked = eventTypes.includes(evt);
            return (
              <label
                key={evt}
                className="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-200 cursor-pointer"
              >
                <input
                  type="checkbox"
                  className="h-4 w-4 rounded border-slate-300 dark:border-slate-600"
                  checked={checked}
                  onChange={() => toggleEventType(evt)}
                  disabled={isRunMode}
                />
                <span>{t(`events.${evt}`)}</span>
              </label>
            );
          })}
        </div>
      </div>

      <OptionalSection
        isOpen={showAdvanced}
        onToggle={() => setShowAdvanced((v) => !v)}
        count={optionalCount}
        label={t('advancedOptions')}
      >
        <div className="space-y-2">
          <label className="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-200 cursor-pointer">
            <input
              type="checkbox"
              className="h-4 w-4 rounded border-slate-300 dark:border-slate-600"
              checked={filterEnabled}
              onChange={(e) => handleFilterEnabledChange(e.target.checked)}
              disabled={isRunMode}
            />
            <span className="font-semibold text-sm text-slate-500 dark:text-slate-400">
              {t('filterLabel')}
            </span>
          </label>
          <p className="text-xs text-slate-400 dark:text-slate-500 leading-relaxed">
            {t('filterHelp')}
          </p>

          {filterEnabled && (
            <div className="space-y-2">
              <div className="space-y-1.5">
                <label className="text-xs text-slate-500 dark:text-slate-400">
                  {t('filterColumn')}
                </label>
                {columnNames.length > 0 ? (
                  <Select
                    value={filterDraft.column || undefined}
                    onValueChange={handleFilterColumnChange}
                    disabled={isRunMode}
                  >
                    <SelectTrigger className="w-full">
                      <SelectValue placeholder={t('filterColumnPlaceholder')} />
                    </SelectTrigger>
                    <SelectContent>
                      {columnNames.map((c) => (
                        <SelectItem key={c} value={c}>
                          {c}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                ) : (
                  <Input
                    value={filterDraft.column}
                    onChange={(e) => handleFilterColumnChange(e.target.value)}
                    placeholder={t('filterColumnPlaceholder')}
                    readOnly={isRunMode}
                  />
                )}
              </div>

              <div className="space-y-1.5">
                <label className="text-xs text-slate-500 dark:text-slate-400">
                  {t('filterOperator')}
                </label>
                <Select
                  value={filterDraft.operator}
                  onValueChange={(v) => handleFilterOperatorChange(v as FilterOperator)}
                  disabled={isRunMode}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {COMPARISON_OPERATORS.map((op) => (
                      <SelectItem key={op} value={op}>
                        {OPERATOR_LABELS[op]}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="space-y-1.5">
                <label className="text-xs text-slate-500 dark:text-slate-400">
                  {t('filterValue')}
                </label>
                <Input
                  value={filterDraft.value}
                  onChange={(e) => handleFilterValueChange(e.target.value)}
                  placeholder={valueDisabled ? t('filterValueNotUsed') : t('filterValuePlaceholder')}
                  disabled={valueDisabled || isRunMode}
                  readOnly={isRunMode}
                />
                {valueDisabled && (
                  <p className="text-xs text-slate-400 italic">{t('filterValueNotUsedHint')}</p>
                )}
              </div>
            </div>
          )}
        </div>
      </OptionalSection>
    </div>
  );
}
