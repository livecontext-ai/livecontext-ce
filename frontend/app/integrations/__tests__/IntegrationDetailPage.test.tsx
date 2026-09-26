/**
 * @vitest-environment jsdom
 */
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PublicIntegration, PublicIntegrationDetail } from '@/lib/integrations/integrations';

const fetchIntegration = vi.fn();
const fetchTopIntegrations = vi.fn();
vi.mock('@/lib/integrations/publicIntegrations', () => ({
  fetchIntegration: (...args: unknown[]) => fetchIntegration(...args),
  fetchTopIntegrations: (...args: unknown[]) => fetchTopIntegrations(...args),
  fetchAllIntegrations: vi.fn(),
  fetchIntegrations: vi.fn(),
  PUBLIC_INTEGRATIONS_REVALIDATE_SECONDS: 3600,
  LANDING_INTEGRATIONS_REVALIDATE_SECONDS: 600,
}));

vi.mock('@/components/landing/LandingShell', () => ({
  LandingShell: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/app/[locale]/_landing/SignInButton', () => ({
  default: ({ children }: { children: React.ReactNode }) => <button type="button">{children}</button>,
}));

const jsonLd: unknown[] = [];
vi.mock('@/components/seo/JsonLd', () => ({
  default: ({ data }: { data: Record<string, unknown> }) => {
    jsonLd.push(data);
    return null;
  },
}));

const notFound = vi.fn(() => {
  throw new Error('NEXT_NOT_FOUND');
});
vi.mock('next/navigation', () => ({ notFound: () => notFound() }));

import IntegrationPage, { generateMetadata } from '../[slug]/page';

function integration(overrides: Partial<PublicIntegration> = {}): PublicIntegration {
  return {
    slug: 'slack',
    name: 'Slack',
    description: 'Post messages, manage channels and read conversations from your workflows.',
    iconSlug: 'slack',
    iconUrl: null,
    toolCount: 45,
    authType: 'oauth2',
    ...overrides,
  };
}

function detail(overrides: Partial<PublicIntegrationDetail> = {}): PublicIntegrationDetail {
  return {
    integration: integration(),
    documentation: 'https://api.slack.com',
    tools: [
      { name: 'send_message', description: 'Post a message to a channel', method: 'POST' },
      { name: 'list_channels', description: 'List the channels you can see', method: 'GET' },
    ],
    toolsTruncated: false,
    ...overrides,
  };
}

async function renderPage(slug = 'slack') {
  render(await IntegrationPage({ params: Promise.resolve({ slug }) }));
}

beforeEach(() => {
  jsonLd.length = 0;
  notFound.mockClear();
  fetchIntegration.mockReset();
  fetchTopIntegrations.mockReset();
  fetchTopIntegrations.mockResolvedValue({ integrations: [], totalElements: 0, truncated: false });
});

