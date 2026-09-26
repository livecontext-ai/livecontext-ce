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
            .thenAnswer(TemplateResolutionStubs.every(fileRef));
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
            .thenAnswer(TemplateResolutionStubs.every("https://example.com/not-a-fileref.mp4"));
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

    /**
     * A table {@code file} / {@code image} cell resolves to the canonical asset, which carries
     * {@code id} and {@code url} on top of a workflow FileRef's fields. The table help tells agents
     * to map that whole cell into this node, so the extra keys must not stop it resolving.
     */
    @Test
    @DisplayName("the asset map's extra keys (id, url) do not stop a link being minted")
    void tableMediaCellMintsPublicUrl() {
        Map<String, Object> assetCell = new HashMap<>();
        assetCell.put("_type", "file");
        assetCell.put("id", "c7963596-ab99-46af-9cb5-fccb64461702");
        assetCell.put("path", OWN_KEY);
        assetCell.put("url", "/api/proxy/files/by-id/c7963596-ab99-46af-9cb5-fccb64461702/raw");
        assetCell.put("name", "clip.mp4");
        assetCell.put("mimeType", "video/mp4");
        assetCell.put("size", 24438642);
        when(templateAdapter.resolveTemplates(anyMap(), any()))
            .thenAnswer(TemplateResolutionStubs.every(assetCell));

        NodeExecutionResult result = node("{{table:queue.output.items[0].video}}", 60, realService).execute(context);

        assertTrue(result.isSuccess(), "failure was: " + result.errorMessage());
        assertNotNull(result.output().get("url"));
    }

    /**
     * The counterpart the help must not over-promise: an asset known only by id carries no storage
     * path, and this node is where that shows up. The message has to name the missing path, because
     * it is the only clue the agent gets about which cell to fix.
     */
    @Test
    @DisplayName("a media cell known only by id -> FAILS naming the missing storage path")
    void tableMediaCellWithoutAPathFails() {
        Map<String, Object> assetCell = new HashMap<>();
        assetCell.put("_type", "file");
        assetCell.put("id", "c7963596-ab99-46af-9cb5-fccb64461702");
        assetCell.put("url", "/api/proxy/files/by-id/c7963596-ab99-46af-9cb5-fccb64461702/raw");
        assetCell.put("name", "clip.mp4");
        when(templateAdapter.resolveTemplates(anyMap(), any()))
            .thenAnswer(TemplateResolutionStubs.every(assetCell));

        NodeExecutionResult result = node("{{table:queue.output.items[0].video}}", 60, realService).execute(context);

        assertFalse(result.isSuccess());
        assertNull(result.output().get("url"));
        assertTrue(result.errorMessage().orElse("").contains("storage path"),
            "failure must name the missing storage path, got: " + result.errorMessage());
    }

    /**
     * The widening accepts a file-shaped map with NO path so the missing-path message can be the
     * one the caller sees. It must not also accept a map whose path is present but malformed: that
     * would stringify to something like "123", fail the tenant-prefix test, and report a shape
     * error as a cross-tenant refusal - in the WARN log that exists for real ones.
     */
    @Test
    @DisplayName("a file-shaped map with a NON-string path is a shape error, not a tenant refusal")
    void nonStringPathIsAShapeErrorNotASecurityRefusal() {
        Map<String, Object> malformed = new HashMap<>();
        malformed.put("_type", "file");
        malformed.put("path", 123);
        when(templateAdapter.resolveTemplates(anyMap(), any()))
            .thenAnswer(TemplateResolutionStubs.every(malformed));

        NodeExecutionResult result = node("{{core:x.output.file}}", 60, realService).execute(context);

        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().orElse("").contains("WHOLE FileRef"),
            "a malformed path must read as a shape error, got: " + result.errorMessage());
        assertFalse(result.errorMessage().orElse("").contains("does not belong"));
    }

    @Test
    @DisplayName("an ordinary API object with url and name is still refused as not a file reference")
    void ordinaryObjectWithUrlAndNameIsStillRefused() {
        Map<String, Object> repo = new HashMap<>();
        repo.put("name", "livecontext");
        repo.put("url", "https://api.github.com/repos/x/livecontext");
        when(templateAdapter.resolveTemplates(anyMap(), any()))
            .thenAnswer(TemplateResolutionStubs.every(repo));

        NodeExecutionResult result = node("{{mcp:gh.output.repo}}", 60, realService).execute(context);

        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().orElse("").contains("WHOLE FileRef"));
    }

    @Test
    @DisplayName("out-of-range TTL is clamped (999999 -> 10080 minutes)")
    void ttlClamped() {
        stubResolvedFile(OWN_KEY);
        NodeExecutionResult result = node("{{core:dl.output.file}}", 999_999, realService).execute(context);

        assertTrue(result.isSuccess());
        assertEquals(10_080, result.output().get("ttl_minutes"));
    }

    @Test
    @DisplayName("reports the file expression as `file`, the plan's key name the label registry already declared")
    @SuppressWarnings("unchecked")
    void reportsFileUnderPlanKeyName() {
        PublicLinkNode node = node("{{core:make.output.file}}", 30, realService);

        NodeExecutionResult result = node.execute(context);

        // The template adapter is deliberately left unstubbed, so the node fails to
        // resolve the file reference. Asserted rather than left implicit: this test
        // pins the FAILURE path, which is the one that used to carry the wrong name,
        // and a future refactor that moved the map inside the success branch would
        // otherwise leave this test green while the failure path drifted again.
        assertFalse(result.isSuccess(), "this test pins the failure path on purpose");

        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        // The plan writes params.file / params.ttl_minutes / params.disposition
        // (stepProcessor). `file_expression` was a fourth name for the first one, and
        // the label registry already had an entry for `file` waiting on a key the
        // node never sent.
        assertEquals("{{core:make.output.file}}", params.get("file"));
        assertFalse(params.containsKey("file_expression"));
        assertEquals(30, params.get("ttl_minutes"));
        assertEquals("inline", params.get("disposition"));
    }

    @Test
    @DisplayName("reports what `file` RESOLVED to as `fileResolved`; the column used to show only the {{...}} expression, which read as unresolved")
    @SuppressWarnings("unchecked")
    void reportsResolvedFileBesideTheExpression() {
        stubResolvedFile(OWN_KEY);

        NodeExecutionResult result = node("{{core:dl.output.file}}", 60, realService).execute(context);

        assertTrue(result.isSuccess());
        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        assertEquals("{{core:dl.output.file}}", params.get("file"));
        assertEquals("clip.mp4 (" + OWN_KEY + ")", params.get("fileResolved"));
    }

    @Test
    @DisplayName("a file reference that resolves to nothing says so in `fileResolved`, instead of leaving the raw expression as the only trace")
    @SuppressWarnings("unchecked")
    void reportsFileResolvedToNothing() {
        NodeExecutionResult result = node("{{core:missing.output.file}}", 30, realService).execute(context);

        assertFalse(result.isSuccess());
        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        assertEquals("(resolved to nothing)", params.get("fileResolved"));
    }

    @Test
    @DisplayName("a {{$vars.f}} file expression publishes the file but its description is withheld in Params")
    @SuppressWarnings("unchecked")
    void workspaceVariableFileIsWithheld() {
        stubResolvedFile(OWN_KEY);

        NodeExecutionResult result = node("{{$vars.f}}", 60, realService).execute(context);

        assertTrue(result.isSuccess(), "the node still used the file");
        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        assertEquals(com.apimarketplace.orchestrator.services.template.ReportedParams.WITHHELD_WORKSPACE_VARIABLE,
            params.get("fileResolved"));
    }
}
