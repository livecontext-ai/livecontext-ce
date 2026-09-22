import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

import {
  CHAT_EXCHANGE_CREDITS,
  CREDIT_EXAMPLES,
  CREDIT_TIERS,
  CREDIT_WORKLOADS,
  MEASURED_PROVIDER_COST,
  pricedMedian,
  PRICING_BASIS_MODEL_ID,
  PRICING_BASIS_PROVIDER,
  PRICING_BASIS_RATES,
} from '@/lib/billing/pricing-constants';

/**
 * The pricing page's three published figures, RECOMPUTED from the margin the platform
 * actually charges.
 *
 * <p><b>The defect this closes, which nothing else can see.</b> The model pickers quote
 * a live figure: the server folds the margin into the coefficients it serves at
 * `/api/credits/estimate-basis`, so moving the lever moves the quote with no code
 * change. The pricing page does not. Its three figures are constants in this repo,
 * re-priced by hand, and they have been re-priced three times now (1.8 to 1.11, 1.11 to
 * 2.0, 2.0 to 1.333333). Nothing connected the two.
 *
 * <p>So the failure is silent and it is a PRICE: move the margin, deploy, and the page
 * keeps advertising the old figure, in six locales, with the whole suite green, while
 * the ledger debits the new one. A reader comparing the page to their own invoice is
 * the detector, which is the worst possible one.
 *
 * <p><b>It recomputes rather than reminds.</b> An earlier version of this file compared
 * the declared lever against a constant recording which lever the figures belonged to.
 * That fails on the margin move, which is most of the value, but it is satisfiable by
 * bumping the constant and re-pricing nothing - and a reminder anyone can dismiss in one
 * keystroke is the shape of guard that rots. This one multiplies the measured provider
 * cost by the declared lever and checks the published figure against the result, so the
 * only way to make it green is to actually re-price.
 *
 * <p>Reading a backend file from a frontend test is the established shape here, not an
 * exception: `__tests__/ci-vitest-paths.test.ts` reads `.github/workflows/ci.yml` and
 * `components/badges/__tests__/badgeCatalogParity.test.ts` reads a locale bundle off
 * disk, both for the same reason - the fact being guarded lives in another file and an
 * import would not reach it.
 */

const APPLICATION_YML = path.resolve(
  __dirname, '..', '..', '..', '..',
  'backend', 'auth-service', 'src', 'main', 'resources', 'application.yml',
);

/**
 * The shipped model catalogue, which is what the pricing page's basis rates are a copy
 * of. Read off disk for the same reason the multiplier is: the fact being guarded lives
 * in a backend resource and an import would not reach it.
 */
const MODEL_CATALOG = path.resolve(
  __dirname, '..', '..', '..', '..',
  'backend', 'agent-service', 'src', 'main', 'resources', 'model-catalog', 'models.json',
);

/** The profiles CREDIT_WORKLOADS copies its token counts from. */
const LLM_COST_PROFILE = path.resolve(
  __dirname, '..', '..', '..', '..',
  'backend', 'auth-service', 'src', 'main', 'java', 'com', 'apimarketplace', 'auth',
  'service', 'LlmCostProfile.java',
);

/**
 * The declared default of `billing.llm.cloud-multiplier`.
 *
 * <p>Matched on the property line rather than parsed as YAML: the value is a Spring
 * placeholder (`${BILLING_LLM_CLOUD_MULTIPLIER:1.333333}`), so a YAML parser would hand
 * back the placeholder string and every reader would have to unwrap it anyway.
 */
function declaredMultiplier(): number {
  expect(
    fs.existsSync(APPLICATION_YML),
    'auth-service application.yml has moved; this guard is reading nothing',
  ).toBe(true);
  const source = fs.readFileSync(APPLICATION_YML, 'utf8');
  const match = /cloud-multiplier:\s*\$\{BILLING_LLM_CLOUD_MULTIPLIER:([0-9.]+)\}/.exec(source);
  expect(match, 'cloud-multiplier is no longer declared in the shape this guard reads').toBeTruthy();
  return Number(match![1]);
}

/**
 * The largest count the page may claim a pack covers: a multiple of five, so it reads as
 * an estimate, or a plain integer below ten where five would understate by nearly half.
 * The same rule `credit-conversation-copy.test.ts` asserts the SHAPE of; here it is
 * computed, because the pack is arithmetic where the unit price is a judgement.
 */
