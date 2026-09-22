// @vitest-environment jsdom
/**
 * Marketplace page ↔ marketplace-install store wiring (2026-07 install-flow
 * redesign). The install machine lives in the shared store; this pins how the
 * page consumes it:
 *  - the card whose publication is INSTALLING receives installProgress (and
 *    keeps it through the 'success' window at 100%), other cards receive none;
 *  - on success the page flips the card to installed/"Open"
 *    (openHref=/app/applications/{id}, APPLICATION display mode only),
 *    refetches the acquired set, then CONSUMES the success from the store;
 *  - terminal errors re-mount the AcquirePublicationModal (inlineProgress) so
 *    the dedicated error screens surface on the marketplace page - including
 *    for installs started from the preview header;
 *  - NON-inline installs (ChatCore's full-modal flow) are ignored end-to-end:
 *    no card progress, no error re-mount, no success consumption - the two
 *    consumers must never fight over the shared state.
 * Mock harness mirrors MarketplacePage.ceCloudParity.test.tsx; the STORE is
 * real (its service dependency resolves to the mock below).
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ActiveMarketplaceInstall } from '@/lib/stores/marketplace-install-store';

// The type filter is a Radix Select; jsdom has none of what it reaches for.
beforeAll(() => {
  (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  (Element.prototype as unknown as { scrollIntoView: () => void }).scrollIntoView = () => {};
  (Element.prototype as unknown as { hasPointerCapture: () => boolean }).hasPointerCapture = () => false;
  (Element.prototype as unknown as { setPointerCapture: () => void }).setPointerCapture = () => {};
  (Element.prototype as unknown as { releasePointerCapture: () => void }).releasePointerCapture = () => {};
});

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
// The tab / type filter are query-param backed (useQueryParamState), so tests
// drive them by mutating this state instead of clicking chips.
const searchParamsState = vi.hoisted(() => ({ params: new URLSearchParams() }));
const routerMock = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }));
vi.mock('next/navigation', () => ({
  useSearchParams: () => searchParamsState.params,
  usePathname: () => '/app/marketplace',
  useRouter: () => routerMock,
}));

/**
 * A refinement is a change of ADDRESS on the page already on screen, so it goes through the
 * history API rather than the router: returning a select to its fallback removes the last
 * query param, and a router replace of the bare pathname is dropped when the page was loaded
 * at it - so on a page opened directly on `?type=agents`, clearing the filter did nothing.
 */
const historyReplace = vi.fn();
const realReplaceState = window.history.replaceState;
vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn().mockResolvedValue(undefined) }),
}));
vi.mock('@/lib/api/cloud-link.service', () => ({
  cloudLinkService: { getAuthUrl: vi.fn(), connect: vi.fn() },
}));
// Capture the org-reset callbacks so tests can simulate a workspace switch
// (the real hook fires them when the active organization changes).
const orgResetCallbacks = vi.hoisted(() => ({ list: [] as Array<() => void> }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({
  useOrgScopedReset: (cb: () => void) => {
    orgResetCallbacks.list.push(cb);
  },
}));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: vi.fn() }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isLoading: false, isAuthenticated: true, numericUserId: 7 }),
}));
// Cloud edition: the Explore tab reads the LOCAL marketplace endpoint (the
// install-store wiring under test is edition-independent).
vi.mock('@/lib/edition', () => ({ IS_CE: false }));
vi.mock('@/hooks/useCeCloudLinkStatus', () => ({
  useCeCloudLinkStatus: () => ({ status: null, isLoading: false, isCloudLinked: false, isInstallCloudLinked: false }),
}));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));

const orchestratorApiMock = vi.hoisted(() => ({
  getMarketplacePublications: vi.fn(),
  searchPublications: vi.fn(),
  getMyPublications: vi.fn(),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: orchestratorApiMock }));

