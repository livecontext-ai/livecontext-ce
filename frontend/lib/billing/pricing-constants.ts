/**
 * Single source of truth for pricing constants and calculation logic.
 * Used by: pricing page, insufficient credits modal, billing tests.
 *
 * Mirrors CreditTierConstants.java; keep both in sync.
 *
 * Pricing curve revised 2026-05-27:
 * - Pro/Starter credit packs are degressive down to $0.70 / 1k credits.
 * - Team has explicit premium pack costs down to $0.80 / 1k credits.
 * - PAYG stays separate at $1.25 / 1k credits.
 * - Yearly discount applies to the base plan only, not to credits.
 */

/**
 * Public GitHub repository for the self-hosted Community Edition.
 * Single source of truth for the "Self-hosted" deployment CTA across every pricing
 * surface (landing, settings, insufficient-credits modal). The CE tree is published
 * by `export-ce.sh --push <giturl>`; update this constant to the final public repo URL.
 */
export const SELF_HOSTED_GITHUB_URL = 'https://github.com/livecontext-ai/livecontext-ce';

/**
 * Canonical credits→USD "list" scale: 1 credit = $0.001 USD. Defined backend-side
 * (ModelPricingService / migration V80) - calculateCost divides provider USD/1M-token
 * rates by 1000, so a stored credit amount IS the dollar cost × 1000. CE shows spend in
 * dollars (see lib/format-cost.ts), so credit-denominated ledger amounts - the local CE
 * ledger AND the cloud-linked relay mirror - are multiplied by this to render real dollars.
 *
 * NOTE: this is the list scale, NOT the per-pack purchase price (those vary $0.0008-$0.00125
 * per credit, see CREDIT_COSTS/CREDIT_TIERS), and the cloud LLM billing multiplier (×1.333333) is
 * already baked into the stored credit amount - so the displayed $ is the billed value,
 * margin included (the deliberate, balance-reconciling choice over stripping the multiplier).
 */
export const CREDIT_LIST_USD = 0.001;

/**
 * PAYG one-time top-up purchase price: $1.25 per 1,000 credits (see the header note).
 * Used to render the referral reward's dollar value from its configured credit amount.
 */
export const PAYG_USD_PER_1K = 1.25;

/**
 * THE MODEL EVERY PUBLISHED FIGURE ON THIS PAGE IS PRICED ON.
 *
 * <p>Until 2026-09-21 it was Claude Sonnet 5, a top-tier model, and the copy said so
 * ("lighter models cost several times less"). The page now quotes the LIGHTWEIGHT end
 * instead, which is a product decision about which reader the estimate is written for:
 * someone pricing a platform, not someone who has already chosen the dearest model in
 * the catalogue. It also INVERTS the direction the figures can be wrong in, so every
 * sentence quoting them now has to say "a top-tier model costs more" where it used to
 * say the opposite. A basis is only honest while the copy names it.
 *
 * <p>Rates are USD per 1M tokens, copied from ONE named row of the shipped model
 * catalogue: {@link PRICING_BASIS_MODEL_ID} as the {@link PRICING_BASIS_PROVIDER}
 * provider serves it. Two rows carry that model id and this is the DEARER one (the
 * other, on qwen, is 0.20 / 0.40): within a class the copy names by family, the page
 * must err upward.
 *
 * <p>THE ONE THING TO KNOW BEFORE EDITING THESE FOUR NUMBERS. They are a COPY of a row
 * this module cannot import (the catalogue is a backend resource), so the copy is the
 * failure mode: four literals that drift from the row they name publish a price
 * attributed by NAME to a model that costs something else, in six locales, one click
 * from a model picker that quotes the real rate for the same model from the live
 * catalogue. That is not hypothetical, it is how this constant shipped its first draft
 * (rates read off a production dump rather than the catalogue, every figure 21% low).
 * {@code margin-repricing.test.ts} now reads the catalogue file off disk and fails on
 * any drift, the same way it already reads the margin out of application.yml.
 *
 * <p>cacheWrite is the INPUT rate on purpose, and it is not a missing value: the row
 * publishes a cache-write price of zero. Only the Anthropic family is billed off a
 * cache-write counter; everywhere else a first send is plain prompt input, so a DeepSeek
 * model's published write price is never charged and quoting it would name a price
 * nobody pays. This mirrors ModelPricingService.cacheRateFallback
 * (modelCacheWritePriceApplies = false) and model-cost-estimate.ts#resolveCacheRates,
 * which is what makes the figures below comparable with the ones a picker quotes.
 */
