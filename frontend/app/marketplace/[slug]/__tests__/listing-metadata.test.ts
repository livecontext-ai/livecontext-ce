import { describe, expect, it, vi, beforeEach } from 'vitest';

/**
 * Regression: every listing named the brand twice.
 *
 * The page built one branded string and handed it to `title` as a plain
 * string, so the root layout's `title.template` ("%s - LiveContext") appended
 * the brand to a title that already ended in "LiveContext Marketplace". The tab,
 * the SERP entry and every share preview read
 * "<listing> - LiveContext Marketplace - LiveContext".
 */

const fetchPublicationBySlug = vi.fn();

vi.mock('@/lib/marketplace/publicPublications', () => ({
  fetchPublicationBySlug: (slug: string) => fetchPublicationBySlug(slug),
  fetchAllPublicPublications: vi.fn(),
}));

// The root layout is imported below for its metadata only. Its font loaders are
// a build-time Next transform with no runtime implementation, so they have to
// be stubbed for the import to resolve at all.
vi.mock('next/font/google', () => ({
  Inter: () => ({ variable: '--font-inter' }),
  Outfit: () => ({ variable: '--font-outfit' }),
}));

import { generateMetadata } from '../page';
import { metadata as rootMetadata } from '@/app/layout';

const listing = {
  id: 'p1',
  publicSlug: 'reconcile',
  title: 'Reconcile',
  description: 'Match bank lines against invoices and flag what does not add up, every morning.',
  publisherName: 'LiveContext Labs',
  publisherId: null,
  publisherHandle: null,
  publisherAvatarUrl: null,
  categorySlug: 'finance',
  categoryName: 'Finance',
  averageRating: 4.5,
  reviewCount: 12,
  useCount: 30,
  publishedAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-02T00:00:00Z',
  publicationType: 'WORKFLOW',
  categoryColor: null,
};

describe('marketplace listing metadata', () => {
  beforeEach(() => {
    fetchPublicationBySlug.mockReset();
  });

  it('is written against a root layout that appends the brand to a plain title', () => {
    // Why `absolute` is the right instrument here, and NOT what it protects
    // against: an `absolute` title is passed through verbatim, so the two
    // tests below are immune to this template either way. What the template
    // decides is the fate of the pages that now carry a BARE title
    // (`/about`, `/changelog`, `/contact`), and those are pinned in
    // `app/__tests__/marketing-metadata.test.ts`. This assertion is here so
    // the reason `absolute` exists at all stays legible next to it.
    expect(rootMetadata.title).toMatchObject({ template: '%s - LiveContext' });
  });

  it('names the brand once, not twice', async () => {
    fetchPublicationBySlug.mockResolvedValue(listing);

    const metadata = await generateMetadata({ params: Promise.resolve({ slug: 'reconcile' }) });

    // `absolute` is what opts this title out of the root template. A plain
    // string here is the bug.
    expect(metadata.title).toEqual({ absolute: 'Reconcile - LiveContext Marketplace' });
    expect(metadata.openGraph?.title).toBe('Reconcile - LiveContext Marketplace');
    expect(metadata.twitter?.title).toBe('Reconcile - LiveContext Marketplace');
  });

  it('still canonicalises to the listing URL', async () => {
    fetchPublicationBySlug.mockResolvedValue(listing);

    const metadata = await generateMetadata({ params: Promise.resolve({ slug: 'reconcile' }) });

    expect(metadata.alternates?.canonical).toBe('https://livecontext.ai/marketplace/reconcile');
  });
});
