import { describe, it, expect } from 'vitest';
import enMessages from '@/messages/en.json';
import { PLAN_FEATURE_KEYS } from '@/lib/billing/pricing-constants';
import {
  buildPlanComparison,
  COMPARISON_PLAN_IDS,
  allFeatureKeys,
  resolveComparisonPlanId,
  resolveRequiredPlanId,
  type ComparisonPlanId,
} from '@/lib/billing/plan-comparison';

/**
 * The comparison is DERIVED from `PLAN_FEATURE_KEYS`, so the property worth
 * pinning is not the shape of the table but that it says the same thing the
 * plan cards say: every feature a plan lists is stated somewhere in its column,
 * exactly once, and no column claims a feature its plan does not have.
 *
 * That is what makes it safe to add a feature key to `PLAN_FEATURE_KEYS` alone.
 * The failure this guards against is silent: a key nobody routed would simply
 * be absent from the table while the card still shows it, and the two surfaces
 * would disagree with no error anywhere.
 */

const sections = buildPlanComparison();
const rows = sections.flatMap((s) => s.rows);

/** The feature keys the table states for a plan, with duplicates kept. */
function statedKeysFor(planId: ComparisonPlanId): string[] {
  return rows.flatMap((row) => {
    if (row.kind === 'scale') {
      const key = row.cells[planId];
      return key ? [key] : [];
    }
    return row.cells[planId] ? [row.id] : [];
  });
}

describe('buildPlanComparison', () => {
  it('states every feature of every plan, and states it once', () => {
    for (const planId of COMPARISON_PLAN_IDS) {
      const declared = [...(PLAN_FEATURE_KEYS[planId] ?? [])].sort();
      const stated = statedKeysFor(planId);

      expect(
        [...stated].sort(),
        `${planId}: the table must state exactly what the plan card lists`
      ).toEqual(declared);
      expect(
        new Set(stated).size,
        `${planId}: a feature appears in two rows: ${stated.join(', ')}`
      ).toBe(stated.length);
    }
  });

  it('never credits a plan with a feature it does not list', () => {
    for (const planId of COMPARISON_PLAN_IDS) {
      const declared = new Set(PLAN_FEATURE_KEYS[planId] ?? []);
      for (const row of rows) {
        if (row.kind === 'scale') {
          const key = row.cells[planId];
          if (key) {
            expect(declared.has(key), `${planId} does not include ${key}`).toBe(true);
          }
        } else if (row.cells[planId]) {
          expect(declared.has(row.id), `${planId} does not include ${row.id}`).toBe(true);
        }
      }
    }
  });

  it('gives every row a cell for every plan', () => {
    for (const row of rows) {
      for (const planId of COMPARISON_PLAN_IDS) {
        expect(planId in row.cells, `row ${row.id} has no cell for ${planId}`).toBe(true);
      }
    }
  });

  it('has no duplicate rows and no empty section', () => {
    const ids = rows.map((row) => `${row.kind}:${row.id}`);
    expect(new Set(ids).size).toBe(ids.length);
    for (const section of sections) {
      expect(section.rows.length, `section ${section.id} is empty`).toBeGreaterThan(0);
    }
  });

  it('never leaves a hole in a dimension once a plan answers it', () => {
    // A blank cell reads as "this plan does not include it", which is only ever
    // true BELOW the tier that introduces a dimension. A blank ABOVE one that
    // has a value states the opposite of what is true, and it is invisible on
    // the plan cards, which show one bullet fewer and nothing else. Enterprise
    // carried exactly that hole on the support dimension until 2026-08-31.
    for (const row of rows) {
      if (row.kind !== 'scale') continue;
      // A row that declares a fallback has no blank cells to misread: the
      // renderer prints that value instead of the "not included" cross, so the
      // premise above does not apply. The AI allowance is the case - only Free
      // has a separate pot, and the paid plans lack one precisely because their
      // credits already fund agents. The fallback must actually say something,
      // or this exemption would let a real hole through.
      if (row.fallbackValueKey) {
        // Not just "non-empty": the exemption is only sound if the fallback
        // actually RESOLVES, otherwise a typo turns the blank cell into a raw
        // message path and the hole comes back wearing a different hat.
        expect(
          (enMessages as { pricing: { compare: { values: Record<string, string> } } }).pricing.compare.values[row.fallbackValueKey],
          `${row.id} declares fallback "${row.fallbackValueKey}", missing from pricing.compare.values`,
        ).toBeTruthy();
        continue;
      }
      const answered = COMPARISON_PLAN_IDS.map((planId) => row.cells[planId] !== null);
      const first = answered.indexOf(true);
      if (first === -1) continue;
      const holes = COMPARISON_PLAN_IDS.filter(
        (planId, index) => index > first && !answered[index]
      );
      expect(holes, `${row.id} goes blank again on: ${holes.join(', ')}`).toEqual([]);
    }
  });

  it('reads cheapest-plan-first, so Free is the first column', () => {
    expect(COMPARISON_PLAN_IDS[0]).toBe('free');
    expect(COMPARISON_PLAN_IDS[COMPARISON_PLAN_IDS.length - 1]).toBe('enterprise');
  });

  it('lists a scale row where the plans genuinely differ', () => {
    const storage = rows.find((row) => row.kind === 'scale' && row.id === 'storage');
    expect(storage).toBeDefined();
    expect(storage!.cells.free).toBe('storage100mb');
    expect(storage!.cells.enterprise).toBe('storage1tb');
  });

  it('marks a capability absent on the plans that do not have it', () => {
    const sso = rows.find((row) => row.kind === 'flag' && row.id === 'sso');
    expect(sso).toBeDefined();
    expect(sso!.cells.free).toBe(false);
    expect(sso!.cells.team).toBe(true);
  });
});

