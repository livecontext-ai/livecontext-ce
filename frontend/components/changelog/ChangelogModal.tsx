'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Sparkles } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { useChangelog } from '@/hooks/useChangelog';
import { APP_SUGGESTIONS_FLAG, WELCOME_GIFT_FLAG } from '@/lib/onboarding/welcomeGiftHandoff';
import ChangelogMediaView from './ChangelogMediaView';
import { track } from '@/lib/analytics/analytics';

/**
 * Onboarding modals already queued in this session. The hook keeps the panel quiet until
 * onboarding is COMPLETE, which covers the flow itself; these flags cover the minutes after it,
 * while the welcome gift and the suggested-applications modal are still waiting to show.
 * Between them, a first run is never interrupted by a release note.
 *
 * <p>Both names are IMPORTED rather than spelled out. The gift's was spelled out once, and when
 * the modal that writes it was replaced the literal here went stale in silence: half this list
 * guarded a modal nobody could arm any more. Onboarding writes its two flags in separate `try`
 * blocks on purpose, so a tab that refuses one write and accepts the other is a real state, and in
 * that state a dead literal lets this panel open on top of the modal it exists to stay behind.
 */
const ONBOARDING_FLAGS = [WELCOME_GIFT_FLAG, APP_SUGGESTIONS_FLAG];

/** Long enough for the app shell to settle, short enough to still read as part of arriving. */
const AUTO_OPEN_DELAY_MS = 1200;

/**
 * The in-app "What's new" panel: one entry, the newest, shown once per user.
 *
 * Mounted once in the app layout. It opens by itself when the running build ships an entry this
 * user has not acknowledged, and nothing else opens it: there is deliberately no entry point in
 * the app chrome. The archive of past entries lives on the public changelog page, which the panel
 * links to.
 *
 * The entry is acknowledged as soon as it is SHOWN, so "once per user" holds even for a reader who
 * closes the tab without dismissing it.
 */
export default function ChangelogModal() {
  const t = useTranslations('changelog');
  const { entry, isAvailable, decision, markSeen } = useChangelog();
  const [open, setOpen] = useState(false);
  // Read at mount, before the onboarding modals consume their own flags: by the time this panel
  // would open, they may already have removed them from sessionStorage.
  const [onboardingInFlight] = useState(hasOnboardingFlag);

  // An account that never lacked what the entry announces is acknowledged without ever being
  // shown it. The ref keeps React's double-invoked effects (StrictMode) to one write.
  const sealedRef = useRef(false);
  useEffect(() => {
    if (decision !== 'seal' || sealedRef.current) return;
    sealedRef.current = true;
    markSeen();
  }, [decision, markSeen]);

  useEffect(() => {
    if (decision !== 'announce' || onboardingInFlight) return;
    const timer = window.setTimeout(() => setOpen(true), AUTO_OPEN_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [decision, onboardingInFlight]);

  // Acknowledged when it OPENS, not when it closes. "Shown once per user" has to mean shown, and
  // a user who reads the panel then closes the tab (or navigates away) writes nothing on the close
  // path - the panel would greet them again on the next load, forever. markSeen is idempotent and
  // no-ops once the key matches.
  useEffect(() => {
    if (open) markSeen();
  }, [open, markSeen]);

  // Reported once per entry actually on screen: the ref holds the key already reported, so
  // StrictMode's doubled effect and a re-render while open do not count it twice.
  const shownKeyRef = useRef<string | null>(null);
  const visible = open && !!entry && isAvailable;
  useEffect(() => {
    if (!visible || !entry || shownKeyRef.current === entry.key) return;
    shownKeyRef.current = entry.key;
    track('changelog_shown', { entry_key: entry.key, has_media: !!entry.media });
  }, [visible, entry]);

  const handleClose = useCallback((action: 'dismiss' | 'learn_more' = 'dismiss') => {
    if (entry) {
      track('changelog_closed', { entry_key: entry.key, has_media: !!entry.media, action });
    }
    setOpen(false);
    // Belt and braces: the open effect above has already acknowledged, and this is a no-op once
    // the key matches. It stays because closing is the moment the user is definitely done with
    // the entry, and an acknowledgement lost to a failed request on open gets a second chance.
    markSeen();
  }, [markSeen, entry]);

  if (!open || !entry || !isAvailable) return null;

  return (
    <Dialog open={open} onOpenChange={(next) => !next && handleClose()}>
      <DialogContent className="max-w-lg gap-0 overflow-hidden border-theme bg-theme-primary p-0">
        <div className="border-b border-theme p-6 pb-5 pr-14">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-theme-tertiary">
              <Sparkles className="h-5 w-5 text-theme-primary" />
            </div>
            <div className="min-w-0">
              <DialogTitle className="text-lg font-semibold leading-6 text-theme-primary">
                {t('latest.title')}
              </DialogTitle>
              <p className="mt-1 text-sm leading-5 text-theme-secondary">{t('whatsNew')}</p>
            </div>
          </div>
        </div>

        {/*
          The middle row is the one that gives. DialogContent caps at max-h-[90vh], and this
          panel passes `overflow-hidden` (it has to: the media is flush to the rounded corners
          and would otherwise square them off), which twMerge-overrides the base
          `overflow-y-auto`. Without a scroll region of its own, anything past the cap is
          clipped with no way to reach it, and the footer is the LAST thing in the box: the
          dismiss button and the "see all updates" link are what a short window eats first.
          Escape and the corner close button still work, so the panel never traps anyone,
          but a reader who never sees the footer does not know the archive exists.
          `min-h-0` is what lets a grid row shrink below its content so `overflow-y-auto` has
          something to do. Measured: at a 640px-tall viewport the German, French and Portuguese
          entries overflow the cap by 17px.
        */}
        <div className="min-h-0 overflow-y-auto space-y-4 p-6">
          {entry.media && <ChangelogMediaView media={entry.media} alt={t('latest.mediaAlt')} />}
          <DialogDescription className="text-sm leading-6 text-theme-secondary">
            {t('latest.body')}
          </DialogDescription>
        </div>

        <div className="flex items-center justify-between gap-3 border-t border-theme p-4">
          {entry.learnMoreUrl ? (
            <a
              href={entry.learnMoreUrl}
              target="_blank"
              rel="noopener noreferrer"
              // Acknowledged on the way out too: the user has seen the entry, whatever they do
              // next with it.
              onClick={() => handleClose('learn_more')}
              className="text-sm text-theme-secondary underline underline-offset-4 hover:text-theme-primary"
            >
              {t('learnMore')}
            </a>
          ) : (
            <span />
          )}
          <Button onClick={() => handleClose('dismiss')} data-testid="changelog-dismiss">
            {t('dismiss')}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function hasOnboardingFlag(): boolean {
  try {
    return ONBOARDING_FLAGS.some((flag) => sessionStorage.getItem(flag) === '1');
  } catch {
    // Private mode / storage disabled: assume no onboarding rather than suppressing the panel
    // forever on a browser that cannot answer.
    return false;
  }
}