function largestLegiblePack(cap: number): number {
  const byFives = Math.floor(cap / 5) * 5;
  return byFives >= 10 ? byFives : Math.floor(cap);
}

/** How far above the true median a rounded-up figure may legibly sit. */
const LEGIBILITY_HEADROOM = 1.25;

describe('the pricing page is priced at the margin the platform charges', () => {
  const lever = declaredMultiplier();

  it.each(CREDIT_EXAMPLES)('$id is priced at the declared margin', (example) => {
    expect(MEASURED_PROVIDER_COST[example.id], `${example.id} has no measured provider cost`)
      .toBeGreaterThan(0);
    // Through pricedMedian, so the FLAT node fee an in-workflow unit also pays is in the
    // figure being checked. Checking the provider cost alone is what let the classify
    // example publish its LLM leg as if it were its price.
    const median = pricedMedian(example.id, lever);

    // Never BELOW the median: `creditsEach` is rounded UP so the page can never quote a
    // unit price lower than the one the ledger will debit.
    expect(
      example.creditsEach,
      `${example.id}: the page says ${example.creditsEach} credits, but at the declared `
        + `margin of ${lever} one costs ${median.toFixed(2)}. Re-price CREDIT_EXAMPLES: round `
        + 'this UP to a legible number, then set perEntryPack to the largest legible count the '
        + '5,000-credit pack covers. See the derivation note in pricing-constants.ts.',
    ).toBeGreaterThanOrEqual(median);

    // ...and not far above it either, or "rounded up to something legible" becomes a
    // licence to leave a stale figure in place after a cut.
    expect(
      example.creditsEach,
      `${example.id}: ${example.creditsEach} credits is more than ${LEGIBILITY_HEADROOM}x the `
        + `${median.toFixed(2)} it costs at the declared margin of ${lever}, which is a stale `
        + 'figure rather than a legible rounding.',
    ).toBeLessThanOrEqual(median * LEGIBILITY_HEADROOM);
  });

  it.each(CREDIT_EXAMPLES)('$id claims only what the entry pack pays for', (example) => {
    // The other half of a re-price, and the half that is pure arithmetic: once the unit
    // price is chosen, how many of them a pack buys is not a judgement.
    expect(
      example.perEntryPack,
      `${example.id}: at ${example.creditsEach} credits each, a ${CREDIT_TIERS[0]}-credit pack `
        + `covers ${largestLegiblePack(CREDIT_TIERS[0] / example.creditsEach)}, not `
        + `${example.perEntryPack}.`,
    ).toBe(largestLegiblePack(CREDIT_TIERS[0] / example.creditsEach));
  });

  it('prices every published example, so a new one cannot arrive unguarded', () => {
    // A fourth worked example added with no measured cost beside it would be quietly
    // skipped by the two loops above if they iterated the measurements instead.
    for (const example of CREDIT_EXAMPLES) {
      expect(
        MEASURED_PROVIDER_COST[example.id],
        `${example.id} is published on the pricing page with no measured provider cost, so `
          + 'nothing can tell whether it still matches the margin',
      ).toBeGreaterThan(0);
    }
  });

  it('prices the chat exchange every credit tooltip quotes', () => {
    // The figure the four pricing "i" carry is NOT one of the worked examples above: it
    // names its own unit (a short exchange with a configured agent) and has no per-pack
    // count, so it is published as its own constant and would otherwise be the one
    // number on the page that no margin move can reach.
    const median = pricedMedian('chatExchange', lever);
    expect(median, 'the chat exchange has no measured provider cost to price from')
      .toBeGreaterThan(0);

    expect(
      CHAT_EXCHANGE_CREDITS,
      `the tooltips say ${CHAT_EXCHANGE_CREDITS} credits, but at the declared margin of `
        + `${lever} a short exchange costs ${median.toFixed(2)}. Round the median UP to a `
        + 'legible number and set CHAT_EXCHANGE_CREDITS to it.',
    ).toBeGreaterThanOrEqual(median);

    expect(
      CHAT_EXCHANGE_CREDITS,
      `${CHAT_EXCHANGE_CREDITS} credits is more than ${LEGIBILITY_HEADROOM}x the `
        + `${median.toFixed(2)} a short exchange costs at the declared margin of ${lever}, `
        + 'which is a stale figure rather than a legible rounding.',
    ).toBeLessThanOrEqual(median * LEGIBILITY_HEADROOM);
  });

  it('prices the page on the catalogue row it names, not on four remembered numbers', () => {
    // The other half of a basis change, and the half the margin checks above cannot see.
    // PRICING_BASIS_RATES is a COPY of a catalogue row that this module cannot import,
    // and the copy is the failure mode: the first draft of it was read off a production
    // dump instead of the shipped catalogue, which put every published figure 21% below
    // what the ledger charges, attributed BY NAME to a model whose picker quotes the
    // higher number from the same catalogue. Nothing in the suite could see it.
    //
    // Recomputing MEASURED_PROVIDER_COST from PRICING_BASIS_RATES cannot see it either -
    // that is the same expression over the same inputs and is green for any values. The
    // only guard that works reads the row itself, the way declaredMultiplier() already
    // reads the margin out of application.yml.
    expect(
      fs.existsSync(MODEL_CATALOG),
      'the model catalogue has moved; this guard is reading nothing',
    ).toBe(true);
    const catalogue = JSON.parse(fs.readFileSync(MODEL_CATALOG, 'utf8')) as {
      models: Array<Record<string, unknown>>;
    };
    const row = catalogue.models.find(
      (m) => m.modelId === PRICING_BASIS_MODEL_ID && m.provider === PRICING_BASIS_PROVIDER,
    );
    expect(
      row,
      `the pricing page is priced on ${PRICING_BASIS_PROVIDER}/${PRICING_BASIS_MODEL_ID}, `
        + 'which the shipped catalogue no longer carries. Point PRICING_BASIS_* at a row '
        + 'that exists and re-price.',
    ).toBeDefined();

    const drift = (declared: number, published: unknown, label: string) =>
      expect(
        declared,
        `PRICING_BASIS_RATES.${label} says ${declared}, the catalogue row says ${published}. `
          + 'Copy the row, then re-price CREDIT_EXAMPLES and CHAT_EXCHANGE_CREDITS.',
      ).toBeCloseTo(Number(published), 6);

    drift(PRICING_BASIS_RATES.input, row!.priceInput, 'input');
    drift(PRICING_BASIS_RATES.output, row!.priceOutput, 'output');
    drift(PRICING_BASIS_RATES.cacheRead, row!.priceCacheRead, 'cacheRead');

    // cacheWrite is NOT copied from the row: outside the Anthropic family a first send is
    // plain prompt input, so the row's own write price (zero here) is never charged and
    // the input rate is what a cache-write token really costs. Pinned because reading the
    // row's zero instead would make 39,600 tokens of every chat exchange free.
    expect(
      PRICING_BASIS_RATES.cacheWrite,
      'outside Anthropic a cache write bills at the input rate, so the two must agree',
    ).toBe(PRICING_BASIS_RATES.input);
  });

  it.each([
    ['chatExchange', 'CHAT_CONVERSATION'],
    ['classifyStep', 'CLASSIFY_STEP'],
  ])('%s still carries the token counts auth-service measures', (id, profile) => {
    // The SECOND cross-file copy in this module, and the one the catalogue guard above
    // cannot see. CREDIT_WORKLOADS restates two LlmCostProfile constants token for token
    // (its docblock says "keep it in step"), and those are re-measured: the chat profile
    // moved on 2026-09-16. Drift there is defect-by-the-other-route - the picker quotes
    // the new workload and the pricing "i" the old one, both naming the same unit in the
    // same words, which is precisely the comparison the change was made to enable.
    expect(
      fs.existsSync(LLM_COST_PROFILE),
      'LlmCostProfile.java has moved; this guard is reading nothing',
    ).toBe(true);
    const source = fs.readFileSync(LLM_COST_PROFILE, 'utf8');
    // The enum constant, e.g. CHAT_CONVERSATION("chatConversation", 10, 39_600, 138_200, 3_400)
    const declared = new RegExp(
      profile + String.raw`\("\w+",\s*([\d_]+),\s*([\d_]+),\s*([\d_]+),\s*([\d_]+)\)`,
    ).exec(source);
    expect(declared, profile + ' is no longer declared in the shape this guard reads')
      .toBeTruthy();
    const [input, cacheWrite, cacheRead, output] =
      declared!.slice(1).map((n) => Number(n.replace(/_/g, '')));

    expect(
      CREDIT_WORKLOADS[id],
      'CREDIT_WORKLOADS.' + id + ' has drifted from LlmCostProfile.' + profile
        + '. Copy the counts across and re-price the published figures.',
    ).toEqual({ input, cacheWrite, cacheRead, output });
  });
});
