import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  brandColors,
  DEFAULT_BAR_COLORS,
  integrationStats,
  isSafeIconSlug,
  titleFontSize,
} from '../integrationOgCard';
import type { PublicIntegration } from '../integrations';

const ICONS = join(process.cwd(), 'public', 'icons', 'services');

function integration(overrides: Partial<PublicIntegration> = {}): PublicIntegration {
  return {
    slug: 'slack',
    name: 'Slack',
    description: 'Team messaging.',
    iconSlug: 'slack',
    iconUrl: null,
    toolCount: 71,
    authType: 'oauth2',
    ...overrides,
  };
}

describe('brandColors', () => {
  it('reads the four Slack colours from the real Slack mark', () => {
    const svg = readFileSync(join(ICONS, 'slack.svg'), 'utf8');

    // In the order the current official mark draws them (svgl, 2026).
    expect(brandColors(svg)).toEqual(['#36C5F0', '#2EB67D', '#ECB22E', '#E01E5A']);
  });

  it('skips greys, near-black and near-white, which are outlines and backgrounds, not brand colours', () => {
    const svg = '<svg><path fill="#000000"/><path fill="#FFFFFF"/><path fill="#6b7280"/><path fill="#635BFF"/></svg>';

    expect(brandColors(svg)).toEqual(['#635BFF']);
  });

  it('expands 3-digit hex and de-duplicates across case', () => {
    const svg = '<svg><path fill="#f00"/><path fill="#FF0000"/><path fill="#ff0000"/></svg>';

    expect(brandColors(svg)).toEqual(['#FF0000']);
  });

  it('keeps at most four colours so the bar stays readable', () => {
    const svg = ['#E01E5A', '#36C5F0', '#2EB67D', '#ECB22E', '#635BFF'].map((c) => `<path fill="${c}"/>`).join('');

    expect(brandColors(svg)).toHaveLength(4);
  });

  it('does not take an 8-digit hex (colour + alpha) apart into a false 6-digit colour', () => {
    expect(brandColors('<path fill="#E01E5A80"/>')).toEqual([...DEFAULT_BAR_COLORS]);
  });

  it('falls back to the site-wide bar for a mono mark, so no card renders an empty or grey bar', () => {
    expect(brandColors('<svg><path fill="#000"/><path fill="currentColor"/></svg>')).toEqual([...DEFAULT_BAR_COLORS]);
  });

  it('falls back to the site-wide bar when the integration has no mark on disk', () => {
    expect(brandColors(null)).toEqual([...DEFAULT_BAR_COLORS]);
  });
});

describe('titleFontSize', () => {
  it('shrinks with the name so a long catalogue name stays clear of the mark', () => {
    expect(titleFontSize('Slack')).toBe(72);
    expect(titleFontSize('a'.repeat(12))).toBe(72);
    expect(titleFontSize('a'.repeat(13))).toBe(62);
    expect(titleFontSize('a'.repeat(20))).toBe(62);
    expect(titleFontSize('Google Analytics Admin API')).toBe(52);
  });
});

describe('isSafeIconSlug', () => {
  it('accepts the icon keys the catalogue uses', () => {
    expect(isSafeIconSlug('slack')).toBe(true);
    expect(isSafeIconSlug('google_sheets')).toBe(true);
    expect(isSafeIconSlug('01aiyi')).toBe(true);
    expect(isSafeIconSlug('x-ads')).toBe(true);
  });

  it('refuses anything that could leave the icons directory once joined into a path', () => {
    expect(isSafeIconSlug('../../etc/passwd')).toBe(false);
    expect(isSafeIconSlug('..')).toBe(false);
    expect(isSafeIconSlug('a/b')).toBe(false);
    expect(isSafeIconSlug('a\\b')).toBe(false);
    expect(isSafeIconSlug('')).toBe(false);
  });
});

describe('integrationStats', () => {
  it('shows the tool count, the auth type and the two product facts', () => {
    expect(integrationStats(integration())).toEqual([
      { icon: 'bolt', value: '71', label: 'Actions' },
      { icon: 'key', value: 'OAuth', label: 'Authentication' },
      { icon: 'bot', value: 'Agents', label: 'AI on a budget' },
      { icon: 'gift', value: 'Free', label: 'to start' },
    ]);
  });

  it('says "Action" for a single tool', () => {
    expect(integrationStats(integration({ toolCount: 1 }))[0]).toEqual({ icon: 'bolt', value: '1', label: 'Action' });
  });

  it('omits the tool count rather than showing zero', () => {
    const icons = integrationStats(integration({ toolCount: 0 })).map((s) => s.icon);

    expect(icons).not.toContain('bolt');
  });

  it('omits the auth stat when the integration declares none it can name', () => {
    const icons = integrationStats(integration({ authType: null })).map((s) => s.icon);

    expect(icons).toEqual(['bolt', 'bot', 'gift']);
  });
});
