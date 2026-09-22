// @vitest-environment jsdom
/**
 * CE-exclusive on the standalone-resource grids (TABLE / INTERFACE / SKILL).
 *
 * A published table carrying a vector column is the resource-side case of the
 * same rule: managed cloud would clone it with the embedding column stripped,
 * so the grid must show the badge and withhold the Install button there, while
 * a self-hosted install keeps both.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ numericUserId: 5 }),
  // The card reads the demo-install mode, which asks for the ADMIN role.
  useOptionalAuth: () => ({ hasRole: () => false }),
}));
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

const MANAGED_CLOUD = { IS_MANAGED_CLOUD: true, IS_CLOUD: true, IS_CE: false };
const COMMUNITY_EDITION = { IS_MANAGED_CLOUD: false, IS_CLOUD: false, IS_CE: true };

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
    ownedByMe: false,
    ...overrides,
  } as WorkflowPublication;
}

/** Fresh module registry per edition: the edition constants are frozen at import time. */
async function renderGrid(edition: { IS_MANAGED_CLOUD: boolean; IS_CLOUD: boolean; IS_CE: boolean }) {
  vi.resetModules();
  vi.doMock('@/lib/edition', () => edition);
  const { ResourceMarketplaceGrid } = await import('../ResourceMarketplaceGrid');
  render(<ResourceMarketplaceGrid type="TABLE" icon={() => null} title="Tables" subtitle="sub" emptyText="empty" />);
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.getLandingSnapshot.mockResolvedValue({ landing: null });
  mocks.getMarketplaceByType.mockResolvedValue({
    publications: [
      pub({ id: 'rag', title: 'RAG Table', ceExclusive: true, ceExclusiveFeatures: ['VECTOR_SEARCH'] }),
      pub({ id: 'plain', title: 'Plain Table' }),
    ],
  });
});

afterEach(() => {
  cleanup();
  vi.doUnmock('@/lib/edition');
  vi.resetModules();
});

describe('ResourceMarketplaceGrid - CE-exclusive', () => {
  it('managed cloud: the CE-exclusive card shows the badge and loses its Install button', async () => {
    await renderGrid(MANAGED_CLOUD);
    await waitFor(() => expect(screen.getByText('RAG Table')).toBeInTheDocument());

    const card = screen.getByText('RAG Table').closest('div.group') as HTMLElement;
    expect(within(card).getByTestId('ce-exclusive-badge')).toBeInTheDocument();
    expect(within(card).queryByText('acquire')).toBeNull();
  });

  it('managed cloud: a normal card in the SAME grid keeps its Install button', async () => {
    await renderGrid(MANAGED_CLOUD);
    await waitFor(() => expect(screen.getByText('Plain Table')).toBeInTheDocument());

    const card = screen.getByText('Plain Table').closest('div.group') as HTMLElement;
    expect(within(card).queryByTestId('ce-exclusive-badge')).toBeNull();
    expect(within(card).getByText('acquire')).toBeInTheDocument();
  });

  it('self-hosted: the CE-exclusive card keeps the badge AND the Install button', async () => {
    await renderGrid(COMMUNITY_EDITION);
    await waitFor(() => expect(screen.getByText('RAG Table')).toBeInTheDocument());

    const card = screen.getByText('RAG Table').closest('div.group') as HTMLElement;
    expect(within(card).getByTestId('ce-exclusive-badge')).toBeInTheDocument();
    expect(within(card).getByText('acquire')).toBeInTheDocument();
  });
});
