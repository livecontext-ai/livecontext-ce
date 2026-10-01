// @vitest-environment jsdom
/**
 * The public profile names a partner's tier next to the one gold badge; a partner is never shown
 * the blue check beside it, and a non-partner has no tier at all.
 */
import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const fetchPublicProfile = vi.fn();

vi.mock('server-only', () => ({}));
vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_MANAGED_CLOUD: true }));
vi.mock('next/navigation', () => ({ notFound: () => { throw new Error('NOT_FOUND'); } }));
vi.mock('@/components/landing/LandingShell', () => ({
  LandingShell: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/lib/marketplace/publicProfiles', () => ({
  fetchPublicProfile: (handle: string) => fetchPublicProfile(handle),
  fetchPublicationsByPublisher: async () => [],
}));
vi.mock('@/lib/marketplace/publicBadges', () => ({ fetchPublicBadges: async () => [], publicBadgeName: () => '' }));
vi.mock('@/components/badges/PublicBadgeShowcase', () => ({ PublicBadgeShowcase: () => null }));

import ProfilePage from '../page';

function profile(overrides: Record<string, unknown>) {
  return {
    userId: 7, displayName: 'Maya Chen', handle: 'maya', avatarUrl: null, bio: null, joinedAt: null,
    searchIndexable: false, verified: true, partner: false, partnerTier: null, ...overrides,
  };
}

async function renderPage() {
  render(await ProfilePage({ params: Promise.resolve({ handle: 'maya' }) }));
}

describe('/u/[handle]: the partner tier', () => {
  beforeEach(() => fetchPublicProfile.mockReset());
  afterEach(cleanup);

  it('a partner shows the gold badge alone and the tier beside it', async () => {
    fetchPublicProfile.mockResolvedValue(profile({ partner: true, partnerTier: 'gold' }));
    await renderPage();

    expect(screen.getByRole('img', { name: 'Official LiveContext partner' })).toBeTruthy();
    expect(screen.queryByRole('img', { name: 'Verified account' })).toBeNull();
    expect(screen.getByText('Gold partner').getAttribute('data-tier')).toBe('gold');
  });

  it('a verified non-partner keeps the blue check and shows no tier', async () => {
    fetchPublicProfile.mockResolvedValue(profile({}));
    await renderPage();

    expect(screen.getByRole('img', { name: 'Verified account' })).toBeTruthy();
    expect(screen.queryByText(/partner$/)).toBeNull();
  });
});
