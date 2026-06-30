// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ numericUserId: 5 }) }));
vi.mock('@/components/marketplace/InterfacePreview', () => ({ InterfacePreview: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({ default: () => null }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => null }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));

const mocks = vi.hoisted(() => ({
  getMarketplaceByType: vi.fn(),
  getLandingSnapshot: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getMarketplaceByType: mocks.getMarketplaceByType,
    getLandingSnapshot: mocks.getLandingSnapshot,
  },
}));

import { ResourceMarketplaceGrid } from '../ResourceMarketplaceGrid';

function pub(overrides: Partial<WorkflowPublication>): WorkflowPublication {
  return {
    id: overrides.id ?? 'p',
    title: overrides.title ?? 'Pub',
    creditsPerUse: 0,
    publisherId: 'someone-else', // a teammate published it (not the viewer)
    status: 'ACTIVE',
    visibility: 'PUBLIC',
    useCount: 0,
    totalCreditsEarned: 0,
    ...overrides,
  } as WorkflowPublication;
}

describe('ResourceMarketplaceGrid org-aware ownership (resource marketplaces)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getLandingSnapshot.mockResolvedValue({ landing: null });
  });

  it('shows Installed for an app owned by my workspace (ownedByMe) and Acquire for one that is not', async () => {
    mocks.getMarketplaceByType.mockResolvedValue({
      publications: [
        pub({ id: 'mine', title: 'Owned Skill', ownedByMe: true }),
        pub({ id: 'other', title: 'Foreign Skill', ownedByMe: false }),
      ],
    });

    render(<ResourceMarketplaceGrid type="SKILL" icon={() => null} title="Skills" subtitle="sub" emptyText="empty" />);

    await waitFor(() => expect(screen.getByText('Owned Skill')).toBeInTheDocument());

    // Owned-by-my-workspace card → Installed badge, no Acquire (even though publisherId != me).
    const ownedCard = screen.getByText('Owned Skill').closest('div.group')!;
    expect(within(ownedCard as HTMLElement).getByText('installed')).toBeInTheDocument();
    expect(within(ownedCard as HTMLElement).queryByText('acquire')).toBeNull();

    // Not-owned card → Acquire button, no Installed badge.
    const otherCard = screen.getByText('Foreign Skill').closest('div.group')!;
    expect(within(otherCard as HTMLElement).getByText('acquire')).toBeInTheDocument();
    expect(within(otherCard as HTMLElement).queryByText('installed')).toBeNull();
  });

  it('falls back to the publisher-id check when ownedByMe is absent (legacy back-compat)', async () => {
    mocks.getMarketplaceByType.mockResolvedValue({
      publications: [
        pub({ id: 'mine-legacy', title: 'My Legacy Skill', publisherId: '5' }), // publisher == me, no ownedByMe
        pub({ id: 'other-legacy', title: 'Other Legacy Skill', publisherId: '9' }),
      ],
    });

    render(<ResourceMarketplaceGrid type="SKILL" icon={() => null} title="Skills" subtitle="sub" emptyText="empty" />);

    await waitFor(() => expect(screen.getByText('My Legacy Skill')).toBeInTheDocument());

    const mineCard = screen.getByText('My Legacy Skill').closest('div.group')!;
    expect(within(mineCard as HTMLElement).getByText('installed')).toBeInTheDocument();
    const otherCard = screen.getByText('Other Legacy Skill').closest('div.group')!;
    expect(within(otherCard as HTMLElement).getByText('acquire')).toBeInTheDocument();
  });
});
