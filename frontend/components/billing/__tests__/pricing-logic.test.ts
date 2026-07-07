import { describe, it, expect } from 'vitest';
import { CREDIT_TIERS, CREDIT_COSTS, TEAM_CREDIT_COSTS, STARTER_MAX_CREDITS, calcPrice, formatTierLabel, getCreditCost, DEFAULT_MAX_TIER_INDEX, resolveMaxTierIndex, clampTierIndex, PLAN_FEATURE_KEYS, CAPABILITY_KEYS } from '@/lib/billing/pricing-constants';

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('Pricing Logic', () => {

  // =========================================================================
  // calcPrice
  // =========================================================================

  describe('calcPrice', () => {
    describe('base prices without credits (tier 0)', () => {
      it('should return base price for Starter monthly at tier 0', () => {
        expect(calcPrice('starter', 'monthly', 0)).toBe(10);
      });

      it('should return base price for Pro monthly at tier 0', () => {
        expect(calcPrice('pro', 'monthly', 0)).toBe(24);
      });

      it('should return base price for Team monthly at tier 0', () => {
        expect(calcPrice('team', 'monthly', 0)).toBe(49);
      });

      it('should return 0 for free plan', () => {
        expect(calcPrice('free', 'monthly', 0)).toBe(0);
      });

      it('should return 0 for unknown plan', () => {
        expect(calcPrice('enterprise', 'monthly', 0)).toBe(0);
      });
    });

    describe('monthly prices with credits', () => {
      it('should add credit cost to base for Starter', () => {
        // Starter tier 2: base 10 + credit 22 = 32
        expect(calcPrice('starter', 'monthly', 2)).toBe(32);
      });

      it('should add credit cost to base for Pro', () => {
        // Pro tier 4: base 24 + credit 80 = 104
        expect(calcPrice('pro', 'monthly', 4)).toBe(104);
      });

      it('should use explicit Team credit costs', () => {
        // Team tier 2: base 49 + credit 30 = 79
        expect(calcPrice('team', 'monthly', 2)).toBe(79);
      });

      it('should handle Team tier 1 credit cost', () => {
        // Team tier 1: base 49 + credit 15 = 64
        expect(calcPrice('team', 'monthly', 1)).toBe(64);
      });
    });

    describe('yearly prices (base-plan discount only)', () => {
      it('should apply 20% discount to Starter base at tier 0', () => {
        // monthly = 10, yearly = Math.round(10 * 0.8) = 8
        expect(calcPrice('starter', 'yearly', 0)).toBe(8);
      });

      it('should apply yearly discount only to Pro base price', () => {
        // base yearly monthly-equivalent = 19, credits are not discounted: 19 + 80 = 99
        expect(calcPrice('pro', 'yearly', 4)).toBe(99);
      });

      it('should apply yearly discount only to Team base price', () => {
        // Team tier 4: base yearly monthly-equivalent 39 + credit 100 = 139
        expect(calcPrice('team', 'yearly', 4)).toBe(139);
      });

      it('should return 0 for free plan yearly', () => {
        expect(calcPrice('free', 'yearly', 0)).toBe(0);
      });
    });

    describe('all tier indices (boundary)', () => {
      it('should handle tier 0 (minimum)', () => {
        expect(CREDIT_COSTS[0]).toBe(0);
        expect(calcPrice('starter', 'monthly', 0)).toBe(10);
      });

      it('should handle tier 9 (maximum)', () => {
        expect(CREDIT_COSTS[9]).toBe(7000);
        expect(calcPrice('pro', 'monthly', 9)).toBe(24 + 7000);
      });

      it('should handle STARTER_MAX_TIER_INDEX (tier 4)', () => {
        expect(CREDIT_TIERS[4]).toBe(STARTER_MAX_CREDITS);
        expect(calcPrice('starter', 'monthly', 4)).toBe(10 + 80);
      });
    });

    describe('every plan x every tier (comprehensive)', () => {
      const plans = ['starter', 'pro', 'team'];
      const cycles: Array<'monthly' | 'yearly'> = ['monthly', 'yearly'];

      for (const plan of plans) {
        for (const cycle of cycles) {
          for (let tier = 0; tier < CREDIT_TIERS.length; tier++) {
            it(`${plan} ${cycle} tier ${tier} should return a positive number`, () => {
              const price = calcPrice(plan, cycle, tier);
              expect(price).toBeGreaterThan(0);
              expect(Number.isInteger(price)).toBe(true);
            });
          }
        }
      }
    });
  });

  // =========================================================================
  // Credit Tier Constants
  // =========================================================================

  describe('credit tier constants', () => {
    it('should have 10 tiers', () => {
      expect(CREDIT_TIERS.length).toBe(10);
      expect(CREDIT_COSTS.length).toBe(10);
      expect(TEAM_CREDIT_COSTS.length).toBe(10);
    });

    it('should have monotonically increasing tiers', () => {
      for (let i = 1; i < CREDIT_TIERS.length; i++) {
        expect(CREDIT_TIERS[i]).toBeGreaterThan(CREDIT_TIERS[i - 1]);
      }
    });

    it('should have monotonically increasing costs', () => {
      for (let i = 1; i < CREDIT_COSTS.length; i++) {
        expect(CREDIT_COSTS[i]).toBeGreaterThan(CREDIT_COSTS[i - 1]);
      }
    });

    it('tier 0 should cost $0', () => {
      expect(CREDIT_COSTS[0]).toBe(0);
      expect(TEAM_CREDIT_COSTS[0]).toBe(0);
    });

    it('tier 0 should give 5000 credits', () => {
      expect(CREDIT_TIERS[0]).toBe(5000);
    });

    it('STARTER_MAX_CREDITS should match tier 4', () => {
      expect(STARTER_MAX_CREDITS).toBe(CREDIT_TIERS[4]);
    });
  });

  // =========================================================================
  // formatTierLabel
  // =========================================================================

  describe('formatTierLabel', () => {
    it('should format thousands as K', () => {
      expect(formatTierLabel(5000)).toBe('5K');
      expect(formatTierLabel(10000)).toBe('10K');
      expect(formatTierLabel(25000)).toBe('25K');
      expect(formatTierLabel(50000)).toBe('50K');
      expect(formatTierLabel(100000)).toBe('100K');
      expect(formatTierLabel(250000)).toBe('250K');
      expect(formatTierLabel(500000)).toBe('500K');
    });

    it('should format millions as M', () => {
      expect(formatTierLabel(1000000)).toBe('1M');
      expect(formatTierLabel(5000000)).toBe('5M');
      expect(formatTierLabel(10000000)).toBe('10M');
    });

    it('should return raw number for values under 1000', () => {
      expect(formatTierLabel(500)).toBe('500');
      expect(formatTierLabel(1)).toBe('1');
    });
  });

  // =========================================================================
  // Credit Tier Change Logic (edge cases)
  // =========================================================================

  describe('credit tier change detection', () => {
    it('should detect upgrade: tier 1 -> tier 3', () => {
      const currentCost = CREDIT_COSTS[1]; // 10
      const newCost = CREDIT_COSTS[3]; // 42
      expect(newCost > currentCost).toBe(true);
    });

    it('should detect downgrade: tier 5 -> tier 2', () => {
      const currentCost = CREDIT_COSTS[5]; // 185
      const newCost = CREDIT_COSTS[2]; // 22
      expect(newCost < currentCost).toBe(true);
    });

    it('should detect no change: same tier', () => {
      const currentCost = CREDIT_COSTS[3];
      const newCost = CREDIT_COSTS[3];
      expect(newCost === currentCost).toBe(true);
    });

    it('should detect downgrade to tier 0 (removing credit pack)', () => {
      const currentCost = CREDIT_COSTS[4]; // 80
      const newCost = CREDIT_COSTS[0]; // 0
      expect(newCost < currentCost).toBe(true);
      expect(newCost).toBe(0);
    });
  });

  describe('getCreditCost', () => {
    it('should return standard credit cost for Pro', () => {
      expect(getCreditCost('pro', 4)).toBe(80);
    });

    it('should return explicit Team credit cost', () => {
      expect(getCreditCost('team', 4)).toBe(100);
      expect(getCreditCost('team', 9)).toBe(8_000);
    });
  });

  // =========================================================================
  // Plan Selection Logic
  // =========================================================================

  describe('plan selection logic', () => {
    // Simulates the isCreditTierChanged check from pricing page
    function isCreditTierChanged(currentPlanCode: string, creditTierIndex: number, subscriptionCreditTierIndex: number) {
      return currentPlanCode !== 'FREE' && creditTierIndex !== subscriptionCreditTierIndex;
    }

    it('should not flag credit change for FREE plan', () => {
      expect(isCreditTierChanged('FREE', 3, 0)).toBe(false);
    });

    it('should flag credit change when tier differs', () => {
      expect(isCreditTierChanged('STARTER', 3, 1)).toBe(true);
    });

    it('should not flag when same tier', () => {
      expect(isCreditTierChanged('STARTER', 2, 2)).toBe(false);
    });

    // Simulates effectiveCurrentPlan logic
    it('should set effectiveCurrentPlan to NONE when credit tier changed', () => {
      const currentPlanCode = 'STARTER';
      const creditTierIndex = 3;
      const subscriptionCreditTierIndex = 1;
      const changed = isCreditTierChanged(currentPlanCode, creditTierIndex, subscriptionCreditTierIndex);
      const effectiveCurrentPlan = changed ? 'NONE' : currentPlanCode;
      expect(effectiveCurrentPlan).toBe('NONE');
    });

    it('should keep effectiveCurrentPlan when credit tier unchanged', () => {
      const currentPlanCode = 'PRO';
      const effectiveCurrentPlan = isCreditTierChanged('PRO', 4, 4) ? 'NONE' : currentPlanCode;
      expect(effectiveCurrentPlan).toBe('PRO');
    });
  });

  // =========================================================================
  // Slider cap (1M default) + hidden 5M/10M unlock
  // =========================================================================

  describe('slider tier cap', () => {
    it('DEFAULT_MAX_TIER_INDEX points at the 1,000,000 tier (index 7)', () => {
      expect(DEFAULT_MAX_TIER_INDEX).toBe(7);
      expect(CREDIT_TIERS[DEFAULT_MAX_TIER_INDEX]).toBe(1_000_000);
    });

    it('hides the two top tiers (5M, 10M) by default', () => {
      // The default cap excludes indices 8 (5M) and 9 (10M).
      expect(CREDIT_TIERS[DEFAULT_MAX_TIER_INDEX + 1]).toBe(5_000_000);
      expect(CREDIT_TIERS[CREDIT_TIERS.length - 1]).toBe(10_000_000);
      expect(DEFAULT_MAX_TIER_INDEX).toBeLessThan(CREDIT_TIERS.length - 1);
    });

    describe('resolveMaxTierIndex', () => {
      const LAST = CREDIT_TIERS.length - 1; // 9

      it('caps at 1M (index 7) when locked and subscription is below the cap', () => {
        expect(resolveMaxTierIndex(false, 0)).toBe(DEFAULT_MAX_TIER_INDEX);
        expect(resolveMaxTierIndex(false, 7)).toBe(DEFAULT_MAX_TIER_INDEX);
      });

      it('reveals the full range when explicitly unlocked', () => {
        expect(resolveMaxTierIndex(true, 0)).toBe(LAST);
      });

      it('auto-unlocks for an existing customer already on a hidden tier', () => {
        // Without this, a 5M/10M customer would be clamped down to 1M and could downgrade by mistake.
        expect(resolveMaxTierIndex(false, 8)).toBe(LAST);
        expect(resolveMaxTierIndex(false, 9)).toBe(LAST);
      });

      it('defaults subscriptionTierIndex to 0 (locked)', () => {
        expect(resolveMaxTierIndex(false)).toBe(DEFAULT_MAX_TIER_INDEX);
      });
    });

    describe('clampTierIndex', () => {
      it('leaves an in-range index untouched', () => {
        expect(clampTierIndex(3, 7)).toBe(3);
        expect(clampTierIndex(7, 7)).toBe(7);
        expect(clampTierIndex(0, 7)).toBe(0);
      });

      it('clamps a hidden-tier selection down when the cap shrinks (re-hide desync fix)', () => {
        // User unlocked, parked on 10M (index 9), then re-hid the tiers (cap back to 1M/index 7).
        // The selected index must follow the cap so price/checkout never reflect a hidden tier.
        const cap = resolveMaxTierIndex(false, 0); // 7
        expect(clampTierIndex(9, cap)).toBe(DEFAULT_MAX_TIER_INDEX);
        expect(clampTierIndex(8, cap)).toBe(DEFAULT_MAX_TIER_INDEX);
      });

      it('does not clamp a hidden tier while it is still unlocked', () => {
        const cap = resolveMaxTierIndex(true, 0); // 9
        expect(clampTierIndex(9, cap)).toBe(9);
      });

      it('floors negative indices at 0', () => {
        expect(clampTierIndex(-1, 7)).toBe(0);
      });
    });
  });

  // =========================================================================
  // Plan card coherence - each tier is a superset of the one below it
  // =========================================================================

  describe('plan card feature coherence', () => {
    const ORDER = ['free', 'starter', 'pro', 'team', 'enterprise'];
    const capsOf = (planId: string) =>
      (PLAN_FEATURE_KEYS[planId] || []).filter((k) => CAPABILITY_KEYS.includes(k));

    it('defines an ordered feature list for every plan', () => {
      for (const planId of ORDER) {
        expect(Array.isArray(PLAN_FEATURE_KEYS[planId])).toBe(true);
        expect(PLAN_FEATURE_KEYS[planId].length).toBeGreaterThan(0);
      }
    });

    it('each tier includes every capability of the tier below it (no regressions up the ladder)', () => {
      for (let i = 1; i < ORDER.length; i++) {
        const lower = capsOf(ORDER[i - 1]);
        const higher = new Set(capsOf(ORDER[i]));
        const missing = lower.filter((k) => !higher.has(k));
        expect(missing, `${ORDER[i]} is missing capabilities present in ${ORDER[i - 1]}: ${missing.join(', ')}`).toEqual([]);
      }
    });

    it('Enterprise explicitly surfaces the Team-tier capabilities (the original bug)', () => {
      // Regression: Enterprise used to omit SSO/RBAC/audit/etc. that Team listed,
      // making it look less capable than Team.
      const enterprise = new Set(PLAN_FEATURE_KEYS.enterprise);
      for (const key of ['sso', 'rbac', 'auditLogs', 'sharedTemplates', 'centralizedBilling', 'apiAccess', 'priorityExecution', 'executionSearch']) {
        expect(enterprise.has(key), `Enterprise must list "${key}"`).toBe(true);
      }
    });

    it('no plan repeats a feature key', () => {
      for (const planId of ORDER) {
        const keys = PLAN_FEATURE_KEYS[planId];
        expect(new Set(keys).size).toBe(keys.length);
      }
    });

    it('lists managed integration credentials (cePlatformCreds) on every paid tier but not Free', () => {
      // The CE catalog credential relay requires an active paid subscription on the
      // linked cloud account, so the marketing line belongs to paid tiers only.
      for (const planId of ['starter', 'pro', 'team', 'enterprise']) {
        expect(PLAN_FEATURE_KEYS[planId], `${planId} must list "cePlatformCreds"`).toContain('cePlatformCreds');
      }
      expect(PLAN_FEATURE_KEYS.free).not.toContain('cePlatformCreds');
    });

    it('registers cePlatformCreds as an additive capability so the superset guard covers it', () => {
      expect(CAPABILITY_KEYS).toContain('cePlatformCreds');
    });
  });

  // =========================================================================
  // STARTER Plan Credit Tier Limit
  // =========================================================================

  describe('STARTER plan credit limit', () => {
    it('STARTER visible tiers are 0..4', () => {
      for (let i = 0; i <= 4; i++) {
        expect(CREDIT_TIERS[i]).toBeLessThanOrEqual(STARTER_MAX_CREDITS);
      }
    });

    it('tiers above 4 exceed STARTER limit', () => {
      for (let i = 5; i < CREDIT_TIERS.length; i++) {
        expect(CREDIT_TIERS[i]).toBeGreaterThan(STARTER_MAX_CREDITS);
      }
    });

    it('Starter plan should be hidden when credit slider > 100K', () => {
      // This mirrors: ...(creditAmount <= STARTER_MAX_CREDITS ? [starterPlan] : [])
      const testCases = [
        { tierIndex: 0, shouldShow: true },   // 5K
        { tierIndex: 4, shouldShow: true },   // 100K
        { tierIndex: 5, shouldShow: false },  // 250K
        { tierIndex: 9, shouldShow: false },  // 10M
      ];
      for (const { tierIndex, shouldShow } of testCases) {
        expect(CREDIT_TIERS[tierIndex] <= STARTER_MAX_CREDITS).toBe(shouldShow);
      }
    });
  });
});
