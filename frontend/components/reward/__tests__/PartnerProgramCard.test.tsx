// @vitest-environment jsdom
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import enMessages from '@/messages/en.json';

const edition = vi.hoisted(() => ({ isCe: false }));
const terms = vi.fn();

vi.mock('@/lib/edition', () => ({
  get IS_CE() { return edition.isCe; },
  get IS_MANAGED_CLOUD() { return !edition.isCe; },
}));
vi.mock('@/lib/api/services/partner-program-api.service', () => ({
  partnerProgramApi: { terms: () => terms() },
}));

import { PartnerProgramCard } from '../PartnerProgramCard';

function renderCard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <PartnerProgramCard />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

describe('Refer & earn: the partner program card', () => {
  beforeEach(() => {
    edition.isCe = false;
    terms.mockReset();
  });
  afterEach(cleanup);

  it('tells partnership apart from inviting friends, with the live top rate, and leads to /partners', async () => {
    terms.mockResolvedValue({
      commission_percent: 30, commission_months: 12,
      tiers: [
        { tier: 'silver', commission_percent: 30, threshold_minor: 0 },
        { tier: 'gold', commission_percent: 40, threshold_minor: 500_000 },
        { tier: 'platinum', commission_percent: 50, threshold_minor: 2_500_000 },
      ],
    });
    renderCard();

    await waitFor(() => expect(screen.getByText(/partners earn up to 50% of every invoice their clients pay, for 12 months/)).toBeTruthy());
    expect(screen.getByText(/Not the same as inviting friends/)).toBeTruthy();
    expect(screen.getByRole('link', { name: /Discover the partner program/ }).getAttribute('href')).toBe('/partners');
  });

  it('when the terms cannot be read it states no figure', async () => {
    terms.mockRejectedValue(new Error('down'));
    renderCard();

    await waitFor(() => expect(screen.getByText(/partners earn a share of every invoice/)).toBeTruthy());
    expect(screen.getByTestId('partner-program-card').textContent).not.toMatch(/\d+%/);
  });

  it('is not shown on a self-hosted install, where the program does not exist', () => {
    edition.isCe = true;
    const { container } = renderCard();

    expect(container.innerHTML).toBe('');
    expect(terms).not.toHaveBeenCalled();
  });
});
