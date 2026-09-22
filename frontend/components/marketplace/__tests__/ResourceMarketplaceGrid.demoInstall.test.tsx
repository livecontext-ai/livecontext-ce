// @vitest-environment jsdom
/**
 * Admin demo-install mode on the by-type resource marketplaces (skills, tables,
 * interfaces). Same rule as the main grid: ownership and "already installed"
 * stop hiding the CTA, while CE-exclusive keeps blocking because that one is
 * true of the publication rather than of the viewer.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ numericUserId: 5 }),
  useOptionalAuth: () => ({ hasRole: () => false }),
}));
vi.mock('@/components/marketplace/InterfacePreview', () => ({ InterfacePreview: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({ default: () => null }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => null }));
// Pin the edition instead of inheriting the ambient default: the CE-exclusive
// case below is only meaningful on MANAGED cloud, where the acquire would 403.
vi.mock('@/lib/edition', () => ({
  EDITION: 'cloud',
  IS_CE: false,
  IS_CLOUD: true,
  IS_MANAGED_CLOUD: true,
}));

const demoMode = vi.hoisted(() => ({ on: false }));
vi.mock('@/lib/marketplace/demoInstallMode', () => ({
  useMarketplaceDemoInstall: () => demoMode.on,
}));

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
    publisherId: 'someone-else',
    status: 'ACTIVE',
    visibility: 'PUBLIC',
    useCount: 0,
    totalCreditsEarned: 0,
    ...overrides,
  } as WorkflowPublication;
}

function grid() {
  return render(
    <ResourceMarketplaceGrid type="SKILL" icon={() => null} title="Skills" subtitle="sub" emptyText="empty" />,
  );
}

async function cardFor(title: string) {
  await waitFor(() => expect(screen.getByText(title)).toBeInTheDocument());
  return screen.getByText(title).closest('div.group') as HTMLElement;
}

describe('ResourceMarketplaceGrid - admin demo-install mode', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    demoMode.on = false;
    mocks.getLandingSnapshot.mockResolvedValue({ landing: null });
    mocks.getMarketplaceByType.mockResolvedValue({
      publications: [pub({ id: 'mine', title: 'Owned Skill', ownedByMe: true })],
    });
  });

  it('off: the owner sees Installed and no CTA', async () => {
    grid();
    const card = await cardFor('Owned Skill');
    expect(within(card).getByText('installed')).toBeInTheDocument();
    expect(within(card).queryByText('acquire')).toBeNull();
  });

  it('on: the owner gets the Install CTA back and loses the badge', async () => {
    demoMode.on = true;
    grid();
    const card = await cardFor('Owned Skill');
    expect(within(card).getByText('acquire')).toBeInTheDocument();
    expect(within(card).queryByText('installed')).toBeNull();
  });

  it('on: a CE-exclusive resource stays blocked', async () => {
    demoMode.on = true;
    mocks.getMarketplaceByType.mockResolvedValue({
      publications: [pub({ id: 'ce', title: 'Local Only Skill', ceExclusive: true })],
    });
    grid();
    const card = await cardFor('Local Only Skill');
    expect(within(card).queryByText('acquire')).toBeNull();
  });
});
