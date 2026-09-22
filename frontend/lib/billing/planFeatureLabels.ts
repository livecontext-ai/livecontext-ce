import { PLAN_FEATURE_KEYS } from '@/lib/billing/pricing-constants';

/**
 * One plan card's feature lines, as plain labels or as `"label||tooltip"`
 * strings for {@link FeatureLabel} to split into a label and an info "i".
 *
 * <p>ONE mapping, read by both surfaces that draw plan cards: the settings
 * pricing page and the public landing section. They each carried their own copy
 * of it, identical down to the comments, differing only in where the credit
 * figure came from. Nothing had diverged yet; what the duplication cost was
 * every edit twice, and this change is one of them: the credits line carried an
 * "i" on the free plan and on no other, so a paid plan quoted a figure with
 * nothing anywhere saying what it buys.
 *
 * <p>The `||` convention is the shared one across the landing pricing section,
 * this page and the insufficient-credits modal; see {@link FeatureLabel}.
 */
export interface PlanFeatureLabelDeps {
  /** Translator scoped to `pricing.planCards`. */
  tCards: (key: string, values?: Record<string, unknown>) => string;
  /**
   * Translator scoped to `pricing`, for the credits tooltip. That message lives
   * under `compare.dimensions` because the plan-comparison table owns it, and
   * the point of reading it from there is that the card and the comparison say
   * the SAME thing about what a month of credits buys.
   */
  tPricing: (key: string, values?: Record<string, unknown>) => string;
  /** The credit pack this card is currently showing, already locale-formatted. */
  credits: string;
  /**
   * What the credits tooltips interpolate: see `creditFactsFor`. Passed in
   * rather than computed here so both surfaces quote one set of figures.
   *
   * <p>Handed to EVERY credit-explaining tooltip, not only the paid-plan one:
   * each of them now states what a credit buys in the same words ("about N
   * credits for <the unit of work>"), so each of them may reach for any of the
   * facts, and a message that ignores one renders unchanged.
   */
  creditFacts: Record<string, string | number>;
  /**
   * The Free plan's monthly AI allowance (V494), already locale-formatted.
   *
   * <p>Read from the live plan rather than frozen into a translation: an admin
   * changes it with one UPDATE, and a card that had the old number baked in would
   * quietly advertise something the product no longer grants. Undefined while the
   * plans request is in flight, which is why the line falls back to a figure-free
   * wording instead of rendering "undefined credits".
   */
  aiCredits?: string;
}

/**
 * Whether a locale-formatted allowance means "this plan grants none".
 *
 * <p>The value arrives already formatted (the caller owns the app locale), so this
 * strips the formatting rather than guessing which separator a locale used, and only
 * then asks whether anything is left but zeros.
 */
function grantsNoAllowance(formatted?: string): boolean {
  if (formatted === undefined) return false;
  const digits = formatted.replace(/[^0-9]/g, '');
  return digits.length > 0 && /^0+$/.test(digits);
}

/** `label||tooltip`, the shape FeatureLabel splits on. */
function withTooltip(label: string, tooltip: string): string {
  return `${label}||${tooltip}`;
}

/**
 * The Free plan's credits tooltip, for a surface that builds its OWN card list and so
 * cannot call {@link planFeatureLabels}.
 *
 * <p>Exported rather than letting that surface name the message, because the message key
 * is special-cased here and `planFeatureLabels.test.ts` pins it to exactly one file. The
 * insufficient-credits dialog used to compose its own sentence for this pot by appending
 * the PAID tooltip to a local note, which said "chat and agents draw the separate
 * allowance instead" and then priced the pot with a short agent exchange, two sentences
 * apart. On FREE the monthly bucket funds only WORKFLOW_NODE, so the second half quoted
 * a price that pot cannot pay, to the one reader who has just run out of it.
 */
export function freeCreditsTooltip(
  tCards: PlanFeatureLabelDeps['tCards'],
  creditFacts: PlanFeatureLabelDeps['creditFacts'],
): string {
  return tCards('features.creditsFreeTooltip', creditFacts);
}

export function planFeatureLabels(planId: string, deps: PlanFeatureLabelDeps): string[] {
  const { tCards, tPricing, credits, creditFacts, aiCredits } = deps;
  const creditsTooltip = tPricing('compare.dimensions.creditsTooltip', creditFacts);

  return (PLAN_FEATURE_KEYS[planId] || [])
    // An allowance an admin has set to zero is how the free tier is CLOSED, so the
    // line is dropped rather than rendered as "0 AI credits per month" - a bullet
    // that advertises nothing while looking like a feature.
    .filter((key) => key !== 'aiCreditsFree' || !grantsNoAllowance(aiCredits))
    .map((key) => {
    switch (key) {
      // The paid plans' monthly pack. It is the line every visitor compares
      // plans on and the one nobody can price from its own words: "50,000
      // credits per month" says nothing about what 50,000 buys. The tooltip is
      // the comparison table's, verbatim, so the two surfaces cannot drift into
      // two different accounts of the same number.
      case 'creditsDynamic':
        return withTooltip(tCards('features.creditsPerMonth', { credits }), creditsTooltip);

      // Enterprise: no figure to show, same question to answer.
      case 'creditsCustom':
        return withTooltip(tCards('features.creditsCustom'), creditsTooltip);

      // Free monthly credits: same "i", different answer. They run workflows;
      // agents draw the separate AI allowance on the line below.
      case 'creditsFree':
        return withTooltip(tCards('features.creditsFree'),
                           freeCreditsTooltip(tCards, creditFacts));

      // The Free plan's separate monthly AI allowance (V494). Its own line, not a
      // clause on the credits line, because it is the answer to the question a
      // visitor actually arrives with: can I talk to an agent without paying? The
      // "i" carries the two conditions the number alone cannot: it is monthly, and
      // it only applies to the models opened to the free tier.
      case 'aiCreditsFree':
        return aiCredits
          ? withTooltip(tCards('features.aiCreditsFree', { credits: aiCredits }),
                        tCards('features.aiCreditsFreeTooltip', creditFacts))
          : withTooltip(tCards('features.aiCreditsFreeUnknown'),
                        tCards('features.aiCreditsFreeTooltip', creditFacts));

      // Managed integration credentials for cloud-linked self-hosted installs
      // (relay + per-call credit markup).
      case 'cePlatformCreds':
        return withTooltip(tCards('features.cePlatformCreds'), tCards('features.cePlatformCredsTooltip'));

      // Which integrations a plan unlocks. The line used to carry the brand
      // list inline ("(YouTube, Instagram, TikTok, X, LinkedIn...)"), which is
      // the longest thing on the card, dates the moment one is added, and reads
      // as an exhaustive promise it was never meant to be. The names moved into
      // the "i", where a list belongs and where it can say "and more".
      case 'nodesPublishing':
        return withTooltip(tCards('features.nodesPublishing'), tCards('features.nodesPublishingTooltip'));

      default:
        return tCards(`features.${key}`);
    }
  });
}

export default planFeatureLabels;
