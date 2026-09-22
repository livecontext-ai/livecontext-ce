// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { closeSync, existsSync, openSync, readFileSync, readSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { FOOTER_INTEGRATION_COUNT, WELL_KNOWN_INTEGRATIONS } from '../wellKnownIntegrations';
import { MONO_DARK_ICON_SLUGS } from '@/lib/credentials/monoIconSlugs';

/**
 * The footer's fallback list must be real integrations, not plausible ones.
 *
 * <p>A hand-written entry here becomes `/integrations/{slug}` in the footer of every public
 * page, so a wrong slug is not a cosmetic issue: it is a 404 repeated site-wide, and it is
 * exactly the objection that kept a fallback out of the footer in the first place. This
 * test is what answers it, so the list may stay.
 *
 * <p>It checks against the API-migration seed corpus, which is what the importer loads into
 * the catalogue, and it reproduces the importer's own slug derivation
 * (`ApiMigrationImporter.slugify`: lowercase, then every run of non-alphanumerics becomes a
 * dash). That derivation is why the seed FILENAME cannot be used as the slug:
 * `google_sheets.json` declares "Google Sheets" and is served as `google-sheets`.
 */

const SEED_DIR = join(process.cwd(), '..', 'scripts', 'api-migrations');

/** Only the head of each seed file: `apiName`/`apiSlug` are top-level and the corpus is ~250 MB. */
const HEAD_BYTES = 4096;

function readHead(path: string): string {
  const fd = openSync(path, 'r');
  try {
    const buffer = Buffer.alloc(HEAD_BYTES);
    const read = readSync(fd, buffer, 0, HEAD_BYTES, 0);
    return buffer.toString('utf8', 0, read);
  } finally {
    closeSync(fd);
  }
}

/** `ApiMigrationImporter.slugify`, with the trailing dash a trailing "(...)" would leave. */
function slugify(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}

/** Every slug the catalogue can serve, mapped to the name the seed declares for it. */
function catalogueSlugs(): Map<string, string> {
  const bySlug = new Map<string, string>();

  for (const file of readdirSync(SEED_DIR)) {
    if (!file.endsWith('.json')) continue;
    const head = readHead(join(SEED_DIR, file));
    const name = head.match(/"apiName"\s*:\s*"((?:[^"\\]|\\.)*)"/)?.[1];
    if (!name) continue;
    const explicit = head.match(/"apiSlug"\s*:\s*"([^"]*)"/)?.[1];
    bySlug.set(explicit || slugify(name), name.replace(/\\"/g, '"'));
  }

  return bySlug;
}