export const PRICING_BASIS_RATES = {
  input: 0.44,
  output: 1.32,
  cacheRead: 0.014,
  cacheWrite: 0.44,
} as const;

/** The catalogue row {@link PRICING_BASIS_RATES} is copied from, and its provider. */
export const PRICING_BASIS_MODEL_ID = 'deepseek-v4-flash';
export const PRICING_BASIS_PROVIDER = 'deepseek';

/**
 * How the COPY names that row. A family name, not the catalogue id: the sentences say
 * "a lightweight model such as {basisModel}", which is an example of a class rather
 * than a model the reader is being told to select, and "deepseek-v4-flash" in a plan
 * card reads as a setting. Six locales quote this string, untranslated.
 */
export const PRICING_BASIS_MODEL = 'DeepSeek Flash';

/** One unit of work, in the four DISJOINT token classes the ledger prices. */
export interface TokenWorkload {
  input: number;
  cacheWrite: number;
  cacheRead: number;
  output: number;
}

/**
 * How many chat exchanges one agent conversation costs.
 *
 * <p>See the note on {@link CREDIT_WORKLOADS}: this is the one relation here that is a
 * derivation rather than a token measurement.
 */
const AGENT_CONVERSATION_IN_CHAT_EXCHANGES = 1.6;

const CHAT_EXCHANGE_WORKLOAD: TokenWorkload = {
  input: 10,
  cacheWrite: 39_600,
  cacheRead: 138_200,
  output: 3_400,
};

function scaleWorkload(workload: TokenWorkload, factor: number): TokenWorkload {
  return {
    input: Math.round(workload.input * factor),
    cacheWrite: Math.round(workload.cacheWrite * factor),
    cacheRead: Math.round(workload.cacheRead * factor),
    output: Math.round(workload.output * factor),
  };
}

/**
 * The unit of work behind every figure this page publishes, stated in TOKENS.
 *
 * <p>Tokens rather than credits because a token count is a MEASUREMENT and survives
 * every pricing decision: the margin lever has moved four times (1.8, 1.11, 2.0,
 * 1.333333) and the basis model once, and each of those used to be a hand re-price of
 * prose. Priced here instead, by one formula, against one declared basis.
 *
 * <p><b>chatExchange</b> mirrors LlmCostProfile.CHAT_CONVERSATION in auth-service,
 * token for token, and that is the whole point of the entry: it is the unit the MODEL
 * PICKERS quote, so the pricing page and the picker now name the same unit in the same
 * words ("a short exchange with a configured agent, its whole context included") and a
 * reader can carry one number between them. Its counts come from 62 Anthropic chat turns
 * measured over the 30 days to 2026-09-16, scaled by 1.6106 to describe a short exchange
 * rather than a single message; keep it in step with LlmCostProfile.
 *
 * <p><b>simpleChat</b> is five turns of the re-measured 2026-09-16 median (501
 * production chat turns with no tool call over 60 days, median 3,011 input and 76 output
 * tokens). No cache tokens: a tool-free turn is the shape that never builds a context
 * worth caching.
 *
 * <p><b>classifyStep</b> is LlmCostProfile.CLASSIFY_STEP, cache-free for the reason
 * stated there: a 1,200-token prompt sits under the floor where a cache entry is worth
 * writing at all.
 *
 * <p><b>agentChat is a DERIVATION, and the one figure here that is not a direct token
 * measurement.</b> Say so rather than let a reader assume otherwise. What was measured,
 * on 2026-09-03 and still the measurement of record, is its COST: across every real
 * conversation in which an agent called tools (n=310, 4.8 turns and 44 tool calls on
 * average) the median was 257 credits at a lever of 1.0 on Claude Sonnet 5, where the
 * chat exchange above costs 160.66 on that same basis. The ratio, 1.6, is therefore a
 * relation between two production medians taken on one basis, and it is what is carried
 * here: an agent conversation is defined as 1.6 chat exchanges. Re-pricing it onto
 * another model assumes the two share a token MIX, which is an approximation and not a
 * measurement; it is accepted because the alternative is to publish nothing for the unit
 * a reader most wants priced. Replace it the day agent conversations are measured in
 * tokens.
 */
