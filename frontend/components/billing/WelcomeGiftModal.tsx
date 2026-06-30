'use client';

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { Gift, Coins, ArrowRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { useTranslations } from 'next-intl';
import { isCeMode } from '@/lib/format-cost';
import { useCreditBalance } from '@/lib/hooks/smart-hooks-complete';

const SESSION_KEY = 'lc_show_welcome_gift';
// Hand-off signal consumed by SuggestedAppsModal so the two onboarding modals
// never overlap. Dispatched both when the gift closes and when it decides not
// to show (e.g. zero balance) - the sessionStorage latch closes the mount-order
// race when the suggestions listener registers after this fires.
const GIFT_DONE_FLAG = 'lc_welcome_gift_done';
const GIFT_DONE_EVENT = 'lc:welcome-gift-done';

export default function WelcomeGiftModal() {
  const t = useTranslations('modals.welcomeGift');
  const [shouldShow, setShouldShow] = useState(false);
  const [open, setOpen] = useState(false);
  const { balance, isLoading, error } = useCreditBalance();
  const doneRef = useRef(false);

  const markGiftDone = useCallback(() => {
    if (doneRef.current) return;
    doneRef.current = true;
    try { sessionStorage.setItem(GIFT_DONE_FLAG, '1'); } catch { /* ignore */ }
    window.dispatchEvent(new Event(GIFT_DONE_EVENT));
  }, []);

  const handleClose = useCallback(() => {
    setOpen(false);
    markGiftDone();
  }, [markGiftDone]);

  useEffect(() => {
    if (isCeMode) return;
    const flag = sessionStorage.getItem(SESSION_KEY);
    if (flag === '1') {
      sessionStorage.removeItem(SESSION_KEY);
      const timer = window.setTimeout(() => setShouldShow(true), 0);
      return () => window.clearTimeout(timer);
    }
  }, []);

  useEffect(() => {
    if (!shouldShow || isLoading) return;
    if (balance != null && balance > 0) {
      const timer = setTimeout(() => setOpen(true), 600);
      return () => clearTimeout(timer);
    }
    // Gift won't show: balance loaded as zero/negative, OR the balance fetch
    // failed. Hand off to the suggestions modal either way so it is never
    // blocked forever. (balance === null with no error = still settling → wait,
    // so we don't hand off prematurely before the query has resolved.)
    if (balance != null || error != null) {
      markGiftDone();
    }
  }, [shouldShow, isLoading, balance, error, markGiftDone]);

  if (!open || balance == null || balance <= 0) return null;

  const formattedBalance = balance.toLocaleString(getClientLocale());

  return (
    <Dialog open={open} onOpenChange={(o) => !o && handleClose()}>
      <DialogContent className="max-w-md gap-0 overflow-hidden border-theme bg-theme-primary p-0">
        <div className="border-b border-theme p-6 pb-5 pr-14">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-theme-tertiary">
              <Gift className="h-5 w-5 text-theme-primary" />
            </div>
            <div className="min-w-0">
              <DialogTitle className="text-lg font-semibold leading-6 text-theme-primary">
                {t('title')}
              </DialogTitle>
              <p className="mt-1 text-sm leading-5 text-theme-secondary">
                {t('subtitle')}
              </p>
            </div>
          </div>
        </div>

        <div className="p-6">
          <div className="rounded-lg border border-theme bg-theme-secondary p-4">
            <div className="flex items-center justify-between gap-4">
              <div className="flex items-center gap-3">
                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-theme-tertiary">
                  <Coins className="h-3.5 w-3.5 text-theme-secondary" />
                </div>
                <span className="text-sm font-medium text-theme-secondary">
                  {t('credits')}
                </span>
              </div>
              <span className="text-2xl font-semibold tabular-nums text-theme-primary">
                {formattedBalance}
              </span>
            </div>
          </div>

          <DialogDescription className="my-5 text-sm leading-6 text-theme-secondary">
            {t('description', { amount: formattedBalance })}
          </DialogDescription>

          <Button
            onClick={handleClose}
            variant="contrast"
            className="w-full"
          >
            {t('cta')}
            <ArrowRight className="h-3.5 w-3.5" />
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
