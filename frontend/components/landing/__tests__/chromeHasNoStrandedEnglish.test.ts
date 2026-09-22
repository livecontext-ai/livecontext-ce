import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * No user-facing ATTRIBUTE in the chrome may be a bare literal.
 *
 * <p><strong>This exists because a review found two that every other test missed.</strong> The
 * header and footer were threaded with `labels` and verified by rendering all six locales, and
 * the theme toggle and the language select still said "Switch to light theme" and "Language" in
 * the bottom bar of `/fr`, `/de`, `/es`, `/pt` and `/zh`. They survived because every check was
 * written around VISIBLE copy: those two controls have no text of their own, so their whole
 * accessible name is an `aria-label`, and the toggle's `title` shows on hover. Nothing rendered
 * them as words, so nothing noticed.
 *
 * <p>Scope, precisely: the four attributes a human reads, in the shell and in each component it
 * imports by default, matched only in the `attr="literal"` form. What it does NOT see: visible
 * text, and an attribute written as `attr={'literal'}`. Those are covered from the other end,
 * by the render tests that mount `LandingThemeToggle`, `LandingLanguageSelect` and
 * `FooterIntegrations` with labels and assert the words that come out, which is the half a
 * source scan cannot do. Neither half alone was enough: the attribute bug shipped because no
 * render test passed a prop, and the props were ignorable because no scan reached the DOM.
 *
 * <p>The file list is read from the shell's own default imports rather than typed out, so a child
 * added to the header or footer tomorrow is covered without anyone remembering this file. A child
 * brought in by a NAMED import is not: a real gap, written down rather than assumed away.
 *
 * <p>An attribute that is genuinely not copy goes in {@link ALLOWED} with its reason, so the
 * next person adding one has to make that call deliberately rather than by omission.
 */
const FRONTEND = path.resolve(__dirname, '..', '..', '..');
const shellPath = path.join(FRONTEND, 'components/landing/LandingShell.tsx');
const read = (abs: string) => readFileSync(abs, 'utf8');
const shellSrc = read(shellPath);

/** Every local component the shell renders, resolved from its `@/` imports. */
function chromeFiles(): { name: string; abs: string }[] {
  const files = [{ name: 'LandingShell.tsx', abs: shellPath }];
  for (const [, spec] of shellSrc.matchAll(/^import\s+(?:\w+)\s+from\s+'@\/([^']+)';$/gm)) {
    // Only the ones that render chrome markup. The theme PROVIDER renders no copy, and the
    // logo is a graphic.
    if (/LandingThemeProvider|LogoAnimate/.test(spec)) continue;
    files.push({ name: `${spec.split('/').pop()!}.tsx`, abs: path.join(FRONTEND, `${spec}.tsx`) });
  }
  return files;
}

/** Literals that are not copy, each with the reason it is not. */
const ALLOWED = new Set([
  // Proper nouns: a brand is spelled the same in every language. Only the ones that actually
  // appear in a SCANNED ATTRIBUTE, so an entry cannot quietly pre-authorise a string the
  // scanner would never have seen anyway. That is why LiveContext, Marketplace and Changelog
  // are absent: they are visible text, and Marketplace is now translated besides.
  //
  // "Logo" is deliberately not here either. The mark is decorative beside the brand name, so
  // it is hidden from assistive tech rather than given a word to translate, and the test below
  // pins that at both call sites.
  'GitHub', 'X', 'LinkedIn', 'YouTube', 'Discord', 'Instagram', 'TikTok',
]);

/** The attributes a human reads: `aria-label`, `title`, `alt`, `placeholder`. */
const LITERAL_ATTR = /(aria-label|title|alt|placeholder)="([^"]+)"/g;

describe('the landing chrome carries no stranded English', () => {
  const files = chromeFiles();

  it('actually found the shell and its children, so the scan below is not vacuous', () => {
    expect(files.length).toBeGreaterThan(3);
    expect(files.map((f) => f.name)).toContain('LandingThemeToggle.tsx');
    expect(files.map((f) => f.name)).toContain('LandingLanguageSelect.tsx');
    expect(files.map((f) => f.name)).toContain('FooterIntegrations.tsx');
  });

  for (const { name, abs } of files) {
    it(`${name} has no hardcoded user-facing attribute`, () => {
      const offenders = [...read(abs).matchAll(LITERAL_ATTR)]
        .filter(([, , value]) => !ALLOWED.has(value))
        .map(([, attr, value]) => `${attr}="${value}"`);
      // A string that varies by language has to arrive as a prop, so its value here is an
      // expression rather than a quoted literal.
      expect(offenders, `${name}: pass these through labels instead`).toEqual([]);
    });
  }

  it('routes every chrome child that shows words through the labels object', () => {
    // The two that were missed are named explicitly, so removing a prop fails here rather
    // than silently reverting five locales to English.
    expect(shellSrc).toContain('<LandingLanguageSelect label={labels.language} />');
    expect(shellSrc).toContain('toLight={labels.toLightTheme}');
    expect(shellSrc).toContain('toDark={labels.toDarkTheme}');
    expect(shellSrc).toContain('heading={labels.integrations}');
    expect(shellSrc).toContain('allLabel={labels.allIntegrations}');
    // The GitHub link's accessible name is composed rather than literal: it is the one place
    // where a label and a proper noun share an attribute.
    expect(shellSrc).toContain('aria-label={`${labels.selfHosted} (GitHub)`}');
  });

  it('hides the brand mark from assistive tech, at BOTH places the chrome renders it', () => {
    // LogoAnimate keeps `role="img" aria-label="Logo"` for callers that use it on its own, so
    // the chrome has to opt out explicitly. Nothing else can catch a missing opt-out: the word
    // is an attribute, so no render test sees it, and the scanner above skips LogoAnimate
    // because the literal there is legitimate for its other callers. Dropping `decorative`
    // from these two call sites used to leave the whole suite green while putting an
    // untranslatable English word into the accessible name of the brand link on all six
    // locales, in the header and again in the footer.
    expect(shellSrc.match(/<LogoAnimate[^>]*\sdecorative\s*\/>/g) ?? [],
      'both chrome logos must opt out of being announced').toHaveLength(2);
    expect(shellSrc.match(/<LogoAnimate/g) ?? []).toHaveLength(2);

    // And the opt-out has to actually drop the label rather than rename it.
    const logo = read(path.join(FRONTEND, 'components/LogoAnimate.tsx'));
    expect(logo).toContain("'aria-hidden': true");
    expect(logo).toMatch(/decorative[\s\S]{0,80}aria-hidden/);
  });

  it('keeps an English default for each of them, for the intl-free pages', () => {
    // /docs, /legal and /marketplace render this chrome with no labels at all, so a new label
    // without a default would leave those pages with nothing rather than with English.
    for (const key of ['language', 'toLightTheme', 'toDarkTheme', 'allIntegrations']) {
      expect(shellSrc, key).toMatch(new RegExp(`${key}: '[^']+'`));
    }
    const toggle = read(path.join(FRONTEND, 'components/landing/LandingThemeToggle.tsx'));
    expect(toggle).toContain("toLight = 'Switch to light theme'");
    expect(read(path.join(FRONTEND, 'components/landing/LandingLanguageSelect.tsx'))).toContain("label = 'Language'");
  });
});
