'use client';

import * as React from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import * as ReactDOM from 'react-dom';
import {
  Info, X, Play, Clock, CheckCircle, AlertCircle, ExternalLink,
} from 'lucide-react';
import type { Node } from 'reactflow';
import {
  Select, SelectContent, SelectGroup, SelectItem, SelectLabel,
  SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import type { BuilderNodeData } from '../../../types';
import { useTranslations } from 'next-intl';
import { OptionalSection } from '../OptionalSection';
import { usePathname } from 'next/navigation';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/lib/api';
import { usePopoverPosition } from '../../../hooks/ui/usePopoverPosition';
import { scheduleSettingsService } from '@/lib/api/orchestrator';
import type { ScheduleOverview, ScheduleConfig } from '@/lib/api/orchestrator';
import { buildStandaloneSourceNodeId } from '../../../utils/standaloneSourceNodeId';
import { findAdoptableSchedule } from '../../../utils/findAdoptableSchedule';
import Link from 'next/link';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';

// Module-level guard: prevents duplicate schedule creation across remounts.
const pendingOrCreatedSchedules = new Map<string, string>();

// -----------------------------------------------------------------------------
// Frequency catalog
// -----------------------------------------------------------------------------
// Each entry is either a fixed preset (immediately resolves to a cron string)
// or an inline configurator (the user picks time / weekday / day-of-month via
// a small sub-picker rendered below the dropdown). The "advanced" entry opens
// a free-text cron input pre-filled with a safe default.
//
// Cron strings are 5-field Unix style. The backend validator and the orchestrator
// `validate-cron` endpoint are the single source of truth for validity and
// description - the frontend never re-parses the expression.

type FrequencyKind = 'preset' | 'daily-custom' | 'weekly-custom' | 'monthly-custom' | 'advanced';
type FrequencyGroup = 'minutes' | 'hours' | 'daily' | 'weekly' | 'monthly' | 'advanced';

interface FrequencyOption {
  value: string;          // i18n key suffix and stable identifier
  group: FrequencyGroup;
  kind: FrequencyKind;
  cron?: string;          // fixed cron for preset entries
}

const FREQUENCIES: FrequencyOption[] = [
  // Minutes
  { value: 'every_minute',     group: 'minutes', kind: 'preset', cron: '* * * * *' },
  { value: 'every_5_minutes',  group: 'minutes', kind: 'preset', cron: '*/5 * * * *' },
  { value: 'every_15_minutes', group: 'minutes', kind: 'preset', cron: '*/15 * * * *' },
  { value: 'every_30_minutes', group: 'minutes', kind: 'preset', cron: '*/30 * * * *' },
  // Hours
  { value: 'every_hour',       group: 'hours',   kind: 'preset', cron: '0 * * * *' },
  { value: 'every_2_hours',    group: 'hours',   kind: 'preset', cron: '0 */2 * * *' },
  { value: 'every_3_hours',    group: 'hours',   kind: 'preset', cron: '0 */3 * * *' },
  { value: 'every_6_hours',    group: 'hours',   kind: 'preset', cron: '0 */6 * * *' },
  { value: 'every_12_hours',   group: 'hours',   kind: 'preset', cron: '0 */12 * * *' },
  // Daily
  { value: 'every_day_midnight', group: 'daily', kind: 'preset', cron: '0 0 * * *' },
  { value: 'every_day_9am',      group: 'daily', kind: 'preset', cron: '0 9 * * *' },
  { value: 'every_day_noon',     group: 'daily', kind: 'preset', cron: '0 12 * * *' },
  { value: 'every_day_6pm',      group: 'daily', kind: 'preset', cron: '0 18 * * *' },
  { value: 'daily_custom',       group: 'daily', kind: 'daily-custom' },
  // Weekly
  { value: 'every_monday',  group: 'weekly', kind: 'preset', cron: '0 9 * * 1' },
  { value: 'every_weekday', group: 'weekly', kind: 'preset', cron: '0 9 * * 1-5' },
  { value: 'every_weekend', group: 'weekly', kind: 'preset', cron: '0 10 * * 6' },
  { value: 'weekly_custom', group: 'weekly', kind: 'weekly-custom' },
  // Monthly
  { value: 'first_of_month',  group: 'monthly', kind: 'preset', cron: '0 9 1 * *' },
  { value: 'monthly_custom',  group: 'monthly', kind: 'monthly-custom' },
  // Advanced (free-text)
  { value: 'advanced', group: 'advanced', kind: 'advanced', cron: '0 0 * * *' },
];

const FREQUENCY_BY_VALUE: Record<string, FrequencyOption> = Object.fromEntries(
  FREQUENCIES.map(f => [f.value, f]),
);

const GROUP_ORDER: FrequencyGroup[] = ['minutes', 'hours', 'daily', 'weekly', 'monthly', 'advanced'];

// Safe default cron applied when switching INTO a configurator entry from one
// whose current cron doesn't match the new shape.
const DEFAULT_CRONS: Record<FrequencyKind, string> = {
  'preset': '0 * * * *',
  'daily-custom': '0 9 * * *',
  'weekly-custom': '0 9 * * 1',
  'monthly-custom': '0 9 1 * *',
  'advanced': '0 0 * * *',
};

const WEEKDAYS = [
  { value: '1', labelKey: 'weekdayMon' },
  { value: '2', labelKey: 'weekdayTue' },
  { value: '3', labelKey: 'weekdayWed' },
  { value: '4', labelKey: 'weekdayThu' },
  { value: '5', labelKey: 'weekdayFri' },
  { value: '6', labelKey: 'weekdaySat' },
  { value: '0', labelKey: 'weekdaySun' },
];

// -----------------------------------------------------------------------------
// Cron <-> frequency reverse mapping
// -----------------------------------------------------------------------------
// Derive the dropdown selection from the cron string. This is the ONLY parsing
// the frontend does - used to restore UI state on load. The actual semantics
// are owned by the backend; this is purely "which entry should the dropdown
// show". If no shape matches, we fall back to 'advanced' so the free-text
// field appears with the current cron prefilled.

function cronToFrequencyValue(cron: string): string {
  if (!cron || cron.trim() === '') return 'advanced';
  const trimmed = cron.trim();

  // 1) Exact preset match
  for (const f of FREQUENCIES) {
    if (f.kind === 'preset' && f.cron === trimmed) return f.value;
  }

  const parts = trimmed.split(/\s+/);
  if (parts.length !== 5) return 'advanced';
  const [minute, hour, dom, month, dow] = parts;

  const isNum = (s: string) => /^\d+$/.test(s);

  // 2) Daily: M H * * *
  if (isNum(minute) && isNum(hour) && dom === '*' && month === '*' && dow === '*') {
    return 'daily_custom';
  }
  // 3) Weekly: M H * * D[,D...] (digits + commas)
  if (isNum(minute) && isNum(hour) && dom === '*' && month === '*' && /^[\d,]+$/.test(dow)) {
    return 'weekly_custom';
  }
  // 4) Monthly: M H D * *
  if (isNum(minute) && isNum(hour) && isNum(dom) && month === '*' && dow === '*') {
    return 'monthly_custom';
  }
  return 'advanced';
}

// Pickers state extracted from a cron string. Defaults applied when the cron
// doesn't match the expected shape.
function parseDailyCron(cron: string): { hour: number; minute: number } {
  const m = cron.trim().match(/^(\d+)\s+(\d+)\s+\*\s+\*\s+\*$/);
  if (!m) return { hour: 9, minute: 0 };
  return { hour: clamp(parseInt(m[2], 10), 0, 23), minute: clamp(parseInt(m[1], 10), 0, 59) };
}

function parseWeeklyCron(cron: string): { hour: number; minute: number; days: string[] } {
  const m = cron.trim().match(/^(\d+)\s+(\d+)\s+\*\s+\*\s+([\d,]+)$/);
  if (!m) return { hour: 9, minute: 0, days: ['1'] };
  const days = m[3].split(',').map(d => d.trim()).filter(d => WEEKDAYS.some(w => w.value === d));
  return {
    hour: clamp(parseInt(m[2], 10), 0, 23),
    minute: clamp(parseInt(m[1], 10), 0, 59),
    days: days.length > 0 ? days : ['1'],
  };
}

function parseMonthlyCron(cron: string): { hour: number; minute: number; dayOfMonth: number } {
  const m = cron.trim().match(/^(\d+)\s+(\d+)\s+(\d+)\s+\*\s+\*$/);
  if (!m) return { hour: 9, minute: 0, dayOfMonth: 1 };
  return {
    hour: clamp(parseInt(m[2], 10), 0, 23),
    minute: clamp(parseInt(m[1], 10), 0, 59),
    dayOfMonth: clamp(parseInt(m[3], 10), 1, 31),
  };
}

function buildDailyCron(hour: number, minute: number): string {
  return `${minute} ${hour} * * *`;
}

function buildWeeklyCron(hour: number, minute: number, days: string[]): string {
  const safeDays = days.length > 0 ? days : ['1'];
  return `${minute} ${hour} * * ${safeDays.join(',')}`;
}

function buildMonthlyCron(hour: number, minute: number, dayOfMonth: number): string {
  return `${minute} ${hour} ${dayOfMonth} * *`;
}

function clamp(n: number, min: number, max: number): number {
  if (isNaN(n)) return min;
  return Math.min(max, Math.max(min, n));
}

// -----------------------------------------------------------------------------
// Types
// -----------------------------------------------------------------------------

interface ScheduleTriggerData {
  cronExpression: string;
  timezone: string;
  maxExecutions: number | null;
  /**
   * Dropdown selection persisted so a "custom" pick whose default cron happens
   * to collide with a preset (e.g. monthly_custom's default 0 9 1 * * also
   * matches the first_of_month preset) doesn't silently revert to the preset.
   * When absent, the selection is derived from cronExpression alone.
   */
  scheduleKind?: string;
}

interface ScheduleStatus {
  cron: string | null;
  timezone: string | null;
  enabled: boolean;
  lastExecutionAt: string | null;
  nextExecutionAt: string | null;
  executionCount: number;
  status: string;
  maxExecutions: number | null;
}

/** GET /status response: { exists, schedules: [{ triggerId, status }] } */
interface ScheduleStatusListResponse {
  exists: boolean;
  schedules?: Array<{
    triggerId: string;
    status: ScheduleStatus;
  }>;
  message?: string;
}

interface ValidateCronResponse {
  valid: boolean;
  description?: string;
  nextExecutions?: string[];
}

interface ScheduleTriggerParametersFormProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;
  /** Trigger ID for multi-DAG support (e.g., "trigger:my_schedule") */
  triggerId?: string | null;
}

