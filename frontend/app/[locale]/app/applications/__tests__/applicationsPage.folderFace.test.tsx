// @vitest-environment jsdom
/**
 * A folder tile on the applications page previews the apps it holds by RENDERING them, the
 * same live showcase their cards show. The tile itself carries only ids and names, so the
 * page is what hands the face the app rows - without that wiring every app in a folder drew
 * as its initial letter, which said nothing a folder tile does not already say.
 *
 * ShowcasePreview is a prop-capture mock: an app inside a folder is NOT among the cards at
 * that level, so any capture here comes from the folder face.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

const mocks = vi.hoisted(() => ({
  getAcquiredApplicationsPage: vi.fn(),
  getMyPublicationsPage: vi.fn(),
  getPublicationByIdPublic: vi.fn(),
  getApplicationRunVersionBatch: vi.fn(),
  folderList: vi.fn(),
  folderMemberships: vi.fn(),
}));

const captured = vi.hoisted(() => ({ calls: [] as Array<Record<string, unknown>> }));

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ isLoading: false }) }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getAcquiredApplicationsPage: mocks.getAcquiredApplicationsPage,
    getMyPublicationsPage: mocks.getMyPublicationsPage,
    getPublicationByIdPublic: mocks.getPublicationByIdPublic,
    getFavoriteIds: () => Promise.resolve([]),
    addFavorite: () => Promise.resolve(),
    removeFavorite: () => Promise.resolve(),
  },
}));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getApplicationRunVersionBatch: mocks.getApplicationRunVersionBatch, getWorkflowRelationsBatch: vi.fn().mockResolvedValue({}) },
}));
vi.mock('@/lib/api/orchestrator/resource-folder.service', () => ({
  resourceFolderService: {
    list: mocks.folderList,
    memberships: mocks.folderMemberships,
    create: vi.fn(),
    rename: vi.fn(),
    remove: vi.fn(),
    move: vi.fn(),
    assign: vi.fn(),
  },
}));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({
  ShowcasePreview: (props: Record<string, unknown>) => {
    captured.calls.push(props);
    return <div data-testid="showcase" data-run={String(props.runId ?? '')} />;
  },
}));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
// Stubbed for the same reason as WorkflowNodeIcons above: it renders NodeIcon,
// which pulls the whole builder module graph (and with it the real API facade)
// into a test that only mocks a few workflow-service methods.
vi.mock('@/components/NodeTypeFilter', () => ({ NodeTypeFilter: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/sharing/ShareLinkDialog', () => ({ ShareLinkDialog: () => null }));
vi.mock('@/components/workflow', () => ({ ShareWorkflowModal: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({
  EmptyState: ({ title }: { title?: string }) => <div data-testid="empty-state">{title}</div>,
}));
vi.mock('@/components/ui/PaginationBar', () => ({ PaginationBar: () => null }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (sel: (s: { currentOrgId: string }) => unknown) => sel({ currentOrgId: 'org1' }),
}));
vi.mock('@/hooks/useSelectableItems', () => ({
  useSelectableItems: () => ({ selectedIds: new Set<string>(), toggle: vi.fn(), clear: vi.fn(), selectAll: vi.fn() }),
}));


// A real element in place of the drag context, so a test can assert what is INSIDE it: the
// folder path has to be within the context that owns the drag or its crumbs are dead targets.
vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>();
  return {
    ...actual,
    DndContext: ({ children }: { children: React.ReactNode }) => (
      <div data-testid="drag-context">{children}</div>
    ),
    DragOverlay: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  };
});

import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';
import ApplicationsPage from '../page';

function renderPage() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <ApplicationsPage />
    </NextIntlClientProvider>,
  );
}

beforeEach(() => {
  fakeFolderRouter.reset();
  captured.calls = [];
  mocks.getApplicationRunVersionBatch.mockResolvedValue({});
  mocks.getAcquiredApplicationsPage.mockResolvedValue({ items: [], totalCount: 0 });
  mocks.folderList.mockResolvedValue([{ id: 'folder-1', name: 'Marketing', parentFolderId: null }]);
  mocks.folderMemberships.mockResolvedValue(new Map([['pub-filed', 'folder-1']]));
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('Applications page - the folder tile renders the apps it holds', () => {
  it('hands the face the filed app, so its tile shows the live showcase instead of a letter', async () => {
    mocks.getMyPublicationsPage.mockResolvedValue({
      items: [{
        id: 'pub-filed',
        title: 'Filed App',
        showcaseRunId: 'run-filed',
        showcaseInterfaceId: 'iface-filed',
        workflowId: 'wf-1',
        updatedAt: '2026-06-01T00:00:00Z',
      }],
      totalCount: 1,
    });

    renderPage();

    // The folder tile is there and the app is inside it, so no card is shown at this level.
    await waitFor(() => expect(screen.getByLabelText('Marketing')).toBeInTheDocument());
    await waitFor(() => expect(captured.calls).toHaveLength(1));
    expect(captured.calls[0].runId).toBe('run-filed');
    expect(captured.calls[0].interfaceId).toBe('iface-filed');
    // Own published app: rendered from its own run, not scoped to a publication.
    expect(captured.calls[0].publicationId).toBeUndefined();
    expect(captured.calls[0].mediaMuted).toBe(true);
  });

  it('does not say the page is empty while a folder is standing right there', async () => {
    // Every app filed away leaves the LEVEL empty, but not the page: the other four lists
    // already gate their empty state on their tiles.
    mocks.getMyPublicationsPage.mockResolvedValue({
      items: [{
        id: 'pub-filed',
        title: 'Filed App',
        showcaseRunId: 'run-filed',
        showcaseInterfaceId: 'iface-filed',
        workflowId: 'wf-1',
        updatedAt: '2026-06-01T00:00:00Z',
      }],
      totalCount: 1,
    });

    renderPage();

    await waitFor(() => expect(screen.getByLabelText('Marketing')).toBeInTheDocument());
    expect(screen.queryByTestId('empty-state')).not.toBeInTheDocument();
  });

  it('still says the page is empty when there is neither an app nor a folder', async () => {
    mocks.getMyPublicationsPage.mockResolvedValue({ items: [], totalCount: 0 });
    mocks.folderList.mockResolvedValue([]);
    mocks.folderMemberships.mockResolvedValue(new Map());

    renderPage();

    await waitFor(() => expect(screen.getByTestId('empty-state')).toBeInTheDocument());
  });

  it('prefers the live application run over the frozen showcase run, as the card does', async () => {
    mocks.getMyPublicationsPage.mockResolvedValue({
      items: [{
        id: 'pub-filed',
        title: 'Filed App',
        showcaseRunId: 'run-frozen',
        showcaseInterfaceId: 'iface-filed',
        workflowId: 'wf-1',
        updatedAt: '2026-06-01T00:00:00Z',
      }],
      totalCount: 1,
    });
    mocks.getApplicationRunVersionBatch.mockResolvedValue({
      'wf-1': { applicationRunId: 'run-live', pinnedVersion: 3, lastExecutedAt: '2026-06-02T00:00:00Z' },
    });

    renderPage();

    await waitFor(() => expect(captured.calls).toHaveLength(1));
    expect(captured.calls[0].runId).toBe('run-live');
  });
});

/**
 * A drop target only exists inside the context that owns the drag, and the folder path lives
 * in the page HEADER. With the drag context around the cards alone every crumb was inert, so
 * dragging a card out of a folder - the one move a folder path is there to offer - did nothing.
 */