const publicationServiceMock = vi.hoisted(() => ({
  getAcquiredApplications: vi.fn(),
  getPurchases: vi.fn(),
  getRemoteMarketplacePublications: vi.fn(),
  searchRemotePublications: vi.fn(),
  // store dependencies (never hit in these tests, but the real store imports them)
  acquirePublication: vi.fn(),
  acquireAgentPublication: vi.fn(),
  acquireResourcePublication: vi.fn(),
  acquireRemotePublication: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: publicationServiceMock,
}));

vi.mock('@/components/marketplace/CategoryFilter', () => ({
  CategoryFilter: () => <div data-testid="category-filter" />,
}));

vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({
  default: ({ isOpen, inlineProgress, publication }: { isOpen: boolean; inlineProgress?: boolean; publication: { id: string } }) =>
    isOpen ? (
      <div data-testid="acquire-modal" data-inline={String(!!inlineProgress)} data-publication-id={publication.id} />
    ) : null,
}));

vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: (props: {
    publication: { id: string; title: string };
    isAcquired?: boolean;
    installProgress?: number | null;
    installBlocked?: boolean;
    openHref?: string;
    onAcquire?: (p: unknown) => void;
  }) => (
    <div
      data-testid="publication-card"
      data-publication-id={props.publication.id}
      data-is-acquired={String(!!props.isAcquired)}
      data-install-progress={props.installProgress ?? ''}
      data-install-blocked={String(!!props.installBlocked)}
      data-open-href={props.openHref ?? ''}
    >
      {props.publication.title}
    </div>
  ),
  PublicationCardSkeleton: () => <div data-testid="card-skeleton" />,
}));

import MarketplacePage from '../page';
import { useMarketplaceInstallStore } from '@/lib/stores/marketplace-install-store';

const APP_PUB = {
  id: 'pub-app-1',
  title: 'Wired App',
  displayMode: 'APPLICATION',
  publicationType: 'WORKFLOW',
  creditsPerUse: 0,
  publisherId: '999',
};

function activeInstall(overrides: Partial<ActiveMarketplaceInstall> = {}): ActiveMarketplaceInstall {
  return {
    publication: APP_PUB as never,
    ceMode: false,
    demo: false,
    inline: true,
    status: 'installing',
    progress: 37,
    acquiredId: null,
    error: null,
    resources: {},
    withEditableCopy: false,
    editableCopyWorkflowId: null,
    editableCopyFailed: false,
    ...overrides,
  };
}

/** Picks a resource type through the Explore type select. */
async function pickType(label: RegExp): Promise<void> {
  fireEvent.click(screen.getByRole('combobox', { name: 'filterByType' }));
  fireEvent.click(await screen.findByRole('option', { name: label }));
}

function card(pubId: string): HTMLElement {
  const match = screen
    .getAllByTestId('publication-card')
    .find((el) => el.getAttribute('data-publication-id') === pubId);
  if (!match) throw new Error(`no card for ${pubId}`);
  return match;
}

const AGENT_PUB = {
  id: 'pub-agent-1',
  title: 'Wired Agent',
  displayMode: 'AGENT',
  publicationType: 'AGENT',
  creditsPerUse: 0,
  publisherId: '999',
};

beforeEach(() => {
  historyReplace.mockClear();
  window.history.replaceState = ((_d: unknown, _u: string, url?: string) =>
    historyReplace(url)) as unknown as typeof window.history.replaceState;
  vi.clearAllMocks();
  searchParamsState.params = new URLSearchParams();
  orgResetCallbacks.list = [];
  useMarketplaceInstallStore.setState({ active: null });
  orchestratorApiMock.getMarketplacePublications.mockResolvedValue({
    publications: [APP_PUB, { ...APP_PUB, id: 'pub-app-2', title: 'Other App' }],
  });
  publicationServiceMock.getAcquiredApplications.mockResolvedValue({ applications: [] });
  publicationServiceMock.getPurchases.mockResolvedValue({ purchases: [] });
});

afterEach(() => {
  window.history.replaceState = realReplaceState;
  cleanup();
  useMarketplaceInstallStore.setState({ active: null });
});

