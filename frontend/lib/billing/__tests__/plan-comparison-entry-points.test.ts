/**
 * WHERE the plan comparison can be opened from, as a closed list.
 *
 * The overlay is a full-screen Free-to-Enterprise matrix. It reached the app by
 * being wired into every upsell in sight - the sidebar's plan name, the billing,
 * usage and storage pages, four modals, a locked-node marker in the builder -
 * which put it one stray click away on surfaces a reader passes through all day.
 * It now belongs where choosing a plan is the task, and nowhere else: the
 * pricing page and the public landing's pricing section. It briefly gained a
 * third entry point, the end of onboarding, which is the counter-example worth
 * keeping: a brand-new account is not choosing a plan, so it is greeted by
 * WelcomeGiftModal stating the two monthly pots instead of by a five-column
 * matrix.
 *
 * That is a property of the whole tree, so no rendering test can see it: each
 * new entry point is one line in a file nobody else's suite reads. This walks
 * the source instead and fails on any opener that is not on the list, which is
 * the only thing standing between "two entry points" and a slow return to a
 * dozen.
 */
import { describe, it, expect } from 'vitest';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const root = join(__dirname, '..', '..', '..');

/** Files allowed to open the comparison, as repo-relative POSIX paths. */
const ALLOWED = [
  // The component every opener goes through. It is the mechanism, not a surface.
  'components/pricing/ComparePlansLink.tsx',
  // The pricing page: the "Compare plans" button under the plan grid.
  'components/pricing/PricingPageContent.tsx',
  // The public landing's pricing section, same button.
  'app/[locale]/_landing/PricingSection.tsx',
];

/** Where the dialog itself is mounted. Every host serves an allowed opener. */
const ALLOWED_HOSTS = [
  // Wraps the app tree, which is where the pricing page lives. Mounted at the
  // layout rather than in the page because the dialog listens on a window event.
  'app/[locale]/app/layout.tsx',
  // The landing section mounts its own: it is outside the app tree.
  'app/[locale]/_landing/PricingSection.tsx',
  // The host component itself.
  'components/pricing/AppPlanComparisonDialog.tsx',
];

const SKIP_DIRS = new Set(['node_modules', '.next', '__tests__', 'e2e']);

/** Trees walked. Not just app/ + components/: a helper hook is exactly where an
 *  `useUpsell()` that opens the comparison would land, and it would be invisible
 *  to a walk of the render tree alone. */
const ROOTS = ['app', 'components', 'hooks', 'contexts', 'lib', 'utils'];

/** The module that DEFINES the opener, and the guard reading this. */
const DEFINITION = 'lib/billing/plan-comparison-open.ts';

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (SKIP_DIRS.has(entry)) continue;
      walk(full, out);
      continue;
    }
    if (/\.tsx?$/.test(entry) && !/\.(test|spec)\.tsx?$/.test(entry)) out.push(full);
  }
  return out;
}

/**
 * Comments stripped before scanning. The files at the centre of this rule
 * NAME the opener in their docblocks - explaining where it may be called from
 * is most of what those blocks are for - and a prose mention is not a call.
 * Without this the guard fires on the dialog's own documentation, which teaches
 * the next reader to delete the sentence rather than the entry point.
 */
const withoutComments = (text: string) =>
  text.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/.*/g, '$1 ');

const sources = ROOTS.flatMap((dir) => {
  const full = join(root, dir);
  return existsSync(full) ? walk(full) : [];
})
  .map((path) => ({
    // POSIX-separated so the expectations read the same on any machine.
    rel: relative(root, path).split(sep).join('/'),
    text: withoutComments(readFileSync(path, 'utf-8')),
  }))
  .filter((f) => f.rel !== DEFINITION);

describe('the plan comparison is opened from the pricing surfaces only', () => {
  it('finds sources to scan at all, so an empty walk cannot pass silently', () => {
    expect(sources.length).toBeGreaterThan(500);
    expect(sources.some((f) => f.rel === 'components/pricing/PricingPageContent.tsx')).toBe(true);
    // Every root actually contributes: a typo in one of them would quietly stop
    // covering a whole tree while the count above still passes on the others.
    for (const dir of ROOTS) {
      expect(sources.some((f) => f.rel.startsWith(`${dir}/`)), `${dir}/ contributed no file`).toBe(true);
    }
  });

  it('has exactly the allowed openers', () => {
    // Every spelling of "opens it", because the others are one rename apart
    // from the first: rendering the shared button, importing it under another
    // name, calling the helper, or dispatching its event by hand. The module
    // that defines the helper is filtered out above; this file is not walked.
    const opens = (text: string) =>
      /<ComparePlansLink/.test(text) ||
      /openPlanComparison\(/.test(text) ||
      // The raw event, but only where it is DISPATCHED: the dialog itself names
      // the same constant to listen for it, and a listener is the opposite of an
      // entry point.
      (/PLAN_COMPARISON_EVENT/.test(text) && /dispatchEvent/.test(text)) ||
      // Catches the button under any local name, which a `<ComparePlansLink`
      // match alone would miss after `import Compare from '.../ComparePlansLink'`.
      /from '@\/components\/pricing\/ComparePlansLink'/.test(text);

    const openers = sources.filter((f) => opens(f.text)).map((f) => f.rel).sort();

    expect(openers).toEqual([...ALLOWED].sort());
  });

  it('mounts the dialog only where an allowed opener can reach it', () => {
    // A host with no opener in its tree is dead weight that quietly invites the
    // next entry point: the builder route kept one after its locked-node marker
    // stopped opening the overlay.
    const hosts = sources
      .filter((f) => /<(App)?PlanComparisonDialog\b/.test(f.text))
      .map((f) => f.rel)
      .sort();

    expect(hosts).toEqual([...ALLOWED_HOSTS].sort());
  });

  it('keeps the sidebar plan name a label, not a way in', () => {
    // The single most-passed-through surface in the app, and the one the
    // comparison was reachable from on every page.
    const sidebar = sources.find((f) => f.rel === 'components/app/AppSidebar.tsx');
    expect(sidebar, 'AppSidebar.tsx must be in the scanned set').toBeDefined();
    expect(sidebar!.text).not.toContain('openPlanComparison');
    expect(sidebar!.text).not.toContain('ComparePlansLink');
  });
});