export const CREDIT_WORKLOADS: Readonly<Record<string, TokenWorkload>> = {
  chatExchange: CHAT_EXCHANGE_WORKLOAD,
  agentChat: scaleWorkload(CHAT_EXCHANGE_WORKLOAD, AGENT_CONVERSATION_IN_CHAT_EXCHANGES),
  simpleChat: { input: 15_055, cacheWrite: 0, cacheRead: 0, output: 380 },
  classifyStep: { input: 1_200, cacheWrite: 0, cacheRead: 0, output: 60 },
};

/**
 * What the PROVIDER charges for one unit of each kind of work, in credits, before any
 * margin at all: {@link CREDIT_WORKLOADS} priced at {@link PRICING_BASIS_RATES}.
 *
 * <p>Credits are USD x 1000 (see {@link CREDIT_LIST_USD}) and rates are USD per 1M
 * tokens, so the whole conversion is a divide by 1,000. Stated at a lever of 1.0
 * precisely so it does not have to be restated when the lever moves, which is what every
 * previous re-price had to do to the prose.
 *
 * <p>It is what lets margin-repricing.test.ts RECOMPUTE every published figure from
 * auth-service's declared multiplier and fail when they have drifted, rather than merely
 * check that a bookkeeping constant was bumped, which is a reminder anyone can satisfy
 * without re-pricing anything.
 *
 * <p>The failure it guards is silent and it is a PRICE: nothing on this page reads the
 * server, so moving the margin and deploying leaves the page advertising the old figure
 * in six locales, with every other test green, while the ledger debits the new one.
 */
export const MEASURED_PROVIDER_COST: Readonly<Record<string, number>> = Object.fromEntries(
  Object.entries(CREDIT_WORKLOADS).map(([id, work]) => [
    id,
    (work.input * PRICING_BASIS_RATES.input
      + work.cacheWrite * PRICING_BASIS_RATES.cacheWrite
      + work.cacheRead * PRICING_BASIS_RATES.cacheRead
      + work.output * PRICING_BASIS_RATES.output) / 1_000,
  ]),
);

/**
 * THE figure the credit "i" quotes, everywhere one is shown: what a short exchange with
 * a configured agent costs, its whole context included, on the basis model above.
 *
 * <p>ONE unit and ONE number, which is the change of 2026-09-21. The plan-card and
 * comparison "i" carried a three-figure breakdown (a plain question, an agent build, a
 * classify step) that ran five lines in six locales and asked a reader to hold three
 * units apart before they had decided anything; the other two each priced a unit of
 * their own. All four now say the same sentence about the same unit, and the breakdown
 * stays where it earns its space: inside the FAQ answer that asks the question, with the
 * per-pack counts beside it.
 *
 * <p>Rounded UP from the priced median to something legible, so the page can never quote
 * a unit price BELOW the one the ledger will debit. margin-repricing.test.ts pins both
 * ends of that (never below the median, never more than 1.25x it).
 */
export const CHAT_EXCHANGE_CREDITS = 35;

/**
 * What ONE workflow node costs to run, in credits. Not an estimate: a flat, exact
 * price, which is why the copy that quotes it drops the "an estimate from real usage"
 * clause the LLM figures carry.
 *
 * <p>Mirrors {@code CreditService.consumeForWorkflowNode}, which debits
 * {@code BigDecimal.ONE} per node in both editions. It is here because it is the ONLY
 * unit the Free plan's monthly credits can pay for, and the plan card had been pricing
 * that pot with a classification step, which on FREE it cannot fund at all: the monthly
 * bucket is restricted to {@code WORKFLOW_NODE} / {@code WORKFLOW_NODE_PROMO}
 * ({@code CreditService.WORKFLOW_SUB_ELIGIBLE_SOURCE_TYPES}), while a classify step
 * bills as {@code CLASSIFY_EXECUTION} and draws the separate AI allowance, and the
 * add-ons (web search and fetch, image generation, platform markup) draw PAYG. A
 * tooltip that prices a pot with a debit the pot refuses is wrong in the expensive
 * direction: it reads as a promise.
 */
export const WORKFLOW_NODE_CREDITS = 1;

