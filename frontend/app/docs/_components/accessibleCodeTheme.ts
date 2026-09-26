// Makes a react-syntax-highlighter (Prism) theme meet WCAG 1.4.3 on a known
// background. The stock One Light tokens measure 2.96:1 to 3.39:1 on the docs code
// surface (#f5f6f8); each token colour keeps its hue and saturation and only moves
// its lightness (darker on a light background, lighter on a dark one) until it
// reaches the target ratio. Colours that already pass are left untouched.

import type { CSSProperties } from 'react';

/** The shape of a react-syntax-highlighter theme: selector -> CSS rules. */
type Style = { [selector: string]: CSSProperties };

const MIN_RATIO = 4.6; // a hair above 4.5 so rounding never lands under the bar

function hexToRgb(hex: string): [number, number, number] {
  const h = hex.replace('#', '');
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h;
  return [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16)) as [number, number, number];
}

function hslToRgb(h: number, s: number, l: number): [number, number, number] {
  const sat = s / 100;
  const light = l / 100;
  const k = (n: number) => (n + h / 30) % 12;
  const a = sat * Math.min(light, 1 - light);
  const f = (n: number) => light - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)));
  return [f(0), f(8), f(4)].map((v) => Math.round(v * 255)) as [number, number, number];
}

function luminance([r, g, b]: [number, number, number]): number {
  const lin = (v: number) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

export function contrastRatio(a: [number, number, number], b: [number, number, number]): number {
  const la = luminance(a);
  const lb = luminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

const HSL = /^hsla?\(\s*([\d.]+)\s*,\s*([\d.]+)%\s*,\s*([\d.]+)%\s*(?:,\s*([\d.]+)\s*)?\)$/;

/** Returns an `hsl()` colour with enough contrast on `background`, or the input unchanged. */
export function ensureContrast(color: string, background: string): string {
  const m = color.trim().match(HSL);
  // Only opaque hsl() colours are adjusted: translucent ones (selection
  // highlights) are backgrounds, not text, and `inherit` has nothing to adjust.
  if (!m || (m[4] !== undefined && Number(m[4]) < 1)) return color;
  const [h, s] = [Number(m[1]), Number(m[2])];
  let l = Number(m[3]);
  const bg = hexToRgb(background);
  const step = luminance(bg) > 0.5 ? -1 : 1;
  if (contrastRatio(hslToRgb(h, s, l), bg) >= MIN_RATIO) return color;
  while (l > 0 && l < 100 && contrastRatio(hslToRgb(h, s, l), bg) < MIN_RATIO) l += step;
  return `hsl(${h}, ${s}%, ${l}%)`;
}

/** A copy of `theme` whose token text colours all reach 4.5:1 on `background`. */
export function accessibleCodeTheme<T extends Style>(theme: T, background: string): T {
  const out: Style = {};
  for (const [selector, rules] of Object.entries(theme)) {
    out[selector] =
      typeof rules.color === 'string' ? { ...rules, color: ensureContrast(rules.color, background) } : rules;
  }
  return out as T;
}
