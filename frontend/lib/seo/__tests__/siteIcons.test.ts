import { readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { PUBLIC_ROOT_FILES } from '@/lib/seo/servedFiles';

/**
 * Every icon the root layout declares, and the favicon browsers fetch on their own.
 *
 * All of them used to point at the 1024x1024 logo, a 1.4 MB PNG, and
 * `favicon.ico` was a byte copy of it: the heaviest request on every public page,
 * reported by Lighthouse on all ten pages audited.
 */
const frontend = path.resolve(__dirname, '../../..');
const layoutSrc = readFileSync(path.join(frontend, 'app/layout.tsx'), 'utf8');

/** The `icons: { ... }` block of the root metadata, so no other URL is picked up. */
const iconsBlock = (() => {
  const start = layoutSrc.indexOf('  icons: {');
  expect(start).toBeGreaterThan(-1);
  const end = layoutSrc.indexOf('\n  },', start);
  expect(end).toBeGreaterThan(start);
  return layoutSrc.slice(start, end);
})();

const declaredIcons = [...iconsBlock.matchAll(/'(\/[^']+\.(?:png|ico))'/g)].map((m) => m[1]);

/** Small enough for a request made on every page; the old logo was 1,429,672 bytes. */
const MAX_ICON_BYTES = 20 * 1024;

describe('site icons', () => {
  it('declares a favicon, a large icon and an apple touch icon', () => {
    expect(declaredIcons).toEqual(expect.arrayContaining(['/favicon.ico', '/icon-192.png', '/apple-touch-icon.png']));
  });

  it('no longer points any icon at the full-size logo', () => {
    expect(declaredIcons).not.toContain('/liveContext-logo.png');
  });

  it.each(['/favicon.ico', '/icon-192.png', '/apple-touch-icon.png'])('%s is a small file that the proxy serves', (icon) => {
    const size = statSync(path.join(frontend, 'public', icon)).size;
    expect(size).toBeGreaterThan(0);
    expect(size).toBeLessThan(MAX_ICON_BYTES);
    expect(PUBLIC_ROOT_FILES).toContain(icon.slice(1));
  });

  it('serves favicon.ico as a real ICO holding the three sizes it declares', () => {
    const ico = readFileSync(path.join(frontend, 'public/favicon.ico'));
    // ICONDIR: reserved 0, type 1 (icon), then the image count.
    expect(ico.readUInt16LE(0)).toBe(0);
    expect(ico.readUInt16LE(2)).toBe(1);
    const count = ico.readUInt16LE(4);
    // Each 16-byte ICONDIRENTRY starts with width then height (0 would mean 256).
    const sizes = Array.from({ length: count }, (_, i) => `${ico[6 + i * 16]}x${ico[7 + i * 16]}`);
    expect(sizes).toEqual(['16x16', '32x32', '48x48']);
  });

  it.each([
    ['/icon-192.png', 192],
    ['/apple-touch-icon.png', 180],
  ])('%s really is %ipx square, as declared', (icon, side) => {
    const png = readFileSync(path.join(frontend, 'public', icon));
    // The IHDR chunk follows the 8-byte signature: width at 16, height at 20.
    expect(png.readUInt32BE(16)).toBe(side);
    expect(png.readUInt32BE(20)).toBe(side);
    expect(iconsBlock).toContain(`'${side}x${side}'`);
  });

  it('keeps the full-size logo, still referenced by the JSON-LD, under 100 KB', () => {
    expect(statSync(path.join(frontend, 'public/liveContext-logo.png')).size).toBeLessThan(100 * 1024);
  });
});