/**
 * The published units that run AS A WORKFLOW NODE, and therefore pay the flat node fee
 * on top of whatever LLM work they do.
 *
 * <p>A unit's provider cost is not its price. Every node a run executes pays
 * {@link WORKFLOW_NODE_CREDITS} (StepCompletionOrchestrator.consumeCreditForNode, cloud
 * only), and an agent node, classify included, ADDITIONALLY pays its tokens. So the
 * classify example, whose copy says "inside a running workflow", costs the fee plus the
 * LLM leg, and quoting the LLM leg alone under-priced it by the larger of the two: 0.81
 * credits of tokens against a 1-credit fee. It was a 12% understatement while the page
 * was priced on a top-tier model and became an 81% one the moment it moved to a
 * lightweight one, which is the shape of error that gets worse exactly when nobody is
 * looking at it again.
 *
 * <p>The chat units are NOT here: a chat turn and an agent conversation happen on the
 * chat surface and execute no workflow node, so no fee applies to them.
 */
const RUNS_AS_A_WORKFLOW_NODE = new Set(['classifyStep']);

/**
 * What one unit of {@code id} costs a user at {@code lever}: the provider cost marked up
 * by the margin, plus the flat platform fee where the unit is a node.
 *
 * <p>Exported because margin-repricing.test.ts checks every published figure against it
 * and the composition must not exist twice. The lever is a PARAMETER rather than a
 * constant here, which is the point of that test: it passes the value it read out of
 * auth-service's application.yml, so a margin move that nobody re-priced fails the build
 * instead of quietly re-pricing the ledger alone.
 */
export function pricedMedian(id: string, lever: number): number {
  const providerCost = MEASURED_PROVIDER_COST[id] ?? 0;
  return providerCost * lever + (RUNS_AS_A_WORKFLOW_NODE.has(id) ? WORKFLOW_NODE_CREDITS : 0);
}

export interface CreditExample {
  /** Labelled by pricing.faq.<CREDIT_EXAMPLES_FAQ_KEY>.examples.<id>. */
  id: string;
  /** Typical cost of one, in credits. */
  creditsEach: number;
  /** How many of them the entry pack covers, rounded down. */
  perEntryPack: number;
}

/**
 * The three units the FAQ's worked-examples block prices, and how many of each the entry
 * pack (CREDIT_TIERS[0]) covers.
 *
 * <p>WHY THREE FIGURES AND NOT ONE, here of all places. The cost of "a conversation" is
 * not one number: a plain question and answer sends the prompt once, while an agent
 * building a workflow re-sends the whole transcript on every tool round-trip, and it
 * makes dozens of them. Measured, the two differ by about 5x, and a classification step
 * is another 5x below the cheaper of them (28x below the agent one). A single average
 * would be true of almost no one. The classify gap USED to be two orders of magnitude
 * and is not any more: on a lightweight basis its flat per-node fee, which no margin
 * touches, is now most of its price.
 * That remains the right answer for a reader who has scrolled to the FAQ and asked the
 * question; it was the wrong answer for a tooltip on a plan card, which is what
 * {@link CHAT_EXCHANGE_CREDITS} now serves.
 *
 * <p>Each creditsEach is the priced median rounded UP to a legible number, so the unit
 * price is never understated, and each perEntryPack is 5,000 divided by that ROUNDED-UP
 * price, then rounded DOWN to a legible one. Both roundings go the same way on purpose,
 * because both errors are the same error: a page may under-claim what a pack buys and
 * may never over-claim it. Dividing by the unrounded median instead would let a count
 * exceed what the pack really pays for, which is the one direction this page must never
 * fail in. Rounded, never merely floored, because a page that promised "90.9
 * workflow-building conversations" would read as a measurement rather than the estimate
 * it is. margin-repricing.test.ts recomputes both roundings, and
 * credit-conversation-copy.test.ts pins the claim they produce (a round number, never
 * more than the pack, and never less than 60% of it).
 *
 * <p>WHAT THE COPY MAY CLAIM FOR agentChat. The unit is ONE agent CONVERSATION of the
 * measured shape, not one finished piece of work. The page names what such a
 * conversation is typically for (building a workflow) because that is what makes it the
 * expensive end and a reader recognises it. It must NOT promise a completed build for
 * that price: the copy states the measured shape (around 5 turns and 45 tool calls, the
 * measured 4.8 and 44 rounded to legible numbers and never DOWN, so the sentence cannot
 * understate the very driver it invokes) and says a longer build costs more.
 */
