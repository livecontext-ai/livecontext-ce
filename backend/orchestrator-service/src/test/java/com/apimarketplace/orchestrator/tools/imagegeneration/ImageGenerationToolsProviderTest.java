package com.apimarketplace.orchestrator.tools.imagegeneration;

import com.apimarketplace.agent.imagegen.ImageProviderCatalog;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ImageGenerationToolsProvider")
class ImageGenerationToolsProviderTest {

    @Mock ImageGenerationModule module;
    @Mock ToolExecutionContext context;
    @Mock com.apimarketplace.interfaces.client.InterfaceClient interfaceClient;
    @Mock com.apimarketplace.agent.client.AgentClient agentClient;

    private ImageGenerationToolsProvider provider;

    @BeforeEach
    void setUp() {
        // agentClient = null by default - exercise the legacy fallback
        // path (static ImageProviderCatalog list). V156 sidecar coverage
        // lives in helpAvailableModelsFiltersBySidecar below.
        provider = new ImageGenerationToolsProvider(module, interfaceClient, null);
        lenient().when(context.tenantId()).thenReturn("42");
    }

    @Nested
    @DisplayName("Tool definition")
    class Definition {

        @Test
        @DisplayName("category is IMAGE_GENERATION")
        void categoryCorrect() {
            assertThat(provider.getCategory()).isEqualTo(ToolCategory.IMAGE_GENERATION);
        }

        @Test
        @DisplayName("exposes a single 'image_generation' tool")
        void singleTool() {
            List<AgentToolDefinition> tools = provider.getTools();
            assertThat(tools).hasSize(1);
            AgentToolDefinition tool = tools.get(0);
            assertThat(tool.name()).isEqualTo("image_generation");
            assertThat(tool.requiredParameters()).containsExactly("action");
        }

        @Test
        @DisplayName("description mentions both providers and the help-first pattern")
        void descriptionMentionsModels() {
            String desc = provider.getTools().get(0).description();
            assertThat(desc).contains("generate", "help", "credits");
        }
    }

    @Nested
    @DisplayName("dispatch")
    class Dispatch {

        @Test
        @DisplayName("unknown tool name → TOOL_NOT_FOUND")
        void unknownToolName() {
            ToolExecutionResult r = provider.execute("foo", Map.of("action", "generate"), context);
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        }

        @Test
        @DisplayName("missing action → MISSING_PARAMETER")
        void missingAction() {
            ToolExecutionResult r = provider.execute("image_generation", Map.of(), context);
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("invalid action → VALIDATION_ERROR")
        void invalidAction() {
            when(module.canHandle("foobar")).thenReturn(false);
            ToolExecutionResult r = provider.execute("image_generation",
                    Map.of("action", "foobar"), context);
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("action='generate' delegates to module with tenantId from context")
        void generateDelegates() {
            when(module.canHandle("generate")).thenReturn(true);
            when(module.execute(eq("generate"), any(), eq("42"), eq(context)))
                    .thenReturn(java.util.Optional.of(ToolExecutionResult.success(Map.of("ok", true))));

            ToolExecutionResult r = provider.execute("image_generation",
                    Map.of("action", "generate", "prompt", "x"), context);

            assertThat(r.success()).isTrue();
        }
    }

    @Nested
    @DisplayName("help action")
    class Help {

        @Test
        @DisplayName("returns description + actions + available_models + concepts + examples")
        void helpStructure() {
            ToolExecutionResult r = provider.execute("image_generation",
                    Map.of("action", "help"), context);

            assertThat(r.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) r.data();
            assertThat(data).containsKeys("description", "actions", "available_models", "concepts", "examples");
        }

        @Test
        @DisplayName("available_models is built from ImageProviderCatalog (drift-guarded)")
        void availableModelsFromCatalog() {
            ToolExecutionResult r = provider.execute("image_generation",
                    Map.of("action", "help"), context);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) r.data();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> models = (List<Map<String, Object>>) data.get("available_models");

            // OpenAI: 6 entries (2 model families × 3 quality tiers)
            // Google: 2 entries (gemini-2.5-flash-image, gemini-3-pro-image)
            int expected = ImageProviderCatalog.OPENAI.size() + ImageProviderCatalog.GOOGLE.size();
            assertThat(models).hasSize(expected);

            // Spot-check that gpt-image-1.5/medium maps to 34 credits
            // (verifies the suffix-stripping in buildAvailableModels works)
            assertThat(models).anyMatch(m ->
                    "openai".equals(m.get("provider"))
                    && "gpt-image-1.5".equals(m.get("model"))
                    && "medium".equals(m.get("quality"))
                    && Integer.valueOf(34).equals(m.get("credits_per_image")));

            // Spot-check Google's nano-banana = 39 credits
            assertThat(models).anyMatch(m ->
                    "google".equals(m.get("provider"))
                    && "gemini-2.5-flash-image".equals(m.get("model"))
                    && Integer.valueOf(39).equals(m.get("credits_per_image")));
        }

        @Test
        @DisplayName("concepts mentions billing and content_moderation")
        void conceptsCovered() {
            ToolExecutionResult r = provider.execute("image_generation",
                    Map.of("action", "help"), context);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) r.data();
            @SuppressWarnings("unchecked")
            Map<String, Object> concepts = (Map<String, Object>) data.get("concepts");
            assertThat(concepts).containsKeys("billing", "pseudo_model", "content_moderation");
        }

