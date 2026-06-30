// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent, within } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

/**
 * AgentTable selection actions float in the bottom-center SelectionActionBar, with the
 * same Share → Unshare → Pending-Review three-way as Interface/DataSource. The publish state
 * now reads straight off the page envelope (publicationStatuses) - no separate
 * getAllMyPublications sweep. Tested per-file (not via a "representative") because each surface
 * wires its own handlers + delete guard.
 */

const mocks = vi.hoisted(() => ({
  getAgentsPage: vi.fn(),
  getFleetTriggers: vi.fn(),
  cloneAgent: vi.fn(),
  deleteAgent: vi.fn(),
  clear: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/app/agent',
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: { cloneAgent: mocks.cloneAgent, deleteAgent: mocks.deleteAgent },
}));
vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: { getAgentsPage: mocks.getAgentsPage, getFleetTriggers: mocks.getFleetTriggers },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { unpublishAgent: vi.fn() },
}));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/components/publications/PublicationStatusIcon', () => ({ PublicationStatusIcon: () => null }));
vi.mock('@/components/chat/CreateAgentModal', () => ({ CreateAgentModal: () => null }));
vi.mock('@/components/marketplace/PublishAgentModal', () => ({ default: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({ AgentPanelContent: () => null, AGENT_CONFIGURATION_TAB: 'config' }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/components/ui/CardSkeletonGrid', () => ({ CardSkeletonGrid: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({
    selectedIds: new Set<string>(['a1']),
    toggle: vi.fn(),
    clear: mocks.clear,
    selectAll: vi.fn(),
  }),
}));

vi.mock('@/hooks/useResourceFavorites', () => ({
  useResourceFavorites: () => ({ favoriteIds: new Set(), toggleFavorite: vi.fn() }),
}));

import { AgentTable } from '../AgentTable';

function renderTable() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <AgentTable />
    </NextIntlClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

// Publication status now ships inline on the page envelope (publicationStatuses), so the bar's
// publish state machine reads it straight from the list page - no separate getAllMyPublications sweep.
const oneAgent = (publicationStatuses: Record<string, { status: string }> = {}) => {
  mocks.getAgentsPage.mockResolvedValue({
    items: [{ id: 'a1', name: 'Agent One', isPublic: false, isActive: true, modelProvider: 'openai', modelName: 'gpt' }],
    totalCount: 1, page: 0, size: 25, publicationStatuses,
  });
  mocks.getFleetTriggers.mockResolvedValue([]);
};

describe('AgentTable - selection actions float + publish state machine in the bar', () => {
  it('an unpublished selection shows Clone + Share + Delete in the bar', async () => {
    oneAgent();
    renderTable();

    const bar = await screen.findByTestId('selection-action-bar');
    expect(within(bar).getByText('1 selected')).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Clone (1)' })).toBeInTheDocument();
    expect(await within(bar).findByRole('button', { name: 'Share' })).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Delete (1)' })).toBeInTheDocument();
  });

  it('a published selection swaps Share → Unshare', async () => {
    oneAgent({ a1: { status: 'ACTIVE' } });
    renderTable();

    const bar = await screen.findByTestId('selection-action-bar');
    expect(await within(bar).findByRole('button', { name: 'Unshare' })).toBeInTheDocument();
    expect(within(bar).queryByRole('button', { name: 'Share' })).not.toBeInTheDocument();
  });

  it('a pending-review selection shows a disabled "Pending Review" action', async () => {
    oneAgent({ a1: { status: 'PENDING_REVIEW' } });
    renderTable();

    const bar = await screen.findByTestId('selection-action-bar');
    expect(await within(bar).findByRole('button', { name: 'Pending Review' })).toBeDisabled();
  });

  it('the bar × clears the selection', async () => {
    oneAgent();
    renderTable();

    const bar = await screen.findByTestId('selection-action-bar');
    fireEvent.click(within(bar).getByTestId('selection-action-bar-clear'));
    expect(mocks.clear).toHaveBeenCalledTimes(1);
  });
});