describe('Applications page - the drag surface', () => {
  it('covers the folder path, so a card can be dragged out of a folder onto it', async () => {
    // Standing inside the folder, which is what puts the path on screen.
    fakeFolderRouter.reset('/en/app/applications');
    window.history.pushState(null, '', '/en/app/applications?folder=folder-1');
    mocks.getMyPublicationsPage.mockResolvedValue({ items: [], totalCount: 0 });

    renderPage();

    const crumb = await screen.findByRole('button', { name: 'All apps' });
    expect(screen.getByTestId('drag-context')).toContainElement(crumb);
  });
});

describe('Applications page - how many folder previews may run at once', () => {
  it('stops running live showcases past the level budget, so a folder-heavy page cannot mount dozens', async () => {
    // Five folders of four apps is twenty candidates, over the sixteen the level allows.
    const pubs = Array.from({ length: 20 }, (_, i) => ({
      id: `pub-${i}`,
      title: `App ${i}`,
      showcaseRunId: `run-${i}`,
      showcaseInterfaceId: `iface-${i}`,
      workflowId: `wf-${i}`,
      updatedAt: '2026-06-01T00:00:00Z',
    }));
    mocks.getMyPublicationsPage.mockResolvedValue({ items: pubs, totalCount: pubs.length });
    mocks.folderList.mockResolvedValue(
      Array.from({ length: 5 }, (_, f) => ({ id: `folder-${f}`, name: `Folder ${f}`, parentFolderId: null })),
    );
    mocks.folderMemberships.mockResolvedValue(
      new Map(pubs.map((p, i) => [p.id, `folder-${Math.floor(i / 4)}`])),
    );

    renderPage();

    await waitFor(() => expect(captured.calls.length).toBeGreaterThan(0));
    await waitFor(() => expect(screen.getAllByRole('button', { name: /^Folder / })).toHaveLength(5));
    // Exactly four whole faces, never part of a fifth: a face showing two running apps
    // beside two initials would read as two apps that failed to load.
    expect(captured.calls).toHaveLength(16);
  });
});
