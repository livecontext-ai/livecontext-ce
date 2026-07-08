// @vitest-environment jsdom
/**
 * Pins the "My Shared" behaviour of {@link PublicationCard}:
 *  - Bug ("publication is not publicly available" on a PRIVATE own app): an own publication must
 *    preview via the AUTHENTICATED per-run path (runId + interfaceId, NO publicationId), which is
 *    valid at any visibility - so a private app renders instead of erroring on the PUBLIC-only
 *    showcase endpoint. Marketplace/foreign cards keep the public, publication-scoped showcase.
 *  - The footer shows a public / private indicator for the viewer's OWN publications only.
 *
 * ShowcasePreview is a prop-capture mock - we assert only the routing-decisive props.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

const captured = vi.hoisted(() => ({ calls: [] as Array<Record<string, unknown>> }));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
// PublicationCard navigates imperatively (Open button) + tracks the click.
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('next/link', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({
  ShowcasePreview: (props: Record<string, unknown>) => { captured.calls.push(props); return null; },
}));
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
    publisherId: 'me',
    status: 'ACTIVE',
    visibility: 'PUBLIC',
    useCount: 0,
    totalCreditsEarned: 0,
    showcaseRunId: 'run_show_1',
    showcaseInterfaceId: 'iface-1',
    ...overrides,
  } as WorkflowPublication;
}

describe('PublicationCard - My Shared owner preview', () => {
  beforeEach(() => { captured.calls = []; cleanup(); });

  it('mine → authenticated own-run render: runId + interfaceId passed, publicationId OMITTED (renders at any visibility)', () => {
    render(<PublicationCard publication={pub({ visibility: 'PRIVATE' })} mine />);
    expect(captured.calls.length).toBeGreaterThan(0);
    expect(captured.calls[0].runId).toBe('run_show_1');
    expect(captured.calls[0].interfaceId).toBe('iface-1');
    expect(captured.calls[0].publicationId).toBeUndefined();
  });

  it('not mine (marketplace) → public showcase render: publicationId passed', () => {
    render(<PublicationCard publication={pub()} />);
    expect(captured.calls.length).toBeGreaterThan(0);
    expect(captured.calls[0].publicationId).toBe('pub-1');
  });
});

describe('PublicationCard - visibility marker (own publications only)', () => {
  beforeEach(() => { captured.calls = []; cleanup(); });

  it('mine + PUBLIC → public indicator', () => {
    render(<PublicationCard publication={pub({ visibility: 'PUBLIC' })} mine />);
    expect(screen.getByTitle('visibilityPublic')).toBeInTheDocument();
  });

  it('mine + PRIVATE → private indicator', () => {
    render(<PublicationCard publication={pub({ visibility: 'PRIVATE' })} mine />);
    expect(screen.getByTitle('visibilityPrivate')).toBeInTheDocument();
  });

  it('not mine → no visibility indicator on foreign/marketplace cards', () => {
    render(<PublicationCard publication={pub({ visibility: 'PUBLIC' })} />);
    expect(screen.queryByTitle('visibilityPublic')).toBeNull();
    expect(screen.queryByTitle('visibilityPrivate')).toBeNull();
  });
});
