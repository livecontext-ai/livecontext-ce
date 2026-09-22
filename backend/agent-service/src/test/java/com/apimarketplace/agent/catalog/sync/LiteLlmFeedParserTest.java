package com.apimarketplace.agent.catalog.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-fixture tests for the LiteLLM feed parser. The inline fixtures cover
 * every rejection branch (non-native provider, non-chat mode, no-tools,
 * slash id, zero price) plus a happy-path accept per native provider.
 *
 * <p>Field values mirror real LiteLLM shapes - the western providers sampled
 * 2026-04-22, the Moonshot / Qwen / Z.AI rows 2026-07-30 - taken from the live
 * feed and trimmed.
 *
 * <p>{@link LiteLlmProviderCoverageTest} guards the complementary invariant:
 * that no executable provider is missing from {@code PROVIDER_MAP} in the
 * first place.
 */
@DisplayName("LiteLlmFeedParser - filters + normalisation")
class LiteLlmFeedParserTest {

    private LiteLlmFeedParser parser;

    @BeforeEach
    void setUp() {
        parser = new LiteLlmFeedParser(new ObjectMapper());
    }

    @Test
    @DisplayName("Happy path: 8 native providers accepted, non-natives dropped")
    void happyPath() {
        String fixture = """
            {
              "sample_spec": {"litellm_provider": "openai"},
              "claude-opus-4-7": {
                "litellm_provider": "anthropic", "mode": "chat",
                "max_input_tokens": 1000000, "max_output_tokens": 128000,
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "supports_function_calling": true, "supports_vision": true,
                "supports_prompt_caching": true, "supports_reasoning": true,
                "cache_read_input_token_cost": 5e-07
              },
              "gpt-5.4": {
                "litellm_provider": "openai", "mode": "chat",
                "max_input_tokens": 1050000, "max_output_tokens": 128000,
                "input_cost_per_token": 2.5e-06, "output_cost_per_token": 1.5e-05,
                "input_cost_per_token_batches": 1.25e-06,
                "output_cost_per_token_batches": 7.5e-06,
                "supports_function_calling": true, "supports_vision": true,
                "supported_endpoints": ["/v1/chat/completions", "/v1/batch"]
              },
              "gemini/gemini-2.5-pro": {
                "litellm_provider": "gemini", "mode": "chat",
                "input_cost_per_token": 1.25e-06, "output_cost_per_token": 1e-05,
                "max_input_tokens": 1048576, "max_output_tokens": 65535,
                "supports_function_calling": true
              },
              "mistral/mistral-large-latest": {
                "litellm_provider": "mistral", "mode": "chat",
                "input_cost_per_token": 2e-06, "output_cost_per_token": 6e-06,
                "supports_function_calling": true
              },
              "deepseek-chat": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 2.7e-07, "output_cost_per_token": 1.1e-06,
                "supports_function_calling": true
              },
              "xai/grok-3": {
                "litellm_provider": "xai", "mode": "chat",
                "input_cost_per_token": 3e-06, "output_cost_per_token": 1.5e-05,
                "supports_function_calling": true
              },
              "perplexity/sonar-pro": {
                "litellm_provider": "perplexity", "mode": "chat",
                "input_cost_per_token": 3e-06, "output_cost_per_token": 1.5e-05,
                "supports_function_calling": true
              },
              "cohere/command-r-plus-08-2024": {
                "litellm_provider": "cohere_chat", "mode": "chat",
                "input_cost_per_token": 2.5e-06, "output_cost_per_token": 1e-05,
                "supports_function_calling": true
              },
              "vertex_ai/claude-opus-4-7": {
                "litellm_provider": "vertex_ai-language-models", "mode": "chat",
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "supports_function_calling": true
              },
              "bedrock/claude-opus-4-7": {
                "litellm_provider": "bedrock", "mode": "chat",
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "supports_function_calling": true
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "test-sha", "2026-04-22T00:00:00Z");

        assertThat(result.isSuccess()).isTrue();
        // 8 native providers accepted; vertex_ai and bedrock rejected.
        assertThat(result.models()).hasSize(8);
        assertThat(result.rejectedProvider()).isEqualTo(2);

        // gemini → google mapping, prefix stripped
        Map<String, Object> gemini = findByModelId(result.models(), "gemini-2.5-pro");
        assertThat(gemini.get("provider")).isEqualTo("google");

        // cohere_chat → cohere alias
        Map<String, Object> cohere = findByModelId(result.models(), "command-r-plus-08-2024");
        assertThat(cohere.get("provider")).isEqualTo("cohere");
    }

    @Test
    @DisplayName("Regression: Kimi / Qwen / GLM are accepted - PROVIDER_MAP used to drop all three")
    void acceptsMoonshotQwenAndZai() {
        // Pre-fix, PROVIDER_MAP held only 8 western providers, so every row
        // below hit the rejectedProvider branch: the sync reported OK with
        // nothing added and Moonshot/Qwen/Z.AI stayed frozen on the ids
        // hardcoded in application.yml. Shapes sampled from the live feed
        // 2026-07-30; note LiteLLM keys Qwen under "dashscope".
        String fixture = """
            {
              "moonshot/kimi-k2.6": {
                "litellm_provider": "moonshot", "mode": "chat",
                "max_input_tokens": 262144, "max_output_tokens": 32768,
                "input_cost_per_token": 9.5e-07, "output_cost_per_token": 4e-06,
                "supports_function_calling": true
              },
              "moonshot/kimi-k2-thinking": {
                "litellm_provider": "moonshot", "mode": "chat",
                "input_cost_per_token": 6e-07, "output_cost_per_token": 2.5e-06,
                "supports_function_calling": true, "supports_reasoning": true
              },
              "dashscope/qwen-max": {
                "litellm_provider": "dashscope", "mode": "chat",
                "input_cost_per_token": 1.6e-06, "output_cost_per_token": 6.4e-06,
                "supports_function_calling": true
              },
              "dashscope/qwen3-vl-32b-instruct": {
                "litellm_provider": "dashscope", "mode": "chat",
                "input_cost_per_token": 1.6e-07, "output_cost_per_token": 6.4e-07,
                "supports_function_calling": true, "supports_vision": true
              },
              "zai/glm-5.1": {
                "litellm_provider": "zai", "mode": "chat",
                "input_cost_per_token": 1.4e-06, "output_cost_per_token": 4.4e-06,
                "supports_function_calling": true
              },
              "minimax/MiniMax-M3": {
                "litellm_provider": "minimax", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "supports_function_calling": true
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.models()).hasSize(6);
        assertThat(result.rejectedProvider()).isZero();

        // minimax was the last Chinese provider still unmapped: its rows pass
        // every other filter untouched, so the entry alone is what let the
        // M-series through.
        Map<String, Object> m3 = findByModelId(result.models(), "MiniMax-M3");
        assertThat(m3.get("provider")).isEqualTo("minimax");
        assertThat((BigDecimal) m3.get("priceInput"))
                .isEqualByComparingTo(new BigDecimal("0.30"));
        assertThat((BigDecimal) m3.get("priceOutput"))
                .isEqualByComparingTo(new BigDecimal("1.20"));

        // The "moonshot/" prefix is stripped, so the native id matches the
        // application.yml entry exactly and the merge updates rather than
        // duplicating it.
        Map<String, Object> kimi = findByModelId(result.models(), "kimi-k2.6");
        assertThat(kimi.get("provider")).isEqualTo("moonshot");
        assertThat((BigDecimal) kimi.get("priceInput"))
                .isEqualByComparingTo(new BigDecimal("0.95"));
        assertThat((BigDecimal) kimi.get("priceOutput"))
                .isEqualByComparingTo(new BigDecimal("4.0"));

        // dashscope is LiteLLM's key for Alibaba's API; our provider is "qwen".
        Map<String, Object> qwen = findByModelId(result.models(), "qwen-max");
        assertThat(qwen.get("provider")).isEqualTo("qwen");
        assertThat((BigDecimal) qwen.get("priceInput"))
                .isEqualByComparingTo(new BigDecimal("1.6"));

        Map<String, Object> qwenVl = findByModelId(result.models(), "qwen3-vl-32b-instruct");
        assertThat(qwenVl.get("provider")).isEqualTo("qwen");
        assertThat(qwenVl.get("supportsVision")).isEqualTo(true);

        Map<String, Object> glm = findByModelId(result.models(), "glm-5.1");
        assertThat(glm.get("provider")).isEqualTo("zai");
        assertThat((BigDecimal) glm.get("priceOutput"))
                .isEqualByComparingTo(new BigDecimal("4.4"));
    }

    @Test
    @DisplayName("stripProviderPrefix maps the new keys onto the ids application.yml already uses")
    void stripsPrefixForNewProviderKeys() {
        // The native id is the row identity: get this wrong and the merge
        // inserts a SECOND row instead of updating the seeded one, so the
        // picker shows "kimi-k2.6" twice with divergent prices.
        assertThat(LiteLlmFeedParser.stripProviderPrefix("moonshot/kimi-k2.6", "moonshot"))
                .isEqualTo("kimi-k2.6");
        // dashscope is LiteLLM's key, "qwen" is ours - the strip must use the
        // FEED key, not the mapped name, or the prefix survives.
        assertThat(LiteLlmFeedParser.stripProviderPrefix("dashscope/qwen-max", "dashscope"))
                .isEqualTo("qwen-max");
        assertThat(LiteLlmFeedParser.stripProviderPrefix("zai/glm-5.1", "zai"))
                .isEqualTo("glm-5.1");

        // Some feed rows carry no prefix at all - they must pass through
        // untouched rather than losing a leading segment.
        assertThat(LiteLlmFeedParser.stripProviderPrefix("kimi-latest-8k", "moonshot"))
                .isEqualTo("kimi-latest-8k");
        // A model id that merely CONTAINS the provider name is not a prefix.
        assertThat(LiteLlmFeedParser.stripProviderPrefix("glm-5-code", "zai"))
                .isEqualTo("glm-5-code");
    }

    @Test
    @DisplayName("Truly unpriced Qwen / GLM rows are still dropped by the zero-price gate")
    void dropsUnpricedChineseRows() {
        // Rows with NO price of any kind (qwen3-30b-a3b: no flat keys, no
        // tiered ladder) and rows with an explicit 0/0 free tier
        // (glm-4.5-flash, glm-4.7-flash) must stay out - a zero-priced row
        // slips through the CreditService and enables free LLM use. This is
        // the counterpart of acceptsTieredPricedChineseFlagships: the ladder
        // fallback must not turn the gate into a rubber stamp.
        String fixture = """
            {
              "dashscope/qwen3-30b-a3b": {
                "litellm_provider": "dashscope", "mode": "chat",
                "supports_function_calling": true
              },
              "zai/glm-4.7-flash": {
                "litellm_provider": "zai", "mode": "chat",
                "input_cost_per_token": 0, "output_cost_per_token": 0,
                "supports_function_calling": true
              },
              "zai/glm-5.1": {
                "litellm_provider": "zai", "mode": "chat",
                "input_cost_per_token": 1.4e-06, "output_cost_per_token": 4.4e-06,
                "supports_function_calling": true
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        // zeroPrice is counted in the parse log but not carried on ParseResult,
        // so assert on what survived: the two unpriced rows are gone and it is
        // NOT the provider gate that dropped them.
        assertThat(result.models()).hasSize(1);
        assertThat(result.models().get(0).get("modelId")).isEqualTo("glm-5.1");
        assertThat(result.rejectedProvider()).isZero();
    }

    @Test
    @DisplayName("Regression: tiered_pricing rows are accepted at their base bracket - the gate used to delete qwen3-max")
    void acceptsTieredPricedChineseFlagships() {
        // Alibaba and ByteDance bill by context bracket, so LiteLLM publishes
        // `tiered_pricing` INSTEAD of the flat cost keys. Reading only the flat
        // keys made these rows look unpriced and the zero-price gate deleted
        // them: 19 models on the live feed (2026-08-19), every one of them
        // Chinese. Fixtures are the real feed shapes, trimmed.
        String fixture = """
            {
              "dashscope/qwen3-max": {
                "litellm_provider": "dashscope", "mode": "chat",
                "max_input_tokens": 258048, "max_output_tokens": 65536,
                "supports_function_calling": true, "supports_reasoning": true,
                "tiered_pricing": [
                  {"range": [0, 32000.0],       "input_cost_per_token": 1.2e-06, "output_cost_per_token": 6e-06},
                  {"range": [32000.0, 128000.0],"input_cost_per_token": 2.4e-06, "output_cost_per_token": 1.2e-05},
                  {"range": [128000.0, 252000.0],"input_cost_per_token": 3e-06,  "output_cost_per_token": 1.5e-05}
                ]
              },
              "dashscope/qwen3-coder-plus": {
                "litellm_provider": "dashscope", "mode": "chat",
                "max_input_tokens": 997952, "max_output_tokens": 65536,
                "supports_function_calling": true,
                "tiered_pricing": [
                  {"range": [0, 32000.0], "input_cost_per_token": 1e-06,
                   "output_cost_per_token": 5e-06, "cache_read_input_token_cost": 1e-07},
                  {"range": [32000.0, 128000.0], "input_cost_per_token": 1.8e-06,
                   "output_cost_per_token": 9e-06, "cache_read_input_token_cost": 1.8e-07}
                ]
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.models()).hasSize(2);

        Map<String, Object> max = findByModelId(result.models(), "qwen3-max");
        assertThat(max.get("provider")).isEqualTo("qwen");
        // Base bracket, i.e. the price Alibaba advertises - NOT the 3.00/15.00
        // top bracket, which would misclassify the model as "top" tier.
        assertThat(max.get("priceInput")).isEqualTo(new BigDecimal("1.2000"));
        assertThat(max.get("priceOutput")).isEqualTo(new BigDecimal("6.0000"));
        assertThat(max.get("tier")).isEqualTo("high");
        assertThat(max.get("contextWindow")).isEqualTo(258048);
        // The whole ladder survives for anyone who needs the upper brackets.
        @SuppressWarnings("unchecked")
        Map<String, Object> feedMeta = (Map<String, Object>) max.get("feedMetadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) feedMeta.get("raw");
        assertThat(raw).containsKey("tiered_pricing");

        // Cache rates live inside the brackets too - read them from the same
        // base tier or a tiered model looks cache-free.
        Map<String, Object> coder = findByModelId(result.models(), "qwen3-coder-plus");
        assertThat(coder.get("priceInput")).isEqualTo(new BigDecimal("1.0000"));
        assertThat(coder.get("priceOutput")).isEqualTo(new BigDecimal("5.0000"));
        assertThat(coder.get("priceCacheRead")).isEqualTo(new BigDecimal("0.1000"));
    }

    @Test
    @DisplayName("A flat price always wins over the ladder, and the base bracket is picked by range not by order")
    void flatPriceWinsAndBaseBracketIsRangeBased() {
        // Two independent guarantees of effectiveCost:
        //  - western rows (flat keys, no ladder) are untouched by the fallback;
        //  - when a feed lists brackets out of order, the [0, …] one is still
        //    the base. Relying on index 0 would have billed doubao at its
        //    long-context rate.
        String fixture = """
            {
              "gpt-5.4": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 2.5e-06, "output_cost_per_token": 1.5e-05,
                "supports_function_calling": true,
                "tiered_pricing": [
                  {"range": [0, 32000.0], "input_cost_per_token": 9.9e-05, "output_cost_per_token": 9.9e-05}
                ]
              },
              "dashscope/qwen3.7-plus": {
                "litellm_provider": "dashscope", "mode": "chat",
                "supports_function_calling": true,
                "tiered_pricing": [
                  {"range": [128000.0, 991808.0], "input_cost_per_token": 1.2e-06, "output_cost_per_token": 4.8e-06},
                  {"range": [0, 128000.0],        "input_cost_per_token": 4e-07,   "output_cost_per_token": 1.6e-06}
                ]
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.models()).hasSize(2);
        Map<String, Object> gpt = findByModelId(result.models(), "gpt-5.4");
        assertThat(gpt.get("priceInput")).isEqualTo(new BigDecimal("2.5000"));
        assertThat(gpt.get("priceOutput")).isEqualTo(new BigDecimal("15.0000"));

        Map<String, Object> plus = findByModelId(result.models(), "qwen3.7-plus");
        assertThat(plus.get("priceInput")).isEqualTo(new BigDecimal("0.4000"));
        assertThat(plus.get("priceOutput")).isEqualTo(new BigDecimal("1.6000"));
    }

    @Test
    @DisplayName("A malformed ladder degrades to unpriced instead of throwing")
    void malformedTieredPricingIsInert() {
        // The feed is third-party input: a ladder that is a string, a list of
        // strings, or empty must not crash the whole sync (one bad row used to
        // be enough - see the fallback_generalizations NPE regression).
        String fixture = """
            {
              "dashscope/a": {"litellm_provider": "dashscope", "mode": "chat",
                              "supports_function_calling": true, "tiered_pricing": "nope"},
              "dashscope/b": {"litellm_provider": "dashscope", "mode": "chat",
                              "supports_function_calling": true, "tiered_pricing": []},
              "dashscope/c": {"litellm_provider": "dashscope", "mode": "chat",
                              "supports_function_calling": true, "tiered_pricing": ["x"]},
              "dashscope/d": {"litellm_provider": "dashscope", "mode": "chat",
                              "supports_function_calling": true,
                              "tiered_pricing": [{"input_cost_per_token": 5e-07,
                                                  "output_cost_per_token": 2e-06}]}
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.isSuccess()).isTrue();
        // a/b/c carry no usable price and are dropped; d has a single bracket
        // with no range declared, so the first entry is the base.
        assertThat(result.models()).hasSize(1);
        assertThat(result.models().get(0).get("modelId")).isEqualTo("d");
        assertThat(result.models().get(0).get("priceInput")).isEqualTo(new BigDecimal("0.5000"));
    }

    @Test
    @DisplayName("Drops mode=embedding / image / audio - only chat is accepted")
    void dropsNonChatMode() {
        String fixture = """
            {
              "text-embedding-3-large": {
                "litellm_provider": "openai", "mode": "embedding",
                "input_cost_per_token": 1.3e-07, "output_cost_per_token": 0,
                "supports_function_calling": false
              },
              "gpt-5-chat": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 1.25e-06, "output_cost_per_token": 1e-05,
                "supports_function_calling": false
              },
              "gpt-5.4": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 2.5e-06, "output_cost_per_token": 1.5e-05,
                "supports_function_calling": true
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.models()).hasSize(1);
        assertThat(result.rejectedMode()).isEqualTo(1);
        assertThat(result.rejectedNoTools()).isEqualTo(1);
        assertThat(result.models().get(0).get("modelId")).isEqualTo("gpt-5.4");
    }

    @Test
    @DisplayName("A rejected id is RECORDED, so native discovery cannot re-admit what the feed refused")
    void recordsWhatItDeclined() {
        // Both rejects here are live on OpenAI's own /models listing, which
        // states neither a mode nor a tool-calling capability. Without the
        // record, discovery re-emits them as chat rows and the platform
        // advertises an embedding model and a model it cannot call tools on.
        String fixture = """
            {
              "text-embedding-3-large": {
                "litellm_provider": "openai", "mode": "embedding",
                "input_cost_per_token": 1.3e-07, "output_cost_per_token": 0,
                "supports_function_calling": false
              },
              "gpt-5-chat": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 1.25e-06, "output_cost_per_token": 1e-05,
                "supports_function_calling": false
              },
              "gemini-exp-free": {
                "litellm_provider": "gemini", "mode": "chat",
                "input_cost_per_token": 0, "output_cost_per_token": 0,
                "supports_function_calling": true
              },
              "gpt-5.4": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 2.5e-06, "output_cost_per_token": 1.5e-05,
                "supports_function_calling": true
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        // Keyed by OUR provider name and the NATIVE id, which is what a vendor
        // listing returns. Recording litellm's own provider key ("gemini") or
        // the namespaced id would match nothing and the filter would be inert
        // while every test still passed.
        assertThat(result.declinedIds()).containsExactlyInAnyOrder(
                NativeModelDiscoveryService.key("openai", "text-embedding-3-large"),
                NativeModelDiscoveryService.key("openai", "gpt-5-chat"),
                NativeModelDiscoveryService.key("google", "gemini-exp-free"));
        // An accepted model is never declined - it is the feed's own catalog.
        assertThat(result.declinedIds())
                .doesNotContain(NativeModelDiscoveryService.key("openai", "gpt-5.4"));
    }

    @Test
    @DisplayName("A namespaced feed id is recorded under its bare native id, the form a vendor listing returns")
    void recordsDeclinedIdsWithoutTheProviderNamespace() {
        String fixture = """
            {
              "mistral/mistral-embed": {
                "litellm_provider": "mistral", "mode": "embedding",
                "input_cost_per_token": 1e-07, "output_cost_per_token": 0,
                "supports_function_calling": false
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.declinedIds()).containsExactly(
                NativeModelDiscoveryService.key("mistral", "mistral-embed"));
    }

    @Test
    @DisplayName("Drops ft: fine-tuning pricing templates - they're not callable until a tenant fine-tunes the base")
    void dropsFineTuneTemplates() {
        // LiteLLM publishes ft:-prefixed rows as per-token rate templates for
        // ANY fine-tune of the base model. A real fine-tune id is
        // "ft:<base>:<org>:<job>:<hash>" - the template collides with no real
        // OpenAI model. Surfacing them in the picker would give every tenant
        // a row that 404s at dispatch. Admins add real fine-tunes via is_custom=true.
        String fixture = """
            {
              "ft:gpt-4.1-mini-2025-04-14": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 8e-07, "output_cost_per_token": 3.2e-06,
                "supports_function_calling": true
              },
              "ft:gpt-4o-2024-08-06": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 3.75e-06, "output_cost_per_token": 1.5e-05,
                "supports_function_calling": true
              },
              "gpt-4o-2024-08-06": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 2.5e-06, "output_cost_per_token": 1e-05,
                "supports_function_calling": true
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.models()).hasSize(1);
        assertThat(result.models().get(0).get("modelId")).isEqualTo("gpt-4o-2024-08-06");
    }

    @Test
    @DisplayName("Accepted model produces canonical map with all V121 enrichment fields")
    void canonicalShape() {
        String fixture = """
            {
              "claude-opus-4-7-20260416": {
                "litellm_provider": "anthropic", "mode": "chat",
                "max_input_tokens": 1000000, "max_output_tokens": 128000,
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "input_cost_per_token_batches": 2.5e-06,
                "cache_read_input_token_cost": 5e-07,
                "cache_creation_input_token_cost": 6.25e-06,
                "supports_function_calling": true, "supports_vision": true,
                "supports_prompt_caching": true, "supports_reasoning": true,
                "supports_computer_use": true, "supports_response_schema": true,
                "deprecation_date": "2027-01-01"
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha-xyz", "fetch-ts");

        assertThat(result.models()).hasSize(1);
        Map<String, Object> m = result.models().get(0);
        assertThat(m.get("provider")).isEqualTo("anthropic");
        assertThat(m.get("modelId")).isEqualTo("claude-opus-4-7-20260416");
        assertThat((BigDecimal) m.get("priceInput"))
                .isEqualByComparingTo(new BigDecimal("5.0000"));
        assertThat((BigDecimal) m.get("priceOutput"))
                .isEqualByComparingTo(new BigDecimal("25.0000"));
        assertThat(m.get("tier")).isEqualTo("top");
        assertThat(m.get("contextWindow")).isEqualTo(1000000);
        assertThat(m.get("maxOutputTokens")).isEqualTo(128000);
        assertThat(m.get("supportsTools")).isEqualTo(true);
        assertThat(m.get("supportsVision")).isEqualTo(true);
        assertThat(m.get("supportsPromptCaching")).isEqualTo(true);
        assertThat(m.get("supportsReasoning")).isEqualTo(true);
        assertThat(m.get("supportsComputerUse")).isEqualTo(true);
        assertThat(m.get("supportsResponseSchema")).isEqualTo(true);
        assertThat(m.get("mode")).isEqualTo("chat");
        assertThat(m.get("deprecationDate")).isEqualTo("2027-01-01");
        assertThat(m.get("releaseDate")).isEqualTo("2026-04-16"); // extracted from suffix

        // Price floor = min(input, inputBatch) = 2.5
        assertThat((BigDecimal) m.get("priceFloorInput"))
                .isEqualByComparingTo(new BigDecimal("2.5"));

        // Feed metadata - raw payload round-trips.
        @SuppressWarnings("unchecked")
        Map<String, Object> feedMeta = (Map<String, Object>) m.get("feedMetadata");
        assertThat(feedMeta).containsEntry("source", "litellm")
                .containsEntry("sourceSha", "sha-xyz")
                .containsEntry("fetchedAt", "fetch-ts");
        assertThat(feedMeta.get("raw")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("Drops rows where stripping provider prefix still leaves a slash")
    void rejectsSlashInNativeId() {
        // Contrived: the identity prefix doesn't match the litellm_provider.
        // Our stripper leaves the slash in, and the no-slash guard catches it.
        String fixture = """
            {
              "weird/nested/id": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 1e-06, "output_cost_per_token": 1e-05,
                "supports_function_calling": true
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");
        assertThat(result.models()).isEmpty();
        assertThat(result.rejectedSlash()).isEqualTo(1);
    }

    @Test
    @DisplayName("Dated alias is dropped when canonical twin exists; dated-only rows are kept")
    void dedupsDatedAliases() {
        String fixture = """
            {
              "claude-opus-4-7": {
                "litellm_provider": "anthropic", "mode": "chat",
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "supports_function_calling": true
              },
              "claude-opus-4-7-20260416": {
                "litellm_provider": "anthropic", "mode": "chat",
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "supports_function_calling": true
              },
              "gpt-4-0613": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 3e-05, "output_cost_per_token": 6e-05,
                "supports_function_calling": true
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.models()).hasSize(2);
        assertThat(result.models()).extracting(m -> m.get("modelId"))
                .containsExactlyInAnyOrder("claude-opus-4-7", "gpt-4-0613");
        // gpt-4-0613 has a date-like suffix but NO canonical twin in the feed
        // (gpt-4 doesn't appear), so it stays.

        // The hidden twin is recorded. Anthropic's own listing returns dated
        // ids, so without this every sync with an Anthropic key would have
        // discovery add claude-opus-4-7-20260416 back as a second row for the
        // same model - the highest-volume duplicate of the lot, and unlike the
        // others it would fire on every single run.
        assertThat(result.declinedIds()).contains(
                NativeModelDiscoveryService.key("anthropic", "claude-opus-4-7-20260416"));
        // A dated-only row was KEPT, so it is a catalog row and must not also
        // be declined - that would hide a model the feed publishes.
        assertThat(result.declinedIds()).doesNotContain(
                NativeModelDiscoveryService.key("openai", "gpt-4-0613"));
    }

    @Test
    @DisplayName("A version-suffixed twin collapses onto its version-free alias when the feed describes them identically")
    void collapsesVersionedTwinOntoAlias() {
        // The live case, taken from the feed on 2026-09-15: DeepSeek publishes
        // deepseek-flash AND deepseek-v4-flash, with the same price, context,
        // output cap and capability flags, and updates the weights behind both
        // in place. So the versioned name says "v4" for weights that are no
        // longer v4, and the picker shows two rows nobody can tell apart, one
        // of which lies about what it serves.
        String fixture = """
            {
              "deepseek-flash": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "max_input_tokens": 1000000, "max_output_tokens": 393216,
                "supports_function_calling": true, "supports_vision": true,
                "supports_reasoning": true
              },
              "deepseek-v4-flash": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "max_input_tokens": 1000000, "max_output_tokens": 393216,
                "supports_function_calling": true, "supports_vision": true,
                "supports_reasoning": true
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        // The ALIAS is the survivor, deliberately: a version-free id cannot go
        // stale, it only ever claims to be the current flash model.
        assertThat(result.models()).extracting(m -> m.get("modelId"))
                .containsExactly("deepseek-flash");
        // Recorded like any other declined id, so the native discovery pass
        // cannot offer deepseek-v4-flash straight back on the same sync.
        assertThat(result.declinedIds()).contains(
                NativeModelDiscoveryService.key("deepseek", "deepseek-v4-flash"));
    }

    @Test
    @DisplayName("A field the ALIAS carries and the versioned twin lacks blocks the collapse")
    void asymmetricFieldsBlockTheCollapse() {
        // publishedFieldsEqual walks the UNION of both key sets, and this is the
        // fixture that proves the union is load-bearing. The direction matters
        // and it is not symmetric: the comparison is always called as
        // (twin, alias), so iterating only the FIRST side's keys still catches a
        // field the twin has and the alias lacks. What it cannot see is the
        // reverse - a key that exists only on the ALIAS is never looked up, both
        // rows read as identical, and the twin is silently collapsed away.
        //
        // Reachable in production rather than theoretical: normalise() writes
        // deprecationDate and releaseDate CONDITIONALLY. Here the vendor has
        // announced an end date for the moving alias while the pinned snapshot
        // carries none, so the two are genuinely different offers and both must
        // stand. Delete `keys.addAll(b.keySet())` and this test fails while
        // every other test in the class still passes.
        String fixture = """
            {
              "vendor-flash": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "supports_function_calling": true,
                "deprecation_date": "2027-01-01"
              },
              "vendor-v4-flash": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "supports_function_calling": true
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.models()).extracting(m -> m.get("modelId"))
                .containsExactlyInAnyOrder("vendor-flash", "vendor-v4-flash");
        assertThat(result.declinedIds()).doesNotContain(
                NativeModelDiscoveryService.key("deepseek", "vendor-v4-flash"));
        // The asymmetry is real, not an artefact of the fixture: exactly one of
        // the two normalised rows carries the key at all.
        assertThat(findByModelId(result.models(), "vendor-flash")).containsKey("deprecationDate");
        assertThat(findByModelId(result.models(), "vendor-v4-flash")).doesNotContainKey("deprecationDate");
    }

    @Test
    @DisplayName("A field the versioned twin carries and the alias lacks also blocks the collapse")
    void asymmetryBlocksTheCollapseInEitherDirection() {
        // The mirror image of the test above. It is the easier direction, caught
        // even by a one-sided comparison, and it is here so the pair states the
        // whole rule rather than the half that happens to be fragile: ANY
        // difference in the published description keeps both rows.
        String fixture = """
            {
              "vendor-flash": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "supports_function_calling": true
              },
              "vendor-v4-flash": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "supports_function_calling": true,
                "deprecation_date": "2027-01-01"
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.models()).extracting(m -> m.get("modelId"))
                .containsExactlyInAnyOrder("vendor-flash", "vendor-v4-flash");
    }

    @Test
    @DisplayName("A version-suffixed row is KEPT when any published field differs from its alias")
    void keepsVersionedRowWhenTheFeedSaysTheyDiffer() {
        // The collapse has to be provable from the feed, not inferred from the
        // name. A vendor can publish a version-free alias next to a genuinely
        // different versioned model, and here the feed says so: 1M of context
        // against 128k. Guessing from the name alone would delete a real model
        // and leave users on a different one under the id they picked.
        String fixture = """
            {
              "vendor-chat": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "max_input_tokens": 1000000,
                "supports_function_calling": true
              },
              "vendor-v3-chat": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "max_input_tokens": 128000,
                "supports_function_calling": true
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.models()).extracting(m -> m.get("modelId"))
                .containsExactlyInAnyOrder("vendor-chat", "vendor-v3-chat");
        assertThat(result.declinedIds()).doesNotContain(
                NativeModelDiscoveryService.key("deepseek", "vendor-v3-chat"));
    }

    @Test
    @DisplayName("An id carrying two version tokens tries each one, not only the first")
    void severalVersionTokensAreEachTried() {
        // The match loop runs `while (mat.find())` rather than testing one
        // candidate, and nothing drove it past its first iteration. Here the
        // FIRST token yields an id the feed does not carry, so a single-shot
        // implementation would give up and keep a redundant row; only trying
        // the second token finds the real alias.
        String fixture = """
            {
              "vendor-v4-v2-flash": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "supports_function_calling": true
              },
              "vendor-v4-flash": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "supports_function_calling": true
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        // Removing "-v4" gives "vendor-v2-flash", which is absent; removing
        // "-v2" gives "vendor-v4-flash", which is present and identical.
        assertThat(result.models()).extracting(m -> m.get("modelId"))
                .containsExactly("vendor-v4-flash");
        assertThat(result.declinedIds()).contains(
                NativeModelDiscoveryService.key("deepseek", "vendor-v4-v2-flash"));
    }

    @Test
    @DisplayName("A version token at the END of an id is never treated as a twin suffix")
    void trailingVersionTokenIsNotAnAlias() {
        // deepseek-prover-v2 is a MODEL NAME that happens to end in a version.
        // Stripping the token there would invent "deepseek-prover", an id the
        // vendor does not serve, and a match against it would delete a real
        // model in favour of a different one. The pattern is anchored on a
        // FOLLOWING hyphen precisely so the end of an id cannot match.
        String fixture = """
            {
              "deepseek-prover": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "supports_function_calling": true
              },
              "deepseek-prover-v2": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "supports_function_calling": true
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.models()).extracting(m -> m.get("modelId"))
                .containsExactlyInAnyOrder("deepseek-prover", "deepseek-prover-v2");
    }

    @Test
    @DisplayName("One vendor's alias does not hide another vendor's, so a legitimate collapse still happens")
    void twoVendorsSharingAnAliasIdDoNotMaskEachOther() {
        // The case that actually pins the (provider, id) map key, which the test
        // below deliberately does not: two vendors publish the SAME alias id,
        // and only one of them also ships the versioned twin.
        //
        // Keyed by the bare id, the two aliases collide in the index and one
        // overwrites the other. The twin then looks up its alias, finds the
        // WRONG vendor's row, and the provider mismatch blocks a collapse that
        // should have happened - a silent miss rather than a loud failure, so
        // the only thing that would ever surface it is a test like this one.
        String fixture = """
            {
              "shared-flash": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "supports_function_calling": true
              },
              "shared-v4-flash": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "supports_function_calling": true
              },
              "moonshot/shared-flash": {
                "litellm_provider": "moonshot", "mode": "chat",
                "input_cost_per_token": 9e-07, "output_cost_per_token": 3.6e-06,
                "supports_function_calling": true
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        // DeepSeek's twin collapsed; both vendors keep their alias.
        assertThat(result.models()).extracting(m -> m.get("provider") + ":" + m.get("modelId"))
                .containsExactlyInAnyOrder("deepseek:shared-flash", "moonshot:shared-flash");
        assertThat(result.declinedIds()).contains(
                NativeModelDiscoveryService.key("deepseek", "shared-v4-flash"));
    }

    @Test
    @DisplayName("Two vendors shipping twin-shaped ids both survive, whatever their prices")
    void versionedTwinsAreMatchedPerProvider() {
        // Honest about its own mechanism, corrected: this passes because the
        // alias index is keyed (provider, id), so the lookup for one vendor's
        // twin never returns the other vendor's row. It is NOT the field
        // comparison doing the work - adding "provider" to the ignored set
        // changes nothing here, because publishedFieldsEqual is never reached
        // for this pair (mutation-proven).
        //
        // It also does not PIN that key: keying by bare id would still leave
        // both rows standing here. The case that pins it is
        // twoVendorsSharingAnAliasIdDoNotMaskEachOther. What this one pins is
        // the user-visible outcome: one vendor's naming habit cannot delete
        // another vendor's model.
        String fixture = """
            {
              "shared-flash": {
                "litellm_provider": "deepseek", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "supports_function_calling": true
              },
              "shared-v4-flash": {
                "litellm_provider": "moonshot", "mode": "chat",
                "input_cost_per_token": 3e-07, "output_cost_per_token": 1.2e-06,
                "supports_function_calling": true
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.models()).extracting(m -> m.get("modelId"))
                .containsExactlyInAnyOrder("shared-flash", "shared-v4-flash");
        assertThat(result.declinedIds()).doesNotContain(
                NativeModelDiscoveryService.key("moonshot", "shared-v4-flash"));
    }

    @Test
    @DisplayName("Two vendors shipping the same dated id are recorded apart, never as one")
    void datedTwinsAreRecordedPerProvider() {
        // The declined index is keyed by provider, so a twin hidden for one
        // vendor must not silence the same id for another. Cheap to get wrong
        // (a key built from the id alone) and invisible when it is: the other
        // vendor's model simply never appears.
        String fixture = """
            {
              "model-x-9": {
                "litellm_provider": "anthropic", "mode": "chat",
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "supports_function_calling": true
              },
              "model-x-9-20260101": {
                "litellm_provider": "anthropic", "mode": "chat",
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "supports_function_calling": true
              },
              "model-x-9-20260101-openai-twin": {
                "litellm_provider": "openai", "mode": "chat",
                "input_cost_per_token": 1e-06, "output_cost_per_token": 2e-06,
                "supports_function_calling": true
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.declinedIds()).containsExactly(
                NativeModelDiscoveryService.key("anthropic", "model-x-9-20260101"));
        assertThat(result.declinedIds()).doesNotContain(
                NativeModelDiscoveryService.key("openai", "model-x-9-20260101"));
    }

    @Test
    @DisplayName("Meta blocks without litellm_provider are rejected, not crashed on (fallback_generalizations regression)")
    void metaEntryWithoutProviderDoesNotCrash() {
        // Regression: LiteLLM's live feed carries non-model meta blocks with no
        // litellm_provider (e.g. "fallback_generalizations", a set of
        // adaptive-thinking inference rules). PROVIDER_MAP is an immutable
        // Map.ofEntries whose get(null) throws NPE, which previously aborted the
        // ENTIRE sync ("sync failed: Cannot invoke Object.hashCode() ...") on the
        // first refresh after that upstream entry appeared. The parser must route
        // such entries through the reject path and still parse real models.
        String fixture = """
            {
              "fallback_generalizations": {
                "rules": [
                  {"name": "anthropic-claude", "pattern": "^claude-.*$",
                   "model_info": {"supports_adaptive_thinking": true}}
                ]
              },
              "claude-opus-4-8": {
                "litellm_provider": "anthropic", "mode": "chat",
                "input_cost_per_token": 5e-06, "output_cost_per_token": 2.5e-05,
                "supports_function_calling": true
              }
            }
            """;

        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.isSuccess()).isTrue();
        // The meta block is counted as a rejected-provider entry, not a crash.
        assertThat(result.rejectedProvider()).isEqualTo(1);
        // The real model still parses.
        assertThat(result.models()).hasSize(1);
        assertThat(result.models().get(0).get("modelId")).isEqualTo("claude-opus-4-8");
        assertThat(result.models().get(0).get("provider")).isEqualTo("anthropic");
    }

    @Test
    @DisplayName("Explicit null litellm_provider is rejected, not crashed on")
    void nullProviderValueDoesNotCrash() {
        // Same class of bug via an explicit JSON null rather than a missing key.
        String fixture = """
            {
              "some-weird-row": {
                "litellm_provider": null, "mode": "chat",
                "input_cost_per_token": 1e-06, "output_cost_per_token": 1e-05,
                "supports_function_calling": true
              }
            }
            """;
        LiteLlmFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "sha", "t");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.models()).isEmpty();
        assertThat(result.rejectedProvider()).isEqualTo(1);
    }

    @Test
    @DisplayName("Invalid JSON → isSuccess=false, empty models, error message populated")
    void malformedJson() {
        LiteLlmFeedParser.ParseResult result = parser.parse(
                "{ not json".getBytes(StandardCharsets.UTF_8), "sha", "t");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).isNotBlank();
        assertThat(result.models()).isEmpty();
    }

    private static Map<String, Object> findByModelId(List<Map<String, Object>> models, String id) {
        return models.stream()
                .filter(m -> id.equals(m.get("modelId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("not found: " + id));
    }
}
