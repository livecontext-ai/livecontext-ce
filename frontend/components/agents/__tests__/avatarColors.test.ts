import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  PRESET_GRADIENTS,
  parsePresetValue,
  buildPresetValue,
  getAvatarGradient,
  heroGradientCss,
  buildRecoloredPresetDataUri,
} from '../avatarColors';

afterEach(() => {
  vi.unstubAllGlobals();
});

// The customized-preset value format ('preset:x?c1=..&c2=..') is what survives the
// backend snapshot filters (startsWith 'preset:'), so parsing/building it correctly
// is what makes recolored avatars render identically on marketplace cards and shares.
describe('avatarColors - value parsing/building', () => {
  it('parses a bare preset value with no custom colors', () => {
    expect(parsePresetValue('preset:purple')).toEqual({ presetId: 'preset:purple', colors: null, tool: null });
  });

  it('parses a customized preset value into base id + normalized hex colors', () => {
    expect(parsePresetValue('preset:teal?c1=ff0000&c2=00ff00')).toEqual({
      presetId: 'preset:teal',
      colors: { c1: '#FF0000', c2: '#00FF00' },
      tool: null,
    });
  });

  it('ignores malformed color params (bad hex) and keeps the base preset', () => {
    expect(parsePresetValue('preset:teal?c1=red&c2=00ff00')).toEqual({
      presetId: 'preset:teal',
      colors: null,
      tool: null,
    });
  });

  it('parses a tool badge, alone or combined with custom colors', () => {
    expect(parsePresetValue('preset:teal?tool=wrench')).toEqual({
      presetId: 'preset:teal',
      colors: null,
      tool: 'wrench',
    });
    expect(parsePresetValue('preset:teal?c1=ff0000&c2=00ff00&tool=git-branch')).toEqual({
      presetId: 'preset:teal',
      colors: { c1: '#FF0000', c2: '#00FF00' },
      tool: 'git-branch',
    });
  });

  it('parses an unknown tool id to null (nothing rendered, value otherwise kept)', () => {
    expect(parsePresetValue('preset:teal?tool=sword')).toEqual({
      presetId: 'preset:teal',
      colors: null,
      tool: null,
    });
  });

  it('returns null for uploaded/generated URLs and empty values', () => {
    expect(parsePresetValue('/api/proxy/files/avatar/abc')).toBeNull();
    expect(parsePresetValue('https://x/y.png')).toBeNull();
    expect(parsePresetValue(undefined)).toBeNull();
  });

  it('buildPresetValue round-trips through parsePresetValue', () => {
    const value = buildPresetValue('preset:blue', { c1: '#123456', c2: '#ABCDEF' });
    expect(value).toBe('preset:blue?c1=123456&c2=ABCDEF');
    expect(parsePresetValue(value)).toEqual({
      presetId: 'preset:blue',
      colors: { c1: '#123456', c2: '#ABCDEF' },
      tool: null,
    });
  });

  it('buildPresetValue encodes the tool after the colors and round-trips', () => {
    const value = buildPresetValue('preset:blue', { c1: '#123456', c2: '#ABCDEF' }, 'wrench');
    expect(value).toBe('preset:blue?c1=123456&c2=ABCDEF&tool=wrench');
    expect(parsePresetValue(value)).toEqual({
      presetId: 'preset:blue',
      colors: { c1: '#123456', c2: '#ABCDEF' },
      tool: 'wrench',
    });
  });

  it('buildPresetValue collapses back to the bare preset when colors equal the defaults', () => {
    const [c1, c2] = PRESET_GRADIENTS['preset:blue'];
    expect(buildPresetValue('preset:blue', { c1, c2 })).toBe('preset:blue');
  });

  it('buildPresetValue keeps the tool when default colors collapse, and drops a null tool', () => {
    const [c1, c2] = PRESET_GRADIENTS['preset:blue'];
    expect(buildPresetValue('preset:blue', { c1, c2 }, 'rocket')).toBe('preset:blue?tool=rocket');
    expect(buildPresetValue('preset:blue', { c1, c2 }, null)).toBe('preset:blue');
  });
});

describe('avatarColors - gradients', () => {
  it('every preset has a 2-stop gradient', () => {
    expect(Object.keys(PRESET_GRADIENTS)).toHaveLength(30);
    Object.values(PRESET_GRADIENTS).forEach(([a, b]) => {
      expect(a).toMatch(/^#[0-9A-F]{6}$/i);
      expect(b).toMatch(/^#[0-9A-F]{6}$/i);
    });
  });

  it('getAvatarGradient prefers custom colors, falls back to preset defaults, null for URLs', () => {
    expect(getAvatarGradient('preset:purple?c1=111111&c2=222222')).toEqual(['#111111', '#222222']);
    expect(getAvatarGradient('preset:purple')).toEqual(PRESET_GRADIENTS['preset:purple']);
    expect(getAvatarGradient('https://x/y.png')).toBeNull();
  });

  it('heroGradientCss uses the avatar colors when known and a neutral wash otherwise', () => {
    expect(heroGradientCss('preset:purple?c1=111111&c2=222222')).toContain('#111111');
    expect(heroGradientCss('/api/proxy/files/avatar/abc')).toContain('rgba(148,163,184');
  });
});

describe('avatarColors - recolored preset data-URI', () => {
  const STOCK_SVG = '<svg xmlns="http://www.w3.org/2000/svg"><stop stop-color="#7C3AED"/><stop stop-color="#4338CA"/></svg>';

  it('swaps both default gradient stops for the custom pair', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, text: () => Promise.resolve(STOCK_SVG) }));

    const uri = await buildRecoloredPresetDataUri('preset:purple', '/avatars/avatar-1.svg', {
      c1: '#FF0000',
      c2: '#00FF00',
    });

    expect(uri).not.toBeNull();
    const svg = decodeURIComponent(uri!.replace('data:image/svg+xml;charset=utf-8,', ''));
    expect(svg).toContain('#FF0000');
    expect(svg).toContain('#00FF00');
    expect(svg).not.toContain('#7C3AED');
    expect(svg).not.toContain('#4338CA');
  });

  it('returns null on fetch failure (caller keeps the stock preset)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));

    // Un-cached image path so the stubbed failure is actually exercised.
    const uri = await buildRecoloredPresetDataUri('preset:blue', '/avatars/avatar-2.svg', {
      c1: '#FF0000',
      c2: '#00FF00',
    });

    expect(uri).toBeNull();
  });

  it('returns null for an unknown preset id', async () => {
    const uri = await buildRecoloredPresetDataUri('preset:nope', '/avatars/avatar-1.svg', {
      c1: '#FF0000',
      c2: '#00FF00',
    });
    expect(uri).toBeNull();
  });
});
