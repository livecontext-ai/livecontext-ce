// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import React from 'react';
import { render, cleanup, fireEvent } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
const routerPush = vi.hoisted(() => vi.fn());
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: routerPush }) }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
// Prop-capture stub: the install-flow tests below assert the inlineProgress
// gate + navigation callbacks this header passes to the modal.
const modalProps = vi.hoisted(() => ({
  calls: [] as Array<{
    inlineProgress?: boolean;
    onInstallStarted?: () => void;
    onSuccess?: (id: string) => void;
  }>,
}));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({
  default: (props: (typeof modalProps.calls)[number]) => {
    modalProps.calls.push(props);
    return null;
  },
}));

// Controllable CE-cloud state so we can exercise both the local and the
// cloud-linked routing of the self-fetched publication.
const cloud = vi.hoisted(() => ({ isCe: false, isCloudLinked: false, isInstallCloudLinked: false }));
vi.mock('@/lib/edition', () => ({ get IS_CE() { return cloud.isCe; } }));
vi.mock('@/hooks/useCeCloudLinkStatus', () => ({
  useCeCloudLinkStatus: () => ({ isCloudLinked: cloud.isCloudLinked, isInstallCloudLinked: cloud.isInstallCloudLinked, isLoading: false, status: null }),
}));

const getPublicationByIdPublic = vi.fn();
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getPublicationByIdPublic: (...args: unknown[]) => getPublicationByIdPublic(...args) },
}));

import { MarketplaceHeaderActions } from '../MarketplaceHeaderActions';

beforeEach(() => {
  cloud.isCe = false;
  cloud.isCloudLinked = false;
  cloud.isInstallCloudLinked = false;
  getPublicationByIdPublic.mockReset();
  routerPush.mockReset();
  modalProps.calls = [];
});
afterEach(() => cleanup());

/** Opens the acquire modal from the header button and returns its latest props. */
async function openModalFor(publication: Record<string, unknown>) {
  getPublicationByIdPublic.mockResolvedValue(publication);
  const view = render(<MarketplaceHeaderActions publicationId={String(publication.id)} />);
  const button = await view.findByRole('button');
  fireEvent.click(button);
  const latest = modalProps.calls[modalProps.calls.length - 1];
  if (!latest) throw new Error('modal never mounted');
  return latest;
}

describe('MarketplaceHeaderActions - credit shimmer', () => {
  it('renders a theme-aware shimmer overlay over the price button', async () => {
    getPublicationByIdPublic.mockResolvedValue({ id: 'p1', creditsPerUse: 5, publicationType: 'WORKFLOW' });
    const { container, findByRole } = render(<MarketplaceHeaderActions publicationId="p1" />);
    await findByRole('button');

    const overlay = container.querySelector('span[aria-hidden="true"]');
    expect(overlay).toBeTruthy();
    // Uses the theme-aware --shimmer-color tokens (not a hardcoded white sweep),
    // so it adapts to the inverted accent button in light vs dark theme...
    const style = overlay?.getAttribute('style') ?? '';
    expect(style).toContain('--shimmer-color');
    // ...driven by the shared shimmer-scan keyframes.
    expect(style).toContain('shimmer-scan');
    // The button clips the sweep to its pill shape.
    expect(container.querySelector('button')?.className).toContain('overflow-hidden');
  });

  it('also shimmers on the Free button (credits === 0)', async () => {
    getPublicationByIdPublic.mockResolvedValue({ id: 'p2', creditsPerUse: 0, publicationType: 'WORKFLOW' });
    const { container, findByRole } = render(<MarketplaceHeaderActions publicationId="p2" />);
    await findByRole('button');
    expect(container.querySelector('span[aria-hidden="true"]')).toBeTruthy();
  });
});

describe('MarketplaceHeaderActions - CE-cloud publication routing', () => {
  it('self-fetches the publication from the LOCAL endpoint by default (non-CE / unlinked)', async () => {
    getPublicationByIdPublic.mockResolvedValue({ id: 'p1', creditsPerUse: 0, publicationType: 'WORKFLOW' });
    const { findByRole } = render(<MarketplaceHeaderActions publicationId="p1" />);
    await findByRole('button');
    expect(getPublicationByIdPublic).toHaveBeenCalledWith('p1', false);
  });

  it('self-fetches through the cloud proxy on a cloud-linked CE (remote=true)', async () => {
    cloud.isCe = true;
    cloud.isCloudLinked = true;
    cloud.isInstallCloudLinked = true;
    getPublicationByIdPublic.mockResolvedValue({ id: 'p1', creditsPerUse: 0, publicationType: 'WORKFLOW' });
    const { findByRole } = render(<MarketplaceHeaderActions publicationId="p1" />);
    await findByRole('button');
    expect(getPublicationByIdPublic).toHaveBeenCalledWith('p1', true);
  });

  it('inherited-link MEMBER (per-user unlinked, install-linked) still fetches through the cloud proxy', async () => {
    // Regression: a member has isCloudLinked=false but isInstallCloudLinked=true. Gating on the
    // per-user flag self-fetched LOCALLY -> 404 ("Failed to load marketplace"); the install-global
    // flag routes them through the cloud proxy like the owner.
    cloud.isCe = true;
    cloud.isCloudLinked = false;
    cloud.isInstallCloudLinked = true;
    getPublicationByIdPublic.mockResolvedValue({ id: 'p1', creditsPerUse: 0, publicationType: 'WORKFLOW' });
    const { findByRole } = render(<MarketplaceHeaderActions publicationId="p1" />);
    await findByRole('button');
    expect(getPublicationByIdPublic).toHaveBeenCalledWith('p1', true);
  });
});

describe('MarketplaceHeaderActions - install-flow routing (inline vs full modal)', () => {
  it('APPLICATION → inline progress; install start navigates back to the marketplace grid; success does NOT navigate', async () => {
    const props = await openModalFor({ id: 'p-app', creditsPerUse: 0, publicationType: 'WORKFLOW', displayMode: 'APPLICATION' });

    expect(props.inlineProgress).toBe(true);
    props.onInstallStarted?.();
    expect(routerPush).toHaveBeenCalledWith('/app/marketplace');
    // The marketplace card owns the success (Open button) - no modal redirect.
    routerPush.mockReset();
    props.onSuccess?.('wf-1');
    expect(routerPush).not.toHaveBeenCalled();
  });

  it('AGENT → classic full-modal flow routed to /app/agent on success (no marketplace card exists for it)', async () => {
    const props = await openModalFor({ id: 'p-agent', creditsPerUse: 0, publicationType: 'AGENT', displayMode: 'AGENT' });

    expect(props.inlineProgress).toBe(false);
    props.onSuccess?.('agent-1');
    expect(routerPush).toHaveBeenCalledWith('/app/agent');
  });

  it('non-APPLICATION workflow display → classic full-modal flow routed to the installed application on success', async () => {
    // Regression (audit D5): the Explore grid surfaces APPLICATION cards only,
    // so a WORKFLOW-display publication must keep in-modal progress + success
    // (inline would strand the user on a grid with no card to watch).
    const props = await openModalFor({ id: 'p-wf', creditsPerUse: 0, publicationType: 'WORKFLOW', displayMode: 'WORKFLOW' });

    expect(props.inlineProgress).toBe(false);
    props.onSuccess?.('wf-2');
    expect(routerPush).toHaveBeenCalledWith('/app/applications/p-wf');
  });
});
