// @vitest-environment jsdom
/**
 * The review queue keeps its page in the address, so a reload stays on the page the admin
 * was working through.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

const getPendingPublications = vi.fn();
const t = vi.hoisted(() => (key: string) => key);

vi.mock('next-intl', () => ({ useTranslations: () => t }));
vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_CLOUD: true }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ hasRole: (r: string) => r === 'ADMIN' }),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getPendingPublications: (...a: unknown[]) => getPendingPublications(...a),
    getModerationStats: vi.fn().mockResolvedValue({ pendingCount: 60 }),
  },
}));
vi.mock('@/components/settings/PageHeader', () => ({ PageHeader: () => null }));
// One stable toast API, as the real hook hands out: the fetch is keyed on addToast.
const toast = vi.hoisted(() => ({ toasts: [], addToast: () => {}, removeToast: () => {} }));
vi.mock('@/components/Toast', () => ({ default: () => null, useToast: () => toast }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('../components/PublicationComparisonView', () => ({ default: () => null }));

import PublicationReviewPage from '../page';

const PAGE = '/en/app/settings/publication-review';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

const publication = (page: number) => ({
  id: `pub-${page}`, title: `Publication on page ${page}`, displayMode: 'WORKFLOW',
  publisherName: 'Ada', publishedAt: '2026-09-01T00:00:00Z',
});

beforeEach(() => {
  getPendingPublications.mockReset().mockImplementation(async (page: number) => ({
    publications: [publication(page)],
    totalPages: 3,
  }));
});
afterEach(cleanup);

describe('PublicationReviewPage - page kept in the address', () => {
  it('opens on the page the address carries', async () => {
    openAt('page=2');
    render(<PublicationReviewPage />);

    expect(await screen.findByText('Publication on page 1')).toBeInTheDocument();
    expect(getPendingPublications).toHaveBeenCalledWith(1, 20);
    expect(getPendingPublications).not.toHaveBeenCalledWith(0, 20);
  });

  it('writes the page as the pager moves', async () => {
    openAt();
    render(<PublicationReviewPage />);
    await screen.findByText('Publication on page 0');

    fireEvent.click(screen.getByRole('button', { name: 'nextPage' }));

    expect(await screen.findByText('Publication on page 1')).toBeInTheDocument();
    expect(fakeFolderRouter.search()).toBe('page=2');
  });

  it('a page past the end of a queue that shrank snaps back to the last one', async () => {
    openAt('page=9');
    render(<PublicationReviewPage />);

    await waitFor(() => expect(getPendingPublications).toHaveBeenCalledWith(2, 20));
    expect(fakeFolderRouter.search()).toBe('page=3');
  });
});
