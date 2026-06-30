'use client';

import React, { useEffect, useState } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import Link from 'next/link';
import {
  ScrollText,
  ExternalLink,
  FileText,
  RefreshCw,
  Calendar,
  ArrowUpRight,
} from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useQuery, useQueryClient } from '@tanstack/react-query';

import PageHeader from '@/components/settings/PageHeader';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useSubscription } from '@/lib/hooks/smart-hooks-complete';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { SettingsPageSkeleton } from '@/components/skeletons/SettingsSkeletons';
import { CancellationModal } from '@/components/billing';
import { OwnerOnlyBillingAction } from '@/components/billing/OwnerOnlyBillingAction';
import { useIsCurrentOrgOwner } from '@/lib/stores/current-org-store';
import { Button } from '@/components/ui/button';
import { TooltipProvider } from '@/components/ui/tooltip';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { isCeMode } from '@/lib/format-cost';
import type {
  BillingInvoice,
  InvoiceListResponse,
  ScheduledChangeResponse,
} from '@/lib/api/services/billing-api.service';

/**
 * Customer-facing billing & subscription page.
 *
 * Composition:
 *  - Card 1 surfaces the current subscription (from /billing/me via
 *    {@link useSubscription}) - plan name, cycle, status badge, plus
 *    state-machine messaging (scheduled change > pending cancel + Reactivate
 *    > next billing date) and a three-way footer (FREE → View pricing;
 *    pending cancel → no footer; active paid → Manage in Stripe + Cancel).
 *  - Card 2 lists up to 12 most-recent invoices from Stripe with a per-row
 *    View / PDF action plus a footer link to the full Stripe Customer Portal
 *    for older history.
 *
 * Action buttons (Manage in Stripe / Cancel / Reactivate / View all in
 * Stripe) are role-gated client-side via {@link OwnerOnlyBillingAction}
 * with {@code hideWhenNotOwner}; the BillingController applies the same
 * {@code requireActiveOrgOwner} guard server-side. A single info banner
 * explains the "switch to your personal workspace" path for non-owners.
 *
 * CE/Cloud: page early-returns {@code null} when {@link isCeMode} so a CE
 * user landing on the route directly (nav entry is already hidden by
 * {@code hiddenInCE: true}) sees nothing - defense in depth alongside the
 * controller's {@code billing.provider=stripe} gate.
 */
export default function BillingPage() {
  if (isCeMode) {
    return null;
  }
  return <BillingPageInner />;
}

