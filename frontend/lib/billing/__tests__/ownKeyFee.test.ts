import { describe, it, expect } from 'vitest';
import {
  ownKeyChargeFor,
  ownKeyFeeFor,
  type ModelCostBasis,
  type ModelRates,
} from '../model-cost-estimate';

const withOwnKey = (providers: string[]): ModelCostBasis => ({
  enabled: true,
  profiles: {},
  ownKey: { providers, feeByTier: { budget: 1, mid: 2, high: 5, top: 10, unknown: 2 } },
});

describe('ownKeyFeeFor', () => {
  it('quotes the tier fee for a provider whose own key will serve', () => {
    const basis = withOwnKey(['anthropic', 'openai']);

    expect(ownKeyFeeFor({ provider: 'anthropic', tier: 'top' }, basis)).toBe(10);
    expect(ownKeyFeeFor({ provider: 'openai', tier: 'high' }, basis)).toBe(5);
    expect(ownKeyFeeFor({ provider: 'openai', tier: 'budget' }, basis)).toBe(1);
  });

  it('matches the provider case-insensitively, the way the catalogue and the credential disagree', () => {
    expect(ownKeyFeeFor({ provider: 'Anthropic', tier: 'top' }, withOwnKey(['anthropic']))).toBe(10);
  });

  it('says nothing for a provider the caller has no key for: that model runs on the platform key', () => {
    const basis = withOwnKey(['anthropic']);

    expect(ownKeyFeeFor({ provider: 'google', tier: 'top' }, basis)).toBeNull();
    expect(ownKeyFeeFor({ provider: null, tier: 'top' }, basis)).toBeNull();
  });

  it('falls back to the unknown-tier fee for a model the catalogue does not price', () => {
    const basis = withOwnKey(['deepseek']);

    expect(ownKeyFeeFor({ provider: 'deepseek', tier: null }, basis)).toBe(2);
    expect(ownKeyFeeFor({ provider: 'deepseek', tier: 'nonsense' }, basis)).toBe(2);
  });

  it('says nothing when the basis carries no own-key block: CE, no saved key, or a plan that does not allow it', () => {
    const plain: ModelCostBasis = { enabled: true, profiles: {} };

    expect(ownKeyFeeFor({ provider: 'anthropic', tier: 'top' }, plain)).toBeNull();
    expect(ownKeyFeeFor({ provider: 'anthropic', tier: 'top' }, null)).toBeNull();
  });
});

/**
 * The charge is not the fee: the ledger debits `min(fee, consumption)`, so a turn cheaper
 * than its own tier fee is billed the cheaper figure. A quoted fee that ignored the cap
 * would overcharge on a quarter of real budget and mid turns, and on essentially every
 * classify or guardrail call, which is the direction that loses the user money.
 */
describe('ownKeyChargeFor', () => {
  const rates: ModelRates = { input: 2, output: 10 };
  // One plain-token profile, so the arithmetic in these tests is readable: a unit costs
  // 2 x inputCoefficient + 10 x outputCoefficient credits.
  const basisWith = (inputCoefficient: number, outputCoefficient: number): ModelCostBasis => ({
    ...withOwnKey(['anthropic']),
    profiles: { classifyStep: { inputCoefficient, outputCoefficient } },
  });

  it('charges the flat fee when the turn would have cost more on the platform route', () => {
    // 2 x 10 + 10 x 10 = 120 credits of tokens, well above the 10-credit top-tier fee.
    const charge = ownKeyChargeFor({ provider: 'anthropic', tier: 'top' }, rates, basisWith(10, 10), 'classifyStep');

    expect(charge).toEqual({ credits: 10, estimated: false });
  });

  it('charges the platform price when it is below the fee, and marks that figure an estimate', () => {
    // 2 x 0.5 + 10 x 0.1 = 2 credits of tokens, a fifth of the top-tier fee.
    const charge = ownKeyChargeFor({ provider: 'anthropic', tier: 'top' }, rates, basisWith(0.5, 0.1), 'classifyStep');

    expect(charge).toEqual({ credits: 2, estimated: true });
  });

  it('charges the fee when the platform price cannot be computed at all', () => {
    // An unpriced model still runs, and the fee is what it will be billed. Falling back
    // to silence here would drop the only number this route has.
    const unpriced = ownKeyChargeFor({ provider: 'anthropic', tier: 'high' }, undefined, basisWith(10, 10), 'classifyStep');
    const noProfile = ownKeyChargeFor({ provider: 'anthropic', tier: 'high' }, rates, withOwnKey(['anthropic']), 'classifyStep');

    expect(unpriced).toEqual({ credits: 5, estimated: false });
    expect(noProfile).toEqual({ credits: 5, estimated: false });
  });

  it('keeps a zero fee, which is a real answer and not a missing one', () => {
    const free: ModelCostBasis = {
      enabled: true,
      profiles: { classifyStep: { inputCoefficient: 10, outputCoefficient: 10 } },
      ownKey: { providers: ['anthropic'], feeByTier: { budget: 0, mid: 2, high: 5, top: 10, unknown: 2 } },
    };

    expect(ownKeyChargeFor({ provider: 'anthropic', tier: 'budget' }, rates, free, 'classifyStep'))
      .toEqual({ credits: 0, estimated: false });
  });

  it('says nothing for a model that does not run on the caller key, whatever it would cost', () => {
    expect(ownKeyChargeFor({ provider: 'openai', tier: 'top' }, rates, basisWith(10, 10), 'classifyStep')).toBeNull();
    expect(ownKeyChargeFor({ provider: 'anthropic', tier: 'top' }, rates, { enabled: true, profiles: {} }, 'classifyStep')).toBeNull();
  });
});
