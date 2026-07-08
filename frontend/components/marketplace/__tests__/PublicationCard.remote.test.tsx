// @vitest-environment jsdom
/**
 * CE-cloud parity propagation: a marketplace/highlight {@link PublicationCard}
 * rendering a CLOUD publication (cloud-linked CE) must thread `remote` into
 * EVERY per-publication read it triggers - the showcase-render thumbnail
 * (workflow), the landing-snapshot thumbnail (interface/table/skill/agent), and
 * the publisher avatar. Without this each card 404s on the local backend (cloud
 * id absent locally). These assert the props actually reach the children (the
 * service-level routing itself is covered by publication.service.remote.test).
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, cleanup, waitFor } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

const captured = vi.hoisted(() => ({
  showcase: [] as Array<Record<string, unknown>>,
  avatar: [] as Array<Record<string, unknown>>,
  userMenu: [] as Array<Record<string, unknown>>,
  landingCalls: [] as unknown[][],
}));
const getLandingSnapshot = vi.hoisted(() => vi.fn().mockResolvedValue({ landing: null }));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
// PublicationCard navigates imperatively (Open button) + tracks the click.
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('next/link', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({
  ShowcasePreview: (props: Record<string, unknown>) => { captured.showcase.push(props); return null; },
}));
vi.mock('@/components/marketplace/InterfacePreview', () => ({ InterfacePreview: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({
  PublisherAvatar: (props: Record<string, unknown>) => { captured.avatar.push(props); return null; },
}));
vi.mock('@/components/profile/UserActionMenu', () => ({
  UserActionMenu: ({ children, ...props }: { children: React.ReactNode }) => { captured.userMenu.push(props); return <div>{children}</div>; },
}));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getLandingSnapshot: (...args: unknown[]) => { captured.landingCalls.push(args); return getLandingSnapshot(...args); } },
}));

import { PublicationCard } from '../PublicationCard';

function pub(overrides: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-cloud-1',
    title: 'Cloud App',
    displayMode: 'APPLICATION',
    creditsPerUse: 0,
    publisherId: 'cloud-user-9',
    status: 'ACTIVE',
    visibility: 'PUBLIC',
    useCount: 0,
    totalCreditsEarned: 0,
    showcaseRunId: 'run_show_1',
    showcaseInterfaceId: 'iface-1',
    ...overrides,
  } as WorkflowPublication;
}

describe('PublicationCard - CE-cloud remote propagation', () => {
  beforeEach(() => { captured.showcase = []; captured.avatar = []; captured.userMenu = []; captured.landingCalls = []; getLandingSnapshot.mockClear(); cleanup(); });

  it('workflow card with remote → ShowcasePreview receives remote=true', () => {
    render(<PublicationCard publication={pub()} remote />);
    expect(captured.showcase[0]?.remote).toBe(true);
    expect(captured.showcase[0]?.publicationId).toBe('pub-cloud-1');
  });

  it('interface-type card with remote → getLandingSnapshot is called with remote=true', async () => {
    // No showcaseRun → PublicationPreview takes the landing-snapshot path.
    render(<PublicationCard publication={pub({ displayMode: 'INTERFACE', showcaseRunId: undefined, showcaseInterfaceId: undefined })} remote />);
    await waitFor(() => expect(captured.landingCalls.length).toBeGreaterThan(0));
    expect(captured.landingCalls[0]).toEqual(['pub-cloud-1', true]);
  });

  it('remote card → PublisherAvatar receives remote=true (cloud publisher avatar)', () => {
    render(<PublicationCard publication={pub()} remote />);
    expect(captured.avatar.some((p) => p.remote === true)).toBe(true);
  });

  it('remote card → UserActionMenu receives remote=true (hides the dead-end DM action)', () => {
    render(<PublicationCard publication={pub()} remote />);
    expect(captured.userMenu.some((p) => p.remote === true)).toBe(true);
  });

  it('default (local) card → children get a falsy remote - no behavior change off CE-cloud', async () => {
    render(<PublicationCard publication={pub({ displayMode: 'INTERFACE', showcaseRunId: undefined, showcaseInterfaceId: undefined })} />);
    await waitFor(() => expect(captured.landingCalls.length).toBeGreaterThan(0));
    // remote is undefined (not passed) → service treats it as local.
    expect(captured.landingCalls[0][1]).toBeFalsy();
    expect(captured.avatar.every((p) => !p.remote)).toBe(true);
    expect(captured.userMenu.every((p) => !p.remote)).toBe(true);
  });
});
