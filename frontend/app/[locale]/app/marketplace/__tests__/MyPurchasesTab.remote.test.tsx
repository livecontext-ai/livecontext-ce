// @vitest-environment jsdom
/**
 * Regression - cloud purchases ("My Purchases") show their showcase preview.
 *
 * A CLOUD purchase on a cloud-linked CE arrives from `/publications/purchases`
 * as a MINIMAL synth publication (remote=true, no showcaseRunId/showcaseInterfaceId)
 * because the source publication lives on the cloud. Without enrichment the
 * PublicationCard has no showcase to render. The fix fetches the full cloud
 * publication via the remote by-id proxy and merges its showcase fields (keeping
 * remote=true), and passes `remote` to the card so its showcase read routes through
 * the cloud proxy. Local purchases need neither the round-trip nor the remote flag.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
vi.mock('next/navigation', () => ({ useSearchParams: () => new URLSearchParams() }));
vi.mock('@tanstack/react-query', () => ({ useQueryClient: () => ({ invalidateQueries: vi.fn() }) }));
vi.mock('@/lib/api/cloud-link.service', () => ({ cloudLinkService: { getAuthUrl: vi.fn(), connect: vi.fn() } }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isLoading: false, isAuthenticated: true, numericUserId: 7 }),
}));
vi.mock('@/lib/edition', () => ({ IS_CE: true }));
vi.mock('@/hooks/useCeCloudLinkStatus', () => ({
  useCeCloudLinkStatus: () => ({ status: null, isLoading: false, isCloudLinked: true }),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/components/marketplace/CategoryFilter', () => ({ CategoryFilter: () => null }));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({ default: () => null }));

const svc = vi.hoisted(() => ({
  getAcquiredApplications: vi.fn(),
  getPurchases: vi.fn(),
  getPublicationByIdPublic: vi.fn(),
  getRemoteMarketplacePublications: vi.fn(),
  searchRemotePublications: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: svc }));

const wfSvc = vi.hoisted(() => ({ getApplicationRunVersionBatch: vi.fn().mockResolvedValue({}) }));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({ workflowService: wfSvc }));

const captured = vi.hoisted(() => ({ calls: [] as Array<Record<string, unknown>> }));
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: (props: Record<string, unknown>) => {
    captured.calls.push(props);
    return <div data-testid="publication-card" />;
  },
  PublicationCardSkeleton: () => <div data-testid="card-skeleton" />,
}));

import { MyPurchasesTab } from '../page';

const lastCardPropsFor = (id: string) =>
  [...captured.calls].reverse().find(
    (c) => (c.publication as { id?: string } | undefined)?.id === id,
  );

beforeEach(() => {
  captured.calls = [];
  svc.getPurchases.mockReset();
  svc.getPublicationByIdPublic.mockReset();
  wfSvc.getApplicationRunVersionBatch.mockReset();
  wfSvc.getApplicationRunVersionBatch.mockResolvedValue({});
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('MyPurchasesTab - cloud purchase enrichment', () => {
  it('enriches a remote purchase from the cloud proxy and routes the card remote', async () => {
    svc.getPurchases.mockResolvedValue({
      purchases: [{
        publicationId: 'cloud-pub',
        hasActiveWorkflow: true,
        publication: { id: 'cloud-pub', title: 'Cloud Buy', status: 'ACTIVE', remote: true },
      }],
    });
    svc.getPublicationByIdPublic.mockResolvedValue({
      id: 'cloud-pub',
      title: 'Cloud Buy',
      showcaseRunId: 'showcase_run',
      showcaseInterfaceId: 'iface-42',
    });

    render(<MyPurchasesTab remote />);

    await waitFor(() =>
      expect(svc.getPublicationByIdPublic).toHaveBeenCalledWith('cloud-pub', true),
    );
    await waitFor(() => expect(lastCardPropsFor('cloud-pub')).toBeTruthy());

    const props = lastCardPropsFor('cloud-pub')!;
    expect(props.remote).toBe(true);
    // Enriched showcase fields reached the card (was absent in the synth pre-fix).
    expect((props.publication as { showcaseInterfaceId?: string }).showcaseInterfaceId).toBe('iface-42');
  });

  it('on a cloud miss keeps the synth (cover tile) and still marks the card remote', async () => {
    svc.getPurchases.mockResolvedValue({
      purchases: [{
        publicationId: 'cloud-pub',
        hasActiveWorkflow: true,
        publication: { id: 'cloud-pub', title: 'Cloud Buy', status: 'ACTIVE', remote: true },
      }],
    });
    svc.getPublicationByIdPublic.mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));

    render(<MyPurchasesTab remote />);

    await waitFor(() => expect(svc.getPublicationByIdPublic).toHaveBeenCalled());
    await waitFor(() => expect(lastCardPropsFor('cloud-pub')).toBeTruthy());

    const props = lastCardPropsFor('cloud-pub')!;
    // No crash; falls back to the synth publication but still routes remote.
    expect(props.remote).toBe(true);
    expect((props.publication as { showcaseInterfaceId?: string }).showcaseInterfaceId).toBeUndefined();
  });

  it('does NOT call the cloud proxy for a LOCAL purchase and leaves the card non-remote', async () => {
    svc.getPurchases.mockResolvedValue({
      purchases: [{
        publicationId: 'local-pub',
        hasActiveWorkflow: true,
        publication: { id: 'local-pub', title: 'Local Buy', status: 'ACTIVE' },
      }],
    });

    render(<MyPurchasesTab remote />);

    await waitFor(() => expect(lastCardPropsFor('local-pub')).toBeTruthy());
    expect(svc.getPublicationByIdPublic).not.toHaveBeenCalled();
    const props = lastCardPropsFor('local-pub')!;
    expect(props.remote).toBeFalsy();
  });

  it('skips a purchase whose publication was removed (null) without error', async () => {
    svc.getPurchases.mockResolvedValue({
      purchases: [{ publicationId: 'gone', hasActiveWorkflow: false, publication: null }],
    });

    render(<MyPurchasesTab remote />);

    await waitFor(() => expect(svc.getPurchases).toHaveBeenCalled());
    expect(svc.getPublicationByIdPublic).not.toHaveBeenCalled();
    expect(lastCardPropsFor('gone')).toBeFalsy();
  });

  // Every My-Purchases card is a receipt-holder, so it must carry `acquired` -> the card
  // routes its showcase render through the receipt-gated AUTH'D endpoint and previews even
  // after the publisher deleted the source (status INACTIVE). Without this the card hits the
  // anonymous /by-id render and 403s "Publication is not publicly available". Fails pre-fix
  // (the page never passed `acquired`).
  it('passes acquired=true to a purchase card whose publisher deleted the source (INACTIVE)', async () => {
    svc.getPurchases.mockResolvedValue({
      purchases: [{
        publicationId: 'deleted-pub',
        hasActiveWorkflow: true,
        publication: {
          id: 'deleted-pub', title: 'Place Explorer', status: 'INACTIVE',
          displayMode: 'APPLICATION', showcaseRunId: 'r1', showcaseInterfaceId: 'i1',
        },
      }],
    });

    render(<MyPurchasesTab />);

    await waitFor(() => expect(lastCardPropsFor('deleted-pub')).toBeTruthy());
    expect(svc.getPublicationByIdPublic).not.toHaveBeenCalled(); // local purchase, no cloud round-trip
    const props = lastCardPropsFor('deleted-pub')!;
    expect(props.acquired).toBe(true);
    expect(props.remote).toBeFalsy();
  });

  // A1 - a CLOUD purchase backed by a LOCAL clone resolves the clone's preview run (one batched
  // call) and wires it onto the synth, so the card renders the acquirer's OWN clone locally -
  // immune to the cloud publisher deleting the source. Cloud-FIRST: an ACTIVE source keeps the
  // populated showcase; only a cloud miss (deleted/unpublished) falls back to the local clone.
  const localClonePurchase = () => ({
    publicationId: 'cloud-pub',
    hasActiveWorkflow: true,
    publication: {
      id: 'cloud-pub', title: 'Cloud App', status: 'ACTIVE', remote: true,
      localShowcase: true, acquiredWorkflowId: 'clone-wf', showcaseInterfaceId: 'local-iface',
    },
  });

  it('A1: cloud source still ACTIVE → keeps the cloud showcase, no local-clone fallback', async () => {
    svc.getPurchases.mockResolvedValue({ purchases: [localClonePurchase()] });
    svc.getPublicationByIdPublic.mockResolvedValue({ id: 'cloud-pub', showcaseRunId: 'cloud-run', showcaseInterfaceId: 'cloud-iface' });

    render(<MyPurchasesTab remote />);

    await waitFor(() => expect(svc.getPublicationByIdPublic).toHaveBeenCalled());
    await waitFor(() => expect(lastCardPropsFor('cloud-pub')).toBeTruthy());
    const props = lastCardPropsFor('cloud-pub')!;
    expect((props.publication as { showcaseRunId?: string }).showcaseRunId).toBe('cloud-run');
    expect((props.publication as { localShowcase?: boolean }).localShowcase).toBe(false); // fallback disabled
    expect(wfSvc.getApplicationRunVersionBatch).not.toHaveBeenCalled();
  });

  it('A1: cloud source DELETED → falls back to the LOCAL clone run (rendered per-run locally)', async () => {
    svc.getPurchases.mockResolvedValue({ purchases: [localClonePurchase()] });
    svc.getPublicationByIdPublic.mockRejectedValue(Object.assign(new Error('gone'), { status: 410 }));
    wfSvc.getApplicationRunVersionBatch.mockResolvedValue({ 'clone-wf': { applicationRunId: 'local-run' } });

    render(<MyPurchasesTab remote />);

    await waitFor(() => expect(wfSvc.getApplicationRunVersionBatch).toHaveBeenCalledWith(['clone-wf']));
    await waitFor(() => expect(lastCardPropsFor('cloud-pub')).toBeTruthy());
    const props = lastCardPropsFor('cloud-pub')!;
    expect((props.publication as { showcaseRunId?: string }).showcaseRunId).toBe('local-run');
    expect((props.publication as { localShowcase?: boolean }).localShowcase).toBe(true);
  });

  it('A1: cloud source DELETED + clone has no run yet → keeps the synth (cover tile)', async () => {
    svc.getPurchases.mockResolvedValue({ purchases: [localClonePurchase()] });
    svc.getPublicationByIdPublic.mockRejectedValue(new Error('gone'));
    wfSvc.getApplicationRunVersionBatch.mockResolvedValue({ 'clone-wf': { applicationRunId: null } });

    render(<MyPurchasesTab remote />);

    await waitFor(() => expect(lastCardPropsFor('cloud-pub')).toBeTruthy());
    const props = lastCardPropsFor('cloud-pub')!;
    expect((props.publication as { showcaseRunId?: string }).showcaseRunId).toBeUndefined();
  });
});

/**
 * Reinstall gate. Every My-Purchases row is a receipt holder, so the reinstall
 * button (PublicationCard.onAcquire) must mirror the backend's receipt-holder
 * re-acquire rule: allowed for ANY status except REJECTED, only when the user
 * has no active clone. The old gate required status === 'ACTIVE', which wrongly
 * hid the button after the publisher deleted the app (status -> INACTIVE) and
 * the user removed their clone - leaving no way to reinstall an owned app.
 */
