/**
 * @vitest-environment jsdom
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// `server-only` throws outside a React Server Component; stub it for the unit
// test (the convention in publicPublications.test.ts).
vi.mock('server-only', () => ({}));

// Plain anchors: the assertions are about which URLs the card exposes, and
// next/link's prefetch machinery needs an app-router context this render has no
// reason to build.
vi.mock('next/link', () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>{children}</a>
  ),
}));

// The live preview has its own suite and needs an IntersectionObserver. Here we
// only care WHETHER the card mounts it, so stand in for it and record the props.
const previewProps: Record<string, unknown>[] = [];
vi.mock('../MarketplaceCardPreview', () => ({
  default: (props: Record<string, unknown>) => {
    previewProps.push(props);
    return <div data-testid="live-preview" />;
  },
}));

import PublicationCardSsr from '../PublicationCardSsr';
import type { PublicPublicationSummary } from '@/lib/marketplace/publicPublications';

function publication(
  overrides: Partial<PublicPublicationSummary> = {},
): PublicPublicationSummary {
  return {
    id: 'pub-1',
    publicSlug: 'stock-sentiment-pulse',
    title: 'Stock Sentiment Pulse',
    description: 'Reads the day of financial news and scores how the market feels about a ticker.',
    publisherName: 'Ada',
    publisherId: 'user-1',
    publisherHandle: 'ada',
    publisherAvatarUrl: null,
    categorySlug: 'finance',
    categoryName: 'Finance',
    categoryColor: null,
    averageRating: 4.5,
    reviewCount: 2,
    useCount: 12,
    publishedAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
    publicationType: 'WORKFLOW',
    displayMode: 'APPLICATION',
    creditsPerUse: 0,
    hasShowcase: true,
    nodeIcons: [],
    agentCount: 0,
    interfaceCount: 1,
    workflowCount: 1,
    skillCount: 0,
    datasourceCount: 0,
    planSnapshot: null,
    ...overrides,
  };
}

describe('PublicationCardSsr', () => {
  it('puts the whole SEO payload in the markup without any client fetch', () => {
    // The point of this card: a crawler that runs no JavaScript still gets the
    // heading, the sentence that describes the listing, and the link to it.
    render(<PublicationCardSsr publication={publication()} />);

    expect(screen.getByRole('heading', { name: 'Stock Sentiment Pulse' })).toBeTruthy();
    expect(screen.getByText(/scores how the market feels/)).toBeTruthy();
    expect(screen.getByText('Finance')).toBeTruthy();
  });

  it('sits at the heading level the page gives it', () => {
    // Default h2 under the marketplace index's h1; h3 on the publisher profile,
    // whose grid is already under a "Published apps" h2. A card that always
    // claimed h2 flattened that page's outline.
    const { container } = render(<PublicationCardSsr publication={publication()} />);
    expect(container.querySelector('h2')?.textContent).toBe('Stock Sentiment Pulse');

    const { container: nested } = render(
      <PublicationCardSsr publication={publication()} headingLevel="h3" />,
    );
    expect(nested.querySelector('h3')?.textContent).toBe('Stock Sentiment Pulse');
    expect(nested.querySelector('h2')).toBeNull();
  });

  it('links the card to the listing, once', () => {
    const { container } = render(<PublicationCardSsr publication={publication()} />);

    const cardLinks = [...container.querySelectorAll('a')].filter(
      (a) => a.getAttribute('href') === '/marketplace/stock-sentiment-pulse',
    );
    // One destination per card: several anchors to the same URL split the
    // signal and give a crawler nothing extra.
    expect(cardLinks).toHaveLength(1);
  });

  it('renders a listing with no slug as text rather than a link that would 404', () => {
    const { container } = render(
      <PublicationCardSsr publication={publication({ publicSlug: null })} />,
    );

    expect(screen.getByRole('heading', { name: 'Stock Sentiment Pulse' })).toBeTruthy();
    expect(
      [...container.querySelectorAll('a')].some((a) =>
        (a.getAttribute('href') ?? '').startsWith('/marketplace/'),
      ),
    ).toBe(false);
  });

  it('links the publisher only when they have a public handle', () => {
    const { container } = render(<PublicationCardSsr publication={publication()} />);
    expect(container.querySelector('a[href="/u/ada"]')).toBeTruthy();

    const { container: anonymous } = render(
      <PublicationCardSsr publication={publication({ publisherHandle: null })} />,
    );
    expect(anonymous.querySelector('a[href^="/u/"]')).toBeNull();
    expect(anonymous.textContent).toContain('Ada');
  });

  it('mounts the live preview only for a listing that froze a showcase', () => {
    previewProps.length = 0;
    const { container: withShowcase } = render(
      <PublicationCardSsr publication={publication({ hasShowcase: true })} />,
    );
    expect(withShowcase.querySelector('[data-testid="live-preview"]')).toBeTruthy();
    expect(previewProps[0]).toMatchObject({ publicationId: 'pub-1' });

    previewProps.length = 0;
    const { container: withoutShowcase } = render(
      <PublicationCardSsr publication={publication({ hasShowcase: false })} />,
    );
    // Nothing to render means the fetch would 404: the cover is the thumbnail.
    expect(withoutShowcase.querySelector('[data-testid="live-preview"]')).toBeNull();
    expect(previewProps).toHaveLength(0);
  });

  it('hides the rating until someone has actually rated the listing', () => {
    const { container } = render(
      <PublicationCardSsr publication={publication({ reviewCount: 0, averageRating: 0 })} />,
    );
    // A bare "0.0" on every new listing reads as a bad score, not as "no reviews".
    expect(container.textContent).not.toContain('0.0');
  });

  it('says Free rather than "0 credits" when a listing costs nothing', () => {
    const { container } = render(<PublicationCardSsr publication={publication()} />);
    expect(container.textContent).toContain('Free');
    expect(container.textContent).not.toContain('0 credits');

    const { container: paid } = render(
      <PublicationCardSsr publication={publication({ creditsPerUse: 25 })} />,
    );
    // Cloud prices in credits; a self-hosted install prices the same listing in
    // dollars, exactly like the in-app card's price pill.
    expect(paid.textContent).toContain('25 credits / run');
  });

  it('draws the integration glyphs a listing carries on to its cover', () => {
    // This is the thumbnail a crawler, a JS-less visitor and a listing with no
    // frozen showcase all get, so it has to say something about the listing.
    const { container } = render(
      <PublicationCardSsr
        publication={publication({
          nodeIcons: [{ nodeId: 'n1', nodeKind: 'mcp', iconSlug: 'gmail', isMcp: true }],
        })}
      />,
    );

    expect(container.querySelector('svg, img')).toBeTruthy();
  });

  it('counts a single install in the singular', () => {
    const { container } = render(
      <PublicationCardSsr publication={publication({ useCount: 1 })} />,
    );
    expect(container.textContent).toContain('1 install');
    expect(container.textContent).not.toContain('1 installs');
  });

  it('renders a listing with no description without an empty paragraph', () => {
    const { container } = render(
      <PublicationCardSsr publication={publication({ description: '' })} />,
    );
    expect(container.querySelector('p')).toBeNull();
  });

  it('leaves the card clickable underneath the live preview', () => {
    // This repo has already shipped this bug once: a live preview iframe laid
    // over a tile ate the tile's click. The preview host covers the ENTIRE
    // thumbnail, which is the card's largest click target, and `inert` is what
    // makes a click fall through to the anchor behind it.
    //
    // jsdom implements neither `inert` nor hit testing, so this asserts the
    // attribute rather than the click. The click itself is checked against a
    // real browser in `e2e/marketplace/marketplace-public-index.spec.ts`.
    previewProps.length = 0;
    const { container } = render(<PublicationCardSsr publication={publication()} />);

    // The half this file can see: the preview sits INSIDE the card's anchor, so
    // a click that falls through it has somewhere to land. That it falls
    // through at all is the preview's own `inert` host, asserted in
    // MarketplaceCardPreview.test.tsx.
    const preview = container.querySelector('[data-testid="live-preview"]');
    expect(preview).toBeTruthy();
    expect(preview?.closest('a')?.getAttribute('href')).toBe('/marketplace/stock-sentiment-pulse');
  });

  it('shows the publisher with the same avatar chip as the in-app card, glyphs beside it', () => {
    const { container } = render(
      <PublicationCardSsr
        publication={publication({
          publisherId: '42',
          nodeIcons: [{ nodeId: 'n1', nodeKind: 'mcp', iconSlug: 'gmail', isMcp: true }],
        })}
      />,
    );
    const avatar = container.querySelector('img[src="/api/proxy/users/42/avatar"]');
    expect(avatar, 'publisher avatar image from the public avatar route').toBeTruthy();
    expect(avatar?.getAttribute('alt')).toBe('Ada');
    // The chip and the glyphs share one row, like PublicationCard's footer.
    const row = avatar?.closest('div.flex.items-center');
    expect(row?.querySelector('svg, img:not([src*="/avatar"])')).toBeTruthy();
  });

  it('shows the verified check right after a verified author name', () => {
    const { getByRole } = render(
      <PublicationCardSsr publication={publication()} publisherVerified />,
    );

    // Labelled in English: this card renders outside the [locale] tree.
    const badge = getByRole('img', { name: 'Verified account' });
    expect(badge).toBeTruthy();
    // Immediately AFTER the name, the way Instagram and X place it.
    expect(badge.previousElementSibling?.textContent).toBe('Ada');
  });

  it('shows no check for an author who is not verified', () => {
    const { queryByRole } = render(<PublicationCardSsr publication={publication()} />);

    expect(queryByRole('img', { name: 'Verified account' })).toBeNull();
  });
});
