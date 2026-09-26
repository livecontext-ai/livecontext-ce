'use client';

import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { KeyRound, LifeBuoy, Smartphone } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useAuth, useOptionalAuth } from '@/lib/providers/smart-providers';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { formatUtcDate } from '@/lib/utils/dateFormatters';

export const MFA_STATUS_QUERY_KEY = ['user', 'mfa-status'] as const;

/** Where Keycloak sends the user back after enrolling or removing an authenticator app. */
export const TWO_FACTOR_RETURN_TO = '/app/settings/overview?tab=security';

/** Keycloak application-initiated action that removes one credential (re-authentication first). */
export const deleteCredentialAction = (credentialId: string) => `delete_credential:${credentialId}`;

/** Keycloak application-initiated action that generates a new set of 12 recovery codes. */
export const RECOVERY_CODES_ACTION = 'CONFIGURE_RECOVERY_AUTHN_CODES';

/** At or below this many codes left, the card asks for a new set. */
export const LOW_RECOVERY_CODES = 3;

/**
 * Set when the user turns two-factor on from this card; read once on the way back. Keycloak
 * runs one application-initiated action per redirect, so "add an app, then save recovery
 * codes" is two redirects: this flag is what chains the second without a second click.
 */
export const OFFER_RECOVERY_CODES_FLAG = 'lc.twoFactor.offerRecoveryCodes';

/**
 * "Two-factor authentication": the authenticator apps (TOTP) protecting the account.
 *
 * Read-only against the backend (GET /me/mfa). Enrolling and removing both happen on
 * Keycloak pages, launched as application-initiated actions, so no secret or code ever
 * transits through the app. Where the account cannot hold a factor (`available=false`)
 * it renders nothing, or a one-line notice when it is the tab's only content
 * (`standalone`), so the Security tab is never an empty page.
 *
 * A platform admin (`required`) cannot remove their last app: the button is hidden, and
 * the backend re-arms enrollment at the next sign-in if it is removed some other way.
 *
 * Recovery codes: without them a lost phone locks the account. The card says so while there
 * are none, counts what is left, and asks for a new set when few remain. Removing the last
 * app also removes the codes (the backend retires them: left alone, Keycloak would demand a
 * recovery code at every sign-in).
 */
