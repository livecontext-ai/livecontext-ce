import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { ExternalLink } from 'lucide-react';
import { LandingShell } from '@/components/landing/LandingShell';
import { docsStyles } from '@/app/docs/_components/docsStyles';
import { changelogStyles } from '@/app/changelog/_components/changelogStyles';
import { fetchReleases, formatReleaseDate } from '@/lib/changelog/githubReleases';
import { SELF_HOSTED_GITHUB_URL } from '@/lib/billing/pricing-constants';
import { IS_CE } from '@/lib/edition/edition';

/**
 * Public changelog, rendered from the GitHub Releases of the public CE repo as
 * a vertical timeline (frieze): one dated, versioned entry per shipped release
 * with its notes formatted as markdown.
 *
 * The release pipeline (publish-ce.yml) creates one Release per shipped version
 * with the release notes as its body - that list IS the changelog, so this page
 * never needs a manual update and can never drift from what was actually
 * published. ISR keeps it fresh (a new release shows up within `revalidate`
 * seconds) while shielding the landing site from GitHub API hiccups and rate
 * limits (any failure degrades to the "browse on GitHub" fallback).
 *
 * Like every page outside the [locale] tree, this stays intl-context-free (see
 * the LandingShell contract): hardcoded English, same as /about and /legal.
 */
export const revalidate = 1800;

export const metadata = {
  title: 'Changelog - LiveContext',
  description: 'What we shipped, when. Product updates and release notes.',
  alternates: { canonical: '/changelog' },
  robots: IS_CE ? { index: false, follow: false } : undefined,
};

export default async function ChangelogPage() {
  const releases = await fetchReleases(revalidate);
  const releasesUrl = `${SELF_HOSTED_GITHUB_URL}/releases`;

  return (
    <LandingShell extraStyles={docsStyles + changelogStyles}>
      <div className="max-w-3xl mx-auto px-6 py-16 md:py-20">
        <header className="mb-14">
          <span className="docs-eyebrow">Product updates</span>
          <h1 className="docs-h1">Changelog</h1>
          <p className="docs-lead">
            A running record of what we ship: every released version with its notes,
            newest first. It comes straight from our{' '}
            <a
              href={releasesUrl}
              target="_blank"
              rel="noopener noreferrer"
              style={{ color: 'var(--expression-color)', fontWeight: 500 }}
            >
              public GitHub releases
            </a>
            , so it always matches what actually went out.
          </p>
        </header>

        {releases.length === 0 ? (
          <div className="cl-fallback">
            Release notes are momentarily unavailable. Browse them directly on{' '}
            <a href={releasesUrl} target="_blank" rel="noopener noreferrer">
              GitHub
            </a>
            .
          </div>
        ) : (
          <ol className="cl-timeline">
            {releases.map((release, index) => (
              <li key={release.tag} id={release.tag} className="cl-entry">
                <span
                  className={index === 0 ? 'cl-dot is-latest' : 'cl-dot'}
                  aria-hidden="true"
                />

                <div className="cl-head">
                  <time className="cl-date" dateTime={release.publishedAt}>
                    {formatReleaseDate(release.publishedAt)}
                  </time>
                  <span className="cl-version">{release.tag}</span>
                  {index === 0 && <span className="cl-latest">Latest</span>}
                </div>

                <h2 className="cl-title">
                  <a href={`#${release.tag}`}>{release.title}</a>
                </h2>

                {release.body.trim() !== '' && (
                  <div className="cl-body docs-prose">
                    <ReactMarkdown
                      remarkPlugins={[remarkGfm]}
                      components={{
                        // Demote body headings so the release title stays the
                        // only h2 per entry (h1 = page, h2 = title, h3+ = body).
                        h1: ({ node: _n, ...p }) => <h3 {...p} />,
                        h2: ({ node: _n, ...p }) => <h3 {...p} />,
                        h3: ({ node: _n, ...p }) => <h4 {...p} />,
                        h4: ({ node: _n, ...p }) => <h5 {...p} />,
                        table: ({ node: _n, ...p }) => (
                          <div className="docs-table-wrap">
                            <table {...p} />
                          </div>
                        ),
                      }}
                    >
                      {release.body}
                    </ReactMarkdown>
                  </div>
                )}

                <a
                  className="cl-gh"
                  href={release.htmlUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <ExternalLink className="h-3.5 w-3.5" />
                  View on GitHub
                </a>
              </li>
            ))}
          </ol>
        )}
      </div>
    </LandingShell>
  );
}
