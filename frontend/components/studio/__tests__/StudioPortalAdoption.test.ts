// @vitest-environment node
import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Every portalled surface the studio can open uses the wrapper that carries its look.
 *
 * <p><b>Why a tree scan and not a render test.</b> A popover, a select list and a dialog each
 * render in a PORTAL on the document, outside the element carrying the studio's colour tokens, so
 * each needs a wrapper that hands the class across. Three wrappers exist and each was written
 * believing it was the last one: `StudioSelectContent` calls itself "the one other place it
 * happens", `StudioDialogContent` calls itself "the third portal, and the one the first two fixes
 * missed". A fourth was found after both.
 *
 * <p>`StudioLookApplied.test.tsx` mounts the wrappers directly and proves they work. It cannot see
 * a call site that does not use one, which is exactly how each of these was missed. This reads the
 * call sites instead, so the next portal is found by a test rather than by a reader noticing a
 * bright panel over a dark page.
 *
 * <p>Modelled on `quoteKey.test.ts`'s "one spelling in the tree" guard, which exists in this repo
 * for the same reason: an invariant about CALL SITES cannot be asserted at the definition.
 */
describe('the studio look reaches every portal it can open', () => {
  const ROOT = process.cwd();

  /**
   * Files reachable from the studio surface, where a bare portal would be seen on the darkroom
   * ground. The credential components are in the list because the studio's model picker mounts
   * them: `CredentialSection` for the payer choice, and `CredentialWizard` from its "add my own
   * key" button.
   */
  const STUDIO_REACHABLE = [
    'components/studio',
    'app/workflows/builder/components/inspector/CredentialSection.tsx',
    'components/credentials/CredentialWizard.tsx',
    // The DESCENDANTS the studio renders, which the first version of this list omitted: a portal
    // opened three components deep lands on the darkroom ground exactly as one opened at the top.
    // Each of these is imported by a file in components/studio.
    'components/app/FileDetailView.tsx',
    'components/generation/GenerationHistoryList.tsx',
    'components/chat/HighlightedApps.tsx',
    'components/chat/HomeModeSwitch.tsx',
  ];

  /** The bare component, and the wrapper that carries the look for it. */
  const PORTALS = [
    { bare: 'PopoverContent', wrapper: 'StudioPopoverContent' },
    { bare: 'SelectContent', wrapper: 'StudioSelectContent' },
    { bare: 'DialogContent', wrapper: 'StudioDialogContent' },
    // The fourth type, listed with no offender yet. Each of the three above was added after a
    // bright panel had already been seen over a dark page, and each called itself the last one -
    // so the list is completed against the design system rather than against the next symptom.
    { bare: 'TooltipContent', wrapper: 'StudioTooltipContent' },
  ];

  /** The wrappers themselves are the one place the bare component is legitimately named. */
  const DEFINITIONS = PORTALS.map((p) => `components/studio/${p.wrapper}.tsx`);

  function sourceFiles(target: string): string[] {
    const full = path.join(ROOT, target);
    if (!fs.existsSync(full)) return [];
    if (fs.statSync(full).isFile()) return [full];
    const out: string[] = [];
    const walk = (dir: string) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        if (entry.name === 'node_modules' || entry.name.startsWith('.')) continue;
        const child = path.join(dir, entry.name);
        if (entry.isDirectory()) { walk(child); continue; }
        if (!/\.tsx?$/.test(entry.name)) continue;
        if (child.includes('__tests__')) continue;
        out.push(child);
      }
    };
    walk(full);
    return out;
  }

  const files = STUDIO_REACHABLE.flatMap(sourceFiles)
    .map((f) => path.relative(ROOT, f).split(path.sep).join('/'))
    .filter((f) => !DEFINITIONS.includes(f));

  it('has files to check, so a broken path cannot make this vacuous', () => {
    // Every assertion below is over a list. An empty list passes them all.
    expect(files.length).toBeGreaterThan(5);
    expect(files).toContain('components/credentials/CredentialWizard.tsx');
    expect(files).toContain('app/workflows/builder/components/inspector/CredentialSection.tsx');
    // A descendant, so a path that stopped resolving is caught rather than silently shrinking the
    // surface this guard covers.
    expect(files).toContain('components/app/FileDetailView.tsx');
  });

  for (const { bare, wrapper } of PORTALS) {
    it(`opens no bare <${bare}> anywhere the studio can reach`, () => {
      // `<Name` rather than the bare word: the import line and a type annotation both mention the
      // component without rendering one, and only a rendered element opens a portal.
      const offenders = files.filter((file) => {
        const source = fs.readFileSync(path.join(ROOT, file), 'utf8');
        return new RegExp(`<${bare}[\\s/>]`).test(source);
      });

      expect(
        offenders,
        `these render <${bare}> directly, so on the darkroom ground they come back in the `
          + `application's theme. Use <${wrapper}>, which is inert off a studio surface: `
          + offenders.join(', '),
      ).toEqual([]);
    });
  }

  it('names a wrapper for every portal the app actually has', () => {
    // The guard above is only as complete as its list. If a fourth kind of portalled content is
    // added to the design system, this is the line that has to change with it.
    for (const { wrapper } of PORTALS) {
      expect(
        fs.existsSync(path.join(ROOT, 'components', 'studio', `${wrapper}.tsx`)),
        `${wrapper} must exist for this guard to have a remedy to point at`,
      ).toBe(true);
    }
  });
});
