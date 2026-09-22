import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';
import { locales } from '@/i18n/routing';

/**
 * What keeps the header and footer holding six languages instead of one.
 *
 * <p><strong>The bug this pins.</strong> The header is a single flex row. When the row does not
 * fit, a browser does not report anything: it WRAPS the longest label. In French the nav CTA
 * ("Commencer gratuitement", 22 characters against the 16 the bar was measured with) wrapped to
 * two lines inside a fixed `h-9` pill and spilled out of it, from 320px all the way up to 900px.
 * The footer had the same shape one level down: seven grid columns are 64px wide at a 1024px
 * viewport, a German "Datenschutzerklärung" is 146px, and a grid item is `min-width:auto`, so
 * the Legal column widened the whole document by 63px.
 *
 * <p><strong>Why source-level, and what it does NOT prove.</strong> Wrapping and overflow are
 * layout, which jsdom does not do: nothing rendered here can see a wrapped label. The browser
 * half lives in `e2e/i18n/landing-chrome-fits.spec.ts`, which measures the real thing at seven
 * widths in all six locales. This file is the half that runs in CI (ci.yml lists it; no CI job
 * runs Playwright at all), and it covers what a source file can actually hold: the classes the
 * width arithmetic depends on, and the copy budget that arithmetic assumes.
 *
 * <p><strong>The numbers below are measured, not estimated</strong>, in Chromium against the dev
 * server, at the widths named. Where a claim is about pixels, it came from
 * `getBoundingClientRect`, not from counting characters.
 */
const shellSrc = readFileSync(path.resolve(__dirname, '../LandingShell.tsx'), 'utf8');

const section = (from: string, to: string) => {
  const start = shellSrc.indexOf(from);
  expect(start, `missing ${from}`).toBeGreaterThan(-1);
  const end = shellSrc.indexOf(to, start);
  expect(end, `missing ${to}`).toBeGreaterThan(start);
  return shellSrc.slice(start, end);
};

const header = section('export function LandingHeader', 'export function LandingFooter');
const footer = section('export function LandingFooter', 'interface LandingShellProps');

/**
 * The class list of the element whose markup follows `anchor`, as a Set of tokens.
 *
 * <p>A Set rather than a substring match on the whole attribute: a class sorter reordering the
 * list must not turn a guard red, and a token must not be satisfied by a class further down the
 * file (asserting `whitespace-nowrap` against everything after `variant="primary"` would pass
 * on the wrong element).
 */
function classesAfter(source: string, anchor: string): Set<string> {
  const from = source.indexOf(anchor);
  expect(from, `anchor not found: ${anchor}`).toBeGreaterThan(-1);
  // An anchor that is itself part of a class list (e.g. "flex-1 grid") belongs to THAT
  // attribute, not to the next element's: take the className the anchor sits inside when
  // there is one, and the following className otherwise.
  const openedBefore = source.lastIndexOf('className="', from);
  if (openedBefore !== -1) {
    const closes = source.indexOf('"', openedBefore + 'className="'.length);
    if (closes > from) {
      return new Set(source.slice(openedBefore + 'className="'.length, closes).split(/\s+/));
    }
  }
  const match = /className="([^"]+)"/.exec(source.slice(from));
  expect(match, `no className after ${anchor}`).not.toBeNull();
  return new Set(match![1].split(/\s+/));
}

// 'h-20' is the bar's own height class, so this resolves to the bar div rather than to the
// <header> wrapper around it.
const bar = () => classesAfter(header, 'h-20');
const logo = () => classesAfter(header, 'withBase(siteBaseUrl, \'/\')');
const nav = () => classesAfter(header, '<nav');
// 'lg:gap-3' belongs to the sign-in cluster and to nothing else in the bar.
const cluster = () => classesAfter(header, 'lg:gap-3');
const signIn = () => classesAfter(header, 'variant="link"');
const cta = () => classesAfter(header, 'variant="primary"');
const footerGrid = () => classesAfter(footer, 'flex-1 grid');

