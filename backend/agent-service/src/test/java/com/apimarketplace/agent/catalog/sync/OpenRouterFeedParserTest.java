package com.apimarketplace.agent.catalog.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    @DisplayName("Drops duplicate-suffix ids (:free, :beta, :extended, :thinking, :nitro, :floor, :batch)")
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
              {"id":"x/y:batch","pricing":{"prompt":"5e-07","completion":"2.5e-06"},
               "supported_parameters":["tools"]},
              {"id":"openai/gpt-5.4","pricing":{"prompt":"2.5e-06","completion":"15e-06"},
               "supported_parameters":["tools"]}
            ]}
            """;
        OpenRouterFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "url", "ts");
        assertThat(result.models()).hasSize(1);
        assertThat(result.rejectedSuffix()).isEqualTo(5);
        assertThat(result.models().get(0).get("modelId")).isEqualTo("openai/gpt-5.4");
    }

    @Test
    @DisplayName("The Python parity script declares exactly the same duplicate suffixes as this parser")
    void pythonParityScriptDeclaresTheSameSuffixes() throws Exception {
        // scripts/models/sync_openrouter.py exists so a human can verify by hand
        // what this parser does to the live feed. That only works while the two
        // lists agree, and until now "MUST stay identical" was a comment, which
        // is a wish rather than a guard: :batch was missing from both for as
        // long as it took someone to read the feed and notice 76 duplicate rows.
        //
        // Comparing as SETS, not as text: the two languages will never agree on
        // quoting or line breaks, and a test that broke on formatting would be
        // turned off the first time someone reflowed the file.
        Path script = locateRepoFile("scripts/models/sync_openrouter.py");
        String source = Files.readString(script, StandardCharsets.UTF_8);

        Matcher m = Pattern.compile("DUPLICATE_SUFFIXES\\s*=\\s*\\(([^)]*)\\)").matcher(source);
        assertThat(m.find())
                .as("DUPLICATE_SUFFIXES tuple not found in %s - if it was renamed, "
                        + "this parity guard has to be renamed with it, not deleted", script)
                .isTrue();

        Set<String> fromPython = Arrays.stream(m.group(1).split(","))
                .map(String::trim)
                .filter(token -> token.length() > 2)
                .map(token -> token.substring(1, token.length() - 1))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(fromPython)
                .as("sync_openrouter.py and OpenRouterFeedParser must filter the same variants")
                .containsExactlyInAnyOrderElementsOf(
                        Set.copyOf(OpenRouterFeedParser.DUPLICATE_SUFFIXES));
    }

    /**
     * Walk up from the working directory to the repo root and resolve a path
     * under it. Surefire's working directory is the MODULE, not the repo, and
     * hard-coding "../.." breaks the day this module moves.
     *
     * <p>Fails loudly when the file cannot be found rather than skipping: a
     * parity guard that quietly opts out on a path problem is the permeable
     * kind, green forever and checking nothing.
     */
    private static Path locateRepoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("could not locate " + relative
                + " walking up from " + Path.of("").toAbsolutePath());
    }

    @Test
    @DisplayName("Regression: a :batch twin is dropped while its canonical model is kept")
    void dropsBatchTwinsKeepsCanonical() {
        // :batch was the one variant suffix missing from the list, and the
        // highest-volume one by far: measured against the live feed on
        // 2026-09-15, 76 of the 356 ids this parser accepted were :batch twins,
        // so more than a fifth of the OpenRouter catalog was a second copy of a
        // model at roughly half price.
        //
        // Half price is exactly what makes them dangerous rather than merely
        // untidy: in a picker sorted or filtered on cost, the batch twin
        // outranks the model it duplicates, and the user who clicks it gets
        // batch latency semantics they never asked for. The rate belongs on the
        // canonical row's batch price columns, which every feed already fills.
        String fixture = """
            {"data": [
              {"id":"anthropic/claude-opus-5","pricing":{"prompt":"5e-06","completion":"25e-06"},
               "supported_parameters":["tools"]},
              {"id":"anthropic/claude-opus-5:batch","pricing":{"prompt":"2.5e-06","completion":"12.5e-06"},
               "supported_parameters":["tools"]}
            ]}
            """;
        OpenRouterFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "url", "ts");

        assertThat(result.models()).extracting(m -> m.get("modelId"))
                .containsExactly("anthropic/claude-opus-5");
        assertThat(result.rejectedSuffix()).isEqualTo(1);
        // The survivor keeps its OWN interactive price. Dropping the twin must
        // never be allowed to drag the cheaper batch rate onto the field a
        // normal call is billed at.
        assertThat(result.models().get(0).get("priceInput"))
                .hasToString("5.0000");
        // But the batch rate is RELOCATED, not discarded: it lands on the
        // columns that exist for it, the ones the LiteLLM feed already fills.
        // Simply dropping the row would have been a silent downgrade dressed
        // up as a cleanup.
        assertThat(result.models().get(0).get("priceInputBatch")).hasToString("2.5000");
        assertThat(result.models().get(0).get("priceOutputBatch")).hasToString("12.5000");
        // And it lands ONLY there. The floor is what the cheapest ordinary call
        // can be charged, and no ordinary call can obtain the batch rate: a
        // batch endpoint is a different request with different latency. Writing
        // the batch rate to the floor would quote a price nobody can get, which
        // normalise() states in a comment and nothing pinned until now.
        assertThat(result.models().get(0).get("priceFloorInput")).hasToString("5.0000");
        assertThat(result.models().get(0).get("priceFloorOutput")).hasToString("25.0000");
    }

    @Test
    @DisplayName("An unpriced or negatively-priced :batch row stamps nothing on its canonical model")
    void unusableBatchRowCarriesNoRate() {
        // A 0/0 batch row is not a free batch tier, it is an unpriced row, and
        // the accept path already refuses those for the interactive rate. The
        // collector has to agree: writing 0/0 onto a paid model would advertise
        // a rate the provider never offered, and a caller reading
        // priceInputBatch has no way to tell an advertised zero from a missing
        // one. The -1 case is OpenRouter's variable-pricing sentinel.
        String fixture = """
            {"data": [
              {"id":"vendor/free-batch","pricing":{"prompt":"5e-06","completion":"25e-06"},
               "supported_parameters":["tools"]},
              {"id":"vendor/free-batch:batch","pricing":{"prompt":"0","completion":"0"},
               "supported_parameters":["tools"]},
              {"id":"vendor/sentinel-batch","pricing":{"prompt":"5e-06","completion":"25e-06"},
               "supported_parameters":["tools"]},
              {"id":"vendor/sentinel-batch:batch","pricing":{"prompt":"-1","completion":"-1"},
               "supported_parameters":["tools"]}
            ]}
            """;
        OpenRouterFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "url", "ts");

        assertThat(result.models()).extracting(m -> m.get("modelId"))
                .containsExactlyInAnyOrder("vendor/free-batch", "vendor/sentinel-batch");
        assertThat(result.models()).allSatisfy(m -> {
            assertThat(m.get("priceInputBatch")).isNull();
            assertThat(m.get("priceOutputBatch")).isNull();
        });
    }

    @Test
    @DisplayName("A :batch row priced negative on ONE side only is still refused")
    void mixedSignBatchRowCarriesNoRate() {
        // The two rejection lines in collectBatchRates are not one condition,
        // and the both-non-positive one shadows the other for every fixture
        // that prices a row at 0/0 or -1/-1. This drives the gap between them:
        // -1 input with a real output passes the first test and must fail the
        // second, because a negative copied onto a canonical row flows into the
        // billing mirror as a negative rate.
        //
        // Written because the previous fixture's DisplayName claimed to cover
        // "negatively-priced" while the branch it names was unreachable by the
        // whole suite: deleting the line left every test green.
        String fixture = """
            {"data": [
              {"id":"vendor/mixed","pricing":{"prompt":"5e-06","completion":"25e-06"},
               "supported_parameters":["tools"]},
              {"id":"vendor/mixed:batch","pricing":{"prompt":"-1","completion":"2.5e-06"},
               "supported_parameters":["tools"]}
            ]}
            """;
        OpenRouterFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "url", "ts");

        assertThat(result.models()).extracting(m -> m.get("modelId"))
                .containsExactly("vendor/mixed");
        assertThat(result.models().get(0).get("priceInputBatch")).isNull();
        assertThat(result.models().get(0).get("priceOutputBatch")).isNull();
    }

    @Test
    @DisplayName("A malformed :batch row is ignored rather than failing the whole feed")
    void malformedBatchRowDoesNotBreakTheParse() {
        // One vendor's broken entry must not cost the other three hundred rows.
        // A missing batch rate leaves one optional column null, which is where
        // it already was; an exception here would lose the refresh.
        String fixture = """
            {"data": [
              {"id":"vendor/ok","pricing":{"prompt":"5e-06","completion":"25e-06"},
               "supported_parameters":["tools"]},
              {"id":"vendor/ok:batch","supported_parameters":["tools"]},
              "not-an-object",
              {"id":"vendor/other","pricing":{"prompt":"1e-06","completion":"2e-06"},
               "supported_parameters":["tools"]},
              {"id":"vendor/other:batch","pricing":{"prompt":null,"completion":null},
               "supported_parameters":["tools"]}
            ]}
            """;
        OpenRouterFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "url", "ts");

        assertThat(result.models()).extracting(m -> m.get("modelId"))
                .containsExactlyInAnyOrder("vendor/ok", "vendor/other");
        assertThat(result.models()).allSatisfy(m ->
                assertThat(m.get("priceInputBatch")).isNull());
    }

    @Test
    @DisplayName("A :batch row whose canonical model is absent carries nothing and publishes nothing")
    void orphanBatchRowIsDroppedWithoutInventingAModel() {
        // There is no row to put the rate on, and materialising one would
        // publish a model that exists only as a batch endpoint - selectable in
        // the picker, and wrong for every interactive call made through it.
        String fixture = """
            {"data": [
              {"id":"vendor/only-as-batch:batch","pricing":{"prompt":"1e-06","completion":"5e-06"},
               "supported_parameters":["tools"]},
              {"id":"openai/gpt-5.4","pricing":{"prompt":"2.5e-06","completion":"15e-06"},
               "supported_parameters":["tools"]}
            ]}
            """;
        OpenRouterFeedParser.ParseResult result = parser.parse(
                fixture.getBytes(StandardCharsets.UTF_8), "url", "ts");

        assertThat(result.models()).extracting(m -> m.get("modelId"))
                .containsExactly("openai/gpt-5.4");
        // The unrelated survivor must not inherit a rate that was never its own.
        assertThat(result.models().get(0).get("priceInputBatch")).isNull();
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
