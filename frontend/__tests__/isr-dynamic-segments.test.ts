import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * A dynamic route segment that declares a positive `revalidate` must also export
 * `generateStaticParams`, or the `revalidate` does nothing.
 *
 * Next renders a `[param]` segment with no `generateStaticParams` on every
 * request and serves it `Cache-Control: private, no-store`. That is how all ~980
 * `/integrations/[slug]` pages were rendered from scratch on each view while
 * their code said "ISR, one hour". Returning `[]` pre-renders nothing at build
 * and caches each page on its first request.
 */
const appDir = path.resolve(__dirname, '../app');

/**
 * Deliberately left per-request, with the reason. A page listed here must NOT be
 * cached: it would serve content past its lifetime, or to the wrong people.
 */
const RENDERED_PER_REQUEST: Record<string, string> = {
  // Server-renders showcase media behind signed URLs that expire after 15 minutes;
  // a cached page could hand a visitor links that are already dead.
  'marketplace/[slug]': 'signed showcase URLs expire',
  // `fetchPublicProfile` returns null on an outage as well as on a 404, so a cached
  // render would pin a gateway blip as a 404 for the whole window, and a profile its
  // owner just made private must not stay pinned in a page (or CDN) cache.
  'u/[handle]': 'an outage reads as a 404; a private profile must not be pinned in a page cache',
};

const SOURCE = /^(page|layout)\.(tsx|ts|jsx|js)$/;
const STATIC_PARAMS_EXPORT = /export (async )?function generateStaticParams|export const generateStaticParams|export \{[^}]*\bgenerateStaticParams\b/;

function segmentFiles(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (entry !== '__tests__' && entry !== 'node_modules') segmentFiles(full, out);
    } else if (SOURCE.test(entry)) {
      out.push(full);
    }
  }
  return out;
}

/** The source without comments, so a commented-out export counts for nothing. */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

const code = (file: string) => stripComments(readFileSync(file, 'utf8'));

const route = (dir: string) => path.relative(appDir, dir).split(path.sep).join('/');

/**
 * `revalidate` opting into ISR: anything but 0 or false, which mean "deliberately
 * dynamic". An expression (`60 * 60`, `3_600`) counts as ISR rather than slipping
 * out of the guard unseen.
 */
function revalidateIsPositive(src: string): boolean {
  const match = src.match(/export const revalidate\s*=\s*([^;\n]+)/);
  if (!match) return false;
  const value = match[1].trim();
  return value !== '0' && value !== 'false';
}

/** `generateStaticParams` exported by any page or layout of the segment (or re-exported). */
function hasStaticParams(segmentDir: string): boolean {
  let dir = segmentDir;
  while (dir.startsWith(appDir)) {
    for (const entry of readdirSync(dir)) {
      if (SOURCE.test(entry) && STATIC_PARAMS_EXPORT.test(code(path.join(dir, entry)))) return true;
    }
    if (/\[.+\]$/.test(path.basename(dir))) break;
    dir = path.dirname(dir);
  }
  return false;
}

/** Directories of dynamic segments whose page or layout opts into ISR. */
const isrSegments = [
  ...new Set(
    segmentFiles(appDir)
      .filter((file) => /\[[^\]]+\]/.test(route(path.dirname(file))) && revalidateIsPositive(code(file)))
      .map((file) => path.dirname(file)),
  ),
];

describe('ISR on dynamic segments', () => {
  it('finds the ISR segments this guards, so the check is not vacuous', () => {
    expect(isrSegments.map(route)).toEqual(expect.arrayContaining(['integrations/[slug]', 'u/[handle]', 'marketplace/[slug]']));
  });

  it('gives every dynamic segment that declares revalidate its generateStaticParams', () => {
    const missing = isrSegments.filter((dir) => !hasStaticParams(dir) && !(route(dir) in RENDERED_PER_REQUEST)).map(route);
    expect(missing).toEqual([]);
  });

  it('keeps the per-request exceptions honest: each still exists and still lacks params', () => {
    for (const segment of Object.keys(RENDERED_PER_REQUEST)) {
      const dir = path.join(appDir, segment);
      expect(statSync(dir).isDirectory()).toBe(true);
      expect(hasStaticParams(dir)).toBe(false);
    }
  });

  it('caches the integration pages (the ~980 pages that were rendered per request)', () => {
    expect(hasStaticParams(path.join(appDir, 'integrations/[slug]'))).toBe(true);
  });

  it('the matchers ignore commented-out code, see re-exports, and read revalidate 0 as dynamic', () => {
    // Guards the guard itself.
    expect(STATIC_PARAMS_EXPORT.test(stripComments('// export async function generateStaticParams() { return []; }'))).toBe(false);
    expect(STATIC_PARAMS_EXPORT.test(stripComments('/* export const generateStaticParams = () => []; */'))).toBe(false);
    expect(STATIC_PARAMS_EXPORT.test("export { generateStaticParams } from './params';")).toBe(true);
    expect(revalidateIsPositive('export const revalidate = 3600;')).toBe(true);
    expect(revalidateIsPositive('export const revalidate = 0;')).toBe(false);
    expect(revalidateIsPositive('export const revalidate = false;')).toBe(false);
    expect(revalidateIsPositive('export const revalidate = 60 * 60;')).toBe(true);
  });
});
