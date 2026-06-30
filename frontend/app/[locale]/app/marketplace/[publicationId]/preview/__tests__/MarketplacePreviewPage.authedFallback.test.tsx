// @vitest-environment jsdom
/**
 * Bug 1 - "Failed to load marketplace" when opening the preview of a publication
 * the publisher deleted (status INACTIVE) that the caller has acquired.
 *
 * The page first reads the pub via the ANONYMOUS /publications/by-id/{id}, which
 * 404s for a non-public pub. The fix retries the AUTH'D /publications/{id} read
 * (receipt/owner bypass) and, when that succeeds, renders the preview with
 * `authenticated` so the interface showcase render also uses the receipt-gated
 * twin. An anonymous visitor with no receipt still sees the error.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import React from 'react';

const svc = vi.hoisted(() => ({
  getPublicationByIdPublic: vi.fn(),
  getPublicationById: vi.fn(),
  getAgentSnapshot: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: svc }));

// Mutable edition/link config so a test can flip to a cloud-linked CE (remote).
const cfg = vi.hoisted(() => ({ isCE: false, installLinked: false }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/lib/edition', () => ({ get IS_CE() { return cfg.isCE; } }));
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => ({ isAuthChecking: false }) }));
vi.mock('@/hooks/useCeCloudLinkStatus', () => ({
  useCeCloudLinkStatus: () => ({ isLoading: false, isInstallCloudLinked: cfg.installLinked }),
}));

// Heavy children stubbed out. PublicationPreviewShell captures its props so the
// test can assert the `authenticated` flag threaded from the load path.
const shellProps = vi.hoisted(() => ({ calls: [] as Array<Record<string, unknown>> }));
vi.mock('@/components/marketplace/PublicationPreviewShell', () => ({
  PublicationPreviewShell: (props: Record<string, unknown>) => {
    shellProps.calls.push(props);
    return <div data-testid="preview-shell">{props.children as React.ReactNode}</div>;
  },
}));
vi.mock('@/components/views/application/ApplicationDetailView', () => ({ ApplicationDetailView: () => <div data-testid="app-detail" /> }));
vi.mock('@/components/views/workflow/WorkflowLoadingState', () => ({ WorkflowLoadingState: () => <div data-testid="loading" /> }));
vi.mock('@/components/agent-fleet/AgentFleetCanvas', () => ({ AgentFleetCanvas: () => null }));
vi.mock('@/components/DataTable', () => ({ default: () => null }));
vi.mock('@/lib/datatable/snapshot-adapter', () => ({ snapshotToDataTable: () => ({}) }));
vi.mock('@/components/InterfacePreview', () => ({ InterfacePreview: () => null }));
vi.mock('@/components/marketplace/PublicationInfoPanel', () => ({ PublicationInfoPanel: () => null }));

import { MarketplacePreviewInner } from '../page';

const WORKFLOW_PUB = {
  publicationType: 'WORKFLOW',
  showcaseRunId: 'r1',
  workflowId: 'w1',
  planSnapshot: {},
  title: 'Place Explorer',
};

function renderPage(id = 'pub-1') {
  return render(<MarketplacePreviewInner publicationId={id} />);
}

const lastShell = () => shellProps.calls[shellProps.calls.length - 1];

beforeEach(() => {
  shellProps.calls = [];
  cfg.isCE = false;
  cfg.installLinked = false;
  svc.getPublicationByIdPublic.mockReset();
  svc.getPublicationById.mockReset();
  svc.getAgentSnapshot.mockReset();
});

describe('MarketplacePreviewPage - authed fallback for a deleted/acquired publication', () => {
  it('falls back to the AUTH\'D read and previews with authenticated=true when the anonymous read 404s', async () => {
    // Publisher deleted the pub -> anonymous by-id 404; the acquirer's receipt
    // admits it on the auth'd endpoint. Fails pre-fix (no fallback -> error screen).
    svc.getPublicationByIdPublic.mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));
    svc.getPublicationById.mockResolvedValue(WORKFLOW_PUB);

    renderPage();

    await waitFor(() => expect(screen.getByTestId('preview-shell')).toBeTruthy());
    expect(svc.getPublicationById).toHaveBeenCalledWith('pub-1');
    expect(lastShell().authenticated).toBe(true);
    // The error text must NOT be shown.
    expect(screen.queryByText('loadError')).toBeNull();
  });

  it('shows the error when BOTH the anonymous and the auth\'d reads fail (anonymous visitor, no receipt)', async () => {
    svc.getPublicationByIdPublic.mockRejectedValue(new Error('404'));
    svc.getPublicationById.mockRejectedValue(new Error('404'));

    renderPage();

    await waitFor(() => expect(screen.getByText('loadError')).toBeTruthy());
    expect(screen.queryByTestId('preview-shell')).toBeNull();
  });

  it('uses the anonymous read and authenticated=false for a still-public (ACTIVE) pub', async () => {
    svc.getPublicationByIdPublic.mockResolvedValue(WORKFLOW_PUB);

    renderPage();

    await waitFor(() => expect(screen.getByTestId('preview-shell')).toBeTruthy());
    expect(svc.getPublicationById).not.toHaveBeenCalled();
    expect(lastShell().authenticated).toBe(false);
  });

  it('does NOT attempt the local auth\'d fallback on a cloud-linked CE (remote) - the cloud id has no local twin', async () => {
    // remote = IS_CE && isInstallCloudLinked. The anonymous read already proxied
    // to the cloud; its failure means the cloud source is gone, and there is no
    // LOCAL /publications/{id} for a cloud id, so we must surface the error.
    cfg.isCE = true;
    cfg.installLinked = true;
    svc.getPublicationByIdPublic.mockRejectedValue(new Error('410 gone'));

    renderPage();

    await waitFor(() => expect(screen.getByText('loadError')).toBeTruthy());
    expect(svc.getPublicationByIdPublic).toHaveBeenCalledWith('pub-1', true); // remote proxy
    expect(svc.getPublicationById).not.toHaveBeenCalled(); // no local authed fallback for a cloud id
  });
});