describe('ExploreTab - inline install wiring', () => {
  it('passes installProgress ONLY to the installing card (and keeps it during the success window)', async () => {
    useMarketplaceInstallStore.setState({ active: activeInstall({ progress: 37 }) });

    render(<MarketplacePage />);
    await screen.findByText('Wired App');

    expect(card('pub-app-1')).toHaveAttribute('data-install-progress', '37');
    expect(card('pub-app-2')).toHaveAttribute('data-install-progress', '');
    // No modal while installing inline - the card is the progress surface.
    expect(screen.queryByTestId('acquire-modal')).not.toBeInTheDocument();
  });

  it('on success: flips the card to installed with the /app/applications open link, refetches, then consumes the store', async () => {
    useMarketplaceInstallStore.setState({
      active: activeInstall({ status: 'success', progress: 100, acquiredId: 'wf-1' }),
    });

    render(<MarketplacePage />);
    await screen.findByText('Wired App');

    await waitFor(() => {
      expect(card('pub-app-1')).toHaveAttribute('data-is-acquired', 'true');
    });
    expect(card('pub-app-1')).toHaveAttribute('data-open-href', '/app/applications/pub-app-1');
    // The non-installed sibling keeps the plain state.
    expect(card('pub-app-2')).toHaveAttribute('data-is-acquired', 'false');
    expect(card('pub-app-2')).toHaveAttribute('data-open-href', '');
    // Acquired set refreshed, then the success consumed (store released).
    // getPurchases is called once on mount and once by the success effect -
    // assert the effect's refetch happened.
    await waitFor(() => {
      expect(publicationServiceMock.getPurchases.mock.calls.length).toBeGreaterThanOrEqual(2);
    });
    await waitFor(() => {
      expect(useMarketplaceInstallStore.getState().active).toBeNull();
    });
  });

  it('a terminal inline error re-mounts the acquire modal (error surface) even with no local acquire target', async () => {
    useMarketplaceInstallStore.setState({
      active: activeInstall({ status: 'error', error: 'boom' }),
    });

    render(<MarketplacePage />);
    await screen.findByText('Wired App');

    const modal = screen.getByTestId('acquire-modal');
    expect(modal).toHaveAttribute('data-inline', 'true');
    expect(modal).toHaveAttribute('data-publication-id', 'pub-app-1');
  });


  it('a DEMO success never marks the card installed: nothing was acquired', async () => {
    // The optimistic "just installed" set outlives the demo toggle, so recording a
    // rehearsal there would make the card claim an install that never happened the
    // moment the mode is switched off.
    useMarketplaceInstallStore.setState({
      active: activeInstall({ demo: true, status: 'success', progress: 100, acquiredId: null }),
    });

    render(<MarketplacePage />);
    await screen.findByText('Wired App');

    // The success is still consumed, so the card leaves its progress state.
    await waitFor(() => {
      expect(useMarketplaceInstallStore.getState().active).toBeNull();
    });
    expect(card('pub-app-1')).toHaveAttribute('data-is-acquired', 'false');
    expect(card('pub-app-1')).toHaveAttribute('data-open-href', '');
  });
  it('ignores NON-inline installs entirely (ChatCore owns them): no card progress, no error modal, no consumption', async () => {
    useMarketplaceInstallStore.setState({
      active: activeInstall({ inline: false, status: 'error', error: 'chat flow error' }),
    });

    render(<MarketplacePage />);
    await screen.findByText('Wired App');

    expect(card('pub-app-1')).toHaveAttribute('data-install-progress', '');
    expect(screen.queryByTestId('acquire-modal')).not.toBeInTheDocument();

    // Same for a non-inline success: the page must not consume it.
    useMarketplaceInstallStore.setState({
      active: activeInstall({ inline: false, status: 'success', progress: 100, acquiredId: 'wf-x' }),
    });
    // Give effects a chance to (wrongly) run.
    await new Promise((r) => setTimeout(r, 50));
    expect(useMarketplaceInstallStore.getState().active?.status).toBe('success');
    expect(card('pub-app-1')).toHaveAttribute('data-is-acquired', 'false');
  });

  it('consuming a success never kills an install started during the refetch (audit D2)', async () => {
    // Install A's success triggers a refetch whose finally consumes the store.
    // Hold that refetch open, start install B in the meantime, then resolve:
    // B's machine must survive (clear() instead of consumeSuccess would kill it).
    let resolveRefetch: (v: unknown) => void = () => {};
    publicationServiceMock.getPurchases
      .mockResolvedValueOnce({ purchases: [] }) // initial mount fetch
      .mockImplementationOnce(() => new Promise((r) => { resolveRefetch = r; })); // success-effect refetch, held open
    useMarketplaceInstallStore.setState({
      active: activeInstall({ status: 'success', progress: 100, acquiredId: 'wf-1' }),
    });

    render(<MarketplacePage />);
    await screen.findByText('Wired App');
    await waitFor(() => {
      expect(publicationServiceMock.getPurchases.mock.calls.length).toBe(2);
    });

    // Install B starts while A's refetch is still in flight.
    useMarketplaceInstallStore.setState({
      active: activeInstall({
        publication: { ...APP_PUB, id: 'pub-app-2', title: 'Other App' } as never,
        status: 'installing',
        progress: 12,
      }),
    });

    await act(async () => {
      resolveRefetch({ applications: [{ sourcePublicationId: 'pub-app-1', workflowId: 'wf-1' }] });
      await new Promise((r) => setTimeout(r, 20));
    });

    const survivor = useMarketplaceInstallStore.getState().active;
    expect(survivor?.publication.id).toBe('pub-app-2');
    expect(survivor?.status).toBe('installing');
  });

  it('on success: the summary names what the install created, and SURVIVES the store being consumed', async () => {
    // Without this screen the workspace silently gains interfaces / tables / agents that
    // the user never sees mentioned. It is held in local state on purpose: the store
    // entry is dropped moments later (consumeSuccess) and the user must still be reading it.
    useMarketplaceInstallStore.setState({
      active: activeInstall({
        status: 'success',
        progress: 100,
        acquiredId: 'wf-1',
        resources: { interfaces: 2, tables: 1 },
      }),
    });

    render(<MarketplacePage />);
    await screen.findByText('Wired App');

    expect(await screen.findByText('installedTitle')).toBeInTheDocument();
    expect(screen.getByText('installedInterfaces')).toBeInTheDocument();
    expect(screen.getByText('installedTables')).toBeInTheDocument();

    await waitFor(() => {
      expect(useMarketplaceInstallStore.getState().active).toBeNull();
    });
    expect(screen.getByText('installedTitle')).toBeInTheDocument();
  });

  it('the summary is dismissible and does not come back on later store activity (no re-open loop)', async () => {
    useMarketplaceInstallStore.setState({
      active: activeInstall({ status: 'success', progress: 100, acquiredId: 'wf-1' }),
    });

    render(<MarketplacePage />);
    await screen.findByText('installedTitle');
    await waitFor(() => expect(useMarketplaceInstallStore.getState().active).toBeNull());

    fireEvent.click(screen.getByRole('button', { name: 'close' }));
    expect(screen.queryByText('installedTitle')).not.toBeInTheDocument();

    // A NEW install starting must not resurrect the dismissed summary.
    act(() => {
      useMarketplaceInstallStore.setState({ active: activeInstall({ progress: 5 }) });
    });
    act(() => {
      useMarketplaceInstallStore.setState({ active: activeInstall({ progress: 40 }) });
    });
    expect(screen.queryByText('installedTitle')).not.toBeInTheDocument();
  });

  it('a success state that keeps being rewritten summarises ONCE (the update must not re-enter its own effect)', async () => {
    // The success effect SETS state, so it must not schedule itself again on every render:
    // a fresh summary object per run turns render -> effect -> render into an infinite loop
    // that freezes the page with no error. Here the store keeps handing back an equal-but-new
    // success entry; the page must settle instead of spinning.
    useMarketplaceInstallStore.setState({
      active: activeInstall({ status: 'success', progress: 100, acquiredId: 'wf-1' }),
    });

    render(<MarketplacePage />);
    await screen.findByText('installedTitle');

    for (let i = 0; i < 5; i++) {
      act(() => {
        useMarketplaceInstallStore.setState({
          active: activeInstall({ status: 'success', progress: 100, acquiredId: 'wf-1' }),
        });
      });
    }

    // Exactly one summary, and the page is still responsive enough to answer.
    expect(screen.getAllByText('installedTitle')).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'close' }));
    expect(screen.queryByText('installedTitle')).not.toBeInTheDocument();
  });

  it('blocks Install on every OTHER card while one publication is installing', async () => {
    // The install machine is single-flight, so a second Install would be dropped with no
    // visible effect - the cards must refuse it up front instead.
    useMarketplaceInstallStore.setState({ active: activeInstall({ progress: 20 }) });

    render(<MarketplacePage />);
    await screen.findByText('Wired App');

    expect(card('pub-app-1')).toHaveAttribute('data-install-blocked', 'false');
    expect(card('pub-app-2')).toHaveAttribute('data-install-blocked', 'true');
  });

  it('does not block anything once the install reaches a terminal state', async () => {
    useMarketplaceInstallStore.setState({
      active: activeInstall({ status: 'error', error: 'boom' }),
    });

    render(<MarketplacePage />);
    await screen.findByText('Wired App');

    expect(card('pub-app-2')).toHaveAttribute('data-install-blocked', 'false');
  });

  it('an org switch drops the install summary with the rest of the workspace state', async () => {
    useMarketplaceInstallStore.setState({
      active: activeInstall({ status: 'success', progress: 100, acquiredId: 'wf-1' }),
    });

    render(<MarketplacePage />);
    await screen.findByText('installedTitle');

    act(() => {
      orgResetCallbacks.list.forEach((cb) => cb());
    });

    // The summary describes what landed in the PREVIOUS workspace.
    expect(screen.queryByText('installedTitle')).not.toBeInTheDocument();
  });

  it('an org switch clears any in-flight install (audit D6)', async () => {
    useMarketplaceInstallStore.setState({ active: activeInstall({ progress: 20 }) });

    render(<MarketplacePage />);
    await screen.findByText('Wired App');
    expect(useMarketplaceInstallStore.getState().active).not.toBeNull();

    act(() => {
      orgResetCallbacks.list.forEach((cb) => cb());
    });

    expect(useMarketplaceInstallStore.getState().active).toBeNull();
  });
});

