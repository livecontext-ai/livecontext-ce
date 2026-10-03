// @vitest-environment jsdom
/**
 * The apps an offer gives, drawn with the marketplace card: the partner's seals come with the
 * offer, so the card never looks them up and shows the verified check only when the partner has it.
 */
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import type { OfferApp } from '@/lib/partners/publicPartnerOffer';

const card = vi.hoisted(() => vi.fn());
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: (props: { publication: { title: string }; publisherBadge?: { verified: boolean; partner: boolean } }) => {
    card(props);
    return <div>{props.publication.title}</div>;
  },
}));

import { OfferAppsSection } from '../OfferAppsSection';

const APP: OfferApp = {
  id: '6f1c0d2e-0000-4000-8000-00000000000a', title: 'Invoice chaser', description: null, publisherId: '42',
  publisherName: 'Northwind Studio', nodeIcons: [], showcaseRunId: null, showcaseInterfaceId: null, visibility: 'PUBLIC',
};

function renderSection(props: Partial<React.ComponentProps<typeof OfferAppsSection>> = {}) {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <OfferAppsSection apps={[APP]} partnerName="Northwind Studio" {...props} />
    </NextIntlClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  card.mockReset();
});

describe('OfferAppsSection', () => {
  it('regression: a verified partner keeps the verified check on their app cards, next to the partner seal', () => {
    renderSection({ partnerVerified: true });

    expect(screen.getByText('Invoice chaser')).toBeTruthy();
    expect(card).toHaveBeenCalledWith(expect.objectContaining({ publisherBadge: { verified: true, partner: true } }));
  });

  it('a partner without the badge, or with a private profile, shows the partner seal alone', () => {
    renderSection();

    expect(card).toHaveBeenCalledWith(expect.objectContaining({ publisherBadge: { verified: false, partner: true } }));
  });

  it('a note says who gets the apps when one is given, and none is drawn otherwise', () => {
    renderSection({ note: 'The apps in this offer need Pro or above.' });
    expect(screen.getByTestId('offer-apps-note').textContent).toBe('The apps in this offer need Pro or above.');
    cleanup();

    renderSection();
    expect(screen.queryByTestId('offer-apps-note')).toBeNull();
  });

  it('draws nothing for an offer without apps', () => {
    const { container } = renderSection({ apps: [] });

    expect(container.querySelector('[data-testid="offer-apps"]')).toBeNull();
    expect(card).not.toHaveBeenCalled();
  });
});