describe('the header row holds a long label', () => {
  it('forbids the CTA from wrapping', () => {
    // The pill is `h-9`: a second line does not make it taller, it spills out of it.
    expect(cta()).toContain('whitespace-nowrap');
    expect(cta()).toContain('h-9');
  });

  it('forbids the nav entries and the sign-in link from wrapping', () => {
    expect(nav()).toContain('whitespace-nowrap');
    expect(signIn()).toContain('whitespace-nowrap');
  });

  it('makes the nav give way before the sign-in cluster does', () => {
    // Without `shrink-0` the flex row takes the space back out of the pill instead, which is
    // what turned a too-wide bar into a broken button rather than a tighter nav.
    expect(cluster()).toContain('shrink-0');
    expect(logo()).toContain('shrink-0');
  });

  it('hides the secondary sign-in link only in the band where the bar has nothing left to give', () => {
    // 768-858px is the only range with the nav visible AND no room: measured with the link in,
    // 768px is 15px short in French and 32px short in German, and every locale fits again by
    // 859 (Tailwind reads `max-[859px]` as an exclusive bound). The pill beside it opens the
    // same sign-in, so this link is what goes there.
    for (const token of ['hidden', 'sm:inline-flex', 'md:max-[859px]:hidden']) {
      expect(signIn(), `sign-in link lost ${token}`).toContain(token);
    }
    // One stacked condition, not `md:hidden` plus a wider min-width rule to undo it: Tailwind
    // emits the arbitrary min-width BEFORE `md:hidden`, so that pair loses the cascade and the
    // link stays hidden at every width above 768 - verified in the browser, and invisible to
    // every other assertion here.
    expect(signIn()).not.toContain('md:hidden');
    // The e2e case `the sign-in link is present wherever the bar has room for it` is what
    // proves the resulting visibility at nine widths.
  });

  it('keeps the gaps and paddings the row is sized around', () => {
    // Each of these buys width at a breakpoint where the row has none, and dropping any one of
    // them puts a locale back over the edge while every other assertion here stays green.
    // Below the nav's breakpoint, where only the brand and the pill are left, the three that
    // yield are worth 24px together and German needed 14 of them at 320px:
    expect(bar()).toContain('px-6');
    expect(bar()).toContain('max-[374px]:px-4');
    expect(bar()).toContain('gap-2');
    expect(bar()).toContain('sm:gap-3');
    // The narrowed padding is scoped to the widths that need it. On `sm` it would hold to
    // 639px and leave the brand 8px left of the hero, the footer and the copyright line - all
    // of which are px-6 - on every phone. The shortfall itself runs out around 344px; 374 is
    // the nearest bound above that, so a few devices get 8px they do not need.
    expect(bar()).not.toContain('sm:px-6');
    // The brand's right margin separates it from the NAV, which does not exist below md, so it
    // only applies from there.
    expect(logo()).toContain('md:mr-1');
    expect(logo()).not.toContain('mr-1');
    // And in the md band, where the nav is back: gap-4 instead of gap-8 is 16px, the pill's
    // px-3 instead of px-4 is 8px, and the cluster's gap-2 is 4px.
    expect(nav()).toContain('gap-4');
    expect(nav()).toContain('lg:gap-8');
    expect(cta()).toContain('px-3');
    expect(cta()).toContain('lg:px-4');
    expect(cluster()).toContain('gap-2');
  });
});

describe('the footer columns hold a long word', () => {
  it('waits for xl before going to seven columns', () => {
    // Seven columns are 64px each at a 1024px viewport: fine for "Gmail", not for
    // "Datenschutzerklärung" (146px). Four columns there are 136px.
    expect(footerGrid()).toContain('lg:grid-cols-4');
    expect(footerGrid()).toContain('xl:grid-cols-7');
    expect(footerGrid()).not.toContain('lg:grid-cols-7');
  });

  it('lets a column be narrower than its longest word, and that word break', () => {
    // A grid item is min-width:auto, so without this a column can never be narrower than its
    // longest word and the surplus is pushed onto the document. `break-words` only helps
    // because the min-width is explicit: overflow-wrap does not affect min-content sizing.
    expect(footerGrid()).toContain('*:min-w-0');
    expect(footerGrid()).toContain('break-words');
    // Not `[&>div]:min-w-0`: that covers today's columns only because each happens to be a
    // <div>, and a column rebuilt as a <nav> or a <ul> would bring the overflow back.
    expect(footer).not.toContain('[&>div]:min-w-0');
  });
});

