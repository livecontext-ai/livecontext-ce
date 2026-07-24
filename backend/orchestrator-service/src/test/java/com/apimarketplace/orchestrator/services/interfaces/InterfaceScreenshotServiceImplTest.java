package com.apimarketplace.orchestrator.services.interfaces;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.ResolvedTemplateSnapshot;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
    @Mock private RestTemplate videoRestTemplate;

    private ObjectMapper objectMapper;

    private static final long DEFAULT_VIDEO_MAX_BYTES = 52_428_800L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    private InterfaceScreenshotServiceImpl newService(String rendererUrl) {
        return newService(rendererUrl, DEFAULT_VIDEO_MAX_BYTES);
    }

    private InterfaceScreenshotServiceImpl newService(String rendererUrl, long videoMaxBytes) {
        // Pass an unbounded InMemorySemaphore so tests that don't exercise backpressure
        // can ignore the cap. The Backpressure nested class uses an explicit semaphore.
        return new InterfaceScreenshotServiceImpl(
            renderService, fileStorageService, workflowRunRepository,
            restTemplate, videoRestTemplate, objectMapper, rendererUrl,
            new com.apimarketplace.common.scaling.lock.InMemorySemaphore(),
            Integer.MAX_VALUE, Integer.MAX_VALUE, videoMaxBytes);
    }

    private ResolvedTemplateSnapshot sampleSnapshot() {
        // Reflects what InterfaceRenderService.resolveTemplateSnapshot would return for an
        // interface with template "<h1>{{title|placeholder}}</h1>" and items[0].data={title=Hello}
        // - i.e. the {{var|default}} substitution has already been applied.
        return sampleSnapshotWithFormat(null);
    }

    /**
     * Same snapshot, carrying the interface's declared format. This is where the format reaches
     * the service: it is a property of the INTERFACE, resolved with its templates, never a
     * capture argument.
     */
    private ResolvedTemplateSnapshot sampleSnapshotWithFormat(String format) {
        return new ResolvedTemplateSnapshot(
            "<h1>Hello</h1>",
            "h1 { color: blue }",
            "",
            Map.of("title", "Hello"),
            format
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
        assertEquals(11, autowiredConstructors.get(0).getParameterCount());
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
    @DisplayName("Screenshot body - global format viewport")
    class ScreenshotFormatBody {

        private ArgumentCaptor<HttpEntity<Map<String, Object>>> stubSuccessfulScreenshotFlow() {
            return stubSuccessfulScreenshotFlow(null);
        }

        /** @param format the format the INTERFACE declares (null = none), as seen by the service. */
        private ArgumentCaptor<HttpEntity<Map<String, Object>>> stubSuccessfulScreenshotFlow(String format) {
            byte[] png = "png".getBytes();
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(sampleSnapshotWithFormat(format)));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor =
                ArgumentCaptor.forClass(HttpEntity.class);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), bodyCaptor.capture(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(png, HttpStatus.OK));
            stubWorkflowLookup();
            when(fileStorageService.upload(any(), anyString(), any(), any(), anyString(), anyString(),
                any(InputStream.class), anyLong(), anyInt(), anyInt(), nullable(Integer.class), anyString()))
                .thenReturn(FileRef.of("p", "f.png", "image/png", png.length));
            return bodyCaptor;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> viewportOf(Map<String, Object> body) {
            return (Map<String, Object>) body.get("viewport");
        }

        @Test
        @DisplayName("No format → classic 1280x800 viewport + fullPage=true (pins the historical behaviour)")
        void noFormatKeepsClassicFullPageCapture() {
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = stubSuccessfulScreenshotFlow();

            newService(RENDERER_URL)
                .capture(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID);

            Map<String, Object> sent = bodyCaptor.getValue().getBody();
            assertEquals(1280, viewportOf(sent).get("width"));
            assertEquals(800, viewportOf(sent).get("height"));
            assertEquals(Boolean.TRUE, sent.get("fullPage"),
                "without a format the whole scrollable interface must be captured");
        }

        @Test
        @DisplayName("interface declares 'vertical' → 1080x1920 viewport + fullPage=false (exact frame, matches the video)")
        void verticalFormatDrivesExactFrameCapture() {
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = stubSuccessfulScreenshotFlow("vertical");

            newService(RENDERER_URL)
                .capture(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID);

            Map<String, Object> sent = bodyCaptor.getValue().getBody();
            assertEquals(1080, viewportOf(sent).get("width"));
            assertEquals(1920, viewportOf(sent).get("height"));
            assertEquals(Boolean.FALSE, sent.get("fullPage"),
                "with a format the capture must be the exact WxH frame, not full-page");
        }

        @Test
        @DisplayName("Interface declares an unresolvable format → falls back to 1280x800 + fullPage=true")
        void unresolvableFormatFallsBackToClassicFullPage() {
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = stubSuccessfulScreenshotFlow("garbage");

            newService(RENDERER_URL)
                .capture(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID);

            Map<String, Object> sent = bodyCaptor.getValue().getBody();
            assertEquals(1280, viewportOf(sent).get("width"));
            assertEquals(800, viewportOf(sent).get("height"));
            assertEquals(Boolean.TRUE, sent.get("fullPage"),
                "an unknown format must keep the historical full-page capture, never break the render");
        }
    }

    @Nested
    @DisplayName("Assembled HTML - platform base CSS contract (fragments only)")
    class AssembledHtmlBaseCss {

        @SuppressWarnings("unchecked")
        private ArgumentCaptor<HttpEntity<Map<String, Object>>> stubFlowWithSnapshot(ResolvedTemplateSnapshot snapshot) {
            byte[] png = "png".getBytes();
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(snapshot));
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor =
                ArgumentCaptor.forClass(HttpEntity.class);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), bodyCaptor.capture(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(png, HttpStatus.OK));
            stubWorkflowLookup();
            when(fileStorageService.upload(any(), anyString(), any(), any(), anyString(), anyString(),
                any(InputStream.class), anyLong(), anyInt(), anyInt(), nullable(Integer.class), anyString()))
                .thenReturn(FileRef.of("p", "f.png", "image/png", png.length));
            return bodyCaptor;
        }

        private String sentHtml(ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor) {
            return (String) bodyCaptor.getValue().getBody().get("html");
        }

        @Test
        @DisplayName("Fragment template: the scaffold injects the platform base CSS BEFORE the author's cssTemplate")
        void fragmentScaffoldInjectsBaseCssBeforeAuthorCss() {
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = stubFlowWithSnapshot(
                new ResolvedTemplateSnapshot("<h1>Hello</h1>", "body { padding: 0 }", "", Map.of(), null));

            newService(RENDERER_URL).capture(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID);

            String html = sentHtml(bodyCaptor);
            int baseIdx = html.indexOf("padding: 8px");
            int authorIdx = html.indexOf("body { padding: 0 }");
            assertTrue(baseIdx > -1, "fragment scaffold must carry the platform base CSS");
            assertTrue(html.contains("box-sizing: border-box"));
            assertTrue(authorIdx > baseIdx,
                "the author's cssTemplate must come AFTER the base so every base rule stays overridable");
        }

        // Regression for the 2026-07-16 preview/video parity report: the frontend preview
        // injected base CSS into complete documents while this renderer path injected
        // nothing. The contract is now identical on both sides: complete documents
        // inherit NOTHING, fragments inherit the shared base.
        @Test
        @DisplayName("Complete document: NO platform base CSS is injected (author owns the page, matches the preview)")
        void completeDocumentGetsNoBaseCss() {
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = stubFlowWithSnapshot(
                new ResolvedTemplateSnapshot(
                    "<!DOCTYPE html><html><head><style>body{margin:0;padding:0}</style></head><body>Hi</body></html>",
                    "h1 { color: blue }", "", Map.of(), null));

            newService(RENDERER_URL).capture(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID);

            String html = sentHtml(bodyCaptor);
            assertFalse(html.contains("padding: 8px"),
                "a complete document must never inherit the platform base CSS");
            assertFalse(html.contains("box-sizing: border-box"));
            assertTrue(html.contains("h1 { color: blue }"),
                "the author's cssTemplate is still injected into the head");
        }

        // Byte-parity contract with the frontend preview: the SAME literal is pinned in
        // interfaceHtmlUtils.test.ts ("the injected base is the exact shared platform
        // literal"). Editing the base on either side without the other fails one suite.
        @Test
        @DisplayName("FRAGMENT_BASE_CSS is byte-identical to the frontend preview base (full-string pin)")
        void fragmentBaseCssPinsSharedRules() {
            String sharedFragmentBaseCss =
                "* { box-sizing: border-box; }\n"
                    + "body {\n"
                    + "  margin: 0;\n"
                    + "  padding: 8px;\n"
                    + "  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n"
                    + "}";
            assertEquals(sharedFragmentBaseCss, InterfaceScreenshotServiceImpl.FRAGMENT_BASE_CSS,
                "keep byte-identical to BASE_IFRAME_CSS in interfaceHtmlUtils.ts");
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
    @DisplayName("Video render (captureVideo)")
    class VideoRender {

        /**
         * Regression pack for the silent-truncation bug: the sidecar finalises a SHORTER but
         * perfectly valid mp4 when its wall-clock budget runs out and still answers 200, so a
         * 20s clip shipped as 9s with nothing in the response, the output or the logs to say
         * why. `X-Render-Truncated` is the only signal; these pin that we read it, that we say
         * so at WARN, and - critically - that a truncated clip is still UPLOADED (it is a
         * degraded result, never a failure).
         */
        @Nested
        @DisplayName("Wall-clock truncation signal (X-Render-Truncated)")
        class TruncationSignal {

            private Logger serviceLogger;
            private Level previousLevel;
            private ListAppender<ILoggingEvent> appender;

            @BeforeEach
            void attachAppender() {
                serviceLogger = (Logger) LoggerFactory.getLogger(InterfaceScreenshotServiceImpl.class);
                previousLevel = serviceLogger.getLevel();
                appender = new ListAppender<>();
                appender.start();
                serviceLogger.addAppender(appender);
                serviceLogger.setLevel(Level.WARN);
            }

            @AfterEach
            void detachAppender() {
                serviceLogger.detachAppender(appender);
                serviceLogger.setLevel(previousLevel);
            }

            private Optional<FileRef> renderWithHeaders(HttpHeaders headers) {
                byte[] mp4 = "fake-mp4-bytes".getBytes();
                when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                    .thenReturn(Optional.of(sampleSnapshot()));
                when(videoRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                    .thenReturn(new ResponseEntity<>(mp4, headers, HttpStatus.OK));
                stubWorkflowLookup();
                lenient().when(fileStorageService.upload(eq(TENANT), anyString(), eq(RUN_ID), eq(NODE_ID),
                        anyString(), eq("video/mp4"), any(InputStream.class), anyLong(),
                        anyInt(), anyInt(), nullable(Integer.class), anyString()))
                    .thenReturn(FileRef.of("k", "n.mp4", "video/mp4", mp4.length));

                return newService(RENDERER_URL)
                    .captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID,
                        "vertical", 20, "smooth", 60);
            }

            private String warnings() {
                return appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);
            }

            @Test
            @DisplayName("X-Render-Truncated=true → WARNs with the frame count and the resulting seconds, and STILL uploads the short clip")
            void truncatedRenderWarnsAndStillUploads() {
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Render-Truncated", "true");
                headers.set("X-Render-Frames", "540");

                Optional<FileRef> result = renderWithHeaders(headers);

                assertTrue(result.isPresent(),
                    "a truncated clip is a valid, shorter mp4 - it must still be uploaded, never dropped");
                String warned = warnings();
                assertTrue(warned.contains("TRUNCATED"), "truncation must be surfaced at WARN, got: " + warned);
                assertTrue(warned.contains("540"), "the WARN must carry the frame count: " + warned);
                assertTrue(warned.contains("9.0"),
                    "the WARN must state the DELIVERED seconds (540 frames / 60fps = 9.0s), which is the "
                        + "number the user actually sees: " + warned);
            }

            @Test
            @DisplayName("X-Render-Truncated=false → no truncation WARN (a clip ended by the page's own done flag is normal)")
            void completeRenderDoesNotWarn() {
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Render-Truncated", "false");
                headers.set("X-Render-Frames", "1200");

                assertTrue(renderWithHeaders(headers).isPresent());
                assertFalse(warnings().contains("TRUNCATED"),
                    "a complete clip must stay silent, or the WARN becomes noise nobody reads");
            }

            @Test
            @DisplayName("Headers absent (live mode / older sidecar) → treated as not truncated, no WARN, render unaffected")
            void missingHeadersAreBackCompatible() {
                assertTrue(renderWithHeaders(new HttpHeaders()).isPresent(),
                    "a sidecar that does not send the header must keep working unchanged");
                assertFalse(warnings().contains("TRUNCATED"));
            }

            @Test
            @DisplayName("Production video-max-bytes default carries a full 20s@60fps clip (47.4MB measured) - under it the clip is DROPPED, not shortened")
            void videoMaxBytesDefaultFitsAFullLengthClip() {
                // The wall-budget fix lets a busy 1080x1920@60fps clip run its full 20s instead of
                // being cut at 9s, which grows it ~2.2x. A MEASURED prod render of such a page is
                // 47.4MB. Over the cap, render() returns Optional.empty() - i.e. raising the budget
                // without raising this turns "short video" into NO video. Pinned as a value so the
                // two numbers can never drift apart unnoticed.
                Constructor<?> autowired = Arrays.stream(InterfaceScreenshotServiceImpl.class.getConstructors())
                    .filter(c -> c.isAnnotationPresent(Autowired.class))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no @Autowired constructor"));
                String declared = Arrays.stream(autowired.getParameters())
                    .filter(p -> p.isAnnotationPresent(org.springframework.beans.factory.annotation.Value.class))
                    .map(p -> p.getAnnotation(org.springframework.beans.factory.annotation.Value.class).value())
                    .filter(v -> v.contains("video-max-bytes"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("video-max-bytes @Value not found"));

                long defaultBytes = Long.parseLong(declared.substring(declared.indexOf(':') + 1, declared.indexOf('}')));
                long measuredFullLengthClipBytes = 47_400_000L;
                assertTrue(defaultBytes > measuredFullLengthClipBytes,
                    "video-max-bytes default (" + defaultBytes + ") must exceed the measured 20s@60fps clip ("
                        + measuredFullLengthClipBytes + "), or the fix ships no video at all");
                assertEquals(104857600L, defaultBytes, "100MB: measured worst case + margin, 2 concurrent within the heap");
            }

            @Test
            @DisplayName("A clip over video-max-bytes is dropped (Optional.empty) rather than uploaded")
            void oversizedClipIsDropped() {
                byte[] huge = new byte[64];
                when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                    .thenReturn(Optional.of(sampleSnapshot()));
                when(videoRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                    .thenReturn(new ResponseEntity<>(huge, HttpStatus.OK));

                Optional<FileRef> result = newService(RENDERER_URL, /* videoMaxBytes */ 32L)
                    .captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID,
                        "vertical", 20, "smooth", 60);

                assertTrue(result.isEmpty(),
                    "over the cap the clip is discarded entirely - this is why the cap must exceed a full-length clip");
                verifyNoInteractions(fileStorageService);
            }

            @Test
            @DisplayName("Header names mirror the sidecar's exported literals (cross-layer contract)")
            void headerNamesMatchTheSidecarContract() {
                // The sidecar pins the same two literals (lib.js RENDER_HEADER_*). Renaming a
                // header on ONE side alone is invisible at runtime - a truncated clip simply
                // answers 200 with a short mp4 again - so both sides pin them independently.
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Render-Truncated", "true");
                headers.set("X-Render-Frames", "540");

                assertTrue(renderWithHeaders(headers).isPresent());
                assertTrue(warnings().contains("TRUNCATED"),
                    "the service must read exactly 'X-Render-Truncated'/'X-Render-Frames' as emitted by the sidecar");
            }

            @Test
            @DisplayName("Unparseable X-Render-Frames → still WARNs about truncation, never throws on the frame count")
            void unparseableFrameCountDoesNotBreakTheRender() {
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Render-Truncated", "true");
                headers.set("X-Render-Frames", "not-a-number");

                Optional<FileRef> result = renderWithHeaders(headers);

                assertTrue(result.isPresent(), "an informational header must never fail a good render");
                assertTrue(warnings().contains("TRUNCATED"),
                    "the truncation itself is still worth reporting even with an unusable frame count");
            }
        }

        @Test
        @DisplayName("Successful recording → uploads <label>_video_epoch_N_spawn_M.mp4 with mime video/mp4 and sourceType INTERFACE_VIDEO, via the LONG-TIMEOUT RestTemplate")
        void successfulVideoUploadsWithVideoNameMimeAndSourceType() {
            byte[] mp4 = "fake-mp4-bytes".getBytes();
            FileRef uploaded = FileRef.of("k", "n.mp4", "video/mp4", mp4.length);

            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(sampleSnapshot()));
            when(videoRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(mp4, HttpStatus.OK));
            stubWorkflowLookup();

            ArgumentCaptor<String> fileNameCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> sourceTypeCaptor = ArgumentCaptor.forClass(String.class);
            when(fileStorageService.upload(eq(TENANT), anyString(), eq(RUN_ID), eq(NODE_ID),
                fileNameCaptor.capture(), eq("video/mp4"), any(InputStream.class), eq((long) mp4.length),
                anyInt(), anyInt(), nullable(Integer.class), sourceTypeCaptor.capture()))
                .thenReturn(uploaded);

            Optional<FileRef> result = newService(RENDERER_URL)
                .captureVideo(TENANT, RUN_ID, /* epoch */ 2, /* spawn */ 3, /* itemIndex */ 4,
                    NODE_ID, INTERFACE_UUID, "vertical", 30, null, null);

            assertTrue(result.isPresent());
            assertEquals(uploaded, result.get());
            assertEquals("my_form_video_epoch_2_spawn_3.mp4", fileNameCaptor.getValue(),
                "video filename must carry the _video_ segment + epoch + spawn and a .mp4 extension");
            assertEquals(
                com.apimarketplace.common.storage.service.StorageSourceTypes.INTERFACE_VIDEO,
                sourceTypeCaptor.getValue(),
                "interface videos are tagged sourceType=INTERFACE_VIDEO");
            // The recording outlives the default 30s read timeout - it MUST go through the
            // dedicated long-timeout template, never the shared one.
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Request targets /internal/render/video and forwards preset + mp4 format + maxDurationMs + waitForDone")
        @SuppressWarnings("unchecked")
        void requestTargetsVideoEndpointWithPresetAndDuration() {
            byte[] mp4 = "mp4".getBytes();
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(sampleSnapshot()));
            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            when(videoRestTemplate.exchange(urlCaptor.capture(), eq(HttpMethod.POST), bodyCaptor.capture(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(mp4, HttpStatus.OK));
            stubWorkflowLookup();
            when(fileStorageService.upload(any(), anyString(), any(), any(), anyString(), anyString(),
                any(InputStream.class), anyLong(), anyInt(), anyInt(), nullable(Integer.class), anyString()))
                .thenReturn(FileRef.of("p", "f.mp4", "video/mp4", mp4.length));

            newService(RENDERER_URL)
                .captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID, "Square", 45, null, null);

            assertTrue(urlCaptor.getValue().endsWith("/internal/render/video"),
                "video render must hit the /internal/render/video endpoint, got: " + urlCaptor.getValue());
            Map<String, Object> sent = bodyCaptor.getValue().getBody();
            assertEquals("square", sent.get("preset"), "preset is lowercased before hitting the sidecar");
            assertEquals("mp4", sent.get("format"));
            assertEquals(45_000, sent.get("maxDurationMs"));
            assertEquals(Boolean.TRUE, sent.get("waitForDone"),
                "the page must be able to end its own clip via window.__DONE__");
        }

        @Test
        @DisplayName("Null preset/duration default to vertical + 30s; out-of-range durations are clamped to 5-120s")
        @SuppressWarnings("unchecked")
        void presetAndDurationDefaultsAndClamps() {
            byte[] mp4 = "mp4".getBytes();
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(sampleSnapshot()));
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            when(videoRestTemplate.exchange(anyString(), eq(HttpMethod.POST), bodyCaptor.capture(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(mp4, HttpStatus.OK));
            stubWorkflowLookup();
            when(fileStorageService.upload(any(), anyString(), any(), any(), anyString(), anyString(),
                any(InputStream.class), anyLong(), anyInt(), anyInt(), nullable(Integer.class), anyString()))
                .thenReturn(FileRef.of("p", "f.mp4", "video/mp4", mp4.length));

            InterfaceScreenshotServiceImpl service = newService(RENDERER_URL);

            service.captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID, null, null, null, null);
            Map<String, Object> defaults = bodyCaptor.getValue().getBody();
            assertEquals("vertical", defaults.get("preset"));
            assertEquals(30_000, defaults.get("maxDurationMs"));

            service.captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID, "vertical", 999, null, null);
            assertEquals(120_000, bodyCaptor.getValue().getBody().get("maxDurationMs"),
                "durations above 120s must clamp to the renderer's hard maximum");

            service.captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID, "vertical", 2, null, null);
            assertEquals(5_000, bodyCaptor.getValue().getBody().get("maxDurationMs"),
                "durations below 5s clamp up to the minimum useful clip length");
        }

        @Test
        @DisplayName("Oversized recording (> videoMaxBytes) → empty, no upload (storage guard)")
        void oversizedVideoSkipsUpload() {
            byte[] big = new byte[64];
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(sampleSnapshot()));
            when(videoRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(big, HttpStatus.OK));

            // Cap below the returned payload: the upload must be skipped, best-effort style.
            Optional<FileRef> result = newService(RENDERER_URL, /* videoMaxBytes */ 32L)
                .captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID, "vertical", 30, null, null);

            assertTrue(result.isEmpty());
            verifyNoInteractions(fileStorageService);
        }

        @Test
        @DisplayName("Sidecar failure → RestClientException swallowed, empty result (best-effort)")
        void sidecarErrorIsSwallowedForVideo() {
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(sampleSnapshot()));
            when(videoRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
                .thenThrow(new RestClientException("recording timed out"));

            Optional<FileRef> result = newService(RENDERER_URL)
                .captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID, "vertical", 30, null, null);

            assertTrue(result.isEmpty());
            verifyNoInteractions(fileStorageService);
        }

        @Test
        @DisplayName("Blank renderer URL → empty, no render/HTTP/upload (best-effort skip)")
        void blankUrlSkipsVideo() {
            Optional<FileRef> result = newService("")
                .captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID, "vertical", 30, null, null);
            assertTrue(result.isEmpty());
            verifyNoInteractions(renderService, restTemplate, videoRestTemplate, fileStorageService, workflowRunRepository);
        }

        @Test
        @DisplayName("Video renders draw permits from the SEPARATE video:sidecar pool with their own cap")
        void videoUsesDedicatedSemaphorePool() {
            com.apimarketplace.common.scaling.lock.DistributedSemaphore sem =
                mock(com.apimarketplace.common.scaling.lock.DistributedSemaphore.class);
            when(sem.tryAcquire(eq("video:sidecar"), eq(2), anyString())).thenReturn(false);
            InterfaceScreenshotServiceImpl service = new InterfaceScreenshotServiceImpl(
                renderService, fileStorageService, workflowRunRepository,
                restTemplate, videoRestTemplate, objectMapper, RENDERER_URL, sem,
                /* maxConcurrent */ 4, /* videoMaxConcurrent */ 2, DEFAULT_VIDEO_MAX_BYTES);

            Optional<FileRef> result = service
                .captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID, "vertical", 30, null, null);

            assertTrue(result.isEmpty(), "at video capacity the render is skipped silently");
            verify(sem).tryAcquire(eq("video:sidecar"), eq(2), anyString());
            verify(sem, never()).tryAcquire(eq("screenshot:sidecar"), anyInt(), anyString());
            verifyNoInteractions(renderService, videoRestTemplate, fileStorageService);
        }

        @Test
        @DisplayName("Unknown preset from a hand-written/imported plan falls back to vertical in the sidecar body (never forwarded raw)")
        @SuppressWarnings("unchecked")
        void unknownPresetFallsBackToVertical() {
            // The agent path normalises via InterfaceNodeConfig, but WorkflowPlanParser keeps the
            // raw plan string - the service is the last line of defence. Forwarding 'cinema'
            // would 400 at the sidecar and silently drop the output.
            byte[] mp4 = "mp4".getBytes();
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(sampleSnapshot()));
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            when(videoRestTemplate.exchange(anyString(), eq(HttpMethod.POST), bodyCaptor.capture(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(mp4, HttpStatus.OK));
            stubWorkflowLookup();
            when(fileStorageService.upload(any(), anyString(), any(), any(), anyString(), anyString(),
                any(InputStream.class), anyLong(), anyInt(), anyInt(), nullable(Integer.class), anyString()))
                .thenReturn(FileRef.of("p", "f.mp4", "video/mp4", mp4.length));

            newService(RENDERER_URL)
                .captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID, "cinema", 30, null, null);

            assertEquals("vertical", bodyCaptor.getValue().getBody().get("preset"),
                "unknown presets must fall back to vertical, honouring the documented contract");
        }

        @Test
        @DisplayName("Sidecar body carries mode=smooth + fps=30 by default; explicit live/60 forwarded; clamps applied")
        @SuppressWarnings("unchecked")
        void modeAndFpsDefaultsAndForwarding() {
            byte[] mp4 = "mp4".getBytes();
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(sampleSnapshot()));
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            when(videoRestTemplate.exchange(anyString(), eq(HttpMethod.POST), bodyCaptor.capture(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(mp4, HttpStatus.OK));
            stubWorkflowLookup();
            when(fileStorageService.upload(any(), anyString(), any(), any(), anyString(), anyString(),
                any(InputStream.class), anyLong(), anyInt(), anyInt(), nullable(Integer.class), anyString()))
                .thenReturn(FileRef.of("p", "f.mp4", "video/mp4", mp4.length));

            InterfaceScreenshotServiceImpl service = newService(RENDERER_URL);

            // Defaults: null mode/fps → the fluid offline renderer at 30fps.
            service.captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID, "vertical", 30, null, null);
            Map<String, Object> defaults = bodyCaptor.getValue().getBody();
            assertEquals("smooth", defaults.get("mode"), "smooth is the default render mode");
            assertEquals(30, defaults.get("fps"));

            // Explicit live + 60fps forwarded (mode lowercased).
            service.captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID, "vertical", 30, "LIVE", 60);
            Map<String, Object> explicit = bodyCaptor.getValue().getBody();
            assertEquals("live", explicit.get("mode"), "mode is lowercased before hitting the sidecar");
            assertEquals(60, explicit.get("fps"));

            // Unknown mode falls back to smooth; out-of-range fps clamps to 60.
            service.captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID, "vertical", 30, "turbo", 240);
            Map<String, Object> clamped = bodyCaptor.getValue().getBody();
            assertEquals("smooth", clamped.get("mode"));
            assertEquals(60, clamped.get("fps"), "fps above 60 clamps to the maximum");
        }

        @Test
        @DisplayName("normalizeVideoModeOrDefault + clampVideoFps: bounds and fallbacks")
        void modeAndFpsHelperBounds() {
            assertEquals("smooth", InterfaceScreenshotServiceImpl.normalizeVideoModeOrDefault(null));
            assertEquals("smooth", InterfaceScreenshotServiceImpl.normalizeVideoModeOrDefault("  "));
            assertEquals("smooth", InterfaceScreenshotServiceImpl.normalizeVideoModeOrDefault("turbo"));
            assertEquals("live", InterfaceScreenshotServiceImpl.normalizeVideoModeOrDefault("Live "));
            assertEquals(30, InterfaceScreenshotServiceImpl.clampVideoFps(null));
            assertEquals(30, InterfaceScreenshotServiceImpl.clampVideoFps(0));
            assertEquals(10, InterfaceScreenshotServiceImpl.clampVideoFps(5));
            assertEquals(60, InterfaceScreenshotServiceImpl.clampVideoFps(120));
            assertEquals(24, InterfaceScreenshotServiceImpl.clampVideoFps(24));
        }

        @Test
        @DisplayName("normalizeVideoPresetOrDefault: supported presets normalised, unknown/blank/null → vertical")
        void normalizeVideoPresetOrDefaultBounds() {
            assertEquals("vertical", InterfaceScreenshotServiceImpl.normalizeVideoPresetOrDefault(null));
            assertEquals("vertical", InterfaceScreenshotServiceImpl.normalizeVideoPresetOrDefault("  "));
            assertEquals("vertical", InterfaceScreenshotServiceImpl.normalizeVideoPresetOrDefault("cinema"));
            assertEquals("horizontal", InterfaceScreenshotServiceImpl.normalizeVideoPresetOrDefault("Horizontal"));
            assertEquals("square", InterfaceScreenshotServiceImpl.normalizeVideoPresetOrDefault("SQUARE "));
        }

        @Test
        @DisplayName("normalizeVideoPresetOrNull: null/blank/unknown → null, supported preset lowercased")
        void normalizeVideoPresetOrNullBounds() {
            assertNull(InterfaceScreenshotServiceImpl.normalizeVideoPresetOrNull(null));
            assertNull(InterfaceScreenshotServiceImpl.normalizeVideoPresetOrNull("  "));
            assertNull(InterfaceScreenshotServiceImpl.normalizeVideoPresetOrNull("weird"));
            assertEquals("square", InterfaceScreenshotServiceImpl.normalizeVideoPresetOrNull("SQUARE"));
            assertEquals("vertical", InterfaceScreenshotServiceImpl.normalizeVideoPresetOrNull(" Vertical "));
        }

        @SuppressWarnings("unchecked")
        private ArgumentCaptor<HttpEntity<Map<String, Object>>> stubSuccessfulVideoFlow() {
            return stubSuccessfulVideoFlow(null);
        }

        /** @param format the format the INTERFACE declares (null = none), as seen by the service. */
        private ArgumentCaptor<HttpEntity<Map<String, Object>>> stubSuccessfulVideoFlow(String format) {
            byte[] mp4 = "mp4".getBytes();
            when(renderService.resolveTemplateSnapshot(any(), any(), any(), anyInt()))
                .thenReturn(Optional.of(sampleSnapshotWithFormat(format)));
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor =
                ArgumentCaptor.forClass(HttpEntity.class);
            when(videoRestTemplate.exchange(anyString(), eq(HttpMethod.POST), bodyCaptor.capture(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(mp4, HttpStatus.OK));
            stubWorkflowLookup();
            when(fileStorageService.upload(any(), anyString(), any(), any(), anyString(), anyString(),
                any(InputStream.class), anyLong(), anyInt(), anyInt(), nullable(Integer.class), anyString()))
                .thenReturn(FileRef.of("p", "f.mp4", "video/mp4", mp4.length));
            return bodyCaptor;
        }

        @Test
        @DisplayName("format + NO preset → body carries an explicit viewport {1080,1920} and NO preset key")
        void formatWithoutPresetSendsViewportInsteadOfPreset() {
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = stubSuccessfulVideoFlow("vertical");

            newService(RENDERER_URL)
                .captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID,
                    /* videoPreset */ null, 30, null, null);

            Map<String, Object> sent = bodyCaptor.getValue().getBody();
            @SuppressWarnings("unchecked")
            Map<String, Object> viewport = (Map<String, Object>) sent.get("viewport");
            assertNotNull(viewport, "the interface's own format must drive the recording viewport");
            assertEquals(1080, viewport.get("width"));
            assertEquals(1920, viewport.get("height"));
            assertFalse(sent.containsKey("preset"),
                "an explicit viewport must replace the preset key, not accompany it");
        }

        @Test
        @DisplayName("explicit preset 'square' + format 'vertical' → preset wins: body has preset=square and NO viewport")
        void explicitPresetWinsOverFormat() {
            // The interface DECLARES vertical: the preset must beat it, otherwise there is no
            // precedence to prove. Stubbing no format here makes the assertions below trivially
            // true and lets an inverted rule (format beating the preset) pass unnoticed.
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = stubSuccessfulVideoFlow("vertical");

            newService(RENDERER_URL)
                .captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID,
                    /* videoPreset */ "square", 30, null, null);

            Map<String, Object> sent = bodyCaptor.getValue().getBody();
            assertEquals("square", sent.get("preset"),
                "an explicit valid videoPreset is a per-video override that beats the interface's format");
            assertFalse(sent.containsKey("viewport"),
                "when the preset wins the interface's format viewport must not be sent");
        }

        @Test
        @DisplayName("unknown preset + valid format → format drives the viewport (invalid preset does not block the fallback)")
        void unknownPresetLetsFormatDriveViewport() {
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = stubSuccessfulVideoFlow("square");

            newService(RENDERER_URL)
                .captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID,
                    /* videoPreset */ "cinema", 30, null, null);

            Map<String, Object> sent = bodyCaptor.getValue().getBody();
            @SuppressWarnings("unchecked")
            Map<String, Object> viewport = (Map<String, Object>) sent.get("viewport");
            assertNotNull(viewport, "an unknown preset counts as 'no explicit preset', so the format applies");
            assertEquals(1080, viewport.get("width"));
            assertEquals(1080, viewport.get("height"));
            assertFalse(sent.containsKey("preset"));
        }

        @Test
        @DisplayName("no preset + unresolvable format → falls back to preset=vertical (historical default, no viewport)")
        void unresolvableFormatFallsBackToVerticalPreset() {
            // The interface declares a format that resolves to nothing: the recording must not
            // break, it falls back to the vertical default (the third rung of the precedence).
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = stubSuccessfulVideoFlow("garbage");

            newService(RENDERER_URL)
                .captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID,
                    /* videoPreset */ null, 30, null, null);

            Map<String, Object> sent = bodyCaptor.getValue().getBody();
            assertEquals("vertical", sent.get("preset"));
            assertFalse(sent.containsKey("viewport"));
        }

        @Test
        @DisplayName("No preset and no declared format → vertical default (pins the bottom rung)")
        void noPresetAndNoFormatFallsBackToVerticalPreset() {
            ArgumentCaptor<HttpEntity<Map<String, Object>>> bodyCaptor = stubSuccessfulVideoFlow();

            newService(RENDERER_URL)
                .captureVideo(TENANT, RUN_ID, EPOCH, 0, null, NODE_ID, INTERFACE_UUID,
                    /* videoPreset */ null, 30, null, null);

            Map<String, Object> sent = bodyCaptor.getValue().getBody();
            assertEquals("vertical", sent.get("preset"));
            assertFalse(sent.containsKey("viewport"));
        }

        @Test
        @DisplayName("clampVideoDurationSeconds: null/non-positive → default 30; bounds enforced")
        void clampVideoDurationSecondsBounds() {
            assertEquals(30, InterfaceScreenshotServiceImpl.clampVideoDurationSeconds(null));
            assertEquals(30, InterfaceScreenshotServiceImpl.clampVideoDurationSeconds(0));
            assertEquals(30, InterfaceScreenshotServiceImpl.clampVideoDurationSeconds(-10));
            assertEquals(5, InterfaceScreenshotServiceImpl.clampVideoDurationSeconds(1));
            assertEquals(60, InterfaceScreenshotServiceImpl.clampVideoDurationSeconds(60));
            assertEquals(120, InterfaceScreenshotServiceImpl.clampVideoDurationSeconds(500));
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
                restTemplate, videoRestTemplate, objectMapper, RENDERER_URL, sem, max,
                /* videoMaxConcurrent */ 2, DEFAULT_VIDEO_MAX_BYTES);
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
                restTemplate, videoRestTemplate, objectMapper, "", sem, 4, 2, DEFAULT_VIDEO_MAX_BYTES);

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
