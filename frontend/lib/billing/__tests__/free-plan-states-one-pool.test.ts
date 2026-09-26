/**
 * Wherever a price surface states the Free plan's credits, it states ONE pool.
 *
 * The Free plan used to hold two monthly pots: credits that funded workflow runs
 * only, and a separate AI allowance (V494) for chat and agent turns. They were
 * merged: the 1,000 monthly credits now pay for workflows AND for chat and agent
 * turns on the models an admin opened to the free tier. A surface that still
 * advertises the second pot promises credits the account does not have.
 *
 * That is a property of the whole SET of pricing surfaces, so no per-component
 * test can see it. Two guards, because a surface can go wrong in two places:
 *
 *  - the CATALOGUE: a locale file keeps (or regains) an AI-credits line.
 *  - the COMPONENT: a source file still renders one of those keys.
 */
import { describe, it, expect } from 'vitest';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import en from '../../../messages/en.json';
import fr from '../../../messages/fr.json';
import de from '../../../messages/de.json';
import es from '../../../messages/es.json';
import pt from '../../../messages/pt.json';
import zh from '../../../messages/zh.json';
import { PLAN_FEATURE_KEYS } from '@/lib/billing/pricing-constants';
import { buildPlanComparison } from '@/lib/billing/plan-comparison';

const root = join(__dirname, '..', '..', '..');
const SKIP_DIRS = new Set(['node_modules', '.next', '__tests__', 'e2e']);
const ROOTS = ['app', 'components', 'hooks', 'lib'];

/** The message keys the retired AI pot was rendered through, in either key family. */
const RETIRED_KEYS = ['aiCreditsFree', 'aiCreditsFreeUnknown', 'aiCreditsFreeTooltip', 'freeAiCredits', 'freeAiCreditsDetail'];

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

/** Comments stripped before scanning: a prose mention is not a rendered line. */
const withoutComments = (text: string) =>
  text.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/.*/g, '$1 ');

const sources = ROOTS.flatMap((dir) => {
  const full = join(root, dir);
  return existsSync(full) ? walk(full) : [];
}).map((path) => ({
  rel: relative(root, path).split(sep).join('/'),
  text: withoutComments(readFileSync(path, 'utf-8')),
}));

function keyPaths(node: unknown, path = ''): string[] {
  if (!node || typeof node !== 'object' || Array.isArray(node)) return [];
  return Object.entries(node as Record<string, unknown>).flatMap(([k, v]) => {
    const here = path ? `${path}.${k}` : k;
    return [here, ...keyPaths(v, here)];
  });
}

describe('every price surface states the Free plan as one pool', () => {
  it('finds sources to scan at all, so an empty walk cannot pass silently', () => {
    expect(sources.length).toBeGreaterThan(500);
    for (const dir of ROOTS) {
      expect(sources.some((f) => f.rel.startsWith(`${dir}/`)), `${dir}/ contributed no file`).toBe(true);
    }
  });

  it.each([['en', en], ['fr', fr], ['de', de], ['es', es], ['pt', pt], ['zh', zh]] as const)(
    '%s carries no AI-credits line for the Free plan',
    (_locale, messages) => {
      const offenders = keyPaths(messages).filter((path) => RETIRED_KEYS.includes(path.split('.').pop()!));
      expect(offenders).toEqual([]);
    },
  );

  it('no component renders a retired AI-credits key', () => {
    const offenders = sources
      .filter((f) => RETIRED_KEYS.some((key) => new RegExp(`['".]${key}['"(]`).test(f.text)))
      .map((f) => f.rel);
    expect(offenders).toEqual([]);
  });

  it('gives the Free card one credits bullet, with a figure', () => {
    expect(PLAN_FEATURE_KEYS.free).toContain('creditsFree');
    expect(PLAN_FEATURE_KEYS.free).not.toContain('aiCreditsFree');
    expect((en as any).pricing.planCards.features.creditsFree).toMatch(/\d/);
  });

  it('gives the comparison table one credits row, and no AI-credits row', () => {
    const usage = buildPlanComparison().find((section) => section.id === 'usage');
    expect(usage).toBeDefined();
    expect(usage!.rows.some((row) => row.id === 'aiCredits')).toBe(false);
    const credits = usage!.rows.find((row) => row.kind === 'scale' && row.id === 'credits');
    expect((credits as { cells: Record<string, string | null> }).cells.free).toBe('creditsFree');
  });

  it('says on every Free credits line that chat and agents are paid from it too', () => {
    // The merge is only visible to a reader if the line says so: "1,000 credits"
    // with a tooltip that still reads "workflows only" is the old rule in new words.
    const tooltip = (en as any).pricing.planCards.features.creditsFreeTooltip as string;
    expect(tooltip).toMatch(/agent/i);
    expect(tooltip).toContain('{exchangeCredits}');
    expect((en as any).modals.welcomeGift.features.freeCreditsDetail).toMatch(/chat and agent/i);
    expect((en as any).pricing.compare.values.creditsFree).not.toMatch(/workflow/i);
  });
});
