import type { Metadata } from 'next';
import Link from 'next/link';
import JsonLd from '@/components/seo/JsonLd';
import { LandingShell } from '@/components/landing/LandingShell';
import SignInButton from '@/app/[locale]/_landing/SignInButton';
import { IS_CE } from '@/lib/edition';
import { fetchAllIntegrations } from '@/lib/integrations/publicIntegrations';
import {
  integrationPath,
  integrationSummary,
  PUBLIC_SITE_LOCALE,
} from '@/lib/integrations/integrations';
import { IntegrationCard } from '@/components/integrations/IntegrationCard';
import IntegrationSearch from '@/components/integrations/IntegrationSearch';
import { docsHref } from '@/lib/docs/docsHostRewrite';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

/** How many entries the page's ItemList advertises. See where it is used. */
const MAX_JSON_LD_ITEMS = 100;

/**
 * Rendered per request, NOT prerendered at build time.
 *
 * <p>The same reasoning as `/marketplace`, and it has already been observed in
 * production there: the gateway is unreachable from the CI builder, so a
 * prerender bakes an EMPTY directory, and every frontend replica then serves
 * that copy until it individually revalidates. A crawler landing on the wrong
 * replica would see a site claiming 1000+ integrations and listing none.
 *
 * <p>The upstream read keeps its own hourly cache window, so this costs one
 * gateway walk per hour per replica, not one per page view.
 */
export const dynamic = 'force-dynamic';

const TITLE = 'Integrations - connect every tool your stack runs on';
const DESCRIPTION =
  'Browse every app and API LiveContext connects to. Each endpoint is a tool your '
  + 'workflows and AI agents can call, with the connection set up once and reused everywhere.';

export const metadata: Metadata = {
  title: TITLE,
  description: DESCRIPTION,
  alternates: { canonical: '/integrations' },
  // Spelled out in full: Next merges metadata shallowly per top-level field, so a
  // partial override here would DROP the root layout's og:image.
  openGraph: {
    siteName: 'LiveContext',
    title: `${TITLE} - LiveContext`,
    description: DESCRIPTION,
    url: `${SITE_URL}/integrations`,
    type: 'website',
    images: [
      {
        url: '/og-image.jpg',
        width: 1200,
        height: 630,
        alt: 'LiveContext: one message in, a working automation out.',
      },
    ],
  },
  twitter: {
    card: 'summary_large_image',
    title: `${TITLE} - LiveContext`,
    description: DESCRIPTION,
    images: ['/og-image.jpg'],
  },
  // Self-hosted deployments must never index marketing pages (same rule as the
  // landing page, /marketplace, /compare and /changelog). `follow: true` matches
  // the detail page beside it: robots.ts already disallows everything on CE, so
  // the two differing was an inconsistency with no upside.
  robots: IS_CE ? { index: false, follow: true } : undefined,
};

