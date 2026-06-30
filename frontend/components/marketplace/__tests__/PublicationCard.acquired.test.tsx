// @vitest-environment jsdom
/**
 * Acquired-card showcase routing: a My-Purchases {@link PublicationCard} (the viewer holds a
 * receipt) must render its publication-scoped showcase through the receipt-gated AUTH'D endpoint
 * so the frozen interface still previews after the publisher unpublishes/deletes the source
 * (status INACTIVE). Without `acquired`, the card hits the anonymous /by-id render which 403s
 * "Publication is not publicly available" for non-public pubs. Cloud purchases (remote) keep the
 * by-id proxy (authenticated must NOT win over remote). These assert the `authenticated` prop
 * actually reaches ShowcasePreview; the service-level routing is covered by publication.service.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

const captured = vi.hoisted(() => ({ showcase: [] as Array<Record<string, unknown>> }));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/link', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({
  ShowcasePreview: (props: Record<string, unknown>) => { captured.showcase.push(props); return null; },
}));
vi.mock('@/components/marketplace/InterfacePreview', () => ({ InterfacePreview: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/profile/UserActionMenu', () => ({
  UserActionMenu: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getLandingSnapshot: vi.fn().mockResolvedValue({ landing: null }) },
}));

import { PublicationCard } from '../PublicationCard';

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    title: 'Place Explorer',
    displayMode: 'APPLICATION',
    creditsPerUse: 0,
    publisherId: 'user-9',
    status: 'INACTIVE', // publisher deleted/unpublished it (soft delete)
    visibility: 'PRIVATE',
    useCount: 0,
    totalCreditsEarned: 0,
    showcaseRunId: 'run_show_1',
    showcaseInterfaceId: 'iface-1',
    ...overrides,
  } as WorkflowPublication;
}

describe('PublicationCard - acquired (My Purchases) showcase routing', () => {
  beforeEach(() => { captured.showcase = []; cleanup(); });

  it('acquired card → ShowcasePreview gets authenticated=true (receipt-gated render of an INACTIVE pub)', () => {
    render(<PublicationCard publication={pub()} acquired isAcquired />);
    expect(captured.showcase[0]?.publicationId).toBe('pub-1');
    expect(captured.showcase[0]?.authenticated).toBe(true);
  });

  it('acquired + remote (cloud purchase) → authenticated=false so the by-id cloud proxy is kept', () => {
    render(<PublicationCard publication={pub()} acquired remote />);
    expect(captured.showcase[0]?.authenticated).toBe(false);
    expect(captured.showcase[0]?.remote).toBe(true);
  });

  it('Explore card (not acquired) → authenticated is falsy (anonymous cross-tenant-safe render)', () => {
    render(<PublicationCard publication={pub({ status: 'ACTIVE', visibility: 'PUBLIC' })} />);
    expect(captured.showcase[0]?.authenticated).toBeFalsy();
  });

  // A1 - a CLOUD purchase backed by a LOCAL clone renders the acquirer's OWN clone via the local
  // per-run path: publicationId omitted (-> runId+interfaceId are local), remote off (no cloud
  // proxy). Immune to the cloud publisher deleting the source.
  it('localShowcase card → renders per-run LOCALLY: no publicationId, runId/interfaceId local, remote off', () => {
    render(<PublicationCard publication={pub({
      remote: true, localShowcase: true, acquiredWorkflowId: 'clone-wf',
      showcaseRunId: 'local-run', showcaseInterfaceId: 'local-iface',
    })} acquired remote />);
    expect(captured.showcase[0]?.publicationId).toBeUndefined();
    expect(captured.showcase[0]?.runId).toBe('local-run');
    expect(captured.showcase[0]?.interfaceId).toBe('local-iface');
    expect(captured.showcase[0]?.remote).toBeFalsy();
  });
});
