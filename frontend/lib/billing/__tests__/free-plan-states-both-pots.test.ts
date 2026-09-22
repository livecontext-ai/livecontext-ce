/**
 * Wherever a price surface states the Free plan's credits, it states BOTH pots.
 *
 * A Free account holds two separate monthly allowances since V494: the credits
 * that fund workflow runs, and the AI allowance that funds chat and agent turns
 * on the models an admin opened to the free tier. They are not interchangeable,
 * and a surface that names only the first is not merely incomplete - it reads as
 * "chat spends your credits too", which is the opposite of what happens and the
 * exact question a reader is asking on every one of these surfaces.
 *
 * That is a property of the whole SET of pricing surfaces, so no per-component
 * test can see it: each new surface is one feature list in a file nobody else's
 * suite renders. Two guards, because a surface can go wrong in two places:
 *
 *  - the CATALOGUE: a new surface writes its own free-plan credits line and no
 *    allowance line. Caught by walking the messages, where every such line has
 *    to appear whatever component renders it.
 *  - the COMPONENT: the keys both exist and the component renders only one.
 *    Caught by walking the source, the same way the comparison's entry points
 *    are held to a list.
 */
import { describe, it, expect } from 'vitest';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import en from '../../../messages/en.json';
import { PLAN_FEATURE_KEYS } from '@/lib/billing/pricing-constants';
import { buildPlanComparison } from '@/lib/billing/plan-comparison';

const root = join(__dirname, '..', '..', '..');
const SKIP_DIRS = new Set(['node_modules', '.next', '__tests__', 'e2e']);
const ROOTS = ['app', 'components', 'hooks', 'lib'];

/**
 * How a free-plan credits line is spelled, and the allowance line that must sit
 * beside it. Two spellings because the two families of surface name their keys
 * differently: the plan cards and the comparison table share one feature-key
 * vocabulary, the upsell modals have their own.
 */
const SIBLINGS: ReadonlyArray<{ credits: string; ai: string }> = [
  { credits: 'creditsFree', ai: 'aiCreditsFree' },
  { credits: 'freeCredits', ai: 'freeAiCredits' },
];

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
 * Comments stripped before scanning: the files at the centre of this rule NAME
 * both keys in their docblocks, and a prose mention is not a rendered line.
 * Without this a surface could satisfy the guard with a sentence.
 */
const withoutComments = (text: string) =>
  text.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/.*/g, '$1 ');

const sources = ROOTS.flatMap((dir) => {
  const full = join(root, dir);
  return existsSync(full) ? walk(full) : [];
}).map((path) => ({
  rel: relative(root, path).split(sep).join('/'),
  text: withoutComments(readFileSync(path, 'utf-8')),
}));

/** Every object in the catalogue, with the path that leads to it. */
function messageObjects(node: unknown, path = ''): Array<{ path: string; keys: string[] }> {
  if (!node || typeof node !== 'object' || Array.isArray(node)) return [];
  const here = { path, keys: Object.keys(node as Record<string, unknown>) };
  const nested = Object.entries(node as Record<string, unknown>).flatMap(([k, v]) =>
    messageObjects(v, path ? `${path}.${k}` : k),
  );
  return [here, ...nested];
}

