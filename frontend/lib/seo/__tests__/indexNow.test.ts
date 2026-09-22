import { describe, it, expect, vi } from 'vitest';
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
// The submission logic lives with the script that runs it, because the script
// IS the only production caller. A second copy in the frontend was written
// first, went unused, and every rule in it was duplicated by the script.
import {
  INDEXNOW_ENDPOINT,
  IndexNowError,
  MAX_URLS_PER_BATCH,
  SECTION_SITEMAPS,
  SUBMIT_BATCH_SIZE,
  batches,
  buildSubmission,
  exitCode,
  fetchSitemapUrls,
  isAccepted,
  locsFromSitemapXml,
  parseArgs,
  readKey,
  urlsOnHost,
} from '../../../../scripts/seo/indexnow.mjs';
import { isServedFilePath } from '../servedFiles';
import { SITEMAP_PATHS } from '../sitemaps';

const SITE = 'https://livecontext.ai';
const PUBLIC_DIR = join(__dirname, '..', '..', '..', 'public');
const KEY = readKey(PUBLIC_DIR);

describe('indexNow - the key file', () => {
  // The key proves control of the host by being readable. Every failure here is
  // silent in production: submissions come back 403 and no URL is ever
  // announced, which looks exactly like "IndexNow does not work".
  it('exists in public/, named after its own contents', () => {
    expect(existsSync(join(PUBLIC_DIR, `${KEY}.txt`))).toBe(true);
    expect(readFileSync(join(PUBLIC_DIR, `${KEY}.txt`), 'utf8').trim()).toBe(KEY);
  });

  it('is served rather than swallowed by the dotted-path 404', () => {
    // The guard that closes `/indexnow.txt` would close this too if the key
    // were not declared: the file would exist on disk and 404 on the web.
    expect(isServedFilePath(`/${KEY}.txt`)).toBe(true);
  });

  it('is a plausible key: 8 to 128 hex characters, as the API requires', () => {
    expect(KEY).toMatch(/^[a-f0-9]{8,128}$/);
  });

  it('refuses to guess when the directory holds no key', () => {
    expect(() => readKey(join(__dirname))).toThrow(/found 0/);
  });

  it('refuses to guess when the directory holds two keys', () => {
    // "Which key is live" must be answerable from the repo. Two files make it a
    // coin flip, and the losing half of the submissions comes back 403.
    const dir = mkdtempSync(join(tmpdir(), 'indexnow-two-'));
    writeFileSync(join(dir, 'aaaaaaaa.txt'), 'aaaaaaaa');
    writeFileSync(join(dir, 'bbbbbbbb.txt'), 'bbbbbbbb');
    expect(() => readKey(dir)).toThrow(/found 2/);
  });

  it('refuses a key file that does not contain its own key', () => {
    // The failure the whole design is about: the file exists, the submission
    // names it, and the engine reads something else and rejects the batch.
    const dir = mkdtempSync(join(tmpdir(), 'indexnow-mismatch-'));
    writeFileSync(join(dir, 'aaaaaaaa.txt'), 'bbbbbbbb');
    expect(() => readKey(dir)).toThrow(/does not contain its own key/);
  });
});

describe('indexNow - arguments', () => {
  it('defaults to the complete sitemap', () => {
    expect(parseArgs([])).toMatchObject({ section: 'all', site: SITE, dryRun: false });
  });

  it('refuses --section and --url together instead of silently dropping one', () => {
    expect(() => parseArgs(['--section', 'videos', '--url', `${SITE}/x`])).toThrow(IndexNowError);
  });

  it('refuses a section that has no sitemap', () => {
    expect(() => parseArgs(['--section', 'nope'])).toThrow(/must be one of/);
  });

  it('refuses an unknown flag rather than ignoring it', () => {
    expect(() => parseArgs(['--fast'])).toThrow(/Unknown argument/);
  });

  it('strips a trailing slash, which would otherwise build a //key.txt keyLocation', () => {
    expect(parseArgs(['--site', 'https://livecontext.ai/']).site).toBe(SITE);
  });

  it('refuses a flag whose value is missing, instead of widening the submission', () => {
    // `--section` with no value used to fall through to the `all` default, so a
    // typo submitted the whole 1096-url sitemap instead of the one section.
    expect(() => parseArgs(['--section'])).toThrow(/--section needs a value/);
    expect(() => parseArgs(['--section', '--dry-run'])).toThrow(/--section needs a value/);
    expect(() => parseArgs(['--site'])).toThrow(/--site needs a value/);
    expect(() => parseArgs(['--url'])).toThrow(/--url needs a value/);
  });

  it('covers every published sitemap, in BOTH directions', () => {
    // A section pointing at a path robots.txt does not advertise would submit
    // from a sitemap nobody maintains. The reverse is the one that rots: a
    // fifth section advertised to search engines and unreachable from the CLI.
    expect(Object.values(SECTION_SITEMAPS).sort()).toEqual([...SITEMAP_PATHS].sort());
  });
});

