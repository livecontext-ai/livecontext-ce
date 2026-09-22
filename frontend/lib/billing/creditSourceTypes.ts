/**
 * What each kind of credit-ledger row is called, and which ones the reader can filter by.
 *
 * <p><b>Why this is one module and not two lists.</b> The quota page renders the history table and
 * the usage chart side by side, from the same ledger, and each had its own copy of this map. They
 * drifted, as two copies of one fact do: the table said "Platform API call" where the chart under
 * it said {@code PLATFORM_MARKUP}, so the row a reader was looking at and the series it belonged to
 * did not share a name. Every kind of spend now has exactly one name on that page.
 *
 * <p>The keys are paths inside the {@code quota} translation namespace, which both surfaces already
 * bind, so a caller passes its own translator and nothing here needs one.
 *
 * <p>WORKFLOW_RUN is deliberately absent from both: there is no per-run debit, billing happens at
 * the WORKFLOW_NODE granularity, so it would be a filter that always returns zero rows.
 */

/**
 * Label key per ledger source type. A type absent from this map renders as its raw wire name, which
 * is deliberate: a new kind of spend showing up as {@code SOMETHING_NEW} is legible, and it says
 * plainly that a label is missing.
 */
export const CREDIT_SOURCE_LABEL_KEYS = {
  AGENT_EXECUTION: 'types.agent',
  WORKFLOW_NODE: 'types.workflowNode',
  // Launch promo: nodes run free (0 credits) and are logged as a distinct 0-cost source type so
  // history rows stay clearly labeled "Workflow node (free)".
  WORKFLOW_NODE_PROMO: 'types.workflowNodePromo',
  CHAT_CONVERSATION: 'types.chat',
  // Cloud-linked CE: every relayed LLM call is billed cloud-side under this single source type (the
  // cloud collapses chat/agent/workflow origin into one). It only ever appears in the
  // cloud-mirrored view, never the local CE ledger.
  CE_LLM_RELAY: 'types.cloudRelay',
  CLASSIFY_EXECUTION: 'types.classify',
  GUARDRAIL_EXECUTION: 'types.guardrail',
  // Browser-agent runs: LLM-driven Chromium sessions surfaced separately from chat-agent /
  // classify / guardrail because the cost profile is different (visual context tokens dominate,
  // multi-minute wall clock).
  BROWSER_AGENT_EXECUTION: 'types.browserAgent',
  // COLD-summary calls charged via AgentObservabilityService. Segregated from AGENT_EXECUTION so
  // users can see compaction cost separately.
  COMPACTION_SUMMARY: 'types.compactionSummary',
  // Web tools - search and fetch are billed as separate source types so they can be filtered
  // independently.
  WEB_SEARCH: 'types.webSearch',
  WEB_FETCH: 'types.webFetch',
  // V148+ unified markup billing. Replaces IMAGE_GENERATION / IMAGE_GENERATION_BYOK for new tool
  // calls. Released states surface to users so they understand when a reservation was returned
  // (failed call, partial result, sweeper auto-release).
  PLATFORM_MARKUP: 'types.platformMarkup',
  PLATFORM_MARKUP_RELEASED: 'types.platformMarkupReleased',
  PLATFORM_MARKUP_RELEASED_TIMEOUT: 'types.platformMarkupReleasedTimeout',
  // Legacy display labels - no longer written, kept for historical row rendering.
  IMAGE_GENERATION: 'types.imageGeneration',
  IMAGE_GENERATION_BYOK: 'types.imageGenerationByok',
  PURCHASE: 'types.purchase',
  PLAN_GRANT: 'types.planGrant',
  PLAN_RESET: 'types.planReset',
  // Money moving the OTHER way, and one debit that is not a tool call at all. Each is written
  // today and lands in the same history table, and each was rendering as its own wire name in
  // front of a reader: an account top-up read PAYG_TOPUP, a referral bonus read REWARD_REFERRAL.
  // They arrived here by asking the question the web-tools gap should have been caught by - what
  // else does this table show that nobody named - rather than by waiting for the next report.
  MARKETPLACE_PURCHASE: 'types.marketplacePurchase',
  PAYG_TOPUP: 'types.paygTopup',
  REWARD_REFERRAL: 'types.rewardReferral',
  REWARD_CLAWBACK: 'types.rewardClawback',
  MANUAL_ADJUSTMENT: 'types.manualAdjustment',
  // `as const` so a mistyped key is a compile error rather than an `undefined` handed to the
  // translator, which throws at render: three surfaces read this map by NAME
  // (CREDIT_SOURCE_LABEL_KEYS.WORKFLOW_NODE and friends) and a typo there used to type-check.
} as const satisfies Record<string, string>;

