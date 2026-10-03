package com.apimarketplace.orchestrator.tools.file;

import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.services.file.FileDownloader;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.utils.file.FileConstants;
import com.apimarketplace.orchestrator.utils.file.FileNameExtractor;
import com.apimarketplace.orchestrator.utils.file.MimeTypeRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FileToolsProvider} - the {@code download_file} + {@code store_file}
 * storage tools. This provider had ZERO dedicated tests before the 2026-06-23 audit
 * (its sibling {@code FilesToolsProvider} is heavily covered; this one was the gap).
 * <p>
 * Collaborators ({@link FileDownloader}, {@link MimeTypeRegistry}, {@link FileStorageService})
 * are mocked; the static {@link FileNameExtractor}/{@link FileConstants} are exercised for real.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileToolsProvider (download_file / store_file)")
class FileToolsProviderTest {

    private static final String TENANT = "tenant-1";

    @Mock private FileStorageService fileStorageService;
    @Mock private FileDownloader fileDownloader;
    @Mock private MimeTypeRegistry mimeTypeRegistry;
    @Mock private com.apimarketplace.common.storage.service.StorageService storageIndex;
    @InjectMocks private FileToolsProvider provider;

    private final FileRef storedRef = FileRef.of("tenant-1/wf/run/step/out.bin", "out.bin", "application/pdf", 123L);

