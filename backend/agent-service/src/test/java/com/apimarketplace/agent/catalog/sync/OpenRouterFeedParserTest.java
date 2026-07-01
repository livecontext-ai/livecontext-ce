package com.apimarketplace.agent.catalog.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenRouter JSON shape mirrored from a real 2026-04 response. Every
 * rejection bucket is exercised plus a happy-path accept with full field
 * extraction.
 */
@DisplayName("OpenRouterFeedParser - filters + normalisation")
class OpenRouterFeedParserTest {

    private OpenRouterFeedParser parser;

    @BeforeEach
    void setUp() {
        parser = new OpenRouterFeedParser(new ObjectMapper());
    }

    @Test
    @DisplayName("Accepts a canonical model, emits provider='openrouter' with all fields")
    void canonicalAccept() {
        String fixture = """
            {"data": [{
              "id": "anthropic/claude-sonnet-4-20250514",
              "name": "Anthropic: Claude Sonnet 4",
              "description": "Fast intelligent model.",
              "context_length": 200000,
              "pricing": {
                "prompt": "0.000003",
                "completion": "0.000015",
                "input_cache_read": "0.0000003",
                "input_cache_write": "0.00000375"
              },
              "top_provider": { "max_completion_tokens": 8192 },
              "supported_parameters": ["tools", "response_format", "reasoning"],
              "architecture": {
                "input_modalities": ["text", "image"],
                "output_modalities": ["text"]
              }
            }]}
            """;

        OpenRouterFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "https://openrouter.ai/api/v1/models",
                "2026-04-22T00:00:00Z");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.models()).hasSize(1);
        Map<String, Object> m = result.models().get(0);

        assertThat(m.get("provider")).isEqualTo("openrouter");
        assertThat(m.get("modelId")).isEqualTo("anthropic/claude-sonnet-4-20250514");
        assertThat(m.get("displayName")).isEqualTo("Anthropic: Claude Sonnet 4");
        assertThat((BigDecimal) m.get("priceInput"))
                .isEqualByComparingTo(new BigDecimal("3.0000"));
        assertThat((BigDecimal) m.get("priceOutput"))
                .isEqualByComparingTo(new BigDecimal("15.0000"));
        // $15/1M output hits TIER_TOP_MIN exactly → "top".
        assertThat(m.get("tier")).isEqualTo("top");
        assertThat(m.get("contextWindow")).isEqualTo(200000);
        assertThat(m.get("maxOutputTokens")).isEqualTo(8192);
        assertThat(m.get("supportsTools")).isEqualTo(true);
        assertThat(m.get("supportsResponseSchema")).isEqualTo(true);
        assertThat(m.get("supportsReasoning")).isEqualTo(true);
        assertThat(m.get("supportsVision")).isEqualTo(true);
        assertThat(m.get("supportsPromptCaching")).isEqualTo(true);
        assertThat(m.get("releaseDate")).isEqualTo("2025-05-14");

        @SuppressWarnings("unchecked")
        java.util.List<String> modalities =
                (java.util.List<String>) m.get("supportedModalities");
        assertThat(modalities).containsExactly("text", "image");
    }

    @Test
    @DisplayName("Rejects the openrouter/auto router's -1 sentinel price (x1e6 = -1000000 would overflow the NUMERIC(10,6) billing mirror)")
    void rejectsNegativeSentinelPrice() {
        String fixture = """
            {"data": [{
              "id": "openrouter/auto",
              "name": "Auto Router",
              "context_length": 200000,
              "pricing": { "prompt": "-1", "completion": "-1" },
              "supported_parameters": ["tools"]
            }]}
            """;

        OpenRouterFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "https://openrouter.ai/api/v1/models",
                "2026-04-22T00:00:00Z");

        assertThat(result.isSuccess()).isTrue();
        // Pre-fix the signum()==0 gate let "-1" through -> accepted -> -1000000 synced to auth ->
        // Postgres numeric overflow on the CE. Post-fix (signum()<=0) it is dropped.
        assertThat(result.models()).isEmpty();
    }

    @Test
    @DisplayName("Keeps a free-input / paid-output model (0 input, >0 output is still billable)")
    void keepsZeroInputPaidOutput() {
        String fixture = """
            {"data": [{
              "id": "vendor/free-in-paid-out",
              "name": "Free In Paid Out",
              "context_length": 100000,
              "pricing": { "prompt": "0", "completion": "0.000002" },
              "supported_parameters": ["tools"]
            }]}
            """;

        OpenRouterFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "https://openrouter.ai/api/v1/models",
                "2026-04-22T00:00:00Z");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.models()).hasSize(1);
        assertThat(result.models().get(0).get("modelId")).isEqualTo("vendor/free-in-paid-out");
    }

    @Test
    @DisplayName("Drops duplicate-suffix ids (:free, :beta, :extended, :thinking, :nitro, :floor)")
    void dropsDuplicateSuffixes() {
        String fixture = """
            {"data": [
              {"id":"google/gemini-2.5-flash:free","pricing":{"prompt":"0","completion":"0"},
               "supported_parameters":["tools"]},
              {"id":"anthropic/claude-sonnet-4:beta","pricing":{"prompt":"3e-06","completion":"15e-06"},
               "supported_parameters":["tools"]},
              {"id":"x/y:thinking","pricing":{"prompt":"1e-06","completion":"5e-06"},
               "supported_parameters":["tools"]},
              {"id":"x/y:nitro","pricing":{"prompt":"1e-06","completion":"5e-06"},
               "supported_parameters":["tools"]},
              {"id":"openai/gpt-5.4","pricing":{"prompt":"2.5e-06","completion":"15e-06"},
               "supported_parameters":["tools"]}
            ]}
            """;
        OpenRouterFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "url", "ts");
        assertThat(result.models()).hasSize(1);
        assertThat(result.rejectedSuffix()).isEqualTo(4);
        assertThat(result.models().get(0).get("modelId")).isEqualTo("openai/gpt-5.4");
    }

    @Test
    @DisplayName("Drops rows without pricing or without 'tools' in supported_parameters")
    void dropsNoPricingAndNoTools() {
        String fixture = """
            {"data": [
              {"id":"a/b","pricing":{"prompt":null,"completion":null},"supported_parameters":["tools"]},
              {"id":"c/d","pricing":{"prompt":"1e-06","completion":"5e-06"},"supported_parameters":["response_format"]},
              {"id":"e/f","pricing":{"prompt":"1e-06","completion":"5e-06"},"supported_parameters":["tools"]}
            ]}
            """;
        OpenRouterFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "url", "ts");
        assertThat(result.models()).hasSize(1);
        assertThat(result.rejectedNoPricing()).isEqualTo(1);
        assertThat(result.rejectedNoTools()).isEqualTo(1);
    }

    @Test
    @DisplayName("Response missing 'data' array → isSuccess=false")
    void missingDataArray() {
        OpenRouterFeedParser.ParseResult result = parser.parse(
                "{\"other\": 1}".getBytes(StandardCharsets.UTF_8), "url", "ts");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).contains("no 'data' array");
    }
}
