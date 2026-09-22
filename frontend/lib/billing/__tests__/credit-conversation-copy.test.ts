import { describe, it, expect } from 'vitest';
import {
  creditFactsFor,
  CREDIT_TIERS,
  CREDIT_EXAMPLES,
  CREDIT_EXAMPLES_FAQ_KEY,
  AGENT_CONVERSATIONS_PER_PACK,
  SIMPLE_CONVERSATIONS_PER_PACK,
  FAQ_KEYS,
} from '@/lib/billing/pricing-constants';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';

/**
 * "Your credits cover about N conversations" is the one number on the pricing
 * page a reader can check against their own invoice, so it gets the same
 * treatment as any other cross-layer contract.
 *
 * Three things can break it silently. An EXAMPLE can drift, so that the page
 * promises more work than the pack can pay for. The two ENDS of the headline
 * range can cross, which would read as a typo. And the COPY can lose a locale
 * or a placeholder: a missing key falls back to English and a misspelt
 * placeholder renders as a literal `{agentConversations}`, neither of which
 * throws, so nothing else in the suite would notice.
 */

const LOCALES: Record<string, any> = { en, fr, de, es, pt, zh };

/**
 * Every value any surface passes into these messages.
 *
 * <p>A FLAT list, and honestly so. An earlier version dressed this up as a
 * per-surface intersection to catch "a message using a placeholder only one of
 * its readers supplies" - the failure that shipped when the credits tooltip was
 * rewritten and the comparison dialog still passed three values by name. It
 * could not: every surface passes the same `creditFactsFor` object, so the
 * intersection was the union and the test was exactly as strong as before,
 * while its comment claimed otherwise.
 *
 * <p>What this list is for is narrower and real: a message may not invent a
 * placeholder NOBODY passes. The cross-surface guarantee is held by a test that
 * renders the component with the real catalogues in all six locales and fails
 * on next-intl's own FORMATTING_ERROR, which is the only thing that can know
 * what a caller actually passes:
 * components/pricing/__tests__/PlanComparisonDialog.tooltips.test.tsx
 */
const SUPPLIED_PLACEHOLDERS = [
  ...Object.keys(creditFactsFor('en')),
  // The per-example rows are rendered one at a time with their own two values.
  'count',
];

function read(messages: any, path: string): unknown {
  return path.split('.').reduce<any>((node, part) => (node ? node[part] : undefined), messages);
}

/** Message paths under `pricing` that the FAQ and the plan comparison resolve. */
const REQUIRED_PATHS = [
  'compare.dimensions.creditsTooltip',
  // The other three "i" that answer "what does a credit buy". They say it in
  // the same words and with the same figures, so they belong to the same
  // parity, placeholder and punctuation sweeps as the one above.
  'compare.dimensions.aiCreditsTooltip',
  'planCards.features.creditsFreeTooltip',
  'planCards.features.aiCreditsFreeTooltip',
  `faq.${CREDIT_EXAMPLES_FAQ_KEY}.examplesCaption`,
  ...CREDIT_EXAMPLES.flatMap((example) => [
    `faq.${CREDIT_EXAMPLES_FAQ_KEY}.examples.${example.id}.count`,
    `faq.${CREDIT_EXAMPLES_FAQ_KEY}.examples.${example.id}.detail`,
  ]),
  ...FAQ_KEYS.flatMap((key) => [`faq.${key}.question`, `faq.${key}.answer`]),
];

