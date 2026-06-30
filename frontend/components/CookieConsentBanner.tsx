'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { Cookie } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { CONSENT_CHANGE_EVENT } from '@/lib/analytics/consent';

// Persisted consent choice. Bump VERSION when the cookie categories change so a
// new banner is shown to re-collect consent. The stored value is the contract a
// future analytics/marketing loader gates on (`status === 'accepted'`).
const STORAGE_KEY = 'lc.cookieConsent';
const VERSION = 1;

type ConsentStatus = 'accepted' | 'rejected';

/**
 * GDPR/ePrivacy cookie consent banner. Mounted once globally (locale layout) so
 * it appears on the first page a visitor opens - landing OR a deep-linked app
 * page - and is dismissed for the whole site once a choice is made.
 *
 * Today the site sets only strictly-necessary (auth/session) and functional
 * (language preference) cookies, so "Reject" simply records the documented
 * choice; there is nothing to disable yet. The persisted value is the gate for
 * any future non-essential cookie/tracker.
 */
export default function CookieConsentBanner() {
  const t = useTranslations('cookieConsent');
  // Render nothing until we have read the stored choice on the client. This
  // avoids an SSR/CSR hydration mismatch (localStorage is client-only) and a
  // flash of the banner for visitors who already decided.
  const [visible, setVisible] = useState(false);
  const regionRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      const parsed = raw ? JSON.parse(raw) : null;
      // Show when there is no prior decision, or when the consent version moved on.
      setVisible(!parsed || parsed.version !== VERSION);
    } catch {
      // Malformed value or storage blocked - show the banner.
      setVisible(true);
    }
  }, []);

  // Move focus to the banner when it appears so keyboard / screen-reader users
  // reach the consent choice instead of having it announced last (it is fixed
  // at the end of the DOM).
  useEffect(() => {
    if (visible) regionRef.current?.focus();
  }, [visible]);

  const persist = (status: ConsentStatus) => {
    try {
      localStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({ status, version: VERSION, ts: Date.now() }),
      );
    } catch {
      // localStorage unavailable (private mode / blocked): still hide for this
      // session so the banner does not nag on every render.
    }
    // Notify live listeners (analytics loader) so consent takes effect without
    // a reload. The persisted value above remains the source of truth.
    try {
      window.dispatchEvent(new CustomEvent(CONSENT_CHANGE_EVENT, { detail: status }));
    } catch {
      // CustomEvent unsupported / blocked - persisted value is still read on next load.
    }
    setVisible(false);
  };

  if (!visible) return null;

  return (
    <div
      ref={regionRef}
      role="region"
      tabIndex={-1}
      aria-labelledby="cookie-consent-title"
      aria-describedby="cookie-consent-desc"
      // z-[100]: above page content, deliberately BELOW the modal/toast layer
      // (dialogs use z-[9999]) so it never overlays an open dialog.
      className="cookie-consent-banner fixed bottom-4 left-4 right-4 z-[100] rounded-2xl border border-theme bg-theme-primary p-4 shadow-lg outline-none sm:left-auto sm:max-w-sm"
    >
      <div className="mb-1.5 flex items-center gap-2">
        <Cookie className="h-4 w-4 text-theme-primary" aria-hidden="true" />
        <h2 id="cookie-consent-title" className="text-sm font-semibold text-theme-primary">{t('title')}</h2>
      </div>
      <p id="cookie-consent-desc" className="text-sm text-theme-secondary">{t('message')}</p>
      <Link
        href="/legal/privacy"
        className="mt-1 inline-block text-sm text-theme-secondary underline underline-offset-2 hover:text-theme-primary"
      >
        {t('privacyLink')}
      </Link>
      <div className="mt-3 flex items-center justify-end gap-2">
        <Button variant="outline" size="sm" onClick={() => persist('rejected')}>
          {t('reject')}
        </Button>
        <Button variant="default" size="sm" onClick={() => persist('accepted')}>
          {t('accept')}
        </Button>
      </div>
    </div>
  );
}
