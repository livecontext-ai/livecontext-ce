'use client';

import React, { useEffect, useState, useCallback, useRef } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { Coins, Bot, MessageSquare, Workflow, RefreshCw, Filter, ChevronLeft, ChevronRight, User, ArrowDownCircle } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { quotaApi, CreditSummary, CreditHistoryPage } from '@/lib/api';
import { useAuth } from '@/lib/providers/smart-providers';
import UsageAnalyticsPanel from './components/UsageAnalyticsPanel';
import { isCeMode, creditsToUsd } from '@/lib/format-cost';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { BalanceBreakdownCard, TopUpModal } from '@/components/billing';
import { useSubscription, useCreditBalance, usePaygTiers } from '@/lib/hooks/smart-hooks-complete';
import { CREDIT_TIERS } from '@/lib/billing/pricing-constants';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { cloudLinkService } from '@/lib/api/cloud-link.service';
import { WorkspaceScopeSelect, ALL_WORKSPACES_SCOPE } from '@/components/settings/WorkspaceScopeSelect';

/** Label keys per source type */
// WORKFLOW_RUN intentionally absent: there is no per-run debit - billing
// happens at the WORKFLOW_NODE granularity. Listing it here would surface a
// dropdown filter that always returns zero rows.
// V148+ display labels. Includes legacy IMAGE_GENERATION* keys so historical
// ledger rows (pre-cutover) still render with a friendly label rather than the
// raw enum string. Display-only - these source types are no longer written by
// the new billing path; new image generations bill as PLATFORM_MARKUP via the
// unified scope reservation lifecycle.
const SOURCE_LABEL_KEYS: Record<string, string> = {
  AGENT_EXECUTION: 'types.agent',
  WORKFLOW_NODE: 'types.workflowNode',
  // Launch promo: nodes run free (0 credits) and are logged as a distinct 0-cost
  // source type so history rows stay clearly labeled "Workflow node (free)".
  WORKFLOW_NODE_PROMO: 'types.workflowNodePromo',
  CHAT_CONVERSATION: 'types.chat',
  // Cloud-linked CE: every relayed LLM call is billed cloud-side under this single source
  // type (the cloud collapses chat/agent/workflow origin into one). It only ever appears
  // in the cloud-mirrored view, never the local CE ledger.
  CE_LLM_RELAY: 'types.cloudRelay',
  CLASSIFY_EXECUTION: 'types.classify',
  GUARDRAIL_EXECUTION: 'types.guardrail',
  // Browser-agent runs: LLM-driven Chromium sessions surfaced separately
  // from chat-agent / classify / guardrail because the cost profile is
  // different (visual context tokens dominate, multi-minute wall clock).
  BROWSER_AGENT_EXECUTION: 'types.browserAgent',
  // Stage 5.4 - COLD-summary calls charged via AgentObservabilityService.
  // Segregated from AGENT_EXECUTION so users can see compaction cost
  // separately in the quota breakdown + analytics panel.
  COMPACTION_SUMMARY: 'types.compactionSummary',
  // Web tools - search and fetch are billed as separate source types so they
  // can be filtered independently on the quota / usage analytics page.
  WEB_SEARCH: 'types.webSearch',
  WEB_FETCH: 'types.webFetch',
  // V148+ unified markup billing. Replaces IMAGE_GENERATION / IMAGE_GENERATION_BYOK
  // for new tool calls. Released states surface to users so they understand
  // when a reservation was returned (failed call, partial result, sweeper auto-release).
  PLATFORM_MARKUP: 'types.platformMarkup',
  PLATFORM_MARKUP_RELEASED: 'types.platformMarkupReleased',
  PLATFORM_MARKUP_RELEASED_TIMEOUT: 'types.platformMarkupReleasedTimeout',
  // Legacy display labels - no longer written, kept for historical row rendering.
  IMAGE_GENERATION: 'types.imageGeneration',
  IMAGE_GENERATION_BYOK: 'types.imageGenerationByok',
  PURCHASE: 'types.purchase',
  PLAN_GRANT: 'types.planGrant',
  PLAN_RESET: 'types.planReset',
};

