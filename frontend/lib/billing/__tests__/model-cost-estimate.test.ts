import { describe, it, expect } from 'vitest';
import {
  estimateModelCredits,
  formatCreditEstimate,
  roundCreditEstimate,
  type ModelCostBasis,
} from '@/lib/billing/model-cost-estimate';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

/**
 * The picker tells a user what a model will cost BEFORE they pick it, and it
 * does that arithmetic here rather than asking the server per model. That is
 * only honest while this file lands on the same number the ledger will debit,
 * so the expectations below are the figures auth-service computes through the
 * real billing code (`LlmCostProfileFormulaTest` pins the other side).
 *
 * The quiet failure this guards is a number appearing where none should: an
 * unpriced model, a catalogue sentinel rate, or a self-hosted install with no
 * margin and no credits. Each of those must render nothing, not a zero.
 */

/**
 * What `GET /api/credits/estimate-basis` serves: one coefficient per rate, with the
 * profile's token workload and the billing multiplier folded together, plus how to
 * resolve a cache rate the catalogue does not publish.
 *
 * The figures are the real ones at the 1.11 lever: a conversation is 10 plain input
 * tokens, 39,600 cache writes, 138,200 cache reads and 3,400 output. Most of its
 * cost is in the two cache classes, which is why they have to be priced at the
 * model's own rates rather than at its input rate.
 */
const BASIS: ModelCostBasis = {
  enabled: true,
  profiles: {
    chatConversation: {
      inputCoefficient: 0.0111,
      cacheWriteCoefficient: 43.956,
      cacheReadCoefficient: 153.402,
      outputCoefficient: 3.774,
    },
    guardrailCheck: {
      inputCoefficient: 17.76,
      cacheWriteCoefficient: 0,
      cacheReadCoefficient: 0,
      outputCoefficient: 0.111,
    },
    classifyStep: {
      inputCoefficient: 1.332,
      cacheWriteCoefficient: 0,
      cacheReadCoefficient: 0,
      outputCoefficient: 0.0666,
    },
  },
  cacheFallback: {
    anthropic: { cacheWriteWeight: 1.25, cacheReadWeight: 0.1, modelCacheWritePriceApplies: true },
    'claude-code': { cacheWriteWeight: 1.25, cacheReadWeight: 0.1, modelCacheWritePriceApplies: true },
    openai: { cacheWriteWeight: 1, cacheReadWeight: 0.5, modelCacheWritePriceApplies: false },
    gemini: { cacheWriteWeight: 1, cacheReadWeight: 0.25, modelCacheWritePriceApplies: false },
    '*': { cacheWriteWeight: 1, cacheReadWeight: 1, modelCacheWritePriceApplies: false },
  },
};

/**
 * Claude Sonnet 5. A convenient, fully-priced fixture for this module's arithmetic, and
 * nothing more: the pricing page moved off it on 2026-09-21 and publishes on a
 * lightweight catalogue row (see PRICING_BASIS_RATES), so a figure here is not a figure
 * that page quotes.
 */
const SONNET_5 = {
  input: 2,
  output: 10,
  cacheWrite: 2.5,
  cacheRead: 0.2,
  provider: 'anthropic',
  supportsPromptCaching: true,
};

