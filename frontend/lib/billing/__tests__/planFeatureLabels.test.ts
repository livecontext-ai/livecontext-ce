import { describe, it, expect } from 'vitest';
import { CHAT_EXCHANGE_CREDITS, PRICING_BASIS_MODEL } from '@/lib/billing/pricing-constants';
import fs from 'node:fs';
import path from 'node:path';
import en from '@/messages/en.json';
import fr from '@/messages/fr.json';
import de from '@/messages/de.json';
import es from '@/messages/es.json';
import pt from '@/messages/pt.json';
import zh from '@/messages/zh.json';
import { planFeatureLabels } from '@/lib/billing/planFeatureLabels';
import { creditFactsFor, PLAN_FEATURE_KEYS } from '@/lib/billing/pricing-constants';

/**
 * The plan card's feature lines, and which of them carry an info "i".
 *
 * The regression a visitor felt: only the FREE plan's credits line had an "i",
 * so every paid plan quoted a number ("50,000 credits per month") with nothing
 * anywhere saying what it buys, which is the single question the page exists to
 * answer.
 *
 * The mapping also existed TWICE, once per surface that draws plan cards. The
 * two copies had NOT diverged - they handled the same three keys identically -
 * so this is not a bug that had already happened; it is the reason the fix
 * above would have had to be applied twice, and the reason the next one would
 * too. `one mapping, not one per surface` below pins the merge by asserting
 * that each special-cased message is read from exactly one place in the app.
 */

const LOCALES: Record<string, any> = { en, fr, de, es, pt, zh };

/** Reads a message out of a locale catalogue by dotted path. */
function read(catalogue: any, dotted: string): string | undefined {
  const value = dotted.split('.').reduce<any>((node, part) => node?.[part], catalogue);
  return typeof value === 'string' ? value : undefined;
}

/** A key-echoing translator, so assertions read wording DECISIONS, not translations. */
function echo(scope: string) {
  return (key: string, values?: Record<string, unknown>) =>
    values ? `${scope}.${key}:${JSON.stringify(values)}` : `${scope}.${key}`;
}

/** The real English catalogue, scoped, so a test can read the sentence a visitor gets. */
function real(scope: string) {
  return (key: string, values?: Record<string, unknown>) => {
    const value = read(en, `${scope}.${key}`) ?? `${scope}.${key}`;
    return values
      ? value.replace(/\{(\w+)\}/g, (_m, name) => String(values[name] ?? `{${name}}`))
      : value;
  };
}

const DEPS = {
  tCards: echo('planCards'),
  tPricing: echo('pricing'),
  credits: '50,000',
  creditFacts: creditFactsFor('en'),
};

/** The line a plan states its monthly credits on, whichever key it uses. */
function creditsLine(planId: string, deps = DEPS): string {
  const keys = PLAN_FEATURE_KEYS[planId];
  const index = keys.findIndex((key) => key.startsWith('credits'));
  expect(index, `${planId} states no credits line at all`).toBeGreaterThan(-1);
  return planFeatureLabels(planId, deps)[index];
}

