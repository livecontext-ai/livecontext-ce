// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

/**
 * Wiring between the template gallery and AgentTable's create modal.
 *
 * Selecting an agent template does NOT create anything: it prefills the create
 * modal. That introduces a piece of state (`templateAgent`) which must never
 * leak into a different intent, so the transitions pinned here are the ones
 * that would silently hand a user the wrong starting configuration:
 *   - template -> "Create agent" must give a BLANK modal
 *   - template -> editing a real agent must show THAT agent, not the template
 */

const mocks = vi.hoisted(() => ({
  getAgentsPage: vi.fn(),
  getFleetTriggers: vi.fn(),
  saveWorkflowPlan: vi.fn(),
  // Mutable: the edit action lives in the selection bar, which only renders
  // when exactly one agent is selected.
  selectedIds: new Set<string>(),
}));

vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/app/agent',
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    cloneAgent: vi.fn(),
    deleteAgent: vi.fn(),
    saveWorkflowPlan: mocks.saveWorkflowPlan,
  },
}));
vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: { getAgentsPage: mocks.getAgentsPage, getFleetTriggers: mocks.getFleetTriggers },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { unpublishAgent: vi.fn() },
}));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/components/publications/PublicationStatusIcon', () => ({ PublicationStatusIcon: () => null }));
vi.mock('@/components/chat/CreateAgentModal', () => ({
  // Echoes whichever agent the modal received, so a leaked prefill is visible.
  CreateAgentModal: ({ agent }: { agent?: { name?: string } }) =>
    agent ? <div data-testid="prefilled-modal">{agent.name}</div> : <div data-testid="blank-modal" />,
}));
vi.mock('@/components/marketplace/PublishAgentModal', () => ({ default: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({
  AgentPanelContent: () => null,
  AGENT_CONFIGURATION_TAB: 'config',
}));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/components/ui/CardSkeletonGrid', () => ({ CardSkeletonGrid: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => true,
  useCurrentOrg: () => ({ currentOrgId: null }),
}));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({
    selectedIds: mocks.selectedIds,
    toggle: vi.fn(),
    clear: vi.fn(),
    selectAll: vi.fn(),
  }),
}));
vi.mock('@/hooks/useResourceFavorites', () => ({
  useResourceFavorites: () => ({ favoriteIds: new Set(), toggleFavorite: vi.fn() }),
}));

import { AgentTable } from '../AgentTable';

const TEMPLATE_TITLE = enMessages.templates.agent.basicAssistant.title;

function renderTable() {
  mocks.getAgentsPage.mockResolvedValue({
    items: [
      { id: 'a1', name: 'Agent One', isPublic: false, isActive: true, modelProvider: 'openai', modelName: 'gpt' },
    ],
    totalCount: 1,
    page: 0,
    size: 25,
    publicationStatuses: {},
  });
  mocks.getFleetTriggers.mockResolvedValue([]);
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <AgentTable />
    </NextIntlClientProvider>,
  );
}

async function clickIt(element: HTMLElement) {
  await act(async () => {
    fireEvent.click(element);
  });
}

/** Opens the templates modal, then triggers the card's action button. */
async function useTemplate() {
  await clickIt(await screen.findByRole('button', { name: enMessages.templates.gallery.browse }));
  const button = await screen.findByRole('button', {
    name: enMessages.templates.gallery.use.replace('{name}', TEMPLATE_TITLE),
  });
  await clickIt(button);
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  mocks.selectedIds = new Set<string>();
});

describe('AgentTable - template gallery wiring', () => {
  it('offers a templates button in the header, without showing the cards', async () => {
    renderTable();
    expect(
      await screen.findByRole('button', { name: enMessages.templates.gallery.browse }),
    ).toBeInTheDocument();
    expect(screen.queryByText(TEMPLATE_TITLE)).toBeNull();
  });

  it('opens the create modal PREFILLED with the template, creating nothing', async () => {
    renderTable();
    await useTemplate();

    const modal = await screen.findByTestId('prefilled-modal');
    expect(modal).toHaveTextContent(TEMPLATE_TITLE);
    expect(screen.queryByTestId('blank-modal')).toBeNull();
  });

  it('gives a BLANK modal when "Create agent" is used after a template', async () => {
    renderTable();
    await useTemplate();
    await screen.findByTestId('prefilled-modal');

    // Close, then take the plain create path.
    const createButton = await screen.findByRole('button', {
      name: enMessages.emptyState.agent.createButton,
    });
    await clickIt(createButton);

    // A stale prefill here would silently hand the user the template again.
    await waitFor(() => expect(screen.getByTestId('blank-modal')).toBeInTheDocument());
    expect(screen.queryByTestId('prefilled-modal')).toBeNull();
  });

  /**
   * Pins the PRECEDENCE, which is the real guarantee here.
   *
   * `agent={editingAgent || templateAgent || undefined}` means a live agent
   * always wins over a pending template prefill. `openEditModal` also clears
   * `templateAgent`, but that is belt and braces: this test passes with or
   * without it, and saying so is more useful than pretending otherwise.
   * What must never regress is the ordering, which is what is asserted.
   */
  it('gives editing a real agent precedence over a pending template prefill', async () => {
    mocks.selectedIds = new Set<string>(['a1']);
    renderTable();
    await useTemplate();
    await screen.findByTestId('prefilled-modal');

    const editButton = await screen.findByRole('button', {
      name: enMessages.common.update,
    });
    await clickIt(editButton);

    const modal = await screen.findByTestId('prefilled-modal');
    expect(modal).toHaveTextContent('Agent One');
    expect(modal).not.toHaveTextContent(TEMPLATE_TITLE);
  });
});
