package com.apimarketplace.auth.service;

import com.apimarketplace.agent.domain.TokenUsageConventions;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.repository.ModelPricingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * The margin is a NUMBER, not a range: every agent surface must bill the same multiple of
 * what the provider charged.
 *
 * <p>Before this was true, it was not. Measured on production over one day, the applied
 * multiplier ran from 1.11x to 3.97x depending on which surface a turn came from and
 * which model it named, while the configured lever said 1.11 throughout. An agent
 * running every 30 minutes on {@code anthropic/claude-fable-5} through a model execution
 * link was billed 3.47x its cost, and nobody chose that.
 *
 * <p><b>Every LLM billing row in the platform is one of eight source types</b>, all of
 * them arriving at {@code CreditController#consume}. This test walks them, using for each
 * the token counts that surface actually delivers - which is the whole difficulty, since
 * a Claude Code bridge folds the cache into its prompt total, the browser runner folds
 * only the reads, and the Anthropic API folds neither. A surface is correct when the bill
 * it produces is exactly {@code cloudMultiplier} times what the model's own published
 * prices say the tokens cost.
 *
 * <p><b>What this class pins, and what it does not.</b> It is an ARITHMETIC pin: for each
 * surface it builds the token shape that surface delivers, puts it through the real
 * {@link ModelPricingService}, and asserts the bill is exactly the multiplier. It does not
 * import a single surface class, so it cannot prove that {@code ClassifyService} or
 * {@code JsonCompletionService} actually calls the conversion - that is proved where those
 * services are exercised ({@code ClassifyServiceTest}, {@code GuardrailServiceTest},
 * {@code JsonCompletionServiceTest}, {@code AgentNodeTest}). What it catches is the thing
 * those tests cannot see: a rate, a family weight or the multiplier itself moving, and one
 * surface drifting off the number while the others stay on it. Several entries below
 * therefore compute the same converted usage, on purpose: they are the same arithmetic
 * reached from different surfaces, and the day a new convention is added they stop being
 * the same.
 *
 * <p>Every surface that charges for tokens is in the first section and lands on the same
 * multiple. The second section holds the one that charges NOTHING by design, a CLI session
 * where the external tool pays its own provider, so that making it billable has to come
 * back here and say so.
 *
 * <p>Classify, guardrail and the COLD summariser used to sit in a third section, pinned at
 * the wrong figure with the reason and the magnitude written next to it. They are billed
 * correctly now, and the pattern is worth remembering: an assertion that says "this is
 * wrong, by this much, for this reason" fails the day someone fixes it and tells them
 * where to come, where a missing assertion lets the gap widen unnoticed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Every agent surface bills the same multiple of provider cost")
class AgentSurfaceBillingMarginTest {

    @Mock
    private ModelPricingRepository pricingRepository;

    /** The margin the platform sells at. One number, for every surface. */
    private static final BigDecimal MULTIPLIER = new BigDecimal("1.333333");

    /**
     * The lever at four decimals, for the ONE assertion that cannot resolve it exactly.
     *
     * <p>Every surface below recovers its multiple by DIVIDING a billed cost by the
     * provider cost, and nine of the ten land on the lever exactly at six decimals - so
     * they compare against {@link #MULTIPLIER} itself and would catch a surface applying
     * 1.3333 where it should apply 1.333333. The tenth prices a model with no stored
     * cache rate, where the family weights are applied before the margin and the extra
     * rounding puts the quotient a few millionths off; blunting all ten to protect that
     * one would give away the resolution three comments in this change call load-bearing.
     */
    private static final BigDecimal BILLED_MULTIPLE = MULTIPLIER.setScale(4, RoundingMode.HALF_UP);

    // claude-fable-5-1 as the production catalog prices it, USD per 1M tokens. Chosen
    // because its cache read (0.025x input) is the furthest from the 0.1x family
    // constant of any Anthropic model, so a surface that falls back to the family
    // weights shows up here as a 4x error rather than rounding noise.
    private static final String MODEL = "claude-fable-5-1";
    private static final BigDecimal IN = new BigDecimal("10.0");
    private static final BigDecimal OUT = new BigDecimal("50.0");
    private static final BigDecimal CACHE_READ = new BigDecimal("0.25");
    private static final BigDecimal CACHE_WRITE = new BigDecimal("12.50");

    // One turn, in its canonical breakdown. Shape taken from a real production ledger
    // row: a long-running scheduled agent, 80% of its context served from cache.
    private static final int PLAIN_INPUT = 6;
    private static final int CACHE_WRITE_TOKENS = 18_945;
    private static final int CACHE_READ_TOKENS = 79_368;
    private static final int COMPLETION = 1_915;

    private ModelPricingService pricing;

    @BeforeEach
    void setUp() {
        ModelPricing row = new ModelPricing();
        row.setProvider("anthropic");
        row.setModel(MODEL);
        row.setInputRate(IN);
        row.setOutputRate(OUT);
        row.setCacheReadRate(CACHE_READ);
        row.setCacheWriteRate(CACHE_WRITE);
        row.setFixedCost(BigDecimal.ZERO);
        // The bridge row carries the same list price - since V130 a bridge is billed at
        // the underlying cloud model's rate, which is what makes a link's relabel a
        // pricing no-op and the convention the only thing that can go wrong.
        ModelPricing bridgeRow = new ModelPricing();
        bridgeRow.setProvider("claude-code");
        bridgeRow.setModel(MODEL);
        bridgeRow.setInputRate(IN);
        bridgeRow.setOutputRate(OUT);
        bridgeRow.setCacheReadRate(CACHE_READ);
        bridgeRow.setCacheWriteRate(CACHE_WRITE);
        bridgeRow.setFixedCost(BigDecimal.ZERO);

        lenient().when(pricingRepository.findCurrentPricing("anthropic", MODEL))
                .thenReturn(Optional.of(row));
        lenient().when(pricingRepository.findCurrentPricing("claude-code", MODEL))
                .thenReturn(Optional.of(bridgeRow));
        pricing = new ModelPricingService(pricingRepository, MULTIPLIER);
    }

    /** What the provider actually charged for this turn, in credits (1 credit = $0.001). */
    private static BigDecimal providerCost() {
        return IN.multiply(BigDecimal.valueOf(PLAIN_INPUT))
                .add(CACHE_WRITE.multiply(BigDecimal.valueOf(CACHE_WRITE_TOKENS)))
                .add(CACHE_READ.multiply(BigDecimal.valueOf(CACHE_READ_TOKENS)))
                .add(OUT.multiply(BigDecimal.valueOf(COMPLETION)))
                .divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP);
    }

    /** The multiple of provider cost a surface's counts end up billing. */
    private BigDecimal appliedMultiplierFor(String billedProvider, LlmTokenBreakdown usage) {
        return pricing.calculateCost(billedProvider, MODEL, usage)
                .divide(providerCost(), 6, RoundingMode.HALF_UP);
    }

    /** The usage as the Claude Code bridge reports it: prompt total contains the cache. */
    private static UsageInfo asBridgeReports() {
        return UsageInfo.builder()
                .promptTokens(PLAIN_INPUT + CACHE_WRITE_TOKENS + CACHE_READ_TOKENS)
                .completionTokens(COMPLETION)
                .totalTokens(PLAIN_INPUT + CACHE_WRITE_TOKENS + CACHE_READ_TOKENS + COMPLETION)
                .cacheCreationInputTokens(CACHE_WRITE_TOKENS)
                .cacheReadInputTokens(CACHE_READ_TOKENS)
                .build();
    }

    private static LlmTokenBreakdown breakdownOf(UsageInfo u) {
        return new LlmTokenBreakdown(
                u.promptTokens() == null ? 0 : u.promptTokens(),
                u.completionTokens() == null ? 0 : u.completionTokens(),
                u.cacheCreationInputTokens() == null ? 0 : u.cacheCreationInputTokens(),
                u.cacheReadInputTokens() == null ? 0 : u.cacheReadInputTokens(),
                u.cachedTokens() == null ? 0 : u.cachedTokens(),
                0);
    }

    @Nested
    @DisplayName("Surfaces that bill the margin exactly")
    class Correct {

        @Test
        @DisplayName("AGENT_EXECUTION, workflow agent node - a direct run on the Anthropic API")
        void workflowAgentDirect() {
            // AgentNode reads AgentExecutionResponseDto.totalUsage and forwards all four
            // cache counters (AgentNode:2518-2524). Unlinked, the numbers arrive in the
            // Anthropic API's own convention.
            LlmTokenBreakdown usage = new LlmTokenBreakdown(
                    PLAIN_INPUT, COMPLETION, CACHE_WRITE_TOKENS, CACHE_READ_TOKENS, 0, 0);

            assertThat(appliedMultiplierFor("anthropic", usage)).isEqualByComparingTo(MULTIPLIER);
        }

        @Test
        @DisplayName("AGENT_EXECUTION, workflow agent node - the SAME run moved onto a bridge by an execution link")
        void workflowAgentThroughAnExecutionLink() {
            // This is the shape that was billed 3.47x in production: the bridge's inclusive
            // prompt total under an Anthropic label. withBilledIdentity converts it.
            UsageInfo converted = TokenUsageConventions.toBilledConvention(
                    asBridgeReports(), "claude-code", "anthropic");

            assertThat(appliedMultiplierFor("anthropic", breakdownOf(converted)))
                    .isEqualByComparingTo(MULTIPLIER);
        }

        @Test
        @DisplayName("AGENT_EXECUTION, delegated sub-agent through a link - converted by convertBridgeResponse")
        void subAgentThroughAnExecutionLink() {
            // A sub-agent's row is stamped with the ENTITY's provider, so a link makes the
            // same mismatch. SubAgentExecutionHandler converts on the same helper.
            UsageInfo converted = TokenUsageConventions.toBilledConvention(
                    asBridgeReports(), "claude-code", "anthropic");

            assertThat(appliedMultiplierFor("anthropic", breakdownOf(converted)))
                    .isEqualByComparingTo(MULTIPLIER);
        }

        @Test
        @DisplayName("CHAT_CONVERSATION - a chat turn on a billed pair, which reaches the bridge only through agent-service")
        void chatConversation() {
            // conversation-service posts to the bridge ITSELF only when the agent's own
            // provider IS a bridge (ConversationAgentService.isBridgeProvider). A
            // Fable 5.1 agent is anthropic, so it goes through agent-service and the link
            // conversion applies, then AgentObservabilityClient forwards all four counters.
            UsageInfo converted = TokenUsageConventions.toBilledConvention(
                    asBridgeReports(), "claude-code", "anthropic");

            assertThat(appliedMultiplierFor("anthropic", breakdownOf(converted)))
                    .isEqualByComparingTo(MULTIPLIER);
        }

        @Test
        @DisplayName("AGENT_EXECUTION - a bridge CHOSEN directly bills the same multiple, which is what makes the link a pricing no-op")
        void bridgeChosenDirectly() {
            // No relabel, no conversion: the inclusive total is billed by ANTHROPIC_CLI,
            // which subtracts it back out. The point of the assertion is that this and the
            // linked case land on the SAME number - in production they were 1.11x and 3.47x.
            assertThat(appliedMultiplierFor("claude-code", breakdownOf(asBridgeReports())))
                    .isEqualByComparingTo(MULTIPLIER);
        }

        @Test
        @DisplayName("BROWSER_AGENT_EXECUTION - the runner's prompt total includes its cache reads and nothing else")
        void browserAgent() {
            // browser-use normalises to "tokens_in contains the reads, cache_creation is
            // disjoint", which matches no billing convention; the node converts it.
            long tokensIn = PLAIN_INPUT + CACHE_READ_TOKENS;
            TokenUsageConventions.InputCounts counts = TokenUsageConventions
                    .fromPromptIncludingCacheReads(tokensIn, CACHE_READ_TOKENS, CACHE_WRITE_TOKENS, "anthropic");

            LlmTokenBreakdown usage = new LlmTokenBreakdown(
                    (int) counts.promptTokens(), COMPLETION,
                    (int) counts.cacheCreationTokens(), (int) counts.cacheReadTokens(),
                    (int) counts.cachedTokens(), 0);

            assertThat(appliedMultiplierFor("anthropic", usage)).isEqualByComparingTo(MULTIPLIER);
        }

        @Test
        @DisplayName("COMPACTION_SUMMARY - a COLD summary now bills the margin like any other turn")
        void compactionSummaryBillsTheMargin() {
            // It used to bill nothing: the source type was allow-listed in billing and
            // resolved by observability, ColdSummarizerService's javadoc said it wrote a
            // row, and none of that mattered because the invoker discarded the usage before
            // anyone could see it. Nine summaries ran in production and charged zero.
            // JsonCompletionService now reads the counts off the completion and records the
            // row, converting the convention first when a link moved the run onto a bridge.
            // That it converts is asserted in JsonCompletionServiceTest; what is asserted
            // here is the bill the converted counts produce.
            UsageInfo converted = TokenUsageConventions.toBilledConvention(
                    asBridgeReports(), "claude-code", "anthropic");

            assertThat(appliedMultiplierFor("anthropic", breakdownOf(converted)))
                    .isEqualByComparingTo(MULTIPLIER);
        }

        @Test
        @DisplayName("CLASSIFY_EXECUTION and GUARDRAIL_EXECUTION - billed from a DTO that now carries the cache")
        void classifyAndGuardrail() {
            // These two were the last surface off the margin, in both directions: over a
            // model execution link the bridge's inclusive prompt total was charged at full
            // input rate (6.1x its cost, measured), and without one the cache was free -
            // the same missing transport. ClassifyResponseDto / GuardrailResponseDto now
            // carry cacheUsage, ClassifyResult / GuardrailResult mirror it, and both
            // orchestrator fill sites read it. Converting without carrying was tried first
            // and is worse: with nowhere to put the cache it leaves the bill entirely.
            // That the services convert and the fill sites read it is asserted in
            // ClassifyServiceTest, GuardrailServiceTest and AgentNodeTest; what is asserted
            // here is the bill those converted counts produce.
            UsageInfo converted = TokenUsageConventions.toBilledConvention(
                    asBridgeReports(), "claude-code", "anthropic");

            assertThat(appliedMultiplierFor("anthropic", breakdownOf(converted)))
                    .isEqualByComparingTo(MULTIPLIER);
        }

        @Test
        @DisplayName("CE_LLM_RELAY - a self-hosted install relaying through the cloud reports direct-API semantics")
        void ceLlmRelay() {
            // CloudLlmRelayController reads a CompletionResponse from the direct provider,
            // so its counts are already in the Anthropic API's convention. No link, no
            // bridge, no conversion.
            LlmTokenBreakdown usage = new LlmTokenBreakdown(
                    PLAIN_INPUT, COMPLETION, CACHE_WRITE_TOKENS, CACHE_READ_TOKENS, 0, 0);

            assertThat(appliedMultiplierFor("anthropic", usage)).isEqualByComparingTo(MULTIPLIER);
        }
    }

    @Nested
    @DisplayName("Surfaces that deliberately bill nothing")
    class KnownGaps {

        @Test
        @DisplayName("CLI_SESSION bills zero on purpose - the external CLI pays its own provider")
        void cliSessionBillsNothing() {
            // CliAgentService stamps provider "external" and never sets a token count, so
            // the row is an audit trail rather than a charge. This zero is a decision, not
            // a gap: a surface that starts charging has to leave this section to do it.
            LlmTokenBreakdown noTokens = new LlmTokenBreakdown(0, 0, 0, 0, 0, 0);

            assertThat(pricing.calculateCost("external", "claude-code", noTokens))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("The margin itself")
    class TheMargin {

        @Test
        @DisplayName("1.333333x is 25% gross margin, and the surfaces above all land on it")
        void multiplierIsTwentyFivePercent() {
            BigDecimal margin = BigDecimal.ONE
                    .subtract(BigDecimal.ONE.divide(MULTIPLIER, 6, RoundingMode.HALF_UP))
                    .multiply(new BigDecimal("100"));

            // Six 3s, not four: this reads the margin back as 1 - 1/m at six decimal
            // places, and 1.3333 would land on 24.9981% instead of the figure the
            // platform publishes. The spelling of the constant is load-bearing.
            assertThat(margin).isEqualByComparingTo(new BigDecimal("25.0"));
        }

        @Test
        @DisplayName("a model with NO stored cache price still bills the margin against the family fallback, so a new vendor is never off by more than the fallback's own error")
        void fallbackStillBillsTheMultiplier() {
            ModelPricing unpriced = new ModelPricing();
            unpriced.setProvider("anthropic");
            unpriced.setModel("brand-new-model");
            unpriced.setInputRate(IN);
            unpriced.setOutputRate(OUT);
            unpriced.setFixedCost(BigDecimal.ZERO);
            lenient().when(pricingRepository.findCurrentPricing("anthropic", "brand-new-model"))
                    .thenReturn(Optional.of(unpriced));

            LlmTokenBreakdown usage = new LlmTokenBreakdown(
                    PLAIN_INPUT, COMPLETION, CACHE_WRITE_TOKENS, CACHE_READ_TOKENS, 0, 0);

            // Cost basis computed on the FAMILY weights, which is what the fallback means.
            BigDecimal fallbackCost = IN.multiply(BigDecimal.valueOf(PLAIN_INPUT))
                    .add(IN.multiply(new BigDecimal("1.25")).multiply(BigDecimal.valueOf(CACHE_WRITE_TOKENS)))
                    .add(IN.multiply(new BigDecimal("0.1")).multiply(BigDecimal.valueOf(CACHE_READ_TOKENS)))
                    .add(OUT.multiply(BigDecimal.valueOf(COMPLETION)))
                    .divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP);

            // Four decimals here and six everywhere else, deliberately: this path applies
            // the family weights to the input rate BEFORE the margin, so it carries one
            // more rounding than a model priced from its own stored cache rates. Asking it
            // for the same resolution would fail on that rounding rather than on a surface
            // billing the wrong multiple, which is the only thing this file is about.
            assertThat(pricing.calculateCost("anthropic", "brand-new-model", usage)
                    .divide(fallbackCost, 4, RoundingMode.HALF_UP))
                    .isEqualByComparingTo(BILLED_MULTIPLE);
        }
    }
}