export const CREDIT_EXAMPLES: readonly CreditExample[] = [
  { id: 'simpleChat', creditsEach: 10, perEntryPack: 500 },
  { id: 'agentChat', creditsEach: 55, perEntryPack: 90 },
  { id: 'classifyStep', creditsEach: 2, perEntryPack: 2_500 },
];

/**
 * The range, low end first: the agent conversation is the floor, the plain one
 * the ceiling.
 *
 * <p>NO English message quotes these today. The credits tooltip used to divide
 * the entry pack by them and now states a per-exchange price instead, which is
 * the only phrasing true of a plan with no pack and no slider. They are still
 * supplied to every message that takes the credit facts, so a locale may phrase
 * an answer with the counts, and the FAQ figures are still derived from them
 * (see the invariants in credit-conversation-copy.test.ts).
 */
export const AGENT_CONVERSATIONS_PER_PACK = 90;
export const SIMPLE_CONVERSATIONS_PER_PACK = 500;

/**
 * The entry pack expressed the way a reader thinks about it, ready to
 * interpolate into any message that quotes it.
 *
 * <p>Lives here, next to the figures, because FOUR surfaces quote them and they
 * must never disagree: the pricing page's FAQ, the plan cards on that page, the
 * same cards on the public landing, and the plan-comparison table.
 *
 * <p>Pass the WHOLE object, never a hand-picked subset. Two surfaces used to
 * build their own, and the comparison table listed three values by name; when
 * the credits tooltip was rewritten to quote per-conversation prices, the table
 * started rendering its raw message path and throwing an IntlError on every
 * render, in all six locales, while the cards were fine. A message may use any
 * fact; a caller that offers only some decides for it.
 *
 * <p>Formatted against the APP locale, never the browser's, so a /fr visitor
 * reads "5 000" on the server-rendered landing and after hydration alike.
 * basisModel is the one fact here that is NOT locale-formatted: it is a product
 * name, and a translated model name would name a model nobody can select.
 *
 * <p>The facts a message may interpolate are exactly the keys returned here:
 * {credits}, {agentConversations}, {simpleConversations}, {agentCredits},
 * {simpleCredits}, {classifyCredits}, {exchangeCredits}, {nodeCredits} and
 * {basisModel}. credit-conversation-copy.test.ts fails on a message that invents one.
 */
export function creditFactsFor(locale: string): Record<string, string | number> {
  const creditsOf = (id: string) =>
    (CREDIT_EXAMPLES.find((example) => example.id === id)?.creditsEach ?? 0).toLocaleString(locale);
  return {
    credits: CREDIT_TIERS[0].toLocaleString(locale),
    agentConversations: AGENT_CONVERSATIONS_PER_PACK,
    simpleConversations: SIMPLE_CONVERSATIONS_PER_PACK,
    agentCredits: creditsOf('agentChat'),
    simpleCredits: creditsOf('simpleChat'),
    classifyCredits: creditsOf('classifyStep'),
    exchangeCredits: CHAT_EXCHANGE_CREDITS.toLocaleString(locale),
    nodeCredits: WORKFLOW_NODE_CREDITS.toLocaleString(locale),
    basisModel: PRICING_BASIS_MODEL,
  };
}

/**
 * The FAQ entry the worked examples are rendered under.
 *
 * They used to be a band of their own above the FAQ, which asked the reader the
 * same question twice and answered it in two places with two sets of words. The
 * figures now sit inside the answer, so the page states the estimate exactly
 * once. Its messages live at `pricing.faq.<key>.examplesCaption` and
 * `pricing.faq.<key>.examples.<exampleId>.{count,detail}`; moving the block to
 * another question means moving those keys in every locale file.
 */
export const CREDIT_EXAMPLES_FAQ_KEY = 'conversationCost';

/**
 * The pricing page's FAQ, in reading order. This list is the ONLY place an entry
 * is added, removed or reordered; each key needs `pricing.faq.<key>.question`
 * and `.answer` in EVERY locale file. Answers are rendered with the page's
 * credit facts, so any of them may interpolate any key {@link creditFactsFor}
 * returns without touching the component.
 */