describe('MyPurchasesTab - reinstall gate (canReinstall)', () => {
  const purchase = (status: string, hasActiveWorkflow: boolean) => ({
    publicationId: 'p1',
    hasActiveWorkflow,
    publication: {
      id: 'p1', title: 'Place Explorer', status,
      displayMode: 'APPLICATION', showcaseRunId: 'r1', showcaseInterfaceId: 'i1',
    },
  });

  it('shows the reinstall button for a publisher-deleted (INACTIVE) app the user no longer has installed', async () => {
    // Fails pre-fix: canReinstall required status === 'ACTIVE', so a deleted
    // (INACTIVE) pub never offered a reinstall once the clone was removed.
    svc.getPurchases.mockResolvedValue({ purchases: [purchase('INACTIVE', false)] });
    render(<MyPurchasesTab />);
    await waitFor(() => expect(lastCardPropsFor('p1')).toBeTruthy());
    expect(typeof lastCardPropsFor('p1')!.onAcquire).toBe('function');
  });

  it('hides the reinstall button while the clone is still installed (INACTIVE + hasActiveWorkflow)', async () => {
    svc.getPurchases.mockResolvedValue({ purchases: [purchase('INACTIVE', true)] });
    render(<MyPurchasesTab />);
    await waitFor(() => expect(lastCardPropsFor('p1')).toBeTruthy());
    expect(lastCardPropsFor('p1')!.onAcquire).toBeUndefined();
  });

  it('hides the reinstall button for a REJECTED publication even with no active clone (mirrors backend block)', async () => {
    svc.getPurchases.mockResolvedValue({ purchases: [purchase('REJECTED', false)] });
    render(<MyPurchasesTab />);
    await waitFor(() => expect(lastCardPropsFor('p1')).toBeTruthy());
    expect(lastCardPropsFor('p1')!.onAcquire).toBeUndefined();
  });

  it('shows the reinstall button for a PENDING_REVIEW publication with no active clone (backend allows receipt-holder re-acquire)', async () => {
    svc.getPurchases.mockResolvedValue({ purchases: [purchase('PENDING_REVIEW', false)] });
    render(<MyPurchasesTab />);
    await waitFor(() => expect(lastCardPropsFor('p1')).toBeTruthy());
    expect(typeof lastCardPropsFor('p1')!.onAcquire).toBe('function');
  });

  it('shows the reinstall button for an ACTIVE app with no active clone (unchanged behavior)', async () => {
    svc.getPurchases.mockResolvedValue({ purchases: [purchase('ACTIVE', false)] });
    render(<MyPurchasesTab />);
    await waitFor(() => expect(lastCardPropsFor('p1')).toBeTruthy());
    expect(typeof lastCardPropsFor('p1')!.onAcquire).toBe('function');
  });
});