describe('estimateModelCredits', () => {
  it.each([
    ['chatConversation', 178.33],
    ['guardrailCheck', 36.63],
    ['classifyStep', 3.33],
  ] as const)('prices %s exactly as the billing service does', (profile, expected) => {
    expect(estimateModelCredits(SONNET_5, BASIS, profile)).toBeCloseTo(expected, 2);
  });

  it('scales with the model, so a picker actually compares two models', () => {
    const opus = { ...SONNET_5, input: 5, output: 25, cacheWrite: 6.25, cacheRead: 0.5 };
    const agentOnOpus = estimateModelCredits(opus, BASIS, 'chatConversation')!;
    const agentOnSonnet = estimateModelCredits(SONNET_5, BASIS, 'chatConversation')!;

    expect(agentOnOpus / agentOnSonnet).toBeCloseTo(2.5, 2);
  });

  it('prices the cache classes at the model rates, not at its input rate', () => {
    // The defect this whole four-term formula exists for. Pricing every cached token
    // at the input rate reads as conservative and is not: on Anthropic a cache WRITE
    // costs 1.25x input and a read a fortieth of it, and a conversation is mostly
    // reads, so the naive figure lands nowhere near the bill.
    const naive = SONNET_5.input * BASIS.profiles.chatConversation.inputCoefficient
      + SONNET_5.input * BASIS.profiles.chatConversation.cacheWriteCoefficient!
      + SONNET_5.input * BASIS.profiles.chatConversation.cacheReadCoefficient!
      + SONNET_5.output * BASIS.profiles.chatConversation.outputCoefficient;

    expect(estimateModelCredits(SONNET_5, BASIS, 'chatConversation')).toBeCloseTo(178.33, 2);
    expect(naive).toBeGreaterThan(390);
  });

  it('falls back to the provider weight, never the input rate, for a model with no cache price', () => {
    // 32% of the catalogue publishes no cache price. Before the server published its
    // family weights a browser had one option here - the input rate - which overstated
    // the largest term of every such model tenfold.
    const noPublishedPrices = {
      input: 2,
      output: 10,
      provider: 'anthropic',
      supportsPromptCaching: true,
    };

    expect(estimateModelCredits(noPublishedPrices, BASIS, 'chatConversation'))
      .toBeCloseTo(estimateModelCredits(SONNET_5, BASIS, 'chatConversation')!, 2);
  });

  it('reads the same missing price differently per provider, because the providers charge differently', () => {
    const onAnthropic = { input: 2, output: 10, provider: 'anthropic', supportsPromptCaching: true };
    const onOpenai = { ...onAnthropic, provider: 'openai' };
    const onGemini = { ...onAnthropic, provider: 'gemini' };

    // OpenAI discounts a cached read by half and charges nothing to write one; Gemini
    // discounts it to a quarter. Anthropic's read is a tenth but its write costs 1.25x,
    // and a conversation writes a third of what it reads.
    expect(estimateModelCredits(onOpenai, BASIS, 'chatConversation')).toBeCloseTo(
      2 * 0.0111 + 2 * 43.956 + 1 * 153.402 + 10 * 3.774, 2);
    expect(estimateModelCredits(onGemini, BASIS, 'chatConversation')).toBeCloseTo(
      2 * 0.0111 + 2 * 43.956 + 0.5 * 153.402 + 10 * 3.774, 2);
    expect(estimateModelCredits(onAnthropic, BASIS, 'chatConversation')).toBeCloseTo(178.33, 2);
  });

  it('ignores a published cache-write price outside Anthropic, where nothing charges for one', () => {
    // The catalogue mirrors whatever the feed publishes, charged or not. Only the
    // Anthropic family is billed off a cache-creation counter; everywhere else a first
    // send is plain prompt input, so quoting a write price would name a price the
    // ledger never debits.
    const withWritePrice = {
      input: 2, output: 10, cacheWrite: 7, cacheRead: 0.2,
      provider: 'openai', supportsPromptCaching: true,
    };
    const withoutWritePrice = { ...withWritePrice, cacheWrite: undefined };

    expect(estimateModelCredits(withWritePrice, BASIS, 'chatConversation'))
      .toBeCloseTo(estimateModelCredits(withoutWritePrice, BASIS, 'chatConversation')!, 2);
  });

  it('prices a model that does not cache with every prompt token at its input rate', () => {
    // Its prompt is re-sent in full every turn, so no cache rate applies at all. The
    // FORM is the two-coefficient one that preceded caching, but not the figure: this
    // is the dearest way to run the same conversation, and it must not land on the
    // cached price.
    const noCaching = { input: 2, output: 10, provider: 'openai', supportsPromptCaching: false };

    expect(estimateModelCredits(noCaching, BASIS, 'chatConversation')).toBeCloseTo(
      2 * (0.0111 + 43.956 + 153.402) + 10 * 3.774, 2);
    // Guards the inverse regression - a future "simplification" that dropped the
    // caching distinction would quote the same figure for both. It does NOT prove the
    // cache-rate resolution: before that existed, this model already resolved to the
    // input rate and this assertion already held.
    expect(estimateModelCredits(noCaching, BASIS, 'chatConversation')!)
      .toBeGreaterThan(estimateModelCredits(SONNET_5, BASIS, 'chatConversation')! * 2);
  });

  it('treats a published cache price as proof the model caches, whatever the flag says', () => {
    // A catalogue row carrying a cache price and no capability flag is the common
    // shape, and a price for a thing is better evidence than a flag about it. Either
    // class counts as that evidence, including a write price on its own.
    const pricedButUnflagged = { input: 2, output: 10, cacheWrite: 2.5, cacheRead: 0.2, provider: 'anthropic' };
    const writePriceOnly = { input: 2, output: 10, cacheWrite: 2.5, provider: 'anthropic' };

    expect(estimateModelCredits(pricedButUnflagged, BASIS, 'chatConversation')).toBeCloseTo(178.33, 2);
    // Write price honoured, read falls to the family weight (2 x 0.1), which is what
    // Sonnet 5 publishes anyway - so the same figure, reached the other way.
    expect(estimateModelCredits(writePriceOnly, BASIS, 'chatConversation')).toBeCloseTo(178.33, 2);
  });

  it('contradicts the flag only on a real price: the flag wins when the price is 0', () => {
    // The two signals disagree here, and the rule has to pick one. A stored 0 is not a
    // price at all - the ledger reads a non-positive cache rate as unknown, and the
    // mirror that fills the column refuses to store a 0 because it would make cached
    // input free. So the flag is all that is left, and it says no caching.
    const flaggedOffWithZeroPrice = {
      input: 2, output: 10, cacheRead: 0, cacheWrite: 0,
      provider: 'google', supportsPromptCaching: false,
    };

    expect(estimateModelCredits(flaggedOffWithZeroPrice, BASIS, 'chatConversation')).toBeCloseTo(
      2 * (0.0111 + 43.956 + 153.402) + 10 * 3.774, 2);
  });

  it('never prices a cached token at zero, even when the catalogue stores one', () => {
    // The defect this is here for: reading 0 as a real price quotes 138,200 cache-read
    // tokens at nothing while the ledger charges the family rate for them. A zero must
    // behave exactly like an ABSENT price, never like a free one.
    const zeroPriced = {
      input: 2, output: 10, cacheRead: 0, cacheWrite: 0,
      provider: 'anthropic', supportsPromptCaching: true,
    };
    const noPriceAtAll = { input: 2, output: 10, provider: 'anthropic', supportsPromptCaching: true };

    expect(estimateModelCredits(zeroPriced, BASIS, 'chatConversation'))
      .toBeCloseTo(estimateModelCredits(noPriceAtAll, BASIS, 'chatConversation')!, 2);
    expect(estimateModelCredits(zeroPriced, BASIS, 'chatConversation')).toBeCloseTo(178.33, 2);
  });

  it('matches the provider whatever case the catalogue serves it in', () => {
    // The server keys its families in lower case and the catalogue promises no case. An
    // unmatched provider falls to the no-discount entry, which quotes a cached token at
    // full price - a silent 60% over-statement on the reference model.
    const upperCased = { input: 2, output: 10, provider: 'Anthropic', supportsPromptCaching: true };

    expect(estimateModelCredits(upperCased, BASIS, 'chatConversation')).toBeCloseTo(178.33, 2);
  });

  it('gives a model with no provider at all the no-discount entry rather than a neighbour', () => {
    const noProvider = { input: 2, output: 10, supportsPromptCaching: true };

    expect(estimateModelCredits(noProvider, BASIS, 'chatConversation')).toBeCloseTo(
      2 * (0.0111 + 43.956 + 153.402) + 10 * 3.774, 2);
  });

  it('gives an unknown provider no cache discount rather than guessing one', () => {
    const unknownVendor = { input: 2, output: 10, provider: 'some-new-vendor', supportsPromptCaching: true };
    const noFallbackPublished: ModelCostBasis = { enabled: true, profiles: BASIS.profiles };

    const atInputRate = 2 * (0.0111 + 43.956 + 153.402) + 10 * 3.774;
    expect(estimateModelCredits(unknownVendor, BASIS, 'chatConversation')).toBeCloseTo(atInputRate, 2);
    // Same answer from a server too old to publish the fallback at all: a wrong
    // discount would be worse than no discount.
    expect(estimateModelCredits(unknownVendor, noFallbackPublished, 'chatConversation'))
      .toBeCloseTo(atInputRate, 2);
  });

  it('shows nothing where credits are not metered, which is every CE install', () => {
    const ce: ModelCostBasis = { enabled: false, profiles: {} };

    expect(estimateModelCredits(SONNET_5, ce, 'chatConversation')).toBeNull();
  });

  it('shows nothing before the basis has been answered', () => {
    expect(estimateModelCredits(SONNET_5, null, 'chatConversation')).toBeNull();
    expect(estimateModelCredits(SONNET_5, undefined, 'chatConversation')).toBeNull();
  });

  it('shows nothing for a model the catalogue does not price', () => {
    expect(estimateModelCredits(undefined, BASIS, 'chatConversation')).toBeNull();
    expect(estimateModelCredits({ input: 2 }, BASIS, 'chatConversation')).toBeNull();
  });

  it('shows nothing for a catalogue sentinel rate rather than a negative price', () => {
    // The openrouter/auto router carries "-1" as its list price. Rendering
    // "-1,100 cr" beside it would read as a bug and, worse, as a refund.
    expect(estimateModelCredits({ input: -1, output: -1 }, BASIS, 'chatConversation')).toBeNull();
  });

  it('shows nothing for a profile the server did not publish', () => {
    const partial: ModelCostBasis = { enabled: true, profiles: {} };

    expect(estimateModelCredits(SONNET_5, partial, 'classifyStep')).toBeNull();
  });

  it('prices a genuinely free model at zero rather than hiding it', () => {
    expect(estimateModelCredits({ input: 0, output: 0 }, BASIS, 'chatConversation')).toBe(0);
  });
});