describe('MyPurchasesTab - inline install wiring', () => {
  const PURCHASE = {
    publicationId: 'pub-app-1',
    hasActiveWorkflow: false,
    publication: { ...APP_PUB, status: 'ACTIVE' },
  };

  // The active tab lives in the URL now, and the mocked router does not
  // navigate, so a click on the tab button would leave the page on Explore -
  // where the same publication also renders, silently testing the wrong tab.
  // Deep-link instead; the click-writes-the-URL half is covered in the query-param suite below.
  async function openPurchasesTab() {
    searchParamsState.params = new URLSearchParams('tab=purchases');
    render(<MarketplacePage />);
    await screen.findByText('Wired App');
  }

  it('passes installProgress to the reinstalling purchase card', async () => {
    publicationServiceMock.getPurchases.mockResolvedValue({ purchases: [PURCHASE] });
    useMarketplaceInstallStore.setState({ active: activeInstall({ progress: 55 }) });

    await openPurchasesTab();

    expect(card('pub-app-1')).toHaveAttribute('data-install-progress', '55');
    expect(screen.queryByTestId('acquire-modal')).not.toBeInTheDocument();
  });

  it('a reinstall gets the SAME summary treatment as a fresh install (it re-creates the whole resource set)', async () => {
    // This tab duplicates ExploreTab's success wiring, so it needs its own pin: a
    // reinstall clones every interface, table and agent again and must say so.
    publicationServiceMock.getPurchases.mockResolvedValue({ purchases: [PURCHASE] });
    useMarketplaceInstallStore.setState({
      active: activeInstall({
        status: 'success',
        progress: 100,
        acquiredId: 'wf-1',
        resources: { interfaces: 1, tables: 2 },
      }),
    });

    await openPurchasesTab();

    expect(await screen.findByText('installedTitle')).toBeInTheDocument();
    expect(screen.getByText('installedInterfaces')).toBeInTheDocument();
    expect(screen.getByText('installedTables')).toBeInTheDocument();
  });

  it('blocks Install on the OTHER purchase cards while one reinstall runs', async () => {
    publicationServiceMock.getPurchases.mockResolvedValue({
      purchases: [
        PURCHASE,
        { ...PURCHASE, publicationId: 'pub-app-2', publication: { ...APP_PUB, id: 'pub-app-2', title: 'Other App' } },
      ],
    });
    useMarketplaceInstallStore.setState({ active: activeInstall({ progress: 20 }) });

    await openPurchasesTab();

    expect(card('pub-app-1')).toHaveAttribute('data-install-blocked', 'false');
    expect(card('pub-app-2')).toHaveAttribute('data-install-blocked', 'true');
  });

  it('an installed APPLICATION purchase exposes the /app/applications open link', async () => {
    publicationServiceMock.getPurchases.mockResolvedValue({
      purchases: [{ ...PURCHASE, hasActiveWorkflow: true }],
    });

    await openPurchasesTab();

    expect(card('pub-app-1')).toHaveAttribute('data-is-acquired', 'true');
    expect(card('pub-app-1')).toHaveAttribute('data-open-href', '/app/applications/pub-app-1');
  });

  it('a terminal inline error re-mounts the acquire modal on the purchases tab', async () => {
    publicationServiceMock.getPurchases.mockResolvedValue({ purchases: [PURCHASE] });
    useMarketplaceInstallStore.setState({ active: activeInstall({ status: 'error', error: 'boom' }) });

    await openPurchasesTab();

    const modal = screen.getByTestId('acquire-modal');
    expect(modal).toHaveAttribute('data-inline', 'true');
    expect(modal).toHaveAttribute('data-publication-id', 'pub-app-1');
  });

  it('on success: refetches the purchases then consumes the store', async () => {
    publicationServiceMock.getPurchases.mockResolvedValue({
      purchases: [{ ...PURCHASE, hasActiveWorkflow: true }],
    });
    useMarketplaceInstallStore.setState({
      active: activeInstall({ status: 'success', progress: 100, acquiredId: 'wf-1' }),
    });

    await openPurchasesTab();

    // Initial tab fetch + the success effect's refetch.
    await waitFor(() => {
      expect(publicationServiceMock.getPurchases.mock.calls.length).toBeGreaterThanOrEqual(2);
    });
    await waitFor(() => {
      expect(useMarketplaceInstallStore.getState().active).toBeNull();
    });
    expect(card('pub-app-1')).toHaveAttribute('data-open-href', '/app/applications/pub-app-1');
  });
});

