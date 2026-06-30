// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent, within, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

/**
 * Applications page selection actions float in the bottom-center SelectionActionBar.
 * The provenance-dependent action set is the regression-prone bit:
 *  - a single OWN published app → Update + Share link + "Delete (n)" (unpublish).
 *  - an ACQUIRED app → "Remove (n)" only (no Update/Share).
 */

const mocks = vi.hoisted(() => ({
  getAcquiredApplicationsPage: vi.fn(),
  getMyPublicationsPage: vi.fn(),
  unpublishWorkflow: vi.fn(),
  getApplicationWorkflow: vi.fn(),
  getApplicationRunVersionBatch: vi.fn(),
  deleteWorkflow: vi.fn(),
  selectedIds: new Set<string>(),
  clear: vi.fn(),
  // Captures every props object the Update wizard (ShareWorkflowModal) is
  // rendered with, so the Update-action regression tests can assert which
  // workflowId is handed to the wizard.
  shareModalProps: [] as Array<Record<string, unknown>>,
}));

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ isLoading: false }) }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getAcquiredApplicationsPage: mocks.getAcquiredApplicationsPage,
    getMyPublicationsPage: mocks.getMyPublicationsPage,
    unpublishWorkflow: mocks.unpublishWorkflow,
    getApplicationWorkflow: mocks.getApplicationWorkflow,
    getFavoriteIds: () => Promise.resolve([]),
    addFavorite: () => Promise.resolve(),
    removeFavorite: () => Promise.resolve(),
  },
}));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getApplicationRunVersionBatch: mocks.getApplicationRunVersionBatch, deleteWorkflow: mocks.deleteWorkflow },
}));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => null }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/sharing/ShareLinkDialog', () => ({ ShareLinkDialog: () => null }));
vi.mock('@/components/workflow', () => ({
  ShareWorkflowModal: (props: Record<string, unknown>) => {
    mocks.shareModalProps.push(props);
    return null;
  },
}));
vi.mock('@/components/ui/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (sel: (s: { currentOrgId: string }) => unknown) => sel({ currentOrgId: 'org1' }),
}));
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({
    selectedIds: mocks.selectedIds,
    toggle: vi.fn(),
    clear: mocks.clear,
    selectAll: vi.fn(),
  }),
}));

import ApplicationsPage from '../page';

function renderPage() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <ApplicationsPage />
    </NextIntlClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  mocks.selectedIds = new Set<string>();
  mocks.shareModalProps = [];
});

