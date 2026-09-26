'use client';

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, RefreshCw, ShieldAlert, UserX, Wrench } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useAuth } from '@/lib/providers/smart-providers';
import { Button } from '@/components/ui/button';
import { apiClient } from '@/lib/api/api-client';
import { getClientLocale } from '@/lib/utils/locale';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';

/**
 * A tool's verdict, computed server-side from how many tenants it fails for.
 * The value is what makes this page worth opening: a failure rate alone cannot
 * tell a broken catalog entry from one customer's expired key, and the two need
 * opposite fixes.
 */
type Verdict = 'ALL_TENANTS' | 'WIDESPREAD' | 'ISOLATED' | 'SINGLE_TENANT';

interface ToolHealthRow {
  toolName: string;
  /** The API or tool id carried in the call arguments. tool_name alone is a meta-tool. */
  toolRef: string | null;
  totalCalls: number;
  failureCount: number;
  failureRatePct: number;
  tenantsCalling: number;
  tenantsAffected: number;
  lastUsedAt: string | null;
  sampleError: string | null;
  verdict: Verdict;
}

interface ToolHealthResponse {
  window: { minCalls: number; sinceDays: number; limit: number };
  toolsWithFailures: number;
  catalogSuspects: number;
  tools: ToolHealthRow[];
}

/** Catalog-facing verdicts come first: they are the ones we can fix ourselves. */
const VERDICT_ORDER: Verdict[] = ['ALL_TENANTS', 'WIDESPREAD', 'ISOLATED', 'SINGLE_TENANT'];

const VERDICT_STYLE: Record<Verdict, string> = {
  ALL_TENANTS: 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-200',
  WIDESPREAD: 'bg-orange-100 text-orange-800 dark:bg-orange-950 dark:text-orange-200',
  ISOLATED: 'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-200',
  SINGLE_TENANT: 'bg-muted text-muted-foreground',
};

const WINDOWS = [7, 30, 90, 0];

