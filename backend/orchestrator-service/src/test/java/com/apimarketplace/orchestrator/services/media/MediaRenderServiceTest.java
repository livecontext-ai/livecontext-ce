package com.apimarketplace.orchestrator.services.media;

import com.apimarketplace.common.scaling.lock.DistributedSemaphore;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.services.media.MediaRenderService.FileResult;
import com.apimarketplace.orchestrator.services.media.MediaRenderService.MediaInput;
import com.apimarketplace.orchestrator.services.media.MediaRenderService.MediaRenderException;
import com.apimarketplace.orchestrator.services.media.MediaRenderService.MediaResult;
import com.apimarketplace.orchestrator.services.media.MediaRenderService.ProbeResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MediaRenderService}: multipart construction (spec JSON part +
 * named binary parts), probe JSON passthrough vs binary upload, the videoMaxBytes guard,
 * semaphore acquire/release discipline, and the owner-tenant download contract
 * (foreign/traversal keys refused before any storage call).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MediaRenderService")
class MediaRenderServiceTest {

    private static final String TENANT = "tenant-1";
    private static final String RENDERER_URL = "http://screenshot-renderer:8094";
    private static final String VIDEO_KEY = TENANT + "/wf/run/node/clip.mp4";
    private static final String AUDIO_KEY = TENANT + "/wf/run/node/music.mp3";
    private static final byte[] VIDEO_BYTES = "video-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AUDIO_BYTES = "audio-bytes".getBytes(StandardCharsets.UTF_8);

    @Mock private FileStorageService fileStorageService;
    @Mock private RestTemplate restTemplate;
    @Mock private DistributedSemaphore semaphore;

    @Captor private ArgumentCaptor<HttpEntity<?>> entityCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MediaRenderService service;

    @BeforeEach
    void setUp() {
        service = service(RENDERER_URL, 100_000_000L);
        when(semaphore.tryAcquire(anyString(), anyInt(), anyString())).thenReturn(true);
        when(fileStorageService.download(TENANT, VIDEO_KEY)).thenReturn(Optional.of(VIDEO_BYTES));
        when(fileStorageService.download(TENANT, AUDIO_KEY)).thenReturn(Optional.of(AUDIO_BYTES));
    }

    private MediaRenderService service(String url, long maxBytes) {
        return new MediaRenderService(fileStorageService, restTemplate, objectMapper, url, semaphore, 2, maxBytes);
    }

    private static Map<String, Object> fileRef(String key, String mime) {
        Map<String, Object> ref = new HashMap<>();
        ref.put("_type", "file");
        ref.put("path", key);
        ref.put("name", key.substring(key.lastIndexOf('/') + 1));
        ref.put("mimeType", mime);
        return ref;
    }

    private static Map<String, Object> fileRefWithSize(String key, String mime, long size) {
        Map<String, Object> ref = fileRef(key, mime);
        ref.put("size", size);
        return ref;
    }

    private static List<MediaInput> muxInputs() {
        return List.of(
            new MediaInput("input0", "video", null, fileRef(VIDEO_KEY, "video/mp4")),
            new MediaInput("input1", "audio", null, fileRef(AUDIO_KEY, "audio/mpeg")));
    }

    private MediaResult render(List<MediaInput> inputs, Map<String, Object> options) {
        return service.render(TENANT, "wf-1", "run-1", "core:add_music", 2, 0, null,
            "mux_audio", options, inputs);
    }

