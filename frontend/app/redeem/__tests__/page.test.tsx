// @vitest-environment jsdom
//
// The public redeem landing ({origin}/redeem?code=CODE) must carry the shared
// PublicHeader so a visitor who pastes the link is not stranded on a chrome-less
// page: the LiveContext brand links back to /app. This pins that, plus that the
// ?code query param is forwarded into the redeem card.
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import RedeemPage from '../page';

// The real card hits apiClient via RewardApiService; stub it so this page test
// stays provider-free and focuses on the page chrome (header + code passthrough).
vi.mock('@/components/reward/RewardRedeemCard', () => ({
  RewardRedeemCard: ({ prefilledCode }: { prefilledCode?: string }) => (
    <div data-testid="redeem-card" data-code={prefilledCode ?? ''} />
  ),
}));

let mockSearch = '';
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(mockSearch),
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  mockSearch = '';
});

describe('RedeemPage', () => {
  it('renders the PublicHeader with a LiveContext brand link back to the app', () => {
    render(<RedeemPage />);

    const brand = screen.getByRole('link', { name: /LiveContext/i });
    expect(brand).toBeInTheDocument();
    expect(brand).toHaveAttribute('href', '/app');
  });

  it('forwards the ?code query param into the redeem card', () => {
    mockSearch = 'code=XVEJV8KZ';
    render(<RedeemPage />);

    expect(screen.getByTestId('redeem-card')).toHaveAttribute('data-code', 'XVEJV8KZ');
  });

  it('passes an empty code when no query param is present', () => {
    render(<RedeemPage />);

    expect(screen.getByTestId('redeem-card')).toHaveAttribute('data-code', '');
  });
});