    @BeforeEach
    void setUp() {
        // upload echoes a canonical FileRef for the success paths; lenient so guard/error
        // tests that never reach upload don't trip strict stubbing.
        lenient().when(fileStorageService.upload(any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), any(), any())).thenReturn(storedRef);
    }

    private ToolExecutionResult exec(String tool, Map<String, Object> params) {
        return exec(tool, params, ToolExecutionContext.of(TENANT));
    }

    private ToolExecutionResult exec(String tool, Map<String, Object> params, ToolExecutionContext ctx) {
        return provider.execute(tool, params, ctx);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolExecutionResult r) {
        return (Map<String, Object>) r.data();
    }

    // ── metadata + dispatch ─────────────────────────────────────────────

    @Test
    @DisplayName("exposes exactly download_file + store_file under UTILITY")
    void metadata() {
        assertThat(provider.getCategory()).isEqualTo(ToolCategory.UTILITY);
        assertThat(provider.getTools()).extracting(t -> t.name())
                .containsExactlyInAnyOrder("download_file", "store_file");
        assertThat(provider.getTools()).allMatch(t -> t.requiresAuth());
    }

    @Test
    @DisplayName("a null tenantId -> MISSING_PARAMETER, no collaborator touched")
    void nullTenant() {
        ToolExecutionResult r = exec("download_file",
                Map.of("url", "https://x/y.pdf"), ToolExecutionContext.empty());
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        verifyNoInteractions(fileDownloader, fileStorageService);
    }

    @Test
    @DisplayName("an unknown tool name -> TOOL_NOT_FOUND")
    void unknownTool() {
        assertThat(exec("nope", Map.of()).errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
    }

    // ── download_file ───────────────────────────────────────────────────

    @Nested
    @DisplayName("download_file")
    class Download {

        @Test
        @DisplayName("happy path: downloads, stores with the provided name/mime, returns the FileRef + message")
        void happy() {
            byte[] bytes = "pdf-bytes".getBytes(StandardCharsets.UTF_8);
            when(fileDownloader.download("https://x/report.pdf")).thenReturn(bytes);

            ToolExecutionResult r = exec("download_file", Map.of(
                    "url", "https://x/report.pdf", "filename", "report.pdf", "mime_type", "application/pdf"));

            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsEntry("file", storedRef);
            assertThat((String) data(r).get("message")).contains("report.pdf");
            // provided mime short-circuits the registry
            verifyNoInteractions(mimeTypeRegistry);
            verify(fileStorageService).upload(eq(TENANT), eq("unknown"), eq("unknown"), eq("download"),
                    eq("report.pdf"), eq("application/pdf"), eq(bytes), eq(0), eq(0), any(), any());
        }

        @Test
        @DisplayName("missing url -> MISSING_PARAMETER, downloader untouched")
        void missingUrl() {
            assertThat(exec("download_file", Map.of()).errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            verifyNoInteractions(fileDownloader);
        }

        @Test
        @DisplayName("filename is derived from the URL when not provided")
        void derivesFilename() {
            byte[] bytes = {1, 2, 3};
            when(fileDownloader.download("https://x/report.pdf")).thenReturn(bytes);

            exec("download_file", Map.of("url", "https://x/report.pdf", "mime_type", "application/pdf"));

            ArgumentCaptor<String> fname = ArgumentCaptor.forClass(String.class);
            verify(fileStorageService).upload(any(), any(), any(), any(), fname.capture(), any(), any(),
                    anyInt(), anyInt(), any(), any());
            assertThat(fname.getValue()).isEqualTo(FileNameExtractor.fromUrl("https://x/report.pdf"));
        }

        @Test
        @DisplayName("mime type is auto-detected via the registry when not provided")
        void autoDetectsMime() {
            byte[] bytes = {1, 2, 3};
            when(fileDownloader.download("https://x/blob")).thenReturn(bytes);
            when(mimeTypeRegistry.resolve(any(), eq(bytes))).thenReturn("image/png");

            exec("download_file", Map.of("url", "https://x/blob", "filename", "blob.png"));

            verify(mimeTypeRegistry).resolve("blob.png", bytes);
            ArgumentCaptor<String> mime = ArgumentCaptor.forClass(String.class);
            verify(fileStorageService).upload(any(), any(), any(), any(), any(), mime.capture(), any(),
                    anyInt(), anyInt(), any(), any());
            assertThat(mime.getValue()).isEqualTo("image/png");
        }

        @Test
        @DisplayName("a download larger than the max size -> EXECUTION_FAILED, never stored")
        void tooLarge() {
            byte[] tooBig = new byte[(int) FileConstants.MAX_FILE_SIZE_BYTES + 1];
            when(fileDownloader.download("https://x/big")).thenReturn(tooBig);

            ToolExecutionResult r = exec("download_file", Map.of("url", "https://x/big", "mime_type", "application/pdf"));

            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("too large");
            verify(fileStorageService, never()).upload(any(), any(), any(), any(), any(), any(), any(),
                    anyInt(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("a FileDownloadException -> EXECUTION_FAILED 'Failed to download'")
        void downloadException() {
            when(fileDownloader.download("https://x/404"))
                    .thenThrow(new FileDownloader.FileDownloadException("404 Not Found"));

            ToolExecutionResult r = exec("download_file", Map.of("url", "https://x/404"));

            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("Failed to download");
        }

        /**
         * A refusal is not an outage. UrlNotAllowedException extends FileDownloadException,
         * so without its own catch it would land on EXECUTION_FAILED above, which an agent
         * reads as transient: it would retry a URL that will be refused identically every
         * time, until it runs out of iterations. What this pins is that the branch EXISTS
         * and passes the reason through unwrapped; the catch order itself needs no test,
         * because javac refuses a subclass caught after its parent. The branch is the kind
         * of thing a later tidy-up merges back into one.
         */
        @Test
        @DisplayName("a refused URL -> INVALID_PARAMETER_VALUE, not the retryable EXECUTION_FAILED")
        void refusedUrlIsNotRetryable() {
            when(fileDownloader.download("https://x/redirects-inward"))
                    .thenThrow(new FileDownloader.UrlNotAllowedException(
                            "Refused to follow redirect from https://x/redirects-inward to "
                                    + "http://169.254.169.254/latest: Requests to private/internal "
                                    + "network addresses are not allowed: 169.254.169.254"));

            ToolExecutionResult r = exec("download_file", Map.of("url", "https://x/redirects-inward"));

            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
            assertThat(r.error())
                    .as("the agent needs the reason to correct the URL, not a generic wrapper")
                    .contains("Refused to follow redirect")
                    .contains("169.254.169.254");
        }

        @Test
        @DisplayName("a non-download failure on the download path (storage throws) hits the generic catch -> EXECUTION_FAILED")
        void genericFailureWrapped() {
            byte[] bytes = {1};
            when(fileDownloader.download("https://x/ok.pdf")).thenReturn(bytes);
            when(fileStorageService.upload(any(), any(), any(), any(), any(), any(), any(),
                    anyInt(), anyInt(), any(), any())).thenThrow(new RuntimeException("S3 down"));

            ToolExecutionResult r = exec("download_file",
                    Map.of("url", "https://x/ok.pdf", "filename", "ok.pdf", "mime_type", "application/pdf"));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("Failed to download file");
        }

        @Test
        @DisplayName("an IllegalArgumentException from the downloader -> INVALID_PARAMETER_VALUE 'Invalid URL'")
        void invalidUrl() {
            when(fileDownloader.download("ftp://nope")).thenThrow(new IllegalArgumentException("bad scheme"));

            ToolExecutionResult r = exec("download_file", Map.of("url", "ftp://nope"));

            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
            assertThat(r.error()).contains("Invalid URL");
        }

        @Test
        @DisplayName("workflow context (workflowId/runId/stepAlias) is threaded from credentials into the storage path")
        void threadsWorkflowContext() {
            byte[] bytes = {9};
            when(fileDownloader.download("https://x/a.pdf")).thenReturn(bytes);
            ToolExecutionContext ctx = new ToolExecutionContext(TENANT,
                    Map.of("workflowId", "wf-1", "runId", "run-1", "stepAlias", "fetch"),
                    Map.of(), Set.of(), null, null, null, null);

            provider.execute("download_file",
                    Map.of("url", "https://x/a.pdf", "filename", "a.pdf", "mime_type", "application/pdf"), ctx);

            verify(fileStorageService).upload(eq(TENANT), eq("wf-1"), eq("run-1"), eq("fetch"),
                    eq("a.pdf"), eq("application/pdf"), eq(bytes), eq(0), eq(0), any(), any());
        }
    }

    // ── store_file ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("store_file")
    class Store {

        @Test
        @DisplayName("happy path: decodes the base64 content and stores it under the 'store' step alias")
        void happy() {
            String b64 = Base64.getEncoder().encodeToString("Hello World".getBytes(StandardCharsets.UTF_8));

            ToolExecutionResult r = exec("store_file", Map.of(
                    "content", b64, "filename", "hello.txt", "mime_type", "text/plain"));

            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsEntry("file", storedRef);

            ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
            verify(fileStorageService).upload(eq(TENANT), eq("unknown"), eq("unknown"), eq("store"),
                    eq("hello.txt"), eq("text/plain"), content.capture(), eq(0), eq(0), any(), any());
            assertThat(new String(content.getValue(), StandardCharsets.UTF_8)).isEqualTo("Hello World");
        }

        @Test
        @DisplayName("missing content / filename / mime_type each -> MISSING_PARAMETER, never stored")
        void missingParams() {
            String b64 = Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8));
            assertThat(exec("store_file", Map.of("filename", "f", "mime_type", "text/plain")).errorCode())
                    .isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(exec("store_file", Map.of("content", b64, "mime_type", "text/plain")).errorCode())
                    .isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(exec("store_file", Map.of("content", b64, "filename", "f")).errorCode())
                    .isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            verify(fileStorageService, never()).upload(any(), any(), any(), any(), any(), any(), any(),
                    anyInt(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("invalid base64 content -> INVALID_PARAMETER_VALUE 'Invalid base64', never stored")
        void invalidBase64() {
            ToolExecutionResult r = exec("store_file", Map.of(
                    "content", "!!! not base64 !!!", "filename", "f.txt", "mime_type", "text/plain"));

            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
            assertThat(r.error()).contains("Invalid base64");
            verify(fileStorageService, never()).upload(any(), any(), any(), any(), any(), any(), any(),
                    anyInt(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("a storage failure is wrapped as EXECUTION_FAILED, not propagated")
        void storageFailureWrapped() {
            String b64 = Base64.getEncoder().encodeToString("data".getBytes(StandardCharsets.UTF_8));
            when(fileStorageService.upload(any(), any(), any(), any(), any(), any(), any(),
                    anyInt(), anyInt(), any(), any())).thenThrow(new RuntimeException("S3 down"));

            ToolExecutionResult r = exec("store_file", Map.of(
                    "content", b64, "filename", "f.txt", "mime_type", "text/plain"));

            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).contains("Failed to store file");
        }
    }

    // ── LC-066: store_file from a restricted execution ─────────────────

    @Nested
    @DisplayName("LC-066 store_file from a restricted context")
    class Lc066RestrictedStore {

        private static final String FILE_ID = "6f7ccab2-0000-4000-8000-000000000001";
        private final FileRef indexedRef =
                FileRef.of("tenant-1/wf/run/step/mail.txt", "mail.txt", "text/plain", 4L, FILE_ID);



        private final String b64 = Base64.getEncoder().encodeToString("mail".getBytes(StandardCharsets.UTF_8));

        private ToolExecutionContext restrictedCtx() {
            return new ToolExecutionContext(TENANT,
                    Map.of(com.apimarketplace.common.classification.DataSensitivity.CREDENTIAL_KEY, "RESTRICTED"),
                    Map.of(), Set.of(), null, null, null, null);
        }

        @BeforeEach
        void wireIndex() {
            provider.setStorageIndex(storageIndex);
            lenient().when(fileStorageService.upload(any(), any(), any(), any(), any(), any(), any(),
                    anyInt(), anyInt(), any(), any())).thenReturn(indexedRef);
        }

        @Test
        @DisplayName("LC-066: a file stored from a restricted context is tagged RESTRICTED on its index row")
        void lc066RestrictedStoreIsTagged() {
            when(storageIndex.markRestricted(eq(TENANT), any(), any())).thenReturn(1);

            ToolExecutionResult r = provider.execute("store_file",
                    Map.of("content", b64, "filename", "mail.txt", "mime_type", "text/plain"), restrictedCtx());

            assertThat(r.success()).isTrue();
            verify(storageIndex).markRestricted(eq(TENANT),
                    eq(java.util.List.of(java.util.UUID.fromString(FILE_ID))), any());
            verify(fileStorageService, never()).delete(any());
            verify(fileStorageService, never()).delete(any(), any());
            verify(storageIndex, never()).deleteById(any(), any());
        }

        @Test
        @DisplayName("LC-066: when the tag cannot be recorded the object is deleted under its OWNER tenant and the index row too")
        void lc066UntaggableRestrictedStoreIsDeleted() {
            when(storageIndex.markRestricted(eq(TENANT), any(), any())).thenThrow(new RuntimeException("db down"));
            when(fileStorageService.delete(TENANT, "tenant-1/wf/run/step/mail.txt")).thenReturn(true);
            when(storageIndex.deleteById(java.util.UUID.fromString(FILE_ID), TENANT)).thenReturn(true);

            ToolExecutionResult r = provider.execute("store_file",
                    Map.of("content", b64, "filename", "mail.txt", "mime_type", "text/plain"), restrictedCtx());

            assertThat(r.success()).isFalse();
            assertThat(r.error()).contains("not stored").contains("Nothing was saved");
            // Owner-aware delete: the key-only delete carries no X-User-ID and is refused (403) remotely.
            verify(fileStorageService).delete(TENANT, "tenant-1/wf/run/step/mail.txt");
            verify(fileStorageService, never()).delete(any());
            // The index row goes too, or the file keeps listing under its name.
            verify(storageIndex).deleteById(java.util.UUID.fromString(FILE_ID), TENANT);
        }

        @Test
        @DisplayName("LC-066: a file with no index row to tag is deleted and the call fails (absent row is not a cleanup failure)")
        void lc066RestrictedStoreWithoutIndexRowIsDeleted() {
            when(storageIndex.markRestricted(eq(TENANT), any(), any())).thenReturn(0);
            when(fileStorageService.delete(TENANT, "tenant-1/wf/run/step/mail.txt")).thenReturn(true);
            when(storageIndex.deleteById(any(), any())).thenReturn(false);

            ToolExecutionResult r = provider.execute("store_file",
                    Map.of("content", b64, "filename", "mail.txt", "mime_type", "text/plain"), restrictedCtx());

            assertThat(r.success()).isFalse();
            assertThat(r.error()).isEqualTo(FileToolsProvider.RESTRICTED_FILE_UNTAGGABLE);
        }

        @Test
        @DisplayName("LC-066 regression: object delete returning false is reported, never 'Nothing was saved'")
        void lc066ObjectDeleteReturningFalseTellsTheTruth() {
            when(storageIndex.markRestricted(eq(TENANT), any(), any())).thenThrow(new RuntimeException("db down"));
            when(fileStorageService.delete(TENANT, "tenant-1/wf/run/step/mail.txt")).thenReturn(false);
            when(storageIndex.deleteById(any(), any())).thenReturn(true);

            ToolExecutionResult r = provider.execute("store_file",
                    Map.of("content", b64, "filename", "mail.txt", "mime_type", "text/plain"), restrictedCtx());

            assertThat(r.success()).isFalse();
            assertThat(r.error()).doesNotContain("Nothing was saved")
                    .contains("removing the unclassified copy also failed")
                    .contains("'mail.txt'");
        }

        @Test
        @DisplayName("LC-066 regression: object delete throwing is reported, never 'Nothing was saved'")
        void lc066ObjectDeleteThrowingTellsTheTruth() {
            when(storageIndex.markRestricted(eq(TENANT), any(), any())).thenThrow(new RuntimeException("db down"));
            when(fileStorageService.delete(TENANT, "tenant-1/wf/run/step/mail.txt"))
                    .thenThrow(new RuntimeException("storage down"));
            when(storageIndex.deleteById(any(), any())).thenReturn(true);

            ToolExecutionResult r = provider.execute("store_file",
                    Map.of("content", b64, "filename", "mail.txt", "mime_type", "text/plain"), restrictedCtx());

            assertThat(r.success()).isFalse();
            assertThat(r.error()).doesNotContain("Nothing was saved").contains("'mail.txt'");
        }

        @Test
        @DisplayName("LC-066 regression: an index row that cannot be deleted is reported, never 'Nothing was saved'")
        void lc066IndexRowDeleteFailingTellsTheTruth() {
            when(storageIndex.markRestricted(eq(TENANT), any(), any())).thenThrow(new RuntimeException("db down"));
            when(fileStorageService.delete(TENANT, "tenant-1/wf/run/step/mail.txt")).thenReturn(true);
            when(storageIndex.deleteById(any(), any())).thenThrow(new RuntimeException("db down"));

            ToolExecutionResult r = provider.execute("store_file",
                    Map.of("content", b64, "filename", "mail.txt", "mime_type", "text/plain"), restrictedCtx());

            assertThat(r.success()).isFalse();
            assertThat(r.error()).doesNotContain("Nothing was saved").contains("'mail.txt'");
        }

        @Test
        @DisplayName("LC-066: without the index service a restricted store is refused before anything is uploaded")
        void lc066RestrictedStoreWithoutIndexIsRefused() {
            provider.setStorageIndex(null);

            ToolExecutionResult r = provider.execute("store_file",
                    Map.of("content", b64, "filename", "mail.txt", "mime_type", "text/plain"), restrictedCtx());

            assertThat(r.success()).isFalse();
            verify(fileStorageService, never()).upload(any(), any(), any(), any(), any(), any(), any(),
                    anyInt(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("LC-066: an ordinary context stores the file untagged (no over-tagging)")
        void lc066NormalStoreIsNotTagged() {
            ToolExecutionResult r = exec("store_file",
                    Map.of("content", b64, "filename", "mail.txt", "mime_type", "text/plain"));

            assertThat(r.success()).isTrue();
            verifyNoInteractions(storageIndex);
        }

        @Test
        @DisplayName("LC-066: download_file is not affected by the restricted tag (it stores a public URL's bytes)")
        void lc066DownloadIsNotTagged() {
            when(fileDownloader.download("https://x/a.pdf")).thenReturn(new byte[] {1});

            provider.execute("download_file",
                    Map.of("url", "https://x/a.pdf", "filename", "a.pdf", "mime_type", "application/pdf"),
                    restrictedCtx());

            verifyNoInteractions(storageIndex);
        }
    }
}