describe('the chrome copy stays inside the width the bar has', () => {
  const messages = { en, fr, de, es, pt, zh };

  /**
   * Latin characters are ~7px at `text-sm`, CJK ~14px, so a raw `length` would read the
   * four-character Chinese label as half the width it occupies. Counting a CJK character as two
   * keeps one budget meaningful for all six languages. The ranges are compared as code points
   * rather than written as literal characters in a regex class, so that re-encoding this file
   * cannot quietly change which characters count double; the test below fixes the behaviour
   * either way.
   */
  const CJK_RANGES = [[0x2e80, 0x9fff], [0xff00, 0xffef]];
  const widthUnits = (label: string) =>
    [...label].reduce((total, char) => {
      const code = char.codePointAt(0) ?? 0;
      const wide = CJK_RANGES.some(([first, last]) => code >= first && code <= last);
      return total + (wide ? 2 : 1);
    }, 0);

  it('counts a CJK character as two, so one budget covers all six languages', () => {
    // Measured: "免费开始" is 80px of pill text, "Commencer" (9 Latin characters) is 106px.
    // Without the weighting the Chinese label would score 4 and a budget could be set on it
    // that no Latin locale could meet.
    expect(widthUnits('免费开始')).toBe(8);
    expect(widthUnits('Commencer')).toBe(9);
    expect(widthUnits('Get started free')).toBe(16);
  });

  // Measured, not guessed: at 768px - the tightest width, where the nav appears - the German
  // bar has ~11px of slack with a 17-unit label and the widest nav of the six. 18 is the last
  // value that still fits; the French label that shipped broken was 22.
  const CTA_BUDGET = 18;

  // The nav is the larger half of the row by pixels and had no budget at all: it is five
  // labels, four of them translated, and one long word in any of them reopens the same bug
  // with every class assertion above still green. Today German and Portuguese tie for widest
  // at 44 units (de: "Marktplatz" + "Preise" + "Changelog" + "Doku" + "Selbst gehostet"), and
  // German leaves ~11px of slack at 768px, so the ceiling is one short word above the worst
  // the bar is known to survive.
  const NAV_BUDGET = 52;

  for (const locale of locales) {
    it(`fits the ${locale} CTA label beside the nav at 768px`, () => {
      const label = messages[locale].LandingShell.getStarted;
      expect(label, `${locale}.LandingShell.getStarted is missing`).toBeTruthy();
      expect(widthUnits(label), `${locale}: "${label}" is too wide for the header`)
        .toBeLessThanOrEqual(CTA_BUDGET);
    });

    it(`fits the ${locale} nav labels in the same row`, () => {
      const shell = messages[locale].LandingShell;
      // "Changelog" is not translated and is part of the row all the same, so it counts.
      const navLabels = [shell.marketplace, shell.pricing, 'Changelog', shell.docs, shell.selfHosted];
      const total = navLabels.reduce((sum, label) => sum + widthUnits(label), 0);
      expect(total, `${locale}: the nav row (${navLabels.join(' / ')}) is too wide`)
        .toBeLessThanOrEqual(NAV_BUDGET);
    });
  }

  it('leaves the long form of the CTA where there is room for it', () => {
    // The hero button is the full width of its own row, so the free-tier wording lives there.
    // This is what makes shortening the French header label a layout change and not a message
    // change: /fr still says "Commencer gratuitement" on the button visitors actually land on.
    expect(fr.LandingHome.hero.startFree).toContain('gratuitement');
  });
});
