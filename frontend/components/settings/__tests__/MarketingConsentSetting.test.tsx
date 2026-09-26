// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

/**
 * Settings > "Email updates": reads the stored consent, writes it on toggle, and puts the
 * switch back when the write is refused.
 */
const mocks = vi.hoisted(() => ({
  getMarketingConsent: vi.fn(),
  setMarketingConsent: vi.fn(),
  auth: { isAuthenticated: true, isLoading: false } as { isAuthenticated: boolean; isLoading: boolean },
}));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/lib/providers/smart-providers', () => ({ useOptionalAuth: () => mocks.auth }));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: {
    getMarketingConsent: mocks.getMarketingConsent,
    setMarketingConsent: mocks.setMarketingConsent,
  },
}));

import { MarketingConsentSetting } from '../MarketingConsentSetting';

function renderSetting() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MarketingConsentSetting />
    </QueryClientProvider>,
  );
}

describe('MarketingConsentSetting', () => {
  beforeEach(() => {
    mocks.getMarketingConsent.mockReset();
    mocks.setMarketingConsent.mockReset();
    mocks.auth = { isAuthenticated: true, isLoading: false };
  });

  afterEach(() => cleanup());

  it('shows the stored consent and saves the new value on toggle', async () => {
    mocks.getMarketingConsent.mockResolvedValue({ consent: false, updatedAt: null });
    mocks.setMarketingConsent.mockResolvedValue(undefined);
    renderSetting();

    const toggle = await screen.findByRole('switch', { name: 'label' });
    await waitFor(() => expect(toggle).toBeEnabled());
    expect(toggle).toHaveAttribute('aria-checked', 'false');

    mocks.getMarketingConsent.mockResolvedValue({ consent: true, updatedAt: '2026-09-24T10:00:00Z' });
    fireEvent.click(toggle);

    await waitFor(() => expect(mocks.setMarketingConsent).toHaveBeenCalledWith(true));
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'true'));
  });

  it('rolls the switch back and says so when the write is refused', async () => {
    mocks.getMarketingConsent.mockResolvedValue({ consent: false, updatedAt: null });
    mocks.setMarketingConsent.mockRejectedValue(new Error('500'));
    renderSetting();

    const toggle = await screen.findByRole('switch', { name: 'label' });
    await waitFor(() => expect(toggle).toBeEnabled());
    fireEvent.click(toggle);

    expect(await screen.findByText('error')).toBeInTheDocument();
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'false'));
  });

  it('does not read the consent while signed out', async () => {
    mocks.auth = { isAuthenticated: false, isLoading: false };
    renderSetting();

    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(mocks.getMarketingConsent).not.toHaveBeenCalled();
  });
});
