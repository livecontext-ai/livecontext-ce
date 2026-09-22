// @vitest-environment node
import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The treatment's colours, MEASURED rather than asserted in a comment.
 *
 * <p><b>Why this exists.</b> Every value in the `.studio-darkroom` blocks shipped with a comment
 * quoting its own contrast ratio, and three of those numbers were wrong or incomplete: the muted
 * text was checked on two of the three grounds the block defines and sat at 4.43:1 on the third,
 * the dark half was excused at 3.91:1 on the grounds that the application itself is worse, and the
 * one piece of chrome the treatment adds - the hairline that stops a white generation dissolving
 * into a light wall - was drawn at 1.41:1 against that wall. A prose ratio is a claim; this is a
 * check.
 *
 * <p>Ratios are computed from the stylesheet itself, so a value edited without its comment cannot
 * pass here, and a comment edited without its value cannot either.
 */
describe('the studio treatment meets WCAG on the grounds it defines', () => {
  const css = fs.readFileSync(path.join(process.cwd(), 'app', 'globals.css'), 'utf8');

  /**
   * The block a selector opens.
   *
   * <p>Anchored on the preceding newline rather than by regex: `.studio-darkroom {` is a SUBSTRING
   * of `.dark .studio-darkroom {`, so a plain `indexOf` finds the wrong block and silently compares
   * one half of the theme against itself. That exact mistake made the parity guard in
   * StudioLook.test.tsx vacuous.
   */
  function blockOf(selector: string): string {
    const start = css.indexOf(`\n${selector} {`);
    expect(start, `${selector} must exist in globals.css as a rule of its own`).toBeGreaterThan(-1);
    const end = css.indexOf('\n}', start);
    expect(end, `${selector} must be a closed block`).toBeGreaterThan(start);
    return css.slice(start, end);
  }

  /**
   * The same lookup for the APPLICATION's own blocks, which sit indented inside a cascade layer
   * rather than at the start of a line, so the anchored reader above cannot find them.
   */
  function appToken(selector: string, name: string): string {
    const start = css.indexOf(`${selector} {`);
    expect(start, `${selector} must exist in globals.css`).toBeGreaterThan(-1);
    const end = css.indexOf('\n  }', start);
    expect(end, `${selector} must be a closed block`).toBeGreaterThan(start);
    const found = new RegExp(`${name}\\s*:\\s*(#[0-9a-fA-F]{3,8})`).exec(css.slice(start, end));
    expect(found, `${selector} must set ${name} to a hex colour`).not.toBeNull();
    return (found as RegExpExecArray)[1];
  }

  /** Every custom property the block declares, so a new ground is measured automatically. */
  function tokensOf(selector: string): string[] {
    return [...blockOf(selector).matchAll(/^\s*(--[\w-]+)\s*:/gm)].map((m) => m[1]);
  }

  function token(selector: string, name: string): string {
    const found = new RegExp(`${name}\\s*:\\s*(#[0-9a-fA-F]{3,8})`).exec(blockOf(selector));
    expect(found, `${selector} must set ${name} to a hex colour`).not.toBeNull();
    return (found as RegExpExecArray)[1];
  }

  /** WCAG 2.1 relative luminance. */
  function luminance(hex: string): number {
    const channels = (hex.replace('#', '').match(/../g) as string[])
      .slice(0, 3)
      .map((pair) => parseInt(pair, 16) / 255)
      .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
  }

  function ratio(a: string, b: string): number {
    const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
    return (hi + 0.05) / (lo + 0.05);
  }

  const AA_TEXT = 4.5;
  const AA_NON_TEXT = 3;

  for (const half of ['.studio-darkroom', '.dark .studio-darkroom']) {
    it(`${half}: muted text clears AA on EVERY ground the block defines`, () => {
      // EVERY ground, which this said in its title and did not do: it iterated three of the four
      // the block defines. The missing one was --bg-hover, and that is not only a hover state -
      // the model picker and the parameter menus paint the SELECTED row with it, permanently, and
      // that row carries muted text. It measured 4.11:1 there while the title claimed AA.
      //
      // Derived from the block rather than listed, so a FIFTH ground added later is measured
      // without anyone remembering to add it here.
      const muted = token(half, '--text-muted');
      const grounds = tokensOf(half).filter((name) => name.startsWith('--bg-'));
      expect(grounds.length, 'the block must define grounds to measure against')
        .toBeGreaterThanOrEqual(4);
      for (const ground of grounds) {
        const measured = ratio(muted, token(half, ground));
        expect(measured, `${muted} on ${ground} is ${measured.toFixed(2)}:1`)
          .toBeGreaterThanOrEqual(AA_TEXT);
      }
    });

    it(`${half}: the asset frame is visible against the ground it separates from`, () => {
      // The hairline is the treatment's only chrome and its whole job is to be seen. Drawn in
      // --border-color it was 1.41:1 in light and 1.58:1 in dark, so the line meant to stop an
      // asset dissolving into the page very nearly dissolved itself.
      const measured = ratio(token(half, '--asset-frame-color'), token(half, '--bg-primary'));
      expect(measured, `the frame is ${measured.toFixed(2)}:1 on the ground`)
        .toBeGreaterThanOrEqual(AA_NON_TEXT);
    });

    it(`${half}: the asset frame is visible against a WHITE asset too`, () => {
      // It draws a boundary with a ground on one side and an image on the other, and the case it
      // was written for is a white-background generation.
      const measured = ratio(token(half, '--asset-frame-color'), '#ffffff');
      expect(measured, `the frame is ${measured.toFixed(2)}:1 against white`)
        .toBeGreaterThanOrEqual(AA_NON_TEXT);
    });
  }

  it('the border never separates LESS than the application\'s own does', () => {
    // The one token this suite argued about in prose instead of measuring, in a file whose stated
    // thesis is that a prose ratio is a claim. Measured, the light half is 1.41:1 where the app is
    // 1.39:1 - it owes nothing - and the dark half was 1.58:1 where the app is 2.64:1, a
    // regression the treatment introduced by deepening the ground without lightening the line.
    //
    // The bar is the APPLICATION's own value, not 3:1. Neither reaches 3:1 and that is a standard
    // for the app to move; what a treatment must not do is make it worse on a surface a reader
    // opts into.
    const appLight = ratio(appToken(':root', '--border-color'), appToken(':root', '--bg-primary'));
    const appDark = ratio(appToken('.dark', '--border-color'), appToken('.dark', '--bg-primary'));

    const studioLight = ratio(token('.studio-darkroom', '--border-color'),
      token('.studio-darkroom', '--bg-primary'));
    const studioDark = ratio(token('.dark .studio-darkroom', '--border-color'),
      token('.dark .studio-darkroom', '--bg-primary'));

    expect(studioLight, `light: studio ${studioLight.toFixed(2)}:1 vs app ${appLight.toFixed(2)}:1`)
      .toBeGreaterThanOrEqual(appLight);
    expect(studioDark, `dark: studio ${studioDark.toFixed(2)}:1 vs app ${appDark.toFixed(2)}:1`)
      .toBeGreaterThanOrEqual(appDark);
  });

  it('measures something: a wrong ratio must be able to fail', () => {
    // The guard on the guard. Every assertion above reads colours out of the stylesheet, so a
    // lookup that stopped matching would make all of them vacuous rather than red.
    expect(ratio('#000000', '#ffffff')).toBeCloseTo(21, 0);
    expect(ratio(token('.studio-darkroom', '--bg-primary'), '#ffffff')).toBeLessThan(2);
    expect(blockOf('.studio-darkroom')).not.toEqual(blockOf('.dark .studio-darkroom'));
  });
});
