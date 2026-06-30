'use client';

/**
 * V250 - One-time PAYG top-up modal.
 *
 * Style mirrors {@link BillingCycleChangeModal}: same backdrop, same card
 * shell, same Header/Content/Footer rhythm, same Button variants. The flow
 * is: pick a tier → click "Continue to checkout" → backend returns the
 * Stripe URL → window redirects.
 *
 * Disabled card state: when {@code tier.configured=false} (i.e. ops haven't
 * wired the Stripe price yet) the corresponding card is rendered grayed-out
 * and unclickable. If NO tier is configured, the whole CTA is replaced with
 * a "PAYG soon" notice so users don't repeatedly poke at dead buttons.
 *
 * The component is "owner-pays aware" via the server: a TEAM member opening
 * this modal from a TEAM workspace will receive a 403 from
 * {@code POST /api/billing/payg-checkout} (the {@code requireActiveOrgOwner}
 * guard). We surface the 403 message verbatim - the user knows to switch
 * back to their personal workspace.
 */

import React, { useState } from 'react';
import { Coins, Check, Zap, ArrowRight } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogTitle } from '@/components/ui/dialog';
import { useTranslations } from 'next-intl';
import { usePaygTiers, usePaygCheckout } from '@/lib/hooks/smart-hooks-complete';
import type { PaygTier } from '@/lib/api/services/billing-api.service';
import { formatCreditsCompact } from '@/lib/format-cost';

interface TopUpModalProps {
  isOpen: boolean;
  onClose: () => void;
  /**
   * Optional pre-selection. Useful when the modal is opened from a context
   * where the user has already clicked a specific tier (e.g. one of the
   * tier cards on /settings/pricing). The user can still change selection
   * inside the modal; this just shortens the path by one click.
   */
  initialTier?: 'small' | 'medium' | 'large';
}

function formatAmount(amountCents: number, currency: string): string {
  const dollars = amountCents / 100;
  const symbol = currency.toUpperCase() === 'EUR' ? '€' : '$';
  return `${symbol}${dollars % 1 === 0 ? dollars.toFixed(0) : dollars.toFixed(2)}`;
}

