import { describe, it, expect, vi, afterEach } from 'vitest';
import { creditsToUsd } from '../format-cost';
import { CREDIT_LIST_USD } from '../billing/pricing-constants';

// Pin the app locale to 'en' so the locale-GROUPED Cloud output is deterministic - otherwise
// toLocaleString's thousands separator is the test runner's system locale (en ',' vs fr ' ').
vi.mock('../utils/locale', () => ({ getClientLocale: () => 'en' }));

/**
 * Regression for the CE usage-history "$" bug: ledger amounts are stored in CREDITS
 * (local CE ledger AND the cloud-linked relay mirror), but the CE quota page rendered
 * them with a bare "$" without converting - so 1080 credits ($1.08) displayed as
 * "$1080.00", a 1000× overstatement. {@link creditsToUsd} is the canonical conversion
 * the formatter now applies (1 credit = $0.001). These assertions FAIL against the
 * pre-fix behavior (which was identity: credits shown as dollars).
 */
describe('format-cost', () => {
  describe('CREDIT_LIST_USD', () => {
    it('pins the canonical list scale: 1 credit = $0.001 (backend ModelPricingService / V80)', () => {
      expect(CREDIT_LIST_USD).toBe(0.001);
    });
  });

  describe('creditsToUsd', () => {
    it('converts 1000 credits to exactly $1', () => {
      expect(creditsToUsd(1000)).toBeCloseTo(1, 10);
    });

    it('converts the bug example: 1080 credits (a relay LLM call) → $1.08, not $1080', () => {
      expect(creditsToUsd(1080)).toBeCloseTo(1.08, 10);
    });

    it('keeps sub-dollar precision for a single cheap call: 18 credits → $0.018', () => {
      expect(creditsToUsd(18)).toBeCloseTo(0.018, 10);
    });

    it('preserves sign for debits (negative ledger amounts)', () => {
      expect(creditsToUsd(-1080)).toBeCloseTo(-1.08, 10);
    });

    it('maps zero to zero', () => {
      expect(creditsToUsd(0)).toBe(0);
    });

    it('scales a 30-day total: 50000 credits → $50', () => {
      expect(creditsToUsd(50000)).toBeCloseTo(50, 10);
    });
  });
});

/**
 * formatCost / formatCostOrDash edition branching. The value passed is a CREDIT amount
 * everywhere (agent metrics, executions, fleet - see callers). CE must render the dollar
 * equivalent (1 credit = $0.001); Cloud renders credits locale-grouped at the caller's
 * precision (i18n rule - thousands separator per the app locale, not a bare toFixed).
 * Pre-fix, CE prepended "$" to the raw credit value (50 credits -> "$50.00" instead of
 * "$0.05") - these CE assertions FAIL against that behavior.
 *
 * IS_CE is a module-level const resolved from env at import, so each case re-imports the
 * module under a mocked edition. getClientLocale is mocked to 'en' (top of file) so the
 * grouped output is deterministic; the dot() helper keeps CE decimal-separator-tolerant.
 */