        @Test
        @DisplayName("help does NOT delegate to module")
        void helpDoesNotCallModule() {
            HashMap<String, Object> params = new HashMap<>();
            params.put("action", "help");
            provider.execute("image_generation", params, context);
            org.mockito.Mockito.verifyNoInteractions(module);
        }

        @Test
        @DisplayName("REGRESSION (V156): help.available_models is filtered by the per-category sidecar - admin-disabled rows do NOT appear")
        @SuppressWarnings("unchecked")
        void helpAvailableModelsFiltersBySidecar() {
            // Wire an agentClient that returns a category-overlaid catalog
            // where the admin disabled gpt-image-1.5-low and ALL gpt-image-1-mini
            // models. Only gpt-image-1.5-medium/high + both google models survive.
            ImageGenerationToolsProvider providerWithClient =
                    new ImageGenerationToolsProvider(module, interfaceClient, agentClient);

            Map<String, Object> openaiProvider = Map.of(
                    "name", "openai",
                    "models", List.of(
                            Map.of("id", "gpt-image-1.5-medium", "displayOrder", 1),
                            Map.of("id", "gpt-image-1.5-high", "displayOrder", 2)));
            Map<String, Object> googleProvider = Map.of(
                    "name", "google",
                    "models", List.of(
                            Map.of("id", "gemini-2.5-flash-image", "displayOrder", 3),
                            Map.of("id", "gemini-3-pro-image", "displayOrder", 4)));
            Map<String, Object> overlaidCatalog = Map.of(
                    "providers", List.of(openaiProvider, googleProvider),
                    "category", "image_generation");
            when(agentClient.getModelsInfo("image_generation")).thenReturn(overlaidCatalog);

            ToolExecutionResult r = providerWithClient.execute("image_generation",
                    Map.of("action", "help"), context);
            Map<String, Object> data = (Map<String, Object>) r.data();
            List<Map<String, Object>> models = (List<Map<String, Object>>) data.get("available_models");

            // Disabled: gpt-image-1.5-low + 3 mini variants → 4 rows hidden.
            // Surviving: gpt-image-1.5 medium/high + 2 google = 4 rows.
            assertThat(models).hasSize(4);
            assertThat(models).noneMatch(m -> "gpt-image-1.5".equals(m.get("model")) && "low".equals(m.get("quality")));
            assertThat(models).noneMatch(m -> "gpt-image-1-mini".equals(m.get("model")));
            // Surviving high-quality openai entry is present.
            assertThat(models).anyMatch(m ->
                    "openai".equals(m.get("provider"))
                    && "gpt-image-1.5".equals(m.get("model"))
                    && "high".equals(m.get("quality")));
        }

        @Test
        @DisplayName("REGRESSION (V156 fallback): help falls back to ImageProviderCatalog when agentClient throws - action stays callable on agent-service outage")
        @SuppressWarnings("unchecked")
        void helpFallsBackWhenAgentClientFails() {
            ImageGenerationToolsProvider providerWithClient =
                    new ImageGenerationToolsProvider(module, interfaceClient, agentClient);
            when(agentClient.getModelsInfo("image_generation"))
                    .thenThrow(new RuntimeException("agent-service down"));

            ToolExecutionResult r = providerWithClient.execute("image_generation",
                    Map.of("action", "help"), context);
            Map<String, Object> data = (Map<String, Object>) r.data();
            List<Map<String, Object>> models = (List<Map<String, Object>>) data.get("available_models");

            // Full static catalog renders: 6 OpenAI + 2 Google = 8 rows.
            assertThat(models).hasSize(ImageProviderCatalog.ALL.size());
        }
    }

