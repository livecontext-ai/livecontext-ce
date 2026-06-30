package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.file.FileDownloader;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.utils.file.MimeTypeRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DownloadFileNode.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DownloadFileNode")
class DownloadFileNodeTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private FileStorageService mockFileStorageService;
    @Mock private FileDownloader mockFileDownloader;
    @Mock private MimeTypeRegistry mockMimeTypeRegistry;
    @Mock private V2TemplateAdapter mockTemplateAdapter;
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowRunRepository mockRunRepository;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        when(mockPlan.getId()).thenReturn("plan-1");
        context = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
    }

    /**
     * Regression pin: execute() emits ONLY the canonical FileRef under `file` and `source_url`.
     * The legacy flattened fields (file_url, file_name, file_size, content_type) were removed
     * in PR2 (2026-05-15) - emitting them would mislead workflow authors into using a shape
     * that the showcase HMAC rewriter doesn't recognise.
     */
    @Nested
    @DisplayName("Shape parity: execute() output matches persisted shape (canonical-only)")
    class ShapeParityTests {

        @Test
        @DisplayName("executeOutputMatchesPersistedShape: only `file` FileRef + source_url, no legacy flats")
        void executeOutputMatchesPersistedShape() throws Exception {
            DownloadFileNode node = new DownloadFileNode("mcp:download", "http://example.com/doc.pdf", "doc.pdf", "application/pdf");
            node.setFileStorageService(mockFileStorageService);
            node.setFileDownloader(mockFileDownloader);
            node.setMimeTypeRegistry(mockMimeTypeRegistry);

            byte[] content = "file content".getBytes();
            when(mockFileDownloader.download("http://example.com/doc.pdf")).thenReturn(content);

            FileRef stored = FileRef.of("tenant/wf/run/node/doc.pdf", "doc.pdf", "application/pdf", content.length);
            when(mockFileStorageService.upload(any(), any(), any(), any(), any(), any(), any(byte[].class),
                    anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(stored);

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());

            Map<String, Object> executeOutput = result.output();

            // Canonical FileRef under `file` + source_url. No legacy flat fields.
            assertTrue(executeOutput.containsKey("file"),         "execute() must contain canonical FileRef under 'file'");
            assertTrue(executeOutput.containsKey("source_url"),   "execute() must contain source_url");
            assertFalse(executeOutput.containsKey("file_url"),    "execute() must NOT contain legacy file_url (PR2 clean break)");
            assertFalse(executeOutput.containsKey("file_name"),   "execute() must NOT contain legacy file_name (PR2 clean break)");
            assertFalse(executeOutput.containsKey("file_size"),   "execute() must NOT contain legacy file_size (PR2 clean break)");
            assertFalse(executeOutput.containsKey("content_type"),"execute() must NOT contain legacy content_type (PR2 clean break)");
            assertFalse(executeOutput.containsKey("url"),         "execute() must NOT contain old 'url' wrapper key");

            // customTransform must strip engine-envelope keys and preserve canonical keys + FileRef.
            DownloadFileNodeSpec spec = new DownloadFileNodeSpec();
            Map<String, Object> persistedOutput = spec.customTransform(executeOutput);
            assertTrue(persistedOutput.containsKey("file"),             "persisted output must contain canonical FileRef under 'file'");
            assertTrue(persistedOutput.containsKey("source_url"),       "persisted output must contain source_url");
            assertFalse(persistedOutput.containsKey("file_url"),        "persisted output must NOT contain legacy file_url");
            assertFalse(persistedOutput.containsKey("file_name"),       "persisted output must NOT contain legacy file_name");
            assertFalse(persistedOutput.containsKey("file_size"),       "persisted output must NOT contain legacy file_size");
            assertFalse(persistedOutput.containsKey("content_type"),    "persisted output must NOT contain legacy content_type");
            assertFalse(persistedOutput.containsKey("node_type"),       "persisted output must NOT contain engine key node_type");
            assertFalse(persistedOutput.containsKey("item_index"),      "persisted output must NOT contain engine key item_index");
            assertFalse(persistedOutput.containsKey("itemIndex"),       "persisted output must NOT contain engine key itemIndex");
            assertFalse(persistedOutput.containsKey("item_id"),         "persisted output must NOT contain engine key item_id");
            assertFalse(persistedOutput.containsKey("resolved_params"), "persisted output must NOT contain engine key resolved_params");
        }
    }

    @Nested
    @DisplayName("Constructor and properties")
    class ConstructorTests {

        @Test
        @DisplayName("should have DOWNLOAD_FILE node type")
        void shouldHaveDownloadFileType() {
            DownloadFileNode node = new DownloadFileNode("mcp:download", "http://example.com/file.pdf", "file.pdf", "application/pdf");
            assertEquals(NodeType.DOWNLOAD_FILE, node.getType());
        }

        @Test
        @DisplayName("should store expressions")
        void shouldStoreExpressions() {
            DownloadFileNode node = new DownloadFileNode("mcp:download", "url", "filename", "mimetype");
            assertEquals("url", node.getUrlExpression());
            assertEquals("filename", node.getFilenameExpression());
            assertEquals("mimetype", node.getMimeTypeExpression());
        }
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        @DisplayName("should fail when FileStorageService is not configured")
        void shouldFailWhenNoFileStorageService() {
            DownloadFileNode node = new DownloadFileNode("mcp:download", "http://example.com/file.pdf", null, null);
            node.setFileDownloader(mockFileDownloader);

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().get().contains("FileStorageService"));
        }

        @Test
        @DisplayName("should fail when FileDownloader is not configured")
        void shouldFailWhenNoFileDownloader() {
            DownloadFileNode node = new DownloadFileNode("mcp:download", "http://example.com/file.pdf", null, null);
            node.setFileStorageService(mockFileStorageService);

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().get().contains("FileDownloader"));
        }

        @Test
        @DisplayName("should fail when URL expression resolves to null")
        void shouldFailWhenUrlIsNull() {
            DownloadFileNode node = new DownloadFileNode("mcp:download", null, null, null);
            node.setFileStorageService(mockFileStorageService);
            node.setFileDownloader(mockFileDownloader);

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().get().contains("URL"));
        }

        @Test
        @DisplayName("should fail when URL expression resolves to blank")
        void shouldFailWhenUrlIsBlank() {
            DownloadFileNode node = new DownloadFileNode("mcp:download", "  ", null, null);
            node.setFileStorageService(mockFileStorageService);
            node.setFileDownloader(mockFileDownloader);

            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().get().contains("URL"));
        }

        @Test
        @DisplayName("should download and store file successfully")
        void shouldDownloadAndStoreFile() throws Exception {
            DownloadFileNode node = new DownloadFileNode("mcp:download", "http://example.com/doc.pdf", "doc.pdf", "application/pdf");
            node.setFileStorageService(mockFileStorageService);
            node.setFileDownloader(mockFileDownloader);
            node.setMimeTypeRegistry(mockMimeTypeRegistry);

            byte[] content = "file content".getBytes();
            when(mockFileDownloader.download("http://example.com/doc.pdf")).thenReturn(content);

            FileRef mockFileRef = mock(FileRef.class);
            when(mockFileRef.path()).thenReturn("s3://bucket/path");
            when(mockFileRef.size()).thenReturn((long) content.length);
            when(mockFileStorageService.upload(any(), any(), any(), any(), any(), any(), any(byte[].class),
                    anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(mockFileRef);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("DOWNLOAD_FILE", result.output().get("node_type"));
            assertEquals("http://example.com/doc.pdf", result.output().get("source_url"));
            // Canonical FileRef object emitted under `file` (recognised by frontend
            // `injectFileProxyToken` + showcase HMAC rewriter for anonymous viewers).
            assertSame(mockFileRef, result.output().get("file"),
                "must emit the FileRef instance itself under `file` so Jackson serialises it as {_type:'file', ...}");
        }

        @Test
        @DisplayName("threads context.epoch()/spawn()/itemIndex() + STEP_OUTPUT sourceType into the storage upload "
                + "(Phase 2a - group workflow files by epoch/spawn/iteration)")
        void passesRunContextAndStepOutputSourceTypeToUpload() throws Exception {
            // Non-zero DAG coordinates so the test would fail if the node hardcoded 0/0/null or
            // forgot to thread the run context (pre-fix it called the 7-arg overload → epoch 0).
            ExecutionContext ctx = ExecutionContext.create(
                "run-9", "wr-9", "tenant-9", "item-5", /* itemIndex */ 5,
                /* triggerId */ "trigger:default", /* epoch */ 3, /* spawn */ 2,
                Map.of(), mockPlan);

            DownloadFileNode node = new DownloadFileNode("mcp:download", "http://example.com/doc.pdf", "doc.pdf", "application/pdf");
            node.setFileStorageService(mockFileStorageService);
            node.setFileDownloader(mockFileDownloader);
            node.setMimeTypeRegistry(mockMimeTypeRegistry);

            byte[] content = "file content".getBytes();
            when(mockFileDownloader.download("http://example.com/doc.pdf")).thenReturn(content);
            FileRef stored = FileRef.of("k", "doc.pdf", "application/pdf", content.length);

            org.mockito.ArgumentCaptor<Integer> epochCaptor = org.mockito.ArgumentCaptor.forClass(Integer.class);
            org.mockito.ArgumentCaptor<Integer> spawnCaptor = org.mockito.ArgumentCaptor.forClass(Integer.class);
            org.mockito.ArgumentCaptor<Integer> itemIndexCaptor = org.mockito.ArgumentCaptor.forClass(Integer.class);
            org.mockito.ArgumentCaptor<String> sourceTypeCaptor = org.mockito.ArgumentCaptor.forClass(String.class);

            when(mockFileStorageService.upload(any(), any(), any(), any(), any(), any(), any(byte[].class),
                    epochCaptor.capture(), spawnCaptor.capture(), itemIndexCaptor.capture(), sourceTypeCaptor.capture()))
                .thenReturn(stored);

            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals(3, epochCaptor.getValue(), "epoch must come from context.epoch()");
            assertEquals(2, spawnCaptor.getValue(), "spawn must come from context.spawn()");
            assertEquals(5, itemIndexCaptor.getValue(), "itemIndex must come from context.itemIndex()");
            assertEquals(
                com.apimarketplace.common.storage.service.StorageSourceTypes.STEP_OUTPUT,
                sourceTypeCaptor.getValue(),
                "Download File is a workflow step producer → sourceType=STEP_OUTPUT");
        }

        @Test
        @DisplayName("output emits only the canonical FileRef under `file` (PR2 - clean break, no legacy flats)")
        void shouldEmitOnlyCanonicalFileRef() throws Exception {
            // Pins the canonical-only contract: marketplace + share preview need the FileRef
            // object (`file` key) because the showcase rewriter only recognises {_type:"file"}.
            // PR2 (2026-05-15) dropped the legacy flat fields entirely - workflows must use
            // {{core:label.output.file}} or sub-paths like {{...output.file.path}}.
            DownloadFileNode node = new DownloadFileNode("mcp:download", "http://example.com/img.png", "img.png", "image/png");
            node.setFileStorageService(mockFileStorageService);
            node.setFileDownloader(mockFileDownloader);
            node.setMimeTypeRegistry(mockMimeTypeRegistry);

            byte[] content = "png-bytes".getBytes();
            when(mockFileDownloader.download("http://example.com/img.png")).thenReturn(content);

            FileRef mockFileRef = mock(FileRef.class);
            when(mockFileRef.path()).thenReturn("tenant/wf/run/step/abc_img.png");
            when(mockFileStorageService.upload(any(), any(), any(), any(), any(), any(), any(byte[].class),
                    anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(mockFileRef);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            // Canonical
            assertSame(mockFileRef, result.output().get("file"));
            // No legacy flat fields - clean break.
            assertFalse(result.output().containsKey("file_url"),     "file_url must not be emitted (PR2 clean break)");
            assertFalse(result.output().containsKey("file_name"),    "file_name must not be emitted (PR2 clean break)");
            assertFalse(result.output().containsKey("file_size"),    "file_size must not be emitted (PR2 clean break)");
            assertFalse(result.output().containsKey("content_type"), "content_type must not be emitted (PR2 clean break)");
        }

        @Test
        @DisplayName("should handle FileDownloadException")
        void shouldHandleDownloadException() throws Exception {
            DownloadFileNode node = new DownloadFileNode("mcp:download", "http://example.com/file", null, null);
            node.setFileStorageService(mockFileStorageService);
            node.setFileDownloader(mockFileDownloader);

            when(mockFileDownloader.download(anyString()))
                .thenThrow(new FileDownloader.FileDownloadException("404 Not Found"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().get().contains("Download failed"));
        }

        /**
         * Regression: pre-fix failure paths returned {resolved_params: ...} which became {}
         * after engine-envelope strip. Post-fix: declared canonical keys are present with
         * safe defaults so persisted JSONB shape matches the NodeSpec declaration.
         */
        @Test
        @DisplayName("failureOutputContainsCanonicalKeys: failure output has canonical `file` (null) + source_url, no legacy flats")
        void failureOutputContainsCanonicalKeys() throws Exception {
            DownloadFileNode node = new DownloadFileNode("mcp:download", "http://example.com/file.pdf", null, null);
            node.setFileStorageService(mockFileStorageService);
            node.setFileDownloader(mockFileDownloader);

            when(mockFileDownloader.download(anyString()))
                .thenThrow(new FileDownloader.FileDownloadException("500 Internal Server Error"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            Map<String, Object> output = result.output();
            assertNotNull(output, "failure output must not be null");

            // Canonical-only contract on failure (PR2 2026-05-15).
            assertTrue(output.containsKey("file"),          "failure output must contain canonical `file` key - consumers null-check this one shape");
            assertTrue(output.containsKey("source_url"),    "failure output must contain source_url");
            assertFalse(output.containsKey("file_url"),     "failure output must NOT contain legacy file_url (PR2 clean break)");
            assertFalse(output.containsKey("file_name"),    "failure output must NOT contain legacy file_name (PR2 clean break)");
            assertFalse(output.containsKey("file_size"),    "failure output must NOT contain legacy file_size (PR2 clean break)");
            assertFalse(output.containsKey("content_type"), "failure output must NOT contain legacy content_type (PR2 clean break)");

            // Safe defaults
            assertNull(output.get("file"),       "file default must be null on failure");
            // source_url carries the resolved URL when available (null when URL resolution itself fails)
            assertEquals("http://example.com/file.pdf", output.get("source_url"),
                "source_url must carry the resolved URL when it was resolved before failure");
        }

        /**
         * Regression: failure output used to omit the engine metadata stamped by success
         * paths. Persistence then inferred DECISION from the core:* id instead of using
         * DOWNLOAD_FILE, so detailed rows were typed incorrectly after SSRF/download errors.
         */
        @Test
        @DisplayName("failureOutputIncludesPersistenceMetadata: failure output stamps DOWNLOAD_FILE and item metadata")
        void failureOutputIncludesPersistenceMetadata() throws Exception {
            DownloadFileNode node = new DownloadFileNode("core:download_asset", "http://example.com/file.pdf", null, null);
            node.setFileStorageService(mockFileStorageService);
            node.setFileDownloader(mockFileDownloader);

            when(mockFileDownloader.download(anyString()))
                .thenThrow(new FileDownloader.FileDownloadException("500 Internal Server Error"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            Map<String, Object> output = result.output();
            assertNotNull(output, "failure output must not be null");
            assertEquals("DOWNLOAD_FILE", output.get("node_type"));
            assertEquals(0, output.get("item_index"));
            assertEquals(0, output.get("itemIndex"));
            assertEquals("item-0", output.get("item_id"));
        }

        @Test
        @DisplayName("failureBeforeUrlResolutionHasNullSourceUrl: source_url is null when URL not yet resolved")
        void failureBeforeUrlResolutionHasNullSourceUrl() {
            // FileStorageService not set → fails before URL resolution
            DownloadFileNode node = new DownloadFileNode("mcp:download", "http://example.com/file.pdf", null, null);
            node.setFileDownloader(mockFileDownloader);
            // no fileStorageService set

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            Map<String, Object> output = result.output();
            assertTrue(output.containsKey("source_url"), "source_url key must be present even when null");
            assertNull(output.get("source_url"), "source_url must be null when URL not yet resolved");
        }
    }

    /**
     * Regression for the file-epoch MISATTRIBUTION bug (2026-06-27).
     *
     * <p>A succeeding DownloadFileNode reached via a deferred dispatch path (signal-resume /
     * async completion) can carry {@code context.epoch()==0} when the signal/pending it resumes
     * from was registered before the first trigger fire, even though the run has since fired to
     * a real epoch. The file was then stored under a phantom {@code epoch 0} that never fired,
     * while the node's own {@code workflow_step_data} row landed at the run's real epoch (because
     * {@code StepDataPersistenceService} already resolves a sentinel 0 via
     * {@code getCurrentEpochFromRun}). The two diverged - the file was misattributed.
     *
     * <p>Fix: {@link BaseNode#resolveStorageEpoch} resolves a sentinel {@code epoch 0} to the
     * run's real current epoch, so the file bucket matches the step-data bucket. The execution
     * context's epoch is deliberately left at 0 (it drives predecessor-output loading / state
     * reconstruction from the epoch where that data actually lives).
     */
    @Nested
    @DisplayName("File epoch resolution (sentinel-0 -> real run epoch)")
    class FileEpochResolution {

        private DownloadFileNode wiredNode() {
            ServiceRegistry registry = ServiceRegistry.builder()
                .fileStorageService(mockFileStorageService)
                .fileDownloader(mockFileDownloader)
                .mimeTypeRegistry(mockMimeTypeRegistry)
                .workflowRunRepository(mockRunRepository)
                .build();
            DownloadFileNode node = new DownloadFileNode("mcp:download", "http://example.com/real.png", "real.png", "image/png");
            node.acceptServices(registry);
            return node;
        }

        private org.mockito.ArgumentCaptor<Integer> stubUploadCapturingEpoch() throws Exception {
            when(mockFileDownloader.download("http://example.com/real.png")).thenReturn("png-bytes".getBytes());
            org.mockito.ArgumentCaptor<Integer> epochCaptor = org.mockito.ArgumentCaptor.forClass(Integer.class);
            when(mockFileStorageService.upload(any(), any(), any(), any(), any(), any(), any(byte[].class),
                    epochCaptor.capture(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(FileRef.of("k", "real.png", "image/png", 9));
            return epochCaptor;
        }

        private ExecutionContext epochZeroContext(String runId) {
            // 7-arg legacy create -> triggerId=null, epoch=0, spawn=0 (the deferred-dispatch sentinel)
            return ExecutionContext.create(runId, "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
        }

        private com.apimarketplace.orchestrator.domain.WorkflowRunEntity runWithCurrentEpoch(Integer currentEpoch) {
            var run = mock(com.apimarketplace.orchestrator.domain.WorkflowRunEntity.class);
            when(run.getMetadata()).thenReturn(
                currentEpoch == null ? Map.of() : Map.of("currentEpoch", currentEpoch));
            return run;
        }

        @Test
        @DisplayName("succeeding download with epoch-0 context in a re-fired run stamps the REAL epoch (2), not 0")
        void succeedingDownloadInRefiredEpochStampsRealEpochNotZero() throws Exception {
            DownloadFileNode node = wiredNode();
            org.mockito.ArgumentCaptor<Integer> epochCaptor = stubUploadCapturingEpoch();
            var run = runWithCurrentEpoch(2);
            when(mockRunRepository.findByRunIdPublic("run-refired")).thenReturn(java.util.Optional.of(run));

            NodeExecutionResult result = node.execute(epochZeroContext("run-refired"));

            assertTrue(result.isSuccess());
            assertEquals(2, epochCaptor.getValue(),
                "file must be stamped with the run's real current epoch (2), never the sentinel 0");
        }

        @Test
        @DisplayName("genuine epoch-0 run (never fired) keeps the file at epoch 0")
        void genuineEpochZeroRunStaysZero() throws Exception {
            DownloadFileNode node = wiredNode();
            org.mockito.ArgumentCaptor<Integer> epochCaptor = stubUploadCapturingEpoch();
            var run = runWithCurrentEpoch(0);
            when(mockRunRepository.findByRunIdPublic("run-initial")).thenReturn(java.util.Optional.of(run));

            NodeExecutionResult result = node.execute(epochZeroContext("run-initial"));

            assertTrue(result.isSuccess());
            assertEquals(0, epochCaptor.getValue(),
                "a run that never fired has currentEpoch=0; the file legitimately stays at epoch 0");
        }

        @Test
        @DisplayName("run not found -> file stays at the raw context epoch (best-effort, never fails the write)")
        void runNotFoundFallsBackToContextEpoch() throws Exception {
            DownloadFileNode node = wiredNode();
            org.mockito.ArgumentCaptor<Integer> epochCaptor = stubUploadCapturingEpoch();
            when(mockRunRepository.findByRunIdPublic("run-missing")).thenReturn(java.util.Optional.empty());

            NodeExecutionResult result = node.execute(epochZeroContext("run-missing"));

            assertTrue(result.isSuccess());
            assertEquals(0, epochCaptor.getValue(),
                "unresolvable run must not break the file write; falls back to the raw context epoch");
        }

        @Test
        @DisplayName("non-zero context epoch is used verbatim - no run lookup (inline-fire fast path)")
        void nonZeroContextEpochUsedVerbatimWithoutRunLookup() throws Exception {
            DownloadFileNode node = wiredNode();
            org.mockito.ArgumentCaptor<Integer> epochCaptor = stubUploadCapturingEpoch();

            ExecutionContext ctx = ExecutionContext.create(
                "run-inline", "wr-1", "tenant-1", "item-0", 0,
                "trigger:default", /* epoch */ 2, /* spawn */ 0, Map.of(), mockPlan);

            NodeExecutionResult result = node.execute(ctx);

            assertTrue(result.isSuccess());
            assertEquals(2, epochCaptor.getValue(), "epoch>0 must be used directly");
            verify(mockRunRepository, never()).findByRunIdPublic(anyString());
        }
    }

    @Nested
    @DisplayName("acceptServices")
    class AcceptServices {

        @Test
        @DisplayName("should accept services from registry")
        void shouldAcceptServices() {
            ServiceRegistry registry = ServiceRegistry.builder()
                .fileStorageService(mockFileStorageService)
                .fileDownloader(mockFileDownloader)
                .mimeTypeRegistry(mockMimeTypeRegistry)
                .build();

            DownloadFileNode node = new DownloadFileNode("mcp:download", "url", null, null);
            node.acceptServices(registry);

            // Verify services are set (indirectly via execution)
            assertDoesNotThrow(() -> node.execute(context));
        }
    }

    @Nested
    @DisplayName("SSRF protection")
    class SsrfProtection {

        @ParameterizedTest
        @ValueSource(strings = {
            "http://127.0.0.1/file.txt",
            "http://localhost/file.txt",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.0/internal",
            "ftp://example.com/file.txt"
        })
        @DisplayName("should reject SSRF URLs")
        void shouldRejectSsrfUrls(String url) {
            DownloadFileNode node = new DownloadFileNode("mcp:download", url, null, null);
            node.setFileStorageService(mockFileStorageService);
            node.setFileDownloader(mockFileDownloader);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            // FileDownloader should never be called for blocked URLs
            verifyNoInteractions(mockFileDownloader);
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build DownloadFileNode with builder")
        void shouldBuild() {
            DownloadFileNode node = DownloadFileNode.builder()
                .nodeId("mcp:download_1")
                .urlExpression("http://example.com")
                .filenameExpression("file.txt")
                .mimeTypeExpression("text/plain")
                .build();

            assertEquals("mcp:download_1", node.getNodeId());
            assertEquals("http://example.com", node.getUrlExpression());
            assertEquals("file.txt", node.getFilenameExpression());
            assertEquals("text/plain", node.getMimeTypeExpression());
        }
    }
}
