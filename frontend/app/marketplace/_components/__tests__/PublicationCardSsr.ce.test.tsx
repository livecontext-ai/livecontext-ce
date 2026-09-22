/**
 * @vitest-environment jsdom
 */
import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// A self-hosted install prices in dollars, managed cloud in credits. The
// edition is resolved at module load, so this needs its own file rather than a
// branch inside the main suite.
vi.mock('@/lib/edition', () => ({ IS_CE: true, IS_MANAGED_CLOUD: false }));

// `server-only` throws outside a React Server Component; stub it for the unit
// test (the convention in publicPublications.test.ts).
vi.mock('server-only', () => ({}));

vi.mock('next/link', () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>{children}</a>
  ),
}));

vi.mock('../MarketplaceCardPreview', () => ({ default: () => <div /> }));

import PublicationCardSsr from '../PublicationCardSsr';
import type { PublicPublicationSummary } from '@/lib/marketplace/publicPublications';

const PUBLICATION: PublicPublicationSummary = {
  id: 'pub-1',
  publicSlug: 'stock-sentiment-pulse',
  title: 'Stock Sentiment Pulse',
  description: 'Scores how the market feels about a ticker.',
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
  creditsPerUse: 25,
  hasShowcase: false,
  nodeIcons: [],
  agentCount: 0,
  interfaceCount: 0,
  workflowCount: 0,
  skillCount: 0,
  datasourceCount: 0,
  planSnapshot: null,
};

describe('PublicationCardSsr on a self-hosted install', () => {
  it('prices in dollars, like the in-app price pill', () => {
    const { container } = render(<PublicationCardSsr publication={PUBLICATION} />);

    expect(container.textContent).toContain('$25 / run');
    // Telling a CE visitor a price in a currency their install does not use is
    // the drift this mirrors PricePill to avoid.
    expect(container.textContent).not.toContain('credits');
  });

  it('never shows a verified check, even when the caller says the author is verified', () => {
    // Verified badges are a managed-cloud feature. This is the presentation half of
    // the lock: the backend already refuses to mark anyone verified here.
    const { queryByRole } = render(
      <PublicationCardSsr publication={PUBLICATION} publisherVerified />,
    );

    expect(queryByRole('img', { name: 'Verified account' })).toBeNull();
  });
});