describe('every price surface states both of the Free plan pots', () => {
  it('finds sources to scan at all, so an empty walk cannot pass silently', () => {
    expect(sources.length).toBeGreaterThan(500);
    for (const dir of ROOTS) {
      expect(sources.some((f) => f.rel.startsWith(`${dir}/`)), `${dir}/ contributed no file`).toBe(true);
    }
  });

  it('never writes a free-plan credits line without the allowance beside it', () => {
    // The catalogue guard, and the one that catches a surface nobody has
    // written yet: a new upsell, a new plan card, a new comparison - whatever
    // renders it, its two lines live here as siblings.
    const blocks = messageObjects(en);
    const offenders: string[] = [];
    let checked = 0;

    for (const { path, keys } of blocks) {
      for (const { credits, ai } of SIBLINGS) {
        if (!keys.includes(credits)) continue;
        checked += 1;
        if (!keys.includes(ai)) offenders.push(`${path}.${credits}`);
      }
    }

    expect(checked, 'no free-plan credits line found at all - the guard is looking at nothing').toBeGreaterThan(0);
    expect(offenders, `these state the credits with no AI allowance beside them: ${offenders.join(', ')}`).toEqual([]);
  });

  it('no component renders the credits line of an upsell without its allowance line', () => {
    // The component guard. Scoped to the upsell modals' vocabulary, which is
    // namespaced enough to be unambiguous: the plan cards' own keys collide
    // with an unrelated agent-budget field (`creditsFree` on the Agent type),
    // and they are pinned structurally by the two tests below instead.
    const quoting = sources.filter((f) => f.text.includes('features.freeCredits'));
    expect(quoting.length, 'nothing renders features.freeCredits any more').toBeGreaterThan(0);

    const missing = quoting.filter((f) => !f.text.includes('features.freeAiCredits')).map((f) => f.rel);

    expect(missing, `these render the credits but not the AI allowance: ${missing.join(', ')}`).toEqual([]);
  });

  it('gives the Free plan a card bullet for each pot, in that order', () => {
    // Order matters on the card: the credits line's own tooltip points DOWN to
    // the allowance ("Agents draw the separate monthly AI allowance below").
    const keys = PLAN_FEATURE_KEYS.free;
    expect(keys).toContain('creditsFree');
    expect(keys).toContain('aiCreditsFree');
    expect(keys.indexOf('aiCreditsFree')).toBe(keys.indexOf('creditsFree') + 1);
  });

  it('gives the comparison table a row for each pot, with a Free cell on both', () => {
    const usage = buildPlanComparison().find((section) => section.id === 'usage');
    expect(usage, 'the usage section is where both pots are stated').toBeDefined();

    const rowFor = (id: string) => usage!.rows.find((row) => row.kind === 'scale' && row.id === id);
    const credits = rowFor('credits');
    const aiCredits = rowFor('aiCredits');

    expect(credits, 'no credits row').toBeDefined();
    expect(aiCredits, 'no AI allowance row').toBeDefined();
    expect((credits as { cells: Record<string, string | null> }).cells.free).toBe('creditsFree');
    expect((aiCredits as { cells: Record<string, string | null> }).cells.free).toBe('aiCreditsFree');
  });

  it('greets a new account with a row for each pot, not a sentence for one', () => {
    // The welcome gift is the FIRST price surface a new account meets, and the
    // only one it meets before it can have spent anything. It states the two
    // pots as two rows rather than as a feature line, so the catalogue guard
    // above sees its key pair and this pins the component actually rendering
    // both. Without this, dropping one row leaves every other test green.
    const gift = sources.find((f) => f.rel === 'components/billing/WelcomeGiftModal.tsx');
    expect(gift, 'the welcome gift modal is gone - move or delete this expectation').toBeDefined();
    expect(gift!.text).toContain('features.freeCredits');
    expect(gift!.text).toContain('features.freeAiCredits');
  });

  it('states the workflow credits as a figure and the allowance as a placeholder', () => {
    // Deliberately different shapes, and the difference is the point. The
    // workflow grant is a product constant; the AI allowance is a row an admin
    // edits, so a surface that baked it into the sentence would go on
    // advertising a number the renewal no longer hands out.
    const cards = (en as any).pricing.planCards.features;
    expect(cards.creditsFree).toMatch(/\d/);
    expect(cards.aiCreditsFree).toContain('{credits}');
    expect((en as any).pricing.compare.values.aiCreditsFree).toContain('{credits}');
    expect((en as any).modals.insufficientCredits.features.freeAiCredits).toContain('{credits}');
    expect((en as any).modals.insufficientStorage.features.freeAiCredits).toContain('{credits}');
  });
});