describe('integration detail page', () => {
  it('renders the integration and every endpoint the catalog exposes', async () => {
    fetchIntegration.mockResolvedValue(detail());

    await renderPage();

    expect(screen.getByRole('heading', { level: 1, name: /Slack integration/ })).toBeTruthy();
    // The endpoint list IS the substance of this page: it is real catalog data,
    // not prose written about the integration.
    expect(screen.getByText('send_message')).toBeTruthy();
    expect(screen.getByText('Post a message to a channel')).toBeTruthy();
    expect(screen.getByText('list_channels')).toBeTruthy();
  });

  it('shows the tool count and the auth type a visitor scans for', async () => {
    fetchIntegration.mockResolvedValue(detail({
      integration: integration({ authType: 'none', toolCount: 3 }),
    }));

    await renderPage();

    expect(screen.getByText('3 tools')).toBeTruthy();
    expect(screen.getByText('No key needed')).toBeTruthy();
  });

  it('says when the endpoint list is truncated instead of implying it is complete', async () => {
    fetchIntegration.mockResolvedValue(detail({
      integration: integration({ toolCount: 400 }),
      toolsTruncated: true,
    }));

    await renderPage();

    expect(screen.getByText(/Showing the first 2 of 400 endpoints/)).toBeTruthy();
  });

  it('404s an unknown or non-public slug', async () => {
    fetchIntegration.mockResolvedValue(null);

    await expect(renderPage('nope')).rejects.toThrow('NEXT_NOT_FOUND');
    expect(notFound).toHaveBeenCalled();
  });

  it('lets a failed catalog read surface as an error, NOT as a 404', async () => {
    fetchIntegration.mockRejectedValue(new Error('gateway down'));

    // A 404 here would invite search engines to drop a page that still exists;
    // the thrown error renders the error boundary, i.e. a 500 they retry.
    await expect(renderPage()).rejects.toThrow('gateway down');
    expect(notFound).not.toHaveBeenCalled();
  });

  it('links on to other integrations, never to itself', async () => {
    fetchIntegration.mockResolvedValue(detail());
    fetchTopIntegrations.mockResolvedValue({
      integrations: [
        integration(),
        integration({ slug: 'github', name: 'GitHub' }),
        integration({ slug: 'stripe', name: 'Stripe' }),
      ],
      totalElements: 3,
      truncated: false,
    });

    await renderPage();

    expect(screen.getByRole('link', { name: /GitHub/ })).toBeTruthy();
    expect(screen.getByRole('link', { name: /Stripe/ })).toBeTruthy();
    // A "related" block that links to the page you are on is a dead link in a
    // crawl and a confusing one for a reader.
    expect(screen.queryByRole('link', { name: /^Slack/ })).toBeNull();
  });

  it('keeps the page up when the related block cannot be read', async () => {
    fetchIntegration.mockResolvedValue(detail());
    fetchTopIntegrations.mockResolvedValue({ integrations: [], totalElements: 0, truncated: true });

    await renderPage();

    expect(screen.getByRole('heading', { level: 1, name: /Slack integration/ })).toBeTruthy();
    expect(screen.queryByText('Popular integrations')).toBeNull();
  });

  it('describes itself and its place in the site tree for search engines', async () => {
    fetchIntegration.mockResolvedValue(detail());

    await renderPage();

    const software = jsonLd.find(
      (e) => (e as { '@type': string })['@type'] === 'SoftwareApplication',
    ) as Record<string, unknown>;
    expect(software.url).toBe('https://livecontext.ai/integrations/slack');
    const breadcrumb = jsonLd.find(
      (e) => (e as { '@type': string })['@type'] === 'BreadcrumbList',
    ) as { itemListElement: Array<{ name: string }> };
    expect(breadcrumb.itemListElement.map((i) => i.name)).toEqual(['Home', 'Integrations', 'Slack']);
  });
});

describe('integration detail metadata', () => {
  it('canonicalises to the integration URL', async () => {
    fetchIntegration.mockResolvedValue(detail());

    const metadata = await generateMetadata({ params: Promise.resolve({ slug: 'slack' }) });

    expect(metadata.alternates?.canonical).toBe('https://livecontext.ai/integrations/slack');
  });

  it('names the brand once, not twice', async () => {
    // Regression: the title was a plain string, so the root layout's
    // `title.template` ("%s - LiveContext") appended the brand to a title that
    // already ended in it, and all ~980 integration pages rendered
    // "Slack integration - LiveContext - LiveContext". `absolute` opts out of
    // the template; the share blocks never had one, so they keep the string.
    fetchIntegration.mockResolvedValue(detail());

    const metadata = await generateMetadata({ params: Promise.resolve({ slug: 'slack' }) });

    expect(metadata.title).toEqual({ absolute: 'Slack integration - LiveContext' });
    expect(metadata.openGraph?.title).toBe('Slack integration - LiveContext');
    expect(metadata.twitter?.title).toBe('Slack integration - LiveContext');
  });

  it('leaves the share image to the per-integration opengraph-image route', async () => {
    // Config-based `images` win over the file-based `opengraph-image.tsx` next to the
    // page, so setting them here would silently put the generic site-wide card back on
    // every shared integration, with no error anywhere.
    fetchIntegration.mockResolvedValue(detail());

    const metadata = await generateMetadata({ params: Promise.resolve({ slug: 'slack' }) });

    expect(metadata.openGraph).not.toHaveProperty('images');
    expect(metadata.twitter).not.toHaveProperty('images');
  });

  it('indexes a page with real content', async () => {
    fetchIntegration.mockResolvedValue(detail());

    const metadata = await generateMetadata({ params: Promise.resolve({ slug: 'slack' }) });

    expect(metadata.robots).toBeUndefined();
  });

  it('keeps a thin page out of the index while still rendering it', async () => {
    fetchIntegration.mockResolvedValue(detail({
      integration: integration({ toolCount: 1, description: 'An API.' }),
    }));

    const metadata = await generateMetadata({ params: Promise.resolve({ slug: 'slack' }) });

    // Enough near-empty pages drag down the ranking of the whole domain. `follow`
    // stays true so the links out of it still carry weight.
    expect(metadata.robots).toEqual({ index: false, follow: true });
  });

  it('returns nothing for an unknown slug rather than inventing a title', async () => {
    fetchIntegration.mockResolvedValue(null);

    expect(await generateMetadata({ params: Promise.resolve({ slug: 'nope' }) })).toEqual({});
  });
});
