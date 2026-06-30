'use client';

import * as React from 'react';
import { AlertTriangle } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { normalizeScopes } from '@/lib/credentials/normalizeScopes';

interface MissingScopesBannerProps {
  /**
   * The OAuth scopes the tool requires (from node metadata `requiredScopes`).
   * Pass an empty array or undefined to render nothing.
   */
  requiredScopes: string[] | undefined | null;
  /**
   * The granted scopes on the user's currently bound credential.
   * Pass undefined / null when the credential is not OAuth2 (no scope concept) -
   * the banner won't render.
   */
  grantedScopes: string[] | undefined | null;
  /**
   * Credential type ('OAuth2', 'API Key', 'Bearer Token', ...). Banner only
   * renders when type === 'OAuth2' (exact case, per CredentialType enum).
   */
  credentialType: string | undefined | null;
  /** Display name shown in the banner copy; falls back to "Google" if missing. */
  integrationDisplayName?: string;
  /** Click handler for the primary "Reconnect (Standard)" CTA. */
  onReconnect?: () => void;
  /**
   * V166 BYOK: click handler for the secondary "Switch to Advanced" CTA.
   * When provided, renders a 2nd button below Reconnect that opens the
   * credential wizard directly on the BYOK form (initialMode='advanced').
   * Use this for restricted scopes the platform can't grant - the user
   * brings their own client_id/secret and a wider consent screen.
   */
  onSwitchToAdvanced?: () => void;
  /**
   * Scopes the platform-shared OAuth client never requests (catalog
   * `oauth2Config.byokOnlyScopes`). Used as a FALLBACK signal when
   * {@link platformScopes} isn't supplied.
   */
  byokOnlyScopes?: string[] | null;
  /**
   * The scopes the platform-shared OAuth client DOES request (catalog
   * `oauth2Config.scopes`). This is the canonical signal: a Standard reconnect
   * can only grant scopes in this set, so ANY missing required scope not in it
   * is unmanaged by the platform and needs BYOK - even one declared in neither
   * `scopes` nor `byokOnlyScopes`. When provided, it supersedes the byokOnly
   * check; the banner then hides "Reconnect (Standard)" and offers BYOK directly.
   */
  platformScopes?: string[] | null;
}

/**
 * V166: warns the user when an MCP tool requires OAuth scopes their bound
 * credential has not been granted. Renders only for OAuth2 credentials with at
 * least one missing scope. Reconnect CTA opens the credential reconnect flow
 * which re-authorizes with the union of currently-granted + missing scopes.
 */
export function MissingScopesBanner({
  requiredScopes,
  grantedScopes,
  credentialType,
  integrationDisplayName,
  onReconnect,
  onSwitchToAdvanced,
  byokOnlyScopes,
  platformScopes,
}: MissingScopesBannerProps) {
  const t = useTranslations('workflow.node.missingScopes');

  if (!requiredScopes || requiredScopes.length === 0) return null;
  // CredentialType enum values are exact-case ('OAuth2', 'API Key', …).
  if (credentialType !== 'OAuth2') return null;

  // Flatten any provider-returned comma/whitespace-blob (e.g. Slack's
  // `"channels:read,channels:history,..."` single-element shape) before
  // building the membership set. Without this normalization, every workflow
  // raises false-positive "missing scopes" because Set.has(individualScope)
  // never matches the embedded blob. See {@link normalizeScopes} for details.
  const granted = new Set(normalizeScopes(grantedScopes));
  const missing = requiredScopes.filter((s) => !granted.has(s));
  if (missing.length === 0) return null;

  // A missing scope the platform client never requests can NEVER be granted by a
  // Standard reconnect → route straight to BYOK and hide the (futile) Standard
  // CTA. Canonical signal: the scope is NOT in the platform connect's scope set
  // (`platformScopes`) - this covers byokOnly scopes AND any required scope the
  // catalog declared in neither list. When platformScopes isn't supplied, fall
  // back to the explicit byokOnly list. Only flip to BYOK-only when a BYOK path
  // is actually available, so we never render a dead-end banner.
  const platformKnown = Array.isArray(platformScopes);
  const needsByok = platformKnown
    ? (() => {
        const grantable = new Set(normalizeScopes(platformScopes));
        return missing.some((s) => !grantable.has(s));
      })()
    : (() => {
        const byokOnly = new Set(normalizeScopes(byokOnlyScopes));
        return missing.some((s) => byokOnly.has(s));
      })();
  const byokOnlyMode = needsByok && !!onSwitchToAdvanced;

  const integration = integrationDisplayName || 'this service';

  return (
    <div
      role="alert"
      className="flex items-start gap-2.5 p-3 rounded-md border border-amber-300 dark:border-amber-700 bg-amber-50 dark:bg-amber-950/40 text-sm"
    >
      <AlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0 text-amber-600 dark:text-amber-400" />
      <div className="flex-1 min-w-0">
        <p className="font-medium text-amber-900 dark:text-amber-100">
          {t('title', { integration })}
        </p>
        <p className="mt-1 text-amber-800 dark:text-amber-200/90">
          {byokOnlyMode ? t('restrictedBody') : t('body')}
        </p>
        <ul className="mt-1.5 space-y-0.5 text-xs font-mono text-amber-900 dark:text-amber-200">
          {missing.map((scope) => (
            <li key={scope} className="break-all">{scope}</li>
          ))}
        </ul>
        <div className="mt-2.5 flex flex-wrap gap-2">
          {/* When a missing scope is byok-only, a Standard reconnect can't grant
              it - hide that CTA and offer BYOK directly. */}
          {!byokOnlyMode && onReconnect && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="h-7 text-xs"
              onClick={onReconnect}
            >
              {t('cta')}
            </Button>
          )}
          {onSwitchToAdvanced && (
            <Button
              type="button"
              variant="default"
              size="sm"
              className="h-7 text-xs"
              onClick={onSwitchToAdvanced}
            >
              {t('switchToAdvanced')}
            </Button>
          )}
        </div>
        {onSwitchToAdvanced && !byokOnlyMode && (
          <p className="mt-2 text-xs text-amber-800 dark:text-amber-200/80">
            {t('advancedHint')}
          </p>
        )}
      </div>
    </div>
  );
}
