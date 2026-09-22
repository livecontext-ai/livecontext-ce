import type { Metadata } from 'next';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { ArrowLeft, ExternalLink } from 'lucide-react';
import JsonLd from '@/components/seo/JsonLd';
import { LandingShell } from '@/components/landing/LandingShell';
import SignInButton from '@/app/[locale]/_landing/SignInButton';
import { IS_CE } from '@/lib/edition';
import { docsHref } from '@/lib/docs/docsHostRewrite';
import { fetchIntegration, fetchTopIntegrations } from '@/lib/integrations/publicIntegrations';
import {
  authTypeLabel,
  integrationPath,
  integrationSummary,
  isIndexableIntegration,
} from '@/lib/integrations/integrations';
import { IntegrationLogo } from '@/components/integrations/IntegrationLogo';
import { IntegrationCard } from '@/components/integrations/IntegrationCard';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

/**
 * ISR rather than SSG.
 *
 * <p>There is no build-time list of slugs to pre-render: the catalog grows
 * whenever a batch of APIs is imported, and a new integration must be reachable
 * without a deploy. `dynamicParams` therefore stays at its default (true),
 * unlike `/compare`, whose content lives in the repo.
 */
// Literal on purpose: Next requires route segment config to be statically
// analyzable, so importing PUBLIC_INTEGRATIONS_REVALIDATE_SECONDS here fails the
// build with "Invalid segment configuration export". Keep the two in step.
export const revalidate = 3600;

/** How many other integrations the footer of this page links on to. */
const RELATED_COUNT = 8;

export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug: string }>;
}): Promise<Metadata> {
  const { slug } = await params;
  const detail = await fetchIntegration(slug);
  if (!detail) return {};

  const { integration } = detail;
  const url = `${SITE_URL}${integrationPath(slug)}`;
  const title = `${integration.name} integration - LiveContext`;
  const description = integrationSummary(integration);

  // Thin pages render normally but stay out of the index, and out of the sitemap,
  // which reads the same predicate.
  const noIndex = IS_CE || !isIndexableIntegration(integration);

  return {
    // `absolute` because this title already names the brand. Left as a plain
    // string it is fed to the root layout's `title.template` ("%s - LiveContext")
    // and every one of the ~980 integration pages rendered "<name> integration -
    // LiveContext - LiveContext". The share blocks below take no template, so
    // they keep the plain string.
    title: { absolute: title },
    description,
    alternates: { canonical: url },
    // Both blocks spelled out in full: Next merges metadata shallowly per
    // top-level field, so a partial override drops the root layout's values.
    openGraph: {
      siteName: 'LiveContext',
      title,
      description,
      url,
      type: 'article',
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
      title,
      description,
      images: ['/og-image.jpg'],
    },
    robots: noIndex ? { index: false, follow: true } : undefined,
  };
}