    /**
     * Iteration-5 audit (E2E): the agent MCP tool result was 1.98 MB even
     * though the persisted Interface entity already carried the bytes - the
     * b64 was being copied into the agent-visible result and never stripped.
     * That blew the harness's tool-result token budget every call. These
     * tests pin the strip behaviour so it doesn't regress.
     */
    @Nested
    @DisplayName("Inline base64 stripping after Interface persistence")
    class StripBase64AfterPersist {

        private com.apimarketplace.interfaces.client.dto.InterfaceDto fakePersisted() {
            com.apimarketplace.interfaces.client.dto.InterfaceDto dto =
                new com.apimarketplace.interfaces.client.dto.InterfaceDto();
            dto.setId(java.util.UUID.fromString("00000000-0000-0000-0000-00000000abcd"));
            dto.setName("test image");
            return dto;
        }

        @Test
        @DisplayName("FileRef passthrough - catalog dehydrator already produced a FileRef → strip just stamps persisted=true")
        void fileRefPassthrough() {
            // v3.0 single chokepoint: catalog dehydrator runs BEFORE OutputProjector
            // in ToolExecutionManager → every image arriving here MUST already be a
            // FileRef Map. The orchestrator strip is now an assertion, not an upload.
            Map<String, Object> imgEntry = new java.util.LinkedHashMap<>();
            imgEntry.put("_type", "file");
            imgEntry.put("path", "42/general/catalog-binary/already_uploaded.png");
            imgEntry.put("name", "already_uploaded.png");
            imgEntry.put("mimeType", "image/png");
            imgEntry.put("size", 999_888);
            imgEntry.put("source_path", "data[0].b64_json");
            imgEntry.put("revised_prompt", "a cat");
            Map<String, Object> rawData = new java.util.LinkedHashMap<>();
            rawData.put("images", List.of(imgEntry));
            rawData.put("count", 1);

            when(module.canHandle("generate")).thenReturn(true);
            when(module.execute(eq("generate"), any(), eq("42"), eq(context)))
                .thenReturn(java.util.Optional.of(ToolExecutionResult.success(rawData)));
            when(context.credentials()).thenReturn(Map.of(
                "conversationId", "conv-1",
                "__messageId__", "msg-1"
            ));
            when(interfaceClient.createOrUpdateImageGenerationInterface(any(), eq("42")))
                .thenReturn(fakePersisted());

            ToolExecutionResult r = provider.execute("image_generation",
                Map.of("action", "generate", "prompt", "a cat"), context);

            assertThat(r.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) r.data();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> images = (List<Map<String, Object>>) data.get("images");
            assertThat(images).hasSize(1);
            assertThat(images.get(0))
                .as("FileRef-shaped entries pass through unchanged - only persisted=true is stamped")
                .containsEntry("path", "42/general/catalog-binary/already_uploaded.png")
                .containsEntry("persisted", Boolean.TRUE);
        }

        @Test
        @DisplayName("n=4 multi-image: every catalog-dehydrated image passes through with persisted=true")
        void multiImagePassthrough() {
            // Regression for the n>1 loop. Every entry arrives here as a FileRef
            // Map (catalog dehydrator did its job pre-projection); the strip
            // hook walks the list and stamps persisted=true on each.
            List<Map<String, Object>> imgs = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Map<String, Object> img = new java.util.LinkedHashMap<>();
                img.put("_type", "file");
                img.put("path", "42/general/catalog-binary/img_" + i + ".png");
                img.put("name", "img_" + i + ".png");
                img.put("mimeType", "image/png");
                img.put("size", 1234L + i);
                img.put("revised_prompt", "prompt-" + i);
                imgs.add(img);
            }
            Map<String, Object> rawData = new java.util.LinkedHashMap<>();
            rawData.put("images", imgs);
            rawData.put("count", 4);

            when(module.canHandle("generate")).thenReturn(true);
            when(module.execute(eq("generate"), any(), eq("42"), eq(context)))
                .thenReturn(java.util.Optional.of(ToolExecutionResult.success(rawData)));
            when(context.credentials()).thenReturn(Map.of(
                "conversationId", "conv-1",
                "__messageId__", "msg-1"
            ));
            when(interfaceClient.createOrUpdateImageGenerationInterface(any(), eq("42")))
                .thenReturn(fakePersisted());

