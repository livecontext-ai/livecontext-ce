'use client';

import React, { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { BarChart3, TrendingUp, TrendingDown, Calendar, Zap, Hash, Cpu, Gauge, Hourglass } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { quotaApi, UsageAnalytics, DailyUsageEntry } from '@/lib/api';
import { isCeMode, creditsToUsd, formatCost } from '@/lib/format-cost';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { CREDIT_SOURCE_LABEL_KEYS } from '@/lib/billing/creditSourceTypes';
import { modelLabelFor, providerOptionLabels, useModelNameIndex, ProviderModelCell } from './modelLabels';
import {
  periodTotals,
  previousTotals,
  relativeChange,
  usageByType,
  usageByModel,
  topWithRest,
  peakDay,
  runwayDays,
} from './usageAnalyticsStats';

// Ledger amounts are stored in credits; CE displays spend in dollars (1 credit = $0.001),
// Cloud keeps raw credits. Applied at the aggregation source so the chart, axis, tooltip and
// summary cards all stay in the same unit. See lib/format-cost.ts.
const toDisplayAmount = (credits: number): number => (isCeMode ? creditsToUsd(credits) : credits);

// The flat per-node platform fee is a CLOUD monetization - CE self-hosts the orchestrator, so it's
// free and must not be counted here. Exclude WORKFLOW_NODE from CE analytics (the CE backend writes
// no such rows going forward; this also hides any pre-existing ones immediately). Cloud is unchanged.
const includeSourceType = (sourceType: string): boolean =>
  !(isCeMode && sourceType === 'WORKFLOW_NODE');

const SOURCE_TYPE_COLORS: Record<string, string> = {
  WORKFLOW_NODE: '#6366f1',
  AGENT_EXECUTION: '#f59e0b',
  CHAT_CONVERSATION: '#10b981',
  CLASSIFY_EXECUTION: '#8b5cf6',
  GUARDRAIL_EXECUTION: '#ec4899',
  // Browser agent - purple-pink in the same family as classify but slightly
  // darker so the dashboard reads "agent-family but distinct".
  BROWSER_AGENT_EXECUTION: '#a855f7',
  // Stage 5.4 - slate-ish teal so compaction cost is visually adjacent to
  // the main agent series (warm colours) without blending into them.
  COMPACTION_SUMMARY: '#0ea5e9',
  // Web tools - cyan/teal family, distinct from the agent (warm) series so
  // search and fetch read as a related but separate cost group.
  WEB_SEARCH: '#14b8a6',
  WEB_FETCH: '#06b6d4',
  // Third-party API calls on the platform key (generations included). Orange, outside both the
  // agent (warm yellow/pink) and web-tool (cyan) families, because it is the one series that is not
  // an LLM cost at all: it is what the platform resold. Without an entry it drew in the fallback
  // grey, which reads as "other" for what is often the largest slice of the chart.
  PLATFORM_MARKUP: '#f97316',
  // Every relayed LLM call on a cloud-linked self-hosted install, collapsed into one type: on the
  // installs that have these rows they are most of the chart, and the fallback grey reads as
  // "other". Violet, adjacent to the agent family it is made of, and distinct from it.
  CE_LLM_RELAY: '#7c3aed',
};

const DEFAULT_COLOR = '#94a3b8';

/** What the chart stacks per day. */
type ChartMetric = 'credits' | 'calls' | 'tokens';

/** Rows the by-model table lists before folding the rest into one line. */
const TOP_MODELS = 8;

interface ChartDataPoint {
  date: string;
  [sourceType: string]: number | string;
}

interface UsageAnalyticsPanelProps {
  /**
   * Page-local workspace scope (Quota page workspace filter). When provided it
   * drives both the refetch trigger AND the per-request `X-Active-Organization-ID`
   * override, so the panel mirrors the workspace the page is filtered to - not
   * just the globally-active one. Omitted = fall back to the active workspace.
   */
  orgId?: string | null;
  /**
   * V366: when true, aggregate analytics across EVERY workspace (the "All
   * workspaces" view). Sends `allWorkspaces=true` and drops the org override.
   */
  allWorkspaces?: boolean;
  /**
   * The wallet balance in credits, when the page knows one (cloud). Turns the average daily spend
   * into "how long the balance lasts at this pace". Omitted in CE, where nothing is metered.
   */
  balance?: number | null;
}

export default function UsageAnalyticsPanel({ orgId, allWorkspaces = false, balance }: UsageAnalyticsPanelProps = {}) {
  const t = useTranslations('quota');

  const [analytics, setAnalytics] = useState<UsageAnalytics | null>(null);
  const [loading, setLoading] = useState(true);
  const [period, setPeriod] = useState('30');
  const [filterSourceType, setFilterSourceType] = useState('');
  const [filterProvider, setFilterProvider] = useState('');
  const [filterModel, setFilterModel] = useState('');
  const [metric, setMetric] = useState<ChartMetric>('credits');
  // The filters list the ids the ledger stored; this names them the way the
  // rest of the app does. See ./modelLabels.
  const modelNames = useModelNameIndex();
  const providerLabels = useMemo(
    () => providerOptionLabels(analytics?.providers ?? []),
    [analytics?.providers],
  );
  const requestSeqRef = useRef(0);

  // 2026-05-21 fix - subscribe to the active workspace so a topbar workspace
  // switch invalidates the cached analytics. Pre-fix, currentOrgId wasn't in
  // the dep array so the panel kept showing the old workspace's data after a
  // switch. The page-local workspace filter (orgId prop) takes precedence when
  // set, so the panel follows the filtered workspace; otherwise it follows the
  // globally-active one.
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);
  const scopeOrgId = orgId !== undefined ? orgId : currentOrgId;
  // V366: in the "All workspaces" view the org override is dropped; the backend
  // returns the full payer aggregate.
  const effectiveOrgId = allWorkspaces ? null : scopeOrgId;

  const fetchAnalytics = useCallback(async () => {
    const requestSeq = ++requestSeqRef.current;
    setLoading(true);
    try {
      const data = await quotaApi.getAnalytics(
        Number(period),
        filterSourceType || undefined,
        filterProvider || undefined,
        filterModel || undefined,
        effectiveOrgId,
        allWorkspaces,
      );
      if (requestSeq !== requestSeqRef.current) return;
      setAnalytics(data);
    } catch (err) {
      if (requestSeq !== requestSeqRef.current) return;
      console.error('Failed to load analytics:', err);
    } finally {
      if (requestSeq === requestSeqRef.current) {
        setLoading(false);
      }
    }
  }, [period, filterSourceType, filterProvider, filterModel, effectiveOrgId, allWorkspaces]);

  useEffect(() => {
    requestSeqRef.current += 1;
    setAnalytics(null);
    setLoading(true);
  }, [effectiveOrgId, allWorkspaces]);

  useEffect(() => {
    fetchAnalytics();
  }, [fetchAnalytics]);

  // Transform daily usage into stacked chart data, for the metric picked above the chart.
  const { chartData, sourceTypesInData } = useMemo(() => {
    if (!analytics?.dailyUsage?.length) return { chartData: [], sourceTypesInData: [] as string[] };

    const dateMap = new Map<string, ChartDataPoint>();
    const typesSet = new Set<string>();
    const valueOf = (entry: DailyUsageEntry) =>
      metric === 'credits' ? toDisplayAmount(Number(entry.credits))
        : metric === 'calls' ? Number(entry.count) || 0
          : Number(entry.tokens) || 0;

    for (const entry of analytics.dailyUsage) {
      if (!includeSourceType(entry.sourceType)) continue;
      typesSet.add(entry.sourceType);
      const existing = dateMap.get(entry.date) ?? { date: entry.date };
      existing[entry.sourceType] = (Number(existing[entry.sourceType] ?? 0)) + valueOf(entry);
      dateMap.set(entry.date, existing);
    }

    const types = Array.from(typesSet).sort();
    const data = Array.from(dateMap.values()).sort((a, b) => a.date.localeCompare(b.date));

    // Ensure all types have a value in every data point
    for (const point of data) {
      for (const type of types) {
        if (point[type] === undefined) point[type] = 0;
      }
    }

    return { chartData: data, sourceTypesInData: types };
  }, [analytics, metric]);

  // Every figure below the chart, in credits (converted at display). CE-hidden types are left out
  // everywhere so the cards, the tables and the chart add up to the same total.
  const stats = useMemo(() => {
    if (!analytics?.dailyUsage?.length) return null;
    const totals = periodTotals(analytics.dailyUsage, includeSourceType);
    if (totals.credits <= 0 && totals.calls <= 0) return null;
    const previous = previousTotals(analytics.previousModelUsage, includeSourceType);
    const days = Number(period);
    const avgDaily = totals.credits / days;
    return {
      totals,
      creditsChange: relativeChange(totals.credits, previous?.credits),
      callsChange: relativeChange(totals.calls, previous?.calls),
      avgDaily,
      avgPerCall: totals.calls > 0 ? totals.credits / totals.calls : 0,
      peak: peakDay(analytics.dailyUsage, includeSourceType),
      // The balance is the whole wallet, so it may only be divided by the whole spend: under a
      // type, provider or model filter the pace is a slice and the runway would be inflated.
      runway: filterSourceType || filterProvider || filterModel ? null : runwayDays(balance, avgDaily),
      byType: usageByType(analytics.dailyUsage, includeSourceType),
      byModel: analytics.modelUsage
        ? topWithRest(usageByModel(analytics.modelUsage, includeSourceType), TOP_MODELS)
        : null,
    };
  }, [analytics, period, balance, filterSourceType, filterProvider, filterModel]);

  const formatDate = (dateStr: string) => {
    try {
      return formatUtcDate(dateStr);
    } catch {
      return dateStr;
    }
  };

  /**
   * The name of a kind of spend, from the SAME map the history table under this chart reads.
   *
   * <p>It used to be a copy of that map, minus four entries, and the gap was exactly the spend a
   * reader comes to this page to understand: a catalogue call on the platform key appeared in the
   * table as "Platform API call" and in the chart legend and filter above it as the raw
   * {@code PLATFORM_MARKUP}. Two names, one row, one screen.
   */
  const formatSourceType = (type: string) => {
    const key = CREDIT_SOURCE_LABEL_KEYS[type];
    return key ? t(key) : type;
  };

  const locale = getClientLocale();
  // The app's cost formatter: dollars with sub-cent precision in CE (an average LLM call is a
  // fraction of a cent and would otherwise read "$0"), credits on cloud.
  const formatAmount = (credits: number) => formatCost(credits);
  const formatCount = (n: number) => n.toLocaleString(locale);
  const formatCompact = (n: number) =>
    new Intl.NumberFormat(locale, { notation: 'compact', maximumFractionDigits: 1 }).format(n);
  const formatShare = (share: number) =>
    new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 1 }).format(share);
  const formatChartValue = (value: number) =>
    metric === 'credits'
      // Sub-dollar CE days keep 4 decimals, as the cards do, instead of reading "$0.00".
      ? `${isCeMode ? '$' : ''}${value.toLocaleString(locale, { maximumFractionDigits: isCeMode && Math.abs(value) < 1 ? 4 : 2 })}`
      : value.toLocaleString(locale, { maximumFractionDigits: 0 });

  return (
    <div>
      <div className="flex items-center gap-3 mb-4">
        <div className="w-10 h-10 bg-theme-secondary rounded-xl flex items-center justify-center">
          <BarChart3 className="w-5 h-5 text-theme-primary" />
        </div>
        <div>
          <h2 className="text-lg font-semibold text-theme-primary">{t('analytics.title')}</h2>
          <p className="text-sm text-theme-secondary">{t('analytics.subtitle')}</p>
        </div>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap gap-3 mb-4">
        <Select value={period} onValueChange={setPeriod}>
          <SelectTrigger className="w-[140px] h-9 min-h-0 py-0 text-sm">
            <SelectValue placeholder={t('analytics.period')} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="7">{t('analytics.period7d')}</SelectItem>
            <SelectItem value="30">{t('analytics.period30d')}</SelectItem>
            <SelectItem value="90">{t('analytics.period90d')}</SelectItem>
          </SelectContent>
        </Select>

        <Select value={filterSourceType || 'ALL'} onValueChange={(v) => setFilterSourceType(v === 'ALL' ? '' : v)}>
          <SelectTrigger className="w-[160px] h-9 min-h-0 py-0 text-sm">
            <SelectValue placeholder={t('analytics.sourceType')} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">{t('analytics.allTypes')}</SelectItem>
            {analytics?.sourceTypes?.filter(includeSourceType).map((type) => (
              <SelectItem key={type} value={type}>{formatSourceType(type)}</SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select value={filterProvider || 'ALL'} onValueChange={(v) => setFilterProvider(v === 'ALL' ? '' : v)}>
          <SelectTrigger className="w-[160px] h-9 min-h-0 py-0 text-sm">
            <SelectValue placeholder={t('analytics.provider')} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">{t('analytics.allProviders')}</SelectItem>
            {/* The VALUE stays the stored id (it is what the API filters on);
                only the label is the name the rest of the app uses, and it falls
                back to the raw key where two stored keys would read alike. */}
            {analytics?.providers?.map((p) => (
              <SelectItem key={p} value={p}>{providerLabels.get(p) ?? p}</SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select value={filterModel || 'ALL'} onValueChange={(v) => setFilterModel(v === 'ALL' ? '' : v)}>
          <SelectTrigger className="w-[160px] h-9 min-h-0 py-0 text-sm">
            <SelectValue placeholder={t('analytics.model')} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">{t('analytics.allModels')}</SelectItem>
            {/* No provider: this list is a flat set of model ids with no provider
                beside them, and the SELECTED provider filter is not model `m`'s.
                A name is shown only where the id is unambiguous, which is the
                honest answer here. */}
            {analytics?.models?.map((m) => (
              <SelectItem key={m} value={m}>{modelLabelFor(null, m, modelNames)}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* Chart */}
      <div className="bg-theme-secondary rounded-xl p-4 border border-theme">
        {/* What the chart stacks: the spend is the default, calls and tokens say whether a rise
            comes from doing more or from each call costing more. */}
        <div className="flex justify-end mb-3" role="group" aria-label={t('analytics.chartMetric')}>
          <div className="inline-flex rounded-lg border border-theme p-0.5 bg-theme-primary">
            {(['credits', 'calls', 'tokens'] as const).map((m) => (
              <button
                key={m}
                type="button"
                aria-pressed={metric === m}
                onClick={() => setMetric(m)}
                data-testid={`analytics-metric-${m}`}
                className={`px-2.5 h-7 rounded-md text-sm font-medium transition-colors ${
                  metric === m ? 'bg-theme-secondary text-theme-primary' : 'text-theme-secondary hover:text-theme-primary'
                }`}
              >
                {m === 'credits'
                  ? (isCeMode ? t('analytics.metricCost') : t('analytics.metricCredits'))
                  : m === 'calls' ? t('analytics.metricCalls') : t('analytics.metricTokens')}
              </button>
            ))}
          </div>
        </div>
        {loading ? (
          <div className="h-[300px] flex items-center justify-center">
            <div className="animate-pulse text-sm text-theme-secondary">{t('analytics.title')}...</div>
          </div>
        ) : chartData.length === 0 ? (
          <div className="h-[300px] flex items-center justify-center">
            <p className="text-sm text-theme-secondary">{t('analytics.noData')}</p>
          </div>
        ) : (
          <ResponsiveContainer width="100%" height={300}>
            <AreaChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" strokeOpacity={0.2} />
              <XAxis
                dataKey="date"
                tickFormatter={formatDate}
                tick={{ fontSize: 12 }}
                tickLine={false}
              />
              <YAxis
                tick={{ fontSize: 12 }}
                tickLine={false}
                axisLine={false}
              />
              <Tooltip
                labelFormatter={formatDate}
                formatter={(value: number, name: string) => [formatChartValue(value), formatSourceType(name)]}
                contentStyle={{
                  backgroundColor: 'var(--bg-secondary)',
                  border: '1px solid var(--border-color)',
                  borderRadius: '8px',
                  fontSize: '12px',
                  color: 'var(--text-primary)',
                }}
                itemStyle={{ color: 'var(--text-primary)' }}
                labelStyle={{ color: 'var(--text-primary)' }}
              />
              <Legend formatter={formatSourceType} wrapperStyle={{ fontSize: '12px' }} />
              {sourceTypesInData.map((type) => (
                <Area
                  key={type}
                  type="monotone"
                  dataKey={type}
                  stackId="1"
                  stroke={SOURCE_TYPE_COLORS[type] ?? DEFAULT_COLOR}
                  fill={SOURCE_TYPE_COLORS[type] ?? DEFAULT_COLOR}
                  fillOpacity={0.4}
                />
              ))}
            </AreaChart>
          </ResponsiveContainer>
        )}
      </div>

      {/* Key figures of the period */}
      {stats && (
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4 mt-4" data-testid="analytics-kpis">
          <KpiCard
            icon={Zap}
            label={isCeMode ? t('analytics.totalCost') : t('analytics.totalCredits')}
            value={formatAmount(stats.totals.credits)}
            change={stats.creditsChange}
            changeLabel={t('analytics.vsPrevious')}
            locale={locale}
          />
          <KpiCard
            icon={Hash}
            label={t('analytics.calls')}
            value={formatCount(stats.totals.calls)}
            change={stats.callsChange}
            changeLabel={t('analytics.vsPrevious')}
            locale={locale}
          />
          <KpiCard
            icon={Cpu}
            label={t('analytics.tokens')}
            value={stats.totals.tokens > 0 ? formatCompact(stats.totals.tokens) : '-'}
            title={formatCount(stats.totals.tokens)}
            locale={locale}
          />
          <KpiCard
            icon={Gauge}
            label={t('analytics.avgPerCall')}
            value={stats.totals.calls > 0 ? formatAmount(stats.avgPerCall) : '-'}
            locale={locale}
          />
          <KpiCard
            icon={TrendingUp}
            label={t('analytics.avgDaily')}
            value={formatAmount(stats.avgDaily)}
            // The peak gets its own card when there is no runway to show; only then is it a hint here.
            hint={stats.peak && stats.runway !== null
              ? t('analytics.peakOn', { date: formatDate(stats.peak.date), amount: formatAmount(stats.peak.credits) })
              : undefined}
            locale={locale}
          />
          {/* With a known balance, the pace becomes a date the reader can act on; without one
              (CE, or the wallet not loaded) the peak day is the more useful last figure. */}
          {stats.runway !== null ? (
            <KpiCard
              icon={Hourglass}
              label={t('analytics.runway')}
              value={stats.runway > 365 ? t('analytics.runwayOverYear') : t('analytics.runwayDays', { count: stats.runway })}
              hint={t('analytics.runwayHint')}
              locale={locale}
            />
          ) : (
            <KpiCard
              icon={Calendar}
              label={t('analytics.peakDay')}
              value={stats.peak ? formatDate(stats.peak.date) : '-'}
              hint={stats.peak ? formatAmount(stats.peak.credits) : undefined}
              locale={locale}
            />
          )}
        </div>
      )}

      {/* Where the spend went: by kind of spend, and by model. A row filters the chart on it. */}
      {stats && (
        <div className={`grid grid-cols-1 gap-4 mt-4 ${stats.byModel ? 'lg:grid-cols-2' : ''}`}>
          <BreakdownTable
            testId="analytics-by-type"
            title={t('analytics.byType')}
            nameHeader={t('analytics.sourceType')}
            amountHeader={isCeMode ? t('analytics.metricCost') : t('analytics.metricCredits')}
            callsHeader={t('analytics.calls')}
            filterHint={t('analytics.filterOn')}
            rows={stats.byType.map((r) => ({
              key: r.sourceType,
              name: formatSourceType(r.sourceType),
              color: SOURCE_TYPE_COLORS[r.sourceType] ?? DEFAULT_COLOR,
              amount: formatAmount(r.credits),
              calls: formatCount(r.calls),
              share: r.share,
              active: filterSourceType === r.sourceType,
              onSelect: () => setFilterSourceType(filterSourceType === r.sourceType ? '' : r.sourceType),
            }))}
            formatShare={formatShare}
          />
          {stats.byModel && (
            <BreakdownTable
              testId="analytics-by-model"
              title={t('analytics.byModel')}
              nameHeader={t('analytics.model')}
              amountHeader={isCeMode ? t('analytics.metricCost') : t('analytics.metricCredits')}
              callsHeader={t('analytics.calls')}
              filterHint={t('analytics.filterOn')}
              rows={[
                ...stats.byModel.top.map((r) => {
                  const model = r.model;
                  return {
                    key: `${r.provider ?? ''}/${model ?? ''}`,
                    name: model
                      ? <ProviderModelCell provider={r.provider} model={model} index={modelNames} />
                      : t('analytics.noModel'),
                    amount: formatAmount(r.credits),
                    calls: formatCount(r.calls),
                    share: r.share,
                    active: !!model && filterModel === model,
                    // A row with no model has nothing to filter on.
                    onSelect: model ? () => setFilterModel(filterModel === model ? '' : model) : undefined,
                  };
                }),
                ...(stats.byModel.rest
                  ? [{
                      key: '__rest__',
                      name: t('analytics.otherModels', { count: stats.byModel.rest.count }),
                      amount: formatAmount(stats.byModel.rest.credits),
                      calls: formatCount(stats.byModel.rest.calls),
                      share: stats.byModel.rest.share,
                      active: false,
                    }]
                  : []),
              ]}
              formatShare={formatShare}
            />
          )}
        </div>
      )}
    </div>
  );
}

function KpiCard({
  icon: Icon,
  label,
  value,
  title,
  hint,
  change,
  changeLabel,
  locale,
}: {
  icon: React.ElementType;
  label: string;
  value: string;
  title?: string;
  hint?: string;
  /** Relative change vs the previous period; null or undefined = nothing to compare to, not shown. */
  change?: number | null;
  changeLabel?: string;
  locale: string;
}) {
  return (
    <div className="bg-theme-secondary rounded-xl p-4 border border-theme min-w-0">
      <div className="flex items-center gap-2 mb-1">
        <Icon className="h-3.5 w-3.5 text-theme-secondary shrink-0" />
        <p className="text-xs text-theme-secondary truncate">{label}</p>
      </div>
      <p className="text-lg font-semibold text-theme-primary truncate" title={title ?? value}>{value}</p>
      {change != null && (
        <p className="flex items-center gap-1 text-xs text-theme-tertiary mt-0.5" data-testid="kpi-change">
          {change >= 0 ? <TrendingUp className="h-3 w-3 shrink-0" /> : <TrendingDown className="h-3 w-3 shrink-0" />}
          <span className="font-medium text-theme-secondary">
            {new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 0, signDisplay: 'exceptZero' }).format(change)}
          </span>
          <span className="truncate">{changeLabel}</span>
        </p>
      )}
      {hint && <p className="text-xs text-theme-tertiary mt-0.5 truncate" title={hint}>{hint}</p>}
    </div>
  );
}

interface BreakdownRow {
  key: string;
  name: React.ReactNode;
  color?: string;
  amount: string;
  calls: string;
  share: number;
  active: boolean;
  onSelect?: () => void;
}

function BreakdownTable({
  testId,
  title,
  nameHeader,
  amountHeader,
  callsHeader,
  filterHint,
  rows,
  formatShare,
}: {
  testId: string;
  title: string;
  nameHeader: string;
  amountHeader: string;
  callsHeader: string;
  filterHint: string;
  rows: BreakdownRow[];
  formatShare: (share: number) => string;
}) {
  return (
    <div className="bg-theme-secondary rounded-xl p-4 border border-theme min-w-0" data-testid={testId}>
      <h3 className="text-sm font-semibold text-theme-primary mb-3">{title}</h3>
      <table className="w-full table-fixed text-sm">
        <colgroup>
          <col />
          <col className="w-[104px]" />
          <col className="w-[72px]" />
        </colgroup>
        <thead>
          <tr className="text-xs text-theme-secondary">
            <th className="text-left font-medium pb-2">{nameHeader}</th>
            <th className="text-right font-medium pb-2">{amountHeader}</th>
            <th className="text-right font-medium pb-2">{callsHeader}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.key} data-testid={`${testId}-row`} className="align-top">
              <td className="py-1.5 pr-3">
                {r.onSelect ? (
                  <button
                    type="button"
                    onClick={r.onSelect}
                    aria-pressed={r.active}
                    title={filterHint}
                    className={`flex items-center gap-2 max-w-full text-left hover:underline ${r.active ? 'font-semibold' : ''}`}
                  >
                    {r.color && <span className="h-2 w-2 rounded-full shrink-0" style={{ backgroundColor: r.color }} />}
                    <span className="truncate text-theme-primary">{r.name}</span>
                  </button>
                ) : (
                  <span className="flex items-center gap-2 max-w-full">
                    {r.color && <span className="h-2 w-2 rounded-full shrink-0" style={{ backgroundColor: r.color }} />}
                    <span className="truncate text-theme-secondary">{r.name}</span>
                  </span>
                )}
                {/* How much of the period this row is, readable at a glance. */}
                <div className="mt-1 flex items-center gap-2">
                  <div className="h-1 flex-1 bg-theme-tertiary rounded-full overflow-hidden">
                    <div
                      className="h-full rounded-full"
                      style={{ width: `${Math.min(100, r.share * 100)}%`, backgroundColor: r.color ?? 'var(--text-secondary)' }}
                    />
                  </div>
                  <span className="text-xs text-theme-tertiary w-12 text-right shrink-0">{formatShare(r.share)}</span>
                </div>
              </td>
              <td className="py-1.5 text-right text-theme-primary whitespace-nowrap overflow-hidden text-ellipsis" title={r.amount}>{r.amount}</td>
              <td className="py-1.5 text-right text-theme-secondary whitespace-nowrap overflow-hidden text-ellipsis" title={r.calls}>{r.calls}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
