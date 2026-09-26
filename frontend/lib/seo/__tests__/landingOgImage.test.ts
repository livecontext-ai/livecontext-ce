import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { locales } from '@/i18n/routing';
import { landingOgImage } from '../siteUrl';

/**
 * Each localised landing page shares a card in its own language. The cards are pre-rendered
 * files, so the failure to guard is a locale added to routing with no card: its share would
 * point at a missing image, which every network renders as a broken or empty preview.
 */
describe('landingOgImage', () => {
  it.each(locales)('points %s at a card that exists and is a real 1200x630 JPEG', (locale) => {
    const url = landingOgImage(locale);
    const file = join(process.cwd(), 'public', url);

    expect(url).toBe(`/landing/og/home-${locale}.jpg`);
    expect(existsSync(file)).toBe(true);

    const bytes = readFileSync(file);
    expect(bytes.subarray(0, 3).toString('hex')).toBe('ffd8ff');
    // Baseline JPEG SOF0 marker carries height then width.
    const sof = bytes.indexOf(Buffer.from([0xff, 0xc0]));
    expect(sof).toBeGreaterThan(0);
    expect(bytes.readUInt16BE(sof + 5)).toBe(630);
    expect(bytes.readUInt16BE(sof + 7)).toBe(1200);
  });

  it('keeps the site-wide card identical to the English landing card', () => {
    // The generator writes home-<locale>.jpg only; og-image.jpg (shared by ~15 pages) is a
    // manual copy of home-en.jpg, so a regenerated English card must not leave it behind.
    const siteWide = readFileSync(join(process.cwd(), 'public', 'og-image.jpg'));
    const english = readFileSync(join(process.cwd(), 'public', 'landing', 'og', 'home-en.jpg'));
    expect(siteWide.equals(english)).toBe(true);
  });

  it('falls back to the site-wide card for a locale it has no card for, never a missing file', () => {
    expect(landingOgImage('xx')).toBe('/og-image.jpg');
    expect(existsSync(join(process.cwd(), 'public', 'og-image.jpg'))).toBe(true);
  });
});
