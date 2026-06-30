// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

// Same vitest shims as the other PublicationInfoPanel suites. The translation
// mock returns the key verbatim, so the vote-count labels render as the raw i18n
// keys ('voter' / 'voters') and the empty state as 'noRatings' - which is exactly
// what we assert on here.
vi.mock('@/hooks/useUserProfile', () => ({
  useUserProfile: () => ({ profile: { id: '999' } }),
}));
vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/',
}));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  usePathname: () => '/',
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

// Mutable auth so each test can pose as the publisher, an anonymous visitor, or
// a non-publisher reviewer. The component derives:
//   isPublisher = String(numericUserId) === publication.publisherId
//   canReview   = !!numericUserId && !isPublisher
// The aggregate (stars + value + vote count) renders only when canReview is
// false (publisher OR logged-out visitor); a reviewer gets the interactive
// control instead.
const authState = vi.hoisted(() => ({ numericUserId: null as number | null }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ numericUserId: authState.numericUserId }),
}));

vi.mock('@/hooks/useMissingCredentials', () => ({
  useMissingCredentials: () => ({ count: 0, wizardable: [], manual: [], refetch: vi.fn() }),
}));

vi.mock('@/components/ui/tabs', async () => {
  const React = await import('react');
  const TabsContext = React.createContext<any>(null);
  return {
    Tabs: ({ children, value, onValueChange }: any) =>
      React.createElement(TabsContext.Provider, { value: { value, onValueChange } },
        React.createElement('div', null, children)),
    TabsList: ({ children }: any) => React.createElement('div', { role: 'tablist' }, children),
    TabsTrigger: ({ children, value }: any) => {
      const ctx = React.useContext(TabsContext);
      return React.createElement('button', {
        type: 'button', role: 'tab', 'aria-selected': ctx?.value === value,
        onClick: () => ctx?.onValueChange(value),
      }, children);
    },
    TabsContent: ({ children, value }: any) => {
      const ctx = React.useContext(TabsContext);
      return ctx?.value === value
        ? React.createElement('div', { role: 'tabpanel', 'data-value': value }, children)
        : null;
    },
  };
});

const svc = vi.hoisted(() => ({
  getCommentCount: vi.fn(),
  getMyReview: vi.fn(),
  getReviews: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getCommentCount: (...a: unknown[]) => svc.getCommentCount(...a),
    getMyReview: (...a: unknown[]) => svc.getMyReview(...a),
    getReviews: (...a: unknown[]) => svc.getReviews(...a),
  },
}));

vi.mock('@/components/credentials/CredentialWizard', () => ({ CredentialWizard: () => null }));
vi.mock('@/components/applications/ApplicationActivationButton', () => ({ ApplicationActivationButton: () => null }));

import { PublicationInfoPanel } from '../PublicationInfoPanel';

function publication(over: Partial<WorkflowPublication> = {}): WorkflowPublication {
  return {
    id: 'pub-1',
    workflowId: 'workflow-1',
    title: 'AI Phone Caller',
    description: 'Calls people for you',
    creditsPerUse: 0,
    publisherId: 'publisher-1',
    publisherName: 'Publisher Co',
    status: 'ACTIVE',
    visibility: 'PUBLIC',
    useCount: 12,
    totalCreditsEarned: 0,
    averageRating: 5,
    reviewCount: 1,
    publishedAt: '2026-05-22T10:00:00Z',
    ...over,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  authState.numericUserId = null;
  svc.getCommentCount.mockResolvedValue(0);
  svc.getMyReview.mockResolvedValue(null);
  svc.getReviews.mockResolvedValue({ reviews: [], totalPages: 0, totalElements: 0 });
});

// Star <svg>s carry exactly one of these fill classes (StarRating.tsx): amber =
// filled, gray = empty. The only stars in the rendered panel live in the footer
// StarRating, so these selectors isolate them from avatar/menu icons.
function filledStarCount(container: HTMLElement): number {
  return container.querySelectorAll('svg.fill-amber-400').length;
}
function starButtons(container: HTMLElement): HTMLButtonElement[] {
  return Array.from(container.querySelectorAll('svg.fill-amber-400, svg.fill-gray-200'))
    .map((s) => s.closest('button'))
    .filter((b): b is HTMLButtonElement => b != null);
}

