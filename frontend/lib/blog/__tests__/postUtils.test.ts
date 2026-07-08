import { describe, it, expect } from 'vitest';
import {
  type BlogPost,
  sortPostsByDateDesc,
  findPostBySlug,
  estimateReadingMinutes,
  formatBlogDate,
  formatAuthors,
} from '../postUtils';

function post(overrides: Partial<BlogPost>): BlogPost {
  return {
    slug: 'slug',
    title: 'Title',
    date: '2026-01-01',
    excerpt: 'Excerpt',
    authors: ['LiveContext'],
    tags: [],
    cover: '/blog/slug.jpg',
    coverAlt: 'Cover',
    content: 'body',
    ...overrides,
  };
}

describe('sortPostsByDateDesc', () => {
  it('orders posts newest first', () => {
    const input = [
      post({ slug: 'old', date: '2026-01-01' }),
      post({ slug: 'new', date: '2026-03-01' }),
      post({ slug: 'mid', date: '2026-02-01' }),
    ];
    expect(sortPostsByDateDesc(input).map((p) => p.slug)).toEqual(['new', 'mid', 'old']);
  });

  it('breaks a same-date tie by ascending slug (stable, deterministic)', () => {
    const input = [
      post({ slug: 'banana', date: '2026-02-01' }),
      post({ slug: 'apple', date: '2026-02-01' }),
    ];
    expect(sortPostsByDateDesc(input).map((p) => p.slug)).toEqual(['apple', 'banana']);
  });

  it('does not mutate the input array', () => {
    const input = [
      post({ slug: 'a', date: '2026-01-01' }),
      post({ slug: 'b', date: '2026-02-01' }),
    ];
    const before = input.map((p) => p.slug);
    sortPostsByDateDesc(input);
    expect(input.map((p) => p.slug)).toEqual(before);
  });
});

describe('findPostBySlug', () => {
  const posts = [post({ slug: 'first' }), post({ slug: 'second' })];

  it('returns the matching post', () => {
    expect(findPostBySlug(posts, 'second')?.slug).toBe('second');
  });

  it('returns undefined for an unknown slug (drives the 404 path)', () => {
    expect(findPostBySlug(posts, 'missing')).toBeUndefined();
  });
});

describe('estimateReadingMinutes', () => {
  it('rounds to whole minutes at 200 words per minute', () => {
    // 500 words / 200 = 2.5 -> rounds to 3
    const content = Array.from({ length: 500 }, () => 'word').join(' ');
    expect(estimateReadingMinutes(content)).toBe(3);
  });

  it('floors at 1 minute for very short content', () => {
    expect(estimateReadingMinutes('just a few words')).toBe(1);
  });

  it('returns 1 for empty content instead of 0', () => {
    expect(estimateReadingMinutes('   ')).toBe(1);
  });

  it('collapses irregular whitespace when counting words', () => {
    // 400 tokens separated by mixed whitespace -> 400 / 200 = 2
    const content = Array.from({ length: 400 }, () => 'x').join('\n\n   ');
    expect(estimateReadingMinutes(content)).toBe(2);
  });
});

describe('formatAuthors', () => {
  it('returns a single author unchanged', () => {
    expect(formatAuthors(['Tom F.'])).toBe('Tom F.');
  });

  it('joins two authors with "and"', () => {
    expect(formatAuthors(['Tom F.', 'theo p.'])).toBe('Tom F. and theo p.');
  });

  it('uses an Oxford-style list for three or more', () => {
    expect(formatAuthors(['A', 'B', 'C'])).toBe('A, B and C');
  });

  it('uses a localized conjunction when provided', () => {
    expect(formatAuthors(['Tom F.', 'theo p.'], 'et')).toBe('Tom F. et theo p.');
    expect(formatAuthors(['A', 'B', 'C'], 'und')).toBe('A, B und C');
  });

  it('returns an empty string for no authors', () => {
    expect(formatAuthors([])).toBe('');
  });
});

describe('formatBlogDate', () => {
  it('formats an ISO date as "Month D, YYYY" with no leading zero on the day', () => {
    expect(formatBlogDate('2026-07-07')).toBe('July 7, 2026');
    expect(formatBlogDate('2026-12-25')).toBe('December 25, 2026');
  });

  it('uses the datetime prefix when a full ISO timestamp is passed', () => {
    expect(formatBlogDate('2026-03-01T12:34:56Z')).toBe('March 1, 2026');
  });

  it('returns the raw string unchanged when it is not an ISO date', () => {
    expect(formatBlogDate('not-a-date')).toBe('not-a-date');
  });

  it('keeps the English format for the "en" locale', () => {
    expect(formatBlogDate('2026-07-07', 'en')).toBe('July 7, 2026');
  });

  it('formats in the given page locale (not the browser locale)', () => {
    // French uses "7 juillet 2026"; assert the localized month word appears and
    // the day/year are present, without pinning exact spacing/punctuation.
    const fr = formatBlogDate('2026-07-07', 'fr');
    expect(fr.toLowerCase()).toContain('juillet');
    expect(fr).toContain('2026');
    const de = formatBlogDate('2026-07-07', 'de');
    expect(de).toContain('Juli');
  });
});
