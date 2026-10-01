// @vitest-environment jsdom
/**
 * The Partner Program Terms notice on its own, in French: the link goes to the French text (the
 * one that prevails), and the accepted line dates the acceptance in the app language. The page
 * suite covers the states and the accept flow in English.
 */
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import frMessages from '@/messages/fr.json';
import type { PartnerAgreement } from '@/lib/api/services/partner-program-api.service';
import { PARTNER_TERMS_VERSION } from '@/lib/partners/terms';

vi.mock('@/lib/api/services/partner-program-api.service', () => ({
  PARTNER_DASHBOARD_QUERY_KEY: ['partner-program', 'me'],
  partnerProgramApi: { acceptTerms: vi.fn() },
}));

import { PartnerTermsNotice } from '../PartnerTermsNotice';

function renderFr(agreement: PartnerAgreement | null) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <NextIntlClientProvider locale="fr" messages={frMessages}>
        <PartnerTermsNotice agreement={agreement} />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

const AGREEMENT: PartnerAgreement = {
  current_version: PARTNER_TERMS_VERSION, accepted_version: null, accepted_at: null,
  accepted_current: false, required: true, payouts_blocked: true,
};

describe('PartnerTermsNotice (French)', () => {
  afterEach(cleanup);

  it('asks to accept, links the French text and says nothing is paid until then', () => {
    renderFr(AGREEMENT);

    const notice = screen.getByTestId('partner-terms-notice');
    expect(notice.textContent).toContain('aucune commission ne peut vous être versée');
    expect(screen.getByRole('link', { name: 'conditions du programme partenaires' }).getAttribute('href'))
      .toBe('/legal/partners/fr');
  });

  it('once accepted, one line with the version, the date in French, and the French text', () => {
    renderFr({
      ...AGREEMENT, accepted_version: PARTNER_TERMS_VERSION, accepted_at: '2026-10-02T09:00:00Z',
      accepted_current: true, required: false, payouts_blocked: false,
    });

    const line = screen.getByTestId('partner-terms-accepted');
    expect(line.textContent).toContain(PARTNER_TERMS_VERSION);
    expect(line.textContent).toMatch(/2 oct\.? 2026|02\/10\/2026|2 octobre 2026/);
    expect(screen.getByRole('link').getAttribute('href')).toBe('/legal/partners/fr');
  });

  it('no agreement block (an older backend), no notice', () => {
    const { container } = renderFr(null);

    expect(container.textContent).toBe('');
  });
});
