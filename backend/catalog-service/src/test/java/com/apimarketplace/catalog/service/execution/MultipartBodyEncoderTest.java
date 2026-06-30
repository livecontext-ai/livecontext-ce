package com.apimarketplace.catalog.service.execution;

import com.apimarketplace.storage.client.StorageClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MultiValueMap;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Validates that {@link MultipartBodyEncoder} correctly:
 * - encodes regular params as string fields
 * - downloads fileRef bytes from MinIO and adds them as ByteArrayResource parts
 * - skips parts whose source param is missing
 * - rejects malformed multipartFields entries
 */
class MultipartBodyEncoderTest {

    private ObjectMapper objectMapper;
    private StorageClient storageClient;
    private MultipartBodyEncoder encoder;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        storageClient = mock(StorageClient.class);
        encoder = new MultipartBodyEncoder(objectMapper);
        Field f = MultipartBodyEncoder.class.getDeclaredField("storageClient");
        f.setAccessible(true);
        f.set(encoder, storageClient);
    }

    @Test
    @DisplayName("encodes a 'param' source as a plain string field")
    void encodesParam() throws Exception {
        JsonNode fields = objectMapper.readTree("[{\"name\":\"model\",\"source\":\"param\",\"paramName\":\"model\"}]");
        Map<String, Object> params = Map.of("model", "whisper-1");
        MultiValueMap<String, Object> body = encoder.encode(fields, params, "tenant");
        assertEquals(1, body.size());
        assertEquals("whisper-1", body.getFirst("model"));
    }

    @Test
    @DisplayName("encodes a 'fileRef' source by downloading bytes from MinIO")
    void encodesFileRef() throws Exception {
        when(storageClient.download(eq("tenant"), eq("tenant/general/x.wav")))
            .thenReturn(new byte[]{10, 20, 30});

        JsonNode fields = objectMapper.readTree("[{\"name\":\"file\",\"source\":\"fileRef\",\"paramName\":\"audio\"}]");
        Map<String, Object> fileRef = new LinkedHashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", "tenant/general/x.wav");
        fileRef.put("name", "x.wav");
        fileRef.put("mimeType", "audio/wav");
        fileRef.put("size", 3);
        Map<String, Object> params = Map.of("audio", fileRef);

        MultiValueMap<String, Object> body = encoder.encode(fields, params, "tenant");
        assertEquals(1, body.size());
        Object filePart = body.getFirst("file");
        assertTrue(filePart instanceof ByteArrayResource);
        assertEquals("x.wav", ((ByteArrayResource) filePart).getFilename());
        assertEquals(3, ((ByteArrayResource) filePart).contentLength());
    }

    @Test
    @DisplayName("encodes mixed params + fileRef in a single body")
    void encodesMixed() throws Exception {
        when(storageClient.download(any(), any())).thenReturn(new byte[]{1});
        JsonNode fields = objectMapper.readTree("[" +
            "{\"name\":\"file\",\"source\":\"fileRef\",\"paramName\":\"audio\"}," +
            "{\"name\":\"model\",\"source\":\"param\",\"paramName\":\"model\"}," +
            "{\"name\":\"language\",\"source\":\"param\",\"paramName\":\"language\"}" +
            "]");
        Map<String, Object> params = Map.of(
            "audio", Map.of("_type", "file", "path", "x", "name", "a.wav"),
            "model", "whisper-1",
            "language", "fr"
        );
        MultiValueMap<String, Object> body = encoder.encode(fields, params, "tenant");
        assertEquals(3, body.size());
        assertTrue(body.getFirst("file") instanceof ByteArrayResource);
        assertEquals("whisper-1", body.getFirst("model"));
        assertEquals("fr", body.getFirst("language"));
    }

    @Test
    @DisplayName("missing param value skips that part")
    void missingParamSkipped() throws Exception {
        JsonNode fields = objectMapper.readTree("[" +
            "{\"name\":\"a\",\"source\":\"param\",\"paramName\":\"a\"}," +
            "{\"name\":\"b\",\"source\":\"param\",\"paramName\":\"b\"}" +
            "]");
        Map<String, Object> params = Map.of("a", "x"); // 'b' missing
        MultiValueMap<String, Object> body = encoder.encode(fields, params, "tenant");
        assertEquals(1, body.size());
        assertEquals("x", body.getFirst("a"));
    }

    @Test
    @DisplayName("non-FileRef value passed to fileRef source is rejected")
    void nonFileRefRejected() throws Exception {
        JsonNode fields = objectMapper.readTree("[{\"name\":\"file\",\"source\":\"fileRef\",\"paramName\":\"f\"}]");
        Map<String, Object> params = Map.of("f", "not-a-file-ref");
        MultiValueMap<String, Object> body = encoder.encode(fields, params, "tenant");
        assertTrue(body.isEmpty());
        verifyNoInteractions(storageClient);
    }

    @Test
    @DisplayName("null multipartFields returns empty body")
    void nullFieldsEmpty() {
        MultiValueMap<String, Object> body = encoder.encode(null, Map.of("a", "x"), "tenant");
        assertTrue(body.isEmpty());
    }
}