describe('every plan explains what its credits buy', () => {
  it.each(['starter', 'pro', 'team', 'enterprise'])('%s carries an info tooltip on its credits line', (plan) => {
    // The regression: this was true of `free` alone. A paid plan quoted a
    // figure and offered no way to find out what it means.
    expect(creditsLine(plan)).toContain('||');
  });

  it.each(['starter', 'pro', 'team', 'enterprise'])(
    '%s explains them with the SAME words as the comparison table',
    (plan) => {
      // Not a second account of the same number: the card and the comparison
      // are read minutes apart by the same person, and the comparison is where
      // that sentence is maintained.
      const [, tooltip] = creditsLine(plan).split('||');
      expect(tooltip).toBe(DEPS.tPricing('compare.dimensions.creditsTooltip', DEPS.creditFacts));
    },
  );

  it('keeps the free plan on its own answer, which is a different one', () => {
    // Free credits run workflows only; chat and agents need a paid plan. Handing
    // free the paid sentence would promise conversations it does not buy.
    const [, tooltip] = creditsLine('free').split('||');
    // Its own message, and fed the same credit facts as the paid one: it now
    // prices the unit free credits actually buy (a workflow step), so it needs
    // the figures too.
    expect(tooltip).toBe(DEPS.tCards('features.creditsFreeTooltip', DEPS.creditFacts));
    expect(tooltip).not.toBe(DEPS.tPricing('compare.dimensions.creditsTooltip', DEPS.creditFacts));
  });

  it('still shows the figure itself, which the tooltip is only an aside to', () => {
    expect(creditsLine('pro')).toContain('50,000');
  });

  it('reads as a real sentence with real numbers, not a leftover placeholder', () => {
    const [, tooltip] = creditsLine('pro', {
      ...DEPS,
      tCards: real('pricing.planCards'),
      tPricing: real('pricing'),
    }).split('||');
    expect(tooltip).not.toMatch(/\{[a-zA-Z]+\}/);
    // The per-exchange price, which is what makes the sentence true on every card. It
    // used to divide the ENTRY PACK instead, and that arithmetic is false of Enterprise
    // (no pack, no slider) and drifts on the paid cards the moment the slider moves off
    // 5,000. It then quoted three units at once, which was true but unreadable on a card
    // a visitor is scanning; the breakdown lives in the FAQ answer now.
    // Derived from the constants rather than restated: these figures are re-priced
    // whenever the LLM billing multiplier moves, and a test that hardcodes them fails
    // for the wrong reason (a deliberate re-price) while still passing if the tooltip
    // silently stopped interpolating the real ones.
    expect(
      tooltip,
      `the ${CHAT_EXCHANGE_CREDITS}-credit figure for a short exchange is missing`,
    ).toContain(CHAT_EXCHANGE_CREDITS.toString());
    expect(tooltip, 'the tooltip no longer says what it was priced on')
      .toContain(PRICING_BASIS_MODEL);
    // A single figure can be a substring of unrelated copy - "6" is in almost any pricing
    // sentence - so the raw message is checked for the placeholder that produces it. The
    // pair is what makes this a real assertion: one proves the value was interpolated, the
    // other that the message still asks for it.
    const raw = (en as any).pricing?.compare?.dimensions?.creditsTooltip ?? '';
    for (const placeholder of ['{exchangeCredits}', '{basisModel}']) {
      expect(raw, `the credits tooltip no longer interpolates ${placeholder}`).toContain(placeholder);
    }
  });

  it('states a RATE, so it stays true on a card the entry pack says nothing about', () => {
    const [, tooltip] = creditsLine('enterprise', {
      ...DEPS,
      tCards: real('pricing.planCards'),
      tPricing: real('pricing'),
    }).split('||');
    // Enterprise is contact-us priced: it has no slider and no starting pack,
    // so a sentence built on either is simply false on this card.
    expect(tooltip.toLowerCase()).not.toContain('slider');
    expect(tooltip).not.toContain('5,000');
  });

  it.each(Object.keys(PLAN_FEATURE_KEYS))(
    '%s passes every tooltip the facts its message asks for',
    (planId) => {
      // The incident this closes, and it has happened here before: a message
      // gains a placeholder and its CALLER is not updated. next-intl then
      // throws FORMATTING_ERROR and renders the key path itself on the card, in
      // all six locales, while an echoing translator in a test sees nothing
      // wrong. `real` leaves an unsupplied placeholder literal, which is the
      // same tell, and it is checked on EVERY line of EVERY plan rather than on
      // the one that regressed last time.
      //
      // Both allowance branches: the Free card renders a different line when the
      // live allowance has not arrived yet, and it carries the same tooltip.
      for (const aiCredits of [undefined, '1,000']) {
        const lines = planFeatureLabels(planId, {
          ...DEPS,
          aiCredits,
          tCards: real('pricing.planCards'),
          tPricing: real('pricing'),
        });
        for (const line of lines) {
          const [label, tooltip] = line.split('||');
          expect(label, `${planId} label: ${label}`).not.toMatch(/\{[a-zA-Z]+\}/);
          if (tooltip !== undefined) {
            expect(tooltip, `${planId} tooltip: ${tooltip}`).not.toMatch(/\{[a-zA-Z]+\}/);
          }
        }
      }
    },
  );

  it('qualifies the agent figure wherever it is quoted, the way pricing-constants requires', () => {
    // CREDIT_EXAMPLES' docblock is binding on any copy quoting that number: it is the
    // median of a CONVERSATION of a measured shape, not the price of a finished
    // workflow, so the shape and "a longer build costs more" travel with it.
    //
    // Swept over every message rather than asserted on the one that happened to quote it.
    // The tooltip used to, and no longer does: an assertion naming a single message goes
    // green the moment the figure MOVES to another one, which is the only way this rule
    // can be broken without someone reading it.
    const quoting: string[] = [];
    const walk = (node: unknown, trail: string) => {
      if (typeof node === 'string') {
        if (node.includes('{agentCredits}')) quoting.push(trail);
      } else if (node && typeof node === 'object') {
        for (const [key, value] of Object.entries(node)) walk(value, trail ? `${trail}.${key}` : key);
      }
    };
    walk((en as any).pricing ?? {}, '');
    expect(quoting.length, 'nothing quotes the agent figure any more, so this guards nothing')
      .toBeGreaterThan(0);

    for (const path of quoting) {
      const message = String(path.split('.').reduce<any>((n, p) => n?.[p], (en as any).pricing));
      expect(message, `${path} quotes the agent figure without its measured shape`)
        .toContain('45 tool calls');
      expect(message.toLowerCase(), `${path} quotes the agent figure as a finished build`)
        .toContain('longer build costs');
    }
  });
});

