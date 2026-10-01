'use client';

import React, { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useMutation } from '@tanstack/react-query';
import { useLocale, useTranslations } from 'next-intl';
import { Send } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { ApiError } from '@/lib/api/api-client';
import { partnerProgramApi } from '@/lib/api/services/partner-program-api.service';
import type { PartnerApplyDraft } from '@/lib/partners/applyDraft';
import { PARTNER_TERMS_VERSION, partnerTermsPathFor } from '@/lib/partners/terms';

/** Same ceilings as the backend (PartnerProgramService), so a field can never be refused for length. */
export const APPLY_LIMITS = { company: 120, website: 255, audience: 500, message: 2000 } as const;

/**
 * The application to the partner program. The backend decides every rule (one open
 * application, not already a partner, website shape); this form only mirrors the length
 * limits and reports the server's refusal in the user's language.
 *
 * <p>Applying accepts the Partner Program Terms (V557): an unticked box keeps the form from being
 * sent, and the version the applicant ticked travels with the application, so the backend
 * records which text they accepted (and refuses an outdated one).
 *
 * <p>On the public /partners page a visitor may fill it before signing in: with
 * {@code signedIn} false, submitting hands what they typed to {@code onSignInToSubmit} (which
 * saves it and starts the sign-in) instead of calling the API. Back signed in, the page mounts
 * the form again with that {@code initialDraft} and {@code autoSubmit}, and it is sent once.
 */
export function PartnerApplyForm({
  onApplied,
  signedIn = true,
  onSignInToSubmit,
  initialDraft,
  autoSubmit = false,
}: {
  onApplied: () => void;
  signedIn?: boolean;
  onSignInToSubmit?: (draft: PartnerApplyDraft) => void;
  initialDraft?: PartnerApplyDraft | null;
  autoSubmit?: boolean;
}) {
  const t = useTranslations('partnerDashboard.apply');
  const tErrors = useTranslations('partnerDashboard.errors');
  const locale = useLocale();
  const [company, setCompany] = useState(initialDraft?.company ?? '');
  const [website, setWebsite] = useState(initialDraft?.website ?? '');
  const [audience, setAudience] = useState(initialDraft?.audience ?? '');
  const [message, setMessage] = useState(initialDraft?.message ?? '');
  // Ticked before signing in carries over, but only for the text that is still current.
  const [acceptedTerms, setAcceptedTerms] = useState(initialDraft?.termsVersion === PARTNER_TERMS_VERSION);
  const [error, setError] = useState<string | null>(null);

  const apply = useMutation({
    mutationFn: () => partnerProgramApi.apply({
      company_name: company.trim(),
      website: website.trim() || undefined,
      audience: audience.trim() || undefined,
      message: message.trim() || undefined,
      terms_version: PARTNER_TERMS_VERSION,
    }),
    onSuccess: () => {
      setError(null);
      onApplied();
    },
    onError: (err: unknown) => {
      const code = err instanceof ApiError ? err.code : undefined;
      setError(code && tErrors.has(code) ? tErrors(code) : tErrors('generic'));
    },
  });

  // Send a draft saved before signing in, once. The ref keeps the effect off the mutation
  // object, which is a new one every render.
  const mutateRef = useRef(apply.mutate);
  mutateRef.current = apply.mutate;
  const autoSent = useRef(false);
  useEffect(() => {
    if (autoSubmit && signedIn && !autoSent.current && company.trim() && acceptedTerms) {
      autoSent.current = true;
      mutateRef.current();
    }
    // Only when the mount conditions change: a later edit must not re-send.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoSubmit, signedIn]);

  const submit = () => {
    if (!acceptedTerms) return;
    if (!signedIn) {
      onSignInToSubmit?.({
        company: company.trim(),
        website: website.trim(),
        audience: audience.trim(),
        message: message.trim(),
        termsVersion: PARTNER_TERMS_VERSION,
      });
      return;
    }
    apply.mutate();
  };

  return (
    <form
      onSubmit={(e) => { e.preventDefault(); submit(); }}
      className="bg-theme-secondary rounded-xl p-6 space-y-4"
      aria-labelledby="partner-apply-title"
    >
      <div>
        <h3 id="partner-apply-title" className="text-base font-semibold text-theme-primary">{t('title')}</h3>
        <p className="text-sm text-theme-secondary">{t('intro')}</p>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="space-y-1">
          <Label htmlFor="partner-company" className="text-sm">{t('company')}</Label>
          <Input
            id="partner-company"
            required
            maxLength={APPLY_LIMITS.company}
            value={company}
            onChange={(e) => setCompany(e.target.value)}
            placeholder={t('companyPlaceholder')}
            className="h-9 text-sm"
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="partner-website" className="text-sm">{t('website')}</Label>
          <Input
            id="partner-website"
            type="url"
            maxLength={APPLY_LIMITS.website}
            value={website}
            onChange={(e) => setWebsite(e.target.value)}
            placeholder="https://"
            className="h-9 text-sm"
          />
        </div>
        <div className="space-y-1 sm:col-span-2">
          <Label htmlFor="partner-audience" className="text-sm">{t('audience')}</Label>
          <Input
            id="partner-audience"
            maxLength={APPLY_LIMITS.audience}
            value={audience}
            onChange={(e) => setAudience(e.target.value)}
            placeholder={t('audiencePlaceholder')}
            className="h-9 text-sm"
          />
        </div>
        <div className="space-y-1 sm:col-span-2">
          <Label htmlFor="partner-message" className="text-sm">{t('message')}</Label>
          <Textarea
            id="partner-message"
            maxLength={APPLY_LIMITS.message}
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            rows={4}
            className="text-sm"
          />
        </div>
      </div>
      {error && <p role="alert" className="text-sm text-red-500">{error}</p>}
      <label className="flex items-start gap-2 text-sm text-theme-secondary" htmlFor="partner-terms">
        <input
          id="partner-terms"
          type="checkbox"
          required
          checked={acceptedTerms}
          onChange={(e) => setAcceptedTerms(e.target.checked)}
          className="mt-0.5 h-4 w-4 shrink-0"
          data-testid="partner-terms-checkbox"
        />
        <span>
          {t.rich('terms', {
            version: PARTNER_TERMS_VERSION,
            link: (chunks) => (
              <Link href={partnerTermsPathFor(locale)} target="_blank" rel="noopener noreferrer" className="underline">
                {chunks}
              </Link>
            ),
          })}
        </span>
      </label>
      <Button type="submit" size="sm" className="gap-1" disabled={apply.isPending || !company.trim() || !acceptedTerms}>
        <Send className="h-3.5 w-3.5" />
        {apply.isPending ? t('submitting') : signedIn ? t('submit') : t('submitSignIn')}
      </Button>
    </form>
  );
}
