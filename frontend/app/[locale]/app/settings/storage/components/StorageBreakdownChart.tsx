'use client';

import React, { useState, useMemo, useEffect, useCallback, useRef } from 'react';
import { useTranslations } from 'next-intl';
import { TrendingUp } from 'lucide-react';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend,
} from 'recharts';
import { cn } from '@/lib/utils';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { storageApi } from '@/lib/api';
import type { StorageHistoryPoint, StorageCategory, StorageBreakdown } from '@/lib/api';
import { mergeStorageHistories } from '../storageAggregation';
import { STORAGE_CATEGORY_HEX } from '@/lib/api';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

/** Order categories by typical size (largest first) */
const CATEGORY_ORDER: StorageCategory[] = [
  'STEP_OUTPUTS', 'FILES', 'EXECUTION_DATA', 'AGENTS',
  'INTERFACES', 'CONVERSATIONS', 'CONFIGURATION', 'DATATABLES', 'PUBLICATIONS',
];

function useIsDarkMode() {
  const [dark, setDark] = useState(false);
  useEffect(() => {
    const check = () => setDark(document.documentElement.classList.contains('dark'));
    check();
    const observer = new MutationObserver(check);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    return () => observer.disconnect();
  }, []);
  return dark;
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

interface StorageBreakdownChartProps {
  className?: string;
  currentBreakdown?: StorageBreakdown[];
  /**
   * Page-local workspace scope (Storage page workspace filter). When provided it
   * drives both the refetch trigger AND the per-request `X-Active-Organization-ID`
   * override, so the trend chart mirrors the workspace the page is filtered to.
   * Omitted = fall back to the active workspace.
   */
  orgId?: string | null;
  /**
   * "All workspaces" view: when set, the trend is the SUM across these workspaces -
   * each workspace's history is fetched in parallel and merged by date + category.
   * Takes precedence over {@link orgId}.
   */
  allWorkspaceIds?: string[];
}

export default function StorageBreakdownChart({ className, currentBreakdown = [], orgId, allWorkspaceIds }: StorageBreakdownChartProps) {
  const t = useTranslations('storage');
  const isDark = useIsDarkMode();
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);
  const scopeOrgId = orgId !== undefined ? orgId : currentOrgId;
  const isAll = !!allWorkspaceIds && allWorkspaceIds.length > 0;
  // Stable primitive for the dep lists (the array ref changes each render).
  const allWorkspaceIdsKey = (allWorkspaceIds ?? []).join(',');
  const [period, setPeriod] = useState('30');
  const [history, setHistory] = useState<StorageHistoryPoint[]>([]);
  const [loading, setLoading] = useState(true);
  const requestSeqRef = useRef(0);

  const fetchHistory = useCallback(async (days: number) => {
    const requestSeq = ++requestSeqRef.current;
    setLoading(true);
    try {
      const data = isAll
        ? mergeStorageHistories(await Promise.all(
            allWorkspaceIdsKey.split(',').filter(Boolean).map((id) => storageApi.getHistory(days, id).catch(() => [] as StorageHistoryPoint[])),
          ))
        : await storageApi.getHistory(days, scopeOrgId);
      if (requestSeq !== requestSeqRef.current) return;
      setHistory(Array.isArray(data) ? data : []);
    } catch (err) {
      if (requestSeq !== requestSeqRef.current) return;
      console.error('Failed to fetch storage history:', err);
    } finally {
      if (requestSeq === requestSeqRef.current) {
        setLoading(false);
      }
    }
  }, [scopeOrgId, isAll, allWorkspaceIdsKey]);

  useEffect(() => {
    requestSeqRef.current += 1;
    setHistory([]);
    setLoading(true);
  }, [scopeOrgId, allWorkspaceIdsKey]);

  useEffect(() => {
    fetchHistory(Number(period));
  }, [period, fetchHistory]);

  const displayHistory = useMemo<StorageHistoryPoint[]>(() => {
    if (history.length > 0) return history;
    const positiveBreakdown = currentBreakdown.filter((entry) => entry.usedBytes > 0);
    if (positiveBreakdown.length === 0) return [];
    const today = new Date().toISOString().slice(0, 10);
    return positiveBreakdown.map((entry) => ({
      snapshotDate: today,
      category: entry.category,
      usedBytes: entry.usedBytes,
      itemCount: entry.itemCount,
    }));
  }, [history, currentBreakdown]);

  /** Pivot raw history into per-date objects for Recharts */
  const chartData = useMemo(() => {
    const byDate = new Map<string, Record<string, number>>();
    for (const point of displayHistory) {
      const existing = byDate.get(point.snapshotDate) || {};
      existing[point.category] = point.usedBytes;
      byDate.set(point.snapshotDate, existing);
    }
    return Array.from(byDate.entries())
      .map(([date, cats]) => ({ date, ...cats }))
      .sort((a, b) => a.date.localeCompare(b.date));
  }, [displayHistory]);

  /** Which categories are present in the data */
  const activeCategories = useMemo(() => {
    const cats = new Set<string>();
    for (const point of displayHistory) {
      if (point.usedBytes > 0) cats.add(point.category);
    }
    return CATEGORY_ORDER.filter(c => cats.has(c));
  }, [displayHistory]);

  const getColor = (cat: StorageCategory) => {
    const hex = STORAGE_CATEGORY_HEX[cat];
    return hex ? (isDark ? hex.dark : hex.light) : '#6b7280';
  };

  return (
    <div className={cn('', className)}>
      <div className="flex items-center gap-3 mb-4">
        <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
          <TrendingUp className="w-5 h-5 text-theme-primary" />
        </div>
        <div>
          <h2 className="text-lg font-semibold text-theme-primary">{t('trends.title')}</h2>
          <p className="text-sm text-theme-secondary">{t('trends.subtitle')}</p>
        </div>
      </div>

      {/* Period selector - matches Usage Analytics filter row */}
      <div className="flex flex-wrap gap-3 mb-4">
        <Select value={period} onValueChange={setPeriod}>
          <SelectTrigger className="w-[140px] h-9 min-h-0 py-0 text-sm">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="7">{t('trends.period7d')}</SelectItem>
            <SelectItem value="30">{t('trends.period30d')}</SelectItem>
            <SelectItem value="90">{t('trends.period90d')}</SelectItem>
          </SelectContent>
        </Select>
      </div>

      <div className="bg-theme-secondary rounded-xl p-4 border border-theme relative">
        {loading && (
          <div className="absolute inset-0 bg-theme-secondary/60 rounded-xl flex items-center justify-center z-10">
            <div className="h-5 w-5 border-2 border-theme-muted border-t-theme-primary rounded-full animate-spin" />
          </div>
        )}

        {chartData.length === 0 && !loading ? (
          <div className="h-[300px] flex items-center justify-center">
            <p className="text-sm text-theme-secondary">{t('trends.noHistory')}</p>
          </div>
        ) : (
          <ResponsiveContainer width="100%" height={300}>
            <AreaChart data={chartData} margin={{ top: 12, right: 20, left: 0, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" strokeOpacity={0.2} />
              <XAxis
                dataKey="date"
                tick={{ fontSize: 12 }}
                tickLine={false}
                tickFormatter={(v: string) => {
                  // Parse the bucket key as UTC midnight (matches backend DATE)
                  // and read UTC components so the displayed day never drifts
                  // for users far east/west of UTC.
                  const d = new Date(v + 'T00:00:00Z');
                  return `${d.getUTCDate()}/${d.getUTCMonth() + 1}`;
                }}
              />
              <YAxis
                tick={{ fontSize: 12 }}
                tickLine={false}
                axisLine={false}
                tickFormatter={(v: number) => formatBytes(v)}
                width={85}
              />
              <Tooltip
                contentStyle={{
                  backgroundColor: 'var(--bg-secondary)',
                  border: '1px solid var(--border-color)',
                  borderRadius: '8px',
                  fontSize: '12px',
                  color: 'var(--text-primary)',
                }}
                itemStyle={{ color: 'var(--text-primary)' }}
                labelStyle={{ color: 'var(--text-primary)' }}
                labelFormatter={(label: string) => formatUtcDate(label + 'T00:00:00Z')}
                formatter={(value: number, name: string) => [
                  formatBytes(value),
                  t(`categories.${name}` as any),
                ]}
              />
              <Legend
                formatter={(value: string) => (
                  <span className="text-xs text-theme-secondary">
                    {t(`categories.${value}` as any)}
                  </span>
                )}
                wrapperStyle={{ fontSize: '12px' }}
              />
              {activeCategories.map((cat) => (
                <Area
                  key={cat}
                  type="monotone"
                  dataKey={cat}
                  stackId="1"
                  stroke={getColor(cat)}
                  fill={getColor(cat)}
                  fillOpacity={0.4}
                  strokeWidth={1.5}
                />
              ))}
            </AreaChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}
