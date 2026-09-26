'use client';

/**
 * CE only: the cloud-link OAuth callback came back with a state the backend no longer knows
 * (expired, already used, or the backend restarted), so the backend redirected here with
 * `?cloud_link_error=expired` instead of a bare 400. The hook turns that into a visible
 * message and removes the parameter from the address bar, so a reload does not show it again.
 *
 * Hosts: the ce-setup wizard, the settings cloud-account page and the marketplace connect CTA,
 * i.e. every page the backend accepts as a cloud-link returnPath.
 */

import * as React from 'react';
import { useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { AlertCircle } from 'lucide-react';
import { cn } from '@/lib/utils';

/** Query parameter the CE backend adds when a cloud-link callback arrives with an unknown or expired state. */
export const CLOUD_LINK_ERROR_PARAM = 'cloud_link_error';

/** True once the page was opened with `?cloud_link_error=expired`; the parameter is then removed from the URL. */
export function useCloudLinkExpired(): boolean {
  const searchParams = useSearchParams();
  const [expired, setExpired] = useState(false);

  useEffect(() => {
    if (searchParams?.get(CLOUD_LINK_ERROR_PARAM) !== 'expired') return;
    setExpired(true);
    try {
      const url = new URL(window.location.href);
      url.searchParams.delete(CLOUD_LINK_ERROR_PARAM);
      window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`);
    } catch {
      // Cosmetic only: the message is already shown.
    }
  }, [searchParams]);

  return expired;
}

export function CloudLinkExpiredNotice({ className }: { className?: string }) {
  const t = useTranslations('ceCloudLink');
  return (
    <div
      role="alert"
      data-testid="cloud-link-expired-notice"
      className={cn(
        'flex items-center gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-sm text-amber-700 dark:text-amber-300',
        className,
      )}
    >
      <AlertCircle className="h-4 w-4 flex-shrink-0" />
      <span>{t('expired')}</span>
    </div>
  );
}
