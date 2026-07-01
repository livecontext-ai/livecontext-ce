package com.apimarketplace.orchestrator.services.interfaces;

import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.ResolvedTemplateSnapshot;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InterfaceScreenshotServiceImpl}. The service is best-effort: any
 * failure (missing config, render error, sidecar non-2xx, storage error) MUST surface as
 * {@link Optional#empty()} without throwing, so workflows continue to completion when the
 * screenshot toggle is on but the renderer is unavailable.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceScreenshotServiceImpl")
class InterfaceScreenshotServiceImplTest {

    private static final String TENANT = "tenant-1";
    private static final String RUN_ID = "run_2026_abcd";
    private static final int EPOCH = 0;
    private static final String NODE_ID = "interface:my_form";
    private static final UUID INTERFACE_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String RENDERER_URL = "http://renderer:8094";

    @Mock private InterfaceRenderService renderService;
    @Mock private FileStorageService fileStorageService;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private RestTemplate restTemplate;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    private InterfaceScreenshotServiceImpl newService(String rendererUrl) {
        // Pass an unbounded InMemorySemaphore so tests that don't exercise backpressure
        // can ignore the cap. The Backpressure nested class uses an explicit semaphore.
        return new InterfaceScreenshotServiceImpl(
            renderService, fileStorageService, workflowRunRepository,
            restTemplate, objectMapper, rendererUrl,
            new com.apimarketplace.common.scaling.lock.InMemorySemaphore(),
            Integer.MAX_VALUE);
    }

    private ResolvedTemplateSnapshot sampleSnapshot() {
        // Reflects what InterfaceRenderService.resolveTemplateSnapshot would return for an
        // interface with template "<h1>{{title|placeholder}}</h1>" and items[0].data={title=Hello}
        // - i.e. the {{var|default}} substitution has already been applied.
        return new ResolvedTemplateSnapshot(
            "<h1>Hello</h1>",
            "h1 { color: blue }",
            "",
            Map.of("title", "Hello")
        );
    }

    private void stubWorkflowLookup() {
        when(workflowRunRepository.findWorkflowIdByRunIdPublic(RUN_ID))
            .thenReturn(Optional.of(UUID.fromString("99999999-8888-7777-6666-555555555555")));
    }

    @Test
    @DisplayName("Production constructor is explicit so Spring can instantiate the service when test constructor exists")
    void productionConstructorIsAutowired() {
        List<Constructor<?>> autowiredConstructors = Arrays.stream(InterfaceScreenshotServiceImpl.class.getConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .toList();

        assertEquals(1, autowiredConstructors.size());
        assertEquals(8, autowiredConstructors.get(0).getParameterCount());
    }

    @Nested
    @DisplayName("Pre-flight skip")
    class PreFlight {

        @Test
        @DisplayName("Blank renderer URL → empty, no render call, no HTTP call")
        void blankUrlSkipsImmediately() {
            InterfaceScreenshotServiceImpl service = newService("");

            Optional<FileRef> result = service.capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isEmpty());
            verifyNoInteractions(renderService, restTemplate, fileStorageService, workflowRunRepository);
        }

        @Test
        @DisplayName("Null renderer URL → empty (defensive - @Value default is empty string, but guard for explicit null)")
        void nullUrlSkipsImmediately() {
            InterfaceScreenshotServiceImpl service = newService(null);

            Optional<FileRef> result = service.capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Render failures")
    class Render {

        @Test
        @DisplayName("Render throws → empty, no HTTP / upload")
        void renderExceptionIsSwallowed() {
            when(renderService.resolveTemplateSnapshot(eq(INTERFACE_UUID), eq(RUN_ID), eq(TENANT), eq(EPOCH)))
                .thenThrow(new RuntimeException("render boom"));

            Optional<FileRef> result = newService(RENDERER_URL)
                .capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isEmpty());
            verifyNoInteractions(restTemplate, fileStorageService);
        }

        @Test
        @DisplayName("Render returns empty snapshot → empty result, no HTTP / upload (defensive guard for interfaces with no html template)")
        void renderEmptySnapshotIsSwallowed() {
            when(renderService.resolveTemplateSnapshot(eq(INTERFACE_UUID), eq(RUN_ID), eq(TENANT), eq(EPOCH)))
                .thenReturn(Optional.empty());

            Optional<FileRef> result = newService(RENDERER_URL)
                .capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isEmpty());
            verifyNoInteractions(restTemplate, fileStorageService);
        }
    }

    @Nested
    @DisplayName("Sidecar HTTP")
    class Sidecar {

        @Test
        @DisplayName("Sidecar 5xx → RestClientException is caught, returns empty, no upload")
        void sidecarErrorIsSwallowed() {
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt())).thenReturn(Optional.of(sampleSnapshot()));
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenThrow(new RestClientException("connection refused"));

            Optional<FileRef> result = newService(RENDERER_URL)
                .capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isEmpty());
            verifyNoInteractions(fileStorageService);
        }

        @Test
        @DisplayName("Sidecar returns null body → empty, no upload")
        void sidecarEmptyBodyIsSwallowed() {
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt())).thenReturn(Optional.of(sampleSnapshot()));
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

            Optional<FileRef> result = newService(RENDERER_URL)
                .capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isEmpty());
            verifyNoInteractions(fileStorageService);
        }

        @Test
        @DisplayName("Sidecar 200 + bytes → uploads with deterministic filename containing the epoch AND spawn")
        void successfulCaptureUploadsWithEpochInFilename() {
            byte[] png = "fake-png-bytes".getBytes();
            FileRef uploaded = FileRef.of("tenant-1/wf/run/interface:my_form/my_form_screenshot_epoch_0_spawn_0.png",
                "my_form_screenshot_epoch_0_spawn_0.png", "image/png", png.length);

            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt())).thenReturn(Optional.of(sampleSnapshot()));
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(png, HttpStatus.OK));
            stubWorkflowLookup();
            // Filename now carries the spawn segment so re-spawns within one epoch don't collide;
            // upload goes through the context-carrying overload with sourceType=INTERFACE_SCREENSHOT.
            when(fileStorageService.upload(eq(TENANT), anyString(), eq(RUN_ID), eq(NODE_ID),
                eq("my_form_screenshot_epoch_0_spawn_0.png"), eq("image/png"), any(InputStream.class),
                eq((long) png.length), anyInt(), anyInt(), nullable(Integer.class), anyString()))
                .thenReturn(uploaded);

            Optional<FileRef> result = newService(RENDERER_URL)
                .capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isPresent());
            assertEquals(uploaded, result.get());
        }

        @Test
        @DisplayName("spawn-aware capture → filename includes the spawn (no cross-spawn collision) AND "
                + "sourceType=INTERFACE_SCREENSHOT + epoch/spawn/itemIndex threaded into the upload")
        void spawnAwareCaptureHasUniqueFilenameAndScreenshotSourceType() {
            byte[] png = "fake-png-bytes".getBytes();
            FileRef uploaded = FileRef.of("k", "n.png", "image/png", png.length);

            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(sampleSnapshot()));
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(png, HttpStatus.OK));
            stubWorkflowLookup();

            ArgumentCaptor<String> fileNameCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Integer> epochCaptor = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> spawnCaptor = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> itemIndexCaptor = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<String> sourceTypeCaptor = ArgumentCaptor.forClass(String.class);

            when(fileStorageService.upload(eq(TENANT), anyString(), eq(RUN_ID), eq(NODE_ID),
                fileNameCaptor.capture(), eq("image/png"), any(InputStream.class), eq((long) png.length),
                epochCaptor.capture(), spawnCaptor.capture(), itemIndexCaptor.capture(), sourceTypeCaptor.capture()))
                .thenReturn(uploaded);

            // epoch=2, spawn=3 - pre-fix the filename was "..._epoch_2.png" for BOTH spawns of the same
            // node/epoch (collision); now the spawn segment disambiguates.
            Optional<FileRef> result = newService(RENDERER_URL)
                .capture(TENANT, RUN_ID, /* epoch */ 2, /* spawn */ 3, /* itemIndex */ 4, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isPresent());
            assertEquals("my_form_screenshot_epoch_2_spawn_3.png", fileNameCaptor.getValue(),
                "filename must include BOTH epoch and spawn so re-spawns don't overwrite each other");
            assertEquals(2, epochCaptor.getValue());
            assertEquals(3, spawnCaptor.getValue());
            assertEquals(4, itemIndexCaptor.getValue());
            assertEquals(
                com.apimarketplace.common.storage.service.StorageSourceTypes.INTERFACE_SCREENSHOT,
                sourceTypeCaptor.getValue(),
                "interface screenshots are tagged sourceType=INTERFACE_SCREENSHOT");
        }

        @Test
        @DisplayName("Sidecar request body requests a FULL-PAGE capture so tall interfaces are not cropped")
        @SuppressWarnings("unchecked")
        void requestsFullPageCapture() {
            byte[] png = "png".getBytes();
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(sampleSnapshot()));
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor =
                ArgumentCaptor.forClass(HttpEntity.class);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), bodyCaptor.capture(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(png, HttpStatus.OK));
            stubWorkflowLookup();
            when(fileStorageService.upload(any(), anyString(), any(), any(), anyString(), anyString(),
                any(InputStream.class), anyLong(), anyInt(), anyInt(), nullable(Integer.class), anyString()))
                .thenReturn(FileRef.of("p", "f.png", "image/png", png.length));

            newService(RENDERER_URL).capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            Map<String, Object> sent = bodyCaptor.getValue().getBody();
            assertEquals(Boolean.TRUE, sent.get("fullPage"));
        }
    }

    @Nested
    @DisplayName("PDF render (capturePdf)")
    class PdfRender {

        @Test
        @DisplayName("Successful render → uploads <label>_pdf_epoch_N_spawn_M.pdf with mime application/pdf and sourceType INTERFACE_PDF")
        void successfulPdfUploadsWithPdfNameMimeAndSourceType() {
            byte[] pdf = "%PDF-1.4 fake".getBytes();
            FileRef uploaded = FileRef.of("k", "n.pdf", "application/pdf", pdf.length);

            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(sampleSnapshot()));
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(pdf, HttpStatus.OK));
            stubWorkflowLookup();

            ArgumentCaptor<String> fileNameCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> sourceTypeCaptor = ArgumentCaptor.forClass(String.class);
            when(fileStorageService.upload(eq(TENANT), anyString(), eq(RUN_ID), eq(NODE_ID),
                fileNameCaptor.capture(), eq("application/pdf"), any(InputStream.class), eq((long) pdf.length),
                anyInt(), anyInt(), nullable(Integer.class), sourceTypeCaptor.capture()))
                .thenReturn(uploaded);

            Optional<FileRef> result = newService(RENDERER_URL)
                .capturePdf(TENANT, RUN_ID, /* epoch */ 2, /* spawn */ 3, /* itemIndex */ 4, NODE_ID, INTERFACE_UUID, "A4", false);

            assertTrue(result.isPresent());
            assertEquals(uploaded, result.get());
            assertEquals("my_form_pdf_epoch_2_spawn_3.pdf", fileNameCaptor.getValue(),
                "PDF filename must carry the _pdf_ segment + epoch + spawn and a .pdf extension");
            assertEquals(
                com.apimarketplace.common.storage.service.StorageSourceTypes.INTERFACE_PDF,
                sourceTypeCaptor.getValue(),
                "interface PDFs are tagged sourceType=INTERFACE_PDF (not INTERFACE_SCREENSHOT)");
        }

        @Test
        @DisplayName("Request targets /internal/render/pdf and forwards format + landscape in the body")
        @SuppressWarnings("unchecked")
        void requestTargetsPdfEndpointWithFormatAndLandscape() {
            byte[] pdf = "%PDF".getBytes();
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(sampleSnapshot()));
            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            when(restTemplate.exchange(urlCaptor.capture(), eq(HttpMethod.POST), bodyCaptor.capture(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(pdf, HttpStatus.OK));
            stubWorkflowLookup();
            when(fileStorageService.upload(any(), anyString(), any(), any(), anyString(), anyString(),
                any(InputStream.class), anyLong(), anyInt(), anyInt(), nullable(Integer.class), anyString()))
                .thenReturn(FileRef.of("p", "f.pdf", "application/pdf", pdf.length));

            newService(RENDERER_URL)
                .capturePdf(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID, "Letter", true);

            assertTrue(urlCaptor.getValue().endsWith("/internal/render/pdf"),
                "PDF render must hit the /internal/render/pdf endpoint, got: " + urlCaptor.getValue());
            Map<String, Object> sent = bodyCaptor.getValue().getBody();
            assertEquals("Letter", sent.get("format"));
            assertEquals(Boolean.TRUE, sent.get("landscape"));
            assertEquals(Boolean.TRUE, sent.get("printBackground"));
        }

        @Test
        @DisplayName("Null/blank format defaults to A4 in the sidecar body")
        @SuppressWarnings("unchecked")
        void nullFormatDefaultsToA4() {
            byte[] pdf = "%PDF".getBytes();
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(sampleSnapshot()));
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), bodyCaptor.capture(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(pdf, HttpStatus.OK));
            stubWorkflowLookup();
            when(fileStorageService.upload(any(), anyString(), any(), any(), anyString(), anyString(),
                any(InputStream.class), anyLong(), anyInt(), anyInt(), nullable(Integer.class), anyString()))
                .thenReturn(FileRef.of("p", "f.pdf", "application/pdf", pdf.length));

            newService(RENDERER_URL)
                .capturePdf(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID, null, false);

            assertEquals("A4", bodyCaptor.getValue().getBody().get("format"),
                "a null format must default to A4, never forward an empty/unsupported page size");
        }

        @Test
        @DisplayName("Blank renderer URL → empty, no render/HTTP/upload (best-effort skip)")
        void blankUrlSkipsPdf() {
            Optional<FileRef> result = newService("")
                .capturePdf(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID, "A4", false);
            assertTrue(result.isEmpty());
            verifyNoInteractions(renderService, restTemplate, fileStorageService, workflowRunRepository);
        }
    }

    @Nested
    @DisplayName("isCompleteHtml helper")
    class IsCompleteHtml {

        @Test
        @DisplayName("Recognises <!doctype html> (any case + whitespace)")
        void recognisesDoctype() {
            assertTrue(InterfaceScreenshotServiceImpl.isCompleteHtml("<!DOCTYPE html><html><body>x</body></html>"));
            assertTrue(InterfaceScreenshotServiceImpl.isCompleteHtml("  <!doctype HTML>...."));
            assertTrue(InterfaceScreenshotServiceImpl.isCompleteHtml("\n\t<!DOCTYPE html>"));
        }

        @Test
        @DisplayName("Recognises bare <html> root")
        void recognisesHtmlRoot() {
            assertTrue(InterfaceScreenshotServiceImpl.isCompleteHtml("<html><body>x</body></html>"));
            assertTrue(InterfaceScreenshotServiceImpl.isCompleteHtml("<HTML lang=\"en\">..."));
        }

        @Test
        @DisplayName("Returns false for body fragments (no doctype, no <html>)")
        void rejectsFragments() {
            assertFalse(InterfaceScreenshotServiceImpl.isCompleteHtml("<div>my fragment</div>"));
            assertFalse(InterfaceScreenshotServiceImpl.isCompleteHtml("<h1>Title</h1>"));
            assertFalse(InterfaceScreenshotServiceImpl.isCompleteHtml("Just text"));
        }

        @Test
        @DisplayName("Returns false for null / blank")
        void rejectsNullAndBlank() {
            assertFalse(InterfaceScreenshotServiceImpl.isCompleteHtml(null));
            assertFalse(InterfaceScreenshotServiceImpl.isCompleteHtml(""));
            assertFalse(InterfaceScreenshotServiceImpl.isCompleteHtml("   \n\t  "));
        }
    }

    @Nested
    @DisplayName("Storage failures")
    class Storage {

        @Test
        @DisplayName("Run not found → empty, no upload (avoids leaking screenshots under wrong workflow scope)")
        void runNotFoundSkipsUpload() {
            byte[] png = "fake".getBytes();
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt())).thenReturn(Optional.of(sampleSnapshot()));
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(png, HttpStatus.OK));
            when(workflowRunRepository.findWorkflowIdByRunIdPublic(RUN_ID)).thenReturn(Optional.empty());

            Optional<FileRef> result = newService(RENDERER_URL)
                .capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isEmpty());
            verify(fileStorageService, never()).upload(any(), any(), any(), any(), any(), any(),
                any(InputStream.class), anyLong());
        }

        @Test
        @DisplayName("Workflow lookup projection works outside an open Hibernate session (regression for LazyInitializationException)")
        void resolvesWorkflowIdViaProjectionNotLazyAssociation() {
            // Pin the fix: previous version called runEntity.getWorkflow().getId() on a LAZY
            // @ManyToOne, which threw LazyInitializationException under spring.jpa.open-in-view=false
            // (caught silently → feature dead in production). The repo projection
            // findWorkflowIdByRunIdPublic does a JPQL field access - no proxy initialization.
            byte[] png = "fake-png".getBytes();
            FileRef uploaded = FileRef.of("tenant-1/key", "x.png", "image/png", png.length);
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt())).thenReturn(Optional.of(sampleSnapshot()));
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(png, HttpStatus.OK));
            stubWorkflowLookup();
            when(fileStorageService.upload(any(), any(), any(), any(), any(), any(),
                any(InputStream.class), anyLong(), anyInt(), anyInt(), nullable(Integer.class), anyString())).thenReturn(uploaded);

            Optional<FileRef> result = newService(RENDERER_URL)
                .capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isPresent());
            verify(workflowRunRepository).findWorkflowIdByRunIdPublic(RUN_ID);
            verify(workflowRunRepository, never()).findByRunIdPublic(any());
        }

        @Test
        @DisplayName("Upload throws → empty (best-effort)")
        void uploadExceptionIsSwallowed() {
            byte[] png = "fake".getBytes();
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt())).thenReturn(Optional.of(sampleSnapshot()));
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(png, HttpStatus.OK));
            stubWorkflowLookup();
            when(fileStorageService.upload(any(), any(), any(), any(), any(), any(),
                any(InputStream.class), anyLong(), anyInt(), anyInt(), nullable(Integer.class), anyString()))
                .thenThrow(new RuntimeException("s3 boom"));

            Optional<FileRef> result = newService(RENDERER_URL)
                .capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Backpressure - DistributedSemaphore cap on the sidecar")
    class Backpressure {

        /** Build a service with explicit semaphore + max-permits so each test pins its capacity. */
        private InterfaceScreenshotServiceImpl newServiceWithSemaphore(
            com.apimarketplace.common.scaling.lock.DistributedSemaphore sem, int max) {
            return new InterfaceScreenshotServiceImpl(
                renderService, fileStorageService, workflowRunRepository,
                restTemplate, objectMapper, RENDERER_URL, sem, max);
        }

        @Test
        @DisplayName("At capacity → empty result, render service NEVER called (skip is silent)")
        void atCapacityShortCircuitsBeforeRender() {
            com.apimarketplace.common.scaling.lock.DistributedSemaphore sem =
                mock(com.apimarketplace.common.scaling.lock.DistributedSemaphore.class);
            when(sem.tryAcquire(eq("screenshot:sidecar"), eq(4), anyString())).thenReturn(false);

            Optional<FileRef> result = newServiceWithSemaphore(sem, 4)
                .capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isEmpty());
            verifyNoInteractions(renderService, restTemplate, fileStorageService);
            verify(sem, never()).release(any(), any());
        }

        @Test
        @DisplayName("Acquired permit → released after successful capture (no leak)")
        void releasedOnSuccess() {
            com.apimarketplace.common.scaling.lock.DistributedSemaphore sem =
                mock(com.apimarketplace.common.scaling.lock.DistributedSemaphore.class);
            when(sem.tryAcquire(eq("screenshot:sidecar"), eq(4), anyString())).thenReturn(true);
            byte[] png = "fake-png".getBytes();
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(sampleSnapshot()));
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(png, HttpStatus.OK));
            stubWorkflowLookup();
            FileRef fr = FileRef.of("p", "n.png", "image/png", 8L);
            when(fileStorageService.upload(any(), any(), any(), any(), any(), any(),
                any(InputStream.class), anyLong(), anyInt(), anyInt(), nullable(Integer.class), anyString())).thenReturn(fr);

            Optional<FileRef> result = newServiceWithSemaphore(sem, 4)
                .capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isPresent());
            verify(sem).release(eq("screenshot:sidecar"), anyString());
        }

        @Test
        @DisplayName("Acquired permit → released even when render throws (try/finally guard)")
        void releasedOnRenderException() {
            com.apimarketplace.common.scaling.lock.DistributedSemaphore sem =
                mock(com.apimarketplace.common.scaling.lock.DistributedSemaphore.class);
            when(sem.tryAcquire(eq("screenshot:sidecar"), eq(4), anyString())).thenReturn(true);
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("render boom"));

            Optional<FileRef> result = newServiceWithSemaphore(sem, 4)
                .capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isEmpty());
            verify(sem).release(eq("screenshot:sidecar"), anyString());
        }

        @Test
        @DisplayName("Blank rendererBaseUrl → semaphore NOT acquired (early skip preserves perms for callers that can actually run)")
        void blankUrlSkipsBeforeAcquire() {
            com.apimarketplace.common.scaling.lock.DistributedSemaphore sem =
                mock(com.apimarketplace.common.scaling.lock.DistributedSemaphore.class);
            InterfaceScreenshotServiceImpl service = new InterfaceScreenshotServiceImpl(
                renderService, fileStorageService, workflowRunRepository,
                restTemplate, objectMapper, "", sem, 4);

            Optional<FileRef> result = service.capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isEmpty());
            verifyNoInteractions(sem);
        }

        @Test
        @DisplayName("Integration with InMemorySemaphore: cap=1 → second concurrent call short-circuits")
        void inMemorySemaphoreCapEnforced() {
            com.apimarketplace.common.scaling.lock.InMemorySemaphore real =
                new com.apimarketplace.common.scaling.lock.InMemorySemaphore();
            // Pre-occupy the single permit so the next call lands at-capacity.
            assertTrue(real.tryAcquire("screenshot:sidecar", 1, "other-owner"));

            Optional<FileRef> result = newServiceWithSemaphore(real, 1)
                .capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);

            assertTrue(result.isEmpty(), "Cap=1 with one pre-acquired permit must short-circuit");
            verifyNoInteractions(renderService, restTemplate, fileStorageService);

            // Release the held permit and verify the next call can proceed.
            real.release("screenshot:sidecar", "other-owner");
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.empty()); // empty snapshot is enough to validate flow

            Optional<FileRef> next = newServiceWithSemaphore(real, 1)
                .capture(TENANT, RUN_ID, EPOCH, NODE_ID, INTERFACE_UUID);
            assertTrue(next.isEmpty()); // still empty (no snapshot) but render WAS called this time
            verify(renderService).resolveTemplateSnapshot(any(), any(), any(), anyInt());
        }
    }
}
