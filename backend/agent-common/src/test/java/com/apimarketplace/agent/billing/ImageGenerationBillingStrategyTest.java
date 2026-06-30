package com.apimarketplace.agent.billing;

import com.apimarketplace.common.billing.catalog.CatalogBillingContext;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ImageGenerationBillingStrategy")
class ImageGenerationBillingStrategyTest {

    @Mock CreditConsumptionClient creditClient;

    private ImageGenerationBillingStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ImageGenerationBillingStrategy(creditClient);
    }

    private static Map<String, Object> openAiResponse(int imageCount) {
        List<Map<String, Object>> data = new java.util.ArrayList<>();
        for (int i = 0; i < imageCount; i++) {
            data.add(Map.of("b64_json", "base64-" + i));
        }
        Map<String, Object> r = new HashMap<>();
        r.put("data", data);
        return r;
    }

    private static Map<String, Object> geminiResponse(boolean withImage) {
        if (!withImage) return Map.of("candidates", List.of());
        return Map.of("candidates", List.of(Map.of(
                "content", Map.of("parts", List.of(
                        Map.of("inlineData", Map.of("data", "base64-x", "mimeType", "image/png")))))));
    }

    private static CatalogBillingContext ctx(String toolId, Map<String, Object> request,
                                               Map<String, Object> response, String credentialSource) {
        return new CatalogBillingContext(
                "tenant-42", toolId, request, response, credentialSource,
                "stream-1", "tc-1", null, 0);
    }

    @Nested
    @DisplayName("OpenAI billing")
    class OpenAi {

        @Test
        @DisplayName("platform-key + n=1 → IMAGE_GENERATION debit, imageCount=1")
        void platformN1() {
            Map<String, Object> req = Map.of("model", "gpt-image-1.5", "quality", "medium", "n", 1);
            Map<String, Object> resp = openAiResponse(1);

            strategy.bill(ctx("openai/openai-create-image", req, resp, "platform"));

            verify(creditClient).consumeCreditsAsync(
                    eq("tenant-42"), eq("IMAGE_GENERATION"),
                    eq("image-generation:CHAT:stream-1:tc-1:0"),
                    eq("openai"), eq("gpt-image-1.5-medium"), eq(1));
        }

        @Test
        @DisplayName("platform-key + n=4 returned → imageCount=4")
        void platformN4() {
            Map<String, Object> req = Map.of("model", "gpt-image-1-mini", "quality", "low", "n", 4);
            Map<String, Object> resp = openAiResponse(4);

            strategy.bill(ctx("openai/openai-create-image", req, resp, "platform"));

            verify(creditClient).consumeCreditsAsync(
                    anyString(), eq("IMAGE_GENERATION"), anyString(),
                    eq("openai"), eq("gpt-image-1-mini-low"), eq(4));
        }

        @Test
        @DisplayName("partial success: requested 4, response has 2 → bills 2 (NOT 4)")
        void partialSuccess() {
            Map<String, Object> req = Map.of("model", "gpt-image-1.5", "quality", "high", "n", 4);
            Map<String, Object> resp = openAiResponse(2);

            strategy.bill(ctx("openai/openai-create-image", req, resp, "platform"));

            verify(creditClient).consumeCreditsAsync(
                    anyString(), anyString(), anyString(),
                    anyString(), anyString(), eq(2));
        }

        @Test
        @DisplayName("user-key (BYOK) → IMAGE_GENERATION_BYOK trace, same imageCount")
        void byokTrace() {
            Map<String, Object> req = Map.of("model", "gpt-image-1.5", "quality", "medium", "n", 1);
            Map<String, Object> resp = openAiResponse(1);

            strategy.bill(ctx("openai/openai-create-image", req, resp, "user"));

            verify(creditClient).consumeCreditsAsync(
                    anyString(), eq("IMAGE_GENERATION_BYOK"), anyString(),
                    eq("openai"), eq("gpt-image-1.5-medium"), eq(1));
        }

        @Test
        @DisplayName("0 images returned → no bill")
        void zeroImages() {
            Map<String, Object> req = Map.of("model", "gpt-image-1.5", "quality", "medium");
            Map<String, Object> resp = openAiResponse(0);

            strategy.bill(ctx("openai/openai-create-image", req, resp, "platform"));

            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("missing 'model' in request → no bill (fail-safe)")
        void missingModel() {
            Map<String, Object> req = Map.of("quality", "medium");
            strategy.bill(ctx("openai/openai-create-image", req, openAiResponse(1), "platform"));
            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("unknown quality → no billing model resolved → no bill")
        void unknownQuality() {
            Map<String, Object> req = Map.of("model", "gpt-image-1.5", "quality", "ultra");
            strategy.bill(ctx("openai/openai-create-image", req, openAiResponse(1), "platform"));
            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("response wrapped under 'result' (catalog projection variant) → still parses")
        void wrappedResult() {
            Map<String, Object> req = Map.of("model", "gpt-image-1.5", "quality", "medium");
            Map<String, Object> resp = Map.of("result", openAiResponse(2));

            strategy.bill(ctx("openai/openai-create-image", req, resp, "platform"));

            verify(creditClient).consumeCreditsAsync(
                    anyString(), anyString(), anyString(),
                    anyString(), anyString(), eq(2));
        }
    }

    @Nested
    @DisplayName("Gemini billing")
    class Gemini {

        @Test
        @DisplayName("platform-key + 1 image → bills 1 with bare model name")
        void platformImage() {
            Map<String, Object> req = Map.of("model", "gemini-2.5-flash-image");
            strategy.bill(ctx("google-gemini/google-gemini-generate-content", req, geminiResponse(true), "platform"));

            verify(creditClient).consumeCreditsAsync(
                    anyString(), eq("IMAGE_GENERATION"), anyString(),
                    eq("google"), eq("gemini-2.5-flash-image"), eq(1));
        }

        @Test
        @DisplayName("user-key BYOK → IMAGE_GENERATION_BYOK")
        void userByok() {
            Map<String, Object> req = Map.of("model", "gemini-3-pro-image");
            strategy.bill(ctx("google-gemini/google-gemini-generate-content", req, geminiResponse(true), "user"));

            verify(creditClient).consumeCreditsAsync(
                    anyString(), eq("IMAGE_GENERATION_BYOK"), anyString(),
                    eq("google"), eq("gemini-3-pro-image"), eq(1));
        }

        @Test
        @DisplayName("no candidates (content-mod blocked entire response) → no bill")
        void emptyCandidates() {
            Map<String, Object> req = Map.of("model", "gemini-2.5-flash-image");
            strategy.bill(ctx("google-gemini/google-gemini-generate-content", req, geminiResponse(false), "platform"));

            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("non-image gemini call (text generation) → model not in image catalog → no bill")
        void textGenSkipped() {
            // generate_content can also be used for text - model is gemini-2.5-flash (text only)
            Map<String, Object> req = Map.of("model", "gemini-2.5-flash");
            // Even with what would be image-shaped data, text models aren't image-billed
            strategy.bill(ctx("google-gemini/google-gemini-generate-content", req, geminiResponse(true), "platform"));

            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("handledToolIds registration")
    class Registration {

        @Test
        @DisplayName("declares both openai/openai-create-image and google-gemini/google-gemini-generate-content")
        void handledIds() {
            assertThat(strategy.handledToolIds())
                    .containsExactlyInAnyOrder("openai/openai-create-image", "google-gemini/google-gemini-generate-content");
        }
    }

    @Nested
    @DisplayName("Idempotency sourceId - chat scope vs UUID fallback")
    class Idempotency {

        @Test
        @DisplayName("streamId + toolCallId present → chat-scope sourceId (IMAGE_GENERATION:CHAT:<stream>:<tc>:<index>)")
        void chatScopeWhenIdsPresent() {
            Map<String, Object> req = Map.of("model", "gpt-image-1.5", "quality", "medium");
            CatalogBillingContext c = new CatalogBillingContext(
                    "tenant-1", "openai/openai-create-image", req, openAiResponse(1),
                    "platform", "stream-A", "tc-B", null, 7);

            strategy.bill(c);

            verify(creditClient).consumeCreditsAsync(
                    eq("tenant-1"), eq("IMAGE_GENERATION"),
                    eq("image-generation:CHAT:stream-A:tc-B:7"),
                    eq("openai"), eq("gpt-image-1.5-medium"), eq(1));
        }

        @Test
        @DisplayName("missing streamId → UUID fallback prefix; two calls in a row produce DIFFERENT sourceIds (no double-bill protection - at-least-once)")
        void uuidFallbackWhenIdsMissing() {
            Map<String, Object> req = Map.of("model", "gpt-image-1.5", "quality", "medium");
            CatalogBillingContext missing = new CatalogBillingContext(
                    "tenant-1", "openai/openai-create-image", req, openAiResponse(1),
                    "platform", null, null, null, 0);

            strategy.bill(missing);
            strategy.bill(missing);

            // Capture the two sourceIds - they must both start with the
            // FALLBACK prefix and they must be DIFFERENT (UUIDs change).
            // This documents the at-least-once degradation when the agent
            // path drops idempotency keys; downstream auth-service
            // dead-letter retry would deduplicate, but the strategy itself
            // does not.
            org.mockito.ArgumentCaptor<String> sourceIdCaptor =
                    org.mockito.ArgumentCaptor.forClass(String.class);
            verify(creditClient, org.mockito.Mockito.times(2)).consumeCreditsAsync(
                    anyString(), anyString(), sourceIdCaptor.capture(),
                    anyString(), anyString(), anyInt());

            List<String> ids = sourceIdCaptor.getAllValues();
            assertThat(ids).hasSize(2);
            assertThat(ids.get(0)).startsWith("image-generation:FALLBACK:");
            assertThat(ids.get(1)).startsWith("image-generation:FALLBACK:");
            assertThat(ids.get(0))
                    .as("two fallback sourceIds must NOT collide (UUID per call)")
                    .isNotEqualTo(ids.get(1));
        }

        @Test
        @DisplayName("only streamId present, toolCallId missing → still falls back to UUID (both keys required for chat scope)")
        void partialIdsAlsoFallBack() {
            Map<String, Object> req = Map.of("model", "gpt-image-1.5", "quality", "medium");
            CatalogBillingContext partial = new CatalogBillingContext(
                    "tenant-1", "openai/openai-create-image", req, openAiResponse(1),
                    "platform", "stream-only", null, null, 0);

            strategy.bill(partial);

            org.mockito.ArgumentCaptor<String> sourceIdCaptor =
                    org.mockito.ArgumentCaptor.forClass(String.class);
            verify(creditClient).consumeCreditsAsync(
                    anyString(), anyString(), sourceIdCaptor.capture(),
                    anyString(), anyString(), anyInt());

            assertThat(sourceIdCaptor.getValue()).startsWith("image-generation:FALLBACK:");
        }
    }
}
