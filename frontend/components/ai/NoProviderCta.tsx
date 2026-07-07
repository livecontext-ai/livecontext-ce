'use client';

/**
 * NoProviderCta - the "no AI provider configured" call to action shown by the
 * model-picking surfaces when the catalog resolves to ZERO usable models.
 *
 * On a CE install that is neither cloud-linked nor BYOK-configured, every
 * provider/model picker used to render silently empty - a dead end with no
 * hint of what to do. This component replaces that emptiness with the two
 * ways out, mirroring the ce-setup wizard's ordering:
 *
 *  - PRIMARY: "Connect to LiveContext Cloud" - one-click OAuth kickoff via
 *    {@link cloudLinkService.getAuthUrl}. No returnPath is passed on purpose:
 *    the backend only whitelists pages that HANDLE the ?cloud_link_callback
 *    (ce-setup / marketplace / settings cloud-account), so the flow lands on
 *    the cloud-account settings page which completes the link and shows the
 *    linked confirmation.
 *  - SECONDARY: "Add an API key" - navigates to /app/settings/ai-providers
 *    where the admin saves a BYOK provider key.
 *
 * Both actions are admin-only server-side, so a non-admin member gets an
 * explanatory "ask your administrator" line instead of buttons that would 403.
 *
 * CE-only: cloud builds render nothing (a cloud tenant always has platform
 * models; an empty catalog there is an outage, not an onboarding state).
 *
 * Hosts: {@code ModelPicker} (form variant - agent modal, workflow node
 * inspectors) and {@code ModelSelectorDropdown} (menu variant - the chat
 * composers, injected via the {@code emptyState} prop so the dropdown itself
 * stays translation-free).
 */

import * as React from 'react';
import Link from 'next/link';
import { Cloud, Key } from 'lucide-react';
import { useLocale, useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import LoadingSpinner from '@/components/LoadingSpinner';
import { cloudLinkService } from '@/lib/api/cloud-link.service';
import { useOptionalAuth } from '@/lib/providers/smart-providers';
import { IS_CE } from '@/lib/edition';
import { cn } from '@/lib/utils';

export interface NoProviderCtaProps {
  /**
   * 'form' - bordered card for form surfaces (ModelPicker hosts: agent modal,
   * node inspectors). 'menu' - compact block for the composer dropdown menu.
   */
  variant?: 'form' | 'menu';
  className?: string;
}

export function NoProviderCta({ variant = 'form', className }: NoProviderCtaProps) {
  const t = useTranslations('aiProviders.noProviderCta');
  const locale = useLocale();
  const auth = useOptionalAuth();
  const isAdmin = auth?.hasRole('ADMIN') ?? false;
  const [connecting, setConnecting] = React.useState(false);
  const [error, setError] = React.useState(false);

  const handleConnectCloud = async () => {
    setConnecting(true);
    setError(false);
    try {
      // Default returnPath: the settings cloud-account page, which handles the
      // ?cloud_link_callback and clears the models cache once linked.
      const { authUrl } = await cloudLinkService.getAuthUrl();
      window.location.href = authUrl;
    } catch {
      setError(true);
      setConnecting(false);
    }
  };

  if (!IS_CE) return null;

  const compact = variant === 'menu';

  return (
    <div
      data-testid="no-provider-cta"
      className={cn(
        'flex flex-col items-center text-center',
        compact ? 'px-3 py-4 gap-2' : 'rounded-xl border border-theme px-4 py-6 gap-2.5',
        className,
      )}
    >
      <div
        className={cn(
          'rounded-full bg-[var(--accent-primary)]/10 flex items-center justify-center',
          compact ? 'w-9 h-9' : 'w-12 h-12',
        )}
      >
        <Cloud className={cn('text-[var(--accent-primary)]', compact ? 'h-4 w-4' : 'h-6 w-6')} />
      </div>
      <p className="text-sm font-semibold text-theme-primary">{t('title')}</p>
      <p className="text-sm text-theme-secondary max-w-xs">
        {isAdmin ? t('body') : t('bodyMember')}
      </p>
      {error && <p className="text-sm text-red-500">{t('connectError')}</p>}
      {isAdmin && (
        <div className={cn('flex flex-col items-stretch gap-2 w-full', compact ? 'mt-1' : 'mt-2 max-w-xs')}>
          <Button onClick={handleConnectCloud} disabled={connecting} size="sm">
            {connecting ? (
              <LoadingSpinner size="xs" className="mr-2" />
            ) : (
              <Cloud className="h-4 w-4 mr-2" />
            )}
            {t('connectCloud')}
          </Button>
          <Button asChild variant="outline" size="sm">
            <Link href={`/${locale}/app/settings/ai-providers`}>
              <Key className="h-4 w-4 mr-2" />
              {t('addApiKey')}
            </Link>
          </Button>
        </div>
      )}
    </div>
  );
}
