/**
 * @vitest-environment jsdom
 *
 * Cloud pricing page banner for a self-hosted install waiting for a paid plan
 * (`?ce_link=1`, set by the onboarding and billing-success continuations). Real en.json
 * strings. Its "continue" button re-checks eligibility for a user who upgraded through a
 * path that does not end on the billing success page, and says why when it cannot continue.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../messages/en.json';

const h = vi.hoisted(() => ({
  searchParams: new URLSearchParams(),
  isCe: false,
  isAuthenticated: true,
  continuePendingCeLink: vi.fn(),
}));

vi.mock('next/navigation', () => ({ useSearchParams: () => h.searchParams }));
vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return h.isCe;
  },
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useOptionalAuth: () => ({ isAuthenticated: h.isAuthenticated }),
}));
vi.mock('@/lib/cloud-link/pendingCeLink', () => ({
  continuePendingCeLink: () => h.continuePendingCeLink(),
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span data-testid="spinner" /> }));

import { CeLinkPricingBanner } from '../CeLinkPricingBanner';

const cloud = messages.ceCloudLink.cloud;
const renderBanner = () =>
  render(
    <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
      <CeLinkPricingBanner />
    </NextIntlClientProvider>,
  );

describe('CeLinkPricingBanner', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    h.searchParams = new URLSearchParams('ce_link=1');
    h.isCe = false;
    h.isAuthenticated = true;
  });
  afterEach(cleanup);

  it('explains the paid-plan requirement on ?ce_link=1', () => {
    renderBanner();
    expect(screen.getByText(cloud.pricingBannerTitle)).toBeInTheDocument();
    expect(screen.getByText(cloud.pricingBannerBody)).toBeInTheDocument();
  });

  it('renders nothing without the flag, and nothing in a CE build', () => {
    h.searchParams = new URLSearchParams();
    const { unmount } = renderBanner();
    expect(screen.queryByTestId('ce-link-pricing-banner')).toBeNull();
    unmount();

    h.searchParams = new URLSearchParams('ce_link=1');
    h.isCe = true;
    renderBanner();
    expect(screen.queryByTestId('ce-link-pricing-banner')).toBeNull();
  });

  it('offers no re-check to a signed-out visitor', () => {
    h.isAuthenticated = false;
    renderBanner();
    expect(screen.queryByRole('button', { name: cloud.pricingBannerContinue })).toBeNull();
  });

  it.each([
    ['plan_required', cloud.pricingBannerStillRequired],
    ['none', cloud.pricingBannerNoPending],
    ['error', cloud.pricingBannerError],
    ['redirected', cloud.returning],
  ])('re-check outcome %s shows its message', async (outcome, text) => {
    h.continuePendingCeLink.mockResolvedValue(outcome);
    renderBanner();
    fireEvent.click(screen.getByRole('button', { name: cloud.pricingBannerContinue }));
    await waitFor(() => expect(screen.getByText(text)).toBeInTheDocument());
    expect(h.continuePendingCeLink).toHaveBeenCalledTimes(1);
  });
});
