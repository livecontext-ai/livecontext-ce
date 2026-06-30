package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 4a.8 (R30) - defensive guard: no OpenAI-compatible provider may emit
 * {@code "strict": true} in its tools payload.
 *
 * <p><b>Why this matters.</b> Under schema slimming (Stage 4a.1) we coerce every
 * parameter {@code type} to {@code "string"} in the slim payload. On the next
 * turn, when the LLM has to replay history, a tool-call that originally carried
 * (say) an integer argument is re-serialised by us with the coerced-to-string
 * schema. OpenAI's strict mode would reject the integer-shaped value against a
 * "string" schema and hard-fail the turn. Keeping strict OFF is a correctness
 * invariant for the slim-schema path, not a style preference.
 *
 * <p>This test fails fast if a future change (say, adding
 * {@code function.strict = true} to {@link AbstractLLMProvider#buildOpenAITools})
 * silently enables strict validation. It exercises every OpenAI-compatible
 * provider we ship, including the generic OpenAI-compatible factory used for
 * ZAI / Groq / custom endpoints - a per-provider override would slip past a
 * single-class test.
 */
@DisplayName("OpenAI-compatible providers - strict:true must NOT leak into the request body (Stage 4a.8)")
class OpenAIStrictModeNotEnabledTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolDefinition sampleTool() {
        return ToolDefinition.builder()
                .name("sample_tool")
                .description("probe")
                .parameters(List.of(
                        ToolParameter.builder().name("query").type("string").description("q").required(true).build(),
                        ToolParameter.builder().name("limit").type("integer").description("cap").build()
                ))
                .build();
    }

    private static CompletionRequest sampleRequest() {
        return CompletionRequest.builder()
                .model("gpt-4o")
                .userPrompt("probe")
                .tools(List.of(sampleTool()))
                .build();
    }

    static Stream<Arguments> openAICompatibleProviders() {
        // Covers every concrete class that routes through buildOpenAITools.
        // Gemini and Claude use their own tool shapes (not OpenAI) and are
        // covered by separate tests; they're not in this parameter list on
        // purpose - putting them here would mean testing the wrong contract.
        return Stream.of(
                Arguments.of("openai", (AbstractLLMProvider) new OpenAIProvider()),
                Arguments.of("deepseek", (AbstractLLMProvider) new DeepSeekProvider()),
                Arguments.of("mistral", (AbstractLLMProvider) new MistralProvider())
        );
    }

    @ParameterizedTest(name = "{0}: built tools payload contains no 'strict' key")
    @MethodSource("openAICompatibleProviders")
    @DisplayName("strict flag absent from every serialised tool entry")
    void noStrictKeyInBuiltTools(String name, AbstractLLMProvider provider) throws Exception {
        // Exercise buildOpenAITools directly - the shared helper that every
        // OpenAI-compatible concrete class delegates to in buildRequestBody.
        List<Map<String, Object>> built = provider.buildOpenAITools(List.of(sampleTool()));
        assertThat(built).as("%s: tools list", name).isNotEmpty();

        for (Map<String, Object> entry : built) {
            // Wrapper level must not carry strict.
            assertThat(entry)
                    .as("%s: wrapper must not contain 'strict'", name)
                    .doesNotContainKey("strict");

            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) entry.get("function");
            assertThat(function).as("%s: function object", name).isNotNull();
            assertThat(function)
                    .as("%s: function object must not contain 'strict'", name)
                    .doesNotContainKey("strict");

            // Belt-and-braces: the serialised JSON must not carry a "strict"
            // key at all - not true, not false, not the stringified form.
            // Any appearance is a regression that must be reviewed.
            String json = JSON.writeValueAsString(entry);
            assertThat(json)
                    .as("%s: serialised tool JSON", name)
                    .doesNotContain("\"strict\"");
        }
    }

    @ParameterizedTest(name = "{0}: full request body contains no \"strict\" key at all")
    @MethodSource("openAICompatibleProviders")
    @DisplayName("strict flag absent from the full buildRequestBody output (no key, no value)")
    void noStrictTrueInFullRequestBody(String name, AbstractLLMProvider provider) throws Exception {
        // Catches providers that might inject strict at the body level or
        // via response_format rather than in the tools array. Harder than
        // the tools-only check: the FULL JSON must not contain the key
        // "strict" in any form - `strict: true`, `strict: "true"`, or a
        // nested `response_format.strict: true` are all forbidden.
        Map<String, Object> body = provider.buildRequestBody(sampleRequest());
        String json = JSON.writeValueAsString(body);
        assertThat(json)
                .as("%s: full request body must not mention 'strict' at all", name)
                .doesNotContain("\"strict\"");
    }

    @ParameterizedTest(name = "{0}: streaming body also free of 'strict'")
    @MethodSource("openAICompatibleProviders")
    @DisplayName("addStreamingRequestOptions must not inject strict on the streaming path")
    void noStrictTrueAfterStreamingOptions(String name, AbstractLLMProvider provider) throws Exception {
        // Shared-agent-lib applies addStreamingRequestOptions AFTER
        // buildRequestBody on the streaming path (see completeStreaming /
        // streamReactive in AbstractLLMProvider). A provider override that
        // added `strict: true` during streaming-options wiring would slip
        // past buildRequestBody-only tests. Exercise the exact sequence.
        Map<String, Object> body = provider.buildRequestBody(sampleRequest());
        body.put("stream", true);
        provider.addStreamingRequestOptions(body);

        String json = JSON.writeValueAsString(body);
        assertThat(json)
                .as("%s: post-streaming-options body must not mention 'strict'", name)
                .doesNotContain("\"strict\"");
    }

    @Test
    @DisplayName("OpenAICompatibleProvider-produced instances also stay strict-off (ZAI / Groq / custom endpoints)")
    void factoryBuiltProvidersAlsoStrictOff() throws Exception {
        // The generic OpenAI-compatible adapter powers ZAI / Groq / custom
        // endpoints through OpenAICompatibleProviderFactory. A per-brand
        // override that sneaks strict on would slip past the @MethodSource
        // list above, so we exercise this path explicitly. Config values
        // are arbitrary - buildOpenAITools doesn't read them.
        AbstractLLMProvider generic = new OpenAICompatibleProvider(
                "test-brand",
                "https://example.invalid/v1/chat/completions",
                "test-key",
                List.of("test-model"),
                99);
        List<Map<String, Object>> tools = generic.buildOpenAITools(List.of(sampleTool()));

        for (Map<String, Object> entry : tools) {
            assertThat(entry).doesNotContainKey("strict");
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) entry.get("function");
            assertThat(function).doesNotContainKey("strict");
        }

        // And the full JSON should never have the flag either.
        String json = JSON.writeValueAsString(tools);
        assertThat(json)
                .doesNotContain("\"strict\":true")
                .doesNotContain("\"strict\": true");
    }
}
