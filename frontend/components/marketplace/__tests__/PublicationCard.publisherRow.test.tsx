// @vitest-environment jsdom
/**
 * Card footer layout: everything that qualifies the publication - the publisher
 * chip, the integration icons, and (for an AGENT) the resource count badges -
 * belongs on ONE row, reading left to right as "published by X, built with
 * these". The count badges used to sit on a row of their OWN underneath the
 * publisher, which read as detached: an agent publication carries no plan, so
 * it has no nodeIcons and those badges are the only glyphs the card shows.
 *
 * These assert containment (same parent row), not pixel geometry - jsdom has no
 * layout engine, so a screenshot is the only way to catch a visual wrap and
 * that belongs in e2e. What is pinned here is the DOM structure the CSS relies
 * on, which is what actually regressed.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { vi } from 'vitest';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('next/link', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => null }));
vi.mock('@/components/marketplace/InterfacePreview', () => ({ InterfacePreview: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/profile/UserActionMenu', () => ({
  UserActionMenu: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="publisher-chip">{children}</div>
  ),
}));
vi.mock('@/components/WorkflowNodeIcons', () => ({
  WorkflowNodeIcons: () => <div data-testid="node-icons" />,
}));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getLandingSnapshot: vi.fn().mockResolvedValue({ landing: null }) },
}));

import { PublicationCard } from '../PublicationCard';

function agentPub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-agent-1',
    title: 'Support Triage',
    displayMode: 'AGENT',
    creditsPerUse: 0,
    publisherId: 'user-9',
    publisherName: 'livecontext',
    status: 'ACTIVE',
    visibility: 'PUBLIC',
    useCount: 0,
    totalCreditsEarned: 0,
    agentCount: 2,
    skillCount: 3,
    ...overrides,
  } as WorkflowPublication;
}

/** The card's publisher row = the element that directly contains the chip. */
function publisherRow(): HTMLElement {
  const chip = screen.getByTestId('publisher-chip');
  const row = chip.parentElement;
  if (!row) throw new Error('publisher chip has no parent row');
  return row;
}

describe('PublicationCard - publisher row keeps its qualifiers on ONE line', () => {
  beforeEach(() => { cleanup(); });

  it('Regression - agent count badges sit in the publisher row, not on a row below it', () => {
    render(<PublicationCard publication={agentPub()} />);

    const row = publisherRow();
    // 2 agents + 3 skills = two non-zero badges, both inside the publisher row.
    expect(row.textContent).toContain('2');
    expect(row.textContent).toContain('3');
    // And the badges are INSIDE the chip's row, not in a sibling row - the
    // exact structure that regressed.
    const badgeText = screen.getByTitle('resourceType.AGENT');
    expect(row).toContainElement(badgeText);
    // A single non-wrapping flex line, so nothing can drop below again.
    expect(row.className).toContain('flex');
    expect(row.className).not.toContain('flex-wrap');
  });

  it('Node/integration icons stay in the same row as the publisher', () => {
    render(<PublicationCard publication={agentPub({
      nodeIcons: [{ iconSlug: 'slack', isMcp: true }],
    })} />);

    expect(publisherRow()).toContainElement(screen.getByTestId('node-icons'));
  });

  it('A non-agent publication renders the publisher and no count badges at all', () => {
    render(<PublicationCard publication={agentPub({
      displayMode: 'APPLICATION', agentCount: 0, skillCount: 0,
    })} />);

    const row = publisherRow();
    expect(row.textContent).toContain('livecontext');
    // The counts are agent-only: nothing numeric should have leaked into the row.
    expect(row.textContent).not.toMatch(/\d/);
  });

  it('Zero-valued counts are omitted rather than rendered as "0"', () => {
    render(<PublicationCard publication={agentPub({ agentCount: 2, skillCount: 0 })} />);

    const row = publisherRow();
    expect(row.textContent).toContain('2');
    expect(row.textContent).not.toContain('0');
  });
});