describe('roundCreditEstimate', () => {
  it('keeps a tenth under 10 credits, where the tenth is the whole signal', () => {
    // A classify step at 3.3 and one at 0.4 are a different decision; rounding
    // both to whole credits would erase the cheap end of the catalogue.
    expect(roundCreditEstimate(3.33)).toBe(3.3);
    expect(roundCreditEstimate(0.44)).toBe(0.4);
  });

  it('rounds to whole credits in the middle, and to tens above a thousand', () => {
    expect(roundCreditEstimate(284.16)).toBe(284);
    expect(roundCreditEstimate(78.81)).toBe(79);
    expect(roundCreditEstimate(1234)).toBe(1230);
  });
});

describe('formatCreditEstimate', () => {
  it('formats in the app locale, never the browser one', () => {
    // Rates dear enough to push the figure well over a thousand ON PURPOSE: the whole
    // point of this test is the thousands separator, and on an ordinary model the
    // figure lands where 'en' and 'fr' render identically and the assertion would
    // prove nothing.
    const dear = {
      input: 200, output: 1000, cacheWrite: 250, cacheRead: 20,
      provider: 'anthropic', supportsPromptCaching: true,
    };

    expect(formatCreditEstimate(dear, BASIS, 'chatConversation', 'en')).toBe('17,830');
    expect(formatCreditEstimate(dear, BASIS, 'chatConversation', 'fr')).toMatch(/17\s?830/);
  });

  it('says a model is too cheap to price rather than calling it free', () => {
    // A tenth of a credit is the floor of what the badge can express. "0" would
    // read as free, which is a promise the ledger does not keep.
    expect(formatCreditEstimate({ input: 0.0001, output: 0.0001 }, BASIS, 'classifyStep', 'en'))
      .toBe('<0.1');
  });

  it('returns nothing to render when there is no estimate', () => {
    expect(formatCreditEstimate(SONNET_5, { enabled: false, profiles: {} }, 'classifyStep', 'en'))
      .toBeNull();
  });
});

