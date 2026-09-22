import Link from 'next/link';
import { LandingShell } from '@/components/landing/LandingShell';
import { docsStyles } from '@/app/docs/_components/docsStyles';
import { modelsStyles } from './_components/modelsStyles';
import ModelsCatalog from './_components/ModelsCatalog';
import { CATALOG_MODELS, MODEL_CATALOG_STATS } from './_components/modelsData';
import { PROVIDER_PARAM, providerHref, resolveProviderParam } from './_components/modelsQuery';
import { getProviderDisplayName } from '@/lib/ai-providers/providerIcons';
import { docsHref } from '@/lib/docs/docsHostRewrite';
import { IS_CE } from '@/lib/edition/edition';
import { socialCard } from '@/lib/seo/socialCard';

/**
 * Public model directory: every model LiveContext can run, on a chronological
 * strip and in a searchable list.
 *
 * The data is generated from the LIVE public model catalog by
 * scripts/models/build_models_page.py, so a context window or a price on this
 * page is the one the platform actually carries, never a number typed by hand.
 *
 * `?provider=<key>` opens the page filtered, which is how the footer's Models
 * column reaches it. The filter is resolved HERE, on the server, rather than from
 * `useSearchParams` in the client component: the list is the content, and reading
 * the param in a client hook would bail the whole subtree out to client rendering,
 * leaving the HTML, and therefore a crawler, with no models at all.
 *
 * Like every page outside the [locale] tree, this stays intl-context-free (see
 * the LandingShell contract): hardcoded English, same as /about and /changelog.
 */
type ModelsPageProps = {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

const DESCRIPTION =
  `Every model LiveContext runs: ${MODEL_CATALOG_STATS.distinctModels} models from ` +
  `${MODEL_CATALOG_STATS.directProviders} providers plus ${MODEL_CATALOG_STATS.aggregatorModels} more through ` +
  'OpenRouter, with their release date, context window, list price and capabilities, ' +
  'on one chronological page.';

export async function generateMetadata({ searchParams }: ModelsPageProps) {
  const provider = resolveProviderParam((await searchParams)[PROVIDER_PARAM]);

  // Bare titles: the root layout's template already appends " - LiveContext".
  const base = {
    title: 'AI models',
    description: DESCRIPTION,
    alternates: { canonical: '/models' },
    ...socialCard({ title: 'AI models', description: DESCRIPTION, path: '/models' }),
    robots: IS_CE ? { index: false, follow: false } : undefined,
  };
  if (!provider) return base;

  const label = getProviderDisplayName(provider);
  const count = CATALOG_MODELS.filter((model) => model.provider === provider).length;
  return {
    ...base,
    title: `${label} models`,
    description:
      `The ${count} ${label} models LiveContext runs, with their release date, context window, ` +
      'list price per million tokens and capabilities.',
    // Self-canonical, not a canonical back to /models: a provider view is a real
    // subset a visitor can want, not a duplicate of the full list.
    alternates: { canonical: providerHref(provider) },
  };
}

export default async function ModelsPage({ searchParams }: ModelsPageProps) {
  const provider = resolveProviderParam((await searchParams)[PROVIDER_PARAM]);

  return (
    <LandingShell extraStyles={docsStyles + modelsStyles}>
      <div className="max-w-6xl mx-auto px-6 py-16 md:py-20">
        <header className="mb-12">
          <span className="docs-eyebrow">AI</span>
          <h1 className="docs-h1">Models</h1>
          <p className="docs-lead">
            Every model LiveContext can run, and when each one landed. Bring your own key and pay the
            provider directly, or run on ours and pay in credits. Swap the model on any agent, workflow
            step or chat without touching anything else you built.
          </p>
          <p className="docs-lead" style={{ marginTop: '0.75rem' }}>
            {`The catalog holds ${MODEL_CATALOG_STATS.distinctModels} models across `}
            {`${MODEL_CATALOG_STATS.directProviders} providers, plus ${MODEL_CATALOG_STATS.aggregatorModels} `}
            {'more reachable through OpenRouter. Listed below are the '}
            {`${CATALOG_MODELS.length} of them: one row per model family rather than one per moving alias or `}
            {'dated snapshot, and only where the provider published a release date.'}
          </p>
        </header>

        {/* The stat tiles live INSIDE the catalogue, not here: they count what is
            listed, so they have to follow the filter the visitor is changing. */}
        <ModelsCatalog initialProvider={provider} />

        <div
          className="mt-16 rounded-2xl px-6 py-8 text-sm leading-relaxed"
          style={{ background: 'var(--bg-secondary)', border: '1px solid var(--border-color)', color: 'var(--text-secondary)' }}
        >
          <p>
            A model shows up here once it is in the catalog, so this page follows the platform rather
            than a marketing calendar. How keys, CLI bridge providers and per-model settings work is
            covered in{' '}
            <Link href={docsHref(undefined, 'models')} prefetch={false} style={{ color: 'var(--expression-color)', fontWeight: 500 }}>
              the models documentation
            </Link>
            .
          </p>
        </div>
      </div>
    </LandingShell>
  );
}
