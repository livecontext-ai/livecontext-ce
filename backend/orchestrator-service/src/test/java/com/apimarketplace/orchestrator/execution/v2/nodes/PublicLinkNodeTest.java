package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.common.storage.signing.ShowcaseUrlSigner;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.file.PublicLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PublicLinkNode}: successful mint, the tenant-ownership guard (a
 * workflow must NEVER mint a public link for another tenant's file), unresolved-file and
 * disabled-installation failures, and TTL clamping.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PublicLinkNode")
class PublicLinkNodeTest {

    private static final String OWN_KEY = "tenant-1/wf/run/node/clip.mp4";
    private static final String FOREIGN_KEY = "tenant-9/wf/run/node/secret.pdf";

    @Mock private WorkflowPlan mockPlan;
    @Mock private V2TemplateAdapter templateAdapter;

    private ExecutionContext context;
    private PublicLinkService realService;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
        realService = new PublicLinkService(
            new ShowcaseUrlSigner("unit-test-secret"), "https://livecontext.example");
    }

    private PublicLinkNode node(String fileExpr, Integer ttl, PublicLinkService service) {
        PublicLinkNode node = new PublicLinkNode("core:share", fileExpr, ttl, null);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.getPublicLinkService()).thenReturn(service);
        when(registry.getTemplateAdapter()).thenReturn(templateAdapter);
        node.acceptServices(registry);
        return node;
    }

    private void stubResolvedFile(String key) {
        Map<String, Object> fileRef = new HashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", key);
        fileRef.put("name", "clip.mp4");
        fileRef.put("mimeType", "video/mp4");
        when(templateAdapter.resolveTemplates(anyMap(), any()))
            .thenReturn(Map.of("__expr__", fileRef));
    }

    @Test
    @DisplayName("own-tenant file -> success with absolute public url, expiry and FileRef echo")
    void ownFileMintsPublicUrl() {
        stubResolvedFile(OWN_KEY);
        NodeExecutionResult result = node("{{core:dl.output.file}}", 60, realService).execute(context);

        assertTrue(result.isSuccess());
        String url = (String) result.output().get("url");
        assertNotNull(url);
        assertTrue(url.startsWith("https://livecontext.example/api/files/proxy-signed?key="));
        assertNotNull(result.output().get("expires_at"));
        assertEquals(60, result.output().get("ttl_minutes"));
        @SuppressWarnings("unchecked")
        Map<String, Object> echoed = (Map<String, Object>) result.output().get("file");
        assertEquals(OWN_KEY, echoed.get("path"));
    }

    @Test
    @DisplayName("FOREIGN tenant file -> node FAILS and no url is minted (ownership guard)")
    void foreignFileRefused() {
        stubResolvedFile(FOREIGN_KEY);
        NodeExecutionResult result = node("{{core:dl.output.file}}", 60, realService).execute(context);

        assertFalse(result.isSuccess());
        assertNull(result.output().get("url"));
        assertTrue(result.errorMessage().orElse("").contains("does not belong"),
            "failure must state the ownership refusal, got: " + result.errorMessage());
    }

    @Test
    @DisplayName("expression resolving to a plain string (not a FileRef) -> FAILS with actionable message")
    void nonFileRefResolutionFails() {
        when(templateAdapter.resolveTemplates(anyMap(), any()))
            .thenReturn(Map.of("__expr__", "https://example.com/not-a-fileref.mp4"));
        NodeExecutionResult result = node("{{mcp:x.output.url}}", 60, realService).execute(context);

        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().orElse("").contains("WHOLE FileRef"));
    }

    @Test
    @DisplayName("public links disabled on the installation -> FAILS with the enablement message")
    void disabledInstallationFails() {
        PublicLinkService disabled = new PublicLinkService(new ShowcaseUrlSigner(""), "");
        NodeExecutionResult result = node("{{core:dl.output.file}}", 60, disabled).execute(context);

        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().orElse("").contains("not enabled"));
    }

    @Test
    @DisplayName("missing file expression -> FAILS")
    void missingFileFails() {
        NodeExecutionResult result = node(null, 60, realService).execute(context);
        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().orElse("").contains("file is required"));
    }

    @Test
    @DisplayName("traversal segment ('..') in an own-tenant-prefixed key -> FAILS with 'invalid segment' and no url")
    void traversalSegmentRefused() {
        // Starts with "tenant-1/" so it PASSES the tenant-prefix ownership check;
        // only the dedicated ".." guard stands between this key and a signed link.
        stubResolvedFile("tenant-1/../tenant-9/file.mp4");
        NodeExecutionResult result = node("{{core:dl.output.file}}", 60, realService).execute(context);

        assertFalse(result.isSuccess());
        assertNull(result.output().get("url"));
        assertTrue(result.errorMessage().orElse("").contains("invalid segment"),
            "failure must state the traversal refusal, got: " + result.errorMessage());
    }

    @Test
    @DisplayName("CE default: blank signing secret with a NON-blank public URL -> FAILS with the 'not enabled' message")
    void blankSecretWithPublicUrlFails() {
        // The CE out-of-the-box combination: app.public-url is set but the HMAC
        // secret is not. The feature must count as disabled, not half-enabled.
        PublicLinkService ceDefault = new PublicLinkService(
            new ShowcaseUrlSigner(""), "https://livecontext.example");
        NodeExecutionResult result = node("{{core:dl.output.file}}", 60, ceDefault).execute(context);

        assertFalse(result.isSuccess());
        assertNull(result.output().get("url"));
        assertTrue(result.errorMessage().orElse("").contains("not enabled"));
    }

    @Test
    @DisplayName("out-of-range TTL is clamped (999999 -> 10080 minutes)")
    void ttlClamped() {
        stubResolvedFile(OWN_KEY);
        NodeExecutionResult result = node("{{core:dl.output.file}}", 999_999, realService).execute(context);

        assertTrue(result.isSuccess());
        assertEquals(10_080, result.output().get("ttl_minutes"));
    }
}