describe('Applications page - selection actions float, provenance-aware', () => {
  it('a single OWN published app shows Update + Share link + unpublish in the bar', async () => {
    mocks.selectedIds = new Set<string>(['published-p1']);
    mocks.getMyPublicationsPage.mockResolvedValue({ items: [{ id: 'p1', title: 'Pub One', workflowId: 'wf1', status: 'ACTIVE' }], totalCount: 1 });
    mocks.getAcquiredApplicationsPage.mockResolvedValue({ items: [], totalCount: 0 });
    mocks.getApplicationRunVersionBatch.mockResolvedValue({});

    renderPage();
    const bar = await screen.findByTestId('selection-action-bar');
    expect(within(bar).getByText('1 selected')).toBeInTheDocument();
    expect(await within(bar).findByRole('button', { name: 'Update' })).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Share link' })).toBeInTheDocument();
    // Own published unpublish reads "Delete ({count})".
    expect(within(bar).getByRole('button', { name: 'Delete (1)' })).toBeInTheDocument();
  });

  it('Update opens the wizard with the publication source workflowId (not the APPLICATION instance)', async () => {
    mocks.selectedIds = new Set<string>(['published-p1']);
    mocks.getMyPublicationsPage.mockResolvedValue({ items: [{ id: 'p1', title: 'Pub One', workflowId: 'wf1', status: 'ACTIVE' }], totalCount: 1 });
    mocks.getAcquiredApplicationsPage.mockResolvedValue({ items: [], totalCount: 0 });
    mocks.getApplicationRunVersionBatch.mockResolvedValue({});

    renderPage();
    const bar = await screen.findByTestId('selection-action-bar');
    fireEvent.click(await within(bar).findByRole('button', { name: 'Update' }));

    // The wizard must receive pub.workflowId (the source workflow the publication
    // is keyed to), so getPublicationByWorkflowId resolves it into update mode.
    await waitFor(() => {
      const opened = mocks.shareModalProps.filter((p) => p.isOpen);
      expect(opened.at(-1)?.workflowId).toBe('wf1');
    });
    // The reported "No workflow available" bug was caused by routing Update
    // through getApplicationWorkflow (the runnable APPLICATION instance, absent
    // for un-acquired publisher apps). Update must NOT depend on it.
    expect(mocks.getApplicationWorkflow).not.toHaveBeenCalled();
  });

  it('Update still opens even when the APPLICATION instance is absent (regression: no "No workflow available")', async () => {
    mocks.selectedIds = new Set<string>(['published-p1']);
    mocks.getMyPublicationsPage.mockResolvedValue({ items: [{ id: 'p1', title: 'Pub One', workflowId: 'wf-source', status: 'ACTIVE' }], totalCount: 1 });
    mocks.getAcquiredApplicationsPage.mockResolvedValue({ items: [], totalCount: 0 });
    mocks.getApplicationRunVersionBatch.mockResolvedValue({});
    // Pre-fix path: the publisher's APPLICATION clone does not exist, so the old
    // code received null here and hard-failed. Now Update never calls this.
    mocks.getApplicationWorkflow.mockResolvedValue(null);

    renderPage();
    const bar = await screen.findByTestId('selection-action-bar');
    fireEvent.click(await within(bar).findByRole('button', { name: 'Update' }));

    await waitFor(() => {
      const opened = mocks.shareModalProps.filter((p) => p.isOpen);
      expect(opened.at(-1)?.workflowId).toBe('wf-source');
    });
    // No error toast surfaced.
    expect(screen.queryByText('Failed to load application')).not.toBeInTheDocument();
  });

  it('Update surfaces the error toast and does not open the wizard when the publication has no workflowId', async () => {
    mocks.selectedIds = new Set<string>(['published-p1']);
    // A published item with no workflowId (defensive guard branch).
    mocks.getMyPublicationsPage.mockResolvedValue({ items: [{ id: 'p1', title: 'Pub One', status: 'ACTIVE' }], totalCount: 1 });
    mocks.getAcquiredApplicationsPage.mockResolvedValue({ items: [], totalCount: 0 });
    mocks.getApplicationRunVersionBatch.mockResolvedValue({});

    renderPage();
    const bar = await screen.findByTestId('selection-action-bar');
    fireEvent.click(await within(bar).findByRole('button', { name: 'Update' }));

    expect(await screen.findByText('Failed to load application')).toBeInTheDocument();
    // The wizard never opened.
    expect(mocks.shareModalProps.some((p) => p.isOpen)).toBe(false);
  });

  it('an acquired app shows only "Remove (n)" - no Update / Share link', async () => {
    mocks.selectedIds = new Set<string>(['acquired-a1']);
    mocks.getMyPublicationsPage.mockResolvedValue({ items: [], totalCount: 0 });
    mocks.getAcquiredApplicationsPage.mockResolvedValue({
      items: [{ publication: { id: 'a1', title: 'Acq One' }, workflowId: 'cw1', acquiredAt: '2026-06-01T00:00:00Z' }],
      totalCount: 1,
    });
    mocks.getApplicationRunVersionBatch.mockResolvedValue({});

    renderPage();
    const bar = await screen.findByTestId('selection-action-bar');
    expect(await within(bar).findByRole('button', { name: 'Remove (1)' })).toBeInTheDocument();
    expect(within(bar).queryByRole('button', { name: 'Update' })).not.toBeInTheDocument();
    expect(within(bar).queryByRole('button', { name: 'Share link' })).not.toBeInTheDocument();
  });

  it('the bar × clears the selection', async () => {
    mocks.selectedIds = new Set<string>(['published-p1']);
    mocks.getMyPublicationsPage.mockResolvedValue({ items: [{ id: 'p1', title: 'Pub One', workflowId: 'wf1', status: 'ACTIVE' }], totalCount: 1 });
    mocks.getAcquiredApplicationsPage.mockResolvedValue({ items: [], totalCount: 0 });
    mocks.getApplicationRunVersionBatch.mockResolvedValue({});

    renderPage();
    const bar = await screen.findByTestId('selection-action-bar');
    fireEvent.click(within(bar).getByTestId('selection-action-bar-clear'));
    expect(mocks.clear).toHaveBeenCalledTimes(1);
  });
});