            ToolExecutionResult r = provider.execute("image_generation",
                Map.of("action", "generate", "prompt", "x", "n", 4), context);

            assertThat(r.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) r.data();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> outImages = (List<Map<String, Object>>) data.get("images");
            assertThat(outImages).hasSize(4);
            for (int i = 0; i < 4; i++) {
                Map<String, Object> img = outImages.get(i);
                assertThat(img)
                    .as("image #%d preserves FileRef shape end-to-end (no base64 leak)", i)
                    .doesNotContainKey("base64")
                    .containsEntry("_type", "file")
                    .containsEntry("path", "42/general/catalog-binary/img_" + i + ".png")
                    .containsEntry("revised_prompt", "prompt-" + i)
                    .containsEntry("persisted", Boolean.TRUE);
            }
        }

        @Test
        @DisplayName("Raw base64 reaches strip → DROPPED loudly (catalog regression guard, no ghost record shipped)")
        void rawBase64IsDroppedNotMaskedAsGhost() {
            // Regression guard for the OpenAI 28/04 16:16 incident:
            // pre-fix, ToolExecutionManager ran OutputProjector BEFORE the
            // dehydrator → Jackson's String→BinaryNode coercion turned
            // {@code data[0].b64_json} into a {@code byte[]} that the
            // dehydrator's {@code instanceof String} walker missed → raw b64
            // reached the orchestrator → old strip stamped
            // {mime_type, persisted:true} as a path-less ghost.
            //
            // Post-fix the dehydrator runs first; if for any reason raw b64
            // still arrives here, the strip drops the entry loudly so the
            // bug is visible (broken image > silent ghost).
            String fakeB64 = java.util.Base64.getEncoder().encodeToString(new byte[2000]);
            Map<String, Object> imgEntry = new java.util.LinkedHashMap<>();
            imgEntry.put("base64", fakeB64);
            imgEntry.put("mime_type", "image/png");
            Map<String, Object> rawData = new java.util.LinkedHashMap<>();
            rawData.put("images", List.of(imgEntry));
            rawData.put("count", 1);
            rawData.put("provider", "openai");

            when(module.canHandle("generate")).thenReturn(true);
            when(module.execute(eq("generate"), any(), eq("42"), eq(context)))
                .thenReturn(java.util.Optional.of(ToolExecutionResult.success(rawData)));
            when(context.credentials()).thenReturn(Map.of(
                "conversationId", "conv-1",
                "__messageId__", "msg-1"
            ));
            when(interfaceClient.createOrUpdateImageGenerationInterface(any(), eq("42")))
                .thenReturn(fakePersisted());

            ToolExecutionResult r = provider.execute("image_generation",
                Map.of("action", "generate", "prompt", "a cat"), context);

            assertThat(r.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) r.data();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> images = (List<Map<String, Object>>) data.get("images");
            assertThat(images)
                .as("raw b64 = catalog regression - drop the entry rather than ship a {mime_type, persisted:true} ghost (the OpenAI 28/04 bug)")
                .isEmpty();
        }

        @Test
        @DisplayName("when persistence is skipped (no conversationId), inline base64 is preserved (workflow path back-compat)")
        void base64PreservedWhenNoPersistence() {
            String fakeB64 = "A".repeat(200_000);
            Map<String, Object> imgEntry = new java.util.LinkedHashMap<>();
            imgEntry.put("base64", fakeB64);
            imgEntry.put("mime_type", "image/png");
            Map<String, Object> rawData = new java.util.LinkedHashMap<>();
            rawData.put("images", List.of(imgEntry));
            rawData.put("count", 1);
            ToolExecutionResult moduleResult = ToolExecutionResult.success(rawData);

            when(module.canHandle("generate")).thenReturn(true);
            when(module.execute(eq("generate"), any(), eq("42"), eq(context)))
                .thenReturn(java.util.Optional.of(moduleResult));
            // No credentials → enricher returns raw, no persistence happens.
            when(context.credentials()).thenReturn(Map.of());

            ToolExecutionResult r = provider.execute("image_generation",
                Map.of("action", "generate", "prompt", "a cat"), context);

            assertThat(r.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) r.data();
            // Workflow-only path: the bytes must reach downstream nodes intact.
            // No marker → strip path is a no-op → base64 is preserved.
            assertThat(data).doesNotContainKey("marker");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> images = (List<Map<String, Object>>) data.get("images");
            assertThat(images.get(0)).containsEntry("base64", fakeB64);
        }
    }
}
