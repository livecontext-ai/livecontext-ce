// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi, beforeEach } from 'vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

/**
 * Regression - pins the cloud-acquired card enrichment ("on voit l'application
 * créée mais pas les data").
 *
 * A CLOUD-acquired app surfaces on the applications page with only a MINIMAL synth
 * publication (remote=true, NO showcaseRunId/showcaseInterfaceId) because the source
 * publication lives on the cloud. Without enrichment `canPreview` is false and the
 * card shows the empty cover tile. The fix fetches the full cloud publication via the
 * remote by-id proxy and merges its showcase fields (keeping remote=true), so the card
 * renders the publisher's frozen showcase through the cloud-parity showcase-render.
 *
 * ShowcasePreview is a prop-capture mock: we assert the routing props the fix produces.
 */

const mocks = vi.hoisted(() => ({
  getAcquiredApplicationsPage: vi.fn(),
  getMyPublicationsPage: vi.fn(),
  getPublicationByIdPublic: vi.fn(),
  getApplicationRunVersionBatch: vi.fn(),
}));

const captured = vi.hoisted(() => ({ calls: [] as Array<Record<string, unknown>> }));

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
  workflowService: { getApplicationRunVersionBatch: mocks.getApplicationRunVersionBatch },
}));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({
  ShowcasePreview: (props: Record<string, unknown>) => {
    captured.calls.push(props);
    return null;
  },
}));
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
  captured.calls = [];
  // Default: no run/version metadata (fresh acquisitions). The batch is keyed by workflowId.
  mocks.getApplicationRunVersionBatch.mockResolvedValue({});
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('Applications page - cloud-acquired card enrichment', () => {
  it('enriches a remote acquired item from the cloud proxy and routes its preview remote', async () => {
    mocks.getMyPublicationsPage.mockResolvedValue({ items: [], totalCount: 0 });
    // Minimal synth as the backend returns it for a cloud acquisition.
    mocks.getAcquiredApplicationsPage.mockResolvedValue({
      items: [{
        publication: { id: 'cloud-pub', title: 'Cloud App', remote: true },
        workflowId: 'local-clone-wf',
        acquiredAt: '2026-06-01T00:00:00Z',
      }],
      totalCount: 1,
    });
    // Full cloud publication returned by the remote by-id proxy.
    mocks.getPublicationByIdPublic.mockResolvedValue({
      id: 'cloud-pub',
      title: 'Cloud App',
      publisherId: 'pub-x',
      showcaseRunId: 'showcase_run',
      showcaseInterfaceId: 'iface-9',
    });

    renderPage();

    // The fix fetches the cloud publication through the remote proxy (remote=true).
    await waitFor(() =>
      expect(mocks.getPublicationByIdPublic).toHaveBeenCalledWith('cloud-pub', true),
    );

    // The enriched (showcase-bearing) publication reaches the card, which renders the
    // showcase through the cloud proxy: publicationId set + remote=true.
    await waitFor(() => expect(captured.calls.length).toBeGreaterThan(0));
    const props = captured.calls[captured.calls.length - 1];
    expect(props.publicationId).toBe('cloud-pub');
    expect(props.remote).toBe(true);
    expect(props.interfaceId).toBe('iface-9');
  });

  it('on a cloud miss keeps the minimal synth card (no preview) and does not crash', async () => {
    mocks.getMyPublicationsPage.mockResolvedValue({ items: [], totalCount: 0 });
    mocks.getAcquiredApplicationsPage.mockResolvedValue({
      items: [{
        publication: { id: 'cloud-pub', title: 'Cloud App', remote: true },
        workflowId: 'local-clone-wf',
        acquiredAt: '2026-06-01T00:00:00Z',
      }],
      totalCount: 1,
    });
    mocks.getPublicationByIdPublic.mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));

    renderPage();

    await waitFor(() =>
      expect(mocks.getPublicationByIdPublic).toHaveBeenCalledWith('cloud-pub', true),
    );
    // The card still renders its title via the synth; no showcase preview mounts.
    expect(await screen.findByText('Cloud App')).toBeInTheDocument();
    expect(captured.calls.length).toBe(0);
  });

  it('aligns enrichment by index in a MIXED list (remote merge lands on the right card)', async () => {
    // Published item first (index 0, local), remote acquired second (index 1). The
    // enrichment maps positionally over items[], so an off-by-one would merge the cloud
    // showcase onto the wrong card. Each card carries a distinct interface id to catch it.
    mocks.getMyPublicationsPage.mockResolvedValue({
      items: [{
        id: 'local-pub', title: 'Local Pub', workflowId: 'wf-local', status: 'ACTIVE',
        showcaseRunId: 'run-local', showcaseInterfaceId: 'iface-local',
      }],
      totalCount: 1,
    });
    mocks.getAcquiredApplicationsPage.mockResolvedValue({
      items: [{
        publication: { id: 'cloud-pub', title: 'Cloud App', remote: true },
        workflowId: 'local-clone-wf',
        acquiredAt: '2026-06-01T00:00:00Z',
      }],
      totalCount: 1,
    });
    mocks.getPublicationByIdPublic.mockResolvedValue({
      id: 'cloud-pub', title: 'Cloud App',
      showcaseRunId: 'run-cloud', showcaseInterfaceId: 'iface-cloud',
    });

    renderPage();

    await waitFor(() => expect(captured.calls.length).toBeGreaterThanOrEqual(2));
    // Exactly one cloud round-trip, for the remote item only.
    expect(mocks.getPublicationByIdPublic).toHaveBeenCalledTimes(1);
    expect(mocks.getPublicationByIdPublic).toHaveBeenCalledWith('cloud-pub', true);

    const cloudCard = captured.calls.find((c) => c.publicationId === 'cloud-pub');
    const localCard = captured.calls.find((c) => c.interfaceId === 'iface-local');
    // The cloud showcase merged onto the cloud card (not the local one), routed remote.
    expect(cloudCard?.interfaceId).toBe('iface-cloud');
    expect(cloudCard?.remote).toBe(true);
    // The local published card kept its own showcase and stayed local.
    expect(localCard?.publicationId).toBeUndefined();
    expect(localCard?.remote).toBeFalsy();
  });

  it('does NOT call the remote proxy for a local (non-remote) acquired item', async () => {
    mocks.getMyPublicationsPage.mockResolvedValue({ items: [], totalCount: 0 });
    mocks.getAcquiredApplicationsPage.mockResolvedValue({
      items: [{
        publication: { id: 'local-pub', title: 'Local App', showcaseRunId: 'r', showcaseInterfaceId: 'i' },
        workflowId: 'wf',
        acquiredAt: '2026-06-01T00:00:00Z',
      }],
      totalCount: 1,
    });

    renderPage();

    await waitFor(() => expect(mocks.getAcquiredApplicationsPage).toHaveBeenCalled());
    // A local acquisition needs no cloud round-trip.
    expect(mocks.getPublicationByIdPublic).not.toHaveBeenCalled();
  });
});

