import { readFileSync, readdirSync, statSync } from 'fs';
import { join, relative } from 'path';
import { describe, expect, it } from 'vitest';

/**
 * LC-027 CASA E3 (round 2): no `<iframe sandbox="...">` in this codebase may ever combine
 * `allow-same-origin` with a sandbox that renders interface/publisher HTML - `InterfaceIframe`
 * (workflow interfaces, `/interface-frame` shell), `ApplicationTabContent` (the chat/app-tab
 * publisher frame), `InterfacePreview`/`ShowcasePreview`/`MarketplaceCardPreview` (marketplace
 * previews) and the marketplace acquisition preview page.
 *
 * `allow-same-origin` + `allow-scripts` together defeat the sandbox entirely for the framed
 * document's own origin: injected/publisher-authored script could then read the frame's
 * `document.cookie`/`localStorage`/DOM as if it were same-origin, and (since these frames are
 * same-origin to the PARENT app under the `/interface-frame` shell and `srcDoc` modes) potentially
 * reach the parent's storage too. Every one of the surfaces above deliberately omits it today (see
 * the "NEVER add allow-same-origin" comments in InterfaceIframe.tsx, ApplicationTabContent.tsx,
 * InterfacePreview.tsx, ShowcasePreview.tsx and the marketplace preview page) - this test is the
 * regression guard that keeps it that way, since nothing else in the 3-layer contract would catch
 * a future PR that adds it back for a "just this one case" reason.
 *
 * Grep-based, not AST-based, to match every place a `sandbox` VALUE is written, including a
 * destructured default (`sandbox = 'allow-scripts'`) and not just a literal JSX attribute. A
 * comment merely NAMING `allow-same-origin` in prose (as the ones above do) never matches: the
 * pattern requires `sandbox` to be directly followed by `=` and an opening quote containing the
 * token, which comment prose is not shaped like.
 */

const FRONTEND_ROOT = join(__dirname, '..');

const SCAN_EXTENSIONS = new Set(['.tsx', '.ts']);
const EXCLUDED_DIR_NAMES = new Set([
  'node_modules',
  '__tests__',
  '.next',
  'e2e',
  'coverage',
  '.git',
]);

/**
 * Files allowed to combine a `sandbox=` value with `allow-same-origin` - empty today (no
 * legitimate first-party use exists: every sandboxed frame in the app renders untrusted
 * interface/publisher HTML or is a plain same-origin file preview that does not need `sandbox`
 * at all). Add an entry here ONLY with a comment justifying why that specific frame needs both
 * `allow-same-origin` and `allow-scripts` together, and why the content it renders is trusted.
 */
const ALLOWLIST: readonly string[] = [];

const SANDBOX_ALLOW_SAME_ORIGIN = /\bsandbox\s*=\s*(?:\{\s*)?['"`][^'"`]*allow-same-origin[^'"`]*['"`]/;

function collectSourceFiles(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    if (EXCLUDED_DIR_NAMES.has(entry)) continue;
    const full = join(dir, entry);
    const stat = statSync(full);
    if (stat.isDirectory()) {
      collectSourceFiles(full, out);
      continue;
    }
    const dot = entry.lastIndexOf('.');
    if (dot === -1) continue;
    if (SCAN_EXTENSIONS.has(entry.slice(dot))) out.push(full);
  }
  return out;
}

describe('no iframe sandbox grants allow-same-origin (LC-027 CASA E3)', () => {
  it('finds a plausible number of source files, so a broken walker cannot pass by scanning nothing', () => {
    expect(collectSourceFiles(join(FRONTEND_ROOT, 'app')).length).toBeGreaterThan(50);
  });

  it('never combines allow-same-origin with a sandbox value, anywhere outside the allow-list', () => {
    const roots = ['app', 'components', 'lib', 'hooks', 'contexts'].map((dir) => join(FRONTEND_ROOT, dir));
    const offenders: string[] = [];

    for (const root of roots) {
      for (const file of collectSourceFiles(root)) {
        const relPath = relative(FRONTEND_ROOT, file).split('\\').join('/');
        if (ALLOWLIST.includes(relPath)) continue;
        const source = readFileSync(file, 'utf8');
        if (SANDBOX_ALLOW_SAME_ORIGIN.test(source)) offenders.push(relPath);
      }
    }

    expect(
      offenders,
      `these files grant allow-same-origin inside an iframe sandbox - combined with allow-scripts `
        + `this lets framed content read the frame's own storage/DOM as same-origin, defeating the `
        + `sandbox for interface/publisher HTML. Add allow-same-origin only via the ALLOWLIST above, `
        + `with a comment justifying it:\n  ${offenders.join('\n  ')}`,
    ).toEqual([]);
  });

  it('the detector itself actually fires on the dangerous shape (proves the regex is not vacuous)', () => {
    expect(SANDBOX_ALLOW_SAME_ORIGIN.test('<iframe sandbox="allow-scripts allow-same-origin" />')).toBe(true);
    expect(SANDBOX_ALLOW_SAME_ORIGIN.test("sandbox = 'allow-scripts allow-same-origin',")).toBe(true);
    expect(SANDBOX_ALLOW_SAME_ORIGIN.test('sandbox={`allow-scripts allow-same-origin`}')).toBe(true);
  });

  it('does not false-positive on prose that only NAMES allow-same-origin (comments, docs)', () => {
    expect(SANDBOX_ALLOW_SAME_ORIGIN.test('// NEVER add allow-same-origin here.')).toBe(false);
    expect(SANDBOX_ALLOW_SAME_ORIGIN.test('sandbox (no allow-same-origin) so untrusted JS cannot reach the parent.')).toBe(false);
    expect(SANDBOX_ALLOW_SAME_ORIGIN.test("sandbox='allow-scripts'")).toBe(false);
  });
});