describe('well-known integrations fallback', () => {
  const catalogue = catalogueSlugs();

  it('reads the seed corpus at all, so the cases below cannot pass vacuously', () => {
    expect(catalogue.size).toBeGreaterThan(500);
  });

  it('holds at least the entries the footer slices off the front', () => {
    // The footer renders the first FOOTER_INTEGRATION_COUNT and the landing strip renders
    // all of them, so a list shorter than the slice silently ships a stunted column.
    expect(WELL_KNOWN_INTEGRATIONS.length).toBeGreaterThanOrEqual(FOOTER_INTEGRATION_COUNT);
  });

  it('retains the full homepage selection and the supported social platforms', () => {
    expect(WELL_KNOWN_INTEGRATIONS.length).toBeGreaterThanOrEqual(31);
    const slugs = WELL_KNOWN_INTEGRATIONS.map((integration) => integration.slug);
    for (const slug of ['reddit', 'linkedin', 'tiktok', 'instagram', 'youtube-data-api', 'facebook', 'pinterest', 'threads', 'twitter-x']) {
      expect(slugs).toContain(slug);
    }
  });

  it('opens with the eight the footer column was curated around', () => {
    // Their ORDER is the footer's reading order and carries the reasoning recorded on the
    // list: recognition first, then mail, chat, docs, code, spreadsheet, CRM, payments.
    // Entries added for the landing strip go after them, never in front.
    expect(WELL_KNOWN_INTEGRATIONS.slice(0, FOOTER_INTEGRATION_COUNT).map((i) => i.slug)).toEqual([
      'instagram',
      'gmail',
      'slack',
      'notion',
      'github',
      'google-sheets',
      'hubspot',
      'stripe',
    ]);
  });

  it.each(WELL_KNOWN_INTEGRATIONS.map((i) => [i.slug, i.name]))(
    '/integrations/%s exists in the catalogue seed',
    (slug) => {
      expect(catalogue.has(slug)).toBe(true);
    },
  );

  it.each(WELL_KNOWN_INTEGRATIONS.map((i) => [i.slug, i.name]))(
    '%s is shown under the name the catalogue gives it (%s)',
    (slug, name) => {
      // A drifted label is not a broken link, but it makes the footer disagree with the
      // page it leads to, which is the whole reason the column is ranked and not curated.
      expect(catalogue.get(slug)).toBe(name);
    },
  );

  /**
   * Relative luminance of an sRGB hex colour, 0 (black) to 1 (white).
   *
   * <p>The 0.18 threshold below is empirical, not a standard: the five dark marks in this
   * list measure 0.086 (GitHub) and 0.000 (OpenAI) while the next lightest brand colour in
   * the list is far above it, so the band is wide and nothing sits near the edge.
   */
  function luminance(hex: string): number {
    const full = hex.replace('#', '');
    const rgb = full.length === 3 ? full.split('').map((c) => c + c).join('') : full;
    const [r, g, b] = [0, 2, 4].map((i) => parseInt(rgb.slice(i, i + 2), 16));
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
  }

  /**
   * Whether an icon's artwork renders dark enough to vanish on the dark theme.
   *
   * <p>No fill at all means SVG's default, which is pure black: that is how Zendesk shipped
   * invisible. Fills that are all gradient references (`url(#...)`) yield no hex to measure
   * and are brand gradients here, so they are not the flat near-black artwork this looks for.
   * `Math.max` is the right aggregator because one light region is enough to see the mark.
   */
  function rendersDark(iconSlug: string): boolean {
    const svg = readFileSync(join(process.cwd(), 'public', 'icons', 'services', `${iconSlug}.svg`), 'utf8');
    const fills = Array.from(svg.matchAll(/fill="(#[0-9a-fA-F]{3,6})"/g)).map((m) => m[1]);
    if (!/fill="/.test(svg)) return true;
    return fills.length > 0 && Math.max(...fills.map(luminance)) < 0.18;
  }

  it.each(WELL_KNOWN_INTEGRATIONS.map((i) => [i.name, i.iconSlug]))(
    '%s stays legible on the dark theme (%s)',
    (_name, iconSlug) => {
      // Existing on disk is not the same as being visible. Zendesk shipped here declaring NO
      // fill at all, so the mark disappeared into the first band under the hero in dark mode
      // while passing every other case. Artwork that renders dark must be declared in
      // MONO_DARK_ICON_SLUGS, which flips it to white.
      if (rendersDark(iconSlug)) {
        expect(MONO_DARK_ICON_SLUGS.has(iconSlug)).toBe(true);
      }
    },
  );

  it('proves the dark-legibility case is not vacuous', () => {
    // It has to count what the DETECTOR classifies, not what the set declares. An earlier
    // version counted membership of MONO_DARK_ICON_SLUGS, and a review neutered rendersDark
    // to a constant false with every case still green: the conditional above had gone
    // silently vacuous and this case could not tell. Five entries classify dark today
    // (github, openai, linear, dropbox, zendesk).
    const detected = WELL_KNOWN_INTEGRATIONS.filter((i) => rendersDark(i.iconSlug));
    expect(detected.length).toBeGreaterThanOrEqual(5);
  });

  it('detects both shapes of dark artwork, not just one', () => {
    // The two branches fail differently and both have shipped a bug: a declared near-black
    // fill (github, #161614) and no fill at all (zendesk, SVG's black default). A detector
    // that lost either branch would still look busy.
    expect(rendersDark('github')).toBe(true);
    expect(rendersDark('zendesk')).toBe(true);
    // And a bright mark must not be swept in, or every icon would demand the mono flip.
    expect(rendersDark('gmail')).toBe(false);
    // A gradient-filled mark yields no hex to measure. Math.max over an empty array is
    // -Infinity, which reported Instagram as pure black until it was handled explicitly.
    expect(rendersDark('instagram')).toBe(false);
  });
  it.each(WELL_KNOWN_INTEGRATIONS.map((i) => [i.name, i.iconSlug]))(
    '%s is drawn by an icon that exists on disk (%s.svg)',
    (_name, iconSlug) => {
      // The icon key is NOT the slug (google-sheets is drawn by googlesheets), so verifying
      // the link says nothing about the mark. Both the footer and the landing strip render
      // these, and a missing file is a blank square in the first band under the hero.
      expect(existsSync(join(process.cwd(), 'public', 'icons', 'services', `${iconSlug}.svg`))).toBe(true);
    },
  );

  it('lists each integration once', () => {
    const slugs = WELL_KNOWN_INTEGRATIONS.map((i) => i.slug);
    expect(new Set(slugs).size).toBe(slugs.length);
  });
});
