// Pure, data-free helpers for the public blog. Kept separate from `posts.ts`
// (which imports the `.md` bodies) so this logic is unit-testable without a
// Markdown loader. English-only and locale-free by design: the blog renders
// outside the `app/[locale]` tree, so nothing here formats through a browser
// locale.

export interface BlogPost {
  /** URL slug: `/blog/<slug>`. Unique. */
  slug: string;
  title: string;
  /** Publish date as an ISO `YYYY-MM-DD` string. */
  date: string;
  /** One or two sentence summary shown on the index cards and in metadata. */
  excerpt: string;
  /** Byline authors (personas). One or two names, shown as "A and B". */
  authors: string[];
  tags: string[];
  /** Public path to the cover image (under `/public`), e.g. `/blog/<slug>.jpg`. */
  cover: string;
  /** Descriptive alt text for the cover (accessibility + image SEO). */
  coverAlt: string;
  /** Raw Markdown body, rendered with react-markdown + remark-gfm. */
  content: string;
}

/** A copy of `posts` sorted newest first (descending date, slug as a stable tiebreak). */
export function sortPostsByDateDesc(posts: BlogPost[]): BlogPost[] {
  return [...posts].sort((a, b) => {
    if (a.date !== b.date) return a.date < b.date ? 1 : -1;
    return a.slug < b.slug ? -1 : 1;
  });
}

/** The post whose slug matches, or `undefined` when none does. */
export function findPostBySlug(posts: BlogPost[], slug: string): BlogPost | undefined {
  return posts.find((post) => post.slug === slug);
}

// Scripts that are not whitespace-delimited: CJK ideographs (+ extension A and
// the compatibility block), kana, and Hangul syllables. Splitting these on
// whitespace counts a whole paragraph as ONE token, so a Chinese article used to
// report a small fraction of its real reading time (the zh translation of a
// 22-minute English post announced 7 minutes). They are counted per character
// instead.
const NON_SPACED_SCRIPT = /[㐀-䶿一-鿿豈-﫿぀-ヿ가-힯]/g;

const WORDS_PER_MINUTE = 200;
// Chinese is read per character, not per word, and far denser per character than
// English is per letter. 400 characters/minute is a common published pace and
// lands within ~10% of the English estimate for the same article translated.
const CHARS_PER_MINUTE = 400;

/**
 * Estimated reading time in whole minutes, minimum 1.
 *
 * Mixed-script safe: characters from non-whitespace-delimited scripts (CJK, kana,
 * Hangul) are counted individually at {@link CHARS_PER_MINUTE}, and whatever
 * remains is counted as whitespace-separated words at {@link WORDS_PER_MINUTE}.
 * A post that mixes both (Chinese prose around English model names, figures and
 * code) is therefore charged for both parts exactly once.
 */
export function estimateReadingMinutes(content: string): number {
  const chars = content.match(NON_SPACED_SCRIPT)?.length ?? 0;
  const rest = content.replace(NON_SPACED_SCRIPT, ' ');
  const words = rest.trim().split(/\s+/).filter(Boolean).length;
  return Math.max(1, Math.round(words / WORDS_PER_MINUTE + chars / CHARS_PER_MINUTE));
}

/**
 * Join author names into a byline: one name as is, two as "A and B", three or
 * more with an Oxford-style list "A, B and C". Empty list yields "". The
 * conjunction is localizable (e.g. "et", "und", "y") and defaults to "and".
 */
export function formatAuthors(authors: string[], conjunction = 'and'): string {
  if (authors.length === 0) return '';
  if (authors.length === 1) return authors[0];
  return `${authors.slice(0, -1).join(', ')} ${conjunction} ${authors[authors.length - 1]}`;
}

const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

/**
 * Format an ISO `YYYY-MM-DD` date for display. English (the default, and the
 * canonical `/blog`) uses a deterministic hand-rolled `Month D, YYYY` (no Intl,
 * mirrors the changelog). The localized `/<locale>/blog` routes pass their page
 * locale, so the date is formatted in that locale (e.g. "7 juillet 2026") via
 * Intl with an explicit locale and a fixed UTC zone: this is the PAGE locale, not
 * the browser's, so it stays server-deterministic and rule-compliant.
 */
export function formatBlogDate(date: string, locale?: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(date);
  if (!match) return date;
  const [, year, month, day] = match;

  if (locale && locale !== 'en') {
    try {
      return new Intl.DateTimeFormat(locale, {
        year: 'numeric',
        month: 'long',
        day: 'numeric',
        timeZone: 'UTC',
      }).format(new Date(`${year}-${month}-${day}T00:00:00Z`));
    } catch {
      /* unknown locale: fall through to the English format */
    }
  }

  const monthLabel = MONTHS[Number(month) - 1] ?? month;
  return `${monthLabel} ${Number(day)}, ${year}`;
}