function BillingPageInner() {
  const t = useTranslations('settings.billing');
  const { isAuthChecking, isAuthenticated } = useAuthGuard();
  const queryClient = useQueryClient();
  const isOwner = useIsCurrentOrgOwner();

  const {
    subscription: billingData,
    isLoading: subLoading,
    forceLoadSubscription,
  } = useSubscription();

  const invoicesQuery = useQuery<InvoiceListResponse>({
    queryKey: ['billing', 'invoices'],
    queryFn: () => unifiedApiService.getInvoices(),
    enabled: !!(isAuthenticated && !isAuthChecking),
    staleTime: 60_000,
    refetchOnMount: 'always',
    refetchOnWindowFocus: true,
  });

  const scheduledQuery = useQuery<ScheduledChangeResponse>({
    queryKey: ['billing', 'scheduledChange'],
    queryFn: () => unifiedApiService.getScheduledChange(),
    enabled: !!(isAuthenticated && !isAuthChecking),
    staleTime: 60_000,
  });

  // useSubscription has refetchOnMount: false (smart-hooks-complete.ts).
  // Explicit kick here covers the post-Stripe-Portal return path: user
  // cancels in the portal, redirects back to this page, no focus event
  // fires on client-side nav → Card 1 would render stale data.
  useEffect(() => {
    forceLoadSubscription();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const [showCancellationModal, setShowCancellationModal] = useState(false);

  if (isAuthChecking || subLoading) {
    return <SettingsPageSkeleton />;
  }

  // /billing/me payload shape: { userId, subscription: {...} | null,
  // hasActiveSubscription, ... }. Spread defensively because FREE-via-
  // checkout users land here with subscription === null while still being
  // legitimately on the FREE plan.
  const subscription = (billingData as {
    subscription?: {
      planCode?: string;
      planName?: string;
      cadence?: string;
      status?: string;
      cancelAtPeriodEnd?: boolean;
      currentPeriodEnd?: string;
    } | null;
  } | null)?.subscription ?? null;

  const planCode = subscription?.planCode ?? 'FREE';
  const planName = subscription?.planName ?? 'Free';
  const cadence = subscription?.cadence ?? null;
  const status = subscription?.status ?? 'active';
  const cancelAtPeriodEnd = !!subscription?.cancelAtPeriodEnd;
  const currentPeriodEnd = subscription?.currentPeriodEnd ?? undefined;
  const isFree = planCode === 'FREE';

  const hasScheduledChange = scheduledQuery.data?.hasScheduledChange === true;
  const scheduled = scheduledQuery.data?.scheduledChange;
  const invoices = invoicesQuery.data?.invoices ?? [];

  const safeDate = (s?: string | null) => {
    if (!s) return '';
    try {
      return formatUtcDate(s);
    } catch {
      return s;
    }
  };

  const formatMoney = (smallestUnit: number, currency: string) => {
    const code = (currency || 'USD').toUpperCase();
    const locale = getClientLocale();
    try {
      return new Intl.NumberFormat(locale, { style: 'currency', currency: code }).format(
        smallestUnit / 100,
      );
    } catch {
      return `${(smallestUnit / 100).toFixed(2)} ${code}`;
    }
  };

  /** Display the right amount per status: paid → amountPaid; void → '-';
   *  otherwise (open / draft / uncollectible) → amountDue. The `?? 0`
   *  defends against a null amount field on synthetic / draft invoices. */
  const renderAmount = (inv: BillingInvoice): React.ReactNode => {
    if (inv.status === 'void') return <span className="text-theme-tertiary">-</span>;
    const value = (inv.status === 'paid' ? inv.amountPaid : inv.amountDue) ?? 0;
    return formatMoney(value, inv.currency);
  };

  const SUB_STATUS_TO_KEY: Record<string, string> = {
    active: 'active',
    trialing: 'trialing',
    past_due: 'pastDue',
    canceled: 'canceled',
    unpaid: 'unpaid',
    incomplete: 'incomplete',
  };
  const renderSubStatus = (s: string) => {
    const key = SUB_STATUS_TO_KEY[s] ?? null;
    return key ? t(`status.${key}` as 'status.active') : s;
  };

  const INVOICE_STATUS_KEYS = new Set(['paid', 'open', 'uncollectible', 'void', 'draft']);
  const renderInvoiceStatus = (s: string) =>
    INVOICE_STATUS_KEYS.has(s)
      ? t(`invoiceStatus.${s}` as 'invoiceStatus.paid')
      : t('invoiceStatus.unknown');

  const statusBadgeClass = (s: string) => {
    switch (s) {
      case 'paid':
        return 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300';
      case 'open':
        return 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300';
      case 'uncollectible':
        return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300';
      case 'void':
        return 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400';
      case 'draft':
        return 'bg-gray-50 text-gray-500 dark:bg-gray-800/50 dark:text-gray-500';
      default:
        return 'bg-theme-tertiary text-theme-secondary';
    }
  };

  const handleOpenPortal = async () => {
    try {
      const result = await unifiedApiService.openBillingPortal(window.location.href);
      if (result?.url) {
        window.location.assign(result.url);
      }
    } catch (err) {
      // 403 (non-owner) or Stripe error - log; UI keeps user on the page.
      console.error('Failed to open Stripe portal:', err);
    }
  };

  const handleReactivate = async () => {
    try {
      await unifiedApiService.reactivateSubscription();
      forceLoadSubscription();
    } catch (err) {
      console.error('Failed to reactivate subscription:', err);
    }
  };

  return (
    <TooltipProvider delayDuration={150}>
      <div className="space-y-8">
        <PageHeader icon={ScrollText} title={t('title')} subtitle={t('subtitle')} />

        {/* Single non-owner notice. OwnerOnlyBillingAction renders its
            built-in "switch to personal workspace" panel when the active
            workspace is not OWNER. The {!isOwner} guard short-circuits the
            owner branch so we never mount an empty wrapper for owners. */}
        {!isOwner && <OwnerOnlyBillingAction>{null}</OwnerOnlyBillingAction>}

        {/* ── Card 1 - Subscription summary ─────────────────────────── */}
        <section
          aria-labelledby="billing-summary-heading"
          className="rounded-xl p-6 border border-theme"
        >
          <div className="flex items-center justify-between mb-6">
            <div>
              <h2
                id="billing-summary-heading"
                className="text-lg font-semibold text-theme-primary"
              >
                {planName}
              </h2>
              <p className="text-sm text-theme-secondary mt-1">
                <span>{renderSubStatus(status)}</span>
                {cadence ? <span className="text-theme-tertiary"> · {cadence}</span> : null}
              </p>
            </div>
          </div>

          {/* Precedence: scheduled change > pending cancel > next billing */}
          {hasScheduledChange && scheduled && (
            <div className="rounded-lg border border-blue-200 dark:border-blue-900/40 bg-blue-50 dark:bg-blue-900/15 p-3 mb-4">
              <p className="text-sm text-blue-900 dark:text-blue-100">
                {t('summary.scheduledChange')}
                {scheduled.effectiveDate ? ` · ${safeDate(scheduled.effectiveDate)}` : ''}
              </p>
              <Link
                href="/app/settings/pricing"
                className="text-sm underline-offset-2 hover:underline text-blue-800 dark:text-blue-300 inline-flex items-center gap-1 mt-1"
              >
                {t('actions.viewPricing')}
                <ArrowUpRight className="h-3.5 w-3.5" />
              </Link>
            </div>
          )}

          {!hasScheduledChange && cancelAtPeriodEnd && currentPeriodEnd && (
            <div className="rounded-lg border border-amber-200 dark:border-amber-900/40 bg-amber-50 dark:bg-amber-900/15 p-3 mb-4 flex items-center justify-between gap-4 flex-wrap">
              <p className="text-sm text-amber-900 dark:text-amber-100">
                {t('summary.accessEndsOn', { date: safeDate(currentPeriodEnd) })}
              </p>
              <OwnerOnlyBillingAction hideWhenNotOwner>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={handleReactivate}
                  className="h-8 px-3"
                >
                  {t('actions.reactivate')}
                </Button>
              </OwnerOnlyBillingAction>
            </div>
          )}

          {!hasScheduledChange && !cancelAtPeriodEnd && !isFree && currentPeriodEnd && (
            <div className="flex items-center gap-2 text-sm text-theme-secondary mb-4">
              <Calendar className="h-3.5 w-3.5" />
              <span>{t('summary.nextBilling', { date: safeDate(currentPeriodEnd) })}</span>
            </div>
          )}

          {/* Footer actions - 3-way */}
          <div className="flex flex-wrap gap-3 pt-4 border-t border-theme">
            {isFree ? (
              <Link href="/app/settings/pricing">
                <Button size="sm" className="h-8 px-3">
                  <ArrowUpRight className="h-4 w-4 mr-1" />
                  {t('summary.upgradePlan')}
                </Button>
              </Link>
            ) : cancelAtPeriodEnd ? null : (
              <>
                <OwnerOnlyBillingAction hideWhenNotOwner>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={handleOpenPortal}
                    className="h-8 px-3"
                  >
                    <ExternalLink className="h-3.5 w-3.5 mr-1" />
                    {t('actions.manageStripe')}
                  </Button>
                </OwnerOnlyBillingAction>
                <OwnerOnlyBillingAction hideWhenNotOwner>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => setShowCancellationModal(true)}
                    className="h-8 px-3 text-red-600 hover:text-red-700 dark:text-red-400 dark:hover:text-red-300"
                  >
                    {t('actions.cancel')}
                  </Button>
                </OwnerOnlyBillingAction>
              </>
            )}
          </div>
        </section>

        {/* ── Card 2 - Invoices ─────────────────────────────────────── */}
        <section
          aria-labelledby="billing-invoices-heading"
          className="rounded-xl p-6 border border-theme"
        >
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
                <FileText className="w-5 h-5 text-theme-primary" />
              </div>
              <h2
                id="billing-invoices-heading"
                className="text-lg font-semibold text-theme-primary"
              >
                {t('invoices.title')}
              </h2>
            </div>
            <button
              type="button"
              onClick={() =>
                queryClient.invalidateQueries({ queryKey: ['billing', 'invoices'] })
              }
              className="text-theme-secondary hover:text-theme-primary p-1 rounded"
              aria-label={t('invoices.refresh')}
              title={t('invoices.refresh')}
            >
              <RefreshCw
                className={`h-4 w-4 ${invoicesQuery.isFetching ? 'animate-spin' : ''}`}
              />
            </button>
          </div>

          {invoices.length === 0 ? (
            <p className="text-sm text-theme-secondary py-6 text-center">
              {t('invoices.empty')}
            </p>
          ) : (
            <div className="overflow-x-auto rounded-xl overflow-hidden border border-slate-200 dark:border-slate-700/50">
              <table className="min-w-full" style={{ borderSpacing: 0 }}>
                <thead className="bg-theme-secondary border-b border-slate-200 dark:border-slate-700/50">
                  <tr>
                    <th
                      scope="col"
                      className="px-4 py-2.5 font-medium text-left text-theme-secondary text-sm"
                    >
                      {t('invoices.date')}
                    </th>
                    <th
                      scope="col"
                      className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm"
                    >
                      {t('invoices.amount')}
                    </th>
                    <th
                      scope="col"
                      className="px-4 py-2.5 font-medium text-left text-theme-secondary text-sm"
                    >
                      {t('invoices.status')}
                    </th>
                    <th
                      scope="col"
                      className="px-4 py-2.5 font-medium text-right text-theme-secondary text-sm"
                    >
                      <span className="sr-only">{t('invoices.actions')}</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {invoices.map((inv) => (
                    <tr
                      key={inv.id}
                      className="border-b border-slate-200 dark:border-slate-700/50 last:border-b-0 hover:bg-theme-secondary/50 transition-colors"
                    >
                      <td className="px-4 py-2 text-sm text-theme-primary whitespace-nowrap">
                        {safeDate(inv.created)}
                      </td>
                      <td className="px-4 py-2 text-sm text-right text-theme-primary whitespace-nowrap">
                        {renderAmount(inv)}
                      </td>
                      <td className="px-4 py-2 text-sm">
                        <span
                          className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${statusBadgeClass(
                            inv.status,
                          )}`}
                        >
                          {renderInvoiceStatus(inv.status)}
                        </span>
                      </td>
                      <td className="px-4 py-2 text-sm text-right whitespace-nowrap">
                        <div className="flex items-center justify-end gap-3">
                          {inv.hostedInvoiceUrl && (
                            <a
                              href={inv.hostedInvoiceUrl}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="text-theme-primary hover:underline"
                            >
                              {t('invoices.view')}
                            </a>
                          )}
                          {inv.invoicePdf && (
                            <a
                              href={inv.invoicePdf}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="text-theme-secondary hover:underline"
                            >
                              {t('invoices.pdf')}
                            </a>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* Footer link - opens the Stripe Customer Portal for full invoice history. */}
          {!isFree && (
            <div className="mt-4 flex justify-end">
              <OwnerOnlyBillingAction hideWhenNotOwner>
                <button
                  type="button"
                  onClick={handleOpenPortal}
                  className="text-sm text-theme-secondary hover:text-theme-primary inline-flex items-center gap-1"
                >
                  {t('invoices.viewAllInStripe')}
                  <ExternalLink className="h-3.5 w-3.5" />
                </button>
              </OwnerOnlyBillingAction>
            </div>
          )}
        </section>

        {/* Cancellation modal - wired to /billing/cancel-subscription via
            CancellationModal's own service call. onSuccess refreshes both
            /billing/me (Card 1) and the invoices/scheduled-change queries. */}
        <CancellationModal
          isOpen={showCancellationModal}
          onClose={() => setShowCancellationModal(false)}
          onSuccess={() => {
            setShowCancellationModal(false);
            forceLoadSubscription();
            queryClient.invalidateQueries({ queryKey: ['billing', 'invoices'] });
            queryClient.invalidateQueries({ queryKey: ['billing', 'scheduledChange'] });
          }}
          planName={planName}
          currentPeriodEnd={currentPeriodEnd}
        />
      </div>
    </TooltipProvider>
  );
}
