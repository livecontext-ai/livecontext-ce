/**
 * @vitest-environment jsdom
 */
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PublicIntegration } from '@/lib/integrations/integrations';

// The reader is `server-only` and talks to the gateway; the page's contract with
// it is what this suite is about, so it is stubbed here.
const fetchAllIntegrations = vi.fn();
vi.mock('@/lib/integrations/publicIntegrations', () => ({
  fetchAllIntegrations: (...args: unknown[]) => fetchAllIntegrations(...args),
  fetchTopIntegrations: vi.fn(),
  fetchIntegration: vi.fn(),
  fetchIntegrations: vi.fn(),
  PUBLIC_INTEGRATIONS_REVALIDATE_SECONDS: 3600,
  LANDING_INTEGRATIONS_REVALIDATE_SECONDS: 600,
}));

// The chrome mounts a theme provider and the whole footer (which does a catalog
// read of its own); none of it is under test.
vi.mock('@/components/landing/LandingShell', () => ({
  LandingShell: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/app/[locale]/_landing/SignInButton', () => ({
  default: ({ children }: { children: React.ReactNode }) => <button type="button">{children}</button>,
}));

// Capture the structured data instead of parsing it back out of a script tag.
const jsonLd: unknown[] = [];
vi.mock('@/components/seo/JsonLd', () => ({
  default: ({ data }: { data: Record<string, unknown> }) => {
    jsonLd.push(data);
    return null;
  },
}));

import IntegrationsDirectoryPage from '../page';

function integration(index: number, overrides: Partial<PublicIntegration> = {}): PublicIntegration {
  return {
    slug: `api-${index}`,
    name: `API ${index}`,
    description: `What API ${index} does, in a sentence.`,
    iconSlug: `api${index}`,
    iconUrl: null,
    toolCount: 10,
    authType: 'oauth2',
    ...overrides,
  };
}

interface CollectionJsonLd {
  '@type': string;
  mainEntity: {
    numberOfItems: number;
    itemListElement: Array<{ name: string; url: string }>;
  };
}

async function renderPage() {
  render(await IntegrationsDirectoryPage());
}

beforeEach(() => {
  jsonLd.length = 0;
  fetchAllIntegrations.mockReset();
});

describe('integrations directory page', () => {
  it('renders the WHOLE catalog, not one API page of it', async () => {
    // Every integration has to be one click from a crawlable page: anything the
    // index omits is discoverable only through the sitemap, which is a hint
    // rather than a path, and earns no internal link.
    const integrations = Array.from({ length: 240 }, (_, i) => integration(i));
    fetchAllIntegrations.mockResolvedValue({ integrations, totalElements: 240, truncated: false });

    await renderPage();

    expect(screen.getAllByRole('link', { name: /^API \d+/ })).toHaveLength(240);

    // The structured data is capped where the rendered list is not: an ItemList of
    // 900+ members is past what search engines process, while the cards are what
    // put every integration one click from the header. numberOfItems counts what is
    // actually listed, so the cap cannot make the JSON-LD claim more than it says.
    const collection = jsonLd.find(
      (entry) => (entry as CollectionJsonLd)['@type'] === 'CollectionPage',
    ) as CollectionJsonLd;
    expect(collection.mainEntity.itemListElement).toHaveLength(100);
    expect(collection.mainEntity.numberOfItems).toBe(100);
    // A minute, against a default of twenty seconds. This renders 240 cards and then asks jsdom
    // for the accessible name of every link on the page, which is real work: about nineteen
    // seconds on an idle machine and past the default on a busy one. It failed in full-suite runs
    // and passed alone, which reads as flakiness and is not - the assertion is sound, the budget
    // was for a unit test. Shrinking the catalogue would be the other fix and would weaken what
    // this proves, which is that a SECOND page of results is rendered rather than the first.
  }, 60_000);

  it('walks the catalog rather than reading a single page', async () => {
    fetchAllIntegrations.mockResolvedValue({ integrations: [], totalElements: 0, truncated: false });

    await renderPage();

    expect(fetchAllIntegrations).toHaveBeenCalled();
  });

  it('counts the integrations and their tools from the rows it rendered', async () => {
    fetchAllIntegrations.mockResolvedValue({
      integrations: [integration(0, { toolCount: 45 }), integration(1, { toolCount: 12 })],
      totalElements: 2,
      truncated: false,
    });

    await renderPage();

    // Counted, never claimed: both numbers come from the rows on the page, so
    // the headline cannot outlive the catalog it describes. Matched as one
    // string because the search box also announces the count, and a loose
    // /2 integrations/ would match either.
    expect(screen.getByText(/2 integrations · 57 ready-made tools/)).toBeTruthy();
    // And the search box announces the SAME number, not the backend's total: a page
    // stating two different catalog sizes on one screen is what the header comment
    // above rules out, and they diverge exactly when a page of the walk failed.
    expect(screen.getByText(/^2 integrations, most-used first\.$/)).toBeTruthy();
  });

  it('lists every integration in the ItemList with its own URL', async () => {
    fetchAllIntegrations.mockResolvedValue({
      integrations: [integration(0), integration(1)],
      totalElements: 2,
      truncated: false,
    });

    await renderPage();

    const collection = jsonLd.find(
      (entry) => (entry as CollectionJsonLd)['@type'] === 'CollectionPage',
    ) as CollectionJsonLd;
    expect(collection.mainEntity.numberOfItems).toBe(2);
    expect(collection.mainEntity.itemListElement[0]).toMatchObject({
      name: 'API 0',
      url: 'https://livecontext.ai/integrations/api-0',
    });
  });

  it('emits a breadcrumb even when the catalog read came back empty', async () => {
    fetchAllIntegrations.mockResolvedValue({ integrations: [], totalElements: 0, truncated: true });

    await renderPage();

    // The ItemList is dropped (an empty list is not a listing), but the page is
    // still a real page in the site tree.
    expect(jsonLd.some((e) => (e as { '@type': string })['@type'] === 'BreadcrumbList')).toBe(true);
    expect(jsonLd.some((e) => (e as { '@type': string })['@type'] === 'CollectionPage')).toBe(false);
  });

  it('says the catalog is unreachable rather than showing an empty grid', async () => {
    fetchAllIntegrations.mockResolvedValue({ integrations: [], totalElements: 0, truncated: true });

    await renderPage();

    expect(screen.getByText(/catalog is not reachable right now/i)).toBeTruthy();
  });

  it('warns when the walk stopped early, so a partial catalog is never silent', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    fetchAllIntegrations.mockResolvedValue({
      integrations: [integration(0)],
      totalElements: 700,
      truncated: true,
    });

    await renderPage();

    // Silence here would look identical to a healthy render, with integrations
    // quietly missing from the index and from every internal link on it.
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('stopped early'));
    warn.mockRestore();
  });
});
