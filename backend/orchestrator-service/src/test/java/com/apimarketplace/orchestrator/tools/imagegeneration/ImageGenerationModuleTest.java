package com.apimarketplace.orchestrator.tools.imagegeneration;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ImageGenerationModule")
class ImageGenerationModuleTest {

    @Mock CreditConsumptionClient creditClient;
    @Mock ImageProvider openAi;
    @Mock ImageProvider google;
    @Mock ToolExecutionContext context;

    private ImageGenerationModule module;

    private static final String TENANT = "42";

    @BeforeEach
    void setUp() {
        lenient().when(openAi.providerKey()).thenReturn("openai");
        lenient().when(google.providerKey()).thenReturn("google");
        module = new ImageGenerationModule(List.of(openAi, google), creditClient);
    }

    private static ImageProvider.GeneratedImage img(int idx) {
        return ImageProvider.GeneratedImage.ofBase64("base64-" + idx, "image/png", null);
    }

    @Nested
    @DisplayName("Param validation")
    class Validation {

        @Test
        @DisplayName("missing prompt → MISSING_PARAMETER, no upstream call")
        void missingPromptFails() {
            Optional<ToolExecutionResult> out = module.execute(
                    "generate", Map.of(), TENANT, context);

            assertThat(out.get().success()).isFalse();
            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            verifyNoInteractions(openAi, google, creditClient);
        }

        @Test
        @DisplayName("n=0 → INVALID_PARAMETER_VALUE")
        void invalidNTooLow() {
            Optional<ToolExecutionResult> out = module.execute(
                    "generate", Map.of("prompt", "x", "n", 0), TENANT, context);

            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        }

        @Test
        @DisplayName("n=11 → INVALID_PARAMETER_VALUE (cap=10)")
        void invalidNTooHigh() {
            Optional<ToolExecutionResult> out = module.execute(
                    "generate", Map.of("prompt", "x", "n", 11), TENANT, context);

            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        }

        @Test
        @DisplayName("unknown provider/model/quality combo → INVALID_PARAMETER_VALUE before pricing call")
        void unknownComboFailsFast() {
            Optional<ToolExecutionResult> out = module.execute(
                    "generate", Map.of(
                            "prompt", "x",
                            "provider", "openai",
                            "model", "made-up",
                            "quality", "low"),
                    TENANT, context);

            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
            verifyNoInteractions(creditClient, openAi, google);
        }

        @Test
        @DisplayName("non-existent provider → INVALID_PARAMETER_VALUE")
        void unknownProvider() {
            Optional<ToolExecutionResult> out = module.execute(
                    "generate", Map.of(
                            "prompt", "x",
                            "provider", "stability"),
                    TENANT, context);

            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
            verifyNoInteractions(openAi, google, creditClient);
        }
    }

    @Nested
    @DisplayName("Tenant resolution")
    class TenantResolution {

        @Test
        @DisplayName("null tenantId → MISSING_PARAMETER, no provider call (catalog needs X-User-Id)")
        void nullTenantFailsFast() {
            org.mockito.Mockito.lenient().when(creditClient.hasPricing("openai", "gpt-image-1.5-medium")).thenReturn(true);

            Optional<ToolExecutionResult> out = module.execute(
                    "generate", Map.of("prompt", "x"), null, context);

            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(out.get().error()).contains("tenantId");
            verify(openAi, never()).generate(any(), any());
        }

        @Test
        @DisplayName("blank tenantId → MISSING_PARAMETER")
        void blankTenantFailsFast() {
            org.mockito.Mockito.lenient().when(creditClient.hasPricing("openai", "gpt-image-1.5-medium")).thenReturn(true);

            Optional<ToolExecutionResult> out = module.execute(
                    "generate", Map.of("prompt", "x"), "  ", context);

            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }
    }

    @Nested
    @DisplayName("Happy path (result shape - billing now lives in CatalogBillingDispatcher)")
    class HappyPath {

        @Test
        @DisplayName("default model + n=1 → result data carries images/count/provider/billing_model")
        void defaultsN1() {
            org.mockito.Mockito.lenient().when(creditClient.hasPricing("openai", "gpt-image-1.5-medium")).thenReturn(true);
            when(openAi.generate(any(), any())).thenReturn(new ImageProvider.Response(List.of(img(0))));

            Optional<ToolExecutionResult> out = module.execute(
                    "generate", Map.of("prompt", "a cat"), TENANT, context);

            assertThat(out.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) out.get().data();
            assertThat(data).containsEntry("count", 1);
            assertThat(data).containsEntry("provider", "openai");
            assertThat(data).containsEntry("billing_model", "gpt-image-1.5-medium");
            assertThat((List<?>) data.get("images")).hasSize(1);
        }

