// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import React from 'react';
import { render, cleanup } from '@testing-library/react';

vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
// Modal is closed on initial render; stub it so importing the component is cheap.
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({ default: () => null }));

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

beforeEach(() => { cloud.isCe = false; cloud.isCloudLinked = false; cloud.isInstallCloudLinked = false; getPublicationByIdPublic.mockReset(); });
afterEach(() => cleanup());

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
