// @vitest-environment jsdom
/**
 * The resource marketplaces (skills / tables / interfaces) share the single-flight install
 * machine with the application marketplace. While an install is running ANYWHERE, an
 * Install click here would be dropped by the store with no visible effect, so the button
 * must refuse it up front and say why.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent, cleanup } from '@testing-library/react';
import { Zap } from 'lucide-react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ numericUserId: 5 }),
  // The card reads the demo-install mode, which asks for the ADMIN role.
  useOptionalAuth: () => ({ hasRole: () => false }),
}));
vi.mock('@/components/marketplace/InterfacePreview', () => ({ InterfacePreview: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({
  default: ({ isOpen, publication }: { isOpen: boolean; publication: { id: string } }) =>
    isOpen ? <div data-testid="acquire-modal" data-publication-id={publication.id} /> : null,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => null }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));

const mocks = vi.hoisted(() => ({
  getMarketplaceByType: vi.fn(),
  getLandingSnapshot: vi.fn(),
  acquireResourcePublication: vi.fn(),
  acquirePublication: vi.fn(),
  acquireAgentPublication: vi.fn(),
  acquireRemotePublication: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: mocks }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));

import { ResourceMarketplaceGrid } from '../ResourceMarketplaceGrid';
import { useMarketplaceInstallStore } from '@/lib/stores/marketplace-install-store';

function pub(overrides: Partial<WorkflowPublication>): WorkflowPublication {
  return {
    id: overrides.id ?? 'p',
    title: overrides.title ?? 'Pub',
    creditsPerUse: 0,
    publisherId: 'someone-else',
    status: 'ACTIVE',
    visibility: 'PUBLIC',
    ...overrides,
  } as WorkflowPublication;
}

function renderGrid() {
  return render(
    <ResourceMarketplaceGrid
      type="SKILL"
      icon={Zap}
      title="Skills"
      subtitle="Community skills"
      emptyText="none"
    />,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  useMarketplaceInstallStore.setState({ active: null });
  mocks.getLandingSnapshot.mockResolvedValue({ landing: null });
  mocks.getMarketplaceByType.mockResolvedValue({
    publications: [pub({ id: 'skill-1', title: 'First Skill' }), pub({ id: 'skill-2', title: 'Second Skill' })],
  });
  // Never resolves: keeps the store in 'installing' for the duration of a test.
  mocks.acquireResourcePublication.mockImplementation(() => new Promise(() => {}));
});

afterEach(() => {
  useMarketplaceInstallStore.getState().clear();
  cleanup();
});

describe('ResourceMarketplaceGrid - one install at a time', () => {
  it('disables the Install buttons and explains why while another publication installs', async () => {
    renderGrid();
    await screen.findByText('First Skill');

    useMarketplaceInstallStore.getState().startInstall(
      pub({ id: 'installing-elsewhere', title: 'Busy', publicationType: 'SKILL' }));

    await waitFor(() => {
      const buttons = screen.getAllByTestId('resource-card-acquire');
      expect(buttons[0]).toBeDisabled();
      expect(buttons[0]).toHaveAttribute('title', 'installBusy');
    });
  });

  it('a blocked click opens no acquire modal', async () => {
    renderGrid();
    await screen.findByText('First Skill');
    useMarketplaceInstallStore.getState().startInstall(
      pub({ id: 'installing-elsewhere', title: 'Busy', publicationType: 'SKILL' }));

    await waitFor(() => expect(screen.getAllByTestId('resource-card-acquire')[0]).toBeDisabled());
    fireEvent.click(screen.getAllByTestId('resource-card-acquire')[0]);

    expect(screen.queryByTestId('acquire-modal')).not.toBeInTheDocument();
  });

  it('leaves Install usable when nothing is installing', async () => {
    renderGrid();
    await screen.findByText('First Skill');

    const button = screen.getAllByTestId('resource-card-acquire')[0];
    expect(button).toBeEnabled();

    fireEvent.click(button);
    expect(screen.getByTestId('acquire-modal')).toHaveAttribute('data-publication-id', 'skill-1');
  });
});
