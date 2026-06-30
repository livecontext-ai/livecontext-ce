import { describe, it, expect } from 'vitest';
import { extractWebSearchUrls } from '../extractWebSearchUrls';

describe('extractWebSearchUrls', () => {
  it('returns [] for tools other than web_search', () => {
    expect(extractWebSearchUrls('catalog', '{"action":"search"}')).toEqual([]);
    expect(extractWebSearchUrls('workflow', undefined, '{"results":[{"url":"https://x.io"}]}')).toEqual([]);
  });

  it('returns [] when args and result are both missing', () => {
    expect(extractWebSearchUrls('web_search')).toEqual([]);
  });

  it('returns [] when args/result are not valid JSON', () => {
    expect(extractWebSearchUrls('web_search', 'not-json', 'also-not-json')).toEqual([]);
  });

  it('extracts urls from a search result (results[].url)', () => {
    const result = JSON.stringify({
      results: [
        { url: 'https://example.com/a' },
        { url: 'https://other.org/b' },
      ],
    });
    expect(extractWebSearchUrls('web_search', '{"action":"search","query":"x"}', result)).toEqual([
      'https://example.com/a',
      'https://other.org/b',
    ]);
  });

  it('extracts urls from nested results[].results[].url (search-of-searches)', () => {
    const result = JSON.stringify({
      results: [
        {
          query: 'q',
          results: [
            { url: 'https://a.io', title: 'A' },
            { url: 'https://b.io', title: 'B' },
          ],
        },
      ],
    });
    expect(extractWebSearchUrls('web_search', undefined, result)).toEqual([
      'https://a.io',
      'https://b.io',
    ]);
  });

  it('extracts the single fetch URL from args before the result lands', () => {
    const args = JSON.stringify({ action: 'fetch', url: 'https://foo.bar/page' });
    expect(extractWebSearchUrls('web_search', args)).toEqual(['https://foo.bar/page']);
  });

  it('extracts batch fetch URLs from args.urls[]', () => {
    const args = JSON.stringify({
      action: 'fetch',
      urls: ['https://a.com/1', 'https://b.com/2', 'https://c.com/3'],
    });
    expect(extractWebSearchUrls('web_search', args)).toEqual([
      'https://a.com/1',
      'https://b.com/2',
      'https://c.com/3',
    ]);
  });

  it('unwraps the streamed { raw: "<json>" } args wrapper', () => {
    const inner = JSON.stringify({ action: 'fetch', url: 'https://wrapped.example' });
    const args = JSON.stringify({ raw: inner, thinking: 'looking up the page' });
    expect(extractWebSearchUrls('web_search', args)).toEqual(['https://wrapped.example']);
  });

  it('deduplicates by hostname, keeping first occurrence', () => {
    const result = JSON.stringify({
      results: [
        { url: 'https://news.example.com/a' },
        { url: 'https://news.example.com/b' },
        { url: 'https://other.org/c' },
      ],
    });
    expect(extractWebSearchUrls('web_search', undefined, result)).toEqual([
      'https://news.example.com/a',
      'https://other.org/c',
    ]);
  });

  it('keeps malformed URLs as their own dedup key', () => {
    const result = JSON.stringify({
      results: [{ url: 'not-a-url' }, { url: 'not-a-url' }, { url: 'https://valid.io' }],
    });
    expect(extractWebSearchUrls('web_search', undefined, result)).toEqual([
      'not-a-url',
      'https://valid.io',
    ]);
  });

  it('also picks up result.pages[].url for fetch results', () => {
    const result = JSON.stringify({
      pages: [{ url: 'https://page1.io' }, { url: 'https://page2.io' }],
    });
    expect(extractWebSearchUrls('web_search', '{"action":"fetch"}', result)).toEqual([
      'https://page1.io',
      'https://page2.io',
    ]);
  });

  it('survives non-string url fields gracefully', () => {
    const result = JSON.stringify({
      results: [{ url: 42 }, { url: null }, { url: 'https://ok.io' }],
    });
    expect(extractWebSearchUrls('web_search', undefined, result)).toEqual(['https://ok.io']);
  });

  it('returns [] for an agent_browse call (no urls in args, result shape differs)', () => {
    const args = JSON.stringify({ action: 'agent_browse', task: 'Find iPhone price' });
    const result = JSON.stringify({ final_result: 'done', steps: [] });
    expect(extractWebSearchUrls('web_search', args, result)).toEqual([]);
  });

  it('ignores args.url when action is search (only result.results[] wins)', () => {
    // Sanity check: a misbuilt search call with a stray args.url field must not pollute
    // the favicon stack - only the search result URLs should be displayed.
    const args = JSON.stringify({ action: 'search', query: 'x', url: 'https://ignored.example' });
    const result = JSON.stringify({ results: [{ url: 'https://hit.io' }] });
    expect(extractWebSearchUrls('web_search', args, result)).toEqual(['https://hit.io']);
  });

  it('handles a mixed args.url + args.urls[] batch fetch by concatenating both', () => {
    const args = JSON.stringify({
      action: 'fetch',
      url: 'https://primary.io',
      urls: ['https://other.io'],
    });
    expect(extractWebSearchUrls('web_search', args)).toEqual([
      'https://primary.io',
      'https://other.io',
    ]);
  });

  it('returns [] for a fetch call with neither url nor urls and no result yet', () => {
    const args = JSON.stringify({ action: 'fetch' });
    expect(extractWebSearchUrls('web_search', args)).toEqual([]);
  });
});
