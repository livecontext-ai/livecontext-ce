// @vitest-environment jsdom
/**
 * `/app/agent?openAgent=<id>` is the ONLY way to open one agent: there is no per-agent page,
 * `/app/agent/<id>` is a 404. Every caller routes through here (the conversation sidebar's
 * "go to agent", the notification rows, the global search, the agenda), so the contract this
 * pins is the one those links depend on.
 *
 * Two regressions (2026-09-16) live here:
 *  - the deep link only matched the agents ON SCREEN (one page of one folder), so a link to a
 *    filed agent did nothing at all: no panel, no message;
 *  - the "already handled" guard was never released, so using the same link a second time was
 *    a no-op - harmless for a notification row, wrong for a menu item clicked over and over.
 *
 * And one introduced while fixing those two: the by-id fetch was cancelled by the effect's
 * cleanup, which React runs on every DEPENDENCY change, not just on unmount. Any re-render
 * while the fetch was in flight threw the answer away AND left the claim taken, so the link
 * died more thoroughly than before. Cancellation now happens on unmount only.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React, { StrictMode } from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

const mocks = vi.hoisted(() => ({
  getAgentsPage: vi.fn(),
  getAgent: vi.fn(),
  openTab: vi.fn(),
}));

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
// URL-backed fake router: the deep link clears itself through the history API, and the
// component has to SEE that clearing to become usable again. A plain vi.fn() router would
// record the call and leave `useSearchParams` frozen on the old query, hiding the re-use bug.
vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: {
    getAgentsPage: mocks.getAgentsPage,
    getAgent: mocks.getAgent,
    getFleetTriggers: vi.fn().mockResolvedValue([]),
  },
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { unpublishAgent: vi.fn() },
}));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/components/agents/AvatarPicker', () => ({ AvatarDisplay: () => null }));
vi.mock('@/components/publications/PublicationStatusIcon', () => ({ PublicationStatusIcon: () => null }));
vi.mock('@/components/chat/CreateAgentModal', () => ({ CreateAgentModal: () => null }));
vi.mock('@/components/marketplace/PublishAgentModal', () => ({ default: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({
  AgentPanelContent: () => null,
  AGENT_CONFIGURATION_TAB: 'config',
}));
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({ openTab: mocks.openTab }),
}));
vi.mock('@/lib/api/orchestrator/resource-folder.service', () => ({
  resourceFolderService: {
    list: vi.fn().mockResolvedValue([]),
    create: vi.fn(),
    rename: vi.fn(),
    move: vi.fn(),
    remove: vi.fn(),
    assign: vi.fn(),
  },
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { cloneAgent: vi.fn(), deleteAgent: vi.fn() } }));
vi.mock('@/components/ui/CardSkeletonGrid', () => ({ CardSkeletonGrid: () => null }));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/components/templates/TemplateGallery', () => ({ TemplateGallery: () => null }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => true,
  useCurrentOrg: () => ({ currentOrgId: null }),
}));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/hooks/useResourceFavorites', () => ({
  useResourceFavorites: () => ({ favoriteIds: new Set(), toggleFavorite: vi.fn() }),
}));
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({
    selectedIds: new Set<string>(),
    toggle: vi.fn(),
    clear: vi.fn(),
    selectAll: vi.fn(),
  }),
}));
vi.mock('@dnd-kit/core', () => ({
  DndContext: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DragOverlay: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  MouseSensor: class {},
  TouchSensor: class {},
  pointerWithin: () => [],
  rectIntersection: () => [],
  useSensor: () => ({}),
  useSensors: () => [],
  useDroppable: () => ({ setNodeRef: () => {}, isOver: false }),
  useDraggable: () => ({ setNodeRef: () => {}, attributes: {}, listeners: {}, isDragging: false }),
}));

import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';
import { AgentTable } from '../AgentTable';

/** The one agent the list actually shows: top level, first page. */
function page() {
  return {
    items: [{ id: 'on-screen', name: 'Loose agent', updatedAt: '2026-06-01T00:00:00Z' }],
    totalCount: 1,
    page: 0,
    size: 25,
    publicationStatuses: {},
    folders: [],
    folderTrail: [],
  };
}

function renderTable() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <AgentTable />
    </NextIntlClientProvider>,
  );
}

/** Follow the deep link, the way every caller does: navigate to the list carrying the id. */
function followDeepLink(agentId: string) {
  fakeFolderRouter.navigate(`/en/app/agent?openAgent=${agentId}`);
}