    private void stubBinaryResponse(byte[] body, String durationHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("video/mp4"));
        if (durationHeader != null) {
            headers.set("X-Media-Duration-Seconds", durationHeader);
        }
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
            .thenReturn(new ResponseEntity<>(body, headers, HttpStatus.OK));
    }

    // ==================== Availability + backpressure ====================

    @Test
    @DisplayName("blank renderer URL -> isEnabled false and render throws the unavailability exception")
    void disabledInstallationThrows() {
        MediaRenderService disabled = service("", 100L);

        assertFalse(disabled.isEnabled());
        MediaRenderException e = assertThrows(MediaRenderException.class,
            () -> disabled.render(TENANT, "wf", "run", "core:x", 1, 0, null, "probe", Map.of(), List.of()));
        assertTrue(e.getMessage().contains("renderer component"));
    }

    @Test
    @DisplayName("semaphore denied -> throws 'maximum number of jobs' without touching storage or HTTP")
    void semaphoreDeniedThrows() {
        when(semaphore.tryAcquire(anyString(), anyInt(), anyString())).thenReturn(false);

        MediaRenderException e = assertThrows(MediaRenderException.class,
            () -> render(muxInputs(), Map.of()));
        assertTrue(e.getMessage().contains("maximum number of jobs"));
        verifyNoInteractions(fileStorageService);
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("semaphore acquired on 'media:sidecar' and RELEASED after success")
    void semaphoreReleasedOnSuccess() {
        stubBinaryResponse(VIDEO_BYTES, "20.0");
        when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
            any(byte[].class), anyInt(), anyInt(), any(), anyString()))
            .thenReturn(FileRef.of("k", "n", "video/mp4", 1));

        render(muxInputs(), Map.of());

        ArgumentCaptor<String> ownerCaptor = ArgumentCaptor.forClass(String.class);
        verify(semaphore).tryAcquire(eq("media:sidecar"), eq(2), ownerCaptor.capture());
        verify(semaphore).release("media:sidecar", ownerCaptor.getValue());
    }

    @Test
    @DisplayName("semaphore RELEASED even when the render fails (foreign key refusal)")
    void semaphoreReleasedOnFailure() {
        List<MediaInput> foreign = List.of(
            new MediaInput("input0", "input", null, fileRef("tenant-9/wf/run/n/x.mp4", "video/mp4")));

        assertThrows(MediaRenderException.class, () -> render(foreign, Map.of()));

        verify(semaphore).release(eq("media:sidecar"), anyString());
    }

    // ==================== Owner-tenant download contract ====================

    @Nested
    @DisplayName("input download - owner-tenant contract")
    class InputDownload {

        @Test
        @DisplayName("downloads every input through the tenant-aware two-arg download (key-owner contract)")
        void ownerTenantDownloadUsed() {
            stubBinaryResponse(VIDEO_BYTES, null);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
                any(byte[].class), anyInt(), anyInt(), any(), anyString()))
                .thenReturn(FileRef.of("k", "n", "video/mp4", 1));

            render(muxInputs(), Map.of());

            verify(fileStorageService).download(TENANT, VIDEO_KEY);
            verify(fileStorageService).download(TENANT, AUDIO_KEY);
            verify(fileStorageService, never()).download(anyString());
        }

        @Test
        @DisplayName("FOREIGN tenant key -> refused with 'does not belong', storage never called")
        void foreignKeyRefused() {
            List<MediaInput> foreign = List.of(
                new MediaInput("input0", "input", null, fileRef("tenant-9/wf/run/n/secret.mp4", "video/mp4")));

            MediaRenderException e = assertThrows(MediaRenderException.class,
                () -> render(foreign, Map.of()));
            assertTrue(e.getMessage().contains("does not belong"));
            verify(fileStorageService, never()).download(anyString(), anyString());
        }

        @Test
        @DisplayName("traversal segment ('..') in an own-tenant-prefixed key -> refused before download")
        void traversalKeyRefused() {
            List<MediaInput> traversal = List.of(
                new MediaInput("input0", "input", null, fileRef(TENANT + "/../tenant-9/x.mp4", "video/mp4")));

            MediaRenderException e = assertThrows(MediaRenderException.class,
                () -> render(traversal, Map.of()));
            assertTrue(e.getMessage().contains("invalid storage path"));
            verify(fileStorageService, never()).download(anyString(), anyString());
        }

        @Test
        @DisplayName("FileRef without a path -> refused with the whole-FileRef hint")
        void missingPathRefused() {
            Map<String, Object> broken = new HashMap<>();
            broken.put("_type", "file");
            List<MediaInput> inputs = List.of(new MediaInput("input0", "input", null, broken));

            MediaRenderException e = assertThrows(MediaRenderException.class,
                () -> render(inputs, Map.of()));
            assertTrue(e.getMessage().contains("no storage path"));
        }

        @Test
        @DisplayName("empty download (file deleted) -> explicit 'could not be read' failure, not a silent empty part")
        void missingBytesThrows() {
            when(fileStorageService.download(TENANT, VIDEO_KEY)).thenReturn(Optional.empty());

            MediaRenderException e = assertThrows(MediaRenderException.class,
                () -> render(muxInputs(), Map.of()));
            assertTrue(e.getMessage().contains("could not be read from storage"));
            verifyNoInteractions(restTemplate);
        }
    }

    // ==================== Pre-flight declared-size check ====================

    @Nested
    @DisplayName("pre-flight input-size check - declared FileRef sizes vs the 300MB renderer cap")
    class PreFlightSizeCheck {

        @Test
        @DisplayName("declared sizes above MAX_TOTAL_INPUT_BYTES -> refused with the limit named, BEFORE any storage download")
        void oversizedDeclaredInputsRefusedBeforeDownload() {
            List<MediaInput> oversized = List.of(
                new MediaInput("input0", "video", null,
                    fileRefWithSize(VIDEO_KEY, "video/mp4", 200L * 1024 * 1024)),
                new MediaInput("input1", "audio", null,
                    fileRefWithSize(AUDIO_KEY, "audio/mpeg", 150L * 1024 * 1024)));

            MediaRenderException e = assertThrows(MediaRenderException.class,
                () -> render(oversized, Map.of()));

            assertTrue(e.getMessage().contains(String.valueOf(MediaRenderService.MAX_TOTAL_INPUT_BYTES)),
                "the failure must name the byte limit, got: " + e.getMessage());
            verifyNoInteractions(fileStorageService);
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("declared sizes summing EXACTLY to the limit pass the pre-flight and download normally")
        void declaredSizesAtLimitProceed() {
            stubBinaryResponse(VIDEO_BYTES, null);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
                any(byte[].class), anyInt(), anyInt(), any(), anyString()))
                .thenReturn(FileRef.of("k", "n", "video/mp4", 1));
            long half = MediaRenderService.MAX_TOTAL_INPUT_BYTES / 2;
            List<MediaInput> atLimit = List.of(
                new MediaInput("input0", "video", null, fileRefWithSize(VIDEO_KEY, "video/mp4", half)),
                new MediaInput("input1", "audio", null, fileRefWithSize(AUDIO_KEY, "audio/mpeg", half)));

            MediaResult result = render(atLimit, Map.of());

            assertInstanceOf(FileResult.class, result);
            verify(fileStorageService).download(TENANT, VIDEO_KEY);
            verify(fileStorageService).download(TENANT, AUDIO_KEY);
        }

        @Test
        @DisplayName("FileRefs WITHOUT a size field are not summed - the render proceeds (back-compat)")
        void missingSizesSkipPreFlight() {
            stubBinaryResponse(VIDEO_BYTES, null);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
                any(byte[].class), anyInt(), anyInt(), any(), anyString()))
                .thenReturn(FileRef.of("k", "n", "video/mp4", 1));

            MediaResult result = render(muxInputs(), Map.of());

            assertInstanceOf(FileResult.class, result);
        }
    }

    // ==================== Multipart construction ====================

    @Test
    @DisplayName("POST goes to <renderer>/internal/media with a spec JSON part naming operation/options/inputs and binary parts input0/input1")
    @SuppressWarnings("unchecked")
    void multipartConstruction() throws Exception {
        stubBinaryResponse(VIDEO_BYTES, "20.0");
        when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
            any(byte[].class), anyInt(), anyInt(), any(), anyString()))
            .thenReturn(FileRef.of("k", "n", "video/mp4", 1));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("volume", 80d);

        render(muxInputs(), options);

        verify(restTemplate).exchange(eq(RENDERER_URL + "/internal/media"), eq(HttpMethod.POST),
            entityCaptor.capture(), eq(byte[].class));
        HttpEntity<?> entity = entityCaptor.getValue();
        assertTrue(MediaType.MULTIPART_FORM_DATA.isCompatibleWith(entity.getHeaders().getContentType()));

        MultiValueMap<String, Object> body = (MultiValueMap<String, Object>) entity.getBody();
        assertNotNull(body);

        // spec part: application/json with {operation, options, inputs:[{name, role}]}
        HttpEntity<String> spec = (HttpEntity<String>) body.getFirst("spec");
        assertNotNull(spec, "spec part must be present");
        assertEquals(MediaType.APPLICATION_JSON, spec.getHeaders().getContentType());
        Map<String, Object> specJson = objectMapper.readValue(spec.getBody(), Map.class);
        assertEquals("mux_audio", specJson.get("operation"));
        assertEquals(80d, ((Map<String, Object>) specJson.get("options")).get("volume"));
        List<Map<String, Object>> inputs = (List<Map<String, Object>>) specJson.get("inputs");
        assertEquals(2, inputs.size());
        assertEquals(Map.of("name", "input0", "role", "video"), inputs.get(0));
        assertEquals(Map.of("name", "input1", "role", "audio"), inputs.get(1));

        // binary parts: named after the spec inputs, typed, carrying the downloaded bytes
        HttpEntity<ByteArrayResource> videoPart = (HttpEntity<ByteArrayResource>) body.getFirst("input0");
        assertNotNull(videoPart, "input0 binary part must be present");
        assertEquals("video/mp4", videoPart.getHeaders().getContentType().toString());
        assertArrayEquals(VIDEO_BYTES, videoPart.getBody().getByteArray());
        assertEquals("clip.mp4", videoPart.getBody().getFilename());

        HttpEntity<ByteArrayResource> audioPart = (HttpEntity<ByteArrayResource>) body.getFirst("input1");
        assertNotNull(audioPart, "input1 binary part must be present");
        assertArrayEquals(AUDIO_BYTES, audioPart.getBody().getByteArray());
    }

    @Test
    @DisplayName("concat: three clips POST as binary parts input0/input1/input2 with role video + clip index in the spec")
    @SuppressWarnings("unchecked")
    void concatMultipartPartNaming() throws Exception {
        stubBinaryResponse(VIDEO_BYTES, "42.0");
        when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
            any(byte[].class), anyInt(), anyInt(), any(), anyString()))
            .thenReturn(FileRef.of("k", "n", "video/mp4", 1));
        List<MediaInput> inputs = List.of(
            new MediaInput("input0", "video", 0, fileRef(VIDEO_KEY, "video/mp4")),
            new MediaInput("input1", "video", 1, fileRef(VIDEO_KEY, "video/mp4")),
            new MediaInput("input2", "video", 2, fileRef(VIDEO_KEY, "video/mp4")));

        service.render(TENANT, "wf-1", "run-1", "core:compile", 1, 0, null, "concat", Map.of(), inputs);

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), entityCaptor.capture(), eq(byte[].class));
        MultiValueMap<String, Object> body = (MultiValueMap<String, Object>) entityCaptor.getValue().getBody();
        assertNotNull(body.getFirst("input0"), "input0 binary part must be present");
        assertNotNull(body.getFirst("input1"), "input1 binary part must be present");
        assertNotNull(body.getFirst("input2"), "input2 binary part must be present");

        HttpEntity<String> spec = (HttpEntity<String>) body.getFirst("spec");
        Map<String, Object> specJson = objectMapper.readValue(spec.getBody(), Map.class);
        assertEquals("concat", specJson.get("operation"));
        List<Map<String, Object>> specInputs = (List<Map<String, Object>>) specJson.get("inputs");
        assertEquals(3, specInputs.size());
        assertEquals(Map.of("name", "input0", "role", "video", "trackIndex", 0), specInputs.get(0));
        assertEquals(Map.of("name", "input2", "role", "video", "trackIndex", 2), specInputs.get(2));
    }

    @Test
    @DisplayName("frame: video input0 + overlay-style extra parts follow the generic naming (input -> input0)")
    @SuppressWarnings("unchecked")
    void framePartNaming() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("image/jpeg"));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
            .thenReturn(new ResponseEntity<>(VIDEO_BYTES, headers, HttpStatus.OK));
        when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
            any(byte[].class), anyInt(), anyInt(), any(), anyString()))
            .thenReturn(FileRef.of("k", "n", "image/jpeg", 1));
        List<MediaInput> inputs = List.of(
            new MediaInput("input0", "input", null, fileRef(VIDEO_KEY, "video/mp4")));

        service.render(TENANT, "wf-1", "run-1", "core:cover", 1, 0, null, "frame", Map.of(), inputs);

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), entityCaptor.capture(), eq(byte[].class));
        MultiValueMap<String, Object> body = (MultiValueMap<String, Object>) entityCaptor.getValue().getBody();
        assertNotNull(body.getFirst("input0"), "frame's single video travels as input0");
        HttpEntity<String> spec = (HttpEntity<String>) body.getFirst("spec");
        Map<String, Object> specJson = objectMapper.readValue(spec.getBody(), Map.class);
        List<Map<String, Object>> specInputs = (List<Map<String, Object>>) specJson.get("inputs");
        assertEquals(Map.of("name", "input0", "role", "input"), specInputs.get(0));
    }

    @Test
    @DisplayName("overlay: video -> input0 and image -> input1 in both the spec and the binary parts")
    @SuppressWarnings("unchecked")
    void overlayPartNaming() throws Exception {
        stubBinaryResponse(VIDEO_BYTES, "20.0");
        when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
            any(byte[].class), anyInt(), anyInt(), any(), anyString()))
            .thenReturn(FileRef.of("k", "n", "video/mp4", 1));
        String imageKey = TENANT + "/wf/run/node/logo.png";
        when(fileStorageService.download(TENANT, imageKey)).thenReturn(Optional.of(AUDIO_BYTES));
        List<MediaInput> inputs = List.of(
            new MediaInput("input0", "video", null, fileRef(VIDEO_KEY, "video/mp4")),
            new MediaInput("input1", "image", null, fileRef(imageKey, "image/png")));

        service.render(TENANT, "wf-1", "run-1", "core:brand", 1, 0, null, "overlay", Map.of(), inputs);

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), entityCaptor.capture(), eq(byte[].class));
        MultiValueMap<String, Object> body = (MultiValueMap<String, Object>) entityCaptor.getValue().getBody();
        HttpEntity<ByteArrayResource> videoPart = (HttpEntity<ByteArrayResource>) body.getFirst("input0");
        assertArrayEquals(VIDEO_BYTES, videoPart.getBody().getByteArray());
        HttpEntity<ByteArrayResource> imagePart = (HttpEntity<ByteArrayResource>) body.getFirst("input1");
        assertArrayEquals(AUDIO_BYTES, imagePart.getBody().getByteArray());
        assertEquals("image/png", imagePart.getHeaders().getContentType().toString());

        HttpEntity<String> spec = (HttpEntity<String>) body.getFirst("spec");
        Map<String, Object> specJson = objectMapper.readValue(spec.getBody(), Map.class);
        List<Map<String, Object>> specInputs = (List<Map<String, Object>>) specJson.get("inputs");
        assertEquals(Map.of("name", "input0", "role", "video"), specInputs.get(0));
        assertEquals(Map.of("name", "input1", "role", "image"), specInputs.get(1));
    }

    @Test
    @DisplayName("pre-flight sums declared sizes ACROSS all concat clips - 8 x 40MB (320MB) is refused before any download")
    void concatCumulativePreFlightAcrossClips() {
        List<MediaInput> clips = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            clips.add(new MediaInput("input" + i, "video", i,
                fileRefWithSize(VIDEO_KEY, "video/mp4", 40L * 1024 * 1024)));
        }

        MediaRenderException e = assertThrows(MediaRenderException.class,
            () -> service.render(TENANT, "wf-1", "run-1", "core:compile", 1, 0, null,
                "concat", Map.of(), clips));

        assertTrue(e.getMessage().contains(String.valueOf(MediaRenderService.MAX_TOTAL_INPUT_BYTES)),
            "the failure must name the 300MB byte limit, got: " + e.getMessage());
        verifyNoInteractions(fileStorageService);
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("mix track inputs carry their trackIndex in the spec descriptor")
    @SuppressWarnings("unchecked")
    void trackIndexInSpec() throws Exception {
        stubBinaryResponse(VIDEO_BYTES, null);
        when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
            any(byte[].class), anyInt(), anyInt(), any(), anyString()))
            .thenReturn(FileRef.of("k", "n", "video/mp4", 1));
        List<MediaInput> inputs = List.of(
            new MediaInput("input0", "audio", 0, fileRef(AUDIO_KEY, "audio/mpeg")));

        service.render(TENANT, "wf-1", "run-1", "core:mix", 1, 0, null, "mix", Map.of(), inputs);

        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), entityCaptor.capture(), eq(byte[].class));
        MultiValueMap<String, Object> body = (MultiValueMap<String, Object>) entityCaptor.getValue().getBody();
        HttpEntity<String> spec = (HttpEntity<String>) body.getFirst("spec");
        Map<String, Object> specJson = objectMapper.readValue(spec.getBody(), Map.class);
        List<Map<String, Object>> specInputs = (List<Map<String, Object>>) specJson.get("inputs");
        assertEquals(0, specInputs.get(0).get("trackIndex"));
    }

    // ==================== Response handling ====================

    @Test
    @DisplayName("probe: JSON response is parsed and passed through verbatim, nothing uploaded")
    void probeJsonPassthrough() {
        String json = "{\"duration_seconds\":12.5,\"has_video\":false,\"has_audio\":true,\"video\":null}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
            .thenReturn(new ResponseEntity<>(json.getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK));
        List<MediaInput> inputs = List.of(
            new MediaInput("input0", "input", null, fileRef(AUDIO_KEY, "audio/mpeg")));

        MediaResult result = service.render(TENANT, "wf-1", "run-1", "core:probe", 1, 0, null,
            "probe", Map.of(), inputs);

        assertInstanceOf(ProbeResult.class, result);
        Map<String, Object> fields = ((ProbeResult) result).fields();
        assertEquals(12.5, fields.get("duration_seconds"));
        assertEquals(true, fields.get("has_audio"));
        assertNull(fields.get("video"));
        verify(fileStorageService, never()).upload(anyString(), any(), anyString(), anyString(),
            anyString(), anyString(), any(byte[].class), anyInt(), anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("binary response is uploaded as STEP_OUTPUT with run coordinates and returns file + header duration")
    void binaryResultUploadedAsStepOutput() {
        stubBinaryResponse(VIDEO_BYTES, "20.5");
        FileRef stored = FileRef.of(TENANT + "/wf-1/run-1/core:add_music/out.mp4", "out.mp4", "video/mp4", VIDEO_BYTES.length);
        when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
            any(byte[].class), anyInt(), anyInt(), any(), anyString()))
            .thenReturn(stored);

        MediaResult result = render(muxInputs(), Map.of());

        assertInstanceOf(FileResult.class, result);
        assertEquals(stored, ((FileResult) result).file());
        assertEquals(20.5, ((FileResult) result).durationSeconds());

        ArgumentCaptor<String> fileNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileStorageService).upload(eq(TENANT), eq("wf-1"), eq("run-1"), eq("core:add_music"),
            fileNameCaptor.capture(), eq("video/mp4"), eq(VIDEO_BYTES), eq(2), eq(0), eq((Integer) null),
            eq(com.apimarketplace.common.storage.service.StorageSourceTypes.STEP_OUTPUT));
        assertEquals("add_music_mux_audio_epoch_2_spawn_0.mp4", fileNameCaptor.getValue());
    }

    @Test
    @DisplayName("X-Media-Timestamp-Seconds header is parsed into FileResult.timestampSeconds (frame contract)")
    void timestampHeaderParsed() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("image/jpeg"));
        headers.set("X-Media-Timestamp-Seconds", "7.5");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
            .thenReturn(new ResponseEntity<>(VIDEO_BYTES, headers, HttpStatus.OK));
        FileRef stored = FileRef.of("k", "cover.jpg", "image/jpeg", VIDEO_BYTES.length);
        when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
            any(byte[].class), anyInt(), anyInt(), any(), anyString()))
            .thenReturn(stored);
        List<MediaInput> inputs = List.of(
            new MediaInput("input0", "input", null, fileRef(VIDEO_KEY, "video/mp4")));

        MediaResult result = service.render(TENANT, "wf-1", "run-1", "core:cover", 1, 0, null,
            "frame", Map.of(), inputs);

        assertInstanceOf(FileResult.class, result);
        assertEquals(7.5, ((FileResult) result).timestampSeconds());
        assertNull(((FileResult) result).durationSeconds(),
            "frame answers no duration header - a still image has no duration");
    }

    @Test
    @DisplayName("no timestamp header -> FileResult.timestampSeconds is null (non-frame operations)")
    void missingTimestampHeaderIsNull() {
        stubBinaryResponse(VIDEO_BYTES, "20.0");
        when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
            any(byte[].class), anyInt(), anyInt(), any(), anyString()))
            .thenReturn(FileRef.of("k", "n", "video/mp4", 1));

        MediaResult result = render(muxInputs(), Map.of());

        assertNull(((FileResult) result).timestampSeconds());
    }

    @Test
    @DisplayName("image/jpeg response is stored with the image MIME and a .jpg file name (frame naming pattern)")
    void imageResultStoredWithJpgExtension() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("image/jpeg"));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
            .thenReturn(new ResponseEntity<>(VIDEO_BYTES, headers, HttpStatus.OK));
        when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
            any(byte[].class), anyInt(), anyInt(), any(), anyString()))
            .thenReturn(FileRef.of("k", "n", "image/jpeg", 1));
        List<MediaInput> inputs = List.of(
            new MediaInput("input0", "input", null, fileRef(VIDEO_KEY, "video/mp4")));

        service.render(TENANT, "wf-1", "run-1", "core:cover", 3, 1, null, "frame", Map.of(), inputs);

        ArgumentCaptor<String> fileNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileStorageService).upload(eq(TENANT), any(), anyString(), anyString(),
            fileNameCaptor.capture(), eq("image/jpeg"), any(byte[].class), eq(3), eq(1), any(), anyString());
        assertEquals("cover_frame_epoch_3_spawn_1.jpg", fileNameCaptor.getValue());
    }

    @Test
    @DisplayName("image/png response gets the .png extension")
    void pngResultStoredWithPngExtension() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("image/png"));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
            .thenReturn(new ResponseEntity<>(VIDEO_BYTES, headers, HttpStatus.OK));
        when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
            any(byte[].class), anyInt(), anyInt(), any(), anyString()))
            .thenReturn(FileRef.of("k", "n", "image/png", 1));
        List<MediaInput> inputs = List.of(
            new MediaInput("input0", "input", null, fileRef(VIDEO_KEY, "video/mp4")));

        service.render(TENANT, "wf-1", "run-1", "core:cover", 1, 0, null, "frame", Map.of(), inputs);

        ArgumentCaptor<String> fileNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileStorageService).upload(eq(TENANT), any(), anyString(), anyString(),
            fileNameCaptor.capture(), eq("image/png"), any(byte[].class), anyInt(), anyInt(), any(), anyString());
        assertTrue(fileNameCaptor.getValue().endsWith(".png"),
            "got: " + fileNameCaptor.getValue());
    }

    @Test
    @DisplayName("missing/unparseable duration header -> FileResult with null duration, never a failure")
    void missingDurationHeaderIsNull() {
        stubBinaryResponse(VIDEO_BYTES, null);
        when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
            any(byte[].class), anyInt(), anyInt(), any(), anyString()))
            .thenReturn(FileRef.of("k", "n", "video/mp4", 1));

        MediaResult result = render(muxInputs(), Map.of());

        assertNull(((FileResult) result).durationSeconds());
    }

    @Test
    @DisplayName("binary result over videoMaxBytes -> explicit 'too large' failure, upload never attempted")
    void videoMaxBytesRejected() {
        service = service(RENDERER_URL, 5L);
        stubBinaryResponse(VIDEO_BYTES, "20.0"); // 11 bytes > 5

        MediaRenderException e = assertThrows(MediaRenderException.class,
            () -> render(muxInputs(), Map.of()));
        assertTrue(e.getMessage().contains("too large"));
        verify(fileStorageService, never()).upload(anyString(), any(), anyString(), anyString(),
            anyString(), anyString(), any(byte[].class), anyInt(), anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("empty binary body -> explicit 'empty result' failure")
    void emptyBinaryBodyThrows() {
        stubBinaryResponse(new byte[0], null);

        MediaRenderException e = assertThrows(MediaRenderException.class,
            () -> render(muxInputs(), Map.of()));
        assertTrue(e.getMessage().contains("empty result"));
    }

    // ==================== Renderer errors ====================

    @Test
    @DisplayName("renderer 422 with {error, code, stderr_tail} -> failure message carries the error and the stderr tail")
    void renderer422Parsed() {
        String errorBody = "{\"error\":\"ffmpeg exited with code 1\",\"code\":\"FFMPEG_FAILED\",\"stderr_tail\":\"Invalid data found\"}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable Entity",
                new HttpHeaders(), errorBody.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        MediaRenderException e = assertThrows(MediaRenderException.class,
            () -> render(muxInputs(), Map.of()));
        assertTrue(e.getMessage().contains("ffmpeg exited with code 1"));
        assertTrue(e.getMessage().contains("Invalid data found"));
    }

    @Test
    @DisplayName("renderer 400 with non-JSON body -> failure message falls back to the status code")
    void renderer400NonJsonBody() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                new HttpHeaders(), "boom".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        MediaRenderException e = assertThrows(MediaRenderException.class,
            () -> render(muxInputs(), Map.of()));
        assertTrue(e.getMessage().contains("400"));
    }

    @Test
    @DisplayName("renderer 504 MEDIA_TIMEOUT -> failure explains the render budget and suggests shorter inputs")
    void renderer504TimeoutMapped() {
        String errorBody = "{\"error\":\"operation exceeded the render budget\",\"code\":\"MEDIA_TIMEOUT\"}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.GATEWAY_TIMEOUT, "Gateway Timeout",
                new HttpHeaders(), errorBody.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        MediaRenderException e = assertThrows(MediaRenderException.class,
            () -> render(muxInputs(), Map.of()));
        assertTrue(e.getMessage().contains("render budget"), "got: " + e.getMessage());
        assertTrue(e.getMessage().contains("shorter inputs"), "got: " + e.getMessage());
    }

    @Test
    @DisplayName("renderer 429 BUSY -> failure suggests retrying when fewer media operations run concurrently")
    void renderer429BusyMapped() {
        String errorBody = "{\"error\":\"queue full\",\"code\":\"BUSY\"}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                new HttpHeaders(), errorBody.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        MediaRenderException e = assertThrows(MediaRenderException.class,
            () -> render(muxInputs(), Map.of()));
        assertTrue(e.getMessage().contains("fewer media operations"), "got: " + e.getMessage());
    }

    @Test
    @DisplayName("transport failure (no answer) -> 'did not answer' failure naming the operation")
    void transportFailureWrapped() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
            .thenThrow(new ResourceAccessException("connection refused"));

        MediaRenderException e = assertThrows(MediaRenderException.class,
            () -> render(muxInputs(), Map.of()));
        assertTrue(e.getMessage().contains("did not answer"));
        assertTrue(e.getMessage().contains("mux_audio"));
    }

    @Test
    @DisplayName("transport failure message is STATIC - never embeds the internal renderer URL or the exception text")
    void transportFailureDoesNotLeakInternalUrl() {
        String springStyleMessage = "I/O error on POST request for \"" + RENDERER_URL
            + "/internal/media\": connection refused";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(byte[].class)))
            .thenThrow(new ResourceAccessException(springStyleMessage));

        MediaRenderException e = assertThrows(MediaRenderException.class,
            () -> render(muxInputs(), Map.of()));

        assertFalse(e.getMessage().contains(RENDERER_URL),
            "the internal renderer URL must never reach the agent-visible failure text, got: " + e.getMessage());
        assertFalse(e.getMessage().contains("connection refused"),
            "the transport exception text must not be embedded, got: " + e.getMessage());
        assertTrue(e.getMessage().contains("did not answer"));
    }
}