describe('the publishing line names no brands', () => {
  it('leaves the platform list to the tooltip', () => {
    // Found by its KEY's position, not by the English text: matching on the
    // label makes renaming that string fail as an opaque TypeError rather than
    // as the assertion below.
    const index = PLAN_FEATURE_KEYS.starter.indexOf('nodesPublishing');
    expect(index, 'starter no longer lists the publishing integrations').toBeGreaterThan(-1);
    const [label, tooltip] = planFeatureLabels('starter', {
      ...DEPS,
      tCards: real('pricing.planCards'),
      tPricing: real('pricing'),
    })[index].split('||');

    // The label was "Publishing integrations included (YouTube, Instagram,
    // TikTok, X, LinkedIn...)": the longest thing on the card, out of date the
    // day a platform is added, and an exhaustive-looking promise it never was.
    for (const brand of ['YouTube', 'Instagram', 'TikTok', 'LinkedIn']) {
      expect(label, `"${brand}" is still on the card line`).not.toContain(brand);
    }
    // Moved, not deleted: the names are what make the line concrete.
    for (const brand of ['YouTube', 'Instagram', 'TikTok', 'LinkedIn']) {
      expect(tooltip).toContain(brand);
    }
    // And it does not read as the complete list.
    expect(tooltip.toLowerCase()).toContain('more');
  });

  it.each(Object.keys(LOCALES))('%s keeps the brands out of the label too', (locale) => {
    // A translation that kept the parenthetical would put the card back to two
    // lines in that language alone, which no English-only check would see.
    const label = read(LOCALES[locale], 'pricing.planCards.features.nodesPublishing') ?? '';
    const tooltip = read(LOCALES[locale], 'pricing.planCards.features.nodesPublishingTooltip') ?? '';
    expect(label.length, `${locale} has no publishing label`).toBeGreaterThan(0);
    for (const brand of ['YouTube', 'Instagram', 'TikTok', 'LinkedIn']) {
      expect(label, `${locale}: "${brand}" is still on the card line`).not.toContain(brand);
      expect(tooltip, `${locale}: "${brand}" is missing from the tooltip`).toContain(brand);
    }
  });
});