describe('credit examples', () => {
  it.each(CREDIT_EXAMPLES)('$id never promises more than the entry pack can pay for', (example) => {
    // The page says the pack buys `perEntryPack` of these and that one costs
    // `creditsEach`. Multiply them back out and the claim has to fit inside the
    // pack the page is talking about.
    expect(example.creditsEach * example.perEntryPack).toBeLessThanOrEqual(CREDIT_TIERS[0]);
  });

  it.each(CREDIT_EXAMPLES)('$id stays close enough to the pack to still be a useful figure', (example) => {
    // The opposite failure: rounding down so hard the page undersells itself and
    // stops matching what a user sees in their own usage.
    expect(example.creditsEach * example.perEntryPack).toBeGreaterThanOrEqual(CREDIT_TIERS[0] * 0.6);
  });

  it('keeps an agent conversation the expensive end and a plain one the cheap end', () => {
    // The whole point of splitting the figure: an agent re-sends the transcript
    // on every tool round-trip, so it must never be quoted as the cheaper case.
    const agent = CREDIT_EXAMPLES.find((example) => example.id === 'agentChat')!;
    const simple = CREDIT_EXAMPLES.find((example) => example.id === 'simpleChat')!;
    const classify = CREDIT_EXAMPLES.find((example) => example.id === 'classifyStep')!;
    expect(agent.creditsEach).toBeGreaterThan(simple.creditsEach);
    expect(simple.creditsEach).toBeGreaterThan(classify.creditsEach);
  });

  it('reads as a range, low end first', () => {
    expect(AGENT_CONVERSATIONS_PER_PACK).toBeLessThan(SIMPLE_CONVERSATIONS_PER_PACK);
  });

  it('quotes the headline range straight from the examples it illustrates', () => {
    // A reader who divides the pack by the per-conversation figure must land on
    // the headline, so the two cannot be edited apart.
    expect(AGENT_CONVERSATIONS_PER_PACK).toBe(
      CREDIT_EXAMPLES.find((example) => example.id === 'agentChat')!.perEntryPack
    );
    expect(SIMPLE_CONVERSATIONS_PER_PACK).toBe(
      CREDIT_EXAMPLES.find((example) => example.id === 'simpleChat')!.perEntryPack
    );
  });

  it('quotes round numbers, so the copy reads as an estimate rather than a guarantee', () => {
    for (const example of CREDIT_EXAMPLES) {
      // A multiple of 5, OR a single digit. The multiple-of-5 rule exists so a count
      // reads as an estimate rather than a measurement.
      //
      // The single-digit escape hatch is currently UNUSED and kept on purpose. It was
      // added when an agent conversation cost ~550 credits and a pack covered 9.1 of
      // them: the only multiples of 5 on offer were 5 (understating the pack by 45%,
      // which is a different claim rather than an estimate) and 10 (which the pack did
      // not cover). Since the page moved to a lightweight basis on 2026-09-21 an agent
      // conversation is ~55 credits and a pack covers 90, so every count is comfortably
      // above ten and no example needs the hatch. A margin move or a dearer basis can
      // put one back under ten, which is why it stays.
      const roundEnough = example.perEntryPack % 5 === 0 || example.perEntryPack < 10;
      expect(roundEnough, `perEntryPack ${example.perEntryPack} for ${example.id}`).toBe(true);
    }
  });
});

