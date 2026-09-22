// @vitest-environment jsdom
/**
 * Admin demo-install mode as seen by a marketplace card.
 *
 * The mode exists because the person demonstrating the marketplace is usually
 * the publisher, and a publisher's own card never offers Install. These tests
 * pin that the two reasons a card withholds the CTA (own publication, already
 * installed) stop applying, that the green "installed" badge goes with them,
 * and that CE-exclusive stays blocked because that one is a true statement
 * about where the publication can run.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('next/link', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => null }));
vi.mock('@/components/marketplace/InterfacePreview', () => ({ InterfacePreview: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/profile/UserActionMenu', () => ({ UserActionMenu: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
// Managed cloud, so a ceExclusive publication is genuinely un-installable here.
vi.mock('@/lib/edition', () => ({ IS_MANAGED_CLOUD: true, IS_CE: false }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getLandingSnapshot: vi.fn().mockResolvedValue({ landing: null }) },
}));

const demoMode = vi.hoisted(() => ({ on: false }));
vi.mock('@/lib/marketplace/demoInstallMode', () => ({
  useMarketplaceDemoInstall: () => demoMode.on,
}));

import { PublicationCard } from '../PublicationCard';

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    title: 'Invoice to Client',
    displayMode: 'WORKFLOW',
    creditsPerUse: 0,
    publisherId: 'pub-user',
    status: 'ACTIVE',
    visibility: 'PUBLIC',
    useCount: 0,
    totalCreditsEarned: 0,
    ...overrides,
  } as WorkflowPublication;
}

describe('PublicationCard - admin demo-install mode', () => {
  const onAcquire = vi.fn();

  beforeEach(() => {
    demoMode.on = false;
    onAcquire.mockReset();
  });

  it('off: the publisher still sees Installed on their own publication', () => {
    render(<PublicationCard publication={pub()} currentUserId="me" ownedByMe onAcquire={onAcquire} />);
    expect(screen.getByText('installed')).toBeInTheDocument();
    expect(screen.queryByText('acquire')).toBeNull();
  });

  it('on: the publisher gets the Install CTA back on their own publication', () => {
    demoMode.on = true;
    render(<PublicationCard publication={pub()} currentUserId="me" ownedByMe onAcquire={onAcquire} />);
    expect(screen.getByText('acquire')).toBeInTheDocument();
    expect(screen.queryByText('installed')).toBeNull();
  });

  it('on: an already-installed publication offers Install again', () => {
    demoMode.on = true;
    render(
      <PublicationCard
        publication={pub({ publisherId: 'other' })}
        currentUserId="me"
        ownedByMe={false}
        isAcquired
        onAcquire={onAcquire}
      />,
    );
    expect(screen.getByText('acquire')).toBeInTheDocument();
    expect(screen.queryByText('installed')).toBeNull();
  });

  it('on: a card with no acquire handler stays without a CTA (anonymous visitors)', () => {
    demoMode.on = true;
    render(<PublicationCard publication={pub({ publisherId: 'other' })} currentUserId="me" />);
    expect(screen.queryByText('acquire')).toBeNull();
  });

  it('on: an installed application swaps its Open button for Install', () => {
    demoMode.on = true;
    render(
      <PublicationCard
        publication={pub({ publisherId: 'other' })}
        currentUserId="me"
        isAcquired
        onAcquire={onAcquire}
        openHref="/app/applications/pub-1"
      />,
    );
    expect(screen.getByText('acquire')).toBeInTheDocument();
    expect(screen.queryByText('open')).toBeNull();
  });

  it('on: a card with an Open link but no acquire handler keeps Open rather than losing every button', () => {
    // My-Purchases rows that cannot be reinstalled: dropping Open here would
    // leave the card with nothing at all.
    demoMode.on = true;
    render(
      <PublicationCard
        publication={pub({ publisherId: 'other' })}
        currentUserId="me"
        isAcquired
        openHref="/app/applications/pub-1"
      />,
    );
    expect(screen.getByText('open')).toBeInTheDocument();
    expect(screen.queryByText('acquire')).toBeNull();
  });

  it('off: an installed application keeps its Open button', () => {
    render(
      <PublicationCard
        publication={pub({ publisherId: 'other' })}
        currentUserId="me"
        isAcquired
        onAcquire={onAcquire}
        openHref="/app/applications/pub-1"
      />,
    );
    expect(screen.getByText('open')).toBeInTheDocument();
    expect(screen.queryByText('acquire')).toBeNull();
  });

  it('on: a CE-exclusive publication stays un-installable, because that is true of the app, not of the viewer', () => {
    demoMode.on = true;
    render(
      <PublicationCard
        publication={pub({ publisherId: 'other', ceExclusive: true })}
        currentUserId="me"
        onAcquire={onAcquire}
      />,
    );
    expect(screen.queryByText('acquire')).toBeNull();
  });

  it('on: an install already in flight still owns the card, no CTA behind the gauge', () => {
    demoMode.on = true;
    render(
      <PublicationCard publication={pub()} currentUserId="me" ownedByMe onAcquire={onAcquire} installProgress={40} />,
    );
    expect(screen.queryByText('acquire')).toBeNull();
  });
});