describe('estimate copy', () => {
  const LOCALES: Record<string, any> = { en, fr, de, es, pt, zh };
  const PROFILES = ['chatConversation', 'chatConversation', 'guardrailCheck', 'classifyStep'];

  it.each(Object.keys(LOCALES))('%s labels the badge and every profile tooltip', (locale) => {
    const modelInfo = (LOCALES[locale] as any)?.modelInfo ?? {};
    expect(typeof modelInfo.creditEstimateShort).toBe('string');
    for (const profile of PROFILES) {
      const tooltip = modelInfo.creditEstimateTooltip?.[profile];
      expect(typeof tooltip, `${locale}.${profile}`).toBe('string');
      // Every tooltip states the figure; one that dropped the placeholder would
      // describe the shape of work and forget to price it.
      expect(tooltip, `${locale}.${profile}`).toContain('{credits}');
    }
  });

  it.each(Object.keys(LOCALES))('%s names the unit in full on the badge, never an abbreviation', (locale) => {
    // The badge read "~2,840 cr", which is not a word in any of these
    // languages: a reader who has never met the currency has to guess it, and
    // "cr" beside a price reads as easily as a currency code. So the unit the
    // badge prints must be the unit the sentence explaining it prints - the
    // text right after the figure in the tooltip.
    //
    // Compared at a WORD BOUNDARY, which is the whole difficulty. A plain
    // `startsWith` passes on the abbreviation ("credits for a typical..."
    // does start with "cr"), and splitting the tooltip on punctuation only
    // worked for zh by the accident of the ideographic comma that happens to
    // follow it today. The rule below holds for a space-separated language and
    // for a CJK one alike: what the badge prints must open the tooltip's own
    // wording, and must not stop mid-word.
    const modelInfo = (LOCALES[locale] as any)?.modelInfo ?? {};
    const badgeUnit = modelInfo.creditEstimateShort.split('{credits}')[1]?.trim() ?? '';
    const tooltipRest = modelInfo.creditEstimateTooltip.chatConversation
      .split('{credits}')[1]
      ?.trimStart() ?? '';

    expect(badgeUnit.length, `${locale}.creditEstimateShort states no unit`).toBeGreaterThan(0);
    expect(tooltipRest, `${locale} tooltip states nothing after the figure`).not.toBe('');
    expect(tooltipRest.startsWith(badgeUnit), `${locale}: badge "${badgeUnit}" is not how the tooltip opens`).toBe(true);
    // The character that follows must end the word. "cr" fails here: the
    // tooltip continues "credits", so the next character is a letter.
    const next = tooltipRest.slice(badgeUnit.length, badgeUnit.length + 1);
    expect(/^\p{L}$/u.test(next), `${locale}: badge "${badgeUnit}" truncates the tooltip's word`).toBe(false);
  });

  it.each(Object.keys(LOCALES))('%s writes no em-dash or en-dash in the estimate copy', (locale) => {
    const modelInfo = (LOCALES[locale] as any)?.modelInfo ?? {};
    const strings = [modelInfo.creditEstimateShort, ...PROFILES.map((p) => modelInfo.creditEstimateTooltip?.[p])];
    expect(strings.filter((value) => typeof value === 'string' && /[--]/.test(value))).toEqual([]);
  });
});

