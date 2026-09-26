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
import { useModelNameIndex } from './components/modelLabels';
import { UsageHistoryPanel } from './components/UsageHistoryPanel';
import { isCeMode, creditsToUsd } from '@/lib/format-cost';
import { BalanceBreakdownCard, TopUpModal } from '@/components/billing';
import { usePaygTiers } from '@/lib/hooks/smart-hooks-complete';
import { useCreditWallet } from '@/lib/hooks/useCreditWallet';
import { useScheduledPlanChange } from '@/lib/hooks/useScheduledPlanChange';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { cloudLinkService } from '@/lib/api/cloud-link.service';
import { WorkspaceScopeSelect, ALL_WORKSPACES_SCOPE } from '@/components/settings/WorkspaceScopeSelect';
import { useQuery } from '@tanstack/react-query';
import { organizationApi } from '@/lib/api/organization-api';
import {
  CREDIT_SOURCE_FILTERS,
  CREDIT_SOURCE_FILTERS_LOCAL_LEDGER,
  CREDIT_SOURCE_LABEL_KEYS,
} from '@/lib/billing/creditSourceTypes';

/** Rows per usage-history page; the table pads a short last page to it. */
const HISTORY_PAGE_SIZE = 15;

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
  // Names the models the history charges for; see ./components/modelLabels.
  const modelNames = useModelNameIndex();
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
        quotaApi.getHistory(currentPage, HISTORY_PAGE_SIZE, filterType || undefined, effectiveOrgId, allWorkspaces).catch(() => null),
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
          cloudLinkService.getCloudUsageHistory(currentPage, HISTORY_PAGE_SIZE).catch(() => null),
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
            <div className="w-10 h-10 bg-theme-tertiary rounded-xl flex items-center justify-center">
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
      <UsageHistoryPanel
        history={history}
        pageSize={HISTORY_PAGE_SIZE}
        amountHeader={t('history.cost')}
        formatAmount={formatCredits}
        modelNames={modelNames}
        toolbar={
          // The cloud-mirrored view is a single source type (CE_LLM_RELAY), so the
          // per-type filter only makes sense against the local BYOK ledger.
          !usingCloud && (
            <Select value={filterType || 'ALL'} onValueChange={handleFilterChange}>
              <SelectTrigger
                data-testid="usage-history-filter"
                className="w-full sm:w-[180px] h-9 min-h-0 py-0 text-sm"
              >
                <SelectValue placeholder={t('history.filterAll')} />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">{t('history.filterAll')}</SelectItem>
                {/* This branch reads the install's OWN ledger, which never carries a relayed row. */}
                {CREDIT_SOURCE_FILTERS_LOCAL_LEDGER.map((type) => (
                  <SelectItem key={type} value={type}>
                    {CREDIT_SOURCE_LABEL_KEYS[type] ? t(CREDIT_SOURCE_LABEL_KEYS[type]) : type}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )
        }
        footer={
          history && history.totalPages > 1 && (
            <div className="flex items-center justify-between mt-3">
              <p className="text-sm text-theme-secondary">{t('history.page', { current: history.number + 1, total: history.totalPages })}</p>
              <div className="flex items-center gap-2">
                <Button variant="ghost" size="sm" className="h-8 w-8 p-0" onClick={() => setCurrentPage((p) => Math.max(0, p - 1))} disabled={history.number === 0} aria-label={t('history.previousPage')}>
                  <ChevronLeft className="h-4 w-4" />
                </Button>
                <Button variant="ghost" size="sm" className="h-8 w-8 p-0" onClick={() => setCurrentPage((p) => p + 1)} disabled={history.number >= history.totalPages - 1} aria-label={t('history.nextPage')}>
                  <ChevronRight className="h-4 w-4" />
                </Button>
              </div>
            </div>
          )
        }
      />
    </div>
  );
}

function QuotaPageInner() {
  // Names the models the history charges for; see ./components/modelLabels.
  const modelNames = useModelNameIndex();
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
  // A page or filter change refetches while the current rows stay on screen (dimmed). Only the
  // FIRST load and a workspace re-scope show the skeleton: swapping the whole page for it on
  // every click threw the reader back to the top, away from the table they were paging.
  const [historyLoading, setHistoryLoading] = useState(false);
  // Bumped by every pager click. The pager asks for the page NEXT TO THE ONE SHOWN, which after a
  // failed request can equal the page already requested; the bump makes that retry refetch.
  const [historyReloadKey, setHistoryReloadKey] = useState(0);
  const [topUpOpen, setTopUpOpen] = useState(false);
  const requestSeqRef = useRef(0);

  // Wallet breakdown + plan info - used to render the bucket-aware balance
  // card and the monthly-cycle counter for paid subscribers.
  // Same hook as the header dial and the sidebar block. This page used to derive
  // the allowance itself, which gave a FREE account no monthly grant here while
  // the dial that links to this page showed it 1,000 - and skipped the owner-pays
  // guard, so a guest saw the OWNER's balance measured against their OWN tier.
  const {
    balance: walletTotal,
    subBalance: walletSub,
    paygBalance: walletPayg,
    allowance,
    renewsAt,
    periodEndsAt,
  } = useCreditWallet();
  const { configured: paygConfigured } = usePaygTiers();
  // The wallet card promises the CURRENT tier's grant, so it must stand down when a different
  // tier is already scheduled to take effect. Same hook, same query key and same fail-open
  // posture as the Billing page, which suppresses its own rows under this condition.
  const { hasScheduledChange } = useScheduledPlanChange(isAuthenticated);
  // The three travel together because the card states them in one sentence, and
  // useCreditWallet resolves all three behind the same payer guard: an allowance we are
  // sure of can never be paired with a date belonging to somebody else's subscription.
  const monthlyPlan =
    allowance !== null ? { allowance, renewsAt, periodEndsAt, hasScheduledChange } : undefined;

  // Same membership query (and cache key) as the workspace selector above.
  const { data: workspaces } = useQuery({
    queryKey: ['organizations', 'memberships'],
    queryFn: () => organizationApi.getOrganizations(),
    enabled: isAuthenticated,
    staleTime: 5 * 60 * 1000,
  });
  // "How long the balance lasts" divides the ACCOUNT's balance by the spend the panel shows, so
  // it is only honest when that spend is the whole account's: every workspace is the viewer's own
  // (a guest's pace is not what drains the owner's wallet) and the view covers all of them. A
  // single workspace's pace against the shared balance would promise days that are not there.
  const enterableWorkspaces = (workspaces ?? []).filter((w) => !w.paused && !w.pendingDeletion);
  const viewCoversAccount =
    enterableWorkspaces.length > 0 &&
    enterableWorkspaces.every((w) => w.currentUserRole === 'OWNER') &&
    (scopeOrgId === ALL_WORKSPACES_SCOPE || enterableWorkspaces.length === 1);

  const fetchData = useCallback(async () => {
    const requestSeq = ++requestSeqRef.current;
    try {
      setHistoryLoading(true);
      setError(null);
      // V366: "All workspaces" aggregates across every workspace; a real id slices
      // to that workspace. Routing/balance are unaffected (single owner-pays wallet).
      const allWorkspaces = scopeOrgId === ALL_WORKSPACES_SCOPE;
      const effectiveOrgId = allWorkspaces ? null : scopeOrgId;
      const [summaryData, historyData] = await Promise.all([
        quotaApi.getSummary(effectiveOrgId, allWorkspaces),
        quotaApi.getHistory(currentPage, HISTORY_PAGE_SIZE, filterType || undefined, effectiveOrgId, allWorkspaces),
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
        setHistoryLoading(false);
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
  }, [authLoading, isAuthenticated, fetchData, historyReloadKey]);

  const handleRefresh = () => {
    setRefreshing(true);
    fetchData();
  };

  const goToPage = (page: number) => {
    setCurrentPage(page);
    setHistoryReloadKey((k) => k + 1);
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

  // The four kinds of spend that get a summary card. A deliberate SUBSET - four cards is the
  // layout - but each is named from the shared map rather than restated here, so the card, the
  // table row and the chart series for one kind of spend cannot end up with three names.
  const breakdownCards = [
    { key: 'WORKFLOW_NODE', icon: Workflow, labelKey: CREDIT_SOURCE_LABEL_KEYS.WORKFLOW_NODE },
    { key: 'AGENT_EXECUTION', icon: Bot, labelKey: CREDIT_SOURCE_LABEL_KEYS.AGENT_EXECUTION },
    { key: 'CHAT_CONVERSATION', icon: MessageSquare, labelKey: CREDIT_SOURCE_LABEL_KEYS.CHAT_CONVERSATION },
    { key: 'BROWSER_AGENT_EXECUTION', icon: Bot, labelKey: CREDIT_SOURCE_LABEL_KEYS.BROWSER_AGENT_EXECUTION },
  ];

  // Loading skeleton
  if (authLoading || loading) {
    return (
      <div className="space-y-8">
        <div className="bg-theme-secondary rounded-xl p-6 animate-pulse">
          <div className="h-6 bg-theme-tertiary rounded w-1/3 mb-4" />
          <div className="h-3 bg-theme-tertiary rounded-full" />
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          {[1, 2, 3, 4].map((i) => (
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

      {/* Error state. Once the history table is on screen the error is shown beside it
          instead: the reader is paging down there and would never see a banner up here. */}
      {error && !history && (
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
            <div className="w-10 h-10 bg-theme-secondary rounded-xl flex items-center justify-center shrink-0">
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

        {/* The four kinds of spend sit on one row from `lg` (the settings column is ~750px
            there, ~175px a card), two by two from `sm`, stacked on a phone. The label shares
            the icon's line so the amount gets the full card width: it is never truncated, a
            clipped number reads as a wrong one. */}
        <div data-testid="usage-breakdown-grid" className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          {breakdownCards.map((card) => {
            const Icon = card.icon;
            const data = summary?.breakdownByType?.[card.key];
            return (
              <div key={card.key} className="bg-theme-secondary rounded-xl p-4 min-w-0">
                <div className="flex items-center gap-2 min-w-0 mb-2">
                  <div className="w-8 h-8 bg-theme-tertiary rounded-lg flex items-center justify-center shrink-0">
                    <Icon className="w-4 h-4 text-theme-primary" />
                  </div>
                  <p className="text-sm text-theme-secondary truncate" title={t(card.labelKey)}>{t(card.labelKey)}</p>
                </div>
                <p className="text-xl font-semibold text-theme-primary tabular-nums break-words">
                  {data ? formatCredits(data.credits) : '0'}
                </p>
                <p className="text-xs text-theme-tertiary">
                  {t('breakdown.executions', { count: data?.count ?? 0 })}
                </p>
              </div>
            );
          })}
        </div>
      </div>

      {/* Usage History */}
      <UsageHistoryPanel
        history={history}
        busy={historyLoading}
        pageSize={HISTORY_PAGE_SIZE}
        amountHeader={isCeMode ? t('history.cost') : t('history.credits')}
        formatAmount={formatCredits}
        modelNames={modelNames}
        toolbar={
          // Locked while a page is in flight, like the pager: a filter picked then would
          // label rows that belong to the previous one if its request failed.
          <Select value={filterType || 'ALL'} onValueChange={handleFilterChange} disabled={historyLoading}>
            <SelectTrigger
              data-testid="usage-history-filter"
              className="w-full sm:w-[180px] h-9 min-h-0 py-0 text-sm"
            >
              <SelectValue placeholder={t('history.filterAll')} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">{t('history.filterAll')}</SelectItem>
              {CREDIT_SOURCE_FILTERS.map((type) => (
                <SelectItem key={type} value={type}>
                  {CREDIT_SOURCE_LABEL_KEYS[type] ? t(CREDIT_SOURCE_LABEL_KEYS[type]) : type}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        }
        footer={
          <>
            {error && history && (
              <p className="mt-3 text-sm text-red-600 dark:text-red-400" role="alert">{error}</p>
            )}

            {/* Pagination - relative to the page SHOWN, never to the one requested, so a failed
                request cannot leave the pager a page ahead of its rows; and both buttons wait for
                the page in flight, so a double click cannot skip a page or run past the end. */}
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
                    onClick={() => goToPage(Math.max(0, history.number - 1))}
                    disabled={historyLoading || history.number === 0}
                    aria-label={t('history.previousPage')}
                  >
                    <ChevronLeft className="h-4 w-4" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-8 w-8 p-0"
                    onClick={() => goToPage(history.number + 1)}
                    disabled={historyLoading || history.number >= history.totalPages - 1}
                    aria-label={t('history.nextPage')}
                  >
                    <ChevronRight className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            )}
          </>
        }
      />

      {/* Usage Analytics */}
      <UsageAnalyticsPanel
        orgId={scopeOrgId === ALL_WORKSPACES_SCOPE ? null : scopeOrgId}
        allWorkspaces={scopeOrgId === ALL_WORKSPACES_SCOPE}
        balance={viewCoversAccount ? walletTotal ?? summary?.balance ?? null : null}
      />
    </div>
  );
}
