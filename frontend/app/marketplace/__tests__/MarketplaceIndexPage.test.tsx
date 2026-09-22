/**
 * @vitest-environment jsdom
 */
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PublicPublicationSummary } from '@/lib/marketplace/publicPublications';

// The reader is `server-only` and talks to the gateway; the page's contract with
// it is what this suite is about, so it is stubbed here.
const fetchAllPublicPublications = vi.fn();
const fetchVerifiedPublisherHandles =
  vi.fn<(handles: Array<string | null | undefined>) => Promise<Set<string>>>();
vi.mock('@/lib/marketplace/publicPublications', () => ({
  fetchAllPublicPublications: (...args: unknown[]) => fetchAllPublicPublications(...args),
  fetchVerifiedPublisherHandles: (handles: Array<string | null | undefined>) =>
    fetchVerifiedPublisherHandles(handles),
  fetchMarketplacePage: vi.fn(),
}));

// The chrome mounts a theme provider and the whole footer; none of it is under
// test and it drags in intl-context-free assertions of its own.
vi.mock('@/components/landing/LandingShell', () => ({
  LandingShell: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

// Capture the structured data instead of parsing it back out of a script tag.
const jsonLd: unknown[] = [];
vi.mock('@/components/seo/JsonLd', () => ({
  default: ({ data }: { data: Record<string, unknown> }) => {
    jsonLd.push(data);
    return null;
  },
}));

// The card has its own suite. Here it only has to prove it was rendered.
vi.mock('../_components/PublicationCardSsr', () => ({
  default: ({ publication, publisherVerified }: {
    publication: PublicPublicationSummary;
    publisherVerified?: boolean;
  }) => (
    <article data-testid="card" data-publisher-verified={publisherVerified ? 'true' : 'false'}>
      {publication.title}
    </article>
  ),
}));

import MarketplaceIndexPage from '../page';

function publication(index: number): PublicPublicationSummary {
  return {
    id: `pub-${index}`,
    publicSlug: `app-${index}`,
    title: `App ${index}`,
    description: `What app ${index} does, in a sentence long enough to be a real description of it.`,
    publisherName: 'Ada',
    publisherId: 'user-1',
    publisherHandle: 'ada',
    publisherAvatarUrl: null,
    categorySlug: null,
    categoryName: null,
    categoryColor: null,
    averageRating: 0,
    reviewCount: 0,
    useCount: 0,
    publishedAt: null,
    updatedAt: null,
    publicationType: 'WORKFLOW',
    displayMode: 'APPLICATION',
    creditsPerUse: 0,
    hasShowcase: true,
    nodeIcons: [],
    agentCount: 0,
    interfaceCount: 0,
    workflowCount: 0,
    skillCount: 0,
    datasourceCount: 0,
    planSnapshot: null,
  };
}

/** Only the parts of the emitted structured data these tests read back. */
interface CollectionPageJsonLd {
  mainEntity: {
    numberOfItems: number;
    itemListElement: Array<{
      url?: string;
      item: {
        url: string;
        '@type': string;
        name: string;
        description: string;
        image: string;
        aggregateRating?: unknown;
      };
    }>;
  };
}

async function renderPage() {
  render(await MarketplaceIndexPage());
}

beforeEach(() => {
  jsonLd.length = 0;
  fetchAllPublicPublications.mockReset();
  fetchVerifiedPublisherHandles.mockReset();
  fetchVerifiedPublisherHandles.mockResolvedValue(new Set<string>());
});

describe('marketplace index page', () => {
  it('renders the WHOLE catalogue, not one API page of it', async () => {
    // The regression: the page called `fetchMarketplacePage()`, whose default
    // size is 24, so every listing past the 24th was linked from no crawlable
    // page anywhere on the site.
    const publications = Array.from({ length: 79 }, (_, i) => publication(i));
    fetchAllPublicPublications.mockResolvedValue({ publications, truncated: false });

    await renderPage();

    expect(screen.getAllByTestId('card')).toHaveLength(79);
    expect(screen.getByText(/79 published listings/)).toBeTruthy();
  });

  it('walks the catalogue rather than reading a single page', async () => {
    fetchAllPublicPublications.mockResolvedValue({ publications: [], truncated: false });
    await renderPage();
    expect(fetchAllPublicPublications).toHaveBeenCalled();
  });

  it('describes every linkable listing in the ItemList, with its own image', async () => {
    fetchAllPublicPublications.mockResolvedValue({
      publications: [publication(0), publication(1)],
      truncated: false,
    });

    await renderPage();

    const collection = jsonLd[0] as CollectionPageJsonLd;
    expect(collection.mainEntity.numberOfItems).toBe(2);
    const [first] = collection.mainEntity.itemListElement;
    // The URL lives INSIDE `item`, not beside it: a sibling `url` and a nested
    // `item` are Google's two different carousel shapes, and emitting both asks
    // the parser to pick.
    expect(first.url).toBeUndefined();
    expect(first.item).toMatchObject({
      '@type': 'SoftwareApplication',
      name: 'App 0',
      url: 'https://livecontext.ai/marketplace/app-0',
      // The listing's OWN OpenGraph card. Eighty entries sharing one picture is
      // what makes a listing page look templated.
      image: 'https://livecontext.ai/marketplace/app-0/opengraph-image',
    });
    expect(first.item.description).toContain('What app 0 does');
  });

  it('never claims a rating a listing does not have', async () => {
    fetchAllPublicPublications.mockResolvedValue({
      publications: [publication(0)],
      truncated: false,
    });

    await renderPage();

    const collection = jsonLd[0] as CollectionPageJsonLd;
    // An aggregateRating with reviewCount 0 is invalid structured data and
    // earns a Search Console error.
    expect(collection.mainEntity.itemListElement[0].item.aggregateRating).toBeUndefined();
  });

  it('leaves a slugless listing out of the structured data but still shows it', async () => {
    const noSlug = { ...publication(1), publicSlug: null };
    fetchAllPublicPublications.mockResolvedValue({
      publications: [publication(0), noSlug],
      truncated: false,
    });

    await renderPage();

    const collection = jsonLd[0] as {
      mainEntity: { numberOfItems: number; itemListElement: unknown[] };
    };
    // It has no canonical URL to advertise, but it is still a real listing.
    expect(collection.mainEntity.numberOfItems).toBe(1);
    expect(collection.mainEntity.itemListElement).toHaveLength(1);
    expect(screen.getAllByTestId('card')).toHaveLength(2);
  });

  it('says so rather than showing an empty grid when the read fails', async () => {
    fetchAllPublicPublications.mockResolvedValue({ publications: [], truncated: true });
    await renderPage();

    expect(screen.getByText(/No published listings right now/)).toBeTruthy();
    expect(screen.queryAllByTestId('card')).toHaveLength(0);
  });

  it('asks ONCE for every author on the page, and marks the verified ones', async () => {
    // One call for the whole grid, not one per card. It hands over the handle of every
    // listing, duplicates included - deduping is the reader's job and is covered where
    // it lives (publicPublications.test.ts), not here, where the reader is mocked.
    const byAda = publication(0);
    const alsoAda = publication(1);
    const byLinus = { ...publication(2), publisherHandle: 'linus', publisherName: 'Linus' };
    fetchAllPublicPublications.mockResolvedValue({
      publications: [byAda, alsoAda, byLinus],
      truncated: false,
    });
    fetchVerifiedPublisherHandles.mockResolvedValue(new Set(['linus']));

    await renderPage();

    expect(fetchVerifiedPublisherHandles).toHaveBeenCalledTimes(1);
    expect(fetchVerifiedPublisherHandles).toHaveBeenCalledWith(['ada', 'ada', 'linus']);
    const cards = screen.getAllByTestId('card');
    expect(cards.map((c) => c.getAttribute('data-publisher-verified')))
        .toEqual(['false', 'false', 'true']);
  });

  it('matches an author case-insensitively, so a capitalised handle still gets its check', async () => {
    // The lookup answers lowercased handles; a row may store any casing.
    fetchAllPublicPublications.mockResolvedValue({
      publications: [{ ...publication(0), publisherHandle: 'Ada' }],
      truncated: false,
    });
    fetchVerifiedPublisherHandles.mockResolvedValue(new Set(['ada']));

    await renderPage();

    expect(screen.getByTestId('card').getAttribute('data-publisher-verified')).toBe('true');
  });

  it('warns when the walk stopped early, so a partial catalogue is never silent', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    fetchAllPublicPublications.mockResolvedValue({
      publications: [publication(0)],
      truncated: true,
    });

    await renderPage();

    expect(warn).toHaveBeenCalledWith(expect.stringContaining('stopped early'));
    warn.mockRestore();
  });
});
