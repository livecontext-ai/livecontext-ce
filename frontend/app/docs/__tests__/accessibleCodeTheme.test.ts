import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import path from 'path';
import { oneLight, oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { accessibleCodeTheme, contrastRatio, ensureContrast } from '../_components/accessibleCodeTheme';

const LIGHT_BG = '#f5f6f8';
const DARK_BG = '#1f1e1b';

function rgbOf(color: string): [number, number, number] {
  // Resolve any CSS colour the way the browser would, through a canvas-free parse of hsl().
  const m = color.match(/hsla?\(\s*([\d.]+)\s*,\s*([\d.]+)%\s*,\s*([\d.]+)%/)!;
  const [h, s, l] = [Number(m[1]), Number(m[2]) / 100, Number(m[3]) / 100];
  const k = (n: number) => (n + h / 30) % 12;
  const a = s * Math.min(l, 1 - l);
  const f = (n: number) => l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)));
  return [f(0), f(8), f(4)].map((v) => Math.round(v * 255)) as [number, number, number];
}
const hex = (h: string) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16)) as [number, number, number];

describe('code block backgrounds', () => {
  it('adjusts the token colours against the real --bg-secondary of each docs theme', () => {
    // CodeBlock hard-codes the two surfaces it adjusts against; they must stay equal to the
    // theme tokens the code block is actually painted on, or the contrast fix measures the wrong thing.
    const read = (rel: string) => readFileSync(path.resolve(__dirname, '../../..', rel), 'utf8');
    const chrome = read('components/landing/LandingShell.tsx');
    const light = chrome.match(/\.landing-root \{[^}]*--bg-secondary: (#[0-9a-f]{6})/)![1];
    const dark = chrome.match(/\.landing-root\.dark \{[^}]*--bg-secondary: (#[0-9a-f]{6})/)![1];
    const codeBlock = read('app/docs/_components/CodeBlock.tsx');
    expect(codeBlock).toContain(`accessibleCodeTheme(oneLight, '${light}')`);
    expect(codeBlock).toContain(`accessibleCodeTheme(oneDark, '${dark}')`);
    expect([light, dark]).toEqual([LIGHT_BG, DARK_BG]);
  });
});

describe('accessibleCodeTheme', () => {
  it('fixes the One Light tokens that failed the axe scan (red #e45649 at 3.39:1, green #50a14f at 2.96:1)', () => {
    expect(contrastRatio(rgbOf('hsl(5, 74%, 59%)'), hex(LIGHT_BG))).toBeLessThan(4.5);
    expect(contrastRatio(rgbOf(ensureContrast('hsl(5, 74%, 59%)', LIGHT_BG)), hex(LIGHT_BG))).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(rgbOf(ensureContrast('hsl(119, 34%, 47%)', LIGHT_BG)), hex(LIGHT_BG))).toBeGreaterThanOrEqual(4.5);
  });

  it('keeps the hue and saturation, and only moves lightness', () => {
    expect(ensureContrast('hsl(5, 74%, 59%)', LIGHT_BG)).toMatch(/^hsl\(5, 74%, \d+%\)$/);
  });

  it('leaves colours that already pass, translucent colours, and inherit untouched', () => {
    expect(ensureContrast('hsl(230, 8%, 24%)', LIGHT_BG)).toBe('hsl(230, 8%, 24%)');
    expect(ensureContrast('hsla(230, 8%, 24%, 0.2)', LIGHT_BG)).toBe('hsla(230, 8%, 24%, 0.2)');
    expect(ensureContrast('inherit', LIGHT_BG)).toBe('inherit');
  });

  it.each([
    ['One Light', oneLight, LIGHT_BG],
    ['One Dark', oneDark, DARK_BG],
  ])('makes every opaque token colour of %s reach 4.5:1 on the docs code surface', (_name, theme, bg) => {
    const adjusted = accessibleCodeTheme(theme, bg);
    for (const rules of Object.values(adjusted)) {
      const color = rules.color;
      if (typeof color !== 'string' || color === 'inherit' || /^hsla\(/.test(color)) continue;
      // Every other token colour must be an hsl() the helper can adjust: a hex colour would be
      // skipped by ensureContrast and never checked here, so fail loudly instead.
      expect(color, 'unsupported token colour format').toMatch(/^hsl\(/);
      expect(contrastRatio(rgbOf(color), hex(bg)), color).toBeGreaterThanOrEqual(4.5);
    }
  });
});
