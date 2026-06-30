'use client';

import React, { useEffect, useState } from 'react';
import { Sparkles, ArrowRight } from 'lucide-react';
import { useTranslations, useLocale } from 'next-intl';
import { useRouter } from 'next/navigation';
import { useQuery } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { PublicationCard } from '@/components/marketplace/PublicationCard';
import { apiClient } from '@/lib/api';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import type { OnboardingStatus } from '@/components/security/onboardingStatus';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { isCeMode } from '@/lib/format-cost';

/**
 * Onboarding "suggested applications" modal.
 *
 * Shown once, right AFTER the WelcomeGiftModal, at the end of onboarding. It
 * proposes marketplace applications tailored to the user's onboarding choices
 * (the backend OnboardingCategoryMapper turns interests / useCases / profession
 * into category slugs; PublicationListQueryService returns matching public
 * applications, with a top-applications fallback). Each card is the exact same
 * {@link PublicationCard} used in /app/marketplace - clicking it opens the
 * publication's marketplace preview page.
 *
 * Sequencing: onboarding sets {@code lc_show_app_suggestions}; this modal arms
 * on that flag, then waits for {@code lc:welcome-gift-done} (dispatched by
 * WelcomeGiftModal when it closes or decides not to show) so the two modals
 * never overlap. A sessionStorage latch ({@code lc_welcome_gift_done}) closes
 * the mount-order race when the gift finishes before this modal's listener
 * registers.
 */
const SHOW_FLAG = 'lc_show_app_suggestions';
const GIFT_DONE_FLAG = 'lc_welcome_gift_done';
const GIFT_DONE_EVENT = 'lc:welcome-gift-done';
const SUGGESTION_LIMIT = 4;

export default function SuggestedAppsModal() {
  const t = useTranslations('modals.suggestedApps');
  const locale = useLocale();
  const router = useRouter();
  const { user, isLoading } = useAuthGuard();

  const [armed, setArmed] = useState(false);
  const [giftDone, setGiftDone] = useState(false);
  const [open, setOpen] = useState(false);

  // Arm once when onboarding set the flag. Consume it unconditionally (even in
  // CE) so it never lingers in sessionStorage.
  useEffect(() => {
    const flagged = sessionStorage.getItem(SHOW_FLAG) === '1';
    if (flagged) sessionStorage.removeItem(SHOW_FLAG);
    if (isCeMode || !flagged) return;
    const timer = window.setTimeout(() => setArmed(true), 0);
    return () => window.clearTimeout(timer);
  }, []);

  // Wait for the welcome-gift modal to finish before showing.
  useEffect(() => {
    if (!armed) return;
    if (sessionStorage.getItem(GIFT_DONE_FLAG) === '1') {
      sessionStorage.removeItem(GIFT_DONE_FLAG);
      const timer = window.setTimeout(() => setGiftDone(true), 0);
      return () => window.clearTimeout(timer);
    }
    const handler = () => {
      try { sessionStorage.removeItem(GIFT_DONE_FLAG); } catch { /* ignore */ }
      setGiftDone(true);
    };
    window.addEventListener(GIFT_DONE_EVENT, handler);
    return () => window.removeEventListener(GIFT_DONE_EVENT, handler);
  }, [armed]);

  const { data } = useQuery({
    queryKey: ['onboarding-suggested-apps', user?.sub],
    enabled: armed && giftDone && !!user && !isLoading,
    staleTime: Infinity,
    retry: false,
    queryFn: async () => {
      // Fetch the full onboarding profile fresh (the FirstLoginGuard cache only
      // holds the minimal completed/skipped shape, without the choices).
      let status: OnboardingStatus | undefined;
      try {
        status = await apiClient.get<OnboardingStatus>('/auth-service/api/onboarding/status');
      } catch {
        status = undefined;
      }
      return publicationService.getSuggestedApplications({
        interests: status?.interests,
        useCases: status?.useCases,
        profession: status?.profession,
        limit: SUGGESTION_LIMIT,
      });
    },
  });

  const apps: WorkflowPublication[] = data?.publications ?? [];

  // Open once suggestions arrive (small delay for a smooth hand-off from the gift).
  useEffect(() => {
    if (giftDone && apps.length > 0) {
      const timer = setTimeout(() => setOpen(true), 250);
      return () => clearTimeout(timer);
    }
  }, [giftDone, apps.length]);

  if (!open || apps.length === 0) return null;

  const goToMarketplace = () => {
    setOpen(false);
    router.push(`/${locale}/app/marketplace`);
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && setOpen(false)}>
      <DialogContent className="max-w-2xl gap-0 overflow-hidden border-theme bg-theme-primary p-0">
        <div className="border-b border-theme p-6 pb-5 pr-14">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-theme-tertiary">
              <Sparkles className="h-5 w-5 text-theme-primary" />
            </div>
            <div className="min-w-0">
              <DialogTitle className="text-lg font-semibold leading-6 text-theme-primary">
                {t('title')}
              </DialogTitle>
              <DialogDescription className="mt-1 text-sm leading-5 text-theme-secondary">
                {t('subtitle')}
              </DialogDescription>
            </div>
          </div>
        </div>

        <div className="max-h-[60vh] overflow-y-auto px-6 py-5">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {apps.map((app) => (
              // PublicationCard is a <Link> to the preview - close the modal on click
              // so it doesn't linger over the destination route (shared app layout).
              <div key={app.id} onClick={() => setOpen(false)}>
                <PublicationCard publication={app} />
              </div>
            ))}
          </div>
        </div>

        <div className="flex items-center justify-end gap-3 border-t border-theme p-4">
          <Button variant="ghost" size="sm" onClick={() => setOpen(false)}>
            {t('dismiss')}
          </Button>
          <Button variant="contrast" size="sm" onClick={goToMarketplace}>
            {t('cta')}
            <ArrowRight className="h-3.5 w-3.5" />
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