        @Test
        @DisplayName("n=4 requested, 4 returned → result.count=4")
        void n4Full() {
            org.mockito.Mockito.lenient().when(creditClient.hasPricing("openai", "gpt-image-1-mini-low")).thenReturn(true);
            when(openAi.generate(any(), any())).thenReturn(new ImageProvider.Response(
                    List.of(img(0), img(1), img(2), img(3))));

            Optional<ToolExecutionResult> out = module.execute(
                    "generate", Map.of(
                            "prompt", "a banana",
                            "model", "gpt-image-1-mini",
                            "quality", "low",
                            "n", 4),
                    TENANT, context);

            assertThat(out.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) out.get().data();
            assertThat(data).containsEntry("count", 4);
            assertThat(data).containsEntry("billing_model", "gpt-image-1-mini-low");
        }

        @Test
        @DisplayName("Google nano-banana → result carries the model's own billing key (no quality suffix)")
        void googleBillingKey() {
            org.mockito.Mockito.lenient().when(creditClient.hasPricing("google", "gemini-2.5-flash-image")).thenReturn(true);
            when(google.generate(any(), any())).thenReturn(new ImageProvider.Response(List.of(img(0))));

            Optional<ToolExecutionResult> out = module.execute("generate", Map.of(
                    "prompt", "a banana",
                    "provider", "google",
                    "model", "gemini-2.5-flash-image"), TENANT, context);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) out.get().data();
            assertThat(data).containsEntry("provider", "google");
            assertThat(data).containsEntry("billing_model", "gemini-2.5-flash-image");
        }