describe('PublicationInfoPanel - aggregate rating shown to publisher / visitor', () => {
  it('shows the publisher their own app average as value + singular vote count', async () => {
    // numericUserId 999 === publisherId '999' -> isPublisher -> canReview false.
    authState.numericUserId = 999;
    const { container } = render(<PublicationInfoPanel publication={publication({ publisherId: '999', averageRating: 5, reviewCount: 1 })} />);

    expect(await screen.findByText('5.0')).toBeInTheDocument();
    // count===1 -> singular 'voter' key, wrapped in "(1 voter)"
    expect(screen.getByText('(1 voter)')).toBeInTheDocument();
    expect(screen.queryByText('noRatings')).not.toBeInTheDocument();
    // avg 5 -> all five stars filled, and the stars are NON-interactive (read-only)
    expect(filledStarCount(container)).toBe(5);
    const stars = starButtons(container);
    expect(stars).toHaveLength(5);
    stars.forEach((b) => expect(b).toBeDisabled());
  });

  it('uses the plural vote-count label when there is more than one rating', async () => {
    authState.numericUserId = 999;
    const { container } = render(<PublicationInfoPanel publication={publication({ publisherId: '999', averageRating: 4.5, reviewCount: 3 })} />);

    expect(await screen.findByText('4.5')).toBeInTheDocument();
    expect(screen.getByText('(3 voters)')).toBeInTheDocument();
    expect(screen.queryByText('(1 voter)')).not.toBeInTheDocument();
    // Five stars rendered; a 4.5 average fills four (StarRating floors the fill),
    // the .5 is conveyed by the numeric label next to it.
    expect(starButtons(container)).toHaveLength(5);
    expect(filledStarCount(container)).toBe(4);
  });

  it('shows a "no ratings yet" message (not "0.0") when the app has no votes', async () => {
    authState.numericUserId = 999;
    render(<PublicationInfoPanel publication={publication({ publisherId: '999', averageRating: 0, reviewCount: 0 })} />);

    expect(await screen.findByText('noRatings')).toBeInTheDocument();
    expect(screen.queryByText('0.0')).not.toBeInTheDocument();
  });

  it('shows the aggregate to an anonymous (logged-out) visitor too', async () => {
    authState.numericUserId = null;
    render(<PublicationInfoPanel publication={publication({ averageRating: 5, reviewCount: 1 })} />);

    expect(await screen.findByText('5.0')).toBeInTheDocument();
    expect(screen.getByText('(1 voter)')).toBeInTheDocument();
  });

  it('shows BOTH the aggregate AND the interactive vote control to a reviewer', async () => {
    // numericUserId 999, publisherId 'publisher-1' -> not publisher -> canReview true.
    authState.numericUserId = 999;
    const { container } = render(<PublicationInfoPanel publication={publication({ publisherId: 'publisher-1', averageRating: 5, reviewCount: 1 })} />);

    await waitFor(() => expect(svc.getMyReview).toHaveBeenCalled());
    // The current average is now shown (read-only) so the reviewer sees the
    // consensus WHILE casting their own vote - not only after they've voted.
    expect(await screen.findByText('5.0')).toBeInTheDocument();
    expect(screen.getByText('(1 voter)')).toBeInTheDocument();
    expect(screen.queryByText('noRatings')).not.toBeInTheDocument();
    // Two star rows: the read-only average (5 disabled, amber/filled) plus an
    // empty, ENABLED interactive vote row (this user has no rating yet).
    const stars = starButtons(container);
    expect(stars.filter((b) => !b.disabled)).toHaveLength(5);
    expect(stars.filter((b) => b.disabled)).toHaveLength(5);
    expect(filledStarCount(container)).toBe(5);
  });
});
