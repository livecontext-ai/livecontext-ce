package com.apimarketplace.catalog.service.execution;

import com.apimarketplace.storage.client.StorageClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Validates that {@link RawBinaryBodyEncoder} correctly:
 * - returns byte[] directly when param is a byte array
 * - downloads from MinIO when param is a FileRef
 * - decodes base64 strings prefixed with "base64:"
 * - falls back to UTF-8 bytes for plain strings
 * - resolves Content-Type from spec (default octet-stream)
 */
class RawBinaryBodyEncoderTest {

    private ObjectMapper objectMapper;
    private StorageClient storageClient;
    private RawBinaryBodyEncoder encoder;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        storageClient = mock(StorageClient.class);
        encoder = new RawBinaryBodyEncoder(objectMapper);
        Field f = RawBinaryBodyEncoder.class.getDeclaredField("storageClient");
        f.setAccessible(true);
        f.set(encoder, storageClient);
    }

    @Test
    @DisplayName("returns raw byte[] when parameter is already a byte array")
    void byteArrayPassThrough() throws Exception {
        byte[] payload = {1, 2, 3, 4, 5};
        Map<String, Object> params = Map.of("body", payload);
        JsonNode spec = objectMapper.readTree("{\"rawBodyParam\": \"body\"}");

        byte[] result = encoder.encode(spec, params, "tenant-1");

        assertArrayEquals(payload, result);
        verifyNoInteractions(storageClient);
    }

    @Test
    @DisplayName("downloads bytes from MinIO when parameter is a FileRef map")
    void fileRefDownloadsFromStorage() throws Exception {
        Map<String, Object> fileRef = new LinkedHashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", "tenant-1/general/some-upload.pdf");
        fileRef.put("name", "some-upload.pdf");
        byte[] payload = "hello pdf".getBytes(StandardCharsets.UTF_8);
        when(storageClient.download(eq("tenant-1"), eq("tenant-1/general/some-upload.pdf"))).thenReturn(payload);

        Map<String, Object> params = Map.of("body", fileRef);
        JsonNode spec = objectMapper.readTree("{\"rawBodyParam\": \"body\"}");

        byte[] result = encoder.encode(spec, params, "tenant-1");

        assertArrayEquals(payload, result);
    }

    @Test
    @DisplayName("decodes base64-prefixed strings")
    void base64PrefixDecoding() throws Exception {
        byte[] original = {0, 1, 2, 3, 127, -1};
        String encoded = "base64:" + Base64.getEncoder().encodeToString(original);
        Map<String, Object> params = Map.of("body", encoded);
        JsonNode spec = objectMapper.readTree("{\"rawBodyParam\": \"body\"}");

        byte[] result = encoder.encode(spec, params, "t");

        assertArrayEquals(original, result);
    }

    @Test
    @DisplayName("treats plain string as UTF-8 bytes")
    void plainStringIsUtf8() throws Exception {
        String text = "héllo wörld";
        Map<String, Object> params = Map.of("body", text);
        JsonNode spec = objectMapper.readTree("{\"rawBodyParam\": \"body\"}");

        byte[] result = encoder.encode(spec, params, "t");

        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    @DisplayName("uses custom rawBodyParam name from spec (default is 'body')")
    void customRawBodyParam() throws Exception {
        Map<String, Object> params = Map.of("payload", "hello");
        JsonNode spec = objectMapper.readTree("{\"rawBodyParam\": \"payload\"}");

        byte[] result = encoder.encode(spec, params, "t");

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    @DisplayName("returns empty byte[] when rawBodyParam is missing from params")
    void missingParamReturnsEmpty() throws Exception {
        Map<String, Object> params = Map.of("other", "x");
        JsonNode spec = objectMapper.readTree("{\"rawBodyParam\": \"body\"}");

        byte[] result = encoder.encode(spec, params, "t");

        assertEquals(0, result.length);
        verifyNoInteractions(storageClient);
    }

    @Test
    @DisplayName("resolveContentType returns declared value, falls back to application/octet-stream")
    void contentTypeResolution() throws Exception {
        assertEquals("application/octet-stream",
            encoder.resolveContentType(objectMapper.readTree("{}")));
        assertEquals("image/png",
            encoder.resolveContentType(objectMapper.readTree("{\"contentType\": \"image/png\"}")));
        assertEquals("application/octet-stream",
            encoder.resolveContentType(objectMapper.readTree("{\"contentType\": \"\"}")));
    }

    @Test
    @DisplayName("invalid base64 payload logs and returns empty")
    void invalidBase64() throws Exception {
        Map<String, Object> params = Map.of("body", "base64:!!!not-valid-base64!!!");
        JsonNode spec = objectMapper.readTree("{\"rawBodyParam\": \"body\"}");

        byte[] result = encoder.encode(spec, params, "t");

        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("FileRef with no storageClient fails gracefully with empty bytes")
    void fileRefWithoutStorageClient() throws Exception {
        // Rebuild encoder WITHOUT injecting storageClient
        RawBinaryBodyEncoder bare = new RawBinaryBodyEncoder(objectMapper);
        Map<String, Object> fileRef = Map.of("_type", "file", "path", "x");
        JsonNode spec = objectMapper.readTree("{\"rawBodyParam\": \"body\"}");

        byte[] result = bare.encode(spec, Map.of("body", fileRef), "t");

        assertEquals(0, result.length);
    }
}