const openedTabIds = () => mocks.openTab.mock.calls.map((call) => call[0].id);

beforeEach(() => {
  fakeFolderRouter.reset('/en/app/agent');
  mocks.getAgentsPage.mockResolvedValue(page());
  mocks.getAgent.mockResolvedValue({ id: 'filed-away', name: 'Filed agent' });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('AgentTable - the ?openAgent= deep link', () => {
  it('opens an agent that IS on the current page without asking the server for it again', async () => {
    renderTable();
    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());

    followDeepLink('on-screen');

    await waitFor(() => expect(openedTabIds()).toContain('agent-on-screen'));
    expect(mocks.getAgent).not.toHaveBeenCalled();
  });

  it('opens an agent that is NOT on the current page by fetching it by id', async () => {
    renderTable();
    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());

    // A filed agent, or one past row 25: never present in the loaded page.
    followDeepLink('filed-away');

    await waitFor(() => expect(mocks.getAgent).toHaveBeenCalledWith('filed-away'));
    await waitFor(() => expect(openedTabIds()).toContain('agent-filed-away'));
    // Claimed before the fetch starts, so the re-renders this effect sees cannot fire a second.
    expect(mocks.getAgent).toHaveBeenCalledTimes(1);
  });

  it('tells the user when the agent cannot be opened at all, instead of doing nothing', async () => {
    mocks.getAgent.mockRejectedValue(new Error('404 not found'));
    renderTable();
    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());

    followDeepLink('deleted-one');

    await waitFor(() => expect(screen.getByText('Agent not found')).toBeInTheDocument());
    // The message, not just the title: a missing or mistyped key would otherwise ship green.
    expect(screen.getByText(enMessages.emptyState.agent.openFailed)).toBeInTheDocument();
    expect(openedTabIds()).not.toContain('agent-deleted-one');
  });

  it('clears the id from the address, so a reload does not re-open the panel', async () => {
    renderTable();
    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());

    followDeepLink('on-screen');

    await waitFor(() => expect(fakeFolderRouter.search()).toBe(''));
    // Replaced, not pushed: the id was never a step the user chose to come back to.
    expect(fakeFolderRouter.navigations.at(-1)).toEqual({ url: '/en/app/agent', method: 'replace' });
  });

  it('works AGAIN for the same agent - the sidebar menu item is clicked repeatedly', async () => {
    renderTable();
    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());

    followDeepLink('on-screen');
    await waitFor(() => expect(openedTabIds()).toHaveLength(1));
    await waitFor(() => expect(fakeFolderRouter.search()).toBe(''));

    // The user closed the panel and clicked "go to agent" on the same conversation again.
    followDeepLink('on-screen');

    await waitFor(() => expect(openedTabIds()).toEqual(['agent-on-screen', 'agent-on-screen']));
  });

  it('works AGAIN on the fetched branch too, re-asking the server each time', async () => {
    renderTable();
    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());

    followDeepLink('filed-away');
    await waitFor(() => expect(openedTabIds()).toHaveLength(1));
    await waitFor(() => expect(fakeFolderRouter.search()).toBe(''));

    followDeepLink('filed-away');

    await waitFor(() => expect(openedTabIds()).toEqual(['agent-filed-away', 'agent-filed-away']));
    expect(mocks.getAgent).toHaveBeenCalledTimes(2);
  });

  it('survives a re-render while the fetch is in flight', async () => {
    // The effect re-runs on every list reload and on every side-panel update, so its cleanup
    // fires constantly. Cancelling the pending answer there lost it for good: the claim stayed
    // taken, so the re-run bailed out too and the user got nothing at all.
    let settle: (agent: { id: string; name: string }) => void = () => {};
    mocks.getAgent.mockReturnValue(new Promise((resolve) => { settle = resolve; }));

    const { rerender } = renderTable();
    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());

    followDeepLink('filed-away');
    await waitFor(() => expect(mocks.getAgent).toHaveBeenCalledWith('filed-away'));

    rerender(
      <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
        <AgentTable />
      </NextIntlClientProvider>,
    );
    settle({ id: 'filed-away', name: 'Filed agent' });

    await waitFor(() => expect(openedTabIds()).toContain('agent-filed-away'));
    await waitFor(() => expect(fakeFolderRouter.search()).toBe(''));
    expect(mocks.getAgent).toHaveBeenCalledTimes(1);
  });

  it('drops an answer that arrives after the list is gone', async () => {
    let settle: (agent: { id: string; name: string }) => void = () => {};
    mocks.getAgent.mockReturnValue(new Promise((resolve) => { settle = resolve; }));

    const { unmount } = renderTable();
    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());
    followDeepLink('filed-away');
    await waitFor(() => expect(mocks.getAgent).toHaveBeenCalled());

    unmount();
    settle({ id: 'filed-away', name: 'Filed agent' });
    await Promise.resolve();

    // The other half of the cancellation rule: an unmount DOES stop it.
    expect(openedTabIds()).toHaveLength(0);
  });

  it('stays usable when React remounts the effect and keeps the refs', async () => {
    // StrictMode mounts, unmounts and remounts. A mounted flag only ever cleared would be
    // false from then on and every answer would be thrown away for the rest of the session.
    render(
      <StrictMode>
        <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
          <AgentTable />
        </NextIntlClientProvider>
      </StrictMode>,
    );
    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());

    followDeepLink('filed-away');

    await waitFor(() => expect(openedTabIds()).toContain('agent-filed-away'));
  });

  it('waits for the list before deciding an agent is not on it', async () => {
    // The deep link can BE the entry URL, arriving while the first page is still loading.
    // Acting then would fetch by id an agent that was about to appear in the list.
    let settlePage: (value: unknown) => void = () => {};
    mocks.getAgentsPage.mockReturnValue(new Promise((resolve) => { settlePage = resolve; }));

    fakeFolderRouter.navigate('/en/app/agent?openAgent=on-screen');
    renderTable();
    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());

    expect(mocks.getAgent).not.toHaveBeenCalled();

    settlePage(page());

    await waitFor(() => expect(openedTabIds()).toContain('agent-on-screen'));
    expect(mocks.getAgent).not.toHaveBeenCalled();
  });

  it('keeps a folder the user opened while the fetch was in flight', async () => {
    // The address is rebuilt to drop `openAgent`. Rebuilt from a snapshot taken when the
    // fetch STARTED, it reverted whatever the user had navigated to since - and `?folder=` is
    // a real param of this very page, on the branch that means "the agent is in a folder".
    let settle: (agent: { id: string; name: string }) => void = () => {};
    mocks.getAgent.mockReturnValue(new Promise((resolve) => { settle = resolve; }));

    renderTable();
    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());
    followDeepLink('filed-away');
    await waitFor(() => expect(mocks.getAgent).toHaveBeenCalled());

    fakeFolderRouter.navigate('/en/app/agent?folder=f1');
    settle({ id: 'filed-away', name: 'Filed agent' });

    await waitFor(() => expect(openedTabIds()).toContain('agent-filed-away'));
    expect(fakeFolderRouter.search()).toBe('folder=f1');
  });

  it('leaves the rest of the address alone when it takes the id out', async () => {
    renderTable();
    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());

    fakeFolderRouter.navigate('/en/app/agent?folder=f1&openAgent=on-screen');

    await waitFor(() => expect(openedTabIds()).toContain('agent-on-screen'));
    // Only `openAgent` goes: taking the whole query would throw the user out of the folder.
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('folder=f1'));
  });

  it('ignores a slow answer once a DIFFERENT link has been followed', async () => {
    // Two rows clicked in a row (a search result list, a notification list) put two fetches in
    // flight. The claim cannot order them, since it is released as soon as the address clears,
    // so without a request id the slower FIRST answer opened last and stole the panel: the
    // user clicked the second agent and was shown the first.
    let settleSlow: (agent: { id: string; name: string }) => void = () => {};
    mocks.getAgent
      .mockImplementationOnce(() => new Promise((resolve) => { settleSlow = resolve; }))
      .mockImplementationOnce(() => Promise.resolve({ id: 'second', name: 'Second' }));

    renderTable();
    await waitFor(() => expect(mocks.getAgentsPage).toHaveBeenCalled());

    followDeepLink('slow-one');
    await waitFor(() => expect(mocks.getAgent).toHaveBeenCalledWith('slow-one'));

    followDeepLink('second');
    await waitFor(() => expect(openedTabIds()).toContain('agent-second'));

    settleSlow({ id: 'slow-one', name: 'Slow' });
    await Promise.resolve();
    await Promise.resolve();

    // The agent asked for LAST is the one on screen, and stays it.
    expect(openedTabIds()).toEqual(['agent-second']);
  });
});
