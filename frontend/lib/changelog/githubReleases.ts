import { SELF_HOSTED_GITHUB_URL } from '@/lib/billing/pricing-constants';

/**
 * GitHub Releases as the changelog's single source of truth.
 *
 * The release pipeline (publish-ce.yml) creates one public GitHub Release per
 * shipped version, notes included. The landing /changelog page renders THAT
 * list instead of keeping a copy, so the two can never drift: publishing a
 * release is the only step, and the page follows via ISR.
 */
export interface ChangelogRelease {
  /** Tag, e.g. "v0.1.5". */
  tag: string;
  /** Human title (falls back to the tag when the release has no name). */
  title: string;
  /** ISO publication timestamp. */
  publishedAt: string;
  /** Markdown body (may be empty). */
  body: string;
  /** Canonical GitHub URL of the release. */
  htmlUrl: string;
}

/** Releases API endpoint derived from the canonical public-repo constant. */
export function releasesApiUrl(): string {
  const repoPath = SELF_HOSTED_GITHUB_URL.replace(/^https:\/\/github\.com\//, '').replace(/\/$/, '');
  return `https://api.github.com/repos/${repoPath}/releases?per_page=25`;
}

/**
 * Map the raw GitHub payload into view models. Defensive by design: drafts and
 * prereleases are dropped, malformed entries are skipped, a non-array payload
 * yields an empty list (the page renders its fallback instead of crashing).
 * Output is sorted newest-first regardless of API ordering.
 */
export function mapReleases(payload: unknown): ChangelogRelease[] {
  if (!Array.isArray(payload)) {
    return [];
  }
  const releases: ChangelogRelease[] = [];
  for (const entry of payload) {
    if (typeof entry !== 'object' || entry === null) continue;
    const raw = entry as Record<string, unknown>;
    if (raw.draft === true || raw.prerelease === true) continue;
    const tag = typeof raw.tag_name === 'string' ? raw.tag_name : '';
    const publishedAt = typeof raw.published_at === 'string' ? raw.published_at : '';
    const htmlUrl = typeof raw.html_url === 'string' ? raw.html_url : '';
    if (!tag || !publishedAt || !htmlUrl) continue;
    const name = typeof raw.name === 'string' && raw.name.trim() !== '' ? raw.name : tag;
    releases.push({
      tag,
      title: name,
      publishedAt,
      body: typeof raw.body === 'string' ? raw.body : '',
      htmlUrl,
    });
  }
  releases.sort((a, b) => b.publishedAt.localeCompare(a.publishedAt));
  return releases;
}

/**
 * Fetch the published releases with ISR caching. Any failure (network, rate
 * limit, non-200) degrades to an empty list: the changelog page must never
 * take the landing site down because GitHub is unreachable.
 */
export async function fetchReleases(revalidateSeconds = 1800): Promise<ChangelogRelease[]> {
  try {
    const res = await fetch(releasesApiUrl(), {
      headers: { Accept: 'application/vnd.github+json' },
      next: { revalidate: revalidateSeconds },
    });
    if (!res.ok) {
      return [];
    }
    return mapReleases(await res.json());
  } catch {
    return [];
  }
}

const MONTHS = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
];

/**
 * Format an ISO timestamp as a short, locale-neutral date such as "Jul 2, 2026".
 * The changelog renders outside the `[locale]` tree (see the LandingShell
 * contract), so it stays hardcoded English like /about and /legal - and it
 * parses the ISO string by hand (no `toLocale*`, no `Date`) to avoid both the
 * browser-locale trap and any timezone shift on the calendar day.
 */
export function formatReleaseDate(publishedAt: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(publishedAt);
  if (!match) return publishedAt;
  const [, year, month, day] = match;
  const monthLabel = MONTHS[Number(month) - 1] ?? month;
  return `${monthLabel} ${Number(day)}, ${year}`;
}
