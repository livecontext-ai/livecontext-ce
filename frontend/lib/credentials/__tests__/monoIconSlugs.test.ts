/**
 * Tests for `monoIconSlugs` - the single source of truth for brand logos
 * rendered in a near-black color that would be invisible on a dark
 * background. Mirrors `iconSlug.test.ts` style (one behaviour per test,
 * descriptive names) and pins the contract relied on by `ServiceIcon`,
 * `CredentialWizard`, and the landing page.
 */
import { describe, it, expect } from 'vitest';
import {
  MONO_DARK_ICON_SLUGS,
  isMonoDarkIconSlug,
  monoDarkInvertClass,
  extractIconSlugFromUrl,
} from '../monoIconSlugs';

describe('MONO_DARK_ICON_SLUGS', () => {
  it('contains every brand that motivated the centralization (regression)', () => {
    // These five were the union of the landing-page DARK_LOGOS set and the
    // bugs reported on /app/settings/credentials. If a refactor drops one,
    // the corresponding logo silently vanishes again in dark mode.
    expect(MONO_DARK_ICON_SLUGS.has('github')).toBe(true);
    expect(MONO_DARK_ICON_SLUGS.has('openai')).toBe(true);
    expect(MONO_DARK_ICON_SLUGS.has('anthropic')).toBe(true);
    expect(MONO_DARK_ICON_SLUGS.has('linear')).toBe(true);
    expect(MONO_DARK_ICON_SLUGS.has('dropbox')).toBe(true);
  });
});

describe('isMonoDarkIconSlug', () => {
  it('returns true for every slug in the set', () => {
    for (const slug of MONO_DARK_ICON_SLUGS) {
      expect(isMonoDarkIconSlug(slug)).toBe(true);
    }
  });

  it('returns false for multicolor brands (must NOT be inverted)', () => {
    // Inverting gmail/stripe/slack would turn red→cyan, etc. - visually
    // worse than the dark-mode invisibility bug we're fixing.
    expect(isMonoDarkIconSlug('gmail')).toBe(false);
    expect(isMonoDarkIconSlug('stripe')).toBe(false);
    expect(isMonoDarkIconSlug('slack')).toBe(false);
    expect(isMonoDarkIconSlug('googlesheets')).toBe(false);
  });

  it('returns false for null / undefined / empty input', () => {
    expect(isMonoDarkIconSlug(null)).toBe(false);
    expect(isMonoDarkIconSlug(undefined)).toBe(false);
    expect(isMonoDarkIconSlug('')).toBe(false);
  });
});

describe('monoDarkInvertClass', () => {
  it('returns the dark-mode invert utility chain for mono-dark slugs', () => {
    // The two utilities compose via Tailwind v4 CSS custom properties
    // (--tw-brightness, --tw-invert) into a single filter declaration.
    // brightness-0 first crushes the artwork to pure black so a near-black
    // like GitHub's #161614 inverts to white, not pale grey.
    expect(monoDarkInvertClass('github')).toBe('dark:brightness-0 dark:invert');
    expect(monoDarkInvertClass('openai')).toBe('dark:brightness-0 dark:invert');
  });

  it('returns empty string for non-mono slugs (concat-safe)', () => {
    // Callers do `${baseClasses} ${monoDarkInvertClass(slug)}` unconditionally;
    // an empty string keeps the className valid without sprinkling guards.
    expect(monoDarkInvertClass('gmail')).toBe('');
    expect(monoDarkInvertClass(null)).toBe('');
    expect(monoDarkInvertClass(undefined)).toBe('');
  });
});

describe('extractIconSlugFromUrl', () => {
  it('pulls the slug out of the canonical /icons/services/<slug>.svg path', () => {
    expect(extractIconSlugFromUrl('/icons/services/github.svg')).toBe('github');
    expect(extractIconSlugFromUrl('/icons/services/googlesheets.svg')).toBe('googlesheets');
  });

  it('returns undefined for null / empty input', () => {
    expect(extractIconSlugFromUrl(null)).toBeUndefined();
    expect(extractIconSlugFromUrl(undefined)).toBeUndefined();
    expect(extractIconSlugFromUrl('')).toBeUndefined();
  });

  it('returns undefined when the URL does not match the SVG pattern', () => {
    expect(extractIconSlugFromUrl('/icons/services/github.png')).toBeUndefined();
    expect(extractIconSlugFromUrl('https://cdn.example.com/foo')).toBeUndefined();
  });

  it('extracts only the final segment for nested-looking URLs', () => {
    // Defensive: callers may pass a fully-qualified URL or a relative path.
    expect(extractIconSlugFromUrl('https://example.com/icons/services/openai.svg')).toBe('openai');
  });
});
