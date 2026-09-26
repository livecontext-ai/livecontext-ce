// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { darkIconSrc } from '../darkIcons';
import { DARK_ICON_SLUGS } from '../darkIconSlugs.generated';

const ICONS = join(process.cwd(), 'public', 'icons', 'services');
const files = readdirSync(ICONS);

describe('darkIconSrc', () => {
  it('points a shipped icon that has a dark file at that file', () => {
    // github is drawn near-black: its dark file is the reversed (white) mark.
    expect(darkIconSrc('/icons/services/github.svg')).toBe('/icons/services/github.dark.svg');
  });

  it('returns null for an icon whose default file reads on both themes', () => {
    expect(DARK_ICON_SLUGS.has('slack')).toBe(false);
    expect(darkIconSrc('/icons/services/slack.svg')).toBeNull();
  });

  it('returns null for anything that is not a shipped /icons/services/<key>.svg', () => {
    // An uploaded custom-API icon, another folder, a query string, an absolute URL: none
    // can have a sibling dark file, so none may be rewritten into a 404.
    expect(darkIconSrc('https://cdn.example.com/icons/services/github.svg')).toBeNull();
    expect(darkIconSrc('/icons/other/github.svg')).toBeNull();
    expect(darkIconSrc('/icons/services/github.svg?v=2')).toBeNull();
    expect(darkIconSrc('/icons/services/github.png')).toBeNull();
    expect(darkIconSrc(null)).toBeNull();
    expect(darkIconSrc(undefined)).toBeNull();
    expect(darkIconSrc('')).toBeNull();
  });
});

describe('DARK_ICON_SLUGS is generated from the files, and still matches them', () => {
  // The list is written by scripts/icons/install.py. A dark file added or removed by hand
  // without re-running it would either never be drawn or be drawn as a broken image.
  const onDisk = new Set(files.filter((f) => f.endsWith('.dark.svg')).map((f) => f.slice(0, -'.dark.svg'.length)));

  it('lists exactly the <key>.dark.svg files that exist', () => {
    expect([...DARK_ICON_SLUGS].sort()).toEqual([...onDisk].sort());
  });

  it('never lists a dark file without its default file', () => {
    for (const key of DARK_ICON_SLUGS) {
      expect(files, `${key}.dark.svg has no ${key}.svg`).toContain(`${key}.svg`);
    }
  });

  it('ships a dark file only when it is a different drawing of the same shape', () => {
    // A byte-identical copy costs a second element and request for nothing, and a different
    // aspect ratio makes the icon change size when the theme flips.
    const aspect = (svg: string) => {
      const m = svg.match(/viewBox\s*=\s*["']\s*[-\d.]+[\s,]+[-\d.]+[\s,]+([\d.]+)[\s,]+([\d.]+)/);
      return m ? Number(m[1]) / Number(m[2]) : 1;
    };
    for (const key of DARK_ICON_SLUGS) {
      const light = readFileSync(join(ICONS, `${key}.svg`), 'utf8');
      const dark = readFileSync(join(ICONS, `${key}.dark.svg`), 'utf8');
      expect(dark, `${key}.dark.svg is a copy of ${key}.svg`).not.toBe(light);
      const ratio = aspect(dark) / aspect(light);
      expect(ratio, `${key}: dark aspect differs`).toBeGreaterThan(0.75);
      expect(ratio, `${key}: dark aspect differs`).toBeLessThan(1.34);
    }
  });

  it('covers the brands the old CSS-invert list existed for', () => {
    // These seven were flipped to white by a filter before. Each now ships a real file;
    // linear and dropbox left the list because their current marks are coloured.
    for (const key of ['github', 'openai', 'anthropic', 'twitter', 'tiktok', 'threads', 'zendesk']) {
      expect(DARK_ICON_SLUGS.has(key), key).toBe(true);
    }
  });
});

describe('no shipped icon is a letter placeholder or unsafe', () => {
  // The icon pipeline (scripts/icons) replaced 280 letter tiles with each brand's real logo.
  // A tile drawn with <text> is how a placeholder looks, so a new one is a regression that
  // shows a user two letters where the brand should be. The allow-list is the few integrations
  // for which no official logo could be sourced yet; it may only shrink.
  const STILL_PLACEHOLDER = new Set([
    // integrations with no official logo sourced yet (see scripts/icons/decisions.json)
    'autotask', 'awsbedrock', 'codeium', 'cortex', 'descript', 'malcore', 'marketo',
    'microsoftentraid', 'opencti', 'openthesaurus', 'pdfmonkey', 'postbin', 'profitwell',
    'recruitee', 'tabnine', 'waha', 'wecomrobot',
    // files no API seed points at any more
    'alpaca', 'audit-tracking', 'azure_translator', 'tandoor', 'trieve', 'zhipu_ai',
    // 01.AI's own favicon is set in <text>: it is the brand's real mark, not a tile of ours
    'yi',
  ]);

  const isLetterTile = (svg: string) =>
    /<text\b/i.test(svg) && !/<(path|polygon|polyline|image|ellipse|line)\b/i.test(svg);

  it('keeps the allow-list honest: every entry is still a placeholder', () => {
    // A key whose real logo has shipped must leave the list, or the list stops shrinking.
    for (const key of STILL_PLACEHOLDER) {
      expect(isLetterTile(readFileSync(join(ICONS, `${key}.svg`), 'utf8')), `${key} is fixed, drop it`).toBe(true);
    }
  });

  it.each(files.filter((f) => f.endsWith('.svg')))('%s', (file) => {
    const svg = readFileSync(join(ICONS, file), 'utf8');
    expect(svg).not.toMatch(/<script\b|\son[a-z]+\s*=/i);
    const key = file.replace(/(\.dark)?\.svg$/, '');
    if (isLetterTile(svg)) {
      expect(STILL_PLACEHOLDER.has(key), `${file} is a letter placeholder`).toBe(true);
    }
  });
});
