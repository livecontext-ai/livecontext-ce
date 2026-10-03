'use client';

import React, { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { AppWindow, Lock } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useOptionalAuth } from '@/lib/providers/smart-providers';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import { WorkflowNodeIcons } from '@/components/WorkflowNodeIcons';

/** How many applications one offer gives (the server applies the same bound). */
export const MAX_OFFER_APPS = 10;

/**
 * Whether the partner may give this publication with an offer, as the server decides it: their own
 * active application, public or unlisted (what anyone holding the link can see and install), that
 * the cloud can run. A private one could be, once made unlisted: it is shown, not offered. Anything
 * else (a teammate's, a template, a withdrawn or self-hosted-only app) is not listed at all.
 */
export function offerAppState(pub: WorkflowPublication, partnerUserId: string): 'offerable' | 'private' | null {
  if (String(pub.publisherId ?? '') !== partnerUserId) return null;
  if (pub.displayMode !== 'APPLICATION' || pub.status !== 'ACTIVE' || pub.ceExclusive) return null;
  if (pub.visibility === 'PUBLIC' || pub.visibility === 'UNLISTED') return 'offerable';
  return pub.visibility === 'PRIVATE' ? 'private' : null;
}

/**
 * The partner's own applications, to give with an offer: whoever pays through the link has them
 * installed in their workspace after the payment. At most {@link MAX_OFFER_APPS}, in the order
 * picked (the order the offer page shows them).
 */
export function OfferAppsPicker({ selected, onChange }: { selected: string[]; onChange: (ids: string[]) => void }) {
  const t = useTranslations('partnerDashboard.dashboard.builder.apps');
  const numericUserId = useOptionalAuth()?.numericUserId ?? null;
  const me = numericUserId == null ? null : String(numericUserId);
  const apps = useQuery({
    queryKey: ['partner-program', 'offer-apps', me],
    queryFn: () => publicationService.getMyPublications(true),
    enabled: me != null,
    retry: false,
  });
  const rows = useMemo(() => {
    if (!me) return [];
    return (apps.data?.publications ?? [])
      .map((pub) => ({ pub, state: offerAppState(pub, me) }))
      .filter((r): r is { pub: WorkflowPublication; state: 'offerable' | 'private' } => r.state != null)
      // The ones that can be given first.
      .sort((a, b) => (a.state === b.state ? 0 : a.state === 'offerable' ? -1 : 1));
  }, [apps.data, me]);
  const full = selected.length >= MAX_OFFER_APPS;

  const toggle = (id: string) => {
    if (selected.includes(id)) onChange(selected.filter((s) => s !== id));
    else if (!full) onChange([...selected, id]);
  };

  return (
    <fieldset data-testid="builder-apps">
      <legend className="flex w-full items-center justify-between gap-2 text-sm font-medium text-theme-primary">
        <span className="flex items-center gap-2">
          <AppWindow className="h-3.5 w-3.5 text-theme-secondary" aria-hidden />
          {t('title')}
        </span>
        {selected.length > 0 && (
          <span className="text-xs tabular-nums text-theme-secondary" data-testid="builder-apps-count">
            {t('count', { count: selected.length, max: MAX_OFFER_APPS })}
          </span>
        )}
      </legend>
      <p className="mt-1 text-sm text-theme-secondary">{t('hint')}</p>
      {me == null || apps.isPending ? (
        <p className="mt-2 text-sm text-theme-secondary" data-testid="builder-apps-loading">{t('loading')}</p>
      ) : apps.isError ? (
        <p role="alert" className="mt-2 text-sm text-red-500" data-testid="builder-apps-error">{t('error')}</p>
      ) : rows.length === 0 ? (
        <p className="mt-2 text-sm text-theme-secondary" data-testid="builder-apps-empty">{t('empty')}</p>
      ) : (
        <ul className="mt-2 max-h-64 space-y-1.5 overflow-y-auto pr-1">
          {rows.map(({ pub, state }) => {
            const checked = selected.includes(pub.id);
            const disabled = state === 'private' || (!checked && full);
            return (
              <li key={pub.id}>
                <label
                  className={cn(
                    'flex items-center gap-3 rounded-xl border px-3 py-2 text-sm transition-colors',
                    checked ? 'border-[#d99a1e] bg-[#f2b640]/10' : 'border-theme bg-theme-primary',
                    disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer hover:border-[#d99a1e]/60',
                  )}
                  data-testid={`builder-app-${pub.id}`}
                >
                  <input
                    type="checkbox"
                    className="h-3.5 w-3.5 shrink-0 accent-[#d99a1e]"
                    checked={checked}
                    disabled={disabled}
                    onChange={() => toggle(pub.id)}
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium text-theme-primary">{pub.title}</span>
                    {state === 'private' && (
                      <span className="flex items-center gap-1 text-sm text-theme-secondary">
                        <Lock className="h-3.5 w-3.5" aria-hidden />
                        {t('private')}
                      </span>
                    )}
                  </span>
                  {pub.nodeIcons && pub.nodeIcons.length > 0 && (
                    <WorkflowNodeIcons nodeIcons={pub.nodeIcons} maxDisplay={3} prioritizeMcpAndTriggers size="inline" className="shrink-0" />
                  )}
                </label>
              </li>
            );
          })}
        </ul>
      )}
      {full && <p className="mt-2 text-sm text-theme-secondary" data-testid="builder-apps-full">{t('full', { max: MAX_OFFER_APPS })}</p>}
    </fieldset>
  );
}

export default OfferAppsPicker;
