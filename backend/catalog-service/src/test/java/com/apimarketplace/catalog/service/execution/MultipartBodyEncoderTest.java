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

    // ── source:"auto" - polymorphic part (file OR string OR JSON object) ──────────────────────

    @Test
    @DisplayName("auto source with a FileRef value uploads the bytes as a file part")
    void autoWithFileRefUploadsFile() throws Exception {
        when(storageClient.download(eq("tenant"), eq("tenant/general/shot.png")))
            .thenReturn(new byte[]{1, 2, 3, 4});

        JsonNode fields = objectMapper.readTree("[{\"name\":\"photo\",\"source\":\"auto\",\"paramName\":\"photo\"}]");
        Map<String, Object> fileRef = new LinkedHashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", "tenant/general/shot.png");
        fileRef.put("name", "shot.png");
        fileRef.put("mimeType", "image/png");
        fileRef.put("size", 4);
        Map<String, Object> params = Map.of("photo", fileRef);

        MultiValueMap<String, Object> body = encoder.encode(fields, params, "tenant");
        assertEquals(1, body.size());
        Object part = body.getFirst("photo");
        assertTrue(part instanceof ByteArrayResource, "FileRef must become a binary file part");
        assertEquals("shot.png", ((ByteArrayResource) part).getFilename());
        assertEquals(4, ((ByteArrayResource) part).contentLength());
    }

    @Test
    @DisplayName("auto source with a String (file_id or URL) is sent verbatim as a text field, no download")
    void autoWithStringSendsText() throws Exception {
        JsonNode fields = objectMapper.readTree("[{\"name\":\"photo\",\"source\":\"auto\",\"paramName\":\"photo\"}]");

        MultiValueMap<String, Object> byId = encoder.encode(fields, Map.of("photo", "AgACAgEAAxkBAAExfile_id"), "tenant");
        assertEquals("AgACAgEAAxkBAAExfile_id", byId.getFirst("photo"));

        MultiValueMap<String, Object> byUrl = encoder.encode(fields, Map.of("photo", "https://example.com/cat.jpg"), "tenant");
        assertEquals("https://example.com/cat.jpg", byUrl.getFirst("photo"));

        verifyNoInteractions(storageClient);
    }

    @Test
    @DisplayName("auto source with a Map (reply_markup) is JSON-serialized, not Java toString")
    void autoWithMapJsonSerialized() throws Exception {
        JsonNode fields = objectMapper.readTree("[{\"name\":\"reply_markup\",\"source\":\"auto\",\"paramName\":\"reply_markup\"}]");
        Map<String, Object> replyMarkup = Map.of(
            "inline_keyboard", java.util.List.of(java.util.List.of(Map.of("text", "Open", "callback_data", "go"))));
        Map<String, Object> params = Map.of("reply_markup", replyMarkup);

        MultiValueMap<String, Object> body = encoder.encode(fields, params, "tenant");
        Object part = body.getFirst("reply_markup");
        assertTrue(part instanceof String);
        String json = (String) part;
        // Must be real JSON (double-quoted keys), not LinkedHashMap.toString() ("{inline_keyboard=[...]}").
        assertEquals(replyMarkup, objectMapper.readValue(json, Map.class));
        assertTrue(json.contains("\"inline_keyboard\""), "expected JSON string, got: " + json);
        assertFalse(json.contains("="), "must not be Java toString, got: " + json);
        verifyNoInteractions(storageClient);
    }

    @Test
    @DisplayName("auto source with a List (caption_entities) is JSON-serialized")
    void autoWithListJsonSerialized() throws Exception {
        JsonNode fields = objectMapper.readTree("[{\"name\":\"caption_entities\",\"source\":\"auto\",\"paramName\":\"caption_entities\"}]");
        java.util.List<Object> entities = java.util.List.of(Map.of("type", "bold", "offset", 0, "length", 4));
        Map<String, Object> params = Map.of("caption_entities", entities);

        MultiValueMap<String, Object> body = encoder.encode(fields, params, "tenant");
        Object part = body.getFirst("caption_entities");
        assertTrue(part instanceof String);
        assertEquals(entities, objectMapper.readValue((String) part, java.util.List.class));
    }

    @Test
    @DisplayName("auto source with a scalar number/boolean is sent as a text field")
    void autoWithScalarSendsText() throws Exception {
        JsonNode fields = objectMapper.readTree("["
            + "{\"name\":\"message_thread_id\",\"source\":\"auto\",\"paramName\":\"message_thread_id\"},"
            + "{\"name\":\"disable_notification\",\"source\":\"auto\",\"paramName\":\"disable_notification\"}"
            + "]");
        Map<String, Object> params = Map.of("message_thread_id", 42, "disable_notification", true);

        MultiValueMap<String, Object> body = encoder.encode(fields, params, "tenant");
        assertEquals("42", body.getFirst("message_thread_id"));
        assertEquals("true", body.getFirst("disable_notification"));
    }

    @Test
    @DisplayName("auto source with a missing value skips that part")
    void autoMissingSkipped() throws Exception {
        JsonNode fields = objectMapper.readTree("[{\"name\":\"photo\",\"source\":\"auto\",\"paramName\":\"photo\"}]");
        MultiValueMap<String, Object> body = encoder.encode(fields, Map.of(), "tenant");
        assertTrue(body.isEmpty());
        verifyNoInteractions(storageClient);
    }

    @Test
    @DisplayName("auto source with a JSON-string FileRef is recognized and uploaded as a file part")
    void autoWithStringifiedFileRefUploads() throws Exception {
        when(storageClient.download(eq("tenant"), eq("tenant/general/doc.pdf")))
            .thenReturn(new byte[]{9, 9});
        JsonNode fields = objectMapper.readTree("[{\"name\":\"document\",\"source\":\"auto\",\"paramName\":\"document\"}]");
        // A FileRef serialized to a JSON string (e.g. round-tripped through a text field).
        String jsonFileRef = "{\"_type\":\"file\",\"path\":\"tenant/general/doc.pdf\",\"name\":\"doc.pdf\"}";
        Map<String, Object> params = Map.of("document", jsonFileRef);

        MultiValueMap<String, Object> body = encoder.encode(fields, params, "tenant");
        Object part = body.getFirst("document");
        assertTrue(part instanceof ByteArrayResource, "stringified FileRef must still upload as a file part");
        assertEquals("doc.pdf", ((ByteArrayResource) part).getFilename());
    }

    @Test
    @DisplayName("auto source with an already-JSON string (reply_markup) is passed verbatim, not double-encoded")
    void autoWithJsonStringPassedVerbatim() throws Exception {
        JsonNode fields = objectMapper.readTree("[{\"name\":\"reply_markup\",\"source\":\"auto\",\"paramName\":\"reply_markup\"}]");
        // The caller already JSON-serialized the markup. It must NOT be wrapped in quotes again.
        String preSerialized = "{\"inline_keyboard\":[[{\"text\":\"Open\",\"callback_data\":\"go\"}]]}";
        Map<String, Object> params = Map.of("reply_markup", preSerialized);

        MultiValueMap<String, Object> body = encoder.encode(fields, params, "tenant");
        assertEquals(preSerialized, body.getFirst("reply_markup"));
        verifyNoInteractions(storageClient);
    }
}
