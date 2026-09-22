import { describe, expect, it } from 'vitest';
import { PERSONA_KEYS, PERSONA_TINTS } from '../personas';
import { landingStyles } from '@/components/landing/landingStyles';
import { landingChromeStyles } from '@/components/landing/LandingShell';

/**
 * `PERSONA_TINTS` is now ONE editable constant painting both the persona pages and the home
 * page's role-card buttons, and the button is tinted text on a tinted ground. That is legible
 * today only because the mix was walked in by hand: at the first attempt the amber measured
 * 3.4:1, under the 4.5:1 that 14px text at weight 500 needs. Nothing stopped the next hue
 * edit from putting it back there, which is what this file is for.
 *
 * <p>The model below mirrors the stylesheet rather than guessing, and the first test asserts
 * the stylesheet still uses these numbers, so a CSS change cannot silently invalidate the
 * arithmetic instead of failing it.
 */
const LIGHT_GROUND = [0xec, 0xef, 0xf3]; // --bg-tertiary, light
const DARK_GROUND = [0x2a, 0x29, 0x25]; // --bg-tertiary, dark
const STAGE_ALPHA = { light: 0.16, dark: 0.22 }; // strongest stop of the card stage gradient
const BUTTON_ALPHA = { light: 0.14, dark: 0.2 }; // .role-card-cta background
const TEXT_MIX = { light: 0.55, dark: 0.5 }; // color-mix(... tint X%, black|white)

const rgb = (triplet: string) => triplet.split(',').map(Number);
const over = (fg: number[], alpha: number, bg: number[]) => fg.map((c, i) => c * alpha + bg[i] * (1 - alpha));
const mix = (tint: number[], keep: number, towards: number) => tint.map((c) => c * keep + towards * (1 - keep));

function relativeLuminance([r, g, b]: number[]) {
  const channel = (value: number) => {
    const s = value / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

function contrast(a: number[], b: number[]) {
  const [high, low] = [relativeLuminance(a), relativeLuminance(b)].sort((x, y) => y - x);
  return (high + 0.05) / (low + 0.05);
}

function ctaContrast(persona: (typeof PERSONA_KEYS)[number], theme: 'light' | 'dark') {
  const tint = rgb(PERSONA_TINTS[persona]);
  const base = theme === 'light' ? LIGHT_GROUND : DARK_GROUND;
  const stage = over(tint, STAGE_ALPHA[theme], base);
  const ground = over(tint, BUTTON_ALPHA[theme], stage);
  const text = mix(tint, TEXT_MIX[theme], theme === 'light' ? 0 : 255);
  return contrast(text, ground);
}

describe('the role-card call to action stays readable in every persona colour', () => {
  it('is modelled on the numbers the stylesheet actually uses', () => {
    // If any of these move, the arithmetic below is describing a page that no longer exists.
    expect(landingChromeStyles).toContain('--bg-tertiary: #eceff3');
    expect(landingChromeStyles).toContain('--bg-tertiary: #2a2925');
    expect(landingStyles).toContain('color-mix(in srgb, rgb(var(--role-tint)) 55%, #000000)');
    expect(landingStyles).toContain('color-mix(in srgb, rgb(var(--role-tint)) 50%, #ffffff)');
    // And the two that measured under the floor are gone, not merely outnumbered.
    expect(landingStyles).not.toContain('rgb(var(--role-tint)) 60%, #000000');
    expect(landingStyles).not.toContain('rgb(var(--role-tint)) 58%, #ffffff');
    expect(landingStyles).toContain('background: rgba(var(--role-tint), .14)');
    expect(landingStyles).toContain('background: rgba(var(--role-tint), .2)');
  });

  it.each(PERSONA_KEYS)('clears 4.5:1 for %s in light mode', (persona) => {
    expect(ctaContrast(persona, 'light')).toBeGreaterThanOrEqual(4.5);
  });

  it.each(PERSONA_KEYS)('clears 4.5:1 for %s in dark mode', (persona) => {
    expect(ctaContrast(persona, 'dark')).toBeGreaterThanOrEqual(4.5);
  });

  it('would fail on the mixes this replaced', () => {
    // Proof the model has teeth rather than passing whatever it is given. Both of these were
    // in the file and both are under the floor: the amber at 60% light (4.47:1) and the pink
    // at 55% dark (4.41:1). The first draft's 72% is worse still.
    const sales = rgb(PERSONA_TINTS.sales);
    const lightGround = over(sales, BUTTON_ALPHA.light, over(sales, STAGE_ALPHA.light, LIGHT_GROUND));
    expect(contrast(mix(sales, 0.72, 0), lightGround)).toBeLessThan(4.5);
    expect(contrast(mix(sales, 0.6, 0), lightGround)).toBeLessThan(4.5);

    const marketing = rgb(PERSONA_TINTS.marketing);
    const darkGround = over(marketing, BUTTON_ALPHA.dark, over(marketing, STAGE_ALPHA.dark, DARK_GROUND));
    expect(contrast(mix(marketing, 0.55, 255), darkGround)).toBeLessThan(4.5);
  });
});