export default async function IntegrationsDirectoryPage() {
  const { integrations, truncated } = await fetchAllIntegrations();

  if (truncated) {
    // The page still renders what it got; this is the only signal that what it got
    // is not the whole catalog. Silence here would look identical to a healthy
    // render, with integrations quietly missing from the index and from every
    // internal link on it.
    console.warn(
      `[integrations] catalog walk stopped early after ${integrations.length} integrations; `
      + 'the directory is incomplete (page cap reached or a gateway read failed).',
    );
  }

  // Summed from the rows already in hand rather than asked for separately: it is
  // the count the product actually exposes (one tool per endpoint), and computing
  // it here means it can never disagree with the cards below it.
  const toolTotal = integrations.reduce((sum, integration) => sum + integration.toolCount, 0);

  // Capped. The list itself stays complete on the page (that is what makes every
  // integration one click from the header), but an ItemList of 900+ members is far
  // past what search engines process and would add ~250 KB of JSON to a document
  // that already carries the cards.
  const jsonLdItems = integrations.slice(0, MAX_JSON_LD_ITEMS);

  const itemListJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'CollectionPage',
    name: TITLE,
    description: DESCRIPTION,
    url: `${SITE_URL}/integrations`,
    mainEntity: {
      '@type': 'ItemList',
      numberOfItems: jsonLdItems.length,
      itemListElement: jsonLdItems.map((integration, index) => ({
        '@type': 'ListItem',
        position: index + 1,
        name: integration.name,
        description: integrationSummary(integration),
        url: `${SITE_URL}${integrationPath(integration.slug)}`,
      })),
    },
  };

  const breadcrumbJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Home', item: SITE_URL },
      { '@type': 'ListItem', position: 2, name: 'Integrations', item: `${SITE_URL}/integrations` },
    ],
  };

  return (
    <LandingShell>
      {!IS_CE && integrations.length > 0 && <JsonLd data={itemListJsonLd} />}
      {!IS_CE && <JsonLd data={breadcrumbJsonLd} />}

      <div className="mx-auto w-full max-w-6xl px-4 py-10 sm:px-6 md:py-14">
        <header className="mb-8 max-w-3xl">
          <h1 className="text-3xl font-semibold md:text-4xl" style={{ color: 'var(--text-primary)' }}>
            Integrations
          </h1>
          <p className="mt-4 text-base leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            Connect the tools your work already runs on. Every endpoint of every integration is
            exactly one tool, so a workflow step and an AI agent call the same thing, with the
            connection set up once and reused everywhere.
          </p>
          {/* Counted, never claimed: both numbers come from the rows on this page, so
              the headline cannot outlive the catalog it describes. */}
          {integrations.length > 0 && (
            <p className="mt-3 text-sm" style={{ color: 'var(--text-muted)' }}>
              {integrations.length.toLocaleString(PUBLIC_SITE_LOCALE)} integrations ·{' '}
              {toolTotal.toLocaleString(PUBLIC_SITE_LOCALE)} ready-made tools
            </p>
          )}
        </header>

        {integrations.length === 0 ? (
          <div
            className="rounded-xl border p-8 text-center text-sm"
            style={{ borderColor: 'var(--border-color)', color: 'var(--text-secondary)' }}
          >
            <p>The catalog is not reachable right now. Please try again in a moment.</p>
          </div>
        ) : (
          // totalCount is the count the HEADER renders, not the backend's: when the
          // walk truncates the two differ, and a page stating two different numbers
          // for the same thing on one screen is what the header comment rules out.
          <IntegrationSearch totalCount={integrations.length}>
            {/* Server-rendered, in full. The list is what makes each integration page
                reachable by a crawler in one click; paging it would leave everything
                past the first page discoverable only through the sitemap, which is a
                hint rather than a path. */}
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              {integrations.map((integration) => (
                <IntegrationCard key={integration.slug} integration={integration} />
              ))}
            </div>
          </IntegrationSearch>
        )}

        <section
          className="mt-14 rounded-2xl border p-8 text-center"
          style={{ borderColor: 'var(--border-color)', background: 'var(--bg-secondary)' }}
        >
          <h2 className="text-xl font-semibold" style={{ color: 'var(--text-primary)' }}>
            Missing one?
          </h2>
          <p
            className="mx-auto mt-3 max-w-2xl text-sm leading-relaxed"
            style={{ color: 'var(--text-secondary)' }}
          >
            Anything with an HTTP API works without waiting for us: register it as your own
            integration, or drop a raw HTTP request into a workflow. Both give agents the same
            typed tools the catalog does.
          </p>
          <div className="mt-6 flex flex-wrap items-center justify-center gap-3">
            <SignInButton
              variant="primary"
              cta="integrations_start_free"
              className="inline-flex h-9 items-center justify-center rounded-xl px-4 text-sm font-medium transition-colors hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer"
            >
              Start free
            </SignInButton>
            <Link
              href={docsHref(undefined, 'integrations')}
              prefetch={false}
              className="inline-flex h-9 items-center justify-center rounded-xl border px-4 text-sm font-medium transition-colors hover:bg-[var(--bg-tertiary)]"
              style={{ borderColor: 'var(--border-color)', color: 'var(--text-primary)' }}
            >
              Read the docs
            </Link>
          </div>
        </section>
      </div>
    </LandingShell>
  );
}