describe('ExploreTab - installed badge covers every publication type', () => {
  // Regression: the installed set was built from getAcquiredApplications, which
  // lists acquired APPLICATION workflows only. Installing an AGENT produces no
  // APPLICATION clone, so its publication id was never in the set and the card
  // kept offering "Install" after a successful install. The set now comes from
  // the receipts (getPurchases), which are written for every type.
  beforeEach(() => {
    searchParamsState.params = new URLSearchParams('type=agents');
    orchestratorApiMock.getMarketplacePublications.mockResolvedValue({
      publications: [AGENT_PUB, { ...AGENT_PUB, id: 'pub-agent-2', title: 'Other Agent' }],
    });
  });

  it('marks an installed AGENT publication as acquired from its receipt', async () => {
    publicationServiceMock.getPurchases.mockResolvedValue({
      purchases: [{
        publicationId: 'pub-agent-1',
        creditsPaid: 0,
        acquiredAt: '2026-07-20T00:00:00Z',
        // APPLICATION-only field: false for an agent, and deliberately NOT the
        // signal we key on - keying on it would reintroduce the same blind spot.
        hasActiveWorkflow: false,
        publication: { id: 'pub-agent-1', displayMode: 'AGENT' },
      }],
    });

    render(<MarketplacePage />);
    await screen.findByText('Wired Agent');

    await waitFor(() => {
      expect(card('pub-agent-1')).toHaveAttribute('data-is-acquired', 'true');
    });
    // The un-installed sibling is untouched.
    expect(card('pub-agent-2')).toHaveAttribute('data-is-acquired', 'false');
  });

  it('an untyped receipt (publisher hard-deleted the publication) is skipped, not guessed', async () => {
    // Without the nested publication we cannot tell a clone-backed type from an
    // agent, so the receipt defaults to WORKFLOW and takes the conservative
    // clone-backed path. Such a publication is delisted anyway, so the only
    // visible effect is that we never invent an Installed badge for it.
    publicationServiceMock.getPurchases.mockResolvedValue({
      purchases: [{
        publicationId: 'pub-agent-1',
        creditsPaid: 0,
        acquiredAt: '2026-07-20T00:00:00Z',
        hasActiveWorkflow: false,
        publication: null,
      }],
    });

    render(<MarketplacePage />);
    await screen.findByText('Wired Agent');

    await waitFor(() => {
      expect(publicationServiceMock.getPurchases).toHaveBeenCalled();
    });
    expect(card('pub-agent-1')).toHaveAttribute('data-is-acquired', 'false');
  });

  it('an agent with no receipt stays installable', async () => {
    publicationServiceMock.getPurchases.mockResolvedValue({ purchases: [] });

    render(<MarketplacePage />);
    await screen.findByText('Wired Agent');

    await waitFor(() => {
      expect(publicationServiceMock.getPurchases).toHaveBeenCalled();
    });
    expect(card('pub-agent-1')).toHaveAttribute('data-is-acquired', 'false');
  });
});

