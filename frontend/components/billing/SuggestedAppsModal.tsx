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
import {
  APP_SUGGESTIONS_FLAG,
  WELCOME_GIFT_DONE_EVENT,
  isWelcomeGiftPending,
} from '@/lib/onboarding/welcomeGiftHandoff';
import { isCeMode } from '@/lib/format-cost';

/**
 * Onboarding "suggested applications" modal.
 *
 * Shown once at the end of onboarding. It proposes marketplace applications
 * tailored to the user's onboarding choices (the backend
 * OnboardingCategoryMapper turns primaryGoal / interests / useCases /
 * profession into category slugs; PublicationListQueryService returns matching
 * public applications, with a top-applications fallback). Each card is the
 * exact same {@link PublicationCard} used in /app/marketplace - clicking it
 * opens the publication's marketplace preview page.
 *
 * <p><b>Sequencing.</b> Onboarding arms two things: the welcome gift (what the
 * account's monthly credits are) and this. The gift goes first, so this
 * one waits for {@link WELCOME_GIFT_DONE_EVENT} when a gift is still pending,
 * and opens straight away when none is.
 *
 * <p>The wait is conditional on the flag for a reason this modal has already
 * paid for once: it once waited unconditionally on this very hand-off during a
 * period when the gift modal had been deleted, so it sat armed forever and never
 * opened. Reading the flag means it only ever waits while something is actually
 * still owed, and the gift's owner clears that flag on every path it can take,
 * including the ones where it shows nothing.
 *
 * <p><b>Its own flag is consumed when it ARMS, not when it mounts.</b> The two
 * are the same instant on the direct path and minutes apart behind the gift,
 * which is long enough for a reader to reload: consuming at mount would drop
 * these suggestions on the floor while the gift, whose flag is still set,
 * replayed. Claiming the flag late also survives an effect that mounts twice
 * (React's development double-invoke), where consume-at-mount armed a timer and
 * then threw away the flag its own second pass needed.
 */
const SHOW_FLAG = APP_SUGGESTIONS_FLAG;
const SUGGESTION_LIMIT = 4;

export default function SuggestedAppsModal() {
  const t = useTranslations('modals.suggestedApps');
  const locale = useLocale();
  const router = useRouter();
  const { user, isLoading } = useAuthGuard();

  const [armed, setArmed] = useState(false);
  const [open, setOpen] = useState(false);

  // Arm once when onboarding set the flag, and claim the flag at that moment.
  useEffect(() => {
    // Every access is guarded: this is mounted on every app page, and a tab with
    // site data blocked throws on read as well as on write. An unguarded read
    // here would throw at mount on each navigation, for a modal that only ever
    // shows once.
    const claimed = () => {
      try {
        sessionStorage.removeItem(SHOW_FLAG);
      } catch {
        // Nothing stored means nothing to claim.
      }
    };
    let flagged = false;
    try {
      flagged = sessionStorage.getItem(SHOW_FLAG) === '1';
    } catch {
      flagged = false;
    }
    if (!flagged) return;
    if (isCeMode) {
      // Nothing to show self-hosted, so claim it now rather than let it linger.
      claimed();
      return;
    }

    const arm = () => {
      claimed();
      setArmed(true);
    };

    // Behind the welcome gift when one is still owed: two overlays opening on
    // the same paint would stack, and the one a new account should read first
    // is what its plan already grants.
    if (isWelcomeGiftPending()) {
      window.addEventListener(WELCOME_GIFT_DONE_EVENT, arm, { once: true });
      return () => window.removeEventListener(WELCOME_GIFT_DONE_EVENT, arm);
    }

    const timer = window.setTimeout(arm, 0);
    return () => window.clearTimeout(timer);
  }, []);

  const { data } = useQuery({
    queryKey: ['onboarding-suggested-apps', user?.sub],
    enabled: armed && !!user && !isLoading,
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
        primaryGoal: status?.primaryGoal,
        limit: SUGGESTION_LIMIT,
      });
    },
  });

  const apps: WorkflowPublication[] = data?.publications ?? [];

  // Open once suggestions arrive (small delay so the chat behind has painted).
  useEffect(() => {
    if (armed && apps.length > 0) {
      const timer = setTimeout(() => setOpen(true), 250);
      return () => clearTimeout(timer);
    }
  }, [armed, apps.length]);

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
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-theme-tertiary">
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
          <Button variant="default" size="sm" onClick={goToMarketplace}>
            {t('cta')}
            <ArrowRight className="h-3.5 w-3.5" />
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