describe('allFeatureKeys', () => {
  it('returns each key once, in cheapest-plan-first order of appearance', () => {
    const keys = allFeatureKeys();
    expect(new Set(keys).size).toBe(keys.length);
    // 'creditsFree' belongs to Free and 'sso' first appears on Team, so the
    // cheaper one must come first.
    expect(keys.indexOf('creditsFree')).toBeLessThan(keys.indexOf('sso'));
  });

  it('covers every key of every plan', () => {
    const union = new Set(Object.values(PLAN_FEATURE_KEYS).flat());
    expect(new Set(allFeatureKeys())).toEqual(union);
  });
});

describe('resolveComparisonPlanId', () => {
  it.each([
    ['FREE', 'free'],
    ['STARTER', 'starter'],
    ['PRO', 'pro'],
    ['TEAM', 'team'],
    ['ENTERPRISE', 'enterprise'],
  ])('maps %s to the %s column', (code, expected) => {
    expect(resolveComparisonPlanId(code)).toBe(expected);
  });

  it('maps every Enterprise SKU to the single Enterprise column', () => {
    for (const sku of ['ENTERPRISE_BASIC', 'ENTERPRISE_STANDARD', 'ENTERPRISE_PREMIUM', 'ENTERPRISE_ULTIMATE']) {
      expect(resolveComparisonPlanId(sku)).toBe('enterprise');
    }
  });

  it('accepts the lowercase and padded forms a caller may pass through', () => {
    expect(resolveComparisonPlanId(' pro ')).toBe('pro');
    expect(resolveComparisonPlanId('Team')).toBe('team');
  });

  it('marks nothing when no plan governs', () => {
    // Null and blank: unknown. '__NONE__': a connected cloud account with no
    // subscription. 'CE': a self-hosted install, which has no plan to be on.
    expect(resolveComparisonPlanId(null)).toBeNull();
    expect(resolveComparisonPlanId('')).toBeNull();
    expect(resolveComparisonPlanId('__NONE__')).toBeNull();
    expect(resolveComparisonPlanId('CE')).toBeNull();
  });

  it('marks nothing for a plan code it does not know', () => {
    // Fails to "no column" rather than to a wrong one: marking the wrong plan
    // as current is worse than marking none.
    expect(resolveComparisonPlanId('CREDIT_PACK')).toBeNull();
    expect(resolveComparisonPlanId('PAYG')).toBeNull();
  });
});

describe('resolveRequiredPlanId', () => {
  it('points at the plan that lifts the restriction', () => {
    expect(resolveRequiredPlanId('STARTER')).toBe('starter');
    expect(resolveRequiredPlanId('ENTERPRISE_PREMIUM')).toBe('enterprise');
  });

  it('emphasises nothing when there is no upgrade to point at', () => {
    // FREE is a requirement everyone already meets, so highlighting the Free
    // column would tell the reader to move to the plan they are on.
    expect(resolveRequiredPlanId('FREE')).toBeNull();
    expect(resolveRequiredPlanId(null)).toBeNull();
    expect(resolveRequiredPlanId('WHATEVER')).toBeNull();
  });
});
