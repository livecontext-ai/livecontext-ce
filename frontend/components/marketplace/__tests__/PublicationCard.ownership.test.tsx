// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

// Echo translation keys so we can assert on 'installed' / 'acquire' without locale strings.
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/link', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
// Heavy children - not under test here; render nothing / passthrough.
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
    title: 'Gallery',
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

describe('PublicationCard org-aware ownership', () => {
  const onAcquire = vi.fn();

  it('ownedByMe=true → shows Installed and hides the Acquire CTA (even when publisherId is someone else)', () => {
    render(<PublicationCard publication={pub({ publisherId: 'someone-else' })} currentUserId="me" ownedByMe onAcquire={onAcquire} />);
    expect(screen.getByText('installed')).toBeInTheDocument();
    expect(screen.queryByText('acquire')).toBeNull();
  });

  it('ownedByMe=false → shows Acquire (the org-aware flag is authoritative, ignoring publisherId)', () => {
    // publisherId === currentUserId would have read as "own" under the legacy check;
    // an explicit ownedByMe=false from the page must win.
    render(<PublicationCard publication={pub({ publisherId: 'me' })} currentUserId="me" ownedByMe={false} onAcquire={onAcquire} />);
    expect(screen.getByText('acquire')).toBeInTheDocument();
    expect(screen.queryByText('installed')).toBeNull();
  });

  it('ownedByMe omitted → falls back to publisherId === currentUserId (back-compat for non-marketplace callers)', () => {
    render(<PublicationCard publication={pub({ publisherId: 'me' })} currentUserId="me" onAcquire={onAcquire} />);
    expect(screen.getByText('installed')).toBeInTheDocument();
    expect(screen.queryByText('acquire')).toBeNull();
  });

  it('ownedByMe omitted + different publisher → Acquire shown', () => {
    render(<PublicationCard publication={pub({ publisherId: 'other' })} currentUserId="me" onAcquire={onAcquire} />);
    expect(screen.getByText('acquire')).toBeInTheDocument();
    expect(screen.queryByText('installed')).toBeNull();
  });

  it('isAcquired=true → Installed regardless of ownership', () => {
    render(<PublicationCard publication={pub({ publisherId: 'other' })} currentUserId="me" ownedByMe={false} isAcquired onAcquire={onAcquire} />);
    expect(screen.getByText('installed')).toBeInTheDocument();
    expect(screen.queryByText('acquire')).toBeNull();
  });

  it('anonymous viewer (no currentUserId, no ownedByMe) is never owned → Acquire shown, not Installed', () => {
    // Landing / linked-CE path: neither the server flag nor a user id is available, so the
    // nullish fallback resolves to false (not owned) - the card must never read as Installed.
    render(<PublicationCard publication={pub({ publisherId: 'someone-else' })} onAcquire={onAcquire} />);
    expect(screen.getByText('acquire')).toBeInTheDocument();
    expect(screen.queryByText('installed')).toBeNull();
  });
});

describe('PublicationCard - Acquire button control flow', () => {
  // Fresh mock per test so call counts are isolated (the card delegates to onAcquire;
  // the AcquirePublicationModal + its processing state live in the PARENT, never in the card).
  let onAcquire: ReturnType<typeof vi.fn> & ((publication: WorkflowPublication) => void);
  beforeEach(() => { onAcquire = vi.fn() as typeof onAcquire; });

  it('clicking Acquire invokes onAcquire with the publication and prevents the card link default navigation', () => {
    const p = pub({ id: 'pub-acq', publisherId: 'other' });
    render(<PublicationCard publication={p} currentUserId="me" ownedByMe={false} onAcquire={onAcquire} />);

    const button = screen.getByRole('button', { name: 'acquire' });
    // fireEvent.click returns false when a handler called preventDefault on the cancelable event,
    // proving the card stops the wrapping <Link> from navigating while it triggers the acquire.
    const notPrevented = fireEvent.click(button);

    expect(onAcquire).toHaveBeenCalledTimes(1);
    expect(onAcquire).toHaveBeenCalledWith(p);
    expect(notPrevented).toBe(false);
  });

  it('Acquire button is never disabled, so repeated clicks each fire onAcquire (card holds no in-flight guard; concurrency is the parent modal job)', () => {
    render(<PublicationCard publication={pub({ publisherId: 'other' })} currentUserId="me" ownedByMe={false} onAcquire={onAcquire} />);

    const button = screen.getByRole('button', { name: 'acquire' });
    expect(button).not.toBeDisabled();

    // Nothing in the card suppresses the button while a previous acquire is pending, so a second
    // click delegates again - this pins the documented "card stays simple" behavior.
    fireEvent.click(button);
    fireEvent.click(button);
    expect(onAcquire).toHaveBeenCalledTimes(2);
  });
});
