import Link from 'next/link';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { LandingShell } from '@/components/landing/LandingShell';
import { docsStyles } from '@/app/docs/_components/docsStyles';
import { fetchReleases } from '@/lib/changelog/githubReleases';
import { SELF_HOSTED_GITHUB_URL } from '@/lib/billing/pricing-constants';
import { IS_CE } from '@/lib/edition/edition';

/**
 * Public changelog, rendered from the GitHub Releases of the public CE repo.
 *
 * The release pipeline (publish-ce.yml) creates one Release per shipped
 * version with the release notes as its body - that list IS the changelog, so
 * this page never needs a manual update and can never drift from what was
 * actually published. ISR keeps it fresh (a new release shows up within
 * `revalidate` seconds) while shielding the landing site from GitHub API
 * hiccups and rate limits.
 *
 * Like every page outside the [locale] tree, this stays intl-context-free
 * (see the LandingShell contract): hardcoded English, same as /about and
 * /legal. Dates render as ISO (locale-neutral) on purpose.
 */
export const revalidate = 1800;

export const metadata = {
  title: 'Changelog - LiveContext',
  description: 'What we shipped, when. Product updates and release notes.',
  robots: IS_CE ? { index: false, follow: false } : undefined,
};

function isoDate(publishedAt: string): string {
  return publishedAt.slice(0, 10);
}

export default async function ChangelogPage() {
  const releases = await fetchReleases(revalidate);

  return (
    <LandingShell extraStyles={docsStyles}>
      <div className="max-w-3xl mx-auto px-6 py-20">
        <header className="mb-14">
          <h1
            className="text-3xl md:text-4xl font-bold mb-3"
            style={{
              color: 'var(--text-primary)',
              fontFamily: 'var(--font-outfit), Outfit, sans-serif',
              letterSpacing: '-0.02em',
            }}
          >
            Changelog
          </h1>
          <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
            What we shipped, when. Sourced live from our{' '}
            <a
              href={`${SELF_HOSTED_GITHUB_URL}/releases`}
              target="_blank"
              rel="noopener noreferrer"
              style={{ color: 'var(--expression-color)' }}
            >
              GitHub releases
            </a>
            .
          </p>
        </header>

        {releases.length === 0 ? (
          <div
            className="rounded-xl px-6 py-10 text-center text-sm"
            style={{
              background: 'var(--bg-tertiary)',
              border: '1px solid var(--border-color)',
              color: 'var(--text-muted)',
            }}
          >
            Release notes are momentarily unavailable. Browse them directly on{' '}
            <a
              href={`${SELF_HOSTED_GITHUB_URL}/releases`}
              target="_blank"
              rel="noopener noreferrer"
              style={{ color: 'var(--expression-color)' }}
            >
              GitHub
            </a>
            .
          </div>
        ) : (
          <div className="space-y-14">
            {releases.map((release) => (
              <article key={release.tag} id={release.tag}>
                <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 mb-4">
                  <h2
                    className="text-xl md:text-2xl font-semibold"
                    style={{
                      color: 'var(--text-primary)',
                      fontFamily: 'var(--font-outfit), Outfit, sans-serif',
                    }}
                  >
                    <Link href={`#${release.tag}`} style={{ color: 'inherit' }}>
                      {release.title}
                    </Link>
                  </h2>
                  <time
                    dateTime={release.publishedAt}
                    className="text-xs tracking-wide"
                    style={{ color: 'var(--text-muted)' }}
                  >
                    {isoDate(release.publishedAt)}
                  </time>
                  <a
                    href={release.htmlUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-xs"
                    style={{ color: 'var(--expression-color)' }}
                  >
                    View on GitHub
                  </a>
                </div>
                {release.body.trim() !== '' && (
                  <div className="docs-prose">
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>{release.body}</ReactMarkdown>
                  </div>
                )}
              </article>
            ))}
          </div>
        )}
      </div>
    </LandingShell>
  );
}
