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
 * {@link OpenAIImageProvider#generate}. Before v3.0 the provider opted out of
 * dehydration and returned raw base64; now it must detect the FileRef Map the
 * dehydrator left at {@code data[*].b64_json} and surface it via
 * {@link ImageProvider.GeneratedImage#fileRef()}, with the legacy String path
 * still working when the catalog leaves a raw base64 in place.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OpenAIImageProvider - parseResponse")
class OpenAIImageProviderTest {

    @Mock CatalogToolsGateway gateway;
    @Mock ToolExecutionContext context;

    private OpenAIImageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new OpenAIImageProvider(gateway);
    }

    private static ImageProvider.Request request(String tenantId) {
        return new ImageProvider.Request(
                "openai", "gpt-image-1.5", "a cat", 1, "medium", "1024x1024", tenantId);
    }

    @Test
    @DisplayName("FileRef Map at data[0].b64_json → GeneratedImage.fileRef() carries the same Map")
    void detectsFileRefMap() {
        Map<String, Object> fileRef = new LinkedHashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", "catalog-binary/42/img.png");
        fileRef.put("name", "img.png");
        fileRef.put("mimeType", "image/png");
        fileRef.put("size", 12_345);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("data", List.of(Map.of(
                "b64_json", fileRef,
                "revised_prompt", "rev")));

        when(gateway.executeTool(any(), anyMap(), anyString(), anyMap()))
                .thenReturn(new ExecutionResult(true, output, List.of(), List.of()));

        ImageProvider.Response response = provider.generate(request("42"), context);

        assertThat(response.images()).hasSize(1);
        ImageProvider.GeneratedImage img = response.images().get(0);
        assertThat(img.fileRef()).containsEntry("_type", "file")
                .containsEntry("path", "catalog-binary/42/img.png");
        assertThat(img.base64()).isNull();
        assertThat(img.revisedPrompt()).isEqualTo("rev");
    }

    @Test
    @DisplayName("Legacy raw base64 string at data[0].b64_json → ofBase64 fallback")
    void legacyBase64Fallback() {
        Map<String, Object> output = Map.of(
                "data", List.of(Map.of("b64_json", "QkFTRTY0PT0=")));

        when(gateway.executeTool(any(), anyMap(), anyString(), anyMap()))
                .thenReturn(new ExecutionResult(true, output, List.of(), List.of()));

        ImageProvider.Response response = provider.generate(request("42"), context);

        assertThat(response.images()).hasSize(1);
        ImageProvider.GeneratedImage img = response.images().get(0);
        assertThat(img.fileRef()).isNull();
        assertThat(img.base64()).isEqualTo("QkFTRTY0PT0=");
        assertThat(img.mimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("Map without _type='file' is NOT treated as FileRef → falls through to base64 branch")
    void mapWithoutTypeFileIsNotFileRef() {
        // Defensive: only Maps that explicitly self-identify as FileRef should
        // bypass the legacy path. A random Map (catalog projection oddity) must
        // not silently masquerade as a FileRef. The fallback's exact rendering
        // is not the contract under test - only the branch decision is.
        Map<String, Object> notAFileRef = Map.of("foo", "bar");
        Map<String, Object> output = Map.of(
                "data", List.of(Map.of("b64_json", notAFileRef)));

        when(gateway.executeTool(any(), anyMap(), anyString(), anyMap()))
                .thenReturn(new ExecutionResult(true, output, List.of(), List.of()));

        ImageProvider.Response response = provider.generate(request("42"), context);

        ImageProvider.GeneratedImage img = response.images().get(0);
        assertThat(img.fileRef()).isNull();          // not the FileRef branch
        assertThat(img.base64()).isNotNull();        // fell through to base64 branch
    }
}