export const FAQ_KEYS = [
  'conversationCost',
  'exceedCredits',
  'rollover',
  'changePlans',
  'payAsYouGo',
  'sharedStorage',
] as const;

export const CREDIT_TIERS = [5_000, 10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000, 5_000_000, 10_000_000];
export const CREDIT_COSTS = [0, 10, 22, 42, 80, 185, 365, 720, 3_500, 7_000];
export const TEAM_CREDIT_COSTS = [0, 15, 30, 55, 100, 230, 430, 825, 4_000, 8_000];

export const BASE_PRICES: Record<string, number> = {
  starter: 10,
  pro: 24,
  team: 49,
};

export const STARTER_MAX_CREDITS = 100_000;

/**
 * The Free plan's monthly AI allowance (V494): the separate pot that funds chat and
 * agent turns on the models opened to the free tier, so a visitor can try an agent
 * without topping up.
 *
 * Mirrors the `auth.plan.included_ai_credits` seed; keep both in sync, same contract
 * as CREDIT_COSTS mirroring CreditTierConstants.java. It is the FALLBACK only: any
 * surface that can read the live plan (the settings pricing page, via `usePlans`)
 * shows the configured value instead, because an admin can change it with one UPDATE
 * and a card must not keep advertising a number the product no longer grants.
 */
export const FREE_AI_CREDITS = 100;

/**
 * Highest tier index shown on the slider by default (index 7 = 1,000,000 credits).
 * The two tiers above (5M, 10M) carry intimidating prices for a casual visitor, so
 * they are hidden behind the `?tiers=full` unlock (see resolveMaxTierIndex). The
 * underlying CREDIT_TIERS array stays 10-long - the cap is display-only and the
 * backend (CreditTierConstants.java) keeps accepting indices 0-9.
 */
export const DEFAULT_MAX_TIER_INDEX = 7;

/**
 * Resolve the slider's max selectable index.
 * Full range (last index, 10M) when the hidden tiers are explicitly unlocked OR when
 * the user's current subscription already sits on a hidden tier - otherwise an existing
 * 5M/10M customer would be silently clamped down to 1M and could downgrade by accident.
 */
export function resolveMaxTierIndex(fullTiersUnlocked: boolean, subscriptionTierIndex = 0): number {
  if (fullTiersUnlocked || subscriptionTierIndex > DEFAULT_MAX_TIER_INDEX) {
    return CREDIT_TIERS.length - 1;
  }
  return DEFAULT_MAX_TIER_INDEX;
}

/**
 * Clamp a selected tier index into [0, maxTierIndex]. Used to keep the selected index in
 * sync with the slider cap: when the cap shrinks (e.g. the hidden tiers get re-hidden while
 * the user is parked on 5M/10M), the index must follow so the price/checkout never reflect a
 * tier the slider no longer shows.
 */
export function clampTierIndex(tierIndex: number, maxTierIndex: number): number {
  return Math.min(Math.max(tierIndex, 0), maxTierIndex);
}

/**
 * Ordered feature-label keys per plan card. Each key maps to a single i18n string at
 * `pricing.planCards.features.<key>` (shared keys are translated once). The sentinel
 * 'creditsDynamic' is rendered in the component with the live slider amount.
 *
 * Lists are authored as explicit supersets: every tier visibly includes everything the
 * tier below it offers, so Enterprise never appears to have fewer features than Team.
 * The coherence is enforced by a unit test via CAPABILITY_KEYS.
 *
 * That applies to the SCALED dimensions too, and Enterprise carried no support key at
 * all until 2026-08-31: on a card the omission merely showed one bullet fewer, but read
 * across plans (see plan-comparison.ts) it stated that Enterprise includes no support
 * while Team includes support with an SLA. 'supportSla' is therefore its floor, on top
 * of which 'sla999' and 'accountManager' are what Enterprise adds. `plan-comparison`
 * pins the invariant: no dimension may go blank on a plan above one that has a value.
 *
 * The `nodes*` keys are a REPLACED dimension, like support and analytics: each tier
 * states its own node coverage rather than adding to the one below, so they are
 * deliberately absent from CAPABILITY_KEYS.
 */
