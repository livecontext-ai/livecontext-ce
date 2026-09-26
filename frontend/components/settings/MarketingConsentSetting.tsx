'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { Mail } from 'lucide-react';
import { Switch } from '@/components/ui/switch';
import { useOptionalAuth } from '@/lib/providers/smart-providers';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import type { MarketingConsent } from '@/lib/api/services/user-api.service';

export const MARKETING_CONSENT_QUERY_KEY = ['user', 'marketing-consent'] as const;

/**
 * "Email updates": the person's opt-in to LiveContext news and offers by e-mail (cloud only).
 * Reads GET /users/profile/marketing-consent and writes PUT on toggle, optimistically, rolling
 * back when the write is refused.
 */
export function MarketingConsentSetting() {
  const t = useTranslations('settings.emailUpdates');
  const auth = useOptionalAuth();
  const queryClient = useQueryClient();
  const enabled = !!auth && auth.isAuthenticated && !auth.isLoading;

  const { data, isPending, isError } = useQuery({
    queryKey: MARKETING_CONSENT_QUERY_KEY,
    queryFn: () => unifiedApiService.getMarketingConsent(),
    enabled,
    staleTime: 5 * 60 * 1000,
    retry: false,
  });

  const mutation = useMutation({
    mutationFn: (consent: boolean) => unifiedApiService.setMarketingConsent(consent),
    onMutate: async (consent: boolean) => {
      await queryClient.cancelQueries({ queryKey: MARKETING_CONSENT_QUERY_KEY });
      const previous = queryClient.getQueryData<MarketingConsent>(MARKETING_CONSENT_QUERY_KEY);
      queryClient.setQueryData<MarketingConsent>(MARKETING_CONSENT_QUERY_KEY, {
        consent,
        updatedAt: previous?.updatedAt ?? null,
      });
      return { previous };
    },
    onError: (_error, _consent, context) => {
      if (context?.previous) {
        queryClient.setQueryData(MARKETING_CONSENT_QUERY_KEY, context.previous);
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: MARKETING_CONSENT_QUERY_KEY });
    },
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center space-x-3">
        <div className="w-10 h-10 bg-theme-secondary rounded-xl flex items-center justify-center">
          <Mail className="w-5 h-5 text-theme-primary" />
        </div>
        <div>
          <h3 className="text-lg font-semibold text-theme-primary">{t('title')}</h3>
          <p className="text-sm text-theme-secondary">{t('description')}</p>
        </div>
      </div>
      <div className="flex items-center justify-between gap-4">
        <p id="marketing-consent-label" className="text-sm text-theme-primary">
          {t('label')}
        </p>
        <Switch
          checked={data?.consent ?? false}
          disabled={isPending || isError || mutation.isPending}
          onCheckedChange={(checked) => mutation.mutate(checked)}
          aria-label={t('label')}
        />
      </div>
      {(isError || mutation.isError) && (
        <p className="text-sm text-red-600 dark:text-red-400">{t('error')}</p>
      )}
    </div>
  );
}
