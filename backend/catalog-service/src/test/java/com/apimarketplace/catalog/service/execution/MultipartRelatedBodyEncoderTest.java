package com.apimarketplace.catalog.service.execution;

import com.apimarketplace.storage.client.StorageClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Validates {@link MultipartRelatedBodyEncoder}: it must assemble a multipart/related
 * body with a JSON metadata part (built from metadataFields) followed by a binary media
 * part (FileRef bytes downloaded from storage), glued by a boundary the caller echoes in
 * the Content-Type. This is the format YouTube videos.insert (uploadType=multipart) needs.
 */
class MultipartRelatedBodyEncoderTest {

    private ObjectMapper objectMapper;
    private StorageClient storageClient;
    private MultipartRelatedBodyEncoder encoder;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        storageClient = mock(StorageClient.class);
        encoder = new MultipartRelatedBodyEncoder(objectMapper);
        Field f = MultipartRelatedBodyEncoder.class.getDeclaredField("storageClient");
        f.setAccessible(true);
        f.set(encoder, storageClient);
    }

    private JsonNode requestSpec(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    @Test
    @DisplayName("Assembles metadata JSON part + media part with a boundary (YouTube videos.insert shape)")
    void assemblesRelatedBodyWithMetadataAndMedia() throws Exception {
        byte[] video = "FAKE_MP4_BYTES".getBytes(StandardCharsets.UTF_8);
        when(storageClient.download(eq("1"), eq("uploads/demo.mp4"))).thenReturn(video);

        Map<String, Object> fileRef = new LinkedHashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", "uploads/demo.mp4");
        fileRef.put("name", "demo.mp4");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("snippet", "{\"title\":\"Scope Demo\"}");
        params.put("status", "{\"privacyStatus\":\"unlisted\"}");
        params.put("video", fileRef);

        JsonNode spec = requestSpec("{\"multipartRelated\":{"
            + "\"metadataFields\":[\"snippet\",\"status\"],"
            + "\"mediaField\":\"video\",\"mediaContentType\":\"video/*\"}}");

        MultipartRelatedBodyEncoder.EncodedBody result = encoder.encode(spec, params, "1");

        assertNotNull(result);
        assertNotNull(result.boundary());
        String body = new String(result.body(), StandardCharsets.UTF_8);
        // Boundary delimiters present (open + close)
        assertTrue(body.contains("--" + result.boundary()), "open boundary missing");
        assertTrue(body.contains("--" + result.boundary() + "--"), "closing boundary missing");
        // Metadata part: nested JSON object, snippet/status parsed (not stringified)
        assertTrue(body.contains("Content-Type: application/json; charset=UTF-8"));
        assertTrue(body.contains("\"snippet\":{\"title\":\"Scope Demo\"}"),
            "snippet must be nested as parsed JSON, not a quoted string");
        assertTrue(body.contains("\"status\":{\"privacyStatus\":\"unlisted\"}"));
        // Media part: declared content type + the downloaded bytes
        assertTrue(body.contains("Content-Type: video/*"));
        assertTrue(body.contains("FAKE_MP4_BYTES"));
    }

    @Test
    @DisplayName("Returns null when the media fileRef param is missing (caller fails the call)")
    void nullWhenMediaMissing() throws Exception {
        JsonNode spec = requestSpec("{\"multipartRelated\":{\"mediaField\":\"video\"}}");
        MultipartRelatedBodyEncoder.EncodedBody result = encoder.encode(spec, Map.of("snippet", "{}"), "1");
        assertNull(result);
        verifyNoInteractions(storageClient);
    }

    @Test
    @DisplayName("Returns null when the media param is not a FileRef (e.g. a plain string)")
    void nullWhenMediaNotFileRef() throws Exception {
        JsonNode spec = requestSpec("{\"multipartRelated\":{\"mediaField\":\"video\"}}");
        MultipartRelatedBodyEncoder.EncodedBody result = encoder.encode(spec, Map.of("video", "not-a-file"), "1");
        assertNull(result);
    }

    @Test
    @DisplayName("Omits absent metadata fields but still uploads the media")
    void omitsAbsentMetadataFields() throws Exception {
        byte[] video = {9, 8, 7};
        when(storageClient.download(eq("1"), eq("k.mp4"))).thenReturn(video);
        Map<String, Object> fileRef = Map.of("_type", "file", "path", "k.mp4", "name", "k.mp4");
        // status not provided - only snippet
        Map<String, Object> params = Map.of("snippet", "{\"title\":\"x\"}", "video", fileRef);

        JsonNode spec = requestSpec("{\"multipartRelated\":{"
            + "\"metadataFields\":[\"snippet\",\"status\"],\"mediaField\":\"video\",\"mediaContentType\":\"video/mp4\"}}");

        MultipartRelatedBodyEncoder.EncodedBody result = encoder.encode(spec, params, "1");

        assertNotNull(result);
        String body = new String(result.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"snippet\":{\"title\":\"x\"}"));
        assertFalse(body.contains("\"status\""), "absent status must be omitted, not null-injected");
    }

    @Test
    @DisplayName("Returns null when multipartRelated config block is absent")
    void nullWhenConfigMissing() throws Exception {
        JsonNode spec = requestSpec("{\"bodyType\":\"multipart_related\"}");
        assertNull(encoder.encode(spec, Map.of("video", Map.of("_type", "file", "path", "p", "name", "n")), "1"));
    }

    @Test
    @DisplayName("Metadata provided as a Map (real runtime shape for type:object params) nests as JSON")
    void metadataAsMapNestsAsJson() throws Exception {
        // At runtime, type:object body params arrive as Maps (jsonNodeToFlatMap), not strings.
        // This exercises the valueToTree branch of parseToNode that production actually hits.
        byte[] video = {1, 2, 3};
        when(storageClient.download(eq("1"), eq("k.mp4"))).thenReturn(video);
        Map<String, Object> fileRef = Map.of("_type", "file", "path", "k.mp4", "name", "k.mp4");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("snippet", Map.of("title", "Scope Demo"));   // Map, not JSON string
        params.put("video", fileRef);

        JsonNode spec = requestSpec("{\"multipartRelated\":{"
            + "\"metadataFields\":[\"snippet\"],\"mediaField\":\"video\",\"mediaContentType\":\"video/mp4\"}}");

        MultipartRelatedBodyEncoder.EncodedBody result = encoder.encode(spec, params, "1");

        assertNotNull(result);
        String body = new String(result.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"snippet\":{\"title\":\"Scope Demo\"}"),
            "a Map metadata value must nest as a JSON object, not a stringified map");
    }
}
