package com.apimarketplace.orchestrator.tools.imagegeneration;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.orchestrator.services.impl.CatalogToolsGateway;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the v3.0 catalog-FileRef path in
 * {@link GeminiImageProvider#generate}. Gemini returns one image per call -
 * the dehydrator replaces the raw base64 at
 * {@code candidates[0].content.parts[].inlineData.data} with a FileRef Map.
 * The previous bug (now fixed) was {@code String.valueOf(map)} producing a
 * literal {@code "{_type=file, path=...}"} that ended up in chat-card data
 * URIs as a broken image.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeminiImageProvider - parseResponse")
class GeminiImageProviderTest {

    @Mock CatalogToolsGateway gateway;
    @Mock ToolExecutionContext context;

    private GeminiImageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GeminiImageProvider(gateway);
    }

    private static ImageProvider.Request request(String tenantId) {
        return new ImageProvider.Request(
                "google", "gemini-2.5-flash-image", "a cat", 1, "default", "1024x1024", tenantId);
    }

    private static Map<String, Object> candidatesWithInlineData(Object data, String mime) {
        Map<String, Object> inlineData = new LinkedHashMap<>();
        inlineData.put("data", data);
        if (mime != null) inlineData.put("mimeType", mime);

        return Map.of("candidates", List.of(Map.of(
                "content", Map.of(
                        "parts", List.of(Map.of("inlineData", inlineData))))));
    }

    @Test
    @DisplayName("FileRef Map at inlineData.data → GeneratedImage.fileRef() carries the Map")
    void detectsFileRefMap() {
        Map<String, Object> fileRef = new LinkedHashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", "catalog-binary/42/banana.png");
        fileRef.put("mimeType", "image/png");
        fileRef.put("size", 9_876);

        when(gateway.executeTool(any(), anyMap(), anyString(), anyMap()))
                .thenReturn(new ExecutionResult(true,
                        candidatesWithInlineData(fileRef, "image/png"),
                        List.of(), List.of()));

        ImageProvider.Response response = provider.generate(request("42"), context);

        assertThat(response.images()).hasSize(1);
        ImageProvider.GeneratedImage img = response.images().get(0);
        assertThat(img.fileRef())
                .containsEntry("_type", "file")
                .containsEntry("path", "catalog-binary/42/banana.png");
        assertThat(img.base64()).isNull();
    }

    @Test
    @DisplayName("Legacy raw base64 string at inlineData.data → ofBase64 fallback with mime preserved")
    void legacyBase64Fallback() {
        when(gateway.executeTool(any(), anyMap(), anyString(), anyMap()))
                .thenReturn(new ExecutionResult(true,
                        candidatesWithInlineData("QkFTRTY0PT0=", "image/png"),
                        List.of(), List.of()));

        ImageProvider.Response response = provider.generate(request("42"), context);

        ImageProvider.GeneratedImage img = response.images().get(0);
        assertThat(img.fileRef()).isNull();
        assertThat(img.base64()).isEqualTo("QkFTRTY0PT0=");
        assertThat(img.mimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("Empty candidates → CONTENT_BLOCKED (upstream content moderation)")
    void emptyCandidatesIsContentBlocked() {
        when(gateway.executeTool(any(), anyMap(), anyString(), anyMap()))
                .thenReturn(new ExecutionResult(true, Map.of("candidates", List.of()),
                        List.of(), List.of()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                provider.generate(request("42"), context))
                .isInstanceOf(ImageGenerationException.class)
                .hasMessageContaining("content moderation");
    }
}
