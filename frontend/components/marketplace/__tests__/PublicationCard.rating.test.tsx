// @vitest-environment jsdom
/**
 * Pins the average-rating display on {@link PublicationCard}: once an app has at
 * least one rating, the card shows a filled star + the average value + the vote
 * count - on EVERY card (the public marketplace grid included, where showStats is
 * not passed), not only in the owner stats view. No votes -> nothing shown.
 *
 * Star <svg>s carry `fill-amber-400`; the next-intl mock returns keys verbatim.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
// PublicationCard navigates imperatively (Open button) + tracks the click.
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
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: { getLandingSnapshot: vi.fn().mockResolvedValue({ landing: null }) } }));

import { PublicationCard } from '../PublicationCard';

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    title: 'My App',
    displayMode: 'APPLICATION',
    creditsPerUse: 0,
    publisherId: 'pubr',
    status: 'ACTIVE',
    visibility: 'PUBLIC',
    useCount: 0,
    totalCreditsEarned: 0,
    showcaseRunId: 'run_1',
    showcaseInterfaceId: 'iface-1',
    ...overrides,
  } as WorkflowPublication;
}

function amberStarCount(container: HTMLElement): number {
  return container.querySelectorAll('svg.fill-amber-400').length;
}

describe('PublicationCard - average rating', () => {
  beforeEach(() => cleanup());

  it('shows star + value + count on a plain marketplace card (no showStats) once rated', () => {
    const { container } = render(<PublicationCard publication={pub({ averageRating: 4.5, reviewCount: 3 })} />);
    expect(screen.getByText('4.5')).toBeInTheDocument();
    expect(screen.getByText('(3)')).toBeInTheDocument();
    expect(amberStarCount(container)).toBe(1);
    // showStats-only stats (uses) stay hidden on a plain marketplace card
    expect(screen.queryByText('0 uses')).toBeNull();
  });

  it('shows nothing when the app has no votes', () => {
    const { container } = render(<PublicationCard publication={pub({ averageRating: 0, reviewCount: 0 })} />);
    expect(screen.queryByText('0.0')).toBeNull();
    expect(amberStarCount(container)).toBe(0);
  });

  it('shows rating AND usage stats together on the owner stats view (showStats)', () => {
    const { container } = render(<PublicationCard publication={pub({ averageRating: 5, reviewCount: 2, useCount: 7 })} showStats mine />);
    expect(screen.getByText('5.0')).toBeInTheDocument();
    expect(screen.getByText('(2)')).toBeInTheDocument();
    expect(amberStarCount(container)).toBe(1);
    expect(screen.getByText('7 uses')).toBeInTheDocument();
  });

  it('owner stats view with no votes: usage shown, NO star (original behavior preserved)', () => {
    const { container } = render(<PublicationCard publication={pub({ averageRating: 0, reviewCount: 0, useCount: 4 })} showStats mine />);
    expect(screen.getByText('4 uses')).toBeInTheDocument();
    expect(amberStarCount(container)).toBe(0);
    expect(screen.queryByText('0.0')).toBeNull();
  });
});
