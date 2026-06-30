// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

/**
 * Favoriting from the /app/applications list. The card star calls the page's
 * handleToggleFavorite, which optimistically flips the local set and calls
 * add/removeFavorite, reverting + toasting on failure.
 */

const mocks = vi.hoisted(() => ({
  getAcquiredApplicationsPage: vi.fn(),
  getMyPublicationsPage: vi.fn(),
  getApplicationRunVersionBatch: vi.fn(),
  getFavoriteIds: vi.fn(),
  addFavorite: vi.fn(),
  removeFavorite: vi.fn(),
  // Native WORKFLOW favorites store (acquired apps). The page merges it with
  // publication favorites; left unmocked it hits the real apiClient and never resolves.
  wfGetFavoriteIds: vi.fn(),
  wfAddFavorite: vi.fn(),
  wfRemoveFavorite: vi.fn(),
}));

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ isLoading: false }) }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getAcquiredApplicationsPage: mocks.getAcquiredApplicationsPage,
    getMyPublicationsPage: mocks.getMyPublicationsPage,
    getFavoriteIds: mocks.getFavoriteIds,
    addFavorite: mocks.addFavorite,
    removeFavorite: mocks.removeFavorite,
  },
}));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getApplicationRunVersionBatch: mocks.getApplicationRunVersionBatch, deleteWorkflow: vi.fn() },
}));
vi.mock('@/lib/api/orchestrator/favorite.service', () => ({
  favoriteService: {
    getFavoriteIds: mocks.wfGetFavoriteIds,
    addFavorite: mocks.wfAddFavorite,
    removeFavorite: mocks.wfRemoveFavorite,
  },
}));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => null }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/sharing/ShareLinkDialog', () => ({ ShareLinkDialog: () => null }));
vi.mock('@/components/workflow', () => ({ ShareWorkflowModal: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (sel: (s: { currentOrgId: string }) => unknown) => sel({ currentOrgId: 'org1' }),
}));
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({ selectedIds: new Set<string>(), toggle: vi.fn(), clear: vi.fn(), selectAll: vi.fn() }),
}));

import ApplicationsPage from '../page';

function renderPage() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <ApplicationsPage />
    </NextIntlClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  // single published app, no favorites
  mocks.getMyPublicationsPage.mockResolvedValue({ items: [{ id: 'p1', title: 'Pub One', workflowId: 'wf1', status: 'ACTIVE', visibility: 'PUBLIC' }], totalCount: 1 });
  mocks.getAcquiredApplicationsPage.mockResolvedValue({ items: [], totalCount: 0 });
  mocks.getApplicationRunVersionBatch.mockResolvedValue({});
  mocks.getFavoriteIds.mockResolvedValue([]);
  mocks.addFavorite.mockResolvedValue(undefined);
  mocks.removeFavorite.mockResolvedValue(undefined);
  mocks.wfGetFavoriteIds.mockResolvedValue([]);
  mocks.wfAddFavorite.mockResolvedValue(undefined);
  mocks.wfRemoveFavorite.mockResolvedValue(undefined);
});

afterEach(() => cleanup());

describe('Applications page - favorite toggle', () => {
  it('clicking the star favorites the app: optimistic flip + addFavorite(id)', async () => {
    renderPage();
    await screen.findByText('Pub One');

    const star = await screen.findByRole('button', { name: 'Add to favorites' });
    fireEvent.click(star);

    expect(mocks.addFavorite).toHaveBeenCalledWith('p1');
    expect(mocks.removeFavorite).not.toHaveBeenCalled();
    // Optimistically becomes the "remove" action.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Remove from favorites' })).toBeInTheDocument());
  });

  it('starts from the server favorite state (already favorited → shows the remove action)', async () => {
    mocks.getFavoriteIds.mockResolvedValue(['p1']);
    renderPage();
    await screen.findByText('Pub One');
    expect(await screen.findByRole('button', { name: 'Remove from favorites' })).toBeInTheDocument();
  });

  it('a failed favorite reverts the optimistic state and toasts an error', async () => {
    mocks.addFavorite.mockRejectedValue(new Error('boom'));
    renderPage();
    await screen.findByText('Pub One');

    fireEvent.click(await screen.findByRole('button', { name: 'Add to favorites' }));

    // Reverts to the "add" action and surfaces the error toast.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add to favorites' })).toBeInTheDocument());
    expect(await screen.findByText('Could not update favorites')).toBeInTheDocument();
  });
});