/**
 * A provider billed on a lever of its own.
 *
 * The coefficients above fold in the GLOBAL lever and name no provider, which is what
 * keeps the platform's margin off the wire as a number. So a provider that is NOT billed
 * at that lever needs a correction published beside them, or this file confidently
 * renders a figure the ledger will not debit.
 *
 * The shipped configuration overrides nobody, so the factor arrives empty in practice.
 * The tests below therefore name a provider that does not exist: a fixture spelled
 * `typesafe` would read as a claim about what the decision model is charged, and it is
 * charged at the ordinary lever like everything else. The RATES stay Jev's, because a
 * cost base that small is what makes a scaling defect visible at all.
 */
describe('per-provider billing scale', () => {
  const SCALED: ModelCostBasis = { ...BASIS, providerScales: { acme: 7.500002 } };
  /** A decision model's shape: $0.042 per 1M input, output free. */
  const JEV = { input: 0.042, output: 0 };

  it('leaves every provider that has no entry exactly where it was', () => {
    expect(estimateModelCredits(SONNET_5, SCALED, 'classifyStep', 'anthropic'))
      .toBe(estimateModelCredits(SONNET_5, BASIS, 'classifyStep'));
  });

  it('scales the provider that has one, by exactly the published factor', () => {
    const unscaled = estimateModelCredits(JEV, BASIS, 'classifyStep')!;
    const scaled = estimateModelCredits(JEV, SCALED, 'classifyStep', 'acme')!;

    expect(scaled).toBeCloseTo(unscaled * 7.500002, 10);
  });

  it('matches the provider case-insensitively, since the catalogue is not guaranteed to agree', () => {
    expect(estimateModelCredits(JEV, SCALED, 'classifyStep', 'AcMe'))
      .toBe(estimateModelCredits(JEV, SCALED, 'classifyStep', 'acme'));
  });

  it('ignores a basis with no scales at all, which is every install that overrides nothing', () => {
    expect(estimateModelCredits(JEV, BASIS, 'classifyStep', 'acme'))
      .toBe(estimateModelCredits(JEV, BASIS, 'classifyStep'));
  });

  it('refuses a non-positive or non-finite factor rather than quoting a free model', () => {
    // These can only come from a malformed payload, and applying one would render a
    // price of zero or a negative, which a reader takes as a promise.
    for (const bad of [0, -2, Number.NaN, Number.POSITIVE_INFINITY]) {
      const basis: ModelCostBasis = { ...BASIS, providerScales: { acme: bad } };
      expect(estimateModelCredits(JEV, basis, 'classifyStep', 'acme'))
        .toBe(estimateModelCredits(JEV, BASIS, 'classifyStep'));
    }
  });

  it('the decision engine costs a reader far less than the chat one at the shipped lever', () => {
    // What the pricing decision buys the user, with no correction on either side because
    // the platform overrides nobody: the same classification quoted off the same
    // coefficients, on a cost base 48x smaller.
    const jev = estimateModelCredits(JEV, BASIS, 'classifyStep', 'typesafe')!;
    const sonnet = estimateModelCredits(SONNET_5, BASIS, 'classifyStep', 'anthropic')!;

    expect(jev).toBeLessThan(sonnet);
    expect(jev * 40).toBeLessThan(sonnet);
  });

  it('formats through the same scale, so the badge and the ledger agree', () => {
    const formatted = formatCreditEstimate(JEV, SCALED, 'classifyStep', 'en', 'acme');
    const unscaledFormatted = formatCreditEstimate(JEV, BASIS, 'classifyStep', 'en');

    expect(formatted).not.toBeNull();
    expect(formatted).not.toBe(unscaledFormatted);
  });
});

