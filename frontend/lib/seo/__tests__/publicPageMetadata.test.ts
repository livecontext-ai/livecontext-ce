import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { socialCard } from '../socialCard';
import { localizedPathAlternates, localizedPathHref } from '../siteUrl';

const APP_DIR = path.resolve(__dirname, '../../../app');

/** The only robots value a page may set: noindex (an object starting `{ index: false`, or a string). */
const NOINDEX_VALUE = /^(\{\s*index:\s*false\b|['"`]noindex)/;

/** A source without its comments: the comments that explain the rule quote the forbidden form. */
const withoutComments = (source: string) => source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');

/**
 * The robots values in a source that are not noindex, as written: the value is read across
 * lines, so `robots:` followed by a ternary on the next line is seen, and a noindex object
 * opened on one line and closed on the next is accepted.
 */
function robotsOffences(source: string): string[] {
  return [...withoutComments(source).matchAll(/\brobots:\s*([\s\S]{0,80})/g)]
    .filter((match) => !NOINDEX_VALUE.test(match[1]))
    .map((match) => match[1].split('\n')[0].trim().slice(0, 60));
}

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) return name === '__tests__' ? [] : sources(full);
    return /\.(tsx?|mts)$/.test(name) ? [full] : [];
  });
}

describe('public page metadata', () => {
  it('regression: a page sets robots only to keep itself out of the index, never in a way that wipes the root layout\'s directives', () => {
    // Next merges page metadata over the layout key by key, and an explicit `robots: undefined`
    // is a value: 22 public pages served no robots meta at all on the cloud, and the persona
    // pages' `{ index: true, follow: true }` replaced the googleBot directives (large image
    // previews, full snippets). So a page's `robots:` may only say noindex (an object starting
    // `{ index: false`, or a 'noindex' string), written as a spread when it is conditional:
    // `...(IS_CE ? { robots: { index: false, ... } } : {})`. Anything else (a ternary on one line
    // or several, a variable, an index: true) is reported. The root layout itself is the source.
    const offenders = sources(APP_DIR)
      .filter((file) => path.relative(APP_DIR, file) !== 'layout.tsx')
      .flatMap((file) => robotsOffences(readFileSync(file, 'utf8')).map((value) => `${path.relative(APP_DIR, file)}: robots: ${value}`));
    expect(offenders).toEqual([]);
  });

  it('the robots guard itself catches the forms that wiped the directives, on any line layout, and nothing in comments', () => {
    // One line.
    expect(robotsOffences("robots: IS_CE ? { index: false, follow: false } : undefined,")).toHaveLength(1);
    expect(robotsOffences('robots: { index: true, follow: true },')).toHaveLength(1);
    expect(robotsOffences('robots: noIndex,')).toHaveLength(1);
    // The ternary on the line below the key (Prettier's layout): read across lines.
    expect(robotsOffences('robots:\n      IS_CE || !profile.searchIndexable ? { index: false, follow: true } : undefined,')).toHaveLength(1);
    // Accepted: noindex as a spread, as a string, and as an object written across lines.
    expect(robotsOffences('...(IS_CE ? { robots: { index: false, follow: false } } : {}),')).toEqual([]);
    expect(robotsOffences("robots: 'noindex, nofollow',")).toEqual([]);
    expect(robotsOffences('robots: {\n    index: false,\n    follow: false,\n  },')).toEqual([]);
    // A comment quoting the forbidden form is not code.
    expect(robotsOffences("// an explicit 'robots: undefined' wiped it\n/* robots: undefined */")).toEqual([]);
  });

  it('a localized page\'s share card names its language and the others', () => {
    const card = socialCard({ title: 'Partners', description: 'd', path: '/fr/partners', locale: 'fr' });

    expect(card.openGraph.url).toBe('https://livecontext.ai/fr/partners');
    expect(card.openGraph).toMatchObject({ locale: 'fr_FR' });
    expect((card.openGraph as { alternateLocale: string[] }).alternateLocale).toEqual(
      expect.arrayContaining(['en_US', 'de_DE', 'es_ES', 'pt_PT', 'zh_CN']),
    );
  });

  it('a single-language page\'s card is unchanged: no language named', () => {
    const card = socialCard({ title: 'About', description: 'd', path: '/about' });

    expect(card.openGraph).not.toHaveProperty('locale');
    expect(card.openGraph).not.toHaveProperty('alternateLocale');
  });

  it('a localized public page is bare in English and prefixed otherwise, x-default on the English URL', () => {
    expect(localizedPathHref('/partners')).toBe('/partners');
    expect(localizedPathHref('/partners', 'zh')).toBe('/zh/partners');
    expect(localizedPathAlternates('/partners', 'https://livecontext.ai/')).toMatchObject({
      en: 'https://livecontext.ai/partners',
      de: 'https://livecontext.ai/de/partners',
      'x-default': 'https://livecontext.ai/partners',
    });
  });
});
