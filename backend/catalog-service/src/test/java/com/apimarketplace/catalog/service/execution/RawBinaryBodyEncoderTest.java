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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Byte ranges: send ONE part of the file.
    //
    // Generic multipart-upload support. LinkedIn's Videos API is the first consumer: it splits
    // every video into 4 MB parts and hands back one signed URL per part, so sending the whole
    // file to the first URL corrupts the upload while every call still answers 2xx. Declaring no
    // range keeps the previous behaviour exactly, which is what TikTok and WhatsApp rely on.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private static final String RANGE_SPEC =
        "{\"rawBodyParam\": \"body\", \"rangeFirstByteParam\": \"firstByte\","
        + " \"rangeLastByteParam\": \"lastByte\"}";

    private static byte[] tenBytes() {
        return new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    }

    @Test
    @DisplayName("no range declared sends the whole file, exactly as before")
    void noRangeDeclaredSendsEverything() throws Exception {
        JsonNode spec = objectMapper.readTree("{\"rawBodyParam\": \"body\"}");

        byte[] out = encoder.encode(spec, Map.of("body", tenBytes()), "t1");

        assertArrayEquals(tenBytes(), out);
    }

    @Test
    @DisplayName("a declared range sends only that slice, last byte INCLUSIVE")
    void rangeSendsOnlyThatSlice() throws Exception {
        JsonNode spec = objectMapper.readTree(RANGE_SPEC);

        byte[] out = encoder.encode(spec, Map.of("body", tenBytes(), "firstByte", 2, "lastByte", 5), "t1");

        assertArrayEquals(new byte[] {2, 3, 4, 5}, out,
            "an exclusive reading would drop a byte from every part and corrupt the upload");
    }

    @Test
    @DisplayName("the first part starts at zero")
    void firstPartStartsAtZero() throws Exception {
        JsonNode spec = objectMapper.readTree(RANGE_SPEC);

        byte[] out = encoder.encode(spec, Map.of("body", tenBytes(), "firstByte", 0, "lastByte", 3), "t1");

        assertArrayEquals(new byte[] {0, 1, 2, 3}, out);
    }

    @Test
    @DisplayName("bounds arriving as strings are accepted, because a template resolves to text")
    void stringBoundsAreAccepted() throws Exception {
        JsonNode spec = objectMapper.readTree(RANGE_SPEC);

        byte[] out = encoder.encode(spec, Map.of("body", tenBytes(), "firstByte", "4", "lastByte", "6"), "t1");

        assertArrayEquals(new byte[] {4, 5, 6}, out);
    }

    @Test
    @DisplayName("a last byte past the end is clamped to the real tail")
    void lastByteBeyondTheEndIsClamped() throws Exception {
        JsonNode spec = objectMapper.readTree(RANGE_SPEC);

        byte[] out = encoder.encode(spec, Map.of("body", tenBytes(), "firstByte", 8, "lastByte", 999), "t1");

        assertArrayEquals(new byte[] {8, 9}, out,
            "a provider computing fixed-size blocks can declare a tail past the end; refusing it "
                + "would fail every upload whose size is not a multiple of the block size");
    }

    @Test
    @DisplayName("a range declared but not supplied FAILS THE CALL rather than sending the whole file")
    void declaredButUnsuppliedRangeFailsTheCall() throws Exception {
        JsonNode spec = objectMapper.readTree(RANGE_SPEC);

        // Sending the whole file to one part corrupts the upload while every call answers
        // 2xx; failing the call names the two bounds and stops there.
        ByteRangeException thrown = assertThrows(ByteRangeException.class,
            () -> encoder.encode(spec, Map.of("body", tenBytes()), "t1"));
        // Assert WHICH refusal fired: without this the three throw sites are
        // interchangeable and a refactor could route an inverted range here unnoticed.
        assertTrue(thrown.getMessage().contains("firstByte"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("lastByte"), thrown.getMessage());
    }

    @Test
    @DisplayName("an inverted range fails the call")
    void invertedRangeFailsTheCall() throws Exception {
        JsonNode spec = objectMapper.readTree(RANGE_SPEC);

        assertTrue(assertThrows(ByteRangeException.class,
            () -> encoder.encode(spec, Map.of("body", tenBytes(), "firstByte", 7, "lastByte", 2), "t1"))
            .getMessage().contains("invalid byte range"));
    }

    @Test
    @DisplayName("a range starting past the end fails the call")
    void rangeStartingPastTheEndFailsTheCall() throws Exception {
        JsonNode spec = objectMapper.readTree(RANGE_SPEC);

        assertThrows(ByteRangeException.class,
            () -> encoder.encode(spec, Map.of("body", tenBytes(), "firstByte", 40, "lastByte", 60), "t1"));
    }

    /**
     * The shape LinkedIn actually produces: 4 MB blocks over a file that is not a multiple of the
     * block size. Reassembling every part must give back the original bytes, or the upload is
     * corrupt in a way no single call reports.
     */
    @Test
    @DisplayName("every part concatenated reproduces the original file exactly")
    void partsReassembleIntoTheOriginal() throws Exception {
        JsonNode spec = objectMapper.readTree(RANGE_SPEC);
        byte[] file = new byte[10_000];
        for (int i = 0; i < file.length; i++) {
            file[i] = (byte) (i % 251);
        }
        int block = 4096;

        java.io.ByteArrayOutputStream rebuilt = new java.io.ByteArrayOutputStream();
        for (int first = 0; first < file.length; first += block) {
            byte[] part = encoder.encode(spec,
                Map.of("body", file, "firstByte", first, "lastByte", first + block - 1), "t1");
            rebuilt.write(part);
        }

        assertArrayEquals(file, rebuilt.toByteArray(),
            "three parts of 4096/4096/1808 must rebuild the 10000-byte file");
    }

    @Test
    @DisplayName("the range applies to a literal string body too, not only to files")
    void rangeAppliesToAStringBody() throws Exception {
        JsonNode spec = objectMapper.readTree(RANGE_SPEC);

        byte[] out = encoder.encode(spec, Map.of("body", "ABCDEFGHIJ", "firstByte", 2, "lastByte", 4), "t1");

        assertArrayEquals("CDE".getBytes(java.nio.charset.StandardCharsets.UTF_8), out,
            "a body shape the range silently ignored would send the whole payload to every "
                + "part's URL, which is the corruption this mechanism exists to prevent");
    }

    @Test
    @DisplayName("the range applies to a base64 string body too")
    void rangeAppliesToABase64Body() throws Exception {
        JsonNode spec = objectMapper.readTree(RANGE_SPEC);
        String b64 = "base64:" + java.util.Base64.getEncoder()
            .encodeToString(new byte[] {10, 20, 30, 40, 50});

        byte[] out = encoder.encode(spec, Map.of("body", b64, "firstByte", 1, "lastByte", 3), "t1");

        assertArrayEquals(new byte[] {20, 30, 40}, out);
    }

    @Test
    @DisplayName("a negative first byte fails the call")
    void negativeFirstByteFailsTheCall() throws Exception {
        JsonNode spec = objectMapper.readTree(RANGE_SPEC);

        assertThrows(ByteRangeException.class, () -> encoder.encode(
            spec, Map.of("body", tenBytes(), "firstByte", -1, "lastByte", 4), "t1"));
    }

    /**
     * The PRODUCTION path, and the one every other range test here misses: a stored file, fetched
     * through StorageClient, sliced per part. LinkedIn's endpoint passes the same FileRef to every
     * part call and relies on the range alone to pick the bytes, so a range that applied to plain
     * arrays but not to a downloaded file would send the whole video five times over and corrupt
     * the upload while every call answered 2xx.
     */
    @Test
    @DisplayName("a stored file is sliced per part, not sent whole")
    void fileRefIsSlicedByTheDeclaredRange() throws Exception {
        Map<String, Object> fileRef = new LinkedHashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", "tenant-1/general/clip.mp4");
        fileRef.put("name", "clip.mp4");
        byte[] payload = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        when(storageClient.download(eq("tenant-1"), eq("tenant-1/general/clip.mp4"))).thenReturn(payload);
        JsonNode spec = objectMapper.readTree(RANGE_SPEC);

        byte[] out = encoder.encode(spec,
            Map.of("body", fileRef, "firstByte", 3, "lastByte", 6), "tenant-1");

        assertArrayEquals(new byte[] {3, 4, 5, 6}, out);
    }

    @Test
    @DisplayName("every part of a stored file reassembles into the original")
    void fileRefPartsReassembleIntoTheOriginal() throws Exception {
        Map<String, Object> fileRef = new LinkedHashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", "tenant-1/general/clip.mp4");
        fileRef.put("name", "clip.mp4");
        byte[] payload = new byte[9_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        when(storageClient.download(eq("tenant-1"), eq("tenant-1/general/clip.mp4"))).thenReturn(payload);
        JsonNode spec = objectMapper.readTree(RANGE_SPEC);
        int block = 4096;

        java.io.ByteArrayOutputStream rebuilt = new java.io.ByteArrayOutputStream();
        for (int first = 0; first < payload.length; first += block) {
            rebuilt.write(encoder.encode(spec,
                Map.of("body", fileRef, "firstByte", first, "lastByte", first + block - 1), "tenant-1"));
        }

        assertArrayEquals(payload, rebuilt.toByteArray(),
            "three parts of 4096/4096/808 must rebuild the 9000-byte file exactly");
    }
}
