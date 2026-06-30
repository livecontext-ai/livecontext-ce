'use client';

import React, { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { BarChart3, TrendingUp, Calendar, Zap } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { quotaApi, UsageAnalytics, DailyUsageEntry } from '@/lib/api';
import { isCeMode, creditsToUsd } from '@/lib/format-cost';
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
};

const DEFAULT_COLOR = '#94a3b8';

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
}

export default function UsageAnalyticsPanel({ orgId, allWorkspaces = false }: UsageAnalyticsPanelProps = {}) {
  const t = useTranslations('quota');

  const [analytics, setAnalytics] = useState<UsageAnalytics | null>(null);
  const [loading, setLoading] = useState(true);
  const [period, setPeriod] = useState('30');
  const [filterSourceType, setFilterSourceType] = useState('');
  const [filterProvider, setFilterProvider] = useState('');
  const [filterModel, setFilterModel] = useState('');
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

  // Transform daily usage into stacked chart data
  const { chartData, sourceTypesInData } = useMemo(() => {
    if (!analytics?.dailyUsage?.length) return { chartData: [], sourceTypesInData: [] as string[] };

    const dateMap = new Map<string, ChartDataPoint>();
    const typesSet = new Set<string>();

    for (const entry of analytics.dailyUsage) {
      if (!includeSourceType(entry.sourceType)) continue;
      typesSet.add(entry.sourceType);
      const existing = dateMap.get(entry.date) ?? { date: entry.date };
      existing[entry.sourceType] = (Number(existing[entry.sourceType] ?? 0)) + toDisplayAmount(Number(entry.credits));
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
  }, [analytics]);

  // Summary stats
  const summaryStats = useMemo(() => {
    if (!analytics?.dailyUsage?.length) return null;

    // Exclude CE-hidden source types (WORKFLOW_NODE) so the totals match the chart.
    const entries = analytics.dailyUsage.filter((e) => includeSourceType(e.sourceType));
    if (!entries.length) return null;

    const totalCredits = entries.reduce((sum, e) => sum + toDisplayAmount(Number(e.credits)), 0);
    const days = Number(period);

    // Most active source type
    const typeTotals = new Map<string, number>();
    for (const entry of entries) {
      typeTotals.set(entry.sourceType, (typeTotals.get(entry.sourceType) ?? 0) + Number(entry.credits));
    }
    let mostActive = '';
    let maxCredits = 0;
    for (const [type, total] of typeTotals) {
      if (total > maxCredits) {
        mostActive = type;
        maxCredits = total;
      }
    }

    // Peak day
    const dayTotals = new Map<string, number>();
    for (const entry of entries) {
      dayTotals.set(entry.date, (dayTotals.get(entry.date) ?? 0) + Number(entry.credits));
    }
    let peakDay = '';
    let peakCredits = 0;
    for (const [date, total] of dayTotals) {
      if (total > peakCredits) {
        peakDay = date;
        peakCredits = total;
      }
    }

    return {
      totalCredits,
      avgDaily: totalCredits / days,
      mostActive,
      peakDay,
    };
  }, [analytics, period]);

  const formatDate = (dateStr: string) => {
    try {
      return formatUtcDate(dateStr);
    } catch {
      return dateStr;
    }
  };

  const formatSourceType = (type: string) => {
    const keyMap: Record<string, string> = {
      WORKFLOW_NODE: 'types.workflowNode',
      AGENT_EXECUTION: 'types.agent',
      CHAT_CONVERSATION: 'types.chat',
      CLASSIFY_EXECUTION: 'types.classify',
      GUARDRAIL_EXECUTION: 'types.guardrail',
      BROWSER_AGENT_EXECUTION: 'types.browserAgent',
      COMPACTION_SUMMARY: 'types.compactionSummary',
      WEB_SEARCH: 'types.webSearch',
      WEB_FETCH: 'types.webFetch',
    };
    const key = keyMap[type];
    return key ? t(key) : type;
  };

  return (
    <div>
      <div className="flex items-center gap-3 mb-4">
        <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
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
            {analytics?.providers?.map((p) => (
              <SelectItem key={p} value={p}>{p}</SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select value={filterModel || 'ALL'} onValueChange={(v) => setFilterModel(v === 'ALL' ? '' : v)}>
          <SelectTrigger className="w-[160px] h-9 min-h-0 py-0 text-sm">
            <SelectValue placeholder={t('analytics.model')} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">{t('analytics.allModels')}</SelectItem>
            {analytics?.models?.map((m) => (
              <SelectItem key={m} value={m}>{m}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* Chart */}
      <div className="bg-theme-secondary rounded-xl p-4 border border-theme">
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
                formatter={(value: number, name: string) => [
                  `${isCeMode ? '$' : ''}${value.toLocaleString(getClientLocale(), { maximumFractionDigits: 2 })}`,
                  formatSourceType(name),
                ]}
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

      {/* Summary stats */}
      {summaryStats && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mt-4">
          <div className="bg-theme-secondary rounded-xl p-4 border border-theme">
            <div className="flex items-center gap-2 mb-1">
              <Zap className="h-3.5 w-3.5 text-theme-secondary" />
              <p className="text-xs text-theme-secondary">{isCeMode ? t('analytics.totalCost') : t('analytics.totalCredits')}</p>
            </div>
            <p className="text-lg font-semibold text-theme-primary">
              {isCeMode ? '$' : ''}{summaryStats.totalCredits.toLocaleString(getClientLocale(), { maximumFractionDigits: 2 })}
            </p>
          </div>

          <div className="bg-theme-secondary rounded-xl p-4 border border-theme">
            <div className="flex items-center gap-2 mb-1">
              <TrendingUp className="h-3.5 w-3.5 text-theme-secondary" />
              <p className="text-xs text-theme-secondary">{t('analytics.avgDaily')}</p>
            </div>
            <p className="text-lg font-semibold text-theme-primary">
              {isCeMode ? '$' : ''}{summaryStats.avgDaily.toLocaleString(getClientLocale(), { maximumFractionDigits: 2 })}
            </p>
          </div>

          <div className="bg-theme-secondary rounded-xl p-4 border border-theme">
            <div className="flex items-center gap-2 mb-1">
              <Calendar className="h-3.5 w-3.5 text-theme-secondary" />
              <p className="text-xs text-theme-secondary">{t('analytics.peakDay')}</p>
            </div>
            <p className="text-lg font-semibold text-theme-primary">
              {summaryStats.peakDay ? formatDate(summaryStats.peakDay) : '-'}
            </p>
          </div>

          <div className="bg-theme-secondary rounded-xl p-4 border border-theme">
            <div className="flex items-center gap-2 mb-1">
              <BarChart3 className="h-3.5 w-3.5 text-theme-secondary" />
              <p className="text-xs text-theme-secondary">{t('analytics.mostActive')}</p>
            </div>
            <p className="text-lg font-semibold text-theme-primary">
              {summaryStats.mostActive ? formatSourceType(summaryStats.mostActive) : '-'}
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
