'use client';

import React from 'react';
import { useTranslations } from 'next-intl';
import type { WorkflowPublication } from '@/lib/api';
import type { OfferApp } from '@/lib/partners/publicPartnerOffer';
import { PublicationCard } from '@/components/marketplace/PublicationCard';
import { PARTNER_DISPLAY } from '@/components/partner/partnerTheme';

/** The marketplace card's shape for an offered app: an app, included (no price, no install). */
function asPublication(app: OfferApp): WorkflowPublication {
  return {
    id: app.id,
    title: app.title,
    description: app.description ?? undefined,
    publicationType: 'WORKFLOW',
    displayMode: 'APPLICATION',
    isApplication: true,
    creditsPerUse: 0,
    publisherId: app.publisherId,
    publisherName: app.publisherName ?? undefined,
    nodeIcons: app.nodeIcons,
    // With a showcase run the card draws the app's live screen, as the marketplace does.
    showcaseRunId: app.showcaseRunId ?? undefined,
    showcaseInterfaceId: app.showcaseInterfaceId ?? undefined,
    status: 'ACTIVE',
    visibility: app.visibility,
    useCount: 0,
  } as WorkflowPublication;
}

/**
 * The partner's apps that come with the offer, drawn as the landing and the marketplace draw
 * apps: the same card, the app's own screen as its picture. No install button: they are installed
 * in the client's workspace once the client pays. Nothing is drawn for an offer without apps.
 */
export function OfferAppsSection({ apps, partnerName, partnerVerified = false, note = null }: {
  apps: OfferApp[];
  partnerName: string | null;
  /** Whether the partner carries the verified badge (false when their profile is private). */
  partnerVerified?: boolean;
  /** A line under the title: who gets the apps, when not everyone looking at the page does. */
  note?: string | null;
}) {
  const t = useTranslations('partnerOffer');
  if (apps.length === 0) return null;

  return (
    <section id="offer-apps" className="mx-auto max-w-6xl scroll-mt-6 px-4 pt-12 md:px-6 md:pt-16" data-testid="offer-apps">
      <h2 className="text-2xl font-bold text-theme-primary md:text-3xl" style={PARTNER_DISPLAY}>
        {partnerName ? t('appsTitle', { partner: partnerName }) : t('appsTitleGeneric')}
      </h2>
      {note && <p className="mt-2 text-sm text-theme-secondary" data-testid="offer-apps-note">{note}</p>}
      <div className="mt-6 grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3 md:gap-6">
        {apps.map((app) => (
          <div key={app.id} data-testid="offer-app">
            {/* The apps are the partner's own: their seals come with the offer, never looked up. */}
            <PublicationCard publication={asPublication(app)} publisherBadge={{ verified: partnerVerified, partner: true }} />
          </div>
        ))}
      </div>
    </section>
  );
}