describe('Applications page - run + pinned version come from ONE batched call', () => {
  it('calls getApplicationRunVersionBatch ONCE with the deduped workflowIds (no per-item run/version N+1)', async () => {
    // A published app (resolves to its own workflowId) + an acquired app (its clone workflowId).
    mocks.getMyPublicationsPage.mockResolvedValue({
      items: [{ id: 'pub-a', title: 'App A', workflowId: 'wf-a', status: 'ACTIVE' }],
      totalCount: 1,
    });
    mocks.getAcquiredApplicationsPage.mockResolvedValue({
      items: [{
        publication: { id: 'pub-b', title: 'App B' },
        workflowId: 'wf-b',
        acquiredAt: '2026-06-01T00:00:00Z',
      }],
      totalCount: 1,
    });
    mocks.getApplicationRunVersionBatch.mockResolvedValue({
      'wf-a': { applicationRunId: 'run-a', lastExecutedAt: '2026-06-02T00:00:00Z', pinnedVersion: 3 },
    });

    renderPage();

    // ONE batch call replaces the old two-HTTP-calls-per-card (getApplicationRun + listVersions) N+1...
    await waitFor(() => expect(mocks.getApplicationRunVersionBatch).toHaveBeenCalledTimes(1));
    // ...keyed by the resolved (deduped) workflowIds of every card.
    const calledWith = mocks.getApplicationRunVersionBatch.mock.calls[0][0] as string[];
    expect([...calledWith].sort()).toEqual(['wf-a', 'wf-b']);
  });
});