/**
 * One kind of row this map deliberately does NOT cover: the refusal audit.
 *
 * <p>A pre-flight refusal writes {@code <sourceType>_REJECTED}, so its name is composed at write
 * time from a type that is itself in this map. Enumerating the products would double the map and
 * still miss the next source type; naming them properly needs a rule rather than a lookup, which
 * is a change to how both surfaces resolve a label. Until then such a row renders as its wire name,
 * which is legible and rare (it is written only when a charge was REFUSED, so nothing was spent).
 */

/**
 * Source types offered in the history filter.
 *
 * <p>Distinct from {@link CREDIT_SOURCE_LABEL_KEYS} so legacy rows still render a label without
 * being offered as a filter: only {@code PLATFORM_MARKUP*} is written for tool calls now, and a
 * dropdown entry that can only ever return an empty page is a dead end.
 *
 * <p><b>Every CURRENT kind of spend belongs here.</b> Web search and web fetch were billed as their
 * own types precisely so they could be filtered, and were then left out of this list: the rows
 * existed, the labels existed, and the one control that would isolate them did not offer them. The
 * rule for adding a source type is therefore: if a row of that type can be written today and costs
 * credits, it goes in this list at the same time as its label.
 */
export const CREDIT_SOURCE_FILTERS: readonly string[] = [
  'AGENT_EXECUTION',
  'WORKFLOW_NODE',
  'CHAT_CONVERSATION',
  'CLASSIFY_EXECUTION',
  'GUARDRAIL_EXECUTION',
  'BROWSER_AGENT_EXECUTION',
  'COMPACTION_SUMMARY',
  'WEB_SEARCH',
  'WEB_FETCH',
  'PLATFORM_MARKUP',
  // A reservation that was returned: the call failed, produced less than it reserved, or was swept
  // away. They are refunds rather than spend, and they are in the table, so a reader reconciling a
  // charge against what came back needs to be able to isolate them.
  'PLATFORM_MARKUP_RELEASED',
  'PLATFORM_MARKUP_RELEASED_TIMEOUT',
  // A node the launch promo made free. Zero credits, and still a row a reader can be looking for -
  // "how many did I get for nothing" is the question it exists to answer.
  'WORKFLOW_NODE_PROMO',
  // Only ever present on a cloud-linked self-hosted install, where it is EVERY relayed LLM call
  // collapsed into one type - so on the one install that has these rows they are most of the
  // spend, and leaving them out repeated the web-tools omission on the readers least able to
  // reconstruct the total from elsewhere.
  'CE_LLM_RELAY',
  'PURCHASE',
  // PLAN_GRANT is deliberately absent: nothing in the platform writes it, so it would be a control
  // that can only ever come back empty. Its LABEL is kept, because a historical row may carry it.
  'PLAN_RESET',
  'MARKETPLACE_PURCHASE',
  'PAYG_TOPUP',
  'REWARD_REFERRAL',
  'REWARD_CLAWBACK',
  'MANUAL_ADJUSTMENT',
];

/**
 * The same list, for a ledger that is the install's OWN.
 *
 * <p>{@code CE_LLM_RELAY} is written on the CLOUD account of a linked install and reaches a
 * self-hosted reader only through the cloud-mirrored view. The self-hosted history filter is
 * rendered only when that mirror is NOT what is being read, so offering the option there is a
 * control that can only ever return nothing: the dead end this module's own rule forbids. Every
 * other type is unchanged, and the cloud page keeps the full list, where those rows do exist.
 */
export const CREDIT_SOURCE_FILTERS_LOCAL_LEDGER: readonly string[] =
  CREDIT_SOURCE_FILTERS.filter((type) => type !== 'CE_LLM_RELAY');