describe('ExploreTab - applications keep their live-clone semantics', () => {
  // The receipt is permanent: it survives deleting the installed clone and it
  // is not filtered by the org per-member restriction deny-list. So an
  // APPLICATION must NOT be marked installed from its receipt alone, or a
  // deleted clone would show "Installed" with an Open link pointing at nothing.
  // /publications/acquired is derived from the live clone and applies the
  // deny-list, so applications stay on it.
  it('Regression - a receipt WITHOUT a live clone does not mark an application installed', async () => {
    publicationServiceMock.getAcquiredApplications.mockResolvedValue({ applications: [] });
    publicationServiceMock.getPurchases.mockResolvedValue({
      purchases: [{
        publicationId: 'pub-app-1',
        creditsPaid: 0,
        acquiredAt: '2026-07-20T00:00:00Z',
        hasActiveWorkflow: false,
        publication: { id: 'pub-app-1', displayMode: 'APPLICATION' },
      }],
    });

    render(<MarketplacePage />);
    await screen.findByText('Wired App');

    await waitFor(() => {
      expect(publicationServiceMock.getAcquiredApplications).toHaveBeenCalled();
    });
    expect(card('pub-app-1')).toHaveAttribute('data-is-acquired', 'false');
    expect(card('pub-app-1')).toHaveAttribute('data-open-href', '');
  });

  it('an application with a live clone is still marked installed', async () => {
    publicationServiceMock.getAcquiredApplications.mockResolvedValue({
      applications: [{ sourcePublicationId: 'pub-app-1', workflowId: 'wf-1' }],
    });

    render(<MarketplacePage />);
    await screen.findByText('Wired App');

    await waitFor(() => {
      expect(card('pub-app-1')).toHaveAttribute('data-is-acquired', 'true');
    });
    expect(card('pub-app-1')).toHaveAttribute('data-open-href', '/app/applications/pub-app-1');
  });
});