/**
 * Source types offered in the filter dropdown. Distinct from
 * {@link SOURCE_LABEL_KEYS} so we can render labels for legacy history rows
 * (IMAGE_GENERATION*) without offering them as filter options. Removing them
 * from the dropdown matches v9 spec: new billing only writes PLATFORM_MARKUP*.
 */
const FILTER_SOURCE_TYPES = [
  'AGENT_EXECUTION',
  'WORKFLOW_NODE',
  'CHAT_CONVERSATION',
  'CLASSIFY_EXECUTION',
  'GUARDRAIL_EXECUTION',
  'BROWSER_AGENT_EXECUTION',
  'COMPACTION_SUMMARY',
  'PLATFORM_MARKUP',
  'PURCHASE',
  'PLAN_GRANT',
  'PLAN_RESET',
];

/**
 * Quota & Usage page.
 */
export default function QuotaPage() {
  if (isCeMode) {
    return <CeQuotaPage />;
  }
  return <QuotaPageInner />;
}

function CeQuotaPage() {
  const t = useTranslations('quota');
  const { isLoading: authLoading, isAuthenticated } = useAuth();
  // Subscribe to the active workspace so the cost balance + usage history refetch on a
  // workspace switch (apiClient auto-attaches X-Active-Organization-ID). Without this the
  // CE branch stayed pinned to the org loaded at mount - the asymmetry with QuotaPageInner
  // and the storage page, which both key their fetch on currentOrgId.
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);
  // Page-local workspace filter. Defaults to the globally-active workspace and
  // resets to it whenever the user switches workspace in the sidebar, but lets
  // the user re-scope JUST this page to any of their workspaces without
  // switching the whole app (see WorkspaceScopeSelect).
  const [scopeOrgId, setScopeOrgId] = useState<string | null>(currentOrgId);
  const [summary, setSummary] = useState<CreditSummary | null>(null);
  // When the install is CLOUD-linked, the relay meters spend against the cloud account, so
  // the headline reflects the cloud account's usage (mirrored, in $) rather than the CE's
  // own near-empty local ledger. Null = not cloud-linked or cloud unavailable → use local.
  const [cloudSummary, setCloudSummary] = useState<CreditSummary | null>(null);
  const [usingCloud, setUsingCloud] = useState(false);
  const [history, setHistory] = useState<CreditHistoryPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [currentPage, setCurrentPage] = useState(0);
  const [filterType, setFilterType] = useState<string>('');
  const [refreshing, setRefreshing] = useState(false);

  const fetchData = useCallback(async () => {
    try {
      // V366: "All workspaces" aggregates across every workspace; a real id slices
      // to that workspace. Routing/balance are unaffected (single owner-pays wallet).
      const allWorkspaces = scopeOrgId === ALL_WORKSPACES_SCOPE;
      const effectiveOrgId = allWorkspaces ? null : scopeOrgId;
      const [summaryData, historyData, status] = await Promise.all([
        quotaApi.getSummary(effectiveOrgId, allWorkspaces).catch(() => null),
        quotaApi.getHistory(currentPage, 15, filterType || undefined, effectiveOrgId, allWorkspaces).catch(() => null),
        cloudLinkService.getStatus().catch(() => null),
      ]);
      if (summaryData) setSummary(summaryData);
      if (historyData) setHistory(historyData);
      const cloudLinked = !!(status?.registered && status?.llmSource === 'CLOUD');
      if (cloudLinked) {
        // The cloud view is ALWAYS scoped to this install's relay usage (CE_LLM_RELAY),
        // enforced server-side - so no client source-type filter is sent here.
        const [cloud, cloudHistory] = await Promise.all([
          cloudLinkService.getCloudUsageSummary().catch(() => null),
          cloudLinkService.getCloudUsageHistory(currentPage, 15).catch(() => null),
        ]);
        setCloudSummary(cloud);
        setUsingCloud(!!cloud);
        // Mirror the cloud account's ledger in the table too (where the relay rows live);
        // keep the local history only if the cloud one is unavailable.
        if (cloudHistory) setHistory(cloudHistory);
      } else {
        setCloudSummary(null);
        setUsingCloud(false);
      }
    } catch { /* ignore */ } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [currentPage, filterType, scopeOrgId]);

  // Follow the sidebar workspace switcher: a global switch resets the page-local
  // filter back to the newly-active workspace.
  useEffect(() => {
    setScopeOrgId(currentOrgId);
  }, [currentOrgId]);

  // Reset + refetch when the scoped workspace changes (global switch or filter).
  useEffect(() => {
    setSummary(null);
    setHistory(null);
    setLoading(true);
    setCurrentPage(0);
  }, [scopeOrgId]);

  useEffect(() => {
    if (!authLoading && isAuthenticated) fetchData();
    else if (!authLoading) setLoading(false);
  }, [authLoading, isAuthenticated, fetchData]);

  const handleRefresh = () => { setRefreshing(true); fetchData(); };
  const handleFilterChange = (value: string) => { setFilterType(value === 'ALL' ? '' : value); setCurrentPage(0); };

  // CLOUD-linked installs show the cloud account's spend (where the relay meters), else local.
  const headlineSummary = usingCloud && cloudSummary ? cloudSummary : summary;

  // Ledger amounts are stored in CREDITS (local CE ledger and the cloud-linked relay
  // mirror alike). CE displays spend in dollars, so convert at the canonical list scale
  // (1 credit = $0.001) before formatting - without this the raw credit count was rendered
  // with a bare "$" (e.g. 1080 credits shown as "$1080.00" instead of "$1.08").
  const formatCredits = (credits: number) => {
    const dollars = creditsToUsd(credits);
    const abs = Math.abs(dollars);
    // Sub-dollar costs (most single LLM calls) need extra precision; ≥ $1 stays at cents.
    const fractionDigits = abs > 0 && abs < 1 ? 4 : 2;
    // Format the magnitude, then put the sign BEFORE the "$" - "-$0.0086", never "$-0.0086".
    const formatted = abs.toLocaleString(getClientLocale(), { minimumFractionDigits: 2, maximumFractionDigits: fractionDigits });
    return `${dollars < 0 ? '-' : ''}$${formatted}`;
  };

  const formatDate = (dateStr: string) => {
    try {
      return formatUtcDateTime(dateStr);
    } catch { return dateStr; }
  };

  const formatTokens = (count: number | null) => {
    if (count === null || count === undefined) return '-';
    return count.toLocaleString(getClientLocale());
  };

  // IMAGE_GENERATION rows reuse promptTokens to store actualImageCount and leave
  // completionTokens null - rendering "1 / -" in a column labeled "Tokens" is
  // misleading. Image-gen has no token concept; render "-".
  const isImageGenSourceType = (sourceType: string) =>
    sourceType === 'IMAGE_GENERATION' || sourceType === 'IMAGE_GENERATION_BYOK';

  if (authLoading || loading) {
    return (
      <div className="space-y-8">
        <div className="bg-theme-secondary rounded-xl p-6 animate-pulse">
          <div className="h-6 bg-theme-tertiary rounded w-1/3 mb-4" />
          <div className="h-3 bg-theme-tertiary rounded-full" />
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      {/* Page-local workspace filter (hidden unless the user has 2+ workspaces) */}
      <WorkspaceScopeSelect value={scopeOrgId} onChange={setScopeOrgId} includeAllOption className="justify-end" />

      {/* Credit Balance Card */}
      <div className="bg-theme-secondary rounded-xl p-6">
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-theme-tertiary rounded-full flex items-center justify-center">
              <Coins className="w-5 h-5 text-theme-primary" />
            </div>
            <div>
              <h2 className="text-lg font-semibold text-theme-primary">{t('balance.costTitle')}</h2>
              <p className="text-sm text-theme-secondary">{t('balance.costRemaining')}</p>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <span className="text-3xl font-semibold text-theme-primary">&infin;</span>
            <Button variant="ghost" size="icon" onClick={handleRefresh} disabled={refreshing} className="h-8 w-8" title={t('actions.refresh')}>
              <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`} />
            </Button>
          </div>
        </div>
        <div className="flex items-center justify-between text-sm text-theme-secondary">
          <div className="flex items-center gap-1.5">
            <ArrowDownCircle className="h-3.5 w-3.5" />
            <div className="flex flex-col">
              <span>{t('balance.consumed30d')}</span>
              {usingCloud && (
                <span className="text-xs text-theme-tertiary">{t('balance.cloudAccountUsage')}</span>
              )}
            </div>
          </div>
          <span className="font-medium text-theme-primary">
            {headlineSummary ? formatCredits(headlineSummary.totalConsumedLast30Days) : '-'}
          </span>
        </div>
      </div>

      {/* Usage Analytics */}
      <UsageAnalyticsPanel
        orgId={scopeOrgId === ALL_WORKSPACES_SCOPE ? null : scopeOrgId}
        allWorkspaces={scopeOrgId === ALL_WORKSPACES_SCOPE}
      />

      {/* Usage History */}
      <div>
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
              <Coins className="w-5 h-5 text-theme-primary" />
            </div>
            <h2 className="text-lg font-semibold text-theme-primary">{t('history.title')}</h2>
          </div>
          {/* The cloud-mirrored view is a single source type (CE_LLM_RELAY), so the
              per-type filter only makes sense against the local BYOK ledger. */}
          {!usingCloud && (
            <Select value={filterType || 'ALL'} onValueChange={handleFilterChange}>
              <SelectTrigger className="w-full sm:w-[180px] h-9 min-h-0 py-0 text-sm">
                <SelectValue placeholder={t('history.filterAll')} />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">{t('history.filterAll')}</SelectItem>
                {FILTER_SOURCE_TYPES.map((type) => (
                  <SelectItem key={type} value={type}>
                    {SOURCE_LABEL_KEYS[type] ? t(SOURCE_LABEL_KEYS[type]) : type}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        </div>

        {/* History Table */}
        <div className="overflow-x-auto rounded-xl overflow-hidden border border-slate-200 dark:border-slate-700/50">
          <table className="min-w-full" style={{ borderSpacing: '0' }}>
            <thead className="bg-theme-secondary border-b border-slate-200 dark:border-slate-700/50">
              <tr>
                <th className="px-4 py-2.5 font-medium text-left text-theme-secondary text-sm">{t('history.date')}</th>
                <th className="px-4 py-2.5 font-medium text-left text-theme-secondary text-sm">{t('history.type')}</th>
                <th className="px-4 py-2.5 font-medium text-left text-theme-secondary text-sm">{t('history.providerModel')}</th>
                <th className="px-4 py-2.5 font-medium text-left text-theme-secondary text-sm">{t('history.tokens')}</th>
                <th className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm">{t('history.cost')}</th>
                <th className="px-4 py-2.5 font-medium text-left text-theme-secondary text-sm">{t('history.description')}</th>
              </tr>
            </thead>
            <tbody>
              {history?.content?.length > 0 ? (
                history.content.map((entry) => (
                  <tr key={entry.id} className="border-b border-slate-200 dark:border-slate-700/50 last:border-b-0 hover:bg-theme-secondary/50 transition-colors duration-150">
                    <td className="px-4 py-2 text-sm text-theme-primary whitespace-nowrap">{formatDate(entry.createdAt)}</td>
                    <td className="px-4 py-2 text-sm text-theme-primary">
                      {SOURCE_LABEL_KEYS[entry.sourceType] ? t(SOURCE_LABEL_KEYS[entry.sourceType]) : entry.sourceType}
                    </td>
                    <td className="px-4 py-2 text-sm text-theme-primary">
                      {entry.provider && entry.model ? `${entry.provider} / ${entry.model}` : entry.provider || entry.model || '-'}
                    </td>
                    <td className="px-4 py-2 text-sm text-theme-primary whitespace-nowrap">
                      {isImageGenSourceType(entry.sourceType) ? (
                        '-'
                      ) : entry.promptTokens !== null || entry.completionTokens !== null ? (
                        <>{formatTokens(entry.promptTokens)}<span className="text-theme-tertiary mx-1">/</span>{formatTokens(entry.completionTokens)}{entry.cachedTokens ? <span className="text-theme-tertiary block">({t('history.cachedTokens', { count: formatTokens(entry.cachedTokens) })})</span> : null}</>
                      ) : '-'}
                    </td>
                    <td className={`px-4 py-2 text-sm text-right font-medium whitespace-nowrap ${entry.amount > 0 ? 'text-emerald-500 dark:text-emerald-400' : 'text-theme-primary'}`}>
                      {entry.amount < 0 ? '' : '+'}{formatCredits(entry.amount)}
                    </td>
                    <td className="px-4 py-2 text-sm text-theme-primary max-w-[200px] truncate" title={entry.description ?? undefined}>
                      {entry.description || '-'}
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={6} className="px-4 py-12 text-center text-sm text-theme-secondary">{t('history.noHistory')}</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {history && history.totalPages > 1 && (
          <div className="flex items-center justify-between mt-3">
            <p className="text-sm text-theme-secondary">{t('history.page', { current: history.number + 1, total: history.totalPages })}</p>
            <div className="flex items-center gap-2">
              <Button variant="ghost" size="sm" className="h-8 w-8 p-0" onClick={() => setCurrentPage((p) => Math.max(0, p - 1))} disabled={history.number === 0}>
                <ChevronLeft className="h-4 w-4" />
              </Button>
              <Button variant="ghost" size="sm" className="h-8 w-8 p-0" onClick={() => setCurrentPage((p) => p + 1)} disabled={history.number >= history.totalPages - 1}>
                <ChevronRight className="h-4 w-4" />
              </Button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function QuotaPageInner() {
  const t = useTranslations('quota');
  const tSettings = useTranslations('settings');
  const { isLoading: authLoading, isAuthenticated, loginWithRedirect } = useAuth();
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);
  // Page-local workspace filter (see CeQuotaPage for the rationale).
  const [scopeOrgId, setScopeOrgId] = useState<string | null>(currentOrgId);

  const [summary, setSummary] = useState<CreditSummary | null>(null);
  const [history, setHistory] = useState<CreditHistoryPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [currentPage, setCurrentPage] = useState(0);
  const [filterType, setFilterType] = useState<string>('');
  const [refreshing, setRefreshing] = useState(false);
  const [topUpOpen, setTopUpOpen] = useState(false);
  const requestSeqRef = useRef(0);

  // Wallet breakdown + plan info - used to render the bucket-aware balance
  // card and the monthly-cycle counter for paid subscribers.
  const { subscription } = useSubscription();
  const { balance: walletTotal, subBalance: walletSub, paygBalance: walletPayg } = useCreditBalance();
  const { configured: paygConfigured } = usePaygTiers();
  const planCode = (subscription as any)?.subscription?.planCode || null;
  const creditTierIndex = (subscription as any)?.subscription?.creditTierIndex ?? 0;
  const isPaidPlan = !!(planCode && planCode !== 'FREE');
  const monthlyPlan = isPaidPlan && CREDIT_TIERS[creditTierIndex]
    ? { allowance: CREDIT_TIERS[creditTierIndex] }
    : undefined;

  const fetchData = useCallback(async () => {
    const requestSeq = ++requestSeqRef.current;
    try {
      setLoading(true);
      setError(null);
      // V366: "All workspaces" aggregates across every workspace; a real id slices
      // to that workspace. Routing/balance are unaffected (single owner-pays wallet).
      const allWorkspaces = scopeOrgId === ALL_WORKSPACES_SCOPE;
      const effectiveOrgId = allWorkspaces ? null : scopeOrgId;
      const [summaryData, historyData] = await Promise.all([
        quotaApi.getSummary(effectiveOrgId, allWorkspaces),
        quotaApi.getHistory(currentPage, 15, filterType || undefined, effectiveOrgId, allWorkspaces),
      ]);
      if (requestSeq !== requestSeqRef.current) return;
      setSummary(summaryData);
      setHistory(historyData);
    } catch (err) {
      if (requestSeq !== requestSeqRef.current) return;
      console.error('Failed to load quota data:', err);
      setError(t('error.loadFailed'));
    } finally {
      if (requestSeq === requestSeqRef.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, [currentPage, filterType, t, scopeOrgId]);

  // Follow the sidebar workspace switcher: a global switch resets the page-local
  // filter back to the newly-active workspace.
  useEffect(() => {
    setScopeOrgId(currentOrgId);
  }, [currentOrgId]);

  useEffect(() => {
    requestSeqRef.current += 1;
    setSummary(null);
    setHistory(null);
    setLoading(true);
    setCurrentPage(0);
  }, [scopeOrgId]);

  useEffect(() => {
    if (authLoading) return;
    if (isAuthenticated) {
      fetchData();
    } else {
      setLoading(false);
    }
  }, [authLoading, isAuthenticated, fetchData]);

  const handleRefresh = () => {
    setRefreshing(true);
    fetchData();
  };

  const handleFilterChange = (value: string) => {
    setFilterType(value === 'ALL' ? '' : value);
    setCurrentPage(0);
  };

  const formatCredits = (value: number) => {
    const abs = Math.abs(value);
    const fractionDigits = abs > 0 && abs < 0.01 ? 4 : 2;
    // Format the magnitude, then put the sign BEFORE the "$" - "-$0.0086", never "$-0.0086".
    const formatted = abs.toLocaleString(getClientLocale(), { minimumFractionDigits: 2, maximumFractionDigits: fractionDigits });
    const sign = value < 0 ? '-' : '';
    return isCeMode ? `${sign}$${formatted}` : `${sign}${formatted}`;
  };

  const formatDate = (dateStr: string) => {
    try {
      return formatUtcDateTime(dateStr);
    } catch {
      return dateStr;
    }
  };

  const formatTokens = (count: number | null) => {
    if (count === null || count === undefined) return '-';
    return count.toLocaleString(getClientLocale());
  };

  // IMAGE_GENERATION rows reuse promptTokens to store actualImageCount and leave
  // completionTokens null - rendering "1 / -" in a column labeled "Tokens" is
  // misleading. Image-gen has no token concept; render "-".
  const isImageGenSourceType = (sourceType: string) =>
    sourceType === 'IMAGE_GENERATION' || sourceType === 'IMAGE_GENERATION_BYOK';

  // Breakdown card config
  const breakdownCards = [
    { key: 'WORKFLOW_NODE', icon: Workflow, labelKey: 'types.workflowNode' },
    { key: 'AGENT_EXECUTION', icon: Bot, labelKey: 'types.agent' },
    { key: 'CHAT_CONVERSATION', icon: MessageSquare, labelKey: 'types.chat' },
    { key: 'BROWSER_AGENT_EXECUTION', icon: Bot, labelKey: 'types.browserAgent' },
  ];

  // Loading skeleton
  if (authLoading || loading) {
    return (
      <div className="space-y-8">
        <div className="bg-theme-secondary rounded-xl p-6 animate-pulse">
          <div className="h-6 bg-theme-tertiary rounded w-1/3 mb-4" />
          <div className="h-3 bg-theme-tertiary rounded-full" />
        </div>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {[1, 2, 3].map((i) => (
            <div key={i} className="bg-theme-secondary rounded-xl p-4 animate-pulse">
              <div className="h-5 bg-theme-tertiary rounded w-1/2 mb-2" />
              <div className="h-4 bg-theme-tertiary rounded w-1/3" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  // Unauthenticated
  if (!isAuthenticated) {
    return (
      <div className="space-y-8">
        <div className="mx-auto max-w-4xl">
          <div className="min-h-[300px] flex items-center justify-center">
            <div className="text-center">
              <h1 className="text-2xl font-bold text-theme-primary mb-4">
                {tSettings('unauthorized')}
              </h1>
              <p className="text-theme-secondary mb-6">
                {tSettings('mustBeLoggedIn')}
              </p>
              <Button onClick={() => loginWithRedirect()} size="sm" className="h-8 px-3">
                <User className="w-4 h-4 mr-1" />
                {tSettings('signIn')}
              </Button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      {/* Page-local workspace filter (hidden unless the user has 2+ workspaces) */}
      <WorkspaceScopeSelect value={scopeOrgId} onChange={setScopeOrgId} includeAllOption className="justify-end" />

      {/* Wallet card - bucket-aware balance + monthly-cycle counter for paid
          subscribers + Top up CTA. Falls back to the wallet endpoint balance
          (which already matches summary.balance) so we don't render two
          divergent totals. */}
      <BalanceBreakdownCard
        balance={walletTotal ?? summary?.balance ?? null}
        subBalance={walletSub}
        paygBalance={walletPayg}
        onTopUp={() => setTopUpOpen(true)}
        topUpEnabled={paygConfigured}
        monthlyPlan={monthlyPlan}
      />

      <TopUpModal isOpen={topUpOpen} onClose={() => setTopUpOpen(false)} />

      {/* Error state */}
      {error && (
        <div className="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-xl p-4">
          <p className="text-sm text-red-600 dark:text-red-400">{error}</p>
        </div>
      )}

      {/* Usage Breakdown - 30-day consumed value lives in this header so the
          number is anchored to the section that actually breaks it down by
          source. The refresh button covers both summary + history. */}
      <div>
        <div className="flex items-center justify-between mb-4 gap-3">
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center shrink-0">
              <Filter className="w-5 h-5 text-theme-primary" />
            </div>
            <div className="min-w-0">
              <h2 className="text-lg font-semibold text-theme-primary">{t('breakdown.title')}</h2>
              <p className="text-sm text-theme-secondary">{t('breakdown.subtitle')}</p>
            </div>
          </div>
          <div className="flex items-center gap-3 shrink-0">
            <div className="text-right">
              <div className="flex items-center gap-1.5 text-xs text-theme-secondary justify-end">
                <ArrowDownCircle className="h-3 w-3" />
                <span>{t('balance.consumed30d')}</span>
              </div>
              <div className="text-sm font-semibold text-theme-primary">
                {summary ? formatCredits(summary.totalConsumedLast30Days) : '-'}
              </div>
            </div>
            <Button
              variant="ghost"
              size="icon"
              onClick={handleRefresh}
              disabled={refreshing}
              className="h-8 w-8"
              title={t('actions.refresh')}
            >
              <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`} />
            </Button>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {breakdownCards.map((card) => {
            const Icon = card.icon;
            const data = summary?.breakdownByType?.[card.key];
            return (
              <div key={card.key} className="bg-theme-secondary rounded-xl p-4">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 bg-theme-tertiary rounded-full flex items-center justify-center">
                    <Icon className="w-5 h-5 text-theme-primary" />
                  </div>
                  <div>
                    <p className="text-xl font-semibold text-theme-primary">
                      {data ? formatCredits(data.credits) : '0'}
                    </p>
                    <p className="text-sm text-theme-secondary">{t(card.labelKey)}</p>
                    <p className="text-xs text-theme-tertiary">
                      {t('breakdown.executions', { count: data?.count ?? 0 })}
                    </p>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Usage History */}
      <div>
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
              <Coins className="w-5 h-5 text-theme-primary" />
            </div>
            <h2 className="text-lg font-semibold text-theme-primary">{t('history.title')}</h2>
          </div>

          <Select value={filterType || 'ALL'} onValueChange={handleFilterChange}>
            <SelectTrigger className="w-full sm:w-[180px] h-9 min-h-0 py-0 text-sm">
              <SelectValue placeholder={t('history.filterAll')} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">{t('history.filterAll')}</SelectItem>
              {FILTER_SOURCE_TYPES.map((type) => (
                <SelectItem key={type} value={type}>
                  {SOURCE_LABEL_KEYS[type] ? t(SOURCE_LABEL_KEYS[type]) : type}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        {/* History Table - markdown-style */}
        <div className="overflow-x-auto rounded-xl overflow-hidden border border-slate-200 dark:border-slate-700/50">
          <table className="min-w-full" style={{ borderSpacing: '0' }}>
            <thead className="bg-theme-secondary border-b border-slate-200 dark:border-slate-700/50">
              <tr>
                <th className="px-4 py-2.5 font-medium text-left text-theme-secondary text-sm">{t('history.date')}</th>
                <th className="px-4 py-2.5 font-medium text-left text-theme-secondary text-sm">{t('history.type')}</th>
                <th className="px-4 py-2.5 font-medium text-left text-theme-secondary text-sm">{t('history.providerModel')}</th>
                <th className="px-4 py-2.5 font-medium text-left text-theme-secondary text-sm">{t('history.tokens')}</th>
                <th className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm">{isCeMode ? t('history.cost') : t('history.credits')}</th>
                <th className="px-4 py-2.5 font-medium text-left text-theme-secondary text-sm">{t('history.description')}</th>
              </tr>
            </thead>
            <tbody>
              {history?.content?.length > 0 ? (
                history.content.map((entry) => (
                  <tr key={entry.id} className="border-b border-slate-200 dark:border-slate-700/50 last:border-b-0 hover:bg-theme-secondary/50 transition-colors duration-150">
                    <td className="px-4 py-2 text-sm text-theme-primary whitespace-nowrap">
                      {formatDate(entry.createdAt)}
                    </td>
                    <td className="px-4 py-2 text-sm text-theme-primary">
                      {SOURCE_LABEL_KEYS[entry.sourceType] ? t(SOURCE_LABEL_KEYS[entry.sourceType]) : entry.sourceType}
                    </td>
                    <td className="px-4 py-2 text-sm text-theme-primary">
                      {entry.provider && entry.model
                        ? `${entry.provider} / ${entry.model}`
                        : entry.provider || entry.model || '-'}
                    </td>
                    <td className="px-4 py-2 text-sm text-theme-primary whitespace-nowrap">
                      {isImageGenSourceType(entry.sourceType) ? (
                        '-'
                      ) : entry.promptTokens !== null || entry.completionTokens !== null ? (
                        <>
                          {formatTokens(entry.promptTokens)}
                          <span className="text-theme-tertiary mx-1">/</span>
                          {formatTokens(entry.completionTokens)}
                          {entry.cachedTokens ? (
                            <span className="text-theme-tertiary block">
                              ({t('history.cachedTokens', { count: formatTokens(entry.cachedTokens) })})
                            </span>
                          ) : null}
                        </>
                      ) : (
                        '-'
                      )}
                    </td>
                    <td className={`px-4 py-2 text-sm text-right font-medium whitespace-nowrap ${entry.amount > 0 ? 'text-emerald-500 dark:text-emerald-400' : 'text-theme-primary'}`}>
                      {entry.amount < 0 ? '' : '+'}{formatCredits(entry.amount)}
                    </td>
                    <td className="px-4 py-2 text-sm text-theme-primary max-w-[200px] truncate" title={entry.description ?? undefined}>
                      {entry.description || '-'}
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={6} className="px-4 py-12 text-center text-sm text-theme-secondary">
                    {t('history.noHistory')}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {history && history.totalPages > 1 && (
          <div className="flex items-center justify-between mt-3">
            <p className="text-sm text-theme-secondary">
              {t('history.page', { current: history.number + 1, total: history.totalPages })}
            </p>
            <div className="flex items-center gap-2">
              <Button
                variant="ghost"
                size="sm"
                className="h-8 w-8 p-0"
                onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                disabled={history.number === 0}
              >
                <ChevronLeft className="h-4 w-4" />
              </Button>
              <Button
                variant="ghost"
                size="sm"
                className="h-8 w-8 p-0"
                onClick={() => setCurrentPage((p) => p + 1)}
                disabled={history.number >= history.totalPages - 1}
              >
                <ChevronRight className="h-4 w-4" />
              </Button>
            </div>
          </div>
        )}
      </div>

      {/* Usage Analytics */}
      <UsageAnalyticsPanel
        orgId={scopeOrgId === ALL_WORKSPACES_SCOPE ? null : scopeOrgId}
        allWorkspaces={scopeOrgId === ALL_WORKSPACES_SCOPE}
      />
    </div>
  );
}