describe('credit-to-conversation copy', () => {
  it.each(Object.keys(LOCALES))('%s translates every credits and FAQ string', (locale) => {
    const pricing = (LOCALES[locale] as any)?.pricing ?? {};
    const missing = REQUIRED_PATHS.filter((path) => {
      const value = read(pricing, path);
      return typeof value !== 'string' || value.trim() === '';
    });
    expect(missing).toEqual([]);
  });

  it.each(Object.keys(LOCALES))('%s only uses placeholders the page actually supplies', (locale) => {
    const pricing = (LOCALES[locale] as any)?.pricing ?? {};
    const unknown: string[] = [];
    for (const path of REQUIRED_PATHS) {
      const value = read(pricing, path);
      if (typeof value !== 'string') continue;
      for (const match of value.matchAll(/\{(\w+)\}/g)) {
        if (!SUPPLIED_PLACEHOLDERS.includes(match[1])) {
          unknown.push(`${path}: {${match[1]}}`);
        }
      }
    }
    expect(unknown).toEqual([]);
  });

  it.each(Object.keys(LOCALES))('%s names the pack the figures are counted against', (locale) => {
    // The three counts below the answer are meaningless without the pack they
    // divide. A translation that drops the placeholder leaves them floating.
    const caption = read(
      (LOCALES[locale] as any)?.pricing ?? {},
      `faq.${CREDIT_EXAMPLES_FAQ_KEY}.examplesCaption`
    );
    expect(caption).toContain('{credits}');
  });

  it.each(Object.keys(LOCALES))('%s prices each example rather than only counting them', (locale) => {
    // `count` answers "how many", `detail` answers "at what each". A detail
    // that lost its placeholder would state the shape of the work and forget
    // to price it, which is the half a reader came for.
    const faq = (LOCALES[locale] as any)?.pricing?.faq?.[CREDIT_EXAMPLES_FAQ_KEY] ?? {};
    for (const example of CREDIT_EXAMPLES) {
      expect(faq.examples?.[example.id]?.count, `${locale}.${example.id}`).toContain('{count}');
      expect(faq.examples?.[example.id]?.detail, `${locale}.${example.id}`).toContain('{credits}');
    }
  });

  it.each(Object.keys(LOCALES))('%s no longer ships the band the FAQ replaced', (locale) => {
    // The page asked and answered the same question twice; the band is gone and
    // its messages with it, or the next editor updates copy nobody reads.
    expect((LOCALES[locale] as any)?.pricing?.credits?.reference).toBeUndefined();
  });

  it.each(Object.keys(LOCALES))('%s tells the FAQ answer apart for agent and tool-free conversations', (locale) => {
    const answer = read((LOCALES[locale] as any)?.pricing ?? {}, 'faq.conversationCost.answer');
    expect(answer).toContain('{agentCredits}');
    expect(answer).toContain('{simpleCredits}');
    expect(answer).toContain('{classifyCredits}');
  });

  /**
   * The four pricing "i" that price a unit of work, and the three model-picker
   * ones. They are the same sentence in two places: "about N credits for <a
   * unit>, an estimate from real usage". The figure is bolded with the `**`
   * marker (see lib/utils/boldMarkup) because it is the one thing a reader is
   * scanning for.
   */
  const MARKED_TOOLTIPS = [
    ['pricing', 'compare.dimensions.creditsTooltip'],
    ['pricing', 'compare.dimensions.aiCreditsTooltip'],
    ['pricing', 'planCards.features.creditsFreeTooltip'],
    ['pricing', 'planCards.features.aiCreditsFreeTooltip'],
    ['modelInfo', 'creditEstimateTooltip.chatConversation'],
    ['modelInfo', 'creditEstimateTooltip.guardrailCheck'],
    ['modelInfo', 'creditEstimateTooltip.classifyStep'],
  ] as const;

  it.each(Object.keys(LOCALES))('%s bolds a figure in every credit tooltip', (locale) => {
    // Two ways this breaks silently, both in one locale at a time: a translator
    // drops the marker (the figure stops standing out, nothing throws), or
    // leaves an odd one (a literal `**` is printed at a customer, and
    // renderBoldMarkup deliberately does not hide it).
    for (const [root, path] of MARKED_TOOLTIPS) {
      const value = read((LOCALES[locale] as any)?.[root] ?? {}, path);
      expect(typeof value, `${locale}.${root}.${path}`).toBe('string');
      const text = value as string;
      const markers = (text.match(/\*\*/g) ?? []).length;
      expect(markers % 2, `${locale}.${root}.${path} has an unpaired ** marker`).toBe(0);
      // And EVERY figure is marked, not merely one of them. The paid tooltip
      // quotes three, and a locale that emphasised the first and dropped the
      // other two would pass a "contains a marked figure" check while reading,
      // to that language's users, as one bold number and two plain ones.
      const outsideSpans = text.replace(/\*\*[^*]+\*\*/g, '');
      const unmarked = [...outsideSpans.matchAll(/\{\w*[Cc]redits\}/g)].map((m) => m[0]);
      expect(unmarked, `${locale}.${root}.${path} quotes a figure it does not emphasise`)
        .toEqual([]);
      // ...and at least one span exists, or a message quoting no figure at all
      // would satisfy the line above by vacuum.
      expect(text, `${locale}.${root}.${path} marks no figure`)
        .toMatch(/\*\*[^*]*\{\w+\}[^*]*\*\*/);
    }
  });

  it.each(Object.keys(LOCALES))('%s marks a figure ONLY where something renders the marker', (locale) => {
    // The other half of the convention, and the one nothing else can see. The
    // marker is deliberately left visible when it is unpaired (an authoring
    // mistake should be found), which means a `**` in a message that is NOT
    // routed through `renderBoldMarkup` prints raw asterisks at a reader, in
    // that locale only, with every test green. Two components render it today:
    // FeatureLabel (the pricing "i") and ModelInfo (the picker estimate). So
    // the set of marked messages is pinned to the set they read.
    const allowed = new Set(MARKED_TOOLTIPS.map(([root, path]) => `${root}.${path}`));
    const marked: string[] = [];
    const walk = (node: unknown, trail: string) => {
      if (typeof node === 'string') {
        if (node.includes('**')) marked.push(trail);
      } else if (node && typeof node === 'object') {
        for (const [key, value] of Object.entries(node)) {
          walk(value, trail ? `${trail}.${key}` : key);
        }
      }
    };
    walk(LOCALES[locale], '');

    const unrendered = marked.filter((path) => !allowed.has(path));
    expect(
      unrendered,
      `${locale}: these messages mark a figure with ** but nothing renders the marker, `
        + 'so a reader sees the asterisks. Either route the surface through '
        + 'renderBoldMarkup and add it to MARKED_TOOLTIPS, or drop the marker',
    ).toEqual([]);
    // And the allow-list is not stale in the other direction.
    expect(marked.sort()).toEqual([...allowed].sort());
  });

  it('lists the conversation-cost answer first, where a reader looks for it', () => {
    expect(FAQ_KEYS[0]).toBe('conversationCost');
  });

  /** The four pricing "i" (the picker's three price a model the reader already picked). */
  const PRICING_TOOLTIPS = MARKED_TOOLTIPS
    .filter(([root]) => root === 'pricing')
    .map(([, path]) => path);

  /** The figures whose value depends on which MODEL the work ran on. */
  const MODEL_PRICED = ['{exchangeCredits}', '{simpleCredits}', '{agentCredits}', '{classifyCredits}'];

  it.each(Object.keys(LOCALES))('%s attributes every model-priced figure, and only those', (locale) => {
    // The page prices on the LIGHTWEIGHT end (see PRICING_BASIS_MODEL), which inverts the
    // direction these figures can be wrong in: they used to be the ceiling and are now
    // close to the floor. An estimate that no longer names what it was priced on is
    // therefore not merely vaguer, it reads as a promise it cannot keep. The model is
    // interpolated rather than written into the copy so six locales cannot disagree about
    // which model the platform quotes.
    //
    // The inverse matters as much, which is why this is one test and not two: a flat
    // price does NOT vary by model, so naming one beside it would invent a qualification
    // and turn an exact figure into an apparent estimate. {nodeCredits} is the flat one
    // (CreditService debits exactly one credit per workflow node), and the free-plan
    // credits tooltip is the message that quotes it.
    const pricing = (LOCALES[locale] as any)?.pricing ?? {};
    const wrong: string[] = [];
    for (const path of [...PRICING_TOOLTIPS, `faq.${CREDIT_EXAMPLES_FAQ_KEY}.answer`]) {
      const message = String(read(pricing, path) ?? '');
      const modelPriced = MODEL_PRICED.some((unit) => message.includes(unit));
      const attributed = message.includes('{basisModel}');
      if (modelPriced !== attributed) {
        wrong.push(`${path}: ${modelPriced ? 'quotes a model-priced figure and names no model'
          : 'names a model beside a figure that does not vary by model'}`);
      }
    }
    expect(wrong).toEqual([]);
  });

  it.each(Object.keys(LOCALES))('%s names no model the basis no longer is', (locale) => {
    // A translator updating one sentence and leaving the model name in another is the
    // shape this catches: the figure moves, the attribution does not, and only that
    // language's readers see a price attributed to a model it was not priced on.
    const pricing = (LOCALES[locale] as any)?.pricing ?? {};
    const stale = REQUIRED_PATHS.filter((path) => /Sonnet|Claude|GPT|Gemini/i
      .test(String(read(pricing, path) ?? '')));
    expect(stale).toEqual([]);
  });

  it.each(Object.keys(LOCALES))('%s says which pot the free plan\'s comparison figure is', (locale) => {
    // The comparison table's "Monthly credits" row carries ONE label and ONE tooltip for
    // all five columns (plan-comparison.ts declares a single scale row), and that tooltip
    // prices a short agent exchange, which on FREE the monthly bucket refuses. The CELL
    // is therefore the only place that can say which pot the Free figure belongs to, and
    // the plan card two clicks away already says "workflow credits" on its own line.
    //
    // Asserted as a SHAPE, because "workflows" is a different word in every locale: the
    // cell must carry a parenthesised qualifier beside the figure. The English one is
    // then checked for the word itself, so the shape rule cannot be satisfied by a
    // qualifier that says something else entirely.
    const cell = String(read((LOCALES[locale] as any)?.pricing ?? {}, 'compare.values.creditsFree'));
    expect(cell, `${locale}: the free credits cell names no pot`).toMatch(/[(（].+[)）]/);
    if (locale === 'en') expect(cell.toLowerCase()).toContain('workflow');
  });

  it.each(Object.keys(LOCALES))('%s prices each free-plan pot with a debit that pot can fund', (locale) => {
    // The Free plan shows two lines and they fund DIFFERENT SOURCE TYPES, which is a
    // backend rule and not a presentation choice: CreditService restricts the monthly
    // bucket to WORKFLOW_NODE / WORKFLOW_NODE_PROMO, while a chat turn, an agent turn
    // and a classify step all draw the separate AI allowance, and the flat-cost add-ons
    // draw PAYG.
    //
    // So the unit a tooltip quotes is not a wording choice either. This card used to
    // price the monthly pot with a classification step, which on FREE that pot REFUSES:
    // the sentence read as a promise the ledger would not keep. Each "i" now quotes a
    // unit its own pot actually pays for, and the LLM units are pinned OUT of the
    // workflow one so the mistake cannot come back under a different placeholder.
    const features = (LOCALES[locale] as any)?.pricing?.planCards?.features ?? {};
    expect(features.creditsFreeTooltip, `${locale}: the workflow pot must price a workflow node`)
      .toContain('{nodeCredits}');
    for (const llmUnit of ['{exchangeCredits}', '{classifyCredits}', '{simpleCredits}', '{agentCredits}']) {
      expect(
        features.creditsFreeTooltip,
        `${locale}: the monthly workflow pot cannot fund ${llmUnit}, so it must not price itself with it`,
      ).not.toContain(llmUnit);
    }
    expect(features.aiCreditsFreeTooltip, `${locale}: the AI pot must price an agent exchange`)
      .toContain('{exchangeCredits}');
  });

  it.each(Object.keys(LOCALES))('%s writes no em-dash or en-dash in the new copy', (locale) => {
    const pricing = (LOCALES[locale] as any)?.pricing ?? {};
    const offenders = REQUIRED_PATHS.filter((path) => {
      const value = read(pricing, path);
      return typeof value === 'string' && /[--]/.test(value);
    });
    expect(offenders).toEqual([]);
  });
});