describe('Marketplace - tab and type filter survive a round trip (query params)', () => {
  // Regression: both were plain useState, so opening an agent and coming back
  // remounted the page on Explore/Applications and the agent was off screen.
  it('renders the agent grid straight from ?type=agents', async () => {
    searchParamsState.params = new URLSearchParams('type=agents');
    // The type is a QUERY, so the mock answers it the way the backend does:
    // asking for AGENT is what returns only the agent. A component that ignored
    // the deep link would ask for APPLICATION and get the app instead.
    orchestratorApiMock.getMarketplacePublications.mockImplementation(
      async (_page: number, _size: number, _category?: string, refinements?: { displayMode?: string }) => {
        const all = [AGENT_PUB, APP_PUB];
        const rows = refinements?.displayMode
          ? all.filter((p) => p.displayMode === refinements.displayMode)
          : all;
        return { publications: rows, count: rows.length };
      });

    render(<MarketplacePage />);

    await screen.findByText('Wired Agent');
    expect(screen.queryByText('Wired App')).not.toBeInTheDocument();
    expect(orchestratorApiMock.getMarketplacePublications).toHaveBeenCalledWith(
      0, expect.any(Number), undefined, expect.objectContaining({ displayMode: 'AGENT' }));
  });

  it('writes the selected type into the URL so a return trip can restore it', async () => {
    orchestratorApiMock.getMarketplacePublications.mockResolvedValue({
      publications: [APP_PUB],
    });

    render(<MarketplacePage />);
    await screen.findByText('Wired App');

    await pickType(/agents/i);

    expect(historyReplace).toHaveBeenCalledWith(
      '/app/marketplace?type=agents');
  });
});