/**
 * The exclusions the ledger applies BEFORE any multiplier.
 *
 * `ModelPricingService.usesCloudLlmBillingMultiplier` skips the margin entirely for
 * websearch, stability-ai and any image model: those bill per unit against a platform
 * credential, not per token. Applying a per-provider factor to one of them would quote a
 * price the ledger never charges. Unreachable for a decision model, which is none of
 * them; this is the trap the SECOND overridden provider would walk into.
 */
describe('providers the ledger excludes from the multiplier', () => {
  const SCALED: ModelCostBasis = {
    ...BASIS,
    providerScales: { websearch: 5, 'stability-ai': 5, openai: 5 },
  };
  const RATES = { input: 1, output: 1 };

  it('does not scale websearch, which is billed per search', () => {
    expect(estimateModelCredits(RATES, SCALED, 'classifyStep', 'websearch'))
      .toBe(estimateModelCredits(RATES, BASIS, 'classifyStep'));
  });

  it('does not scale stability-ai, which is billed per image', () => {
    expect(estimateModelCredits(RATES, SCALED, 'classifyStep', 'stability-ai'))
      .toBe(estimateModelCredits(RATES, BASIS, 'classifyStep'));
  });

  it('does not scale an image model even on a provider that IS overridden', () => {
    // The exclusion is on the pair, not the provider: openai carries an override here
    // and its chat models take it, while its image models must not.
    expect(estimateModelCredits(RATES, SCALED, 'classifyStep', 'openai', 'gpt-image-1.5'))
      .toBe(estimateModelCredits(RATES, BASIS, 'classifyStep'));
    expect(estimateModelCredits(RATES, SCALED, 'classifyStep', 'openai', 'dall-e-3'))
      .toBe(estimateModelCredits(RATES, BASIS, 'classifyStep'));
  });

  it('still scales that provider chat models, so the exclusion is narrow', () => {
    const scaled = estimateModelCredits(RATES, SCALED, 'classifyStep', 'openai', 'gpt-5-mini')!;
    const plain = estimateModelCredits(RATES, BASIS, 'classifyStep')!;

    expect(scaled).toBeCloseTo(plain * 5, 10);
  });

  it('reads the provider off the rates when the argument is omitted', () => {
    // A call site that forgets the argument must still be correct: the alternative is a
    // silent under-quote that looks entirely plausible.
    const withRatesProvider = { input: 1, output: 1, provider: 'openai' };

    expect(estimateModelCredits(withRatesProvider, SCALED, 'classifyStep'))
      .toBe(estimateModelCredits(RATES, SCALED, 'classifyStep', 'openai'));
  });
});