        @Test
        @DisplayName("module no longer fires consumeCreditsAsync - billing centralized in dispatcher")
        void moduleDoesNotBill() {
            org.mockito.Mockito.lenient().when(creditClient.hasPricing("openai", "gpt-image-1.5-medium")).thenReturn(true);
            when(openAi.generate(any(), any())).thenReturn(new ImageProvider.Response(List.of(img(0))));

            module.execute("generate", Map.of("prompt", "x"), TENANT, context);

            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("Failure paths")
    class Failures {

        @Test
        @DisplayName("provider returns 0 images → EXECUTION_FAILED")
        void zeroImagesNoBill() {
            org.mockito.Mockito.lenient().when(creditClient.hasPricing("openai", "gpt-image-1.5-medium")).thenReturn(true);
            when(openAi.generate(any(), any())).thenReturn(new ImageProvider.Response(List.of()));

            Optional<ToolExecutionResult> out = module.execute(
                    "generate", Map.of("prompt", "x"), TENANT, context);

            assertThat(out.get().success()).isFalse();
            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        }

        @Test
        @DisplayName("CONTENT_BLOCKED maps to VALIDATION_ERROR")
        void contentBlocked() {
            org.mockito.Mockito.lenient().when(creditClient.hasPricing("openai", "gpt-image-1.5-medium")).thenReturn(true);
            when(openAi.generate(any(), any())).thenThrow(new ImageGenerationException(
                    ImageGenerationException.Kind.CONTENT_BLOCKED, "policy violation"));

            Optional<ToolExecutionResult> out = module.execute(
                    "generate", Map.of("prompt", "x"), TENANT, context);

            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("RATE_LIMITED maps to RATE_LIMITED")
        void rateLimited() {
            org.mockito.Mockito.lenient().when(creditClient.hasPricing("openai", "gpt-image-1.5-medium")).thenReturn(true);
            when(openAi.generate(any(), any())).thenThrow(new ImageGenerationException(
                    ImageGenerationException.Kind.RATE_LIMITED, "429"));

            Optional<ToolExecutionResult> out = module.execute(
                    "generate", Map.of("prompt", "x"), TENANT, context);

            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.RATE_LIMITED);
        }
    }

    @Nested
    @DisplayName("canHandle")
    class CanHandle {

        @Test
        @DisplayName("recognises only 'generate'")
        void onlyGenerate() {
            assertThat(module.canHandle("generate")).isTrue();
            assertThat(module.canHandle("help")).isFalse(); // help is on the provider, not the module
            assertThat(module.canHandle("edit")).isFalse();
            assertThat(module.canHandle("foo")).isFalse();
        }
    }

    /**
     * v3.0 reconciliation: the canonical agent-visible shape is a FileRef
     * Map (no inline base64). These guards regress the contract:
     *   1. FileRef keys flow through to data.images[]
     *   2. The presigned {@code url} is DROPPED before reaching the agent
     *      (60-min TTL - agents cache → 403 later)
     *   3. {@code persisted=true} is stamped, matching the legacy strip path
     *   4. Mixed shapes (FileRef + base64 fallback) are projected per entry
     */
    @Nested
    @DisplayName("v3.0 FileRef result projection")
    class FileRefProjection {

        private static ImageProvider.GeneratedImage fileRefImg(String s3Key) {
            Map<String, Object> ref = new java.util.LinkedHashMap<>();
            ref.put("_type", "file");
            ref.put("path", s3Key);
            ref.put("name", "img.png");
            ref.put("mimeType", "image/png");
            ref.put("size", 12_345);
            ref.put("url", "https://signed.example/abc?X-Amz-...");      // must be dropped
            ref.put("source_path", "data[0].b64_json");
            return ImageProvider.GeneratedImage.ofFileRef(ref, "rev prompt");
        }

        @Test
        @DisplayName("FileRef path projects whitelisted keys and stamps persisted=true")
        @SuppressWarnings("unchecked")
        void fileRefProjectsWhitelistAndPersistedFlag() {
            org.mockito.Mockito.lenient().when(creditClient.hasPricing("openai", "gpt-image-1.5-medium")).thenReturn(true);
            when(openAi.generate(any(), any())).thenReturn(
                    new ImageProvider.Response(List.of(fileRefImg("catalog-binary/42/img.png"))));

            Optional<ToolExecutionResult> out = module.execute(
                    "generate", Map.of("prompt", "x"), TENANT, context);

            Map<String, Object> data = (Map<String, Object>) out.get().data();
            List<Map<String, Object>> images = (List<Map<String, Object>>) data.get("images");
            assertThat(images).hasSize(1);
            Map<String, Object> img = images.get(0);

            assertThat(img).containsEntry("_type", "file");
            assertThat(img).containsEntry("path", "catalog-binary/42/img.png");
            assertThat(img).containsEntry("name", "img.png");
            assertThat(img).containsEntry("mimeType", "image/png");
            assertThat(img).containsEntry("size", 12_345);
            assertThat(img).containsEntry("source_path", "data[0].b64_json");
            assertThat(img).containsEntry("revised_prompt", "rev prompt");
            assertThat(img).containsEntry("persisted", true);
        }

        @Test
        @DisplayName("Presigned 'url' is DROPPED - agents must not cache 60-min TTL links")
        @SuppressWarnings("unchecked")
        void urlIsStrippedFromAgentVisibleResult() {
            org.mockito.Mockito.lenient().when(creditClient.hasPricing("openai", "gpt-image-1.5-medium")).thenReturn(true);
            when(openAi.generate(any(), any())).thenReturn(
                    new ImageProvider.Response(List.of(fileRefImg("k/foo.png"))));

            Optional<ToolExecutionResult> out = module.execute(
                    "generate", Map.of("prompt", "x"), TENANT, context);

            Map<String, Object> data = (Map<String, Object>) out.get().data();
            Map<String, Object> img = ((List<Map<String, Object>>) data.get("images")).get(0);
            assertThat(img).doesNotContainKey("url");
            assertThat(img).doesNotContainKey("base64"); // FileRef path → no inline bytes
        }

        @Test
        @DisplayName("Mixed FileRef + base64 fallback → each entry projected per its shape")
        @SuppressWarnings("unchecked")
        void mixedShapesAreProjectedPerEntry() {
            org.mockito.Mockito.lenient().when(creditClient.hasPricing("openai", "gpt-image-1.5-medium")).thenReturn(true);
            when(openAi.generate(any(), any())).thenReturn(new ImageProvider.Response(List.of(
                    fileRefImg("k/a.png"),
                    ImageProvider.GeneratedImage.ofBase64("BASE64==", "image/png", null))));

            Optional<ToolExecutionResult> out = module.execute(
                    "generate", Map.of("prompt", "x"), TENANT, context);

            Map<String, Object> data = (Map<String, Object>) out.get().data();
            List<Map<String, Object>> images = (List<Map<String, Object>>) data.get("images");

            assertThat(images.get(0)).containsKey("path").doesNotContainKey("base64");
            assertThat(images.get(1)).containsEntry("base64", "BASE64==").doesNotContainKey("path");
            assertThat(images.get(1)).doesNotContainKey("persisted"); // legacy fallback - strip path stamps it later
        }
    }
}