describe('one mapping, not one per surface', () => {
  /**
   * The property that matters is not "both files call the helper" - a file can
   * call it and then post-process the result.
   *
   * <p>An earlier version asserted each special-cased message was "read from
   * exactly one place" by grepping for its literal path. That claim was FALSE
   * the day it was written: PlanComparisonDialog builds its keys by template
   * literal (`dimensions.${row.id}Tooltip`), so no literal appears, the scan
   * could not see it, and the assertion reported "exactly one place" over a
   * second live reader - the very surface a later edit then broke.
   *
   * <p>So the invariant is stated the way it is actually true: the special
   * cases are hand-written in ONE file, and the surfaces that draw plan cards
   * hold none of their own. Dynamic readers are named explicitly, because a
   * grep cannot find them and a reviewer needs to know they exist.
   */
  const SPECIAL_CASE_MESSAGES = [
    'features.creditsFreeTooltip',
    'features.cePlatformCredsTooltip',
    'features.nodesPublishingTooltip',
    'compare.dimensions.creditsTooltip',
  ];

  /**
   * Surfaces that resolve these keys WITHOUT naming them, by building the key
   * from a row id. A grep is blind to them; they are listed so the count below
   * is a fact rather than an accident.
   */
  const DYNAMIC_READERS = ['components/pricing/PlanComparisonDialog.tsx'];

  /** The two surfaces that draw plan cards and must hold no mapping of their own. */
  const CARD_SURFACES = [
    'components/pricing/PricingPageContent.tsx',
    'app/[locale]/_landing/PricingSection.tsx',
  ];

  function sourceFiles(dir: string, out: string[] = []): string[] {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (['node_modules', '.next', '__tests__', 'e2e', 'messages'].includes(entry.name)) continue;
        sourceFiles(full, out);
      } else if (/\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name)) {
        out.push(full);
      }
    }
    return out;
  }

  const FILES = ['app', 'components', 'lib'].flatMap((d) => sourceFiles(path.join(process.cwd(), d)));
  const rel = (file: string) => path.relative(process.cwd(), file).split(path.sep).join('/');

  it('scanned a plausible number of files, so an empty sweep cannot pass', () => {
    expect(FILES.length).toBeGreaterThan(200);
    // And it really did reach the files the assertions below are about.
    for (const surface of [...CARD_SURFACES, ...DYNAMIC_READERS]) {
      expect(FILES.map(rel), `${surface} was not scanned`).toContain(surface);
    }
  });

  it.each(SPECIAL_CASE_MESSAGES)('%s is named in exactly one file', (message) => {
    const readers = FILES.filter((file) => fs.readFileSync(file, 'utf8').includes(message)).map(rel);
    expect(readers).toEqual(['lib/billing/planFeatureLabels.ts']);
  });

  it.each(CARD_SURFACES)('%s holds no plan-feature special cases of its own', (file) => {
    // The drift this prevents: two hand-maintained switches, edited one at a
    // time. Each surface may call the mapping; neither may re-implement a case.
    const source = fs.readFileSync(path.join(process.cwd(), file), 'utf8');
    expect(source).toContain('planFeatureLabels(');
    for (const message of SPECIAL_CASE_MESSAGES) {
      expect(source, `${file} re-implements ${message}`).not.toContain(message);
    }
    // And it does not rebuild the credit figures either.
    expect(source).toContain('creditFactsFor');
  });

  it.each(DYNAMIC_READERS)('%s resolves its tooltips dynamically, so a grep will never see it', (file) => {
    // Pinned so this list cannot quietly go stale: if the dialog ever names a
    // key literally, the "exactly one file" assertion above starts covering it
    // and this entry should go.
    const source = fs.readFileSync(path.join(process.cwd(), file), 'utf8');
    expect(source).toMatch(/dimensions\.\$\{[a-zA-Z.]+\}Tooltip/);
    for (const message of SPECIAL_CASE_MESSAGES) {
      expect(source).not.toContain(message);
    }
  });

  // NOT asserted here: that a dynamic reader passes the whole fact set. Two
  // source-greps used to claim it and verified neither word - `creditFactsFor`
  // appearing anywhere in the file satisfied both, and so would `t(tip, {})`.
  // Only rendering the component can know what it passes, so that guarantee
  // lives in PlanComparisonDialog.tooltips.test.tsx, which opens every tooltip
  // in all six locales and fails on next-intl's own FORMATTING_ERROR.
});

describe("the Free plan's AI allowance line (V494)", () => {
  const withAllowance = (aiCredits?: string) => planFeatureLabels('free', { ...DEPS, aiCredits });

  it('quotes the configured figure', () => {
    expect(withAllowance('250').some((l) => l.includes('features.aiCreditsFree') && l.includes('250')))
      .toBe(true);
  });

  it('DROPS the line when the plan grants none, instead of advertising zero', () => {
    // Setting included_ai_credits to 0 is how an admin closes the free tier. Rendering
    // "0 AI credits per month" would keep it on the card as a feature that gives
    // nothing, which is worse than saying nothing at all.
    const lines = withAllowance('0');

    expect(lines.some((l) => l.includes('aiCreditsFree'))).toBe(false);
    // ...and only that line: the rest of the Free card is untouched.
    expect(lines.some((l) => l.includes('features.creditsFree'))).toBe(true);
  });

  it('drops it for a formatted zero in any locale shape', () => {
    // The value arrives already locale-formatted, so the check must not depend on
    // which separator a locale would have used.
    expect(withAllowance('0,0').some((l) => l.includes('aiCreditsFree'))).toBe(false);
  });

  it('keeps a figure-free wording while the request is in flight', () => {
    // Undefined is "not answered yet", which is not "none": the line must stay, or a
    // card would gain and lose a bullet as the page settles.
    const lines = withAllowance(undefined);

    expect(lines.some((l) => l.includes('features.aiCreditsFreeUnknown'))).toBe(true);
  });

  it('keeps any non-zero figure, including one with a grouping separator', () => {
    expect(withAllowance('1,000').some((l) => l.includes('features.aiCreditsFree'))).toBe(true);
  });
});