// -----------------------------------------------------------------------------
// Component
// -----------------------------------------------------------------------------

export function ScheduleTriggerParametersForm({
  node,
  data,
  isRunMode = false,
  onUpdate,
  triggerId,
}: ScheduleTriggerParametersFormProps) {
  const t = useTranslations('workflowBuilder.inspector.scheduleTrigger');
  const pathname = usePathname();

  // Extract workflowId from URL (handles /workflow/[id] and /workflows/[id])
  const workflowId = React.useMemo(() => {
    if (!pathname) return null;
    const match = pathname.match(/\/workflows?\/([a-f0-9-]{36})/i);
    return match ? match[1] : null;
  }, [pathname]);

  // Read current state - back-compat: legacy nodes may still carry scheduleType/preset,
  // we ignore them and derive the dropdown selection from cronExpression unless
  // a scheduleKind has been explicitly persisted (newer custom-picker path).
  const scheduleData: ScheduleTriggerData = React.useMemo(() => {
    const existing = (data as any).scheduleTriggerData as Partial<ScheduleTriggerData> | undefined;
    return {
      cronExpression: existing?.cronExpression || '0 * * * *',
      timezone: existing?.timezone || Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
      maxExecutions: existing?.maxExecutions ?? null,
      scheduleKind: existing?.scheduleKind,
    };
  }, [(data as any).scheduleTriggerData]);

  const currentFrequency = React.useMemo(() => {
    // Prefer the persisted dropdown selection - required when a "custom" entry's
    // default cron collides with a preset (e.g. monthly_custom default 0 9 1 * *
    // would otherwise resolve back to first_of_month and hide the picker).
    if (scheduleData.scheduleKind && FREQUENCY_BY_VALUE[scheduleData.scheduleKind]) {
      return scheduleData.scheduleKind;
    }
    return cronToFrequencyValue(scheduleData.cronExpression);
  }, [scheduleData.scheduleKind, scheduleData.cronExpression]);

  const [showOptionalParams, setShowOptionalParams] = React.useState(false);
  const [isInfoOpen, setIsInfoOpen] = React.useState(false);
  const { buttonRef: infoButtonRef, popoverPosition } = usePopoverPosition(isInfoOpen, 320);

  const queryClient = useQueryClient();
  const [isExecuting, setIsExecuting] = React.useState(false);

  const standaloneScheduleId = (data as any).standaloneScheduleId as string | undefined;

  const dataRef = React.useRef(data);
  dataRef.current = data;
  const onUpdateRef = React.useRef(onUpdate);
  onUpdateRef.current = onUpdate;
  const isCreatingRef = React.useRef(false);

  // Fetch all schedules + config for usage gauge + selector
  const [allSchedules, setAllSchedules] = React.useState<ScheduleOverview[]>([]);
  const [scheduleConfig, setScheduleConfig] = React.useState<ScheduleConfig | null>(null);
  const [isLoadingSchedules, setIsLoadingSchedules] = React.useState(true);
  const [listLoaded, setListLoaded] = React.useState(false);
  const [autoCreateFailed, setAutoCreateFailed] = React.useState(false);

  React.useEffect(() => {
    setIsLoadingSchedules(true);
    Promise.all([
      scheduleSettingsService.getAll().catch(() => [] as ScheduleOverview[]),
      scheduleSettingsService.getConfig().catch(() => null),
    ]).then(([schedules, config]) => {
      setAllSchedules(schedules);
      setScheduleConfig(config);
      setListLoaded(true);
    }).finally(() => setIsLoadingSchedules(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Auto-create schedule if the node has none. Dedup keyed on `node.id`
  // (stable across refreshes - `data.id` regenerates).
  const nodeDataId = node.id;
  React.useEffect(() => {
    if (!listLoaded || standaloneScheduleId || isRunMode || isCreatingRef.current) return;
    const existingScheduleId = pendingOrCreatedSchedules.get(nodeDataId);
    if (existingScheduleId && existingScheduleId !== '__pending__') {
      onUpdateRef.current({
        ...dataRef.current,
        standaloneScheduleId: existingScheduleId,
      } as BuilderNodeData);
      return;
    }
    if (existingScheduleId === '__pending__') return;

    // Adopt an existing schedule for this node/trigger instead of minting a
    // duplicate (see findAdoptableSchedule). Reopening a saved trigger whose
    // row the builder didn't know about used to spawn a SECOND standalone
    // schedule that never fires, cluttered the Triggers bell, and burned the
    // per-user schedule quota.
    const existing = findAdoptableSchedule(allSchedules, {
      nodeId: nodeDataId,
      workflowId,
      triggerId,
    });
    if (existing) {
      pendingOrCreatedSchedules.set(nodeDataId, existing.id);
      onUpdateRef.current({
        ...dataRef.current,
        standaloneScheduleId: existing.id,
      } as BuilderNodeData);
      return;
    }

    if (scheduleConfig && scheduleConfig.currentCount >= scheduleConfig.maxPerUser) {
      setAutoCreateFailed(true);
      return;
    }
    isCreatingRef.current = true;
    pendingOrCreatedSchedules.set(nodeDataId, '__pending__');
    setAutoCreateFailed(false);
    const scheduleNumber = allSchedules.length + 1;
    const defaultCron = scheduleData.cronExpression || '0 * * * *';
    const defaultTz = scheduleData.timezone || 'UTC';
    const sourceNodeId = buildStandaloneSourceNodeId('schedule', nodeDataId);
    scheduleSettingsService.create({
      name: `Schedule #${scheduleNumber}`,
      cron: defaultCron,
      timezone: defaultTz,
      sourceNodeId,
    })
      .then((schedule) => {
        pendingOrCreatedSchedules.set(nodeDataId, schedule.id);
        setAllSchedules((prev) => [schedule, ...prev]);
        if (scheduleConfig) {
          setScheduleConfig({ ...scheduleConfig, currentCount: scheduleConfig.currentCount + 1 });
        }
        onUpdateRef.current({
          ...dataRef.current,
          standaloneScheduleId: schedule.id,
        } as BuilderNodeData);
      })
      .catch((err) => {
        console.error('[ScheduleTrigger] Auto-create failed:', err);
        pendingOrCreatedSchedules.delete(nodeDataId);
        setAutoCreateFailed(true);
        isCreatingRef.current = false;
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [listLoaded, standaloneScheduleId, isRunMode]);

  // Fetch schedule status (run mode)
  const {
    data: scheduleStatus = null,
    isLoading: isLoadingStatus,
  } = useQuery({
    queryKey: ['schedule-status', workflowId, triggerId],
    queryFn: async () => {
      if (triggerId) {
        try {
          return await apiClient.get<ScheduleStatus>(
            `/v2/workflows/${workflowId}/schedule/status/${encodeURIComponent(triggerId)}`,
          );
        } catch {
          return null;
        }
      }
      const response = await apiClient.get<ScheduleStatusListResponse>(
        `/v2/workflows/${workflowId}/schedule/status`,
      );
      if (!response.exists || !response.schedules || response.schedules.length === 0) {
        return null;
      }
      const match = triggerId
        ? response.schedules.find(s => s.triggerId === triggerId)
        : response.schedules[0];
      return match?.status ?? null;
    },
    enabled: isRunMode && !!workflowId,
    staleTime: 30_000,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });

  // ---------------------------------------------------------------------------
  // Live validation via backend (single source of truth for description + next runs)
  //
  // Race guard: each call captures a monotonically increasing requestId.
  // A response is applied to state only if it is still the most recent one
  // by the time it resolves - protects against a slow earlier response
  // overwriting a fresh one on rapid typing.
  // ---------------------------------------------------------------------------
  const [validation, setValidation] = React.useState<ValidateCronResponse | null>(null);
  const [isValidating, setIsValidating] = React.useState(false);
  const validateTimeoutRef = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const validationRequestIdRef = React.useRef(0);

  React.useEffect(() => {
    if (validateTimeoutRef.current) clearTimeout(validateTimeoutRef.current);
    if (!scheduleData.cronExpression) {
      setValidation(null);
      setIsValidating(false);
      return;
    }
    setIsValidating(true);
    validateTimeoutRef.current = setTimeout(() => {
      const requestId = ++validationRequestIdRef.current;
      scheduleSettingsService.validateCron(scheduleData.cronExpression, scheduleData.timezone)
        .then((res) => {
          if (requestId === validationRequestIdRef.current) setValidation(res);
        })
        .catch(() => {
          if (requestId === validationRequestIdRef.current) setValidation({ valid: false });
        })
        .finally(() => {
          if (requestId === validationRequestIdRef.current) setIsValidating(false);
        });
    }, 400);
    return () => {
      if (validateTimeoutRef.current) clearTimeout(validateTimeoutRef.current);
    };
  }, [scheduleData.cronExpression, scheduleData.timezone]);

  // ---------------------------------------------------------------------------
  // Sync to backend (debounced).
  //
  // Empty-cron guard: never POST an empty `cron` to the schedule update endpoint -
  // the backend would reject with 400 and the catch below swallows it, leaving the
  // user with a silently-stale row in DB while their UI shows the cleared input.
  // The validation panel already surfaces the empty/invalid state, so skipping the
  // sync until a non-empty value is typed is the honest behaviour.
  // ---------------------------------------------------------------------------
  const syncTimeoutRef = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const syncToSchedule = React.useCallback((cron: string, timezone: string, maxExec: number | null) => {
    if (!standaloneScheduleId || isRunMode) return;
    if (!cron || cron.trim() === '') return;
    if (syncTimeoutRef.current) clearTimeout(syncTimeoutRef.current);
    syncTimeoutRef.current = setTimeout(() => {
      scheduleSettingsService.update(standaloneScheduleId, {
        cron, timezone, maxExecutions: maxExec ?? undefined,
      }).catch(() => { /* silent */ });
    }, 1000);
  }, [standaloneScheduleId, isRunMode]);

  // ---------------------------------------------------------------------------
  // Update helpers - single setData() path keeps state shape stable.
  // ---------------------------------------------------------------------------
  const updateCron = React.useCallback((
    nextCron: string,
    nextTz?: string,
    nextMaxExec?: number | null,
    nextKind?: string | null,
  ) => {
    if (isRunMode) return;
    const tz = nextTz !== undefined ? nextTz : scheduleData.timezone;
    const maxExec = nextMaxExec !== undefined ? nextMaxExec : scheduleData.maxExecutions;
    // Explicit null clears the persisted kind (back to cron-derived dropdown).
    // Undefined keeps whatever was there.
    const kind = nextKind === undefined ? scheduleData.scheduleKind : (nextKind ?? undefined);
    onUpdate({
      ...data,
      scheduleTriggerData: {
        cronExpression: nextCron,
        timezone: tz,
        maxExecutions: maxExec,
        scheduleKind: kind,
      } satisfies ScheduleTriggerData,
    } as BuilderNodeData);
    syncToSchedule(nextCron, tz, maxExec);
  }, [data, scheduleData, isRunMode, onUpdate, syncToSchedule]);

  // ---------------------------------------------------------------------------
  // Dropdown change - resolve the new cron for the picked entry.
  // ---------------------------------------------------------------------------
  const handleFrequencyChange = React.useCallback((value: string) => {
    const option = FREQUENCY_BY_VALUE[value];
    if (!option) return;

    let nextCron: string;
    if (option.kind === 'preset' && option.cron) {
      nextCron = option.cron;
    } else if (option.kind === 'daily-custom') {
      nextCron = currentFrequency === 'daily_custom' ? scheduleData.cronExpression : DEFAULT_CRONS['daily-custom'];
    } else if (option.kind === 'weekly-custom') {
      nextCron = currentFrequency === 'weekly_custom' ? scheduleData.cronExpression : DEFAULT_CRONS['weekly-custom'];
    } else if (option.kind === 'monthly-custom') {
      nextCron = currentFrequency === 'monthly_custom' ? scheduleData.cronExpression : DEFAULT_CRONS['monthly-custom'];
    } else {
      // Advanced: keep current cron if non-empty, else apply safe default.
      nextCron = scheduleData.cronExpression || DEFAULT_CRONS['advanced'];
    }
    // Persist the chosen entry so it survives renders even when its default
    // cron collides with a preset.
    updateCron(nextCron, undefined, undefined, value);
  }, [currentFrequency, scheduleData.cronExpression, updateCron]);

  // ---------------------------------------------------------------------------
  // Execute-now (run mode)
  // ---------------------------------------------------------------------------
  const handleExecuteNow = React.useCallback(async () => {
    if (!workflowId || !triggerId) return;
    setIsExecuting(true);
    try {
      await apiClient.post(`/v2/workflows/${workflowId}/schedule/execute-now/${encodeURIComponent(triggerId)}`, {});
      await queryClient.invalidateQueries({ queryKey: ['schedule-status', workflowId, triggerId] });
    } catch (err) {
      console.error('[ScheduleTrigger] Execute now failed:', err);
    } finally {
      setIsExecuting(false);
    }
  }, [workflowId, triggerId, queryClient]);

  const formatDate = (isoDate: string | null): string => {
    if (!isoDate) return '-';
    try { return formatUtcDateTime(isoDate); } catch { return isoDate; }
  };

  // Group frequency options by GROUP_ORDER for the SelectContent rendering.
  const groupedFrequencies = React.useMemo(() => {
    const groups: Record<FrequencyGroup, FrequencyOption[]> = {
      minutes: [], hours: [], daily: [], weekly: [], monthly: [], advanced: [],
    };
    for (const f of FREQUENCIES) groups[f.group].push(f);
    return groups;
  }, []);

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------
  return (
    <div className="space-y-4 pt-2">
      {/* Header with info popover */}
      <div className="flex items-center gap-1.5">
        <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('title')}</span>
        <div className="relative inline-flex">
          <button
            ref={infoButtonRef}
            onClick={(e) => { e.stopPropagation(); setIsInfoOpen(!isInfoOpen); }}
            className="p-0.5 rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
            title={t('infoTitle')}
          >
            <Info className="h-3 w-3 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300" />
          </button>
          {isInfoOpen && typeof document !== 'undefined' && ReactDOM.createPortal(
            <>
              <div className="fixed inset-0 z-[9998]" onClick={() => setIsInfoOpen(false)} />
              <div
                className="fixed z-[9999] w-80 p-3 bg-white dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700 shadow-lg"
                style={{ top: popoverPosition.top, left: popoverPosition.left }}
              >
                <div className="flex items-start justify-between gap-2 mb-2">
                  <span className="font-medium text-sm text-slate-700 dark:text-slate-200">{t('infoTitle')}</span>
                  <button
                    onClick={(e) => { e.stopPropagation(); setIsInfoOpen(false); }}
                    className="p-0.5 rounded hover:bg-slate-100 dark:hover:bg-slate-700"
                  >
                    <X className="h-3.5 w-3.5 text-slate-400" />
                  </button>
                </div>
                <p className="text-xs text-slate-500 dark:text-slate-400 leading-relaxed mb-3">
                  {t('infoDescription')}
                </p>
                <div className="border-t border-slate-200 dark:border-slate-700 pt-2 space-y-2">
                  <p className="text-xs font-semibold text-slate-500 dark:text-slate-400">{t('availableOutputs')}</p>
                  <ul className="text-xs text-slate-500 dark:text-slate-400 space-y-0.5 font-mono">
                    <li>• triggered_at</li>
                    <li>• execution_count</li>
                    <li>• next_execution</li>
                  </ul>
                </div>
              </div>
            </>,
            document.body,
          )}
        </div>
      </div>

      {/* Usage counter */}
      {scheduleConfig && (
        <div className="flex items-center justify-between">
          <span className="text-xs text-slate-500 dark:text-slate-400">
            {scheduleConfig.currentCount} / {scheduleConfig.maxPerUser} {t('schedulesUsed')}
          </span>
          <Link
            href="/app/settings/public-access?tab=schedule"
            className="inline-flex items-center gap-1 text-xs text-blue-600 dark:text-blue-400 hover:underline"
          >
            <ExternalLink className="h-3 w-3" />
            {t('manageSchedules')}
          </Link>
        </div>
      )}

      {/* Auto-create failure */}
      {autoCreateFailed && (
        <div className="text-xs text-amber-600 dark:text-amber-400 bg-amber-50 dark:bg-amber-900/20 rounded p-2">
          {t('limitReached')}
        </div>
      )}

      {/* Schedule name (read-only) */}
      {standaloneScheduleId && (
        <div className="space-y-1.5">
          <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">
            {t('selectSchedule')}
          </label>
          <Input
            value={allSchedules.find((s) => s.id === standaloneScheduleId)?.name
              || 'Schedule'}
            disabled
            className="w-full text-sm"
          />
        </div>
      )}

      {/* Frequency selector */}
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('frequency')}</label>
        <Select
          value={currentFrequency}
          onValueChange={handleFrequencyChange}
          disabled={isRunMode}
        >
          <SelectTrigger className="w-full">
            <SelectValue placeholder={t('selectFrequency')} />
          </SelectTrigger>
          <SelectContent>
            {GROUP_ORDER.map((group) => (
              <SelectGroup key={group}>
                <SelectLabel>{t(`group_${group}`)}</SelectLabel>
                {groupedFrequencies[group].map((freq) => (
                  <SelectItem key={freq.value} value={freq.value}>
                    {t(`freq_${freq.value}`)}
                  </SelectItem>
                ))}
              </SelectGroup>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* Inline sub-picker for daily_custom / weekly_custom / monthly_custom / advanced */}
      {currentFrequency === 'daily_custom' && (
        <DailyCustomPicker
          cron={scheduleData.cronExpression}
          disabled={isRunMode}
          onChange={(hour, minute) => updateCron(buildDailyCron(hour, minute))}
          t={t}
        />
      )}
      {currentFrequency === 'weekly_custom' && (
        <WeeklyCustomPicker
          cron={scheduleData.cronExpression}
          disabled={isRunMode}
          onChange={(hour, minute, days) => updateCron(buildWeeklyCron(hour, minute, days))}
          t={t}
        />
      )}
      {currentFrequency === 'monthly_custom' && (
        <MonthlyCustomPicker
          cron={scheduleData.cronExpression}
          disabled={isRunMode}
          onChange={(hour, minute, dom) => updateCron(buildMonthlyCron(hour, minute, dom))}
          t={t}
        />
      )}
      {currentFrequency === 'advanced' && (
        <div className="space-y-2">
          <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('cronExpression')}</label>
          <Input
            className="w-full font-mono"
            placeholder="0 0 * * *"
            value={scheduleData.cronExpression}
            onChange={(e) => updateCron(e.target.value)}
            readOnly={isRunMode}
          />
          <p className="text-xs text-slate-400 dark:text-slate-500">{t('cronHelp')}</p>
        </div>
      )}

      {/* Live validation + next runs (single source of truth: backend) */}
      <ValidationFeedback
        validation={validation}
        isValidating={isValidating}
        timezone={scheduleData.timezone}
        cronIsNonEmpty={!!scheduleData.cronExpression && scheduleData.cronExpression.trim() !== ''}
        hasPersistedSchedule={!!standaloneScheduleId}
        t={t}
      />

      {/* Optional advanced parameters */}
      <OptionalSection
        isOpen={showOptionalParams}
        onToggle={() => setShowOptionalParams(!showOptionalParams)}
        count={2}
        label={t('advancedOptions')}
      >
        {/* Timezone */}
        <label className="flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('timezone')}</span>
            <span className="text-sm text-slate-400 dark:text-slate-500">{t('optional')}</span>
          </div>
          <Select
            value={scheduleData.timezone}
            onValueChange={(value) => updateCron(scheduleData.cronExpression, value)}
            disabled={isRunMode}
          >
            <SelectTrigger className="w-full">
              <SelectValue placeholder={t('selectTimezone')} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="UTC">UTC</SelectItem>
              <SelectItem value="Europe/Paris">Europe/Paris</SelectItem>
              <SelectItem value="Europe/London">Europe/London</SelectItem>
              <SelectItem value="America/New_York">America/New_York</SelectItem>
              <SelectItem value="America/Los_Angeles">America/Los_Angeles</SelectItem>
              <SelectItem value="Asia/Tokyo">Asia/Tokyo</SelectItem>
              <SelectItem value="Asia/Shanghai">Asia/Shanghai</SelectItem>
              {(() => {
                const local = Intl.DateTimeFormat().resolvedOptions().timeZone;
                const presetZones = new Set([
                  'UTC', 'Europe/Paris', 'Europe/London', 'America/New_York',
                  'America/Los_Angeles', 'Asia/Tokyo', 'Asia/Shanghai',
                ]);
                if (presetZones.has(local)) return null;
                return <SelectItem value={local}>{local} ({t('localTimezone')})</SelectItem>;
              })()}
            </SelectContent>
          </Select>
        </label>

        {/* Max Executions */}
        <label className="flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('maxExecutions')}</span>
            <span className="text-sm text-slate-400 dark:text-slate-500">{t('optional')}</span>
          </div>
          <Input
            type="number"
            className="w-full"
            placeholder={t('unlimited')}
            value={scheduleData.maxExecutions === null ? '' : scheduleData.maxExecutions}
            onChange={(e) => {
              const raw = e.target.value;
              const parsed = raw === '' ? null : parseInt(raw, 10);
              const maxExec = parsed === null || isNaN(parsed) ? null : parsed;
              updateCron(scheduleData.cronExpression, undefined, maxExec);
            }}
            min={1}
            readOnly={isRunMode}
          />
          <p className="text-xs text-slate-400 dark:text-slate-500">{t('maxExecutionsHelp')}</p>
        </label>
      </OptionalSection>

      {/* Schedule Status (run mode only) */}
      {isRunMode && (
        <div className="space-y-3 pt-3 border-t border-slate-200 dark:border-slate-700">
          <div className="flex items-center gap-1.5">
            <Clock className="h-3.5 w-3.5 text-slate-500" />
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('statusTitle')}</span>
          </div>

          {isLoadingStatus ? (
            <div className="text-sm text-slate-400 italic">{t('loadingStatus')}</div>
          ) : scheduleStatus ? (
            <div className="space-y-2 text-sm">
              <div className="flex items-center gap-2">
                {scheduleStatus.status === 'active' ? (
                  <CheckCircle className="h-3.5 w-3.5 text-green-500" />
                ) : (
                  <AlertCircle className="h-3.5 w-3.5 text-slate-400" />
                )}
                <span className={scheduleStatus.status === 'active' ? 'text-green-600' : 'text-slate-500'}>
                  {scheduleStatus.status === 'active' ? t('statusActive') : t('statusInactive')}
                </span>
              </div>
              <div className="grid grid-cols-2 gap-2 text-xs">
                <div className="bg-slate-50 dark:bg-slate-800 rounded p-2">
                  <div className="text-slate-400">{t('lastExecution')}</div>
                  <div className="font-medium text-slate-600 dark:text-slate-300">
                    {formatDate(scheduleStatus.lastExecutionAt)}
                  </div>
                </div>
                <div className="bg-slate-50 dark:bg-slate-800 rounded p-2">
                  <div className="text-slate-400">{t('nextExecution')}</div>
                  <div className="font-medium text-slate-600 dark:text-slate-300">
                    {formatDate(scheduleStatus.nextExecutionAt)}
                  </div>
                </div>
              </div>
              <div className="flex items-center justify-between text-xs">
                <span className="text-slate-400">{t('executionCount')}</span>
                <span className="font-medium text-slate-600 dark:text-slate-300">
                  {scheduleStatus.executionCount}
                  {scheduleStatus.maxExecutions ? ` / ${scheduleStatus.maxExecutions}` : ''}
                </span>
              </div>
              <Button
                onClick={handleExecuteNow}
                disabled={isExecuting || !triggerId}
                variant="outline"
                size="sm"
                className="w-full mt-2"
              >
                <Play className="h-3.5 w-3.5 mr-1.5" />
                {isExecuting ? t('executing') : t('executeNow')}
              </Button>
            </div>
          ) : (
            <div className="text-sm text-slate-400 italic">{t('noStatus')}</div>
          )}
        </div>
      )}
    </div>
  );
}

// -----------------------------------------------------------------------------
// Sub-pickers
// -----------------------------------------------------------------------------

interface PickerProps {
  cron: string;
  disabled: boolean;
  t: ReturnType<typeof useTranslations>;
}

function DailyCustomPicker({ cron, disabled, onChange, t }: PickerProps & {
  onChange: (hour: number, minute: number) => void;
}) {
  const { hour, minute } = React.useMemo(() => parseDailyCron(cron), [cron]);
  return (
    <div className="space-y-2 rounded-md border border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-800/50 p-3">
      <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('timeLabel')}</label>
      <TimePicker hour={hour} minute={minute} disabled={disabled} onChange={onChange} />
    </div>
  );
}

function WeeklyCustomPicker({ cron, disabled, onChange, t }: PickerProps & {
  onChange: (hour: number, minute: number, days: string[]) => void;
}) {
  const { hour, minute, days } = React.useMemo(() => parseWeeklyCron(cron), [cron]);
  // Refuse to deselect the last active day - otherwise the cron silently falls
  // back to Monday (per buildWeeklyCron), which would be a confusing UX where
  // the button looks unselected but the schedule still fires Mondays.
  const toggleDay = (day: string) => {
    const isSelected = days.includes(day);
    if (isSelected && days.length === 1) return;
    const next = isSelected ? days.filter(d => d !== day) : [...days, day];
    onChange(hour, minute, next);
  };
  return (
    <div className="space-y-3 rounded-md border border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-800/50 p-3">
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('daysLabel')}</label>
        <div className="flex flex-wrap gap-1.5">
          {WEEKDAYS.map((wd) => {
            const selected = days.includes(wd.value);
            const isLastSelected = selected && days.length === 1;
            const buttonDisabled = disabled || isLastSelected;
            return (
              <button
                key={wd.value}
                type="button"
                disabled={buttonDisabled}
                onClick={() => toggleDay(wd.value)}
                title={isLastSelected ? t('atLeastOneDay') : undefined}
                className={`px-2.5 py-1 rounded text-xs font-medium transition-colors ${
                  selected
                    ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-300 border border-blue-300 dark:border-blue-700'
                    : 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300 border border-slate-200 dark:border-slate-600 hover:bg-slate-200 dark:hover:bg-slate-600'
                } ${buttonDisabled ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer'}`}
              >
                {t(wd.labelKey)}
              </button>
            );
          })}
        </div>
      </div>
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('timeLabel')}</label>
        <TimePicker
          hour={hour}
          minute={minute}
          disabled={disabled}
          onChange={(h, m) => onChange(h, m, days)}
        />
      </div>
    </div>
  );
}

function MonthlyCustomPicker({ cron, disabled, onChange, t }: PickerProps & {
  onChange: (hour: number, minute: number, dayOfMonth: number) => void;
}) {
  const { hour, minute, dayOfMonth } = React.useMemo(() => parseMonthlyCron(cron), [cron]);
  return (
    <div className="space-y-3 rounded-md border border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-800/50 p-3">
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('dayOfMonthLabel')}</label>
        <Input
          type="number"
          min={1}
          max={31}
          value={dayOfMonth}
          disabled={disabled}
          onChange={(e) => {
            const next = clamp(parseInt(e.target.value, 10), 1, 31);
            onChange(hour, minute, next);
          }}
          className="w-24"
        />
      </div>
      <div className="space-y-2">
        <label className="text-sm font-semibold text-slate-500 dark:text-slate-400">{t('timeLabel')}</label>
        <TimePicker
          hour={hour}
          minute={minute}
          disabled={disabled}
          onChange={(h, m) => onChange(h, m, dayOfMonth)}
        />
      </div>
    </div>
  );
}

function TimePicker({
  hour, minute, disabled, onChange,
}: { hour: number; minute: number; disabled: boolean; onChange: (h: number, m: number) => void }) {
  return (
    <div className="flex items-center gap-1.5">
      <Input
        type="number"
        min={0}
        max={23}
        value={hour}
        disabled={disabled}
        onChange={(e) => onChange(clamp(parseInt(e.target.value, 10), 0, 23), minute)}
        className="w-16 text-center"
      />
      <span className="text-slate-400 font-medium">:</span>
      <Input
        type="number"
        min={0}
        max={59}
        value={minute.toString().padStart(2, '0')}
        disabled={disabled}
        onChange={(e) => onChange(hour, clamp(parseInt(e.target.value, 10), 0, 59))}
        className="w-16 text-center"
      />
    </div>
  );
}

// -----------------------------------------------------------------------------
// Validation feedback panel (description + next-runs preview from backend)
// -----------------------------------------------------------------------------

function ValidationFeedback({
  validation, isValidating, timezone, cronIsNonEmpty, hasPersistedSchedule, t,
}: {
  validation: ValidateCronResponse | null;
  isValidating: boolean;
  timezone: string;
  cronIsNonEmpty: boolean;
  hasPersistedSchedule: boolean;
  t: ReturnType<typeof useTranslations>;
}) {
  if (isValidating && !validation) {
    return (
      <div className="rounded-md border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/50 p-3 text-xs text-slate-400 italic">
        {t('validating')}
      </div>
    );
  }
  if (!validation) return null;

  if (!validation.valid) {
    // Surface state divergence: a non-empty INVALID cron is silently rejected by the
    // backend PUT, so the typed value is NOT what the schedule will fire. The previous
    // valid cron (persisted server-side) stays active. Without this hint, the red panel
    // alone could read as "we'll save it anyway", which would be a serious surprise the
    // first time the schedule fires at the OLD cadence.
    const showNotSavedHint = cronIsNonEmpty && hasPersistedSchedule;
    return (
      <div className="rounded-md border border-red-200 dark:border-red-900/50 bg-red-50 dark:bg-red-900/20 p-3">
        <div className="flex items-start gap-2">
          <AlertCircle className="h-3.5 w-3.5 text-red-500 mt-0.5 flex-shrink-0" />
          <div className="space-y-1">
            <p className="text-xs font-medium text-red-700 dark:text-red-300">{t('invalidCron')}</p>
            <p className="text-xs text-red-600 dark:text-red-400">{t('invalidCronHelp')}</p>
            {showNotSavedHint && (
              <p className="text-xs text-red-600 dark:text-red-400 italic pt-1 border-t border-red-200 dark:border-red-900/50 mt-1">
                {t('notSavedHint')}
              </p>
            )}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="rounded-md border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/50 p-3 space-y-2">
      <div className="flex items-start gap-2">
        <CheckCircle className="h-3.5 w-3.5 text-green-500 mt-0.5 flex-shrink-0" />
        <p className="text-xs font-medium text-slate-700 dark:text-slate-200">
          {validation.description || t('valid')}
        </p>
      </div>
      {validation.nextExecutions && validation.nextExecutions.length > 0 && (
        <div className="space-y-1 pl-5">
          <p className="text-xs font-semibold text-slate-500 dark:text-slate-400">
            {t('nextRunsLabel', { timezone })}
          </p>
          <ul className="space-y-0.5">
            {validation.nextExecutions.map((iso, i) => (
              <li key={i} className="text-xs text-slate-600 dark:text-slate-300 font-mono">
                {formatNextRun(iso, timezone)}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function formatNextRun(iso: string, timezone: string): string {
  try {
    const d = new Date(iso);
    return d.toLocaleString(getClientLocale(), {
      timeZone: timezone,
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit',
    });
  } catch {
    return iso;
  }
}
