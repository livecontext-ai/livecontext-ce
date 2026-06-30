/**
 * @vitest-environment jsdom
 *
 * Regression: two chat "visualize application" cards for the SAME app but
 * DIFFERENT executions (each `application:execute` creates a new run/epoch) must
 * open INDEPENDENT side-panel tabs. Pre-fix the tab id was keyed by
 * publicationId alone (`application-${pubId}`), so both cards drove one tab and
 * the second card showed the first card's run - both "settled on the same
 * epoch". The fix scopes the tab id by runId.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

const getPublicationByIdMock = vi.hoisted(() => vi.fn());
const openTabMock = vi.hoisted(() => vi.fn());

vi.mock('@/contexts/SidePanelContext', () => ({
  // isOpen:false → isTabActive is false → clicking always routes to openInPanel.
  useSidePanelSafe: () => ({ isOpen: false, activeTabId: null, openTab: openTabMock, removeTab: vi.fn(), close: vi.fn() }),
}));

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
  PreviewActionMenu: () => <div data-testid="menu" />,
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

const PUB = {
  id: 'pub-1',
  title: 'My App',
  publisherId: '999',
  status: 'ACTIVE',
  displayMode: 'APPLICATION',
  workflowId: 'wf-1',
  nodeIcons: [],
};

describe('ApplicationVisualizeCard - tab id is scoped by runId', () => {
  afterEach(() => {
    getPublicationByIdMock.mockReset();
    openTabMock.mockReset();
    cleanup();
  });

  it('opens a runId-scoped tab so each execution is independent', async () => {
    getPublicationByIdMock.mockResolvedValue(PUB);

    render(<ApplicationVisualizeCard publicationId="pub-1" runId="run-A" />);
    const card = await screen.findByText('My App', undefined, { timeout: 4000 });

    fireEvent.click(card);

    expect(openTabMock).toHaveBeenCalledTimes(1);
    expect(openTabMock.mock.calls[0][0].id).toBe('application-pub-1-run-A');
  });

  it('gives two runs of the same app DIFFERENT tab ids (the epoch-collapse bug)', async () => {
    getPublicationByIdMock.mockResolvedValue(PUB);

    const { rerender } = render(<ApplicationVisualizeCard publicationId="pub-1" runId="run-A" />);
    fireEvent.click(await screen.findByText('My App', undefined, { timeout: 4000 }));

    rerender(<ApplicationVisualizeCard publicationId="pub-1" runId="run-B" />);
    fireEvent.click(await screen.findByText('My App', undefined, { timeout: 4000 }));

    const idA = openTabMock.mock.calls[0][0].id;
    const idB = openTabMock.mock.calls[1][0].id;
    expect(idA).toBe('application-pub-1-run-A');
    expect(idB).toBe('application-pub-1-run-B');
    expect(idA).not.toBe(idB);
  });

  it('falls back to the legacy publication-only id when the card has no run', async () => {
    getPublicationByIdMock.mockResolvedValue(PUB);

    render(<ApplicationVisualizeCard publicationId="pub-1" />);
    fireEvent.click(await screen.findByText('My App', undefined, { timeout: 4000 }));

    expect(openTabMock.mock.calls[0][0].id).toBe('application-pub-1');
  });
});
