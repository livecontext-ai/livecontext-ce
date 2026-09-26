import { describe, expect, it, vi, beforeEach } from 'vitest';
import { isAbsolute, normalize, relative, sep } from 'node:path';

/**
 * The per-integration OG card must draw on every path but one, because a broken OG image
 * silently degrades every share of the page and nothing else reports it.
 *
 * These tests ACTUALLY RASTERISE the card, as the marketplace card's tests do: asserting on
 * the returned object alone would pass on broken markup, since `ImageResponse` builds its
 * headers before drawing anything. That is how the marketplace card 502'd in production for
 * every real listing while HEAD kept answering 200.
 *
 * Every read the route makes goes through a spy on `readFile`, so a test can tell WHICH
 * branch drew the card (the integration's mark, the LiveContext fallback, the generic card)
 * and inject a failed read, instead of only proving that some PNG came out.
 */

const fetchIntegration = vi.fn();
vi.mock('@/lib/integrations/publicIntegrations', () => ({
  fetchIntegration: (slug: string) => fetchIntegration(slug),
}));

/** Paths the route asked to read, and a hook to make chosen reads fail. */
const reads: string[] = [];
let failRead: (path: string) => boolean = () => false;

vi.mock('node:fs/promises', async (importOriginal) => {
  const actual = await importOriginal<typeof import('node:fs/promises')>();
  const readFile = (async (path: Parameters<typeof actual.readFile>[0], ...rest: unknown[]) => {
    const p = String(path);
    reads.push(p);
    if (failRead(p)) throw Object.assign(new Error(`ENOENT (injected): ${p}`), { code: 'ENOENT' });
    return (actual.readFile as (...a: unknown[]) => Promise<unknown>)(path, ...rest);
  }) as typeof actual.readFile;
  return { ...actual, readFile, default: { ...actual, readFile } };
});

const ICONS_DIR = normalize(`${process.cwd()}/public/icons/services`);
const iconReads = () => reads.filter((p) => normalize(p).includes(`${sep}icons${sep}`));
const fontReads = () => reads.filter((p) => p.endsWith('.woff'));

const slack = {
  integration: {
    slug: 'slack',
    name: 'Slack',
    description: 'Send messages, manage channels and react to events in your Slack workspace.',
    iconSlug: 'slack',
    iconUrl: null,
    toolCount: 71,
    authType: 'oauth2',
  },
  documentation: null,
  tools: [],
  toolsTruncated: false,
};

/**
 * A fresh module per test: the route memoises its fonts at module scope, so without this
 * the first test to load them would hide every font-failure path from the others.
 */
async function loadRoute() {
  vi.resetModules();
  return (await import('../opengraph-image')).default;
}

async function renderCard(slug: string): Promise<Buffer> {
  const OpengraphImage = await loadRoute();
  const response = await OpengraphImage({ params: Promise.resolve({ slug }) });
  return Buffer.from(await response.arrayBuffer());
}

function expectPng(png: Buffer) {
  // PNG magic number: proof the bytes were really drawn, not just promised.
  expect(png.subarray(0, 4).toString('hex')).toBe('89504e47');
  expect(png.byteLength).toBeGreaterThan(1_000);
}

describe('integration opengraph-image', () => {
  beforeEach(() => {
    fetchIntegration.mockReset();
    reads.length = 0;
    failRead = () => false;
  });

  it('draws a real integration with its own mark, read from the icons directory', async () => {
    fetchIntegration.mockResolvedValue(slack);

    expectPng(await renderCard('slack'));

    expect(iconReads().map((p) => normalize(p))).toEqual([normalize(`${ICONS_DIR}/slack.svg`)]);
    expect(fontReads()).toHaveLength(3);
  });

  it('draws a long name with no tools and no auth type (the fewest-stats layout)', async () => {
    fetchIntegration.mockResolvedValue({
      ...slack,
      integration: { ...slack.integration, name: 'Google Analytics Admin API', toolCount: 0, authType: null, description: '' },
    });

    expectPng(await renderCard('google-analytics-admin'));
  });

  it('falls back to the LiveContext mark when the integration has no icon on disk', async () => {
    fetchIntegration.mockResolvedValue({ ...slack, integration: { ...slack.integration, iconSlug: 'no-such-icon-anywhere' } });

    expectPng(await renderCard('slack'));

    // It tried the integration's file, then drew with the LiveContext mark instead.
    expect(iconReads()).toHaveLength(1);
    expect(reads.some((p) => p.endsWith('liveContext-logo.svg'))).toBe(true);
  });

  it('never reads outside the icons directory for a hostile icon key', async () => {
    fetchIntegration.mockResolvedValue({ ...slack, integration: { ...slack.integration, iconSlug: '../../package' } });

    expectPng(await renderCard('slack'));

    for (const p of reads) {
      const fromIcons = relative(ICONS_DIR, normalize(p));
      const underIcons = !fromIcons.startsWith('..') && !isAbsolute(fromIcons);
      // Only the fonts and the LiveContext logo may be read from elsewhere, never the key's target.
      expect(underIcons || p.endsWith('.woff') || p.endsWith('liveContext-logo.svg')).toBe(true);
    }
    expect(reads.some((p) => p.includes('package'))).toBe(false);
  });

  it('draws the generic card for an unknown slug, without looking for an icon', async () => {
    fetchIntegration.mockResolvedValue(null);

    expectPng(await renderCard('does-not-exist'));

    expect(iconReads()).toEqual([]);
  });

  it('fails the request when the catalogue cannot be read, so no network caches a generic card', async () => {
    fetchIntegration.mockRejectedValue(new Error('gateway down'));

    await expect(renderCard('slack')).rejects.toThrow('gateway down');
  });

  it('still draws, in the built-in face, when its fonts cannot be read', async () => {
    fetchIntegration.mockResolvedValue(slack);
    failRead = (p) => p.endsWith('.woff');

    expectPng(await renderCard('slack'));
  });

  it('does not memoise a failed font read: the next request reads the fonts again', async () => {
    fetchIntegration.mockResolvedValue(slack);
    const OpengraphImage = await loadRoute();
    const draw = async () => Buffer.from(await (await OpengraphImage({ params: Promise.resolve({ slug: 'slack' }) })).arrayBuffer());

    failRead = (p) => p.endsWith('.woff');
    expectPng(await draw());
    const afterFailure = fontReads().length;

    failRead = () => false;
    expectPng(await draw());
    expect(fontReads().length).toBeGreaterThan(afterFailure);

    // And once they loaded, they ARE memoised: a third draw reads no font.
    const afterSuccess = fontReads().length;
    expectPng(await draw());
    expect(fontReads().length).toBe(afterSuccess);
  });

  it('still draws when the LiveContext logo cannot be read', async () => {
    fetchIntegration.mockResolvedValue(null);
    failRead = (p) => p.endsWith('liveContext-logo.svg');

    expectPng(await renderCard('does-not-exist'));
  });
});