describe('indexNow - reading a sitemap', () => {
  it('takes the page urls and not the video player or thumbnail', () => {
    // A `video:player_loc` is a YouTube embed and a `video:thumbnail_loc` is an
    // image: submitting either announces a URL this site does not own.
    const xml = `<urlset><url><loc>${SITE}/videos/a</loc><video:video>`
      + `<video:player_loc>https://www.youtube.com/embed/x</video:player_loc>`
      + `<video:thumbnail_loc>${SITE}/videos/a.jpg</video:thumbnail_loc>`
      + `</video:video></url></urlset>`;

    expect(locsFromSitemapXml(xml)).toEqual([`${SITE}/videos/a`]);
  });

  it('decodes the entities a sitemap escapes, so the url submitted is the real one', () => {
    const xml = `<urlset><url><loc>${SITE}/s?a=1&amp;b=2</loc></url></urlset>`;
    expect(locsFromSitemapXml(xml)).toEqual([`${SITE}/s?a=1&b=2`]);
  });

  it('reads nothing out of an empty sitemap rather than throwing', () => {
    expect(locsFromSitemapXml('<urlset></urlset>')).toEqual([]);
  });
});

describe('indexNow - the submission body', () => {
  it('names the host, the key and where the key can be read', () => {
    expect(buildSubmission(SITE, KEY, [`${SITE}/videos`])).toEqual({
      host: 'livecontext.ai',
      key: KEY,
      keyLocation: `${SITE}/${KEY}.txt`,
      urlList: [`${SITE}/videos`],
    });
  });

  it('refuses an empty list rather than posting nothing', () => {
    expect(() => buildSubmission(SITE, KEY, [])).toThrow(IndexNowError);
  });

  it('refuses a batch over the API limit', () => {
    const urls = Array.from({ length: MAX_URLS_PER_BATCH + 1 }, (_, i) => `${SITE}/p/${i}`);
    expect(() => buildSubmission(SITE, KEY, urls)).toThrow(/at most 10000/);
  });

  it('posts to the one shared endpoint', () => {
    expect(INDEXNOW_ENDPOINT).toBe('https://api.indexnow.org/indexnow');
  });
});

describe('indexNow - filtering and batching', () => {
  it('drops a url on another host, which would fail the WHOLE batch', () => {
    const urls = [`${SITE}/videos`, 'https://docs.livecontext.ai/agents', 'not-a-url', `${SITE}/videos`];
    expect(urlsOnHost(urls, 'livecontext.ai')).toEqual([`${SITE}/videos`]);
  });

  it('splits by what the endpoint ACCEPTS, not by what the spec documents', () => {
    // Measured against the live endpoint with a key proven valid moments
    // before: 100 urls 202, 500 urls 403, 1075 urls 403. Batching by the
    // documented 10000 ceiling made every real submission fail, and the 403 it
    // returns is the protocol's "key not valid", so the error pointed at the
    // key rather than at the size.
    expect(SUBMIT_BATCH_SIZE).toBeLessThanOrEqual(100);

    const urls = Array.from({ length: SUBMIT_BATCH_SIZE + 5 }, (_, i) => `${SITE}/p/${i}`);
    const chunks = batches(urls);
    expect(chunks).toHaveLength(2);
    expect(chunks[0]).toHaveLength(SUBMIT_BATCH_SIZE);
    expect(chunks[1]).toHaveLength(5);
  });

  it('keeps the protocol ceiling as a REJECTION, which is a different number', () => {
    // `buildSubmission` still refuses anything above the documented maximum:
    // that guard is about the protocol, and lowering it to the batch size would
    // hide a caller passing an unbatched list.
    expect(MAX_URLS_PER_BATCH).toBeGreaterThan(SUBMIT_BATCH_SIZE);
    const urls = Array.from({ length: MAX_URLS_PER_BATCH + 1 }, (_, i) => `${SITE}/p/${i}`);
    expect(() => buildSubmission(SITE, KEY, urls)).toThrow(/at most 10000/);
  });

  it('treats 202 as accepted, since it only means the key is still being checked', () => {
    expect(isAccepted(200)).toBe(true);
    expect(isAccepted(202)).toBe(true);
    // 403 is "the key file could not be read", 422 is "a url is not on this
    // host". Both are actionable, and both are invisible if they read as success.
    for (const status of [400, 403, 422, 429, 500]) {
      expect(isAccepted(status), String(status)).toBe(false);
    }
  });

  it('fails the run when ANY batch was rejected, not only when all were', () => {
    // Half a submission accepted is still a submission that did not do what was
    // asked, and a run that exits 0 on a 403 is a run nobody looks at twice.
    expect(exitCode([200, 202])).toBe(0);
    expect(exitCode([200, 403])).toBe(1);
    expect(exitCode([403])).toBe(1);
    expect(exitCode([])).toBe(0);
  });
});

describe('indexNow - fetching a sitemap', () => {
  it('returns the page urls when the sitemap answers', async () => {
    const xml = `<urlset><url><loc>${SITE}/videos</loc></url></urlset>`;
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, text: async () => xml });

    await expect(fetchSitemapUrls(SITE, '/videos/sitemap.xml', fetchImpl))
      .resolves.toEqual([`${SITE}/videos`]);
    expect(fetchImpl).toHaveBeenCalledWith(
      `${SITE}/videos/sitemap.xml`,
      { headers: { Accept: 'application/xml' } },
    );
  });

  it('stops the run when the sitemap is missing, rather than submitting nothing', async () => {
    // A silent empty list would report "nothing to submit" and exit, which
    // reads as "there was nothing new" instead of "the sitemap 404s".
    const fetchImpl = vi.fn().mockResolvedValue({ ok: false, status: 404 });

    await expect(fetchSitemapUrls(SITE, '/videos/sitemap.xml', fetchImpl))
      .rejects.toThrow(/Could not read .*\/videos\/sitemap\.xml: HTTP 404/);
  });
});