export default function ToolHealthPage() {
  const { isLoading: authLoading } = useAuthGuard();
  const { user } = useAuth();
  const t = useTranslations('toolHealth');

  const [data, setData] = useState<ToolHealthResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sinceDays, setSinceDays] = useState(30);
  const [minCalls, setMinCalls] = useState(10);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await apiClient.get<ToolHealthResponse>('/agents/tool-health', {
        params: { sinceDays: String(sinceDays), minCalls: String(minCalls), limit: '100' },
      });
      setData(res);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [sinceDays, minCalls]);

  useEffect(() => {
    if (authLoading) return;
    void load();
  }, [authLoading, load]);

  const grouped = useMemo(() => {
    const rows = data?.tools ?? [];
    return VERDICT_ORDER.map((v) => ({ verdict: v, rows: rows.filter((r) => r.verdict === v) })).filter(
      (g) => g.rows.length > 0
    );
  }, [data]);

  if (authLoading) {
    return <div className="p-6 text-sm text-muted-foreground">{t('loading')}</div>;
  }

  return (
    <div className="mx-auto w-full max-w-6xl space-y-6 p-4 sm:p-6">
      <header className="space-y-1">
        <h1 className="flex items-center gap-2 text-base font-semibold">
          <Wrench className="h-3.5 w-3.5" />
          {t('title')}
        </h1>
        <p className="text-sm text-muted-foreground">{t('subtitle')}</p>
      </header>

      <div className="flex flex-wrap items-end gap-3 rounded-lg border p-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">{t('window')}</span>
          <select
            className="h-8 rounded-md border bg-background px-2 text-sm"
            value={sinceDays}
            onChange={(e) => setSinceDays(Number(e.target.value))}
          >
            {WINDOWS.map((w) => (
              <option key={w} value={w}>
                {w === 0 ? t('windowAll') : t('windowDays', { days: w })}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">{t('minCalls')}</span>
          <input
            type="number"
            min={1}
            className="h-8 w-24 rounded-md border bg-background px-2 text-sm"
            value={minCalls}
            onChange={(e) => setMinCalls(Math.max(1, Number(e.target.value) || 1))}
          />
        </label>
        <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
          <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
          {t('refresh')}
        </Button>
        {data && (
          <p className="ml-auto text-sm text-muted-foreground">
            {t('summary', {
              tools: data.toolsWithFailures.toLocaleString(getClientLocale()),
              suspects: data.catalogSuspects.toLocaleString(getClientLocale()),
            })}
          </p>
        )}
      </div>

      {error && (
        <div className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 p-3 text-sm dark:border-red-900 dark:bg-red-950">
          <ShieldAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <div>
            <p className="font-medium">{t('errorTitle')}</p>
            <p className="text-muted-foreground">{error}</p>
          </div>
        </div>
      )}

      {!error && data && data.tools.length === 0 && (
        <p className="rounded-lg border p-6 text-center text-sm text-muted-foreground">{t('empty')}</p>
      )}

      {grouped.map(({ verdict, rows }) => (
        <section key={verdict} className="space-y-2">
          <h2 className="flex items-center gap-2 text-sm font-medium">
            <span className={`rounded px-1.5 py-0.5 text-xs font-medium ${VERDICT_STYLE[verdict]}`}>
              {t(`verdict.${verdict}`)}
            </span>
            <span className="text-muted-foreground">{t(`verdictHint.${verdict}`)}</span>
          </h2>
          <div className="overflow-x-auto rounded-lg border">
            <table className="w-full text-sm">
              <thead className="bg-muted/50">
                <tr className="text-left">
                  <th className="p-2 font-medium">{t('col.tool')}</th>
                  <th className="p-2 font-medium">{t('col.failures')}</th>
                  <th className="p-2 font-medium">{t('col.rate')}</th>
                  <th className="p-2 font-medium">{t('col.tenants')}</th>
                  <th className="p-2 font-medium">{t('col.lastUsed')}</th>
                  <th className="p-2 font-medium">{t('col.error')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  // Rows are grouped by (tool_name, tool_ref), so the name alone
                  // repeats across every endpoint of the same meta-tool.
                  <tr key={`${r.toolName}#${r.toolRef ?? ''}`} className="border-t align-top">
                    <td className="p-2 font-mono text-xs">
                      {r.toolName}
                      {r.toolRef && <div className="text-muted-foreground">{r.toolRef}</div>}
                    </td>
                    <td className="p-2 whitespace-nowrap">
                      {r.failureCount.toLocaleString(getClientLocale())}
                      <span className="text-muted-foreground">
                        {' / '}
                        {r.totalCalls.toLocaleString(getClientLocale())}
                      </span>
                    </td>
                    <td className="p-2 whitespace-nowrap">
                      {r.failureRatePct.toLocaleString(getClientLocale(), { maximumFractionDigits: 1 })}
                      {' %'}
                    </td>
                    <td className="p-2 whitespace-nowrap">
                      <span className="inline-flex items-center gap-1">
                        {r.tenantsAffected > 1 ? (
                          <AlertTriangle className="h-3 w-3" />
                        ) : (
                          <UserX className="h-3 w-3" />
                        )}
                        {t('tenantsOf', {
                          affected: r.tenantsAffected.toLocaleString(getClientLocale()),
                          calling: r.tenantsCalling.toLocaleString(getClientLocale()),
                        })}
                      </span>
                    </td>
                    <td className="p-2 whitespace-nowrap text-muted-foreground">
                      {r.lastUsedAt ? formatUtcDateTime(r.lastUsedAt) : '-'}
                    </td>
                    <td className="p-2 text-xs text-muted-foreground">{r.sampleError || '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ))}

      <p className="text-sm text-muted-foreground">{t('scopeNote')}</p>
      {user?.email && <span className="sr-only">{user.email}</span>}
    </div>
  );
}