export const PLAN_FEATURE_KEYS: Record<string, string[]> = {
  free: ['creditsFree', 'aiCreditsFree', 'nodesCore', 'users1', 'workspaces1', 'variables3', 'concurrent1', 'storage100mb', 'logs7', 'supportCommunity'],
  starter: ['creditsDynamic', 'nodesPublishing', 'users1', 'workspaces1', 'variables25', 'concurrent5', 'storage1gb', 'logs30', 'versioning', 'apiAccess', 'cePlatformCreds', 'analyticsBasic', 'supportEmail'],
  pro: ['creditsDynamic', 'nodesAll', 'users1', 'workspaces3', 'variables100', 'concurrent20', 'storage10gb', 'logs30', 'versioning', 'apiAccess', 'cePlatformCreds', 'vectorSearch', 'browserAgent', 'ownLlmKey', 'priorityExecution', 'executionSearch', 'analyticsDetailed', 'supportPriority'],
  team: ['creditsDynamic', 'nodesAll', 'users25', 'workspaces10', 'variables500', 'concurrent50', 'storage100gb', 'logs90', 'versioning', 'apiAccess', 'cePlatformCreds', 'vectorSearch', 'browserAgent', 'ownLlmKey', 'priorityExecution', 'executionSearch', 'sso', 'rbac', 'auditLogs', 'sharedTemplates', 'centralizedBilling', 'analyticsTeam', 'supportSla'],
  enterprise: ['creditsCustom', 'nodesAll', 'usersUnlimited', 'workspacesUnlimited', 'variablesUnlimited', 'concurrentUnlimited', 'storage1tb', 'logsCustom', 'versioning', 'apiAccess', 'cePlatformCreds', 'vectorSearch', 'browserAgent', 'ownLlmKey', 'priorityExecution', 'executionSearch', 'sso', 'rbac', 'auditLogs', 'sharedTemplates', 'centralizedBilling', 'dedicatedInstance', 'compliance', 'overageProtection', 'analyticsAdvanced', 'supportSla', 'sla999', 'accountManager', 'onboarding'],
};

/**
 * Purely-additive capability keys (excludes value-scaled dimensions such as
 * credits/users/concurrent/storage/logs and the replaced support/analytics dims).
 * Used by the coherence test: each tier must include every capability of the tier below.
 */
export const CAPABILITY_KEYS = [
  'versioning', 'apiAccess', 'cePlatformCreds', 'vectorSearch', 'browserAgent', 'ownLlmKey',
  'priorityExecution', 'executionSearch',
  'sso', 'rbac', 'auditLogs', 'sharedTemplates', 'centralizedBilling',
  'dedicatedInstance', 'compliance', 'overageProtection', 'accountManager', 'onboarding',
];

export function getCreditCost(planId: string, creditTierIndex: number): number {
  return planId === 'team'
    ? TEAM_CREDIT_COSTS[creditTierIndex]
    : CREDIT_COSTS[creditTierIndex];
}

/**
 * Yearly-cycle multiplier, applied to the BASE plan price only and never to credits.
 * Exported so a pricing event composes its announced future price through exactly the
 * same formula as the current billed price (see priceFromBase).
 */
export const YEARLY_BASE_MULTIPLIER = 0.8;

/**
 * Compose a displayed monthly price from an arbitrary base price.
 *
 * Shared by calcPrice (the price actually billed today) and by the pricing-event
 * announcement (the higher price a plan moves to once the event window closes), so the
 * two can never drift apart on the yearly discount or on how credits are added.
 */
export function priceFromBase(
  base: number,
  planId: string,
  cycle: 'monthly' | 'yearly',
  creditTierIndex: number
): number {
  const basePrice = cycle === 'yearly' ? Math.round(base * YEARLY_BASE_MULTIPLIER) : base;
  return basePrice + getCreditCost(planId, creditTierIndex);
}

export function calcPrice(planId: string, cycle: 'monthly' | 'yearly', creditTierIndex: number): number {
  const base = BASE_PRICES[planId];
  if (base === undefined) return 0;
  return priceFromBase(base, planId, cycle, creditTierIndex);
}

export function formatTierLabel(tier: number): string {
  if (tier >= 1_000_000) return `${tier / 1_000_000}M`;
  if (tier >= 1_000) return `${tier / 1_000}K`;
  return String(tier);
}