describe('formatCost / formatCostOrDash - edition branching', () => {
  afterEach(() => {
    vi.resetModules();
    vi.doUnmock('@/lib/edition');
  });

  async function load(isCe: boolean) {
    vi.resetModules();
    vi.doMock('@/lib/edition', () => ({ IS_CE: isCe, IS_CLOUD: !isCe, EDITION: isCe ? 'ce' : 'cloud' }));
    return import('../format-cost');
  }

  const dot = (s: string) => s.replace(/,/g, '.'); // locale-tolerant decimal separator

  it('CE: converts credits to $, ignoring the credit-tuned decimals arg (1600cr, d=4 -> $1.60)', async () => {
    const { formatCost } = await load(true);
    expect(dot(formatCost(1600, 4))).toBe('$1.60');
  });

  it('CE regression: a bare credit amount is NOT shown as-is with $ (50cr -> $0.05, not $50.00)', async () => {
    const { formatCost } = await load(true);
    expect(dot(formatCost(50, 4))).toBe('$0.05');
  });

  it('CE: keeps sub-cent precision up to 4 decimals (5cr -> $0.005, 1cr -> $0.001)', async () => {
    const { formatCost } = await load(true);
    expect(dot(formatCost(5))).toBe('$0.005');
    expect(dot(formatCost(1))).toBe('$0.001');
  });

  it('Cloud: shows credits at the caller precision, locale-grouped, no $ (1600, d=4 -> "1,600.0000")', async () => {
    const { formatCost } = await load(false);
    expect(formatCost(1600, 4)).toBe('1,600.0000');
  });

  it('Cloud: large totals get thousands grouping (i18n) - 72984.2097, d=4 -> "72,984.2097", not "72984.2097"', async () => {
    const { formatCost } = await load(false);
    expect(formatCost(72984.2097, 4)).toBe('72,984.2097');
  });

  it('null/undefined -> em-dash in both editions', async () => {
    const ce = await load(true);
    expect(ce.formatCost(null)).toBe('-');
    const cloud = await load(false);
    expect(cloud.formatCost(undefined)).toBe('-');
  });

  it('formatCostOrDash: zero -> em-dash; CE converts a non-zero credit amount', async () => {
    const { formatCostOrDash } = await load(true);
    expect(formatCostOrDash(0)).toBe('-');
    expect(dot(formatCostOrDash(2000))).toBe('$2.00');
  });

  it('formatCostOrDash Cloud: non-zero shows credits locale-grouped, no $', async () => {
    const { formatCostOrDash } = await load(false);
    expect(formatCostOrDash(2000, 1)).toBe('2,000.0');
  });

  it('CE: decimals=0 (budget callers) is ignored, shows cents (20000cr, d=0 -> $20.00)', async () => {
    const { formatCost } = await load(true);
    expect(dot(formatCost(20000, 0))).toBe('$20.00');
  });

  it('CE: decimals=0 no longer coarsely rounds a sub-dollar amount (500cr, d=0 -> $0.50, not $1)', async () => {
    const { formatCost } = await load(true);
    expect(dot(formatCost(500, 0))).toBe('$0.50');
  });

  it('CE: a debit (negative) puts the minus BEFORE the $ - "-$0.0086", never "$-0.0086"', async () => {
    const { formatCost } = await load(true);
    expect(dot(formatCost(-8.6))).toBe('-$0.0086');
    expect(dot(formatCost(-1080))).toBe('-$1.08');
  });

  it('CE: formatCostOrDash signs a negative before the $ too ("-$2.00")', async () => {
    const { formatCostOrDash } = await load(true);
    expect(dot(formatCostOrDash(-2000))).toBe('-$2.00');
  });

  it('Cloud: a negative keeps signed credits, locale-grouped, no $ (-1080, d=4 -> "-1,080.0000")', async () => {
    const { formatCost } = await load(false);
    expect(formatCost(-1080, 4)).toBe('-1,080.0000');
  });

  // formatCostCompact - width-safe cell formatter (K/M/B for large magnitudes, full
  // precision for small). The regression it guards: a big aggregate rendered through
  // formatCost(v, 4) (e.g. Cloud "4,959,138.5490") overflows the narrow metrics column.
  it('CE compact: abbreviates large $ magnitudes (M/B), keeps small ones precise', async () => {
    const { formatCostCompact } = await load(true);
    expect(dot(formatCostCompact(4_959_138_549))).toBe('$5.0M');   // usd 4,959,138.549
    expect(dot(formatCostCompact(2_500_000_000_000))).toBe('$2.5B'); // usd 2.5B
    expect(dot(formatCostCompact(3_421_000))).toBe('$3.4K');        // usd 3421
    expect(dot(formatCostCompact(1600))).toBe('$1.60');            // usd 1.6 (<1000, no abbrev)
    expect(dot(formatCostCompact(2.5))).toBe('$0.0025');           // sub-cent precision kept
    expect(dot(formatCostCompact(0))).toBe('$0.00');
  });

  it('CE compact: signs a negative before the $, in both the abbreviated and small ranges', async () => {
    const { formatCostCompact } = await load(true);
    expect(dot(formatCostCompact(-1080))).toBe('-$1.08');          // usd -1.08
    expect(dot(formatCostCompact(-5_000_000_000))).toBe('-$5.0M'); // usd -5,000,000
  });

  it('Cloud compact: abbreviates large credit magnitudes (K/M/B), no $', async () => {
    const { formatCostCompact } = await load(false);
    expect(dot(formatCostCompact(4_959_138.549))).toBe('5.0M');    // the overflow case, now compact
    expect(dot(formatCostCompact(2_500_000_000))).toBe('2.5B');
    expect(dot(formatCostCompact(3421))).toBe('3.4K');
    expect(dot(formatCostCompact(2.5))).toBe('2.50');             // <1000, 2 decimals
    expect(dot(formatCostCompact(0.0025))).toBe('0.0025');        // sub-unit, up to 4 decimals
    expect(dot(formatCostCompact(-1500))).toBe('-1.5K');
  });

  it('compact: null/undefined -> dash in both editions', async () => {
    const ce = await load(true);
    expect(ce.formatCostCompact(null)).toBe('-');
    const cloud = await load(false);
    expect(cloud.formatCostCompact(undefined)).toBe('-');
  });
});
