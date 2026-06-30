// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { PublicationReview, WorkflowPublication } from '@/lib/api/orchestrator/types';

// Same vitest shims as the other PublicationInfoPanel suites: next-intl + the
// locale-aware navigation can't resolve under jsdom, so stub them. The
// translation mock returns the key verbatim, so aria-labels are the i18n key
// (e.g. "deleteComment", "removeRating") - which is exactly what we query by.
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

// Viewer is user 999, the publication's publisher is "publisher-1", so the
// viewer canReview, and a review with reviewerId "999" is THEIR own.
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ numericUserId: 999 }),
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
  deleteComment: vi.fn(),
  deleteRating: vi.fn(),
  deleteReview: vi.fn(),
  submitReview: vi.fn(),
}));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getCommentCount: (...a: unknown[]) => svc.getCommentCount(...a),
    getMyReview: (...a: unknown[]) => svc.getMyReview(...a),
    getReviews: (...a: unknown[]) => svc.getReviews(...a),
    deleteComment: (...a: unknown[]) => svc.deleteComment(...a),
    deleteRating: (...a: unknown[]) => svc.deleteRating(...a),
    deleteReview: (...a: unknown[]) => svc.deleteReview(...a),
    submitReview: (...a: unknown[]) => svc.submitReview(...a),
  },
}));

vi.mock('@/components/credentials/CredentialWizard', () => ({ CredentialWizard: () => null }));
vi.mock('@/components/applications/ApplicationActivationButton', () => ({ ApplicationActivationButton: () => null }));

import { PublicationInfoPanel } from '../PublicationInfoPanel';

function publication(): WorkflowPublication {
  return {
    id: 'pub-1',
    workflowId: 'workflow-1',
    title: 'Weather App',
    description: 'Forecast workflow',
    creditsPerUse: 0,
    publisherId: 'publisher-1',
    publisherName: 'Publisher Co',
    status: 'ACTIVE',
    visibility: 'PUBLIC',
    useCount: 12,
    totalCreditsEarned: 0,
    averageRating: 4.5,
    reviewCount: 3,
    publishedAt: '2026-05-22T10:00:00Z',
  };
}

function ownReview(over: Partial<PublicationReview> = {}): PublicationReview {
  return {
    id: 'rev-own',
    publicationId: 'pub-1',
    reviewerId: '999',
    reviewerName: 'Me',
    rating: 4,
    comment: 'My honest take',
    createdAt: '2026-06-20T10:00:00Z',
    updatedAt: '2026-06-20T10:00:00Z',
    replyCount: 0,
    ...over,
  };
}

describe('PublicationInfoPanel field-scoped deletes (comment vs rating are separate)', () => {
  beforeEach(() => {
    Object.values(svc).forEach((m) => m.mockReset());
    svc.getCommentCount.mockResolvedValue(1);
    svc.getReviews.mockResolvedValue({ reviews: [ownReview()], totalPages: 1, totalElements: 1 });
    svc.deleteComment.mockResolvedValue(undefined);
    svc.deleteRating.mockResolvedValue(undefined);
    svc.deleteReview.mockResolvedValue(undefined);
  });

  it('deletes only the comment from the comment itself - calls deleteComment, never deleteReview', async () => {
    svc.getMyReview.mockResolvedValue(ownReview());
    render(<PublicationInfoPanel publication={publication()} defaultOpen hideActivationButton />);

    // Go to the Comments tab. The delete control lives ON the comment in the
    // list (aria-label = "deleteComment"), so its presence proves the own
    // ReviewItem rendered.
    fireEvent.click(screen.getByRole('tab', { name: /commentsTab/i }));
    // Wait for the lazy comment-list load to settle so the count below isolates
    // the post-delete refetch.
    await waitFor(() => expect(svc.getReviews).toHaveBeenCalledTimes(1));
    const myReviewCallsBefore = svc.getMyReview.mock.calls.length;

    fireEvent.click(await screen.findByLabelText('deleteComment'));

    await waitFor(() => expect(svc.deleteComment).toHaveBeenCalledWith('pub-1'));
    expect(svc.deleteReview).not.toHaveBeenCalled();
    expect(svc.deleteRating).not.toHaveBeenCalled();
    // The parent reconciles state after the delete: re-fetch my review (rating
    // may survive) and reload the comment list (the entry drops out).
    await waitFor(() => expect(svc.getMyReview.mock.calls.length).toBeGreaterThan(myReviewCallsBefore));
    await waitFor(() => expect(svc.getReviews.mock.calls.length).toBeGreaterThan(1));
  });

  it('removes only the rating from the Info tab stars - calls deleteRating, never deleteReview', async () => {
    svc.getMyReview.mockResolvedValue(ownReview());
    render(<PublicationInfoPanel publication={publication()} defaultOpen hideActivationButton />);

    // Info tab is the default; the rating-remove control appears once the user's
    // rating (4) loads.
    fireEvent.click(await screen.findByLabelText('removeRating'));

    await waitFor(() => expect(svc.deleteRating).toHaveBeenCalledWith('pub-1'));
    expect(svc.deleteReview).not.toHaveBeenCalled();
    expect(svc.deleteComment).not.toHaveBeenCalled();
  });

  it('hides the rating-remove control when the user has a comment but no rating', async () => {
    svc.getMyReview.mockResolvedValue(ownReview({ rating: null }));
    render(<PublicationInfoPanel publication={publication()} defaultOpen hideActivationButton />);

    // Let getMyReview settle so formRating reflects the (absent) rating.
    await waitFor(() => expect(svc.getMyReview).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByLabelText('removeRating')).not.toBeInTheDocument());
  });
});