export function TwoFactorSettingsCard({ standalone = false }: { standalone?: boolean }) {
  const t = useTranslations('settings.twoFactor');
  const auth = useOptionalAuth();
  const { loginWithRedirect } = useAuth();
  const enabled = !!auth && auth.isAuthenticated && !auth.isLoading;

  const { data, isPending, isError } = useQuery({
    queryKey: MFA_STATUS_QUERY_KEY,
    queryFn: () => unifiedApiService.getMfaStatus(),
    enabled,
    staleTime: 60 * 1000,
    retry: false,
  });

  // Back from turning two-factor on: offer the recovery codes straight away, once.
  useEffect(() => {
    if (!data) return;
    let offer = false;
    try {
      offer = window.sessionStorage.getItem(OFFER_RECOVERY_CODES_FLAG) === '1';
      if (offer) window.sessionStorage.removeItem(OFFER_RECOVERY_CODES_FLAG);
    } catch {
      return;
    }
    if (offer && data.available && data.totpEnabled && !data.recoveryCodes) {
      void loginWithRedirect({
        authorizationParams: { kc_action: RECOVERY_CODES_ACTION },
        appState: { returnTo: TWO_FACTOR_RETURN_TO },
        resetLoopGuards: true,
      });
    }
  }, [data, loginWithRedirect]);

  if (data && !data.available) {
    return standalone ? (
      <p className="text-sm text-theme-secondary" data-testid="two-factor-unavailable">{t('unavailable')}</p>
    ) : null;
  }

  const launch = (kcAction: string) =>
    loginWithRedirect({
      authorizationParams: { kc_action: kcAction },
      appState: { returnTo: TWO_FACTOR_RETURN_TO },
      resetLoopGuards: true,
    });

  const turnOn = () => {
    try {
      window.sessionStorage.setItem(OFFER_RECOVERY_CODES_FLAG, '1');
    } catch {
      // Storage unavailable: the codes are then offered by the card, not chained.
    }
    void launch('CONFIGURE_TOTP');
  };

  const devices = data?.devices ?? [];
  const canRemove = !data?.required || devices.length > 1;
  const codes = data?.recoveryCodes ?? null;
  const codesLow = codes?.remaining != null && codes.remaining <= LOW_RECOVERY_CODES;

  return (
    <div className="rounded-lg border border-theme bg-theme-tertiary p-6 space-y-4" data-testid="two-factor-card">
      <div className="flex items-start justify-between gap-4">
        <div className="flex items-center space-x-3">
          <div className="w-10 h-10 bg-theme-secondary rounded-xl flex items-center justify-center">
            <KeyRound className="w-5 h-5 text-theme-primary" />
          </div>
          <div>
            <h4 className="text-base font-semibold text-theme-primary">{t('title')}</h4>
            <p className="text-sm text-theme-secondary">{t('description')}</p>
          </div>
        </div>
        {data && (
          <span
            className={`shrink-0 rounded-md px-2 py-0.5 text-xs font-semibold ${
              data.totpEnabled
                ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-400'
                : 'bg-theme-secondary text-theme-secondary'
            }`}
            data-testid="two-factor-state"
          >
            {data.totpEnabled ? t('stateOn') : t('stateOff')}
          </span>
        )}
      </div>

      {!data && !isError && <p className="text-sm text-theme-secondary">{t('loading')}</p>}
      {isError && <p className="text-sm text-red-600 dark:text-red-400">{t('error')}</p>}

      {data?.required && (
        <p className="text-sm text-theme-secondary" data-testid="two-factor-required">
          {data.totpEnabled ? t('requiredForAdmins') : t('requiredForAdminsPending')}
        </p>
      )}

      {devices.length > 0 && (
        <ul className="space-y-2">
          {devices.map((device) => (
            <li
              key={device.id}
              className="flex items-center justify-between gap-3 rounded-md border border-theme bg-theme-primary px-3 py-2"
            >
              <div className="flex items-center gap-2 min-w-0">
                <Smartphone className="h-3.5 w-3.5 shrink-0 text-theme-secondary" />
                <span className="text-sm text-theme-primary truncate">{device.label || t('unnamedDevice')}</span>
                {device.createdAt && (
                  <span className="text-xs text-theme-secondary shrink-0">
                    {t('addedOn', { date: formatUtcDate(device.createdAt) })}
                  </span>
                )}
              </div>
              {canRemove && (
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  className="h-8 px-3"
                  onClick={() => launch(deleteCredentialAction(device.id))}
                >
                  {t('remove')}
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      {data?.totpEnabled && (
        <div className="rounded-md border border-theme bg-theme-primary p-3 space-y-2" data-testid="recovery-codes">
          <div className="flex items-start justify-between gap-3">
            <div className="flex items-start gap-2 min-w-0">
              <LifeBuoy className="h-3.5 w-3.5 mt-0.5 shrink-0 text-theme-secondary" />
              <div className="min-w-0">
                <p className="text-sm font-medium text-theme-primary">{t('recoveryTitle')}</p>
                <p
                  className={`text-sm ${!codes || codesLow ? 'text-amber-700 dark:text-amber-400' : 'text-theme-secondary'}`}
                  data-testid="recovery-codes-state"
                >
                  {!codes
                    ? t('recoveryNone')
                    : codes.remaining == null || codes.total == null
                      ? t('recoverySet')
                      : codesLow
                        ? t('recoveryLow', { remaining: codes.remaining })
                        : t('recoveryLeft', { remaining: codes.remaining, total: codes.total })}
                </p>
              </div>
            </div>
            <Button
              type="button"
              size="sm"
              variant={!codes || codesLow ? 'default' : 'outline'}
              className="h-8 px-3 shrink-0"
              onClick={() => launch(RECOVERY_CODES_ACTION)}
            >
              {codes ? t('recoveryRegenerate') : t('recoveryCreate')}
            </Button>
          </div>
        </div>
      )}

      {/* Only "Turn on" is offered. Adding a SECOND app is deliberately not surfaced: the
          recovery codes are the backup for a lost phone, and each extra secret is one more
          place to steal it from. Several apps stay supported (Keycloak accepts them, the list
          above shows and removes each one), there is just no button to add more. */}
      {data && !data.totpEnabled && (
        <div className="flex justify-end">
          <Button type="button" size="sm" className="h-8 px-3" onClick={turnOn}>
            <KeyRound className="w-4 h-4 mr-1" />
            <span>{t('turnOn')}</span>
          </Button>
        </div>
      )}
    </div>
  );
}