export default async function IntegrationPage({
  params,
}: {
  params: Promise<{ slug: string }>;
}) {
  const { slug } = await params;

  // fetchIntegration returns null ONLY for "no such public integration" and
  // THROWS when the catalog could not be read, so a gateway blip renders the
  // error boundary (a 500 a crawler retries) instead of a 404 that would invite
  // it to drop a page that still exists.
  const detail = await fetchIntegration(slug);
  if (!detail) notFound();

  const { integration, documentation, tools, toolsTruncated } = detail;

  // Best-effort: this block is a navigation aid, so a failed read costs the block
  // and nothing else. One extra is requested because this integration is very
  // likely in its own top list.
  const { integrations: popular } = await fetchTopIntegrations(RELATED_COUNT + 1);
  const related = popular.filter((other) => other.slug !== integration.slug).slice(0, RELATED_COUNT);

  const url = `${SITE_URL}${integrationPath(slug)}`;
  const auth = authTypeLabel(integration.authType);

  const softwareJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'SoftwareApplication',
    name: `${integration.name} integration for LiveContext`,
    description: integrationSummary(integration),
    url,
    applicationCategory: 'BusinessApplication',
    operatingSystem: 'Web',
    offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' },
  };

  const breadcrumbJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Home', item: SITE_URL },
      { '@type': 'ListItem', position: 2, name: 'Integrations', item: `${SITE_URL}/integrations` },
      { '@type': 'ListItem', position: 3, name: integration.name, item: url },
    ],
  };

  return (
    <LandingShell>
      {!IS_CE && <JsonLd data={softwareJsonLd} />}
      {!IS_CE && <JsonLd data={breadcrumbJsonLd} />}

      <div className="mx-auto w-full max-w-5xl px-4 py-10 sm:px-6 md:py-14">
        <Link
          href="/integrations"
          className="inline-flex items-center gap-1.5 text-sm transition-opacity hover:opacity-80"
          style={{ color: 'var(--text-muted)' }}
        >
          <ArrowLeft className="h-3.5 w-3.5" aria-hidden="true" />
          All integrations
        </Link>

        <header className="mt-6 flex flex-col gap-5 sm:flex-row sm:items-start sm:gap-6">
          <div
            className="flex h-16 w-16 shrink-0 items-center justify-center rounded-2xl border"
            style={{ borderColor: 'var(--border-color)', background: 'var(--bg-secondary)' }}
          >
            <IntegrationLogo integration={integration} size={36} />
          </div>
          <div className="min-w-0">
            <h1 className="text-3xl font-semibold md:text-4xl" style={{ color: 'var(--text-primary)' }}>
              {integration.name} integration
            </h1>
            {integration.description && (
              <p className="mt-3 text-base leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                {integration.description}
              </p>
            )}
            <div className="mt-4 flex flex-wrap items-center gap-2 text-xs">
              <Badge>
                {integration.toolCount} {integration.toolCount === 1 ? 'tool' : 'tools'}
              </Badge>
              {auth && <Badge>{auth}</Badge>}
              {documentation && (
                <a
                  href={documentation}
                  target="_blank"
                  rel="noopener noreferrer nofollow"
                  className="inline-flex items-center gap-1 rounded-full border px-2.5 py-1 transition-colors hover:bg-[var(--bg-tertiary)]"
                  style={{ borderColor: 'var(--border-color)', color: 'var(--text-secondary)' }}
                >
                  API docs
                  <ExternalLink className="h-3 w-3" aria-hidden="true" />
                </a>
              )}
            </div>
          </div>
        </header>

        <div className="mt-8 flex flex-wrap gap-3">
          <SignInButton
            variant="primary"
            cta="integration_detail_start_free"
            className="inline-flex h-9 items-center justify-center rounded-xl px-4 text-sm font-medium transition-colors hover:bg-[var(--accent-hover)] active:scale-[0.98] cursor-pointer"
          >
            Connect {integration.name}
          </SignInButton>
          <Link
            href={docsHref(undefined, 'integrations')}
            prefetch={false}
            className="inline-flex h-9 items-center justify-center rounded-xl border px-4 text-sm font-medium transition-colors hover:bg-[var(--bg-tertiary)]"
            style={{ borderColor: 'var(--border-color)', color: 'var(--text-primary)' }}
          >
            How integrations work
          </Link>
        </div>

        <Section title={`What you can do with ${integration.name}`}>
          <p className="mb-5 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            Each of these is one operation on one endpoint, available both as a workflow step and as
            a tool an AI agent can call on its own. Nothing bundles several operations into one
            action, so what a run did stays readable afterwards.
          </p>
          {/* Not decoration. V457 puts individual endpoints (the social publishing
              ones) behind PRO on the cloud, so a page that lists them under a "free"
              banner is advertising something a free account cannot run. The gate is
              cloud-only: a self-hosted install clears every bar. */}
          <p className="mb-5 text-sm leading-relaxed" style={{ color: 'var(--text-muted)' }}>
            Most endpoints run on the free plan. A few, mainly the ones that publish on your
            behalf, need a paid plan on the cloud; a self-hosted install has no such limit.
          </p>
          {tools.length === 0 ? (
            <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
              The endpoint list is not available right now.
            </p>
          ) : (
            <>
              <ul className="grid grid-cols-1 gap-2 md:grid-cols-2">
                {tools.map((tool) => (
                  <li
                    key={tool.name}
                    className="rounded-xl border p-3"
                    style={{ borderColor: 'var(--border-color)', background: 'var(--bg-primary)' }}
                  >
                    <div className="flex items-center gap-2">
                      {tool.method && (
                        <span
                          className="rounded px-1.5 py-px font-mono text-[10px] uppercase tracking-wide"
                          style={{ background: 'var(--bg-tertiary)', color: 'var(--text-muted)' }}
                        >
                          {tool.method}
                        </span>
                      )}
                      <span className="truncate text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                        {tool.name}
                      </span>
                    </div>
                    {tool.description && (
                      <p className="mt-1.5 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                        {tool.description}
                      </p>
                    )}
                  </li>
                ))}
              </ul>
              {toolsTruncated && (
                <p className="mt-4 text-sm" style={{ color: 'var(--text-muted)' }}>
                  Showing the first {tools.length} of {integration.toolCount} endpoints. The rest are
                  in the product, searchable from the workflow builder and from chat.
                </p>
              )}
            </>
          )}
        </Section>

        <Section title={`How to connect ${integration.name}`}>
          <ol className="space-y-4">
            <Step index={1} title="Create your workspace">
              Sign up free. Nothing to install, and self-hosting the same build is an option at any
              point.
            </Step>
            <Step index={2} title={`Connect ${integration.name} once`}>
              {auth === 'No key needed'
                ? `${integration.name} needs no credential, so its tools work immediately.`
                : `Add your ${integration.name} connection in Settings. Every tool from this `
                  + 'integration reuses it, and secrets are encrypted at rest and visible only to you.'}
            </Step>
            <Step index={3} title="Describe the job in chat">
              The workflow builds itself in front of you and picks the endpoints it needs. Drag the
              steps yourself on the canvas whenever you would rather be explicit.
            </Step>
          </ol>
        </Section>

        {related.length > 0 && (
          <Section title="Popular integrations">
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
              {related.map((other) => (
                <IntegrationCard key={other.slug} integration={other} />
              ))}
            </div>
            <p className="mt-5 text-sm">
              <Link
                href="/integrations"
                className="underline underline-offset-2 transition-opacity hover:opacity-80"
                style={{ color: 'var(--text-secondary)' }}
              >
                Browse the whole catalog
              </Link>
            </p>
          </Section>
        )}
      </div>
    </LandingShell>
  );
}

function Badge({ children }: { children: React.ReactNode }) {
  return (
    <span
      className="inline-flex items-center rounded-full border px-2.5 py-1"
      style={{ borderColor: 'var(--border-color)', color: 'var(--text-secondary)' }}
    >
      {children}
    </span>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="mt-12">
      <h2 className="mb-4 text-xl font-semibold" style={{ color: 'var(--text-primary)' }}>
        {title}
      </h2>
      {children}
    </section>
  );
}

function Step({
  index,
  title,
  children,
}: {
  index: number;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <li className="flex gap-3">
      <span
        className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-medium"
        style={{ background: 'var(--bg-tertiary)', color: 'var(--text-secondary)' }}
        aria-hidden="true"
      >
        {index}
      </span>
      <div>
        <p className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
          {title}
        </p>
        <p className="mt-1 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
          {children}
        </p>
      </div>
    </li>
  );
}