describe('Marketplace - tab is query-param backed too', () => {
  it('opens My Purchases straight from ?tab=purchases', async () => {
    searchParamsState.params = new URLSearchParams('tab=purchases');
    publicationServiceMock.getPurchases.mockResolvedValue({ purchases: [] });

    render(<MarketplacePage />);

    // The purchases tab owns this fetch; Explore's grid must not be showing.
    await waitFor(() => {
      expect(publicationServiceMock.getPurchases).toHaveBeenCalled();
    });
    expect(screen.queryByText('Wired App')).not.toBeInTheDocument();
  });

  it('tab and type coexist without clobbering each other', async () => {
    searchParamsState.params = new URLSearchParams('tab=explore&type=agents');
    orchestratorApiMock.getMarketplacePublications.mockResolvedValue({
      publications: [AGENT_PUB],
    });

    render(<MarketplacePage />);
    await screen.findByText('Wired Agent');

    // Selecting the fallback tab drops only `tab` and preserves `type`.
    fireEvent.click(screen.getByText('tabExplore'));
    expect(historyReplace).toHaveBeenCalledWith(
      '/app/marketplace?type=agents');
  });

  it('clicking My Purchases writes ?tab=purchases', async () => {
    orchestratorApiMock.getMarketplacePublications.mockResolvedValue({
      publications: [APP_PUB],
    });

    render(<MarketplacePage />);
    await screen.findByText('Wired App');

    fireEvent.click(screen.getByText('tabMyPurchases'));

    expect(historyReplace).toHaveBeenCalledWith(
      '/app/marketplace?tab=purchases');
  });

  it('selecting the default type drops the param instead of writing type=apps', async () => {
    searchParamsState.params = new URLSearchParams('type=agents');
    orchestratorApiMock.getMarketplacePublications.mockResolvedValue({
      publications: [AGENT_PUB],
    });

    render(<MarketplacePage />);
    await screen.findByText('Wired Agent');

    await pickType(/applications/i);

    expect(historyReplace).toHaveBeenCalledWith('/app/marketplace');
  });
});