export default function TopUpModal({ isOpen, onClose, initialTier }: TopUpModalProps) {
  const t = useTranslations('billing.payg.modal');
  const tBilling = useTranslations('pricing.billing');
  const { tiers, configured, isLoading: isLoadingTiers } = usePaygTiers();
  const { mutateAsync: checkout, isPending: isCheckingOut } = usePaygCheckout();

  const [selectedTier, setSelectedTier] = useState<'small' | 'medium' | 'large' | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Reset on (re)open - honor optional pre-selection from the caller so a
  // user who clicked a specific tier card lands on that tier already chosen.
  React.useEffect(() => {
    if (isOpen) {
      setSelectedTier(initialTier ?? null);
      setError(null);
    }
  }, [isOpen, initialTier]);

  // NOTE: previously this guard short-circuited the modal in CE deployments
  // ("CE has no Stripe"). That assumption no longer holds - CE users access
  // cloud LLM credits via the same PAYG flow, so the modal is reachable from
  // the pricing page's PAYG section. Reachability is now gated upstream: the PAYG cards
  // disable themselves when {@code tier.configured=false}, and the parent
  // section renders an "unconfigured" empty state if no tier is wired.

  const handleSelect = (tier: PaygTier) => {
    if (!tier.configured || isCheckingOut) return;
    setSelectedTier(tier.tier);
    setError(null);
  };

  const handleConfirm = async () => {
    if (!selectedTier) return;
    try {
      setError(null);
      const result = await checkout(selectedTier);
      if (result?.url) {
        window.location.href = result.url;
      } else {
        setError(t('errors.noUrl'));
      }
    } catch (err: any) {
      // Backend surfaces both 403 (org-owner guard) and 503
      // (PAYG_PRICE_UNCONFIGURED) with a structured error string we can
      // forward verbatim - the user gets a discoverable message instead
      // of a generic "something went wrong".
      setError(err?.message || t('errors.generic'));
    }
  };

  return (
    <Dialog open={isOpen} onOpenChange={(o) => !o && !isCheckingOut && onClose()}>
      <DialogContent className="max-w-2xl p-0 gap-0 overflow-hidden">
        {/* Header */}
        <div className="p-6 pb-4">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-full bg-theme-tertiary">
              <Coins className="h-5 w-5 text-theme-secondary" />
            </div>
            <div>
              <DialogTitle className="text-lg font-semibold text-theme-primary">
                {t('title')}
              </DialogTitle>
              <p className="text-sm text-theme-secondary mt-0.5">
                {t('subtitle')}
              </p>
            </div>
          </div>
        </div>

        {/* Content */}
        <div className="p-6">
          {isLoadingTiers ? (
            <div className="flex justify-center py-12">
              <LoadingSpinner size="md" />
            </div>
          ) : !configured ? (
            <div className="rounded-lg p-6 bg-theme-secondary border border-theme text-center">
              <Zap className="h-6 w-6 text-theme-muted mx-auto mb-2" />
              <p className="text-sm font-medium text-theme-primary mb-1">
                {t('unconfigured.title')}
              </p>
              <p className="text-sm text-theme-secondary">
                {t('unconfigured.body')}
              </p>
            </div>
          ) : (
            <>
              <div className="grid grid-cols-1 md:grid-cols-3 gap-3 mb-6">
                {tiers.map((tier) => (
                  <TierCard
                    key={tier.tier}
                    tier={tier}
                    selected={selectedTier === tier.tier}
                    onSelect={() => handleSelect(tier)}
                    disabled={isCheckingOut}
                  />
                ))}
              </div>

              <div className="rounded-lg p-4 bg-theme-secondary border border-theme text-sm text-theme-secondary">
                {t('paygNote')}
              </div>

              <div className="mt-3 rounded-lg p-4 bg-theme-secondary border border-theme text-sm text-theme-secondary">
                {tBilling('taxNote')}
              </div>
            </>
          )}

          {error && (
            <div className="mt-4 p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-700 rounded-lg">
              <p className="text-sm text-red-700 dark:text-red-300">{error}</p>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="p-6 pt-0 flex gap-3">
          <Button
            onClick={onClose}
            disabled={isCheckingOut}
            variant="outline"
            className="flex-1"
          >
            {t('cancel')}
          </Button>
          <Button
            onClick={handleConfirm}
            disabled={!selectedTier || isCheckingOut || !configured}
            variant="contrast"
            className="flex-1"
          >
            {isCheckingOut ? (
              <>
                <LoadingSpinner size="xs" />
                {t('redirecting')}
              </>
            ) : (
              <>
                {t('confirm')}
                <ArrowRight className="h-4 w-4 ml-1" />
              </>
            )}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

interface TierCardProps {
  tier: PaygTier;
  selected: boolean;
  onSelect: () => void;
  disabled: boolean;
}

function TierCard({ tier, selected, onSelect, disabled }: TierCardProps) {
  const t = useTranslations('billing.payg.modal.tiers');
  const isDisabled = disabled || !tier.configured;

  return (
    <button
      type="button"
      onClick={onSelect}
      disabled={isDisabled}
      className={[
        'relative rounded-xl border p-4 text-left transition-all',
        selected
          ? 'border-theme bg-theme-secondary ring-2 ring-[var(--text-primary)] ring-offset-1 ring-offset-[var(--bg-primary)]'
          : 'border-theme bg-theme-primary hover:border-[var(--text-muted)]',
        isDisabled ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer',
      ].join(' ')}
    >
      {selected && (
        <div className="absolute top-3 right-3 w-5 h-5 rounded-full bg-[var(--text-primary)] flex items-center justify-center">
          <Check className="h-3 w-3 text-[var(--bg-primary)]" />
        </div>
      )}

      <div className="text-sm font-medium text-theme-secondary mb-1">
        {t(tier.tier)}
      </div>

      <div className="text-2xl font-semibold text-theme-primary mb-1">
        {formatAmount(tier.amountCents, tier.currency)}
      </div>

      <div className="flex items-center gap-1 text-sm text-theme-secondary">
        <Coins className="h-3.5 w-3.5" />
        {formatCreditsCompact(tier.credits)} {t('creditsSuffix')}
      </div>

      {!tier.configured && (
        <div className="text-xs text-theme-muted mt-2">
          {t('unavailable')}
        </div>
      )}
    </button>
  );
}
