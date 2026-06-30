/**
 * @vitest-environment jsdom
 *
 * Regression: the chat "visualize application" card (rendered inside MessageHistory
 * via VisualizeBlock) must NOT offer an Install/acquire button - even for a
 * marketplace app the viewer does not own. Install lives only on the marketplace
 * and applications surfaces. Pre-removal this card rendered an "Install" button
 * for any non-owned ACTIVE publication.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

const getPublicationByIdMock = vi.hoisted(() => vi.fn());

vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({ isOpen: false, activeTabId: null, openTab: vi.fn(), removeTab: vi.fn(), close: vi.fn() }),
}));

// Viewer (42) is NOT the publisher (999) → pre-removal this satisfied `canInstall`.
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ numericUserId: 42 }),
}));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getPublicationById: getPublicationByIdMock, unpublishWorkflow: vi.fn() },
}));

vi.mock('@/components/app/ApplicationSidePanel', () => ({ ApplicationPanelContent: () => null }));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => <div /> }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => <div data-testid="node-icons" /> }));
vi.mock('@/components/chat/WorkflowRunBlock', () => ({ WorkflowRunBlock: () => <div /> }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <div /> }));
vi.mock('@/components/chat/PreviewActionMenu', () => ({
  PreviewActionMenu: ({ items }: { items: Array<{ id: string; label: string }> }) => (
    <div data-testid="menu">{items.map((i) => <span key={i.id}>{i.label}</span>)}</div>
  ),
  ActionIcons: { open: null, delete: null },
}));
vi.mock('@/components/chat/ConfirmDeleteModal', () => ({ ConfirmDeleteModal: () => null }));
vi.mock('@/components/chat/SimpleToast', () => ({ SimpleToast: () => null }));
vi.mock('@/hooks/useDeleteFlow', () => ({
  useDeleteFlow: () => ({
    isDeleted: false, showDeleteModal: false, isDeleting: false, toast: null,
    hideToast: vi.fn(), handleDeleteClick: vi.fn(), handleConfirmDelete: vi.fn(), handleCancelDelete: vi.fn(),
  }),
}));
vi.mock('@/lib/stores/app-run-autoopen-store', () => ({
  useAppRunAutoOpenStore: { getState: () => ({ consume: () => false }) },
}));

import { ApplicationVisualizeCard } from '@/components/chat/ApplicationVisualizeCard';

describe('ApplicationVisualizeCard - no Install on the chat visualize card', () => {
  afterEach(() => {
    getPublicationByIdMock.mockReset();
    cleanup();
  });

  it('does NOT render an Install button for a non-owned ACTIVE marketplace app', async () => {
    getPublicationByIdMock.mockResolvedValue({
      id: 'pub-1',
      title: 'Marketplace App',
      publisherId: '999', // viewer is 42 → not owned
      status: 'ACTIVE',
      displayMode: 'APPLICATION',
      nodeIcons: [],
    });

    render(<ApplicationVisualizeCard publicationId="pub-1" />);

    // Card finished loading (title shows in the footer).
    expect(await screen.findByText('Marketplace App', undefined, { timeout: 4000 })).toBeTruthy();

    // The Install affordance must be gone (it was a button labelled "Install").
    expect(screen.queryByRole('button', { name: /install/i })).toBeNull();
    expect(screen.queryByText('Install')).toBeNull();

    // The action menu is still present with Open (not Install).
    expect(screen.getByText('Open')).toBeTruthy();
  });
});
